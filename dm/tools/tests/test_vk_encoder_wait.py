"""Run the production encoder ring and futex handshake on Linux; mock only driver calls and AArch64 WFE.

By Nociif, from 71f6a73a on branch fix/mkw of inazuma2k8/BananaNative, without its DM_VK_ENC_WAKE part (not in this change).
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class EncoderWait(unittest.TestCase):
    @unittest.skipUnless(shutil.which("cc"), "C compiler required")
    def test_ring_sleep_flush_and_wrap(self):
        src = (ROOT / "dm/gfx/vk_android/vka_enc.c").read_text()
        ring = src[src.index("enum { RING ="):src.index("// ---------- The records:")]
        a = ring.index("static inline uint64_t wait_change(")
        b = ring.index("static uint64_t mono_ns(", a)
        # Linux x86 has no WFE: model an event-stream wake at 100 us. Keep all
        # production publication, flag/sequence ordering, futex and ring code.
        ring = ring[:a] + r'''
        static _Atomic unsigned monitor_waits;
        static inline uint64_t wait_change(_Atomic uint64_t *a, uint64_t old) {
            if (a == &g_head) atomic_fetch_add(&monitor_waits, 1);
            uint64_t v = atomic_load_explicit(a, memory_order_acquire);
            if (v == old) { struct timespec ts = {0, 100000}; nanosleep(&ts, NULL); }
            return v;
        }
        ''' + ring[b:]
        start = src.index("static void *enc_main(")
        worker = src[start:src.index("// ---------- Interface", start)]
        pre = r'''
        #define _GNU_SOURCE
        #include <assert.h>
        #include <errno.h>
        #include <linux/futex.h>
        #include <pthread.h>
        #include <sched.h>
        #include <stdatomic.h>
        #include <stdint.h>
        #include <stdio.h>
        #include <stdlib.h>
        #include <string.h>
        #include <sys/syscall.h>
        #include <time.h>
        #include <unistd.h>
        typedef void *VkCommandBuffer;
        enum { E_WRAP = 0, E_CALL = 1 };
        #define VRED(...) do { fprintf(stderr, __VA_ARGS__); abort(); } while (0)
        static void vka_perf_thread(int nice) { (void)nice; }
        '''
        driver = r'''
        static uint64_t expected;
        static void run(const hdr *r) {
            const uint64_t *p = (const uint64_t *)(r + 1);
            assert(r->id == E_CALL && p[0] == expected++);
            for (unsigned i = 1; i < (r->size - sizeof *r) / sizeof *p; ++i)
                assert(p[i] == (p[0] ^ (UINT64_C(0x8123987654321000) + i)));
        }
        '''
        post = r'''
        static void idle(void) {
            uint64_t t0 = mono_ns();
            while (!atomic_load(&g_csleep)) {
                assert(mono_ns() - t0 < 2000000000);
                sched_yield();
            }
        }
        static void record(uint64_t seq) {
            size_t n = 64 + seq % 31 * 8;
            hdr *r = rec_alloc(E_CALL, n);
            uint64_t *p = (uint64_t *)(r + 1); p[0] = seq;
            for (unsigned i = 1; i < (n - sizeof *r) / sizeof *p; ++i)
                p[i] = seq ^ (UINT64_C(0x8123987654321000) + i);
            rec_done(n);
        }
        int main(void) {
            g_ring = malloc(RING); assert(g_ring);
            pthread_t th; assert(!pthread_create(&th, NULL, enc_main, NULL));
            idle();
            // A 200 us deadline cannot turn into 64 event-stream wakes.
            assert(atomic_load(&monitor_waits) <= 4);
            atomic_store_explicit(&g_spin_ns, 0, memory_order_relaxed);
            unsigned before = atomic_load(&monitor_waits);
            // A record below the normal batch threshold must be forced out by sync.
            record(0); assert(atomic_load(&g_head) == 0);
            wait_tail(g_ph); idle();
            assert(atomic_load(&monitor_waits) == before);
            // Wake a sleeping consumer with a run of batches; the flush at the wait stays mandatory.
            uint64_t seq = 1;
            for (; seq < 500; seq++) record(seq);
            wait_tail(g_ph); idle();
            // Many transitions between idle, publishing, and producer tail waits.
            g_batch = 0;
            for (; seq < 20000; seq++) {
                if (!(seq % 97)) atomic_store_explicit(&g_spin_ns, seq % 2 ? 200000 : 0, memory_order_relaxed);
                record(seq);
                if (!(seq % 7)) wait_tail(g_ph);
            }
            // Fill past ring capacity with pending unpublished data: rec_alloc must
            // flush before waiting for room. Unequal record sizes force wrap padding.
            g_batch = RING * 2;
            for (; seq < 250000; seq++) record(seq);
            wait_tail(g_ph); idle();
            assert(atomic_load(&g_tail) == g_ph && expected == seq && g_ph > RING * 4);
            puts("production ring: deadlines, zero spin, sleep/wake, flush, capacity and wrap passed");
            return 0;
        }
        '''
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "encoder.c"
            binary = Path(tmp) / "encoder"
            source.write_text(pre + ring + driver + worker + post)
            subprocess.run(["cc", "-O2", "-g", "-std=c11", "-Wall", "-Wextra",
                            "-Wno-unused-function", "-Wno-unused-variable", "-Werror",
                            "-pthread", "-fsanitize=address,undefined", str(source), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], timeout=20, check=True)


if __name__ == "__main__":
    unittest.main()
