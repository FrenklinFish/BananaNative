// Platform layer, Apple side (macOS and iOS): the Mach, CommonCrypto, CoreFoundation and libdispatch calls the host made before plat.h existed, unchanged.
// Inline, so that every host file and unit test that includes dm.h links as it did, with no object of its own. Included by plat.h only
#pragma once
#include <CommonCrypto/CommonDigest.h>
#include <CoreFoundation/CoreFoundation.h>
#include <dispatch/dispatch.h>
#include <libkern/OSCacheControl.h>
#include <mach-o/getsect.h>
#include <mach-o/ldsyms.h>
#include <mach/mach.h>
#include <mach/mach_time.h>
#include <malloc/malloc.h>
#include <pthread/qos.h>
#include <sys/clonefile.h>
#include <sys/mman.h>
#include <sys/sysctl.h>
#include <sys/time.h>
#include <sys/xattr.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#if !TARGET_OS_IPHONE
#include <mach/mach_vm.h>   // the iPhone SDK has no mach_vm.h
#endif

static inline const void *plat_table(const char *name, unsigned long *size) {
    *size = 0; return getsectiondata(&_mh_execute_header, "__DATA", name, size);
}

static inline uint64_t plat_abs_time(void) { return mach_absolute_time(); }
static inline uint64_t plat_clock_ns(clockid_t c) { return clock_gettime_nsec_np(c); }
static inline void plat_timebase(plat_timebase_t *tb) { mach_timebase_info_data_t t; mach_timebase_info(&t); tb->numer = t.numer; tb->denom = t.denom; }
static inline void plat_wait_until(uint64_t abs) { mach_wait_until(abs); }
static inline int64_t plat_process_age_ns(void) {   // p_starttime from sysctl KERN_PROC_PID, wall clock
    struct kinfo_proc kp; size_t n = sizeof kp; int mib[4] = {CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()};
    struct timeval tv; gettimeofday(&tv, NULL);
    if (sysctl(mib, 4, &kp, &n, NULL, 0) || !n) return -1;
    return (int64_t)(tv.tv_sec - kp.kp_proc.p_starttime.tv_sec) * 1000000000ll + (tv.tv_usec - kp.kp_proc.p_starttime.tv_usec) * 1000ll;
}

static inline int plat_mem_read(uint64_t addr, void *out, size_t len) {
    vm_size_t got = 0;
    return vm_read_overwrite(mach_task_self(), (vm_address_t)addr, (vm_size_t)len, (vm_address_t)(uintptr_t)out, &got) == KERN_SUCCESS && got == len ? 0 : -1;
}
static inline int plat_mem_write(uint64_t addr, const void *src, size_t len) {
    return vm_write(mach_task_self(), (vm_address_t)addr, (vm_offset_t)(uintptr_t)src, (mach_msg_type_number_t)len) == KERN_SUCCESS ? 0 : -1;
}
static inline int plat_mem_region(uint64_t addr, uint64_t *start, uint64_t *size, int *prot) {
    vm_address_t at = (vm_address_t)addr; vm_size_t sz = 0; vm_region_basic_info_data_64_t info; mach_msg_type_number_t cnt = VM_REGION_BASIC_INFO_COUNT_64; mach_port_t obj;
    if (vm_region_64(mach_task_self(), &at, &sz, VM_REGION_BASIC_INFO_64, (vm_region_info_t)&info, &cnt, &obj) != KERN_SUCCESS) return -1;   // vm_region_64: iOS has no mach_vm.h
    *start = at; *size = sz; *prot = info.protection;   // VM_PROT_* has PROT_*'s bits
    return 0;
}
static inline void *plat_mem_alias(void *addr, size_t len) {
#if TARGET_OS_IPHONE
    (void)addr; (void)len; return NULL;   // the app's signed code pages: no mapping can make them writable
#else
    mach_vm_address_t dst = 0; vm_prot_t cur = 0, mx = 0;
    kern_return_t kr = mach_vm_remap(mach_task_self(), &dst, len, 0, VM_FLAGS_ANYWHERE, mach_task_self(), (mach_vm_address_t)(uintptr_t)addr, FALSE, &cur, &mx, VM_INHERIT_SHARE);
    if (kr) return NULL;
    // The alias's protections are independent of the original mapping's: make it writable once
    if (mach_vm_protect(mach_task_self(), dst, len, 0, VM_PROT_READ | VM_PROT_WRITE)) { mach_vm_deallocate(mach_task_self(), dst, len); return NULL; }
    return (void *)(uintptr_t)dst;
#endif
}
static inline void plat_icache_invalidate(void *p, size_t len) { sys_icache_invalidate(p, len); }
static inline size_t plat_page_size(void) { return vm_page_size; }
static inline void *plat_mem_reserve(size_t size) { void *p = mmap(NULL, size, PROT_NONE, MAP_PRIVATE | MAP_ANON, -1, 0); return p == MAP_FAILED ? NULL : p; }
static inline int64_t plat_footprint_bytes(void) {
    task_vm_info_data_t vi; mach_msg_type_number_t cnt = TASK_VM_INFO_COUNT;
    return task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&vi, &cnt) == KERN_SUCCESS ? (int64_t)vi.phys_footprint : -1;
}

