cmake_minimum_required(VERSION 3.22.1)
project(pocketmonk_whisper)

include(FetchContent)

FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.5.5
    GIT_SHALLOW    TRUE
)

# Disable everything we don't need
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_CUDA              OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL            OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN            OFF CACHE BOOL "" FORCE)
set(GGML_SYCL              OFF CACHE BOOL "" FORCE)
set(GGML_BLAS              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(whisper)

# JNI bridge
add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${whisper_SOURCE_DIR}
    ${whisper_SOURCE_DIR}/ggml/include
)

target_link_libraries(whisper_jni
    whisper
    android
    log
)

target_compile_options(whisper_jni PRIVATE -O3 -ffast-math)
