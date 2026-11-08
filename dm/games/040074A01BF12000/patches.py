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

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md)
BINDING = [
    {'what': "frame buffer scale: the scene at the window's full size instead of 80 %", 'now': 'build', 'goal': 'game', 'purpose': 'rendering',
     'why': "the game's own per-mode table entry, multiplied in at two fixed offsets; a function fingerprint of the scene setup (the two fmul after the table load) could carry it over the game's updates"},
]
