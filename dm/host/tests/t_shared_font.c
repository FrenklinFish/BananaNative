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

// A minimal sfnt of n tables for the checks: head, cmap, glyf, loca, maxp (the bits of mask, in that order) each 8 bytes long after the directory; with in = 0 the last table's offset lies outside the file
static size_t mk_sfnt(uint8_t *o, const char *tag, unsigned mask, int in) {
    static const char *names[] = {"head", "cmap", "glyf", "loca", "maxp"};
    unsigned k = 0; for (int i = 0; i < 5; i++) if (mask >> i & 1) k++;
    size_t dir = 12 + 16 * (size_t)k, total = dir + 8 * (size_t)k; memset(o, 0, total);
    memcpy(o, tag, 4); o[4] = 0; o[5] = (uint8_t)k;
    unsigned j = 0;
    for (int i = 0; i < 5; i++) if (mask >> i & 1) {
        uint8_t *r = o + 12 + 16 * (size_t)j; uint32_t off = (uint32_t)(dir + 8 * (size_t)j) + (!in && j == k - 1 ? 1000 : 0);
        memcpy(r, names[i], 4); r[8] = (uint8_t)(off >> 24); r[9] = (uint8_t)(off >> 16); r[10] = (uint8_t)(off >> 8); r[11] = (uint8_t)off; r[15] = 8; j++;
    }
    return total;
}
static int put(const char *path, const void *d, size_t n) { FILE *f = fopen(path, "wb"); if (!f) return 0; int ok = fwrite(d, 1, n, f) == n; fclose(f); return ok; }
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
    type_f[0] = (font_t){standard, sizeof standard}; type_f[5] = (font_t){extension, sizeof extension};
    memset(type_done, 1, sizeof type_done);
    const void *input[] = {standard, extension, standard + 8, extension + 8, standard + 1, extension + 9, unrelated, NULL};
    const void *expect[] = {standard + 8, extension + 8, standard + 8, extension + 8, standard + 1, extension + 9, unrelated, NULL};
    const uint32_t results[] = {0, 403, 301, 0x80000001u, ~0u};
    for (unsigned r = 0; r < sizeof results / sizeof *results; r++) for (unsigned i = 0; i < sizeof input / sizeof *input; i++) {
        int n = calls; status = results[r];
        CHECK(load(&self, name, input[i], ~0u, sizeof name) == status, "SDK status preserved");
        CHECK(calls == n + 1 && seen_data == expect[i], "only exact host bases skip the header, once");
        CHECK(seen_self == &self && seen_name == name && seen_index == ~0u && seen_capacity == sizeof name, "all other arguments preserved");
    }
    type_f[5] = type_f[0];
    CHECK(load(&self, name, standard, 0, 0) == status && seen_data == standard + 8, "extension fallback to standard");
    CHECK(seen_index == 0 && seen_capacity == 0, "zero unsigned arguments preserved");
    CHECK(lookups == 1, "original resolved once");
    CHECK(!memcmp(standard, unrelated, sizeof standard) && !memcmp(extension, unrelated, sizeof extension), "input bytes unchanged");
    // SDK 21's built-in font record (Splatoon Raiders): filled from the standard font, past its header, and reported loaded
    pl_builtin_font_t bf; memset(&bf, 0xee, sizeof bf);
    unsigned got = h_pl_BuiltInOunce(&bf, 0, 0); font_t f0 = font_get(0);
    CHECK(got == 1 && f0.p && bf.address == f0.p + font_skip() && bf.size == f0.n - font_skip() && bf.type == 0, "built-in font record filled");
    // Real selection from here on: forget the planted fonts
    memset(type_f, 0, sizeof type_f); memset(type_done, 0, sizeof type_done);
    char dir[] = "/tmp/t_shared_font_XXXXXX"; CHECK(mkdtemp(dir) != NULL, "temp folder");
    unsetenv("T_SHARED_FONT_UNSET"); unsetenv("DM_FONT_DIR"); unsetenv("DM_SHARED_FONT"); unsetenv("DM_SHARED_FONT_EXT");
    // With neither the switch nor the folder set, the system's own font (SYSTEM_FONT: Arial Unicode on the Mac, Roboto on Android) is the standard font
    char tmp[300]; snprintf(tmp, sizeof tmp, "%s/system.ttf", dir);
    uint8_t sf[512]; size_t sn = mk_sfnt(sf, "\0\1\0\0", 31, 1);
    CHECK(put(tmp, sf, sn), "stand-in system font written");
    font_t sys = font_first("T_SHARED_FONT_UNSET", "standard.ttf", tmp);
    CHECK(sys.p && sys.n == sn + 8 && !memcmp(sys.p + 8, sf, sn), "the system font is taken when nothing else is set");
    CHECK(!font_variable(&sys), "a font without an fvar table is not variable");
    // A list of system fonts: the first that opens (Android: RobotoStatic before the variable Roboto)
    char list[2048]; snprintf(list, sizeof list, "/nonexistent/a.ttf:%s:/nonexistent/b.ttf", tmp);
    font_t l = font_first("T_SHARED_FONT_UNSET", "standard.ttf", list);
    CHECK(l.p && l.n == sn + 8, "the first font of the list that opens is taken");
    // An sfnt header with an fvar table record is a variable font
    uint8_t vf[8 + 12 + 32] = {0}; memcpy(vf + 8, "\0\1\0\0\0\2", 6); memcpy(vf + 8 + 12, "glyf", 4); memcpy(vf + 8 + 28, "fvar", 4);
    font_t v = {vf, sizeof vf}; CHECK(font_variable(&v), "an fvar table makes a variable font");
    for (const char *p = SYSTEM_FONT; p; p = strchr(p, ':') ? strchr(p, ':') + 1 : NULL) CHECK(!strncmp(p + strcspn(p, ":") - 4, ".ttf", 4), "each system font is one TTF, not a collection");
    // What a usable font is
    uint8_t bad[512]; font_t b;
    size_t bn = mk_sfnt(bad + 8, "\0\1\0\0", 31, 1); b = (font_t){bad, bn + 8}; CHECK(font_unusable(&b) == NULL, "a plain TrueType font is usable");
    bn = mk_sfnt(bad + 8, "true", 31, 1); b = (font_t){bad, bn + 8}; CHECK(font_unusable(&b) == NULL, "an Apple 'true' font is usable");
    bn = mk_sfnt(bad + 8, "ttcf", 31, 1); b = (font_t){bad, bn + 8}; CHECK(font_unusable(&b) != NULL, "a font collection is not used");
    bn = mk_sfnt(bad + 8, "OTTO", 31, 1); b = (font_t){bad, bn + 8}; CHECK(font_unusable(&b) != NULL, "CFF outlines are not used");
    bn = mk_sfnt(bad + 8, "\0\1\0\0", 31 & ~4, 1); b = (font_t){bad, bn + 8}; CHECK(font_unusable(&b) != NULL, "no glyf table: not used");
    bn = mk_sfnt(bad + 8, "\0\1\0\0", 31, 0); b = (font_t){bad, bn + 8}; CHECK(font_unusable(&b) != NULL, "a table outside the file: not used");
    bn = mk_sfnt(bad + 8, "\0\1\0\0", 31, 1); b = (font_t){bad, 8 + 10}; CHECK(font_unusable(&b) != NULL, "shorter than a header: not used");
    // The order of choice, per type. Folder with the standard font and a Korean one only
    uint8_t std_b[512], kr_b[512], sc_b[512], ext_b[512], tc_b[512];
    size_t std_n = mk_sfnt(std_b, "\0\1\0\0", 31, 1), kr_n = mk_sfnt(kr_b, "\0\1\0\0", 31, 1), sc_n = mk_sfnt(sc_b, "\0\1\0\0", 31, 1), ext_n = mk_sfnt(ext_b, "\0\1\0\0", 31, 1);
    std_b[100] = 1; kr_b[100] = 2; sc_b[100] = 3; ext_b[100] = 4;   // different content (inside a table's data)
    char path[400];
