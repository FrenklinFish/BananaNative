// An image's format list (dm/gfx/vk_android/vka_fmtlist.h; vka_tex.c img_create, img_lists, vka_view, img_storage_upgrade): which view formats it takes, the entries of
// its VkImageFormatListCreateInfo, and that an image remade in place takes the new image's list whole (the record said "not listed" for a format the new VkImage had)
#include <stdio.h>
#include "../../gfx/vk_android/vka_fmtlist.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
enum { RGBA8 = 37, RGBA8_SRGB = 43, R32_UINT = 98, R32_SFLOAT = 100, B10G11R11 = 122, E5B9G9R9 = 123 };   // VkFormat values

int main(void) {
    // an image without a list: views in its own format only
    vkfl none = {0}; uint32_t e[2 + VKFL_MAX];
    CHECK(vkfl_has(&none, RGBA8, RGBA8) && !vkfl_has(&none, RGBA8, R32_UINT) && !vkfl_has(&none, RGBA8, 0), "no list: its own format only");
    CHECK(vkfl_entries(&none, RGBA8, e) == 0, "no list: no format list entries");
    // DM_VK_UBWC=0: any format, no list
    vkfl any = {.any = 1};
    CHECK(vkfl_has(&any, RGBA8, R32_UINT) && vkfl_entries(&any, RGBA8, e) == 0, "any: every format, no entries");
    // an RGBA8 target made as a render target: its sRGB twin and the pair learned for its size
    vkfl rt = {.alt = RGBA8_SRGB, .x = {B10G11R11}, .nx = 1};
    int n = vkfl_entries(&rt, RGBA8, e);
    CHECK(n == 3 && e[0] == RGBA8 && e[1] == RGBA8_SRGB && e[2] == B10G11R11, "render target: own, sRGB twin, learned (%d entries)", n);
    CHECK(vkfl_has(&rt, RGBA8, RGBA8_SRGB) && vkfl_has(&rt, RGBA8, B10G11R11) && !vkfl_has(&rt, RGBA8, R32_UINT), "render target: no R32_UINT");
    // no sRGB twin: no 0 entry
    vkfl r11 = {.x = {E5B9G9R9, R32_UINT, R32_SFLOAT}, .nx = 3};
    n = vkfl_entries(&r11, B10G11R11, e);
    int zero = 0; for (int k = 0; k < n; k++) zero |= !e[k];
    CHECK(n == 4 && e[0] == B10G11R11 && !zero, "no twin: own and three learned, no UNDEFINED entry (%d)", n);
    // remade in place with image intent (img_storage_upgrade): the new image's list has the learned shader-image format (R32_UINT for every size); the record that stays
    // (the old vka_img, which every serial and view shares) takes it whole, so a view in R32_UINT is listed after the remake and the next bind does not remake it again
    vkfl remade = {.alt = RGBA8_SRGB, .x = {B10G11R11, R32_UINT}, .nx = 2};
    vkfl im = rt;
    CHECK(!vkfl_has(&im, RGBA8, R32_UINT), "before the remake: not listed");
    im = remade;   // (vka_tex.c: im->fl = n->fl)
    CHECK(vkfl_has(&im, RGBA8, R32_UINT) && vkfl_has(&im, RGBA8, B10G11R11) && vkfl_has(&im, RGBA8, RGBA8_SRGB), "after the remake: R32_UINT listed with the rest");
    n = vkfl_entries(&im, RGBA8, e);
    CHECK(n == 4 && e[3] == R32_UINT, "after the remake: its entries are the new image's (%d)", n);
    // a count past VKFL_MAX never reads past the array
    vkfl bad = {.x = {1, 2, 3, 4}, .nx = 9};
    CHECK(vkfl_entries(&bad, 5, e) == 5 && !vkfl_has(&bad, 5, 6), "nx past the array: clamped");
    // a view's usage (vka_view): storage stays when the view's format has the storage feature, whatever the image's own format is. The R32_UINT shader-image view of an
    // RGB9E5 target (storage use through the listed R32_UINT, EXTENDED_USAGE) had storage left out and its compute writes went nowhere
    enum { SAMPLED = 0x4, STORAGE = 0x8, COLOR = 0x10, XFER = 0x3, F_SAMPLED = 0x1, F_STORAGE = 0x2, F_COLOR = 0x80 };
    uint32_t u = XFER | SAMPLED | STORAGE | COLOR;
    CHECK(vkfl_view_usage(u, F_SAMPLED | F_STORAGE | F_COLOR) == u, "R32_UINT view of an RGB9E5 image: storage kept");
    CHECK(vkfl_view_usage(u, F_SAMPLED) == (XFER | SAMPLED | COLOR), "RGB9E5 view of its own image (no storage feature): storage left out");
    CHECK(vkfl_view_usage(u, F_SAMPLED | F_COLOR) == (XFER | SAMPLED | COLOR), "sRGB twin view: storage left out");
    CHECK(vkfl_view_usage(XFER | SAMPLED, F_STORAGE) == (XFER | SAMPLED), "an image without storage: no storage added");
    printf("t_android_fmtlist: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
