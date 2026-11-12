"""Fire Emblem Fortune's Weave v131072 (main db93b0125f4850601d61c3edde012f8e).

VARIANT_CUTS['43']: a native 4:3 mod (game.tsv `variant 43`, the launcher's Game settings › Aspect ratio). The game keeps its 16:9
window and scene sizes; its cameras take a 4:3 aspect and its UI is laid out for 4:3 inside the 16:9 frame, which the present then
shows at 4:3 (DM_VK_PICTURE_ASPECT=4:3: it fills a 4:3 screen and gets side bars on a 16:9 one). The words started as a .pchtxt
for Eden-family emulators (camera aspect, ui2d pane rules) and were extended here; each group below says what it fixes. The
original bytes are kept so the rewriter refuses another executable."""

# Minimap rotation: v11 pinned N_RotCenter's Z rotation (+0x44) at 180 degrees, taking the game's turning of the map with the camera for a
# fault. The game places the quest pins (N_Quest, outside N_RotCenter) with its own angle, so they slid across the pinned map while walking and
# turning, and the map was the wrong way round for the camera. N_RotCenter now only skips the pane rules (0x58f570) and keeps its rotation;
# the scissor fix below keeps the turning map inside its frame. Checked in Dagsion against 16:9 through a walk and a camera turn.
# Camera aspect and ui2d pane rules (v11). The ui2d rules: a pane at |y| >= 120 under a parent at global (0,0) moves 180 units toward
# the nearer top or bottom edge (the "edge rule"), a centred full-screen pane (>= 1896 x 960) gets its y scaled by 4/3 (the
# "overlay rule"), sliding 30x40 containers count as anchored; a few names are exempt (L_UnitIn*, L_Cursor*, L_Popup_*).
_V11 = [
    (0x00b51e38, '2118221e', '01102d1e', 'v11 camera and UI'),
    (0x0055b424, '00000000', '4c1440b9', 'v11 camera and UI'),
    (0x0055b428, '00000000', 'cd3a9752', 'v11 camera and UI'),
    (0x0055b42c, '00000000', '4d5ea772', 'v11 camera and UI'),
    (0x0055b430, '00000000', '9f010d6b', 'v11 camera and UI'),
    (0x0055b434, '00000000', 'c1250054', 'v11 camera and UI'),
    (0x0055b438, '00000000', '4c0040b9', 'v11 camera and UI'),
    (0x0055b43c, '00000000', '72100014', 'v11 camera and UI'),
    (0x0055f604, '00000000', '2d119152', 'v11 camera and UI'),
    (0x0055f608, '00000000', '0d51a772', 'v11 camera and UI'),
    (0x0055f60c, '00000000', '9f010d6b', 'v11 camera and UI'),
    (0x0055f610, '00000000', 'e116fe54', 'v11 camera and UI'),
    (0x0055f614, '00000000', '5a04c03d', 'v11 camera and UI'),
    (0x0055f618, '00000000', '1b102d1e', 'v11 camera and UI'),
    (0x0055f61c, '00000000', 'dbf8ff17', 'v11 camera and UI'),
    (0x0055d988, '00000000', '5a939b4f', 'v11 camera and UI'),
    (0x0055d98c, '00000000', '5a04803d', 'v11 camera and UI'),
    (0x0055d990, '00000000', '089840b9', 'v11 camera and UI'),
    (0x0055d994, '00000000', '40fcff17', 'v11 camera and UI'),
    (0x0055b8ec, '00000000', '089840b9', 'v11 camera and UI'),
    (0x0055b8f0, '00000000', '69040014', 'v11 camera and UI'),
    (0x0055ca90, '089840b9', '65faff17', 'v11 camera and UI'),
    (0x0058e724, '00000000', '0b5840f9', 'v11 camera and UI'),
    (0x0058e728, '00000000', 'cae98bd2', 'v11 camera and UI'),
    (0x0058e72c, '00000000', '4aeaadf2', 'v11 camera and UI'),
    (0x0058e730, '00000000', '8a6ec8f2', 'v11 camera and UI'),
    (0x0058e734, '00000000', 'aaccedf2', 'v11 camera and UI'),
    (0x0058e738, '00000000', '8c030014', 'v11 camera and UI'),
    (0x0058f568, '00000000', '7f010aeb', 'v11 camera and UI'),
    (0x0058f56c, '00000000', 'c16efb54', 'v11 camera and UI'),
    (0x0058f570, '00000000', 'c1fbff17', 'minimap: b 0x58e474 (N_RotCenter keeps the game rotation; v11 wrote 180 to +0x44)'),
    (0x00586344, '00000000', '890904b4', 'v11 camera and UI'),
    (0x00586348, '00000000', '101d40bd', 'v11 camera and UI'),
    (0x0058634c, '00000000', '110d40bd', 'v11 camera and UI'),
    (0x00586350, '00000000', '0822201e', 'v11 camera and UI'),
    (0x00586354, '00000000', '81031d54', 'v15: b.ne 0x5c03c4 (parent off y=0: N_Player_Bg*/N_Target_Bg* check; v11 had b.ne 0x58e474)'),
    (0x0058faa4, '00000000', '2822201e', 'v11 camera and UI'),
    (0x0058faa8, '00000000', '614eff54', 'v11 camera and UI'),
    (0x0058faac, '00000000', 'a6520014', 'v12: b 0x5a4544 (A_Contents check; v11 had mov x10, #0x5f4c)'),
    (0x0058fab0, '00000000', '6aa8aef2', 'v11 camera and UI'),
    (0x0058fab4, '00000000', '4a6ecef2', 'v11 camera and UI'),
    (0x0058fab8, '00000000', 'ea4deef2', 'v11 camera and UI'),
    (0x0058fabc, '00000000', '2a000014', 'v11 camera and UI'),
    (0x0058fb64, '00000000', '7f010aeb', 'v11 camera and UI'),
    (0x0058fb68, '00000000', '6048ff54', 'v11 camera and UI'),
    (0x0058fb6c, '00000000', 'aacaadf2', 'v11 camera and UI'),
    (0x0058fb70, '00000000', '2a8dcef2', 'v11 camera and UI'),
    (0x0058fb74, '00000000', '2ac9edf2', 'v11 camera and UI'),
    (0x0058fb78, '00000000', '73f3ff17', 'v11 camera and UI'),
    (0x0058c944, '00000000', '7f010aeb', 'v11 camera and UI'),
    (0x0058c948, '00000000', '60d90054', 'v11 camera and UI'),
    (0x0058c94c, '00000000', '0aeaadf2', 'v11 camera and UI'),
    (0x0058c950, '00000000', '0aaecef2', 'v11 camera and UI'),
    (0x0058c954, '00000000', '0aeeebf2', 'v11 camera and UI'),
    (0x0058c958, '00000000', 'b31a0014', 'v11 camera and UI'),
    (0x00593424, '00000000', '7f010aeb', 'v11 camera and UI'),
    (0x00593428, '00000000', '6082fd54', 'v11 camera and UI'),
    (0x0059342c, '00000000', '2d698cd2', 'v11 camera and UI'),
    (0x00593430, '00000000', 'edcdadf2', 'v11 camera and UI'),
    (0x00593434, '00000000', 'cde8cdf2', 'v11 camera and UI'),
    (0x00593438, '00000000', '4d6eecf2', 'v11 camera and UI'),
    (0x0059343c, '00000000', '0a590014', 'v14: b 0x5a9864 (Player_/Target_Fade check; v11 had b 0x58f004)'),
    (0x0058f004, '00000000', '102a221e', 'v11 camera and UI'),
    (0x0058f008, '00000000', '7f010deb', 'v11 camera and UI'),
    (0x0058f00c, '00000000', 'c0adff54', 'v11 camera and UI'),
    (0x0058f010, '00000000', '0a5ea852', 'v11 camera and UI'),
    (0x0058f014, '00000000', '5201271e', 'v11 camera and UI'),
    (0x0058f018, '00000000', '11c2201e', 'v11 camera and UI'),
    (0x0058f01c, '00000000', '6dfdff17', 'v11 camera and UI'),
    (0x0058e5c4, '00000000', '8a66a852', 'v11 camera and UI'),
    (0x0058e5c8, '00000000', '5101271e', 'v11 camera and UI'),
    (0x0058e5cc, '00000000', 'a6ffff17', 'v11 camera and UI'),
    (0x0058e5d0, '00000000', '2022321e', 'v11 camera and UI'),
    (0x0058e5d4, '00000000', '0bf5ff54', 'v11 camera and UI'),
    (0x0058e5d8, '00000000', 'fbffff17', 'v11 camera and UI'),
    (0x0058e464, '00000000', '3342211e', 'v11 camera and UI'),
    (0x0058e468, '00000000', '0822201e', 'v11 camera and UI'),
    (0x0058e46c, '00000000', '31ce331e', 'v11 camera and UI'),
    (0x0058e470, '00000000', '4228311e', 'v11 camera and UI'),
    (0x0058e474, '00000000', '0318472d', 'v11 camera and UI'),
    (0x0058e478, '00000000', '80010014', 'v11 camera and UI'),
    (0x0058ea74, '0318472d', '2cffff17', 'v11 camera and UI'),
    (0x005749c4, '00000000', '091040b9', 'v11 camera and UI'),
    (0x005749c8, '00000000', '2a119152', 'v11 camera and UI'),
    (0x005749cc, '00000000', '0a51a772', 'v11 camera and UI'),
    (0x005749d0, '00000000', '3f010a6b', 'v11 camera and UI'),
    (0x005749d4, '00000000', '81340354', 'v11 camera and UI'),
    (0x005749d8, '00000000', 'd3ffff17', 'v11 camera and UI'),
    (0x00574924, '00000000', '092440b9', 'v11 camera and UI'),
    (0x00574928, '00000000', 'ca3a9752', 'v11 camera and UI'),
    (0x0057492c, '00000000', '4a5ea772', 'v11 camera and UI'),
    (0x00574930, '00000000', '3f010a6b', 'v11 camera and UI'),
    (0x00574934, '00000000', '81390354', 'v11 camera and UI'),
    (0x00574938, '00000000', '0bffff17', 'v11 camera and UI'),
    (0x00574564, '00000000', '0008c03d', 'v11 camera and UI'),
    (0x00574568, '00000000', '01102d1e', 'v11 camera and UI'),
    (0x0057456c, '00000000', '0090814f', 'v11 camera and UI'),
    (0x00574570, '00000000', '0008803d', 'v11 camera and UI'),
    (0x00574574, '00000000', 'bc1a0014', 'v11 camera and UI'),
    (0x0057b064, '00000000', '008440ad', 'v11 camera and UI'),
    (0x0057b068, '00000000', 'afeeff17', 'v11 camera and UI'),
    (0x00576b20, '008440ad', 'a9f7ff17', 'v11 camera and UI'),
    (0x00586358, '00000000', '8ba9ff17', 'v11 camera and UI'),
    (0x00570984, '00000000', '2822201e', 'v11 camera and UI'),
    (0x0057098c, '00000000', '122840fd', 'v11 camera and UI'),
    (0x00570990, '00000000', 'aa9da852', 'v11 camera and UI'),
    (0x00570994, '00000000', '5301271e', 'v11 camera and UI'),
    (0x00570998, '00000000', '8b340014', 'v11 camera and UI'),
    (0x0057dbc4, '00000000', '4022331e', 'v11 camera and UI'),
    (0x0057dbcc, '00000000', '52060c5e', 'v11 camera and UI'),
    (0x0057dbd0, '00000000', '0a8ea852', 'v11 camera and UI'),
    (0x0057dbd4, '00000000', '5301271e', 'v11 camera and UI'),
    (0x0057dbd8, '00000000', '4022331e', 'v11 camera and UI'),
    (0x0057dbdc, '00000000', '1a050014', 'v11 camera and UI'),
    (0x0057f048, '00000000', '37330114', 'v18: b 0x5cbd24 (UnitPub check, then the v12 L_List check at 0x5a18a4)'),
    (0x0057f04c, '00000000', '4af5a772', 'v11 camera and UI'),
    (0x0057f050, '00000000', '5301271e', 'v11 camera and UI'),
    (0x0057f054, '00000000', '2108331e', 'v11 camera and UI'),
    (0x0056ce04, '00000000', '2c2940f9', 'v11 camera and UI'),
    (0x0056ce08, '00000000', '0a3ea8d2', 'v11 camera and UI'),
    (0x0056ce0c, '00000000', '0a44e8f2', 'v11 camera and UI'),
    (0x0056ce10, '00000000', '9f010aeb', 'v11 camera and UI'),
    (0x0056ce14, '00000000', '81951d54', 'v13: b.ne 0x5a80c4 (empty sliding parent check)'),
    (0x0056ce18, '00000000', 'dd0e0014', 'v11 camera and UI'),
    (0x00570988, '00000000', 'e123fe54', 'v11 camera and UI'),
    (0x0057dbc8, '00000000', 'eb051554', 'v12: b.lt 0x5a7c84 (tall-pane overlay check; v11 had b.lt 0x58faac)'),
    (0x0057f044, '00000000', '0bd12554', 'v18: b.lt 0x5caa64 (under 960 tall: the Fade check; v11 had b.lt 0x58faac)'),
    (0x0057f058, '00000000', '95420014', 'v11 camera and UI'),
]

