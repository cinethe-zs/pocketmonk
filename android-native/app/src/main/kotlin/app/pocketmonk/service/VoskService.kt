package app.pocketmonk.service

import android.app.Application
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class VoskModelEntry(
    val key: String,        // e.g. "en_small", "en_medium", "fr_large"
    val language: String,   // ISO code: "en", "fr", "es", …
    val size: String,       // "small" | "medium" | "large"
    val langLabel: String,  // "English", "French", …
    val sizeLabel: String,  // "Small", "Medium", "Large"
    val diskSize: String,   // "~40 MB"
    val modelName: String,  // Vosk directory name inside the zip
    val url: String,
    val desc: String,
)

class VoskService(private val application: Application) {

    companion object {
        const val SAMPLE_RATE = 16000

        val catalog: List<VoskModelEntry> = listOf(
            // ── English ──────────────────────────────────────────────────────
            VoskModelEntry("en_small",  "en", "small",  "English", "Small",  "~40 MB",
                "vosk-model-small-en-us-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                "Fast, good for most uses"),
            VoskModelEntry("en_medium", "en", "medium", "English", "Medium", "~128 MB",
                "vosk-model-en-us-0.22-lgraph",
                "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip",
                "Better accuracy, 3× larger"),
            VoskModelEntry("en_large",  "en", "large",  "English", "Large",  "~1.8 GB",
                "vosk-model-en-us-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip",
                "Best accuracy"),
            // ── French ───────────────────────────────────────────────────────
            VoskModelEntry("fr_small",  "fr", "small",  "French",  "Small",  "~41 MB",
                "vosk-model-small-fr-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
                "Rapide, bon pour la plupart des usages"),
            VoskModelEntry("fr_large",  "fr", "large",  "French",  "Large",  "~1.4 GB",
                "vosk-model-fr-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-fr-0.22.zip",
                "Meilleure précision"),
            // ── Spanish ──────────────────────────────────────────────────────
            VoskModelEntry("es_small",  "es", "small",  "Spanish", "Small",  "~39 MB",
                "vosk-model-small-es-0.42",
                "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
                "Rápido, bueno para la mayoría de usos"),
            VoskModelEntry("es_large",  "es", "large",  "Spanish", "Large",  "~1.5 GB",
                "vosk-model-es-0.42",
                "https://alphacephei.com/vosk/models/vosk-model-es-0.42.zip",
                "Mejor precisión"),
            // ── German ───────────────────────────────────────────────────────
            VoskModelEntry("de_small",  "de", "small",  "German",  "Small",  "~45 MB",
                "vosk-model-small-de-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
                "Schnell, gut für die meisten Zwecke"),
            VoskModelEntry("de_large",  "de", "large",  "German",  "Large",  "~1.9 GB",
                "vosk-model-de-0.21",
                "https://alphacephei.com/vosk/models/vosk-model-de-0.21.zip",
                "Beste Genauigkeit"),
            // ── Italian ──────────────────────────────────────────────────────
            VoskModelEntry("it_small",  "it", "small",  "Italian", "Small",  "~48 MB",
                "vosk-model-small-it-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip",
                "Veloce, buono per la maggior parte degli usi"),
            // ── Portuguese ───────────────────────────────────────────────────
            VoskModelEntry("pt_small",  "pt", "small",  "Portuguese", "Small", "~31 MB",
                "vosk-model-small-pt-0.3",
                "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip",
                "Rápido, bom para a maioria dos usos"),
            // ── Russian ──────────────────────────────────────────────────────
            VoskModelEntry("ru_small",  "ru", "small",  "Russian", "Small",  "~45 MB",
                "vosk-model-small-ru-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
                "Быстро, хорошо для большинства целей"),
            VoskModelEntry("ru_large",  "ru", "large",  "Russian", "Large",  "~1.5 GB",
                "vosk-model-ru-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-ru-0.22.zip",
                "Лучшая точность"),
            // ── Chinese ──────────────────────────────────────────────────────
            VoskModelEntry("zh_small",  "zh", "small",  "Chinese", "Small",  "~42 MB",
                "vosk-model-small-cn-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
                "Fast, good for most uses"),
            VoskModelEntry("zh_large",  "zh", "large",  "Chinese", "Large",  "~1.3 GB",
                "vosk-model-cn-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip",
                "Better accuracy"),
            // ── Japanese ─────────────────────────────────────────────────────
            VoskModelEntry("ja_small",  "ja", "small",  "Japanese", "Small", "~48 MB",
                "vosk-model-small-ja-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
                "Fast, good for most uses"),
        )

        private val SIZE_ORDER = mapOf("small" to 1, "medium" to 2, "large" to 3)
    }

    private val modelsDir: File
        get() = File(application.getExternalFilesDir(null), "vosk_models")

    fun modelDir(key: String): File {
        val entry = catalog.first { it.key == key }
        return File(modelsDir, entry.modelName)
    }

    fun isDownloaded(key: String) = modelDir(key).let { it.exists() && it.isDirectory }
    fun isAnyModelDownloaded() = catalog.any { isDownloaded(it.key) }
    fun downloadedLanguages(): Set<String> = catalog.filter { isDownloaded(it.key) }.map { it.language }.toSet()

    /** Returns the key of the best available model for (language, sizePref), or any model if none for that language. */
    fun bestModelKey(language: String, sizePref: String): String? {
        // Exact match
        val exact = "${language}_${sizePref}"
        if (isDownloaded(exact)) return exact
        // Same language, prefer larger sizes (better quality as fallback)
        catalog.filter { it.language == language && isDownloaded(it.key) }
            .maxByOrNull { SIZE_ORDER[it.size] ?: 0 }
            ?.let { return it.key }
        // Any downloaded model
        return catalog.firstOrNull { isDownloaded(it.key) }?.key
    }

    // ── Download ──────────────────────────────────────────────────────────────

    suspend fun download(key: String, onProgress: (Float) -> Unit): File {
        val entry = catalog.first { it.key == key }
        return downloadModel(entry.url, entry.modelName, onProgress)
    }

    private suspend fun downloadModel(
        url: String,
        modelName: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val zipFile = File(modelsDir, "$modelName.zip")
        val destDir = File(modelsDir, modelName)

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3600, TimeUnit.SECONDS)
            .build()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

        val body = response.body ?: throw IOException("Empty response body")
        val contentLength = body.contentLength()

        onProgress(-1f)
        body.byteStream().use { input ->
            zipFile.outputStream().buffered().use { output ->
                val buf = ByteArray(65_536)
                var downloaded = 0L
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (contentLength > 0) onProgress(downloaded.toFloat() / contentLength * 0.9f)
                }
            }
        }

