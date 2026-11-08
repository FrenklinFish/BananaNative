"""shader_split: the scan keeps one copy of each key as it finds them (Shaders), inflates a large KTGL container into a temporary file instead of memory
(idrk_shaders, SPOOL), shows its progress inside a large file (InFile), and with DM_SPLIT_SELECTED keeps the code of only the selected shaders (KEEP, entry) while
the tables stay those of a full split. Pokémon Pokopia's 2.38 GiB .fdata (203,120 GLSLC outputs, 125,950 keys) ran the phone's split out of memory. No game files needed."""
import contextlib
import io
import os
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '0400A3A01EE4C000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had


def nvn2_output(stage_bits, seed):
    """One NVN2 GLSLC output of one stage (glslc_layout's NVN2 form): header, one GPU code section header at +0xa8, its data (control section, then code area)"""
    code = bytearray(0x100); struct.pack_into('<I', code, 0, stage_bits << 10 | 1); code[0x80:0x84] = struct.pack('<I', seed)
    ctl = bytearray(0x60); ctl[:4] = ss.CTL_MAGIC; ctl[4:8] = b'\xc9\0\0\0'; struct.pack_into('<I', ctl, 0x44, len(code))
    data = bytes(ctl) + bytes(code); soff = 0xa8 + 0x98
    sec = bytearray(0x98); struct.pack_into('<3I', sec, 0, len(data), soff, 0); struct.pack_into('<4I', sec, 0x30, 0, len(ctl), len(code), len(ctl))
    h = bytearray(0xa8); h[:4] = b'\x91\x68\x86\x19'; struct.pack_into('<I', h, 0x30, 0xc9); struct.pack_into('<I', h, 0x5c, soff + len(data)); struct.pack_into('<I', h, 0x64, 1)
    return bytes(h) + bytes(sec) + data


def entry(data, flags=0x400000):
    """An IDRK entry: 0x40-byte header ('IDRK0000', entry length, stored length, length, ..., flags at +0x2c), then the stored data (zlib chunks of at most 16 KB)"""
    if flags & 0x500000:
        stored = b''
        for i in range(0, len(data), 0x4000):
            z = zlib.compress(data[i:i + 0x4000]); stored += struct.pack('<H', len(z)) + bytes(8) + z
        stored += bytes(10)
    else: stored = data
    head = bytearray(0x40); head[:8] = ss.IDRK; struct.pack_into('<QQQ', head, 8, 0x40 + len(stored), len(stored), len(data)); struct.pack_into('<I', head, 0x2c, flags)
    return bytes(head) + stored


def fdata(containers):
    return ss.PDRK + struct.pack('<Q', 0x10) + b''.join(entry(c) for c in containers)


PS, VS = 5, 1
G2S_A = b'_S2G3000' + bytes(8) + nvn2_output(PS, 1) + nvn2_output(VS, 2) + nvn2_output(PS, 1)   # the third: a copy of the first
G2S_B = b'_S2G3000' + bytes(8) + nvn2_output(PS, 3) + nvn2_output(PS, 1)


