// Test runner for libSPIRV-Tools-shared.so on the phone (adb shell; android/ONDEVICE.md): loads the validator as the app's job service does (vk_android_build.py SPIRV_VAL=<.so>,
// spvValidateWithOptions in the Vulkan 1.3 environment, the check spirv-val --target-env vulkan1.3 makes) and validates SPIR-V files from several threads of one process,
// then prints the wall time, the bytes checked, the failures and the peak memory of the process.
// Usage: spv_val_run <libSPIRV-Tools-shared.so> <threads> <file.spv>...   or   spv_val_run <lib> <threads> @<list file>   (one path a line)
// Build: <NDK clang> --target=aarch64-linux-android33 -O2 -o spv_val_run spv_val_run.c -ldl   (build.sh run)
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <time.h>

typedef struct { size_t line, column, index; char *error; bool isTextSource; } diag_t;
typedef struct { const uint32_t *code; size_t wordCount; } binary_t;
static bool (*parse_env)(const char *, int *);
static void *(*ctx_create)(int);
static void *(*opt_create)(void);
static int (*validate)(void *, void *, const binary_t *, diag_t **);
static void (*diag_destroy)(diag_t *);
static int env; static char **files; static int nfiles;
static atomic_int next, bad; static atomic_llong bytes;
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }

static void *worker(void *arg) {
    (void)arg;
    void *ctx = ctx_create(env), *opt = opt_create();
    for (int i; (i = atomic_fetch_add(&next, 1)) < nfiles;) {
        FILE *f = fopen(files[i], "rb"); if (!f) { atomic_fetch_add(&bad, 1); continue; }
        fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
        uint32_t *w = malloc(n + 4); size_t got = fread(w, 1, n, f); fclose(f);
        binary_t b = {w, got / 4}; diag_t *d = NULL;
        if (validate(ctx, opt, &b, &d) != 0) { atomic_fetch_add(&bad, 1); if (atomic_load(&bad) <= 5) printf("[RED] %s: %s\n", files[i], d && d->error ? d->error : "invalid"); }
        if (d) diag_destroy(d);
        atomic_fetch_add(&bytes, (long long)got); free(w);
    }
    return NULL;
}

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: spv_val_run <libSPIRV-Tools-shared.so> <threads> <file.spv>... | @<list>\n"); return 2; }
    void *h = dlopen(argv[1], RTLD_NOW);
    if (!h) { fprintf(stderr, "[RED] %s\n", dlerror()); return 1; }
    parse_env = dlsym(h, "spvParseTargetEnv"); ctx_create = dlsym(h, "spvContextCreate"); opt_create = dlsym(h, "spvValidatorOptionsCreate");
    validate = dlsym(h, "spvValidateWithOptions"); diag_destroy = dlsym(h, "spvDiagnosticDestroy");
    if (!parse_env || !ctx_create || !opt_create || !validate || !diag_destroy || !parse_env("vulkan1.3", &env)) { fprintf(stderr, "[RED] SPIRV-Tools entry points\n"); return 1; }
    if (argv[3][0] == '@') {   // a list file
        FILE *f = fopen(argv[3] + 1, "r"); if (!f) { perror(argv[3] + 1); return 1; }
        int cap = 1024; files = malloc(cap * sizeof *files); char line[4096];
        while (fgets(line, sizeof line, f)) {
            line[strcspn(line, "\r\n")] = 0; if (!*line) continue;
            if (nfiles == cap) files = realloc(files, (cap *= 2) * sizeof *files);
            files[nfiles++] = strdup(line);
        }
        fclose(f);
    } else { files = argv + 3; nfiles = argc - 3; }
    int nt = atoi(argv[2]); if (nt < 1) nt = 1; if (nt > 64) nt = 64;
    pthread_t th[64]; double t0 = now();
    for (int i = 0; i < nt; i++) pthread_create(&th[i], NULL, worker, NULL);
    for (int i = 0; i < nt; i++) pthread_join(th[i], NULL);
    struct rusage ru; getrusage(RUSAGE_SELF, &ru);
    printf("files %d threads %d invalid %d bytes %lld wall %.3f s user %.3f s sys %.3f s peak RSS %ld KiB\n", nfiles, nt, atomic_load(&bad), (long long)atomic_load(&bytes), now() - t0,
           ru.ru_utime.tv_sec + ru.ru_utime.tv_usec * 1e-6, ru.ru_stime.tv_sec + ru.ru_stime.tv_usec * 1e-6, ru.ru_maxrss);
    return 0;
}
