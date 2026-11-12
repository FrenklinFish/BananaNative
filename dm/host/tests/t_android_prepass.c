// The depth-image rule of mesh_prepass_cut (dm/gfx/vk_android/vka_prepass.h): a prepass draw is cut only into a depth image of the shape its pair's colour draws tested
// EQUAL against lately, told by shape so that a scene depth texture initialized anew each frame still matches, and a shadow map does not
#include "../../gfx/vk_android/vka_prepass.h"
#include <stdio.h>

static int g_fail;
#define CHECK(c, ...) do { if (!(c)) { g_fail++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

int main(void) {
    // Ocarina of Time's scene depth (1440x810, a 32-bit float depth format) and a shadow image (another format and size)
    const uint32_t D32 = 0x36, D16 = 0x32;
    uint64_t scene = vkpp_depth_shape(D32, 1440, 810, 1, 1, 0), scene2 = vkpp_depth_shape(D32, 1440, 810, 1, 0, 0);
    uint64_t shadow = vkpp_depth_shape(D16, 1000, 1000, 3, 1, 0), layer1 = vkpp_depth_shape(D32, 1440, 810, 1, 1, 1);
    CHECK(scene && scene == scene2, "the same shape (samples 0 and 1 both single-sampled) gives the same identity, never 0");
    CHECK(scene != shadow && scene != layer1 && scene != vkpp_depth_shape(D32, 1280, 720, 1, 1, 0), "another format, size or layer gives another identity");

    vkpp_pair p = {0};
    // frame 1: the prepass comes first; no colour draw seen yet: drawn as asked; then the colour draw keeps EQUAL
    CHECK(!vkpp_prepass(&p, scene, 1), "frame 1: the first prepass draw is drawn as asked");
    CHECK(!vkpp_colour(&p, scene, 1), "frame 1: its colour draw tests EQUAL as asked");
    // frame 2 onwards: the game initializes its depth texture anew (another serial), the shape stays: the prepass is cut, the colour draw takes its depth
    for (uint64_t f = 2; f < 40; f++) {
        CHECK(!vkpp_prepass(&p, shadow, f), "frame %llu: a shadow draw of the same program is drawn as asked", (unsigned long long)f);
        CHECK(vkpp_prepass(&p, scene, f), "frame %llu: the prepass draw is cut", (unsigned long long)f);
        CHECK(vkpp_colour(&p, scene, f), "frame %llu: the colour draw takes the cut prepass's depth", (unsigned long long)f);
    }
    // the colour pass stops (another scene): within 4 frames still cut, then drawn as asked
    CHECK(vkpp_prepass(&p, scene, 43), "4 frames after the last colour draw: still cut");
    CHECK(!vkpp_prepass(&p, scene, 44), "5 frames after: drawn as asked");
    CHECK(!vkpp_colour(&p, scene, 46), "a colour draw two frames after the last cut: EQUAL as asked");
    // two depth images of the pair's colour draws (two views): both kept
    vkpp_pair q = {0};
    vkpp_colour(&q, scene, 1); vkpp_colour(&q, layer1, 1);
    CHECK(vkpp_prepass(&q, scene, 2) && vkpp_prepass(&q, layer1, 2) && !vkpp_prepass(&q, shadow, 2), "two depth images the colour draws tested against: both cut, the shadow not");
    CHECK(!vkpp_prepass(&q, 0, 2), "no depth target: drawn as asked");

    // which cut prepass draws are still run with rasterizer discard (prepass_side): those whose shaders write guest memory. Ocarina of Time's 65a079a285d693a0 has a
    // storage buffer it adds into (binding 7, RW); d98076e4148db34f only reads its uniform and storage buffers
    dmsh_vk_bind rd[3] = {{.binding = 0, .kind = DMSH_VK_KIND_CBUF}, {.binding = 4, .kind = DMSH_VK_KIND_SBUF}, {.binding = 5, .kind = DMSH_VK_KIND_CBUF}};
    dmsh_vk_bind wr[3] = {{.binding = 0, .kind = DMSH_VK_KIND_CBUF}, {.binding = 7, .kind = DMSH_VK_KIND_SBUF, .flags = DMSH_VK_BF_RW}, {.binding = 5, .kind = DMSH_VK_KIND_CBUF}};
    dmsh_vk_bind img[1] = {{.binding = 9, .kind = DMSH_VK_KIND_IMG, .flags = DMSH_VK_BF_ATOMIC}};
    dmsh_vk_bind unk[1] = {{.binding = 9, .kind = DMSH_VK_KIND_SBUF, .flags = DMSH_VK_BF_NOSLOT}};
    CHECK(!vkpp_binds_write(rd, 3) && !vkpp_binds_write(NULL, 0), "a shader that only reads writes nothing: its cut prepass is not run");
    CHECK(vkpp_binds_write(wr, 3), "a storage buffer it writes: run with rasterizer discard");
    CHECK(vkpp_binds_write(img, 1) && vkpp_binds_write(unk, 1), "image atomics, or a slot not decoded: counted as writes");

    if (g_fail) { printf("t_android_prepass: %d FAILED\n", g_fail); return 1; }
    printf("PASS: mesh_prepass_cut depth-image rule (vka_prepass.h): by shape, a depth texture renewed each frame still matches, a shadow image does not\n");
    return 0;
}
