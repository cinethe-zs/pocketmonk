package app.pocketmonk.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.pocketmonk.model.Message
import app.pocketmonk.model.MessageRole
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlmService(private val context: Context) {

    private var llm: LlmInference? = null
    var isReady = false
        private set
    var isInferring = false
        private set

    private var currentSession: LlmInferenceSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun initialize(
        modelPath: String,
        maxTokens: Int = 2048,
    ) = withContext(Dispatchers.IO) {
        dispose()

        // Crash guard: if the process dies inside createFromOptions (native SIGABRT),
        // PocketMonkApp.onCreate() will see this flag on the next launch.
        val crashPrefs = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
        crashPrefs.edit()
            .putBoolean("model_loading", true)
            .putString("model_loading_path", modelPath)
            .commit()

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(40)
                // Force CPU backend — v0.10.13-0.10.14 had a known GPU memory allocation
                // bug (SIGABRT in LlmGpuCalculator). CPU is stable on all devices.
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            llm = LlmInference.createFromOptions(context, options)
            isReady = true
        } finally {
            // Clear guard on success OR on catchable JVM exception (not native crash).
            crashPrefs.edit().remove("model_loading").remove("model_loading_path").apply()
        }
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
        isInferring = true
        val prompt = formatPrompt(history, systemPrompt, contextSummary)

        try {
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(1.0f)
                .build()
            val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
            currentSession = session

            session.addQueryChunk(prompt)

            val accumulated = StringBuilder()
            session.generateResponseAsync { partial, done ->
                if (!isInferring) return@generateResponseAsync
                if (!partial.isNullOrEmpty()) {
                    accumulated.append(partial)
                    val snapshot = accumulated.toString()
                    mainHandler.post { onPartial(snapshot) }
                }
                if (done) {
                    mainHandler.post {
                        isInferring = false
                        currentSession = null
                        try { session.close() } catch (_: Exception) {}
                        onDone()
                    }
                }
            }
        } catch (e: Exception) {
            isInferring = false
            currentSession = null
            onError(e.message ?: "Unknown error during inference")
        }
    }

    suspend fun summarizeHistory(messages: List<Message>, existingSummary: String? = null): String? = withContext(Dispatchers.IO) {
        val engine = llm ?: return@withContext null
        val textToSummarize = messages
            .filter { !it.isSummary && !it.isArchived }
            .joinToString("\n") { msg ->
                val roleLabel = if (msg.role == MessageRole.USER) "User" else "Assistant"
                "$roleLabel: ${msg.content}"
            }
        val prompt = buildString {
            append("<start_of_turn>user\n")
            if (existingSummary != null) {
                append("Here is a summary of earlier conversation:\n$existingSummary\n\n")
                append("Now extend the summary to include the following new exchanges, in 2-4 sentences total, preserving key facts:\n\n")
            } else {
                append("Summarize the following conversation in 2-3 sentences, preserving key facts:\n\n")
            }
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

    suspend fun generateTitleFromContext(context: String): String? = withContext(Dispatchers.IO) {
        val engine = llm ?: return@withContext null
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Give a 4-6 word title for this conversation. Reply with only the title:\n\n")
            append(context.take(800))
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
        val session = currentSession
        isInferring = false
        currentSession = null
        try { session?.cancelGenerateResponseAsync() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
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
