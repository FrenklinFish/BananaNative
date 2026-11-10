"""vk_android_build.py's package records found by their keys (locate), and pack_android.py's split check: a shader package from another split numbers its records
otherwise than the profile's shader_map.tsv, so a record read by the map's number alone can be another shader's code. Each record's hash must be its key."""
import ast
import hashlib
import os
import struct
import sys
import tempfile
import types
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'dm/gfx/tools'
sys.path.insert(0, str(REPO / 'scripts'))


def vk_functions(*names):
    """Functions of vk_android_build.py (a script: it cannot be imported), with the globals they use"""
    src = (TOOLS / 'vk_android_build.py').read_text()
    tree = ast.parse(src)
    keep = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names]
    keep += [n for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id in ('SPLIT', 'KEY_FNS', 'KEY_CONSTS') for t in n.targets)
             or isinstance(n, ast.Assign) and any(isinstance(t, ast.Tuple) and any(isinstance(e, ast.Name) and e.id == 'KEY_FNS' for e in t.elts) for t in n.targets)]
    ns = {'ast': ast, 'os': os, 'sys': sys, 'struct': struct, 'hashlib': hashlib, 'HERE': str(TOOLS)}
    keep.sort(key=lambda n: n.lineno)
    exec(compile(ast.Module(body=keep, type_ignores=[]), 'vk_android_build.py', 'exec'), ns)
    return ns


NS = vk_functions('borrow_key', 'record_key', 'locate')
SKEY = NS['borrow_key']((0, 0))


def split_empty_record():
    """shader_split.py's EMPTY_RECORD: the record DM_SPLIT_SELECTED writes for a shader whose code the package does not keep (no code area, no control section)"""
    tree = ast.parse((TOOLS / 'shader_split.py').read_text())
    node = next(n for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'EMPTY_RECORD' for t in n.targets))
    return eval(compile(ast.Expression(node.value), 'shader_split.py', 'eval'), {'bytes': bytes})


EMPTY = split_empty_record()


def nvn2_ctl(tag):   # an NVN2 control section (version 0xc9), its own length at +0x30, which the key hashes as zero
    c = bytearray(0x60); c[:4] = bytes.fromhex('34127698'); c[4:8] = b'\xc9\0\0\0'; struct.pack_into('<I', c, 0x30, 0xd8); c[0x50:0x58] = tag.ljust(8, b'.')
    return bytes(c)


def record(tag):
    code = b'\x78\x56\x34\x12' + tag.ljust(0x40, b'-'); ctl = nvn2_ctl(tag)
    rec = bytearray(b'BTCDBTCD' + bytes(0x30)); struct.pack_into('<2I', rec, 0x28, len(ctl), len(code))
    key = hashlib.sha256(ctl[:0x30] + bytes(4) + ctl[0x34:0x48] + code).hexdigest()[:16]
    return bytes(rec + code + ctl), key


class Keys(unittest.TestCase):
    def test_record_key_is_the_splits_key(self):
        rec, key = record(b'a')
        self.assertEqual(NS['record_key'](rec, SKEY), key)
        self.assertIsNone(NS['record_key'](b'not a record' * 8, SKEY))
        self.assertIsNone(NS['record_key'](rec[:0x50], SKEY), 'a cut record has no key')

    def test_an_empty_record_has_no_key(self):
        # a selected split's record of a shader whose code it does not keep: hashing it read control +0x20 of an empty section (struct.error, Pokopia on update 327680)
        self.assertEqual(len(EMPTY), 0x38)
        self.assertIsNone(NS['record_key'](EMPTY, SKEY))
        rec = bytearray(EMPTY); struct.pack_into('<2I', rec, 0x28, 0x10, 4); rec += bytes(0x14)
        self.assertIsNone(NS['record_key'](bytes(rec), SKEY), 'a control section too short to hold its own length (+0x20) has no key')


