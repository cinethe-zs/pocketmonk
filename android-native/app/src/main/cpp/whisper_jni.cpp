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
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
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
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

// ── Progress callback ────────────────────────────────────────────────────────

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
    // whisper_full is synchronous and called on the JNI thread, so env is valid.
    data->env->CallVoidMethod(
        data->callbackObj,
        data->onProgressMethod,
        static_cast<jint>(progress));
}

// ── Transcription ────────────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_app_pocketmonk_service_WhisperService_nativeTranscribe(
    JNIEnv *env, jclass /*clazz*/, jlong handle,
    jfloatArray samples, jstring language, jobject progressCallback)
{
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");

    const char *lang     = env->GetStringUTFChars(language, nullptr);
    jsize       n_samples = env->GetArrayLength(samples);
    jfloat     *pcm      = env->GetFloatArrayElements(samples, nullptr);

    // Resolve the onProgress(Int) method on the callback object.
    jclass    cbClass   = env->GetObjectClass(progressCallback);
    jmethodID cbMethod  = env->GetMethodID(cbClass, "onProgress", "(I)V");
    ProgressCbData cbData { env, progressCallback, cbMethod };

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language                  = (lang[0] == '\0' || (lang[0] == 'a' && lang[1] == 'u'))
                                       ? nullptr : lang;
    params.translate                 = false;
    params.print_progress            = false;
    params.print_timestamps          = false;
    params.single_segment            = false;
    params.n_threads                 = 8;
    params.no_context                = true;
    params.progress_callback         = whisper_progress_cb;
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
    result = result.substr(start, end - start + 1);

    return env->NewStringUTF(result.c_str());
}

} // extern "C"
