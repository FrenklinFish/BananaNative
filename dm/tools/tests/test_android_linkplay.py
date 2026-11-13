"""Link Play's saved choice to environment mapping, using the same plain JVM seam as other launcher settings tests."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/LinkPlayJvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class LinkPlay(unittest.TestCase):
    def test_join_configuration(self):
        with tempfile.TemporaryDirectory(prefix='linkplay_') as tmp:
            stub = Path(tmp) / 'BuildConfig.java'
            stub.write_text('package dev.nxnative.android; public final class BuildConfig { public static final String NX_PROFILES = ""; }', encoding='utf-8')
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', tmp, str(SRC / 'LinkPlayConfig.java'),
                            str(SRC / 'Profile.java'), str(TEST), str(stub)], check=True)
            result = subprocess.run(['java', '-cp', tmp, 'dev.nxnative.android.LinkPlayJvmTest'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('configuration checks passed', result.stdout)


if __name__ == '__main__':
    unittest.main()
