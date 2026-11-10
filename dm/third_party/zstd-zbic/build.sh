#!/bin/sh
# Builds libzbicdec (README.md): build.sh <output library> [compiler and its flags...]   (default: cc, a shared library for this machine)
# android/ondevice/build.sh builds the phone's libzbicdec.so with the NDK's clang through it, dm/tools/tests/test_nso.py a copy for the tests on the Mac.
set -e
OUT=${1:?usage: build.sh <output library> [compiler and flags...]}; shift
[ $# -gt 0 ] || set -- cc
Z=$(cd "$(dirname "$0")" && pwd)
case $(uname -s) in Darwin) SHARED="-dynamiclib";; *) SHARED="-shared -Wl,-Bsymbolic -Wl,-soname,$(basename "$OUT")";; esac
for a in "$@"; do case $a in --target=*android*) SHARED="-shared -Wl,-Bsymbolic -Wl,-soname,$(basename "$OUT") -Wl,-z,max-page-size=16384";; esac; done
# Only zbic_decompress and zbic_error_name are exported (-fvisibility=hidden, zstd's own API hidden too), and the library binds its calls to itself, so a
# stock libzstd in the same process neither sees nor replaces the ZBIC table reader. The prefix map keeps this machine's paths out of the library.
"$@" $SHARED -fPIC -O2 -DNDEBUG -fvisibility=hidden -ffile-prefix-map="$Z"=zstd-zbic \
  -DZSTD_LEGACY_SUPPORT=0 -DZSTD_DISABLE_ASM -DZSTD_TRACE=0 -DDEBUGLEVEL=0 -DZSTDLIB_VISIBLE= -DZSTDERRORLIB_VISIBLE= -DXXH_NAMESPACE=ZBIC_ \
  -o "$OUT" \
  "$Z/lib/common/debug.c" "$Z/lib/common/error_private.c" "$Z/lib/common/fse_decompress.c" "$Z/lib/common/xxhash.c" "$Z/lib/common/zstd_common.c" \
  "$Z/lib/decompress/huf_decompress.c" "$Z/lib/decompress/zstd_ddict.c" "$Z/lib/decompress/zstd_decompress_block.c" \
  "$Z/zbic/zbic_entropy.c" "$Z/zbic/zbic_frame.c" "$Z/zbic/zbic_ncount.c" "$Z/zbic/zbic.c"
