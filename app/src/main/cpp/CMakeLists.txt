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
    add_subdirectory(${LLAMA_DIR} llama-build EXCLUDE_FROM_ALL)
    add_library(llamajni SHARED llama_jni.cpp)
    target_link_libraries(llamajni llama ${log-lib})
else()
    add_library(llamajni SHARED llama_jni_stub.cpp)
    target_link_libraries(llamajni ${log-lib})
endif()
