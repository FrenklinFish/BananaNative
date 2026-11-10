// Shared-font ownership and LoadFont forwarding through the real forced registration; no game module or font file is needed.
#include "../hle_sdk16.c"
void dm_applet_enter(const char *name) { (void)name; }   // hle_oe.c: the shop stand-ins hold the game out of focus
void dm_applet_leave(void) {}
#include <mach-o/getsect.h>
#include <sys/wait.h>
#include <sys/resource.h>

static int checks, fails, calls, lookups, missing;
static void *seen_self;
static char *seen_name;
static const void *seen_data;
static unsigned seen_index, seen_capacity;
static uint32_t status;
#define CHECK(c, msg) do { checks++; if (!(c)) { fails++; fprintf(stderr, "FAIL: %s\n", msg); } } while (0)
static uint32_t original(void *self, char *name, const void *data, unsigned index, unsigned capacity) {
    calls++; seen_self = self; seen_name = name; seen_data = data;
    seen_index = index; seen_capacity = capacity;
    return status;
}
void *guest_sym(const char *name) {
    lookups++;
    CHECK(!strcmp(name, "_ZN2nn6fontll18ScalableFontEngine8LoadFontEPcPKvjj"), "original symbol lookup");
    return missing ? NULL : original;
}
module_t *g_mod;
int g_nmod;
uint64_t g_t0_ns;
uint64_t now_ns(void) { return 0; }
int dm_sdk_major(void) { return 20; }
int dm_main_imports(const char *prefix) { (void)prefix; return 1; }
module_t *module_of(const void *p) { (void)p; return NULL; }
void dm_backtrace(uint64_t fp) { (void)fp; }
void hle_bind_system_event(void *ev, int autoclear) { (void)ev; (void)autoclear; }
void hle_signal_system_event(void *ev) { (void)ev; }
uint64_t hle_system_event_binding(void *ev) { (void)ev; return 1; }
int hle_signal_system_event_binding(uint64_t binding) { (void)binding; return 1; }
uint32_t hle_hid_connected_npad(void) { return 0; }
void dm_host_helper_qos(qos_class_t q) { (void)q; }
extern const struct mach_header_64 _mh_execute_header;
int main(void) {
    unsigned long bytes = 0;
    const hle_force_ent *e = (const hle_force_ent *)getsectiondata(&_mh_execute_header, "__DATA", "swforce", &bytes);
    uint32_t (*load)(void *, char *, const void *, unsigned, unsigned) = NULL;
    unsigned count = 0;
    for (size_t i = 0; e && i < bytes / sizeof *e; i++) {
        if (strcmp(e[i].name, "_ZN2nn6fontll18ScalableFontEngine8LoadFontEPcPKvjj")) continue;
        load = e[i].fn; count++;
        CHECK(e[i].why && e[i].why[0], "takeover has a reason");
    }
    CHECK(count == 1 && load, "exact LoadFont forced registration exists once");
    if (!load) return 1;
    // A missing SDK implementation must fail loudly rather than fabricate a successful font load.
    pid_t child = fork();
    CHECK(child >= 0, "missing-original child starts");
    if (!child) { struct rlimit r = {0, 0}; setrlimit(RLIMIT_CORE, &r); missing = 1; load(NULL, NULL, NULL, 0, 0); _exit(1); }
    if (child > 0) { int s; waitpid(child, &s, 0); CHECK(WIFSIGNALED(s) && WTERMSIG(s) == SIGABRT, "missing original aborts"); }
    uint8_t standard[64] = {0}, extension[64] = {0}, unrelated[64] = {0};
    uint32_t marker = 0x18029a7fu;
    memcpy(standard, &marker, 4); memcpy(extension, &marker, 4); memcpy(unrelated, &marker, 4);
    char name[50] = {0}; int self;
    const void *before[] = {NULL, unrelated, standard, extension};
    for (unsigned i = 0; i < sizeof before / sizeof *before; i++) {
        status = 403; int n = calls;
        CHECK(load(&self, name, before[i], 17, sizeof name) == status, "uninitialized fonts preserve result");
        CHECK(calls == n + 1 && seen_data == before[i], "uninitialized fonts leave all data unchanged");
    }
    std_f = (font_t){standard, sizeof standard}; ext_f = (font_t){extension, sizeof extension};
    const void *input[] = {standard, extension, standard + 8, extension + 8, standard + 1, extension + 9, unrelated, NULL};
    const void *expect[] = {standard + 8, extension + 8, standard + 8, extension + 8, standard + 1, extension + 9, unrelated, NULL};
    const uint32_t results[] = {0, 403, 301, 0x80000001u, ~0u};
    for (unsigned r = 0; r < sizeof results / sizeof *results; r++) for (unsigned i = 0; i < sizeof input / sizeof *input; i++) {
        int n = calls; status = results[r];
        CHECK(load(&self, name, input[i], ~0u, sizeof name) == status, "SDK status preserved");
        CHECK(calls == n + 1 && seen_data == expect[i], "only exact host bases skip the header, once");
        CHECK(seen_self == &self && seen_name == name && seen_index == ~0u && seen_capacity == sizeof name, "all other arguments preserved");
    }
    ext_f = std_f;
    CHECK(load(&self, name, standard, 0, 0) == status && seen_data == standard + 8, "extension fallback to standard");
    CHECK(seen_index == 0 && seen_capacity == 0, "zero unsigned arguments preserved");
    CHECK(lookups == 1, "original resolved once");
    CHECK(!memcmp(standard, unrelated, sizeof standard) && !memcmp(extension, unrelated, sizeof extension), "input bytes unchanged");
    // SDK 21's built-in font record (Splatoon Raiders): filled from the standard font, past its header, and reported loaded
    pl_builtin_font_t bf; memset(&bf, 0xee, sizeof bf);
    unsigned got = h_pl_BuiltInOunce(&bf, 0, 0); font_t f0 = font_get(0);
    CHECK(got == 1 && f0.p && bf.address == f0.p + font_skip() && bf.size == f0.n - font_skip() && bf.type == 0, "built-in font record filled");
    // With neither the switch nor the folder set, the system's own font (SYSTEM_FONT: Arial Unicode on the Mac, Roboto on Android) is the standard font
    char tmp[] = "/tmp/t_shared_font_XXXXXX"; int fd = mkstemp(tmp);
    CHECK(fd >= 0 && write(fd, "\0\1\0\0ttf", 7) == 7, "stand-in system font written"); if (fd >= 0) close(fd);
    unsetenv("T_SHARED_FONT_UNSET"); unsetenv("DM_FONT_DIR");
    font_t sys = font_first("T_SHARED_FONT_UNSET", "standard.ttf", tmp);
    CHECK(sys.p && sys.n == 7 + 8 && !memcmp(sys.p + 8, "\0\1\0\0ttf", 7), "the system font is taken when nothing else is set");
    CHECK(!font_variable(&sys), "a font without tables is not variable");
    // A list of system fonts: the first that opens (Android: RobotoStatic before the variable Roboto)
    char list[2048]; snprintf(list, sizeof list, "/nonexistent/a.ttf:%s:/nonexistent/b.ttf", tmp);
    font_t l = font_first("T_SHARED_FONT_UNSET", "standard.ttf", list);
    CHECK(l.p && l.n == 7 + 8, "the first font of the list that opens is taken");
    unlink(tmp);
    // An sfnt header with an fvar table record is a variable font
    uint8_t vf[8 + 12 + 32] = {0}; memcpy(vf + 8, "\0\1\0\0\0\2", 6); memcpy(vf + 8 + 12, "glyf", 4); memcpy(vf + 8 + 28, "fvar", 4);
    font_t v = {vf, sizeof vf}; CHECK(font_variable(&v), "an fvar table makes a variable font");
    for (const char *p = SYSTEM_FONT; p; p = strchr(p, ':') ? strchr(p, ':') + 1 : NULL) CHECK(!strncmp(p + strcspn(p, ":") - 4, ".ttf", 4), "each system font is one TTF, not a collection");
    printf("shared font: %d checks, %d failed\n", checks, fails);
    return fails != 0;
}
