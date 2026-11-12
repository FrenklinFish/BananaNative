"""vk_android_build.py's result files (<RES>/<key>.r): written whole (temporary name, fsync, rename), and a file shorter or longer than its head says, what a stop left
on storage that lost the end of a renamed file (#82), is not a result: the start's check (result_file) has it translated again, and read_result raises ResultCut for one
cut later, which the build translates again instead of stopping. The functions are taken from the script (it cannot be imported) with stand-in globals."""
import ast
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'dm/gfx/tools'
NAMES = ('ResultCut', 'Stored', 'stored_bytes', 'result_path', 'result_file', 'result_head', 'write_result', 'read_result', 'sync_results', '_tmp_owner_gone')


def vk_functions(res):
    src = (TOOLS / 'vk_android_build.py').read_text()
    keep = [n for n in ast.parse(src).body if isinstance(n, (ast.FunctionDef, ast.ClassDef)) and n.name in NAMES
            or isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'RESULT_MAGIC' for t in n.targets)]
    ns = {'os': os, 'sys': sys, 'json': json, 'struct': struct, 'RES': str(res), 'TOOLS': 'vk0', 'INPUTS': 'in0', 'RAW': False, 'VAL_ID': 'val0',
          'STAGE_OF': {}, 'VK': {'CODEC_ZSTD': 1}, 'zstd': None}
    exec(compile(ast.Module(body=keep, type_ignores=[]), 'vk_android_build.py', 'exec'), ns)
    return ns


