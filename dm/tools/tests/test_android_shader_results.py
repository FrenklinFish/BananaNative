"""The Android app's Retry of the shaders step: ShaderResults.java (android/app/src/ondevice) removes the kept results of shaders that failed, so a rerun of
vk_android_build.py translates them again instead of failing on them every time (DMSH_VK_REQUIRE_ALL). The result files here are written in the tool's own layout
(vk_android_build.py write_result: its RESULT_MAGIC, read from the source, the JSON head's length as uint32 LE, the head, the modules). Skipped without a JDK."""
import json
import re
import shutil
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/ShaderResults.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/ShaderResultsJvmTest.java'
TOOL = REPO / 'dm/gfx/tools/vk_android_build.py'


def magic():
    return re.search(r"^RESULT_MAGIC = b'([^']+)'", TOOL.read_text(encoding='utf-8'), re.M).group(1).encode()


def write_result(d, key, st, err, refl=None, modules=()):
    """As vk_android_build.py write_result (the same keys in the same order, the same separators)"""
    h = {'vk': 'bdf12a2caade', 'inputs': '0123456789ab', 'raw': False, 'val': 'v2025.1', 'key': key, 'stage': 'ps', 'st': st, 'err': err, 'ps_inputs': None,
         'refl': refl, 'modules': [[v, len(b), len(b), 0] for v, b in modules]}
    j = json.dumps(h, separators=(',', ':')).encode()
    with open(d / f'{key}.r', 'wb') as f:
        f.write(magic() + struct.pack('<I', len(j)) + j)
        for _, b in modules: f.write(b)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Retry(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='shres_'))
        cls.classes = cls.tmp / 'classes'
        subprocess.run(['javac', '-d', str(cls.classes), str(SRC), str(TEST)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def run_on(self, d):
        r = subprocess.run(['java', '-cp', str(self.classes), 'dev.nxnative.android.ShaderResultsJvmTest', str(d)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return [l.split(' ', 1)[1].split('\t') for l in r.stdout.splitlines()]

    def test_drops_only_failures(self):
        d = self.tmp / 'xlate'; d.mkdir()
        big = {'bindings': [{'kind': 'ubo', 'set': 0, 'binding': i, 'name': 'x' * 40} for i in range(200)]}   # a head far longer than the start the app reads first
        write_result(d, '00000000000000aa', 'ok', '', big, [('', b'\x03\x02\x23\x07' * 50)])
        write_result(d, '00000000000000bb', 'translation failed', 'out of memory "here"')
        write_result(d, '00000000000000cc', 'spirv-val failed', 'ID 12 has not been defined')
        write_result(d, '00000000000000dd', 'reflection failed', 'ValueError: no slot')
        (d / '00000000000000ee.r').write_bytes(b'DMVKRES1\x00')   # cut short: left for the tool, which rebuilds it
        (d / '00000000000000ff.r').write_bytes(b'not a result')
        (d / 'x.r.tmp').write_bytes(b'')
        got = self.run_on(d)
        self.assertEqual([g[:2] for g in got], [['00000000000000bb', 'translation failed'], ['00000000000000cc', 'spirv-val failed'], ['00000000000000dd', 'reflection failed']])
        self.assertEqual(got[1][2], 'ID 12 has not been defined')
        self.assertEqual(sorted(p.name for p in d.iterdir()), ['00000000000000aa.r', '00000000000000ee.r', '00000000000000ff.r', 'x.r.tmp'])
        self.assertEqual(self.run_on(d), [])   # a second Retry finds nothing more to drop

    def test_no_folder(self):   # a first run: the tool has made no results yet
        self.assertEqual(self.run_on(self.tmp / 'missing'), [])

    def test_status_after_long_head(self):   # "st" past the first 4 KiB (a head whose earlier fields grew): the whole head is read
        d = self.tmp / 'long'; d.mkdir()
        h = {'vk': 'x' * 5000, 'key': '0000000000000011', 'st': 'translation failed', 'err': 'e'}
        j = json.dumps(h, separators=(',', ':')).encode()
        (d / '0000000000000011.r').write_bytes(magic() + struct.pack('<I', len(j)) + j)
        self.assertEqual([g[:2] for g in self.run_on(d)], [['0000000000000011', 'translation failed']])


class Layout(unittest.TestCase):
    def test_magic_and_order(self):   # the app reads the tool's layout: the same magic, and "st" written before the reflection
        src = SRC.read_text(encoding='utf-8')
        self.assertIn(f'"{magic().decode()}"', src)
        tool = TOOL.read_text(encoding='utf-8')
        head = re.search(r"h = \{'vk': TOOLS.*?\}", tool, re.S).group(0)
        self.assertLess(head.index("'st'"), head.index("'refl'"))


if __name__ == '__main__':
    unittest.main()
