// Optional co-op host seam: real portable module, no server or game assets.
// Build with NX_COOP_VENDOR=1 and the pinned module's ldn/vnet/mpnet/plat/auth/disc
// sources. Each mode runs in a fresh process because the node is created once.
#include "../hle.h"
// Keep plain SDK overloads in this seam without registering unrelated services.
#pragma push_macro("HLE_FORCE")
#undef HLE_FORCE
#define HLE_FORCE(mangled, why, ret, cname, ...) static ret plain_##cname(__VA_ARGS__)
#include "../hle_offline.c"
#pragma pop_macro("HLE_FORCE")
#include <assert.h>

// Close a real virtual socket at the exact validation-to-poll boundary.
static int close_before_poll = -1;
static int test_coop_poll(coop_node *n, coop_pollfd *p, uint64_t count, int ms) {
    if (close_before_poll >= 0) {
        int fd = close_before_poll; close_before_poll = -1;
        assert(coop_vnet_close(n, fd) == 0);
    }
    return coop_vnet_poll(n, p, count, ms);
}
#define coop_vnet_poll test_coop_poll
#include "../hle_net.c"
#undef coop_vnet_poll
#include "../hle_coop.c"

module_t *g_mod;
int g_nmod;
static uint64_t current_binding = 1;
static int signals, checks, failures;
void *guest_sym(const char *name) { (void)name; return NULL; }
void dm_guest_sleep_ns(int64_t ns) { struct timespec t = {ns / 1000000000, ns % 1000000000}; nanosleep(&t, NULL); }
uint64_t hle_system_event_binding(void *g) { return g ? current_binding : 0; }
int hle_signal_system_event_binding(uint64_t binding) { if (binding != current_binding) return 0; signals++; return 1; }
#define CHECK(c, text) do { checks++; if (!(c)) { failures++; fprintf(stderr, "FAIL: %s\n", text); } } while (0)
static void bit(uint64_t *s, int fd) { s[fd >> 6] |= 1ull << (fd & 63); }
static int has(const uint64_t *s, int fd) { return !!(s[fd >> 6] & (1ull << (fd & 63))); }

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "enabled";
    int enabled = !strcmp(mode, "enabled") || !strcmp(mode, "select-race");
    unsetenv("DM_UID"); unsetenv("DM_COOP_JOIN"); unsetenv("DM_COOP_ROOM"); unsetenv("DM_COOP_RELAY");
    setenv("DM_COOP_INTERNET", "0", 1);
    if (!strcmp(mode, "unset")) unsetenv("DM_COOP");
    else setenv("DM_COOP", enabled ? "join:127.0.0.1" :
                !strcmp(mode, "zero") ? "0" : !strcmp(mode, "empty") ? "" : mode, 1);
    coop_node *n = dm_coop_node();
    if (!enabled) {
        CHECK(n == NULL, "unset/off/0/empty/invalid yields no node");
        CHECK(h_sk_Socket(2, 2, 17) == -1 && t_skerr == SK_ENETDOWN, "C++ socket stays offline");
        CHECK(h_nsk_Socket(2, 2, 17) == -1 && t_skerr == SK_ENETDOWN, "C socket stays offline");
        int fd = h_sk_EventFd(1, 4); sk_pollfd p = {fd, SK_POLLIN, 0};
        CHECK(fd >= EFD_BASE && dm_sk_poll(&p, 1, 0) == 1 && p.revents == SK_POLLIN, "eventfd still works offline");
        h_sk_Close(fd);
    } else {
        CHECK(n != NULL, "valid opt-in creates node");
        if (!n) return 1;
        CHECK(coop_ldn_initialize(n) == 0, "LDN initializes without a host connection");
        if (!strcmp(mode, "select-race")) {
            int fd = h_sk_Socket(2, 2, 17);
            assert(fd >= COOP_FD_BASE);
            uint64_t ex[16] = {0}; int64_t tv[2] = {0, 0}; bit(ex, fd);
            close_before_poll = fd;
            CHECK(dm_sk_select(1024, NULL, NULL, ex, tv) == -1 && t_skerr == SK_EBADF && has(ex, fd),
                  "exception-only select reports EBADF and preserves sets after a concurrent close");
            coop_ldn_finalize(n);
            printf("co-op host (%s): %d checks, %d failed\n", mode, checks, failures);
            return failures != 0;
        }
        int v = h_sk_Socket(2, 2, 17), c = h_nsk_Socket(2, 2, 17), e = h_sk_EventFd(1, 4);
        CHECK(v >= COOP_FD_BASE && c > v && e >= EFD_BASE, "virtual C/C++ sockets and eventfd have separate ranges");
        CHECK(h_nsk_Fcntl(c, SK_F_SETFL, SK_O_NONBLOCK) == 0 && h_nsk_Fcntl(c, SK_F_GETFL, 0) == (2 | SK_O_NONBLOCK), "C fcntl reaches virtual socket");
        unsigned char sa[16] = {16, 2}; unsigned len = sizeof sa;
        CHECK(h_nsk_Bind(c, sa, sizeof sa) == 0 && h_nsk_GetSockName(c, sa, &len) == 0 && len == 16, "C bind and getsockname reach virtual socket");
        char one;
        CHECK(h_nsk_Recv(c, &one, 1, 0) == -1 && t_skerr == COOP_EAGAIN, "empty nonblocking virtual receive reports BSD EAGAIN");
        CHECK(plain_h_sk_Connect2(c, NULL, 0) == -1 && plain_h_sk_Errno() == SK_ENETDOWN,
              "plain Connect replaces a prior EAGAIN with ENETDOWN");
        assert(h_nsk_Recv(c, &one, 1, 0) == -1 && t_skerr == COOP_EAGAIN);
        CHECK(plain_h_sk_Accept2(c, NULL, NULL) == -1 && plain_h_sk_Errno() == SK_ENETDOWN,
              "plain Accept replaces a prior EAGAIN with ENETDOWN");
        assert(h_nsk_Recv(c, &one, 1, 0) == -1 && t_skerr == COOP_EAGAIN);
        CHECK(plain_h_sk_Bind(-1, sa, sizeof sa) == -1 && plain_h_sk_Errno() == SK_ENETDOWN,
              "plain Bind fallback replaces a prior EAGAIN with ENETDOWN");
        CHECK(h_nsk_Socket(2, 1, 0) == -1 && t_skerr == SK_ENETDOWN, "TCP remains unavailable");
        sk_pollfd p[160];
        for (int i = 0; i < 160; i++) p[i] = (sk_pollfd){i & 1 ? v : e, i & 1 ? SK_POLLOUT : SK_POLLIN, -1};
        int got = dm_sk_poll(p, 160, 0), complete = 1;
        for (int i = 0; i < 160; i++) complete &= p[i].revents == p[i].events;
        CHECK(got == 160 && complete, "mixed poll writes all entries beyond its 64-entry batch");
        sk_pollfd mixed[3] = {{v, SK_POLLOUT, 0}, {e, SK_POLLIN, 0}, {-1, SK_POLLIN, -1}};
        CHECK(dm_sk_poll(mixed, 3, 0) == 2 && mixed[2].revents == 0, "mixed poll ignores negative descriptors");
        uint64_t rd[16] = {0}, wr[16] = {0}; int64_t tv[2] = {0, 0}; bit(rd, e); bit(wr, v);
        CHECK(dm_sk_select(1024, rd, wr, NULL, tv) == 2 && has(rd, e) && has(wr, v), "mixed select reports both descriptor kinds");
        CHECK(h_nsk_Close(c) == 0 && !dm_coop_vfd(c), "C close releases the virtual socket");
        dm_coop_set_event(&one); c_state(NULL, 1);
        CHECK(signals == 1, "LDN state signals the attached binding");
        current_binding++; c_state(NULL, 1);
        CHECK(signals == 1, "closed/reused guest event cannot receive a stale callback");
        dm_coop_set_event(NULL); c_state(NULL, 0);
        CHECK(signals == 1, "cleared event binding is not signalled");
        h_sk_Close(v); h_sk_Close(e); coop_ldn_finalize(n);
    }
    printf("co-op host (%s): %d checks, %d failed\n", mode, checks, failures);
    return failures != 0;
}
