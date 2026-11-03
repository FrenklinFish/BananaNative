// Image memory: small images share 64 MiB blocks instead of a VkDeviceMemory each (android/PORTING.md, NVN → Vulkan replay, Textures).
// Every image had a dedicated allocation of its own. Beside the placed pool segments (vka_mem.c) that runs into the device's maxMemoryAllocationCount: Mario Kart World's
// image allocations all failed from about 2,300 images on (beside 1,732 segments, "-2; 262 MiB in images", small images too), and Donkey Kong Bananza keeps about 7,200.
// The count problem is thousands of small images, so only an image under VKA_IMEM_MAX (4 MiB) whose memory requirements neither require nor prefer a dedicated allocation
// (VkMemoryDedicatedRequirements) takes space in a block of its memory type: exactly the 256-byte units its size needs, at its alignment, first fit (vka_extents.h). Larger
// images, the ones the driver wants dedicated, every image with DM_VK_IMGMEM=0, and an image whose block could not be allocated keep a dedicated allocation as before.
// Only optimal-tiling images live in the blocks, so bufferImageGranularity (which separates linear from optimal resources) does not apply between them.
// Memory overhead: block space no image uses is committed memory too (the "free in blocks" of the stats line), which matters to a game near the low-memory killer (Donkey Kong
// Bananza: 8.07 GB RSS, killed at about 9.2 GB); an earlier version rounded every image up to a power of two (a third more for mipped textures, up to half for 8-16 MiB ones).
// Lifetime: as before, an image's memory is given back in img_destroy, which runs once the GPU is done with the image (vka_after), after vkDestroyImage. A block with no image
// left is freed, except one empty block a memory type keeps for the next images. The 64 MiB vkAllocateMemory runs outside the lock (other threads' allocations and frees go on)
#if defined(__ANDROID__)
#include "vka.h"
#include "vka_extents.h"
#include <stdlib.h>

#define UNIT 256ull
#define BLOCK (64ull << 20)
#define VKA_IMEM_MAX (4ull << 20)            // images of at least 4 MiB keep their own allocation
typedef struct iblock { struct iblock *next; VkDeviceMemory dm; uint32_t type; vke_list free; } iblock;
static iblock *g_blocks[VK_MAX_MEMORY_TYPES];
static pthread_mutex_t g_imu = PTHREAD_MUTEX_INITIALIZER;
// Stats (under g_imu): blocks, images in blocks (count, units' bytes, the images' own sizes), dedicated allocations (count, bytes), blocks freed, block allocations that failed
static struct { uint64_t blocks, runs, run_bytes, req_bytes, ded, ded_bytes, freed, fail; } S;

// Shared memory for the image (it fits a block): 1, or 0 (a dedicated allocation)
static int sharable(VkImage img, uint32_t type, const VkMemoryRequirements *mr) {
    if (!VKA_ENV("DM_VK_IMGMEM", 1) || mr->size >= VKA_IMEM_MAX || mr->alignment > VKA_IMEM_MAX || type >= VK_MAX_MEMORY_TYPES || BLOCK > V.max_alloc
        || !vk.vkGetImageMemoryRequirements2) return 0;
    VkMemoryDedicatedRequirements dr = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS};
    VkMemoryRequirements2 r2 = {VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2, &dr};
    VkImageMemoryRequirementsInfo2 ri = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2, NULL, img};
    vk.vkGetImageMemoryRequirements2(V.dev, &ri, &r2);
    return !dr.prefersDedicatedAllocation && !dr.requiresDedicatedAllocation;
}
static VkResult dedicated(VkImage img, uint32_t type, const VkMemoryRequirements *mr, vka_imem *m) {   // as before: one allocation for the image
    VkMemoryDedicatedAllocateInfo dai = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, .image = img};
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &dai, .allocationSize = mr->size, .memoryTypeIndex = type};
    VkResult r;
    if ((r = vk.vkAllocateMemory(V.dev, &ai, NULL, &m->dm))) { m->dm = VK_NULL_HANDLE; return r; }
    if ((r = vk.vkBindImageMemory(V.dev, img, m->dm, 0))) { vk.vkFreeMemory(V.dev, m->dm, NULL); m->dm = VK_NULL_HANDLE; return r; }
    m->size = mr->size;
    pthread_mutex_lock(&g_imu); S.ded++; S.ded_bytes += mr->size; pthread_mutex_unlock(&g_imu);
    return VK_SUCCESS;
}
static int take(iblock *b, uint32_t n, uint32_t al, const VkMemoryRequirements *mr, vka_imem *m) {   // n units of b (caller holds g_imu): 1, or 0 when they do not fit
    int64_t u = vke_alloc(&b->free, n, al);
    if (u < 0) return 0;
    S.runs++; S.run_bytes += n * UNIT; S.req_bytes += mr->size;
    m->dm = b->dm; m->off = (uint64_t)u * UNIT; m->blk = b; m->units = n; m->size = mr->size;
    return 1;
}

