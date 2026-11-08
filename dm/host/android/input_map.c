// Android input → pad / touch / motion state, the pure part (input_map.h)
#include "input_map.h"
#include "../motion_fusion.h"
#include <math.h>
#include <string.h>

uint64_t nxin_key_button(int k, int labelled) {
    switch (k) {
    case AK_BUTTON_A: return labelled ? DM_BTN_A : DM_BTN_B;   // bottom
    case AK_BUTTON_B: return labelled ? DM_BTN_B : DM_BTN_A;   // right
    case AK_BUTTON_X: return labelled == 1 ? DM_BTN_X : DM_BTN_Y;   // left (labelled 2: A and B by label, X and Y by position)
    case AK_BUTTON_Y: return labelled == 1 ? DM_BTN_Y : DM_BTN_X;   // top
    case AK_BUTTON_L1: return DM_BTN_L;
    case AK_BUTTON_R1: return DM_BTN_R;
    case AK_BUTTON_L2: return DM_BTN_ZL;
    case AK_BUTTON_R2: return DM_BTN_ZR;
    case AK_BUTTON_THUMBL: return DM_BTN_LS;
    case AK_BUTTON_THUMBR: return DM_BTN_RS;
    case AK_BUTTON_START: return DM_BTN_PLUS;
    case AK_BUTTON_SELECT: return DM_BTN_MINUS;
    case AK_DPAD_UP: return DM_BTN_UP;
    case AK_DPAD_DOWN: return DM_BTN_DOWN;
    case AK_DPAD_LEFT: return DM_BTN_LEFT;
    case AK_DPAD_RIGHT: return DM_BTN_RIGHT;
    default: return 0;   // BUTTON_MODE (Home), C, Z, BACK: nothing on the Switch side; left to the launcher
    }
}
const char *const nxin_src_names[NXIN_SRC_N] = {
    "BUTTON_A", "BUTTON_B", "BUTTON_X", "BUTTON_Y", "BUTTON_L1", "BUTTON_R1", "BUTTON_L2", "BUTTON_R2", "BUTTON_THUMBL", "BUTTON_THUMBR",
    "BUTTON_START", "BUTTON_SELECT", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "BUTTON_C", "BUTTON_Z",
};
static const int src_keys[NXIN_SRC_N] = {
    AK_BUTTON_A, AK_BUTTON_B, AK_BUTTON_X, AK_BUTTON_Y, AK_BUTTON_L1, AK_BUTTON_R1, AK_BUTTON_L2, AK_BUTTON_R2, AK_BUTTON_THUMBL, AK_BUTTON_THUMBR,
    AK_BUTTON_START, AK_BUTTON_SELECT, AK_DPAD_UP, AK_DPAD_DOWN, AK_DPAD_LEFT, AK_DPAD_RIGHT, AK_BUTTON_C, AK_BUTTON_Z,
};
enum { SRC_L2 = 6, SRC_R2 = 7, SRC_UP = 12, SRC_DOWN = 13, SRC_LEFT = 14, SRC_RIGHT = 15 };
const char *const nxin_switch_names[16] = {"A", "B", "X", "Y", "LS", "RS", "L", "R", "ZL", "ZR", "PLUS", "MINUS", "LEFT", "UP", "RIGHT", "DOWN"};
int nxin_src_of_key(int k) { for (int i = 0; i < NXIN_SRC_N; i++) if (src_keys[i] == k) return i; return -1; }
static int name_is(const char *a, size_t n, const char *b) { return strlen(b) == n && !strncmp(a, b, n); }
int nxin_map_parse(nxin_map *m, const char *spec, char *bad, size_t nbad) {
    memset(m, 0, sizeof *m);
    if (bad && nbad) *bad = 0;
    int took = 0;
    for (const char *s = spec ? spec : ""; *s;) {
        while (*s == ',' || *s == ' ') s++;
        if (!*s) break;
        const char *e = s; while (*e && *e != ',') e++;
        const char *eq = memchr(s, '=', (size_t)(e - s));
        int src = -1, to = -2;
        if (eq) {
            for (int i = 0; i < NXIN_SRC_N; i++) if (name_is(s, (size_t)(eq - s), nxin_src_names[i])) src = i;
            if (name_is(eq + 1, (size_t)(e - eq - 1), "NONE")) to = -1;
            for (int i = 0; i < 16; i++) if (name_is(eq + 1, (size_t)(e - eq - 1), nxin_switch_names[i])) to = i;
        }
        if (src >= 0 && to >= -1) { m->to[src] = to < 0 ? 0 : 1ull << to; m->set |= 1u << src; took++; }
        else if (bad && nbad && !*bad) { size_t n = (size_t)(e - s) < nbad - 1 ? (size_t)(e - s) : nbad - 1; memcpy(bad, s, n); bad[n] = 0; }
        s = e;
    }
    return took;
}
static uint64_t mapped(const nxin_map *m, int src, uint64_t def) { return m && src >= 0 && (m->set & (1u << src)) ? m->to[src] : def; }
uint64_t nxin_pad_button(const nxin_pad *p, int k, int labelled) { return mapped(p->map, nxin_src_of_key(k), nxin_key_button(k, labelled)); }
int nxin_pad_key(nxin_pad *p, int k, int down, int labelled) {
    int src = nxin_src_of_key(k);
    uint64_t b = nxin_pad_button(p, k, labelled);
    if (!b) return p->map && src >= 0 && (p->map->set & (1u << src));   // a source mapped to NONE is still the pad's: it does nothing, and the launcher does not act on it
    if (down) p->keys |= b; else p->keys &= ~b;
    return 1;
}
static float clamp1(float v) { return !(v == v) ? 0 : v > 1 ? 1 : v < -1 ? -1 : v; }
int nxin_pad_axis(nxin_pad *p, int axis, float v) {
    v = clamp1(v);
    switch (axis) {
    case AA_X: p->lx = v; return 1;
    case AA_Y: p->ly = v; return 1;
    case AA_Z: case AA_RX: p->rx = v; return 1;    // Android's key layouts put the right stick on Z / RZ; generic HID pads report RX / RY
    case AA_RZ: case AA_RY: p->ry = v; return 1;
    case AA_HAT_X: p->hat_x = v; return 1;
    case AA_HAT_Y: p->hat_y = v; return 1;
    case AA_LTRIGGER: case AA_BRAKE: p->lt = v < 0 ? 0 : v; return 1;
    case AA_RTRIGGER: case AA_GAS: p->rt = v < 0 ? 0 : v; return 1;
    default: return 0;
    }
}
int nxin_pad_axes(nxin_pad *p, const int *axes, const float *v, int n) {
    int z = 0, used = 0;
    for (int i = 0; i < n; i++) z |= axes[i] == AA_Z || axes[i] == AA_RZ;
    for (int i = 0; i < n; i++) if (!(z && (axes[i] == AA_RX || axes[i] == AA_RY))) used |= nxin_pad_axis(p, axes[i], v[i]);
    return used;
}
void nxin_stick(float x, float y, float dz, int32_t *ox, int32_t *oy) {
    if (!(dz >= 0)) dz = 0; if (dz > 0.9f) dz = 0.9f;
    x = clamp1(x); y = clamp1(y);
    float m = sqrtf(x * x + y * y);
    if (m <= dz || m == 0) { *ox = *oy = 0; return; }
    float s = (m - dz) / (1 - dz) / m; if (m * s > 1) s = 1 / m;   // rescaled radius, at most full tilt
    *ox = (int32_t)lrintf(x * s * DM_STICK_MAX); *oy = (int32_t)lrintf(-y * s * DM_STICK_MAX);
}
void nxin_pad_out(nxin_pad *p, float dz, uint64_t *buttons, int32_t *lx, int32_t *ly, int32_t *rx, int32_t *ry) {
    p->lt_on = p->lt_on ? p->lt > 0.4f : p->lt > 0.5f;   // the trigger hysteresis is state
    p->rt_on = p->rt_on ? p->rt > 0.4f : p->rt > 0.5f;
    const nxin_map *m = p->map;   // the analog triggers are BUTTON_L2 / R2 and the hat DPAD_* for the map
    uint64_t b = p->keys | (p->lt_on ? mapped(m, SRC_L2, DM_BTN_ZL) : 0) | (p->rt_on ? mapped(m, SRC_R2, DM_BTN_ZR) : 0);
    if (p->hat_x < -0.5f) b |= mapped(m, SRC_LEFT, DM_BTN_LEFT); if (p->hat_x > 0.5f) b |= mapped(m, SRC_RIGHT, DM_BTN_RIGHT);
    if (p->hat_y < -0.5f) b |= mapped(m, SRC_UP, DM_BTN_UP); if (p->hat_y > 0.5f) b |= mapped(m, SRC_DOWN, DM_BTN_DOWN);
    *buttons = b;
    nxin_stick(p->lx, p->ly, dz, lx, ly); nxin_stick(p->rx, p->ry, dz, rx, ry);
}

