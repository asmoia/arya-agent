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

#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif

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
#include <exception>
#include <stdint.h>
#include <sys/auxv.h>
#include <sys/syscall.h>
#include <ucontext.h>

#include "llama.h"
#include "ggml.h"

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
    int n_ubatch;
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

static char g_crash_path[256] = "/data/user/0/io.agents.arya/cache/engine_logs/native-crash.txt";
static char g_stage_path[256] = "/data/user/0/io.agents.arya/cache/engine_logs/native-load-stage.txt";
static char g_heartbeat_path[256] = "/data/user/0/io.agents.arya/cache/engine_logs/native-heartbeat.txt";
static char g_env_path[256] = "/data/user/0/io.agents.arya/cache/engine_logs/native-env.txt";
static char g_llama_log_path[256] = "/data/user/0/io.agents.arya/cache/engine_logs/llama-cpp.log";
static char g_maps_path[256] = "/data/user/0/io.agents.arya/cache/engine_logs/native-maps.txt";
static char g_last_stage[128] = "boot";

// Resolve the onDeltaPiece method against the *kept* interface
// (EngineNative$NativeStreamCallback) instead of the runtime object's class.
// GetMethodID on a concrete obfuscated class was returning a slot whose
// signature no longer matched after R8 renamed the method, which surfaced as
// "NoSuchMethodError: no non-static method L<cls>;.onDeltaPiece(...)". The
// interface is covered by a -keep rule, so its method id stays stable.
static jclass g_stream_cb_class = nullptr;
static jclass stream_cb_class(JNIEnv * env) {
    if (!g_stream_cb_class) {
        jclass local = env->FindClass("io/agents/arya/engine/EngineNative$NativeStreamCallback");
        if (local) {
            g_stream_cb_class = static_cast<jclass>(env->NewGlobalRef(local));
            env->DeleteLocalRef(local);
        } else {
            env->ExceptionClear();
        }
    }
    return g_stream_cb_class;
}

static long get_self_rss_kb() {
    FILE * f = fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256];
    long rss = -1;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmRSS:", 6) == 0) {
            long value = -1;
            if (sscanf(line + 6, "%ld", &value) == 1) rss = value;
            break;
        }
    }
    fclose(f);
    return rss;
}

static void set_path_buf(char * dst, size_t dst_sz, const char * src) {
    if (!src || !src[0] || !dst || dst_sz == 0) return;
    strncpy(dst, src, dst_sz - 1);
    dst[dst_sz - 1] = '\0';
}

static void derive_sibling(char * dst, size_t dst_sz, const char * any_path, const char * filename) {
    if (!dst || dst_sz == 0 || !filename) return;
    const char * slash = nullptr;
    if (any_path) {
        for (const char * p = any_path; *p; ++p) {
            if (*p == '/') slash = p;
        }
    }
    if (!slash) {
        snprintf(dst, dst_sz, "/data/user/0/io.agents.arya/cache/engine_logs/%s", filename);
        return;
    }
    size_t dir_len = static_cast<size_t>(slash - any_path);
    size_t name_len = strlen(filename);
    if (dir_len + 1 + name_len + 1 > dst_sz) {
        snprintf(dst, dst_sz, "/data/user/0/io.agents.arya/cache/engine_logs/%s", filename);
        return;
    }
    memcpy(dst, any_path, dir_len);
    dst[dir_len] = '/';
    memcpy(dst + dir_len + 1, filename, name_len + 1);
}

static long get_tid() {
    return static_cast<long>(syscall(__NR_gettid));
}

static int get_cpu() {
    int c = sched_getcpu();
    return c < 0 ? -1 : c;
}

static void remember_stage(const char * stage) {
    if (!stage) return;
    size_t n = 0;
    while (stage[n] && n + 1 < sizeof(g_last_stage)) {
        g_last_stage[n] = stage[n];
        ++n;
    }
    g_last_stage[n] = '\0';
}

static void write_all(int fd, const char * buf, size_t n) {
    const char * cursor = buf;
    ssize_t remaining = static_cast<ssize_t>(n);
    while (remaining > 0) {
        ssize_t written = write(fd, cursor, static_cast<size_t>(remaining));
        if (written <= 0) break;
        cursor += written;
        remaining -= written;
    }
}

