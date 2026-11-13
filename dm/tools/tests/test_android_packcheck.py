"""The launcher's pack code check (android/.../PackCheck.java) must not hold a game module in the Java heap, and must not repeat at every start (Forgejo #214: at
launch every game card decoded a whole ~105 MB .nup module into the heap, in parallel, until the 512 MB cap gave an OutOfMemoryError; the verdict was only kept in
memory, so the crash came back at every start). The JVM part (PackCheckJvmTest with Nup, Zstd, Verdict) checks:
 - Nup.hashTo (the streaming decode into the hash) gives the digest of Nup.read(f, -1) for every file of a .nup, raw and zstd;
 - a ~105 MB module (generated here, compressible) is hashed under -Xmx64m with a peak of the stored extent plus a few MiB, where the old whole-buffer read runs out
   of memory at the same heap;
 - Zstd.decodeTo equals Zstd.decode on frames with windows from 1 KiB (many slides) up, at several levels, and refuses a window above the limit before any output;
 - the second path, Game.collapse at every start (GameList.collapse over BuildCheck.gameKey): the build ID of a big .nup module is read from the first pieces of its stored
   extent, not the whole extent, so two copies of a big game collapse under -Xmx24m where the extent (30 MB or more) does not fit;
 - a damaged extent is refused with an error that names the file;
 - the kept verdict (Verdict): read back, invalid after another size/time/APK version/install time, a corrupt or throwing store is ignored, unknown is never kept.
Skipped without a JDK (the zstd parts without Python's zstd module, Python 3.14)."""
import hashlib
import random
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_nup  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/PackCheckJvmTest.java'
HAVE_JDK = bool(shutil.which('javac') and shutil.which('java'))
try:
    from compression import zstd as pyzstd
except ImportError:
    pyzstd = None
MIB = 1 << 20


def big_module(path, size=105 * MIB):
    """A compressible module: 1 MiB blocks of word soup, repeated, with every 8th block fresh (so the stored extent is a few MiB, not a few bytes)"""
    rnd = random.Random(214)
    words = [bytes(rnd.getrandbits(8) for _ in range(rnd.randint(3, 12))) for _ in range(400)]
    def soup(n):
        out = bytearray()
        while len(out) < n: out += rnd.choice(words)
        return bytes(out[:n])
    block = soup(MIB)
    h = hashlib.sha256()
    with open(path, 'wb') as f:
        i = 0
        while f.tell() < size:
            b = soup(MIB) if i % 8 == 7 else block[:-8] + i.to_bytes(8, 'little')
            n = min(len(b), size - f.tell()); f.write(b[:n]); h.update(b[:n]); i += 1
    return h.hexdigest()