# v12 UI fixes, with the v11 entry words at 0x57f048 and 0x58faac above redirected:

# - L_List: v10's full-screen overlay rule (centred pane >= 1896x960 gets y scale x4/3) caught the Base Menu's 2694x1120 list container;
#   the game places the menu cursor at (target global - parent global) without the parent's scale, so the cursor drifted further below
#   the selected entry with every step down. Panes whose name starts with L_Li are now left unscaled (caves 0x5a18a4, 0x5a3c84).
# - A_Contents: v4's HUD rule (|y| >= 120 under a parent at (0,0) moves 180 units toward the edge) moved dialog text (T_Text at y 180 in
#   A_Contents) out of its window ("You cannot fast-travel at this time." above the box). Children of A_Contents are now left in place
#   (caves 0x5a4544, 0x5a6864). Both found by reading the live pane trees in a PC emulator and verified there.

_UI_V12 = [

    (0x005a18a4, '00000000', '0ab040b9', 'v12: ldr w10, [x0, #0xb0]'),

    (0x005a18a8, '00000000', '5201271e', 'v12: fmov s18, w10'),

    (0x005a18ac, '00000000', '8ae98b52', 'v12: mov w10, #0x5f4c'),

    (0x005a18b0, '00000000', '8a29ad72', 'v12: movk w10, #0x694c, lsl #16'),

    (0x005a18b4, '00000000', '5301271e', 'v12: fmov s19, w10'),

    (0x005a18b8, '00000000', '4022331e', 'v12: fcmp s18, s19'),

    (0x005a18bc, '00000000', 'f2080014', 'v12: b #0x5a3c84'),

    (0x005a3c84, '00000000', '40f1f554', 'v12: b.eq #0x58faac'),

    (0x005a3c88, '00000000', '6a559552', 'v12: mov w10, #0xaaab'),

    (0x005a3c8c, '00000000', 'f06cff17', 'v12: b #0x57f04c'),

    (0x005a4544, '00000000', '2c5940f9', 'v12: ldr x12, [x9, #0xb0]'),

    (0x005a4548, '00000000', '2ae88bd2', 'v12: mov x10, #0x5f41'),

    (0x005a454c, '00000000', '6ae8adf2', 'v12: movk x10, #0x6f43, lsl #16'),

    (0x005a4550, '00000000', 'ca8dcef2', 'v12: movk x10, #0x746e, lsl #32'),

    (0x005a4554, '00000000', 'aaccedf2', 'v12: movk x10, #0x6e65, lsl #48'),

    (0x005a4558, '00000000', '9f010aeb', 'v12: cmp x12, x10'),

    (0x005a455c, '00000000', 'da7c0014', 'v16: b 0x5c38c4 (A_Contents / N_Status parent check; v12 had b 0x5a6864)'),

    (0x005a6864, '00000000', '80e0f354', 'v12: b.eq #0x58e474'),

    (0x005a6868, '00000000', '8ae98bd2', 'v12: mov x10, #0x5f4c'),

    (0x005a686c, '00000000', '91a4ff17', 'v12: b #0x58fab0'),

    # Tall overlays: v10 scaled a centred pane only when it was at least 1896 wide AND 960 tall; the shop's Fade01 (958x1080, the left
    # half dimmer) failed the width test and stayed short. A pane at least 1080 tall now passes alone (cave 0x5a7c84)
    (0x005a7c84, '00000000', '53060c5e', 'v12: mov s19, v18.s[1]'),
    (0x005a7c88, '00000000', 'ea90a852', 'v12: mov w10, #0x44870000 (1080.0)'),
    (0x005a7c8c, '00000000', '5101271e', 'v12: fmov s17, w10'),
    (0x005a7c90, '00000000', '6022311e', 'v12: fcmp s19, s17'),
    (0x005a7c94, '00000000', 'caf9ea54', 'v12: b.ge 0x57dbcc'),
    (0x005a7c98, '00000000', '859fff17', 'v12: b 0x58faac'),
]