#define IN(name) (snprintf(path, sizeof path, "%s/%s", dir, name), path)
    CHECK(put(IN("standard.ttf"), std_b, std_n) && put(IN("cjk_kr.ttf"), kr_b, kr_n), "font folder written");
    setenv("DM_FONT_DIR", dir, 1);
    f0 = font_get(0); font_t f4 = font_get(4);
    CHECK(f0.p && f0.n == std_n + 8 && !memcmp(f0.p + 8, std_b, std_n), "type 0: the folder's standard.ttf before the system font");
    CHECK(f4.p && f4.n == kr_n + 8 && !memcmp(f4.p + 8, kr_b, kr_n), "type 4: the folder's cjk_kr.ttf");
    CHECK(font_get(1).p == f0.p && font_get(2).p == f0.p && font_get(3).p == f0.p, "types 1-3: no CJK font of their own: the standard font");
    CHECK(font_get(5).p == f0.p, "type 5: no nintendo_ext.ttf: the standard font");
    CHECK(font_get(9).p == f0.p && font_get(-1).p == f0.p, "an unknown type is the standard font");
    // More files in the folder (a new process: nothing is cached)
    memset(type_f, 0, sizeof type_f); memset(type_done, 0, sizeof type_done);
    CHECK(put(IN("cjk_sc.ttf"), sc_b, sc_n) && put(IN("nintendo_ext.ttf"), ext_b, ext_n), "more fonts written");
    size_t tc_n = mk_sfnt(tc_b, "OTTO", 31, 1); CHECK(put(IN("cjk_tc.ttf"), tc_b, tc_n), "a CFF cjk_tc.ttf written");
    f0 = font_get(0);
    CHECK(font_get(1).p && font_get(1).p == font_get(2).p && font_get(1).p != f0.p && !memcmp(font_get(1).p + 8, sc_b, sc_n), "types 1 and 2 share cjk_sc.ttf (one mapping)");
    CHECK(font_get(3).p == f0.p, "type 3: a CFF cjk_tc.ttf is refused, the standard font is served");
    CHECK(font_get(5).p && font_get(5).p != f0.p && !memcmp(font_get(5).p + 8, ext_b, ext_n), "type 5: the folder's nintendo_ext.ttf");
    // The layout the game reads: marker, obfuscated length, TTF as is
    font_t k = font_get(4); uint32_t mk, ln; memcpy(&mk, k.p, 4); memcpy(&ln, k.p + 4, 4);
    CHECK(mk == 0x18029a7fu && (__builtin_bswap32(ln) ^ 0x49621806u) == kr_n && k.n == kr_n + 8, "BFTTF header: decoded marker and the length of the TTF");
    CHECK(h_pl_Addr(4) == k.p + font_skip() && h_pl_Size(4) == k.n - font_skip(), "address and size follow the profile's header form");
    CHECK(h_pl_Addr(4) == k.p && h_pl_Size(4) == k.n, "(SDK 20 with Decode: the header is kept)");
    int cnt = 0; pl_font_data_t all[8]; memset(all, 0, sizeof all);
    CHECK(h_pl_GetSharedFont(&cnt, all, 8, 0) && cnt == 6, "GetSharedFont: six fonts");
    CHECK(all[4].type == 4 && all[4].address == k.p + 8 && all[4].size == kr_n && all[5].address == font_get(5).p + 8 && all[0].address == f0.p + 8 && all[0].size == std_n, "GetSharedFont: each type's TTF past the header");
    // The switches win. DM_SHARED_FONT: one font for types 0-4 whatever the folder holds; DM_SHARED_FONT_EXT for type 5
    memset(type_f, 0, sizeof type_f); memset(type_done, 0, sizeof type_done);
    setenv("DM_SHARED_FONT", IN("cjk_sc.ttf"), 1);
    f0 = font_get(0);
    CHECK(f0.p && !memcmp(f0.p + 8, sc_b, sc_n) && font_get(4).p == f0.p && font_get(3).p == f0.p && font_get(1).p == f0.p, "DM_SHARED_FONT is the font of types 0-4");
    CHECK(font_get(5).p && !memcmp(font_get(5).p + 8, ext_b, ext_n), "type 5 stays the extension font");
    setenv("DM_SHARED_FONT_EXT", IN("cjk_kr.ttf"), 1); memset(type_f, 0, sizeof type_f); memset(type_done, 0, sizeof type_done);
    CHECK(font_get(5).p && !memcmp(font_get(5).p + 8, kr_b, kr_n), "DM_SHARED_FONT_EXT is the font of type 5");
    // Nothing usable anywhere: no font, no crash
    unsetenv("DM_SHARED_FONT"); unsetenv("DM_SHARED_FONT_EXT"); setenv("DM_FONT_DIR", "/nonexistent", 1);
    memset(type_f, 0, sizeof type_f); memset(type_done, 0, sizeof type_done);
    if (!strstr(SYSTEM_FONT, "nonexistent") && access(SYSTEM_FONT, R_OK)) CHECK(font_get(0).p == NULL && font_get(3).p == NULL && h_pl_Size(1) == 0, "no font found: null, size 0");
#undef IN
    char rm[600]; snprintf(rm, sizeof rm, "rm -rf '%s'", dir); if (system(rm)) {}
    printf("shared font: %d checks, %d failed\n", checks, fails);
    return fails != 0;
}
