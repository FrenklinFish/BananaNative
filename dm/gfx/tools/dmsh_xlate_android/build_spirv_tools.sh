#!/bin/zsh
# Builds SPIRV-Tools' validator for the phone (android/ONDEVICE.md): libSPIRV-Tools-shared.so for arm64-v8a with the Android NDK, the library vk_android_build.py calls through ctypes
# (SPIRV_VAL=<.so>: spvValidateWithOptions, Vulkan 1.3 environment) where the Mac runs spirv-val. The version is the Mac's: Homebrew's spirv-tools 1.4.363.0 (spirv-val v2026.4),
# the Vulkan SDK tag vulkan-sdk-1.4.363.0 of SPIRV-Tools and SPIRV-Headers, so the phone and the Mac accept the same modules. The NDK build makes no executables (SPIRV-Tools
# skips them for Android); spv_val_run.c (build.sh run) is the adb shell test runner.
# Usage: build_spirv_tools.sh <work dir> [tag]   → <work dir>/libSPIRV-Tools-shared.so (stripped, 16 KB pages). Sources and the build go into <work dir> (keep it off the home folder)
set -e
W=${1:?usage: build_spirv_tools.sh <work dir> [tag]}; TAG=${2:-vulkan-sdk-1.4.363.0}
NDK=${ANDROID_NDK_HOME:-$(ls -d ~/Library/Android/sdk/ndk/* 2>/dev/null | sort -V | tail -1)}
[ -d "$NDK/toolchains/llvm/prebuilt" ] || { echo "[RED] no Android NDK (set ANDROID_NDK_HOME)"; exit 1; }
mkdir -p $W; W=${W:A}
[ -d $W/src ] || git clone -q --depth 1 -b $TAG https://github.com/KhronosGroup/SPIRV-Tools.git $W/src
[ -d $W/src/external/spirv-headers ] || git clone -q --depth 1 -b $TAG https://github.com/KhronosGroup/SPIRV-Headers.git $W/src/external/spirv-headers
cmake -S $W/src -B $W/build-android -G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 \
  -DCMAKE_BUILD_TYPE=Release -DSPIRV_SKIP_TESTS=ON -DSPIRV_WERROR=OFF -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 > $W/cmake.log
ninja -C $W/build-android SPIRV-Tools-shared > $W/ninja.log
$(ls -d $NDK/toolchains/llvm/prebuilt/*/bin | head -1)/llvm-strip -o $W/libSPIRV-Tools-shared.so $W/build-android/source/libSPIRV-Tools-shared.so
ls -la $W/libSPIRV-Tools-shared.so
