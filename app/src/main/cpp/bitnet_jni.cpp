/*
 * bitnet_jni.cpp — JNI bridge between Kotlin (BitNetNative) and llama.cpp.
 *
 * Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
 *
 * Provides model loading, text completion, and streaming token output
 * for GGUF models via llama.cpp (works with BitNet i2_s when built with
 * bitnet.cpp kernels).
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#include "llama.h"

#define LOG_TAG "BitNetJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---- Model handle wrapper ----
struct ModelContext {
    llama_model * model;
    llama_context * ctx;
    const llama_vocab * vocab;
};

static ModelContext * handle_to_ctx(jlong handle) {
    return reinterpret_cast<ModelContext *>(handle);
}

// ---- Native methods ----

extern "C" JNIEXPORT jlong JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeLoadModel(
    JNIEnv * env, jobject /* thiz */,
    jstring model_path, jint n_ctx, jint n_threads)
{
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) {
        LOGE("nativeLoadModel: null path");
        return -1;
    }
    LOGI("Loading model: %s  n_ctx=%d  n_threads=%d", path, n_ctx, n_threads);

    // Auto-detect thread count
    if (n_threads <= 0) {
        n_threads = sysconf(_SC_NPROCESSORS_ONLN);
        if (n_threads > 4) n_threads = 4;  // cap for thermal reasons
    }

    // ---- Initialize llama.cpp backend ----
    llama_backend_init();

    // ---- Load model ----
    auto * model_params = llama_model_default_params();
    model_params->n_gpu_layers = 0;  // CPU-only for now; NPU support later

    llama_model * model = llama_model_load_from_file(path, *model_params);
    if (!model) {
        LOGE("Failed to load model: %s", path);
        env->ReleaseStringUTFChars(model_path, path);
        return -2;
    }

    // ---- Create context ----
    auto * ctx_params = llama_context_default_params();
    ctx_params->n_ctx       = n_ctx;
    ctx_params->n_threads   = n_threads;
    ctx_params->n_threads_batch = n_threads;
    ctx_params->n_batch     = 512;
    ctx_params->flash_attn  = false;
    ctx_params->embeddings  = false;

    llama_context * ctx = llama_init_from_model(model, *ctx_params);
    if (!ctx) {
        LOGE("Failed to create context for: %s", path);
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path, path);
        return -3;
    }

    env->ReleaseStringUTFChars(model_path, path);

    auto * mc = new ModelContext{model, ctx, llama_model_get_vocab(model)};
    LOGI("Model loaded successfully, handle=%p", mc);
    return reinterpret_cast<jlong>(mc);
}

extern "C" JNIEXPORT void JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeFreeModel(
    JNIEnv * /* env */, jobject /* thiz */, jlong handle)
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
    JNIEnv * env, jobject /* thiz */,
    jlong handle, jstring prompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k, jfloat repeat_penalty,
    jstring stop_sequences_json)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->ctx) {
        LOGE("nativeCompletion: invalid handle");
        return nullptr;
    }

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_str) return nullptr;

    // ---- Tokenize ----
    const llama_vocab * vocab = mc->vocab;
    int n_ctx = llama_n_ctx(mc->ctx);

    std::vector<llama_token> tokens;
    auto add_bos = llama_vocab_get_add_bos(vocab);
    tokens = llama_tokenize(vocab, prompt_str, add_bos, true);

    if ((int)tokens.size() >= n_ctx) {
        LOGW("Prompt too long (%d tokens, context=%d), truncating", (int)tokens.size(), n_ctx);
        tokens.resize(n_ctx - 1);
    }

    // ---- Parse stop sequences ----
    // Simple parse: extract strings from JSON array
    std::vector<std::string> stop_strs;
    const char * stops_json = env->GetStringUTFChars(stop_sequences_json, nullptr);
    if (stops_json) {
        // Naive JSON array parser: find strings between quotes
        std::string json(stops_json);
        bool in_str = false;
        std::string current;
        for (char c : json) {
            if (c == '"' && !in_str) {
                in_str = true;
                current.clear();
            } else if (c == '"' && in_str) {
                in_str = false;
                if (!current.empty()) stop_strs.push_back(current);
            } else if (in_str) {
                current += c;
            }
        }
        env->ReleaseStringUTFChars(stop_sequences_json, stops_json);
    }

    env->ReleaseStringUTFChars(prompt, prompt_str);

    // ---- Sampling ----
    auto * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ---- Generate ----
    std::string result;
    llama_token new_token;

    for (int i = 0; i < max_tokens; i++) {
        if ((int)tokens.size() >= n_ctx - 1) break;

        // Evaluate
        if (llama_decode(mc->ctx, llama_batch_get_one(tokens.data(), tokens.size())) != 0) {
            LOGE("llama_decode failed at token %d", i);
            break;
        }

        // Sample
        new_token = llama_sampler_sample(smpl, mc->ctx, -1);

        // Check EOS
        if (llama_vocab_is_eog(vocab, new_token)) break;

        // Convert to text
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            buf[n] = '\0';
            result += std::string(buf, n);
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

    return env->NewStringUTF(result.c_str());
}

