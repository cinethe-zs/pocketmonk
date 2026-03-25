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
        "py", "js", "ts", "kt", "java", "swift", "c", "cpp", "h", "rb", "go", "rs"
    )
    private val ZIP_XML_EXTS = setOf("docx", "pptx", "xlsx", "odt", "odp", "ods")
    private val IMAGE_EXTS   = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")

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

    /**
     * Extract plain text from a non-image document.
     * @return extracted text, empty string if the file has no readable text,
     *         or null if the format is unsupported.
     */
    suspend fun extract(bytes: ByteArray, mimeType: String?, fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || mimeType == "application/pdf"                -> extractPdf(bytes)
            ext in ZIP_XML_EXTS || mimeType in ZIP_XML_MIMES             -> extractZipXml(bytes)
            ext == "doc"  || mimeType == "application/msword"            -> extractDoc(bytes)
            ext == "ppt"  || mimeType == "application/vnd.ms-powerpoint" -> extractPpt(bytes)
            ext == "xls"  || mimeType == "application/vnd.ms-excel"      -> extractXls(bytes)
            mimeType?.startsWith("text/") == true || ext in TEXT_EXTS    -> decodeText(bytes)
            else                                                          -> null
        }
    }

    /**
     * Run on-device OCR on an image. Suspend until ML Kit completes.
     */
    suspend fun extractFromImage(bytes: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        val result = ocrBitmap(bitmap)
        bitmap.recycle()
        return result
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

    private fun xmlText(xml: String, tag: String): String {
        val t = Regex.escape(tag)
        return Regex("<$t(?:\\s[^>]*)?>([^<]*)</$t>")
            .findAll(xml)
            .joinToString(" ") {
                it.groupValues[1]
                    .replace("&amp;", "&").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            }
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if ('\uFFFD' in utf8) bytes.toString(Charsets.ISO_8859_1) else utf8
    }
}
