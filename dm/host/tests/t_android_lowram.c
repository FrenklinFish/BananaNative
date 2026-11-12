// A profile's low-RAM keys (dm/gfx/vk_android/vka_lowram.h): they apply only for a profile that sets them and a MemTotal of 12 GiB or less (the texture cache's 12 GB
// class, vka_texlimit.h), not when MemTotal is unknown; DM_VK_LOW_RAM=0 turns them off and =1 applies them on any device, never for a profile without them; the image
// cache's ceiling only lowers the measured limit
#include <stdio.h>
#include "../../gfx/vk_android/vka_lowram.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
#define MIB VKTL_MIB
#define GIB (1024 * MIB)

int main(void) {
    // The threshold is the texture cache's: the reserve is an eighth exactly where the keys apply
    CHECK(VKLR_MEM_TOTAL_MAX == 12 * GIB, "threshold %llu", (unsigned long long)VKLR_MEM_TOTAL_MAX);
    CHECK(vktl_reserve(VKLR_MEM_TOTAL_MAX) == VKLR_MEM_TOTAL_MAX / 8 && vktl_reserve(VKLR_MEM_TOTAL_MAX + 4096) != (VKLR_MEM_TOTAL_MAX + 4096) / 8, "same class as vktl_reserve");
    // Unset DM_VK_LOW_RAM: by MemTotal
    CHECK(vklr_applies(1, 11265 * MIB, -1), "an 11 GiB device (AYN Thor)");
    CHECK(vklr_applies(1, 12 * GIB, -1), "exactly 12 GiB");
    CHECK(!vklr_applies(1, 12 * GIB + 4096, -1), "just over 12 GiB");
    CHECK(!vklr_applies(1, 15433 * MIB, -1), "a 16 GB device (OnePlus Pad 3)");
    CHECK(!vklr_applies(1, 0, -1), "MemTotal not known");
    CHECK(!vklr_applies(0, 8 * GIB, -1), "a profile without the keys");
    // DM_VK_LOW_RAM
    CHECK(!vklr_applies(1, 8 * GIB, 0), "=0 on a small device");
    CHECK(vklr_applies(1, 15433 * MIB, 1), "=1 on a 16 GB device");
    CHECK(vklr_applies(1, 0, 1), "=1 with MemTotal not known");
    CHECK(!vklr_applies(0, 8 * GIB, 1), "=1 for a profile without the keys");
    // A profile with only low_ram_app_pool_mb has the keys (vka_tex.c vka_low_ram: has counts all three); the same predicate decides it (hle_svc.c app_pool)
    CHECK(vklr_applies(1, 11265 * MIB, -1) && !vklr_applies(1, 15433 * MIB, -1) && vklr_applies(1, 15433 * MIB, 1), "the application pool key on 11 GiB, 16 GB, 16 GB with =1");
    // The ceiling
    CHECK(vklr_ceiling(4300 * MIB, 1024 * MIB) == 1024 * MIB, "a measured limit above the ceiling");
    CHECK(vklr_ceiling(700 * MIB, 1024 * MIB) == 700 * MIB, "a measured limit below it (memory pressure) stays");
    CHECK(vklr_ceiling(4300 * MIB, 0) == 4300 * MIB, "no ceiling");
    // With the measured rule: a Thor-like 11 GiB device, 4 GiB available, 1.4 GiB of images; under pressure the rule goes below the ceiling
    vktl_in in = {.mem_total = 11265 * MIB, .mem_avail = 4 * GIB, .img_bytes = 1400 * MIB, .psi_some10 = -1};
    CHECK(vklr_ceiling(vktl_limit(&in), 1024 * MIB) == 1024 * MIB, "the ceiling holds over the measured %llu MiB", (unsigned long long)(vktl_limit(&in) >> 20));
    in.img_bytes = 900 * MIB; in.psi_some10 = 6.0;
    CHECK(vklr_ceiling(vktl_limit(&in), 1024 * MIB) == 900 * MIB, "pressure holds the cache at its images, under the ceiling");
    if (!fails) printf("t_android_lowram: ok\n");
    return fails != 0;
}
