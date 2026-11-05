"""dm/tools/nup.py, the NUP reader (android/NUP.md), against files written by the clean-room writer dm/tools/nup_pack.py: raw, zstd, empty and shared (dedup) files,
the package selectors and paths after them, romfs.py's views of a package (read, seek, mmap), damaged files refused (SPEC §6.1), and the tools that take a game folder
(pack_dump.py, pack_android.py, romfs_image.py) giving the same result from the folder and from the .nup of it. No game files needed."""
import hashlib
import io
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(REPO / 'dm/tools'), str(REPO / 'scripts')]
import nup
import romfs
import romfs_image
import pack_dump
import nso

TID = 0x040074A01BF12000   # Donkey Kong Bananza: a profile in dm/games (modules main sdk)
VER = 458752
DLC = TID + 0x1001


def have_zstd():
    try: nup.zstd(); return True
    except nup.Error: return False


def npdm(tid):
    d = bytearray(0x100)
    d[:4] = b'META'
    struct.pack_into('<II', d, 0x70, 0x80, 0x20)
    d[0x80:0x84] = b'ACI0'
    struct.pack_into('<Q', d, 0x90, tid)
    return bytes(d)


def module(tag):
    """A valid NSO whose text differs by tag: three uncompressed segments on pages from 0 with their hashes (as test_nso.make_nso, without lz4)"""
    hdr, body, mo = bytearray(0x100), b'', 0
    hdr[:4] = b'NSO0'; hdr[0x40:0x60] = hashlib.sha256(tag).digest()
    for i, s in enumerate((bytes(range(256)) * 20 + tag, b'read-only ' * 300, b'\x01\x02' * 0x900)):
        struct.pack_into('<III', hdr, 0x10 + i * 0x10, 0x100 + len(body), mo, len(s)); struct.pack_into('<I', hdr, 0x60 + 4 * i, len(s))
        hdr[0xa0 + 0x20 * i:0xc0 + 0x20 * i] = hashlib.sha256(s).digest()
        body += s; mo = (mo + len(s) + 0xfff) & ~0xfff
    struct.pack_into('<I', hdr, 0xc, 0x38); struct.pack_into('<I', hdr, 0x3c, 0x5000)
    return bytes(hdr + body)


BIG = bytes(range(256)) * 4096 + b'tail'   # 1 MiB + 4: compresses (zstd), and is larger than the SMALL the streaming tests set


def dump(root):
    """A folder dump (pack_dump.py's layout) with every kind of file the writer stores: compressible (zstd), incompressible (raw), empty, equal files (one extent)"""
    rnd = hashlib.sha256(b'seed').digest() * 64   # 2 KiB that zstd does not shrink much: stored raw
    for v, sub in ((0, 'base'), (VER, f'updates/{VER}')):
        d = root / sub
        (d / 'exefs').mkdir(parents=True); (d / 'romfs/Shader/deep/er').mkdir(parents=True); (d / 'romfs/Empty').mkdir()
        (d / 'exefs/main').write_bytes(module(b'main%d' % v)); (d / 'exefs/sdk').write_bytes(module(b'sdk'))
        (d / 'exefs/main.npdm').write_bytes(npdm(TID)); (d / 'exefs/.listing').write_text('dump tool listing')
        (d / 'romfs/a.txt').write_text(f'version {v}\n' * 100)
        (d / 'romfs/random.bin').write_bytes(rnd)
        (d / 'romfs/zero').touch()
        (d / 'romfs/Shader/big.bin').write_bytes(BIG)
        (d / 'romfs/Shader/deep/er/same1').write_bytes(b'same bytes ' * 50); (d / 'romfs/Shader/deep/er/same2').write_bytes(b'same bytes ' * 50)
    (root / f'updates/{VER}/romfs/only_in_update').write_text('new')
    (root / 'dlc/040074a01bf13001/0/romfs').mkdir(parents=True)
    (root / 'dlc/040074a01bf13001/0/romfs/dlcfile').write_text('dlc ' * 10)


def write_nup(src, out, *args):
    r = subprocess.run([sys.executable, str(REPO / 'dm/tools/nup_pack.py'), str(src), str(out), *args], capture_output=True, text=True)
    if r.returncode: raise AssertionError(r.stdout + r.stderr)
    return out