// The image's memory (type: the memory type the caller picked from mr's memoryTypeBits): space in a block, or a dedicated allocation as before; bound to the image.
// VK_SUCCESS, or the Vulkan error with nothing allocated
VkResult vka_imem_bind(VkImage img, uint32_t type, const VkMemoryRequirements *mr, vka_imem *m) {
    memset(m, 0, sizeof *m);
    if (!sharable(img, type, mr)) return dedicated(img, type, mr, m);
    uint32_t n = (uint32_t)((mr->size + UNIT - 1) / UNIT), al = mr->alignment > UNIT ? (uint32_t)(mr->alignment / UNIT) : 1;
    if (al & (al - 1)) return dedicated(img, type, mr, m);   // (Vulkan alignments are powers of two)
    pthread_mutex_lock(&g_imu);
    int got = 0;
    for (iblock *b = g_blocks[type]; b && !got; b = b->next) got = take(b, n, al, mr, m);
    pthread_mutex_unlock(&g_imu);
    if (!got) {   // a new block, allocated without the lock; another thread may have added one meanwhile, which is tried first
        iblock *nb = calloc(1, sizeof *nb);
        VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = BLOCK, .memoryTypeIndex = type};
        int ok = nb && vke_init(&nb->free, (uint32_t)(BLOCK / UNIT)) && !vk.vkAllocateMemory(V.dev, &ai, NULL, &nb->dm);
        if (nb && !ok) nb->dm = VK_NULL_HANDLE;
        pthread_mutex_lock(&g_imu);
        for (iblock *b = g_blocks[type]; b && !got; b = b->next) got = take(b, n, al, mr, m);
        if (!got && ok) { nb->type = type; nb->next = g_blocks[type]; g_blocks[type] = nb; S.blocks++; got = take(nb, n, al, mr, m); nb = NULL; }
        if (!ok) S.fail++;
        pthread_mutex_unlock(&g_imu);
        if (nb) { if (nb->dm) vk.vkFreeMemory(V.dev, nb->dm, NULL); vke_fini(&nb->free); free(nb); }   // not needed (another thread's block had room) or not allocated
        if (!got) return dedicated(img, type, mr, m);   // the block could not be allocated: this image as before
    }
    VkResult r;
    if ((r = vk.vkBindImageMemory(V.dev, img, m->dm, m->off))) { vka_imem_free(m); return r; }
    return VK_SUCCESS;
}

// The memory back (after vkDestroyImage of the image bound to it, once the GPU is done with it)
void vka_imem_free(vka_imem *m) {
    if (!m->dm) return;
    if (!m->blk) {
        vk.vkFreeMemory(V.dev, m->dm, NULL);
        if (m->size) { pthread_mutex_lock(&g_imu); S.ded--; S.ded_bytes -= m->size; pthread_mutex_unlock(&g_imu); }   // (size 0: an allocation made elsewhere, vka_framegen.c)
        memset(m, 0, sizeof *m); return;
    }
    iblock *b = m->blk, *gone = NULL;
    pthread_mutex_lock(&g_imu);
    int fr = vke_free(&b->free, (uint32_t)(m->off / UNIT), m->units);
    if (fr == -2) VRED_ONCE("image memory block: units %llu+%u given back twice or never given (left as they are)", (unsigned long long)(m->off / UNIT), m->units);
    else if (fr) { VRED_ONCE("image memory block: free extent list not grown, %u units lost to reuse until the block empties", m->units); b->free.used -= m->units; }   // still counted free: the block can be released
    if (fr != -2) { S.runs--; S.run_bytes -= m->units * UNIT; S.req_bytes -= m->size; }   // a bad free was never a run
    if (vke_empty(&b->free)) {   // freed unless it is the memory type's only empty block
        int empties = 0; for (iblock *x = g_blocks[b->type]; x; x = x->next) empties += vke_empty(&x->free);
        if (empties > 1) {
            for (iblock **p = &g_blocks[b->type]; *p; p = &(*p)->next) if (*p == b) { *p = b->next; break; }
            gone = b; S.blocks--; S.freed++;
        }
    }
    pthread_mutex_unlock(&g_imu);
    if (gone) { vk.vkFreeMemory(V.dev, gone->dm, NULL); vke_fini(&gone->free); free(gone); }
    memset(m, 0, sizeof *m);
}

// For the stats line: the blocks, the bytes the images in them take, what rounding to units and the blocks' free space leave unused, the dedicated allocations
void vka_imem_stats(char *o, size_t n) {
    pthread_mutex_lock(&g_imu);
    snprintf(o, n, "image memory: %llu blocks (%.0f MiB, %llu freed), used %.1f MiB by %llu images (wasted %.2f MiB rounding to units, %.1f MiB free in blocks), %llu dedicated (%.0f MiB)",
             (unsigned long long)S.blocks, S.blocks * (double)BLOCK / 1048576.0, (unsigned long long)S.freed, S.req_bytes / 1048576.0, (unsigned long long)S.runs,
             (S.run_bytes - S.req_bytes) / 1048576.0, (S.blocks * (double)BLOCK - S.run_bytes) / 1048576.0, (unsigned long long)S.ded, S.ded_bytes / 1048576.0);
    if (S.fail) { size_t l = strlen(o); snprintf(o + l, n - l, "; %llu block allocations failed (those images dedicated)", (unsigned long long)S.fail); }
    pthread_mutex_unlock(&g_imu);
}
uint64_t vka_imem_allocations(void) { pthread_mutex_lock(&g_imu); uint64_t c = S.blocks + S.ded; pthread_mutex_unlock(&g_imu); return c; }
#endif
