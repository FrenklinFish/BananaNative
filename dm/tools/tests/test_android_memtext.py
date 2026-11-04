"""The in-game overlay's memory lines (android/README.md, Overlay; MemText.java with android/app/src/jvmtest's MemTextJvmTest): VmRSS read from a
/proc/self/status text, and the game's and the phone's lines, in GiB. The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/MemText.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/MemTextJvmTest.java'
GIB = 1 << 30
STATUS = 'Name:\tdev.nxnative.an\nVmPeak:\t 99999999 kB\nVmHWM:\t 5000000 kB\nVmRSS:\t 4508876 kB\nRssAnon:\t 4000000 kB\nThreads:\t87\n'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class MemText(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='memtext_'))
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.tmp / 'classes'), str(SRC), str(TEST)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def java(self, *a):
        r = subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', str(self.tmp / 'classes'), 'dev.nxnative.android.MemTextJvmTest', *map(str, a)],
                           capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.rstrip('\n').split(' ', 1)[1]

    def status(self, text):
        p = self.tmp / 'status'
        p.write_text(text)
        return int(self.java('rss', p))

    def test_vmrss(self):
        self.assertEqual(self.status(STATUS), 4508876 * 1024)
        self.assertEqual(self.status('Name:\tx\nVmRSS:\t0 kB\n'), 0)
        self.assertEqual(self.status('Name:\tx\nThreads:\t1\n'), -1)       # a kernel thread has no VmRSS
        self.assertEqual(self.status('Name:\tVmRSS: 5 kB\n'), -1)          # only a line's own key counts

    def test_overlay_lines(self):
        self.assertEqual(self.java('app', 4508876 * 1024), 'App 4.3 GiB')
        self.assertEqual(self.java('app', -1), 'App ? GiB')
        self.assertEqual(self.java('system', int(14.7 * GIB), int(4.9 * GIB)), 'RAM 9.8/14.7 GiB')
        self.assertEqual(self.java('system', 0, 0), 'RAM ?')
        self.assertEqual(self.java('system', 2 * GIB, 3 * GIB), 'RAM 0.0/2.0 GiB')   # availMem above totalMem never reads negative


if __name__ == '__main__':
    unittest.main()
