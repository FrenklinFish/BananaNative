/* SPDX-License-Identifier: MIT
 * Banana Native: libzbicdec, a decoder of ZBIC, the Zstandard variant of the segments of system 22.0.0+ NSO modules (dm/tools/nso.py loads it with ctypes). */
#ifndef ZBIC_H
#define ZBIC_H
#include <stddef.h>

/* Decompresses the ZBIC frames of src (src_size bytes) into dst (dst_size bytes). Returns the number of bytes written, or a negative value on any
 * error: -(the zstd error code), whose name zbic_error_name gives. Damaged or truncated input is an error, never a read or write out of bounds. */
long zbic_decompress(void *dst, size_t dst_size, const void *src, size_t src_size);
const char *zbic_error_name(long result);
#endif
