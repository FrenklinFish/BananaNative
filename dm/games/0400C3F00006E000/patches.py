# Mario Kart World, Switch 2 (0400C3F00006E000): the rewriter's patches of this game's own builds. dm/tools/patch_nso.py loads every profile's patches.py and applies those of a module's
# NSO build ID ("Registered patches" there). This build has no cuts; its observation gates make the 3D cameras and the 2D layer follow the picture's shape.

# 1.8.0 main: the 3D cameras and the 2D layer follow the picture's shape (Game settings > Aspect ratio; dm/host/android/mkw_wide.c, GPL-3.0-or-later). Six observation gates, the kind in the
# parameter's bits from 5 up (0 camera aspect store, register in bits 0-4; 1 draw setup; 2 text setup; 3 projection rows). The callback does nothing unless the launcher set
# DM_VK_PICTURE_ASPECT to a shape other than 16:9. Every original word is position independent and checked by the rewriter. Sites and idea: adriadam10's mkw-recomp (MIT),
# recomp/patches-mkw-180.txt `aspect` set, found again by disassembly of this build (its bl / ldp sites move to the instruction before or after, a gate cannot relocate a branch)
_MKW_WIDE = 33   # OBS_MKW_WIDE in dm/host/dm.h
OBS = {
  'd00e4f399e411d0613aa8d26b1c02fef': [
    (0x8a6d8, _MKW_WIDE | 8 << 8, 'bd00b100', 'layer camera aspect store (str s0,[x8,#0xb0], the viewport width over height); x8 the sead::PerspectiveProjection, s0 widened before the store'),
    (0x8a788, _MKW_WIDE | 11 << 8, 'bd00b160', 'the same store in the second copy of the loop (str s0,[x11,#0xb0]); x11 the projection'),
    (0x17f3c1c, _MKW_WIDE | 1 << 13, 'bd00df20', 'nn::ui2d draw constant-buffer setup, before its bl to DrawInfo\'s projection getter (main+0x17ed900); x0 pointed at a scaled copy of the 16:9 projection'),
    (0x1805f18, _MKW_WIDE | 2 << 13, 'a90d22c8', 'nn::ui2d text setup, before its bl to the same getter; x0 pointed at a scaled copy, pane in x21'),
    (0x17f5088, _MKW_WIDE | 3 << 13, '4f809027', 'view-projection product made on the CPU, after ldp q0,q4,[x21,#0x10]; q0 (x row) or q4 (y row) scaled'),
  ],
}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md)
BINDING = [
    {'what': 'camera aspect and 2D layer: the 3D cameras and the UI follow the picture shape (Game settings > Aspect ratio)', 'now': 'build', 'goal': 'game', 'purpose': 'rendering',
     'why': 'sead::PerspectiveProjection and nn::ui2d::DrawInfo are the same in every title of this engine, but the five sites are fixed addresses of this build',
     'blocked': 'one build on hand; the aspect store and the getter calls could be found by function fingerprint'},
]
