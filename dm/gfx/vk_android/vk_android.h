// The NVN → Vulkan replay on Android: what the launcher calls (android/PORTING.md "NVN → Vulkan replay").
#pragma once
#include <stdint.h>

struct ANativeWindow;

// The surface to present to: the launcher calls it from any thread, before or after nxhost_main, again with every new surface (surfaceCreated / surfaceChanged) and with NULL when the
// surface goes (surfaceDestroyed, before it returns). The replay holds its own reference (ANativeWindow_acquire) until the next call. Without a window, presents are counted and dropped
__attribute__((visibility("default"))) void nxhost_set_window(struct ANativeWindow *w);
// No game needed: sets the window, then clears a texture in placed guest memory to a colour through the NVN replay (records, submit, fence), reads two texels back through a
// texture→buffer copy, and presents it for frames frames (the colour goes round the hues every 6 s). Returns 0 when every step worked; the log lines start with "[vk] selftest".
// Also run when NXHOST_VK_SELFTEST=<frames> is set at the first nxhost_set_window, on a thread of its own
__attribute__((visibility("default"))) int nxhost_vk_selftest(struct ANativeWindow *w, int frames);
// The overlay's frame rate (vka_fps.c), for the launcher to draw over the game: out[0] game frames presented a second, out[1] images presented a second (with frame generation
// on, the game's and the generated ones), out[2] the game's frame time in ms, out[3] frame generation (0 off, 1 generating, 2 on and not generating, -1 unavailable),
// out[4] images per game frame, out[5] the GPU's busy time a game frame in ms, averaged over recent frames (-1: no GPU timestamps; only computed when n > 5).
// n is out's length (at most 6 are written). 1 = fresh numbers, 0 = no present for 2.5 s (the rates are 0 then)
__attribute__((visibility("default"))) int nxhost_fps(float *out, int n);
// Frame generation (Lossless Scaling's LSFG, vka_framegen.c): on / off, images per game frame (2..4), a target output rate (0 = the fixed multiplier), the optical flow's
// resolution in % of the game's (0 = auto: 100 %), taken from the next frame. The status: 0 off, 1 generating, 2 on and not generating, -1 unavailable; buf gets the reason
__attribute__((visibility("default"))) void nxhost_framegen_set(int on, int multiplier, int target_rate, int flow_pct);
__attribute__((visibility("default"))) int nxhost_framegen_status(char *buf, int n);
// Where the letterboxed picture sits in the window's free space, never scaled or cropped: x 0 = left, 1 = right; y 0 = top, 1 = bottom (as the user sees it); 0.5 = centred,
// the default. Taken from the next present; values outside 0..1 are clamped
__attribute__((visibility("default"))) void nxhost_picture_place(float x, float y);
// The readiness check (vka_readiness.c): what the Vulkan device and driver report, as "key=value" lines in buf (n bytes, at least 64), on the driver the replay would load
// (DM_VK_DRIVER, read at the first call in the process: set it before). Its own instance, no device; callable before nxhost_main. 0 = facts written, 1 = an "error=" line
// says why the facts stop there, -1 = no buffer
__attribute__((visibility("default"))) int nxhost_vk_readiness(char *buf, int n);