class Stream(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp()); self.addCleanup(lambda: __import__('shutil').rmtree(self.tmp))
        self.path = self.tmp / '0x38f972af.fdata'; self.path.write_bytes(fdata([G2S_A, G2S_B]))

    def scan(self):
        with contextlib.redirect_stdout(io.StringIO()): return ss.scan_file((str(self.path), 'motor/0x38f972af.fdata'))

    def test_one_copy_of_each_key(self):
        rel, keys, pairs, extra = self.scan()
        self.assertEqual((rel, len(keys), extra), ('motor/0x38f972af.fdata', 3, {}))
        self.assertEqual(sorted(v[1] for v in keys.values()), ['ps', 'ps', 'vs'])
        self.assertTrue(all(v[4][2] and v[4][1] is None for v in keys.values()), 'every shader kept whole, every record sound')
        self.assertIn('#IDRK@', next(iter(keys.values()))[0])

    def test_spooled_container_reads_the_same(self):
        whole = self.scan()
        with mock.patch.object(ss, 'SPOOL', 16):   # every container through a temporary file
            spooled = self.scan()
        self.assertEqual(whole, spooled)

    def test_selected_keeps_only_their_code(self):
        _, keys, _, _ = self.scan()
        keep = sorted(keys)[:1]
        with mock.patch.object(ss, 'KEEP', set(keep)): _, sel, _, _ = self.scan()
        self.assertEqual(sorted(sel), sorted(keys), 'the same keys, so the same tables')
        for k, v in sel.items():
            self.assertEqual(v[4][:2], keys[k][4][:2], 'the same digest and record check')
            if k in keep: self.assertEqual(v[:4], keys[k][:4])
            else: self.assertEqual((len(v[2]), len(v[3]), v[4][2]), (0x60, 0x80, False), 'the first bytes only: the tables need no more')
        self.assertEqual(ss.ps_inputs(sel[sorted(keys)[-1]][3]), ss.ps_inputs(keys[sorted(keys)[-1]][3]))

    def test_progress_inside_a_large_file(self):
        out = io.StringIO()
        with mock.patch.object(ss, 'AT', (3, 79)), mock.patch.object(ss, 'LARGE', 16), contextlib.redirect_stdout(out):
            at = ss.InFile('x', 1000); at.t -= 5; at.now(410)
        self.assertEqual(out.getvalue(), 'progress: 3/79 41%\n')
        out = io.StringIO()
        with mock.patch.object(ss, 'AT', None), contextlib.redirect_stdout(out):
            at = ss.InFile('x', 1 << 40); at.t -= 5; at.now(10)
        self.assertEqual(out.getvalue(), '', 'not in a pool worker (J > 1)')


class Signature(unittest.TestCase):
    """shader_sig agrees with same_shader, which compared the bytes of two copies"""
    def ctl(self, n=0x880, own=0x880, tail=b''):
        c = bytearray(n); c[:4] = ss.CTL_MAGIC; struct.pack_into('<I', c, 0x20, own); return bytes(c) + tail

    def test_agrees(self):
        code = b'\x78\x56\x34\x12' + bytes(0x60)
        a = (self.ctl(), code)
        for name, b in (('equal', (self.ctl(), code)), ('other code', (self.ctl(), code[:-1] + b'\1')),
                        ('bytes past its own length', (self.ctl(0x880, 0x800, b'xx'), code)), ('own length', (self.ctl(0x880, 0x800), code)),
                        ('other control byte', (self.ctl()[:0x100] + b'\1' + self.ctl()[0x101:], code))):
            with self.subTest(name):
                x = a if name != 'bytes past its own length' else (self.ctl(0x880, 0x800), code)
                self.assertEqual(ss.shader_sig(*x) == ss.shader_sig(*b), ss.same_shader(x, b))

    def test_a_key_twice_with_other_bytes_ends_the_split(self):
        s = ss.Shaders()
        ctl = self.ctl(); code = b'\x78\x56\x34\x12' + bytes(0x60)
        s.append(('/a', 'ps', ctl, code)); s.append(('/b', 'ps', ctl, code))
        self.assertEqual((len(s), len(s.uniq)), (2, 1))
        with mock.patch.object(ss, 'skey', return_value=next(iter(s.uniq))), self.assertRaisesRegex(SystemExit, 'is the same but'):
            s.append(('/c', 'ps', ctl, code + b'\1'))

    def test_empty_record(self):
        self.assertEqual(len(ss.EMPTY_RECORD), 0x38)
        self.assertIsNone(ss.record_problem(self.ctl(), b'\x78\x56\x34\x12' + bytes(0x60)))
        self.assertIn('control magic', ss.record_problem(self.ctl(), ss.CTL_MAGIC + bytes(0x60)))


if __name__ == '__main__':
    unittest.main()
