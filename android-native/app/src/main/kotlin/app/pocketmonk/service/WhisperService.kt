package app.pocketmonk.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class WhisperService(private val context: Context) {

    companion object {
        const val TINY_MODEL_FILENAME = "ggml-tiny-q5_1.bin"
        const val TINY_MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"

        const val BASE_MODEL_FILENAME = "ggml-base-q5_1.bin"
        const val BASE_MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"

        // Legacy alias kept for ViewModel back-compat
        const val MODEL_FILENAME = BASE_MODEL_FILENAME
        const val MODEL_URL = BASE_MODEL_URL

        /** Maximum audio duration fed to whisper (5 minutes → avoid OOM). */
        private const val MAX_AUDIO_SEC = 5 * 60
        private const val WHISPER_SAMPLE_RATE = 16_000

        /** Called from C++ with values 0–100 during whisper_full. */
        fun interface ProgressCallback {
            fun onProgress(percent: Int)
        }

        init {
            System.loadLibrary("whisper_jni")
        }

        @JvmStatic external fun nativeLoadModel(modelPath: String): Long
        @JvmStatic external fun nativeFreeModel(handle: Long)
        @JvmStatic external fun nativeTranscribe(
            handle: Long, samples: FloatArray, language: String,
            progressCallback: ProgressCallback,
        ): String
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var modelHandle = 0L
    private var loadedModelPath: String? = null

    // ── File helpers ────────────────────────────────────────────────────────

    private fun modelsDir(): File {
        val dir = context.getExternalFilesDir(null)
            ?.let { File(it, "models") }
            ?: File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun tinyModelFile(): File = File(modelsDir(), TINY_MODEL_FILENAME)
    fun modelFile(): File = File(modelsDir(), BASE_MODEL_FILENAME)  // base = legacy

    fun isTinyModelDownloaded(): Boolean = tinyModelFile().let { it.exists() && it.length() > 500_000L }
    fun isBaseModelDownloaded(): Boolean = modelFile().let { it.exists() && it.length() > 1_000_000L }

    /** True if any Whisper model is available for transcription. */
    fun isModelDownloaded(): Boolean = isTinyModelDownloaded() || isBaseModelDownloaded()

    // ── Model lifecycle ─────────────────────────────────────────────────────

    /** Loads the best available model (tiny preferred). Returns false if none available. */
    fun loadModel(): Boolean {
        val target = when {
            isTinyModelDownloaded() -> tinyModelFile()
            isBaseModelDownloaded() -> modelFile()
            else -> return false
        }
        if (modelHandle != 0L && loadedModelPath == target.absolutePath) return true
        // Model changed or not yet loaded — (re)load.
        if (modelHandle != 0L) { nativeFreeModel(modelHandle); modelHandle = 0L; loadedModelPath = null }
        modelHandle = nativeLoadModel(target.absolutePath)
        if (modelHandle != 0L) loadedModelPath = target.absolutePath
        return modelHandle != 0L
    }

    fun releaseModel() {
        if (modelHandle != 0L) {
            nativeFreeModel(modelHandle)
            modelHandle = 0L
            loadedModelPath = null
        }
    }

    // ── Download ────────────────────────────────────────────────────────────

    private suspend fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); throw Exception("Download failed: HTTP ${response.code}") }
            val body = response.body ?: run { response.close(); throw Exception("Empty response body") }
            val total = body.contentLength()
            var received = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(65_536)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        if (!coroutineContext.isActive) { dest.delete(); throw Exception("Cancelled") }
                        output.write(buf, 0, read)
                        received += read
                        if (total > 0) onProgress(received.toFloat() / total)
                    }
                }
            }
        }

    /** Downloads the Tiny Whisper model (~31 MB). Cooperative cancellation. */
    suspend fun downloadTinyModel(onProgress: (Float) -> Unit): File {
        val f = tinyModelFile()
        downloadFile(TINY_MODEL_URL, f, onProgress)
        return f
    }

    /** Downloads the Base Whisper model (~57 MB). Cooperative cancellation. */
    suspend fun downloadModel(onProgress: (Float) -> Unit): File {
        val f = modelFile()
        downloadFile(BASE_MODEL_URL, f, onProgress)
        return f
    }

    // ── Transcription ───────────────────────────────────────────────────────

    /**
     * Transcribes the audio track of [uri].
     * Returns the transcript string, or empty string if no speech was found or model unavailable.
     * Pass [language] as a two-letter BCP-47 code (e.g. "en", "fr") or "auto" for auto-detect.
     */
    suspend fun transcribeUri(
        uri: Uri,
        language: String = "auto",
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val samples = decodeAudio(uri) ?: return@withContext ""
        if (samples.isEmpty()) return@withContext ""
        if (!loadModel()) return@withContext ""
        nativeTranscribe(modelHandle, samples, language) { percent ->
            onProgress(percent / 100f)
        }
    }

    // ── Audio decoding ──────────────────────────────────────────────────────

    /**
     * Decodes the first audio track from [uri] into a 16 kHz mono float PCM array.
     * Resamples on-the-fly into a pre-allocated output buffer — no ArrayList boxing,
     * no intermediate source-rate buffer. Caps at [MAX_AUDIO_SEC] seconds.
     * Returns null on any error.
     */
    private fun decodeAudio(uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            val mime         = format.getString(MediaFormat.KEY_MIME) ?: return null
            val srcRate      = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val maxUs        = MAX_AUDIO_SEC * 1_000_000L

            // Pre-allocate output at 16 kHz — avoids ArrayList<Float> boxing overhead.
            val durationUs = runCatching {
                if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION)
                else maxUs
            }.getOrDefault(maxUs).coerceAtMost(maxUs)
            val maxOut = ((WHISPER_SAMPLE_RATE.toLong() * durationUs + 999_999L) / 1_000_000L).toInt() +
                WHISPER_SAMPLE_RATE  // +1 s headroom
            val out = FloatArray(maxOut)
            var outCount = 0

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info       = MediaCodec.BufferInfo()
            var inputDone  = false
            var outputDone = false
            // src samples per output sample (ratio ≥ 1 for downsampling)
            val ratio    = srcRate.toDouble() / WHISPER_SAMPLE_RATE
            var srcTotal = 0L   // total mono source frames decoded so far
            var prevMono = 0f   // last mono sample of previous codec block (boundary interpolation)

            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx) ?: continue
                            val read  = extractor.readSampleData(inBuf, 0)
                            if (read < 0 || extractor.sampleTime > maxUs) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inIdx, 0, read, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                    if (outIdx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            outputDone = true

                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / channelCount

                            // Decode this codec block into a small temp mono array (no boxing).
                            val block = FloatArray(frames)
                            for (i in 0 until frames) {
                                var m = 0f
                                repeat(channelCount) { m += shorts.get() / 32768f }
                                block[i] = m / channelCount
                            }

                            // Resample block on-the-fly into `out` via linear interpolation.
                            // We need both s0 and s1 to be within [prevMono .. block[frames-1]].
                            while (outCount < maxOut) {
                                val srcPos = outCount.toDouble() * ratio
                                val s0Idx  = srcPos.toLong()
                                val b0     = (s0Idx - srcTotal).toInt()
                                val b1     = b0 + 1
                                // Stop when s1 (b1) would fall outside the current block.
                                if (b0 >= frames - 1) break
                                val s0 = if (b0 < 0) prevMono else block[b0]
                                val s1 = block[b1]
                                out[outCount++] = s0 + (s1 - s0) * (srcPos - s0Idx).toFloat()
                            }

                            if (frames > 0) prevMono = block[frames - 1]
                            srcTotal += frames
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            return if (outCount == 0) null else out.copyOf(outCount)

        } catch (_: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }
}
