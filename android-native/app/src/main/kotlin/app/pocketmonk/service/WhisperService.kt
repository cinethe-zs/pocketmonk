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
import kotlin.math.roundToInt

class WhisperService(private val context: Context) {

    companion object {
        const val MODEL_FILENAME = "ggml-base-q5_1.bin"
        const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"

        /** Maximum audio duration fed to whisper (10 minutes → avoid OOM). */
        private const val MAX_AUDIO_SEC = 10 * 60
        private const val WHISPER_SAMPLE_RATE = 16_000

        init {
            System.loadLibrary("whisper_jni")
        }

        @JvmStatic external fun nativeLoadModel(modelPath: String): Long
        @JvmStatic external fun nativeFreeModel(handle: Long)
        @JvmStatic external fun nativeTranscribe(
            handle: Long, samples: FloatArray, language: String
        ): String
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var modelHandle = 0L

    // ── File helpers ────────────────────────────────────────────────────────

    fun modelFile(): File {
        val dir = context.getExternalFilesDir(null)
            ?.let { File(it, "models") }
            ?: File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_FILENAME)
    }

    fun isModelDownloaded(): Boolean {
        val f = modelFile()
        return f.exists() && f.length() > 1_000_000L
    }

    // ── Model lifecycle ─────────────────────────────────────────────────────

    fun loadModel(): Boolean {
        if (modelHandle != 0L) return true
        val f = modelFile()
        if (!f.exists()) return false
        modelHandle = nativeLoadModel(f.absolutePath)
        return modelHandle != 0L
    }

    fun releaseModel() {
        if (modelHandle != 0L) {
            nativeFreeModel(modelHandle)
            modelHandle = 0L
        }
    }

    // ── Download ────────────────────────────────────────────────────────────

    /**
     * Downloads the Whisper model (~57 MB) from HuggingFace (public, no token needed).
     * Reports progress in [0..1] via [onProgress]. Cooperative cancellation.
     */
    suspend fun downloadModel(onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val file = modelFile()
            val request = Request.Builder().url(MODEL_URL).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw Exception("Download failed: HTTP ${response.code}")
            }
            val body = response.body ?: run {
                response.close()
                throw Exception("Empty response body")
            }
            val total = body.contentLength()
            var received = 0L
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(65_536)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        if (!coroutineContext.isActive) {
                            file.delete()
                            throw Exception("Cancelled")
                        }
                        output.write(buf, 0, read)
                        received += read
                        if (total > 0) onProgress(received.toFloat() / total)
                    }
                }
            }
            file
        }

    // ── Transcription ───────────────────────────────────────────────────────

    /**
     * Transcribes the audio track of [uri].
     * Returns the transcript string, or empty string if no speech was found or model unavailable.
     * Pass [language] as a two-letter BCP-47 code (e.g. "en", "fr") or "auto" for auto-detect.
     */
    suspend fun transcribeUri(uri: Uri, language: String = "auto"): String =
        withContext(Dispatchers.IO) {
            val samples = decodeAudio(uri) ?: return@withContext ""
            if (samples.isEmpty()) return@withContext ""
            if (!loadModel()) return@withContext ""
            nativeTranscribe(modelHandle, samples, language)
        }

    // ── Audio decoding ──────────────────────────────────────────────────────

    /**
     * Decodes the first audio track from [uri] into a 16 kHz mono float PCM array.
     * Caps at [MAX_AUDIO_SEC] seconds. Returns null on any error.
     */
    private fun decodeAudio(uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            // Locate the first audio track
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

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcm         = ArrayList<Float>(srcRate * 60) // pre-alloc ~1 min
            val info        = MediaCodec.BufferInfo()
            var inputDone   = false
            var outputDone  = false

            try {
                while (!outputDone) {
                    // Feed input buffers
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

                    // Drain output buffers
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                    if (outIdx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            // Mix all channels down to mono
                            val framesInBlock = shorts.remaining() / channelCount
                            repeat(framesInBlock) {
                                var mono = 0f
                                repeat(channelCount) { mono += shorts.get() / 32768f }
                                pcm.add(mono / channelCount)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            val raw = FloatArray(pcm.size) { pcm[it] }
            return if (srcRate == WHISPER_SAMPLE_RATE) raw
            else resample(raw, srcRate, WHISPER_SAMPLE_RATE)

        } catch (_: Exception) {
            return null
        } finally {
            extractor.release()
        }
    }

    /** Linear-interpolation resampler. */
    private fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        val ratio  = srcRate.toDouble() / dstRate
        val outLen = (input.size / ratio).roundToInt()
        return FloatArray(outLen) { i ->
            val pos  = i * ratio
            val idx  = pos.toInt().coerceAtMost(input.size - 2)
            val frac = (pos - idx).toFloat()
            input[idx] * (1f - frac) + input[idx + 1] * frac
        }
    }
}