// ---- Streaming variant ----

extern "C" JNIEXPORT jstring JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeCompletionStreaming(
    JNIEnv * env, jobject thiz,
    jlong handle, jstring prompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k, jfloat repeat_penalty,
    jstring stop_sequences_json,
    jobject on_token_kfn)
{
    // For the streaming variant, we use the same logic but call the Kotlin
    // lambda for each token via the Function1 interface.
    //
    // The lambda is a Kotlin (String) -> Unit, which on JNI is a
    // kotlinx.coroutines or Function1 object. We call its invoke method.

    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->ctx) {
        LOGE("nativeCompletionStreaming: invalid handle");
        return nullptr;
    }

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_str) return nullptr;

    const llama_vocab * vocab = mc->vocab;
    int n_ctx = llama_n_ctx(mc->ctx);

    std::vector<llama_token> tokens;
    auto add_bos = llama_vocab_get_add_bos(vocab);
    tokens = llama_tokenize(vocab, prompt_str, add_bos, true);

    if ((int)tokens.size() >= n_ctx) {
        tokens.resize(n_ctx - 1);
    }

    // Parse stop sequences
    std::vector<std::string> stop_strs;
    const char * stops_json = env->GetStringUTFChars(stop_sequences_json, nullptr);
    if (stops_json) {
        std::string json(stops_json);
        bool in_str = false;
        std::string current;
        for (char c : json) {
            if (c == '"' && !in_str) { in_str = true; current.clear(); }
            else if (c == '"' && in_str) { in_str = false; if (!current.empty()) stop_strs.push_back(current); }
            else if (in_str) { current += c; }
        }
        env->ReleaseStringUTFChars(stop_sequences_json, stops_json);
    }

    env->ReleaseStringUTFChars(prompt, prompt_str);

    // Get the Kotlin Function1.invoke method
    jclass function1Class = env->FindClass("kotlin/jvm/functions/Function1");
    jmethodID invokeMethod = function1Class ? env->GetMethodID(function1Class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;") : nullptr;

    // Sampling
    auto * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result;

    for (int i = 0; i < max_tokens; i++) {
        if ((int)tokens.size() >= n_ctx - 1) break;

        if (llama_decode(mc->ctx, llama_batch_get_one(tokens.data(), tokens.size())) != 0) {
            LOGE("llama_decode failed at token %d", i);
            break;
        }

        llama_token new_token = llama_sampler_sample(smpl, mc->ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            buf[n] = '\0';
            std::string piece(buf, n);
            result += piece;

            // Call onToken callback
            if (invokeMethod && on_token_kfn) {
                jstring jpiece = env->NewStringUTF(piece.c_str());
                env->CallObjectMethod(on_token_kfn, invokeMethod, jpiece);
                env->DeleteLocalRef(jpiece);
            }
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

    return env->NewStringUTF(result.c_str());
}

// ---- Tokenization helpers ----

extern "C" JNIEXPORT jintArray JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeTokenize(
    JNIEnv * env, jobject /* thiz */, jlong handle, jstring text)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->vocab) return nullptr;

    const char * text_str = env->GetStringUTFChars(text, nullptr);
    if (!text_str) return nullptr;

    auto tokens = llama_tokenize(mc->vocab, text_str, false, true);
    env->ReleaseStringUTFChars(text, text_str);

    jintArray result = env->NewIntArray(tokens.size());
    env->SetIntArrayRegion(result, 0, tokens.size(), reinterpret_cast<const jint *>(tokens.data()));
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeDetokenize(
    JNIEnv * env, jobject /* thiz */, jlong handle, jintArray tokens)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->vocab) return nullptr;

    jsize len = env->GetArrayLength(tokens);
    jint * arr = env->GetIntArrayElements(tokens, nullptr);
    if (!arr) return nullptr;

    std::string result;
    for (jsize i = 0; i < len; i++) {
        char buf[256];
        int n = llama_token_to_piece(mc->vocab, static_cast<llama_token>(arr[i]), buf, sizeof(buf), 0, true);
        if (n > 0) result.append(buf, n);
    }

    env->ReleaseIntArrayElements(tokens, arr, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_io_agents_arya_agent_llm_BitNetNative_nativeEosToken(
    JNIEnv * /* env */, jobject /* thiz */, jlong handle)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->vocab) return -1;
    return static_cast<jint>(llama_vocab_eos(mc->vocab));
}
