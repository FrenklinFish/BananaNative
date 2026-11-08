// Optional implementation-dependent CPU alias of exported Vulkan memory. OPAQUE_FD does not promise mmap support: never use this path until the actual device's
// export and bidirectional alias probe passes. The driver's original map stays alive; replacing the guest alias never moves the driver's own mapping.
#pragma once
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static VkBufferUsageFlags vka_guest_usage(int bda) {
    return VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
           VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT |
           VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT | (bda ? VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT : 0);
}

typedef struct { VkBuffer buf; VkDeviceMemory mem; void *mapped; int fd; } vka_fd_memory;

static void vka_fd_destroy(vka_fns *f, VkDevice dev, vka_fd_memory *m) {
    if (m->fd >= 0) close(m->fd);
    if (m->mapped) f->vkUnmapMemory(dev, m->mem);
    if (m->buf) f->vkDestroyBuffer(dev, m->buf, NULL);
    if (m->mem) f->vkFreeMemory(dev, m->mem, NULL);
    *m = (vka_fd_memory){.fd = -1};
}

static VkResult vka_fd_create(vka_fns *f, VkPhysicalDevice pd, VkDevice dev, const VkPhysicalDeviceMemoryProperties *mp,
                              VkDeviceSize n, int bda, vka_fd_memory *out) {
    *out = (vka_fd_memory){.fd = -1};
    if (!f->vkGetMemoryFdKHR || !f->vkGetPhysicalDeviceExternalBufferProperties) return VK_ERROR_EXTENSION_NOT_PRESENT;
    VkPhysicalDeviceExternalBufferInfo qi = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_BUFFER_INFO, .usage = vka_guest_usage(bda),
                                            .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT};
    VkExternalBufferProperties ep = {VK_STRUCTURE_TYPE_EXTERNAL_BUFFER_PROPERTIES}; f->vkGetPhysicalDeviceExternalBufferProperties(pd, &qi, &ep);
    if (!(ep.externalMemoryProperties.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT)) return VK_ERROR_FEATURE_NOT_PRESENT;
    VkExternalMemoryBufferCreateInfo ei = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO, .handleTypes = qi.handleType};
    VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .pNext = &ei, .size = n, .usage = qi.usage, .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
    VkResult r = f->vkCreateBuffer(dev, &bi, NULL, &out->buf); if (r) return r;
    VkMemoryRequirements mr; f->vkGetBufferMemoryRequirements(dev, out->buf, &mr);
    uint32_t type = UINT32_MAX; const VkMemoryPropertyFlags hv = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    for (uint32_t i = 0; i < mp->memoryTypeCount; i++) if ((mr.memoryTypeBits & (1u << i)) && (mp->memoryTypes[i].propertyFlags & hv) == hv) {
        if (type == UINT32_MAX || (mp->memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT)) type = i;
    }
    if (type == UINT32_MAX) { r = VK_ERROR_FEATURE_NOT_PRESENT; goto fail; }
    VkMemoryAllocateFlagsInfo af = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO, .flags = VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT};
    VkMemoryDedicatedAllocateInfo ded = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, .pNext = bda ? &af : NULL, .buffer = out->buf};
    VkExportMemoryAllocateInfo ex = {VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, .pNext = &ded, .handleTypes = qi.handleType};
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &ex, .allocationSize = mr.size, .memoryTypeIndex = type};
    if ((r = f->vkAllocateMemory(dev, &ai, NULL, &out->mem)) || (r = f->vkBindBufferMemory(dev, out->buf, out->mem, 0)) ||
        (r = f->vkMapMemory(dev, out->mem, 0, VK_WHOLE_SIZE, 0, &out->mapped))) goto fail;
    VkMemoryGetFdInfoKHR gi = {VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR, .memory = out->mem, .handleType = qi.handleType};
    if ((r = f->vkGetMemoryFdKHR(dev, &gi, &out->fd))) goto fail;
    return VK_SUCCESS;
fail:
    vka_fd_destroy(f, dev, out); return r;
}

static int vka_fd_enabled(void) { const char *e = getenv("DM_VK_FD_ALIAS"); return !e || strcmp(e, "0"); }

static int vka_fd_probe(vka_fns *f, VkPhysicalDevice pd, VkDevice dev, const VkPhysicalDeviceMemoryProperties *mp, int bda, char *why, size_t whyn) {
    const size_t n = 65536; vka_fd_memory m; VkResult r = vka_fd_create(f, pd, dev, mp, n, bda, &m);
    if (r) { snprintf(why, whyn, "export allocation failed (%d)", r); return 0; }
    void *at = mmap(NULL, n, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0); int ok = 0;
    if (at != MAP_FAILED) {
        void *a = mmap(at, n, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, m.fd, 0);
        if (a != MAP_FAILED) {
            volatile uint32_t *original = m.mapped, *alias = a; ok = 1;
            for (size_t i = 0; i < n / 4; i++) original[i] = (uint32_t)i ^ 0x5eed1234;
            for (size_t i = 0; i < n / 4; i++) if (alias[i] != ((uint32_t)i ^ 0x5eed1234)) { ok = 0; break; }
            for (size_t i = 0; i < n / 4; i++) alias[i] = (uint32_t)i ^ 0xc0ffee00;
            if (mprotect((char *)a + 4096, 4096, PROT_READ) || mprotect((char *)a + 4096, 4096, PROT_READ | PROT_WRITE)) ok = 0;
            for (size_t i = 0; i < n / 4; i++) if (original[i] != ((uint32_t)i ^ 0xc0ffee00) || alias[i] != original[i]) { ok = 0; break; }
            snprintf(why, whyn, "%s bidirectional fixed alias and mprotect", ok ? "passed" : "failed");
        } else snprintf(why, whyn, "exported fd mmap failed (errno %d)", errno);
        munmap(at, n);
    } else snprintf(why, whyn, "alias reservation failed (errno %d)", errno);
    vka_fd_destroy(f, dev, &m); return ok;
}
