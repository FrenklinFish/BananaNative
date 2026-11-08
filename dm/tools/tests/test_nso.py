"""dm/tools/nso.py, the one loader of code modules: a damaged NSO or NRO is an nso.Error before anything its header claims is allocated (GitHub issue #451), never a short image"""
import hashlib
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import tracemalloc
import unittest
from unittest import mock

import lz4.block

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import nso

TEXT, RO, DATA = bytes(range(256)) * 20, b'read-only ' * 300, b'\x01\x02' * 0x900


def page(x): return (x + 0xfff) & ~0xfff


def make_nso(segs=(TEXT, RO, DATA), compress=(1, 0, 1), bss=0x5000, pack=lambda s: lz4.block.compress(s, store_size=False)):
    """An NSO of the three segments, back to back on pages from 0 with their hashes, as Nintendo's tools write them (pack compresses a segment: LZ4, or ZBIC with flag bit 7)"""
    hdr, body, mo = bytearray(0x100), b'', 0
    hdr[:4] = b'NSO0'; hdr[0x40:0x60] = bytes(range(0x20))
    for i, s in enumerate(segs):
        stored = pack(s) if compress[i] else s
        struct.pack_into('<III', hdr, 0x10 + i * 0x10, 0x100 + len(body), mo, len(s)); struct.pack_into('<I', hdr, 0x60 + 4 * i, len(stored))
        hdr[0xa0 + 0x20 * i:0xc0 + 0x20 * i] = hashlib.sha256(s).digest()
        body += stored; mo = page(mo + len(s))
    struct.pack_into('<I', hdr, 0xc, 0x38 | sum(c << i for i, c in enumerate(compress))); struct.pack_into('<I', hdr, 0x3c, bss)
    return bytearray(hdr + body)


def make_nro(segs=(TEXT, RO, DATA)):
    """An NRO as nn::ro takes it: whole pages from 0, the file as long as the module"""
    img, at = bytearray(), []
    for s in segs:
        o = len(img); img += s; img += bytes(page(len(img)) - len(img)); at.append((o, len(img) - o))
    img[0x10:0x14] = b'NRO0'; struct.pack_into('<I', img, 0x18, len(img))
    for i, (o, sz) in enumerate(at): struct.pack_into('<II', img, 0x20 + 8 * i, o, sz)
    struct.pack_into('<I', img, 0x38, 0x3000); img[0x40:0x60] = bytes(0x20)
    return img, at


def seg(d, i, **kw):   # rewrite text (0), ro (1) or data (2) in the header: fo, mo, sz, stored
    fo, mo, sz = struct.unpack_from('<III', d, 0x10 + i * 0x10)
    fo, mo, sz = kw.get('fo', fo), kw.get('mo', mo), kw.get('sz', sz)
    struct.pack_into('<III', d, 0x10 + i * 0x10, fo, mo, sz)
    if 'stored' in kw: struct.pack_into('<I', d, 0x60 + 4 * i, kw['stored'])
    return d


def flags(d, set_=0, clear=0):
    f = struct.unpack_from('<I', d, 0xc)[0]; struct.pack_into('<I', d, 0xc, (f | set_) & ~clear); return d


