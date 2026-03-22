package app.pocketmonk.service

import android.content.Context
import app.pocketmonk.model.Message
import app.pocketmonk.model.MessageRole
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlmService(private val context: Context) {

    private var llm: LlmInference? = null
    var isReady = false
        private set
    var isInferring = false
        private set

    // Mutable callbacks updated before each generateResponseAsync call
    private var onPartialCallback: ((String) -> Unit)? = null
    private var onDoneCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    suspend fun initialize(
        modelPath: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f
    ) = withContext(Dispatchers.IO) {
        dispose()
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setTopK(40)
            .setTemperature(temperature)
            .setRandomSeed(101)
            .setResultListener { partialResult, done ->
                onPartialCallback?.invoke(partialResult ?: "")
                if (done) {
                    isInferring = false
                    onDoneCallback?.invoke()
                }
            }
            .setErrorListener { e ->
                isInferring = false
                onErrorCallback?.invoke(e.message ?: "Unknown inference error")
            }
            .build()
        llm = LlmInference.createFromOptions(context, options)
        isReady = true
    }

    fun chat(
        history: List<Message>,
        systemPrompt: String?,
        contextSummary: String?,
        onPartial: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val engine = llm
        if (engine == null || !isReady) {
            onError("Model not initialized")
            return
        }
        if (isInferring) {
            onError("Already inferring")
            return
        }
        onPartialCallback = onPartial
        onDoneCallback = onDone
        onErrorCallback = onError
        isInferring = true
        val prompt = formatPrompt(history, systemPrompt, contextSummary)
        try {
            engine.generateResponseAsync(prompt)
        } catch (e: Exception) {
            isInferring = false
            onPartialCallback = null
            onDoneCallback = null
            onErrorCallback = null
            onError(e.message ?: "Unknown error during inference")
        }
    }

    suspend fun summarizeHistory(messages: List<Message>): String? = withContext(Dispatchers.IO) {
        val engine = llm ?: return@withContext null
        val textToSummarize = messages
            .filter { !it.isSummary }
            .joinToString("\n") { msg ->
                val roleLabel = if (msg.role == MessageRole.USER) "User" else "Assistant"
                "$roleLabel: ${msg.content}"
            }
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Summarize the following conversation in 2-3 sentences, preserving key facts:\n\n")
            append(textToSummarize)
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
        return@withContext try {
            engine.generateResponse(prompt)?.trim()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun generateTitle(userMsg: String, assistantMsg: String): String? = withContext(Dispatchers.IO) {
        val engine = llm ?: return@withContext null
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Give a 4-6 word title for this conversation. Reply with only the title:\n\n")
            append("User: ${userMsg.take(200)}\n")
            append("Assistant: ${assistantMsg.take(200)}")
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
        return@withContext try {
            engine.generateResponse(prompt)?.trim()
                ?.replace("\"", "")?.replace("'", "")
                ?.take(60)
        } catch (e: Exception) {
            null
        }
    }

    fun cancel() {
        isInferring = false
        onPartialCallback = null
        onDoneCallback = null
        onErrorCallback = null
    }

    fun dispose() {
        cancel()
        isReady = false
        try { llm?.close() } catch (_: Exception) {}
        llm = null
    }

    private fun formatPrompt(
        messages: List<Message>,
        systemPrompt: String?,
        contextSummary: String?
    ): String = buildString {
        val sys = buildString {
            if (!systemPrompt.isNullOrBlank()) append(systemPrompt)
            else append("You are PocketMonk, a helpful private AI assistant running entirely on-device with no internet access.")
            if (!contextSummary.isNullOrBlank()) {
                append("\n\n[Summary of earlier conversation: $contextSummary]")
            }
        }
        append("<start_of_turn>user\n$sys<end_of_turn>\n")
        append("<start_of_turn>model\nUnderstood.<end_of_turn>\n")
        for (m in messages.filter { !it.isSummary }) {
            val role = if (m.role == MessageRole.USER) "user" else "model"
            append("<start_of_turn>$role\n${m.content}<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }
}
