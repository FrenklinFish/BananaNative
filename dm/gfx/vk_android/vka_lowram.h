// A profile's low-RAM keys (dm/games/README.md low_ram_place_part_mb, low_ram_img_cache_max_mb, low_ram_app_pool_mb): defaults the Vulkan replay takes only on a device of 12 GB or less, for a
// profile that sets them. One predicate for both (vka_tex.c vka_low_ram): the profile sets one of the keys and MemTotal is at most 12 GiB, the class vka_texlimit.h's
// reserve uses. DM_VK_LOW_RAM=0 turns them off and =1 applies them on any device (an A/B; a profile without the keys still gets nothing). They sit beside the texture cache's
// limit (vka_texlimit.h), not in it: the image cache's key is a ceiling on the limit that rule measures, applied after it (vklr_ceiling), so the rule's RAM, budget and memory
// pressure parts still act below it. DM_VK_IMG_LIMIT_MB and DM_VK_PLACE_PART_MB still replace what they replace. Free of Vulkan: the Mac's unit test
// (dm/host/tests/t_android_lowram.c) checks it
#ifndef VKA_LOWRAM_H
#define VKA_LOWRAM_H
#include <stdint.h>
#include "vka_texlimit.h"

#define VKLR_MEM_TOTAL_MAX (12 * 1024 * VKTL_MIB)   // the 12 GB class of vktl_reserve

// Whether the profile's low-RAM keys apply: has = the profile sets one of them; mem_total = MemTotal in bytes (0 = not known: they do not apply);
// force = DM_VK_LOW_RAM (-1 unset, 0 never, 1 on any device)
static inline int vklr_applies(int has, uint64_t mem_total, int force) {
    if (!has || !force) return 0;
    if (force > 0) return 1;
    return mem_total && mem_total <= VKLR_MEM_TOTAL_MAX;
}
// The image cache's limit under a ceiling (0 = none): the smaller of the two
static inline uint64_t vklr_ceiling(uint64_t limit, uint64_t ceiling) { return ceiling && limit > ceiling ? ceiling : limit; }

int vka_low_ram(void);                 // the predicate, decided once (vka_tex.c)
int vka_low_ram_place_part_mb(void);   // the profile's low_ram_place_part_mb where the keys apply, else 0
int vka_low_ram_app_pool_mb(void);     // the profile's low_ram_app_pool_mb where the keys apply, else 0 (hle_svc.c app_pool: read at the SDK's start, before Vulkan)
#endif
