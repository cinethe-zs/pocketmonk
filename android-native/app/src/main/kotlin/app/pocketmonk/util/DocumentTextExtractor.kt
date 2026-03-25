package app.pocketmonk.util

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object DocumentTextExtractor {

    private val TEXT_EXTS = setOf(
        "txt", "md", "csv", "json", "xml", "html", "htm",
        "log", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "py", "js", "ts", "kt", "java", "swift", "c", "cpp", "h", "rb", "go", "rs"
    )
    private val ZIP_XML_EXTS = setOf("docx", "pptx", "xlsx", "odt", "odp", "ods")
    private val BINARY_EXTS = setOf("doc", "ppt", "xls")

    private val ZIP_XML_MIMES = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.oasis.opendocument.spreadsheet",
    )

    /**
     * Extract plain text from [bytes].
     * @return extracted text, empty string if file has no readable text, or null if the format
     *         is explicitly unsupported (old binary .doc / .ppt / .xls).
     */
    fun extract(bytes: ByteArray, mimeType: String?, fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "pdf" || mimeType == "application/pdf"      -> extractPdf(bytes)
            ext in ZIP_XML_EXTS || mimeType in ZIP_XML_MIMES   -> extractZipXml(bytes)
            ext in BINARY_EXTS                                  -> null  // unsupported binary
            ext in TEXT_EXTS || mimeType?.startsWith("text/") == true -> decodeText(bytes)
            else                                                -> decodeText(bytes)
        }
    }

    private fun extractPdf(bytes: ByteArray): String {
        return try {
            PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
                PDFTextStripper().getText(doc).trim()
            }
        } catch (_: Exception) { "" }
    }

    private fun extractZipXml(bytes: ByteArray): String {
        val sb = StringBuilder()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        // DOCX — body text
                        entry.name == "word/document.xml" ->
                            sb.append(xmlText(zip.readBytes().toString(Charsets.UTF_8), "w:t"))
                        // PPTX — one entry per slide
                        entry.name.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) -> {
                            sb.append(xmlText(zip.readBytes().toString(Charsets.UTF_8), "a:t"))
                            sb.append("\n")
                        }
                        // XLSX — shared strings table
                        entry.name == "xl/sharedStrings.xml" ->
                            sb.append(xmlText(zip.readBytes().toString(Charsets.UTF_8), "t"))
                        // ODT / ODP / ODS — all text in content.xml
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
