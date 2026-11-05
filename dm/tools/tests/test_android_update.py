"""The Android launcher's in-app updater (Updater, android/README.md "Updates"): UpdateCheck.java's decisions on the JVM with android/app/src/jvmtest's
UpdateCheckJvmTest (version numbers, the release it picks, the asset digest, the release notes' Markdown: ReleaseNotes.java, also on the real v0.1.3
body), and the manifest's install permission. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/UpdateCheckJvmTest.java'
BODY = REPO / 'android/app/src/jvmtest/release-v0.1.3.md'   # gh release view v0.1.3 -R moceure/BananaNative --json body
MANIFEST = REPO / 'android/app/src/main/AndroidManifest.xml'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class UpdateCheckJvm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', cls.tmp.name, str(SRC / 'UpdateCheck.java'), str(SRC / 'ReleaseNotes.java'), str(TEST)],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def java(self, *a):
        r = subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', self.tmp.name, 'dev.nxnative.android.UpdateCheckJvmTest', *a], capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return r.stdout

    def test_decisions(self):
        self.assertTrue(self.java().startswith('ok '))

    def test_real_notes(self):
        text, rest = self.java('notes', str(BODY)).split('\n--\n')
        kinds, spans = rest.strip().split('\n')
        self.assertTrue(text.startswith('Banana Native v0.1.3\nInstall over v0.1.2: saves, packs and settings are kept.\n• 040074A01BF12000: 30 fps on Turnip'), text)
        self.assertEqual(kinds, 'HEADING2 PARAGRAPH0 BULLET0 BULLET0 BULLET0 PARAGRAPH0 PARAGRAPH0')
        for marker in ('**', '`', '## '): self.assertNotIn(marker, text)
        self.assertIn('BOLD:One shader update after installing.;', spans)
        self.assertIn('BOLD:Ready;', spans)
        self.assertIn('CODE:410a01c004110180fba08c81ab721bbff9203d5fee94cb06d090fb113ef05790;', spans)


class UpdaterSource(unittest.TestCase):
    def test_manifest(self):
        self.assertIn('android.permission.REQUEST_INSTALL_PACKAGES', MANIFEST.read_text())

    def test_list_not_latest(self):
        """Every release is a pre-release, which /releases/latest skips: the updater reads the list"""
        src = (SRC / 'Updater.java').read_text()
        self.assertIn('/releases?per_page=10', src)
        self.assertNotIn('/releases/latest"', src)   # in a string, not the comment that says why
        self.assertIn('browser_download_url', src)


if __name__ == '__main__':
    unittest.main()