static void write_cstr(int fd, const char * s) {
    if (!s) return;
    size_t n = 0;
    while (s[n]) ++n;
    write_all(fd, s, n);
}

static void write_dec_fd(int fd, long long v) {
    if (v < 0) {
        write_cstr(fd, "-");
        v = -v;
    }
    char digits[32];
    int n = 0;
    if (v == 0) {
        digits[n++] = '0';
    } else {
        while (v > 0 && n < 31) {
            digits[n++] = static_cast<char>('0' + (v % 10));
            v /= 10;
        }
    }
    for (int i = n - 1; i >= 0; --i) {
        char c = digits[i];
        write(fd, &c, 1);
    }
}

static void write_hex_fd(int fd, uint64_t v) {
    write_cstr(fd, "0x");
    char buf[16];
    for (int i = 15; i >= 0; --i) {
        int nib = static_cast<int>((v >> (static_cast<unsigned>(i) * 4)) & 0xF);
        buf[15 - i] = static_cast<char>(nib < 10 ? '0' + nib : 'a' + (nib - 10));
    }
    write_all(fd, buf, 16);
}

static void append_to_path(const char * path, const char * data, size_t n, bool do_fsync) {
    if (!path || !path[0] || !data || n == 0) return;
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    write_all(fd, data, n);
    if (do_fsync) fsync(fd);
    close(fd);
}

static void overwrite_path(const char * path, const char * data, size_t n) {
    if (!path || !path[0] || !data) return;
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return;
    write_all(fd, data, n);
    fsync(fd);
    close(fd);
}

static void format_stage_line(char * line, size_t cap, const char * stage) {
    snprintf(
        line, cap,
        "t_ms=%lld pid=%ld tid=%ld cpu=%d rss_kb=%ld avail_mb=%ld stage=%s\n",
        static_cast<long long>(now_ms()), static_cast<long>(getpid()), get_tid(), get_cpu(),
        get_self_rss_kb(), get_available_ram_mb(), stage);
}

static void heartbeat_only(const char * stage) {
    if (!stage || !stage[0]) return;
    remember_stage(stage);
    char line[384];
    format_stage_line(line, sizeof(line), stage);
    overwrite_path(g_heartbeat_path, line, strlen(line));
}

static void append_load_stage(const char * stage) {
    if (!stage || !stage[0]) return;
    remember_stage(stage);
    char line[384];
    format_stage_line(line, sizeof(line), stage);
    LOGI("stage %s pid=%ld tid=%ld cpu=%d rss_kb=%ld", stage,
         static_cast<long>(getpid()), get_tid(), get_cpu(), get_self_rss_kb());
    append_to_path(g_stage_path, line, strlen(line), true);
    overwrite_path(g_heartbeat_path, line, strlen(line));
}

