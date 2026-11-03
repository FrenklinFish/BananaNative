// The Android host's pure input part (android/input_map.c), on the Mac: gamepad key and axis mapping, the on-screen controls and touchscreen routing, the sensor frame, shakes
#include "android/input_map.c"
#include <stdio.h>

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static void t_keys(void) {
    // by position: Android's bottom / right / left / top are the Switch's B / A / Y / X
    CHECK(nxin_key_button(AK_BUTTON_A, 0) == DM_BTN_B && nxin_key_button(AK_BUTTON_B, 0) == DM_BTN_A, "A / B by position");
    CHECK(nxin_key_button(AK_BUTTON_X, 0) == DM_BTN_Y && nxin_key_button(AK_BUTTON_Y, 0) == DM_BTN_X, "X / Y by position");
    CHECK(nxin_key_button(AK_BUTTON_A, 1) == DM_BTN_A && nxin_key_button(AK_BUTTON_Y, 1) == DM_BTN_Y, "by label");
    static const struct { int k; uint64_t b; } m[] = {
        {AK_BUTTON_L1, DM_BTN_L}, {AK_BUTTON_R1, DM_BTN_R}, {AK_BUTTON_L2, DM_BTN_ZL}, {AK_BUTTON_R2, DM_BTN_ZR}, {AK_BUTTON_THUMBL, DM_BTN_LS}, {AK_BUTTON_THUMBR, DM_BTN_RS},
        {AK_BUTTON_START, DM_BTN_PLUS}, {AK_BUTTON_SELECT, DM_BTN_MINUS}, {AK_DPAD_UP, DM_BTN_UP}, {AK_DPAD_DOWN, DM_BTN_DOWN}, {AK_DPAD_LEFT, DM_BTN_LEFT}, {AK_DPAD_RIGHT, DM_BTN_RIGHT},
        {AK_BUTTON_MODE, 0}, {AK_BACK, 0}, {AK_BUTTON_C, 0}, {29 /* KEYCODE_A, a keyboard letter */, 0},
    };
    for (size_t i = 0; i < sizeof m / sizeof *m; i++) CHECK(nxin_key_button(m[i].k, 0) == m[i].b, "keycode %d → %#llx", m[i].k, (unsigned long long)nxin_key_button(m[i].k, 0));
    // every button bit is reached exactly once
    uint64_t all = 0; for (int k = 0; k < 300; k++) { uint64_t b = nxin_key_button(k, 0); CHECK(!(all & b), "bit %#llx twice", (unsigned long long)b); all |= b; }
    CHECK(all == 0xffff, "all sixteen buttons: %#llx", (unsigned long long)all);
}
static void t_pad(void) {
    nxin_pad p = {0}; uint64_t b; int32_t lx, ly, rx, ry;
    nxin_pad_key(&p, AK_BUTTON_B, 1, 0); nxin_pad_key(&p, AK_BUTTON_R1, 1, 0);
    nxin_pad_axis(&p, AA_X, 1); nxin_pad_axis(&p, AA_Y, -1);   // left stick right and up (Android y is down)
    nxin_pad_axis(&p, AA_Z, -0.5f); nxin_pad_axis(&p, AA_RZ, 0.05f);
    nxin_pad_out(&p, 0.1f, &b, &lx, &ly, &rx, &ry);
    CHECK(b == (DM_BTN_A | DM_BTN_R), "buttons %#llx", (unsigned long long)b);
    CHECK(lx == 23170 && ly == 23170, "a diagonal at full tilt stays on the circle: %d %d", lx, ly);
    CHECK(rx < 0 && rx > -DM_STICK_MAX / 2 && ry < 0, "right stick rescaled past the dead zone (y down on Android is y down here too): %d %d", rx, ry);
    nxin_pad_axis(&p, AA_Z, 0.05f); nxin_pad_axis(&p, AA_RZ, -0.05f); nxin_pad_out(&p, 0.1f, &b, &lx, &ly, &rx, &ry);
    CHECK(rx == 0 && ry == 0, "inside the dead zone: %d %d", rx, ry);
    nxin_pad_axis(&p, AA_X, 1); nxin_pad_axis(&p, AA_Y, 0); nxin_pad_out(&p, 0.1f, &b, &lx, &ly, &rx, &ry);
    CHECK(lx == DM_STICK_MAX && ly == 0, "full right: %d %d", lx, ly);
    nxin_pad_key(&p, AK_BUTTON_B, 0, 0);
    // triggers: on above 0.5, off below 0.4; the gas / brake axes count too
    nxin_pad_axis(&p, AA_LTRIGGER, 0.45f); nxin_pad_out(&p, 0.1f, &b, &lx, &ly, &rx, &ry); CHECK(!(b & DM_BTN_ZL), "ZL off at 0.45");
    nxin_pad_axis(&p, AA_LTRIGGER, 0.6f); nxin_pad_out(&p, 0.1f, &b, &lx, &ly, &rx, &ry); CHECK(b & DM_BTN_ZL, "ZL on at 0.6");
    nxin_pad_axis(&p, AA_LTRIGGER, 0.45f); nxin_pad_out(&p, 0.1f, &b, &lx, &ly, &rx, &ry); CHECK(b & DM_BTN_ZL, "ZL holds at 0.45");
    nxin_pad_axis(&p, AA_LTRIGGER, 0.3f); nxin_pad_out(&p, 0.1f, &b, &lx, &ly, &rx, &ry); CHECK(!(b & DM_BTN_ZL), "ZL off at 0.3");
    nxin_pad_axis(&p, AA_GAS, 1); nxin_pad_out(&p, 0.1f, &b, &lx, &ly, &rx, &ry); CHECK(b & DM_BTN_ZR, "gas is ZR");
    // the hat is the D-pad
    nxin_pad_axis(&p, AA_HAT_X, -1); nxin_pad_axis(&p, AA_HAT_Y, 1); nxin_pad_out(&p, 0.1f, &b, &lx, &ly, &rx, &ry);
    CHECK((b & (DM_BTN_LEFT | DM_BTN_DOWN | DM_BTN_UP | DM_BTN_RIGHT)) == (DM_BTN_LEFT | DM_BTN_DOWN), "hat left + down: %#llx", (unsigned long long)b);
    CHECK(!nxin_pad_axis(&p, 32 /* AXIS_GENERIC_1 */, 1), "an unknown axis is not used");
    {   // a batch with Z / RZ keeps RX / RY off the right stick; without Z / RZ, RX / RY are the right stick
        nxin_pad q = {0}; int ax[4] = {AA_Z, AA_RZ, AA_RX, AA_RY}; float v[4] = {0.9f, -0.9f, 0, 0};
        CHECK(nxin_pad_axes(&q, ax, v, 4) && q.rx == 0.9f && q.ry == -0.9f, "Z / RZ win: %f %f", q.rx, q.ry);
        float v2[2] = {0.3f, 0.4f};
        CHECK(nxin_pad_axes(&q, ax + 2, v2, 2) && q.rx == 0.3f && q.ry == 0.4f, "RX / RY alone: %f %f", q.rx, q.ry);
        int bad[1] = {40}; CHECK(!nxin_pad_axes(&q, bad, v, 1), "no used axis");
    }
    CHECK(nxin_pad_axis(&p, AA_X, 0.0f / 0.0f) && p.lx == 0, "NaN reads 0");
}
static int ctl(const nxin_touch *t, uint64_t b) { for (int i = 0; i < t->n; i++) if (t->c[i].kind == NXT_BUTTON && t->c[i].buttons == b) return i; return -1; }
static int stick(const nxin_touch *t, int s) { for (int i = 0; i < t->n; i++) if (t->c[i].kind == NXT_STICK && t->c[i].stick == s) return i; return -1; }
static void t_touch(void) {
    nxin_touch t; nxin_touch_init(&t);
    uint64_t b; int32_t lx, ly, rx, ry; nxin_finger f[16];
    // the default layout has every button once, inside the screen, and no two controls overlap
    uint64_t all = 0;
    for (int i = 0; i < t.n; i++) {
        const nxhost_touch_control *c = &t.c[i]; all |= c->buttons;
        CHECK(c->x > 0 && c->x < 1 && c->y > 0 && c->y < 1 && c->r > 0, "control %d inside the screen", i);
        for (int j = 0; j < i; j++) { float dx, dy, d = dist(&t, &t.c[j], c->x, c->y, &dx, &dy); CHECK(d >= c->r + t.c[j].r - 1e-4f, "controls %s and %s overlap (%.3f < %.3f)", c->label, t.c[j].label, d, c->r + t.c[j].r); }
    }
    CHECK((all & 0xffff) == (0xffff & ~(DM_BTN_LS | DM_BTN_RS)), "every button but the stick clicks: %#llx", (unsigned long long)all);
    CHECK(stick(&t, 0) >= 0 && stick(&t, 1) >= 0, "two sticks");
    // press A, hold the left stick to the right, touch the picture with a third finger
    const nxhost_touch_control *A = &t.c[ctl(&t, DM_BTN_A)], *L = &t.c[stick(&t, 0)];
    CHECK(nxin_touch_event(&t, AM_DOWN, 7, A->x, A->y) == 1, "A down drives the pad");
    float a = t.sw / t.sh;
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 3, L->x + L->r * 0.3f / a, L->y) == 1, "stick grabbed");
    nxin_touch_event(&t, AM_MOVE, 3, L->x + L->r * 2 / a, L->y);   // dragged past the ring: full tilt
    nxin_touch_pad(&t, &b, &lx, &ly, &rx, &ry);
    CHECK(b == DM_BTN_A && lx == DM_STICK_MAX && ly == 0 && rx == 0, "A + stick right: %#llx %d %d", (unsigned long long)b, lx, ly);
    nxin_touch_event(&t, AM_MOVE, 3, L->x, L->y - L->r * 0.4f);   // up, half of full tilt (full at 0.8 r)
    nxin_touch_pad(&t, &b, &lx, &ly, &rx, &ry);
    CHECK(lx == 0 && ly > DM_STICK_MAX * 0.45 && ly < DM_STICK_MAX * 0.55, "stick up half: %d %d", lx, ly);
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 9, 0.5f, 0.45f) == 2, "a touch in the open is the touchscreen");
    int n = nxin_touch_fingers(&t, f, 16);
    CHECK(n == 1 && f[0].id == 0 && fabsf(f[0].x - 0.5f) < 1e-5f && fabsf(f[0].y - 0.45f) < 1e-5f, "one finger in slot 0: %d", n);
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 11, 0.55f, 0.5f) == 2 && nxin_touch_fingers(&t, f, 16) == 2 && f[1].id == 1, "the second finger takes slot 1");
    nxin_touch_event(&t, AM_POINTER_UP, 9, 0.5f, 0.45f);
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 12, 0.4f, 0.5f) == 2 && nxin_touch_fingers(&t, f, 16) == 2 && (f[0].id == 0 || f[1].id == 0), "a freed slot is reused");
    nxin_touch_event(&t, AM_POINTER_UP, 7, A->x, A->y);
    nxin_touch_pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == 0 && ly > 0, "A released, the stick still held");
    nxin_touch_event(&t, AM_CANCEL, 0, 0, 0);
    nxin_touch_pad(&t, &b, &lx, &ly, &rx, &ry);
    CHECK(b == 0 && lx == 0 && ly == 0 && nxin_touch_fingers(&t, f, 16) == 0 && t.np == 0, "cancel lets everything go");
    // the D-pad by direction, with a dead spot in its centre
    int di = -1; for (int i = 0; i < t.n; i++) if (t.c[i].kind == NXT_DPAD) di = i;
    const nxhost_touch_control *D = &t.c[di];
    nxin_touch_event(&t, AM_DOWN, 1, D->x, D->y); nxin_touch_pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == 0, "D-pad centre is nothing");
    nxin_touch_event(&t, AM_MOVE, 1, D->x + D->r * 0.7f / a, D->y + D->r * 0.7f); nxin_touch_pad(&t, &b, &lx, &ly, &rx, &ry);
    CHECK(b == (DM_BTN_RIGHT | DM_BTN_DOWN), "D-pad down-right: %#llx", (unsigned long long)b);
    nxin_touch_event(&t, AM_MOVE, 1, D->x, D->y - D->r * 0.8f); nxin_touch_pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == DM_BTN_UP, "slides to up: %#llx", (unsigned long long)b);
    nxin_touch_event(&t, AM_UP, 1, 0, 0);
    // a letterboxed picture: touches map into it, touches in the bars outside any control are dropped
    t.gx = 0.125f; t.gw = 0.75f;
    nxin_touch_event(&t, AM_DOWN, 2, 0.5f, 0.3f); n = nxin_touch_fingers(&t, f, 16);
    CHECK(n == 1 && fabsf(f[0].x - 0.5f) < 1e-5f, "picture coordinates: %.3f", n ? f[0].x : -1);
    nxin_touch_event(&t, AM_MOVE, 2, 0.05f, 0.3f); nxin_touch_fingers(&t, f, 16); CHECK(f[0].x == 0, "a finger that slides off stays on the edge");
    nxin_touch_event(&t, AM_UP, 2, 0, 0);
    CHECK(nxin_touch_event(&t, AM_DOWN, 4, 0.06f, 0.35f) == 0 && nxin_touch_fingers(&t, f, 16) == 0, "a touch in the bar is dropped");
    nxin_touch_event(&t, AM_UP, 4, 0, 0);
    // controls off: a touch on A is a finger
    t.enabled = 0; t.gx = 0; t.gw = 1;
    CHECK(nxin_touch_event(&t, AM_DOWN, 5, A->x, A->y) == 2, "controls off: touchscreen only");
    nxin_touch_event(&t, AM_UP, 5, 0, 0);
}
// The launcher's own layout: its controls drive the pad, a stick click is a button, a held touch is let go when the layout changes, n <= 0 brings the default back
static void t_layout(void) {
    nxin_touch t; nxin_touch_init(&t); uint64_t b; int32_t lx, ly, rx, ry; int kept = -1;
    CHECK(nxin_touch_event(&t, AM_DOWN, 1, t.c[ctl(&t, DM_BTN_A)].x, t.c[ctl(&t, DM_BTN_A)].y) == 1, "A held on the default layout");
    nxhost_touch_control c[3] = {{NXT_STICK, 0, 1, 0.9f, 0.8f, 0.1f, "R"}, {NXT_BUTTON, DM_BTN_RS, 0, 0.8f, 0.6f, 0.04f, "R3"}, {NXT_BUTTON, DM_BTN_B, 7, 0.1f, 0.1f, 0.05f, "B"}};
    CHECK(nxin_touch_set_layout(&t, c, 3, &kept) == 1 && kept == 3 && t.np == 0, "the held A is let go: %d", kept);
    nxin_touch_pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == 0, "nothing held after the change");
    CHECK(t.c[2].stick == 0, "a button's stick index is normalised");
    CHECK(nxin_touch_event(&t, AM_DOWN, 2, 0.8f, 0.6f) == 1, "R3 pressed");
    nxin_touch_event(&t, AM_POINTER_DOWN, 3, 0.9f, 0.8f - 0.1f);   // right stick, pushed up past full tilt
    nxin_touch_pad(&t, &b, &lx, &ly, &rx, &ry);
    CHECK(b == DM_BTN_RS && rx == 0 && ry == DM_STICK_MAX && lx == 0, "R3 + right stick up: %#llx %d %d", (unsigned long long)b, rx, ry);
    CHECK(nxin_touch_event(&t, AM_POINTER_DOWN, 4, 0.5f, 0.5f) == 2, "the open picture is still the touchscreen");
    CHECK(nxin_touch_set_layout(&t, NULL, 0, &kept) == 3 && kept == 13 && ctl(&t, DM_BTN_A) >= 0, "the default layout back: %d", kept);
    nxin_touch_pad(&t, &b, &lx, &ly, &rx, &ry); CHECK(b == 0 && rx == 0 && ry == 0, "and nothing held");
}
static void t_motion(void) {
    float acc[3], rot[3];
    // lying face up: +9.81 m/s² on z is gravity (0, 0, -1) in the Switch frame
    nxin_motion_to_switch(0, (float[]){0, 0, 9.80665f}, (float[]){0, 0, 0}, acc, rot);
    CHECK(fabsf(acc[2] + 1) < 1e-5f && fabsf(acc[0]) < 1e-6f, "face up: %f %f %f", acc[0], acc[1], acc[2]);
    // ROTATION_90: landscape with the phone's top to the left; held upright, gravity points to the phone's -x, which is the picture's down (-y)... the phone's +x is the picture's up
    nxin_motion_to_switch(1, (float[]){9.80665f, 0, 0}, (float[]){0, 0.5f, 0}, acc, rot);
    CHECK(fabsf(acc[1] + 1) < 1e-5f && fabsf(acc[0]) < 1e-6f, "ROTATION_90 upright: %f %f %f", acc[0], acc[1], acc[2]);
    CHECK(fabsf(rot[0] + 0.5f) < 1e-6f, "ROTATION_90: the phone's y turn is the picture's -x: %f %f %f", rot[0], rot[1], rot[2]);
    nxin_motion_to_switch(3, (float[]){-9.80665f, 0, 0}, (float[]){0, 0, 0}, acc, rot);
    CHECK(fabsf(acc[1] + 1) < 1e-5f, "ROTATION_270 upright: %f %f %f", acc[0], acc[1], acc[2]);
    nxin_motion_to_switch(2, (float[]){0, -9.80665f, 0}, (float[]){0, 0, 0}, acc, rot);
    CHECK(fabsf(acc[1] + 1) < 1e-5f, "ROTATION_180 upright: %f %f %f", acc[0], acc[1], acc[2]);
}
static void t_shake(void) {
    nxin_shake s = {.thr = 1.5f}; uint64_t t = 1000000000ull; int on = 0;
    for (int i = 0; i < 100; i++, t += 10000000) on |= nxin_shake_feed(&s, (float[]){0, 0, -1}, t);
    CHECK(!on, "still: no shake");
    CHECK(nxin_shake_feed(&s, (float[]){2.5f, 0, -1}, t), "a 2.5 G jolt is a shake"); t += 10000000;
    CHECK(nxin_shake_feed(&s, (float[]){0, 0, -1}, t + 50000000), "still shaking 60 ms later");
    CHECK(!nxin_shake_feed(&s, (float[]){0, 0, -1}, t + 200000000), "over 120 ms after the last hit");
    CHECK(!nxin_shake_feed(&s, (float[]){0.8f, 0, -1}, t + 210000000), "0.8 G is no shake");
}
int main(void) {
    t_keys(); t_pad(); t_touch(); t_layout(); t_motion(); t_shake();
    if (fails) { printf("t_android_input: %d failures\n", fails); return 1; }
    printf("t_android_input: ok\n");
    return 0;
}
