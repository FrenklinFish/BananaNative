// Platform layer: the host services whose system interface differs between Apple's kernels (macOS, iOS) and Linux / Bionic (Android).
// Everything else in dm/host is plain POSIX and C. plat_apple.h keeps the Apple calls the host made before this layer existed, so the Mac and iPhone behave as they did;
// plat_android.c is the Bionic side (android/PORTING.md lists each call and its replacement). A service Android does not have yet logs a [RED] line once and fails the way
// the Apple call fails, so the caller's existing fallback runs.
#pragma once
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>
#include <time.h>
#if defined(__APPLE__)
#include <TargetConditionals.h>
#include <sys/ucontext.h>
#define PLAT_APPLE 1
#define PLAT_ANDROID 0
#define PLAT_MAC TARGET_OS_OSX
#define PLAT_IOS TARGET_OS_IPHONE
#elif defined(__ANDROID__) && defined(__aarch64__)
#include <ucontext.h>
#define PLAT_APPLE 0
#define PLAT_ANDROID 1
#define PLAT_MAC 0
#define PLAT_IOS 0
#else
#error "the host runs on Apple platforms or Android arm64"
#endif
// Apple: every function is a static inline wrapper in plat_apple.h; Android: plat_android.c
#if PLAT_APPLE
#define PLAT_FN static inline
#else
#define PLAT_FN
#endif

// ---- Object file conventions (Mach-O vs ELF) ----
// A table the host collects at load time (hle.h HLE_FORCE, the swhle and swnvn tables): a Mach-O section in __DATA, an ELF section bounded by __start_ / __stop_
#if PLAT_APPLE
#define PLAT_TABLE(name) __attribute__((used, section("__DATA," #name)))
#else
#define PLAT_TABLE(name) __attribute__((used, retain, section(#name)))
#endif
// The table's bytes and size; NULL when the image has none
PLAT_FN const void *plat_table(const char *name, unsigned long *size);
// A C symbol in inline assembly text (Mach-O prefixes an underscore), and its page / page offset relocations
#if PLAT_APPLE
#define PLAT_ASM_SYM(s) "_" #s
#define PLAT_ASM_PAGE(s) "_" #s "@PAGE"
#define PLAT_ASM_PAGEOFF(s) "_" #s "@PAGEOFF"
#define PLAT_ASM_HIDDEN ".private_extern"
#else
#define PLAT_ASM_SYM(s) #s
#define PLAT_ASM_PAGE(s) #s
#define PLAT_ASM_PAGEOFF(s) ":lo12:" #s
#define PLAT_ASM_HIDDEN ".hidden"
#endif

// ---- Time ----
// The monotonic clock in its native unit (Apple: mach_absolute_time ticks; Android: CLOCK_MONOTONIC ns) and the ratio to nanoseconds
typedef struct { uint32_t numer, denom; } plat_timebase_t;
PLAT_FN uint64_t plat_abs_time(void);
PLAT_FN void plat_timebase(plat_timebase_t *tb);
PLAT_FN void plat_wait_until(uint64_t abs);   // sleep until plat_abs_time() reaches abs
PLAT_FN uint64_t plat_clock_ns(clockid_t c);   // clock_gettime in nanoseconds (CLOCK_MONOTONIC, CLOCK_THREAD_CPUTIME_ID, ...)
PLAT_FN int64_t plat_process_age_ns(void);    // how long ago the process was created (wall clock); -1 unknown