# The Armorer's Buy/Sell and message panes have empty parents that slide horizontally.
# Apply the same vertical anchor during their slide as when they reach x=0.
_UI_V13 = [
    (0x005a80c4, '00000000', '30460014', 'v15: b 0x5b9984 (sliding parent 0x0 or 422x4; v13 had cbnz x12, 0x58e474)'),
    (0x005a80c8, '00000000', '3122ff17', 'v13: b 0x57098c'),
]


# v14: combat HUD smoke. The combat screen's Player_Fade / Target_Fade (960x536 smoky bands at y -40 in N_*_Bg) sit
# under a parent at (0,0) but have |y| < 120, so v4's edge rule left them while the names, HP bars and stats above them moved 180 toward
# the bottom edge: the smoke floated above the text. Panes named Player_Fade / Target_Fade now always take the move (caves 0x5a9864,
# 0x5b0744, 0x5b0be4: compare the pane name's first 8 bytes, then 'ade'; join the IconForc* path at 0x58e5c4). Verified in a PC emulator.
_UI_V14 = [
    (0x005a9864, '00000000', '0a8a8dd2', 'v14: mov x10, #0x6c50'),
    (0x005a9868, '00000000', '2a2caff2', 'v14: movk x10, #0x7961, lsl #16'),
    (0x005a986c, '00000000', 'aa4ccef2', 'v14: movk x10, #0x7265, lsl #32'),
    (0x005a9870, '00000000', 'eacbe8f2', 'v14: movk x10, #0x465f, lsl #48'),
    (0x005a9874, '00000000', '7f010aeb', 'v14: cmp x11, x10'),
    (0x005a9878, '00000000', '609b0354', 'v14: b.eq #0x5b0be4'),
    (0x005a987c, '00000000', 'b21b0014', 'v14: b #0x5b0744'),
    (0x005b0744, '00000000', '8a2a8cd2', 'v14: mov x10, #0x6154'),
    (0x005b0748, '00000000', '4aeeacf2', 'v14: movk x10, #0x6772, lsl #16'),
    (0x005b074c, '00000000', 'aa8ccef2', 'v14: movk x10, #0x7465, lsl #32'),
    (0x005b0750, '00000000', 'eacbe8f2', 'v14: movk x10, #0x465f, lsl #48'),
    (0x005b0754, '00000000', '7f010aeb', 'v14: cmp x11, x10'),
    (0x005b0758, '00000000', '60240054', 'v14: b.eq #0x5b0be4'),
    (0x005b075c, '00000000', '2a7aff17', 'v14: b #0x58f004'),
    (0x005b0be4, '00000000', '0cb840b9', 'v14: ldr w12, [x0, #0xb8]'),
    (0x005b0be8, '00000000', '2a8c8c52', 'v14: mov w10, #0x6461'),
    (0x005b0bec, '00000000', 'aa0ca072', 'v14: movk w10, #0x65, lsl #16'),
    (0x005b0bf0, '00000000', '9f010a6b', 'v14: cmp w12, w10'),
    (0x005b0bf4, '00000000', '8120ef54', 'v14: b.ne #0x58f004'),
    (0x005b0bf8, '00000000', '102a221e', 'v14: fadd s16, s16, s2'),
    (0x005b0bfc, '00000000', '7276ff17', 'v14: b #0x58e5c4'),
]

