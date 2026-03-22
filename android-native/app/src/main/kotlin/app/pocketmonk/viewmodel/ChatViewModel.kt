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

        // Find and remove last active user message
        val lastUser = conv.messages.lastOrNull { it.role == MessageRole.USER && !it.isArchived }
        val lastUserMsg = lastUser?.content ?: return
        conv.messages.remove(lastUser)
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

    fun newConversation(modelPath: String? = null, contextSize: Int? = null) {
        val path = modelPath ?: currentModelPath ?: return
        val size = contextSize ?: currentContextSize
        // Re-init model if path or context size differs from what's currently loaded
        if (path != currentModelPath || size != currentContextSize) {
            initModel(path, size)
        }
        val conv = Conversation(
            title = "New Conversation",
            modelPath = path,
            contextSize = size
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
