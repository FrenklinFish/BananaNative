"""The Android launcher's GPU driver install (DriverZip.java, GpuDriver's zip step) on the JVM: javac compiles it with android/app/src/jvmtest's DriverZipJvmTest:
a good driver zip installed flat; a zip with a non-UTF-8 entry name, a truncated one, garbage, one without meta.json or without its library, and one with too many
files refused with a reason (an IOException, never an unchecked exception), each leaving the installed driver of that name (the pick) as it was and no .installing
folder; a good zip of the same name replacing it; a killed install's leftovers recovered. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/DriverZipJvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class DriverZipJvm(unittest.TestCase):
    def test_install(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-d', str(d / 'classes'), str(SRC / 'DriverZip.java'), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            (d / 'run').mkdir()
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.DriverZipJvmTest', str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)


if __name__ == '__main__':
    unittest.main()
