// Where the game's picture lands on the display (vka_present.c present_one), pure so tests/t_android_input.c checks it with the launcher's twin (PictureFit.rect).
// Fit (the default) keeps the crop's aspect ratio at the largest size that fits; stretch (DM_VK_STRETCH=1, Game settings › Display) fills the whole display, so a
// 16:9 picture on a 4:3 or a 20:9 screen is drawn wider or taller than it is. size_pct (DM_VK_PICTURE_SIZE, the in-game panel's Picture size: 50..100, 100 the
// default) then shrinks that rectangle about its own aspect, and the result sits at (place_x, place_y) of the free space in 1/10000 (nxhost_picture_place: 5000 centred)
#pragma once

static inline int vka_fit_size(int pct) { return pct <= 0 || pct > 100 ? 100 : pct < 50 ? 50 : pct; }

static inline void vka_fit_rect(int ew, int eh, int cw, int ch, int stretch, int size_pct, int place_x, int place_y, int r[4]) {
    if (cw <= 0) cw = 1;
    if (ch <= 0) ch = 1;
    int pct = vka_fit_size(size_pct), dw, dh;
    if (stretch) { dw = ew; dh = eh; }
    else {
        double s = (double)ew / cw < (double)eh / ch ? (double)ew / cw : (double)eh / ch;
        dw = (int)(cw * s + 0.5); dh = (int)(ch * s + 0.5);
        if (dw > ew) dw = ew;
        if (dh > eh) dh = eh;
    }
    if (pct < 100) { dw = (int)((long)dw * pct / 100); dh = (int)((long)dh * pct / 100); }
    if (dw < 1) dw = 1;
    if (dh < 1) dh = 1;
    r[0] = (int)((long)(ew - dw) * place_x / 10000); r[1] = (int)((long)(eh - dh) * place_y / 10000); r[2] = dw; r[3] = dh;
}
