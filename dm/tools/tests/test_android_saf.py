"""The launcher without All files access, on the JVM: save backups exported as a .zip and imported from one (SaveZip.java with SaveStore: where a backup goes, the
zip read back and checked, zip slip and limits refused, what an import restores from, a restore from an imported zip) and a game whose file is gone (GameFile.java:
its sentence, picking it again for the same title), by android/app/src/jvmtest's SaveZipJvmTest. A .nup used where it is (PickedPath, Nup.opener) is checked by
test_nup.py's NupJvmTest. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/SaveZipJvmTest.java'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=aa";
    public static final String NX_DMSH_VK_CHAIN = "6607c8d19abc";
    public static final String NX_DMSH_VK_ABI = "01b52a48538b";
    public static final String NX_DMSH_VK_ABI_COMPAT = "";
}
'''


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class SaveZipJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            t = Path(d)
            (t / 'BuildConfig.java').write_text(STUB)
            srcs = [SRC / f'{n}.java' for n in ('SaveZip', 'SaveStore', 'PackZip', 'CopyPlan', 'GameFile', 'GameList', 'Frontend', 'Pack', 'DumpLayout', 'BuildCheck',
                                                'RomfsCheck', 'Nup', 'Zstd', 'Profile', 'ShaderLibs')]
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', str(t / 'classes'), *map(str, srcs), str(TEST), str(t / 'BuildConfig.java')],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            (t / 'run').mkdir()
            r = subprocess.run(['java', '-cp', str(t / 'classes'), 'dev.nxnative.android.SaveZipJvmTest', str(t / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout[-3000:] + r.stderr[-3000:])
            self.assertRegex(r.stdout, r'^ok \d+')


if __name__ == '__main__':
    unittest.main()