// ---- Memory ----
// Copies through the kernel: a page unmapped or protected fails instead of faulting (vm_read_overwrite / vm_write; process_vm_readv / writev). 0 = every byte copied
PLAT_FN int plat_mem_read(uint64_t addr, void *out, size_t len);
PLAT_FN int plat_mem_write(uint64_t addr, const void *src, size_t len);
// The mapping that holds addr: its start, size and PROT_* bits; -1 when addr is not mapped
PLAT_FN int plat_mem_region(uint64_t addr, uint64_t *start, uint64_t *size, int *prot);
// A second mapping of [addr, addr + len) sharing its pages, read-write (cheat writes to code, the probe write watch); NULL when not possible
PLAT_FN void *plat_mem_alias(void *addr, size_t len);
PLAT_FN void plat_icache_invalidate(void *p, size_t len);
PLAT_FN size_t plat_page_size(void);
// The guest address block: size bytes of inaccessible address space, no memory (Apple: anywhere; Android: at 0x4000000000 when free, always below 1 << 39); NULL when it cannot be had
PLAT_FN void *plat_mem_reserve(size_t size);
PLAT_FN int64_t plat_footprint_bytes(void);   // the memory this process is charged with (Apple phys_footprint, Android resident set); -1 unknown
// The guest block's name in /proc/self/maps for [p, p + n) after a fixed map replaced part of it (Android: anonymous mappings merge again only with neighbours of the same name;
// Apple: nothing to do)
PLAT_FN void plat_mem_name_guest(void *p, size_t n);
// The process's memory maps (Android: lines of /proc/self/maps) and the most it may have (*max: vm.max_map_count); -1 unknown (Apple). Reads into a stack buffer, no
// allocation: it also answers when the process is at the limit
PLAT_FN int plat_maps_count(int *max);
// A private allocation zone (Unreal's heap): its own accounting on Apple, the process heap elsewhere
typedef struct plat_zone plat_zone_t;
PLAT_FN plat_zone_t *plat_zone_create(const char *name);
PLAT_FN void *plat_zone_malloc(plat_zone_t *z, size_t n);
PLAT_FN void *plat_zone_memalign(plat_zone_t *z, size_t align, size_t n);
PLAT_FN void *plat_zone_realloc(plat_zone_t *z, void *p, size_t n);
PLAT_FN void plat_zone_free(plat_zone_t *z, void *p);
PLAT_FN size_t plat_malloc_size(const void *p);
PLAT_FN size_t plat_malloc_good_size(size_t n);   // the size an allocation of n bytes really gets
// A pointer-keyed table (CFDictionary on Apple)
typedef struct plat_ptrmap plat_ptrmap_t;
PLAT_FN plat_ptrmap_t *plat_ptrmap_create(void);
PLAT_FN void *plat_ptrmap_get(plat_ptrmap_t *m, const void *k);
PLAT_FN void plat_ptrmap_set(plat_ptrmap_t *m, const void *k, void *v);
PLAT_FN void plat_ptrmap_remove(plat_ptrmap_t *m, const void *k);

// ---- Guest thread slots ----
// The rewriter (patch_nso.py) turns the guest's x18, tpidr_el0 and tpidrro_el0 into four per-thread slots, which its code reaches as <thread base> + <key page entry>.
// Apple: the base is tpidrro_el0 (the pthread TSD array) and each slot a pthread key, the entry key × 8. Android (--target android): the base is the block Bionic's
// TLS_SLOT_APP holds (tpidr_el0 + 0x10), the entry the slot's offset in it (android/PORTING.md "Thread slots")
enum { PLAT_SLOT_K18, PLAT_SLOT_KTLS, PLAT_SLOT_KTLR, PLAT_SLOT_KGATE, PLAT_SLOT_N };
PLAT_FN void plat_slots_init(void);                          // once, before the first module is mapped
PLAT_FN uint64_t plat_slot_key_entry(unsigned slot);         // what the loader writes into a module's key page for this slot
PLAT_FN void plat_slot_check(uint64_t k18_entry);            // the main thread reaches the K18 slot through that entry, as the rewritten code will (dies otherwise)
#if PLAT_APPLE
extern pthread_key_t g_key18, g_keytls, g_keytlr, g_keygate;
#define dm_slot_get(s) pthread_getspecific(g_key##s)
#define dm_slot_set(s, v) pthread_setspecific(g_key##s, (v))
#else
typedef struct { void *slot18, *slottls, *slottlr, *slotgate; } plat_slots_t;   // = PLAT_SLOT_* order (the key page entries are offsets into it)
plat_slots_t *plat_slots_own(void);   // this thread's block, made on first use and freed when the thread exits
static inline plat_slots_t *plat_slots_peek(void) { void **tp; __asm__ volatile("mrs %0, tpidr_el0" : "=r"(tp)); return (plat_slots_t *)tp[2]; }   // Bionic's TLS_SLOT_APP; NULL before the first set
static inline plat_slots_t *plat_slots(void) { plat_slots_t *s = plat_slots_peek(); return s ? s : plat_slots_own(); }
// A read never allocates (signal handlers read the slots) and gives NULL on a thread that never set one, as an unset pthread key does on Apple
#define dm_slot_get(s) (plat_slots_peek() ? plat_slots_peek()->slot##s : NULL)
#define dm_slot_set(s, v) (plat_slots()->slot##s = (void *)(v), 0)
#endif

