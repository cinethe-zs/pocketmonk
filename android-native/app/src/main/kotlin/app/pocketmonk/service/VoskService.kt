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

class VoskService(private val application: Application) {

    companion object {
        private const val EN_MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val FR_MODEL_NAME = "vosk-model-small-fr-0.22"
        private const val EN_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val FR_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
        private const val SAMPLE_RATE = 16000
    }

    private val modelsDir: File
        get() = File(application.getExternalFilesDir(null), "vosk_models")

    fun enModelDir(): File = File(modelsDir, EN_MODEL_NAME)
    fun frModelDir(): File = File(modelsDir, FR_MODEL_NAME)

    fun isEnModelDownloaded() = enModelDir().let { it.exists() && it.isDirectory }
    fun isFrModelDownloaded() = frModelDir().let { it.exists() && it.isDirectory }
    fun isAnyModelDownloaded() = isEnModelDownloaded() || isFrModelDownloaded()

    fun isModelDownloaded(language: String) = when (language) {
        "fr" -> isFrModelDownloaded()
        else -> isEnModelDownloaded()
    }

    fun bestAvailableLanguage(preferred: String): String? = when {
        isModelDownloaded(preferred) -> preferred
        preferred == "fr" && isEnModelDownloaded() -> "en"
        preferred == "en" && isFrModelDownloaded() -> "fr"
        else -> null
    }

    private fun modelDir(language: String) = when (language) {
        "fr" -> frModelDir()
        else -> enModelDir()
    }

    // ── Download ──────────────────────────────────────────────────────────────

    suspend fun downloadEnModel(onProgress: (Float) -> Unit): File =
        downloadModel(EN_MODEL_URL, EN_MODEL_NAME, onProgress)

    suspend fun downloadFrModel(onProgress: (Float) -> Unit): File =
        downloadModel(FR_MODEL_URL, FR_MODEL_NAME, onProgress)

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
            .readTimeout(600, TimeUnit.SECONDS)
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

    fun deleteEnModel() = enModelDir().deleteRecursively()
    fun deleteFrModel() = frModelDir().deleteRecursively()

    // ── Transcription ─────────────────────────────────────────────────────────

    suspend fun transcribeUri(
        uri: Uri,
        language: String = "en",
        onProgress: (Float) -> Unit = {},
        onPartialResult: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val model = Model(modelDir(language).absolutePath)
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

                        // Mix down to mono
                        val mono = if (srcChannels == 1) raw
                        else ShortArray(raw.size / srcChannels) { i ->
                            var s = 0L
                            repeat(srcChannels) { c -> s += raw[i * srcChannels + c] }
                            (s / srcChannels).toShort()
                        }

                        // Nearest-neighbour resample to 16 kHz
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