static void crash_handler(int sig, siginfo_t * si, void * uctx) {
    // Async-signal-safe only: open/write/fsync/close/_exit. No snprintf.
    int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd >= 0) {
        write_cstr(fd, "native_signal sig=");
        write_dec_fd(fd, sig);
        write_cstr(fd, " code=");
        write_dec_fd(fd, si ? si->si_code : 0);
        write_cstr(fd, " addr=");
        write_hex_fd(fd, si ? static_cast<uint64_t>(reinterpret_cast<uintptr_t>(si->si_addr)) : 0);
        write_cstr(fd, " pid=");
        write_dec_fd(fd, static_cast<long long>(getpid()));
        write_cstr(fd, " tid=");
        write_dec_fd(fd, static_cast<long long>(syscall(__NR_gettid)));
#if defined(__aarch64__)
        if (uctx) {
            ucontext_t * uc = static_cast<ucontext_t *>(uctx);
            write_cstr(fd, " pc=");
            write_hex_fd(fd, static_cast<uint64_t>(uc->uc_mcontext.pc));
            write_cstr(fd, " lr=");
            write_hex_fd(fd, static_cast<uint64_t>(uc->uc_mcontext.regs[30]));
            write_cstr(fd, " sp=");
            write_hex_fd(fd, static_cast<uint64_t>(uc->uc_mcontext.sp));
            write_cstr(fd, " fault=");
            write_hex_fd(fd, static_cast<uint64_t>(uc->uc_mcontext.fault_address));
        }
#endif
        write_cstr(fd, " stage=");
        write_cstr(fd, g_last_stage);
        write_cstr(fd, "\n");
        if (sig == SIGILL) write_cstr(fd, "name=SIGILL\n");
        else if (sig == SIGSEGV) write_cstr(fd, "name=SIGSEGV\n");
        else if (sig == SIGBUS) write_cstr(fd, "name=SIGBUS\n");
        else if (sig == SIGABRT) write_cstr(fd, "name=SIGABRT\n");
        else if (sig == SIGFPE) write_cstr(fd, "name=SIGFPE\n");
        fsync(fd);
        close(fd);
    }
    int hb = open(g_heartbeat_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (hb >= 0) {
        write_cstr(hb, "CRASH sig=");
        write_dec_fd(hb, sig);
        write_cstr(hb, " stage=");
        write_cstr(hb, g_last_stage);
        write_cstr(hb, "\n");
        fsync(hb);
        close(hb);
    }
    _exit(128 + sig);
}

static void install_crash_handler();

static void set_crash_log_path(const char * path) {
    set_path_buf(g_crash_path, sizeof(g_crash_path), path);
    install_crash_handler();
}

static void set_stage_log_path(const char * path) {
    set_path_buf(g_stage_path, sizeof(g_stage_path), path);
    derive_sibling(g_heartbeat_path, sizeof(g_heartbeat_path), path, "native-heartbeat.txt");
    derive_sibling(g_env_path, sizeof(g_env_path), path, "native-env.txt");
    derive_sibling(g_llama_log_path, sizeof(g_llama_log_path), path, "llama-cpp.log");
    derive_sibling(g_maps_path, sizeof(g_maps_path), path, "native-maps.txt");
}

static void install_crash_handler() {
    static std::atomic<bool> installed{false};
    bool expected = false;
    if (!installed.compare_exchange_strong(expected, true)) return;
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
}

static void llama_log_bridge(enum ggml_log_level level, const char * text, void * /*user*/) {
    if (!text) return;
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) prio = ANDROID_LOG_DEBUG;
    __android_log_print(prio, "AryaLlama", "%s", text);
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        size_t n = strlen(text);
        append_to_path(g_llama_log_path, text, n, level == GGML_LOG_LEVEL_ERROR);
        if (n == 0 || text[n - 1] != '\n') {
            append_to_path(g_llama_log_path, "\n", 1, level == GGML_LOG_LEVEL_ERROR);
        }
    }
}

#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1UL << 20)
#endif
#ifndef HWCAP_ASIMD
#define HWCAP_ASIMD (1UL << 1)
#endif
#ifndef HWCAP_FP
#define HWCAP_FP (1UL << 0)
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1UL << 13)
#endif

