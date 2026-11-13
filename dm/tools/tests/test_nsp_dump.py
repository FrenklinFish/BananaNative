"""NSP script with a dump folder (scripts/pack_dump.py), its only input: the folder's layout is read and packed into what the script's later steps read.
The run stops at the prerequisite check; no game files or Xcode needed."""
import importlib.util
import io
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools')); sys.path.insert(0, str(REPO / 'dm/games'))
import cnmt, romfs_image
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


def cnmt_record(rid, ver, typ, ext, contents, extdata=b''):   # a content meta record in switchbrew's layout: header, extended header, content infos, extended data, digest
    b = struct.pack('<QIBBHHHB3xI4x', rid, ver, typ, 1, len(ext), len(contents), 0, 0, 0) + ext
    for t, off in contents: b += b'\0' * 0x20 + b'\x11' * 0x10 + (1000).to_bytes(5, 'little') + bytes([1, t, off])
    return b + extdata + b'\0' * 0x20


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

    def test_ouncecake_dlc(self):   # OunceCake's DLC layout: <version>/base/program/<index>/romfs and its data patches' updates/<version>/...; the newest patch is the patched tree
        d = self.dump / 'dlc/040074a01bf13002/0'
        for p in ('base/program/0/romfs', 'updates/65536/program/0/romfs', 'updates/131072/program/0/romfs', 'base/program/0/meta'): (d / p).mkdir(parents=True)
        self.assertEqual(pack_dump.scan(self.dump)[2][TID + 0x1002], d / 'updates/131072/program/0/romfs')
        shutil.rmtree(d / 'updates')
        self.assertEqual(pack_dump.scan(self.dump)[2][TID + 0x1002], d / 'base/program/0/romfs')
        shutil.rmtree(d / 'base/program/0/romfs'); (d / 'base/program/0/data/romfs').mkdir(parents=True)   # a program and a data content at one index
        self.assertEqual(pack_dump.scan(self.dump)[2][TID + 0x1002], d / 'base/program/0/data/romfs')
        (d / 'base/program/1/romfs').mkdir(parents=True)
        with self.assertRaisesRegex(pack_dump.Error, 'expected one RomFS folder'): pack_dump.scan(self.dump)

    def test_ouncecake_game(self):   # OunceCake's unflattened layout: each version's package at program/0/; a Switch 2 Edition's program/1 is not read
        d = self.dump.parent / 'ounce'
        version(d / 'base/program/0', marker='old')
        version(d / f'updates/{VER}/program/0', main=b'new code', marker='new')
        version(d / f'updates/{VER}/program/1', tid=TID + 0x1, main=b'switch 2 code')
        (d / f'updates/{VER}/program/0/control').mkdir()
        self.assertTrue(nsp.dump_folder(d))
        app, vers, _ = pack_dump.scan(d)
        self.assertEqual((app, vers), (TID, {VER: d / f'updates/{VER}/program/0'}))
        self.assertEqual(pack_dump.version_dir(d, vers, 0), d / 'base/program/0')
        self.assertEqual(pack_dump.version_part(d, vers, VER, 'exefs'), d / f'updates/{VER}/program/0/exefs')
        shutil.rmtree(d / f'updates/{VER}/program/0/romfs')   # an update without a RomFS of its own: the base's
        self.assertEqual(pack_dump.version_part(d, vers, VER, 'romfs'), d / 'base/program/0/romfs')
        e = self.dump.parent / 'ounce-data'; version(e / 'base/program/0/program')   # a program and a data content at one index
        self.assertEqual(pack_dump.scan(e)[0], TID)
        self.assertEqual(pack_dump.base_dir(e), e / 'base/program/0/program')

    def test_content_meta_records(self):   # the records in meta/ (the layout of Super Mario Bros. Wonder's add-on and data patch records): checked, and their version needs read
        d = self.dump / 'dlc/040074a01bf13002/0'
        (d / 'base/program/0/romfs').mkdir(parents=True); (d / 'updates/131072/program/0/romfs').mkdir(parents=True)
        def put(path, rec): (d / path).mkdir(parents=True, exist_ok=True); (d / path / 'r.cnmt').write_bytes(rec)
        put('base/program/0/meta', cnmt_record(TID + 0x1002, 0, 0x82, struct.pack('<QIB3xQ', TID, 196608, 0, TID + 0x1802), [(2, 0)]))
        put('updates/131072/program/0/meta', cnmt_record(TID + 0x1802, 131072, 0x84, struct.pack('<QQII8x', TID + 0x1002, TID, VER, 0), [(2, 0), (6, 0)]))
        rom = pack_dump.scan(self.dump)[2][TID + 0x1002]
        self.assertEqual(rom, d / 'updates/131072/program/0/romfs')
        self.assertEqual(pack_dump.dlc_required_version(rom), VER)   # the data patch's need, the package the RomFS came from
        self.assertEqual(pack_dump.dlc_required_version(d / 'base/program/0/romfs'), 196608)
        r = cnmt.read(d / 'base/program/0/meta/r.cnmt')
        self.assertEqual((r.id, r.type, r.application_id, r.data_patch_id, r.contents), (TID + 0x1002, cnmt.ADDON, TID, TID + 0x1802, [(2, 0, 1000)]))
        put('updates/131072/program/0/meta', cnmt_record(TID + 0x1803, 131072, 0x84, struct.pack('<QQII8x', TID + 0x1003, TID, VER, 0), []))   # another add-on's
        with self.assertRaisesRegex(pack_dump.Error, 'data patch 040074A01BF13803 of add-on 040074A01BF13003'): pack_dump.scan(self.dump)
        put('base/program/0/meta', cnmt_record(TID + 0x1009, 0, 0x82, struct.pack('<QIB3xQ', TID, 0, 0, 0), []))
        with self.assertRaisesRegex(pack_dump.Error, 'add-on 040074A01BF13009, the folder is 040074A01BF13002'): pack_dump.scan(self.dump)
        (d / 'base/program/0/meta/r.cnmt').write_bytes(b'\0' * 0x10)
        with self.assertRaisesRegex(pack_dump.Error, 'shorter than a content meta header'): pack_dump.scan(self.dump)

    def test_dlc_highest_version(self):   # the console keeps one version of an add-on installed: of several version folders the highest is read
        d = self.dump / 'dlc/040074a01bf13003'
        for p in ('0/base/program/0/romfs', '65536/base/program/0/romfs', '65536/updates/131072/program/0/romfs', '9/romfs'): (d / p).mkdir(parents=True)
        self.assertEqual(pack_dump.scan(self.dump)[2][TID + 0x1003], d / '65536/updates/131072/program/0/romfs')

    def test_refuses(self):
        (self.dump / 'dlc/0100000000000000/romfs').mkdir(parents=True)
        with self.assertRaisesRegex(pack_dump.Error, 'is not a DLC of'): pack_dump.scan(self.dump)
        d = self.dump.parent / 'patch'; version(d / 'base', tid=TID + 0x800)
        with self.assertRaisesRegex(pack_dump.Error, 'not a base title ID'): pack_dump.scan(d)
        with self.assertRaisesRegex(pack_dump.Error, 'no base/exefs'): pack_dump.scan(self.dump.parent)

    def test_layout_is_the_only_input(self):
        self.assertTrue(nsp.dump_folder(self.dump))
        self.assertFalse(nsp.dump_folder(self.dump / 'dlc'))


