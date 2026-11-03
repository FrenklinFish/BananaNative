// The readiness check's Vulkan facts (vk_android.h nxhost_vk_readiness): what the device and the driver the game would run on report, for the launcher to compare with
// the host's own needs and the game profile's req rows (android/app Readiness.java; android/README.md "Readiness check"). Its own instance, made and destroyed here, on the
// same driver the replay loads (DM_VK_DRIVER through vka_driver_open, else the system's libvulkan.so) and the same physical device (the first, as vka_dev.c takes); no
// device is created, so it costs a few milliseconds and leaves nothing behind for the replay's own vka_init. Nothing here decides: the facts are "key=value" lines
// whose keys the profiles' readiness.tsv rows name (dm/games/README.md "Readiness rows"), and a key the driver does not report is simply absent (it reads as missing)
#if defined(__ANDROID__)
#include "vka.h"
#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct { char *p; int n, at; } outbuf;
static void put(outbuf *o, const char *fmt, ...) {
    if (o->at >= o->n - 1) return;
    va_list ap; va_start(ap, fmt);
    int w = vsnprintf(o->p + o->at, (size_t)(o->n - o->at), fmt, ap);
    va_end(ap);
    if (w > 0) o->at = o->at + w < o->n - 1 ? o->at + w : o->n - 1;
}
static void put_str(outbuf *o, const char *key, const char *s) {   // a driver string on one line: no newline or '=' trouble for the parser (it splits at the first '=')
    char c[512]; size_t i = 0;
    for (; s && s[i] && i + 1 < sizeof c; i++) c[i] = s[i] == '\n' || s[i] == '\r' ? ' ' : s[i];
    c[i] = 0; put(o, "%s=%s\n", key, c);
}
static void put_ver(outbuf *o, const char *key, uint32_t v) { put(o, "%s=%u.%u.%u\n", key, VK_API_VERSION_MAJOR(v), VK_API_VERSION_MINOR(v), VK_API_VERSION_PATCH(v)); }

// The extensions a requirement or the report may name; each one present is "ext.<name>=1"
static const char *const k_exts[] = {
    VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_KHR_MAP_MEMORY_2_EXTENSION_NAME, VK_EXT_MAP_MEMORY_PLACED_EXTENSION_NAME, VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME,
    VK_EXT_VERTEX_INPUT_DYNAMIC_STATE_EXTENSION_NAME, VK_KHR_ROBUSTNESS_2_EXTENSION_NAME, VK_EXT_ROBUSTNESS_2_EXTENSION_NAME, VK_KHR_INDEX_TYPE_UINT8_EXTENSION_NAME,
    VK_EXT_INDEX_TYPE_UINT8_EXTENSION_NAME, VK_KHR_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME, VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME,
    VK_EXT_GRAPHICS_PIPELINE_LIBRARY_EXTENSION_NAME, VK_EXT_MESH_SHADER_EXTENSION_NAME, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME, VK_EXT_TRANSFORM_FEEDBACK_EXTENSION_NAME,
    VK_EXT_CUSTOM_BORDER_COLOR_EXTENSION_NAME, VK_EXT_DEPTH_CLIP_ENABLE_EXTENSION_NAME, VK_EXT_PROVOKING_VERTEX_EXTENSION_NAME, "VK_QCOM_queue_perf_hint",
};

// Formats whose sparse residency decides how large sparse texture arrays are made (vka_tex.c: a format without it gets a dense image, capped by DM_VK_SPARSE_DENSE_MB)
static const struct { const char *name; VkFormat f; } k_sparse_fmt[] = {
    {"rgba8", VK_FORMAT_R8G8B8A8_UNORM}, {"rgba16f", VK_FORMAT_R16G16B16A16_SFLOAT}, {"bc1", VK_FORMAT_BC1_RGBA_UNORM_BLOCK}, {"bc6h", VK_FORMAT_BC6H_UFLOAT_BLOCK},
    {"bc7", VK_FORMAT_BC7_UNORM_BLOCK}, {"astc4x4", VK_FORMAT_ASTC_4x4_UNORM_BLOCK}, {"astc8x8", VK_FORMAT_ASTC_8x8_UNORM_BLOCK},
};