# Scissor panes at 4:3 (with the v11 words): ui2d's scissor-pane draw (main+0x5975a0) turns the pane's global matrix into a pixel
# scissor without the v11 UI squeeze, so the Dagsion minimap's S_Scissor (256x256 at y 553) clipped 1626,0 256x115 instead of
# 29..221: the map spilled above its frame and its lower half was cut (also in PC emulators). On a screen-sized target (16:9, at least
# 720 high: handheld 1920x1080, docked 2048x1152, 1280x720; s5 x s4 is the target's size) the pane's y scale (s16) and y translation
# (s6) get the same 0.75 as the projection; other targets keep the game's scissor. Caves 0x59afa4 and 0x59c544 (7 words each, all used)
_SCISSOR43 = [
    (0x005975ec, '450c251e', '6e0e0014', 'scissor 4:3: b 0x59afa4'),
    (0x0059afa4, '00000000', '450c251e', 'scissor 4:3: fcsel s5, s2, s5, eq (moved)'),
    (0x0059afa8, '00000000', '8a00381e', 'scissor 4:3: fcvtzs w10, s4 (target height)'),
    (0x0059afac, '00000000', 'a900381e', 'scissor 4:3: fcvtzs w9, s5 (target width)'),
    (0x0059afb0, '00000000', '5f410b71', 'scissor 4:3: cmp w10, #720'),
    (0x0059afb4, '00000000', 'eb31fe54', 'scissor 4:3: b.lt 0x5975f0'),
    (0x0059afb8, '00000000', '4a6d1c53', 'scissor 4:3: lsl w10, w10, #4 (height x 16)'),
    (0x0059afbc, '00000000', '62050014', 'scissor 4:3: b 0x59c544'),
    (0x0059c544, '00000000', '290d090b', 'scissor 4:3: add w9, w9, w9, lsl #3 (width x 9)'),
    (0x0059c548, '00000000', '3f010a6b', 'scissor 4:3: cmp w9, w10 (16:9)'),
    (0x0059c54c, '00000000', '2185fd54', 'scissor 4:3: b.ne 0x5975f0'),
    (0x0059c550, '00000000', '15102d1e', 'scissor 4:3: fmov s21, #0.75'),
    (0x0059c554, '00000000', '100a351e', 'scissor 4:3: fmul s16, s16, s21'),
    (0x0059c558, '00000000', 'c608351e', 'scissor 4:3: fmul s6, s6, s21'),
    (0x0059c55c, '00000000', '25ecff17', 'scissor 4:3: b 0x5975f0'),
]