class Fixture(unittest.TestCase):
    raw_only = False

    @classmethod
    def setUpClass(cls):
        cls.td = tempfile.TemporaryDirectory()
        cls.root = Path(cls.td.name).resolve()
        cls.dump = cls.root / 'Game'; dump(cls.dump)
        if not cls.raw_only and not have_zstd(): raise unittest.SkipTest('no zstd module (Python 3.14 or backports.zstd)')
        cls.nup = write_nup(cls.dump, cls.root / 'Game.nup', *(['--raw'] if cls.raw_only else []))

    @classmethod
    def tearDownClass(cls): cls.td.cleanup()

    def tree(self, top):   # {relative path: bytes or None for a directory} under a romfs.Path or a folder
        out = {}
        for p in sorted(top.rglob('*')):
            out[p.relative_to(top).as_posix()] = None if p.is_dir() else p.read_bytes()
        return out


class Read(Fixture):
    def test_packages_and_codecs(self):
        n = nup.open_nup(self.nup)
        self.assertEqual([(p.selector, p.title_id, p.version) for p in n.packages], [('base', TID, 0), (f'update/{VER}', TID, VER), (f'addon/{DLC:016x}', DLC, 0)])
        codecs = {nup.FILE.unpack_from(n.toc, n.o_file + nup.FILE.size * i)[6] for i in range(n.nf)}
        self.assertEqual(codecs, {nup.RAW, nup.ZSTD})   # random.bin and the empty files raw, the rest zstd
        k = n.node(f'update/{VER}/romfs/Shader/deep/er/same1'); k2 = n.node(f'update/{VER}/romfs/Shader/deep/er/same2')
        self.assertEqual(n.record(k)[1:3], n.record(k2)[1:3])   # one extent for equal files
        self.assertEqual(n.read(n.node('base/romfs/zero')), b'')
        self.assertTrue(all(n.verify(n.node(f'update/{VER}/romfs/{f}')) for f in ('a.txt', 'random.bin', 'zero', 'Shader/big.bin')))
        self.assertFalse((romfs.Path(f'{self.nup}:base/exefs') / '.listing').exists())   # the writer leaves dump-tool listings out (SPEC §6.3)

    def test_same_tree_as_the_folders(self):
        for sel, folder in (('base', 'base'), (f'update/{VER}', f'updates/{VER}'), (f'addon/{DLC:016x}', 'dlc/040074a01bf13001/0')):
            for sub in ('exefs', 'romfs'):
                if not (self.dump / folder / sub).is_dir(): continue
                want = {k: v for k, v in self.tree(self.dump / folder / sub).items() if not k.endswith('.listing')}
                self.assertEqual(self.tree(romfs.Path(f'{self.nup}:{sel}/{sub}')), want, f'{sel}/{sub}')

    def test_selectors(self):
        n = nup.open_nup(self.nup)
        self.assertEqual(n.select('').kind, nup.UPDATE); self.assertEqual(n.select(None).version, VER)   # no selector: the highest update
        self.assertEqual(n.select('base').kind, nup.BASE)
        self.assertEqual(n.select(f'addon/{DLC:016X}').title_id, DLC)   # hex in either case
        for bad in ('update/1', 'addon/0000000000000001'):
            with self.assertRaisesRegex(nup.Error, 'no package'): n.select(bad)
        for bad in ('updates/458752', 'update/x', 'addon/123', 'base/'):
            with self.assertRaisesRegex(nup.Error, 'not a package selector'): n.select(bad)
        self.assertEqual(nup.split(f'/a/b.nup:c/x.nup:update/{VER}/romfs'), ('/a/b.nup:c/x.nup', f'update/{VER}/romfs'))   # at the last '.nup:'
        self.assertIsNone(nup.split('/a/b/romfs'))
        self.assertEqual(nup.default_romfs(self.nup), f'{self.nup}:update/{VER}/romfs')
        self.assertEqual(nup.default_romfs(f'{self.nup}:base'), f'{self.nup}:base/romfs')
        self.assertEqual(nup.default_romfs(f'{self.nup}:base/romfs'), f'{self.nup}:base/romfs')
        self.assertEqual(nup.default_romfs(self.dump / 'base/romfs'), str(self.dump / 'base/romfs'))
        root = romfs.Path(f'{self.nup}:')
        self.assertEqual(sorted(p.name for p in root.iterdir()), ['addon', 'base', 'update'])
        self.assertEqual([p.name for p in (root / 'update').iterdir()], [str(VER)])
        self.assertTrue(romfs.Path(f'{self.nup}:update/{VER}/exefs/main').is_file())
        self.assertFalse(romfs.Path(f'{self.nup}:update/1/romfs').exists())
        self.assertFalse(romfs.Path(f'{self.nup}:base/romfs/only_in_update').exists())
        self.assertEqual(romfs.Path(f'{self.nup}:addon/{DLC:016x}/romfs/dlcfile').read_text(), 'dlc ' * 10)
        self.assertEqual(romfs.os.listdir(f'{self.nup}:addon/{DLC:016x}'), ['romfs'])   # an add-on has no exefs

    def test_streams_seek_and_mmap(self):
        for small in (nup.SMALL, 1000):   # in memory, and streamed (a seek back starts the frame again)
            with mock.patch.object(nup, 'SMALL', small):
                p = f'{self.nup}:update/{VER}/romfs/Shader/big.bin'
                self.assertEqual(romfs.os.path.getsize(p), len(BIG))
                with romfs.open(p, 'rb') as f:
                    f.seek(1000); self.assertEqual(f.read(10), BIG[1000:1010])
                    f.seek(5); self.assertEqual(f.read(3), BIG[5:8])
                    f.seek(-4, 2); self.assertEqual(f.read(), b'tail')
                    f.seek(len(BIG) + 7); self.assertEqual(f.read(), b'')
                    with romfs.mmap.mmap(f.fileno(), 0, access=romfs.mmap.ACCESS_READ) as m:   # a zstd file through a temporary file
                        self.assertEqual(len(m), len(BIG)); self.assertEqual(m[:], BIG); self.assertEqual(m.find(b'tail'), len(BIG) - 4)
                    f.seek(2); self.assertEqual(f.read(2), BIG[2:4])
                with romfs.open(f'{self.nup}:update/{VER}/romfs/a.txt') as f: self.assertEqual(f.readline(), f'version {VER}\n')
        with romfs.open(f'{self.nup}:base/romfs/random.bin', 'rb') as f, romfs.mmap.mmap(f.fileno(), 0, access=romfs.mmap.ACCESS_READ) as m:   # raw: the .nup's own pages
            self.assertEqual(m[:], (self.dump / 'base/romfs/random.bin').read_bytes())
        with self.assertRaises(OSError): romfs.open(f'{self.nup}:base/romfs/a.txt', 'wb')
        with self.assertRaises(IsADirectoryError): romfs.open(f'{self.nup}:base/romfs', 'rb')
        with self.assertRaises(FileNotFoundError): romfs.open(f'{self.nup}:base/romfs/missing', 'rb')

    def test_rewriter_reads_a_plain_copy(self):   # nso.py is one of the rewriter's files (pack_android.REWRITER): it stays as it is, the module is copied out for it
        import pack_android
        out = self.root / 'rsrc'; self.addCleanup(shutil.rmtree, out, True)
        src = romfs.Path(f'{self.nup}:update/{VER}/exefs/main'); plain = self.dump / f'updates/{VER}/exefs/main'
        f = pack_android.rewriter_src(src, out, 'main')
        self.assertTrue(f.is_file() and f.name == 'main' and f.read_bytes() == plain.read_bytes())
        self.assertEqual(nso.read(f).image, nso.read(plain).image)
        self.assertEqual(pack_android.rewriter_src(plain, out, 'main'), plain)   # a folder module is read where it is


