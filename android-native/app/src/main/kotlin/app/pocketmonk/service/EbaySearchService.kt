package app.pocketmonk.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class EbayListing(
    val title: String,
    val price: Double?,
    val condition: String,
    val bestOffer: Boolean = false,
    val soldDate: String? = null   // null for active listings
)

data class EbayScrapeResult(
    val listings: List<EbayListing>,
    val htmlChars: Int,
    val rawItemsParsed: Int,
    val error: String? = null
)

class EbaySearchService {

    companion object {
        private val PARTS_KEYWORDS = listOf(
            "for parts", "for repair", "not working", "parts only",
            "as is", "broken", "untested", "no power", "faulty", "spares"
        )
    }

    suspend fun scrapeSold(query: String): EbayScrapeResult = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.ebay.com/sch/i.html?_nkw=$encoded&LH_Complete=1&LH_Sold=1&_sop=13&_ipg=60"
        try {
            val html = fetchHtml(url)
            val listings = parseListings(html, isSold = true)
            EbayScrapeResult(
                listings = listings.first,
                htmlChars = html.length,
                rawItemsParsed = listings.second
            )
        } catch (e: Exception) {
            EbayScrapeResult(listings = emptyList(), htmlChars = 0, rawItemsParsed = 0, error = e.message)
        }
    }

    suspend fun scrapeActive(query: String): EbayScrapeResult = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.ebay.com/sch/i.html?_nkw=$encoded&_sop=15&_ipg=60"
        try {
            val html = fetchHtml(url)
            val listings = parseListings(html, isSold = false)
            EbayScrapeResult(
                listings = listings.first,
                htmlChars = html.length,
                rawItemsParsed = listings.second
            )
        } catch (e: Exception) {
            EbayScrapeResult(listings = emptyList(), htmlChars = 0, rawItemsParsed = 0, error = e.message)
        }
    }

    fun isForParts(listing: EbayListing): Boolean {
        val combined = "${listing.title} ${listing.condition}".lowercase()
        return PARTS_KEYWORDS.any { combined.contains(it) }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun fetchHtml(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            setRequestProperty("Accept-Encoding", "identity")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
    }

    /** Returns (listings, totalBlocksFound) so callers can distinguish parse failures from empty results. */
    private fun parseListings(html: String, isSold: Boolean): Pair<List<EbayListing>, Int> {
        val listings = mutableListOf<EbayListing>()

        val itemRegex = Regex(
            """<li[^>]*class="[^"]*s-item[^"]*"[^>]*>(.*?)</li>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val blocks = itemRegex.findAll(html).toList()

        for (match in blocks) {
            val block = match.groupValues[1]
            val title = extractTitle(block) ?: continue
            if (title.contains("Shop on eBay", ignoreCase = true)) continue

            val price = extractPrice(block) ?: continue
            val condition = extractCondition(block)
            val bestOffer = block.contains("Best Offer", ignoreCase = true)
            val soldDate = if (isSold) extractSoldDate(block) else null

            listings.add(EbayListing(title, price, condition, bestOffer, soldDate))
        }

        return Pair(listings, blocks.size)
    }

    private fun extractTitle(block: String): String? {
        val patterns = listOf(
            Regex("""class="[^"]*s-item__title[^"]*"[^>]*>\s*<span[^>]*>(.*?)</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
            Regex("""class="[^"]*s-item__title[^"]*"[^>]*>(.*?)<""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        )
        for (p in patterns) {
            val raw = p.find(block)?.groupValues?.get(1) ?: continue
            val cleaned = stripTags(raw).trim()
                .removePrefix("New Listing").removePrefix("SPONSORED").trim()
            if (cleaned.isNotBlank()) return cleaned
        }
        return null
    }

    private fun extractPrice(block: String): Double? {
        val m = Regex(
            """class="[^"]*s-item__price[^"]*"[^>]*>.*?\$\s*([\d,]+\.?\d*)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(block) ?: return null
        return m.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    private fun extractCondition(block: String): String {
        val patterns = listOf(
            Regex("""class="[^"]*s-item__condition[^"]*"[^>]*>(.*?)<""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
            Regex("""class="[^"]*SECONDARY_INFO[^"]*"[^>]*>(.*?)<""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        )
        for (p in patterns) {
            val raw = p.find(block)?.groupValues?.get(1) ?: continue
            val cleaned = stripTags(raw).trim()
            if (cleaned.isNotBlank()
                && !cleaned.contains("Buy It Now", ignoreCase = true)
                && !cleaned.contains("Best Offer", ignoreCase = true)
                && !cleaned.contains("Auction", ignoreCase = true)) return cleaned
        }
        return "Unknown"
    }

    private fun extractSoldDate(block: String): String? {
        val m = Regex(
            """class="[^"]*s-item__ended-date[^"]*"[^>]*>(.*?)<""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(block) ?: return null
        return stripTags(m.groupValues[1]).trim().removePrefix("Sold").trim().ifBlank { null }
    }

    private fun stripTags(html: String) = html.replace(Regex("<[^>]+>"), "")
}