struct plat_zone { malloc_zone_t z; };
static inline plat_zone_t *plat_zone_create(const char *name) { malloc_zone_t *z = malloc_create_zone(0, 0); malloc_set_zone_name(z, name); return (plat_zone_t *)z; }
static inline void *plat_zone_malloc(plat_zone_t *z, size_t n) { return malloc_zone_malloc((malloc_zone_t *)z, n); }
static inline void *plat_zone_memalign(plat_zone_t *z, size_t align, size_t n) { return malloc_zone_memalign((malloc_zone_t *)z, align, n); }
static inline void *plat_zone_realloc(plat_zone_t *z, void *p, size_t n) { return malloc_zone_realloc((malloc_zone_t *)z, p, n); }
static inline void plat_zone_free(plat_zone_t *z, void *p) { malloc_zone_free((malloc_zone_t *)z, p); }
static inline size_t plat_malloc_size(const void *p) { return malloc_size(p); }
static inline size_t plat_malloc_good_size(size_t n) { return malloc_good_size(n); }

static inline plat_ptrmap_t *plat_ptrmap_create(void) { return (plat_ptrmap_t *)CFDictionaryCreateMutable(NULL, 0, NULL, NULL); }
static inline void *plat_ptrmap_get(plat_ptrmap_t *m, const void *k) { return (void *)CFDictionaryGetValue((CFMutableDictionaryRef)m, k); }
static inline void plat_ptrmap_set(plat_ptrmap_t *m, const void *k, void *v) { CFDictionarySetValue((CFMutableDictionaryRef)m, k, v); }
static inline void plat_ptrmap_remove(plat_ptrmap_t *m, const void *k) { CFDictionaryRemoveValue((CFMutableDictionaryRef)m, k); }

// Thread slots: pthread keys (loader.c), read by the rewritten code at tpidrro_el0 (the pthread TSD array) + 8 × key
static inline void plat_slots_init(void) {
    if (pthread_key_create(&g_key18, NULL) || pthread_key_create(&g_keytls, NULL) ||
        pthread_key_create(&g_keytlr, NULL) || pthread_key_create(&g_keygate, NULL)) { fprintf(stderr, "[dm] FATAL cannot create the host key\n"); abort(); }
    flockfile(stderr); fprintf(stderr, "[dm] key numbers K18=%lu KTLS=%lu KTLR=%lu KGATE=%lu\n", g_key18, g_keytls, g_keytlr, g_keygate); funlockfile(stderr);
}
static inline uint64_t plat_slot_key_entry(unsigned slot) {
    pthread_key_t k = slot == PLAT_SLOT_K18 ? g_key18 : slot == PLAT_SLOT_KTLS ? g_keytls : slot == PLAT_SLOT_KTLR ? g_keytlr : g_keygate;
    return (uint64_t)k * 8;
}
// The pthread TSD layout is fixed, slot = tpidrro_el0 + 8 × key
static inline void plat_slot_check(uint64_t off) {
    pthread_setspecific(g_key18, (void *)0x1234);
    uint64_t base; __asm__ volatile("mrs %0, tpidrro_el0" : "=r"(base));
    if (((uint64_t *)(base & ~7ull))[off / 8] != 0x1234) { fprintf(stderr, "[dm] FATAL TSD layout mismatch\n"); abort(); }
    pthread_setspecific(g_key18, 0);
}

