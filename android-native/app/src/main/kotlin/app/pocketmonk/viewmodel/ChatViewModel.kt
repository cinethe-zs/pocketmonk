package app.pocketmonk.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pocketmonk.model.Conversation
import app.pocketmonk.model.Message
import app.pocketmonk.model.MessageRole
import app.pocketmonk.model.MessageStatus
import app.pocketmonk.repository.ConversationRepository
import app.pocketmonk.service.DownloadState
import app.pocketmonk.service.LlmService
import app.pocketmonk.service.ModelEntry
import app.pocketmonk.service.ModelManager
import app.pocketmonk.service.WebSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ConversationRepository(application)
    val llmService = LlmService(application)
    val modelManager = ModelManager(application)
    private val webSearchService = WebSearchService()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var downloadJob: Job? = null

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchStatus = MutableStateFlow<String?>(null)
    val searchStatus: StateFlow<String?> = _searchStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _modelReady = MutableStateFlow(false)
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    // Streaming text for the currently generating assistant message.
    // Kept separate from _currentConversation so each partial update triggers
    // recomposition regardless of StateFlow equality deduplication.
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    val contextLength: Int
        get() = _currentConversation.value?.contextSize ?: 2048

    // Message to send after auto-compress finishes
    private var _pendingSendText: String? = null

    val estimatedTokenCount: Int
        get() {
            val conv = _currentConversation.value ?: return 0
            val activeMessages = conv.messages.filter {
                !it.isSummary && !it.isArchived && it.status != MessageStatus.ERROR
            }
            // content tokens + ~12 tokens per message for Gemma chat template overhead
            val messageTokens = activeMessages.sumOf { it.content.length / 4 + 12 }
            // system prompt tokens (included in every prompt)
            val systemTokens = (conv.systemPrompt?.length ?: 80) / 4 + 12
            return messageTokens + systemTokens
        }

    private var currentModelPath: String? = null
    private var currentContextSize: Int = 2048
    private var consecutiveRetries = 0

    init {
        viewModelScope.launch {
            _conversations.value = repo.loadAll()
        }
    }

    fun initModel(modelPath: String, contextSize: Int = 2048) {
        currentModelPath = modelPath
        currentContextSize = contextSize
        viewModelScope.launch {
            _modelReady.value = false
            _errorMessage.value = null
            try {
                llmService.initialize(modelPath, maxTokens = contextSize)
                _modelReady.value = true
                // Ensure there's an active conversation to show in ChatScreen
                if (_currentConversation.value == null) {
                    val existing = _conversations.value.firstOrNull()
                    if (existing != null) {
                        _currentConversation.value = existing
                    } else {
                        newConversation()
                    }
                }
                checkAndAutoRetry()
            } catch (e: Throwable) {
                _errorMessage.value = "Failed to load model: ${e.javaClass.simpleName}: ${e.message}"
                _modelReady.value = false
                // Clear the saved path so the next launch doesn't retry this broken model
                modelManager.clearActiveModelPath()
            }
        }
    }

    fun sendMessage(text: String) {
        val conv = _currentConversation.value ?: run {
            _errorMessage.value = "No active conversation"
            return
        }
        if (!_modelReady.value || _isGenerating.value || _isCompressing.value) return

        // Auto-compress before generating if context is near the limit
        val ratio = estimatedTokenCount.toFloat() / contextLength
        val activeCount = conv.messages.count { !it.isSummary && !it.isArchived }
        if (ratio > 0.70f && activeCount > 4) {
            _pendingSendText = text
            compressContext()
            return
        }

        val userMessage = Message(
            role = MessageRole.USER,
            content = text.trim()
        )
        conv.messages.add(userMessage)
        _currentConversation.value = conv.copy(messages = conv.messages)

        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.STREAMING
        )
        conv.messages.add(assistantMessage)
        _streamingText.value = ""
        _isGenerating.value = true
        _errorMessage.value = null

        val contextSummary = conv.messages.firstOrNull { it.isSummary }?.content
        val historyForPrompt = conv.messages.filter {
            !it.isSummary && !it.isArchived &&
            it.status != MessageStatus.STREAMING && it.status != MessageStatus.ERROR
        }

        llmService.chat(
            history = historyForPrompt,
            systemPrompt = conv.systemPrompt,
            contextSummary = contextSummary,
            temperature = conv.temperature,
            onPartial = { partial ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (_isGenerating.value) {
                        _streamingText.value = partial
                    }
                }
            },
            onDone = {
                viewModelScope.launch(Dispatchers.Main) {
                    val finalText = _streamingText.value
                    _streamingText.value = ""
                    _isGenerating.value = false

                    if (finalText.isBlank()) {
                        // Empty response — auto-compress and retry if context is large enough,
                        // otherwise surface the error for manual retry.
                        val ratio = estimatedTokenCount.toFloat() / contextLength
                        val activeCount = conv.messages.count { !it.isSummary && !it.isArchived }
                        if (ratio > 0.40f && activeCount > 3) {
                            conv.messages.remove(assistantMessage)
                            conv.messages.remove(userMessage)
                            _currentConversation.value = conv.copy(messages = conv.messages)
                            _pendingSendText = userMessage.content
                            compressContext()
                        } else {
                            assistantMessage.content = "*(no response — tap ↺ to retry)*"
                            assistantMessage.status = MessageStatus.ERROR
                            _currentConversation.value = conv.copy(messages = conv.messages)
                            persistCurrentConversation()
                            checkAndAutoRetry()
                        }
                    } else {
                        consecutiveRetries = 0
                        assistantMessage.content = finalText
                        assistantMessage.status = MessageStatus.DONE
                        _currentConversation.value = conv.copy(messages = conv.messages)

                        if (conv.title == "New Conversation" && conv.messages.size == 2) {
                            autoGenerateTitle(conv, userMessage.content, finalText)
                        }

                        val postRatio = estimatedTokenCount.toFloat() / contextLength
                        val postActiveCount = conv.messages.count { !it.isSummary && !it.isArchived }
                        if (postRatio > 0.70f && postActiveCount > 4) {
                            compressContext()
                        } else {
                            persistCurrentConversation()
                        }
                    }
                }
            },
            onError = { error ->
                viewModelScope.launch(Dispatchers.Main) {
                    assistantMessage.content = _streamingText.value.ifEmpty { "Error: $error" }
                    assistantMessage.status = MessageStatus.ERROR
                    _streamingText.value = ""
                    _isGenerating.value = false
                    _errorMessage.value = error
                    _currentConversation.value = conv.copy(messages = conv.messages)
                    checkAndAutoRetry()
                }
            }
        )
    }

    fun searchAndSend(query: String, level: Int = 2) {
        val conv = _currentConversation.value ?: return
        if (!_modelReady.value || _isGenerating.value || _isCompressing.value || _isSearching.value) return

        _isSearching.value = true
        _searchStatus.value = "Fetching results…"
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                // ── Level 4: Mega Deep agentic pipeline ──────────────────────
                if (level == 4) {
                    val maxIterations = 5
                    var accumulatedGapContext: String? = null
                    val allFindings = mutableListOf<Triple<String, String, String>>() // (query, title, extracted)
                    val queriesUsed = mutableListOf<String>()

                    for (iteration in 1..maxIterations) {
                        // Step 1: Generate optimized query
                        withContext(Dispatchers.Main) {
                            _searchStatus.value = if (iteration == 1) "Forging search query…"
                                                  else "Iteration $iteration: refining search query…"
                        }
                        val optimizedQuery = runCatching {
                            llmService.generateOptimizedQuery(query, accumulatedGapContext)
                        }.getOrNull() ?: break
                        queriesUsed.add(optimizedQuery)

                        // Step 2: Fetch 10 results (metadata only — no page fetches yet)
                        withContext(Dispatchers.Main) { _searchStatus.value = "Searching: \"$optimizedQuery\"…" }
                        val results = runCatching {
                            webSearchService.searchMetadataOnly(optimizedQuery, 10)
                        }.getOrNull() ?: break
                        if (results.isEmpty()) break

                        val resultsSummary = results.mapIndexed { i, r ->
                            "${i + 1}. ${r.title}\n   ${r.snippet}"
                        }.joinToString("\n")

                        // Step 3a: Score results by relevance (title+snippet only)
                        withContext(Dispatchers.Main) { _searchStatus.value = "Scoring ${results.size} results…" }
                        val rankedIndices = runCatching {
                            llmService.scoreResults(resultsSummary, query)
                        }.getOrElse { results.indices.toList() }

                        // Step 3b: Analyze what's missing (accumulated across iterations)
                        withContext(Dispatchers.Main) { _searchStatus.value = "Analyzing what's missing…" }
                        val gapAnalysis = runCatching {
                            llmService.analyzeGaps(resultsSummary, query, accumulatedGapContext)
                        }.getOrNull()
                        if (!gapAnalysis.isNullOrBlank()) {
                            accumulatedGapContext = buildString {
                                if (!accumulatedGapContext.isNullOrBlank()) appendLine(accumulatedGapContext)
                                appendLine(gapAnalysis)
                            }.trim()
                        }

                        // Step 4: Read top 3 ranked pages in full and extract
                        for (idx in rankedIndices.take(3)) {
                            val result = results.getOrNull(idx) ?: continue
                            val url = result.realUrl ?: continue
                            withContext(Dispatchers.Main) {
                                _searchStatus.value = "Reading ${result.displayUrl.ifBlank { result.title }}…"
                            }
                            val fullContent = runCatching {
                                webSearchService.fetchFullPage(url)
                            }.getOrElse { "" }
                            if (fullContent.isBlank()) continue
                            withContext(Dispatchers.Main) {
                                _searchStatus.value = "Extracting from ${result.displayUrl.ifBlank { result.title }}…"
                            }
                            val extracted = runCatching {
                                llmService.extractRelevantInfo(fullContent, optimizedQuery, query)
                            }.getOrNull()
                            if (!extracted.isNullOrBlank()) {
                                allFindings.add(Triple(optimizedQuery, result.title, extracted))
                            }
                        }

                        // Step 5: Evaluate sufficiency — stop early if we have enough
                        if (allFindings.isNotEmpty()) {
                            withContext(Dispatchers.Main) { _searchStatus.value = "Evaluating sufficiency…" }
                            val gathered = allFindings.joinToString("\n") { "• ${it.second}: ${it.third}" }
                            val sufficient = runCatching {
                                llmService.evaluateSufficiency(gathered, query)
                            }.getOrElse { false }
                            if (sufficient) break
                        }
                    }

                    // Synthesize all findings into a final context
                    var synthesis: String? = null
                    if (allFindings.isNotEmpty()) {
                        withContext(Dispatchers.Main) { _searchStatus.value = "Synthesizing research…" }
                        synthesis = runCatching {
                            llmService.synthesizeResearch(query, allFindings)
                        }.getOrNull()
                    }

                    val formatted = buildString {
                        appendLine("[Mega Deep Research: \"$query\"]")
                        appendLine("Queries used: ${queriesUsed.joinToString(" · ")}")
                        if (!synthesis.isNullOrBlank()) {
                            appendLine()
                            appendLine("Synthesis:")
                            appendLine(synthesis)
                        }
                        if (allFindings.isNotEmpty()) {
                            appendLine()
                            appendLine("Research notes:")
                            allFindings.groupBy { it.first }.forEach { (q, items) ->
                                appendLine("— $q —")
                                items.forEach { (_, title, info) -> appendLine("• $title: $info") }
                            }
                        }
                    }.trim()

                    withContext(Dispatchers.Main) {
                        if (allFindings.isNotEmpty() || synthesis != null) {
                            val searchMsg = Message(
                                role = MessageRole.USER,
                                content = formatted,
                                isSearchResult = true
                            )
                            conv.messages.add(searchMsg)
                            _currentConversation.value = conv.copy(messages = conv.messages)
                        }
                        _isSearching.value = false
                        _searchStatus.value = null
                        sendMessage(query)
                    }
                    return@launch
                }

                // ── Levels 1–3: standard search pipeline ─────────────────────
                var results = webSearchService.search(query, level, contextLength)
                var synthesis: String? = null

                if (level >= 2 && results.isNotEmpty()) {
                    // ── Stage 1: compress each page individually ──────────────
                    val pagesWithContent = results.count { it.pageContent != null }
                    results = results.mapIndexed { i, result ->
                        val raw = result.pageContent ?: return@mapIndexed result
                        withContext(Dispatchers.Main) {
                            _searchStatus.value = "Compressing page ${i + 1} / $pagesWithContent…"
                        }
                        val compressed = runCatching {
                            llmService.compressText(raw, query)
                        }.getOrNull()
                        result.copy(pageContent = compressed ?: raw.take(300))
                    }

                    // ── Stage 2: synthesize all stage-1 summaries ─────────────
                    val allSummaries = results.mapIndexedNotNull { i, r ->
                        r.pageContent?.let { "${i + 1}. ${r.title}: $it" }
                    }.joinToString("\n")

                    if (allSummaries.isNotBlank()) {
                        withContext(Dispatchers.Main) { _searchStatus.value = "Synthesizing findings…" }
                        synthesis = runCatching {
                            llmService.compressText(allSummaries, query)
                        }.getOrNull()
                    }
                }

                withContext(Dispatchers.Main) {
                    if (results.isEmpty()) {
                        _isSearching.value = false
                        _searchStatus.value = null
                        sendMessage(query)
                        return@withContext
                    }
                    val formatted = webSearchService.format(query, results, synthesis)
                    val searchMsg = Message(
                        role = MessageRole.USER,
                        content = formatted,
                        isSearchResult = true
                    )
                    conv.messages.add(searchMsg)
                    _currentConversation.value = conv.copy(messages = conv.messages)
                    _isSearching.value = false
                    _searchStatus.value = null
                    sendMessage(query)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isSearching.value = false
                    _searchStatus.value = null
                    _errorMessage.value = "Search failed: ${e.message ?: "unknown error"}"
                }
            }
        }
    }

    fun stopGeneration() {
        llmService.cancel()
        _isGenerating.value = false
        val conv = _currentConversation.value ?: return
        val lastMsg = conv.messages.lastOrNull()
        if (lastMsg?.status == MessageStatus.STREAMING) {
            val stopped = _streamingText.value
            lastMsg.content = stopped.ifBlank { "*(stopped — tap ↺ to retry)*" }
            lastMsg.status = MessageStatus.DONE
            _currentConversation.value = conv.copy(messages = conv.messages)
        }
        _streamingText.value = ""
        persistCurrentConversation()
    }

    fun regenerateLastResponse() {
        val conv = _currentConversation.value ?: return
        if (_isGenerating.value || _isCompressing.value) return

        // Remove last active (non-archived) assistant message
        val lastAssistant = conv.messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.isArchived }
        if (lastAssistant != null) {
            conv.messages.remove(lastAssistant)
            _currentConversation.value = conv.copy(messages = conv.messages)
        }

        // Find and remove last active user message (skip search result messages)
        val lastUser = conv.messages.lastOrNull { it.role == MessageRole.USER && !it.isArchived && !it.isSearchResult }
        val lastUserMsg = lastUser?.content ?: return
        // Also remove the preceding search result message if any
        val lastUserIdx = conv.messages.indexOf(lastUser)
        val precedingSearch = if (lastUserIdx > 0) conv.messages[lastUserIdx - 1].takeIf { it.isSearchResult } else null
        conv.messages.remove(lastUser)
        if (precedingSearch != null) conv.messages.remove(precedingSearch)
        _currentConversation.value = conv.copy(messages = conv.messages)

        // If context is already above 40% compress first, then send.
        // This handles the case where the model returned empty because
        // the prompt left too little room for the response.
        val ratio = estimatedTokenCount.toFloat() / contextLength
        if (ratio > 0.40f) {
            _pendingSendText = lastUserMsg
            compressContext()
        } else {
            sendMessage(lastUserMsg)
        }
    }

    fun editMessageAt(index: Int, newContent: String) {
        val conv = _currentConversation.value ?: return
        if (index < 0 || index >= conv.messages.size) return
        val msg = conv.messages[index]
        msg.content = newContent

        // Remove all messages after this one
        while (conv.messages.size > index + 1) {
            conv.messages.removeAt(conv.messages.size - 1)
        }
        _currentConversation.value = conv.copy(messages = conv.messages)
        persistCurrentConversation()
    }

    fun toggleStarMessage(messageId: String) {
        val conv = _currentConversation.value ?: return
        val msg = conv.messages.find { it.id == messageId } ?: return
        msg.starred = !msg.starred
        _currentConversation.value = conv.copy(messages = conv.messages)
        persistCurrentConversation()
    }

    fun forkConversationAt(messageIndex: Int) {
        val conv = _currentConversation.value ?: return
        if (messageIndex < 0 || messageIndex >= conv.messages.size) return

        val forkedMessages = conv.messages.take(messageIndex + 1).map { it.copy() }.toMutableList()
        val forked = Conversation(
            title = "${conv.title} (fork)",
            modelPath = conv.modelPath,
            systemPrompt = conv.systemPrompt,
            tags = conv.tags.toMutableList(),
            messages = forkedMessages
        )
        viewModelScope.launch {
            repo.save(forked)
            val updated = _conversations.value.toMutableList()
            updated.add(0, forked)
            _conversations.value = updated
            _currentConversation.value = forked
        }
    }

    fun compressContext(keepLast: Int = 3) {
        val conv = _currentConversation.value ?: return
        if (_isCompressing.value) return

        // Only active (non-archived, non-summary) messages are candidates
        val activeMessages = conv.messages.filter { !it.isSummary && !it.isArchived }
        if (activeMessages.size <= keepLast) return

        val toArchive = activeMessages.dropLast(keepLast)
        val existingSummary = conv.messages.firstOrNull { it.isSummary }?.content

        _isCompressing.value = true

        viewModelScope.launch {
            val summary = llmService.summarizeHistory(toArchive, existingSummary)
            withContext(Dispatchers.Main) {
                if (summary != null) {
                    // Mark messages as archived — they stay visible in the UI
                    toArchive.forEach { it.isArchived = true }

                    // Replace the existing summary message (or insert one after the last archived msg)
                    conv.messages.removeAll { it.isSummary }
                    val insertIndex = conv.messages.indexOfLast { it.isArchived } + 1
                    conv.messages.add(insertIndex, Message(
                        role = MessageRole.ASSISTANT,
                        content = summary,
                        isSummary = true,
                        status = MessageStatus.DONE
                    ))
                    _currentConversation.value = conv.copy(messages = conv.messages)
                }
                _isCompressing.value = false
                persistCurrentConversation()

                val pending = _pendingSendText
                if (pending != null) {
                    _pendingSendText = null
                    sendMessage(pending)
                }
            }
        }
    }

    fun newConversation(modelPath: String? = null, contextSize: Int? = null, temperature: Float? = null) {
        val path = modelPath ?: currentModelPath ?: return
        val size = contextSize ?: currentContextSize
        val temp = temperature ?: 1.0f
        // Re-init model if path or context size differs from what's currently loaded
        if (path != currentModelPath || size != currentContextSize) {
            initModel(path, size)
        }
        val conv = Conversation(
            title = "New Conversation",
            modelPath = path,
            contextSize = size,
            temperature = temp
        )
        viewModelScope.launch {
            repo.save(conv)
            val updated = _conversations.value.toMutableList()
            updated.add(0, conv)
            _conversations.value = updated
            _currentConversation.value = conv
        }
    }

    fun loadConversation(conv: Conversation) {
        _currentConversation.value = conv
        _errorMessage.value = null
        checkAndAutoRetry()
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            val updated = _conversations.value.filter { it.id != id }
            _conversations.value = updated
            if (_currentConversation.value?.id == id) {
                _currentConversation.value = updated.firstOrNull()
            }
        }
    }

    fun setSystemPrompt(prompt: String?) {
        val conv = _currentConversation.value ?: return
        conv.systemPrompt = prompt
        _currentConversation.value = conv.copy(systemPrompt = prompt)
        persistCurrentConversation()
    }

    fun addTag(conv: Conversation, tag: String) {
        if (tag.isBlank() || conv.tags.contains(tag)) return
        conv.tags.add(tag)
        refreshConversationList(conv)
        persistCurrentConversation()
    }

    fun removeTag(conv: Conversation, tag: String) {
        conv.tags.remove(tag)
        refreshConversationList(conv)
        persistCurrentConversation()
    }

    fun exportConversation(): String {
        val conv = _currentConversation.value ?: return ""
        val sb = StringBuilder()
        sb.appendLine("# ${conv.title}")
        sb.appendLine()
        if (!conv.systemPrompt.isNullOrBlank()) {
            sb.appendLine("**System Prompt:** ${conv.systemPrompt}")
            sb.appendLine()
        }
        for (msg in conv.messages) {
            if (msg.isSummary) {
                sb.appendLine("---")
                sb.appendLine("*[Context Summary: ${msg.content}]*")
                sb.appendLine("---")
            } else {
                val label = if (msg.role == MessageRole.USER) "**You**" else "**PocketMonk**"
                sb.appendLine("$label: ${msg.content}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun clearHistory() {
        val conv = _currentConversation.value ?: return
        conv.messages.clear()
        _currentConversation.value = conv.copy(messages = conv.messages)
        persistCurrentConversation()
    }

    fun renameConversation() {
        val conv = _currentConversation.value ?: return
        val messages = conv.messages.filter { !it.isSummary }
        if (messages.isEmpty()) return
        val context = messages.take(6).joinToString("\n") { msg ->
            val role = if (msg.role == MessageRole.USER) "User" else "Assistant"
            "$role: ${msg.content.take(200)}"
        }
        viewModelScope.launch {
            val title = llmService.generateTitleFromContext(context)
            if (!title.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    applyTitle(conv, title)
                }
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    // ── Model download ────────────────────────────────────────────────────────

    fun downloadModel(entry: ModelEntry) {
        if (_downloadState.value is DownloadState.Downloading) return
        downloadJob = viewModelScope.launch {
            _downloadState.value = DownloadState.Downloading(entry.id, 0f)
            try {
                val file = modelManager.downloadModel(entry) { downloaded, total ->
                    val progress = if (total > 0) downloaded.toFloat() / total else -1f
                    _downloadState.value = DownloadState.Downloading(entry.id, progress)
                }
                modelManager.setActiveModelPath(file.absolutePath)
                _downloadState.value = DownloadState.Done(file.absolutePath)
            } catch (e: Exception) {
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = DownloadState.Idle
    }

    fun dismissDownloadError() {
        _downloadState.value = DownloadState.Idle
    }

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }

    fun deleteModel(entry: ModelEntry) {
        modelManager.deleteModel(entry)
    }

    fun useLocalModel(path: String) {
        modelManager.setActiveModelPath(path)
        _downloadState.value = DownloadState.Done(path)
    }

    private fun autoGenerateTitle(conv: Conversation, userMsg: String, assistantMsg: String) {
        viewModelScope.launch {
            val title = llmService.generateTitle(userMsg, assistantMsg)
            if (!title.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    applyTitle(conv, title)
                }
            }
        }
    }

    // Applies a new title to a conversation without mutating the existing object,
    // ensuring StateFlow detects the change and both current + list are updated.
    private fun applyTitle(conv: Conversation, title: String) {
        val updated = conv.copy(title = title)
        if (_currentConversation.value?.id == conv.id) {
            _currentConversation.value = updated
        }
        val list = _conversations.value.toMutableList()
        val idx = list.indexOfFirst { it.id == conv.id }
        if (idx >= 0) {
            list[idx] = updated
            _conversations.value = list
        }
        viewModelScope.launch { repo.save(updated) }
    }

    private fun refreshConversationList(conv: Conversation) {
        val list = _conversations.value.toMutableList()
        val idx = list.indexOfFirst { it.id == conv.id }
        if (idx >= 0) {
            list[idx] = conv
            _conversations.value = list
        }
    }

    private fun checkAndAutoRetry() {
        val conv = _currentConversation.value ?: return
        if (!_modelReady.value || _isGenerating.value || _isCompressing.value) return
        val lastActive = conv.messages.lastOrNull { !it.isArchived && !it.isSummary }
        if (lastActive?.status == MessageStatus.ERROR && lastActive.role == MessageRole.ASSISTANT) {
            if (consecutiveRetries < 3) {
                consecutiveRetries++
                regenerateLastResponse()
            } else {
                consecutiveRetries = 0
                // 3 attempts failed — leave the error visible so the user can start a new conversation
            }
        } else {
            consecutiveRetries = 0
        }
    }

    private fun persistCurrentConversation() {
        val conv = _currentConversation.value ?: return
        viewModelScope.launch {
            repo.save(conv)
            refreshConversationList(conv)
        }
    }

    override fun onCleared() {
        super.onCleared()
        llmService.dispose()
    }
}
