"""The launcher's official-build stamp (android/README.md, Signer.java with android/app/src/jvmtest's SignerJvmTest): the signing certificate's SHA-256 as apksigner
prints it, the build tag's mark (official / unofficial build for the play and release apps, none for the test app) and the short signer id the logs and readiness
report carry. The JVM part is skipped without a JDK."""
import hashlib
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/Signer.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/SignerJvmTest.java'
OFFICIAL = 'ad232b1c519e8f14d1f6dbeaa2569847002714306bc93f18924dd3a7e8abe23e'   # the v0.1.0 release APK's certificate (apksigner verify --print-certs)
TAG = 'Banana Native 0.1 · 6a6448d'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Stamp(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='signer_'))
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.tmp / 'classes'), str(SRC), str(TEST)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def java(self, *a):
        r = subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', str(self.tmp / 'classes'), 'dev.nxnative.android.SignerJvmTest', *map(str, a)],
                           capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.rstrip('\n').split(' ', 1)[1]

    def test_official_constant(self):
        self.assertEqual(self.java('official'), OFFICIAL)

    def test_sha256_as_hashlib(self):
        p = self.tmp / 'cert.der'
        p.write_bytes(bytes(range(256)) * 3)
        self.assertEqual(self.java('sha256', p), hashlib.sha256(p.read_bytes()).hexdigest())

    def test_marks(self):
        other = hashlib.sha256(b'someone else').hexdigest()
        self.assertEqual(self.java('tag', TAG, 1, OFFICIAL), TAG + ' · official')
        self.assertEqual(self.java('tag', TAG, 1, OFFICIAL.upper()), TAG + ' · official')
        self.assertEqual(self.java('tag', TAG, 1, other), TAG + ' · unofficial build')
        self.assertEqual(self.java('tag', TAG, 1, OFFICIAL, other), TAG + ' · unofficial build')   # a second signer: not ours
        self.assertEqual(self.java('tag', TAG, 1), TAG + ' · unofficial build')                    # nothing readable
        self.assertEqual(self.java('tag', TAG, 0, OFFICIAL), TAG)                                       # the test app makes no claim
        self.assertEqual(self.java('tag', TAG, 0, other), TAG)

    def test_build_id(self):
        """Home's build identifier and the overlay's build tag come from one function: the tag is the app's name and the identifier"""
        self.assertEqual(self.java('build', 0, '0.1.1-test', '4a150670aa', '-', 'android-launcher-2'),
                         'launcher-2@4a15067|Banana Native test · launcher-2@4a15067')
        self.assertEqual(self.java('build', 0, '0.1.1', 'unknown', '+dirty', 'int'), 'int@unknown+dirty|Banana Native test · int@unknown+dirty')
        self.assertEqual(self.java('build', 1, '0.1-release', '6a6448d123', '-', 'main'), '0.1 · 6a6448d|' + TAG)

    def test_short_id(self):
        other = hashlib.sha256(b'x').hexdigest()
        self.assertEqual(self.java('id', OFFICIAL), OFFICIAL[:16])
        self.assertEqual(self.java('id', OFFICIAL, other), OFFICIAL[:16] + '+1')
        self.assertEqual(self.java('id'), 'unknown')


if __name__ == '__main__':
    unittest.main()