# v15:
# - EXP panel pop-in: Battle_Exp_In slides N_IO (422x4) from x -100 to 0; v11/v13 only treat 30x40 and 0x0 sliding parents as anchored,
#   so for the slide the name/EXP panel sat at its 16:9 height and jumped 180 down at x=0. 422x4 parents now count too (cave 0x5b9984).
# - Combat HUD flag and smoke: N_Player_Bg / N_Target_Bg (and *_Bg_Pos) animate between y 0 and -80. At -80 the edge rule skipped all
#   their children (it needs a parent at global y 0), so IconForcesSymbol_*, *_Bg00/01 and *_Fade stayed 180 above the moved names and
#   battle report (forecast, Blaze Arts, some combat phases). Under those parents the rule now runs as under (0,0); their own -80 is below
#   the 120 threshold, so nothing moves twice (caves 0x5c03c4, 0x5c2ce4, 0x5c3284). Found from the layouts in Layout/Battle.arc and the live pane trees.
_UI_V15 = [
    (0x005b9984, '00000000', '4c80dbb4', 'v15: cbz x12, 0x57098c (0x0 parent: anchor)'),
    (0x005b9988, '00000000', '6a7aa8d2', 'v15: x10 = 422x4 (Battle_Exp N_IO / N_Animal_IO)'),
    (0x005b998c, '00000000', '0a10e8f2', 'v15: movk x10, #0x4080, lsl #48'),
    (0x005b9990, '00000000', '9f010aeb', 'v15: cmp x12, x10'),
    (0x005b9994, '00000000', 'c07fdb54', 'v15: b.eq 0x57098c (422x4 parent: anchor)'),
    (0x005b9998, '00000000', 'b752ff17', 'v15: b 0x58e474 (other parents: leave)'),
    (0x005c03c4, '00000000', '2c5940f9', 'v15: ldr x12, [x9, #0xb0] (parent name)'),
    (0x005c03c8, '00000000', 'cae98bd2', 'v15: x10 = "N_Player"'),
    (0x005c03cc, '00000000', '0a8aadf2', 'v15: movk x10, #0x6c50, lsl #16'),
    (0x005c03d0, '00000000', '2a2ccff2', 'v15: movk x10, #0x7961, lsl #32'),
    (0x005c03d4, '00000000', 'aa4ceef2', 'v15: movk x10, #0x7265, lsl #48'),
    (0x005c03d8, '00000000', 'cde98bd2', 'v15: x13 = "N_Target"'),
    (0x005c03dc, '00000000', '420a0014', 'v15: b 0x5c2ce4'),
    (0x005c2ce4, '00000000', '8d2aacf2', 'v15: movk x13, #0x6154, lsl #16'),
    (0x005c2ce8, '00000000', '4deeccf2', 'v15: movk x13, #0x6772, lsl #32'),
    (0x005c2cec, '00000000', 'ad8ceef2', 'v15: movk x13, #0x7465, lsl #48'),
    (0x005c2cf0, '00000000', '9f010aeb', 'v15: cmp x12, x10'),
    (0x005c2cf4, '00000000', '84114dfa', 'v15: ccmp x12, x13, #4, ne (N_Player or N_Target)'),
    (0x005c2cf8, '00000000', 'e1bbe554', 'v15: b.ne 0x58e474 (other parents: leave)'),
    (0x005c2cfc, '00000000', '62010014', 'v15: b 0x5c3284'),
    (0x005c3284, '00000000', '2cb940b9', 'v15: ldr w12, [x9, #0xb8] (name bytes 8..11)'),
    (0x005c3288, '00000000', '8c5d0012', 'v15: and w12, w12, #0xffffff'),
    (0x005c328c, '00000000', 'ea4b8852', 'v15: w10 = "_Bg"'),
    (0x005c3290, '00000000', 'ea0ca072', 'v15: movk w10, #0x67, lsl #16'),
    (0x005c3294, '00000000', '9f010a6b', 'v15: cmp w12, w10'),
    (0x005c3298, '00000000', 'e18ee554', 'v15: b.ne 0x58e474'),
    (0x005c329c, '00000000', 'bab5fe17', 'v15: b 0x570984 (N_*_Bg / N_*_Bg_Pos: edge rule as under (0,0))'),
]

