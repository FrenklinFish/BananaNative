"""Profile option rows and environment variable generation (Profile.java with android/app/src/jvmtest's
ProfileOptionsJvmTest, and build.gradle's optionsOf). The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/Profile.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/ProfileOptionsJvmTest.java'
GRADLE = REPO / 'android/app/build.gradle'
OOT_TSV = REPO / 'dm/games/0400486024DEC000/game.tsv'

STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "";
}
'''


class OptionTsv(unittest.TestCase):
    def test_oot_options_in_game_tsv(self):
        rows = [line.split('\t') for line in OOT_TSV.read_text(encoding='utf-8').splitlines() if line.startswith('option\t')]
        self.assertEqual(len(rows), 3, f'OoT must declare 3 options: {rows}')
        envs = [r[1].strip() for r in rows]
        self.assertEqual(envs, ['DM_OOT_MAX_RESOLUTION', 'DM_OOT_TEXTURE_POOL_X2', 'DM_OOT_FPS_UNLOCK'])
        labels = [r[2].strip() for r in rows]
        self.assertEqual(labels, ['Max resolution', 'Texture pool ×2', 'Unlocked frame rate'])
        # Check attributes
        max_res_attrs = [tok for col in rows[0][4:] for tok in col.split()]
        self.assertIn('restart', max_res_attrs)
        self.assertIn('implies=DM_OOT_TEXTURE_POOL_X2', max_res_attrs)
        self.assertIn('needs=OOT_PATCHES', max_res_attrs)

        pool_attrs = [tok for col in rows[1][4:] for tok in col.split()]
        self.assertIn('restart', pool_attrs)
        self.assertIn('needs=OOT_PATCHES', pool_attrs)

        fps_attrs = [tok for col in rows[2][4:] for tok in col.split()]
        self.assertIn('restart', fps_attrs)
        self.assertIn('needs=OOT_PATCHES', fps_attrs)

    def test_gradle_options_of_parser(self):
        gradle = GRADLE.read_text(encoding='utf-8')
        self.assertIn("def optionsOf = { String game ->", gradle)
        self.assertIn("optionsOf(g)", gradle)
        self.assertIn("implies=", gradle)
        self.assertIn("needs=", gradle)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ProfileOptionsJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            stub = Path(d) / 'BuildConfig.java'
            stub.write_text(STUB)
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST), str(stub)],
                           check=True, capture_output=True, text=True)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.ProfileOptionsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
