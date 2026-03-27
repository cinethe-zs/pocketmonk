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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import com.google.ai.edge.litertlm.Message as LitMessage

class LlmService(private val context: Context) {

    private var engine: Engine? = null
    var isReady = false
        private set
    @Volatile var isInferring = false
        private set

    /** Tracked only for cancellation — either a Conversation or Session. */
    @Volatile private var currentConversation: com.google.ai.edge.litertlm.Conversation? = null
    @Volatile private var currentSession: Session? = null
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
                    backend = Backend.CPU(numOfThreads = 6),
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
        try { staleConv?.cancelProcess() } catch (_: Throwable) {}
        try { staleConv?.close() } catch (_: Throwable) {}

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
                    try { conversation.close() } catch (_: Throwable) {}
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
                                try { conv?.close() } catch (_: Throwable) {}
                                isInferring = false
                                onDone()
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            mainHandler.removeCallbacks(watchdog)
                            mainHandler.post {
                                val conv = currentConversation
                                currentConversation = null
                                try { conv?.close() } catch (_: Throwable) {}
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

    suspend fun generateSearchQueries(question: String, onRaw: ((String) -> Unit)? = null): List<String> = withContext(Dispatchers.IO) {
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
        onRaw?.invoke(raw)
        raw
            .replace("\\n", "\n")
            .replace("\\r", "")
            .lines()
            .map { line ->
                line.trim()
                    // Strip bullets / numbering
                    .trimStart('-', '*', '•', '·')
                    .trimStart { c -> c.isDigit() || c == '.' || c == ')' || c == ' ' }
                    .trim()
                    // Strip ALL surrounding quotes (robust)
                    .replace(Regex("""^[\s"'“”„‟″]+|[\s"'“”„‟″]+$"""), "")
                    .trim()
            }
            .filter { it.length > 4 && !it.equals("SKIP", ignoreCase = true) }
            .distinct()
            .take(5)
    }

    suspend fun generateAnswerDraft(question: String, context: String): String? =
        withContext(Dispatchers.IO) {
            val eng = engine ?: return@withContext null
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Research notes:\n${context.take(8000)}\n\n")
                append("Based on the research notes above, write the best answer you can to: \"$question\"\n")
                append("Mark any claims you are uncertain about with [?].\n")
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim().ifBlank { null } }
        }

