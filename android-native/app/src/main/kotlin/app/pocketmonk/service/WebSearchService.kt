package app.pocketmonk.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class WebSearchService {

    data class SearchResult(
        val title: String,
        val snippet: String,
        val displayUrl: String,
        val realUrl: String?,
        val pageContent: String? = null
    )

    private data class LevelConfig(val maxResults: Int, val pagesToFetch: Int)

    // Session-level LRU cache: URL → full extracted page text.
    // Avoids re-fetching the same page in multi-iteration deep searches.
    private val pageCache: MutableMap<String, String> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, String>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > 40
        }
    )

    private val levels = mapOf(
        0 to LevelConfig(maxResults = 10, pagesToFetch = 5),  // Sub-query  – internal use by Mega Deep
        1 to LevelConfig(maxResults = 6, pagesToFetch = 2),   // Normal     – top 2 pages
        2 to LevelConfig(maxResults = 8, pagesToFetch = 3),   // Deep       – 3 pages, LLM-compressed
        3 to LevelConfig(maxResults = 10, pagesToFetch = 5),  // Super Deep – 5 pages, LLM-compressed
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * [level] 1=Normal, 2=Deep, 3=Super Deep.
     * [contextSize] drives the content budget (40% of context window).
     * Pages within the fetch limit are fetched in parallel.
     */
    suspend fun search(
        query: String,
        level: Int = 2,
        contextSize: Int = 2048
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val config = levels[if (level == 0) 0 else level.coerceIn(1, 3)] ?: levels[1]!!

        // Budget: 40 % of context tokens, converted to characters (~4 chars / token).
        // Levels 2/3 bypass the per-page budget — raw content is LLM-compressed later.
        val totalBudgetChars = (contextSize * 0.40 * 4).toInt()
        val charsPerPage = when {
            config.pagesToFetch == 0 -> 0
            level == 3 -> 12000 // raw; ViewModel will LLM-compress these
            level == 2 -> 8000  // raw; ViewModel will LLM-compress these
            level == 0 -> 12000 // sub-query pages; ViewModel will compress then extract
            else -> totalBudgetChars / config.pagesToFetch
        }

        val html = fetchDdgHtml(query)
        val results = parseResults(html, config.maxResults)

        // Fetch pages in parallel — only the first pagesToFetch results need content.
        coroutineScope {
            results.mapIndexed { i, result ->
                async {
                    if (i < config.pagesToFetch && result.realUrl != null) {
                        val content = runCatching { fetchPageContent(result.realUrl, charsPerPage) }.getOrNull()
                        result.copy(pageContent = content)
                    } else result
                }
            }.awaitAll()
        }
    }

    /** Formats results into LLM-readable context text. [synthesis] is the Stage 2 mega-summary for Level 4. */
    fun format(query: String, results: List<SearchResult>, synthesis: String? = null): String = buildString {
        appendLine("[Web search results for \"$query\":]")
        if (!synthesis.isNullOrBlank()) {
            appendLine("Key findings: $synthesis")
            appendLine()
        }
        results.forEachIndexed { i, r ->
            appendLine("${i + 1}. ${r.title}")
            if (r.snippet.isNotBlank()) appendLine("   Summary: ${r.snippet}")
            if (r.displayUrl.isNotBlank()) appendLine("   Source: ${r.displayUrl}")
            if (!r.pageContent.isNullOrBlank()) {
                appendLine("   Content: ${r.pageContent.replace("\n", " ")}")
            }
        }
    }.trim()

    // ── DuckDuckGo fetcher & parser ───────────────────────────────────────────

    private fun fetchDdgHtml(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val conn = URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Accept-Encoding", "identity")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            conn.inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        var searchStart = 0

        while (results.size < maxResults) {
            val classPos = html.indexOf("""class="result__a"""", searchStart)
            if (classPos == -1) break

            val href = extractHrefAt(html, classPos)
            val realUrl = href?.let { decodeRealUrl(it) }

            val tagEnd = html.indexOf('>', classPos).takeIf { it != -1 } ?: break
            val titleEnd = html.indexOf("</a>", tagEnd).takeIf { it != -1 } ?: break
            val title = stripTags(html.substring(tagEnd + 1, titleEnd)).trim()
            if (title.isBlank()) { searchStart = titleEnd + 4; continue }

            val tail = html.substring(titleEnd)
            val snippet = extractAfterClass(tail, "result__snippet")
            val displayUrl = extractAfterClass(tail, "result__url")

            results.add(SearchResult(title, snippet, displayUrl, realUrl))
            searchStart = titleEnd + 4
        }

        return results
    }

    /** Finds href="…" in the <a> tag that encloses the class attribute at [classPos]. */
    private fun extractHrefAt(html: String, classPos: Int): String? {
        val tagStart = html.lastIndexOf("<a ", classPos).takeIf { it != -1 } ?: return null
        val tagEnd = html.indexOf('>', classPos).takeIf { it != -1 } ?: return null
        val tag = html.substring(tagStart, tagEnd)
        val hrefIdx = tag.indexOf("href=\"").takeIf { it != -1 } ?: return null
        val start = hrefIdx + 6
        val end = tag.indexOf('"', start).takeIf { it != -1 } ?: return null
        return tag.substring(start, end)
    }

    /**
     * DDG uses /l/?uddg=ENCODED_URL redirect hrefs.
     * Decode the uddg parameter to get the real URL without following the redirect.
     */
    private fun decodeRealUrl(href: String): String? = runCatching {
        when {
            href.startsWith("http") -> href
            href.contains("uddg=") -> URLDecoder.decode(
                href.substringAfter("uddg=").substringBefore("&"), "UTF-8"
            )
            else -> null
        }
    }.getOrNull()

    private fun extractAfterClass(html: String, cls: String): String {
        val marker = html.indexOf("""class="$cls"""").takeIf { it != -1 } ?: return ""
        val after = html.substring(marker)
        val start = after.indexOf('>').takeIf { it != -1 } ?: return ""
        val end = after.indexOf("</", start).takeIf { it != -1 } ?: return ""
        return stripTags(after.substring(start + 1, end)).trim()
    }

    // ── Public helpers for Mega Deep ─────────────────────────────────────────

    /** Fetches DDG results without reading any pages — title, snippet and URL only. */
    suspend fun searchMetadataOnly(query: String, maxResults: Int = 10): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val html = runCatching { fetchDdgHtml(query) }.getOrElse { return@withContext emptyList() }
            parseResults(html, maxResults)
        }

    /** Fetches a full page with no character cap. */
    suspend fun fetchFullPage(url: String): String = withContext(Dispatchers.IO) {
        runCatching { fetchPageContent(url, Int.MAX_VALUE) }.getOrElse { "" }
    }

    /** Clears the in-memory page cache. */
    fun clearCache() { pageCache.clear() }

    // ── Page fetcher ──────────────────────────────────────────────────────────

    /**
     * Fetches and extracts text from [url], caching the full result.
     * Retries once on IOException (transient network failures, timeouts).
     * HTTP error codes are not retried.
     */
    private fun fetchPageContent(url: String, maxChars: Int): String {
        val cached = pageCache[url]
        if (cached != null) {
            return if (cached.length > maxChars) cached.take(maxChars) + "…" else cached
        }
        for (attempt in 0..1) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                setRequestProperty("Accept", "text/html")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "identity")
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    return ""  // HTTP error — don't retry
                }
                val html = conn.inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
                conn.disconnect()
                val full = extractMainText(html, Int.MAX_VALUE)
                pageCache[url] = full
                return if (full.length > maxChars) full.take(maxChars) + "…" else full
            } catch (e: java.io.IOException) {
                conn.disconnect()
                if (attempt == 1) return ""
                // fall through to retry
            }
        }
        return ""
    }

    /**
     * Strips boilerplate and extracts meaningful content paragraphs.
     * Prefers longer lines (≥60 chars) which are more likely to be real content
     * rather than navigation links or menu items.
     */
    private fun extractMainText(html: String, maxChars: Int): String {
        var text = html

        // Remove entire noisy block elements (tag + all inner content)
        for (tag in listOf("script", "style", "nav", "header", "footer",
                            "aside", "noscript", "form", "iframe")) {
            text = text.replace(
                Regex("<$tag[^>]*>[\\s\\S]*?</$tag>", RegexOption.IGNORE_CASE), " "
            )
        }

        // Strip remaining HTML tags and decode entities
        text = text
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

        // Split into lines, drop short lines (nav, menu, labels)
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val content = lines
            .filter { it.length >= 60 }                   // real paragraph threshold
            .joinToString("\n")
            .ifBlank { lines.joinToString("\n") }          // fallback: use everything

        // Trim to budget
        return if (content.length > maxChars) content.take(maxChars) + "…" else content
    }

    private fun stripTags(html: String): String = html
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()
}
