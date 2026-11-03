#!/bin/zsh
# Builds the Android translator (dmsh_xlate_android.csproj, android/ONDEVICE.md) on the Mac: .NET 10 SDK + the Android NDK (its clang links the NativeAOT output for bionic).
# Usage: build.sh <output dir> [exe|lib|run|all]   (default all)   → <output dir>/exe/dmsh_xlate (an executable for adb shell tests), <output dir>/lib/libdmsh_xlate.so (the app's),
#        <output dir>/run/xlate_run (xlate_run.c: calls the library from threads, as the app does), <output dir>/run/spv_val_run (spv_val_run.c: the same for SPIRV-Tools' validator,
#        libSPIRV-Tools-shared.so from build_spirv_tools.sh)
# NDK: $ANDROID_NDK_HOME, else the newest under ~/Library/Android/sdk/ndk. Intermediate files: obj/ beside the project (git ignores it)
set -e
O=${1:?usage: build.sh <output dir> [exe|lib|run|all]}; WHAT=${2:-all}; HERE=${0:A:h}
NDK=${ANDROID_NDK_HOME:-$(ls -d ~/Library/Android/sdk/ndk/* 2>/dev/null | sort -V | tail -1)}
[ -d "$NDK/toolchains/llvm/prebuilt" ] || { echo "[RED] no Android NDK (set ANDROID_NDK_HOME)"; exit 1; }
export PATH=$(ls -d $NDK/toolchains/llvm/prebuilt/*/bin | head -1):$PATH
mkdir -p $O; O=${O:A}
mkdir -p $HERE/obj && python3 $HERE/../xlate_tree.py > $HERE/obj/tree.sha   # embedded as the library's --tree-sha (vk_android_build.py checks it)
pub() {   # pub <form dir> [msbuild properties...]
  local f=$1; shift; rm -rf $HERE/obj/Release/net10.0/linux-bionic-arm64/native   # the two forms share obj/: link each afresh
  dotnet publish $HERE/dmsh_xlate_android.csproj -c Release -nologo -v quiet -o $O/$f "$@"
}
[[ $WHAT == exe || $WHAT == all ]] && pub exe
[[ $WHAT == lib || $WHAT == all ]] && { pub lib -p:NativeLib=Shared -p:OutputType=Library; mv -f $O/lib/dmsh_xlate.so $O/lib/libdmsh_xlate.so; }
[[ $WHAT == run || $WHAT == all ]] && { mkdir -p $O/run; clang --target=aarch64-linux-android33 -O2 -Wall -o $O/run/xlate_run $HERE/xlate_run.c -ldl
  clang --target=aarch64-linux-android33 -O2 -Wall -o $O/run/spv_val_run $HERE/spv_val_run.c -ldl; }
ls -la $O/exe $O/lib $O/run 2>/dev/null | grep -E 'dmsh_xlate($|\.so$)|xlate_run$' || true