static void dump_cpuinfo_and_env(const char * model_path, int n_ctx, int n_threads) {
    FILE * out = fopen(g_env_path, "w");
    if (!out) return;
    fprintf(out, "arya_engine_diag=1.2.18\n");
    fprintf(out, "pid=%ld tid=%ld cpu=%d\n", static_cast<long>(getpid()), get_tid(), get_cpu());
    fprintf(out, "model_path=%s\n", model_path ? model_path : "");
    fprintf(out, "n_ctx=%d n_threads_arg=%d forced_threads=1 n_batch=16 n_ubatch=16 load_mode=MMAP+prefetch\n", n_ctx, n_threads);
#ifdef __ARM_FEATURE_DOTPROD
    fprintf(out, "compiled_arm_feature_dotprod=1\n");
#else
    fprintf(out, "compiled_arm_feature_dotprod=0\n");
#endif
#ifdef __ARM_FEATURE_MATMUL_INT8
    fprintf(out, "compiled_arm_feature_i8mm=1\n");
#else
    fprintf(out, "compiled_arm_feature_i8mm=0\n");
#endif
#ifdef __ARM_FEATURE_FP16_VECTOR_ARITHMETIC
    fprintf(out, "compiled_arm_feature_fp16=1\n");
#else
    fprintf(out, "compiled_arm_feature_fp16=0\n");
#endif
#if defined(__aarch64__)
    unsigned long hwcap = getauxval(AT_HWCAP);
    unsigned long hwcap2 = getauxval(AT_HWCAP2);
    fprintf(out, "hwcap=0x%lx hwcap2=0x%lx\n", hwcap, hwcap2);
    fprintf(out, "hwcap_fp=%d hwcap_asimd=%d hwcap_asimddp=%d hwcap2_i8mm=%d\n",
            (hwcap & HWCAP_FP) != 0,
            (hwcap & HWCAP_ASIMD) != 0,
            (hwcap & HWCAP_ASIMDDP) != 0,
            (hwcap2 & HWCAP2_I8MM) != 0);
#endif
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) == 0) {
        fprintf(out, "affinity=");
        for (int i = 0; i < 16; i++) {
            if (CPU_ISSET(i, &set)) fprintf(out, "%d ", i);
        }
        fprintf(out, "\n");
    }
    fprintf(out, "nprocs=%d ram_total_mb=%ld ram_avail_mb=%ld rss_kb=%ld\n",
            get_nprocs_onln(), get_total_ram_mb(), get_available_ram_mb(), get_self_rss_kb());
    const char * sys = llama_print_system_info();
    if (sys) fprintf(out, "llama_system_info=%s\n", sys);
    fprintf(out, "--- /proc/self/status ---\n");
    FILE * st = fopen("/proc/self/status", "r");
    if (st) {
        char line[256];
        while (fgets(line, sizeof(line), st)) {
            if (strncmp(line, "Vm", 2) == 0 || strncmp(line, "Threads", 7) == 0 ||
                strncmp(line, "Cpus_allowed", 12) == 0 || strncmp(line, "SigCgt", 6) == 0) {
                fputs(line, out);
            }
        }
        fclose(st);
    }
    fprintf(out, "--- cpu max freq ---\n");
    for (int i = 0; i < 16; i++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE * f = fopen(path, "r");
        if (!f) continue;
        long freq = 0;
        if (fscanf(f, "%ld", &freq) == 1) fprintf(out, "cpu%d_max_khz=%ld\n", i, freq);
        fclose(f);
    }
    fprintf(out, "--- /proc/cpuinfo ---\n");
    FILE * ci = fopen("/proc/cpuinfo", "r");
    if (ci) {
        char line[256];
        int bytes = 0;
        while (fgets(line, sizeof(line), ci) && bytes < 8000) {
            fputs(line, out);
            bytes += static_cast<int>(strlen(line));
        }
        fclose(ci);
    }
    fflush(out);
    fclose(out);

    FILE * maps_in = fopen("/proc/self/maps", "r");
    FILE * maps_out = fopen(g_maps_path, "w");
    if (maps_in && maps_out) {
        char line[512];
        while (fgets(line, sizeof(line), maps_in)) {
            if (strstr(line, "arya") || strstr(line, "ggml") || strstr(line, "llama") ||
                strstr(line, "libarya") || strstr(line, "[vdso]") || strstr(line, "linker")) {
                fputs(line, maps_out);
            }
        }
    }
    if (maps_in) fclose(maps_in);
    if (maps_out) fclose(maps_out);
}

static bool should_log_prefill(int i) {
    if (i < 16) return true;
    if (i < 64) return (i % 8) == 0;
    return (i % 32) == 0;
}