// ---- Threads ----
// Another thread of this process, by its kernel identity (Apple: mach port; Android: kernel thread ID)
typedef uintptr_t plat_thread_t;
PLAT_FN plat_thread_t plat_thread_of(pthread_t t);
PLAT_FN plat_thread_t plat_thread_self(void);   // a reference the caller may drop with plat_thread_self_release
PLAT_FN void plat_thread_self_release(plat_thread_t t);
PLAT_FN void plat_thread_set_name(const char *name);   // this thread's name (Linux keeps 15 bytes of it)
// Registers of a suspended thread (the subset the host reads)
typedef struct { uint64_t x[29], fp, lr, sp, pc, cpsr; } plat_regs_t;
// Stop a thread where it is and read its registers. Between suspend and resume only memory reads may run (the stopped thread may hold any lock): no malloc, no stdio
PLAT_FN int plat_thread_suspend(plat_thread_t t);   // 0 = stopped
PLAT_FN int plat_thread_get_regs(plat_thread_t t, plat_regs_t *r);   // 0 = read (the thread is suspended)
PLAT_FN void plat_thread_resume(plat_thread_t t);
// Scheduler state (TH_STATE_* numbering: 1 running, 3 waiting, …; -1 unknown) and CPU time in µs (user + system; -1 unknown)
PLAT_FN void plat_thread_stack(uint64_t *top, uint64_t *size);   // this thread's stack: its highest address and its size
PLAT_FN int plat_thread_run_state(plat_thread_t t);
PLAT_FN int64_t plat_thread_cpu_us(plat_thread_t t);
PLAT_FN int64_t plat_thread_self_system_ns(void);   // this thread's system CPU time
// Every thread of the process; the list is released with plat_threads_release (Apple's ports are counted references)
PLAT_FN int plat_threads(plat_thread_t **list, unsigned *n);
PLAT_FN void plat_threads_release(plat_thread_t *list, unsigned n);
// Quality of service classes, with Darwin's values (logs print them as before)
typedef enum { PLAT_QOS_UNSPECIFIED = 0x00, PLAT_QOS_UTILITY = 0x11, PLAT_QOS_USER_INITIATED = 0x19, PLAT_QOS_USER_INTERACTIVE = 0x21 } plat_qos_t;
PLAT_FN int plat_qos_set_self(plat_qos_t q);   // 0 = set
PLAT_FN plat_qos_t plat_qos_of(pthread_t t);
PLAT_FN void *plat_qos_override_start(pthread_t t, plat_qos_t q);   // lend a thread a class until _end (NULL when not possible)
PLAT_FN void plat_qos_override_end(void *ov);
// A counting semaphore with a timed wait (dispatch_semaphore on Apple)
typedef struct plat_sem plat_sem_t;
PLAT_FN plat_sem_t *plat_sem_create(long value);
PLAT_FN void plat_sem_signal(plat_sem_t *s);
PLAT_FN int plat_sem_wait_ns(plat_sem_t *s, int64_t ns);   // 0 = taken; ns < 0 waits for ever
PLAT_FN void plat_sem_destroy(plat_sem_t *s);
PLAT_FN int plat_cond_timedwait_rel(pthread_cond_t *cv, pthread_mutex_t *mu, const struct timespec *rel);   // pthread_cond_timedwait for a relative time
// A serial work queue: jobs run one after another on a host thread, in the order they were queued
typedef struct plat_queue plat_queue_t;
PLAT_FN plat_queue_t *plat_queue_create(const char *name);
PLAT_FN void plat_queue_async(plat_queue_t *q, void (*fn)(void *), void *ctx);
PLAT_FN void plat_queue_drain(plat_queue_t *q);   // returns once every job queued so far has run
PLAT_FN void plat_after_ns(uint64_t ns, void (*fn)(void *), void *ctx);   // fn(ctx) on a host thread after ns
PLAT_FN void plat_on_signal(int sig, void (*fn)(void *), void *ctx);       // fn(ctx) on a host thread when sig arrives (its default action is ignored)

