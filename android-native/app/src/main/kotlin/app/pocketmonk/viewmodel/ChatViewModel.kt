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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    fun searchAndSend(query: String, level: Int = 2) {
        val conv = _currentConversation.value ?: return
        if (!_modelReady.value || _isGenerating.value || _isCompressing.value || _isSearching.value) return

        _isSearching.value = true
        _searchStatus.value = "Fetching results…"
        _errorMessage.value = null

        processingJob = viewModelScope.launch {
            try {
                // ── Level 1: Stratified Convergent Search ──────────────────────
                if (level == 1) {
                    val pageBudgetChars = (contextLength * 0.5 * 4).toInt()
                    val allFindings = mutableListOf<Triple<String, String, String>>()
                    var rollingContext = ""
                    var sufficientEarly = false

                    withContext(Dispatchers.Main) { _searchLog.value = "=== Stratified Convergent Search ===" }

                    suspend fun appendLog(line: String) {
                        withContext(Dispatchers.Main) {
                            _searchLog.value = (_searchLog.value ?: "") + "\n$line"
                        }
                    }

                    // ── Stage 0: Snippet Oracle ────────────────────────────────
                    appendLog("\n[Stage 0 — Snippet Oracle]")
                    withContext(Dispatchers.Main) { _searchStatus.value = "Snippet oracle…" }
                    val stage0Query = runCatching {
                        llmService.generateOptimizedQuery(query)
                    }.getOrNull() ?: query
                    appendLog("  Query: \"$stage0Query\"")
                    val snippetResults = runCatching {
                        webSearchService.searchMetadataOnly(stage0Query, 10)
                    }.getOrElse { emptyList() }
                    if (snippetResults.isNotEmpty()) {
                        val snippetCtx = snippetResults.joinToString("\n") { "• ${it.title}: ${it.snippet}" }
                        appendLog("  ${snippetResults.size} snippets — checking sufficiency…")
                        withContext(Dispatchers.Main) { _searchStatus.value = "Checking snippets…" }
                        val (suf0, _) = runCatching {
                            llmService.evaluateSufficiency(snippetCtx, query)
                        }.getOrElse { Pair(false, 0) }
                        appendLog("  Sufficient from snippets: ${if (suf0) "YES — fast path" else "no"}")
                        if (suf0) {
                            snippetResults.filter { it.snippet.isNotBlank() }
                                .forEach { allFindings.add(Triple(stage0Query, it.title, it.snippet)) }
                            sufficientEarly = true
                        }
                    }

                    // ── Stage 1: Parallel Multi-Query ─────────────────────────
                    if (!sufficientEarly) {
                        appendLog("\n[Stage 1 — Parallel Multi-Query]")
                        withContext(Dispatchers.Main) { _searchStatus.value = "Generating search queries…" }
                        val subQueries = runCatching {
                            llmService.generateSearchQueries(query)
                        }.getOrElse { listOf(stage0Query) }.take(3)
                        appendLog("  ${subQueries.size} queries: ${subQueries.joinToString(" | ") { "\"$it\"" }}")

                        withContext(Dispatchers.Main) { _searchStatus.value = "Searching (${subQueries.size} parallel)…" }
                        val queryResultPairs = coroutineScope {
                            subQueries.map { q ->
                                async {
                                    runCatching { webSearchService.searchMetadataOnly(q, 10) }
                                        .getOrElse { emptyList() }
                                        .map { it to q }
                                }
                            }.awaitAll()
                        }.flatten()

                        val byUrl = linkedMapOf<String, Pair<WebSearchService.SearchResult, String>>()
                        queryResultPairs.forEach { (r, q) ->
                            if (r.realUrl != null) byUrl.putIfAbsent(r.realUrl, r to q)
                        }
                        val uniqueResults = byUrl.values.toList()
                        appendLog("  ${uniqueResults.size} unique results")

                        withContext(Dispatchers.Main) { _searchStatus.value = "Scoring ${uniqueResults.size} results…" }
                        val scoreSummary = uniqueResults.mapIndexed { i, (r, _) ->
                            "${i + 1}. ${r.title}\n   ${r.snippet}"
                        }.joinToString("\n")
                        val scores = runCatching {
                            llmService.scoreResults(scoreSummary, query)
                        }.getOrElse { uniqueResults.indices.map { Pair(it, 5) } }

                        val top6 = scores.take(6)
                        top6.forEachIndexed { rank, (idx, score) ->
                            uniqueResults.getOrNull(idx)?.let { (r, _) ->
                                appendLog("  #${rank + 1} [$score/10] ${r.title.take(60)}")
                            }
                        }

                        withContext(Dispatchers.Main) { _searchStatus.value = "Fetching top ${top6.size} pages…" }
                        val fetchedPages = coroutineScope {
                            top6.map { (idx, score) ->
                                val pair = uniqueResults.getOrNull(idx)
                                async {
                                    if (pair == null) return@async null
                                    val (result, q) = pair
                                    val url = result.realUrl ?: return@async null
                                    val content = runCatching { webSearchService.fetchFullPage(url) }.getOrElse { "" }
                                    if (content.isBlank()) null else Triple(score, result to q, content)
                                }
                            }.awaitAll().filterNotNull()
                        }

                        for ((score, rq, content) in fetchedPages) {
                            val (result, q) = rq
                            val host = result.displayUrl.ifBlank { result.title }.take(50)
                            withContext(Dispatchers.Main) { _searchStatus.value = "Extracting from $host…" }
                            appendLog("  [$score/10] $host — ${content.length} chars")
                            val extracted = runCatching {
                                llmService.extractRelevantInfo(content, q, query)
                            }.getOrNull()
                            if (!extracted.isNullOrBlank()) {
                                allFindings.add(Triple(q, result.title, extracted))
                                val chunk = "\n• ${result.title}: $extracted"
                                rollingContext = if (rollingContext.length + chunk.length > pageBudgetChars) {
                                    runCatching { llmService.compressText(rollingContext + chunk, query) }.getOrNull()
                                        ?: (rollingContext + chunk).takeLast(pageBudgetChars)
                                } else rollingContext + chunk
                                extracted.lines().forEach { appendLog("      $it") }
                            } else appendLog("    → nothing relevant")
                        }
                        appendLog("  Stage 1 done: ${allFindings.size} findings")

                        if (rollingContext.isNotBlank()) {
                            withContext(Dispatchers.Main) { _searchStatus.value = "Checking sufficiency…" }
                            val (suf1, _) = runCatching {
                                llmService.evaluateSufficiency(rollingContext, query)
                            }.getOrElse { Pair(false, 0) }
                            appendLog("  Sufficiency after Stage 1: ${if (suf1) "YES" else "no"}")
                            if (suf1) sufficientEarly = true
                        }
                    }

                    // ── Stage 2: Deep Extraction Loop (2 passes) ──────────────
                    if (!sufficientEarly) {
                        appendLog("\n[Stage 2 — Deep Extraction Loop]")
                        var gapContext: String? = null
                        for (pass in 1..2) {
                            appendLog("  Pass $pass / 2")
                            val gap = if (rollingContext.isNotBlank()) {
                                withContext(Dispatchers.Main) { _searchStatus.value = "Analyzing gaps (pass $pass)…" }
                                runCatching { llmService.analyzeGaps(rollingContext, query, gapContext) }.getOrNull()
                            } else null
                            if (gap.isNullOrBlank()) { appendLog("  No gap — stopping"); break }
                            appendLog("  Gap: ${gap.take(120)}${if (gap.length > 120) "…" else ""}")
                            gapContext = buildString {
                                if (!gapContext.isNullOrBlank()) appendLine(gapContext)
                                append(gap)
                            }.trim()

                            val gapQuery = runCatching {
                                llmService.generateOptimizedQuery(query, gapContext)
                            }.getOrNull() ?: query
                            appendLog("  Gap query: \"$gapQuery\"")
                            withContext(Dispatchers.Main) { _searchStatus.value = "Gap search (pass $pass)…" }

                            val gapMeta = runCatching {
                                webSearchService.searchMetadataOnly(gapQuery, 8)
                            }.getOrElse { emptyList() }
                            if (gapMeta.isEmpty()) { appendLog("  No results — stopping"); break }

                            val gapScoreSummary = gapMeta.mapIndexed { i, r ->
                                "${i + 1}. ${r.title}\n   ${r.snippet}"
                            }.joinToString("\n")
                            val gapScores = runCatching {
                                llmService.scoreResults(gapScoreSummary, query)
                            }.getOrElse { gapMeta.indices.map { Pair(it, 5) } }

                            withContext(Dispatchers.Main) { _searchStatus.value = "Fetching gap pages (pass $pass)…" }
                            val gapPages = coroutineScope {
                                gapScores.take(3).map { (idx, score) ->
                                    val result = gapMeta.getOrNull(idx)
                                    async {
                                        if (result == null) return@async null
                                        val url = result.realUrl ?: return@async null
                                        val content = runCatching { webSearchService.fetchFullPage(url) }.getOrElse { "" }
                                        if (content.isBlank()) null else Triple(score, result, content)
                                    }
                                }.awaitAll().filterNotNull()
                            }

                            for ((score, result, content) in gapPages) {
                                val host = result.displayUrl.ifBlank { result.title }.take(50)
                                withContext(Dispatchers.Main) { _searchStatus.value = "Extracting from $host…" }
                                val extracted = runCatching {
                                    llmService.extractRelevantInfo(content, gapQuery, query)
                                }.getOrNull()
                                if (!extracted.isNullOrBlank()) {
                                    allFindings.add(Triple(gapQuery, result.title, extracted))
                                    appendLog("  [$score/10] $host → extracted")
                                    val chunk = "\n• ${result.title}: $extracted"
                                    rollingContext = if (rollingContext.length + chunk.length > pageBudgetChars) {
                                        runCatching { llmService.compressText(rollingContext + chunk, query) }.getOrNull()
                                            ?: (rollingContext + chunk).takeLast(pageBudgetChars)
                                    } else rollingContext + chunk
                                }
                            }

                            withContext(Dispatchers.Main) { _searchStatus.value = "Checking sufficiency (pass $pass)…" }
                            val (suf2, _) = runCatching {
                                llmService.evaluateSufficiency(rollingContext, query)
                            }.getOrElse { Pair(false, 0) }
                            appendLog("  Sufficiency: ${if (suf2) "YES" else "no"}")
                            if (suf2) break
                        }
                    }

                    // ── Stage 3: Draft-Gap Closure ─────────────────────────────
                    if (!sufficientEarly && allFindings.isNotEmpty() && rollingContext.isNotBlank()) {
                        appendLog("\n[Stage 3 — Draft-Gap Closure]")
                        withContext(Dispatchers.Main) { _searchStatus.value = "Drafting answer…" }
                        val draft = runCatching {
                            llmService.generateAnswerDraft(query, rollingContext)
                        }.getOrNull()
                        if (!draft.isNullOrBlank()) {
                            appendLog("  Draft: ${draft.take(100)}…")
                            withContext(Dispatchers.Main) { _searchStatus.value = "Identifying gaps in draft…" }
                            val holes = runCatching {
                                llmService.identifyUnsupportedClaims(draft, query)
                            }.getOrElse { emptyList() }
                            if (holes.isEmpty()) {
                                appendLog("  Draft complete — no gaps to fill")
                            } else {
                                appendLog("  ${holes.size} gaps: ${holes.joinToString(" | ")}")
                                for (hole in holes) {
                                    withContext(Dispatchers.Main) { _searchStatus.value = "Filling: ${hole.take(40)}…" }
                                    appendLog("  Filling: $hole")
                                    val holeQuery = "${query.take(60)} $hole"
                                    val holeMeta = runCatching {
                                        webSearchService.searchMetadataOnly(holeQuery, 5)
                                    }.getOrElse { emptyList() }
                                    if (holeMeta.isEmpty()) continue
                                    val holePages = coroutineScope {
                                        holeMeta.take(2).map { result ->
                                            async {
                                                val url = result.realUrl ?: return@async null
                                                val content = runCatching { webSearchService.fetchFullPage(url) }.getOrElse { "" }
                                                if (content.isBlank()) null else result to content
                                            }
                                        }.awaitAll().filterNotNull()
                                    }
                                    for ((result, content) in holePages) {
                                        val extracted = runCatching {
                                            llmService.extractRelevantInfo(content, holeQuery, query)
                                        }.getOrNull()
                                        if (!extracted.isNullOrBlank()) {
                                            allFindings.add(Triple(holeQuery, result.title, extracted))
                                            val chunk = "\n• ${result.title}: $extracted"
                                            rollingContext = if (rollingContext.length + chunk.length > pageBudgetChars) {
                                                runCatching { llmService.compressText(rollingContext + chunk, query) }.getOrNull()
                                                    ?: (rollingContext + chunk).takeLast(pageBudgetChars)
                                            } else rollingContext + chunk
                                            appendLog("    → ${result.title.take(50)}: extracted")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Common exit ────────────────────────────────────────────
                    appendLog("\n${allFindings.size} findings gathered.")
                    withContext(Dispatchers.Main) {
                        val finalLog = _searchLog.value
                        if (!finalLog.isNullOrBlank()) {
                            conv.messages.add(Message(
                                role = MessageRole.USER,
                                content = finalLog,
                                isSearchLog = true
                            ))
                        }
                        _searchLog.value = null
                        _isSearching.value = false
                        _searchStatus.value = null
                    }
                    if (allFindings.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            _currentConversation.value = conv.copy(messages = conv.messages)
                            sendMessage(query)
                        }
                        return@launch
                    }
                    val scsUserMsg = Message(role = MessageRole.USER, content = query)
                    val scsAssistant = Message(role = MessageRole.ASSISTANT, content = "", status = MessageStatus.STREAMING)
                    withContext(Dispatchers.Main) {
                        conv.messages.add(scsUserMsg)
                        conv.messages.add(scsAssistant)
                        _streamingText.value = ""
                        _isGenerating.value = true
                        _currentConversation.value = conv.copy(messages = conv.messages)
                    }
                    llmService.answerFromResearch(
                        question = query,
                        findings = allFindings,
                        maxFindingsChars = (contextLength * 0.6 * 4).toInt(),
                        onPartial = { partial ->
                            viewModelScope.launch(Dispatchers.Main) {
                                if (_isGenerating.value) _streamingText.value = partial
                            }
                        },
                        onDone = {
                            viewModelScope.launch(Dispatchers.Main) {
                                if (scsAssistant.status != MessageStatus.STREAMING) return@launch
                                val finalText = _streamingText.value
                                _streamingText.value = ""
                                _isGenerating.value = false
                                scsAssistant.content = finalText.ifBlank { "*(no response — tap ↺ to retry)*" }
                                scsAssistant.status = if (finalText.isBlank()) MessageStatus.ERROR else MessageStatus.DONE
                                _currentConversation.value = conv.copy(messages = conv.messages)
                                persistCurrentConversation()
                            }
                        },
                        onError = { error ->
                            viewModelScope.launch(Dispatchers.Main) {
                                if (scsAssistant.status != MessageStatus.STREAMING) return@launch
                                scsAssistant.content = _streamingText.value.ifEmpty { "Error: $error" }
                                scsAssistant.status = MessageStatus.ERROR
                                _streamingText.value = ""
                                _isGenerating.value = false
                                _errorMessage.value = error
                                _currentConversation.value = conv.copy(messages = conv.messages)
                            }
                        }
                    )
                    return@launch
                }

                // ── Levels 3–6: Deep agentic pipeline ────────────────────────
                if (level >= 3) {
                    // Config per level
                    data class DeepConfig(
                        val name: String,
                        val maxIterations: Int,
                        val maxPages: Int,
                        val checkSufficiency: Boolean,  // false = always read maxPages (Forced modes)
                        val minPagesBeforeCheck: Int    // read at least N pages before first sufficiency check
                    )
                    val config = when (level) {
                        4    -> DeepConfig("Super Deep",  5,  10, true,  5)
                        5    -> DeepConfig("5-Forced",    1,   5, false, 0)
                        6    -> DeepConfig("10-Forced",   1,  10, false, 0)
                        else -> DeepConfig("Deep",        5,  10, true,  0)
                    }

                    var accumulatedGapContext: String? = null
                    val allFindings = mutableListOf<Triple<String, String, String>>()
                    val queriesUsed = mutableListOf<String>()
                    var overallSufficient = false

                    // Live log — dedicated StateFlow guarantees recomposition on every append
                    withContext(Dispatchers.Main) { _searchLog.value = "=== ${config.name} Search ===" }

                    suspend fun appendLog(line: String) {
                        withContext(Dispatchers.Main) {
                            _searchLog.value = (_searchLog.value ?: "") + "\n$line"
                        }
                    }

                    for (iteration in 1..config.maxIterations) {
                        if (overallSufficient) break
                        appendLog("\n[Iteration $iteration / ${config.maxIterations}]")

                        // ── Step 1: Generate optimized search query ──────────────────────
                        withContext(Dispatchers.Main) {
                            _searchStatus.value = if (iteration == 1) "Forging search query…"
                                                  else "Iteration $iteration: refining search query…"
                        }
                        appendLog("Step 1 — Generating search query…")
                        val optimizedQuery = runCatching {
                            llmService.generateOptimizedQuery(query, accumulatedGapContext)
                        }.getOrNull() ?: query
                        queriesUsed.add(optimizedQuery)
                        appendLog("  Query: \"$optimizedQuery\"")

                        // ── Step 2: Fetch 10 results (metadata only) ────────────────────
                        withContext(Dispatchers.Main) { _searchStatus.value = "Searching: \"$optimizedQuery\"…" }
                        appendLog("Step 2 — Searching DuckDuckGo…")
                        val results = runCatching {
                            webSearchService.searchMetadataOnly(optimizedQuery, 10)
                        }.getOrNull()
                        if (results == null) { appendLog("  Search failed — stopping."); break }
                        if (results.isEmpty()) { appendLog("  No results found — stopping."); break }
                        appendLog("  ${results.size} results found")

                        val resultsSummary = results.mapIndexed { i, r ->
                            "${i + 1}. ${r.title}\n   ${r.snippet}"
                        }.joinToString("\n")

                        // ── Step 3a: Score all 10 results ───────────────────────────────
                        withContext(Dispatchers.Main) { _searchStatus.value = "Scoring ${results.size} results…" }
                        appendLog("Step 3a — Scoring relevance (title + snippet)…")
                        val scoredResults = runCatching {
                            llmService.scoreResults(resultsSummary, query)
                        }.getOrElse { results.indices.map { Pair(it, 5) } }

                        // Fill in any missing results at score 0 so all 10 are covered
                        val allScored = run {
                            val seen = scoredResults.map { it.first }.toSet()
                            val missing = results.indices.filter { it !in seen }.map { Pair(it, 0) }
                            (scoredResults + missing)
                        }
                        allScored.forEachIndexed { rank, (idx, score) ->
                            val title = results.getOrNull(idx)?.title?.take(70) ?: "?"
                            appendLog("  #${rank + 1} [$score/10] $title")
                        }

                        // ── Step 4+5: Read pages, optionally check sufficiency after each ──
                        val label45 = if (config.checkSufficiency) "Step 4+5 — Reading pages & checking sufficiency…" else "Step 4 — Reading top ${config.maxPages} pages…"
                        appendLog(label45)
                        var pagesReadWithContent = 0
                        for ((idx, relevanceScore) in allScored.take(config.maxPages)) {
                            if (overallSufficient) break
                            val result = results.getOrNull(idx) ?: continue
                            val url = result.realUrl
                            if (url == null) {
                                appendLog("  [${result.title.take(50)}] — no URL, skipped")
                                continue
                            }
                            val displayHost = result.displayUrl.ifBlank { result.title }.take(50)
                            withContext(Dispatchers.Main) { _searchStatus.value = "Reading $displayHost…" }
                            appendLog("  Reading [$relevanceScore/10]: $displayHost")
                            val fullContent = runCatching {
                                webSearchService.fetchFullPage(url)
                            }.getOrElse { "" }
                            if (fullContent.isBlank()) {
                                appendLog("    -> Could not fetch page")
                                continue
                            }
                            appendLog("    -> ${fullContent.length} chars — extracting…")
                            withContext(Dispatchers.Main) { _searchStatus.value = "Extracting from $displayHost…" }
                            val extracted = runCatching {
                                llmService.extractRelevantInfo(fullContent, optimizedQuery, query)
                            }.getOrNull()
                            if (!extracted.isNullOrBlank()) {
                                allFindings.add(Triple(optimizedQuery, result.title, extracted))
                                pagesReadWithContent++
                                extracted.lines().forEach { appendLog("      $it") }
                            } else {
                                appendLog("    -> Nothing relevant found")
                                continue
                            }

                            // Step 5: sufficiency check (only when enabled and min pages threshold met)
                            if (config.checkSufficiency && pagesReadWithContent >= config.minPagesBeforeCheck) {
                                withContext(Dispatchers.Main) { _searchStatus.value = "Evaluating sufficiency…" }
                                val gathered = allFindings.joinToString("\n") { "• ${it.second}: ${it.third}" }
                                val (sufficient, _) = runCatching {
                                    llmService.evaluateSufficiency(gathered, query)
                                }.getOrElse { Pair(false, 0) }
                                appendLog("    -> Sufficiency: ${if (sufficient) "SUFFICIENT" else "not yet"}")
                                if (sufficient) {
                                    overallSufficient = true
                                    break
                                }
                            }
                        }

                        if (overallSufficient) break

                        // ── Step 3b: Gap analysis (only if looping with sufficiency check) ──
                        if (config.checkSufficiency && iteration < config.maxIterations && allFindings.isNotEmpty()) {
                            withContext(Dispatchers.Main) { _searchStatus.value = "Analyzing gaps…" }
                            appendLog("Step 3b — Gap analysis on extracted content…")
                            val findingsText = allFindings.joinToString("\n") { "• ${it.second}: ${it.third}" }
                            val gapAnalysis = runCatching {
                                llmService.analyzeGaps(findingsText, query, accumulatedGapContext)
                            }.getOrNull()
                            if (!gapAnalysis.isNullOrBlank()) {
                                appendLog("  Gap: ${gapAnalysis.take(200)}${if (gapAnalysis.length > 200) "…" else ""}")
                                accumulatedGapContext = buildString {
                                    if (!accumulatedGapContext.isNullOrBlank()) appendLine(accumulatedGapContext)
                                    appendLine(gapAnalysis)
                                }.trim()
                            } else {
                                appendLog("  No gap identified — stopping.")
                                break
                            }
                        }
                    }

                    appendLog(if (allFindings.isNotEmpty()) "\n${allFindings.size} findings gathered." else "\nNo findings gathered.")

                    // Save the live log as a permanent message in the conversation
                    withContext(Dispatchers.Main) {
                        val finalLog = _searchLog.value
                        if (!finalLog.isNullOrBlank()) {
                            conv.messages.add(Message(
                                role = MessageRole.USER,
                                content = finalLog,
                                isSearchLog = true
                            ))
                        }
                        _searchLog.value = null
                        _isSearching.value = false
                        _searchStatus.value = null
                    }

                    if (allFindings.isEmpty()) {
                        // Nothing found — fall back to a plain sendMessage
                        withContext(Dispatchers.Main) {
                            _currentConversation.value = conv.copy(messages = conv.messages)
                            sendMessage(query)
                        }
                        return@launch
                    }

                    // Stream the final answer directly from research notes (no intermediate synthesis call)
                    val userMsg = Message(role = MessageRole.USER, content = query)
                    val assistantMessage = Message(
                        role = MessageRole.ASSISTANT,
                        content = "",
                        status = MessageStatus.STREAMING
                    )
                    withContext(Dispatchers.Main) {
                        conv.messages.add(userMsg)
                        conv.messages.add(assistantMessage)
                        _streamingText.value = ""
                        _isGenerating.value = true
                        _currentConversation.value = conv.copy(messages = conv.messages)
                    }

                    llmService.answerFromResearch(
                        question = query,
                        findings = allFindings,
                        maxFindingsChars = (contextLength * 0.6 * 4).toInt(),
                        onPartial = { partial ->
                            viewModelScope.launch(Dispatchers.Main) {
                                if (_isGenerating.value) {
                                    _streamingText.value = partial
                                }
                            }
                        },
                        onDone = {
                            viewModelScope.launch(Dispatchers.Main) {
                                if (assistantMessage.status != MessageStatus.STREAMING) return@launch
                                val finalText = _streamingText.value
                                _streamingText.value = ""
                                _isGenerating.value = false
                                assistantMessage.content = finalText.ifBlank { "*(no response — tap ↺ to retry)*" }
                                assistantMessage.status = if (finalText.isBlank()) MessageStatus.ERROR else MessageStatus.DONE
                                _currentConversation.value = conv.copy(messages = conv.messages)
                                persistCurrentConversation()
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
                            }
                        }
                    )
                    return@launch
                }

                // ── Level 2 (Normal): standard search pipeline ───────────────
                var results = webSearchService.search(query, 1, contextLength)
                var synthesis: String? = null

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
