package app.pocketmonk.service

import android.content.Context
import java.io.File

data class ModelInfo(
    val name: String,
    val filename: String,
    val size: String,
    val description: String
)

class ModelManager(private val context: Context) {

    val catalog: List<ModelInfo> = listOf(
        ModelInfo(
            name = "Gemma 3 1B (INT4)",
            filename = "gemma-3-1b-it-int4.bin",
            size = "~600 MB",
            description = "Fastest, lowest quality"
        ),
        ModelInfo(
            name = "Gemma 2 2B (INT4)",
            filename = "gemma2-2b-it-gpu-int4.bin",
            size = "~1.3 GB",
            description = "Balanced"
        ),
        ModelInfo(
            name = "Gemma 2 2B (CPU)",
            filename = "gemma2-2b-it-cpu-int8.bin",
            size = "~2.6 GB",
            description = "Best quality, slowest"
        ),
    )

    fun modelsDirectory(): File {
        val external = context.getExternalFilesDir(null)
        val dir = if (external != null) {
            File(external, "models")
        } else {
            File(context.filesDir, "models")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listAvailableModels(): List<File> {
        return modelsDirectory()
            .listFiles { f -> f.isFile && f.extension == "bin" }
            ?.toList()
            ?: emptyList()
    }

    fun modelExists(filename: String): Boolean {
        return File(modelsDirectory(), filename).exists()
    }
}