# v16: level-up window. Common_LvUpStatusWdw's T_UnitName (y 258 under N_Status at (0,0)) took v4's 180 move while its
# window Wdw_Status (y 90) and the stat rows (y <= 118) did not: the unit's name floated above the panel. Children of a parent named
# exactly N_Status are now left in place (caves 0x5c38c4, 0x5c6a24, entry 0x5a455c after the A_Contents check).
_UI_V16 = [
    (0x005c38c4, '00000000', '805de554', 'v16: b.eq 0x58e474 (A_Contents children: leave)'),
    (0x005c38c8, '00000000', 'cae98bd2', 'v16: x10 = "N_Status"'),
    (0x005c38cc, '00000000', '6a8aaef2', 'v16: movk x10, #0x7453, lsl #16'),
    (0x005c38d0, '00000000', '2a8ccef2', 'v16: movk x10, #0x7461, lsl #32'),
    (0x005c38d4, '00000000', 'aa6eeef2', 'v16: movk x10, #0x7375, lsl #48'),
    (0x005c38d8, '00000000', '9f010aeb', 'v16: cmp x12, x10'),
    (0x005c38dc, '00000000', '520c0014', 'v16: b 0x5c6a24'),
    (0x005c6a24, '00000000', '011f0054', 'v17: b.ne 0x5c6e04 (not N_Status: L_UnitIn* check; v16 had b.ne 0x5a6868)'),
    (0x005c6a28, '00000000', '2ce14239', 'v16: ldrb w12, [x9, #0xb8] (name byte 8)'),
    (0x005c6a2c, '00000000', '4cd2e334', 'v16: cbz w12, 0x58e474 (exactly N_Status: leave)'),
    (0x005c6a30, '00000000', '8e7fff17', 'v16: b 0x5a6868'),
]