    suspend fun identifyUnsupportedClaims(draft: String, question: String): List<String> =
        withContext(Dispatchers.IO) {
            val eng = engine ?: return@withContext emptyList()
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Question: \"$question\"\n\nDraft answer:\n${draft.take(2000)}\n\n")
                append("List up to 3 specific facts in this answer that are vague, uncertain, or missing.\n")
                append("Be specific: instead of 'more detail needed', write 'exact release date not stated'.\n")
                append("Output only the list, one item per line. If the draft fully answers the question, reply: COMPLETE")
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            val raw = runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim() }
                ?: return@withContext emptyList()
            if (raw.trim().uppercase().startsWith("COMPLETE")) return@withContext emptyList()
            raw.lines()
                .map { it.trim().trimStart('-', '*', '•', '·').trimStart { c -> c.isDigit() || c == '.' || c == ')' || c == ' ' }.trim() }
                .filter { it.length > 5 }
                .take(3)
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
     *
     * Progress format: "Analyzing section X of Y on iteration Z: chars/7000"
     *   - Iteration 1 = MAP phase, 2+ = REDUCE passes
     *   - chars = accumulated extracted/compressed chars so far
     */
    suspend fun mapReduceDocument(
        document: String,
        question: String,
        onProgress: (String) -> Unit,
        onIterationBuffer: (iterIndex: Int, buffer: String) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext ""
        val REDUCE_THRESHOLD = 7000

        fun extractChunk(chunk: String, onToken: (String) -> Unit): String? {
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Question: \"$question\"\n\n")
                append("From the excerpt below, extract only the facts relevant to answering the question. ")
                append("Be concise. If nothing is relevant, reply exactly: NONE\n\n")
                append(chunk)
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            val r = runSessionStreaming(eng, listOf(InputData.Text(prompt)), onToken)
            return if (r == null || r.isBlank() || r.equals("NONE", ignoreCase = true)) null else r
        }

        fun compressBatch(batch: String, onToken: (String) -> Unit): String? {
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("Question: \"$question\"\n\n")
                append("Synthesize the following extracted facts into a concise summary relevant to the question. ")
                append("Keep your response under 3000 characters.\n\n")
                append(batch)
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            return runSessionStreaming(eng, listOf(InputData.Text(prompt)), onToken)?.ifBlank { null }
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
            if (joined.length <= REDUCE_THRESHOLD) return parts
            val batches = packIntoBatches(parts, REDUCE_THRESHOLD)
            val compressed = mutableListOf<String>()
            batches.forEachIndexed { i, batch ->
                val prevChars = compressed.sumOf { it.length }
                val prevBuffer = compressed.joinToString("\n\n---\n\n")
                onProgress("ANALYZE · section ${i + 1} / ${batches.size} · iter ${pass + 1} · $prevChars / $REDUCE_THRESHOLD")
                val r = compressBatch(batch) { partial ->
                    onProgress("ANALYZE · section ${i + 1} / ${batches.size} · iter ${pass + 1} · ${prevChars + partial.length} / $REDUCE_THRESHOLD")
                    val sep = if (prevBuffer.isNotEmpty()) "\n\n---\n\n" else ""
                    onIterationBuffer(pass, "$prevBuffer$sep$partial")
                }
                if (r != null) {
                    compressed.add(r)
                    onIterationBuffer(pass, compressed.joinToString("\n\n---\n\n"))
                }
            }
            return if (compressed.isEmpty()) listOf(parts.first())
            else reduce(compressed, pass + 1)
        }

        // MAP (iteration 1, iterIndex = 0)
        val chunks = splitIntoChunks(document, REDUCE_THRESHOLD)
        val mapped = mutableListOf<String>()
        val mapBuffer = StringBuilder()
        chunks.forEachIndexed { i, chunk ->
            val prevChars = mapped.sumOf { it.length }
            val prevBuffer = mapBuffer.toString()
            onProgress("ANALYZE · section ${i + 1} / ${chunks.size} · iter 1 · $prevChars / $REDUCE_THRESHOLD")
            val r = extractChunk(chunk) { partial ->
                onProgress("ANALYZE · section ${i + 1} / ${chunks.size} · iter 1 · ${prevChars + partial.length} / $REDUCE_THRESHOLD")
                val sep = if (prevBuffer.isNotEmpty()) "\n\n---\n\n" else ""
                onIterationBuffer(0, "$prevBuffer$sep$partial")
            }
            if (r != null) {
                if (mapBuffer.isNotEmpty()) mapBuffer.append("\n\n---\n\n")
                mapBuffer.append(r)
                mapped.add(r)
                onIterationBuffer(0, mapBuffer.toString())
            }
        }
        if (mapped.isEmpty()) return@withContext ""

        // REDUCE
        val reduced = reduce(mapped, 1)
        reduced.joinToString("\n\n---\n\n")
    }

    data class IntentResult(val intent: String, val matchedKeyword: String?)

    /**
     * LLM-based classifier. Sends a few-shot prompt to the model and logs both the prompt
     * and the raw response via [onLog]. Falls back to ANALYZE on any failure.
     */
    suspend fun classifyIntentLlm(
        question: String,
        onLog: (prompt: String, response: String) -> Unit,
    ): IntentResult = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext IntentResult("ANALYZE", null)
        val prompt = buildString {
            append("<start_of_turn>user\n")
            append("Does this request need all the document content?\n\n")
            append("Request: $question\n\n")
            append("Answer by yes or no.\n")
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
        val raw = runSession(eng) { it.generateContent(listOf(InputData.Text(prompt))).trim().cleaned() } ?: ""
        onLog(prompt, raw)
        // "yes" = can be applied chunk by chunk = TRANSFORM (stream)
        // "no"  = needs global context across all chunks = ANALYZE (map-reduce)
        if (raw.lowercase().contains("yes")) IntentResult("TRANSFORM", null) else IntentResult("ANALYZE", null)
    }

    /**
     * Keyword-based classifier — deterministic, instant, no LLM call.
     * Kept as fallback / future reuse.
     * Returns [IntentResult] with the intent and the keyword that triggered it (null for ANALYZE).
     */
    fun classifyIntentByKeyword(question: String): IntentResult {
        val q = question.lowercase()
        val transformKeywords = listOf(
            // English
            "translat", "rewrite", "re-write", "rephrase", "paraphrase",
            "fix the", "fix grammar", "fix spelling", "fix typo",
            "correct the", "correct grammar", "correct spelling",
            "format", "reformat", "convert",
            "replace ", "replace all", "substitute",
            "improve the", "improve style", "improve tone",
            "make it more", "make this more", "make the text",
            "change the tone", "change the style",
            "simplify", "formalize",
            // French
            "tradui", "traduire", "traduction",
            "réécri", "réécrire", "reformule", "reformuler",
            "corrige", "corriger", "correction",
            "remplace", "remplacer",
            "formate", "formater",
            "améliore", "améliorer",
            "simplifie", "simplifier",
            // Spanish
            "traduc",
            // German
            "übersetz",
        )
        val match = transformKeywords.firstOrNull { q.contains(it) }
        return if (match != null) IntentResult("TRANSFORM", match.trim())
               else IntentResult("ANALYZE", null)
    }

    /**
     * Stream-processes a large document by applying [instruction] to each ~3000-char chunk
     * and concatenating the results.
     * Used for TRANSFORM requests (translation, rewriting, formatting, etc.).
     */
    suspend fun streamDocument(
        document: String,
        instruction: String,
        onProgress: (String) -> Unit,
        onBuffer: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext ""
        val chunks = splitIntoChunks(document, 3000)
        val results = StringBuilder()
        chunks.forEachIndexed { i, chunk ->
            val prevLength = results.length
            onProgress("TRANSFORM · section ${i + 1} / ${chunks.size} · $prevLength chars")
            val prompt = buildString {
                append("<start_of_turn>user\n")
                append("$instruction\n\n")
                append(chunk)
                append("<end_of_turn>\n<start_of_turn>model\n")
            }
            val prevResults = results.toString()
            val r = runSessionStreaming(eng, listOf(InputData.Text(prompt))) { partial ->
                onProgress("TRANSFORM · section ${i + 1} / ${chunks.size} · ${prevLength + partial.length} chars")
                val sep = if (prevResults.isNotEmpty()) "\n\n" else ""
                onBuffer("$prevResults$sep$partial")
            }
            if (!r.isNullOrBlank()) {
                if (results.isNotEmpty()) results.append("\n\n")
                results.append(r)
                onBuffer(results.toString())
            }
        }
        results.toString()
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
                    .replace("\\n", "\n").replace("\\r", "")
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
                append("\n\nDoes the information above contain SPECIFIC, VERIFIABLE facts that DIRECTLY and COMPLETELY answer the question?\n")
                append("Reply YES only if the key facts are explicitly stated. Reply NO if the answer is vague, indirect, or incomplete.\n")
                append("When in doubt, reply NO.\n")
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
        maxFindingsChars: Int = 12_000,
        onPartial: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val eng = engine
        if (eng == null || !isReady) { onError("Model not initialized"); return }
        if (isInferring) { onError("Already inferring"); return }
        isInferring = true

        // Build prompt, capping findings to avoid overflowing the context window.
        val prompt = buildString {
            append("<start_of_turn>user\n")
            var charsUsed = 0
            findings.groupBy { it.first }.entries.forEach groupLoop@{ (q, items) ->
                val header = "[Web search results for \"$q\":]\n"
                if (charsUsed + header.length > maxFindingsChars) return@groupLoop
                append(header); charsUsed += header.length
                items.forEach { (_, title, info) ->
                    val line = "  • $title: $info\n"
                    if (charsUsed + line.length <= maxFindingsChars) {
                        append(line); charsUsed += line.length
                    }
                }
                append("\n"); charsUsed += 1
            }
            append("Based on the previous research notes, provide a comprehensive, well-organized answer to this: \"$question\"\n")
            append("<end_of_turn>\n<start_of_turn>model\n")
        }

        // Spawn a Thread that acquires engineLock before creating the session.
        // This serializes answerFromResearch with all runSession() / runSessionStreaming() calls,
        // preventing "FAILED_PRECONDITION: a session already exists" races.
        Thread {
            engineLock.lock()
            val latch = CountDownLatch(1)
            val accumulated = StringBuilder()
            var shouldCallDone = false
            var callbackError: String? = null
            var session: Session? = null

            try {
                session = eng.createSession(SessionConfig(SamplerConfig(topK = 40, topP = 1.0, temperature = 1.0)))
                currentSession = session

                // Watchdog: cancel if generation hangs for more than 45 s.
                val watchdog = Runnable { cancel() }
                mainHandler.postDelayed(watchdog, 45_000)

                session.generateContentStream(listOf(InputData.Text(prompt)), object : ResponseCallback {
                    override fun onNext(response: String) {
                        accumulated.append(response)
                        mainHandler.post { onPartial(accumulated.toString().cleaned()) }
                    }
                    override fun onDone() {
                        mainHandler.removeCallbacks(watchdog)
                        currentSession = null
                        try { session?.close() } catch (_: Throwable) {}
                        shouldCallDone = true
                        latch.countDown()
                    }
                    override fun onError(throwable: Throwable) {
                        mainHandler.removeCallbacks(watchdog)
                        currentSession = null
                        try { session?.close() } catch (_: Throwable) {}
                        // CancellationException = user stopped or watchdog fired.
                        // Use accumulated partial text rather than showing an error.
                        if (throwable is CancellationException) shouldCallDone = true
                        else callbackError = throwable.message ?: "Unknown error"
                        latch.countDown()
                    }
                })

                try { latch.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            } catch (e: Exception) {
                currentSession = null
                try { session?.close() } catch (_: Throwable) {}
                callbackError = e.message ?: "Unknown error during inference"
            } finally {
                isInferring = false
                engineLock.unlock()
            }

            // Post result callbacks only after the lock is released.
            if (shouldCallDone) mainHandler.post { onDone() }
            else callbackError?.let { err -> mainHandler.post { onError(err) } }
        }.start()
    }

    fun cancel() {
        isInferring = false
        // Only signal cancellation — do NOT call close() here.
        // The MessageCallback/ResponseCallback owns the lifecycle and calls close()
        // in its onDone/onError. Calling close() from a different thread while the
        // callback is still running causes a double-free native crash (SIGABRT).
        val conv = currentConversation; currentConversation = null
        try { conv?.cancelProcess() } catch (_: Throwable) {}
        val sess = currentSession; currentSession = null
        try { sess?.cancelProcess() } catch (_: Throwable) {}
    }

    fun dispose() {
        cancel()
        isReady = false
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
    }

    // ── Helpers ────────────────────────────────────────────────

    /**
     * Splits [text] into chunks of at most [maxChars], breaking on paragraph/line/sentence/word
     * boundaries rather than mid-word.
     */
    private fun splitIntoChunks(text: String, maxChars: Int): List<String> {
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

    /**
     * Like [runSession] but streams tokens via [generateContentStream].
     * [onToken] is called with the fully accumulated text after each token arrives.
     * Holds [engineLock] for the full duration.
     */
    private fun runSessionStreaming(
        eng: Engine,
        inputs: List<InputData>,
        onToken: (String) -> Unit,
    ): String? {
        engineLock.lock()
        var session: Session? = null
        val latch = CountDownLatch(1)
        val accumulated = StringBuilder()
        var finalResult: String? = null
        // Saved before repetition loop was detected; used as the actual result.
        var preRepetitionResult: String? = null
        return try {
            session = eng.createSession(
                SessionConfig(SamplerConfig(topK = 40, topP = 0.95, temperature = 0.4))
            )
            currentSession = session
            session.generateContentStream(inputs, object : ResponseCallback {
                override fun onNext(response: String) {
                    accumulated.append(response)
                    val text = accumulated.toString()
                    if (preRepetitionResult == null) {
                        val trimmed = trimRepetitionLoop(text)
                        if (trimmed != null) {
                            // Loop detected: save clean portion, stop generation
                            preRepetitionResult = trimmed
                            try { currentSession?.cancelProcess() } catch (_: Throwable) {}
                            return
                        }
                        onToken(text)
                    }
                }
                override fun onDone() {
                    finalResult = (preRepetitionResult ?: accumulated.toString())
                        .trim().cleaned().ifBlank { null }
                    currentSession = null
                    try { session?.close() } catch (_: Throwable) {}
                    latch.countDown()
                }
                override fun onError(throwable: Throwable) {
                    // If we cancelled due to repetition, use the saved clean portion
                    finalResult = (preRepetitionResult ?: accumulated.toString())
                        .trim().cleaned().ifBlank { null }
                    currentSession = null
                    try { session?.close() } catch (_: Throwable) {}
                    latch.countDown()
                }
            })
            try { latch.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finalResult
        } catch (e: Exception) {
            try { session?.close() } catch (_: Throwable) {}
            null
        } finally {
            engineLock.unlock()
        }
    }

    /**
     * Returns the text with the repetition loop stripped if a loop is detected, null otherwise.
     * Detection: any 40-char pattern appearing 4+ times in the last 600 chars of [text].
     */
    private fun trimRepetitionLoop(text: String): String? {
        if (text.length < 160) return null
        val tail = text.takeLast(600)
        for (patLen in intArrayOf(40, 60, 100)) {
            if (tail.length < patLen * 4) continue
            val pattern = tail.substring(tail.length - patLen)
            val count = tail.split(pattern).size - 1
            if (count >= 4) {
                // Trim everything from where the repetition started
                val dropCount = patLen * (count - 1)
                return text.dropLast(minOf(dropCount, text.length / 2)).trim()
            }
        }
        return null
    }

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
            try { session.close() } catch (_: Throwable) {}
            result
        } catch (e: Exception) {
            try { session?.close() } catch (_: Throwable) {}
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
