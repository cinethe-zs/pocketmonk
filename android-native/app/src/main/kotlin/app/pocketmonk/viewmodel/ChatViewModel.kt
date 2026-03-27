package app.pocketmonk.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pocketmonk.model.Conversation
import app.pocketmonk.model.Message
import app.pocketmonk.model.MessageRole
import app.pocketmonk.model.MessageStatus
import app.pocketmonk.model.Persona
import app.pocketmonk.repository.ConversationRepository
import app.pocketmonk.service.DownloadState
import app.pocketmonk.service.LlmService
import app.pocketmonk.service.ModelEntry
import app.pocketmonk.service.ModelManager
import app.pocketmonk.service.PersonaStore
import app.pocketmonk.service.VoskService
import app.pocketmonk.service.WebSearchService
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
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
    val voskService = VoskService(application)
    private val webSearchService = WebSearchService()
    private val personaStore = PersonaStore(application)
    private val gson = GsonBuilder().create()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var downloadJob: Job? = null
    private var processingJob: Job? = null

    private val voskPrefs =
        application.getSharedPreferences("vosk_prefs", android.content.Context.MODE_PRIVATE)

    private val _voskLanguage = MutableStateFlow(
        voskPrefs.getString("language", "en") ?: "en"
    )
    val voskLanguage: StateFlow<String> = _voskLanguage.asStateFlow()

    fun setVoskLanguage(lang: String) {
        _voskLanguage.value = lang
        voskPrefs.edit().putString("language", lang).apply()
    }

    private val _voskSizePref = MutableStateFlow(
        voskPrefs.getString("size_pref", "small") ?: "small"
    )
    val voskSizePref: StateFlow<String> = _voskSizePref.asStateFlow()

    fun setVoskSizePref(pref: String) {
        _voskSizePref.value = pref
        voskPrefs.edit().putString("size_pref", pref).apply()
    }

    private val _voskDownloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val voskDownloadStates: StateFlow<Map<String, DownloadState>> = _voskDownloadStates.asStateFlow()
    private val voskDownloadJobs = mutableMapOf<String, Job>()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _transcriptionProgress = MutableStateFlow(0f)
    val transcriptionProgress: StateFlow<Float> = _transcriptionProgress.asStateFlow()

    private val _isMapReducing = MutableStateFlow(false)
    val isMapReducing: StateFlow<Boolean> = _isMapReducing.asStateFlow()

    private val _mapReduceStatus = MutableStateFlow<String?>(null)
    val mapReduceStatus: StateFlow<String?> = _mapReduceStatus.asStateFlow()

    private val _classifierLog = MutableStateFlow<String?>(null)
    val classifierLog: StateFlow<String?> = _classifierLog.asStateFlow()

    private val _transformLog = MutableStateFlow<String?>(null)
    val transformLog: StateFlow<String?> = _transformLog.asStateFlow()

    private val _analyzeIterationLogs = MutableStateFlow<List<String>>(emptyList())
    val analyzeIterationLogs: StateFlow<List<String>> = _analyzeIterationLogs.asStateFlow()

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

    private val _searchLog = MutableStateFlow<String?>(null)
    val searchLog: StateFlow<String?> = _searchLog.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _documentName = MutableStateFlow<String?>(null)
    val documentName: StateFlow<String?> = _documentName.asStateFlow()
    private val _documentContent = MutableStateFlow<String?>(null)

    private val _documentLog = MutableStateFlow<String?>(null)
    val documentLog: StateFlow<String?> = _documentLog.asStateFlow()

    // Separate logs for video: frame OCR and audio transcript shown as two distinct cards.
    private val _ocrLog = MutableStateFlow<String?>(null)
    val ocrLog: StateFlow<String?> = _ocrLog.asStateFlow()

    private val _audioLog = MutableStateFlow<String?>(null)
    val audioLog: StateFlow<String?> = _audioLog.asStateFlow()

    private val _modelReady = MutableStateFlow(false)
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    private val _personas = MutableStateFlow<List<Persona>>(emptyList())
    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()

    // Streaming text for the currently generating assistant message.
    // Kept separate from _currentConversation so each partial update triggers
    // recomposition regardless of StateFlow equality deduplication.
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    val contextLength: Int
        get() = _currentConversation.value?.contextSize ?: 4096

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
    private var currentContextSize: Int = 4096
    private var consecutiveRetries = 0

    init {
        viewModelScope.launch {
            _conversations.value = repo.loadAll()
        }
        _personas.value = personaStore.load()
    }

    fun initModel(modelPath: String, contextSize: Int = 4096) {
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

        val userMessage = Message(role = MessageRole.USER, content = text.trim())
        conv.messages.add(userMessage)
        _currentConversation.value = conv.copy(messages = conv.messages)
        _isGenerating.value = true
        _streamingText.value = ""
        _errorMessage.value = null

        val docContent = _documentContent.value
        if (docContent != null && docContent.length > 8000) {
            // Long document: classify intent, then stream (TRANSFORM) or map-reduce (ANALYZE)
            _isMapReducing.value = true
            _mapReduceStatus.value = "Classifying request…"
            _transformLog.value = null
            _analyzeIterationLogs.value = emptyList()
            processingJob = viewModelScope.launch {
                val classification = llmService.classifyIntentLlm(text) { prompt, response ->
                    viewModelScope.launch(Dispatchers.Main) {
                        _classifierLog.value = buildString {
                            appendLine("=== Prompt ===")
                            appendLine(prompt)
                            appendLine("=== Response ===")
                            appendLine(response)
                            appendLine("=== Decision ===")
                            append(if (response.lowercase().contains("yes")) "TRANSFORM" else "ANALYZE")
                        }
                    }
                }
                val intent = classification.intent
                val intentLabel = intent

                if (intent == "TRANSFORM") {
                    // Stream: apply instruction chunk-by-chunk, concatenate results
                    withContext(Dispatchers.Main) { _mapReduceStatus.value = "$intentLabel — preparing stream…" }
                    val result = try {
                        llmService.streamDocument(
                            document = docContent,
                            instruction = text,
                            onProgress = { status ->
                                viewModelScope.launch(Dispatchers.Main) { _mapReduceStatus.value = status }
                            },
                            onBuffer = { buf ->
                                viewModelScope.launch(Dispatchers.Main) { _transformLog.value = buf }
                            },
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        withContext(Dispatchers.Main) {
                            _isMapReducing.value = false
                            _mapReduceStatus.value = null
                            _isGenerating.value = false
                            val newMsgs = conv.messages.filterNot { it === userMessage }.toMutableList()
                            _currentConversation.value = conv.copy(messages = newMsgs)
                            _errorMessage.value = "Failed to process document: ${e.message}"
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        _isMapReducing.value = false
                        _mapReduceStatus.value = null
                        _isGenerating.value = false
                        if (result.isBlank()) {
                            conv.messages.remove(userMessage)
                            _currentConversation.value = conv.copy(messages = conv.messages)
                            _errorMessage.value = "Stream processing produced no output."
                        } else {
                            // Full result shown in UI via streamDisplay;
                            // short placeholder in LLM context to avoid context overflow.
                            val assistantMessage = Message(
                                role = MessageRole.ASSISTANT,
                                content = "I processed and transformed your document.",
                                streamDisplay = result,
                                status = MessageStatus.DONE,
                            )
                            conv.messages.add(assistantMessage)
                            // Replace documentContent with the result for follow-up questions
                            _documentContent.value = result
                            _documentLog.value = result
                            _currentConversation.value = conv.copy(messages = conv.messages)
                            persistCurrentConversation()
                            if (conv.title == "New Conversation" && conv.messages.size == 2) {
                                autoGenerateTitle(conv, userMessage.content, result.take(200))
                            }
                        }
                    }
                } else {
                    // Map-reduce: extract relevant facts per chunk, then compress
                    withContext(Dispatchers.Main) { _mapReduceStatus.value = "$intentLabel — extracting…" }
                    val synthesis = try {
                        llmService.mapReduceDocument(
                            document = docContent,
                            question = text,
                            onProgress = { status ->
                                viewModelScope.launch(Dispatchers.Main) { _mapReduceStatus.value = status }
                            },
                            onIterationBuffer = { iterIndex, buffer ->
                                viewModelScope.launch(Dispatchers.Main) {
                                    val current = _analyzeIterationLogs.value.toMutableList()
                                    while (current.size <= iterIndex) current.add("")
                                    current[iterIndex] = buffer
                                    _analyzeIterationLogs.value = current.toList()
                                }
                            },
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        withContext(Dispatchers.Main) {
                            _isMapReducing.value = false
                            _mapReduceStatus.value = null
                            _isGenerating.value = false
                            val newMsgs = conv.messages.filterNot { it === userMessage }.toMutableList()
                            _currentConversation.value = conv.copy(messages = newMsgs)
                            _errorMessage.value = "Failed to analyze document: ${e.message}"
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        _isMapReducing.value = false
                        _mapReduceStatus.value = null
                        doChat(conv, userMessage, synthesis.ifBlank { null })
                    }
                }
            }
        } else {
            doChat(conv, userMessage, docContent)
        }
    }

    private fun doChat(conv: Conversation, userMessage: Message, documentContent: String?) {
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.STREAMING,
        )
        conv.messages.add(assistantMessage)
        _currentConversation.value = conv.copy(messages = conv.messages)

        val contextSummary = conv.messages.firstOrNull { it.isSummary }?.content
        val baseHistory = conv.messages.filter {
            !it.isSummary && !it.isArchived &&
            it.status != MessageStatus.STREAMING && it.status != MessageStatus.ERROR
        }

        llmService.chat(
            history = baseHistory,
            systemPrompt = conv.systemPrompt,
            contextSummary = contextSummary,
            documentName = _documentName.value,
            documentContent = documentContent,
            temperature = if (documentContent != null) 0.4f else conv.temperature,
            onPartial = { partial ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (_isGenerating.value) _streamingText.value = partial
                }
            },
            onDone = {
                viewModelScope.launch(Dispatchers.Main) {
                    // If stop was pressed, the message was already finalized by stopGeneration().
                    if (assistantMessage.status != MessageStatus.STREAMING) return@launch
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
                    if (assistantMessage.status != MessageStatus.STREAMING) return@launch
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

    fun searchAndSend(query: String) {
        val conv = _currentConversation.value ?: return
        if (!_modelReady.value || _isGenerating.value || _isCompressing.value || _isSearching.value) return

        _isSearching.value = true
        _searchStatus.value = "Fetching results…"
        _errorMessage.value = null

        processingJob = viewModelScope.launch {
            try {
                // Tag conversation as web-searched immediately
                withContext(Dispatchers.Main) {
                    if (!conv.tags.contains("web")) {
                        conv.tags.add("web")
                        _currentConversation.value = conv.copy(messages = conv.messages)
                        persistCurrentConversation()
                    }
                }

                // ── Websearch: fetch top 6 results (DDG), read top 2 pages, inject as context ──
                val results = webSearchService.search(query, 1, contextLength)

                withContext(Dispatchers.Main) {
                    if (results.isEmpty()) {
                        _isSearching.value = false
                        _searchStatus.value = null
                        sendMessage(query)
                        return@withContext
                    }
                    val formatted = webSearchService.format(query, results)
                    val formattedWithMeta = "$formatted\n\n[Context: ${formatted.length} chars]"
                    val searchMsg = Message(
                        role = MessageRole.USER,
                        content = formattedWithMeta,
                        isSearchResult = true
                    )
                    conv.messages.add(searchMsg)
                    _currentConversation.value = conv.copy(messages = conv.messages)
                    _isSearching.value = false
                    _searchStatus.value = null
                    sendMessage(query)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    _isSearching.value = false
                    _searchStatus.value = null
                    _searchLog.value = null
                }
                throw e
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
        // Cancel map-reduce/stream processing if running
        if (_isMapReducing.value) {
            processingJob?.cancel()
            processingJob = null
            llmService.cancel()
            _isMapReducing.value = false
            _mapReduceStatus.value = null
            _isGenerating.value = false
            _streamingText.value = ""
            _transformLog.value = null
            _analyzeIterationLogs.value = emptyList()
            val conv = _currentConversation.value ?: return
            val lastUser = conv.messages.lastOrNull { it.role == MessageRole.USER && !it.isArchived }
            if (lastUser != null) {
                val newMessages = conv.messages.filterNot { it === lastUser }.toMutableList()
                _currentConversation.value = conv.copy(messages = newMessages)
            }
            return
        }
        // Cancel in-flight search if running
        if (_isSearching.value) {
            processingJob?.cancel()
            processingJob = null
            llmService.cancel()
            _isSearching.value = false
            _searchStatus.value = null
            _searchLog.value = null
            _isGenerating.value = false
            _streamingText.value = ""
            return
        }
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
        val lastUser = conv.messages.lastOrNull { it.role == MessageRole.USER && !it.isArchived && !it.isSearchResult && !it.isSearchLog }
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

    fun newConversation(
        modelPath: String? = null,
        contextSize: Int? = null,
        temperature: Float? = null,
        systemPrompt: String? = null
    ) {
        clearDocument()
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
            temperature = temp,
            systemPrompt = systemPrompt
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
        clearDocument()
        _currentConversation.value = conv
        _errorMessage.value = null
        checkAndAutoRetry()
    }

    fun loadDocument(name: String, content: String) {
        _documentName.value = name
        _documentContent.value = content
        _documentLog.value = content
        _ocrLog.value = null
        _audioLog.value = null
        _currentConversation.value?.let { addTag(it, "file") }
    }

    fun clearDocument() {
        _documentName.value = null
        _documentContent.value = null
        _documentLog.value = null
        _ocrLog.value = null
        _audioLog.value = null
        _classifierLog.value = null
        _transformLog.value = null
        _analyzeIterationLogs.value = emptyList()
    }

    fun loadDocumentFromUri(uri: android.net.Uri) {
        val ctx = getApplication<android.app.Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Query name and size in one pass
            var name = "document"
            var fileSize = 0L
            try {
                ctx.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0) ?: uri.lastPathSegment ?: "document"
                        fileSize = cursor.getLong(1)
                    }
                }
            } catch (_: Exception) {
                name = uri.lastPathSegment ?: "document"
            }

            val ext = name.substringAfterLast('.', "").lowercase()
            val mimeType = ctx.contentResolver.getType(uri)
            val isImage = app.pocketmonk.util.DocumentTextExtractor.isImage(mimeType, ext)
            val isVideo = app.pocketmonk.util.DocumentTextExtractor.isVideo(mimeType, ext)
            val isAudio = !isVideo &&
                app.pocketmonk.util.DocumentTextExtractor.isAudio(mimeType, ext)

            // Audio: transcribe via Vosk
            if (isAudio) {
                val lang = voskService.bestModelKey(_voskLanguage.value, _voskSizePref.value)
                if (lang == null) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _errorMessage.value =
                            "Download a speech recognition model in Settings to transcribe audio."
                    }
                    return@launch
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _isTranscribing.value = true
                    _transcriptionProgress.value = 0f
                }
                val transcript = try {
                    voskService.transcribeUri(
                        uri,
                        modelKey = lang,
                        onProgress = { progress ->
                            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                _transcriptionProgress.value = progress.coerceIn(0f, 1f)
                            }
                        },
                        onPartialResult = { partial ->
                            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                if (_documentName.value == null) _documentName.value = name
                                _audioLog.value = partial
                            }
                        },
                    )
                } catch (e: Throwable) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _isTranscribing.value = false
                        _transcriptionProgress.value = 0f
                        _documentName.value = null
                        _audioLog.value = null
                        _errorMessage.value = "Failed to transcribe \"$name\": ${e.message}"
                    }
                    return@launch
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _isTranscribing.value = false
                    _transcriptionProgress.value = 0f
                    if (transcript.isBlank()) {
                        _documentName.value = null
                        _audioLog.value = null
                        _errorMessage.value = "No speech detected in \"$name\"."
                    } else {
                        _documentName.value = name
                        _documentContent.value = "Audio transcript:\n$transcript"
                        _documentLog.value = null
                        _ocrLog.value = null
                        _audioLog.value = transcript
                        _currentConversation.value?.let { addTag(it, "file") }
                    }
                }
                return@launch
            }

            // Video: frame OCR + optional Vosk audio transcription
            if (isVideo) {
                val ocrText = try {
                    app.pocketmonk.util.DocumentTextExtractor.extractFromVideo(ctx, uri)
                } catch (e: Throwable) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _errorMessage.value = "Failed to read \"$name\": ${e.message}"
                    }
                    return@launch
                }
                if (ocrText.isNotBlank()) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _documentName.value = name
                        _ocrLog.value = ocrText
                    }
                }
                val audioLang = voskService.bestModelKey(_voskLanguage.value, _voskSizePref.value)
                val audioText = if (audioLang != null) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _isTranscribing.value = true
                        _transcriptionProgress.value = 0f
                    }
                    try {
                        voskService.transcribeUri(
                            uri,
                            modelKey = audioLang,
                            onProgress = { progress ->
                                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    _transcriptionProgress.value = progress.coerceIn(0f, 1f)
                                }
                            },
                            onPartialResult = { partial ->
                                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    _audioLog.value = partial
                                }
                            },
                        )
                    } catch (_: Throwable) { "" }
                    .also {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            _isTranscribing.value = false
                            _transcriptionProgress.value = 0f
                        }
                    }
                } else ""
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val combined = buildString {
                        if (ocrText.isNotBlank()) { append("On-screen text (OCR):\n"); append(ocrText) }
                        if (audioText.isNotBlank()) { if (isNotEmpty()) append("\n\n"); append("Audio transcript:\n"); append(audioText) }
                    }
                    if (combined.isBlank()) {
                        _errorMessage.value = "No text or speech found in \"$name\"."
                        _documentName.value = null
                        _ocrLog.value = null
                        _audioLog.value = null
                    } else {
                        _documentName.value = name
                        _documentContent.value = combined
                        _documentLog.value = null
                        _ocrLog.value = ocrText.takeIf { it.isNotBlank() }
                        _audioLog.value = audioText.takeIf { it.isNotBlank() }
                        _currentConversation.value?.let { addTag(it, "file") }
                    }
                }
                return@launch
            }

            // Reject files that would OOM — 20 MB is generous for any text/doc/image content
            val limitBytes = 20 * 1024 * 1024L
            if (fileSize > limitBytes) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _errorMessage.value = "\"$name\" is too large (${fileSize / 1024 / 1024} MB). Maximum is 20 MB."
                }
                return@launch
            }

            val bytes = try {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Throwable) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _errorMessage.value = "Failed to open \"$name\": ${e.message}"
                }
                return@launch
            } ?: run {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _errorMessage.value = "Could not read \"$name\""
                }
                return@launch
            }

            val content: String? = try {
                if (isImage) {
                    app.pocketmonk.util.DocumentTextExtractor.extractFromImage(bytes)
                        .let { if (it.isBlank()) "" else "Text extracted from image (OCR):\n$it" }
                } else {
                    app.pocketmonk.util.DocumentTextExtractor.extract(bytes, mimeType, name)
                }
            } catch (e: Throwable) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _errorMessage.value = "Failed to read \"$name\": ${e.message}"
                }
                return@launch
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                when {
                    content == null ->
                        _errorMessage.value = ".$ext files are not supported. Save as .pdf or .docx instead."
                    content.isBlank() ->
                        _errorMessage.value = if (isImage)
                            "No readable text found in \"$name\"."
                        else
                            "\"$name\" appears to be empty or has no readable text."
                    else -> loadDocument(name, content)
                }
            }
        }
    }

    fun handleSharedFile(uri: android.net.Uri) {
        newConversation(contextSize = 4096)
        loadDocumentFromUri(uri)
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

    // ── Vosk model download ───────────────────────────────────────────────────

    fun downloadVoskModel(key: String) {
        if (_voskDownloadStates.value[key] is DownloadState.Downloading) return
        voskDownloadJobs[key] = viewModelScope.launch {
            setVoskState(key, DownloadState.Downloading(key, 0f))
            try {
                val dir = voskService.download(key) { p -> setVoskState(key, DownloadState.Downloading(key, p)) }
                setVoskState(key, DownloadState.Done(dir.absolutePath))
            } catch (e: Exception) {
                setVoskState(key, DownloadState.Error(e.message ?: "Download failed"))
            }
        }
    }

    fun cancelVoskDownload(key: String) {
        voskDownloadJobs.remove(key)?.cancel()
        setVoskState(key, DownloadState.Idle)
    }

    fun dismissVoskError(key: String) { setVoskState(key, DownloadState.Idle) }

    fun deleteVoskModel(key: String) {
        voskService.delete(key)
        setVoskState(key, DownloadState.Idle)
    }

    private fun setVoskState(key: String, state: DownloadState) {
        _voskDownloadStates.value = _voskDownloadStates.value + (key to state)
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

    // ── Personas ─────────────────────────────────────────────────────────────

    fun savePersona(name: String, systemPrompt: String) {
        val updated = _personas.value + Persona(name = name, systemPrompt = systemPrompt)
        _personas.value = updated
        personaStore.save(updated)
    }

    fun deletePersona(id: String) {
        val updated = _personas.value.filter { it.id != id }
        _personas.value = updated
        personaStore.save(updated)
    }

    // ── Backup / restore ──────────────────────────────────────────────────────

    fun exportBackup(): String {
        val type = object : TypeToken<List<Conversation>>() {}.type
        return gson.toJson(_conversations.value, type)
    }

    @Suppress("UNCHECKED_CAST")
    fun importBackup(json: String) {
        val type = object : TypeToken<List<Conversation>>() {}.type
        val imported: List<Conversation> = runCatching {
            (gson.fromJson(json, type) as? List<Conversation>) ?: emptyList()
        }.getOrDefault(emptyList())
        if (imported.isEmpty()) return
        viewModelScope.launch {
            imported.forEach { conv -> repo.save(conv) }
            val all = repo.loadAll()
            _conversations.value = all
            if (_currentConversation.value == null) {
                _currentConversation.value = all.firstOrNull()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        llmService.dispose()
    }
}
