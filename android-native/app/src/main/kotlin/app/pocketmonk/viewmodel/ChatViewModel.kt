package app.pocketmonk.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pocketmonk.model.Conversation
import app.pocketmonk.model.Message
import app.pocketmonk.model.MessageRole
import app.pocketmonk.model.MessageStatus
import app.pocketmonk.repository.ConversationRepository
import app.pocketmonk.service.LlmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ConversationRepository(application)
    val llmService = LlmService(application)

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

    val contextLength: Int = 1024

    val estimatedTokenCount: Int
        get() = _currentConversation.value?.messages
            ?.filter { !it.isSummary }
            ?.sumOf { it.content.length }
            ?.div(4) ?: 0

    private var currentModelPath: String? = null

    init {
        viewModelScope.launch {
            _conversations.value = repo.loadAll()
        }
    }

    fun initModel(modelPath: String) {
        currentModelPath = modelPath
        viewModelScope.launch {
            _modelReady.value = false
            _errorMessage.value = null
            try {
                llmService.initialize(modelPath)
                _modelReady.value = true
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load model: ${e.message}"
                _modelReady.value = false
            }
        }
    }

    fun sendMessage(text: String) {
        val conv = _currentConversation.value ?: run {
            _errorMessage.value = "No active conversation"
            return
        }
        if (!_modelReady.value || _isGenerating.value || _isCompressing.value) return

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
        _isGenerating.value = true
        _errorMessage.value = null

        val contextSummary = conv.messages.firstOrNull { it.isSummary }?.content
        val historyForPrompt = conv.messages.filter { !it.isSummary && it.status != MessageStatus.STREAMING }

        llmService.chat(
            history = historyForPrompt,
            systemPrompt = conv.systemPrompt,
            contextSummary = contextSummary,
            onPartial = { partial ->
                viewModelScope.launch(Dispatchers.Main) {
                    if (_isGenerating.value) {
                        assistantMessage.content = partial
                        assistantMessage.status = MessageStatus.STREAMING
                        _currentConversation.value = conv.copy(messages = conv.messages)
                    }
                }
            },
            onDone = {
                viewModelScope.launch(Dispatchers.Main) {
                    assistantMessage.status = MessageStatus.DONE
                    _isGenerating.value = false
                    _currentConversation.value = conv.copy(messages = conv.messages)

                    // Auto-title after first exchange
                    if (conv.title == "New Conversation" && conv.messages.size == 2) {
                        autoGenerateTitle(conv, userMessage.content, assistantMessage.content)
                    }

                    // Auto-compress check
                    val ratio = estimatedTokenCount.toFloat() / contextLength
                    if (ratio > 0.85f && conv.messages.count { !it.isSummary } > 6) {
                        compressContext()
                    } else {
                        persistCurrentConversation()
                    }
                }
            },
            onError = { error ->
                viewModelScope.launch(Dispatchers.Main) {
                    assistantMessage.status = MessageStatus.ERROR
                    assistantMessage.content = "Error: $error"
                    _isGenerating.value = false
                    _errorMessage.value = error
                    _currentConversation.value = conv.copy(messages = conv.messages)
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
            lastMsg.status = MessageStatus.DONE
            _currentConversation.value = conv.copy(messages = conv.messages)
        }
        persistCurrentConversation()
    }

    fun regenerateLastResponse() {
        val conv = _currentConversation.value ?: return
        if (_isGenerating.value || _isCompressing.value) return

        // Remove last assistant message
        val lastAssistant = conv.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        if (lastAssistant != null) {
            conv.messages.remove(lastAssistant)
            _currentConversation.value = conv.copy(messages = conv.messages)
        }

        // Find last user message to re-send
        val lastUserMsg = conv.messages.lastOrNull { it.role == MessageRole.USER }?.content ?: return

        // Remove last user message too so sendMessage can re-add it
        val lastUser = conv.messages.lastOrNull { it.role == MessageRole.USER }
        if (lastUser != null) {
            conv.messages.remove(lastUser)
            _currentConversation.value = conv.copy(messages = conv.messages)
        }

        sendMessage(lastUserMsg)
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

    fun compressContext(keepLast: Int = 4) {
        val conv = _currentConversation.value ?: return
        if (_isCompressing.value) return

        val nonSummaryMessages = conv.messages.filter { !it.isSummary }
        if (nonSummaryMessages.size <= keepLast) return

        val toSummarize = nonSummaryMessages.dropLast(keepLast)
        val toKeep = nonSummaryMessages.takeLast(keepLast)

        _isCompressing.value = true

        viewModelScope.launch {
            val summary = llmService.summarizeHistory(toSummarize)
            withContext(Dispatchers.Main) {
                if (summary != null) {
                    val summaryMessage = Message(
                        role = MessageRole.ASSISTANT,
                        content = summary,
                        isSummary = true,
                        status = MessageStatus.DONE
                    )
                    conv.messages.clear()
                    conv.messages.add(summaryMessage)
                    conv.messages.addAll(toKeep)
                    _currentConversation.value = conv.copy(messages = conv.messages)
                }
                _isCompressing.value = false
                persistCurrentConversation()
            }
        }
    }

    fun newConversation() {
        val modelPath = currentModelPath ?: return
        val conv = Conversation(
            title = "New Conversation",
            modelPath = modelPath
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

    fun dismissError() {
        _errorMessage.value = null
    }

    private fun autoGenerateTitle(conv: Conversation, userMsg: String, assistantMsg: String) {
        viewModelScope.launch {
            val title = llmService.generateTitle(userMsg, assistantMsg)
            if (!title.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    conv.title = title
                    _currentConversation.value = conv.copy(title = title)
                    refreshConversationList(conv)
                    persistCurrentConversation()
                }
            }
        }
    }

    private fun refreshConversationList(conv: Conversation) {
        val list = _conversations.value.toMutableList()
        val idx = list.indexOfFirst { it.id == conv.id }
        if (idx >= 0) {
            list[idx] = conv
            _conversations.value = list
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
