"""The Android launcher's Export pack… / Import pack… (PackZip.java) on the JVM: javac compiles it with Pack, Nup, Profile, ShaderLibs, SaveStore, a stub BuildConfig
and android/app/src/jvmtest's PackZipJvmTest: an export and import round trip (a stored zip, read back; the imported pack equal to the exported one file by file),
zip slip refused with nothing written, a pack of a title without a profile (or of another game than the sheet's) refused, unfinished code or no library refused,
and the swap that keeps the current pack until the new one has its name. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/PackZipJvmTest.java'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=aa\\n0400C3F00006E000\\tMario Kart World\\t0\\tunknown\\t";
    public static final String NX_DMSH_VK_CHAIN = "c0ffee", NX_DMSH_VK_ABI = "abi1";
}
'''


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class PackZipJvm(unittest.TestCase):
    def test_export_import(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'stub').mkdir()
            (d / 'stub/BuildConfig.java').write_text(STUB)
            srcs = [SRC / f'{n}.java' for n in ('PackZip', 'Pack', 'Nup', 'Zstd', 'Profile', 'ShaderLibs', 'SaveStore')]
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-d', str(d / 'classes'), *map(str, srcs), str(TEST), str(d / 'stub/BuildConfig.java')],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            (d / 'run').mkdir()
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.PackZipJvmTest', str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)


if __name__ == '__main__':
    unittest.main()
