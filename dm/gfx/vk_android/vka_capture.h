// NVN frame capture on Android (DM_NVN_CAPTURE, vka_capture.c) and its replayer on the phone (vka_replay.c, nxhost_replay): what the two share. The file format is
// dm/gfx/nvn/nvn_capture.h's, with the Vulkan replay's answers where the Mac's are Metal's: texture contents and the presented frame carry VkFormats, a program's interface hash is
// its library entries' binding records, and the header says "backend vulkan" (a Mac capture does not replay here, nor this one on the Mac)
#pragma once
#include "vka.h"
#include "../nvn/nvn_capture.h"
#include "../nvn/nvn_mtl.h"
#include "../nvn/dmsh_chain.h"
#include "../nvn/dmsh_chain_vk.h"

#define VCAP_IFACE 101   // header key iface: what vcap_iface hashes (the Mac's cap_iface is 2)

// One-off GPU work outside the submissions (texture contents in and out): a command buffer of its own, submitted on the queue and waited for
VkCommandBuffer vcap_cb_begin(void);
int vcap_cb_run(VkCommandBuffer cb);   // 1 = done
typedef struct { VkBuffer b; VkDeviceMemory m; uint8_t *p; VkDeviceSize n; } vcap_hbuf;   // host-visible, coherent
int vcap_hbuf_make(vcap_hbuf *h, VkDeviceSize n);
void vcap_hbuf_free(vcap_hbuf *h);

// An image's subresources as a capture stores them (plane 0 colour or depth, 1 stencil; rows packed): the table (malloc'd) and the bytes in all; 0 = not readable
// (multisampled, a format without a known block size)
uint32_t vcap_subs(const vka_img *im, cap_tsub **subs, uint64_t *total);
// Copies between an image (in GENERAL) and h, each subresource at a 16-byte aligned offset (vcap_sub_off): recorded into cb
VkDeviceSize vcap_sub_off(const cap_tsub *subs, uint32_t i);
void vcap_img_copy(VkCommandBuffer cb, vka_img *im, const cap_tsub *subs, uint32_t nsub, VkBuffer b, int to_image);
// Level 0, layer 0, plane 0 of im, rows packed, into a malloc'd buffer; *n its size. NULL = not readable
uint8_t *vcap_img_level0(vka_img *im, size_t *n);
// The presented texture's digest, as the capture and the replayer take it: SHA-256 of level 0 slice 0 with rows packed; pix (optional) gets the bytes, png (optional) a picture
int vcap_digest(const tex_t *t, uint8_t md[32], uint64_t *bytes, uint32_t *w, uint32_t *h, uint32_t *fmt, const char *png, int flip, cap_buf *pix);
// A PNG (8-bit RGBA) of rows packed in format f (the common colour formats; 0 = a format it cannot show). flip: bottom row first (a lower-left origin)
int vcap_png(const char *path, const uint8_t *px, uint32_t w, uint32_t h, VkFormat f, int flip);
// Per NVN stage of a program, a hash of the binding records of the library entry it resolves to (0 = none): a replay whose library gives another refuses the capture
void vcap_iface(const prog_t *p, uint64_t out[6]);
// The shader library the replay loads (vka_shader.c lib_load's path)
void vcap_shlib_path(char *out, size_t n);

// Accessors of the replay's own tables (vka_tex.c, vka_mem.c) and hle_nvn.c's side tables
int vka_cap_treg(int id, tex_t *t, view_t *v, uint8_t *has_v, uint8_t *img, int *n);
int vka_cap_sreg(int id, samp_par *p, int *n);
int vka_cap_cpuw(uint64_t serial);
vka_img *vka_cap_shared(const tex_t *t);
int vka_pools(uint64_t *lo, uint64_t *size, int max);
uint64_t vka_frame(void);
void nvn_prog_keys_set(const prog_t *p, const uint8_t key[6][8], uint8_t ok);
void nvn_dpool_base_set(const void *pool, int tex_base, int samp_base);
int nvn_host_readable(uint64_t a, uint64_t n);
int nvn_dpool_base(const void *pool, int tex);
void nvn_ft_poll(void);
