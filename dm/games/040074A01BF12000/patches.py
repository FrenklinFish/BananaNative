# Donkey Kong Bananza's own code, keyed by the NSO build ID (the rewriter writes these into the pack's main).
CUTS = {
    # v458752: the game draws its scene at 80 % of the window and scales the rest up itself. At a scene's start it sets its frame buffer scale to a base (1.0)
    # times a per-mode table entry (0.8 here), and the render targets and the upscaler's target are the window times that scale (main+0x790b0). With the two
    # products replaced by the base alone, the scene is drawn at the window's full size, so the Android launcher's Dynamic resolution "Off" is the native size
    # and its percentages are of the window (probe.c drs_install steers the range below that).
    'e25c90c1c136638885659fd55bf8a03b': [
        (0x30f8f4, '2008201e', '2040201e', 'frame buffer scale: fmul s0, s1, s0 → fmov s0, s1 (the base, 1.0, instead of base × 0.8)'),
        (0x30f904, '0008211e', '1f2003d5', 'frame buffer scale: the second fmul → nop (the height keeps the base too)'),
    ],
}

# Observation gates. v458752: sead's frame-rate controller's sdiv of 60 by its interval (sdiv w8, w8, w21; the controller in x20), where the host makes the rate the
# measured one (hle_fpslock.c sead_rate; gate OBS_SEAD_RATE = 27 with w8, w21 and x20 in its upper bits, nso_code.py sead_param; the profile opts in with fps_decouple sead).
# The rewriter finds the same place by the controller's code (nso_code.py sead_find), and in any other build (the base game v0: main+0x1eadf0, not a profile build, so no
# row here); this row names it for v458752 whatever the search finds (an override, and a fallback should it stop finding it)
sead_why = "sead's frame-rate controller: 60 / interval (w8 / w21, the controller in x20); the host makes it the measured rate (hle_fpslock.c sead_rate)"
# v458752 main: the 3D cameras and the 2D layer follow the picture's shape (Game settings > Aspect ratio / Screen shape; dm/host/android/dkb_wide.c, GPL-3.0-or-later). Three observation
# gates, the site kind in the gate number's parameter from bit 5 up (0 camera: the register in bits 0-4, 1 HUD). The camera gate is on the projection update of the scene camera's updater (x19:
# near +0xf8, far +0xfc, aspect +0x100, fovy in degrees +0x104), before its two reads of the fovy and the aspect; the callback rewrites them in the updater (aspect; for a narrower picture also
# the fovy, so the horizontal reach stays). The two HUD gates are the instruction after the matrix copy main+0xb1314 in the two layout projection setups (main+0x45f840 and main+0x803b60, the same
# code twice; the HUD's is the second): the layouts are drawn in perspective (fovy 15 degrees, aspect 1920 / 1080, the camera 4101.7 units from the layout plane); the copy in the draw info (x0, the matrix at +0x10) has its x row (wider picture) or
# y row (narrower) squeezed so the layer stays in the middle 16:9. Every original word is position independent and checked by the rewriter (the calls themselves are not, so the gates sit after them;
# x0 is still the draw info there: the copy uses x8 and x9 only). The callback does nothing unless the launcher set DM_VK_PICTURE_ASPECT to a shape other than 16:9. Sites and idea:
# Lucas-blip-png's dkb-pc-recomp, recomp/patches-dkb458752.txt (set dkbwide, GPL-3.0), same build; the camera site is not theirs (they patch the updater's constructor, main+0x462504, and the
# aspect setter, main+0x45d5d4, and widen the virtual display; we keep the frame and read the values where used)
_DKB_WIDE = 32   # OBS_DKB_WIDE in dm/host/dm.h
_dkb_hud = 32 | 1 << 13
OBS = {
    'e25c90c1c136638885659fd55bf8a03b': [   # v458752
        (0x204b20, 27 | (8 | 21 << 5 | 20 << 10) << 8, '1ad50d08', sead_why),
        (0x45e238, _DKB_WIDE | 19 << 8, 'bd410660', 'scene camera updater projection update, before it reads the fovy (ldr s0,[x19,#0x104]); x19 the updater (aspect +0x100, fovy degrees +0x104)'),
        (0x45f9e0, _dkb_hud, 'bd400e80', 'layout system projection setup main+0x45f840, after its copy main+0xb1314 (ldr s0,[x20,#0xc]); x0 the draw info, the perspective matrix at +0x10'),
        (0x803c9c, _dkb_hud, '910003e8', 'the second layout projection setup main+0x803b60 (the scene\'s layouts: HUD), after its copy main+0xb1314 (mov x8,sp; the adrp before it is not relocatable); x0 the draw info'),
    ],
}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md)
BINDING = [
    {'what': "frame buffer scale: the scene at the window's full size instead of 80 %", 'now': 'build', 'goal': 'game', 'purpose': 'rendering',
     'why': "the game's own per-mode table entry, multiplied in at two fixed offsets; a function fingerprint of the scene setup (the two fmul after the table load) could carry it over the game's updates"},
    {'what': "sead's frame-rate controller's place for v458752, named whatever the search finds", 'now': 'build', 'goal': 'build', 'purpose': 'frame rate',
     'why': "an override and fallback of the rewriter's search (nso_code.py sead_find), which finds the same place in every build of this game by the controller's code",
     'checked': 'v0 and v458752 by disassembly, 2026-11-10: the search finds main+0x1eadf0 and main+0x204b20, nso_fnmap.py maps one to the other exactly'},
    {'what': 'camera aspect and 2D layer: the 3D camera follows the picture shape and the layout perspective stays in the middle 16:9 (Game settings > Aspect ratio / Screen shape)', 'now': 'build', 'goal': 'game', 'purpose': 'rendering',
     'why': "sead::PerspectiveProjection and the draw info's projection copy are engine code, but the three gate sites (the scene camera updater's projection update and the two layout projection setups after their copies) are fixed addresses of this build",
     'blocked': 'one build on hand; the projection update and the matrix copy are plain code a function fingerprint could find',
     'checked': 'v458752 by disassembly, 2026-11-13: every original word read from the main image; the prior art\'s sites (dkbwide set) match the same words; the layout uniform block seen on the phone (DM_VK_UB_PEEK)'},
]
