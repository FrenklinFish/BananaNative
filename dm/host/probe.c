// Probes DM_PROBE: when the debugger needs a system authorization prompt on the Mac, use this to print registers and a backtrace at a guest address.
#include "dm.h"
#include <signal.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <pthread.h>
#include <stdatomic.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
void crash_set_trap_hook(int (*fn)(ucontext_t *uc));
#if !PLAT_IOS
// Syntax module:offset[@x0 offset]. Put a brk at the guest address; on hit print x0..x3 (and the readable strings they point to) and a backtrace, then execute the original instruction from a trampoline slot and jump back.
// The trampoline borrows x16: place probes only at function entries or at points where x16 is dead; the original instruction must not be PC-relative (adr/adrp/b/bl/ldr literal/cbz...).
#define MAX_PROBE 64
typedef void (*probe_fn)(ucontext_t *uc);
static struct { uint64_t at; uint32_t *slot; unsigned hits, seen; char name[40]; long dump, dpost; probe_fn fn; int freg; long foff; uint64_t fval; int hreg; int once; } g_probe[MAX_PROBE];   // once: removed after its callback's first hit   // dpost: @*xN+off+dpost

// !xN: treat xN as a sead::ExpHeap and print its free and used blocks (block size at block +0x18, alignment pad at +0x12). Settings (docs/agents/05-debugging.md):
// DM_PROBE_HEAP=<name>,<start>,<size>,<free list>,<used list> layout by sead version (hex; node offset at list +0x14), default SMBW 0,28,30,b0,c8 (no name), BotW 28,38,40,e0,f8;
// DM_PROBE_HEAPNAME=<text> only heaps whose name contains it; DM_PROBE_HEAPWORDS=1 each used block's first two words; DM_PROBE_HEAPFRAME=<vtable offset in main> heaps of that class
// are frame heaps (head +0xe0, tail +0xe8); DM_PROBE_HEAPRAW=1 the heap object's first 0x130 bytes
static struct { int init, words, raw; long lay[5], frame_vt; const char *want; } g_hw = { .lay = { 0, 0x28, 0x30, 0xb0, 0xc8 } };
static void probe_heapcfg(void) {
    if (g_hw.init) return; g_hw.init = 1;
    const char *e = getenv("DM_PROBE_HEAP"); if (e) sscanf(e, "%lx,%lx,%lx,%lx,%lx", &g_hw.lay[0], &g_hw.lay[1], &g_hw.lay[2], &g_hw.lay[3], &g_hw.lay[4]);
    e = getenv("DM_PROBE_HEAPFRAME"); g_hw.frame_vt = e ? strtol(e, 0, 16) : -1;
    g_hw.want = getenv("DM_PROBE_HEAPNAME"); g_hw.words = getenv("DM_PROBE_HEAPWORDS") != NULL; g_hw.raw = getenv("DM_PROBE_HEAPRAW") != NULL;
}
static int rd64(uint64_t a, uint64_t *v) { return !plat_mem_read(a, v, 8); }
static void probe_word(uint64_t v) {   // A word as module+offset when it points into a module (a vtable names the object)
    module_t *m = v > 0x10000 ? module_of((void *)v) : NULL;
    if (m) fprintf(stderr, "%s+%llx", m->name, (unsigned long long)(v - (uint64_t)m->base)); else fprintf(stderr, "%llx", (unsigned long long)v);
}
static void probe_heapname(uint64_t h, char *nm, size_t n) {   // sead heaps are named (resource heaps after their file)
    uint64_t np = 0; nm[0] = 0;
    if (g_hw.lay[0] && rd64(h + g_hw.lay[0], &np) && np) { nm[plat_mem_read(np, nm, n - 1) ? 0 : n - 1] = 0; for (int k = 0; nm[k]; k++) if (nm[k] < 0x20 || nm[k] > 0x7e) { nm[k] = 0; break; } }
}
static void probe_heapwalk(uint64_t h) {
    char nm[128]; probe_heapname(h, nm, sizeof nm);
    uint64_t start = 0, size = 0, vt = 0; rd64(h + g_hw.lay[1], &start); rd64(h + g_hw.lay[2], &size); rd64(h, &vt);
    module_t *vm = vt ? module_of((void *)vt) : NULL;
    if (g_hw.frame_vt > 0 && vm && !strcmp(vm->name, "main") && vt - (uint64_t)vm->base == (uint64_t)g_hw.frame_vt) {
        uint64_t hd = 0, tl = 0; rd64(h + 0xe0, &hd); rd64(h + 0xe8, &tl);
        fprintf(stderr, "[dm]   FrameHeap %#llx \"%s\" size %#llx head +%#llx tail +%#llx free %#llx\n", (unsigned long long)h, nm, (unsigned long long)size, (unsigned long long)(hd - start), (unsigned long long)(tl - start), (unsigned long long)(tl - hd));
        return;
    }
    fprintf(stderr, "[dm]   ExpHeap %#llx \"%s\" start %#llx size %#llx\n", (unsigned long long)h, nm, (unsigned long long)start, (unsigned long long)size);
    if (g_hw.raw) { fprintf(stderr, "[dm]   heap words:"); for (int k = 0; k < 0x130; k += 8) { uint64_t v = 0; rd64(h + k, &v); fprintf(stderr, "%s+%x=", k % 64 ? " " : "\n[dm]     ", k); probe_word(v); } fputc('\n', stderr); }
    static const char *L[2] = { "free", "used" };
    for (int k = 0; k < 2; k++) {
        uint64_t list = h + g_hw.lay[3 + k], offw = 0, node = 0, sum = 0, pads = 0; int n = 0; rd64(list + 0x14, &offw); int32_t off = (int32_t)offw;
        rd64(list + 8, &node);
        fprintf(stderr, "[dm]   %s:", L[k]);
        while (node && node != list && n < 4000) {
            uint64_t blk = node - off, bsz = 0, w2 = 0; rd64(blk + 0x18, &bsz); rd64(blk + 0x10, &w2);
            unsigned pad = (unsigned)(w2 >> 16) & 0xffff;
            if (g_hw.words && k) {
                uint64_t a = 0, b = 0; rd64(blk + 0x20, &a); rd64(blk + 0x28, &b);
                fprintf(stderr, "\n[dm]     %llx+%llx pad %x: ", (unsigned long long)(blk - start), (unsigned long long)bsz, pad); probe_word(a); fputc(' ', stderr); probe_word(b);
            } else {
                fprintf(stderr, "%s%llx+%llx", n % 8 ? " " : "\n[dm]     ", (unsigned long long)(blk - start), (unsigned long long)bsz);
                if (pad) fprintf(stderr, "p%x", pad);
            }
            sum += bsz; pads += pad; n++;
            if (!rd64(node + 8, &node)) break;
        }
        fprintf(stderr, "\n[dm]   %s %d blocks %#llx bytes, alignment pads %#llx\n", L[k], n, (unsigned long long)sum, (unsigned long long)pads);
    }
}   // dump: on hit print 16 bytes at [x0+dump] (-1 = do not print); fn: dedicated handler (replaces the default print)
static int g_nprobe;
static uint64_t g_probe_frame_first, g_probe_frame_last = UINT64_MAX;
static unsigned g_probe_limit, g_probe_every;   // DM_PROBE_LIMIT printed hits per probe; DM_PROBE_EVERY=N every hit counts, every N-th prints (#N is then the true hit count: rates; nxnative d0182aa2)
uint64_t dm_guest_frame(void);
static void probe_str(uint64_t p) {
    char buf[80]; size_t got = sizeof buf - 1;
    if (p < 0x1000 || plat_mem_read(p, buf, got)) return;
    buf[got] = 0; int n = 0; while (buf[n] >= 0x20 && buf[n] < 0x7f) n++;
    if (n >= 3 && (buf[n] == 0 || n == (int)got)) { fprintf(stderr, " \"%.*s\"", n, buf); return; }
    // Game string object {char* pointer, u64 length, u64 capacity|top bit} (the path argument of 0xdf67f0 FJSONUtils has exactly this layout)
    if (got >= 24) {
        uint64_t ptr, len; memcpy(&ptr, buf, 8); memcpy(&len, buf + 8, 8);
        char t[96];
        if (len >= 3 && len < sizeof t && !plat_mem_read(ptr, t, len)) {
            int k = 0; while (k < (int)len && t[k] >= 0x20 && t[k] < 0x7f) k++;
            if (k == (int)len) fprintf(stderr, " S\"%.*s\"", (int)len, t);
        }
    }
}
// DM_PROBE_DUMP=module:offset:length[:file]: on the first hit of any probe, write that range of guest memory to a file (default run/platform/probe_dump.bin)
static void probe_dump_once(void) {
    static int done; const char *e = getenv("DM_PROBE_DUMP"); if (done || !e) return; done = 1;
    char spec[512]; strlcpy(spec, e, sizeof spec);
    char *mod = strtok(spec, ":"), *o = strtok(NULL, ":"), *n = strtok(NULL, ":"), *f = strtok(NULL, ":");
    module_t *m = NULL; for (int i = 0; i < g_nmod; i++) if (mod && !strcmp(g_mod[i].name, mod)) m = &g_mod[i];
    if (!m || !o || !n) { LOG("DM_PROBE_DUMP format: module:offset:length[:file]"); return; }
    char def[512]; snprintf(def, sizeof def, "%s%s/run/platform/probe_dump.bin", getenv("DM_ROOT") ?: getenv("HOME"), getenv("DM_ROOT") ? "" : "/dm-work");
    FILE *fp = fopen(f ? f : def, "wb"); if (!fp) return;
    fwrite(m->base + strtoull(o, 0, 16), 1, strtoull(n, 0, 16), fp); fclose(fp);
    LOG("probe dump %s+%s length %s → %s", mod, o, n, f ? f : def);
}
static int probe_hit(ucontext_t *uc) {   // Registered by probe_install with the SIGTRAP branch of crash.c
    uint64_t pc = PLAT_UC_PC(uc);
    int n = __atomic_load_n(&g_nprobe, __ATOMIC_ACQUIRE);   // probe_add publishes an entry before its brk is written
    for (int i = 0; i < n; i++) if (g_probe[i].at == pc) {
        if (g_probe[i].fn) { g_probe[i].hits++; g_probe[i].fn(uc); PLAT_UC_PC(uc) = (uint64_t)g_probe[i].slot;
            if (g_probe[i].once) {   // the original instruction back in place (this hit still resumes through the slot)
                uint32_t *w = (uint32_t *)(uintptr_t)g_probe[i].at; uint64_t ps = (uint64_t)getpagesize(), pg = g_probe[i].at & ~(ps - 1);
                if (!mprotect((void *)pg, ps, PROT_READ | PROT_WRITE)) { *w = g_probe[i].slot[0]; mprotect((void *)pg, ps, PROT_READ | PROT_EXEC); plat_icache_invalidate(w, 4); }
                g_probe[i].at = 0;
            }
            return 1; }
        uint64_t frame = dm_guest_frame();
        if (g_probe_every && frame >= g_probe_frame_first && frame <= g_probe_frame_last && ++g_probe[i].seen % g_probe_every) { PLAT_UC_PC(uc) = (uint64_t)g_probe[i].slot; return 1; }
        if (frame < g_probe_frame_first || frame > g_probe_frame_last || (g_probe_limit && g_probe[i].hits >= g_probe_limit)) {
            PLAT_UC_PC(uc) = (uint64_t)g_probe[i].slot; return 1;
        }
        if (g_probe[i].freg >= 0) {   // ?xN=value / ?[xN+offset]=value: pass through silently when it does not match
            uint64_t v = PLAT_UC_X(uc, g_probe[i].freg);
            if (g_probe[i].foff >= 0 && plat_mem_read(v + g_probe[i].foff, &v, 8)) v = ~g_probe[i].fval;
            if (v != g_probe[i].fval) { PLAT_UC_PC(uc) = (uint64_t)g_probe[i].slot; return 1; }
        }
        if (g_probe[i].hreg >= 0 && g_hw.want) {   // !xN with DM_PROBE_HEAPNAME: report only hits on heaps of that name
            char nm[128]; probe_heapname(PLAT_UC_X(uc, g_probe[i].hreg), nm, sizeof nm);
            if (!strstr(nm, g_hw.want)) { PLAT_UC_PC(uc) = (uint64_t)g_probe[i].slot; return 1; }
        }
        probe_dump_once();
        flockfile(stderr);   // One block per hit when threads hit at once
        { extern uint64_t g_t0_ns; uint64_t now_ns(void); uint32_t thr_cur_handle(void); ++g_probe[i].hits; fprintf(stderr, "[dm] PROBE %s #%u [%.4fs] F=%llu T=%x", g_probe[i].name, g_probe_every ? g_probe[i].seen : g_probe[i].hits, (now_ns() - g_t0_ns) / 1e9, (unsigned long long)frame, thr_cur_handle()); }
        for (int r = 0; r < 8; r++) { uint64_t v = PLAT_UC_X(uc, r); fprintf(stderr, " x%d=%#llx", r, v); probe_str(v); }
        for (int r = 19; r < 24; r++) fprintf(stderr, " x%d=%#llx", r, PLAT_UC_X(uc, r));
        if (getenv("DM_PROBE_ALL")) for (int r = 8; r < 19; r++) fprintf(stderr, " x%d=%#llx", r, PLAT_UC_X(uc, r));   // also print x8–x18
        fprintf(stderr, " lr=%#llx", PLAT_UC_LR(uc));
        { uint64_t x8 = PLAT_UC_X(uc, 8); Dl_info di; fprintf(stderr, " x8=%#llx", x8);   // x8: indirect call targets are often here (probe placed before blr x8)
          if (dladdr((void *)x8, &di) && di.dli_sname) fprintf(stderr, " (%s+%#lx)", di.dli_sname, (unsigned long)(x8 - (uint64_t)di.dli_saddr)); }
        if (g_probe[i].dump >= 0) {
            uint8_t b[16];
            int dr = (g_probe[i].dump >> 25) & 31;                                    // @xN+offset: based on xN
            uint64_t at = PLAT_UC_X(uc, dr) + (g_probe[i].dump & 0xffffff);
            if (g_probe[i].dump & 0x1000000) { uint64_t p = 0; if (!plat_mem_read(at, &p, 8) && p) at = p + g_probe[i].dpost; else at = 0; }   // @*offset[+post]: dereference first, then dump (post bytes further on)
            if (at && !plat_mem_read(at, b, 16)) {
                fprintf(stderr, " [%sx%d+%#lx]", (g_probe[i].dump & 0x1000000) ? "*" : "", dr, g_probe[i].dump & 0xffffff);
                if (g_probe[i].dpost) fprintf(stderr, "+%#lx", g_probe[i].dpost);
                fputc('=', stderr); for (int k = 0; k < 16; k++) fprintf(stderr, "%02x", b[k]); }
        }
        fputc('\n', stderr);
        { static long xd = -2; if (xd == -2) { const char *e = getenv("DM_PROBE_X2"); xd = e ? strtol(e, 0, 16) : -1; }   // DM_PROBE_X2=bytes: also print the memory x2 points to (to read object fields)
          if (xd > 0) { uint64_t at = PLAT_UC_X(uc, 2); uint8_t b[512]; long n = xd > 512 ? 512 : xd, got = n;
            if (!plat_mem_read(at, b, (size_t)n)) { fprintf(stderr, "[dm]   [x2]:"); for (long k = 0; k < (long)got; k += 8) { uint64_t v; memcpy(&v, b + k, 8); fprintf(stderr, "%s%llx", k % 32 ? " " : "\n[dm]   ", (unsigned long long)v); } fputc('\n', stderr); } } }
        if (g_probe[i].hreg >= 0) probe_heapwalk(PLAT_UC_X(uc, g_probe[i].hreg));
        dm_backtrace(PLAT_UC_FP(uc));
        funlockfile(stderr);
        PLAT_UC_PC(uc) = (uint64_t)g_probe[i].slot;
        return 1;
    }
    return 0;
}
static uint32_t *g_slots; static int g_slots_sealed;
static void probe_add(module_t *m, uint64_t off, long dump, probe_fn fn) {
    if (g_nprobe >= MAX_PROBE) die("too many probes");
    if (!g_slots) g_slots = mmap(NULL, 0x4000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    uint64_t at = (uint64_t)m->base + off;
    uint32_t *w = (uint32_t *)at, *sl = g_slots + g_nprobe * 8;
    if (g_slots_sealed) mprotect(g_slots, 0x4000, PROT_READ | PROT_WRITE | PROT_EXEC);   // a probe added while the game runs: the slot page opened for it (still executable: other probes resume through it)
    sl[0] = *w;                                  // Original instruction
    sl[1] = 0x58000050;                          // ldr x16, #8
    sl[2] = 0xD61F0200;                          // br x16
    *(uint64_t *)(sl + 3) = at + 4;
    if (g_slots_sealed) { mprotect(g_slots, 0x4000, PROT_READ | PROT_EXEC); plat_icache_invalidate(sl, 32); }
    // The entry is complete and counted before the brk is written: a thread of the running game can reach the brk at once (a live probe), and a trap that probe_hit
    // finds no entry for is an unhandled one
    int k = g_nprobe;
    g_probe[k].at = at; g_probe[k].slot = sl; g_probe[k].dump = dump; g_probe[k].fn = fn; g_probe[k].freg = -1; g_probe[k].hreg = -1;
    snprintf(g_probe[k].name, sizeof g_probe[0].name, "%s+%#llx", m->name, off);
    __atomic_store_n(&g_nprobe, k + 1, __ATOMIC_RELEASE);
    uint64_t ps = (uint64_t)getpagesize(), pg = at & ~(ps - 1);   // the host's page (4 KB on Android): a 16 KB block could take a data page next to the code read-only
    int px = g_slots_sealed ? PROT_EXEC : 0;   // while the game runs the page stays executable: other threads may be running code on it
    if (mprotect((void *)pg, ps, PROT_READ | PROT_WRITE | px)) die("probe mprotect");
    __atomic_store_n(w, 0xD43E0000u, __ATOMIC_RELEASE);   // brk #0xf000
    mprotect((void *)pg, ps, PROT_READ | PROT_EXEC);
    plat_icache_invalidate(w, 4);
    LOG("probe %s (original instruction %08x)", g_probe[k].name, sl[0]);
}

// DM_PROBE_WATCH=<address>[+<length>] (hex guest address, length 8 by default): who writes these bytes, without a debugger. Their host page is write-protected for good once it is writable,
// and every store that faults on it is carried out by the fault handler through a writable alias of the page (STR / STUR / STP of general and SIMD registers in their immediate, pre- and
// post-index forms, register-offset STR, DC ZVA), so a burst of stores to a busy page cannot slip past. A store reaching the watched bytes prints the thread, pc, lr, the store address,
// the old and new values and a backtrace. Any other instruction (atomics, exclusives) is logged once per pc and the page is unprotected for it, then protected again a millisecond later
// (a store in that millisecond goes unseen). STEINS;GATE RE:BOOT: the engine object's page takes bursts of stores every frame, and a watch that unprotected the page for each fault missed
// the one that matters; this one found it at once (main+0x389e90, a setter its start-up code calls with 0: the game renders 1280x720 docked by its own choice)
static void watch_start(void);
static atomic_flag g_wlk = ATOMIC_FLAG_INIT;   // held while the watched page is opened and shut around one store (handler threads, the arming thread)
static uint64_t g_wat, g_wlen, g_wpg, g_wpsz; static uint8_t *g_walias; static _Atomic int g_warmed;
static void watch_where(const char *tag, uint64_t a) {
    module_t *m = module_of((void *)a); Dl_info di;
    if (m) fprintf(stderr, " %s=%s+%#llx", tag, m->name, (unsigned long long)(a - (uint64_t)m->base));
    else if (dladdr((void *)a, &di) && di.dli_sname) fprintf(stderr, " %s=%s+%#lx", tag, di.dli_sname, (unsigned long)(a - (uint64_t)di.dli_saddr));
    else fprintf(stderr, " %s=%#llx", tag, (unsigned long long)a);
}
static uint64_t watch_val(void) { uint64_t v = 0; memcpy(&v, (void *)g_wat, g_wlen < 8 ? g_wlen : 8); return v; }
// Decode the store at pc: its address, size and data (up to 64 bytes); the base register write-back (wb: register number, value) for pre- and post-index forms. 0 when not a decodable store
static int watch_decode(ucontext_t *uc, uint32_t in, uint64_t *ea, unsigned *len, uint8_t *data, int *wb, uint64_t *wbv) {
#define XN(r) ((r) == 31 ? PLAT_UC_SP(uc) : (r) == 29 ? PLAT_UC_FP(uc) : (r) == 30 ? PLAT_UC_LR(uc) : PLAT_UC_X(uc, r))   // base register: 31 = sp
#define XT(r) ((r) == 31 ? 0 : (r) == 29 ? PLAT_UC_FP(uc) : (r) == 30 ? PLAT_UC_LR(uc) : PLAT_UC_X(uc, r))           // data register: 31 = zero
    unsigned rt = in & 31, rn = (in >> 5) & 31, v = (in >> 26) & 1; *wb = -1;
    if ((in & 0xffffffe0) == 0xd50b7420) {                        // DC ZVA: zero the 64-byte block
        *ea = XT(rt) & ~63ull; *len = 64; memset(data, 0, 64); return 1; }
    if ((in & 0x3a000000) == 0x28000000) {                                                               // STP / STNP: opc 101 V 0 type L imm7
        if (in & (1u << 22)) return 0;                                                                   // LDP
        unsigned opc = in >> 30, type = (in >> 23) & 3, sz = v ? 4u << opc : (opc & 2 ? 8 : 4);
        if ((!v && opc == 1) || opc == 3) return 0;                                                      // STGP / LDPSW, reserved
        int64_t imm = ((int64_t)(int32_t)(in << 10) >> 25) * (int64_t)sz; uint64_t base = XN(rn);
        *ea = type == 1 ? base : base + imm; *len = 2 * sz;
        unsigned r2 = (in >> 10) & 31;
        if (v) { memcpy(data, &PLAT_UC_V(uc)[rt], sz); memcpy(data + sz, &PLAT_UC_V(uc)[r2], sz); }
        else { uint64_t a = XT(rt), b = XT(r2); memcpy(data, &a, sz); memcpy(data + sz, &b, sz); }
        if (type == 1 || type == 3) { *wb = (int)rn; *wbv = base + imm; }
        return 1;
    }
    if ((in & 0x3b000000) == 0x39000000 || (in & 0x3b200000) == 0x38000000 || (in & 0x3b200c00) == 0x38200800) {   // STR (unsigned offset) / STUR, STR pre / post / STR (register)
        unsigned size = in >> 30, opc = (in >> 22) & 3, sz;
        if (v) { if (opc == 0) sz = 1u << size; else if (opc == 2 && size == 0) sz = 16; else return 0; }
        else { if (opc != 0) return 0; sz = 1u << size; }
        uint64_t base = XN(rn), addr;
        if ((in & 0x3b000000) == 0x39000000) addr = base + (uint64_t)((in >> 10) & 0xfff) * sz;          // unsigned offset
        else if (!(in & (1u << 21))) {                                                                   // imm9: unscaled, post (01), pre (11)
            int64_t imm = (int64_t)(int32_t)(in << 11) >> 23; unsigned k = (in >> 10) & 3;
            if (k == 2) return 0;                                                                        // STTR
            addr = k == 1 ? base : base + imm;
            if (k == 1 || k == 3) { *wb = (int)rn; *wbv = base + imm; }
        } else {                                                                                         // register offset: option (15:13), S (12)
            unsigned rm = (in >> 16) & 31, opt = (in >> 13) & 7, s = (in >> 12) & 1; uint64_t x = XT(rm);
            if (opt == 2) x = (uint32_t)x; else if (opt == 6) x = (uint64_t)(int64_t)(int32_t)x; else if (opt != 3 && opt != 7) return 0;
            addr = base + (s ? x << __builtin_ctz(sz) : x);
        }
        *ea = addr; *len = sz;
        if (v) memcpy(data, &PLAT_UC_V(uc)[rt], sz); else { uint64_t a = XT(rt); memcpy(data, &a, sz); }
        return 1;
    }
    return 0;
#undef XN
#undef XT
}
static int watch_fault(void *addr, int is_write, void *ucv) {
    uint64_t a = (uint64_t)addr;
    if (!is_write || a < g_wpg || a >= g_wpg + g_wpsz) return 0;
    ucontext_t *uc = ucv; uint64_t pc = PLAT_UC_PC(uc), ea = 0, wbv = 0; unsigned len = 0; int wb = -1; uint8_t data[64];
    if (watch_decode(uc, *(uint32_t *)pc, &ea, &len, data, &wb, &wbv)) {   // (no alias mapping, Android: the page is opened for this one store and shut again, microseconds)
        int hit = ea < g_wat + g_wlen && ea + len > g_wat; uint64_t old = hit ? watch_val() : 0;
        if (!g_walias) { while (atomic_flag_test_and_set(&g_wlk)) {} mprotect((void *)g_wpg, g_wpsz, PROT_READ | PROT_WRITE); }
        for (unsigned i = 0; i < len; i++) {   // bytes in the watched page go through the alias; a store crossing into the next page writes the rest directly
            uint64_t b = ea + i;
            if (g_walias && b >= g_wpg && b < g_wpg + g_wpsz) g_walias[b - g_wpg] = data[i]; else *(volatile uint8_t *)b = data[i];
        }
        if (!g_walias) { if (atomic_load(&g_warmed)) mprotect((void *)g_wpg, g_wpsz, PROT_READ); atomic_flag_clear(&g_wlk); }
        if (wb >= 0) { if (wb == 31) PLAT_UC_SP(uc) = wbv; else if (wb == 29) PLAT_UC_FP(uc) = wbv; else if (wb == 30) PLAT_UC_LR(uc) = wbv; else PLAT_UC_X(uc, wb) = wbv; }
        PLAT_UC_PC(uc) = pc + 4;
        if (hit) {
            flockfile(stderr);
            { extern uint64_t g_t0_ns; uint64_t now_ns(void); uint32_t thr_cur_handle(void);
              fprintf(stderr, "[dm] WATCH %#llx [%.4fs] T=%x store of %u bytes at %#llx: %#llx → %#llx", (unsigned long long)g_wat, (now_ns() - g_t0_ns) / 1e9, thr_cur_handle(), len, (unsigned long long)ea, (unsigned long long)old, (unsigned long long)watch_val()); }
            watch_where("pc", pc); watch_where("lr", PLAT_UC_LR(uc)); fputc('\n', stderr);
            dm_backtrace(PLAT_UC_FP(uc));
            funlockfile(stderr);
        }
        return 1;
    }
    // CAS / CASA / CASL / CASAL (all sizes) carried out here, without an alias: every store to the page faults, so doing them one at a time under a lock is atomic
    // for all writers (Donkey Kong Bananza: a lock on the watched page takes thousands a second; opening the page for each let the stage name's write slip through)
    { uint32_t in = *(uint32_t *)pc;
      if ((in & 0x3fa07c00) == 0x08a07c00) {
          unsigned sz = 1u << (in >> 30), rs = (in >> 16) & 31, rn = (in >> 5) & 31, rt = in & 31;
          uint64_t a = rn == 31 ? PLAT_UC_SP(uc) : PLAT_UC_X(uc, rn), mask = sz == 8 ? ~0ull : (1ull << (8 * sz)) - 1;
          uint64_t cmp = (rs == 31 ? 0 : PLAT_UC_X(uc, rs)) & mask, nv = (rt == 31 ? 0 : PLAT_UC_X(uc, rt)) & mask, old = 0;
          while (atomic_flag_test_and_set(&g_wlk)) {}
          mprotect((void *)g_wpg, g_wpsz, PROT_READ | PROT_WRITE);
          memcpy(&old, (void *)a, sz);
          int hit = old == cmp && a < g_wat + g_wlen && a + sz > g_wat;
          if (old == cmp) { __atomic_thread_fence(__ATOMIC_SEQ_CST); memcpy((void *)a, &nv, sz); __atomic_thread_fence(__ATOMIC_SEQ_CST); }
          if (atomic_load(&g_warmed)) mprotect((void *)g_wpg, g_wpsz, PROT_READ);
          atomic_flag_clear(&g_wlk);
          if (rs != 31) PLAT_UC_X(uc, rs) = old;
          PLAT_UC_PC(uc) = pc + 4;
          if (hit) { flockfile(stderr); fprintf(stderr, "[dm] WATCH %#llx compare-and-swap at %#llx: %#llx -> %#llx", (unsigned long long)g_wat, (unsigned long long)a, (unsigned long long)old, (unsigned long long)nv);
                     watch_where("pc", pc); watch_where("lr", PLAT_UC_LR(uc)); fputc('\n', stderr); dm_backtrace(PLAT_UC_FP(uc)); funlockfile(stderr); }
          return 1;
      } }
    // Exclusive and atomic stores (STXR / STLXR / STLR / STXP, CAS, SWP, LD<op>) address [Xn] with no offset: one that reaches the watched bytes is reported like a store, every time
    // (its new value lands when it runs on the unprotected page). Tears of the Kingdom's memory-shortage flag is set by such a store, and the page it lies on takes many of them
    { uint32_t in = *(uint32_t *)pc; int ex = (in & 0x3f000000) == 0x08000000, at = (in & 0x3f200c00) == 0x38200000;   // exclusive class (CAS / CASP among them); LD<op> / SWP
      if (ex || at) {
          unsigned rn = (in >> 5) & 31, sz = 1u << (in >> 30);
          uint64_t base = rn == 31 ? PLAT_UC_SP(uc) : rn == 29 ? PLAT_UC_FP(uc) : rn == 30 ? PLAT_UC_LR(uc) : PLAT_UC_X(uc, rn);
          if ((in & 0xbfa00000) == 0x88200000) sz *= 2;   // STXP / STLXP: a register pair
          if (base < g_wat + g_wlen && base + sz > g_wat) {
              flockfile(stderr);
              { extern uint64_t g_t0_ns; uint64_t now_ns(void); uint32_t thr_cur_handle(void);
                fprintf(stderr, "[dm] WATCH %#llx [%.4fs] T=%x %s store of %u bytes at %#llx (instruction %08x, not emulated), value before %#llx", (unsigned long long)g_wat, (now_ns() - g_t0_ns) / 1e9, thr_cur_handle(),
                        ex ? "exclusive" : "atomic", sz, (unsigned long long)base, in, (unsigned long long)watch_val()); }
              watch_where("pc", pc); watch_where("lr", PLAT_UC_LR(uc)); fputc('\n', stderr);
              dm_backtrace(PLAT_UC_FP(uc));
              funlockfile(stderr);
          }
      } }
    { static uint64_t seen[16]; static _Atomic int ns; int k = 0, n = atomic_load(&ns);   // not a store form the handler carries out: let it run on an unprotected page
      while (k < n && k < 16 && seen[k] != pc) k++;
      if (k == n && n < 16) { seen[atomic_fetch_add(&ns, 1) & 15] = pc; flockfile(stderr); fprintf(stderr, "[dm] WATCH: instruction %08x not emulated, page unprotected for it:", *(uint32_t *)pc); watch_where("pc", pc); fputc('\n', stderr); funlockfile(stderr); } }
    while (atomic_flag_test_and_set(&g_wlk)) {}
    mprotect((void *)g_wpg, g_wpsz, PROT_READ | PROT_WRITE); atomic_store(&g_warmed, 0);
    atomic_flag_clear(&g_wlk);
    return 1;
}
static void *watch_arm(void *p) {
    (void)p;
    for (;;) {
        if (!atomic_load(&g_warmed)) {
            uint64_t ra = 0, rs = 0; int prot = 0;
            if (!plat_mem_region(g_wpg, &ra, &rs, &prot) && ra <= g_wpg && (prot & PROT_WRITE)) {
                if (!g_walias) {   // a second, writable mapping of the same page, for the handler's stores
                    g_walias = plat_mem_alias((void *)g_wpg, g_wpsz);
                    static uint64_t told; if (told != g_wat) { told = g_wat; LOG("WATCH %#llx armed, value %#llx%s", (unsigned long long)g_wat, (unsigned long long)watch_val(), g_walias ? "" : " (no alias mapping: each store goes through with the page opened for it)"); }
                }
                while (atomic_flag_test_and_set(&g_wlk)) {}
                atomic_store(&g_warmed, 1); mprotect((void *)g_wpg, g_wpsz, PROT_READ);
                atomic_flag_clear(&g_wlk); }
        }
        usleep(1000);
    }
    return NULL;
}
// DM_PROBE_POLL=<address>[+<length>] (hex guest address, up to 8 bytes, 4 by default): when even the watch above misses a write (a page that atomics keep unprotected), a host thread spins on
// the bytes and, on each change, prints old → new and dumps every guest thread with its backtrace (svc_thread_dump, the first 8 changes): the writer is still inside the code that wrote.
// Tears of the Kingdom's memory-shortage flag (a word its frame check compares with 1 and clears) lies on such a page. A suffix =<value> (hex) snapshots and dumps only the changes to that value,
// for a word that changes thousands of times before the bad store (Mario vs. Donkey Kong: a heap block's list link, rewritten by every allocation beside it, until 0x3f landed in it)
static uint64_t g_poll, g_plen, g_pval; static int g_pwant;
// All threads but this one stopped at once, then their pc, lr and 8 frame-pointer frames read and printed after they run again: the writer is caught a few microseconds after its store
static void poll_snap(void) {
    enum { MT = 256, NF = 8 }; static uint64_t fr[MT][NF + 2]; static plat_thread_t keep[MT];
    plat_thread_t *ths; unsigned nth = 0, ns = 0; plat_thread_t self = plat_thread_self();
    if (plat_threads(&ths, &nth)) return;
    for (unsigned i = 0; i < nth && ns < MT; i++) if (ths[i] != self && !plat_thread_suspend(ths[i])) keep[ns++] = ths[i];
    for (unsigned i = 0; i < ns; i++) {
        plat_regs_t st; memset(fr[i], 0, sizeof fr[i]);
        if (plat_thread_get_regs(keep[i], &st)) continue;
        fr[i][0] = st.pc; fr[i][1] = st.lr;
        uint64_t fp = st.fp, w[2];
        for (int k = 0; k < NF && fp > 0x100000000ull && !plat_mem_read(fp, w, 16); k++) { fr[i][2 + k] = w[1]; fp = w[0]; }
    }
    for (unsigned i = 0; i < ns; i++) plat_thread_resume(keep[i]);
    plat_threads_release(ths, nth); plat_thread_self_release(self);
    flockfile(stderr);
    for (unsigned i = 0; i < ns; i++) {   // threads with guest code anywhere in their first frames
        int guest = 0; for (int k = 0; k < NF + 2; k++) guest |= module_of((void *)fr[i][k]) != NULL;
        if (!guest) continue;
        fprintf(stderr, "[dm] POLL thread %u:", i); watch_where("pc", fr[i][0]); watch_where("lr", fr[i][1]);
        for (int k = 2; k < NF + 2 && fr[i][k]; k++) watch_where("<", fr[i][k]);
        fputc('\n', stderr);
    }
    funlockfile(stderr);
}
static void *poll_run(void *p) {
    (void)p; void svc_thread_dump(void); extern uint64_t g_t0_ns; uint64_t now_ns(void);
    uint64_t old = 0;   // read through the kernel: the address is not readable until the game maps it (the heap before SetHeapSize)
    while (plat_mem_read(g_poll, &old, g_plen)) usleep(1000);
    LOG("POLL %#llx + %llu armed, value %#llx", (unsigned long long)g_poll, (unsigned long long)g_plen, (unsigned long long)old);
    for (int n = 0;;) {
        uint64_t v = 0;
        if (plat_mem_read(g_poll, &v, g_plen) || v == old) continue;
        int hit = !g_pwant || v == g_pval;
        if (hit && n < 8) poll_snap();
        fprintf(stderr, "[dm] POLL %#llx [%.4fs] %#llx → %#llx\n", (unsigned long long)g_poll, (now_ns() - g_t0_ns) / 1e9, (unsigned long long)old, (unsigned long long)v);
        old = v;
        if (hit && n++ < 2) svc_thread_dump();
    }
    return NULL;
}
static void watch_install(void) {
    { const char *e = getenv("DM_PROBE_POLL");
      if (e) { char *end; g_poll = strtoull(e, &end, 16); g_plen = *end == '+' ? strtoull(end + 1, &end, 16) : 4;
               if (*end == '=') { g_pwant = 1; g_pval = strtoull(end + 1, 0, 16); }
               if (!g_poll || !g_plen || g_plen > 8) die("DM_PROBE_POLL format: <address>[+<length up to 8>][=<value>] (hex)");
               pthread_t t; pthread_create(&t, NULL, poll_run, NULL); pthread_detach(t); } }
    watch_start();
}
static void watch_start(void) {   // DM_PROBE_WATCH at start, or later through the live control (probe_live)
    const char *e = getenv("DM_PROBE_WATCH"); if (!e) return;
    char *end; uint64_t want = strtoull(e, &end, 16);
    if (g_wat) {   // a new address through the live control: the old page writable again, the arming thread takes the new one
        if (want == g_wat) return;
        atomic_store(&g_warmed, 1); mprotect((void *)g_wpg, g_wpsz, PROT_READ | PROT_WRITE);
        g_wlen = *end == '+' ? strtoull(end + 1, 0, 16) : 8; g_wpg = want & ~(g_wpsz - 1); g_walias = NULL; g_wat = want; atomic_store(&g_warmed, 0);
        LOG("write watch moved to %#llx + %#llx", (unsigned long long)g_wat, (unsigned long long)g_wlen); return;
    }
    g_wat = want; g_wlen = *end == '+' ? strtoull(end + 1, 0, 16) : 8;
    if (!g_wat || !g_wlen) die("DM_PROBE_WATCH format: <address>[+<length>] (hex)");
    g_wpsz = (uint64_t)getpagesize(); g_wpg = g_wat & ~(g_wpsz - 1);
    if (g_wat + g_wlen > g_wpg + g_wpsz) die("DM_PROBE_WATCH: the watched bytes must lie in one host page");
    dm_fault_hook_register(watch_fault);
    pthread_t t; pthread_create(&t, NULL, watch_arm, NULL); pthread_detach(t);
    LOG("write watch on %#llx + %#llx (host page %#llx)", (unsigned long long)g_wat, (unsigned long long)g_wlen, (unsigned long long)g_wpg);
}

#endif

#if !PLAT_IOS
// ---------- Dev tools, changed while the game runs (DM_DEVTOOLS=1 at start; off by default, nothing below runs without it) ----------
// A thread looks at the live control's generation (vka_ctl.c: an "env KEY=VALUE" line in DM_CTL, android/tools/nxctl.sh env) five times a second and, when it moved,
// reads DM_PROBE_LIVE and DM_PROBE_WATCH again
extern _Atomic uint32_t vka_env_gen __attribute__((weak));
static int devtools_on(void) { static int on = -1; if (on < 0) { const char *e = getenv("DM_DEVTOOLS"); on = e && atoi(e) > 0; } return on; }
static module_t *main_mod(void) { for (int i = 0; i < g_nmod; i++) if (!strcmp(g_mod[i].name, "main")) return &g_mod[i]; return NULL; }
// DM_PROBE_LIVE=<hex offset>[,...] (into main): each new offset gets a printing probe while the game runs, no restart (at most 6 printed hits each unless
// DM_PROBE_LIMIT says otherwise). The same rules as DM_PROBE: function entries, never a PC-relative instruction
static void probe_live(void) {
    watch_start();
    module_t *m = main_mod(); const char *e = getenv("DM_PROBE_LIVE"); if (!e || !m) return;
    if (!g_probe_limit) g_probe_limit = 6;
    char spec[512]; strlcpy(spec, e, sizeof spec);
    for (char *t = strtok(spec, ","); t; t = strtok(NULL, ",")) {
        uint64_t off = strtoull(t, 0, 16), at = (uint64_t)m->base + off; int have = 0;
        for (int i = 0; i < g_nprobe; i++) if (g_probe[i].at == at) have = 1;
        if (!have && off && g_nprobe < MAX_PROBE) { probe_add(m, off, -1, NULL); LOG("probe: main+%#llx added while running", (unsigned long long)off); }
    }
}
static void *devtools_run(void *arg) {
    (void)arg; uint32_t gen = 0;
    for (;;) { usleep(200000); uint32_t g = atomic_load_explicit(&vka_env_gen, memory_order_relaxed); if (g != gen) { gen = g; probe_live(); } }
    return NULL;
}
static void devtools_start(void) {
    if (!devtools_on()) return;
    if (!&vka_env_gen) { LOG("[RED] dev tools: this host has no live control (vka_env_gen): DM_DEVTOOLS does nothing"); return; }
    pthread_t t; pthread_create(&t, NULL, devtools_run, NULL); pthread_detach(t);
    LOG("dev tools on (DM_DEVTOOLS): DM_PROBE_LIVE and DM_PROBE_WATCH are read again at each live control change");
}
#endif

#if PLAT_ANDROID   // the launcher's Dynamic resolution setting; the Mac and iOS builds leave the game's own range
// ---------- A game's dynamic resolution range (the profile's drs_ctl rows: Donkey Kong Bananza) ----------
// The game picks its render size between NdsrUpscaleMinRate and NdsrUpscaleMaxRate below its upscaler's target (ProjectGraphicsParam; the level's 1.375 and 2.0: 73 % to
// 50 % of the target on each side), holding the GPU near MaintainGpuPercentage. Its controller reads both from their parameter objects on every decision (update
// at main+0x38e0f0: [params+0x5d0]+0x64 the max rate, main+0x38dd00: [params+0x5c8]+0x64 the min rate, params = [controller+0x10]), so a store there applies at once.
// The parameter object is found by a probe at main+0x38e100 (ldr x8, [x10, #0x5d0]: x10 is it), removed after its first hit. DM_DRS=<min %>,<max %> of the target at
// start (100,100: always the full target, the upscaler a 1:1 copy), live nxhost_drs_set; unset or 0: the game's own range. The addresses below are Bananza's main e25c90c1; each build's come from its drs_ctl row
static uint64_t g_drs_pick, g_drs_budget, g_drs_preset;   // the drs_ctl row's places for the controller's pick, its GPU budget load and the preset's automatic flag
static _Atomic uint64_t g_drs_params; static _Atomic uint32_t g_drs_want; static float g_drs_orig[2]; static int g_drs_have_orig;
static void drs_apply(void) {
    uint64_t p = atomic_load(&g_drs_params), pmin, pmax; if (!p) return;
    if (plat_mem_read(p + 0x5c8, &pmin, 8) || plat_mem_read(p + 0x5d0, &pmax, 8) || !pmin || !pmax) return;
    float *fmin = (float *)(uintptr_t)(pmin + 0x64), *fmax = (float *)(uintptr_t)(pmax + 0x64);
    if (!g_drs_have_orig) { g_drs_orig[0] = *fmin; g_drs_orig[1] = *fmax; g_drs_have_orig = 1; }
    uint32_t w = atomic_load(&g_drs_want), lo = w >> 16, hi = w & 0xffff;
    if (!w) { *fmin = g_drs_orig[0]; *fmax = g_drs_orig[1]; LOG("dynamic resolution: the game's own range (rates %.3f-%.3f)", g_drs_orig[0], g_drs_orig[1]); return; }
    *fmin = 100.0f / (float)hi; *fmax = 100.0f / (float)lo;   // (the smaller rate is the larger size)
    LOG("dynamic resolution: %u-%u %% of the upscaler's target (rates %.3f-%.3f)", lo, hi, *fmin, *fmax);
}
// The controller's own paths do not all honor the range: when the frame rate falls below 30 (and when a caller hands it a rate, [x1+0x2c]) it picks a rate of its own,
// the level's 2.0 at 1080p, so with the range at 100 % the picture still dropped to half size for a stretch about every 0.6 s (blurred, with a hitch at each switch;
// 2026-11-05). Every path meets at main+0x38de2c (ldr x9, [x19, #0xf0]) with its pick in s8, before it is snapped to the rate steps and stored: a second probe there
// (kept: one trap a decision, a frame) clamps s8 into the range chosen, or the game's own range when none is: it is installed at start
#if defined(__linux__) && defined(__aarch64__)
#include <asm/sigcontext.h>
static float *uc_sreg(ucontext_t *uc, int n) {   // the low lane of vector register n in the signal frame (written back at the signal's return)
    struct _aarch64_ctx *h = (struct _aarch64_ctx *)(void *)uc->uc_mcontext.__reserved;
    for (int k = 0; k < 32 && h->magic && h->size; k++) { if (h->magic == FPSIMD_MAGIC) return (float *)(void *)&((struct fpsimd_context *)(void *)h)->vregs[n]; h = (struct _aarch64_ctx *)(void *)((char *)h + h->size); }
    return NULL;
}
#else
static float *uc_sreg(ucontext_t *uc, int n) { (void)uc; (void)n; return NULL; }
#endif
static module_t *g_drs_mod;
extern _Atomic uint32_t vka_env_gen __attribute__((weak));   // the live control's generation (vka_ctl.c): DM_DRS changed while the game runs is taken at the next decision
int nxhost_fps(float *out, int n) __attribute__((weak));   // the measured frame rate (vka_fps.c)
int nxhost_fps_limit_get(void) __attribute__((weak));   // a frame limiter's cap where the host has one
static int drs_cap(void) {   // the frame rate the dynamic resolution aims at: the limiter's cap, else the frame rate cap (DM_FPS_TARGET; 0 or unset: the game's 60)
    int c = &nxhost_fps_limit_get ? nxhost_fps_limit_get() : 0;
    if (c <= 0) { const char *e = getenv("DM_FPS_LIMIT"); if (!e || atoi(e) <= 0) e = getenv("DM_FPS_TARGET"); c = e ? atoi(e) : 0; }
    return c > 0 && c <= 60 ? c : 60;
}
static void drs_clamp(ucontext_t *uc) {
    if (&vka_env_gen) { static uint32_t gen; uint32_t g = atomic_load_explicit(&vka_env_gen, memory_order_relaxed);
        if (g != gen) { gen = g; const char *e = getenv("DM_DRS"); int a = 0, b = 0; if (e) sscanf(e, "%d,%d", &a, &b);
            if (a < 10 || b < a) a = b = 0; if (b > 100) b = 100; uint32_t w = a > 0 ? (uint32_t)a << 16 | (uint32_t)b : 0;
            if (e && w != atomic_load(&g_drs_want)) atomic_store(&g_drs_want, w); } }   // the range chosen, else the game's own (its picks went to rate 1.0 for 0.6 s every few seconds: full size the GPU could not hold)
    uint32_t w = atomic_load(&g_drs_want); float lo, hi;
    if (w) { lo = 100.0f / (float)(w & 0xffff); hi = 100.0f / (float)(w >> 16); }   // rates: lo the full size end
    else if (g_drs_have_orig) { lo = g_drs_orig[0]; hi = g_drs_orig[1]; }
    else return;
    float *s8 = uc_sreg(uc, 8), *s9 = uc_sreg(uc, 9); if (!s8 || !s9) return;
    float pick = *s8, cur = *s9, out = pick;   // s9: the rate in use (the controller loads it first, [[ctrl+0xf0]+0x270]+0xc)
    if (out < lo) out = lo; else if (out > hi) out = hi;
    // A step to a larger size only after a wait: every change of the rate in use cost the GPU several slow frames (Donkey Kong Bananza at 1080p, a light area: the
    // controller flipped 2.0 / 1.833 every few frames, five frames at three times their GPU time about once a second, the hitching gone with a fixed size). A smaller size
    // at once; a larger one only when the last step down is DM_DRS_BACKOFF (s, default 1) behind, a wait that doubles (to 30 s) each time a step up is taken back within
    // 2 s and halves after 10 s without one. DM_DRS_BACKOFF=0: the controller's steps as they come
    // Slack: a smaller size only once the game's measured frame rate (nxhost_fps, a 1 s window) is DM_DRS_SLACK % (default 15) under the cap chosen: at 60, below 51 fps; the
    // game's own GPU budget alone stepped down at frame rates the player could not tell from 60 (2026-11-06)
    {   static int slack = -1; if (slack < 0) { const char *e = getenv("DM_DRS_SLACK"); slack = e ? atoi(e) : 15; }
        float v[1]; int cap = drs_cap();
        if (slack > 0 && out > cur + 0.001f && &nxhost_fps && nxhost_fps(v, 1) && v[0] >= cap * (100 - slack) / 100.0f) out = cur;
    }
    static float base = -1, wait_s; static double last_down, last_up, calm_since;
    if (base < 0) { const char *e = getenv("DM_DRS_BACKOFF"); base = e ? (float)atof(e) : 1.0f; wait_s = base; }
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); double now = (double)ts.tv_sec + ts.tv_nsec / 1e9;
    if (base > 0 && cur >= lo && cur <= hi + 0.001f) {
        if (out > cur + 0.001f) {   // smaller: at once; a step up taken back soon doubles the wait
            if (now - last_up < 2.0) { wait_s = wait_s * 2 > 30 ? 30 : wait_s * 2; calm_since = now; }
            last_down = now;
        } else if (out < cur - 0.001f) {   // larger: only after the wait
            if (now - last_down < wait_s) out = cur; else last_up = now;
        }
        if (now - calm_since > 10 && wait_s > base) { wait_s = wait_s / 2 < base ? base : wait_s / 2; calm_since = now; }
    }
    *s8 = out;
    static float last_out; static _Atomic int nlog;
    if (out != last_out && atomic_fetch_add(&nlog, 1) < 300)
        { float v[1] = {0}; if (&nxhost_fps) nxhost_fps(v, 1);
          LOG("dynamic resolution: rate %.3f at %.2f s (controller picked %.3f, in use %.3f, range %.3f-%.3f, step-up wait %.0f s, %.1f fps)", out, now, pick, cur, lo, hi, wait_s, v[0]); }
    last_out = out;
}
// The controller holds the GPU time under a budget, [x1+8] minus a margin [ctrl+0xe8] (main+0x38dcb8: ldr s1, [x20, #8]), the game's 60 fps frame whatever the frame
// limiter allows; with a cap of 30 it still shrank the picture to fit 16.7 ms. A third probe, on the next instruction (main+0x38dcbc, ldr s2, [x19, #0xe8]: s1 is loaded,
// x16 unused here), sets s1 to the frame time of the cap the player chose (drs_cap), in the unit the game's own value is in
static void drs_budget(ucontext_t *uc) {
    float *s1 = uc_sreg(uc, 1); if (!s1) return;
    static _Atomic int noted; if (!atomic_exchange(&noted, 1)) LOG("dynamic resolution: the game's GPU budget %.1f %% of its frame", *s1);
    int cap = drs_cap();
    *s1 *= 60.0f / (float)cap;   // the budget is a share of the game's 60 fps frame (the frame-rate controller is held at 1 interval): a cap of 30 allows twice the GPU time
    static int last; if (cap != last) { last = cap; LOG("dynamic resolution: aimed at %d fps (budget %.1f %% of a 60 fps frame)", cap, *s1); }
}
// Some areas' presets turn the dynamic resolution off ([preset+0x29] = 0: a fixed rate, [preset+0x2c], 1.5 in the Wild level's battle stages): with a range chosen the flag
// is set before the controller reads it (main+0x38dbc8, ldrb w8, [x20, #0x29]), so every area adapts within the range
static void drs_auto(ucontext_t *uc) {
    if (!atomic_load(&g_drs_want)) return;
    uint8_t *f = (uint8_t *)(uintptr_t)(PLAT_UC_X(uc, 20) + 0x29);
    if (!*f) { *f = 1; static _Atomic int n; if (atomic_fetch_add(&n, 1) < 20) LOG("dynamic resolution: preset %#llx made automatic (its fixed rate %.3f)", (unsigned long long)PLAT_UC_X(uc, 20), *(float *)(void *)(f + 3)); }
}
static _Atomic int g_drs_clamp_on;
static void drs_hit(ucontext_t *uc) { if (!atomic_load(&g_drs_params)) { atomic_store(&g_drs_params, PLAT_UC_X(uc, 10)); drs_apply(); } }
__attribute__((visibility("default"))) void nxhost_drs_set(int min_pct, int max_pct) {
    if (min_pct < 10) min_pct = 0; if (max_pct > 100) max_pct = 100; if (min_pct > max_pct) min_pct = max_pct;
    atomic_store(&g_drs_want, min_pct > 0 ? (uint32_t)min_pct << 16 | (uint32_t)max_pct : 0); drs_apply();
    if (g_drs_mod && !atomic_exchange(&g_drs_clamp_on, 1)) {
        // each place is checked for the instruction it must hold (the profile row could be for another build): a mismatch leaves that part out, with a red line
        if (*(uint32_t *)(g_drs_mod->base + g_drs_pick) == 0xf9407a69u) { probe_add(g_drs_mod, g_drs_pick, -1, drs_clamp); LOG("dynamic resolution: the controller's own picks clamped into the range too"); }
        else LOG("[RED] dynamic resolution: main+%#llx (drs_ctl) is not ldr x9, [x19, #0xf0], the controller's meeting point: its own picks stay", (unsigned long long)g_drs_pick);
        if (*(uint32_t *)(g_drs_mod->base + g_drs_budget) == 0xbd40ea62u) probe_add(g_drs_mod, g_drs_budget, -1, drs_budget);
        else LOG("[RED] dynamic resolution: main+%#llx (drs_ctl) is not ldr s2, [x19, #0xe8], the budget load: it aims at the game's own frame rate", (unsigned long long)g_drs_budget);
        if (*(uint32_t *)(g_drs_mod->base + g_drs_preset) == 0x3940a688u) probe_add(g_drs_mod, g_drs_preset, -1, drs_auto);
        else LOG("[RED] dynamic resolution: main+%#llx (drs_ctl) is not ldrb w8, [x20, #0x29], the preset's automatic flag: fixed-rate areas stay fixed", (unsigned long long)g_drs_preset);
    }
}
static void drs_install(void) {
    module_t *m = NULL; for (int i = 0; i < g_nmod; i++) if (!strcmp(g_mod[i].name, "main")) m = &g_mod[i];
    static const struct { const char *build; uint64_t params, pick, budget, preset; } rows[] = DM_GAME_DRS_CTL;
    int r = -1; for (int k = 0; m && k < DM_GAME_NDRS_CTL; k++) if (!strcmp(rows[k].build, m->build_id)) r = k;
    if (r < 0) return;   // no drs_ctl row for this main: the game keeps its own range
    if (*(uint32_t *)(m->base + rows[r].params) != 0xf942e948u) { LOG("[RED] dynamic resolution: main+%#llx (drs_ctl) is not ldr x8, [x10, #0x5d0]: the game keeps its own range", (unsigned long long)rows[r].params); return; }
    probe_add(m, rows[r].params, -1, drs_hit); g_probe[g_nprobe - 1].once = 1;
    g_drs_pick = rows[r].pick; g_drs_budget = rows[r].budget; g_drs_preset = rows[r].preset;
    g_drs_mod = m;
    const char *e = getenv("DM_DRS"); int lo = 0, hi = 0; if (e) sscanf(e, "%d,%d", &lo, &hi);
    nxhost_drs_set(lo, hi);   // (also the game's own: its range clamps the controller's own picks)
}
#endif
void probe_install(void) {
#if PLAT_ANDROID
    drs_install();
#endif
#if !PLAT_IOS
    crash_set_trap_hook(probe_hit);
    const char *frames = getenv("DM_PROBE_FRAMES");
    if (frames) {
        char *end; g_probe_frame_first = strtoull(frames, &end, 10);
        g_probe_frame_last = *end == '-' ? strtoull(end + 1, NULL, 10) : g_probe_frame_first;
    }
    const char *every = getenv("DM_PROBE_EVERY");
    if (every) g_probe_every = (unsigned)strtoul(every, NULL, 10);
    const char *limit = getenv("DM_PROBE_LIMIT");
    if (limit) g_probe_limit = (unsigned)strtoul(limit, NULL, 10);
    const char *e = getenv("DM_PROBE");
    if (e) {
        char spec[512]; strlcpy(spec, e, sizeof spec);
        for (char *t = strtok(spec, ","); t; t = strtok(NULL, ",")) {
            char *c = strchr(t, ':'); if (!c) die("DM_PROBE format: module:offset");
            *c = 0; module_t *m = NULL;
            for (int i = 0; i < g_nmod; i++) if (!strcmp(g_mod[i].name, t)) m = &g_mod[i];
            if (!m) die("DM_PROBE: no such module %s", t);
            char *at_ = strchr(c + 1, '@');
            long dump = -1, post = 0;
            if (at_) {   // @offset / @*offset / @xN+offset / @*xN+offset / @*xN+offset+post (the dereferenced pointer plus post: @*x0+48+8 = an al::LiveActor's position, al::getTrans)
                const char *q = at_ + 1; long deref = 0, reg = 0; char *e3;
                if (*q == '*') { deref = 0x1000000; q++; }
                if (*q == 'x') { char *e2; reg = strtol(q + 1, &e2, 10) & 31; q = *e2 == '+' ? e2 + 1 : e2; }
                dump = deref | (reg << 25) | (strtol(q, &e3, 16) & 0xffffff);
                if (deref && *e3 == '+') post = strtol(e3 + 1, 0, 16);
            }
            char *qm = strchr(c + 1, '?'), *bang = strchr(c + 1, '!');
            probe_add(m, strtoull(c + 1, 0, 16), dump, NULL);
            g_probe[g_nprobe - 1].dpost = post;
            if (bang && bang[1] == 'x') { g_probe[g_nprobe - 1].hreg = (int)strtol(bang + 2, 0, 10) & 31; probe_heapcfg(); }
            if (qm) {   // Filter: ?x0=706c400000 or ?[x0+38]=781548 (hex)
                const char *q = qm + 1; int br = *q == '[';
                if (br) q++;
                if (*q != 'x') die("DM_PROBE filter format: ?xN=value or ?[xN+offset]=value");
                char *e2; g_probe[g_nprobe - 1].freg = (int)strtol(q + 1, &e2, 10) & 31; g_probe[g_nprobe - 1].foff = -1;
                if (br) { g_probe[g_nprobe - 1].foff = *e2 == '+' ? strtol(e2 + 1, &e2, 16) : 0; if (*e2 == ']') e2++; }
                if (*e2 != '=') die("DM_PROBE filter missing =");
                g_probe[g_nprobe - 1].fval = strtoull(e2 + 1, 0, 16);
            }
        }
    }
    if (devtools_on() && !g_slots) g_slots = mmap(NULL, 0x4000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);   // live probes need the slot page sealed now
    if (g_slots && !g_slots_sealed) { mprotect(g_slots, 0x4000, PROT_READ | PROT_EXEC); plat_icache_invalidate(g_slots, 0x4000); g_slots_sealed = 1; }
    watch_install();
    devtools_start();
#endif
}
