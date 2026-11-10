/* SPDX-License-Identifier: MIT
 * Banana Native: zstd's zstd_decompress.c (unmodified, BSD) compiled with the ZBIC frame magic, "ZBIC" (0x4349425A little-endian), in place of
 * Zstandard's 0xFD2FB528. zstd.h is read first and its magic replaced; its include guard keeps the replacement when zstd_decompress.c includes it. */
#define ZSTD_STATIC_LINKING_ONLY
#include "../lib/zstd.h"
#undef ZSTD_MAGICNUMBER
#define ZSTD_MAGICNUMBER 0x4349425A
#include "../lib/decompress/zstd_decompress.c"
