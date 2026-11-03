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

    def test_records_read_lazily(self):
        reads = []
        def lazy(rec):
            def f(): reads.append(1); return rec
            return f
        files = {'ps_x.bin': [lazy(self.r[b'a'][0]), lazy(self.r[b'b'][0])]}
        loc = {self.k[b'b']: ('ps_x.bin', 1)}
        found, bad = NS['locate'](list(loc), loc, files, SKEY)
        self.assertEqual((found, bad, len(reads)), (loc, [], 1), 'a record at its place is the only one read')


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
