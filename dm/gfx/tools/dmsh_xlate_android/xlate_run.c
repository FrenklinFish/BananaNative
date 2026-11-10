// Test runner for libdmsh_xlate.so on the phone (adb shell; android/ONDEVICE.md): loads the library as the app's job service does and calls dmsh_xlate_main on each job,
// several jobs at once in threads of one process (the service's model), then prints the wall time, each job's time and exit code, and the peak memory of the process.
// Usage: xlate_run <libdmsh_xlate.so> <threads> <out dir> <job.json>...   (each job's output folder is <out dir>/<job file name without .json>, its log <that folder>/log.txt)
// Build: <NDK clang> --target=aarch64-linux-android33 -O2 -o xlate_run xlate_run.c -ldl   (build.sh run)
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <time.h>

typedef int (*xlate_fn)(int, char **, const char *);
static xlate_fn xlate;
static char **jobs; static int njobs; static const char *outdir;
static atomic_int next;
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }

static void *worker(void *arg) {
    (void)arg;
    for (int i; (i = atomic_fetch_add(&next, 1)) < njobs;) {
        char name[256], out[1024], log[1100];
        const char *b = strrchr(jobs[i], '/'); b = b ? b + 1 : jobs[i];
        snprintf(name, sizeof name, "%s", b); char *dot = strstr(name, ".json"); if (dot) *dot = 0;
        snprintf(out, sizeof out, "%s/%s", outdir, name); mkdir(out, 0755);
        snprintf(log, sizeof log, "%s/log.txt", out);
        char *argv[2] = {jobs[i], out};
        double t0 = now(); int rc = xlate(2, argv, log); double t1 = now();
        printf("job %s rc %d %.3f s\n", name, rc, t1 - t0); fflush(stdout);
    }
    return NULL;
}

int main(int argc, char **argv) {
    if (argc < 5) { fprintf(stderr, "usage: xlate_run <libdmsh_xlate.so> <threads> <out dir> <job.json>...\n"); return 2; }
    void *h = dlopen(argv[1], RTLD_NOW);
    if (!h || !(xlate = (xlate_fn)dlsym(h, "dmsh_xlate_main"))) { fprintf(stderr, "[RED] %s\n", dlerror()); return 1; }
    int nt = atoi(argv[2]); outdir = argv[3]; jobs = argv + 4; njobs = argc - 4; mkdir(outdir, 0755);
    if (nt < 1) nt = 1;
    pthread_t th[64]; if (nt > 64) nt = 64;
    double t0 = now();
    for (int i = 0; i < nt; i++) pthread_create(&th[i], NULL, worker, NULL);
    for (int i = 0; i < nt; i++) pthread_join(th[i], NULL);
    struct rusage ru; getrusage(RUSAGE_SELF, &ru);
    printf("jobs %d threads %d wall %.3f s user %.3f s sys %.3f s peak RSS %ld KiB\n", njobs, nt, now() - t0,
           ru.ru_utime.tv_sec + ru.ru_utime.tv_usec * 1e-6, ru.ru_stime.tv_sec + ru.ru_stime.tv_usec * 1e-6, ru.ru_maxrss);
    return 0;
}
