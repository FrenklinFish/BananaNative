"""The launcher's recommended settings per phone (game.tsv recommend rows): Recommend.java with Profile's column 24 on the JVM (android/app/src/jvmtest's RecommendJvmTest),
dm/tools/recommend.py's check of every profile (build.gradle's recommendOf in Python: the same value lists, the same refusals), and that the settings, the start and the
log are wired to it. The JVM part is skipped without a JDK."""
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
import recommend   # noqa: E402

SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/RecommendJvmTest.java'
FACTS = REPO / 'android/app/src/jvmtest/honor-pnm-n49.readiness.txt'
GRADLE = REPO / 'android/app/build.gradle'
FE = '04007EF01F8AE000'
OOT = '0400486024DEC000'   # its rows: the driver on Qualcomm's own driver, the preset and vegetation on any Adreno (the no-stored-facts case)
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "";
}
'''


def java_list(src, name):
    m = re.search(name + r'\s*=\s*\{([^}]*)\}', src)
    body = m.group(1)
    return re.findall(r'"([^"]*)"', body) if '"' in body else [x.strip() for x in body.split(',')]


class Wiring(unittest.TestCase):
    def test_gradle_column(self):
        g = GRADLE.read_text(encoding='utf-8')
        self.assertIn('def recommendOf = { String game ->', g)
        self.assertIn('skipLayersOf(g), recommendOf(g)', g, 'the column after skip_layers (Profile column 24)')
        self.assertIn('p.recommend = c.length > 24 ? c[24] : "";', (SRC / 'Profile.java').read_text(encoding='utf-8'))

    def test_value_lists_agree(self):
        """build.gradle, dm/tools/recommend.py and Recommend.java take the same values, in the order of the launcher's own lists (the index is what is stored)"""
        g = GRADLE.read_text(encoding='utf-8')
        rec = (SRC / 'Recommend.java').read_text(encoding='utf-8')
        for py, jname in ((recommend.UPSCALERS, 'UPSCALERS'), (recommend.DLSS_MODES, 'DLSS_MODES'), (recommend.DRS, 'DRS_VALUES'), (recommend.CAPS, 'CAPS'), (recommend.PACE, 'PACE')):
            self.assertEqual(java_list(rec, jname), py, jname)
            self.assertIn('[' + ', '.join(f"'{v}'" for v in py) + ']', g, f'build.gradle takes {py}')
        self.assertIn('"' + '", "'.join(recommend.FACTS) + '"', rec)
        self.assertIn('[' + ', '.join(f"'{v}'" for v in recommend.FACTS) + ']', g)
        for p in recommend.FACT_PREFIXES: self.assertIn(f'"{p}"', rec); self.assertIn(f"'{p}'", g)
        # the launcher's lists, index for index
        modes = java_list((SRC / 'GameUpscaler.java').read_text(encoding='utf-8'), 'MODES')
        self.assertEqual(len(modes), len(recommend.UPSCALERS))
        for word, label in zip(recommend.UPSCALERS, modes):
            self.assertTrue(label.lower().replace(' ', '').startswith({'nearest': 'nearest', 'bilinear': 'bilinear', 'fsr1': 'fsr1', 'native': 'native', 'sgsr1': 'sgsr1'}[word]), (word, label))
        dl = java_list((SRC / 'DlssTune.java').read_text(encoding='utf-8'), 'MODE_NAMES')
        self.assertEqual([x.split(' (')[0].lower().replace(' ', '').replace('.1', '') for x in dl], recommend.DLSS_MODES)
        prefs = (SRC / 'Prefs.java').read_text(encoding='utf-8')
        self.assertEqual([x.rstrip('%').lower() for x in java_list(prefs, 'DRS_NAMES')], recommend.DRS)
        self.assertEqual(java_list(prefs, 'int\\[\\] FPS_CAPS'), ['0', '60', '30', '20', '15'])
        self.assertEqual(java_list(prefs, 'int\\[\\] PACE_CAPS'), ['0', '45', '40', '30', '20'])

    def test_settings_and_start(self):
        prefs = (SRC / 'Prefs.java').read_text(encoding='utf-8')
        for getter in ('Recommend.VEGETATION), true)', 'Recommend.OPTION + env), false)', 'Recommend.HOLD60), false)', 'Recommend.SKIP_LAYERS), false)',
                       'Integer r = recCap(c, titleId);', 'rec(c, titleId, Recommend.DRS)', 'rec(c, titleId, Recommend.RES_PRESET)'):
            self.assertIn(getter, prefs, 'a getter whose default is the recommendation')
        self.assertIn('Recommend.UPSCALERS, Prefs.rec(c, titleId, Recommend.UPSCALER)', (SRC / 'GameUpscaler.java').read_text(encoding='utf-8'))
        self.assertIn('Recommend.DLSS_MODES, Prefs.rec(c, titleId, Recommend.UPSCALER)', (SRC / 'GameUpscaler.java').read_text(encoding='utf-8'))
        self.assertIn('if (s == null) s = Prefs.recommendedDriver(c, titleId);', (SRC / 'GpuDriver.java').read_text(encoding='utf-8'))
        self.assertNotIn('putString(gameKey(', prefs.split('---- Recommended settings')[1].split('static void backToRecommended')[0], 'a recommendation is never stored')
        act = (SRC / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('Native.note(Recommend.logLine(Prefs.recStates(this, id)));', act)
        self.assertIn('Prefs.rememberFacts(this, probedChoice, vulkan);', act)
        self.assertLess(act.index('Prefs.pinDriver(this, id);'), act.index('settingsEnv(id, genv);'), 'the driver is pinned before the check probes it')
        self.assertIn('Prefs.chosenFpsCap(this, id)', act)
        self.assertIn('Prefs.rememberFacts(app,', (SRC / 'ReadinessActivity.java').read_text(encoding='utf-8'))
        gs = (SRC / 'GameSettings.java').read_text(encoding='utf-8')
        self.assertIn('"Recommended for this phone"', gs); self.assertIn('"Use recommended"', gs); self.assertIn('Prefs.backToRecommended(a, id)', gs)
        self.assertIn('Recommend.line(states,', gs); self.assertIn('"Recommended: " + Prefs.recName(', gs); self.assertIn('Recommend.changedLine(', gs)
        self.assertNotIn('Default · ', gs, 'rows show their value alone')
        self.assertNotIn('Recommended · ', gs)
        self.assertIn('"Default (Settings: "', gs, "a list's first choice still says Default")
        for s in ('RES_PRESET', 'DRIVER', 'UPSCALER', 'HOLD60', 'FPS_CAP', 'DRS', 'VEGETATION', 'SKIP_LAYERS'):
            self.assertIn(f'track(recRows, Recommend.{s}, ', gs, f'{s}: its row carries the tag')
        for t in ('fullVegetation', 'skipLayers', 'hold60'): self.assertIn(f'Prefs.{t}(a, id, on); refresh[0].run();', gs, f'{t}: the tag follows a toggle')
        ui = (SRC / 'Ui.java').read_text(encoding='utf-8')
        self.assertIn('static TextView tag(Context c, CharSequence s, boolean warn)', ui)
        self.assertIn('R.color.bn_warn_tint', ui)


class Rows(unittest.TestCase):
    def test_every_profile(self):
        for g in game.all_games(): recommend.column(g)   # raises on a bad row

    def test_fire_emblem(self):
        self.assertEqual(recommend.column(game.load(FE)), 'driver.id=8,driver.version&0xffc00000=0x80000000,driver.version&0x3ff000<0x320000|driver=18')

    def test_refusals(self):
        g = game.load('0400486024DEC000')   # Ocarina of Time: presets, options, vegetation, skip_layers, a frame step
        ok = lambda *w: recommend.check_row(g, list(w))
        self.assertEqual(ok('any', 'res_preset=performance', 'upscaler=sgsr1', 'hold60=on'), 'any|res_preset=performance upscaler=sgsr1 hold60=on')
        self.assertTrue(ok('feature.meshShader=0', 'vegetation=off', 'skip_layers=on'))
        self.assertTrue(ok('gpu.device&0xff000000=0x44000000,vk.api>=1.3', 'fps_cap=30'))
        for bad in (('any',), ('any', 'upscalr=sgsr1'), ('any', 'upscaler=gsr2'), ('any', 'res_preset=ultra'), ('any', 'hold60=yes'), ('any', 'drs=40'),
                    ('driver.name=Turnip', 'upscaler=sgsr1'), ('gpu.name=Adreno', 'upscaler=sgsr1'), ('driver.id==8', 'upscaler=sgsr1'), ('any', 'driver=turnip'),
                    ('any', 'driver=18', 'upscaler=sgsr1'), ('any', 'option.DM_NOPE=on'), ('any', 'upscaler')):
            with self.assertRaises(recommend.Error, msg=bad): ok(*bad)
        fe = game.load(FE)   # the DLSS stand-in's modes, no presets
        self.assertTrue(recommend.check_row(fe, ['any', 'upscaler=gsr2']))
        for bad in (('any', 'upscaler=sgsr1'), ('any', 'res_preset=quality'), ('any', 'vegetation=off')):
            with self.assertRaises(recommend.Error, msg=bad): recommend.check_row(fe, list(bad))

    def test_rows_cite_notes(self):
        """Each row has the comment above it that says where its fact is measured (a game note or docs/reference)"""
        for g in game.all_games():
            lines = (g.dir / 'game.tsv').read_text(encoding='utf-8').splitlines()
            for i, l in enumerate(lines):
                if l.startswith('recommend\t'):
                    above = lines[i - 1] if i else ''
                    j = i - 1
                    while j >= 0 and lines[j].startswith('#'): j -= 1
                    block = ' '.join(lines[j + 1:i])
                    self.assertTrue(above.startswith('#') and ('README.md' in block or 'docs/reference' in block), f'{g.id}: {l}')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class RecommendJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'stub').mkdir()
            (d / 'stub/BuildConfig.java').write_text(STUB)
            srcs = [SRC / f'{n}.java' for n in ('Recommend', 'Readiness', 'Profile')]
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *map(str, srcs), str(TEST), str(d / 'stub/BuildConfig.java')],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.RecommendJvmTest', str(FACTS), recommend.column(game.load(FE)), recommend.column(game.load(OOT))], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