        onProgress(0.92f)
        if (destDir.exists()) destDir.deleteRecursively()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(modelsDir, entry.name)
                if (entry.isDirectory) target.mkdirs()
                else { target.parentFile?.mkdirs(); target.outputStream().use { zis.copyTo(it) } }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        zipFile.delete()
        onProgress(1f)
        destDir
    }

    fun delete(key: String) { modelDir(key).deleteRecursively() }

    // ── Transcription ─────────────────────────────────────────────────────────

    suspend fun transcribeUri(
        uri: Uri,
        modelKey: String,
        onProgress: (Float) -> Unit = {},
        onPartialResult: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val model = Model(modelDir(modelKey).absolutePath)
        try {
            transcribeWithModel(model, uri, onProgress, onPartialResult)
        } finally {
            model.close()
        }
    }

    private fun transcribeWithModel(
        model: Model,
        uri: Uri,
        onProgress: (Float) -> Unit,
        onPartialResult: (String) -> Unit,
    ): String {
        val rec = Recognizer(model, SAMPLE_RATE.toFloat())
        rec.setMaxAlternatives(0)
        try {
            val pcm = decodeToPcm16(uri)
            if (pcm.isEmpty()) return ""

            val result = StringBuilder()
            val CHUNK = 4000   // 0.25 s at 16 kHz
            var offset = 0

            while (offset < pcm.size) {
                val end = minOf(offset + CHUNK, pcm.size)
                val chunk = pcm.copyOfRange(offset, end)

                if (rec.acceptWaveForm(chunk, chunk.size)) {
                    val text = extractText(rec.result)
                    if (text.isNotBlank()) {
                        result.append(text).append(' ')
                        onPartialResult(result.toString().trim())
                    }
                } else {
                    val partial = extractPartial(rec.partialResult)
                    if (partial.isNotBlank()) {
                        onPartialResult((result.toString() + partial).trim())
                    }
                }
                onProgress(end.toFloat() / pcm.size)
                offset = end
            }

            val finalText = extractText(rec.finalResult)
            if (finalText.isNotBlank()) result.append(finalText)
            return result.toString().trim()
        } finally {
            rec.close()
        }
    }

    private fun extractText(json: String) =
        Regex(""""text"\s*:\s*"([^"]*?)"""").find(json)?.groupValues?.get(1) ?: ""

    private fun extractPartial(json: String) =
        Regex(""""partial"\s*:\s*"([^"]*?)"""").find(json)?.groupValues?.get(1) ?: ""

    // ── Audio decode: any URI → 16 kHz mono 16-bit PCM ───────────────────────

    private fun decodeToPcm16(uri: Uri): ShortArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(application, uri, null)

        var trackIdx = -1
        var trackFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIdx = i; trackFormat = fmt; break
            }
        }
        if (trackIdx < 0) { extractor.release(); return ShortArray(0) }

        extractor.selectTrack(trackIdx)
        val mime = trackFormat!!.getString(MediaFormat.KEY_MIME)!!
        val srcRate = trackFormat.getIntSafe(MediaFormat.KEY_SAMPLE_RATE, 44100)
        val srcChannels = trackFormat.getIntSafe(MediaFormat.KEY_CHANNEL_COUNT, 1)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(trackFormat, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        val chunks = mutableListOf<ShortArray>()
        var totalSamples = 0
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, if (inputDone) 100_000L else 10_000L)
                when {
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        val sb = buf.asShortBuffer()
                        val raw = ShortArray(sb.limit()) { sb.get(it) }

                        val mono = if (srcChannels == 1) raw
                        else ShortArray(raw.size / srcChannels) { i ->
                            var s = 0L
                            repeat(srcChannels) { c -> s += raw[i * srcChannels + c] }
                            (s / srcChannels).toShort()
                        }

                        val resampled = if (srcRate == SAMPLE_RATE) mono
                        else {
                            val ratio = SAMPLE_RATE.toDouble() / srcRate
                            ShortArray((mono.size * ratio).toInt()) { i ->
                                mono[(i / ratio).toInt().coerceIn(0, mono.size - 1)]
                            }
                        }

                        chunks.add(resampled)
                        totalSamples += resampled.size
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* ignore */ }
                    else -> if (inputDone) outputDone = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val result = ShortArray(totalSamples)
        var offset = 0
        for (chunk in chunks) { chunk.copyInto(result, offset); offset += chunk.size }
        return result
    }

    private fun MediaFormat.getIntSafe(key: String, default: Int) =
        if (containsKey(key)) getInteger(key) else default
}
