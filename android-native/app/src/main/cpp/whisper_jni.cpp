#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_pocketmonk_service_WhisperService_nativeLoadModel(
    JNIEnv *env, jclass /*clazz*/, jstring modelPath)
{
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    // Try GPU first; fall back to CPU if Vulkan is unavailable on this device.
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = true;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    if (ctx == nullptr) {
        LOGE("GPU init failed, retrying with CPU");
        params.use_gpu = false;
        ctx = whisper_init_from_file_with_params(path, params);
    }
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("Failed to load whisper model");
        return 0L;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_app_pocketmonk_service_WhisperService_nativeFreeModel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle)
{
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

// ── Per-state lifecycle (for parallel chunk processing) ──────────────────────

JNIEXPORT jlong JNICALL
Java_app_pocketmonk_service_WhisperService_nativeInitState(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong ctxHandle)
{
    auto *ctx = reinterpret_cast<whisper_context *>(ctxHandle);
    if (!ctx) return 0L;
    return reinterpret_cast<jlong>(whisper_init_state(ctx));
}

JNIEXPORT void JNICALL
Java_app_pocketmonk_service_WhisperService_nativeFreeState(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong stateHandle)
{
    auto *state = reinterpret_cast<whisper_state *>(stateHandle);
    if (state) whisper_free_state(state);
}

// ── Progress callback (used by single-chunk nativeTranscribe only) ────────────

struct ProgressCbData {
    JNIEnv   *env;
    jobject   callbackObj;
    jmethodID onProgressMethod;
};

static void whisper_progress_cb(
    struct whisper_context * /*ctx*/,
    struct whisper_state   * /*state*/,
    int progress,
    void *user_data)
{
    auto *data = static_cast<ProgressCbData *>(user_data);
    data->env->CallVoidMethod(
        data->callbackObj,
        data->onProgressMethod,
        static_cast<jint>(progress));
}

// ── Single-chunk transcription (fallback / very short audio) ─────────────────

JNIEXPORT jstring JNICALL
Java_app_pocketmonk_service_WhisperService_nativeTranscribe(
    JNIEnv *env, jclass /*clazz*/, jlong handle,
    jfloatArray samples, jstring language, jobject progressCallback)
{
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");

    const char *lang      = env->GetStringUTFChars(language, nullptr);
    jsize       n_samples = env->GetArrayLength(samples);
    jfloat     *pcm       = env->GetFloatArrayElements(samples, nullptr);

    jclass    cbClass  = env->GetObjectClass(progressCallback);
    jmethodID cbMethod = env->GetMethodID(cbClass, "onProgress", "(I)V");
    ProgressCbData cbData { env, progressCallback, cbMethod };

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language                    = (lang[0] == '\0' || (lang[0] == 'a' && lang[1] == 'u'))
                                         ? nullptr : lang;
    params.translate                   = false;
    params.print_progress              = false;
    params.print_timestamps            = false;
    params.single_segment              = false;
    params.n_threads                   = 8;
    params.no_context                  = true;
    params.progress_callback           = whisper_progress_cb;
    params.progress_callback_user_data = &cbData;

    int rc = whisper_full(ctx, params, pcm, static_cast<int>(n_samples));

    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return env->NewStringUTF("");
    }

    std::string result;
    int n_seg = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_seg; i++) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) result += seg;
    }

    size_t start = result.find_first_not_of(" \t\n\r");
    size_t end   = result.find_last_not_of(" \t\n\r");
    if (start == std::string::npos) return env->NewStringUTF("");
    return env->NewStringUTF(result.substr(start, end - start + 1).c_str());
}

// ── Parallel chunk transcription ──────────────────────────────────────────────
// Uses a dedicated whisper_state so multiple chunks can run concurrently on
// the same model context without contention.

JNIEXPORT jstring JNICALL
Java_app_pocketmonk_service_WhisperService_nativeTranscribeChunk(
    JNIEnv *env, jclass /*clazz*/,
    jlong ctxHandle, jlong stateHandle,
    jfloatArray samples, jstring language, jint nThreads)
{
    auto *ctx   = reinterpret_cast<whisper_context *>(ctxHandle);
    auto *state = reinterpret_cast<whisper_state   *>(stateHandle);
    if (!ctx || !state) return env->NewStringUTF("");

    const char *lang      = env->GetStringUTFChars(language, nullptr);
    jsize       n_samples = env->GetArrayLength(samples);
    jfloat     *pcm       = env->GetFloatArrayElements(samples, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language         = (lang[0] == '\0' || (lang[0] == 'a' && lang[1] == 'u'))
                              ? nullptr : lang;
    params.translate        = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.single_segment   = false;
    params.n_threads        = static_cast<int>(nThreads);
    params.no_context       = true;
    // No progress callback — progress is tracked per completed chunk in Kotlin.

    int rc = whisper_full_with_state(ctx, state, params,
                                     pcm, static_cast<int>(n_samples));

    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (rc != 0) {
        LOGE("whisper_full_with_state failed: %d", rc);
        return env->NewStringUTF("");
    }

    std::string result;
    int n_seg = whisper_full_n_segments_from_state(state);
    for (int i = 0; i < n_seg; i++) {
        const char *seg = whisper_full_get_segment_text_from_state(state, i);
        if (seg) result += seg;
    }

    size_t start = result.find_first_not_of(" \t\n\r");
    size_t end   = result.find_last_not_of(" \t\n\r");
    if (start == std::string::npos) return env->NewStringUTF("");
    return env->NewStringUTF(result.substr(start, end - start + 1).c_str());
}

} // extern "C"
