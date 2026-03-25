cmake_minimum_required(VERSION 3.22.1)
project(pocketmonk_whisper)

include(FetchContent)

# ── Vulkan-Hpp: C++ bindings (header-only) ────────────────────────────────────
# NDK 27 ships VK_HEADER_VERSION 275 — match it exactly.
FetchContent_Declare(
    vulkan_hpp
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Hpp.git
    GIT_TAG        v1.3.275
    GIT_SHALLOW    TRUE
)

# ── Whisper.cpp ───────────────────────────────────────────────────────────────
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.7.3
    GIT_SHALLOW    TRUE
)

# Disable everything we don't need
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_METAL             OFF CACHE BOOL "" FORCE)
set(GGML_CUDA              OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL            OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN            ON  CACHE BOOL "" FORCE)
set(GGML_SYCL              OFF CACHE BOOL "" FORCE)
set(GGML_BLAS              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

# Populate without configuring so we can patch the source tree first
FetchContent_Populate(whisper)
FetchContent_Populate(vulkan_hpp)

# ── Patch whisper: replace ggml-vulkan CMakeLists with Android-friendly version
# The stock version calls find_package(Vulkan) and builds vulkan-shaders-gen
# (a host tool), both of which fail during cross-compilation.
# We substitute our own CMakeLists that:
#   • skips find_package / vulkan-shaders-gen
#   • uses pre-generated ggml-vulkan-shaders.{hpp,cpp} committed to the repo
#   • links against Android system libvulkan
set(GGML_VK_SHADERS_DIR  "${CMAKE_CURRENT_SOURCE_DIR}")
# vulkan.hpp lives at ${vulkan_hpp_SOURCE_DIR}/vulkan/vulkan.hpp
# so the include dir is the repo root.
# NDK toolchain already provides vulkan/vulkan_core.h via sysroot.
set(VULKAN_HPP_INCLUDE_DIR "${vulkan_hpp_SOURCE_DIR}")

configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/ggml-vulkan-android.cmake"
    "${whisper_SOURCE_DIR}/ggml/src/ggml-vulkan/CMakeLists.txt"
    COPYONLY
)

# Empty out the vulkan-shaders sub-CMakeLists (nothing to build there)
file(WRITE "${whisper_SOURCE_DIR}/ggml/src/ggml-vulkan/vulkan-shaders/CMakeLists.txt" "")

# ── Configure whisper ─────────────────────────────────────────────────────────
add_subdirectory("${whisper_SOURCE_DIR}" "${whisper_BINARY_DIR}")

# ── JNI bridge ────────────────────────────────────────────────────────────────
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
