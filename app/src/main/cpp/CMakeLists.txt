cmake_minimum_required(VERSION 3.22)
project(arya-engine LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Pin: ggml-org/llama.cpp b10603 (2026-08-23, latest stable release at fix time).
# Qwen3 GGUF requires llama.cpp ≥ b5092. Do NOT revert to b3800 (Sep 2024) —
# that tag has no Qwen3 architecture and nativeLoadModel returns -2.
# Do NOT revert to v0.2.0 (2023) — no llama_model_load_from_file / llama_vocab.
include(FetchContent)
FetchContent_Declare(llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b10603
    GIT_SHALLOW    TRUE
    SOURCE_SUBDIR  .
)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Kirin 9000S: ggml runtime/compile +dotprod SIGILLs on Taishan during
# batched llama_decode. Warmup of 1 token survived; prefill n_ubatch=8 died
# (ADY-LX9 1.2.15 log, FunctionGemma AND Qwen3-0.6B). Force plain armv8-a
# on EVERY translation unit including llama.cpp/ggml, not just engine_jni.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8-a)
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
endif()

# Do NOT patch llama.cpp into lazy mmap. 1.2.7 flipped init_mappings(true) to
# false so a 1.28 GB GGUF could "load" with ~70 MB RSS; the first generate
# then died on Huawei. engine_jni.cpp uses LLAMA_LOAD_MODE_MMAP + prefetch.
# Leave upstream mapping behaviour alone.
FetchContent_MakeAvailable(llama_cpp)

add_library(arya-engine SHARED engine_jni.cpp)

target_include_directories(arya-engine PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(arya-engine llama ggml log)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # armv8-a only. Do NOT bake +dotprod/+fp16 — Kirin 9000S Taishan can SIGILL
    # on the first llama_decode even though mmap "succeeded".
    target_compile_options(arya-engine PRIVATE -O3 -fvisibility=hidden -march=armv8-a -ffunction-sections -fdata-sections)
    target_link_options(arya-engine PRIVATE -Wl,--gc-sections)
else()
    target_compile_options(arya-engine PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
    target_link_options(arya-engine PRIVATE -Wl,--gc-sections)
endif()
