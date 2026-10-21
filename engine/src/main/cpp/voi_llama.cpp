// JNI bridge for llama.cpp text polishing.
//
// Keeps the KV cache between calls and only re-evaluates the part of the prompt that
// changed, so the (long, constant) system prompt is processed once per style.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "VoiLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct LlamaHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string tmpl;
    std::vector<llama_token> cached; // tokens currently in the KV cache (seq 0)
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

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, true, true);
    std::vector<llama_token> out(n);
    llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), n, true, true);
    return out;
}

std::string apply_template(LlamaHandle * h, const std::vector<std::string> & roles,
                           const std::vector<std::string> & contents) {
    std::vector<llama_chat_message> msgs;
    for (size_t i = 0; i < roles.size(); ++i) msgs.push_back({roles[i].c_str(), contents[i].c_str()});
    const char * tmpl = h->tmpl.empty() ? nullptr : h->tmpl.c_str();
    std::vector<char> buf(4096);
    int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (n < 0) {
        // Unknown template: fall back to ChatML, which most small instruct models understand.
        n = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
        if (n < 0) n = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    return n > 0 ? std::string(buf.data(), n) : std::string();
}

// Evaluates `tokens` in chunks of n_batch starting at position `start`.
bool eval(LlamaHandle * h, std::vector<llama_token> & tokens, size_t start) {
    const size_t n_batch = llama_n_batch(h->ctx);
    for (size_t i = start; i < tokens.size(); i += n_batch) {
        int32_t n = (int32_t) std::min(n_batch, tokens.size() - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
        if (llama_decode(h->ctx, batch) != 0) return false;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_voiability_engine_LlamaNative_init(JNIEnv * env, jobject, jstring model_path, jint n_ctx, jint threads, jint threads_batch) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_log_set(quiet_log, nullptr);
        llama_backend_init();
        backend_ready = true;
    }
    auto path = to_string(env, model_path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("failed to load %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = n_ctx;
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 512;
    cparams.n_seq_max       = 1;
    cparams.n_threads       = threads;
    cparams.n_threads_batch = threads_batch;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cparams.no_perf         = true;

    auto * h = new LlamaHandle();
    h->model = model;
    h->ctx = llama_init_from_model(model, cparams);
    if (!h->ctx) {
        llama_model_free(model);
        delete h;
        return 0;
    }
    h->vocab = llama_model_get_vocab(model);
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl) h->tmpl = tmpl;

    // Greedy decoding: the polisher should be faithful, not creative.
    h->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(h->sampler, llama_sampler_init_greedy());

    llama_set_abort_callback(h->ctx, [](void * data) { return static_cast<LlamaHandle *>(data)->abort.load(); }, h);
    LOGI("loaded %s", path.c_str());
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_voiability_engine_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h) return;
    llama_sampler_free(h->sampler);
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_voiability_engine_LlamaNative_abort(JNIEnv *, jobject, jlong handle) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle);
    if (h) h->abort.store(true);
}

// Runs a chat completion. `roles`/`contents` describe the conversation; generated bytes are
// streamed to `sink.onBytes(byte[])`, which returns false to stop early. Returns the number of
// generated tokens, or -1 on failure.
extern "C" JNIEXPORT jint JNICALL
Java_com_voiability_engine_LlamaNative_generate(
        JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
        jint max_tokens, jobject sink) {
    auto * h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h) return -1;
    h->abort.store(false);

    std::vector<std::string> r, c;
    const jsize n_msgs = env->GetArrayLength(roles);
    for (jsize i = 0; i < n_msgs; ++i) {
        auto rs = (jstring) env->GetObjectArrayElement(roles, i);
        auto cs = (jstring) env->GetObjectArrayElement(contents, i);
        r.push_back(to_string(env, rs));
        c.push_back(to_string(env, cs));
        env->DeleteLocalRef(rs);
        env->DeleteLocalRef(cs);
    }

    std::string prompt = apply_template(h, r, c);
    std::vector<llama_token> tokens = tokenize(h->vocab, prompt);
    const int n_ctx = (int) llama_n_ctx(h->ctx);
    if ((int) tokens.size() + max_tokens >= n_ctx) {
        LOGE("prompt too long: %zu tokens", tokens.size());
        return -1;
    }

    // Reuse the longest common prefix with what is already in the KV cache.
    size_t common = 0;
    while (common < h->cached.size() && common < tokens.size() && h->cached[common] == tokens[common]) ++common;
    if (common == tokens.size()) --common; // always evaluate at least one token to get logits
    llama_memory_t mem = llama_get_memory(h->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    h->cached.assign(tokens.begin(), tokens.begin() + common);
    if (!eval(h, tokens, common)) {
        llama_memory_clear(mem, true);
        h->cached.clear();
        return -1;
    }
    h->cached = tokens;

    jclass cls = env->GetObjectClass(sink);
    jmethodID on_bytes = env->GetMethodID(cls, "onBytes", "([B)Z");

    llama_sampler_reset(h->sampler);
    int generated = 0;
    char piece[256];
    while (generated < max_tokens && !h->abort.load()) {
        llama_token tok = llama_sampler_sample(h->sampler, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        llama_sampler_accept(h->sampler, tok);

        int n = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) {
            jbyteArray arr = env->NewByteArray(n);
            env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(piece));
            jboolean keep_going = env->CallBooleanMethod(sink, on_bytes, arr);
            env->DeleteLocalRef(arr);
            if (!keep_going) break;
        }
        ++generated;

        llama_token next = tok;
        if (llama_decode(h->ctx, llama_batch_get_one(&next, 1)) != 0) break;
        h->cached.push_back(tok);
    }
    return generated;
}
