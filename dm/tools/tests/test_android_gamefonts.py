"""The launcher's shared-font stand-ins (GameFonts.java with android/app/src/jvmtest's GameFontsJvmTest): the APK carries Noto Sans Regular (dm/third_party/fonts), the launcher places it as
<files>/fonts/standard.ttf and passes DM_FONT_DIR, so shared-font text does not depend on the phone's fonts (issue #223). The JVM part is skipped without a JDK."""
import hashlib
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
JAVA = REPO / 'android/app/src/main/java/dev/nxnative/android'
SRC = JAVA / 'GameFonts.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/GameFontsJvmTest.java'
FONTS = REPO / 'dm/third_party/fonts'


class Wiring(unittest.TestCase):
    def test_activity_sets_the_folder_before_the_players_lines(self):
        t = (JAVA / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('GameFonts.envLine(', t)
        self.assertLess(t.index('GameFonts.envLine('), t.index('env.addAll(user);'))   # the player's DM_FONT_DIR line comes later and wins

    def test_gradle_copies_the_font_from_third_party(self):
        g = (REPO / 'android/app/build.gradle').read_text(encoding='utf-8')
        self.assertIn("'dm/third_party/fonts'", g)
        self.assertIn('NotoSans-Regular-subset.ttf', g)

    def test_asset_name_exists(self):
        self.assertTrue((FONTS / Path('NotoSans-Regular-subset.ttf')).is_file())
        self.assertTrue((FONTS / 'OFL.txt').is_file())

    def test_font_matches_its_record(self):
        # dm/third_party/fonts/README.md names the file's size and sha256; a changed font needs the record changed
        readme = (FONTS / 'README.md').read_text(encoding='utf-8')
        data = (FONTS / 'NotoSans-Regular-subset.ttf').read_bytes()
        self.assertIn(hashlib.sha256(data).hexdigest(), readme)
        self.assertIn(f'{len(data):,} bytes', readme)
        self.assertEqual(data[:4], b'\x00\x01\x00\x00')   # plain TrueType: not a collection, not CFF
        self.assertNotIn(b'fvar', data[:512])             # static: no variations table in the directory


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class GameFontsJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.GameFontsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