__attribute__((visibility("default"))) int nxhost_vk_readiness(char *buf, int n) {
    if (!buf || n < 64) return -1;
    outbuf o = {buf, n, 0}; buf[0] = 0;
    void *lib = vka_driver_open();   // the custom driver when DM_VK_DRIVER names one (opened once per process: the replay gets the same library)
    if (!lib) lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    PFN_vkGetInstanceProcAddr gipa = lib ? (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr") : NULL;
    if (!gipa) { put(&o, "error=no Vulkan library (libvulkan.so)\n"); return 1; }
    put_str(&o, "driver.custom", vka_driver_tag() ? vka_driver_tag() : "");
#define G(f) PFN_##f f = (PFN_##f)gipa(NULL, #f);
    G(vkCreateInstance) G(vkEnumerateInstanceVersion)
#undef G
    uint32_t api = VK_API_VERSION_1_0; if (vkEnumerateInstanceVersion) vkEnumerateInstanceVersion(&api);
    put_ver(&o, "vk.instance", api);
    if (!vkCreateInstance) { put(&o, "error=no vkCreateInstance\n"); return 1; }
    VkApplicationInfo ai = {VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "nxnative readiness", .pEngineName = "nxnative",
                            .apiVersion = api >= VK_API_VERSION_1_3 ? VK_API_VERSION_1_3 : api >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0};
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &ai};
    VkInstance inst; VkResult r = vkCreateInstance(&ici, NULL, &inst);
    if (r) { put(&o, "error=vkCreateInstance returned %d\n", r); return 1; }
#define I(f) PFN_##f f = (PFN_##f)gipa(inst, #f);
    I(vkDestroyInstance) I(vkEnumeratePhysicalDevices) I(vkGetPhysicalDeviceProperties) I(vkGetPhysicalDeviceProperties2) I(vkGetPhysicalDeviceFeatures2)
    I(vkGetPhysicalDeviceMemoryProperties) I(vkGetPhysicalDeviceQueueFamilyProperties) I(vkEnumerateDeviceExtensionProperties)
    I(vkGetPhysicalDeviceImageFormatProperties) I(vkGetPhysicalDeviceSparseImageFormatProperties) I(vkGetPhysicalDeviceFormatProperties)
#undef I
    uint32_t npd = 1; VkPhysicalDevice pd = VK_NULL_HANDLE;
    if (!vkEnumeratePhysicalDevices || vkEnumeratePhysicalDevices(inst, &npd, &pd) < 0 || !npd || !pd) {
        put(&o, "error=no Vulkan physical device\n"); if (vkDestroyInstance) vkDestroyInstance(inst, NULL); return 1; }
    VkPhysicalDeviceProperties p; vkGetPhysicalDeviceProperties(pd, &p);
    put_str(&o, "gpu.name", p.deviceName);
    put(&o, "gpu.vendor=%#x\ngpu.device=%#x\ndriver.version=%#x\n", p.vendorID, p.deviceID, p.driverVersion);
    put_ver(&o, "vk.api", p.apiVersion);
    const int v12 = p.apiVersion >= VK_API_VERSION_1_2, v13 = p.apiVersion >= VK_API_VERSION_1_3;

    // Extensions
    uint32_t nde = 0; vkEnumerateDeviceExtensionProperties(pd, NULL, &nde, NULL);
    VkExtensionProperties *de = calloc(nde ? nde : 1, sizeof *de); vkEnumerateDeviceExtensionProperties(pd, NULL, &nde, de);
    int mesh = 0, placed = 0;
    for (size_t k = 0; k < sizeof k_exts / sizeof *k_exts; k++)
        for (uint32_t i = 0; i < nde; i++) if (!strcmp(de[i].extensionName, k_exts[k])) {
            put(&o, "ext.%s=1\n", k_exts[k]);
            if (!strcmp(k_exts[k], VK_EXT_MESH_SHADER_EXTENSION_NAME)) mesh = 1;
            if (!strcmp(k_exts[k], VK_EXT_MAP_MEMORY_PLACED_EXTENSION_NAME)) placed = 1;
            break;
        }
    put(&o, "ext.count=%u\n", nde);
    free(de);

    // Driver identity (1.2 core) and the 1.1 / 1.3 properties the requirements may name
    if (v12 && vkGetPhysicalDeviceProperties2) {
        VkPhysicalDeviceVulkan13Properties p13 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_PROPERTIES};
        VkPhysicalDeviceVulkan11Properties p11 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_PROPERTIES, .pNext = v13 ? &p13 : NULL};
        VkPhysicalDeviceMeshShaderPropertiesEXT pms = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_PROPERTIES_EXT, .pNext = &p11};
        VkPhysicalDeviceDriverProperties pdr = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES, .pNext = mesh ? (void *)&pms : (void *)&p11};
        VkPhysicalDeviceProperties2 p2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &pdr};
        vkGetPhysicalDeviceProperties2(pd, &p2);
        put(&o, "driver.id=%d\n", (int)pdr.driverID);
        put_str(&o, "driver.name", pdr.driverName); put_str(&o, "driver.info", pdr.driverInfo);
        put(&o, "driver.conformance=%u.%u.%u.%u\n", pdr.conformanceVersion.major, pdr.conformanceVersion.minor, pdr.conformanceVersion.subminor, pdr.conformanceVersion.patch);
        put(&o, "limit.subgroupSize=%u\nlimit.subgroupStages=%#x\n", p11.subgroupSize, p11.subgroupSupportedStages);
        if (v13) put(&o, "limit.minSubgroupSize=%u\nlimit.maxSubgroupSize=%u\n", p13.minSubgroupSize, p13.maxSubgroupSize);
        if (mesh) put(&o, "limit.maxMeshSharedMemorySize=%u\nlimit.maxTaskSharedMemorySize=%u\n", pms.maxMeshSharedMemorySize, pms.maxTaskSharedMemorySize);
    }
    const VkPhysicalDeviceLimits *L = &p.limits;
    put(&o, "limit.maxComputeSharedMemorySize=%u\nlimit.maxImageDimension2D=%u\nlimit.maxImageArrayLayers=%u\nlimit.maxStorageBufferRange=%u\n"
            "limit.maxPerStageDescriptorSampledImages=%u\nlimit.maxPerStageDescriptorStorageImages=%u\nlimit.maxBoundDescriptorSets=%u\nlimit.maxMemoryAllocationCount=%u\n"
            "limit.sparseAddressSpaceMiB=%llu\n",
        L->maxComputeSharedMemorySize, L->maxImageDimension2D, L->maxImageArrayLayers, L->maxStorageBufferRange, L->maxPerStageDescriptorSampledImages,
        L->maxPerStageDescriptorStorageImages, L->maxBoundDescriptorSets, L->maxMemoryAllocationCount, (unsigned long long)(L->sparseAddressSpaceSize >> 20));

    // Features: core 1.0 by name, then the 1.2 / 1.3 ones the replay requires or uses, and the extensions' own
    VkPhysicalDeviceMeshShaderFeaturesEXT fms = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT};
    VkPhysicalDeviceMapMemoryPlacedFeaturesEXT fmp = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT};
    VkPhysicalDeviceVulkan13Features f13 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    VkPhysicalDeviceVulkan12Features f12 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceVulkan11Features f11 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES};
    VkPhysicalDeviceFeatures2 f2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    void *chain = NULL;   // only the structures this device's version and extensions define
