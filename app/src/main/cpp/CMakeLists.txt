cmake_minimum_required(VERSION 3.22)
project("openchat-native" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(pty SHARED pty.c)

find_library(log-lib log)
target_link_libraries(pty ${log-lib})

# ---------------------------------------------------------------------------
# On-device GGUF inference (llama.cpp, pinned by CI — see .github/workflows).
# When the pinned source tree is absent (e.g. a plain local checkout), a stub
# with the same JNI symbols is built instead and reports "unavailable" honestly
# at runtime — the app never pretends the engine exists (spec §32).
# ---------------------------------------------------------------------------
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
if(EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
    # ---------------------------------------------------------------------------
    # GPU: enable the Vulkan backend for 64-bit ABIs when a shader compiler
    # (glslc, from the Vulkan SDK) is available on the build host. Devices
    # without a Vulkan driver fall back to CPU at load time — honest fallback,
    # never a fake GPU claim. armeabi-v7a stays CPU-only (legacy 32-bit GPUs).
    # ---------------------------------------------------------------------------
    find_program(GLSLC_PROG glslc)
    if(GLSLC_PROG AND (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64"))
        message(STATUS "OpenChat native: GGML_VULKAN=ON for ${ANDROID_ABI} (glslc: ${GLSLC_PROG})")
        set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    else()
        message(STATUS "OpenChat native: GGML_VULKAN=OFF for ${ANDROID_ABI} (glslc found: ${GLSLC_PROG})")
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
    add_subdirectory(${LLAMA_DIR} llama-build EXCLUDE_FROM_ALL)
    add_library(llamajni SHARED llama_jni.cpp)
    target_link_libraries(llamajni llama ${log-lib})
    # ggml-vulkan compiles <vulkan/vulkan.hpp> — the NDK ships only the C
    # headers. CI provides a consistent vulkan.h+vulkan.hpp pair from the
    # Vulkan SDK in an isolated dir; prepended so the pair stays matched.
    if(TARGET ggml-vulkan AND OPENCHAT_VK_INCLUDE AND EXISTS ${OPENCHAT_VK_INCLUDE}/vulkan/vulkan.hpp)
        target_include_directories(ggml-vulkan BEFORE PRIVATE ${OPENCHAT_VK_INCLUDE})
        message(STATUS "OpenChat native: Vulkan-Hpp headers from ${OPENCHAT_VK_INCLUDE}")
    endif()
else()
    add_library(llamajni SHARED llama_jni_stub.cpp)
    target_link_libraries(llamajni ${log-lib})
endif()