// ---------- On-screen controls ----------
#define STICK_GRAB 1.4f   // a touch this close to a stick's centre (times its radius) takes it
#define STICK_FULL 0.8f   // full tilt at this fraction of the radius
#define STICK_DEAD 0.1f
#define DPAD_GRAB 1.3f
static void add(nxin_touch *t, int kind, uint64_t b, int stick, float x, float y, float r, const char *label) {
    if (t->n >= NXT_MAX) return;
    nxhost_touch_control *c = &t->c[t->n++];
    memset(c, 0, sizeof *c); c->kind = kind; c->buttons = b; c->stick = stick; c->x = x; c->y = y; c->r = r;
    strncpy(c->label, label, sizeof c->label - 1);
}
// Landscape, laid out as a Switch in handheld: left stick high and D-pad low on the left, the face buttons high and the right stick low on the right, shoulders in the top corners
void nxin_touch_default_layout(nxin_touch *t) {
    t->n = 0;
    add(t, NXT_STICK, 0, 0, 0.13f, 0.60f, 0.15f, "L");
    add(t, NXT_DPAD, DM_BTN_UP | DM_BTN_DOWN | DM_BTN_LEFT | DM_BTN_RIGHT, 0, 0.28f, 0.83f, 0.11f, "+");
    add(t, NXT_BUTTON, DM_BTN_A, 0, 0.935f, 0.55f, 0.065f, "A");
    add(t, NXT_BUTTON, DM_BTN_B, 0, 0.87f, 0.69f, 0.065f, "B");
    add(t, NXT_BUTTON, DM_BTN_X, 0, 0.87f, 0.41f, 0.065f, "X");
    add(t, NXT_BUTTON, DM_BTN_Y, 0, 0.805f, 0.55f, 0.065f, "Y");
    add(t, NXT_STICK, 0, 1, 0.72f, 0.83f, 0.11f, "R");
    add(t, NXT_BUTTON, DM_BTN_ZL, 0, 0.05f, 0.10f, 0.065f, "ZL");
    add(t, NXT_BUTTON, DM_BTN_L, 0, 0.14f, 0.12f, 0.065f, "L");
    add(t, NXT_BUTTON, DM_BTN_R, 0, 0.86f, 0.12f, 0.065f, "R");
    add(t, NXT_BUTTON, DM_BTN_ZR, 0, 0.95f, 0.10f, 0.065f, "ZR");
    add(t, NXT_BUTTON, DM_BTN_MINUS, 0, 0.40f, 0.08f, 0.045f, "-");
    add(t, NXT_BUTTON, DM_BTN_PLUS, 0, 0.60f, 0.08f, 0.045f, "+");
}
static void release(nxin_touch *t, int ctl);
int nxin_touch_set_layout(nxin_touch *t, const nxhost_touch_control *c, int n, int *kept) {
    int r = 0; for (int i = 0; i < t->np; i++) r |= t->p[i].ctl >= 0 ? 1 : t->p[i].ctl == -1 ? 2 : 0, release(t, t->p[i].ctl);
    t->np = 0;
    if (!c || n <= 0) nxin_touch_default_layout(t);
    else {
        t->n = n < NXT_MAX ? n : NXT_MAX;
        for (int i = 0; i < t->n; i++) { t->c[i] = c[i]; t->c[i].label[sizeof t->c[i].label - 1] = 0; if (t->c[i].stick != 1) t->c[i].stick = 0; }
    }
    memset(t->pressed, 0, sizeof t->pressed); t->sx[0] = t->sy[0] = t->sx[1] = t->sy[1] = 0;
    if (kept) *kept = t->n;
    return r;
}
void nxin_touch_init(nxin_touch *t) {
    memset(t, 0, sizeof *t);
    t->enabled = 1; t->sw = 16; t->sh = 9; t->gw = t->gh = 1;
    nxin_touch_default_layout(t);
}
// Distance from a control's centre in units of the screen height
static float dist(const nxin_touch *t, const nxhost_touch_control *c, float x, float y, float *dx, float *dy) {
    float a = t->sh > 0 ? t->sw / t->sh : 1;
    *dx = (x - c->x) * a; *dy = y - c->y;
    return sqrtf(*dx * *dx + *dy * *dy);
}
static int hit(const nxin_touch *t, float x, float y) {
    int best = -1; float bd = 0;
    for (int i = 0; i < t->n; i++) {
        const nxhost_touch_control *c = &t->c[i]; float dx, dy, d = dist(t, c, x, y, &dx, &dy);
        float grab = c->kind == NXT_STICK ? STICK_GRAB : c->kind == NXT_DPAD ? DPAD_GRAB : 1;
        if (d <= c->r * grab && (best < 0 || d / c->r < bd)) { best = i; bd = d / c->r; }
    }
    return best;
}
static uint64_t dpad_dir(float dx, float dy, float r) {   // 8 directions, a centre dead spot
    if (sqrtf(dx * dx + dy * dy) < r * 0.2f) return 0;
    uint64_t b = 0; float k = 0.4142f;   // tan 22.5°
    if (dx > fabsf(dy) * k) b |= DM_BTN_RIGHT; if (-dx > fabsf(dy) * k) b |= DM_BTN_LEFT;
    if (dy > fabsf(dx) * k) b |= DM_BTN_DOWN; if (-dy > fabsf(dx) * k) b |= DM_BTN_UP;
    return b;
}
static void drive(nxin_touch *t, nxin_pointer *p) {   // a pointer on a control: what it presses or how far it tilts
    nxhost_touch_control *c = &t->c[p->ctl]; float dx, dy; dist(t, c, p->x, p->y, &dx, &dy);
    if (c->kind == NXT_BUTTON) t->pressed[p->ctl] = c->buttons;
    else if (c->kind == NXT_DPAD) t->pressed[p->ctl] = dpad_dir(dx, dy, c->r);
    else {
        float sx = dx / (c->r * STICK_FULL), sy = -dy / (c->r * STICK_FULL), m = sqrtf(sx * sx + sy * sy);
        if (m > 1) { sx /= m; sy /= m; }
        if (m < STICK_DEAD) sx = sy = 0;
        t->sx[c->stick] = sx; t->sy[c->stick] = sy;
    }
}
static void release(nxin_touch *t, int ctl) {
    if (ctl < 0) return;
    t->pressed[ctl] = 0;
    if (t->c[ctl].kind == NXT_STICK) t->sx[t->c[ctl].stick] = t->sy[t->c[ctl].stick] = 0;
}
static int in_game(const nxin_touch *t, float x, float y) { return x >= t->gx && y >= t->gy && x < t->gx + t->gw && y < t->gy + t->gh; }
int nxin_touch_event(nxin_touch *t, int action, int id, float x, float y) {
    int k = -1; for (int i = 0; i < t->np; i++) if (t->p[i].id == id) k = i;
    if (action == AM_CANCEL) {   // the whole gesture is gone
        int r = 0; for (int i = 0; i < t->np; i++) r |= t->p[i].ctl >= 0 ? 1 : t->p[i].ctl == -1 ? 2 : 0, release(t, t->p[i].ctl);
        t->np = 0; return r;
    }
    if (action == AM_DOWN || action == AM_POINTER_DOWN) {
        if (k >= 0 || t->np >= 16) return 0;
        nxin_pointer *p = &t->p[t->np++]; p->id = id; p->x = x; p->y = y; p->slot = -1;
        p->ctl = t->enabled ? hit(t, x, y) : -1;
        if (p->ctl >= 0) { for (int i = 0; i < t->np - 1; i++) if (t->p[i].ctl == p->ctl) { p->ctl = -2; return 0; } drive(t, p); return 1; }   // a control has one pointer
        if (!in_game(t, x, y)) { p->ctl = -2; return 0; }
        p->ctl = -1;
        uint32_t used = 0; for (int i = 0; i < t->np - 1; i++) if (t->p[i].ctl == -1) used |= 1u << t->p[i].slot;
        for (int s = 0; s < 16; s++) if (!(used & 1u << s)) { p->slot = s; break; }   // the lowest free finger slot
        return 2;
    }
    if (k < 0) return 0;
    nxin_pointer *p = &t->p[k]; p->x = x; p->y = y;
    int r = p->ctl >= 0 ? 1 : p->ctl == -1 ? 2 : 0;
    if (action == AM_MOVE) { if (p->ctl >= 0) drive(t, p); return r; }
    if (action == AM_UP || action == AM_POINTER_UP) { release(t, p->ctl); t->p[k] = t->p[--t->np]; return r; }
    return 0;
}
static int32_t axis(float v) { return (int32_t)lrintf(v * DM_STICK_MAX); }
void nxin_touch_pad(const nxin_touch *t, uint64_t *buttons, int32_t *lx, int32_t *ly, int32_t *rx, int32_t *ry) {
    uint64_t b = 0; for (int i = 0; i < t->n; i++) b |= t->pressed[i];
    *buttons = b; *lx = axis(t->sx[0]); *ly = axis(t->sy[0]); *rx = axis(t->sx[1]); *ry = axis(t->sy[1]);
}
int nxin_touch_fingers(const nxin_touch *t, nxin_finger *out, int max) {
    int n = 0;
    for (int i = 0; i < t->np && n < max; i++) {
        const nxin_pointer *p = &t->p[i]; if (p->ctl != -1) continue;
        float fx = (p->x - t->gx) / (t->gw > 0 ? t->gw : 1), fy = (p->y - t->gy) / (t->gh > 0 ? t->gh : 1);   // a finger that slides off the picture stays on its edge
        fx = fx < 0 ? 0 : fx > 0.9999f ? 0.9999f : fx; fy = fy < 0 ? 0 : fy > 0.9999f ? 0.9999f : fy;
        out[n++] = (nxin_finger){(uint32_t)p->slot, fx, fy};
    }
    return n;
}

