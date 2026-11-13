# The Legend of Zelda: Ocarina of Time, Switch 2 (0400486024DEC000): the rewriter's patches of this game's own builds. dm/tools/patch_nso.py loads every profile's patches.py and applies
# those of a module's NSO build ID; CUTS, OBS and FRAME_STEP as described there ("Registered patches"). Patches of Nintendo SDK module builds are in dm/tools/sdk_patches.py
CUTS = {
  # v131072 main: the movie player's video enhancement. Creating a movie player (main+0x3b5f500), the game takes the enhanced player (0x2f0 bytes, main+0x3b62cd0, then the NDVE setup
  # main+0x3b62ea0) when its NDVE manager is ready ([x0+0x460] set and its +0x10 not 0), else the plain one (0x1c0 bytes, main+0x3b619e0). The enhanced player runs an Ampere tensor network
  # (Movie/Ndve/Ndve-Nvn2-vid_seq5.dat: 14 compute dispatches a movie frame, 465 HMMA and 1,005 subgroup shuffles a thread in its largest) that took 47.5 ms of GPU time a frame on an
  # M-series Mac, against a 16.7 ms frame: the movies ran at 20 fps. A workaround for that platform limit (the HMMA chains are open in docs/reference/shaders.md, "Ampere HMMA through
  # simdgroup_matrix"): the branch always takes the plain player, which shows the decoded 1920x1080 picture as it is
  'e0f3ccfaf50065ae4ea01eb5aeb13b1a': [
    (0x3b5f524, '28010034', '09000014', 'movie player: cbz w8 → b main+0x3b5f548 (the plain player, without the NDVE tensor network)'),
  ],
}

# v131072 main: the 3D cameras and the 2D layer follow the picture's shape (Game settings > Aspect ratio; dm/host/android/oot_wide.c, GPL-3.0-or-later). Eight observation gates (five camera sites, then the HUD, movie and save picture sites), each on an
# instruction that reads a sead::PerspectiveProjection's half-angle tangent (+0xac) and aspect (+0xb0) or has just written them, the gate number's upper bits naming the register that
# holds the projection. The callback rewrites the projection in place (aspect; for a narrower picture also fovy, sin, cos and tan, so the horizontal reach stays) before the read, and
# does nothing unless the launcher set DM_VK_PICTURE_ASPECT to a shape other than 16:9. Every original word is position independent and checked by the rewriter. Offsets and the
# idea: Hookshot nx2/oot_wide.py (GPL-3.0, NotRexed), after NX2navi patches-v111.txt (Ivan0xFF, MIT) and Jbuck7's GameBanana mod 737495; same game build (main e0f3ccfa...)
_OOT_WIDE = 31   # OBS_OOT_WIDE in dm/host/dm.h
OBS = {
  'e0f3ccfaf50065ae4ea01eb5aeb13b1a': [
    (0x4c5340, _OOT_WIDE | 20 << 8, 'f9400108', 'camera update, after it stored the camera aspect at +0xb0 (ldr x8,[x8]); x20 the projection'),
    (0x3485cc0, _OOT_WIDE | 0 << 8, '2d558400', 'sead PerspectiveProjection matrix update (ldp s0,s1,[x0,#0xac]); x0 the projection'),
    (0x4f1e8, _OOT_WIDE | 21 << 8, '2d558ea0', 'inlined projection update (ldp s0,s3,[x21,#0xac]); x21 the projection'),
    (0x23b9a8, _OOT_WIDE | 0 << 8, '2d558c00', 'inlined projection update (ldp s0,s3,[x0,#0xac]); x0 the projection'),
    (0x33b82c, _OOT_WIDE | 2 << 8, '2d558440', 'inlined projection update (ldp s0,s1,[x2,#0xac]); x2 the projection'),
    # the 2D layer, kind in the parameter's bits from 5 up (1 HUD, 2 movie, 3 save picture): kept in the middle 16:9 of a picture of another shape
    (0x774368, _OOT_WIDE | 1 << 13, 'ad401041', 'UI canvas matrix product (ldp q1,q4,[x2], the projection rows; x0 the canvas, x2 pointed at a scaled copy for the full-screen UI projection)'),
    (0x3b5c758, _OOT_WIDE | (2 << 5 | 29) << 8, 'a941a269', 'movie layer draw, after its viewport struct at x29-0x28 is built (ldp x9,x8,[x19,#0x18]); the viewport pulled into the middle 16:9'),
    (0x284824, _OOT_WIDE | 3 << 13, 'f9400108', 'save picture: the viewport helper\'s SetViewport call (ldr x8,[x8]; x1..x4 = x, y, w, h); the middle 16:9 fills the texture'),
  ],
}

from nso_asm import ADRP, B

