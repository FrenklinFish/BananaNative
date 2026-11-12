// The hardware Opus decoder hooks (hle_codec.c) over a stand-in for the sdk's software OpusDecoder C interface. The stand-in lays its OpusDecoderType out as SDK 21's
// InitializeOpusDecoderImpl does (Ocarina of Time's sdk+0x327750: the work buffer at +0 and +8, its size at +0x10, rate and channels at +0x18). No game module is needed.
// Checked: ResetHardwareOpusDecoder and FinalizeHardwareOpusDecoder of the older interface reach the software decoder's own state, not its work buffer (0.3.12 reset the work
// buffer as a decoder: Ocarina of Time's sound streams decoded to silence), and a manager's decoder (SDK 21) still enqueues, decodes, dequeues, resets and finalizes.
#include "../hle_codec.c"
#include <assert.h>

static int checks, fails;
#define CHECK(c, msg) do { checks++; if (!(c)) { fails++; fprintf(stderr, "FAIL: %s\n", msg); } } while (0)

// objmap (hle_os.c) as a small table
static const void *om_k[64]; static void *om_v[64];
void *objmap_get(const void *k) { for (int i = 0; i < 64; i++) if (om_k[i] == k) return om_v[i]; return NULL; }
void objmap_put(const void *k, void *v) { for (int i = 0; i < 64; i++) if (!om_k[i]) { om_k[i] = k; om_v[i] = v; return; } abort(); }
void *objmap_del(const void *k) { for (int i = 0; i < 64; i++) if (om_k[i] == k) { om_k[i] = NULL; return om_v[i]; } return NULL; }
void hle_bind_system_event(void *ev, int autoclear) { (void)ev; (void)autoclear; }
uint64_t hle_system_event_binding(void *ev) { (void)ev; return 1; }
static int signals;
int hle_signal_system_event_binding(uint64_t binding) { (void)binding; signals++; return 1; }

// The software decoder stand-in: the state as the sdk writes it; each call records the state it was given
typedef struct { void *work0, *work8; size_t size; int rate, ch; } soft_state;
static const void *last_reset, *last_fini; static int resets, finis;
static size_t s_wsize(int rate, int ch) { (void)rate; return 0x100 * (size_t)ch; }
static int s_init(void *st, int rate, int ch, void *buf, size_t size) { soft_state *s = st; s->work0 = s->work8 = buf; s->size = size; s->rate = rate; s->ch = ch; memset(buf, 0x5a, size); return 0; }
static void s_fini(void *st) { last_fini = st; finis++; }
static void s_reset(void *st) { last_reset = st; resets++; }
static int s_rate(const void *st) { return ((const soft_state *)st)->rate; }
static int s_ch(const void *st) { return ((const soft_state *)st)->ch; }
static int s_decode(void *st, size_t *used, int *samples, int16_t *out, size_t out_size, const void *in, size_t in_size) {
    const soft_state *s = st; assert(s->size && s->work0 == s->work8);
    *used = in_size; *samples = 960; for (size_t i = 0; i < 960u * (size_t)s->ch && (i + 1) * 2 <= out_size; i++) out[i] = 1000; return 0;
}
void *guest_sym(const char *name) {
    static const struct { const char *n; void *f; } t[] = {
        {"_ZN2nn5codec28GetOpusDecoderWorkBufferSizeEii", s_wsize},
        {"_ZN2nn5codec21InitializeOpusDecoderEPNS0_15OpusDecoderTypeEiiPvm", s_init},
        {"_ZN2nn5codec19FinalizeOpusDecoderEPNS0_15OpusDecoderTypeE", s_fini},
        {"_ZN2nn5codec16ResetOpusDecoderEPNS0_15OpusDecoderTypeE", s_reset},
        {"_ZN2nn5codec24GetOpusDecoderSampleRateEPKNS0_15OpusDecoderTypeE", s_rate},
        {"_ZN2nn5codec26GetOpusDecoderChannelCountEPKNS0_15OpusDecoderTypeE", s_ch},
        {"_ZN2nn5codec21DecodeOpusInterleavedEPNS0_15OpusDecoderTypeEPmPiPsmPKvm", s_decode},
    };
    for (unsigned i = 0; i < sizeof t / sizeof *t; i++) if (!strcmp(t[i].n, name)) return t[i].f;
    return NULL;
}