// ---------- Motion ----------
void nxin_motion_to_switch(int rotation, const float a[3], const float w[3], float acc[3], float rot[3]) {
    static const int orient[4] = {1, 3, 2, 4};   // Surface.ROTATION_0 / 90 (the phone's top to the left) / 180 / 270 → motion_phone_to_switch's UIInterfaceOrientation
    float g[3] = {-a[0] / 9.80665f, -a[1] / 9.80665f, -a[2] / 9.80665f};   // specific force → gravity's direction
    int o = orient[rotation & 3];
    motion_phone_to_switch(o, g, acc); motion_phone_to_switch(o, w, rot);
}
int nxin_shake_feed(nxin_shake *s, const float a[3], uint64_t t) {
    if (!s->init) { memcpy(s->g, a, sizeof s->g); s->init = 1; s->last_ns = t; }
    float dt = (float)((double)(t - s->last_ns) / 1e9); s->last_ns = t;
    float k = dt > 0 ? dt / 0.8f : 0; if (k > 1) k = 1;
    float dx = a[0] - s->g[0], dy = a[1] - s->g[1], dz = a[2] - s->g[2];
    for (int i = 0; i < 3; i++) s->g[i] += (a[i] - s->g[i]) * k;
    if (sqrtf(dx * dx + dy * dy + dz * dz) > (s->thr > 0 ? s->thr : 1.5f)) s->hit_ns = t ? t : 1;
    return s->hit_ns && t - s->hit_ns < 120000000ull;
}