class Load(unittest.TestCase):
    def test_nso(self):
        m = nso.load(make_nso())
        ro, data = page(len(TEXT)), page(page(len(TEXT)) + len(RO))
        self.assertEqual(m.segments, ((0, len(TEXT)), (ro, len(RO)), (data, len(DATA))))
        self.assertEqual(len(m.image), data + len(DATA))   # bss is a size only
        self.assertEqual((m.image[:len(TEXT)], m.image[ro:ro + len(RO)], m.image[data:]), (TEXT, RO, DATA))
        self.assertEqual((m.bss, m.text_end, m.build_id), (0x5000, len(TEXT), bytes(range(0x20))))

    def test_nro(self):
        d, at = make_nro()
        m = nso.load(d)
        self.assertEqual(m.segments, tuple(at)); self.assertEqual(bytes(m.image), bytes(d)); self.assertEqual(m.bss, 0x3000)

    def error(self, d, *words):
        with self.assertRaises(nso.Error) as c: nso.load(d, 'main')
        for w in ('main',) + words: self.assertIn(w, str(c.exception))
        return c.exception

    def test_huge_segment_is_refused_before_allocating(self):   # the fuzzing case: a header that lays out gigabytes in a few hundred bytes
        tracemalloc.start()
        try:
            self.error(seg(make_nso(), 0, sz=0xFFFFF000), 'text segment', 'more than Horizon loads')
            self.error(seg(seg(make_nso(), 0, sz=0x7FFFF000), 1, mo=0x80000000), 'text segment', 'more than LZ4 can produce')
            self.error(seg(make_nso(), 2, mo=0xFFFFE000), 'span', 'more than a file of')   # a gap Horizon allows, but bigger than the file could fill
            self.assertLess(tracemalloc.get_traced_memory()[1], 1 << 20)
        finally: tracemalloc.stop()

    def test_layout_as_horizon(self):   # pages, text at 0, ro then data without overlap; a gap is allowed
        ro = page(len(TEXT))
        self.error(seg(make_nso(), 1, mo=ro + 0x10), 'ro segment', 'not on a page')
        self.error(seg(make_nso(), 0, mo=0x1000), 'text segment', 'not at 0')
        self.error(seg(make_nso(), 1, mo=0), 'ro segment', 'overlaps the text segment')
        self.error(seg(make_nso(), 2, mo=ro), 'data segment', 'overlaps the ro segment')
        m = nso.load(seg(make_nso(), 2, mo=page(ro + len(RO)) + 0x2000))
        self.assertEqual(m.image[page(ro + len(RO)) + 0x2000:], DATA); self.assertFalse(any(m.image[ro + len(RO):page(ro + len(RO)) + 0x2000]))

    def test_short_segment_is_an_error(self):   # the fuzzing case: an LZ4 stream that decompresses to less than its size, which used to shrink the image without a word
        d = make_nso()
        self.error(seg(d, 2, sz=len(DATA) + 0x100), 'data segment', 'decompresses to')
        d = make_nso()
        fo = struct.unpack_from('<I', d, 0x10)[0]; stored = struct.unpack_from('<I', d, 0x60)[0]
        d[fo + stored - 8:fo + stored] = b'\xff' * 8   # a damaged stream
        self.error(d, 'text segment')

    def test_damaged_bytes_of_the_right_size(self):   # caught by the segment hash when its flag is set (every retail module), as on the Switch
        d = make_nso(); fo = struct.unpack_from('<I', d, 0x20)[0]
        d[fo + 5] ^= 1
        self.error(d, 'ro segment', 'SHA-256')
        m = nso.load(flags(d, clear=0x10))   # flag cleared, as in a modified main: not checked
        self.assertEqual(m.image[page(len(TEXT)) + 5], RO[5] ^ 1)

    def test_cut_short(self):
        d = make_nso()
        self.error(d[:-10], 'data segment', 'past the end of the file')
        self.error(d[:0x80], 'header is cut short')
        d, at = make_nro()
        self.error(d[:-1], 'the file is cut short')
        self.error(b'NSP0' + bytes(0x100), 'not an NSO or NRO')

    def test_nro_layout_as_nn_ro(self):
        d, at = make_nro()
        struct.pack_into('<I', d, 0x2c, at[1][1] - 0x10); self.error(d, 'ro segment', 'whole pages')
        d, at = make_nro(); struct.pack_into('<I', d, 0x18, len(d) + 0x1000); self.error(d, "the module's size")

    def test_uncompressed_is_read_at_its_size(self):   # Horizon ignores the stored size of an uncompressed segment
        d = make_nso()
        self.assertEqual(nso.load(seg(d, 1, stored=0)).image, nso.load(make_nso()).image)
        d = make_nso(); c = struct.unpack_from('<I', d, 0x68)[0]
        self.error(seg(d, 2, sz=c - 1), 'data segment', 'more than its')   # a compressed one is never larger stored than loaded

    def test_compression_that_fails_is_named(self):
        # flag bit 7 makes every compressed segment ZBIC (system 22.0.0 on): these segments are LZ4, so the Error names ZBIC, whether zbic is installed or not
        self.error(flags(make_nso(), set_=0x80), 'ZBIC')
        d, _ = make_nro(); struct.pack_into('<I', d, 0x1c, 2); self.error(d, 'compressed NRO')

    def test_zbic(self):   # system 22.0.0+ (The Legend of Zelda: Ocarina of Time's modules): flag bit 7, every compressed segment ZBIC, with each decoder there is
        try: import zbic   # pyright: ignore[reportMissingImports]
        except ImportError: self.skipTest('no zbic (android/environment.yml)')
        for name, env in decoders():
            with self.subTest(name), mock.patch.dict(os.environ, env):
                d = flags(make_nso(pack=zbic.compress), set_=0x80)
                self.assertEqual(nso.load(d).image, nso.load(make_nso()).image)
                fo, _, sz = struct.unpack_from('<III', d, 0x10); c = struct.unpack_from('<I', d, 0x60)[0]
                bad = bytearray(d); bad[fo + c // 2] ^= 0xff
                self.error(bytes(bad), 'text segment')   # a damaged stream: the decoder's own error or the hash check, never a wrong image
                d2 = flags(make_nso(pack=lambda s: zbic.compress(s[:-1])), set_=0x80)
                self.error(d2, 'text segment', 'ZBIC decompresses to')   # a stream of the wrong length

    def test_zbic_vector(self):   # a committed ZBIC stream (zbic.compress of synthetic text, chosen so that its tables include low-probability counts): libzbicdec
        lib = zbicdec()
        if not lib: self.skipTest('no C compiler for libzbicdec')
        text, z = synthetic(24000, 3), (Path(__file__).parent / 'zbic_synthetic.zbic').read_bytes()
        try: import zbic   # pyright: ignore[reportMissingImports]
        except ImportError: pass
        else: self.assertEqual(zbic.decompress(z), text)
        d = flags(make_nso(segs=(text, RO, DATA), pack=lambda s: z if s == text else lz4.block.compress(s, store_size=False), compress=(1, 0, 0)), set_=0x80)
        with mock.patch.dict(os.environ, {nso.ZBIC_LIB_ENV: lib}):
            self.assertEqual(bytes(nso.load(d).image[:len(text)]), text)
            fo = struct.unpack_from('<I', d, 0x10)[0]
            for at in (5, 40, len(z) // 2, len(z) - 3):   # damaged in a table description, the literals, the sequences, the end
                bad = bytearray(d); bad[fo + at] ^= 0x5a
                self.error(bad, 'text segment')
            self.error(seg(bytearray(d), 0, sz=len(text) - 0x10), 'text segment', 'more than the')   # the header claims less than the stream holds
            self.error(seg(bytearray(d), 0, sz=len(text) + 0x10), 'text segment', 'ZBIC decompresses to')

    def test_zbicdec_equals_zbic(self):   # libzbicdec and PyPI's zbic decode zbic.compress's streams alike: sizes, levels, kinds of data
        try: import zbic   # pyright: ignore[reportMissingImports]
        except ImportError: self.skipTest('no zbic (android/environment.yml)')
        if not (lib := zbicdec()): self.skipTest('no C compiler for libzbicdec')
        with mock.patch.dict(os.environ, {nso.ZBIC_LIB_ENV: lib}):
            decode = nso.zbic_decoder()
            for n, seed in ((0, 1), (1, 1), (100, 2), (5000, 3), (24000, 3), (200000, 4)):
                for data in (synthetic(n, seed), bytes(range(256)) * (n // 256), os.urandom(n)):
                    for lv in (1, 3, 9, 19):
                        c = zbic.compress(data, lv)
                        out, err = decode(c, len(data))
                        self.assertIsNone(err); self.assertEqual(bytes(out), data); self.assertEqual(zbic.decompress(c), data)

    def test_zbic_missing_is_named(self):   # with neither decoder: the Error names both (the packer's message on a PC that lacks them)
        d = flags(make_nso(), set_=0x80)
        env = {k: v for k, v in os.environ.items() if k != nso.ZBIC_LIB_ENV}
        with mock.patch.dict(sys.modules, {'zbic': None}), mock.patch.dict(os.environ, env, clear=True), mock.patch.object(sys, 'platform', 'darwin'):
            self.error(d, 'text segment', 'libzbicdec', nso.ZBIC_LIB_ENV, 'pip install zbic')
        with mock.patch.dict(os.environ, {nso.ZBIC_LIB_ENV: '/nonexistent/libzbicdec.so'}):   # a library named but not there: no silent fallback
            self.error(d, nso.ZBIC_LIB_ENV, 'does not load')


def synthetic(n, seed):
    """Text-like bytes from a fixed generator (words, line ends, now and then a stray byte): the input of zbic_synthetic.zbic is synthetic(24000, 3)"""
    words = [b'the', b'zbic', b'frame', b'table', b'count', b'segment', b'module', b'banana', b'native', b'x', b'qq', b'lorem', b'ipsum']
    x, out = seed, bytearray()
    while len(out) < n:
        x = (x * 6364136223846793005 + 1442695040888963407) & (2**64 - 1)
        out += words[(x >> 33) % len(words)] + (b' ' if (x >> 20) & 7 else b'\n') + (bytes([(x >> 40) & 0xff]) if (x >> 13) & 31 == 0 else b'')
    return bytes(out[:n])


_zbicdec = []


def zbicdec():
    """A build of libzbicdec (dm/third_party/zstd-zbic/build.sh with this machine's cc) in a temporary folder, made once; None without a compiler"""
    if not _zbicdec:
        import atexit, shutil
        lib = None
        if shutil.which('cc'):
            tmp = tempfile.mkdtemp(); atexit.register(shutil.rmtree, tmp, True)
            lib = os.path.join(tmp, 'libzbicdec.dylib' if sys.platform == 'darwin' else 'libzbicdec.so')
            r = subprocess.run(['sh', str(REPO / 'dm/third_party/zstd-zbic/build.sh'), lib, 'cc', '-Wall', '-Werror'], capture_output=True, text=True)
            if r.returncode: raise AssertionError(f'libzbicdec does not build:\n{r.stdout}{r.stderr}')
        _zbicdec.append(lib)
    return _zbicdec[0]


def decoders():
    """(name, environment) of each ZBIC decoder here: libzbicdec when it builds, and the zbic package (no DM_ZBIC_LIB)"""
    out = [('zbic package', {nso.ZBIC_LIB_ENV: ''})]
    if lib := zbicdec(): out.insert(0, ('libzbicdec', {nso.ZBIC_LIB_ENV: lib}))
    return out


class Tools(unittest.TestCase):
    """The scripts that read a module stop on a damaged one with a [RED] line, not a traceback"""
    def test_red_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / 'main'; bad.write_bytes(seg(make_nso(), 2, sz=len(DATA) + 0x100))
            for argv in (['dm/tools/patch_nso.py', bad, tmp, 'main'], ['dm/tools/nso.py', bad, Path(tmp) / 'main.bin'], ['dm/tools/insn_census.py', bad],
                         ['dm/tools/nso2elf.py', bad, Path(tmp) / 'main.elf'], ['dm/tools/loader_census.py', bad], ['dm/tools/nso_code.py', bad]):
                r = subprocess.run([sys.executable, *argv], cwd=REPO, capture_output=True, text=True, env=dict(os.environ, DM_GAME=''))
                self.assertNotEqual(r.returncode, 0, argv[0])
                self.assertNotIn('Traceback', r.stderr, argv[0]); self.assertTrue(r.stderr.startswith('[RED] '), (argv[0], r.stderr))

    def test_image_with_sparse_bss(self):   # nso.py's output file: the whole memory image, bss as a zero tail it never allocates
        with tempfile.TemporaryDirectory() as tmp:
            src, out = Path(tmp) / 'main', Path(tmp) / 'main.bin'; src.write_bytes(make_nso())
            subprocess.run([sys.executable, 'dm/tools/nso.py', src, out], cwd=REPO, check=True, capture_output=True)
            img = out.read_bytes(); m = nso.read(src)
            self.assertEqual(len(img), len(m.image) + 0x5000); self.assertEqual(img[:len(m.image)], m.image); self.assertFalse(any(img[len(m.image):]))


if __name__ == '__main__':
    unittest.main()
