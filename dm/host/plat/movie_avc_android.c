// H.264 for the movie and video codec decoders (hle_movie_avc.h) on Android: the phone's AMediaCodec "video/avc" decoder, ByteBuffer output asked as NV12 (COLOR_FormatYUV420SemiPlanar),
// copied out through the output format's stride, slice height and crop (av_android.h). The interface is the one hle_movie_avc.c gives on VideoToolbox: SPS / PPS from Configure
// (csd-0 / csd-1, Annex B or length-prefixed) or the avcC box, or from the stream; one access unit per mavc_decode; pictures in presentation order from mavc_take, all of them with flush.
// MediaCodec reorders by itself and its pictures come in presentation order, so no reorder queue is needed: a picture is handed out as soon as the decoder gives it. The decoder is
// asynchronous inside: mavc_decode waits briefly for a picture while none is held, and a flush sends end-of-stream and drains. A new SPS / PPS makes a new decoder, as on Apple
#include "../dm.h"
#include "../hle_movie_avc.h"
#include "../android/av_android.h"
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <stdatomic.h>

#define QMAX 16
#define PENDING_MAX 8   // packets in the decoder without a picture out before mavc_decode waits for one
struct mavc {
    uint8_t sps[512], pps[512]; int nsps, npps, dirty;
    AMediaCodec *c; int cw, chh; char name[64];
    nx_yuv_layout L; int bt709, full;
    struct { uint8_t *nv12; int w, h; int64_t pts; } q[QMAX]; int nq;
    int pending, eos_sent, eos_done;
    uint8_t *au; size_t aucap;
    int red, red_fmt, rgba;
};

mavc *mavc_new(void) { mavc *a = calloc(1, sizeof *a); if (!a) die("movie: AVC decoder allocation failed"); return a; }
static void close_codec(mavc *a) { if (a->c) { AMediaCodec_stop(a->c); AMediaCodec_delete(a->c); a->c = NULL; } a->pending = a->eos_sent = a->eos_done = 0; }
void mavc_drop(mavc *a) {
    for (int i = 0; i < a->nq; i++) free(a->q[i].nv12);
    a->nq = 0;
    if (a->c && (a->pending || a->eos_sent)) AMediaCodec_flush(a->c);   // the decoder's own pictures belong to the old position too; csd given at configure is sent again by MediaCodec
    a->pending = a->eos_sent = a->eos_done = 0;
}
void mavc_free(mavc *a) { if (!a) return; mavc_drop(a); close_codec(a); free(a->au); free(a); }
int mavc_queued(const mavc *a) { return a->nq; }
void mavc_set_rgba(mavc *a) { if (a) a->rgba = 1; }   // converted at mavc_take: the decoder stays as it is

static void keep_ps(mavc *a, const uint8_t *nal, size_t n) {
    int t = nal[0] & 31; uint8_t *dst = t == 7 ? a->sps : a->pps; int *dn = t == 7 ? &a->nsps : &a->npps;
    if (n > sizeof a->sps) { if (!a->red++) LOG("[RED] movie: AVC parameter set of %zu bytes, too long", n); return; }
    if ((size_t)*dn == n && !memcmp(dst, nal, n)) return;
    memcpy(dst, nal, n); *dn = (int)n; a->dirty = 1;
}
// NAL units of p: Annex B start codes, or else 4-byte lengths (as hle_movie_avc.c's each_nal)
static size_t next_sc(const uint8_t *p, size_t n, size_t i) { for (; i + 3 <= n; i++) if (!p[i] && !p[i + 1] && p[i + 2] == 1) return i; return n; }
static void each_nal(const uint8_t *p, size_t n, void (*fn)(void *, const uint8_t *, size_t), void *ctx) {
    size_t s = next_sc(p, n, 0);
    if (s < n && s <= 1) {
        while (s < n) {
            size_t b = s + 3, e = next_sc(p, n, b), end = e;
            while (end > b && !p[end - 1]) end--;
            if (end > b) fn(ctx, p + b, end - b);
            s = e;
        }
    } else for (size_t i = 0; i + 4 <= n;) {
        size_t len = (size_t)p[i] << 24 | (size_t)p[i + 1] << 16 | (size_t)p[i + 2] << 8 | p[i + 3]; i += 4;
        if (!len || len > n - i) break;
        fn(ctx, p + i, len); i += len;
    }
}
static void ps_only(void *ctx, const uint8_t *nal, size_t n) { int t = nal[0] & 31; if (t == 7 || t == 8) keep_ps(ctx, nal, n); }
void mavc_param_sets(mavc *a, const uint8_t *p, size_t n) { each_nal(p, n, ps_only, a); }
void mavc_avcc(mavc *a, const uint8_t *p, size_t n) {
    if (n < 7 || p[0] != 1) { if (!a->red++) LOG("[RED] movie: unrecognized avcC record (%zu bytes, version %d)", n, n ? p[0] : -1); return; }
    size_t i = 5;
    for (int set = 0; set < 2 && i < n; set++) {
        int cnt = set ? p[i] : p[i] & 31; i++;
        for (int k = 0; k < cnt && i + 2 <= n; k++) { size_t len = (size_t)p[i] << 8 | p[i + 1]; i += 2; if (len > n - i) return; if (len) keep_ps(a, p + i, len); i += len; }
    }
}

