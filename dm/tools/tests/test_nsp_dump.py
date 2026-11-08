"""NSP script with a dump folder (scripts/pack_dump.py), its only input: the folder's layout is read and packed into what the script's later steps read.
The run stops at the prerequisite check; no game files or Xcode needed."""
import importlib.util
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools')); sys.path.insert(0, str(REPO / 'dm/games'))
import romfs_image
spec = importlib.util.spec_from_file_location('build_ipa_from_nsp', REPO / 'scripts/build_ipa_from_nsp.py')
if spec is None or spec.loader is None: raise ImportError('scripts/build_ipa_from_nsp.py')
nsp = importlib.util.module_from_spec(spec); spec.loader.exec_module(nsp)
pack_dump = nsp.pack_dump

TID = 0x040074A01BF12000   # a game with a profile in dm/games, whose registered version is an update
VER = 458752


def npdm(tid):
    d = bytearray(0x100)
    d[:4] = b'META'
    struct.pack_into('<II', d, 0x70, 0x80, 0x20)
    d[0x80:0x84] = b'ACI0'
    struct.pack_into('<Q', d, 0x90, tid)
    return bytes(d)


def version(folder, tid=TID, main=b'code', marker='marker'):
    (folder / 'exefs').mkdir(parents=True); (folder / 'romfs/dir').mkdir(parents=True)
    (folder / 'exefs/main').write_bytes(main)
    (folder / 'exefs/main.npdm').write_bytes(npdm(tid))
    (folder / 'exefs/.listing').write_text('not a module')
    (folder / f'romfs/dir/{marker}').write_text('data')


class Base(unittest.TestCase):
    def setUp(self):
        td = tempfile.TemporaryDirectory(); self.addCleanup(td.cleanup)
        self.dump = Path(td.name).resolve() / 'dump'
        version(self.dump / 'base', marker='old')
        version(self.dump / f'updates/{VER}', main=b'new code', marker='new')
        (self.dump / 'dlc/040074a01bf13001/0/romfs').mkdir(parents=True)
        (self.dump / 'dlc/040074a01bf13001/0/romfs/dlcfile').write_text('dlc')
        (self.dump / 'dlc/.listing').write_text('not a DLC')


class Scan(Base):
    def test_layout(self):
        self.assertEqual(pack_dump.scan(self.dump), (TID, {VER: self.dump / f'updates/{VER}'}, {TID + 0x1001: self.dump / 'dlc/040074a01bf13001/0/romfs'}))

    def test_base_alone(self):
        d = self.dump.parent / 'alone'; version(d / 'base')
        self.assertEqual(pack_dump.scan(d), (TID, {}, {}))

    def test_update_romfs_only(self):   # a phone's dump: base/exefs only, the update's RomFS is the whole tree
        shutil.rmtree(self.dump / 'base/romfs')
        self.assertEqual(pack_dump.scan(self.dump)[1], {VER: self.dump / f'updates/{VER}'})
        self.assertTrue(nsp.dump_folder(self.dump))
        shutil.rmtree(self.dump / f'updates/{VER}/romfs')
        with self.assertRaisesRegex(pack_dump.Error, 'base/romfs'): pack_dump.scan(self.dump)
        with self.assertRaisesRegex(pack_dump.Error, 'romfs'): pack_dump.pack_version(self.dump / 'base', TID, self.dump / 'out', say=lambda s: None)

    def test_refuses(self):
        (self.dump / 'dlc/0100000000000000/romfs').mkdir(parents=True)
        with self.assertRaisesRegex(pack_dump.Error, 'is not a DLC of'): pack_dump.scan(self.dump)
        d = self.dump.parent / 'patch'; version(d / 'base', tid=TID + 0x800)
        with self.assertRaisesRegex(pack_dump.Error, 'not a base title ID'): pack_dump.scan(d)
        with self.assertRaisesRegex(pack_dump.Error, 'no base/exefs'): pack_dump.scan(self.dump.parent)

    def test_layout_is_the_only_input(self):
        self.assertTrue(nsp.dump_folder(self.dump))
        self.assertFalse(nsp.dump_folder(self.dump / 'dlc'))


