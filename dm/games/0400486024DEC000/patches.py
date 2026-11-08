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

from nso_asm import ADRP, B

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

FRAME_STEP = {'e0f3ccfaf50065ae4ea01eb5aeb13b1a': frame_step}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md): what, its binding now, the loosest it could have (goal),
# purpose, why; blocked or kept: why the gap stays open (cannot close yet, or left open on purpose); checked: what was checked on game files (else a judgement)
BINDING = [
    {'what': "frame step: the frame-rate controller's interval and fps from the host", 'now': 'build', 'goal': 'game', 'purpose': 'frame rate',
     'why': "the game's own 30 fps mode (interval 2), chosen by the host's step so presentation and game time agree",
     'blocked': 'frame-step stubs have no function-matched form yet'},
    {'what': 'movie player: the plain player instead of the NDVE-enhanced one', 'now': 'build', 'goal': 'game', 'purpose': 'frame rate',
     'why': 'a workaround: the tensor network costs 47.5 ms of GPU a movie frame; a function fingerprint could carry it over updates, and once the translator keeps LDSM / MOVM / HMMA chains in matrices the cut can go',
     'blocked': 'one build on hand; the faster tensor path is translator work (docs/reference/shaders.md, Ampere HMMA through simdgroup_matrix, Open)'},
]