@unittest.skipUnless(HAVE_JDK, 'no JDK')
class Jvm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.td = tempfile.TemporaryDirectory(prefix='packcheck_')
        cls.t = Path(cls.td.name).resolve()
        (cls.t / 'BuildConfig.java').write_text(test_nup.Launcher.STUB)
        mods = ('Nup', 'Zstd', 'Pack', 'DumpLayout', 'BuildCheck', 'RomfsCheck', 'Profile', 'ShaderLibs', 'PickedPath', 'CopyPlan', 'Verdict', 'GameList', 'Frontend')
        subprocess.run(['javac', '-Xlint:all', '-Xlint:-deprecation', '-d', str(cls.t / 'classes')] + [str(SRC / f'{c}.java') for c in mods] + [str(TEST), str(cls.t / 'BuildConfig.java')],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls): cls.td.cleanup()

    def java(self, *args, xmx=None):
        cmd = ['java'] + ([f'-Xmx{xmx}', '-XX:+UseSerialGC'] if xmx else []) + ['-cp', str(self.t / 'classes'), 'dev.nxnative.android.PackCheckJvmTest', *map(str, args)]
        r = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout[-2000:] + r.stderr[-3000:])
        self.assertRegex(r.stdout, r'ok \d+')
        return r.stdout

    def test_verdict(self): self.java('verdict')

    @unittest.skipUnless(pyzstd, 'no zstd module (Python 3.14)')
    def test_hashto_equals_read_for_every_file(self):
        d = self.t / 'Game'; test_nup.dump(d)
        self.java('equal', test_nup.write_nup(d, self.t / 'Game.nup'))

    @unittest.skipUnless(pyzstd, 'no zstd module (Python 3.14)')
    def test_a_module_larger_than_the_heap(self):
        d = self.t / 'Big'; test_nup.dump(d)
        want = big_module(d / 'base/exefs/main')
        nup = test_nup.write_nup(d, self.t / 'Big.nup')
        out = self.java('stream', nup, 'base', 'exefs/main', want, xmx='40m')
        self.assertIn('peak heap above the idle heap', out)
        print('\n' + out.strip().splitlines()[0])
        self.java('damaged', nup, 'base', 'exefs/main')

    @unittest.skipUnless(pyzstd, 'no zstd module (Python 3.14)')
    def test_collapse_of_big_duplicates_in_a_small_heap(self):
        # Forgejo #214, second path: Game.collapse (nx-list-merge thread, at every start) read each .nup's whole stored main for 0x60 bytes of build ID: 62 MB, OutOfMemoryError
        d = self.t / 'Dup'; test_nup.dump(d)
        rnd = random.Random(5)
        for sub in ('base', f'updates/{test_nup.VER}'):
            with open(d / sub / 'exefs/main', 'ab') as f:   # a valid NSO head, then about 60 MiB that compresses to about half (random and zero MiBs alternate)
                for i in range(60): f.write(rnd.randbytes(MIB) if i % 2 else bytes(MIB))
        a = test_nup.write_nup(d, self.t / 'DupA.nup')
        b = self.t / 'DupB.nup'; shutil.copyfile(a, b)
        out = self.java('collapse', a, b, xmx='24m')
        self.assertIn('ok', out)

    @unittest.skipUnless(pyzstd, 'no zstd module (Python 3.14)')
    def test_frames_with_small_windows(self):
        f = self.t / 'frames'; f.mkdir()
        rnd = random.Random(7)
        # matches at every offset up to 70 KiB (past a 1 KiB window: the encoder cannot use them), runs, RLE, noise, over 3 MB (the 1 KiB window's buffer is about 260 KiB: it slides a dozen times)
        data = bytearray()
        while len(data) < 3_000_000:
            k = rnd.randint(0, 3)
            if k == 0 and len(data) > 10: back = rnd.randint(1, min(len(data), 70_000)); ln = rnd.randint(4, 500); data += bytes(data[-back:][:ln])
            elif k == 1: data += bytes([rnd.randint(0, 255)]) * rnd.randint(1, 3000)
            elif k == 2: data += rnd.randbytes(rnd.randint(1, 400))
            else: data += b'the quick brown fox %d ' % rnd.randint(0, 50) * rnd.randint(1, 40)
        data = bytes(data[:3_000_000])
        P = pyzstd.CompressionParameter
        for name, opts in {'l1': {P.compression_level: 1}, 'l3': {P.compression_level: 3}, 'l19': {P.compression_level: 19}, 'l3-wlog10': {P.compression_level: 3, P.window_log: 10},
                           'l19-wlog10': {P.compression_level: 19, P.window_log: 10}, 'l1-wlog14-nocheck': {P.compression_level: 1, P.window_log: 14, P.checksum_flag: 0}}.items():
            (f / f'{name}.zst').write_bytes(pyzstd.compress(data, options=opts)); (f / f'{name}.raw').write_bytes(data)
        (f / 'small.zst').write_bytes(pyzstd.compress(b'tiny single segment frame ' * 5)); (f / 'small.raw').write_bytes(b'tiny single segment frame ' * 5)
        self.java('frames', f, xmx='64m')


if __name__ == '__main__':
    unittest.main()