class Locate(unittest.TestCase):
    def setUp(self):
        self.r = {t: record(t) for t in (b'a', b'b', b'c', b'd')}
        self.k = {t: v[1] for t, v in self.r.items()}

    def test_the_maps_package(self):
        files = {'ps_x.bin': [self.r[b'a'][0], self.r[b'b'][0]], 'vs_x.bin': [self.r[b'c'][0]]}
        loc = {self.k[b'a']: ('ps_x.bin', 0), self.k[b'b']: ('ps_x.bin', 1), self.k[b'c']: ('vs_x.bin', 0)}
        found, bad = NS['locate'](list(loc), loc, files, SKEY)
        self.assertEqual((found, bad), (loc, []))

    def test_another_split_is_read_by_key(self):
        # a split that scanned more (base and DLC RomFS): an extra shader first, the others shifted and one in another file of its stage
        files = {'ps_x.bin': [self.r[b'd'][0], self.r[b'a'][0]], 'ps_y.bin': [self.r[b'b'][0]]}
        loc = {self.k[b'a']: ('ps_x.bin', 0), self.k[b'b']: ('ps_x.bin', 1)}
        found, bad = NS['locate'](list(loc), loc, files, SKEY)
        self.assertEqual(bad, [])
        self.assertEqual(found, {self.k[b'a']: ('ps_x.bin', 1), self.k[b'b']: ('ps_y.bin', 0)})

    def test_a_shader_the_package_lacks_stops(self):
        files = {'ps_x.bin': [self.r[b'b'][0]], 'vs_x.bin': [self.r[b'a'][0]]}
        loc = {self.k[b'a']: ('ps_x.bin', 0)}   # another shader at the map's place, and its own record only under another stage
        found, bad = NS['locate'](list(loc), loc, files, SKEY)
        self.assertEqual(found, {}); self.assertEqual(len(bad), 1); self.assertIn(self.k[b'a'], bad[0])

    def test_a_selected_split_of_another_build(self):
        # DM_SPLIT_SELECTED on a build the map was not made from (Pokopia 327680, map of 655360): the records are numbered otherwise, the shaders not kept are empty
        # records, and one key of the map is not in this build at all. The index of the whole package skips the empty records instead of hashing them
        files = {'ps_x.bin': [EMPTY, self.r[b'd'][0], EMPTY, self.r[b'b'][0]], 'ps_y.bin': [EMPTY, self.r[b'a'][0]]}
        loc = {self.k[b'a']: ('ps_x.bin', 0), self.k[b'b']: ('ps_x.bin', 1), self.k[b'c']: ('ps_x.bin', 2)}
        found, bad = NS['locate'](list(loc), loc, files, SKEY)
        self.assertEqual(found, {self.k[b'a']: ('ps_y.bin', 1), self.k[b'b']: ('ps_x.bin', 3)})
        self.assertEqual(len(bad), 1); self.assertIn(self.k[b'c'], bad[0])

    def test_records_read_lazily(self):
        reads = []
        def lazy(rec):
            def f(): reads.append(1); return rec
            return f
        files = {'ps_x.bin': [lazy(self.r[b'a'][0]), lazy(self.r[b'b'][0])]}
        loc = {self.k[b'b']: ('ps_x.bin', 1)}
        found, bad = NS['locate'](list(loc), loc, files, SKEY)
        self.assertEqual((found, bad, len(reads)), (loc, [], 1), 'a record at its place is the only one read')


