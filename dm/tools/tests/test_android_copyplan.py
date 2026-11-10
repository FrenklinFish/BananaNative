"""The launcher's Copy into the app (CopyPlan.java with android/app/src/jvmtest's CopyPlanJvmTest; GameCopy does the copying on the phone): where a copy goes,
which provider names are refused, and the free-space check. The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/CopyPlan.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/CopyPlanJvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class CopyPlanJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST)], check=True, capture_output=True, text=True)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.CopyPlanJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
