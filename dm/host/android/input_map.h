// Android input → the host's pad, touch and motion state, the pure part (no Android or JNI headers, no host state), so tests/t_android_input.c checks it on the Mac.
// input_android.c feeds it from the launcher's events and publishes the results through dm_input.h / front.h.
#pragma once
#include <stdint.h>
#include "../dm_input.h"

// Android's own numbers (android/keycodes.h, android/input.h), repeated so the Mac build needs no NDK
enum {
    AK_BACK = 4, AK_DPAD_UP = 19, AK_DPAD_DOWN = 20, AK_DPAD_LEFT = 21, AK_DPAD_RIGHT = 22, AK_DPAD_CENTER = 23,
    AK_BUTTON_A = 96, AK_BUTTON_B = 97, AK_BUTTON_C = 98, AK_BUTTON_X = 99, AK_BUTTON_Y = 100, AK_BUTTON_Z = 101,
    AK_BUTTON_L1 = 102, AK_BUTTON_R1 = 103, AK_BUTTON_L2 = 104, AK_BUTTON_R2 = 105, AK_BUTTON_THUMBL = 106, AK_BUTTON_THUMBR = 107,
    AK_BUTTON_START = 108, AK_BUTTON_SELECT = 109, AK_BUTTON_MODE = 110,
};
enum {
    AA_X = 0, AA_Y = 1, AA_Z = 11, AA_RX = 12, AA_RY = 13, AA_RZ = 14, AA_HAT_X = 15, AA_HAT_Y = 16,
    AA_LTRIGGER = 17, AA_RTRIGGER = 18, AA_GAS = 22, AA_BRAKE = 23,
};
enum { AM_DOWN = 0, AM_UP = 1, AM_MOVE = 2, AM_CANCEL = 3, AM_POINTER_DOWN = 5, AM_POINTER_UP = 6 };   // MotionEvent actions (masked)

// ---------- Gamepads ----------
// Face buttons: Android names them by position as on an Xbox pad (BUTTON_A bottom, B right, X left, Y top), the Switch's layout is right A, bottom B, top X, left Y,
// so by default a pad maps by position (pad_apple.h does the same). labelled = 1 maps name for name instead, for a pad whose driver reports Nintendo's labels
uint64_t nxin_key_button(int keycode, int labelled);   // DM_BTN_* bit, 0 when the key is not a pad button
typedef struct {
    uint64_t keys;                 // buttons held from key events
    float lx, ly, rx, ry;          // sticks as Android reports them (-1..1, y down)
    float hat_x, hat_y, lt, rt;    // D-pad hat (-1, 0, 1) and the triggers (0..1)
    int lt_on, rt_on;              // the triggers as ZL / ZR, with hysteresis
} nxin_pad;
int nxin_pad_key(nxin_pad *p, int keycode, int down, int labelled);   // 1 when the key is a pad button (the launcher then consumes the event)
int nxin_pad_axis(nxin_pad *p, int axis, float v);                     // 1 when the axis is used
// One MotionEvent's axes (only those the device has: InputDevice.getMotionRange). A device that has Z / RZ has its right stick there (Android's key layouts put it there,
// with the triggers on LTRIGGER / RTRIGGER), and its RX / RY are left out, so a pad that also reports RX / RY as idle or as triggers does not pull the stick back. Returns 1 when any is used
int nxin_pad_axes(nxin_pad *p, const int *axes, const float *values, int n);
// Buttons and sticks for dm_pad_set_player: stick y up, ±DM_STICK_MAX, a radial dead zone (0..0.9) rescaled to the full range
void nxin_pad_out(nxin_pad *p, float deadzone, uint64_t *buttons, int32_t *lx, int32_t *ly, int32_t *rx, int32_t *ry);
void nxin_stick(float x, float y, float deadzone, int32_t *ox, int32_t *oy);   // one stick, Android axes in (y down), Switch axes out (y up)

// ---------- On-screen controls ----------
// A data model the launcher draws: each control has a centre in normalized screen coordinates (0..1, origin top left) and a radius in units of the screen height,
// so circles stay round on any aspect ratio. Touches that land on a control drive it; every other touch inside the game's picture is a touchscreen finger
enum { NXT_BUTTON = 0, NXT_STICK = 1, NXT_DPAD = 2 };
#define NXT_MAX 24
typedef struct { int kind; uint64_t buttons; int stick; float x, y, r; char label[8]; } nxhost_touch_control;   // stick: 0 left, 1 right (NXT_STICK only)
typedef struct { int id, ctl, slot; float x, y; } nxin_pointer;   // ctl: control index, or -1 for a touchscreen finger in slot (dm_touch id), -2 for a touch outside both
typedef struct {
    nxhost_touch_control c[NXT_MAX]; int n;
    int enabled;                    // 0: the controls are hidden and every touch goes to the game's touchscreen
    float sw, sh;                   // screen size in pixels (aspect for hit tests; any unit)
    float gx, gy, gw, gh;           // the game's picture inside the screen, normalized
    nxin_pointer p[16]; int np;
    uint64_t pressed[NXT_MAX];      // what each control holds now (buttons), for the launcher's drawing
    float sx[2], sy[2];             // stick deflection, -1..1, y up
} nxin_touch;
void nxin_touch_init(nxin_touch *t);              // the default layout, enabled, 16:9 screen, picture full screen
void nxin_touch_default_layout(nxin_touch *t);
// A layout of the launcher's own (it draws the controls): n controls (at most NXT_MAX), or the default layout when n <= 0. Pointers on the old layout are let go
// (bit 1 / 2 of the return say whether the pad / touchscreen changed, as nxin_touch_event); *kept is the number of controls now
int nxin_touch_set_layout(nxin_touch *t, const nxhost_touch_control *c, int n, int *kept);
// One pointer event: action is MotionEvent's (masked), id its pointer id, x / y normalized screen coordinates. Returns bit 1 when the pad state changed, bit 2 when the touchscreen did
int nxin_touch_event(nxin_touch *t, int action, int id, float x, float y);
void nxin_touch_pad(const nxin_touch *t, uint64_t *buttons, int32_t *lx, int32_t *ly, int32_t *rx, int32_t *ry);
typedef struct { uint32_t id; float x, y; } nxin_finger;   // the same layout as front.h's dm_touch
int nxin_touch_fingers(const nxin_touch *t, nxin_finger *out, int max);   // touchscreen fingers in the picture's normalized coordinates

// ---------- Motion ----------
// Android sensors: x to the right of the natural (portrait) screen, y to its top, z out of it, as CoreMotion; acceleration in m/s² as the specific force (+9.81 on z lying face up),
// rotation rate in rad/s. Out: the Switch frame for the display rotation (Surface.ROTATION_0..3), acceleration in G as gravity's direction, as dm_motion_set_switch takes them
void nxin_motion_to_switch(int rotation, const float acc_ms2[3], const float gyro[3], float acc_g[3], float rot[3]);
// Shaking the phone (DM_BTN_SHAKE), as the iOS front end detects it: gravity followed by a slow low-pass (about 0.8 s), a sample whose acceleration beyond it exceeds thr G is a hit,
// shaking lasts until 120 ms after the last hit. Returns 1 while shaking
typedef struct { float g[3], thr; int init; uint64_t last_ns, hit_ns; } nxin_shake;
int nxin_shake_feed(nxin_shake *s, const float acc_g[3], uint64_t t_ns);