static inline plat_thread_t plat_thread_of(pthread_t t) { return pthread_mach_thread_np(t); }
static inline plat_thread_t plat_thread_self(void) { return mach_thread_self(); }
static inline void plat_thread_self_release(plat_thread_t t) { mach_port_deallocate(mach_task_self(), (mach_port_t)t); }
static inline void plat_thread_set_name(const char *name) { pthread_setname_np(name); }
static inline int plat_thread_suspend(plat_thread_t t) { return thread_suspend((thread_act_t)t) == KERN_SUCCESS ? 0 : -1; }
static inline int plat_thread_get_regs(plat_thread_t t, plat_regs_t *r) {
    arm_thread_state64_t st; mach_msg_type_number_t n = ARM_THREAD_STATE64_COUNT;
    if (thread_get_state((thread_act_t)t, ARM_THREAD_STATE64, (thread_state_t)&st, &n) != KERN_SUCCESS) return -1;
    for (int i = 0; i < 29; i++) r->x[i] = st.__x[i];
    r->fp = __darwin_arm_thread_state64_get_fp(st); r->lr = __darwin_arm_thread_state64_get_lr(st);
    r->sp = __darwin_arm_thread_state64_get_sp(st); r->pc = __darwin_arm_thread_state64_get_pc(st); r->cpsr = st.__cpsr;
    return 0;
}
static inline void plat_thread_resume(plat_thread_t t) { thread_resume((thread_act_t)t); }
static inline void plat_thread_stack(uint64_t *top, uint64_t *size) { *top = (uint64_t)pthread_get_stackaddr_np(pthread_self()); *size = pthread_get_stacksize_np(pthread_self()); }
static inline int plat_thread_run_state(plat_thread_t t) {
    thread_basic_info_data_t bi; mach_msg_type_number_t c = THREAD_BASIC_INFO_COUNT;
    return thread_info((thread_act_t)t, THREAD_BASIC_INFO, (thread_info_t)&bi, &c) == KERN_SUCCESS ? bi.run_state : -1;
}
static inline int64_t plat_thread_cpu_us(plat_thread_t t) {
    thread_basic_info_data_t bi; mach_msg_type_number_t c = THREAD_BASIC_INFO_COUNT;
    if (thread_info((thread_act_t)t, THREAD_BASIC_INFO, (thread_info_t)&bi, &c) != KERN_SUCCESS) return -1;
    return (int64_t)(bi.user_time.seconds + bi.system_time.seconds) * 1000000 + (bi.user_time.microseconds + bi.system_time.microseconds);
}
static inline int64_t plat_thread_self_system_ns(void) {
    thread_basic_info_data_t bi; mach_msg_type_number_t n = THREAD_BASIC_INFO_COUNT; mach_port_t me = mach_thread_self(); int64_t r = 0;
    if (thread_info(me, THREAD_BASIC_INFO, (thread_info_t)&bi, &n) == KERN_SUCCESS) r = (int64_t)bi.system_time.seconds * 1000000000ll + (int64_t)bi.system_time.microseconds * 1000ll;
    mach_port_deallocate(mach_task_self(), me); return r;
}
static inline int plat_threads(plat_thread_t **list, unsigned *n) {
    thread_act_array_t ths; mach_msg_type_number_t nth = 0;
    if (task_threads(mach_task_self(), &ths, &nth) != KERN_SUCCESS) return -1;
    plat_thread_t *l = calloc(nth ? nth : 1, sizeof *l);
    if (!l) { for (mach_msg_type_number_t i = 0; i < nth; i++) mach_port_deallocate(mach_task_self(), ths[i]); vm_deallocate(mach_task_self(), (vm_address_t)ths, nth * sizeof *ths); return -1; }
    for (mach_msg_type_number_t i = 0; i < nth; i++) l[i] = ths[i];
    vm_deallocate(mach_task_self(), (vm_address_t)ths, nth * sizeof *ths);
    *list = l; *n = nth; return 0;
}
static inline void plat_threads_release(plat_thread_t *list, unsigned n) {
    for (unsigned i = 0; i < n; i++) mach_port_deallocate(mach_task_self(), (mach_port_t)list[i]);
    free(list);
}
static inline int plat_qos_set_self(plat_qos_t q) { return pthread_set_qos_class_self_np((qos_class_t)q, 0); }
static inline plat_qos_t plat_qos_of(pthread_t t) { qos_class_t qc = QOS_CLASS_UNSPECIFIED; int rp = 0; pthread_get_qos_class_np(t, &qc, &rp); return (plat_qos_t)qc; }
static inline void *plat_qos_override_start(pthread_t t, plat_qos_t q) { return pthread_override_qos_class_start_np(t, (qos_class_t)q, 0); }
static inline void plat_qos_override_end(void *ov) { pthread_override_qos_class_end_np(ov); }

#ifndef __OBJC__   // dispatch objects are Objective-C objects under ARC: the host's Objective-C files do not use these
struct plat_sem { char unused; };
static inline plat_sem_t *plat_sem_create(long value) { return (plat_sem_t *)dispatch_semaphore_create(value); }
static inline void plat_sem_signal(plat_sem_t *s) { dispatch_semaphore_signal((dispatch_semaphore_t)s); }
static inline int plat_sem_wait_ns(plat_sem_t *s, int64_t ns) { return (int)dispatch_semaphore_wait((dispatch_semaphore_t)s, ns < 0 ? DISPATCH_TIME_FOREVER : dispatch_time(DISPATCH_TIME_NOW, ns)); }
static inline void plat_sem_destroy(plat_sem_t *s) { dispatch_release((dispatch_semaphore_t)s); }
static inline int plat_cond_timedwait_rel(pthread_cond_t *cv, pthread_mutex_t *mu, const struct timespec *rel) { return pthread_cond_timedwait_relative_np(cv, mu, rel); }

