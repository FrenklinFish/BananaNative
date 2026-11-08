// An image's format list (vka_tex.c img_create): the formats its views may take besides its own, free of Vulkan so the Mac's unit test
// (dm/host/tests/t_android_fmtlist.c) can check it. Formats are VkFormat values. One record, so an image remade in place (vka_tex.c img_storage_upgrade, img_own,
// img_rebase: the new VkImage swapped into the old vka_img) takes the new image's list whole with one assignment: the list is a property of the VkImage, and a record that
// kept the old image's list while the new one had more formats said "not listed" for a view the image could take. Ocarina of Time's 960x540 RGBA8 target, bound as an
// r32ui shader image, was remade with R32_UINT in its list on every bind and still read as unbound ("has no view of shape 0x81 (storage)")
#ifndef VKA_FMTLIST_H
#define VKA_FMTLIST_H
#include <stdint.h>

enum { VKFL_MAX = 4 };   // learned formats one image takes at most (vka_tex.c VL_PER_IMAGE)
typedef struct {
    int any;                 // DM_VK_UBWC=0: MUTABLE_FORMAT without a list, views of any format
    uint32_t alt;            // the one other format its views may take (its sRGB twin), or 0 (VK_FORMAT_UNDEFINED)
    uint32_t x[VKFL_MAX];    // further formats: ones textures over the same memory were seen to view it in (vka_tex.c, learned view formats)
    int nx;
} vkfl;

// The list takes views in v (own: the image's own format)
static inline int vkfl_has(const vkfl *l, uint32_t own, uint32_t v) {
    if (!v) return 0;
    if (v == own || v == l->alt || l->any) return 1;
    for (int k = 0; k < l->nx && k < VKFL_MAX; k++) if (l->x[k] == v) return 1;
    return 0;
}
// The VkImageFormatListCreateInfo entries (own first, no 0 entry), at most 2 + VKFL_MAX in out; 0 = the image needs no list (views in its own format only)
static inline int vkfl_entries(const vkfl *l, uint32_t own, uint32_t *out) {
    if (!l->alt && !l->nx) return 0;
    int n = 0; out[n++] = own;
    if (l->alt) out[n++] = l->alt;
    for (int k = 0; k < l->nx && k < VKFL_MAX; k++) out[n++] = l->x[k];
    return n;
}
// The usage a view of the image takes (vka_tex.c vka_view: VkImageViewUsageCreateInfo when it is less than the image's): the image's usage without storage when the
// view's format has no storage feature (an sRGB twin; RGB9E5 or R11G11B10 itself on an image whose storage use comes through a listed R32_UINT, EXTENDED_USAGE), and with
// it otherwise, whatever the view's format. Storage was left out of every view in another format than the image's own (when the only other format was the sRGB twin), so
// the R32_UINT views Ocarina of Time binds as shader images over RGB9E5, R11G11B10 and RGBA8 targets had no storage use: the compute pass that packs the scene into RGB9E5
// (a335c1db6270c355) wrote nothing on Adreno and the upscaler network sampled R11G11B10 bits as RGB9E5 (false colours). Values: VkImageUsageFlagBits / VkFormatFeatureFlagBits
enum { VKFL_USAGE_STORAGE = 0x8, VKFL_FEAT_STORAGE_IMAGE = 0x2 };
static inline uint32_t vkfl_view_usage(uint32_t img_usage, uint32_t view_feats) {
    return view_feats & VKFL_FEAT_STORAGE_IMAGE ? img_usage : img_usage & ~(uint32_t)VKFL_USAGE_STORAGE;
}
#endif