sys.path.insert(0, str(REPO / 'scripts'))
import dump_layout, nup_pack


class Layout(Base):
    """scripts/dump_layout.py: a flattened dump (a Switch 2 Edition's program1/, control/, a flattened add-on) moved into the console's layout and back"""
    def setUp(self):
        super().setUp()
        version(self.dump / f'updates/{VER}/program1', tid=TID + 1, main=b'switch 2 code')
        (self.dump / f'updates/{VER}/control').mkdir(); (self.dump / f'updates/{VER}/control/control.nacp').write_bytes(b'nacp')
        (self.dump / '.listing').write_text('ftp listing')
        self.files = sorted(p.relative_to(self.dump).as_posix() for p in self.dump.rglob('*') if p.is_file())

    def run_layout(self, *opts):
        with mock.patch('builtins.print'): return dump_layout.main([str(self.dump), *opts])

    def test_moves_and_undo(self):
        before = pack_dump.scan(self.dump)
        self.assertEqual(self.run_layout(), 0)   # a listing only
        self.assertTrue((self.dump / 'base/exefs').is_dir())
        self.assertEqual(self.run_layout('--apply'), 0)
        for p in ('base/program/0/exefs/main', 'base/program/0/romfs/dir/old', f'updates/{VER}/program/0/exefs/main', f'updates/{VER}/program/0/control/control.nacp',
                  f'updates/{VER}/program/1/exefs/main', 'dlc/040074a01bf13001/0/base/program/0/romfs/dlcfile', '.listing'):
            self.assertTrue((self.dump / p).is_file(), p)
        self.assertFalse((self.dump / 'base/exefs').exists() or (self.dump / f'updates/{VER}/program1').exists())
        app, vers, dlc = pack_dump.scan(self.dump)
        self.assertEqual((app, sorted(vers), sorted(dlc)), (before[0], sorted(before[1]), sorted(before[2])))
        self.assertEqual(vers[VER], self.dump / f'updates/{VER}/program/0')
        self.assertEqual(dump_layout.plan(self.dump), [])   # already in it
        self.assertEqual(self.run_layout('--undo'), 0)
        self.assertEqual(sorted(p.relative_to(self.dump).as_posix() for p in self.dump.rglob('*') if p.is_file()), self.files)   # every file back, the log gone
        self.assertFalse((self.dump / 'base/program').exists())

    def test_both_layouts_refused(self):
        (self.dump / 'base/program/0/exefs').mkdir(parents=True)
        with self.assertRaisesRegex(dump_layout.Error, 'both layouts'): dump_layout.plan(self.dump)

    def test_nup_writer_reads_both(self):   # dm/tools/nup_pack.py finds the same packages in either layout
        def packages():
            return [(k, t, v, sorted(c.name for c in r.kids)) for k, t, v, _, r in nup_pack.discover(str(self.dump), ('base', 'update', 'addon'))]
        flat = packages()
        self.run_layout('--apply')
        self.assertEqual(packages(), flat)
        self.assertEqual([p[3] for p in flat], [[b'exefs', b'romfs']] * 2 + [[b'romfs']])


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
