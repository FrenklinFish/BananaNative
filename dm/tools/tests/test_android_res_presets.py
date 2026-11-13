"""The launcher's Resolution presets (ResPresets.java with Profile's column 22, SceneRes and Prefs' migration) on the JVM (android/app/src/jvmtest's ResPresetsJvmTest),
build.gradle's presetsOf (checked against a Python reading of the same game.tsv rows and against dm/tools/scene_res.py's scales), and that the settings and the start are
wired to it. The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools')); sys.path.insert(0, str(REPO / 'dm/games'))
import game   # noqa: E402
import scene_res   # noqa: E402

SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/ResPresetsJvmTest.java'
GRADLE = REPO / 'android/app/build.gradle'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "";
    public static final String NX_DMSH_VK_CHAIN = "c0ffee000001", NX_DMSH_VK_ABI = "abc123abc123", NX_DMSH_VK_ABI_COMPAT = "";
}
'''


def rows(game_id, key):
    t = REPO / 'dm/games' / game_id / 'game.tsv'
    return [line.split('\t') for line in t.read_text(encoding='utf-8').splitlines() if line.startswith(key + '\t')]


def presets_of(game_id):   # build.gradle presetsOf, in Python
    clean = lambda s: re.sub(r'[;|]', ',', re.sub(r'[\t\n\\"]', ' ', s)).strip()
    outs = {c[1].strip(): (c[2].strip().split('x'), c[3].strip()) for c in rows(game_id, 'scene_output') if len(c) >= 4 and re.fullmatch(r'\d+x\d+', c[2].strip())}
    pal = bool(rows(game_id, 'scene_scale_palette'))
    out = []
    for c in rows(game_id, 'res_preset'):
        if len(c) < 4: continue
        pid, win, t = c[1].strip(), c[2].strip(), c[3].strip()
        label = ' '.join(c[4:]).strip() if len(c) > 4 else pid
        m = ('default', '0') if t == 'default' else (lambda x: (x.group(1), x.group(2)) if x else None)(re.fullmatch(r'(floor|fixed):(\d+)', t))
        o = outs.get(win)
        if m is None or (m[0] != 'default' and (not pal or o is None)): continue
        out.append('|'.join(clean(x) for x in (pid, label or pid, win, m[0], m[1], o[0][0] if o else '0', o[0][1] if o else '0', o[1] if o else 'open')))
    return ';'.join(out)


class Wiring(unittest.TestCase):
    def test_gradle_column(self):
        g = GRADLE.read_text(encoding='utf-8')
        self.assertIn('def presetsOf = { String game ->', g)
        self.assertIn('sceneOf(g), presetsOf(g)', g, 'the column after the scenes (Profile column 22)')
        self.assertIn('p.presets = c.length > 22 ? c[22] : "";', (SRC / 'Profile.java').read_text(encoding='utf-8'))

    def test_oot_column(self):
        self.assertEqual(presets_of('0400486024DEC000'), 'quality|Quality|1920x1080|default|0|1920|1080|measured;medium|Balanced|1920x1080|fixed|720|1920|1080|measured;'
                         'performance|Performance|1280x720|fixed|540|1280|720|measured')

    def test_from_column(self):
        """Profile column 26 (build.gradle presetFromOf): the earlier ids map to the presets that replaced them"""
        g = GRADLE.read_text(encoding='utf-8')
        self.assertRegex(g, r'presetFromOf\(g\)(?:, *\w+Of\(g\))*\]\.join')   # the 26th column, however many columns follow it
        self.assertIn('p.presetFrom = c.length > 26 ? c[26] : "";', (SRC / 'Profile.java').read_text(encoding='utf-8'))
        self.assertEqual([c[1:3] for c in rows('0400486024DEC000', 'res_preset_from')], [['balanced', 'medium'], ['max-performance', 'performance']])
        scene_res.res_presets(game.load('0400486024DEC000'))   # refuses a row naming a preset that is not there

    def test_column_matches_the_pack_step(self):
        """Every profile: the presets the launcher offers with a scene of their own are the overlays the pack step makes, at the scale the launcher labels"""
        for g in game.all_games():
            col = presets_of(g.id)
            if not col: continue
            launcher = {f[0]: int(f[4]) / int(f[6]) for f in (r.split('|') for r in col.split(';')) if f[3] != 'default'}
            tool = {p[0]: p[4] for p in scene_res.res_presets(g) if p[4] is not None}
            self.assertEqual(launcher.keys(), tool.keys(), g.id)
            for k in tool: self.assertAlmostEqual(launcher[k], tool[k], msg=f'{g.id} {k}')

    def test_start_and_settings(self):
        act = (SRC / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('prof.resolutionEnv(Prefs.startWindow(this, id))', act)
        self.assertIn('SceneRes.overlay(pack.scenes, packDir, rp.id)', act)
        self.assertLess(act.index('SceneRes.envLine(ov)'), act.index('env.addAll(user);'), "the player's own DM_ROMFS_OVERLAY line comes later and wins")
        gs = (SRC / 'GameSettings.java').read_text(encoding='utf-8')
        self.assertIn('"Resolution", v -> presets(a, g, again)', gs)
        self.assertIn('"Window", v -> resolution(a, g, again)', gs); self.assertIn('"3D scene", v -> scene(a, g, again)', gs)
        prefs = (SRC / 'Prefs.java').read_text(encoding='utf-8')
        self.assertIn('"res_preset"', prefs.split('GAME_SETTINGS')[1].split(';')[0], 'Reset to defaults forgets it')
        side = (SRC / 'SidePanel.java').read_text(encoding='utf-8')
        self.assertIn('p.resolutionLive(Prefs.startWindow(act, game()))', side, 'the in-game panel changes the preset\'s window live')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ResPresetsJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'stub').mkdir(); (d / 'run').mkdir()
            (d / 'stub/BuildConfig.java').write_text(STUB)
            srcs = [SRC / f'{n}.java' for n in ('ResPresets', 'SceneRes', 'Pack', 'DumpLayout', 'BuildCheck', 'RomfsCheck', 'Nup', 'Zstd', 'Profile', 'ShaderLibs')]
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *map(str, srcs), str(TEST), str(d / 'stub/BuildConfig.java')],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.ResPresetsJvmTest', str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
