package app.pocketmonk.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object FileSaver {

    /** Extension inferred from common language tags */
    fun extensionFor(language: String): String = when (language.lowercase().trim()) {
        "python", "py" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "html" -> "html"
        "css" -> "css"
        "markdown", "md" -> "md"
        "json" -> "json"
        "bash", "sh", "shell" -> "sh"
        "kotlin", "kt" -> "kt"
        "java" -> "java"
        "xml" -> "xml"
        "sql" -> "sql"
        "yaml", "yml" -> "yml"
        "c", "cpp", "c++" -> if (language == "c") "c" else "cpp"
        "rust", "rs" -> "rs"
        "go" -> "go"
        "swift" -> "swift"
        "ruby", "rb" -> "rb"
        "php" -> "php"
        "csv" -> "csv"
        else -> "txt"
    }

    /**
     * Saves [content] to Downloads/PocketMonk/[filename].
     * Returns the human-readable path on success.
     */
    fun save(context: Context, filename: String, content: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, filename, content)
        } else {
            saveDirectly(filename, content)
        }
    }

    private fun saveViaMediaStore(context: Context, filename: String, content: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/PocketMonk")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create file in Downloads")
        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        return "Downloads/PocketMonk/$filename"
    }

    private fun saveDirectly(filename: String, content: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PocketMonk"
        )
        dir.mkdirs()
        val file = File(dir, filename)
        file.writeText(content)
        return file.absolutePath
    }
}