#define LINK(on, st) if (on) { (st).pNext = chain; chain = &(st); }
    LINK(mesh, fms) LINK(placed, fmp) LINK(v13, f13) LINK(v12, f12) LINK(v12, f11)
#undef LINK
    f2.pNext = chain;
    if (vkGetPhysicalDeviceFeatures2) vkGetPhysicalDeviceFeatures2(pd, &f2);
    const VkPhysicalDeviceFeatures *F = &f2.features;
#define B(name, v) put(&o, "feature." name "=%d\n", (v) ? 1 : 0);
    B("geometryShader", F->geometryShader) B("tessellationShader", F->tessellationShader) B("textureCompressionBC", F->textureCompressionBC)
    B("textureCompressionASTC_LDR", F->textureCompressionASTC_LDR) B("textureCompressionETC2", F->textureCompressionETC2) B("shaderInt64", F->shaderInt64)
    B("shaderInt16", F->shaderInt16) B("shaderFloat64", F->shaderFloat64) B("shaderStorageImageReadWithoutFormat", F->shaderStorageImageReadWithoutFormat)
    B("shaderStorageImageWriteWithoutFormat", F->shaderStorageImageWriteWithoutFormat) B("shaderStorageImageExtendedFormats", F->shaderStorageImageExtendedFormats)
    B("shaderImageGatherExtended", F->shaderImageGatherExtended) B("dualSrcBlend", F->dualSrcBlend) B("logicOp", F->logicOp) B("depthClamp", F->depthClamp)
    B("fillModeNonSolid", F->fillModeNonSolid) B("multiViewport", F->multiViewport) B("imageCubeArray", F->imageCubeArray) B("independentBlend", F->independentBlend)
    B("sparseBinding", F->sparseBinding) B("sparseResidencyBuffer", F->sparseResidencyBuffer) B("sparseResidencyImage2D", F->sparseResidencyImage2D)
    B("sparseResidencyImage3D", F->sparseResidencyImage3D) B("sparseResidencyAliased", F->sparseResidencyAliased)
    if (v12) {
        B("shaderDrawParameters", f11.shaderDrawParameters) B("storageBuffer16BitAccess", f11.storageBuffer16BitAccess)
        B("timelineSemaphore", f12.timelineSemaphore) B("bufferDeviceAddress", f12.bufferDeviceAddress) B("runtimeDescriptorArray", f12.runtimeDescriptorArray)
        B("descriptorBindingPartiallyBound", f12.descriptorBindingPartiallyBound) B("descriptorBindingSampledImageUpdateAfterBind", f12.descriptorBindingSampledImageUpdateAfterBind)
        B("shaderFloat16", f12.shaderFloat16) B("storageBuffer8BitAccess", f12.storageBuffer8BitAccess) B("scalarBlockLayout", f12.scalarBlockLayout)
        B("drawIndirectCount", f12.drawIndirectCount)
    }
    if (v13) { B("dynamicRendering", f13.dynamicRendering) B("synchronization2", f13.synchronization2) B("subgroupSizeControl", f13.subgroupSizeControl) }
    if (placed) B("memoryMapPlaced", fmp.memoryMapPlaced)
    if (mesh) { B("meshShader", fms.meshShader) B("taskShader", fms.taskShader) }
