cmake_minimum_required(VERSION 3.22)
project(arya-engine LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Pinned llama.cpp stable tag — recorded in PROGRESS.md
# Tag: v0.2.0 (2026-08-21, ggml-org/llama.cpp)
include(FetchContent)
FetchContent_Declare(llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        v0.2.0
    SOURCE_SUBDIR  .
)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

add_library(arya-engine SHARED engine_jni.cpp)

target_include_directories(arya-engine PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(arya-engine llama ggml log)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(arya-engine PRIVATE -O3 -fvisibility=hidden -march=armv8.2-a+fp16+dotprod -ffunction-sections -fdata-sections)
    target_link_options(arya-engine PRIVATE -Wl,--gc-sections)
else()
    target_compile_options(arya-engine PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
    target_link_options(arya-engine PRIVATE -Wl,--gc-sections)
endif()