static int decode_logged(llama_context * ctx, llama_batch batch, const char * tag, int i, int n_total, int tok, bool verbose) {
    char before[96];
    snprintf(before, sizeof(before), "%s_in_%d_of_%d_tok_%d_cpu_%d", tag, i, n_total, tok, get_cpu());
    if (verbose) append_load_stage(before);
    else heartbeat_only(before);
    double t0 = now_ms();
    int rc = llama_decode(ctx, batch);
    int ms = static_cast<int>(now_ms() - t0);
    char after[96];
    snprintf(after, sizeof(after), "%s_ok_%d_rc_%d_ms_%d_cpu_%d", tag, i, rc, ms, get_cpu());
    if (verbose || rc != 0) append_load_stage(after);
    else heartbeat_only(after);
    if (rc != 0) {
        LOGE("%s decode rc=%d i=%d/%d tok=%d ms=%d", tag, rc, i, n_total, tok, ms);
    }
    return rc;
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
            LOGI("nativeLoadModel progress=%d%% rss_kb=%ld", pct, get_self_rss_kb());
            if (p) p->last_logged = pct;
            char stage[40];
            snprintf(stage, sizeof(stage), "weights_pct_%d", pct);
            append_load_stage(stage);
        }
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


static std::string json_escape(const std::string & s) {
    std::string o;
    o.reserve(s.size() + 16);
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    o += buf;
                } else {
                    o += static_cast<char>(c);
                }
        }
    }
    return o;
}

// ==================== JNI IMPLEMENTATION ====================