static int open_codec(mavc *a) {
    close_codec(a);
    if (!a->nsps || !a->npps) { if (!a->red++) LOG("[RED] movie: AVC has no SPS / PPS, no pictures"); return 0; }
    int w = 1920, h = 1080; if (!avc_sps_size(a->sps, (size_t)a->nsps, &w, &h) && !a->red++) LOG("[RED] movie: AVC SPS size not readable, configuring 1920×1080");
    a->c = AMediaCodec_createDecoderByType("video/avc");
    if (!a->c) { if (!a->red++) LOG("[RED] movie: the phone has no H.264 decoder (video/avc), no pictures"); return 0; }
    uint8_t csd[2][4 + 512]; static const uint8_t sc[4] = {0, 0, 0, 1};
    memcpy(csd[0], sc, 4); memcpy(csd[0] + 4, a->sps, (size_t)a->nsps); memcpy(csd[1], sc, 4); memcpy(csd[1] + 4, a->pps, (size_t)a->npps);
    AMediaFormat *f = AMediaFormat_new();
    AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_WIDTH, w); AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_HEIGHT, h);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_COLOR_FORMAT, 21);   // COLOR_FormatYUV420SemiPlanar: NV12 in the ByteBuffer, which Codec2 converts to when asked
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, w * h * 3 / 2 > (1 << 20) ? w * h * 3 / 2 : 1 << 20);
    AMediaFormat_setBuffer(f, "csd-0", csd[0], 4 + (size_t)a->nsps); AMediaFormat_setBuffer(f, "csd-1", csd[1], 4 + (size_t)a->npps);
    media_status_t st = AMediaCodec_configure(a->c, f, NULL, NULL, 0);
    AMediaFormat_delete(f);
    if (st == AMEDIA_OK) st = AMediaCodec_start(a->c);
    if (st != AMEDIA_OK) { if (!a->red++) LOG("[RED] movie: AMediaCodec refuses the AVC configuration (%d, %d×%d), no pictures", (int)st, w, h); AMediaCodec_delete(a->c); a->c = NULL; return 0; }
    char *name = NULL; AMediaCodec_getName(a->c, &name); snprintf(a->name, sizeof a->name, "%s", name ? name : "AMediaCodec"); if (name) AMediaCodec_releaseName(a->c, name);
    a->cw = w; a->chh = h;
    a->L = (nx_yuv_layout){NXYUV_NV12, w, h, 0, 0, w, h}; a->bt709 = h >= 720; a->full = 0;
    LOG("[movie] AVC: %s decoding %d×%d, profile %d", a->name, w, h, a->nsps > 1 ? a->sps[1] : 0);
    a->dirty = 0; return 1;
}
static void out_format(mavc *a) {
    AMediaFormat *f = AMediaCodec_getOutputFormat(a->c); int32_t v, w = a->cw, h = a->chh, stride, slice, cf = 21;
    AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_WIDTH, &w); AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_HEIGHT, &h);
    AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_COLOR_FORMAT, &cf);
    if (!AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_STRIDE, &stride) || stride < w) stride = w;
    if (!AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SLICE_HEIGHT, &slice) || slice < h) slice = h;
    int32_t l = 0, t = 0, r = w - 1, b = h - 1;
    if (!AMediaFormat_getRect(f, AMEDIAFORMAT_KEY_DISPLAY_CROP, &l, &t, &r, &b)) {
        AMediaFormat_getInt32(f, "crop-left", &l); AMediaFormat_getInt32(f, "crop-top", &t); AMediaFormat_getInt32(f, "crop-right", &r); AMediaFormat_getInt32(f, "crop-bottom", &b);
    }
    if (l < 0 || t < 0 || r < l || b < t || r >= stride || b >= slice) { l = t = 0; r = w - 1; b = h - 1; }
    a->L = (nx_yuv_layout){nx_yuv_kind(cf), stride, slice, l, t, r - l + 1, b - t + 1};
    a->bt709 = a->L.ch >= 720; if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_COLOR_STANDARD, &v)) a->bt709 = v == 1;   // COLOR_STANDARD_BT709
    a->full = AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_COLOR_RANGE, &v) && v == 1;      // COLOR_RANGE_FULL
    AMediaFormat_delete(f);
    if (a->L.kind == NXYUV_NONE && !a->red_fmt++) LOG("[RED] movie: %s outputs colour format %#x, which the host cannot read: no pictures", a->name, cf);
    LOG("[movie] AVC output: %d×%d (crop %d,%d %d×%d), stride %d, slice height %d, colour format %#x, %s %s range", w, h, l, t, a->L.cw, a->L.ch, stride, slice, cf,
        a->bt709 ? "BT.709" : "BT.601", a->full ? "full" : "limited");
}
static void keep_picture(mavc *a, const uint8_t *p, size_t n, int64_t pts) {
    nx_yuv_layout L = a->L;
    if (L.kind == NXYUV_NONE) return;
    if (n < nx_yuv_bytes(&L) && L.slice > L.ct + L.ch) L.slice = L.ct + L.ch;   // a decoder whose slice height overstates its buffer: try the picture's own height
    int W = (L.cw + 1) & ~1, H = (L.ch + 1) & ~1;
    uint8_t *f = malloc((size_t)W * H * 3 / 2); if (!f) return;
    if (nx_yuv_to_nv12(p, n, &L, f)) { free(f); if (!a->red_fmt++) LOG("[RED] movie: %s gave a %zu-byte picture, smaller than its format (stride %d, slice height %d)", a->name, n, L.stride, L.slice); return; }
    if (a->nq == QMAX) { if (!a->red++) LOG("[RED] movie: AVC picture queue full, dropping the oldest picture"); free(a->q[0].nv12); memmove(a->q, a->q + 1, --a->nq * sizeof a->q[0]); }
    a->q[a->nq].nv12 = f; a->q[a->nq].w = W; a->q[a->nq].h = H; a->q[a->nq].pts = pts; a->nq++;
}
static void drain(mavc *a, int64_t wait_us) {
    while (a->c) {
        AMediaCodecBufferInfo info; ssize_t i = AMediaCodec_dequeueOutputBuffer(a->c, &info, wait_us);
        if (i == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) { out_format(a); continue; }
        if (i == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
        if (i < 0) return;
        wait_us = 0;
        size_t sz = 0; uint8_t *b = AMediaCodec_getOutputBuffer(a->c, (size_t)i, &sz);
        if (b && info.size > 0 && (size_t)info.offset + (size_t)info.size <= sz) { keep_picture(a, b + info.offset, (size_t)info.size, info.presentationTimeUs); if (a->pending) a->pending--; }
        if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) a->eos_done = 1;
        AMediaCodec_releaseOutputBuffer(a->c, (size_t)i, false);
        if (a->eos_done) return;
    }
}
static ssize_t input_slot(mavc *a) {   // an input buffer, taking pictures out meanwhile (a full decoder frees inputs only when its outputs are taken); about a second at most
    for (int k = 0; k < 50; k++) {
        ssize_t i = AMediaCodec_dequeueInputBuffer(a->c, 10000);
        if (i >= 0) return i;
        drain(a, 10000);
    }
    return -1;
}
typedef struct { mavc *a; size_t len; int picture; } au_ctx;
static void au_add(void *ctx, const uint8_t *nal, size_t n) {   // the access unit in Annex B, without parameter sets (they are in the decoder's csd)
    au_ctx *c = ctx; mavc *a = c->a; int t = nal[0] & 31;
    if (t == 7 || t == 8) { keep_ps(a, nal, n); return; }
    if (t == 9) return;
    if (t >= 1 && t <= 5) c->picture = 1;
    if (c->len + 4 + n > a->aucap) { a->aucap = (c->len + 4 + n) * 2; a->au = realloc(a->au, a->aucap); if (!a->au) die("movie: AVC buffer allocation failed"); }
    uint8_t *o = a->au + c->len; o[0] = o[1] = o[2] = 0; o[3] = 1; memcpy(o + 4, nal, n); c->len += 4 + n;
}
int mavc_decode(mavc *a, const uint8_t *p, int n, int64_t pts_us) {
    au_ctx c = {a, 0, 0}; each_nal(p, (size_t)n, au_add, &c);
    if (!c.len) return 0;
    if ((a->dirty || !a->c) && !open_codec(a)) return -1;
    if (a->eos_sent) { AMediaCodec_flush(a->c); a->pending = a->eos_sent = a->eos_done = 0; }   // packets after the end of a stream without a seek: a new stream
    ssize_t i = input_slot(a);
    if (i < 0) { if (!a->red++) LOG("[RED] movie: %s takes no input (first case): a packet is dropped", a->name); return -1; }
    size_t cap = 0; uint8_t *b = AMediaCodec_getInputBuffer(a->c, (size_t)i, &cap);
    if (!b || c.len > cap) { AMediaCodec_queueInputBuffer(a->c, (size_t)i, 0, 0, 0, 0); if (!a->red++) LOG("[RED] movie: AVC access unit of %zu bytes, the input buffer has %zu", c.len, cap); return -1; }
    memcpy(b, a->au, c.len);
    if (AMediaCodec_queueInputBuffer(a->c, (size_t)i, 0, c.len, (uint64_t)(pts_us < 0 ? 0 : pts_us), 0) != AMEDIA_OK) { if (!a->red++) LOG("[RED] movie: AVC packet not queued (first case)"); return -1; }
    if (c.picture) a->pending++;
    drain(a, a->nq ? 0 : 8000);
    for (int k = 0; k < 10 && a->pending > PENDING_MAX && !a->nq; k++) drain(a, 20000);
    return 0;
}
static void end_of_stream(mavc *a) {   // flush: every picture still inside the decoder comes out
    if (!a->c || a->eos_done) return;
    if (!a->eos_sent) {
        ssize_t i = input_slot(a); if (i < 0) return;
        AMediaCodec_queueInputBuffer(a->c, (size_t)i, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM); a->eos_sent = 1;
    }
    for (int k = 0; k < 50 && !a->eos_done && !a->nq; k++) drain(a, 20000);
}
int mavc_take(mavc *a, int flush, uint8_t *dst, int w, int h, int64_t *pts_us) {
    if (a->c) drain(a, 0);
    if (!a->nq && flush) end_of_stream(a);
    if (!a->nq) return 0;
    uint8_t *f = a->q[0].nv12; int pw = a->q[0].w, ph = a->q[0].h; if (pts_us) *pts_us = a->q[0].pts;
    memmove(a->q, a->q + 1, --a->nq * sizeof a->q[0]);
    int cw = pw < w ? pw : w, chh = ph < h ? ph : h;
    if (a->rgba) {   // a smaller picture is padded with opaque black, a larger one cropped
        if (cw < w || chh < h) for (size_t i = 0; i < (size_t)w * h; i++) ((uint32_t *)dst)[i] = 0xff000000u;
        uint8_t *rows = malloc((size_t)pw * 4 * chh);
        if (rows) {
            nx_nv12_to_rgba(f, f + (size_t)pw * ph, pw, chh, pw, rows, pw * 4, a->bt709, a->full);
            for (int r = 0; r < chh; r++) memcpy(dst + (size_t)r * w * 4, rows + (size_t)r * pw * 4, (size_t)cw * 4);
            free(rows);
        }
    } else {
        if (cw < w || chh < h) { memset(dst, 16, (size_t)w * h); memset(dst + (size_t)w * h, 128, (size_t)w * h / 2); }
        for (int r = 0; r < chh; r++) memcpy(dst + (size_t)r * w, f + (size_t)r * pw, (size_t)cw);
        size_t uvb = (size_t)((cw + 1) & ~1); if (uvb > (size_t)w) uvb = (size_t)w;
        for (int r = 0; r < (chh + 1) / 2 && r < h / 2; r++) memcpy(dst + (size_t)w * h + (size_t)r * w, f + (size_t)pw * ph + (size_t)r * pw, uvb);
    }
    free(f);
    return 1;
}
