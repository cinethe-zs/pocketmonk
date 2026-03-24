package app.pocketmonk.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.pocketmonk.model.Message
import app.pocketmonk.model.MessageRole
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import com.google.ai.edge.litertlm.Message as LitMessage

class LlmService(private val context: Context) {

    private var engine: Engine? = null
    var isReady = false
        private set
    var isInferring = false
        private set

    /** Tracked only for cancellation — either a Conversation or Session. */
    private var currentConversation: com.google.ai.edge.litertlm.Conversation? = null
    private var currentSession: Session? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun initialize(
        modelPath: String,
        maxTokens: Int = 2048,
    ) = withContext(Dispatchers.IO) {
        dispose()

        val crashPrefs = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
        crashPrefs.edit()
            .putBoolean("model_loading", true)
            .putString("model_loading_path", modelPath)
            .commit()

        try {
            val e = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    maxNumTokens = maxTokens,
                    cacheDir = context.cacheDir.absolutePath,
                )
            )
            e.initialize()
            engine = e
            isReady = true
        } finally {
            crashPrefs.edit().remove("model_loading").remove("model_loading_path").apply()
        }
    }

    /**
     * Streaming chat inference using the Conversation API (correct path for multimodal).
     * History is injected via ConversationConfig.initialMessages so the library
     * applies the proper prompt template (including image tokens for Gemma 3n).
     */
    fun chat(
        history: List<Message>,
        systemPrompt: String?,
        contextSummary: String?,
        temperature: Float = 1.0f,
        onPartial: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val eng = engine
        if (eng == null || !isReady) { onError("Model not initialized"); return }
        if (isInferring) { onError("Already inferring"); return }
        isInferring = true

        val onErrorOuter = onError

        // Grab and clear the stale conversation reference before starting the thread
        // so that cancel() called during setup sees null and won't try to double-close.
        val staleConv = currentConversation
        currentConversation = null

        // Run the entire setup (close + createConversation) on a background thread.
        // This allows Thread.sleep() retries without blocking the main thread, and
        // avoids the FAILED_PRECONDITION race where nativeDeleteConversation's async
        // cleanup hasn't finished before createConversation is called.
        Thread {
            try {
                // Close previous conversation; native delete is async so we retry below.
                try { staleConv?.close() } catch (_: Exception) {}

                val effectiveTemp = if (temperature < 0.1f) 1.0f else temperature
                val sysText = buildString {
                    if (!systemPrompt.isNullOrBlank()) append(systemPrompt)
                    else append("You are PocketMonk, a helpful private AI assistant running entirely on-device.")
                    if (!contextSummary.isNullOrBlank()) {
                        append("\n\n[Summary of earlier conversation: $contextSummary]")
                    }
                }
                val initialMsgs = buildLitMessages(history.dropLast(1))
                val config = ConversationConfig(
                    systemInstruction = Contents.of(sysText),
                    initialMessages = initialMsgs,
                    samplerConfig = SamplerConfig(
                        topK = 40, topP = 1.0, temperature = effectiveTemp.toDouble()
                    ),
                )

                // Retry createConversation with backoff — the native slot may not be free yet.
                var conversation: com.google.ai.edge.litertlm.Conversation? = null
                var lastConvEx: Exception? = null
                for (attempt in 0..4) {
                    if (attempt > 0) Thread.sleep(200L * attempt)
                    try {
                        conversation = eng.createConversation(config)
                        break
                    } catch (e: Exception) {
                        lastConvEx = e
                        if (e.message?.contains("FAILED_PRECONDITION", ignoreCase = true) != true) break
                    }
                }

                if (conversation == null) {
                    val msg = lastConvEx?.message ?: "Failed to create conversation"
                    mainHandler.post { isInferring = false; onErrorOuter(msg) }
                    return@Thread
                }

                // If cancel() was called while we were setting up, abort cleanly.
                if (!isInferring) {
                    try { conversation.close() } catch (_: Exception) {}
                    return@Thread
                }
                currentConversation = conversation

                val contentList = listOf(Content.Text(history.last().content))
                val watchdog = Runnable {
                    if (isInferring) {
                        cancel()
                        mainHandler.post { onErrorOuter("Generation timed out — context may be too long. Tap ↺ to retry.") }
                    }
                }
                mainHandler.postDelayed(watchdog, 45_000)

                val accumulated = StringBuilder()
                conversation.sendMessageAsync(
                    Contents.of(contentList),
                    object : MessageCallback {
                        override fun onMessage(message: LitMessage) {
                            if (!isInferring) return
                            accumulated.append(message.toString())
                            mainHandler.removeCallbacks(watchdog)
                            mainHandler.post { onPartial(accumulated.toString().cleaned()) }
                        }

                        override fun onDone() {
                            mainHandler.removeCallbacks(watchdog)
                            mainHandler.post {
                                isInferring = false
                                // Keep conversation alive — close it at the start of the next
                                // chat() call once the native async delete has had time to finish.
                                onDone()
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            mainHandler.removeCallbacks(watchdog)
                            mainHandler.post {
                                isInferring = false
                                if (throwable is CancellationException) onDone()
                                else onErrorOuter(throwable.message ?: "Unknown error during inference")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                mainHandler.post {
                    isInferring = false
                    currentConversation = null
                    onErrorOuter(e.message ?: "Unknown error during inference")
                }
            }
        }.start()
    }

    // ──────────────────────────────────────────────────────────
    // All functions below use the lower-level Session API for
    // synchronous or raw-prompt inference (text-only, no history).
    // ──────────────────────────────────────────────────────────

    suspend fun summarizeHistory(messages: List<Message>, existingSummary: String? = null): String? = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext null
        val textToSummarize = messages
            .filter { !it.isSummary && !it.isArchived }
            .joinToString("\n") { msg ->
                val roleLabel = if (msg.role == MessageRole.USER) "User" else "Assistant"
                "$roleLabel: ${msg.content}"
            }
        val prompt = buildString {
            append("<start_of_turn>user\n")
            if (existingSummary != null) {
                append("Earlier summary: $existingSummary\n\n")
                append("Extend it to cover the new exchanges below. Reply with one concise paragraph only:\n\n")
            } else {
                append("Summarize this conversation in 1-2 sentences. Reply with the summary only:\n\n")
            }
            append(textToSummarize)
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        return@withContext runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim().cleaned().ifBlank { null } }
    }

    suspend fun generateSearchQueries(question: String): List<String> = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext emptyList()
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Generate specific web search queries to thoroughly research this question. ")
            append("Output ONLY the search queries, one per line. No numbering, no explanation, no bullet points. ")
            append("Generate between 2 and 5 queries based on the complexity of the question:\n\n")
            append(question)
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        val raw = runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim() } ?: return@withContext emptyList()
        raw.lines()
            .map { it.trim().trimStart('-', '*', '•', '·').trimStart { c -> c.isDigit() || c == '.' || c == ')' || c == ' ' }.trim() }
            .filter { it.length > 4 && !it.equals("SKIP", ignoreCase = true) }
            .take(5)
    }

    suspend fun extractRelevantInfo(
        pageContent: String,
        subQuery: String,
        originalQuestion: String
    ): String? = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext null
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Extract only the facts relevant to answering: \"$originalQuestion\"\n")
            append("Research angle: \"$subQuery\"\n\n")
            append("Content:\n${pageContent.take(8000)}\n\n")
            append("Reply with up to 8 concise bullet points of relevant facts only. If nothing is relevant, reply exactly: SKIP")
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        val result = runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim().cleaned() } ?: return@withContext null
        if (result.isBlank() || result.equals("SKIP", ignoreCase = true)) null else result
    }

    suspend fun synthesizeResearch(
        question: String,
        findings: List<Triple<String, String, String>>
    ): String? = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext null
        val notesText = findings
            .groupBy { it.first }
            .entries.joinToString("\n\n") { (q, items) ->
                "Research area: $q\n" + items.joinToString("\n") { "  • ${it.second}: ${it.third}" }
            }
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Based on the following research notes, write a thorough synthesis that answers: \"$question\"\n\n")
            append(notesText)
            append("\n\nProvide a comprehensive, well-organized synthesis of all findings.")
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim().ifBlank { null } }
    }

    suspend fun compressText(text: String, query: String): String? = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext null
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Summarize the following content in 2-3 sentences. Focus only on what is relevant to: \"$query\"\n\n")
            append(text.take(3000))
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim().ifBlank { null } }
    }

    suspend fun generateTitleFromContext(ctx: String): String? = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext null
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Give a 4-6 word title for this conversation. Reply with only the title:\n\n")
            append(ctx.take(800))
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        runSession(eng) {
            it.generateContent(listOf(InputData.Text(prompt))).trim()
                .replace("\"", "").replace("'", "").take(60).ifBlank { null }
        }
    }

    suspend fun generateTitle(userMsg: String, assistantMsg: String): String? = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext null
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Give a 4-6 word title for this conversation. Reply with only the title:\n\n")
            append("User: ${userMsg.take(200)}\n")
            append("Assistant: ${assistantMsg.take(200)}")
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        runSession(eng) {
            it.generateContent(listOf(InputData.Text(prompt))).trim()
                .replace("\"", "").replace("'", "").take(60).ifBlank { null }
        }
    }

    suspend fun generateOptimizedQuery(question: String, gapContext: String? = null): String? =
        withContext(Dispatchers.IO) {
            val eng = engine ?: return@withContext null
            val prompt = buildString {
                append("<start_of_turn>user\n")
                if (gapContext.isNullOrBlank()) {
                    append("Rewrite this as a short Google search query (5-8 words max):\n$question\n")
                } else {
                    append("Question: $question\nPrevious searches missed: $gapContext\n")
                    append("Write a short Google search query (5-8 words) targeting the missing information.\n")
                }
                append("Reply with only the search query, no explanation, no quotes.")
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            runSession(eng) { session ->
                session.generateContent(listOf(InputData.Text(prompt))).trim()
                    .lines().firstOrNull { it.isNotBlank() }
                    ?.trimStart { c -> c.isDigit() || c == '.' || c == ')' || c == '-' || c == '*' || c == '•' || c == ' ' }
                    ?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
                    ?.trim()?.ifBlank { null }
            }
        }

    suspend fun scoreResults(resultsSummary: String, question: String): List<Pair<Int, Int>> =
        withContext(Dispatchers.IO) {
            val eng = engine ?: return@withContext emptyList()
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Score each search result 0-10 for relevance to: \"$question\"\n10 = highly relevant, 0 = not relevant.\n\n")
                append(resultsSummary)
                append("\n\nReply in this exact format, one per line:\n1: 8\n2: 3\n...")
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            val raw = runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim() } ?: return@withContext emptyList()
            Regex("(\\d+):\\s*(\\d+)").findAll(raw)
                .map { Pair(it.groupValues[1].toInt() - 1, it.groupValues[2].toInt().coerceIn(0, 10)) }
                .filter { it.first >= 0 }.distinctBy { it.first }.sortedByDescending { it.second }.toList()
        }

    suspend fun analyzeGaps(
        extractedFindings: String,
        question: String,
        previousGapContext: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext null
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Question: \"$question\"\n\n")
            if (!previousGapContext.isNullOrBlank()) append("Previously identified gaps:\n$previousGapContext\n\n")
            append("Based on the following extracted information, what is still missing to fully answer the question?\n\n")
            append(extractedFindings.take(4000))
            append("\n\nReply with 2-4 sentences describing what specific information is still missing.")
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim().cleaned().ifBlank { null } }
    }

    suspend fun evaluateSufficiency(gatheredInfo: String, question: String): Pair<Boolean, Int> =
        withContext(Dispatchers.IO) {
            val eng = engine ?: return@withContext Pair(false, 0)
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Question: \"$question\"\n\nInformation gathered:\n")
                append(gatheredInfo.take(4000))
                append("\n\nDoes the information above contain enough specific facts to fully answer the question?\n")
                append("Be strict: reply YES only if the information directly covers all main aspects of the question.\n")
                append("Reply with only YES or NO.")
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            val raw = runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim().uppercase() }
                ?: return@withContext Pair(false, 0)
            val sufficient = raw.startsWith("YES")
            Pair(sufficient, if (sufficient) 10 else 0)
        }

    fun answerFromResearch(
        question: String,
        findings: List<Triple<String, String, String>>,
        onPartial: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val eng = engine
        if (eng == null || !isReady) { onError("Model not initialized"); return }
        if (isInferring) { onError("Already inferring"); return }
        isInferring = true

        val onErrorOuter = onError
        val prompt = buildString {
            append("<start_of_turn>user\n")
            findings.groupBy { it.first }.entries.forEach { (q, items) ->
                append("[Web search results for \"$q\":]\n")
                items.forEach { (_, title, info) -> append("  • $title: $info\n") }
                append("\n")
            }
            append("Based on the previous research notes, provide a comprehensive, well-organized answer to this: \"$question\"\n")
            append("<end_of_turn>\n<start_of_turn>model\n")
        }

        try {
            val session = eng.createSession(SessionConfig(SamplerConfig(topK = 40, topP = 1.0, temperature = 1.0)))
            currentSession = session

            val watchdog = Runnable {
                if (isInferring) {
                    cancel()
                    mainHandler.post { onErrorOuter("Generation timed out — context may be too long. Tap ↺ to retry.") }
                }
            }
            mainHandler.postDelayed(watchdog, 45_000)

            val accumulated = StringBuilder()
            Thread {
                try {
                    session.generateContentStream(listOf(InputData.Text(prompt)), object : ResponseCallback {
                        override fun onNext(response: String) {
                            if (!isInferring) return
                            accumulated.append(response)
                            mainHandler.removeCallbacks(watchdog)
                            mainHandler.post { onPartial(accumulated.toString().cleaned()) }
                        }
                        override fun onDone() {
                            mainHandler.removeCallbacks(watchdog)
                            mainHandler.post {
                                isInferring = false; currentSession = null
                                try { session.close() } catch (_: Exception) {}
                                onDone()
                            }
                        }
                        override fun onError(throwable: Throwable) {
                            mainHandler.removeCallbacks(watchdog)
                            mainHandler.post {
                                isInferring = false; currentSession = null
                                try { session.close() } catch (_: Exception) {}
                                if (throwable is CancellationException) onDone()
                                else onErrorOuter(throwable.message ?: "Unknown error")
                            }
                        }
                    })
                } catch (e: Exception) {
                    mainHandler.post {
                        isInferring = false; currentSession = null
                        try { session.close() } catch (_: Exception) {}
                        onErrorOuter(e.message ?: "Unknown error")
                    }
                }
            }.start()

        } catch (e: Exception) {
            isInferring = false; currentSession = null
            onErrorOuter(e.message ?: "Unknown error during inference")
        }
    }

    fun cancel() {
        isInferring = false
        val conv = currentConversation; currentConversation = null
        try { conv?.cancelProcess() } catch (_: Exception) {}
        try { conv?.close() } catch (_: Exception) {}
        val sess = currentSession; currentSession = null
        try { sess?.cancelProcess() } catch (_: Exception) {}
        try { sess?.close() } catch (_: Exception) {}
    }

    fun dispose() {
        cancel()
        isReady = false
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }

    // ── Helpers ────────────────────────────────────────────────

    /** Runs [block] on a fresh Session, closing it afterward. Returns null on any exception. */
    private fun <T> runSession(eng: Engine, block: (Session) -> T): T? {
        var session: Session? = null
        return try {
            session = eng.createSession()
            val result = block(session)
            try { session.close() } catch (_: Exception) {}
            result
        } catch (e: Exception) {
            // Always close the session, even when block() throws — otherwise the
            // native session leaks and all subsequent createConversation() calls fail
            // with FAILED_PRECONDITION "a session already exists".
            try { session?.close() } catch (_: Exception) {}
            null
        }
    }

    /**
     * Converts PocketMonk messages to litertlm Messages for use as Conversation.initialMessages.
     * Applies the same search-result merging as formatPrompt().
     */
    private fun buildLitMessages(messages: List<Message>): List<LitMessage> {
        val result = mutableListOf<LitMessage>()
        val active = messages.filter { !it.isSummary && !it.isSearchLog }
        var i = 0
        while (i < active.size) {
            val m = active[i]
            when {
                m.isSearchResult -> {
                    val next = active.getOrNull(i + 1)
                    if (next != null && next.role == MessageRole.USER) {
                        result.add(LitMessage.user("${m.content}\n\nUser question: ${next.content}"))
                        i += 2
                    } else {
                        result.add(LitMessage.user(m.content))
                        i++
                    }
                }
                m.role == MessageRole.USER -> {
                    result.add(LitMessage.user(m.content))
                    i++
                }
                else -> {
                    result.add(LitMessage.model(m.content))
                    i++
                }
            }
        }
        return result
    }

    private fun String.cleaned() = replace("\\n", "\n").replace("\\t", "\t")
}
