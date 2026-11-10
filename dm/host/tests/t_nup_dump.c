// Manual check of the host's NUP reader against a real dump (not in `make run`: it needs a dump and a .nup of it):
//   make -C dm/host/tests GAME=<title ID> build/t_nup_dump && dm/host/tests/build/t_nup_dump <file>.nup:<selector> <RomFS folder of that package>
// Mounts the package as rom: through hle_fs.c (as the game does) and reads every file of the folder through OpenFile / ReadFile in 1 MiB pieces,
// comparing each with the folder's copy; then checks the image holds no file the folder lacks. Prints the time spent reading raw and compressed
// files and the most the zstd cache held. Exit status 0 when every file matches.
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include "../hle_fs.c"
struct dm_flags g_flag; uint64_t g_t0_ns; uint64_t now_ns(void) { return 0; }   // stubs, as in t_fs.c
void dm_guest_write_prepare(void *a, size_t n) { (void)a; (void)n; }

static uint64_t nfiles, nbytes, nbad, nraw_ns, nz_ns, nz_files, nz_bytes, peak;
static uint8_t g_a[1 << 20], g_b[1 << 20];
static uint64_t ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + t.tv_nsec; }

static void check_file(const char *host, const char *guest) {
    FILE *f = fopen(host, "rb"); uint64_t h; int64_t sz = -1;
    if (!f) { printf("  cannot open %s\n", host); nbad++; return; }
    uint64_t t0 = ns();
    if (h_fs_OpenFile(&h, guest, MODE_READ) != RES_OK || h_fs_GetFileSize(&sz, h) != RES_OK) { printf("  ✗ %s: does not open in the .nup\n", guest); nbad++; fclose(f); return; }
    const img_ent *x = &g_mnt[IMG_M(FH_IN(h))].img->e[IMG_K(FH_IN(h))]; int z = !!(x->flags & IMG_ENT_ZSTD);
    int64_t off = 0; int ok = 1;
    for (;;) {
        size_t want = fread(g_a, 1, sizeof g_a, f), got = 0;
        if (h_fs_ReadFile(&got, h, off, g_b, sizeof g_b) != RES_OK || got != want || memcmp(g_a, g_b, want)) { ok = 0; break; }
        off += (int64_t)got; if (!want) break;
    }
    nup_aux *a = g_mnt[IMG_M(FH_IN(h))].img->aux; if (a->held > peak) peak = a->held;
    h_fs_CloseFile(h); fclose(f);
    uint64_t dt = ns() - t0;
    if (z) { nz_ns += dt; nz_files++; nz_bytes += (uint64_t)off; } else nraw_ns += dt;
    if (!ok || off != sz) { printf("  ✗ %s: differs (at %lld of %lld)\n", guest, (long long)off, (long long)sz); nbad++; }
    nfiles++; nbytes += (uint64_t)off;
}
static void walk(const char *host, const char *guest) {
    DIR *d = opendir(host); struct dirent *e;
    if (!d) { printf("  cannot list %s\n", host); nbad++; return; }
    while ((e = readdir(d))) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..") || !strcmp(e->d_name, ".listing")) continue;
        char hp[2048], gp[2048]; snprintf(hp, sizeof hp, "%s/%s", host, e->d_name); snprintf(gp, sizeof gp, "%s/%s", guest, e->d_name);
        struct stat st; if (stat(hp, &st)) continue;
        if (S_ISDIR(st.st_mode)) walk(hp, gp); else check_file(hp, gp);
    }
    closedir(d);
}
int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "usage: %s <file>.nup:<selector> <RomFS folder>\n", argv[0]); return 2; }
    setenv("DM_ROMFS", argv[1], 1);
    uint64_t t0 = ns();
    if (h_fs_MountRom("rom", NULL, 0) != RES_OK || !g_mnt[0].img) { printf("mount failed\n"); return 1; }
    printf("mount: %.0f ms, %llu entries\n", (ns() - t0) / 1e6, (unsigned long long)g_mnt[0].img->n);
    t0 = ns(); walk(argv[2], "rom:");
    uint64_t img_files = 0; for (uint64_t k = 0; k < g_mnt[0].img->n; k++) img_files += g_mnt[0].img->e[k].type;
    printf("%llu files, %.2f GiB in %.1f s; raw files %.1f s, zstd files %llu (%.2f GiB) %.1f s = %.0f MiB/s; cache peak %.0f MiB\n",
           (unsigned long long)nfiles, nbytes / 1073741824.0, (ns() - t0) / 1e9, nraw_ns / 1e9, (unsigned long long)nz_files, nz_bytes / 1073741824.0, nz_ns / 1e9,
           nz_ns ? nz_bytes / 1048576.0 / (nz_ns / 1e9) : 0, peak / 1048576.0);
    if (img_files != nfiles) { printf("  ✗ the .nup package has %llu files, the folder %llu\n", (unsigned long long)img_files, (unsigned long long)nfiles); nbad++; }
    printf(nbad ? "== %llu problems ==\n" : "== every file matches ==\n", (unsigned long long)nbad);
    return nbad != 0;
}