class OpenPacks(unittest.TestCase):
    """open_packs on a package that lacks keys of the map: it stops the build, except with DMSH_VK_REQUIRE_ALL=0 (the phone's Try anyway), which leaves them out"""
    def setUp(self):
        self.r = {t: record(t) for t in (b'a', b'b', b'c')}
        self.k = {t: v[1] for t, v in self.r.items()}
        ns = vk_functions('borrow_key', 'record_key', 'locate', 'open_packs')
        recs = {'ps_x.bin': [EMPTY, self.r[b'b'][0], self.r[b'a'][0]]}   # a selected split of another build: c is not in it
        ns.update(packs={'ps_x.bin': (None, list(range(4)))}, index_packs=lambda: None, close_packs=lambda: None, _rec=lambda n, j: recs[n][j],
                  GAME={}, dm_dir='pkg', MISSING=set(), TOTAL=[3], ORDER=[self.k[b'a'], self.k[b'b'], self.k[b'c']],
                  loc={self.k[b'a']: ('ps_x.bin', 0), self.k[b'b']: ('ps_x.bin', 1), self.k[b'c']: ('ps_x.bin', 2)})
        self.ns = ns
        self.env = os.environ.get('DMSH_VK_REQUIRE_ALL')

    def tearDown(self):
        if self.env is None: os.environ.pop('DMSH_VK_REQUIRE_ALL', None)
        else: os.environ['DMSH_VK_REQUIRE_ALL'] = self.env

    def test_stops_by_default_and_with_require_all(self):
        for v in (None, '1'):
            if v is None: os.environ.pop('DMSH_VK_REQUIRE_ALL', None)
            else: os.environ['DMSH_VK_REQUIRE_ALL'] = v
            with self.assertRaises(SystemExit) as e: self.ns['open_packs'](list(self.ns['ORDER']))
            self.assertIn('1 of the 3 shaders to build are not in the package', str(e.exception.code))

    def test_try_anyway_builds_without_them(self):
        os.environ['DMSH_VK_REQUIRE_ALL'] = '0'
        a, b, c = self.k[b'a'], self.k[b'b'], self.k[b'c']
        self.assertEqual(self.ns['open_packs']([a, b, c]), [a, b])
        self.assertEqual((self.ns['MISSING'], self.ns['ORDER'], self.ns['TOTAL']), ({c}, [a, b], [2]))
        self.assertEqual((self.ns['loc'][a], self.ns['loc'][b]), (('ps_x.bin', 2), ('ps_x.bin', 1)))

    def test_real_package_files_and_windows_wrapper(self):
        """index_packs, _rec and close_packs on a real .bin package (with CR LF and 0x1a bytes in the shader code, which Windows text mode alters, and
        os.pread from vk_android_win.install on Windows)"""
        import concurrent.futures as cf, mmap
        sys.path.insert(0, str(TOOLS))
        import vk_android_win
        if os.name == 'nt': vk_android_win.install()
        ra, ka = record(b'a\r\n\x1ab')
        rb, kb = record(b'c\x1a\r\nd')
        ns = vk_functions('borrow', 'borrow_key', 'record_key', 'locate', 'index_packs', '_rec', 'open_packs', 'close_packs')
        ns['BUILD'], ns['BORROWED'] = str(TOOLS / 'dmsh_build.py'), ('record_index',)
        ns['NS'] = ns['borrow']({'os': os, 're': __import__('re'), 'mmap': mmap})
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / 'ps_x.bin').write_bytes(ra + rb)
            ns.update(packs={}, dm_dir=d, GAME={}, MISSING=set(), TOTAL=[2], ORDER=[ka, kb], loc={ka: ('ps_x.bin', 0), kb: ('ps_x.bin', 1)})
            try:
                self.assertEqual(ns['open_packs']([ka, kb]), [ka, kb])
                with cf.ThreadPoolExecutor(4) as ex:
                    got = list(ex.map(lambda i: ns['_rec']('ps_x.bin', i % 2), range(32)))
                self.assertEqual(got, [ra if i % 2 == 0 else rb for i in range(32)])
            finally:
                ns['close_packs']()


class SplitCheck(unittest.TestCase):
    """pack_android.py split_shaders: the dump's split must hold every shader of the profile's map"""
    def run_split(self, profile_keys, split_keys):
        import pack_android
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); (d / 'prof').mkdir()
            row = lambda i, k: f'ps_x.bin\t{i}\tpixel\t{k}\t-\n'
            (d / 'prof/shader_map.tsv').write_text('# map\n' + ''.join(row(i, k) for i, k in enumerate(profile_keys)))
            def fake_run(cmd, env=None, **kw):
                out = Path(cmd[4]); out.mkdir(parents=True)
                (Path(cmd[5])).write_text('# map\n' + ''.join(row(i, k) for i, k in enumerate(split_keys)))
                for p in cmd[6:]: Path(p).write_text('')
                return types.SimpleNamespace(returncode=0)
            real_run, real_py = pack_android.subprocess.run, pack_android.shader_python
            pack_android.subprocess.run, pack_android.shader_python = fake_run, (lambda *a, **k: sys.executable)
            try:
                pack_android.split_shaders(types.SimpleNamespace(shader_python=None), types.SimpleNamespace(id='0100000000000000', dir=d / 'prof'), d / 'ver', d / 'shaders/pkg')
                return (d / 'shaders/pkg').is_dir()
            finally: pack_android.subprocess.run, pack_android.shader_python = real_run, real_py

    def test_superset_split_is_kept(self):
        self.assertTrue(self.run_split(['aa', 'bb'], ['cc', 'aa', 'bb']))

    def test_split_lacking_a_shader_stops(self):
        import pack_android
        with self.assertRaises(pack_android.Error) as e: self.run_split(['aa', 'bb'], ['aa', 'cc'])
        self.assertIn('lack 1 of the 2', str(e.exception))


if __name__ == '__main__':
    unittest.main()
