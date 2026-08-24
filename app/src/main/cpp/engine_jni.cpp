/*
 * engine_jni.cpp — JNI bridge between Kotlin (EngineNative) and llama.cpp.
 *
 * Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
 *
 * - Isolated process :engine C++ backend
 * - UTF-8 boundary guard for detokenization
 * - Cancel atomic flag for immediate cancellation
 * - Prefix state save/load (llama_state_save_file / llama_state_load_file)
 * - Token counting
 *
 * llama.cpp b10603 C API (verified against include/llama.h):
 *   llama_state_save_file(ctx, path, tokens, n_token_count)           -> bool  (4 args)
 *   llama_state_load_file(ctx, path, tokens_out, cap, n_token_out)    -> bool  (5 args)
 *   llama_sampler_init_penalties(n_vocab, last_n, repeat, freq, pres) -> 5 args
 *   llama_batch_get_one(tokens, n_tokens)                             -> 2 args
 *   llama_model_params has NO use_mmap / use_mlock (removed)
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <unistd.h>
#include <sys/sysinfo.h>
#include <time.h>
#include <sched.h>
#include <pthread.h>
#include <atomic>
#include <sys/stat.h>
#include <fcntl.h>
#include <signal.h>
#include <algorithm>
#include <errno.h>
#include <stdio.h>

#include "llama.h"

#define LOG_TAG "AryaEngineJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static double now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

struct ModelContext {
    llama_model * model;
    llama_context * ctx;
    const llama_vocab * vocab;
    double load_time_ms;
    size_t model_size_bytes;
    int n_threads_used;
    int n_ctx;
    int n_embd;
    int n_layers;
    int n_params_b;
    bool uses_mmap;
    std::atomic<bool> cancel_flag{false};
    int current_n_past{0};
    std::string loaded_prefix_key;
};

static ModelContext * handle_to_ctx(jlong handle) {
    return reinterpret_cast<ModelContext *>(handle);
}

static std::vector<llama_token> tokenize_string(
    const llama_vocab * vocab, const std::string & text, bool add_bos, bool parse_special)
{
    const int32_t max_tokens_est = static_cast<int32_t>(text.size()) + 128;
    std::vector<llama_token> tokens(max_tokens_est);
    int32_t n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                               tokens.data(), max_tokens_est, add_bos, parse_special);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                           tokens.data(), -n, add_bos, parse_special);
    }
    if (n > 0) tokens.resize(n);
    else tokens.clear();
    return tokens;
}

static int get_nprocs_onln() {
#ifdef __ANDROID__
    return get_nprocs();
#else
    return sysconf(_SC_NPROCESSORS_ONLN);
#endif
}

static int detect_inference_threads() {
    int nprocs = get_nprocs_onln();
    int big_cores = 0, little_cores = 0;
    for (int i = 0; i < nprocs && i < 16; i++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE * f = fopen(path, "r");
        if (f) {
            long freq = 0;
            if (fscanf(f, "%ld", &freq) == 1 && freq > 1500000) big_cores++;
            else little_cores++;
            fclose(f);
        } else {
            little_cores++;
        }
    }
    LOGI("CPU: %d big + %d little = %d total", big_cores, little_cores, nprocs);
    int recommended = (big_cores > 0) ? std::min(big_cores, 2) + little_cores : nprocs;
    if (recommended > 4) recommended = 4;
    if (recommended < 1) recommended = 1;
    return recommended;
}

static long get_total_ram_mb() {
    struct sysinfo si;
    if (sysinfo(&si) == 0) return si.totalram * si.mem_unit / (1024 * 1024);
    return 0;
}

static long get_available_ram_mb() {
    struct sysinfo si;
    if (sysinfo(&si) == 0) return si.freeram * si.mem_unit / (1024 * 1024);
    return 0;
}

static bool detect_gpu_available() {
    if (access("/dev/kgsl-3d0", F_OK) == 0) return true;
    if (access("/dev/mali0", F_OK) == 0) return true;
    return false;
}

static char g_crash_path[256] = "/data/data/io.agents.arya/cache/engine_logs/native-crash.txt";

static void crash_handler(int sig) {
    char buf[80];
    int n = snprintf(buf, sizeof(buf), "native signal %d\n", sig);
    int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        if (n > 0) {
            ssize_t wr = write(fd, buf, static_cast<size_t>(n));
            (void) wr;
        }
        close(fd);
    }
    _exit(128 + sig);
}

static void install_crash_handler() {
    static std::atomic<bool> installed{false};
    bool expected = false;
    if (!installed.compare_exchange_strong(expected, true)) return;
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = crash_handler;
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
}

static void log_rss(const char * tag) {
    FILE * f = fopen("/proc/self/status", "r");
    if (!f) return;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmRSS:", 6) == 0 || strncmp(line, "VmSize:", 7) == 0) {
            LOGI("%s %s", tag, line);
        }
    }
    fclose(f);
}

struct LoadProgressBridge {
    JNIEnv * env;
    jobject cb;
    jmethodID mid;
    int last_logged;
};

static bool llama_load_progress_cb(float progress, void * user_data) {
    int pct = static_cast<int>(progress * 100.0f + 0.5f);
    auto * p = static_cast<LoadProgressBridge *>(user_data);
    if (pct == 0 || pct == 100 || (pct % 10) == 0) {
        if (!p || pct != p->last_logged) {
            LOGI("nativeLoadModel progress=%d%%", pct);
            if (p) p->last_logged = pct;
        }
    }
    if (p && p->env && p->cb && p->mid) {
        jstring phase = p->env->NewStringUTF("Reading weights into RAM");
        p->env->CallVoidMethod(p->cb, p->mid, pct, phase);
        if (p->env->ExceptionCheck()) p->env->ExceptionClear();
        if (phase) p->env->DeleteLocalRef(phase);
    }
    return true;
}

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

// Extract valid UTF-8, holding back incomplete trailing byte sequence
static std::string extract_complete_utf8(std::string &stream_buf) {
    if (stream_buf.empty()) return "";
    size_t i = stream_buf.size();
    while (i > 0) {
        unsigned char c = static_cast<unsigned char>(stream_buf[i - 1]);
        if ((c & 0x80) == 0) {
            break;
        } else if ((c & 0xC0) == 0xC0) {
            size_t needed = 1;
            if ((c & 0xE0) == 0xC0) needed = 2;
            else if ((c & 0xF0) == 0xE0) needed = 3;
            else if ((c & 0xF8) == 0xF0) needed = 4;

            if (stream_buf.size() - (i - 1) < needed) {
                std::string complete = stream_buf.substr(0, i - 1);
                stream_buf = stream_buf.substr(i - 1);
                return complete;
            } else {
                break;
            }
        }
        i--;
    }
    std::string complete = stream_buf;
    stream_buf.clear();
    return complete;
}

// ==================== JNI IMPLEMENTATION ====================

extern "C" JNIEXPORT jlong JNICALL
Java_io_agents_arya_engine_EngineNative_nativeLoadModel(
    JNIEnv * env, jobject, jstring model_path, jint n_ctx, jint n_threads, jobject progress_cb)
{
    install_crash_handler();
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) return -1;
    double t0 = now_ms();
    LOGI("Loading model: %s n_ctx=%d n_threads=%d (load_mode=NONE)", path, n_ctx, n_threads);
    log_rss("before-load");

    if (n_threads <= 0) n_threads = detect_inference_threads();

    struct stat st; size_t model_size = 0;
    if (stat(path, &st) == 0) model_size = st.st_size;

    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // Force a real read into anonymous RAM. Default AUTO/MMAP on Huawei FUSE
    // "succeeds" in 3s with ~70 MB RSS, then the first llama_decode dies.
    mp.load_mode = LLAMA_LOAD_MODE_NONE;

    LoadProgressBridge bridge{env, nullptr, nullptr, -1};
    if (progress_cb) {
        jclass cls = env->GetObjectClass(progress_cb);
        bridge.cb = progress_cb;
        bridge.mid = cls ? env->GetMethodID(cls, "onProgress", "(ILjava/lang/String;)V") : nullptr;
        if (cls) env->DeleteLocalRef(cls);
    }
    mp.progress_callback = llama_load_progress_cb;
    mp.progress_callback_user_data = &bridge;

    llama_model * model = llama_model_load_from_file(path, mp);
    if (!model) {
        LOGE("Failed to load model from file: %s", path);
        env->ReleaseStringUTFChars(model_path, path);
        return -2;
    }
    log_rss("after-weights");

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    // Small batches: 512 made the first-decode compute buffer huge on 1.7B.
    cp.n_batch = 128;
    cp.n_ubatch = 32;
    cp.n_seq_max = 1;
    cp.embeddings = false;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("Failed to create context: %s", path);
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path, path);
        return -3;
    }
    env->ReleaseStringUTFChars(model_path, path);

    const llama_vocab * vocab = llama_model_get_vocab(model);

    // Touch the compute graph now so a SIGILL/OOM happens during load, not
    // 8 seconds into a silent generate.
    auto warm = tokenize_string(vocab, "Hi", false, false);
    if (!warm.empty()) {
        int n = std::min(1, static_cast<int>(warm.size()));
        llama_batch wb = llama_batch_get_one(warm.data(), n);
        int wr = llama_decode(ctx, wb);
        LOGI("warmup decode rc=%d", wr);
        llama_memory_clear(llama_get_memory(ctx), true);
    }
    log_rss("after-warmup");

    int n_embd = llama_model_n_embd(model);
    int n_layers = llama_model_n_layer(model);
    double load_time = now_ms() - t0;
    LOGI("load complete ms=%.0f size_mb=%.1f n_embd=%d n_layer=%d n_ctx=%d",
         load_time, model_size / (1024.0 * 1024.0), n_embd, n_layers, n_ctx);

    auto * mc = new ModelContext{
        model, ctx, vocab, load_time, model_size,
        n_threads, n_ctx, n_embd, n_layers,
        static_cast<int>(model_size * 8.0 / 4.3 / 1e9), false
    };
    return reinterpret_cast<jlong>(mc);
}

extern "C" JNIEXPORT void JNICALL
Java_io_agents_arya_engine_EngineNative_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    auto * mc = handle_to_ctx(handle);
    if (!mc) return;
    LOGI("Freeing model handle=%p", mc);
    if (mc->ctx) llama_free(mc->ctx);
    if (mc->model) llama_model_free(mc->model);
    delete mc;
}

extern "C" JNIEXPORT void JNICALL
Java_io_agents_arya_engine_EngineNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto * mc = handle_to_ctx(handle);
    if (mc) {
        mc->cancel_flag.store(true);
        LOGI("Cancel requested for handle=%p", mc);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_agents_arya_engine_EngineNative_nativeSaveState(
    JNIEnv * env, jobject, jlong handle, jstring state_path)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->ctx) return JNI_FALSE;
    const char * path = env->GetStringUTFChars(state_path, nullptr);
    if (!path) return JNI_FALSE;

    LOGI("Saving state to file: %s", path);
    const bool ok = llama_state_save_file(mc->ctx, path, nullptr, 0);
    env->ReleaseStringUTFChars(state_path, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_agents_arya_engine_EngineNative_nativeLoadState(
    JNIEnv * env, jobject, jlong handle, jstring state_path)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->ctx) return JNI_FALSE;
    const char * path = env->GetStringUTFChars(state_path, nullptr);
    if (!path) return JNI_FALSE;

    LOGI("Loading state from file: %s", path);
    size_t n_tokens_out = 0;
    const bool ok = llama_state_load_file(mc->ctx, path, nullptr, 0, &n_tokens_out);
    env->ReleaseStringUTFChars(state_path, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_agents_arya_engine_EngineNative_nativeCountTokens(
    JNIEnv * env, jobject, jlong handle, jstring text)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->vocab) return 0;
    const char * t = env->GetStringUTFChars(text, nullptr);
    if (!t) return 0;
    bool add_bos = llama_vocab_get_add_bos(mc->vocab);
    auto tokens = tokenize_string(mc->vocab, std::string(t), add_bos, true);
    env->ReleaseStringUTFChars(text, t);
    return static_cast<jint>(tokens.size());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_agents_arya_engine_EngineNative_nativeGenerateStream(
    JNIEnv * env, jobject, jlong handle,
    jstring prompt, jstring prompt_mode, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k, jfloat repeat_penalty,
    jstring stop_json, jlong deadline_ms, jlong token_deadline_ms,
    jobject stream_callback)
{
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->ctx || !mc->vocab) {
        return env->NewStringUTF("{\"error\": \"invalid_handle\"}");
    }

    mc->cancel_flag.store(false);

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_str) return env->NewStringUTF("{\"error\": \"null_prompt\"}");

    const char * mode_str = prompt_mode ? env->GetStringUTFChars(prompt_mode, nullptr) : "full";
    bool is_delta = (mode_str && strcmp(mode_str, "delta") == 0);
    if (prompt_mode && mode_str) env->ReleaseStringUTFChars(prompt_mode, mode_str);

    bool add_bos = !is_delta && llama_vocab_get_add_bos(mc->vocab);
    auto tokens = tokenize_string(mc->vocab, std::string(prompt_str), add_bos, true);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    const char * stops_c = stop_json ? env->GetStringUTFChars(stop_json, nullptr) : nullptr;
    auto stop_strs = parse_stop_sequences(stops_c);
    if (stop_json && stops_c) env->ReleaseStringUTFChars(stop_json, stops_c);

    const int n_ctx = llama_n_ctx(mc->ctx);
    const int n_prompt = static_cast<int>(tokens.size());

    double t_start = now_ms();
    double t_last_token = t_start;
    LOGI("generate start n_prompt=%d n_ctx=%d max_tokens=%d delta=%d",
         n_prompt, n_ctx, max_tokens, is_delta ? 1 : 0);
    log_rss("generate-start");

    if (n_prompt >= n_ctx) {
        return env->NewStringUTF("{\"error\": \"prompt_exceeds_ctx\"}");
    }

    if (!is_delta) {
        llama_memory_clear(llama_get_memory(mc->ctx), true);
        mc->current_n_past = 0;
    }

    if (n_prompt > 0) {
        const int chunk = 32;
        for (int i = 0; i < n_prompt; ) {
            if (mc->cancel_flag.load()) {
                return env->NewStringUTF("{\"error\": \"cancelled\"}");
            }
            int n = std::min(chunk, n_prompt - i);
            llama_batch pb = llama_batch_get_one(const_cast<llama_token*>(tokens.data() + i), n);
            int rc = llama_decode(mc->ctx, pb);
            if (rc != 0) {
                LOGE("prefill decode failed rc=%d at %d/%d", rc, i, n_prompt);
                return env->NewStringUTF("{\"error\": \"decode_failed\"}");
            }
            i += n;
            if (i == n || (i % 64) == 0 || i == n_prompt) {
                LOGI("prefill %d/%d", i, n_prompt);
            }
        }
    }
    double prompt_eval_ms = now_ms() - t_start;
    LOGI("prefill done ms=%.0f n_prompt=%d", prompt_eval_ms, n_prompt);

    // Sampler setup (b10603: penalties is still n_vocab, last_n, repeat, freq, present)
    auto * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    const int32_t n_vocab = llama_vocab_n_tokens(mc->vocab);
    if (repeat_penalty > 0.01f && repeat_penalty != 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(n_vocab, 64, repeat_penalty, 0.0f, 0.0f));
    }
    if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    if (temperature < 0.01f)
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    else
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cb_class = stream_callback ? env->GetObjectClass(stream_callback) : nullptr;
    jmethodID on_delta_method = cb_class ? env->GetMethodID(cb_class, "onDeltaPiece", "(Ljava/lang/String;)V") : nullptr;

    std::string accumulated;
    std::string pending_utf8;
    int gen_tokens = 0;
    double gen_start = now_ms();
    std::string finish_reason = "stop";

    for (int i = 0; i < max_tokens; i++) {
        double now = now_ms();
        if (mc->cancel_flag.load()) {
            finish_reason = "cancelled";
            break;
        }
        if ((i % 8) == 0) {
            if (deadline_ms > 0 && (now - t_start) > deadline_ms) {
                finish_reason = "deadline";
                break;
            }
            if (token_deadline_ms > 0 && gen_tokens > 0 && (now - t_last_token) > token_deadline_ms) {
                finish_reason = "token_deadline";
                break;
            }
        }

        if (n_prompt + gen_tokens >= n_ctx - 1) {
            finish_reason = "ctx_full";
            break;
        }

        llama_token tok = llama_sampler_sample(smpl, mc->ctx, -1);
        if (llama_vocab_is_eog(mc->vocab, tok)) {
            finish_reason = "eos";
            break;
        }

        llama_batch ob = llama_batch_get_one(&tok, 1);
        if (llama_decode(mc->ctx, ob) != 0) {
            finish_reason = "decode_error";
            break;
        }

        t_last_token = now_ms();
        char buf[256];
        int n = llama_token_to_piece(mc->vocab, tok, buf, sizeof(buf), 0, true);
        if (n > 0) {
            pending_utf8.append(buf, n);
            accumulated.append(buf, n);

            std::string delta = extract_complete_utf8(pending_utf8);
            if (!delta.empty() && on_delta_method && stream_callback) {
                jstring jdelta = env->NewStringUTF(delta.c_str());
                env->CallVoidMethod(stream_callback, on_delta_method, jdelta);
                env->DeleteLocalRef(jdelta);
            }
        }
        gen_tokens++;

        // Stop sequence check
        bool stopped = false;
        for (const auto & s : stop_strs) {
            if (accumulated.length() >= s.length() &&
                accumulated.compare(accumulated.length() - s.length(), s.length(), s) == 0) {
                finish_reason = "stop_sequence";
                stopped = true;
                break;
            }
        }
        if (stopped) break;
    }

    // Flush any remaining UTF-8 bytes if available
    if (!pending_utf8.empty() && on_delta_method && stream_callback) {
        jstring jdelta = env->NewStringUTF(pending_utf8.c_str());
        env->CallVoidMethod(stream_callback, on_delta_method, jdelta);
        env->DeleteLocalRef(jdelta);
    }

    double gen_end = now_ms();
    double gen_ms = gen_end - gen_start;

    llama_sampler_free(smpl);
    LOGI("generate done reason=%s gen_tokens=%d prompt_ms=%.0f gen_ms=%.0f",
         finish_reason.c_str(), gen_tokens, prompt_eval_ms, gen_ms);

    char stats[512];
    snprintf(stats, sizeof(stats),
        "{\"prompt_eval_ms\":%.1f,\"prompt_tokens\":%d,\"gen_ms\":%.1f,\"gen_tokens\":%d,"
        "\"gen_tok_per_s\":%.1f,\"finish_reason\":\"%s\"}",
        prompt_eval_ms, n_prompt, gen_ms, gen_tokens,
        gen_ms > 0 ? gen_tokens / (gen_ms / 1000.0) : 0, finish_reason.c_str());

    return env->NewStringUTF(stats);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_agents_arya_engine_EngineNative_nativeGetModelInfo(JNIEnv * env, jobject, jlong handle) {
    auto * mc = handle_to_ctx(handle);
    if (!mc) return env->NewStringUTF("{}");
    char buf[512];
    snprintf(buf, sizeof(buf),
        "{\"load_time_ms\":%.0f,\"model_size_mb\":%.1f,\"n_threads\":%d,"
        "\"n_ctx\":%d,\"n_embd\":%d,\"n_layers\":%d,\"n_params_b\":%d,\"uses_mmap\":%s}",
        mc->load_time_ms, mc->model_size_bytes / (1024.0 * 1024.0), mc->n_threads_used,
        mc->n_ctx, mc->n_embd, mc->n_layers, mc->n_params_b,
        mc->uses_mmap ? "true" : "false");
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_agents_arya_engine_EngineNative_nativeGetSystemInfo(JNIEnv * env, jobject) {
    char buf[256];
    snprintf(buf, sizeof(buf), "{\"cpu_cores\":%d,\"ram_total_mb\":%ld,\"ram_avail_mb\":%ld,\"gpu_available\":%s}",
        get_nprocs_onln(), get_total_ram_mb(), get_available_ram_mb(),
        detect_gpu_available() ? "true" : "false");
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT void JNICALL
Java_io_agents_arya_engine_EngineNative_nativeClearKv(JNIEnv *, jobject, jlong handle) {
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->ctx) return;
    llama_memory_clear(llama_get_memory(mc->ctx), true);
    mc->current_n_past = 0;
    mc->loaded_prefix_key.clear();
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_agents_arya_engine_EngineNative_nativeModelMeta(
    JNIEnv * env, jobject, jstring model_path)
{
    // Lightweight: report file size only. Full GGUF KV is parsed in Kotlin
    // (GgufHeaderParser) so this never has to mmap the weights.
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) return env->NewStringUTF("{}");
    struct stat st;
    long size = 0;
    if (stat(path, &st) == 0) size = (long) st.st_size;
    env->ReleaseStringUTFChars(model_path, path);
    char buf[128];
    snprintf(buf, sizeof(buf), "{\"file_bytes\":%ld}", size);
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_agents_arya_engine_EngineNative_nativeDetectBigCores(JNIEnv *, jobject) {
    int nprocs = get_nprocs_onln();
    int big = 0;
    for (int i = 0; i < nprocs && i < 16; i++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE * f = fopen(path, "r");
        if (f) {
            long freq = 0;
            if (fscanf(f, "%ld", &freq) == 1 && freq > 1500000) big++;
            fclose(f);
        }
    }
    return big > 0 ? big : 1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_agents_arya_engine_EngineNative_nativeBench(JNIEnv * env, jobject, jint n_threads) {
    const int N = 1024;
    const int ITER = 64;
    std::vector<float> a(N * N), b(N), c(N);
    for (int i = 0; i < N * N; i++) a[i] = 0.001f * (i % 97);
    for (int i = 0; i < N; i++) b[i] = 0.002f * (i % 53);

    double t0 = now_ms();
    // Simple GEMV proxy; thread count is advisory (single-thread loop, scaled).
    for (int it = 0; it < ITER; it++) {
        for (int r = 0; r < N; r++) {
            float acc = 0.f;
            const float * row = &a[r * N];
            for (int k = 0; k < N; k++) acc += row[k] * b[k];
            c[r] = acc;
        }
    }
    double dt = (now_ms() - t0) / 1000.0;
    if (dt < 1e-6) dt = 1e-6;
    double flops = 2.0 * N * N * ITER;
    double gflops = (flops / dt) / 1e9;
    // memcpy bandwidth
    std::vector<char> src(16 * 1024 * 1024), dst(16 * 1024 * 1024);
    double b0 = now_ms();
    memcpy(dst.data(), src.data(), src.size());
    double bdt = (now_ms() - b0) / 1000.0;
    if (bdt < 1e-6) bdt = 1e-6;
    double bw = (16.0 / 1024.0) / bdt; // GB/s
    volatile float sink = c[0];
    (void) sink;
    (void) n_threads;
    char buf[192];
    snprintf(buf, sizeof(buf), "{\"gflops\":%.3f,\"mem_bw_gbs\":%.3f,\"threads\":%d}", gflops, bw, n_threads);
    return env->NewStringUTF(buf);
}
