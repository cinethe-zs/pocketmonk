package app.pocketmonk.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ModelEntry(
    val id: String,
    val name: String,
    val filename: String,          // local filename to save as
    val sizeLabel: String,
    val description: String,
    val hfRepo: String,            // e.g. "litert-community/Gemma3-1B-IT"
    val hfFilename: String,        // file inside the HF repo
    val recommendedForPixel7a: Boolean = false,
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val modelId: String, val progress: Float) : DownloadState()
    data class Done(val modelPath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("pocketmonk_prefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // no timeout for large downloads
        .followRedirects(true)
        .build()

    // ── Model catalog ────────────────────────────────────────────────────────

    val catalog: List<ModelEntry> = listOf(
        ModelEntry(
            id = "gemma3-1b-int4",
            name = "Gemma 3 1B IT (INT4)",
            filename = "gemma3-1b-it-int4.task",
            sizeLabel = "~600 MB",
            description = "Fastest. Recommended for Pixel 7a.",
            hfRepo = "litert-community/Gemma3-1B-IT",
            hfFilename = "gemma3-1b-it-int4.task",
            recommendedForPixel7a = true,
        ),
        ModelEntry(
            id = "gemma3-1b-int4-4k",
            name = "Gemma 3 1B IT (INT4, 4096 ctx)",
            filename = "Gemma3-1B-IT_multi-prefill-seq_q4_block128_ekv4096.task",
            sizeLabel = "~700 MB",
            description = "Larger context window (4096 tokens).",
            hfRepo = "litert-community/Gemma3-1B-IT",
            hfFilename = "Gemma3-1B-IT_multi-prefill-seq_q4_block128_ekv4096.task",
        ),
        ModelEntry(
            id = "gemma2-2b-int8",
            name = "Gemma 2 2B IT (INT8)",
            filename = "Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task",
            sizeLabel = "~2.6 GB",
            description = "Better quality, needs 3+ GB free RAM.",
            hfRepo = "litert-community/Gemma2-2B-IT",
            hfFilename = "Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task",
        ),
        ModelEntry(
            id = "gemma3-4b-int4",
            name = "Gemma 3 4B IT (INT4)",
            filename = "gemma3-4b-it-int4-web.task",
            sizeLabel = "~2.6 GB",
            description = "Best quality available. Needs 3+ GB free RAM. Experimental — report issues if it fails to load.",
            hfRepo = "litert-community/Gemma3-4B-IT",
            hfFilename = "gemma3-4b-it-int4-web.task",
        ),
    )

    // ── Directory & file helpers ─────────────────────────────────────────────

    fun modelsDirectory(): File {
        val dir = context.getExternalFilesDir(null)
            ?.let { File(it, "models") }
            ?: File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun modelFile(entry: ModelEntry): File = File(modelsDirectory(), entry.filename)

    fun isDownloaded(entry: ModelEntry): Boolean {
        val f = modelFile(entry)
        return f.exists() && f.length() > 100_000L
    }

    fun listLocalFiles(): List<File> =
        modelsDirectory().listFiles { f ->
            f.isFile && (f.extension == "task" || f.extension == "bin")
        }?.toList() ?: emptyList()

    // ── Credentials ──────────────────────────────────────────────────────────

    fun getHfToken(): String? = prefs.getString("hf_token", null)

    fun saveHfToken(token: String) =
        prefs.edit().putString("hf_token", token.trim()).apply()

    fun hasHfToken(): Boolean = !getHfToken().isNullOrBlank()

    fun clearHfToken() = prefs.edit().remove("hf_token").apply()

    // ── Active model ─────────────────────────────────────────────────────────

    fun getActiveModelPath(): String? = prefs.getString("active_model_path", null)

    fun setActiveModelPath(path: String) =
        prefs.edit().putString("active_model_path", path).apply()

    fun clearActiveModelPath() =
        prefs.edit().remove("active_model_path").apply()

    // ── Download ─────────────────────────────────────────────────────────────

    /**
     * Downloads [entry] from HuggingFace.
     * Calls [onProgress] with (bytesDownloaded, totalBytes) on each chunk.
     * Returns the downloaded [File] on success, or throws on failure.
     * Cancellation is cooperative via coroutine [isActive].
     */
    suspend fun downloadModel(
        entry: ModelEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val token = getHfToken()
        val url = "https://huggingface.co/${entry.hfRepo}/resolve/main/${entry.hfFilename}"

        val requestBuilder = Request.Builder().url(url)
        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            when (code) {
                401, 403 -> throw Exception(
                    "Access denied (HTTP $code). " +
                    "Accept the license at huggingface.co/${entry.hfRepo} " +
                    "(different from google/gemma — each repo requires its own acceptance). " +
                    "Also verify your token is a valid Read token."
                )
                404 -> throw Exception(
                    "Model file not found (HTTP 404). " +
                    "Check the HuggingFace repo for the correct filename."
                )
                else -> throw Exception("Download failed: HTTP $code")
            }
        }

        val body = response.body ?: run {
            response.close()
            throw Exception("Empty response body")
        }

        val totalBytes = body.contentLength()  // -1 if unknown
        val destFile = modelFile(entry)
        val tmpFile  = File(destFile.parent, "${entry.filename}.tmp")

        try {
            var downloaded = 0L
            tmpFile.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(8 * 1024)
                    while (isActive) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, totalBytes)
                    }
                    if (!isActive) {
                        tmpFile.delete()
                        throw Exception("Download cancelled")
                    }
                }
            }
            tmpFile.renameTo(destFile)
            destFile
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        } finally {
            response.close()
        }
    }

    fun deleteModel(entry: ModelEntry) = modelFile(entry).delete()
}
