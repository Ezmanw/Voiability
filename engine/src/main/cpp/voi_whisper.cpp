// JNI bridge for whisper.cpp speech-to-text.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "VoiWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct WhisperHandle {
    whisper_context * ctx = nullptr;
    std::atomic<bool> abort{false};
};

void quiet_log(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, TAG, text);
}

std::string to_string(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java's NewStringUTF expects modified UTF-8, which breaks on emoji and other 4-byte
// sequences, so hand raw bytes back and let Kotlin decode them.
jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_voiability_engine_WhisperNative_init(JNIEnv * env, jobject, jstring model_path) {
    whisper_log_set(quiet_log, nullptr);
    auto path = to_string(env, model_path);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = true;
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (!ctx) {
        LOGE("failed to load %s", path.c_str());
        return 0;
    }
    auto * h = new WhisperHandle();
    h->ctx = ctx;
    LOGI("loaded %s", path.c_str());
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_voiability_engine_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<WhisperHandle *>(handle);
    if (!h) return;
    whisper_free(h->ctx);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_voiability_engine_WhisperNative_abort(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<WhisperHandle *>(handle);
    if (h) h->abort.store(true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_voiability_engine_WhisperNative_isMultilingual(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<WhisperHandle *>(handle);
    return h && whisper_is_multilingual(h->ctx) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_voiability_engine_WhisperNative_transcribe(
        JNIEnv * env, jobject, jlong handle, jfloatArray samples, jint n_samples,
        jstring language, jstring prompt, jstring vad_model, jint threads, jboolean fast) {
    auto * h = reinterpret_cast<WhisperHandle *>(handle);
    if (!h) return nullptr;
    h->abort.store(false);

    std::vector<float> pcm(n_samples);
    env->GetFloatArrayRegion(samples, 0, n_samples, pcm.data());

    auto lang = to_string(env, language);
    auto initial_prompt = to_string(env, prompt);
    auto vad_path = to_string(env, vad_model);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads        = threads;
    p.translate        = false;
    p.no_context       = true;
    p.no_timestamps    = true;
    p.single_segment   = false;
    p.print_special    = false;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_timestamps = false;
    p.suppress_blank   = true;
    p.suppress_nst     = true;
    p.language         = lang.empty() ? "auto" : lang.c_str();
    p.detect_language  = false;
    p.initial_prompt   = initial_prompt.empty() ? nullptr : initial_prompt.c_str();
    p.carry_initial_prompt = true;
    if (fast) {
        // Live preview: no temperature fallback, it only needs to look right.
        p.temperature_inc = 0.0f;
    }
    if (!vad_path.empty()) {
        p.vad = true;
        p.vad_model_path = vad_path.c_str();
        p.vad_params = whisper_vad_default_params();
        p.vad_params.min_silence_duration_ms = 300;
        p.vad_params.speech_pad_ms = 120;
    }
    p.abort_callback = [](void * data) { return static_cast<WhisperHandle *>(data)->abort.load(); };
    p.abort_callback_user_data = h;

    if (whisper_full(h->ctx, p, pcm.data(), n_samples) != 0) {
        return nullptr;
    }

    std::string text;
    const int n = whisper_full_n_segments(h->ctx);
    for (int i = 0; i < n; ++i) {
        text += whisper_full_get_segment_text(h->ctx, i);
    }
    return to_bytes(env, text);
}