// ---- Signal contexts ----
// Registers of a signal handler's ucontext_t (crash.c, probe.c, the pause signal of hle_thread.c)
#if PLAT_APPLE
#define PLAT_UC_X(uc, i) ((uc)->uc_mcontext->__ss.__x[i])
#define PLAT_UC_FP(uc) ((uc)->uc_mcontext->__ss.__fp)
#define PLAT_UC_LR(uc) ((uc)->uc_mcontext->__ss.__lr)
#define PLAT_UC_SP(uc) ((uc)->uc_mcontext->__ss.__sp)
#define PLAT_UC_PC(uc) ((uc)->uc_mcontext->__ss.__pc)
#define PLAT_UC_CPSR(uc) ((uc)->uc_mcontext->__ss.__cpsr)
#define PLAT_UC_V(uc) ((uc)->uc_mcontext->__ns.__v)
#define PLAT_UC_FPSR(uc) ((uc)->uc_mcontext->__ns.__fpsr)
#define PLAT_UC_FPCR(uc) ((uc)->uc_mcontext->__ns.__fpcr)
#define PLAT_UC_ESR(uc) ((uc)->uc_mcontext->__es.__esr)
#else
#include <asm/sigcontext.h>
struct fpsimd_context *plat_uc_fpsimd(ucontext_t *uc);
uint64_t plat_uc_esr(ucontext_t *uc);
#define PLAT_UC_X(uc, i) ((uc)->uc_mcontext.regs[i])
#define PLAT_UC_FP(uc) ((uc)->uc_mcontext.regs[29])
#define PLAT_UC_LR(uc) ((uc)->uc_mcontext.regs[30])
#define PLAT_UC_SP(uc) ((uc)->uc_mcontext.sp)
#define PLAT_UC_PC(uc) ((uc)->uc_mcontext.pc)
#define PLAT_UC_CPSR(uc) ((uc)->uc_mcontext.pstate)
#define PLAT_UC_V(uc) (plat_uc_fpsimd(uc)->vregs)
#define PLAT_UC_FPSR(uc) (plat_uc_fpsimd(uc)->fpsr)
#define PLAT_UC_FPCR(uc) (plat_uc_fpsimd(uc)->fpcr)
#define PLAT_UC_ESR(uc) plat_uc_esr(uc)
#endif

// ---- Files ----
PLAT_FN int plat_fd_path(int fd, char *out, size_t n);   // the path an open descriptor names; 0 = found
PLAT_FN int plat_clone_tree(const char *from, const char *to);   // copy-on-write copy of a tree (clonefile); -1 with errno
PLAT_FN int plat_rename_swap(const char *a, const char *b);      // swap two paths atomically (RENAME_SWAP / RENAME_EXCHANGE); -1 with errno
PLAT_FN void plat_swap_recover(const char *b);                    // at mount: finish a plat_rename_swap a crash interrupted (Android shared storage); nothing elsewhere
// Extended attributes of the path itself (never followed); an absent one fails with errno PLAT_ENOATTR
PLAT_FN ssize_t plat_getxattr(const char *path, const char *name, void *buf, size_t n);
PLAT_FN int plat_setxattr(const char *path, const char *name, const void *buf, size_t n);
PLAT_FN int plat_removexattr(const char *path, const char *name);
#if PLAT_APPLE
#define PLAT_ENOATTR ENOATTR
#else
#define PLAT_ENOATTR ENODATA
#endif

// ---- Device ----
PLAT_FN void plat_device_model(char *out, size_t n);   // "Mac15,3", "iPhone16,1", the Android product model; "" unknown
PLAT_FN void plat_build_commit(char *out, size_t n);   // the commit the packer stamped into the app; unchanged when there is none

// ---- SHA-256 (CommonCrypto on Apple, a small implementation in plat_android.c) ----
typedef struct { uint64_t ctx[16]; } plat_sha256_t;   // room for CC_SHA256_CTX and for plat_android.c's state
PLAT_FN void plat_sha256_init(plat_sha256_t *c);
PLAT_FN void plat_sha256_update(plat_sha256_t *c, const void *p, size_t n);
PLAT_FN void plat_sha256_final(uint8_t out[32], plat_sha256_t *c);
PLAT_FN void plat_sha256(const void *p, size_t n, uint8_t out[32]);

// A stable sort (BSD mergesort)
PLAT_FN int plat_sort_stable(void *base, size_t n, size_t size, int (*cmp)(const void *, const void *));

#if PLAT_APPLE
#include "plat_apple.h"
#endif
