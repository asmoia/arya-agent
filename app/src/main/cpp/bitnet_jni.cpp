/*
 * bitnet_jni.cpp — JNI bridge between Kotlin (BitNetNative) and llama.cpp.
 *
 * Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
 *
 * Compatible with llama.cpp API as of b9999+ (post-llama-model-api refactor).
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <unistd.h>    // sysconf on Android
#include <sys/sysinfo.h> // get_nprocs on Android

#include "llama.h"

#define LOG_TAG "BitNetJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct ModelContext {
    llama_model * model;
    llama_context * ctx;
    const llama_vocab * vocab;
};

static ModelContext * handle_to_ctx(jlong handle) {
    return reinterpret_cast<ModelContext *>(handle);
}

// ---- Wrapper for llama_tokenize (new API: returns count, needs pre-allocated buffer) ----
static std::vector<llama_token> tokenize_string(
    const llama_vocab * vocab,
    const std::string & text,
    bool add_bos,
    bool parse_special)
{
    // First call: get required size
    const int32_t max_tokens_est = text.size() + 128;  // generous estimate
    std::vector<llama_token> tokens(max_tokens_est);

    int32_t n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                               tokens.data(), max_tokens_est, add_bos, parse_special);

    if (n < 0) {
        // Buffer too small; n is the negative of the required size
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                           tokens.data(), -n, add_bos, parse_special);
    }

    if (n > 0) {
        tokens.resize(n);
    } else {
        tokens.clear();
    }
    return tokens;
}

static int get_nprocs_onln() {
#ifdef __ANDROID__
    return get_nprocs();
#else
    return sysconf(_SC_NPROCESSORS_ONLN);
#endif
}

// ---- Parse stop sequences from JSON array ----
static std::vector<std::string> parse_stop_sequences(const char * json) {
    std::vector<std::string> result;
    if (!json) return result;
    std::string s(json);
    bool in_str = false;
    std::string cur;
    for (char c : s) {
        if (c == '"' && !in_str) { in_str = true; cur.clear(); }
        else if (c == '"' && in_str) { in_str = false; if (!cur.empty()) result.push_back(cur); }
        else if (in_str) { cur += c; }
    }
    return result;
}

// ---- Completion engine (shared by blocking & future streaming) ----
static std::string do_completion(
    llama_context * ctx,
    const llama_vocab * vocab,
    const std::vector<llama_token> & prompt_tokens,
    int max_tokens,
    float temperature,
    float top_p,
    int top_k,
    float repeat_penalty,
    const std::vector<std::string> & stop_strs)
{
    const int n_ctx = llama_n_ctx(ctx);
    std::vector<llama_token> tokens = prompt_tokens;

    // Sampler chain
    auto * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result;

    for (int i = 0; i < max_tokens; i++) {
        if ((int)tokens.size() >= n_ctx - 1) break;

        if (llama_decode(ctx, llama_batch_get_one(tokens.data(), tokens.size())) != 0) {
            LOGE("llama_decode failed at token %d", i);
            break;
        }

        llama_token new_token = llama_sampler_sample(smpl, ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            buf[n] = '\0';
            result.append(buf, n);
        }

        tokens.push_back(new_token);

        // Check stop sequences
        bool stopped = false;
        for (const auto & stop : stop_strs) {
            if (result.length() >= stop.length() &&
                result.compare(result.length() - stop.length(), stop.length(), stop) == 0) {
                result.erase(result.length() - stop.length());
                stopped = true;
                break;
            }
        }
        if (stopped) break;
    }

    llama_sampler_free(smpl);
    return result;
}

// ==================== JNI Methods ====================

extern "C" JNIEXPORT jlong JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeLoadModel(
    JNIEnv * env, jobject, jstring model_path, jint n_ctx, jint n_threads)
{
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) { LOGE("nativeLoadModel: null path"); return -1; }
    LOGI("Loading model: %s  n_ctx=%d  n_threads=%d", path, n_ctx, n_threads);

    if (n_threads <= 0) {
        n_threads = get_nprocs_onln();
        if (n_threads > 4) n_threads = 4;
    }

    llama_backend_init();

    // Model params — llama_model_default_params returns a struct, not a pointer
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;  // CPU-only for now

    llama_model * model = llama_model_load_from_file(path, model_params);
    if (!model) {
        LOGE("Failed to load model: %s", path);
        env->ReleaseStringUTFChars(model_path, path);
        return -2;
    }

    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = n_ctx;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.n_batch         = 512;
    ctx_params.embeddings      = false;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context for: %s", path);
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path, path);
        return -3;
    }

    env->ReleaseStringUTFChars(model_path, path);

    const llama_vocab * vocab = llama_model_get_vocab(model);
    auto * mc = new ModelContext{model, ctx, vocab};
    LOGI("Model loaded successfully, handle=%p", mc);
    return reinterpret_cast<jlong>(mc);
}

extern "C" JNIEXPORT void JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeFreeModel(
    JNIEnv *, jobject, jlong handle)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc) return;
    LOGI("Freeing model handle=%p", mc);
    if (mc->ctx)   llama_free(mc->ctx);
    if (mc->model) llama_model_free(mc->model);
    delete mc;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeCompletion(
    JNIEnv * env, jobject,
    jlong handle, jstring prompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k, jfloat repeat_penalty,
    jstring stop_sequences_json)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->ctx || !mc->vocab) {
        LOGE("nativeCompletion: invalid handle");
        return nullptr;
    }

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_str) return nullptr;

    const llama_vocab * vocab = mc->vocab;

    // Tokenize — newer llama.cpp returns std::vector<llama_token>
    // and takes (vocab, text, add_bos, special)
    bool add_bos = llama_vocab_get_add_bos(vocab);
    std::vector<llama_token> tokens = tokenize_string(vocab, std::string(prompt_str), add_bos, true);

    env->ReleaseStringUTFChars(prompt, prompt_str);

    // Parse stop sequences
    const char * stops_json = env->GetStringUTFChars(stop_sequences_json, nullptr);
    auto stop_strs = parse_stop_sequences(stops_json);
    if (stops_json) env->ReleaseStringUTFChars(stop_sequences_json, stops_json);

    // Run completion
    std::string result = do_completion(mc->ctx, vocab, tokens,
        max_tokens, temperature, top_p, top_k, repeat_penalty, stop_strs);

    return env->NewStringUTF(result.c_str());
}

// ---- Tokenization helpers ----

extern "C" JNIEXPORT jintArray JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeTokenize(
    JNIEnv * env, jobject, jlong handle, jstring text)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->vocab) return nullptr;

    const char * text_str = env->GetStringUTFChars(text, nullptr);
    if (!text_str) return nullptr;

    std::vector<llama_token> tokens = tokenize_string(mc->vocab, std::string(text_str), false, true);
    env->ReleaseStringUTFChars(text, text_str);

    jintArray result = env->NewIntArray(static_cast<jsize>(tokens.size()));
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(tokens.size()),
                           reinterpret_cast<const jint *>(tokens.data()));
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeDetokenize(
    JNIEnv * env, jobject, jlong handle, jintArray tokens)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->vocab) return nullptr;

    jsize len = env->GetArrayLength(tokens);
    jint * arr = env->GetIntArrayElements(tokens, nullptr);
    if (!arr) return nullptr;

    std::string result;
    for (jsize i = 0; i < len; i++) {
        char buf[256];
        int n = llama_token_to_piece(mc->vocab, static_cast<llama_token>(arr[i]),
                                     buf, sizeof(buf), 0, true);
        if (n > 0) result.append(buf, n);
    }

    env->ReleaseIntArrayElements(tokens, arr, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeEosToken(
    JNIEnv *, jobject, jlong handle)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->vocab) return -1;
    return static_cast<jint>(llama_vocab_eos(mc->vocab));
}