# v17: unit detail screen (X on a unit). Battle_UnitInfoDetail's L_UnitInfo (3400x1625) takes v10's x4/3 y stretch; inside
# it the header L_UnitInfoDetail is exempt from v4's move by v11's L_UnitIn name rule, but its sibling N_Status (the stat windows, y 226)
# was stretched and moved, so the windows rode 240 up into the header. Children of a parent named L_UnitIn* are now left in place, as
# the pane itself is (cave 0x5c6e04 after the v16 N_Status check). The only other such parent at (0,0) (L_UnitInfo00) has children
# within +-120, so it is unchanged. Found from the live pane trees.
_UI_V17 = [
    (0x005c6e04, '00000000', '8ae98bd2', 'v17: x10 = "L_UnitIn"'),
    (0x005c6e08, '00000000', 'aacaadf2', 'v17: movk x10, #0x6e55, lsl #16'),
    (0x005c6e0c, '00000000', '2a8dcef2', 'v17: movk x10, #0x7469, lsl #32'),
    (0x005c6e10, '00000000', '2ac9edf2', 'v17: movk x10, #0x6e49, lsl #48'),
    (0x005c6e14, '00000000', '9f010aeb', 'v17: cmp x12, x10'),
    (0x005c6e18, '00000000', 'e0b2e354', 'v17: b.eq 0x58e474 (children of L_UnitIn*: leave)'),
    (0x005c6e1c, '00000000', 'd22d0014', 'v20: b 0x5d2564 (MenuItemList check; v17 had b 0x5a6868)'),
]

