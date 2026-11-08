"""The launcher's Button mapping (PadMap.java with android/app/src/jvmtest's PadMapJvmTest): the host's default by position, DM_PAD_MAP specs read and written
as dm/host/android/input_map.c reads them, and the editor's swap. The source and Switch button names must match the host's. The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/PadMap.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/PadMapJvmTest.java'
HOST = REPO / 'dm/host/android/input_map.c'


def names(text, var):
    m = re.search(var + r'(?:\[[^\]]*\])?\s*=\s*\{(.*?)\};', text, re.S)
    return re.findall(r'"([A-Z0-9_]+)"', m.group(1))


class Names(unittest.TestCase):
    def test_same_as_host(self):
        j, c = SRC.read_text(), HOST.read_text()
        self.assertEqual(names(j, 'SOURCES'), names(c, 'nxin_src_names'))
        self.assertEqual(names(j, 'SWITCH'), names(c, 'nxin_switch_names'))


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class PadMapJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST)], check=True, capture_output=True, text=True)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.PadMapJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