def lichii_options(ctx):
    """Native equivalents of Lichii's resolution, texture pool and frame timing add-ons."""
    here, emit, site, put, state = ctx.here, ctx.emit, ctx.site, ctx.put, ctx.state

    resolution, full_scale = 0x805bd0, 0x80640c
    site(resolution, 'e8410036')             # tbz w8,#0,full_scale
    start = here()
    emit(0xa9bf47f0)                       # stp x16,x17,[sp,#-16]!
    emit(ADRP(16, here(), state))
    emit(0xb9403a10)                       # ldr w16,[x16,#0x38]
    emit(0x37000090)                       # tbnz w16,#0,full
    emit(0x36000068)                       # tbz w8,#0,full (original condition)
    emit(0xa8c147f0)                       # ldp x16,x17,[sp],#16
    emit(B(here(), resolution + 4))
    emit(0xa8c147f0)                       # full: ldp x16,x17,[sp],#16
    emit(B(here(), full_scale))
    put(resolution, [B(resolution, start)], 'Max Resolution: optional full-scale branch of the dynamic resolution controller')

    texture_pool = 0xc2a36c
    site(texture_pool, 'e82701a9')           # stp x8,x9,[sp,#0x10]
    start = here()
    emit(0xa9bf47f0)                       # stp x16,x17,[sp,#-16]!
    emit(ADRP(16, here(), state))
    emit(0xb9403a10)                       # ldr w16,[x16,#0x38]
    emit(0x36080050)                       # tbz w16,#1,store
    emit(0xd37ff908)                       # lsl x8,x8,#1
    emit(0xa8c147f0)                       # store: ldp x16,x17,[sp],#16
    emit(0xa90127e8)                       # stp x8,x9,[sp,#0x10] (original store)
    emit(B(here(), texture_pool + 4))
    put(texture_pool, [B(texture_pool, start)], 'Texture Pool x2: optional doubling of the initial graphics allocation')

    # This update adds s8 to the game's accumulator. The original divides a 30 or 60 Hz
    # reference (s1) by the integer frame-rate field; the alternative multiplies it by
    # elapsed guest seconds. Presentation is still governed by the separate frame controller.
    game_step = 0x6e6cc4
    site(game_step, '2818201e')             # fdiv s8,s1,s0
    start = here()
    emit(0x1e201828)                       # fdiv s8,s1,s0 (also the first sample's fallback)
    emit(0xa9bd2fea)                       # stp x10,x11,[sp,#-48]!
    emit(ADRP(10, here(), state))
    emit(0xb940394a)                       # ldr w10,[x10,#0x38]
    emit(0x3710006a)                       # tbnz w10,#2,measure
    emit(0xa8c32fea)                       # ldp x10,x11,[sp],#48
    emit(B(here(), game_step + 4))
    emit(0xf9000bec)                       # measure: str x12,[sp,#16]
    emit(0x3d800bf0)                       # str q16,[sp,#32]
    ctx.counter(10, game_step)             # x10 = the host's 31.25 MHz Switch 2 clock
    emit(ADRP(11, here(), state))
    for word in (
        0xf940216c,                       # ldr x12,[x11,#0x40] (previous sample)
        0xf900216a,                       # str x10,[x11,#0x40]
        0xb40001cc,                       # cbz x12,restore
        0xcb0c014a,                       # sub x10,x10,x12
        0x9e220148,                       # scvtf s8,x10
        0x528e0beb,                       # movz w11,#0x705f
        0x72a6612b,                       # movk w11,#0x3309,lsl #16
        0x1e270170,                       # fmov s16,w11 (1 / 31,250,000)
        0x1e300908,                       # fmul s8,s8,s16
        0x1e2703f0,                       # fmov s16,wzr
        0x1e306908,                       # fmaxnm s8,s8,s16
        0x529999ab,                       # movz w11,#0xcccd
        0x72a7b98b,                       # movk w11,#0x3dcc,lsl #16
        0x1e270170,                       # fmov s16,w11 (0.1 s; cap after a stall)
        0x1e307908,                       # fminnm s8,s8,s16
        0x1e210908,                       # fmul s8,s8,s1
        0x3dc00bf0,                       # restore: ldr q16,[sp,#32]
        0xf9400bec,                       # ldr x12,[sp,#16]
        0xa8c32fea,                       # ldp x10,x11,[sp],#48
    ):
        emit(word)
    emit(B(here(), game_step + 4))
    put(game_step, [B(game_step, start)], 'FPS Unlock: optional fractional update step from the host guest clock')
    ctx.oot_patches = 1