class Results(unittest.TestCase):
    def setUp(self):
        self.res = Path(tempfile.mkdtemp(prefix='vkres_'))
        self.ns = vk_functions(self.res)

    def tearDown(self):
        shutil.rmtree(self.res, ignore_errors=True)

    def write(self, key, mods=((0, b'\x03\x02\x23\x07' * 8), (1, b'\x03\x02\x23\x07' * 3))):
        self.ns['STAGE_OF'][key] = 'ps'
        self.ns['write_result'](key, 'ok', '', ({'stage': 'ps'}, [(v, len(b), b, 0, None) for v, b in mods]), {'in0'})
        return self.res / f'{key}.r'

    def test_whole_file(self):
        p = self.write('00000000000000aa')
        self.assertEqual(self.ns['result_file'](str(p))[1], False)
        self.assertEqual(self.ns['result_head'](str(p))['ps_inputs'], ['in0'])
        key, st, _, (refl, mods) = self.ns['read_result']('00000000000000aa')
        self.assertEqual((key, st, refl), ('00000000000000aa', 'ok', {'stage': 'ps'}))
        self.assertEqual([self.ns['stored_bytes'](m[2]) for m in mods], [b'\x03\x02\x23\x07' * 8, b'\x03\x02\x23\x07' * 3])
        self.assertEqual([n for n in os.listdir(self.res)], ['00000000000000aa.r'])   # no temporary file left

    def test_cut_modules(self):   # #82: the head whole, the modules not: not a result, so the shader is translated again
        p = self.write('00000000000000bb')
        with open(p, 'r+b') as f: f.truncate(p.stat().st_size - 5)
        self.assertEqual(self.ns['result_file'](str(p)), (None, True))
        self.assertIsNone(self.ns['result_head'](str(p)))
        with self.assertRaises(self.ns['ResultCut']): self.ns['read_result']('00000000000000bb')

    def test_cut_head(self):
        p = self.write('00000000000000cc')
        with open(p, 'r+b') as f: f.truncate(20)
        self.assertEqual(self.ns['result_file'](str(p)), (None, True))

    def test_longer_than_head(self):   # bytes past the modules: not what write_result made
        p = self.write('00000000000000dd')
        with open(p, 'ab') as f: f.write(b'\0' * 16)
        self.assertEqual(self.ns['result_file'](str(p)), (None, True))

    def test_stale_is_not_cut(self):   # another build's result: rebuilt, but not reported as cut
        p = self.write('00000000000000ee')
        self.ns['TOOLS'] = 'vk1'
        self.assertEqual(self.ns['result_file'](str(p)), (None, False))

    def test_failure_without_modules(self):
        self.ns['STAGE_OF']['00000000000000ff'] = 'vs'
        self.ns['write_result']('00000000000000ff', 'translation failed', 'no spv', None, None)
        h, cut = self.ns['result_file'](str(self.res / '00000000000000ff.r'))
        self.assertEqual((h['st'], cut), ('translation failed', False))

    def test_not_a_result(self):
        (self.res / '0000000000000011.r').write_bytes(b'not a result')
        self.assertEqual(self.ns['result_file'](str(self.res / '0000000000000011.r')), (None, False))
        self.assertEqual(self.ns['result_file'](str(self.res / 'missing.r')), (None, False))

    def test_kept_shaders_one_sync(self):   # a delta's kept shaders: written unflushed and unnamed, then one os.sync for all of them before they take their names
        keys = [f'{i:016x}' for i in range(1, 6)]; tmps = []
        for k in keys:
            self.ns['STAGE_OF'][k] = 'ps'
            t = self.ns['write_result'](k, 'ok', '', ({'stage': 'ps', 'reused': 'v0'}, [(0, 8, b'\x03\x02\x23\x07' * 2, 0, None)]), None, sync=False)
            self.assertTrue(t.endswith('.tmp') and os.path.isfile(t)); tmps.append((t, k))
        self.assertEqual([n for n in os.listdir(self.res) if n.endswith('.r')], [])   # no result under its name before the sync
        order = []
        with mock.patch.object(os, 'sync', lambda: order.append('sync'), create=True), mock.patch.object(os, 'fsync', side_effect=AssertionError('an fsync each')), \
                mock.patch.object(os, 'replace', side_effect=lambda a, b, _r=os.replace: (order.append('replace'), _r(a, b))):
            self.ns['sync_results'](tmps)
        self.assertEqual(order, ['sync'] + ['replace'] * len(keys))
        self.assertEqual(sorted(os.listdir(self.res)), [k + '.r' for k in keys])
        for k in keys: self.assertEqual(self.ns['result_file'](str(self.res / f'{k}.r'))[0]['reused'], 'v0')

    def test_kept_shaders_without_os_sync(self):   # Windows: an fsync each, still before the rename
        self.ns['STAGE_OF']['0000000000000077'] = 'ps'
        t = self.ns['write_result']('0000000000000077', 'ok', '', ({'stage': 'ps'}, []), None, sync=False)
        n = []
        real = os.fsync
        with mock.patch.object(os, 'sync', create=True) as sy:
            del os.sync
            try:
                with mock.patch.object(os, 'fsync', side_effect=lambda fd: (n.append(fd), real(fd))): self.ns['sync_results']([(t, '0000000000000077')])
            finally: os.sync = sy
        self.assertEqual(len(n), 1); self.assertTrue((self.res / '0000000000000077.r').is_file())

    def test_stale_temporary_files(self):   # a stopped build's (this process, or one that ended) go at the start; a running build's stay
        gone = self.ns['_tmp_owner_gone']
        self.assertTrue(gone(f'0000000000000001.r.{os.getpid()}.tmp'))
        self.assertTrue(gone('0000000000000001.r.notapid.tmp'))
        if os.name != 'nt':
            self.assertFalse(gone(f'0000000000000001.r.{os.getppid()}.tmp'))
            child = subprocess.Popen([sys.executable, '-c', '']); child.wait()   # a pid whose process has ended
            self.assertTrue(gone(f'0000000000000001.r.{child.pid}.tmp'))
            self.assertTrue(gone('0000000000000001.r.99999999999.tmp'))   # no pid is that large

    def test_flushed_before_rename(self):   # the order that keeps a renamed file whole
        src = (TOOLS / 'vk_android_build.py').read_text()
        body = src[src.index('def write_result('):src.index('def read_result(')]
        self.assertLess(body.index('os.fsync('), body.index('os.replace('))


if __name__ == '__main__':
    unittest.main()
