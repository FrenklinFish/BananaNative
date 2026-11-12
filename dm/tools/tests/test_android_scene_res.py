"""The launcher's Scene resolution (SceneRes.java with Profile's column and Pack's scene_res rows) on the JVM (android/app/src/jvmtest's SceneResJvmTest), build.gradle's
sceneOf (checked against a Python reading of the same game.tsv rows), and that the settings, the start and the pack step are wired to it. The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/SceneResJvmTest.java'
GRADLE = REPO / 'android/app/build.gradle'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "";
    public static final String NX_DMSH_VK_CHAIN = "c0ffee000001", NX_DMSH_VK_ABI = "abc123abc123", NX_DMSH_VK_ABI_COMPAT = "";
}
'''


def rows(game, key):
    t = REPO / 'dm/games' / game / 'game.tsv'
    return [line.split('\t') for line in t.read_text(encoding='utf-8').splitlines() if line.startswith(key + '\t')]


def scene_of(game):   # build.gradle sceneOf, in Python
    if not rows(game, 'scene_scale_palette'): return ''
    clean = lambda s: re.sub(r'[;|]', ',', re.sub(r'[\t\n\\"]', ' ', s)).strip()
    return ';'.join('|'.join(clean(x) for x in (c[1].strip(), ' '.join(c[4:]).strip() or c[1].strip(), c[2].strip(), c[3].strip())) for c in rows(game, 'scene_scale') if len(c) >= 4)


class Wiring(unittest.TestCase):
    def test_gradle_column(self):
        g = GRADLE.read_text(encoding='utf-8')
        self.assertIn("def sceneOf = { String game ->", g)
        self.assertIn("optionsOf(g), sceneOf(g), ", g, 'the column after the options (Profile column 21)')
        self.assertIn('p.scenes = c.length > 21 ? c[21] : "";', (SRC / 'Profile.java').read_text(encoding='utf-8'))

    def test_oot_presets(self):
        s = scene_of('0400486024DEC000')
        self.assertEqual([p.split('|')[0] for p in s.split(';')], ['720p', '720p-min', '540p', '480p', '360p'])
        self.assertTrue(all('|' in p and p.split('|')[1] for p in s.split(';')), s)
        self.assertEqual([tuple(p.split('|')[2:]) for p in s.split(';')], [('0.6133', 'fixed'), ('0.6133', 'floor'), ('0.46875', 'fixed'), ('0.4166667', 'fixed'), ('0.3125', 'fixed')],
                         'each row\'s scale and mode, for the migration to presets (ResPresets.current)')

    def test_games_without_the_palette_have_none(self):
        for game in ('040074A01BF12000', '0400C3F00006E000'):   # checked on their RomFS: neither has a ResolutionReduceScale palette
            self.assertEqual(scene_of(game), '', game)

    def test_start_and_settings(self):
        act = (SRC / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('SceneRes.overlay(scenes, pack.scenes, packDir, sceneName)', act)
        self.assertLess(act.index('SceneRes.envLine(ov)'), act.index('env.addAll(user);'), "the player's own DM_ROMFS_OVERLAY line comes later and wins")
        gs = (SRC / 'GameSettings.java').read_text(encoding='utf-8')
        self.assertIn('"3D scene", v -> scene(a, g, again)', gs); self.assertIn('.sub()', gs, 'under Resolution in Advanced')
        self.assertIn('"scene_res"', (SRC / 'Prefs.java').read_text(encoding='utf-8').split('GAME_SETTINGS')[1].split(';')[0], 'Reset to defaults forgets it')
        self.assertIn("('scene_res', f'{n}\\t{p}')", (REPO / 'scripts/pack_android.py').read_text(encoding='utf-8'))


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class SceneResJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'stub').mkdir(); (d / 'run').mkdir()
            (d / 'stub/BuildConfig.java').write_text(STUB)
            srcs = [SRC / f'{n}.java' for n in ('SceneRes', 'Pack', 'DumpLayout', 'BuildCheck', 'RomfsCheck', 'Nup', 'Zstd', 'Profile', 'ShaderLibs')]
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *map(str, srcs), str(TEST), str(d / 'stub/BuildConfig.java')],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.SceneResJvmTest', str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
