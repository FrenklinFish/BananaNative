/* SPDX-License-Identifier: MIT
 * Banana Native: the ZBIC normalized-count reader, written from a description of the format (README.md), not from any other implementation.
 *
 * ZBIC frames are Zstandard frames whose FSE table descriptions (the sequence tables in FSE_Compressed mode and the FSE header of compressed Huffman
 * weights) are coded differently. This file defines FSE_readNCount and FSE_readNCount_bmi2, the two entry points zstd's decoder calls for every such
 * description; zbic_entropy.c renames zstd's own reader out of the way. Same contract as zstd's: the counts of symbols 0..S (-1 = "less than one"),
 * S and the table log through the pointers, the bytes consumed as the result, or an error code (corruption_detected).
 *
 * Header: byte 0 = L (bit 7, the low-probability flag) | R (bits 0..6); bytes 1..R are a mixed-radix integer read from byte R down to byte 1 through a
 * 64-bit state that is refilled a byte at a time while below 2^40. Digits, in order: S - 1 (radix 52), tableLog - 5 (radix 8), then the total of
 * (count + L) over the symbols less one, or less S + 2 when L is set (radix 2^tableLog), then the cumulative sums of (count + L) at the inner points of
 * [0, N] (N the smallest power of two above S) by interpolative coding: the midpoint of each interval, pre-order, right half before left, each a digit of
 * radix (right sum - left sum + 1), an interval whose two ends are equal taking no digit and its inner points that value; then one more interval,
 * (0, 0, 1). Every payload byte must be consumed and the counts must add up (in absolute value) to 2^tableLog. */
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define FSE_STATIC_LINKING_ONLY
#include "../lib/common/fse.h"
#include "../lib/common/error_private.h"

#define ZBIC_MAX_S 52            /* the first digit's radix: S = digit + 1 */
#define ZBIC_REFILL_BELOW ((uint64_t)1 << 40)
#define ZBIC_MAX_N 255           /* the cumulative array's last index; N is a power of two, at most 64 for S <= 52 */

typedef struct {
    const uint8_t *in;   /* the header: in[1..pos] are the payload bytes not yet taken */
    size_t pos;
    uint64_t state;
} zbic_digits;

static void zbic_refill(zbic_digits *r)
{
    while (r->pos > 0 && r->state < ZBIC_REFILL_BELOW) {
        r->state = (r->state << 8) | r->in[r->pos];
        r->pos--;
    }
}

/* the next digit of radix m; 0 when m is 0, which only damaged data gives */
static int zbic_digit(zbic_digits *r, uint32_t m, uint32_t *d)
{
    if (m == 0) return 0;
    *d = (uint32_t)(r->state % m);
    r->state /= m;
    zbic_refill(r);
    return 1;
}

size_t FSE_readNCount_bmi2(short *normalizedCounter, unsigned *maxSVPtr, unsigned *tableLogPtr, const void *headerBuffer, size_t hbSize, int bmi2)
{
    const uint8_t *in = (const uint8_t *)headerBuffer;
    zbic_digits r;
    uint32_t c[ZBIC_MAX_N + 2];   /* c[x]: the sum of (count + L) over the symbols below x */
    uint32_t j, k, m, d, top;
    unsigned S, tableLog, N, L, R, s;
    int total, acc;
    (void)bmi2;

    if (hbSize < 2) return ERROR(corruption_detected);
    L = in[0] >> 7;
    R = in[0] & 0x7f;
    if ((size_t)R + 1 >= hbSize) return ERROR(corruption_detected);   /* the header is strictly shorter than what is available */

    r.in = in; r.pos = R; r.state = 0;
    zbic_refill(&r);
    if (!zbic_digit(&r, ZBIC_MAX_S, &j)) return ERROR(corruption_detected);
    S = j + 1;
    if (S > *maxSVPtr) return ERROR(corruption_detected);
    if (!zbic_digit(&r, 8, &k)) return ERROR(corruption_detected);
    tableLog = k + 5;
    if (!zbic_digit(&r, 1u << tableLog, &m)) return ERROR(corruption_detected);
    top = L ? S + m + 2 : m + 1;
    for (N = 1; N <= S; N <<= 1) {}
    if (N > ZBIC_MAX_N) return ERROR(corruption_detected);

    memset(c, 0, sizeof c);
    c[N] = top;
    {   /* the inner points: an explicit stack of intervals, the right half pushed last so that it is visited first */
        unsigned stack[2 * 16][2];
        int sp = 0;
        stack[sp][0] = 0; stack[sp][1] = N; sp++;
        while (sp > 0) {
            unsigned lo, hi, mid, x;
            uint32_t a, b;
            sp--; lo = stack[sp][0]; hi = stack[sp][1];
            if (hi - lo < 2) continue;
            mid = (lo + hi) / 2;
            a = c[lo]; b = c[hi];
            if (a == b) {
                for (x = lo + 1; x < hi; x++) c[x] = a;
            } else {
                if (!zbic_digit(&r, b - a + 1, &d)) return ERROR(corruption_detected);
                c[mid] = a + d;
            }
            if (sp + 2 > (int)(sizeof stack / sizeof stack[0])) return ERROR(corruption_detected);   /* cannot happen: the depth is log2(N) */
            stack[sp][0] = lo; stack[sp][1] = mid; sp++;
            stack[sp][0] = mid; stack[sp][1] = hi; sp++;
        }
    }
    if (c[0] != c[1]) {   /* the closing interval (0, 0, 1): its value is not used, but its digit takes payload bytes */
        if (!zbic_digit(&r, c[1] - c[0] + 1, &d)) return ERROR(corruption_detected);
        c[0] = c[0] + d;
    }

    memset(normalizedCounter, 0, (*maxSVPtr + 1) * sizeof normalizedCounter[0]);   /* as zstd's reader: symbols above S count 0 */
    total = 1 << tableLog;
    acc = 0;
    for (s = 0; s <= S; s++) {
        int v = (int16_t)(uint16_t)(c[s + 1] & 0xffff);
        int delta = (int16_t)(uint16_t)((v - acc) & 0xffff);
        int count = delta - (int)L;
        /* A cumulative sum never decreases in a stream an encoder wrote, so a count is never below -1 (-1 needs L set). Beyond the format's own checks,
         * a lower one is refused here: zstd's table builder treats only -1 as "less than one" and would leave cells of the decoding table unset. */
        if (count < -1) return ERROR(corruption_detected);
        normalizedCounter[s] = (short)count;
        total -= count < 0 ? -count : count;
        acc += delta;
    }
    if (total != 0 || r.pos != 0) return ERROR(corruption_detected);

    *maxSVPtr = S;
    *tableLogPtr = tableLog;
    return (size_t)R + 1;
}

size_t FSE_readNCount(short *normalizedCounter, unsigned *maxSVPtr, unsigned *tableLogPtr, const void *headerBuffer, size_t hbSize)
{
    return FSE_readNCount_bmi2(normalizedCounter, maxSVPtr, tableLogPtr, headerBuffer, hbSize, 0);
}