class Pack(Base):
    def out(self): return self.dump / 'out'

    def test_update_packed_on_its_own(self):
        pack_dump.pack_version(self.dump / f'updates/{VER}', TID, self.out(), say=lambda s: None)
        self.assertEqual((self.out() / 'ExeFS/main').read_bytes(), b'new code')
        self.assertFalse((self.out() / 'ExeFS/.listing').exists())
        names = romfs_image.read_index(self.out() / 'RomFS.img')
        self.assertIn('dir/new', names)
        self.assertNotIn('dir/old', names)   # an update's RomFS is the patched tree, never laid over the base

    def test_other_title_refused(self):
        with self.assertRaisesRegex(pack_dump.Error, 'the base is'): pack_dump.pack_version(self.dump / f'updates/{VER}', TID + 0x2000, self.out(), say=lambda s: None)

    def test_repeat_keeps_what_is_unchanged(self):
        src = self.dump / f'updates/{VER}'
        pack_dump.pack_version(src, TID, self.out(), say=lambda s: None)
        main, img = self.out() / 'ExeFS/main', self.out() / 'RomFS.img'
        before = main.stat().st_ino, img.stat().st_mtime_ns
        pack_dump.pack_version(src, TID, self.out(), say=lambda s: None)
        self.assertEqual((main.stat().st_ino, img.stat().st_mtime_ns), before)
        (src / 'exefs/main').write_bytes(b'patched code')
        pack_dump.pack_version(src, TID, self.out(), say=lambda s: None)
        self.assertEqual(main.read_bytes(), b'patched code')

    def test_dlc_images_follow_the_folder(self):
        aoc = self.out() / 'aoc'
        aoc.mkdir(parents=True); (aoc / '7.img').write_bytes(b'gone'); (aoc / '7.img.inputs.json').write_text('{}')
        _, _, dlc = pack_dump.scan(self.dump)
        pack_dump.pack_dlc(TID, dlc, aoc, say=lambda s: None)
        self.assertEqual(sorted(p.name for p in aoc.iterdir()), ['1.img', '1.img.inputs.json'])
        self.assertIn('dlcfile', romfs_image.read_index(aoc / '1.img'))


class Stop(Exception): pass


class Main(Base):
    def run_main(self, *opts):
        args = nsp.parse_args([str(self.dump), *opts])
        seen, said = {}, []
        def stop(env, *a): seen.update(env); raise Stop()
        with mock.patch.object(nsp, 'find_repo', return_value=REPO), mock.patch.object(nsp, 'configure_dotnet'), mock.patch.object(nsp, 'WORK_DIR', ''), \
             mock.patch.object(nsp, 'check_prereqs', side_effect=stop), mock.patch.object(nsp, 'say', side_effect=said.append), \
             mock.patch.object(nsp, 'ask', side_effect=AssertionError('asked for something')):
            with self.assertRaises(Stop): nsp.main(args)
        return Path(seen['DM_ROOT']), said

    def test_asks_for_nothing_and_builds_beside_the_dump(self):
        work, said = self.run_main()
        self.assertEqual(work, self.dump / f'{TID:016X}_build')
        self.assertTrue(any(s.startswith('a dump folder: ') for s in said), said)

    def test_title_id_must_match(self):
        with mock.patch.object(nsp, 'find_repo', return_value=REPO), mock.patch.object(nsp, 'configure_dotnet'), self.assertRaisesRegex(nsp.BuildError, 'which holds'):
            nsp.main(nsp.parse_args([str(self.dump), '--title-id', '0100000000010000']))

    def test_not_a_dump_refused(self):
        d = self.dump.parent / 'empty'; d.mkdir(); (d / 'game.bin').write_bytes(b'x')
        with mock.patch.object(nsp, 'find_repo', return_value=REPO), mock.patch.object(nsp, 'configure_dotnet'), self.assertRaisesRegex(nsp.BuildError, 'no base/exefs'):
            nsp.main(nsp.parse_args([str(d)]))

    def test_no_profile_is_surveyed_from_the_dump(self):
        d = self.dump.parent / 'unknown'; version(d / 'base', tid=0x0400000000000000)
        with mock.patch.object(nsp, 'find_repo', return_value=REPO), mock.patch.object(nsp, 'configure_dotnet'), mock.patch.object(nsp, 'say'), \
             mock.patch.object(nsp, 'same_build', return_value=None), mock.patch.object(nsp, '_importable', return_value=True), mock.patch.object(nsp, 'check_capstone'), \
             mock.patch.object(nsp, 'survey', side_effect=Stop()) as survey:
            with self.assertRaises(Stop): nsp.main(nsp.parse_args([str(d), '--survey-only']))
        self.assertEqual(survey.call_args.args[:2], (d, 0x0400000000000000))

    def test_survey_lists_the_newest_version(self):   # the survey's extraction: the code modules, a RomFS manifest and the RomFS's NROs, read from the dump in place
        (self.dump / f'updates/{VER}/romfs/nro').mkdir(); (self.dump / f'updates/{VER}/romfs/nro/m.nro').write_bytes(b'nro')
        _, fam = nsp.scan_dump(self.dump)
        import nacp, profile_guess
        with mock.patch.object(nsp, 'pg', profile_guess, create=True), mock.patch.object(nsp, 'nacp', nacp, create=True), mock.patch.object(nsp, 'say'), \
             mock.patch.object(nsp, 'PROG', mock.Mock()), mock.patch.object(nsp, 'check_aarch64'), mock.patch.object(profile_guess, 'facts_of', side_effect=Stop()):
            with self.assertRaises(Stop): nsp.survey(self.dump, TID, fam, REPO, sys.executable, {})
        ex = self.dump / f'{TID:016X}_survey/extract'
        self.assertEqual((ex / 'ExeFS/main').read_bytes(), b'new code')
        rows = sorted(l.split('\t')[:2] for l in (ex / 'romfs_manifest.tsv').read_text().splitlines() if not l.startswith('#'))
        self.assertEqual(rows, [['/dir/new', '4'], ['/nro/m.nro', '3']])
        self.assertEqual((ex / 'romfs_nro/nro/m.nro').read_bytes(), b'nro')


if __name__ == '__main__':
    unittest.main()
