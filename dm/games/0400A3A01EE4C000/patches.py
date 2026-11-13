"""Pokopia 2.0.1 camera-only opt-in, exact main NSO build.

The observation gates execute their original instructions and call back into
dm/host/android/pkp_aspect.c. Without DM_PKP_ASPECT, the callbacks make no changes.
UI layout/canvas are NOT part of this contribution.
"""
OBS = {
    '76a489f3bd3642fa5e18bd83d32c98ba': [
        (0x08cc128, 28, '398dc273', 'scene camera enters render view; x20 camera'),
        (0x1f12130, 29, 'aa0003f3', 'projection builder entry; x0 camera'),
        (0x1f12354, 30, '6d4123e9', 'perspective projection exit; x19 camera'),
    ],
}
BINDING = [
    {'what': 'Pokopia camera aspect hooks in main v2.0.1', 'now': 'build',
     'goal': 'game', 'purpose': 'rendering',
     'checked': 'main 76a489f3bd3642fa5e18bd83d32c98ba (Fold 7 observation, 2026-11-11)',
     'why': 'camera layout/offsets are specific to this exact game build',
     'blocked': 'future game builds require exact offset and original-word validation'},
]