cmake_minimum_required(VERSION 3.22)
project(bitnet-jni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# ---- Fetch llama.cpp from GitHub ----
include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp
    GIT_TAG        b9999
    SOURCE_SUBDIR  .
)

# Disable everything we don't need in llama.cpp
set(LLAMA_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_CUDA                OFF CACHE BOOL "" FORCE)
set(GGML_METAL               OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN              OFF CACHE BOOL "" FORCE)
set(GGML_BLAS                OFF CACHE BOOL "" FORCE)
set(GGML_RPC                 OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

# ---- Our JNI library ----
add_library(bitnet-jni SHARED
    bitnet_jni.cpp
)

target_include_directories(bitnet-jni PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(bitnet-jni
    llama
    ggml
    log
)

# Strip debug symbols for smaller .so
target_compile_options(bitnet-jni PRIVATE -O2 -fvisibility=hidden)
