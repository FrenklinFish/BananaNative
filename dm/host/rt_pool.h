// A render target pool the loader sizes (profile key rt_pool, loader.c rt_pool_apply): the game makes the pool from one movz w<d>, #<MiB << 4>, lsl #16 in its text, and the
// pool's allocator range follows the size it records, so writing that one word is the whole change. The row names the word, the word the game has there (its own size), and
// one to four larger sizes, each with the render_cfg max_render it allows (hle_render.c takes it only when at least that size was written: rtp_cap). The loader picks the
// size from the setting the run starts with (rtp_need: the smallest size whose max_render holds its render size, at least the first larger one for an output of out_h lines
// or more), at least the first larger size on a device of more than 12 GB, and DM_RT_POOL_MB over both. Pure functions, so that the unit test (tests/t_render_cfg.c) checks
// the encoding, the choice and the checks without a game
#pragma once
#include <stdint.h>

#define RTP_TIERS 4   // larger sizes per row (game.py checks the count)
typedef struct {
    const char *build;           // the module's build ID (32 hex)
    const char *module;          // the module the word is in (main)
    uint64_t off;                // the word's offset in the module's text
    uint32_t word;               // the word the game has there: movz w<d>, #<base_mb << 4>, lsl #16
    unsigned base_mb;            // the game's own size
    unsigned nt;                 // larger sizes, ascending
    unsigned mb[RTP_TIERS], max_w[RTP_TIERS], max_h[RTP_TIERS];   // each, and render_cfg's max_render while at least that size is written
    unsigned out_h;              // an output of this many lines or more takes at least the first larger size (0: none)
} rtpool_row_t;

#define RTP_MIN_MB 16u     // DM_RT_POOL_MB's range (game.py checks the row's sizes against it): 1 MiB steps up to the largest movz w, lsl #16 gives below 4 GiB
#define RTP_MAX_MB 4095u

// movz w<d>, #imm16, lsl #16 (sf 0, opc 10, hw 1)
static inline int rtp_is_movz16(uint32_t w) { return (w & 0xFFE00000u) == 0x52A00000u; }
// The size in MiB a movz w, lsl #16 sets (its immediate is MiB << 4); 0 when it is not one or not whole MiB
static inline unsigned rtp_word_mb(uint32_t w) { unsigned imm = (w >> 5) & 0xFFFFu; return rtp_is_movz16(w) && !(imm & 0xF) ? imm >> 4 : 0; }
// The word for a size: the row's word with its immediate replaced (same register, same shift)
static inline uint32_t rtp_encode(uint32_t word, unsigned mb) { return (word & ~(0xFFFFu << 5)) | ((mb << 4) << 5); }

// DM_RT_POOL_MB: NULL or empty = unset (-1), 0 = the game's own size, RTP_MIN_MB..RTP_MAX_MB; -2 when another value (decimal digits only)
static inline long rtp_env_mb(const char *e) {
    if (!e || !*e) return -1;
    long v = 0;
    for (const char *p = e; *p; p++) { if (*p < '0' || *p > '9' || v > (long)RTP_MAX_MB) return -2; v = v * 10 + (*p - '0'); }
    return v == 0 ? 0 : v >= (long)RTP_MIN_MB && v <= (long)RTP_MAX_MB ? v : -2;
}

// The larger size a setting needs: -1 the game's own (its render size fits render_cfg's max_render cw x ch, and its output is below out_h), else the index of the smallest
// larger size whose max_render holds the render size (the largest when none does: the render is capped at its max_render), at least 0 for an output of out_h lines or more.
// rw x rh: the render size before any cap (rcfg_render with no cap)
static inline int rtp_need(const rtpool_row_t *r, unsigned cw, unsigned ch, unsigned oh, unsigned rw, unsigned rh) {
    int t = rw <= cw && rh <= ch ? -1 : (int)r->nt - 1;
    if (t >= 0) for (unsigned i = 0; i < r->nt; i++) if (rw <= r->max_w[i] && rh <= r->max_h[i]) { t = (int)i; break; }
    if (r->out_h && oh >= r->out_h && t < 0) t = 0;
    return t;
}

// The size for this run. env: rtp_env_mb's answer; low_ram: vka_low_ram() (1 a device of 12 GB or less or DM_VK_LOW_RAM=1, 0 more than 12 GB or DM_VK_LOW_RAM=0, -1 no
// predicate on this host); need: rtp_need's answer. Why: 0 DM_RT_POOL_MB, 1 the setting needs it, 2 more than 12 GB (the first larger size), 3 nothing needs more (the game's own)
static inline unsigned rtp_choose(const rtpool_row_t *r, int low_ram, long env, int need, int *why) {
    if (env == 0) { *why = 0; return r->base_mb; }
    if (env > 0) { *why = 0; return (unsigned)env; }
    if (low_ram == 0 && need <= 0 && r->nt) { *why = need == 0 ? 1 : 2; return r->mb[0]; }
    if (need >= 0 && (unsigned)need < r->nt) { *why = 1; return r->mb[need]; }
    *why = 3; return r->base_mb;
}

// The write: 1 written, 0 nothing to write (the game's own size), -1 the word is not the row's (nothing written). *was gets the word found
static inline int rtp_apply(uint32_t *p, const rtpool_row_t *r, unsigned mb, uint32_t *was) {
    *was = *p;
    if (*p != r->word) return -1;
    if (mb == r->base_mb) return 0;
    *p = rtp_encode(r->word, mb);
    return 1;
}
// render_cfg's max_render with mb MiB written this run (applied: rtp_apply's answer): the largest larger size's at or below mb, 0 when none is (render_cfg's own stays)
static inline int rtp_cap(const rtpool_row_t *r, int applied, unsigned mb, unsigned *w, unsigned *h) {
    int t = -1;
    if (applied == 1) for (unsigned i = 0; i < r->nt; i++) if (mb >= r->mb[i]) t = (int)i;
    if (t < 0) return 0;
    *w = r->max_w[t]; *h = r->max_h[t];
    return 1;
}