#undef B

    // Queues: the replay's one queue is the first family with graphics and compute (vka_dev.c); sparse binds go to the same queue
    uint32_t nq = 0; vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, NULL);
    VkQueueFamilyProperties *qp = calloc(nq ? nq : 1, sizeof *qp); vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qp);
    uint32_t qf = UINT32_MAX; for (uint32_t i = 0; i < nq && qf == UINT32_MAX; i++) if ((qp[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) qf = i;
    int qsparse = qf < nq && (qp[qf].queueFlags & VK_QUEUE_SPARSE_BINDING_BIT);
    put(&o, "queue.graphicsCompute=%d\nqueue.sparseBinding=%d\n", qf != UINT32_MAX, qsparse);
    free(qp);
    // Sparse residency images as the replay takes them (vka_dev.c V.sparse_res): binding, 2D residency, the standard block shape, strict non-resident reads, on the queue
    const VkPhysicalDeviceSparseProperties *sp = &p.sparseProperties;
    put(&o, "sparse.standard2DBlockShape=%d\nsparse.nonResidentStrict=%d\n", sp->residencyStandard2DBlockShape ? 1 : 0, sp->residencyNonResidentStrict ? 1 : 0);
    int sres = F->sparseBinding && F->sparseResidencyImage2D && sp->residencyStandard2DBlockShape && sp->residencyNonResidentStrict && qsparse;
    put(&o, "sparse.residencyImages=%d\n", sres ? 1 : 0);
    // Per format: can a sparse residency 2D array image be made (sampled, uploaded to), as vka_tex.c asks before it makes one
    for (size_t k = 0; k < sizeof k_sparse_fmt / sizeof *k_sparse_fmt; k++) {
        VkFormat f = k_sparse_fmt[k].f; VkFormatProperties fp = {0};
        if (vkGetPhysicalDeviceFormatProperties) vkGetPhysicalDeviceFormatProperties(pd, f, &fp);
        put(&o, "format.%s=%d\n", k_sparse_fmt[k].name, (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) && (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_TRANSFER_DST_BIT) ? 1 : 0);
        uint32_t ns = 0;
        if (sres && vkGetPhysicalDeviceImageFormatProperties && vkGetPhysicalDeviceSparseImageFormatProperties) {
            VkImageUsageFlags u = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT; VkImageFormatProperties ip;
            if (!vkGetPhysicalDeviceImageFormatProperties(pd, f, VK_IMAGE_TYPE_2D, VK_IMAGE_TILING_OPTIMAL, u, VK_IMAGE_CREATE_SPARSE_BINDING_BIT | VK_IMAGE_CREATE_SPARSE_RESIDENCY_BIT, &ip))
                vkGetPhysicalDeviceSparseImageFormatProperties(pd, f, VK_IMAGE_TYPE_2D, VK_SAMPLE_COUNT_1_BIT, u, VK_IMAGE_TILING_OPTIMAL, &ns, NULL);
        }
        put(&o, "sparse.format.%s=%d\n", k_sparse_fmt[k].name, ns ? 1 : 0);
    }
    // Memory: the device-local heaps (on a phone, the system's RAM the GPU shares)
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    uint64_t local = 0; for (uint32_t i = 0; i < mp.memoryHeapCount; i++) if (mp.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) local += mp.memoryHeaps[i].size;
    put(&o, "memory.deviceLocalMiB=%llu\n", (unsigned long long)(local >> 20));
    vkDestroyInstance(inst, NULL);
    return 0;
}
#endif
