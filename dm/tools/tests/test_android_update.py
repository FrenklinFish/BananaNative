"""The Android launcher's in-app updater (Updater, android/README.md "Updates"): UpdateCheck.java's decisions on the JVM with android/app/src/jvmtest's
UpdateCheckJvmTest (version numbers, the release it picks, the asset digest, the release notes' Markdown: ReleaseNotes.java, also on the real v0.1.3
body; the release sources: GitHub and git.eden-emu.dev, both APIs' JSON with Json.java, also on a recorded Forgejo answer, the newest across sources,
a source that fails, the asset host allow-list; the cache-busting of the list's URL), and the manifest's install permission. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/UpdateCheckJvmTest.java'
BODY = REPO / 'android/app/src/jvmtest/release-v0.1.3.md'   # gh release view v0.1.3 -R moceure/BananaNative --json body
FORGEJO = REPO / 'android/app/src/jvmtest/forgejo-releases-v0.2.0.json'   # GET https://git.eden-emu.dev/api/v1/repos/moceure/BananaNative/releases, body and author trimmed
MANIFEST = REPO / 'android/app/src/main/AndroidManifest.xml'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class UpdateCheckJvm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', cls.tmp.name, str(SRC / 'UpdateCheck.java'), str(SRC / 'Json.java'), str(SRC / 'ReleaseNotes.java'), str(TEST)],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def java(self, *a):
        r = subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', self.tmp.name, 'dev.nxnative.android.UpdateCheckJvmTest', *a], capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return r.stdout

    def test_decisions(self):
        self.assertTrue(self.java().startswith('ok '))

    def test_sources(self):
        self.assertTrue(self.java('sources', str(FORGEJO)).startswith('ok '))

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
        src = (SRC / 'UpdateCheck.java').read_text() + (SRC / 'Updater.java').read_text()
        self.assertIn('/releases?per_page=10', src)
        self.assertIn('/api/v1/repos/', src)
        self.assertNotIn('/releases/latest"', src)   # in a string, not the comment that says why
        self.assertIn('browser_download_url', src)

    def test_list_not_cached(self):
        """The releases list is asked through a URL with a fresh query parameter and Cache-Control: no-cache (a shared cache in front of git.eden-emu.dev
        served a two-hour-old list after v0.2.3); the download is not (the asset link is new per release and the file is checked)"""
        src = (SRC / 'Updater.java').read_text()
        self.assertIn('open(UpdateCheck.busted(s.api, Long.toString(System.currentTimeMillis())), s.accept, s.name, API_CONNECT_MS, API_READ_MS, true)', src)
        self.assertIn('c.setRequestProperty("Cache-Control", "no-cache")', src)
        self.assertIn('c.setRequestProperty("Pragma", "no-cache")', src)
        self.assertIn('open(u.apk.url, "application/octet-stream", u.release.source.name, DL_CONNECT_MS, DL_READ_MS, false)', src)

    def test_install_checks(self):
        """The download is checked against its source's hosts; the install session is limited to this package (Android checks the signer)"""
        src = (SRC / 'Updater.java').read_text()
        self.assertIn('UpdateCheck.allowedAsset(u.apk.url, u.release.source)', src)
        self.assertIn('sp.setAppPackageName(', src)

    def test_matcher_append_api33(self):
        """Matcher.appendReplacement / appendTail take a StringBuilder only from API 34 (the launcher supports Android 13, API 33): the JVM test runs on a newer
        runtime and cannot catch it, so the launcher's sources must pass a StringBuffer (issue 252: a crash at start whenever the notes had an image)"""
        import re
        for f in sorted(SRC.glob('*.java')):
            t = f.read_text()
            if not re.search(r'\.append(Replacement|Tail)\(', t):
                continue
            self.assertNotIn('StringBuilder b = new StringBuilder()', t.split('htmlImages(String s)')[-1].split('}')[0] if f.name == 'ReleaseNotes.java' else '', f.name)
            self.assertIn('StringBuffer', t, f.name)


if __name__ == '__main__':
    unittest.main()
