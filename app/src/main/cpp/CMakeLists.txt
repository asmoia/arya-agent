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

# Fetch manually so the pinned upstream loader can be patched before it is
# added as a subdirectory. b10603 calls init_mappings(true), which passes
# -1 to llama_mmap and enables MAP_POPULATE for the whole GGUF. That defeats
# lazy mmap and can make a 1.28 GB model resident at once on Android.
FetchContent_GetProperties(llama_cpp)
if(NOT llama_cpp_POPULATED)
    FetchContent_Populate(llama_cpp)
endif()

set(LLAMA_MODEL_SOURCE "${llama_cpp_SOURCE_DIR}/src/llama-model.cpp")
file(READ "${LLAMA_MODEL_SOURCE}" LLAMA_MODEL_TEXT)
set(LLAMA_PREFETCH_CALL "ml.init_mappings(true, use_mlock ? &pimpl->mlock_mmaps : nullptr);")
set(LLAMA_LAZY_CALL "ml.init_mappings(false, use_mlock ? &pimpl->mlock_mmaps : nullptr);")
if(LLAMA_MODEL_TEXT MATCHES "ml\\.init_mappings\\(true, use_mlock")
    string(REPLACE "${LLAMA_PREFETCH_CALL}" "${LLAMA_LAZY_CALL}" LLAMA_MODEL_TEXT "${LLAMA_MODEL_TEXT}")
    file(WRITE "${LLAMA_MODEL_SOURCE}" "${LLAMA_MODEL_TEXT}")
elseif(NOT LLAMA_MODEL_TEXT MATCHES "ml\\.init_mappings\\(false, use_mlock")
    message(FATAL_ERROR "Pinned llama.cpp source did not contain the expected init_mappings call")
endif()

add_subdirectory("${llama_cpp_SOURCE_DIR}" "${llama_cpp_BINARY_DIR}")

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