struct plat_queue { char unused; };
static inline plat_queue_t *plat_queue_create(const char *name) { return (plat_queue_t *)dispatch_queue_create(name, DISPATCH_QUEUE_SERIAL); }
static inline void plat_queue_async(plat_queue_t *q, void (*fn)(void *), void *ctx) { dispatch_async_f((dispatch_queue_t)q, ctx, fn); }
static inline void plat_nothing_(void *ctx) { (void)ctx; }
static inline void plat_queue_drain(plat_queue_t *q) { dispatch_sync_f((dispatch_queue_t)q, NULL, plat_nothing_); }
static inline void plat_after_ns(uint64_t ns, void (*fn)(void *), void *ctx) { dispatch_after_f(dispatch_time(DISPATCH_TIME_NOW, (int64_t)ns), dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ctx, fn); }
static inline void plat_on_signal(int sig, void (*fn)(void *), void *ctx) {
    signal(sig, SIG_IGN);   // do not take the default action: the GCD signal source runs fn on an ordinary thread
    dispatch_source_t src = dispatch_source_create(DISPATCH_SOURCE_TYPE_SIGNAL, (uintptr_t)sig, 0, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0));
    dispatch_set_context(src, ctx); dispatch_source_set_event_handler_f(src, fn);
    dispatch_resume(src);
}
#endif

static inline int plat_fd_path(int fd, char *out, size_t n) { char p[MAXPATHLEN] = ""; if (fcntl(fd, F_GETPATH, p) || !p[0]) return -1; snprintf(out, n, "%s", p); return 0; }
static inline int plat_clone_tree(const char *from, const char *to) { return clonefile(from, to, 0) ? -1 : 0; }
static inline int plat_rename_swap(const char *a, const char *b) { return renamex_np(a, b, RENAME_SWAP); }
static inline void plat_swap_recover(const char *b) { (void)b; }
static inline ssize_t plat_getxattr(const char *path, const char *name, void *buf, size_t n) { return getxattr(path, name, buf, n, 0, XATTR_NOFOLLOW); }
static inline int plat_setxattr(const char *path, const char *name, const void *buf, size_t n) { return setxattr(path, name, buf, n, 0, XATTR_NOFOLLOW); }
static inline int plat_removexattr(const char *path, const char *name) { return removexattr(path, name, XATTR_NOFOLLOW); }

static inline void plat_device_model(char *out, size_t n) { size_t len = n; if (sysctlbyname(TARGET_OS_OSX ? "hw.model" : "hw.machine", out, &len, NULL, 0)) out[0] = 0; }
static inline void plat_build_commit(char *out, size_t n) {   // DMCommit in the app's Info.plist; a run outside an app has none
    CFBundleRef b = CFBundleGetMainBundle(); CFTypeRef c = b ? CFBundleGetValueForInfoDictionaryKey(b, CFSTR("DMCommit")) : NULL;
    if (c && CFGetTypeID(c) == CFStringGetTypeID()) CFStringGetCString((CFStringRef)c, out, (CFIndex)n, kCFStringEncodingUTF8);
}

_Static_assert(sizeof(CC_SHA256_CTX) <= sizeof(plat_sha256_t), "plat_sha256_t holds a CC_SHA256_CTX");
static inline void plat_sha256_init(plat_sha256_t *c) { CC_SHA256_Init((CC_SHA256_CTX *)c); }
static inline void plat_sha256_update(plat_sha256_t *c, const void *p, size_t n) {
    while (n) { CC_LONG k = n > 0x40000000 ? 0x40000000 : (CC_LONG)n; CC_SHA256_Update((CC_SHA256_CTX *)c, p, k); p = (const uint8_t *)p + k; n -= k; }
}
static inline void plat_sha256_final(uint8_t out[32], plat_sha256_t *c) { CC_SHA256_Final(out, (CC_SHA256_CTX *)c); }
static inline void plat_sha256(const void *p, size_t n, uint8_t out[32]) { CC_SHA256(p, (CC_LONG)n, out); }

static inline int plat_sort_stable(void *base, size_t n, size_t size, int (*cmp)(const void *, const void *)) { return mergesort(base, n, size, cmp); }
