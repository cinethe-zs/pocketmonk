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
        temperature: Float = 1.0f,
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
            // Guard: Gson deserializes missing float fields as 0.0 — treat that as "not set" → 1.0
            val effectiveTemp = if (temperature < 0.1f) 1.0f else temperature
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(effectiveTemp)
                .build()
            val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
            currentSession = session

            session.addQueryChunk(prompt)

            // Watchdog: if no token arrives within 45 s, cancel and report error
            val watchdog = Runnable {
                if (isInferring) {
                    cancel()
                    mainHandler.post { onError("Generation timed out — context may be too long. Tap ↺ to retry.") }
                }
            }
            mainHandler.postDelayed(watchdog, 45_000)

            val accumulated = StringBuilder()
            session.generateResponseAsync { partial, done ->
                if (!isInferring) return@generateResponseAsync
                if (!partial.isNullOrEmpty()) {
                    accumulated.append(partial)
                    // Reset watchdog on each received token
                    mainHandler.removeCallbacks(watchdog)
                    val snapshot = accumulated.toString()
                    mainHandler.post { onPartial(snapshot) }
                }
                if (done) {
                    mainHandler.removeCallbacks(watchdog)
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
                append("Earlier summary: $existingSummary\n\n")
                append("Extend it to cover the new exchanges below. Reply with one concise paragraph only:\n\n")
            } else {
                append("Summarize this conversation in 1-2 sentences. Reply with the summary only:\n\n")
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

    /**
     * Breaks [question] into specific search queries.
     * Returns 2-5 queries the model decided are needed to research the question.
     */
    suspend fun generateSearchQueries(question: String): List<String> = withContext(Dispatchers.IO) {
        val engine = llm ?: return@withContext emptyList()
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Generate specific web search queries to thoroughly research this question. ")
            append("Output ONLY the search queries, one per line. No numbering, no explanation, no bullet points. ")
            append("Generate between 2 and 5 queries based on the complexity of the question:\n\n")
            append(question)
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        try {
            val raw = engine.generateResponse(prompt)?.trim() ?: return@withContext emptyList()
            raw.lines()
                .map { it.trim().trimStart('-', '*', '•', '·').trimStart { c -> c.isDigit() || c == '.' || c == ')' || c == ' ' }.trim() }
                .filter { it.length > 4 && !it.equals("SKIP", ignoreCase = true) }
                .take(5)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Extracts only the information relevant to [originalQuestion] from [pageContent],
     * with [subQuery] as the specific research angle for this page.
     * Returns null if the page contains nothing relevant.
     */
    suspend fun extractRelevantInfo(
        pageContent: String,
        subQuery: String,
        originalQuestion: String
    ): String? = withContext(Dispatchers.IO) {
        val engine = llm ?: return@withContext null
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Extract only the facts relevant to answering: \"$originalQuestion\"\n")
            append("Research angle: \"$subQuery\"\n\n")
            append("Content:\n${pageContent.take(8000)}\n\n")
            append("Reply with up to 8 concise bullet points of relevant facts only. If nothing is relevant, reply exactly: SKIP")
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        try {
            val result = engine.generateResponse(prompt)?.trim()
            if (result.isNullOrBlank() || result.equals("SKIP", ignoreCase = true)) null else result
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Synthesizes all research [findings] into a comprehensive context summary.
     * [findings] is a list of (subQuery, sourceTitle, extractedInfo).
     */
    suspend fun synthesizeResearch(
        question: String,
        findings: List<Triple<String, String, String>>
    ): String? = withContext(Dispatchers.IO) {
        val engine = llm ?: return@withContext null
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
        try {
            engine.generateResponse(prompt)?.trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compresses [text] into a 2-3 sentence summary focused on [query].
     * Used for Level 2/3 multi-stage web search compression.
     */
    suspend fun compressText(text: String, query: String): String? = withContext(Dispatchers.IO) {
        val engine = llm ?: return@withContext null
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Summarize the following content in 2-3 sentences. Focus only on what is relevant to: \"$query\"\n\n")
            append(text.take(3000))
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
        try {
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

    /**
     * Generates 3 diverse candidate search queries with explicit search syntax guidance.
     * If [gapContext] is provided, targets the missing information.
     */
    suspend fun generateQueryCandidates(question: String, gapContext: String? = null): List<String> =
        withContext(Dispatchers.IO) {
            val engine = llm ?: return@withContext emptyList()
            val prompt = buildString {
                append("<start_of_turn>user\n")
                if (gapContext.isNullOrBlank()) {
                    append("Generate 3 diverse, targeted web search queries to find information answering:\n$question\n\n")
                } else {
                    append("Question: $question\n\nPrevious searches missed: $gapContext\n\n")
                    append("Generate 3 web search queries targeting the missing information.\n\n")
                }
                append("Output ONLY the 3 queries, one per line, no numbering, no explanation.")
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            try {
                val raw = engine.generateResponse(prompt)?.trim() ?: return@withContext emptyList()
                raw.lines()
                    .map { it.trim().trimStart { c -> c.isDigit() || c == '.' || c == ')' || c == '-' || c == '*' || c == '•' || c == ' ' }.trim() }
                    .filter { it.length > 4 }
                    .take(5)
            } catch (e: Exception) { emptyList() }
        }

    /**
     * Picks the best query from [candidates] for answering [question].
     * Returns the selected query string.
     */
    suspend fun pickBestQuery(candidates: List<String>, question: String): String? =
        withContext(Dispatchers.IO) {
            if (candidates.isEmpty()) return@withContext null
            if (candidates.size == 1) return@withContext candidates.first()
            val engine = llm ?: return@withContext candidates.first()
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Which of these search queries would most effectively find information to answer: \"$question\"?\n\n")
                candidates.forEachIndexed { i, q -> append("${i + 1}. $q\n") }
                append("\nReply with only the chosen query, nothing else.")
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            try {
                val picked = engine.generateResponse(prompt)?.trim()
                    ?.lines()?.firstOrNull { it.isNotBlank() }
                    ?.trimStart { c -> c.isDigit() || c == '.' || c == ')' || c == '-' || c == ' ' }?.trim()
                // If model returned a number, map it to the candidate
                val num = picked?.let { Regex("^(\\d+)").find(it) }?.groupValues?.get(1)?.toIntOrNull()
                if (num != null && num in 1..candidates.size) candidates[num - 1]
                else picked?.ifBlank { null } ?: candidates.first()
            } catch (e: Exception) { candidates.first() }
        }

    /**
     * Scores each result in [resultsSummary] (numbered list of title+snippet) 0-10 for relevance.
     * Returns list of (0-based index, score) pairs sorted by score descending.
     */
    suspend fun scoreResults(resultsSummary: String, question: String): List<Pair<Int, Int>> =
        withContext(Dispatchers.IO) {
            val engine = llm ?: return@withContext emptyList()
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Score each search result 0-10 for relevance to: \"$question\"\n")
                append("10 = highly relevant, 0 = not relevant.\n\n")
                append(resultsSummary)
                append("\n\nReply in this exact format, one per line:\n1: 8\n2: 3\n...")
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            try {
                val raw = engine.generateResponse(prompt)?.trim() ?: return@withContext emptyList()
                Regex("(\\d+):\\s*(\\d+)").findAll(raw)
                    .map { Pair(it.groupValues[1].toInt() - 1, it.groupValues[2].toInt().coerceIn(0, 10)) }
                    .filter { it.first >= 0 }
                    .distinctBy { it.first }
                    .sortedByDescending { it.second }
                    .toList()
            } catch (e: Exception) { emptyList() }
        }

    /**
     * Analyzes what information is still missing based on [extractedFindings] (real extracted content).
     * Carries over [previousGapContext] from prior iterations.
     */
    suspend fun analyzeGaps(
        extractedFindings: String,
        question: String,
        previousGapContext: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val engine = llm ?: return@withContext null
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Question: \"$question\"\n\n")
            if (!previousGapContext.isNullOrBlank()) {
                append("Previously identified gaps:\n$previousGapContext\n\n")
            }
            append("Based on the following extracted information, what is still missing to fully answer the question?\n\n")
            append(extractedFindings.take(4000))
            append("\n\nReply with 2-4 sentences describing what specific information is still missing.")
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        try {
            engine.generateResponse(prompt)?.trim()?.ifBlank { null }
        } catch (e: Exception) { null }
    }

    /**
     * Rates 0-10 how completely [gatheredInfo] answers [question].
     * Returns (sufficient, score) where sufficient = score >= 7.
     */
    suspend fun evaluateSufficiency(gatheredInfo: String, question: String): Pair<Boolean, Int> =
        withContext(Dispatchers.IO) {
            val engine = llm ?: return@withContext Pair(false, 0)
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Rate 0-10 how completely the following information answers: \"$question\"\n")
                append("10 = fully answers, 0 = doesn't answer at all.\n\n")
                append(gatheredInfo.take(4000))
                append("\n\nReply with only the number (0-10).")
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            try {
                val raw = engine.generateResponse(prompt)?.trim() ?: return@withContext Pair(false, 0)
                val score = Regex("\\d+").find(raw)?.value?.toIntOrNull()?.coerceIn(0, 10) ?: 0
                Pair(score >= 7, score)
            } catch (e: Exception) { Pair(false, 0) }
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
            else append("You are PocketMonk, a helpful private AI assistant running entirely on-device.")
            if (!contextSummary.isNullOrBlank()) {
                append("\n\n[Summary of earlier conversation: $contextSummary]")
            }
        }
        append("<start_of_turn>user\n$sys<end_of_turn>\n")
        append("<start_of_turn>model\nUnderstood.<end_of_turn>\n")

        val active = messages.filter { !it.isSummary && !it.isSearchLog }
        var i = 0
        while (i < active.size) {
            val m = active[i]
            if (m.isSearchResult) {
                // Merge search context + following user question into one user turn
                val next = active.getOrNull(i + 1)
                if (next != null && next.role == MessageRole.USER) {
                    append("<start_of_turn>user\n${m.content}\n\nUser question: ${next.content}<end_of_turn>\n")
                    i += 2
                } else {
                    append("<start_of_turn>user\n${m.content}<end_of_turn>\n")
                    i++
                }
            } else {
                val role = if (m.role == MessageRole.USER) "user" else "model"
                append("<start_of_turn>$role\n${m.content}<end_of_turn>\n")
                i++
            }
        }
        append("<start_of_turn>model\n")
    }
}