# v131072 main: the frame step (patch_nso.py "Frame step in whole vsyncs"). The game has its own frame-rate controller (main+0x3b4ef60, called once a frame with x20 = its
# config): from the measured frame time it picks a vsync interval between config +0x0 and +0x4 (with hysteresis, +0x50 / +0x54), stores it at +0x48 and fps = 60 / interval
# at +0x58, then at main+0x3b4f074 (where its paths meet) calls main+0x3b4f160, which hands +0x58 to setFrameRate (main+0x4dc0f0), and the framework's setInterval
# (main+0x346c5c0) with +0x48, which sets the window's present interval (main+0xf49970) and the framework's fps (+0xfc = 60 / interval). So 30 fps (interval 2) is the game's
# own mode, with its game time stepping by the interval. The stub at main+0x3b4f074 replaces the interval and fps with the host's step (state page +0x20, 1 = 60 fps, 2 = 30) while
# a target fps is set (state +0x0, the launcher's Frame rate / DM_FPS_TARGET; 0 = the game's own choice), and counts the vsyncs stepped (+0x30) for the host's speed check
def frame_step(ctx):
    here, emit, fs_site, fs_put, FS_STATE_OFF = ctx.here, ctx.emit, ctx.site, ctx.put, ctx.state
    site = 0x3b4f074
    fs_site(site, 'e00314aa')   # mov x0,x20
    start = here(); emit(ADRP(16, here(), FS_STATE_OFF))
    for w in (0xb9400211,   # ldr w17,[x16]           target fps (float bits; 0 = off)
              0x34000151,   # cbz w17,orig (+10)
              0xb9402211,   # ldr w17,[x16,#0x20]     step in vsyncs
              0x34000111,   # cbz w17,orig (+8)       not decided yet
              0xb9004a91,   # str w17,[x20,#0x48]     the controller's interval
              0x52800789,   # mov w9,#60
              0x1ad10929,   # udiv w9,w9,w17
              0xb9005a89,   # str w9,[x20,#0x58]      its fps, which setFrameRate takes
              0xf9401a09,   # ldr x9,[x16,#0x30]
              0x8b314129,   # add x9,x9,w17,uxtw
              0xf9001a09,   # str x9,[x16,#0x30]      vsyncs stepped
              0xaa1403e0):  # orig: mov x0,x20
        emit(w)
    emit(B(here(), site + 4))
    fs_put(site, [B(site, start)], "frame step: the frame-rate controller's interval and fps follow the host's vsyncs (state page target fps; 0 = the game's own choice)")
    if ctx.build_id in (None, 'e0f3ccfaf50065ae4ea01eb5aeb13b1a45da3348000000000000000000000000'):
        lichii_options(ctx)

FRAME_STEP = {'e0f3ccfaf50065ae4ea01eb5aeb13b1a': frame_step}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md): what, its binding now, the loosest it could have (goal),
# purpose, why; blocked or kept: why the gap stays open (cannot close yet, or left open on purpose); checked: what was checked on game files (else a judgement)
BINDING = [
    {'what': 'camera aspect: the 3D cameras follow the picture shape (Game settings > Aspect ratio)', 'now': 'build', 'goal': 'game', 'purpose': 'rendering',
     'why': 'sead::PerspectiveProjection is the same in every title of this engine, but the five read sites are fixed addresses of this build',
     'blocked': 'one build on hand; the projection reads are plain sead code and could be found by function fingerprint'},
    {'what': 'Max Resolution: optional full-scale dynamic resolution path', 'now': 'build', 'goal': 'game', 'purpose': 'rendering',
     'why': 'the title has its own dynamic resolution controller and full-scale branch', 'blocked': 'one full build ID and branch verified'},
    {'what': 'Texture Pool x2: optional larger initial graphics allocation', 'now': 'build', 'goal': 'game', 'purpose': 'memory',
     'why': 'the allocator is in the game and full-scale rendering can exhaust the original pool', 'blocked': 'one full build ID and allocator call verified'},
    {'what': 'FPS Unlock: optional elapsed-time update step', 'now': 'build', 'goal': 'game', 'purpose': 'frame rate',
     'why': 'the game update derives a fractional step from its integer frame rate', 'blocked': 'one full build ID and update hook verified'},
    {'what': "frame step: the frame-rate controller's interval and fps from the host", 'now': 'build', 'goal': 'game', 'purpose': 'frame rate',
     'why': "the game's own 30 fps mode (interval 2), chosen by the host's step so presentation and game time agree",
     'blocked': 'frame-step stubs have no function-matched form yet'},
    {'what': 'movie player: the plain player instead of the NDVE-enhanced one', 'now': 'build', 'goal': 'game', 'purpose': 'frame rate',
     'why': 'a workaround: the tensor network costs 47.5 ms of GPU a movie frame; a function fingerprint could carry it over updates, and once the translator keeps LDSM / MOVM / HMMA chains in matrices the cut can go',
     'blocked': 'one build on hand; the faster tensor path is translator work (docs/reference/shaders.md, Ampere HMMA through simdgroup_matrix, Open)'},
]