# v18: obelisks (Obelisk_Hero). The darkening Fade (1920x856 at y -112) is under the overlay rule's 960 and under the edge rule's 120, so it
# kept its 16:9 extent and the 4:3 screen's bottom 180 units stayed lit; a full-width pane named exactly Fade now takes the overlay's x4/3
# (cave 0x5caa64, from 0x57f044). The Flame Lord art UnitPub (1000x1110, taller than 1080) was caught by the overlay rule and drawn 4/3 too
# tall; a pane named exactly UnitPub is now left unscaled (cave 0x5cbd24, from 0x57f048).
# v19: the obelisk Fade is also moved 180 down (its 4/3 stretch about its own centre left the bottom lit), caves 0x5caa64, 0x5cd904.
_UI_V18 = [
    (0x005caa64, '00000000', 'ca288c52', 'v19: w10 = "Fade" (name bytes 0..3)'),
    (0x005caa68, '00000000', '8aacac72', 'v19: movk w10, #0x6564, lsl #16'),
    (0x005caa6c, '00000000', '7f010a6b', 'v19: cmp w11, w10'),
    (0x005caa70, '00000000', 'e181e254', 'v19: others: as before'),
    (0x005caa74, '00000000', '7f1d60f2', 'v19: name byte 4 is the end'),
    (0x005caa78, '00000000', 'a181e254', 'v19: b.ne 0x58faac'),
    (0x005caa7c, '00000000', 'a20b0014', 'v19: b 0x5cd904'),
    (0x005cd904, '00000000', '8a66b852', 'v19: -180.0'),
    (0x005cd908, '00000000', '5301271e', 'v19: fmov s19, w10'),
    (0x005cd90c, '00000000', '4228331e', 'v19: Fade moved 180 down (to the 4:3 bottom edge)'),
    (0x005cd910, '00000000', '13102f1e', 'v19: fmov s19, #1.5'),
    (0x005cd914, '00000000', '2108331e', 'v19: and stretched 1.5x tall: it covers the 4:3 screen'),
    (0x005cd918, '00000000', 'd702ff17', 'v19: no further rule'),
    (0x005cbd24, '00000000', 'aaca8dd2', 'v18: x10 = "UnitPub"'),
    (0x005cbd28, '00000000', '2a8daef2', 'v18: movk x10, #0x7469, lsl #16'),
    (0x005cbd2c, '00000000', '0aaacef2', 'v18: movk x10, #0x7550, lsl #32'),
    (0x005cbd30, '00000000', '4a0ce0f2', 'v18: movk x10, #0x62, lsl #48'),
    (0x005cbd34, '00000000', '7f010aeb', 'v18: cmp x11, x10'),
    (0x005cbd38, '00000000', 'a0ebe154', 'v18: b.eq 0x58faac (UnitPub, the hero art: not stretched)'),
    (0x005cbd3c, '00000000', 'da56ff17', 'v18: b 0x5a18a4 (others: overlay as before)'),
]

# v20: choice boxes (Common_Choice: MenuItemList holds L_MenuItem00..03 at y 210 / 90 / -30 / -150). The edge rule moved only the
# outer two, so the choices split apart; children of a parent named MenuItem* are now left together (cave 0x5d2564 after v17).
_UI_V20 = [
    (0x005d2564, '00000000', 'aaa98cd2', 'v20: x10 = "MenuItem"'),
    (0x005d2568, '00000000', 'caadaef2', 'v20: movk x10, #0x756e, lsl #16'),
    (0x005d256c, '00000000', '2a89cef2', 'v20: movk x10, #0x7449, lsl #32'),
    (0x005d2570, '00000000', 'aaacedf2', 'v20: movk x10, #0x6d65, lsl #48'),
    (0x005d2574, '00000000', '9f010aeb', 'v20: cmp x12, x10'),
    (0x005d2578, '00000000', 'e0f7dd54', 'v20: children of MenuItem* (choice lists): leave together'),
    (0x005d257c, '00000000', 'bb50ff17', 'v20: b 0x5a6868'),
]

_MAIN = 'db93b0125f4850601d61c3edde012f8e'
VARIANT_CUTS = {'43': {_MAIN: _V11 + _SCISSOR43 + _UI_V12 + _UI_V13 + _UI_V14 + _UI_V15 + _UI_V16 + _UI_V17 + _UI_V18 + _UI_V20}}

BINDING = [
    {'what': "Fire Emblem Fortune's Weave native 4:3 variant (VARIANT_CUTS['43']: camera aspect and ui2d pane rules)", 'now': 'build', 'goal': 'unreviewed', 'purpose': 'rendering',
     'why': 'an opt-in variant (Game settings › Aspect ratio) of about 230 words in code caves of this main; each ui2d rule names panes of this build\'s layouts'},
]
