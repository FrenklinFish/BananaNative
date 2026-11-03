// AAC for the movie decoder on AMediaCodec (aac_android.h). Synchronous use from the decoder's host thread: each frame is queued and the outputs that are ready are taken
// (waiting up to 5 ms while nothing is held), so a frame comes out about one frame after it went in, as AudioToolbox's converter primes on the Mac
#include "../dm.h"
#include "aac_android.h"
#include "av_android.h"
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

struct maac { AMediaCodec *c; int ch, rate, och, enc; int16_t *fifo; size_t fn, fcap; int red; };

maac *maac_new(int *rate, int *ch, const uint8_t *asc_in, int an) {
    static const int rates[] = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
    uint8_t asc[64]; if (an < 0 || an > (int)sizeof asc) an = 0;
    if (an) memcpy(asc, asc_in, (size_t)an);
    if (an >= 2) {   // sampling frequency index and channel configuration, as md_aac_open reads them on the Mac
        int fi = (asc[0] & 7) << 1 | asc[1] >> 7, cc = asc[1] >> 3 & 15;
        if (fi < 13 && *rate <= 0) *rate = rates[fi];
        if (cc && cc < 8 && *ch <= 0) *ch = cc == 7 ? 8 : cc;
    }
    if (*rate <= 0) *rate = 48000; if (*ch <= 0) *ch = 2;
    if (!an) { int fi = 3; for (int i = 0; i < 13; i++) if (rates[i] == *rate) fi = i; asc[0] = (uint8_t)(2 << 3 | fi >> 1); asc[1] = (uint8_t)((fi & 1) << 7 | (*ch & 15) << 3); an = 2; }
    AMediaCodec *c = AMediaCodec_createDecoderByType("audio/mp4a-latm");
    if (!c) { LOG("[RED] movie: the phone has no AAC decoder (audio/mp4a-latm), outputting silence"); return NULL; }
    AMediaFormat *f = AMediaFormat_new();
    AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, *rate);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, *ch);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_IS_ADTS, 0);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_PCM_ENCODING, 2);   // AudioFormat.ENCODING_PCM_16BIT
    AMediaFormat_setBuffer(f, "csd-0", asc, (size_t)an);
    media_status_t st = AMediaCodec_configure(c, f, NULL, NULL, 0);
    AMediaFormat_delete(f);
    if (st == AMEDIA_OK) st = AMediaCodec_start(c);
    if (st != AMEDIA_OK) { LOG("[RED] movie: AMediaCodec refuses the AAC configuration (%d; %d channels %d Hz), outputting silence", (int)st, *ch, *rate); AMediaCodec_delete(c); return NULL; }
    maac *m = calloc(1, sizeof *m); if (!m) { AMediaCodec_stop(c); AMediaCodec_delete(c); return NULL; }
    m->c = c; m->ch = m->och = *ch; m->rate = *rate; m->enc = 2;
    char *name = NULL; AMediaCodec_getName(c, &name);
    LOG("[movie] AAC: %s, %d channels %d Hz, AudioSpecificConfig %d bytes (object type %d)", name ? name : "AMediaCodec", *ch, *rate, an, asc[0] >> 3);
    if (name) AMediaCodec_releaseName(c, name);
    return m;
}
void maac_free(maac *m) { if (!m) return; AMediaCodec_stop(m->c); AMediaCodec_delete(m->c); free(m->fifo); free(m); }

static void keep(maac *m, const uint8_t *data, size_t bytes) {   // one output buffer into the FIFO, in the track's channel count
    size_t bps = m->enc == 4 ? 4 : 2, frames = bytes / (bps * (size_t)m->och);
    if (!frames) return;
    size_t need = m->fn + frames * (size_t)(m->och > m->ch ? m->och : m->ch);
    if (need > m->fcap) { int16_t *f = realloc(m->fifo, need * sizeof *f); if (!f) return; m->fifo = f; m->fcap = need; }
    int16_t *o = m->fifo + m->fn;
    if (m->enc == 4) for (size_t i = 0; i < frames * (size_t)m->och; i++) { float v; memcpy(&v, data + 4 * i, 4); v *= 32767.f; o[i] = (int16_t)(v > 32767.f ? 32767 : v < -32768.f ? -32768 : v); }
    else memcpy(o, data, frames * (size_t)m->och * 2);
    if (m->och != m->ch) ch_map(o, m->och, o, m->ch, frames);   // in place: grows only when och < ch, and the FIFO has room for the larger layout
    m->fn += frames * (size_t)m->ch;
}
static void drain(maac *m, int64_t wait_us) {
    for (;;) {
        AMediaCodecBufferInfo info; ssize_t i = AMediaCodec_dequeueOutputBuffer(m->c, &info, wait_us);
        wait_us = 0;
        if (i == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *f = AMediaCodec_getOutputFormat(m->c); int32_t v;
            if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &v) && v > 0 && v <= RS_MAXCH) m->och = v;
            if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_PCM_ENCODING, &v)) m->enc = v == 4 ? 4 : 2;
            if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &v) && v != m->rate && !m->red++) LOG("[RED] movie: the AAC decoder outputs %d Hz for a %d Hz track (SBR); played as it comes", v, m->rate);
            if (m->och != m->ch) LOG("[movie] AAC: the decoder outputs %d channels for a %d-channel track: mapped", m->och, m->ch);
            AMediaFormat_delete(f); continue;
        }
        if (i == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
        if (i < 0) return;
        size_t sz = 0; uint8_t *b = AMediaCodec_getOutputBuffer(m->c, (size_t)i, &sz);
        if (b && info.size > 0 && (size_t)info.offset + (size_t)info.size <= sz) keep(m, b + info.offset, (size_t)info.size);
        AMediaCodec_releaseOutputBuffer(m->c, (size_t)i, false);
    }
}
int maac_decode(maac *m, const uint8_t *p, int n, int64_t pts_us, int16_t *out, int max_vals) {
    if (n > 0) {
        ssize_t i = AMediaCodec_dequeueInputBuffer(m->c, 20000);
        if (i < 0) { drain(m, 0); i = AMediaCodec_dequeueInputBuffer(m->c, 50000); }
        if (i < 0) { if (!m->red++) LOG("[RED] movie: the AAC decoder takes no input (first case): a frame is dropped"); }
        else {
            size_t cap = 0; uint8_t *b = AMediaCodec_getInputBuffer(m->c, (size_t)i, &cap);
            size_t k = b && (size_t)n <= cap ? (size_t)n : 0;
            if (b && k) memcpy(b, p, k);
            AMediaCodec_queueInputBuffer(m->c, (size_t)i, 0, k, (uint64_t)(pts_us < 0 ? 0 : pts_us), 0);
        }
    }
    drain(m, m->fn ? 0 : 5000);
    size_t v = m->fn < (size_t)max_vals ? m->fn : (size_t)max_vals;
    v -= v % (size_t)m->ch;
    if (!v) return 0;
    memcpy(out, m->fifo, v * sizeof *out);
    m->fn -= v; if (m->fn) memmove(m->fifo, m->fifo + v, m->fn * sizeof *m->fifo);
    return (int)v;
}
