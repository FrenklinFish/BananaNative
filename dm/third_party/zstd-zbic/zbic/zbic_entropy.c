/* SPDX-License-Identifier: MIT
 * Banana Native: zstd's entropy_common.c (unmodified, BSD) with its normalized-count reader renamed, so that zstd's decoder links to the ZBIC reader of
 * zbic_ncount.c under the reader's own names. The renamed upstream functions are not called. */
#define FSE_readNCount zstd_upstream_FSE_readNCount
#define FSE_readNCount_bmi2 zstd_upstream_FSE_readNCount_bmi2
#include "../lib/common/entropy_common.c"
