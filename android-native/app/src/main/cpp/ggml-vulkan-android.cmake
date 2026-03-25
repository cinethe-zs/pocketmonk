# Android Vulkan backend — pre-generated shaders, no glslc needed at build time.
# NDK toolchain provides vulkan/vulkan_core.h automatically via sysroot.
# VULKAN_HPP_INCLUDE_DIR and GGML_VK_SHADERS_DIR are set by the parent CMakeLists.txt.

ggml_add_backend_library(ggml-vulkan
    ggml-vulkan.cpp
    ${GGML_VK_SHADERS_DIR}/ggml-vulkan-shaders.cpp
    ${GGML_VK_SHADERS_DIR}/ggml-vulkan-shaders.hpp
    ${GGML_VK_SHADERS_DIR}/ggml-vulkan-coopmat-stubs.cpp
)

target_include_directories(ggml-vulkan PRIVATE
    ${GGML_VK_SHADERS_DIR}       # ggml-vulkan-shaders.hpp
    ${VULKAN_HPP_INCLUDE_DIR}    # vulkan/vulkan.hpp  (from Vulkan-Hpp)
    ${CMAKE_CURRENT_BINARY_DIR}  # ggml-vulkan.cpp looks here for generated shaders
)

target_link_libraries(ggml-vulkan PRIVATE vulkan)
