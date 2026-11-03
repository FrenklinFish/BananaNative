// Front end on Android (front.h). The guest runs on the calling thread; the window does not pass through here: the launcher hands its Surface to the NVN → Vulkan replay
// with nxhost_set_window (dm/gfx/vk_android). There is no text input, no progress screen and no screen to match; nxhost_main is what the APK's launcher calls, with the
// arguments dmhost takes on the Mac (main.c)
#include "../dm.h"
#include "../front.h"
#include <malloc.h>
#include <stdatomic.h>
void front_run(void (*guest)(void)) { LOG("front end: the guest runs on this thread; presents go to the launcher's window (nxhost_set_window)"); guest(); }
void *front_metal_layer(void) { return NULL; }
int front_screen_max_fps(void) { return 0; }
int front_screen_variable_refresh(void) { return 0; }
void front_screen_match(int fps) { (void)fps; }
void front_progress(const char *title, long done, long total) { (void)done; (void)total; if (title) { static _Atomic int once; if (!atomic_exchange(&once, 1)) LOG("[RED] front end: no progress screen on Android yet (%s)", title); } }
int front_text_input(const char *title, const char *sub, const char *guide, const char *initial, int max_chars, char *out, int out_cap) {
    (void)title; (void)sub; (void)guide; (void)initial; (void)max_chars; (void)out; (void)out_cap; return -1;   // no window: the caller's no-window path
}
int main(int argc, char **argv);
// The launcher (android/app) calls this on a thread of its own with an 8 MB stack, which is not attached to ART. Heap pointer tagging goes off first: Bionic's malloc hands
// out pointers with a tag in the top byte (0xb4) since Android 11, and the host gives malloc'd blocks to the guest (its TLS and thread-local region, the libc passthrough),
// whose code may compare or mask pointers as Horizon's untagged ones. The manifest says the same (allowNativeHeapPointerTagging), this covers a launcher without it
__attribute__((visibility("default"))) int nxhost_main(int argc, char **argv) {
    if (!mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE)) LOG("[RED] heap pointer tagging could not be turned off: guest code sees tagged pointers");
    return main(argc, argv);
}