int main(void) {
    // The older interface (Ocarina of Time imports only it): Reset and Finalize reach the software state
    static uint8_t game_dec[0x40], work[0x200];
    size_t ws = h_codec_HwOpusWorkSize(48000, 2, 0);
    CHECK(ws == 0x200, "work buffer size from the software decoder");
    CHECK(h_codec_HwOpusInit(game_dec, 48000, 2, work, ws, 0) == 0, "older interface: initialize");
    void *state = objmap_get(game_dec);
    CHECK(state && ((soft_state *)state)->work0 == work, "older interface: the software state holds the game's work buffer");
    h_codec_HwOpusReset(game_dec);
    CHECK(resets == 1 && last_reset == state, "older interface: Reset resets the software decoder's state (not its work buffer)");
    uint8_t probe[0x200]; memset(probe, 0x5a, sizeof probe);
    CHECK(!memcmp(work, probe, sizeof work), "older interface: Reset leaves the game's work buffer (libopus state) alone");
    CHECK(((soft_state *)state)->size == ws && h_codec_HwOpusRate(game_dec) == 48000 && h_codec_HwOpusCh(game_dec) == 2, "older interface: the state survives Reset");
    int16_t pcm[960 * 2]; size_t used = 0; int samples = 0; uint8_t pkt[16] = {0};
    CHECK(h_codec_HwOpusDecode(game_dec, &used, &samples, pcm, sizeof pcm, pkt, sizeof pkt) == 0 && samples == 960 && pcm[0] == 1000, "older interface: decodes after Reset");
    h_codec_HwOpusFini(game_dec);
    CHECK(finis == 1 && last_fini == state && !objmap_get(game_dec), "older interface: Finalize finalizes the software state and forgets it");

    // SDK 21's manager interface
    static uint8_t gm[0x18], gd[0x30], ev[0x40]; uint8_t mbuf[0x1000];
    CHECK(h_codec_HwOpusMgrInit(gm, ev, 48000, 2, 20000000, 2, 4, mbuf, sizeof mbuf) == 0, "manager: initialize");
    CHECK(h_codec_HwOpusMgrDecInit(gd, 48000, 2, 20000000, gm) == 0, "manager: decoder");
    CHECK(!objmap_get(gd), "manager: its decoder is not in objmap (the older interface's map)");
    uint8_t mp[8 + 16] = {0}; size_t c = 0;
    CHECK(h_codec_HwOpusEnqueue(gd, &c, mp, sizeof mp) == 0 && c == sizeof mp, "manager: enqueue");
    CHECK(h_codec_HwOpusWaiting(gd) == 1 && h_codec_HwOpusMgrFreeIo(gm) == 3, "manager: one waiting, one I/O buffer used");
    CHECK(h_codec_HwOpusExecute(gm) == 0 && signals == 1 && h_codec_HwOpusCompleted(gd) == 1, "manager: execute decodes and signals");
    samples = 0;
    CHECK(h_codec_HwOpusDequeue(gd, &samples, pcm, sizeof pcm) == 0 && samples == 960 && pcm[0] == 1000, "manager: dequeue");
    CHECK(h_codec_HwOpusMgrFreeIo(gm) == 4, "manager: the I/O buffer is back");
    int r0 = resets;
    CHECK(h_codec_HwOpusEnqueue(gd, &c, mp, sizeof mp) == 0, "manager: enqueue again");
    h_codec_HwOpusReset(gd);
    CHECK(resets == r0 + 1 && h_codec_HwOpusWaiting(gd) == 0 && h_codec_HwOpusMgrFreeIo(gm) == 4, "manager: Reset drops its requests");
    int f0 = finis;
    h_codec_HwOpusFini(gd);
    CHECK(finis == f0 + 1 && !opus_mdec_find(gd, 0), "manager: Finalize finalizes its decoder and forgets it");
    h_codec_HwOpusMgrFini(gm);

    printf("opus_hw: %d checks, %d failed\n", checks, fails);
    return fails != 0;
}
