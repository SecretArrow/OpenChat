#!/usr/bin/env bash
# Shared CI toolchain setup for the native (llama.cpp + Vulkan) build:
# CMake 3.31.1 via sdkmanager, LunarG Vulkan SDK (glslc + vulkan.hpp),
# pinned SPIRV-Headers config. Exports OPENCHAT_VK_INCLUDE and
# SPIRV_HEADERS_DIR for gradle→CMake. Used by ci.yml and android-ubuntu-e2e.yml.
set -e
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "cmake;3.31.1" >/dev/null 2>&1 || true
wget -qO /tmp/lunarg-key.asc "https://packages.lunarg.com/lunarg-signing-key-pub.asc"
test -s /tmp/lunarg-key.asc
sudo gpg --batch --yes --dearmor -o /usr/share/keyrings/lunarg-vulkan.gpg /tmp/lunarg-key.asc
echo "deb [signed-by=/usr/share/keyrings/lunarg-vulkan.gpg] https://packages.lunarg.com/vulkan noble main" | sudo tee /etc/apt/sources.list.d/lunarg-vulkan.list >/dev/null
sudo apt-get update -qq
sudo apt-get install -y -qq vulkan-sdk
glslc --version | head -1
# ggml-vulkan includes <vulkan/vulkan.hpp> (Vulkan-Hpp C++ bindings)
# which the NDK does not ship. The LunarG SDK headers are a
# consistent vulkan.h+vulkan.hpp pair; copy them into an isolated
# include dir (host /usr/include itself must NOT leak into the
# cross build) and forward the path to gradle→CMake.
VKHPP="$(find /usr -name vulkan.hpp -path '*vulkan*' 2>/dev/null | head -1)"
test -n "$VKHPP"
mkdir -p /tmp/vk-include
cp -r "$(dirname "$VKHPP")" /tmp/vk-include/vulkan
test -f /tmp/vk-include/vulkan/vulkan.hpp
# vulkan_core.h also includes the vk_video codec headers — same SDK.
VKVID="$(find /usr -type d -name vk_video 2>/dev/null | head -1)"
if [ -n "$VKVID" ]; then cp -r "$VKVID" /tmp/vk-include/vk_video; fi
echo "OPENCHAT_VK_INCLUDE=/tmp/vk-include" >> "$GITHUB_ENV"
# ggml-vulkan needs the SPIRV-Headers CMake config package. Host
# prefixes are invisible to the NDK cross-configure (root-path
# filtering), so provide a pinned Khronos install explicitly.
git clone --depth 1 --branch vulkan-sdk-1.4.309.0 https://github.com/KhronosGroup/SPIRV-Headers /tmp/spirv-headers-src
cmake -S /tmp/spirv-headers-src -B /tmp/spirv-headers-build -DCMAKE_INSTALL_PREFIX=/tmp/spirv-headers-install >/dev/null
cmake --install /tmp/spirv-headers-build >/dev/null
# ggml-vulkan also includes <spirv/unified1/spirv.hpp>; the official
# SDK ships it in the same include prefix — mirror that layout here.
mkdir -p /tmp/vk-include
cp -r /tmp/spirv-headers-install/include/spirv /tmp/vk-include/spirv
test -f /tmp/vk-include/spirv/unified1/spirv.hpp
# Config lands in <prefix>/share/cmake/SPIRV-Headers (DATADIR) — detect it.
SP_DIR="$(dirname "$(find /tmp/spirv-headers-install -name SPIRV-HeadersConfig.cmake | head -1)")"
test -n "$SP_DIR" && test -f "$SP_DIR/SPIRV-HeadersConfig.cmake"
echo "SPIRV_HEADERS_DIR=$SP_DIR" >> "$GITHUB_ENV"
