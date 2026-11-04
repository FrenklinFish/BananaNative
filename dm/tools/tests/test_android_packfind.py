"""The Android launcher's Add game searches (PackFind.java) on the JVM: javac compiles it with Pack, Nup, Zstd, Profile, ShaderLibs, a stub BuildConfig and
android/app/src/jvmtest's PackFindJvmTest. The pack finder: a pack in an ancestor's <title ID>-work/<any name> found, a current library beating an older one, another
game build's pack never chosen, another title's ignored, the app's pack preferred when it fits, nothing above the volume root or past three levels. The folder scan:
several .nups (made by dm/tools/nup_pack.py), dump subfolders, a title without a profile, and folders that are not games. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/PackFindJvmTest.java'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=aa\\t\\t\\t0ld000000001=500\\t"
            + "57a000000001:%s:%s\\t\\n0400C3F00006E000\\tMario Kart World\\t0\\tunknown\\t";
    public static final String NX_DMSH_VK_CHAIN = "c0ffee000001", NX_DMSH_VK_ABI = "abc123abc123";
}
''' % ('1' * 64, '2' * 64)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class PackFindJvm(unittest.TestCase):
    def test_finder_and_scan(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'stub').mkdir()
            (d / 'stub/BuildConfig.java').write_text(STUB)
            srcs = [SRC / f'{n}.java' for n in ('PackFind', 'Pack', 'Nup', 'Zstd', 'Profile', 'ShaderLibs')]
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-d', str(d / 'classes'), *map(str, srcs), str(TEST), str(d / 'stub/BuildConfig.java')],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            (d / 'run').mkdir()
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.PackFindJvmTest', str(REPO), str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)


if __name__ == '__main__':
    unittest.main()
