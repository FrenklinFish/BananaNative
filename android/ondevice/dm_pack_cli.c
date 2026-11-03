// Test runner for libnxpack.so in `adb shell` (android/ONDEVICE.md): the app's entry (dm_pack_init + dm_pack_run) from a plain process, so a tool's run on the phone can be
// timed and compared without the app. Prints the tool's output, then one line: exit code, wall time, CPU time, peak RSS.
// Usage: dm_pack_cli <python home> <tool.py> [args...]   (the process environment is the tool's: DM_GAME=..., DM_REWRITE_J=1, J=1 ...)
// DM_PACK_INTERRUPT=<seconds>: dm_pack_interrupt from another thread after that long (the app's Pause), to test the stop (exit 130)
#include "dm_pack.h"
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/resource.h>
#include <time.h>

static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }

static void *interrupter(void *arg) {
    usleep((useconds_t)(atof(arg) * 1e6));
    fprintf(stderr, "dm_pack_cli: interrupt → %d\n", dm_pack_interrupt());
    return NULL;
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: dm_pack_cli <python home> <tool.py> [args...]\n"); return 2; }
    double t0 = now();
    if (dm_pack_init(argv[1])) return 2;
    double t1 = now();
    const char *ia = getenv("DM_PACK_INTERRUPT");
    pthread_t it;
    if (ia && *ia) pthread_create(&it, NULL, interrupter, (void *)ia);
    int rc = dm_pack_run(argv[2], argc - 3, argv + 3, NULL, "/dev/stdout");   // Python on Android sends its own sys.stdout to logcat: the tool writes to this terminal instead
    double t2 = now();
    struct rusage u; getrusage(RUSAGE_SELF, &u);
    fflush(stdout);
    fprintf(stderr, "dm_pack_cli: exit %d, start %.2f s, run %.2f s wall, %.2f s user, %.2f s system, peak RSS %ld MB\n", rc, t1 - t0, t2 - t1,
            u.ru_utime.tv_sec + u.ru_utime.tv_usec * 1e-6, u.ru_stime.tv_sec + u.ru_stime.tv_usec * 1e-6, u.ru_maxrss / 1024);
    return rc;
}
