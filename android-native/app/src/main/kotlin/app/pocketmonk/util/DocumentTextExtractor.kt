package app.pocketmonk.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object DocumentTextExtractor {

    private val TEXT_EXTS = setOf(
        "txt", "md", "csv", "json", "xml", "html", "htm",
        "log", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "py", "js", "ts", "kt", "java", "swift", "c", "cpp", "h", "rb", "go", "rs",
        "sh", "bat", "r", "dart", "php", "lua", "pl", "sql", "tex", "rst"
    )
    private val ZIP_XML_EXTS = setOf("docx", "pptx", "xlsx", "odt", "odp", "ods")
    private val IMAGE_EXTS   = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "tif"
    )
    private val VIDEO_EXTS   = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts", "m4v", "wmv",
        "mts", "m2ts", "vob", "ogv", "rm", "rmvb", "asf", "divx"
    )
    private val AUDIO_EXTS   = setOf(
        "mp3", "m4a", "aac", "ogg", "flac", "wav", "opus", "wma", "aiff", "aif"
    )

    private val ZIP_XML_MIMES = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.oasis.opendocument.spreadsheet",
    )

    fun isImage(mimeType: String?, ext: String) =
        mimeType?.startsWith("image/") == true || ext in IMAGE_EXTS

    fun isVideo(mimeType: String?, ext: String) =
        mimeType?.startsWith("video/") == true || ext in VIDEO_EXTS

    fun isAudio(mimeType: String?, ext: String) =
        mimeType?.startsWith("audio/") == true || ext in AUDIO_EXTS

    /**
     * Extract plain text from a non-image document.
     * Returns extracted text, empty string if the file has no readable text,
     * or null if the format is unsupported.
     */
    suspend fun extract(bytes: ByteArray, mimeType: String?, fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf"  || mimeType == "application/pdf"                -> extractPdf(bytes)
            ext in ZIP_XML_EXTS || mimeType in ZIP_XML_MIMES              -> extractZipXml(bytes)
            ext == "epub" || mimeType == "application/epub+zip"           -> extractEpub(bytes)
            ext == "doc"  || mimeType == "application/msword"             -> extractDoc(bytes)
            ext == "ppt"  || mimeType == "application/vnd.ms-powerpoint"  -> extractPpt(bytes)
            ext == "xls"  || mimeType == "application/vnd.ms-excel"       -> extractXls(bytes)
            ext == "rtf"  || mimeType in setOf("application/rtf","text/rtf") -> extractRtf(bytes)
            ext == "fb2"                                                   -> extractFb2(bytes)
            ext in setOf("srt", "vtt", "sub")                             -> extractSubtitle(bytes)
            ext in setOf("vcf", "vcard") ||
                mimeType in setOf("text/vcard", "text/x-vcard")           -> extractVcf(bytes)
            ext in setOf("ics", "ical") || mimeType == "text/calendar"    -> extractIcs(bytes)
            mimeType?.startsWith("text/") == true || ext in TEXT_EXTS     -> decodeText(bytes)
            else                                                           -> null
        }
    }

    /**
     * Run on-device OCR on an image. Tries all 4 orientations before giving up.
     */
    suspend fun extractFromImage(bytes: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        val result = ocrBitmap(bitmap)
        bitmap.recycle()
        return result
    }

    /**
     * Sample frames evenly across a video and OCR each one.
     * Uses MediaMetadataRetriever so the whole file is never loaded into memory.
     * Caps at 30 min / 20 frames. Skips consecutive duplicate results.
     */
    suspend fun extractFromVideo(
        context: android.content.Context,
        uri: android.net.Uri
    ): String {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return ""

            val effectiveMs = minOf(durationMs, 30 * 60 * 1000L)   // cap at 30 min
            val maxFrames   = 20
            val intervalUs  = maxOf(effectiveMs * 1000L / maxFrames, 1_000_000L) // ≥ 1 s apart
            val frameCount  = minOf(maxFrames, (effectiveMs * 1000L / intervalUs + 1).toInt())

            val results = mutableListOf<String>()
            for (i in 0 until frameCount) {
                val bitmap = retriever.getFrameAtTime(
                    i * intervalUs,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue
                val text = ocrBitmap(bitmap)
                bitmap.recycle()
                // Drop blanks, very short noise, and consecutive duplicates
                if (text.length > 5 && text != results.lastOrNull()) {
                    results.add(text)
                }
            }

            return results.joinToString("\n---\n").trim()
        } finally {
            retriever.release()
        }
    }

    // --- PDF -------------------------------------------------------------------------

    private suspend fun extractPdf(bytes: ByteArray): String {
        // Try embedded text first — fast path for digital PDFs
        val text = runCatching {
            PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
                PDFTextStripper().getText(doc).trim()
            }
        }.getOrDefault("")
        if (text.isNotBlank()) return text

        // No embedded text → OCR each page (scanned / image-only PDFs)
        return runCatching {
            PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
                val renderer = PDFRenderer(doc)
                val maxPages = minOf(doc.numberOfPages, 10)
                val sb = StringBuilder()
                for (i in 0 until maxPages) {
                    val bitmap = renderer.renderImage(i, 1.5f)
                    val pageText = ocrBitmap(bitmap)
                    bitmap.recycle()
                    if (pageText.isNotBlank()) {
                        if (maxPages > 1) sb.appendLine("--- Page ${i + 1} ---")
                        sb.appendLine(pageText)
                    }
                }
                sb.toString().trim()
            }
        }.getOrDefault("")
    }

    // --- EPUB -----------------------------------------------------------------------

    private fun extractEpub(bytes: ByteArray): String {
        val sb = StringBuilder()
        val skipNames = setOf("toc", "nav", "cover", "ncx")
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && sb.length < 200_000) {
                    val baseName = entry.name.substringAfterLast('/').substringBeforeLast('.').lowercase()
                    if (!entry.isDirectory &&
                        (entry.name.endsWith(".xhtml") || entry.name.endsWith(".html")) &&
                        skipNames.none { baseName.contains(it) }
                    ) {
                        val text = stripHtml(zip.readBytes().toString(Charsets.UTF_8))
                        if (text.isNotBlank()) { sb.appendLine(text); sb.appendLine() }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) {}
        return sb.toString().trim()
    }

    // --- FictionBook 2 (FB2) --------------------------------------------------------

    private fun extractFb2(bytes: ByteArray): String {
        val xml = decodeText(bytes)
        val body = Regex("<body[^>]*>(.*?)</body>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(xml)?.groupValues?.get(1) ?: xml
        return Regex("<(?:p|v|subtitle|text-author)[^>]*>(.*?)</(?:p|v|subtitle|text-author)>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(body)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").decodeEntities().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    // --- Subtitles (SRT / VTT / SUB) ------------------------------------------------

    private fun extractSubtitle(bytes: ByteArray): String {
        val timestampRe = Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*.*""")
        val indexRe     = Regex("""\d+""")
        val vttHeaderRe = Regex("""^(WEBVTT|NOTE|STYLE|REGION).*""", RegexOption.IGNORE_CASE)
        return decodeText(bytes)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.isEmpty() || timestampRe.matches(t) || indexRe.matches(t) || vttHeaderRe.matches(t)
            }
            .map { it.replace(Regex("<[^>]+>"), "").trim() } // strip inline tags
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    // --- vCard (VCF) ----------------------------------------------------------------

    private fun extractVcf(bytes: ByteArray): String {
        val keep = setOf("FN", "N", "TEL", "EMAIL", "ORG", "TITLE", "ADR", "URL", "NOTE", "BDAY")
        return decodeText(bytes)
            .lines()
            .mapNotNull { line ->
                val key = line.substringBefore(":").substringBefore(";").uppercase()
                if (key in keep) "$key: ${line.substringAfter(":").trim()}".takeIf { line.contains(":") }
                else null
            }
            .joinToString("\n")
            .trim()
    }

    // --- iCalendar (ICS) ------------------------------------------------------------

    private fun extractIcs(bytes: ByteArray): String {
        val keep = setOf(
            "SUMMARY", "DESCRIPTION", "DTSTART", "DTEND", "LOCATION",
            "ORGANIZER", "ATTENDEE", "STATUS", "RRULE"
        )
        return decodeText(bytes)
            .lines()
            .mapNotNull { line ->
                val key = line.substringBefore(":").substringBefore(";").uppercase()
                if (key in keep) {
                    val value = line.substringAfter(":").replace("\\n", "\n").replace("\\,", ",").trim()
                    "$key: $value"
                } else null
            }
            .joinToString("\n")
            .trim()
    }

    // --- RTF ------------------------------------------------------------------------

    private fun extractRtf(bytes: ByteArray): String {
        val rtf = bytes.toString(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        var i = 0
        var depth = 0
        var skipDepth = 0 // non-zero → inside an ignorable destination

        while (i < rtf.length) {
            when (val c = rtf[i]) {
                '{' -> {
                    depth++
                    // Ignorable destination: {\* \word ...}
                    val ahead = rtf.substring(i + 1, minOf(i + 5, rtf.length)).trimStart()
                    if (skipDepth == 0 && ahead.startsWith("\\*")) skipDepth = depth
                    i++
                }
                '}' -> {
                    if (depth == skipDepth) skipDepth = 0
                    depth--
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= rtf.length) break
                    when (val next = rtf[i]) {
                        '\\' -> { if (skipDepth == 0) sb.append('\\'); i++ }
                        '{'  -> { if (skipDepth == 0) sb.append('{');  i++ }
                        '}'  -> { if (skipDepth == 0) sb.append('}');  i++ }
                        '\n', '\r' -> i++
                        '\'' -> {
                            i++
                            if (i + 1 < rtf.length) {
                                val code = rtf.substring(i, i + 2).toIntOrNull(16)
                                if (skipDepth == 0 && code != null) sb.append(code.toChar())
                                i += 2
                            }
                        }
                        else -> if (next.isLetter() || next == '-') {
                            val start = i
                            if (rtf[i] == '-') i++
                            while (i < rtf.length && rtf[i].isLetter()) i++
                            val word = rtf.substring(start, i)
                            // Skip optional numeric parameter
                            if (i < rtf.length && rtf[i] == '-') i++
                            while (i < rtf.length && rtf[i].isDigit()) i++
                            // Consume trailing delimiter space
                            if (i < rtf.length && rtf[i] == ' ') i++
                            if (skipDepth == 0) when (word) {
                                "par", "pard", "page", "column", "sect" -> sb.append('\n')
                                "line", "softline" -> sb.append('\n')
                                "tab" -> sb.append('\t')
                            }
                        } else i++
                    }
                }
                '\r', '\n' -> i++ // bare newlines in RTF stream are ignored
                else -> { if (skipDepth == 0) sb.append(c); i++ }
            }
        }

        return sb.toString()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    // --- Legacy Office binary formats (Apache POI) ----------------------------------

    private fun extractDoc(bytes: ByteArray): String =
        runCatching {
            HWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
                WordExtractor(doc).text.trim()
            }
        }.getOrDefault("")

    private fun extractPpt(bytes: ByteArray): String =
        runCatching {
            HSLFSlideShow(ByteArrayInputStream(bytes)).use { show ->
                show.slides.joinToString("\n\n") { slide ->
                    slide.textParagraphs.flatten().joinToString("\n") { para ->
                        para.textRuns.joinToString("") { it.rawText }
                    }
                }.trim()
            }
        }.getOrDefault("")

    private fun extractXls(bytes: ByteArray): String =
        runCatching {
            HSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
                (0 until wb.numberOfSheets).joinToString("\n\n") { i ->
                    val sheet = wb.getSheetAt(i)
                    (sheet.firstRowNum..sheet.lastRowNum).joinToString("\n") { r ->
                        val row = sheet.getRow(r) ?: return@joinToString ""
                        (row.firstCellNum until row.lastCellNum).mapNotNull { c ->
                            val cell = row.getCell(c.toInt()) ?: return@mapNotNull null
                            when (cell.cellType) {
                                CellType.STRING  -> cell.stringCellValue
                                CellType.NUMERIC -> if (DateUtil.isCellDateFormatted(cell))
                                    cell.dateCellValue.toString() else cell.numericCellValue.toString()
                                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                                CellType.FORMULA -> runCatching { cell.stringCellValue }
                                    .getOrElse { cell.numericCellValue.toString() }
                                else -> null
                            }
                        }.joinToString("\t")
                    }
                }.trim()
            }
        }.getOrDefault("")

    // --- OCR shared helper ----------------------------------------------------------

    private suspend fun ocrBitmap(bitmap: Bitmap): String {
        for (degrees in listOf(0, 90, 180, 270)) {
            val image = InputImage.fromBitmap(bitmap, degrees)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val text = suspendCancellableCoroutine<String> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { result -> cont.resume(result.text.trim()) }
                    .addOnFailureListener  { cont.resume("") }
                    .addOnCompleteListener { recognizer.close() }
                cont.invokeOnCancellation { recognizer.close() }
            }
            if (text.isNotBlank()) return text
        }
        return ""
    }

    // --- Modern Office XML / ODF (ZIP+XML) ------------------------------------------

    private fun extractZipXml(bytes: ByteArray): String {
        val sb = StringBuilder()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "word/document.xml" ->
                            sb.append(xmlText(zip.readBytes().toString(Charsets.UTF_8), "w:t"))
                        entry.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) -> {
                            sb.append(xmlText(zip.readBytes().toString(Charsets.UTF_8), "a:t"))
                            sb.append("\n")
                        }
                        entry.name == "xl/sharedStrings.xml" ->
                            sb.append(xmlText(zip.readBytes().toString(Charsets.UTF_8), "t"))
                        entry.name == "content.xml" ->
                            sb.append(xmlText(zip.readBytes().toString(Charsets.UTF_8), "text:p"))
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) {}
        return sb.toString().trim()
    }

    // --- Shared utilities -----------------------------------------------------------

    private fun stripHtml(html: String): String =
        html.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .decodeEntities()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    private fun String.decodeEntities(): String =
        replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")

    private fun xmlText(xml: String, tag: String): String {
        val t = Regex.escape(tag)
        return Regex("<$t(?:\\s[^>]*)?>([^<]*)</$t>")
            .findAll(xml)
            .joinToString(" ") { it.groupValues[1].decodeEntities() }
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if ('\uFFFD' in utf8) bytes.toString(Charsets.ISO_8859_1) else utf8
    }
}
