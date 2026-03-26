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
import java.util.concurrent.locks.ReentrantLock
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

    /**
     * Serializes all native session/conversation creation and teardown.
     * LiteRT allows only one Session OR Conversation to be active at a time.
     * runSession() holds this lock for its full duration (createSession → generateContent → close).
     * chat() holds it only for (cancelProcess + close staleConv + createConversation).
     */
    private val engineLock = ReentrantLock()

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
        documentName: String? = null,
        documentContent: String? = null,
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

        // Close the stale conversation immediately on the calling thread.
        // Doing it here (before the background Thread starts) gives the native async
        // delete maximum lead time to complete before createConversation() is attempted.
        val staleConv = currentConversation
        currentConversation = null
        try { staleConv?.cancelProcess() } catch (_: Exception) {}
        try { staleConv?.close() } catch (_: Exception) {}

        Thread {
            try {
                // Build config (pure computation, no native calls).
                val effectiveTemp = if (temperature < 0.1f) 1.0f else temperature
                val sysText = buildString {
                    if (!systemPrompt.isNullOrBlank()) append(systemPrompt)
                    else append("You are PocketMonk, a helpful private AI assistant running entirely on-device.")
                    if (!contextSummary.isNullOrBlank()) {
                        append("\n\n[Summary of earlier conversation: $contextSummary]")
                    }
                    if (!documentName.isNullOrBlank() && !documentContent.isNullOrBlank()) {
                        append("\n\nThe user has attached the following document:\n[Document: \"$documentName\"]\n$documentContent")
                    }
                }
                // If the second-to-last message is a search/document result, merge it with the
                // last user message into a single sendMessageAsync call. Leaving it as a lone
                // user turn in initialMessages causes LiteRT to create an internal session for
                // that pending turn, then sendMessageAsync creates another → FAILED_PRECONDITION.
                val lastMsg = history.last()
                val penultimate = history.getOrNull(history.size - 2)
                val initialMsgs: List<LitMessage>
                val sendContent: String
                if (penultimate?.isSearchResult == true) {
                    initialMsgs = buildLitMessages(history.dropLast(2))
                    sendContent = "${penultimate.content}\n\nUser question: ${lastMsg.content}"
                } else {
                    initialMsgs = buildLitMessages(history.dropLast(1))
                    sendContent = lastMsg.content
                }

                val config = ConversationConfig(
                    systemInstruction = Contents.of(sysText),
                    initialMessages = initialMsgs,
                    samplerConfig = SamplerConfig(
                        topK = 40, topP = 1.0, temperature = effectiveTemp.toDouble()
                    ),
                )

                // Acquire the engine lock: blocks until any active runSession() (title generation,
                // summarization, web search helpers) has fully closed its Session.
                // Inside the lock, retry createConversation to handle any remaining async cleanup.
                engineLock.lock()
                var conversation: com.google.ai.edge.litertlm.Conversation? = null
                var lastEx: Exception? = null
                try {
                    for (attempt in 0..4) {
                        if (attempt > 0) Thread.sleep(100L * attempt)
                        try {
                            conversation = eng.createConversation(config)
                            break
                        } catch (e: Exception) {
                            lastEx = e
                            if (e.message?.contains("FAILED_PRECONDITION", ignoreCase = true) != true) break
                        }
                    }
                } finally {
                    engineLock.unlock()
                }

                if (conversation == null) {
                    val msg = lastEx?.message ?: "Failed to create conversation"
                    mainHandler.post { isInferring = false; onErrorOuter(msg) }
                    return@Thread
                }

                // If cancel() was called while we were waiting for the lock, abort cleanly.
                if (!isInferring) {
                    try { conversation.close() } catch (_: Exception) {}
                    return@Thread
                }
                currentConversation = conversation

                val contentList = listOf(Content.Text(sendContent))
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
                                // Close conversation immediately so nativeDeleteConversation()
                                // has maximum time to complete before the next createConversation().
                                val conv = currentConversation
                                currentConversation = null
                                try { conv?.close() } catch (_: Exception) {}
                                isInferring = false
                                onDone()
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            mainHandler.removeCallbacks(watchdog)
                            mainHandler.post {
                                val conv = currentConversation
                                currentConversation = null
                                try { conv?.close() } catch (_: Exception) {}
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

    // ── Map-reduce for long documents ─────────────────────────────────────────

    /**
     * Processes a document too large to fit in the context window using map-reduce:
     * MAP:    split into ~7000-char chunks, extract facts relevant to [question] from each
     * REDUCE: iteratively compress batches of extractions until they fit in 7000 chars
     * Returns the final synthesis to use as documentContent in the chat() call.
     */
    suspend fun mapReduceDocument(
        document: String,
        question: String,
        onProgress: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext ""

        fun splitIntoChunks(text: String, maxChars: Int): List<String> {
            val chunks = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                if (start + maxChars >= text.length) {
                    val tail = text.substring(start).trim()
                    if (tail.isNotBlank()) chunks.add(tail)
                    break
                }
                val window = text.substring(start, start + maxChars)
                val minSplit = maxChars / 2
                val splitAt = listOf(
                    window.lastIndexOf("\n\n"),
                    window.lastIndexOf('\n'),
                    window.lastIndexOf(". "),
                    window.lastIndexOf(' '),
                ).filter { it > minSplit }
                 .maxOrNull()
                 ?: (maxChars - 1)
                chunks.add(window.substring(0, splitAt + 1).trim())
                start += splitAt + 1
            }
            return chunks.filter { it.isNotBlank() }
        }

        fun extractChunk(chunk: String): String? {
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Question: \"$question\"\n\n")
                append("From the excerpt below, extract only the facts relevant to answering the question. ")
                append("Be concise. If nothing is relevant, reply exactly: NONE\n\n")
                append(chunk)
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            val r = runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim().cleaned() }
            return if (r == null || r.isBlank() || r.equals("NONE", ignoreCase = true)) null else r
        }

        fun compressBatch(batch: String): String? {
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Question: \"$question\"\n\n")
                append("Synthesize the following extracted facts into a concise summary relevant to the question. ")
                append("Keep your response under 3000 characters.\n\n")
                append(batch)
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            return runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim().cleaned().ifBlank { null } }
        }

        fun packIntoBatches(parts: List<String>, maxChars: Int): List<String> {
            val batches = mutableListOf<String>()
            val current = StringBuilder()
            for (part in parts) {
                if (current.isNotEmpty() && current.length + part.length + 7 > maxChars) {
                    batches.add(current.toString().trim())
                    current.clear()
                }
                if (current.isNotEmpty()) current.append("\n\n---\n\n")
                current.append(part)
            }
            if (current.isNotEmpty()) batches.add(current.toString().trim())
            return batches
        }

        fun reduce(parts: List<String>, pass: Int): List<String> {
            val joined = parts.joinToString("\n\n---\n\n")
            if (joined.length <= 7000) return parts
            val batches = packIntoBatches(parts, 7000)
            val compressed = mutableListOf<String>()
            batches.forEachIndexed { i, batch ->
                onProgress("Synthesizing (pass $pass, batch ${i + 1}/${batches.size})…")
                val r = compressBatch(batch)
                if (r != null) compressed.add(r)
            }
            return if (compressed.isEmpty()) listOf(parts.first())
            else reduce(compressed, pass + 1)
        }

        // MAP
        val chunks = splitIntoChunks(document, 7000)
        val mapped = mutableListOf<String>()
        chunks.forEachIndexed { i, chunk ->
            onProgress("Analyzing section ${i + 1} of ${chunks.size}…")
            val r = extractChunk(chunk)
            if (r != null) mapped.add(r)
        }
        if (mapped.isEmpty()) return@withContext ""

        // REDUCE
        val reduced = reduce(mapped, 1)
        reduced.joinToString("\n\n---\n\n")
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

    /**
     * Runs [block] on a fresh Session, closing it afterward. Returns null on any exception.
     * Holds [engineLock] for the full duration so that createConversation() in chat() cannot
     * overlap with an active Session — prevents FAILED_PRECONDITION "a session already exists".
     */
    private fun <T> runSession(eng: Engine, block: (Session) -> T): T? {
        engineLock.lock()
        var session: Session? = null
        return try {
            session = eng.createSession()
            val result = block(session)
            try { session.close() } catch (_: Exception) {}
            result
        } catch (e: Exception) {
            try { session?.close() } catch (_: Exception) {}
            null
        } finally {
            engineLock.unlock()
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
