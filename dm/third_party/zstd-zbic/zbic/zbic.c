/* SPDX-License-Identifier: MIT
 * Banana Native: libzbicdec's C entry points (zbic.h) on zstd's one-shot decoder. */
#include <limits.h>
#define ZSTD_STATIC_LINKING_ONLY
#include "../lib/zstd.h"
#include "../lib/zstd_errors.h"
#include "zbic.h"

#define ZBIC_API __attribute__((visibility("default")))

ZBIC_API long zbic_decompress(void *dst, size_t dst_size, const void *src, size_t src_size)
{
    size_t n;
    if (dst_size > LONG_MAX) dst_size = LONG_MAX;
    n = ZSTD_decompress(dst, dst_size, src, src_size);
    if (ZSTD_isError(n)) return -(long)ZSTD_getErrorCode(n);
    return (long)n;
}

ZBIC_API const char *zbic_error_name(long result)
{
    if (result >= 0) return "no error";
    return ZSTD_getErrorString((ZSTD_ErrorCode)-result);
}
