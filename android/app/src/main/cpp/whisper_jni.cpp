#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define TAG "EchoWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

struct ProgressCtx {
    JNIEnv *env;
    jobject listener;
    jmethodID onProgress;
    jmethodID isCancelled;
};

void on_progress(whisper_context *, whisper_state *, int progress, void *user_data) {
    auto *p = static_cast<ProgressCtx *>(user_data);
    if (p->listener) p->env->CallVoidMethod(p->listener, p->onProgress, progress);
}

bool on_abort(void *user_data) {
    auto *p = static_cast<ProgressCtx *>(user_data);
    return p->listener && p->env->CallBooleanMethod(p->listener, p->isCancelled);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_de_hoefer_nookbot_whisper_WhisperLib_initContext(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_de_hoefer_nookbot_whisper_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

// Gibt UTF-8-Bytes zurück: Whisper kann Mehrbyte-Zeichen an Token-Grenzen teilen,
// NewStringUTF würde daran abstürzen. Kotlin dekodiert tolerant.
JNIEXPORT jbyteArray JNICALL
Java_de_hoefer_nookbot_whisper_WhisperLib_transcribe(JNIEnv *env, jobject, jlong ptr, jfloatArray samples,
                                                 jstring language, jint threads, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return nullptr;

    jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);

    ProgressCtx pctx{env, listener, nullptr, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        pctx.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        pctx.isCancelled = env->GetMethodID(cls, "isCancelled", "()Z");
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang;
    params.translate = false;
    params.no_context = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &pctx;
    params.abort_callback = on_abort;
    params.abort_callback_user_data = &pctx;

    int rc = whisper_full(ctx, params, data, n);

    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (rc != 0) {
        LOGI("whisper_full failed: %d", rc);
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    int64_t prev_end = 0;
    for (int i = 0; i < segments; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i); // in 10 ms
        // Längere Sprechpause → neuer Absatz, sonst Leerzeichen.
        if (!text.empty()) text += (t0 - prev_end > 150) ? "\n\n" : " ";
        std::string seg = whisper_full_get_segment_text(ctx, i);
        size_t start = seg.find_first_not_of(' ');
        text += (start == std::string::npos) ? "" : seg.substr(start);
        prev_end = whisper_full_get_segment_t1(ctx, i);
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

JNIEXPORT jstring JNICALL
Java_de_hoefer_nookbot_whisper_WhisperLib_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