class RawOnly(Fixture):
    raw_only = True

    def test_no_zstd_module_needed(self):   # a raw-only file reads with no zstd module at all; a zstd file then names the missing module
        code = ('import sys; sys.modules["compression.zstd"] = None; sys.modules["backports"] = None; sys.path.insert(0, sys.argv[1]); import romfs, nup\n'
                'p = romfs.Path(sys.argv[2] + ":update/458752/romfs"); print(sum(len(f.read_bytes()) for f in p.rglob("*") if f.is_file()))\n')
        r = subprocess.run([sys.executable, '-I', '-c', code, str(REPO / 'dm/tools'), str(self.nup)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(int(r.stdout), sum(f.stat().st_size for f in (self.dump / f'updates/{VER}/romfs').rglob('*') if f.is_file()))
        n = nup.open_nup(self.nup)
        self.assertEqual({nup.FILE.unpack_from(n.toc, n.o_file + nup.FILE.size * i)[6] for i in range(n.nf)}, {nup.RAW})


class Damaged(Fixture):
    def bad(self, data, name):
        p = self.root / name; p.write_bytes(data); self.addCleanup(p.unlink)
        return p

    def toc_fixed(self, d):   # the TOC's SHA-256 written again after a change to it, so the check after the hash is the one that refuses
        toc_size = struct.unpack_from('<Q', d, 0x10)[0]
        d[0x18:0x38] = hashlib.sha256(d[0x80:0x80 + toc_size]).digest()
        return d

    def test_refused(self):
        good = bytearray(self.nup.read_bytes()); n = nup.open_nup(self.nup)
        cases = [('truncated', good[:-100], 'bytes, the superblock says'), ('short', good[:0x40], 'shorter than its superblock'),
                 ('magic', b'NUQ' + good[3:], 'not a NUP file'), ('major', b'NUP\x02' + good[4:], 'format 2')]
        d = bytearray(good); d[0x80 + 0x40] ^= 1; cases.append(('toc hash', d, 'SHA-256'))
        d = bytearray(good); struct.pack_into('<I', d, 0x4C, 2); cases.append(('sb flags', d, 'unknown superblock flags'))
        f0 = next(i for i in range(n.nf) if n.record(n.nd + i)[0])   # a file with content
        d = bytearray(good); d[0x80 + n.o_file + nup.FILE.size * f0 + 0x40] = 3; cases.append(('codec', self.toc_fixed(d), 'unknown codec 3'))
        d = bytearray(good); d[0x80 + n.o_pkg + 0x18] = 5; cases.append(('kind', self.toc_fixed(d), 'unknown kind 5'))
        d = bytearray(good); d[0x80 + n.o_pkg + 0x19] = 2; cases.append(('pkg flags', self.toc_fixed(d), 'unknown flags'))
        d = bytearray(good); struct.pack_into('<Q', d, 0x80 + n.o_file + nup.FILE.size * f0 + 0x10, len(good) - 8)
        cases.append(('extent', self.toc_fixed(d), 'extent out of the blob region'))
        d = bytearray(good); struct.pack_into('<I', d, 0x80 + n.o_pkg + 0x10, n.nd + 5); cases.append(('dir index', self.toc_fixed(d), 'out of range'))
        for name, data, msg in cases:
            with self.subTest(name), self.assertRaisesRegex(nup.Error, msg): nup.Nup(self.bad(bytes(data), f'{name}.nup'))

    def test_damaged_zstd_frame(self):   # a frame whose bytes are wrong: an Error (or zstd's own), never short data taken as the file
        d = bytearray(self.nup.read_bytes()); n = nup.open_nup(self.nup)
        size, off, stored, _, codec = n.record(n.node(f'update/{VER}/romfs/Shader/big.bin'))
        self.assertEqual(codec, nup.ZSTD)
        d[off + stored // 2:off + stored // 2 + 16] = bytes(16)
        p = self.bad(bytes(d), 'frame.nup'); m = nup.Nup(p)
        k = m.node(f'update/{VER}/romfs/Shader/big.bin')
        try: same = m.read(k) == BIG
        except Exception: same = False
        self.assertFalse(same and m.verify(k))


class Tools(Fixture):
    def test_pack_dump_scan(self):
        app, vers, dlc = pack_dump.scan(self.nup)
        self.assertEqual((app, {v: str(p) for v, p in vers.items()}, {t: str(p) for t, p in dlc.items()}),
                         (TID, {VER: f'{self.nup}:update/{VER}'}, {DLC: f'{self.nup}:addon/{DLC:016x}/romfs'}))
        self.assertEqual(str(pack_dump.version_dir(self.nup, vers, 0)), f'{self.nup}:base')
        self.assertEqual(pack_dump.scan(self.dump)[0], app)

    def test_pack_version_and_dlc_images_match_the_folder(self):
        out = self.root / 'packed'; self.addCleanup(shutil.rmtree, out, True)
        quiet = lambda s: None
        for tag, src in (('folder', self.dump), ('nup', self.nup)):
            app, vers, dlc = pack_dump.scan(src)
            pack_dump.pack_version(pack_dump.version_dir(src, vers, VER), app, out / tag / 'v', quiet)
            pack_dump.pack_dlc(app, dlc, out / tag / 'aoc', quiet)
        for f in ('v/ExeFS/main', 'v/ExeFS/sdk', 'v/ExeFS/main.npdm', 'v/RomFS.img', 'aoc/1.img'):
            self.assertEqual((out / 'nup' / f).read_bytes(), (out / 'folder' / f).read_bytes(), f)
        self.assertEqual(sorted(p.name for p in (out / 'nup/v/ExeFS').iterdir()), ['main', 'main.npdm', 'sdk'])
        before = os.stat(out / 'nup/v/RomFS.img').st_mtime_ns   # a reuse keeps the image from the .nup as it does one from a folder
        romfs_image.pack(f'{self.nup}:update/{VER}/romfs', out / 'nup/v/RomFS.img', reuse=True)
        self.assertEqual(os.stat(out / 'nup/v/RomFS.img').st_mtime_ns, before)

    def test_pack_android_same_pack(self):
        try: import capstone, lz4.block   # noqa: F401  (pack_android.py checks them before any work)
        except ImportError: self.skipTest('no capstone / lz4 (android/environment.yml)')
        import pack_android
        def fake_rewrite(py, srcs, out, jobs, g, in_process=False):   # the rewriter's input as it reads it (nso.py), as its output
            for m, src in srcs.items():
                (out / f'{m}.patched.bin').write_bytes(bytes(nso.read(str(pack_android.rewriter_src(src, out, m))).image)); (out / f'{m}.android.key').write_text(pack_android.rewrite_key(src, g) + '\n')
        packs = {}
        for tag, src in (('folder', self.dump), ('nup', self.nup)):
            out = self.root / f'pack-{tag}'; self.addCleanup(shutil.rmtree, out, True)
            argv = ['pack_android.py', str(src), '--out', str(out), '--no-shaders', '--in-process']
            with mock.patch.object(sys, 'argv', argv), mock.patch.object(pack_android, 'rewrite', fake_rewrite), mock.patch('sys.stdout', io.StringIO()):
                pack_android.main()
            packs[tag] = out
        rows = {t: (p / 'pack.tsv').read_text().splitlines() for t, p in packs.items()}
        self.assertIn(f'romfs\tupdates/{VER}/romfs', rows['folder']); self.assertIn('aoc\t1\tdlc/040074a01bf13001/0/romfs', rows['folder'])
        self.assertIn(f'romfs\tGame.nup:update/{VER}/romfs', rows['nup']); self.assertIn(f'aoc\t1\tGame.nup:addon/{DLC:016x}/romfs', rows['nup'])
        strip = lambda rs: [r for r in rs if not r.startswith(('romfs\t', 'aoc\t'))]
        self.assertEqual(strip(rows['nup']), strip(rows['folder']))
        for m in ('main', 'sdk'):
            self.assertEqual((packs['nup'] / f'{m}.patched.bin').read_bytes(), (packs['folder'] / f'{m}.patched.bin').read_bytes())
            self.assertEqual((packs['nup'] / f'{m}.android.key').read_text(), (packs['folder'] / f'{m}.android.key').read_text())   # the same input bytes


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Launcher(unittest.TestCase):
    """The launcher's side on the JVM: android/app/src/jvmtest NupJvmTest (its header) with Nup, Zstd, Pack, Profile and ShaderLibs and a stub BuildConfig: the reader,
    DM_ROMFS / DM_AOC_DIR rows, and which folders and .nup files Prepare starts from (Pack.isDump: an update, or the base game alone). The zstd fixtures need a Python with zstd"""
    STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=aa";
    public static final String NX_DMSH_VK_CHAIN = "6607c8d19abc";
    public static final String NX_DMSH_VK_ABI = "01b52a48538b";
}
'''

    def test_jvm(self):
        src = REPO / 'android/app/src/main/java/dev/nxnative/android'
        t = Path(tempfile.mkdtemp(prefix='nupjvm_'))
        try:
            (t / 'BuildConfig.java').write_text(self.STUB)
            subprocess.run(['javac', '-d', str(t / 'classes')] + [str(src / f'{c}.java') for c in ('Nup', 'Zstd', 'Pack', 'DumpLayout', 'BuildCheck', 'RomfsCheck', 'Profile', 'ShaderLibs')]
                           + [str(REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/NupJvmTest.java'), str(t / 'BuildConfig.java')], check=True, capture_output=True, text=True)
            (t / 'run').mkdir()
            r = subprocess.run(['java', '-cp', str(t / 'classes'), 'dev.nxnative.android.NupJvmTest', str(REPO), str(t / 'run')] + ([sys.executable] if have_zstd() else []),
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout[-2000:] + r.stderr[-2000:])
            self.assertRegex(r.stdout, r'^ok \d+')
        finally: shutil.rmtree(t, ignore_errors=True)


if __name__ == '__main__':
    unittest.main()
