// The host side of local wireless over IP (hle_coop.h): the one co-op node of the process and the glue the nn::ldn / nn::socket hooks share.
#include "hle.h"
#include "dm.h"
#include "hle_coop.h"
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#if NX_COOP_VENDOR
void dm_guest_sleep_ns(int64_t ns);   // hle_thread.c

static _Atomic uint64_t g_ldn_binding;
static coop_node *g_node;
static pthread_once_t g_node_once = PTHREAD_ONCE_INIT;
void dm_coop_set_event(void *ev) { atomic_store(&g_ldn_binding, ev ? hle_system_event_binding(ev) : 0); }
static void c_log(void *u, const char *line) { (void)u; LOG("coop: %s", line); }
static void c_sleep(void *u, int64_t ns) { (void)u; dm_guest_sleep_ns(ns); }
static void c_state(void *u, int st) { (void)u; LOG("coop: ldn state %d", st); uint64_t binding = atomic_load(&g_ldn_binding); if (binding) hle_signal_system_event_binding(binding); }
static const char *c_build(void *u) { (void)u; return dm_main_build(); }
static uint64_t c_mods(void *u, char *what, size_t n) { (void)u; if (what && n) what[0] = 0; return 0; }   // the host applies no gameplay mods: peers compare "none"

// DM_COOP unset, empty, "off" or "0": no node and no log line, so a run without it is the run it was before. The adapter does not echo the value; the dependency's diagnostics can contain addresses and room codes, so redact logs before sharing
static void node_make(void) {
    const char *e = getenv("DM_COOP");
    if (!e || !*e || !strcmp(e, "off") || !strcmp(e, "0")) return;
    coop_hooks h; memset(&h, 0, sizeof h);
    h.log = c_log; h.sleep_ns = c_sleep; h.on_state = c_state; h.build = c_build; h.mods = c_mods;
    g_node = coop_env_node(&h);
    if (g_node) LOG("coop: co-op node up (DM_COOP set), main build %.16s", dm_main_build());
    else LOG("[RED] coop: DM_COOP is set but not valid (auto[:port] | join:<IPv4>[:port] | host:<port>): no co-op node, local wireless stays off");
}
coop_node *dm_coop_node(void) { pthread_once(&g_node_once, node_make); return g_node; }
int dm_coop_vfd(int fd) { coop_node *n = dm_coop_node(); return n && fd >= COOP_FD_BASE && coop_vnet_is(n, fd); }
int dm_coop_ret(int64_t r) { if (r < 0) { dm_sk_seterr((int)-r); return -1; } return (int)r; }

void dm_coop_trace(const char *fn, int64_t r) { static _Atomic int n; if (dm_coop_node() && atomic_fetch_add(&n, 1) < 400) LOG("coop: %s -> %#llx", fn, (unsigned long long)r); }
#endif
