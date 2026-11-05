"""Home's list and the pack build check of the Android launcher on the JVM: javac compiles BuildCheck, GameList, Frontend, Nup, Zstd, Pack, Profile and ShaderLibs
with a stub BuildConfig and android/app/src/jvmtest's GameListJvmTest (its header). One card per game: the same file by any spelling or link, the same title and
build (a folder and its .nup), an older list's duplicates collapsed to the ready / last played entry. The build check: a pack fits a folder and the .nup made from it,
another build names every module whose build ID differs, a missing update, an unsupported build and a damaged .nup are told apart from it.
The three dump layouts (DumpLayout) and the RomFS check (RomfsCheck, game.tsv romfs_check) on folders and .nup files.
With NX_TEST_DKB_NUP set to a real Donkey Kong Bananza .nup (update 458752), or NX_TEST_DKB_DIR to its folder dump, the checks also run on it (read only); skipped otherwise. Skipped without a JDK."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/GameListJvmTest.java'
FIX_TID = '0100000000AB0000'
FIX2 = '0100000000AC0000'   # a game whose supported build is its base, display version 1.8.0 (as Mario Kart World)
FIX3 = '0100000000AD0000'   # a game with a romfs_check row, as Donkey Kong Bananza's
CHECK = 'System/RegionLangMask.txt|4|main'   # build.gradle romfsChecksOf of dm/games/040074A01BF12000's romfs_check row
DKB = ('040074A01BF12000', '458752', 'main=e25c90c1c136638885659fd55bf8a03b', 'sdk=18265daea2105b31f25346adfbbd09b5')


def nso_id(seed):
    return bytes((seed * 31 + i) & 0xff for i in range(16)).hex()


STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "%s\\tFixture\\t0\\tunknown\\t65536 main=%s sdk=%s\\n%s\\tDonkey Kong Bananza\\t0\\tunknown\\t%s %s %s\\t\\t\\t\\t\\t\\t%s"
            + "\\n%s\\tFixture Two\\t0\\tunknown\\t0 @1.8.0 main=%s sdk=%s"
            + "\\n%s\\tFixture Three\\t0\\tunknown\\t65536 main=%s sdk=%s\\t\\t\\t\\t\\t\\t%s";
    public static final String NX_DMSH_VK_CHAIN = "c0ffee000001", NX_DMSH_VK_ABI = "abc123abc123";
}
''' % (FIX_TID, nso_id(2), nso_id(3), *DKB, CHECK, FIX2, nso_id(11), nso_id(3), FIX3, nso_id(2), nso_id(3), CHECK)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class GameListJvm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='gamelist_'))
        (cls.tmp / 'stub').mkdir()
        (cls.tmp / 'stub/BuildConfig.java').write_text(STUB)
        srcs = [SRC / f'{n}.java' for n in ('BuildCheck', 'DumpLayout', 'RomfsCheck', 'GameList', 'Frontend', 'Nup', 'Zstd', 'Pack', 'Profile', 'ShaderLibs')]
        r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-d', str(cls.tmp / 'classes'), *map(str, srcs), str(TEST), str(cls.tmp / 'stub/BuildConfig.java')],
                           capture_output=True, text=True)
        assert r.returncode == 0, r.stdout + r.stderr

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def java(self, *args):
        return subprocess.run(['java', '-cp', str(self.tmp / 'classes'), 'dev.nxnative.android.GameListJvmTest', *map(str, args)], capture_output=True, text=True)

    def test_list_and_build_check(self):
        run = self.tmp / 'run'
        run.mkdir()
        r = self.java(REPO, run, sys.executable)
        self.assertEqual(r.returncode, 0, r.stdout[-3000:] + r.stderr[-3000:])
        self.assertRegex(r.stdout, r'^ok \d+')

    @unittest.skipUnless(os.environ.get('NX_TEST_DKB_NUP') and Path(os.environ.get('NX_TEST_DKB_NUP', '')).is_file(), 'NX_TEST_DKB_NUP: no real .nup')
    def test_real_dkb_nup(self):
        run = self.tmp / 'real'
        run.mkdir()
        r = self.java('real', os.environ['NX_TEST_DKB_NUP'], run, *DKB)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('real ok key=040074A01BF12000:458752:e25c90c1c136638885659fd55bf8a03b', r.stdout)

    @unittest.skipUnless(os.environ.get('NX_TEST_DKB_DIR') and Path(os.environ.get('NX_TEST_DKB_DIR', ''), 'updates/458752').is_dir(), 'NX_TEST_DKB_DIR: no real folder dump')
    def test_real_dkb_folder(self):
        """The real folder dump (base/, updates/458752/): the pack check and the RomFS check (its patched update RomFS's RegionLangMask.txt) pass, read only"""
        run = self.tmp / 'realdir'
        run.mkdir()
        r = self.java('real', os.environ['NX_TEST_DKB_DIR'], run, *DKB)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('real ok key=040074A01BF12000:458752:e25c90c1c136638885659fd55bf8a03b', r.stdout)


class DumpLayouts(unittest.TestCase):
    """scripts/pack_dump.py's side of the three layouts (what pack_android.py packs from, as the launcher's DumpLayout reads them): the base alone, an update alone that
    carries the whole game, both; a version's exefs and romfs its own, else the base's. Each as a folder and as a .nup of it (nup_pack.py --raw)"""

    def test_layouts(self):
        sys.path[:0] = [str(REPO / 'dm/tools'), str(REPO / 'scripts')]
        import pack_dump
        tid = int(FIX2, 16)
        npdm = bytearray(0x100); npdm[:4] = b'META'; npdm[0x70] = 0x80; npdm[0x74] = 0x20; npdm[0x80:0x84] = b'ACI0'; npdm[0x90:0x98] = tid.to_bytes(8, 'little')

        def version(d, code=True, rom=True):
            if code:
                (d / 'exefs').mkdir(parents=True); (d / 'exefs/main').write_bytes(b'NSO0' + bytes(0x200)); (d / 'exefs/main.npdm').write_bytes(bytes(npdm))
            if rom: (d / 'romfs').mkdir(parents=True); (d / 'romfs/a.txt').write_text(d.name)

        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            cases = {'base-only': [('base', True, True)], 'update-only': [('updates/65537', True, True)], 'both': [('base', True, True), ('updates/65537', True, True)],
                     'code-in-update': [('base', True, True), ('updates/65537', True, False)]}
            for name, parts in cases.items():
                folder = t / name
                for sub, code, rom in parts: version(folder / sub, code, rom)
                r = subprocess.run([sys.executable, '-I', str(REPO / 'dm/tools/nup_pack.py'), str(folder), str(t / f'{name}.nup'), '--raw'], capture_output=True, text=True)
                self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
                for d in (folder, t / f'{name}.nup'):
                    with self.subTest(d.name):
                        if d.is_dir(): self.assertTrue(pack_dump.is_dump(d))
                        app, vers, dlc = pack_dump.scan(d)
                        self.assertEqual(app, tid)
                        ver = max(vers, default=0)
                        self.assertEqual(ver, 0 if name == 'base-only' else 65537)
                        exe, rom = pack_dump.version_part(d, vers, ver, 'exefs'), pack_dump.version_part(d, vers, ver, 'romfs')
                        self.assertTrue((exe / 'main.npdm').is_file() and rom.is_dir(), (exe, rom))
                        own = 'base' if name == 'base-only' else 'update/65537' if d.suffix == '.nup' else 'updates/65537'
                        base = 'base'
                        self.assertTrue(str(exe).endswith(f'{own}/exefs'), exe)
                        self.assertTrue(str(rom).endswith(f'{base if name == "code-in-update" else own}/romfs'), rom)


if __name__ == '__main__':
    unittest.main()