extern "C" JNIEXPORT jlong JNICALL
Java_io_agents_arya_engine_EngineNative_nativeLoadModel(
    JNIEnv * env, jobject, jstring model_path, jint n_ctx, jint n_threads, jobject progress_cb)
{
    try {
    install_crash_handler();
    llama_log_set(llama_log_bridge, nullptr);
    append_load_stage("enter");
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) return -1;
    double t0 = now_ms();
    LOGI("Loading model: %s n_ctx=%d n_threads=%d (load_mode=MMAP+prefetch)", path, n_ctx, n_threads);
#ifdef __ARM_FEATURE_DOTPROD
    append_load_stage("compiled_with_dotprod");
#else
    append_load_stage("compiled_without_dotprod");
#endif
    dump_cpuinfo_and_env(path, n_ctx, n_threads);
    log_rss("before-load");

    // Kirin 9000S: 2-thread batched decode dies at prefill_begin even for
    // 270M (rss 394 MB, 4 GB free — not LMK). 1-token warmup always lived.
    n_threads = 1;

    struct stat st; size_t model_size = 0;
    if (stat(path, &st) == 0) model_size = st.st_size;

    append_load_stage("backend_init_begin");
    llama_backend_init();
    append_load_stage("backend_init_done");
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // Keep Android CPU loading on the default host buffer. Extra CPU buffer
    // types may select a repacking allocator, which is not needed with no GPU.
    mp.use_extra_bufts = false;
    // File-backed mmap. LOAD_MODE_NONE put 1.5 GB anonymous RSS in :engine
    // and Huawei SIGKILL'd it 16s into generate (ADY-LX9 log 2026-08-28).
    // mmap lets LMK reclaim pages instead of killing. ModelFileLocalizer
    // already copied off FUSE onto ext4. Prefetch + warmup fault the pages.
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;

    // Log native progress only. Do not re-enter Java from the loader.
    (void) progress_cb;
    LoadProgressBridge bridge{nullptr, nullptr, nullptr, -1};
    mp.progress_callback = llama_load_progress_cb;
    mp.progress_callback_user_data = &bridge;

    append_load_stage("model_load_begin");
    llama_model * model = llama_model_load_from_file(path, mp);
    append_load_stage("model_load_return");
    if (!model) {
        append_load_stage("model_load_failed");
        LOGE("Failed to load model from file: %s", path);
        env->ReleaseStringUTFChars(model_path, path);
        return -2;
    }
    log_rss("after-weights");

    append_load_stage("prefetch_begin");
    {
        int pfd = open(path, O_RDONLY);
        if (pfd >= 0) {
#ifdef POSIX_FADV_WILLNEED
            posix_fadvise(pfd, 0, 0, POSIX_FADV_SEQUENTIAL);
            posix_fadvise(pfd, 0, 0, POSIX_FADV_WILLNEED);
#endif
            std::vector<char> buf(1024 * 1024);
            while (read(pfd, buf.data(), buf.size()) > 0) {}
            close(pfd);
        }
    }
    append_load_stage("prefetch_done");
    log_rss("after-prefetch");

    append_load_stage("weights_loaded");
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    // The old 1-token/1-thread shape was a workaround for a Kirin 9000S SIGILL
    // from +dotprod/SVE compiled into ggml/llama TUs. That is fixed now by
    // forcing -march=armv8-a on EVERY TU (see CMakeLists); the bundle confirms
    // compiled_arm_feature_dotprod=0. So we can safely batch prefill again —
    // which is what was making large prompts take ~65 ms/token and blow the
    // 90 s deadline. Keep decode single-threaded (2-thread batched decode was
    // the variant that died) but batch N tokens per llama_decode.
    cp.n_threads = 1;
    cp.n_threads_batch = 1;
    cp.n_batch = 16;
    cp.n_ubatch = 16;
    cp.n_seq_max = 1;
    cp.embeddings = false;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    append_load_stage("context_init_begin");
    llama_context * ctx = llama_init_from_model(model, cp);
    append_load_stage("context_init_return");
    if (!ctx) {
        append_load_stage("context_init_failed");
        LOGE("Failed to create context: %s", path);
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path, path);
        return -3;
    }
    env->ReleaseStringUTFChars(model_path, path);

    const llama_vocab * vocab = llama_model_get_vocab(model);

    // Touch the compute graph now so a SIGILL/OOM happens during load, not
    // 8 seconds into a silent generate. 1.2.8 deferred this and the crash
    // moved to generate — which is worse for the user.
    append_load_stage("warmup_begin");
    auto warm = tokenize_string(vocab, "Hi", false, false);
    if (!warm.empty()) {
        int n = std::min(1, static_cast<int>(warm.size()));
        llama_batch wb = llama_batch_get_one(warm.data(), n);
        int wr = decode_logged(ctx, wb, "warmup", 0, n, static_cast<int>(warm[0]), true);
        LOGI("warmup decode rc=%d rss_kb=%ld", wr, get_self_rss_kb());
        if (wr != 0) {
            append_load_stage("warmup_failed");
            LOGE("warmup decode failed rc=%d", wr);
            llama_free(ctx);
            llama_model_free(model);
            return -4;
        }
        llama_memory_clear(llama_get_memory(ctx), true);
    }
    append_load_stage("warmup_done");
    log_rss("after-warmup");

    long rss_kb = get_self_rss_kb();
    long rss_mb = rss_kb >= 0 ? rss_kb / 1024 : -1;
    long file_mb = static_cast<long>(model_size / (1024 * 1024));
    if (file_mb >= 80 && rss_mb >= 0 && rss_mb < 200) {
        append_load_stage("rss_too_low");
        LOGE("fake ready rejected: file_mb=%ld rss_mb=%ld", file_mb, rss_mb);
        llama_free(ctx);
        llama_model_free(model);
        return -6;
    }

    int n_embd = llama_model_n_embd(model);
    int n_layers = llama_model_n_layer(model);
    double load_time = now_ms() - t0;
    LOGI("load complete ms=%.0f size_mb=%.1f rss_mb=%ld n_embd=%d n_layer=%d n_ctx=%d",
         load_time, model_size / (1024.0 * 1024.0), rss_mb, n_embd, n_layers, n_ctx);

    append_load_stage("complete");
    auto * mc = new ModelContext{
        model, ctx, vocab, load_time, model_size,
        n_threads, n_ctx, static_cast<int>(cp.n_ubatch), n_embd, n_layers,
        static_cast<int>(model_size * 8.0 / 4.3 / 1e9), false
    };
    return reinterpret_cast<jlong>(mc);
    } catch (const std::exception& e) {
        append_load_stage("cpp_exception");
        LOGE("nativeLoadModel exception: %s", e.what());
        return -7;
    } catch (...) {
        append_load_stage("unknown_exception");
        LOGE("nativeLoadModel unknown exception");
        return -8;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_agents_arya_engine_EngineNative_nativeSetCrashLogPath(
    JNIEnv * env, jobject, jstring crash_path)
{
    if (!crash_path) return;
    const char * path = env->GetStringUTFChars(crash_path, nullptr);
    if (!path) return;
    set_crash_log_path(path);
    env->ReleaseStringUTFChars(crash_path, path);
}

extern "C" JNIEXPORT void JNICALL
Java_io_agents_arya_engine_EngineNative_nativeSetLoadStagePath(
    JNIEnv * env, jobject, jstring stage_path)
{
    if (!stage_path) return;
    const char * path = env->GetStringUTFChars(stage_path, nullptr);
    if (!path) return;
    set_stage_log_path(path);
    env->ReleaseStringUTFChars(stage_path, path);
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
    try {
    auto * mc = handle_to_ctx(handle);
    if (!mc || !mc->ctx || !mc->vocab) {
        return env->NewStringUTF("{\"error\": \"invalid_handle\"}");
    }

    mc->cancel_flag.store(false);
    append_load_stage("generate_enter");

    const char * prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_str) return env->NewStringUTF("{\"error\": \"null_prompt\"}");

    const char * mode_str = prompt_mode ? env->GetStringUTFChars(prompt_mode, nullptr) : "full";
    bool is_delta = (mode_str && strcmp(mode_str, "delta") == 0);
    if (prompt_mode && mode_str) env->ReleaseStringUTFChars(prompt_mode, mode_str);

    bool add_bos = !is_delta && llama_vocab_get_add_bos(mc->vocab);
    append_load_stage(is_delta ? "tokenize_delta" : (add_bos ? "tokenize_full_bos" : "tokenize_full_nobos"));
    auto tokens = tokenize_string(mc->vocab, std::string(prompt_str), add_bos, true);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    const char * stops_c = stop_json ? env->GetStringUTFChars(stop_json, nullptr) : nullptr;
    auto stop_strs = parse_stop_sequences(stops_c);
    if (stop_json && stops_c) env->ReleaseStringUTFChars(stop_json, stops_c);

    const int n_ctx = llama_n_ctx(mc->ctx);
    const int n_prompt = static_cast<int>(tokens.size());
    {
        char tok_stage[48];
        snprintf(tok_stage, sizeof(tok_stage), "tokenize_done_n_%d", n_prompt);
        append_load_stage(tok_stage);
        char ids[160];
        int used = snprintf(ids, sizeof(ids), "prompt_toks");
        int show = std::min(12, n_prompt);
        for (int k = 0; k < show && used > 0 && used < static_cast<int>(sizeof(ids)) - 12; k++) {
            int w = snprintf(ids + used, sizeof(ids) - static_cast<size_t>(used), "_%d", static_cast<int>(tokens[k]));
            if (w > 0) used += w;
        }
        append_load_stage(ids);
    }

    double t_start = now_ms();
    double t_last_token = t_start;
    LOGI("generate start n_prompt=%d n_ctx=%d max_tokens=%d delta=%d add_bos=%d cpu=%d",
         n_prompt, n_ctx, max_tokens, is_delta ? 1 : 0, add_bos ? 1 : 0, get_cpu());
    log_rss("generate-start");

    if (n_prompt >= n_ctx) {
        return env->NewStringUTF("{\"error\": \"prompt_exceeds_ctx\"}");
    }

    if (!is_delta) {
        append_load_stage("kv_clear");
        llama_memory_clear(llama_get_memory(mc->ctx), true);
        mc->current_n_past = 0;
        append_load_stage("kv_clear_done");
    }

    if (n_prompt > 0) {
        append_load_stage("prefill_begin");
        // Batch the prefill (matches the n_ubatch the context was created with).
        // armv8-a is now forced on every TU so batched decode no longer SIGILLs.
        const int chunk = 16;
        for (int i = 0; i < n_prompt; ) {
            if (mc->cancel_flag.load()) {
                append_load_stage("prefill_cancelled");
                return env->NewStringUTF("{\"error\": \"cancelled\"}");
            }
            int n = std::min(chunk, n_prompt - i);
            llama_batch pb = llama_batch_get_one(const_cast<llama_token*>(tokens.data() + i), n);
            int rc = decode_logged(
                mc->ctx, pb, "prefill", i, n_prompt,
                static_cast<int>(tokens[i]), should_log_prefill(i));
            if (rc != 0) {
                LOGE("prefill decode failed rc=%d at %d/%d", rc, i, n_prompt);
                return env->NewStringUTF("{\"error\": \"decode_failed\"}");
            }
            i += n;
        }
    }
    double prompt_eval_ms = now_ms() - t_start;
    append_load_stage("prefill_done");
    LOGI("prefill done ms=%.0f n_prompt=%d", prompt_eval_ms, n_prompt);

    // Sampler setup (b10603: penalties is still n_vocab, last_n, repeat, freq, present)
    append_load_stage("sampler_init");
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

    jclass cb_class = stream_callback ? stream_cb_class(env) : nullptr;
    jmethodID on_delta_method = nullptr;
    if (cb_class && stream_callback) {
        on_delta_method = env->GetMethodID(cb_class, "onDeltaPiece", "(Ljava/lang/String;)V");
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            on_delta_method = nullptr;
            append_load_stage("jni_onDeltaPiece_missing");
            LOGE("GetMethodID onDeltaPiece failed — R8 stripped the JNI callback");
        }
    }

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
        bool verbose_gen = (i < 8) || ((i % 16) == 0);
        if (decode_logged(mc->ctx, ob, "gen", i, max_tokens, static_cast<int>(tok), verbose_gen) != 0) {
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
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    on_delta_method = nullptr;
                    append_load_stage("jni_onDeltaPiece_threw");
                    LOGE("onDeltaPiece threw — continuing without streaming");
                }
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
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            on_delta_method = nullptr;
        }
    }

    double gen_end = now_ms();
    double gen_ms = gen_end - gen_start;

    llama_sampler_free(smpl);
    {
        char done[80];
        snprintf(done, sizeof(done), "generate_done_%s_tok_%d", finish_reason.c_str(), gen_tokens);
        append_load_stage(done);
    }
    LOGI("generate done reason=%s gen_tokens=%d prompt_ms=%.0f gen_ms=%.0f",
         finish_reason.c_str(), gen_tokens, prompt_eval_ms, gen_ms);

    std::string text_for_json = accumulated.size() > 4000 ? accumulated.substr(0, 4000) : accumulated;
    char head[384];
    snprintf(head, sizeof(head),
        "{\"prompt_eval_ms\":%.1f,\"prompt_tokens\":%d,\"gen_ms\":%.1f,\"gen_tokens\":%d,"
        "\"gen_tok_per_s\":%.1f,\"finish_reason\":\"%s\",\"text\":\"",
        prompt_eval_ms, n_prompt, gen_ms, gen_tokens,
        gen_ms > 0 ? gen_tokens / (gen_ms / 1000.0) : 0, finish_reason.c_str());
    std::string stats = std::string(head);
    stats += json_escape(text_for_json);
    stats.push_back('"');
    stats.push_back('}');

    return env->NewStringUTF(stats.c_str());
    } catch (const std::exception& e) {
        LOGE("nativeGenerateStream exception: %s", e.what());
        return env->NewStringUTF("{\"error\":\"native_exception\"}");
    } catch (...) {
        LOGE("nativeGenerateStream unknown exception");
        return env->NewStringUTF("{\"error\":\"native_exception\"}");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_agents_arya_engine_EngineNative_nativeGetModelInfo(JNIEnv * env, jobject, jlong handle) {
    auto * mc = handle_to_ctx(handle);
    if (!mc) return env->NewStringUTF("{}");
    long rss_kb = get_self_rss_kb();
    double rss_mb = rss_kb >= 0 ? rss_kb / 1024.0 : -1.0;
    char buf[640];
    snprintf(buf, sizeof(buf),
        "{\"load_time_ms\":%.0f,\"model_size_mb\":%.1f,\"rss_mb\":%.1f,\"n_threads\":%d,"
        "\"n_ctx\":%d,\"n_ubatch\":%d,\"n_embd\":%d,\"n_layers\":%d,\"n_params_b\":%d,\"uses_mmap\":%s}",
        mc->load_time_ms, mc->model_size_bytes / (1024.0 * 1024.0), rss_mb, mc->n_threads_used,
        mc->n_ctx, mc->n_ubatch, mc->n_embd, mc->n_layers, mc->n_params_b,
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
