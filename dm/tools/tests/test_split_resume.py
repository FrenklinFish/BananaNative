"""shader_split DM_SPLIT_RESUME (ScanCache): a split stopped part way keeps the files it scanned and a rerun scans only the rest, counting from what is done.
The phone's split (android/ONDEVICE.md) is stopped often by thermal and battery pauses; before this every stop started its 80,273 files over from 0.
A kept file is written whole (temporary name, then a rename) and one that does not load is dropped, not taken as done. No game files needed."""
import collections
import contextlib
import io
import os
import pickle
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '01007BB017812000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had


def fake_scan(job):   # stands in for scan_file: a result that names its job
    return job[1], {job[1]: ('w', 'vs', b'c', b'k')}, set(), {}


class Resume(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp()); self.addCleanup(lambda: __import__('shutil').rmtree(self.tmp))
        self.d = str(self.tmp / 'scan')
        self.jobs = [(f'/rom/f{i}', f'f{i}') for i in range(100)]

    def cache(self, digest):   # a ScanCache, its 'resuming' line kept out of the test's output
        with contextlib.redirect_stdout(io.StringIO()): return ss.ScanCache(self.d, digest)

    def run_pooled(self, cache, jobs=None, start=0, total=100, chunk=16):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), mock.patch.object(ss, 'scan_file', side_effect=fake_scan) as sf:
            r = ss.pooled(ss.InProcess(lambda: None, ()), jobs if jobs is not None else self.jobs, total, start, chunk, cache)
        return r, out.getvalue(), sf.call_count

    def test_interrupt_keeps_what_finished(self):
        want, _, _ = self.run_pooled(None)
        calls = [0]
        def stop_after_three(b):
            calls[0] += 1
            if calls[0] > 3: raise KeyboardInterrupt
            return [fake_scan(j) for j in b]
        c = ss.ScanCache(self.d, 'D')
        with mock.patch.object(ss, 'scan_batch', side_effect=stop_after_three), contextlib.redirect_stdout(io.StringIO()), self.assertRaises(KeyboardInterrupt):
            ss.pooled(ss.InProcess(lambda: None, ()), self.jobs, 100, 0, 16, c)
        self.assertEqual(sorted(os.listdir(self.d)), ['0.pkl', 'digest'])   # the interrupt flushed the three batches
        with contextlib.redirect_stdout(io.StringIO()) as said: c2 = ss.ScanCache(self.d, 'D')
        self.assertTrue(said.getvalue().startswith('resuming the split: 48 file(s)'), said.getvalue())
        got, out, scanned = self.run_pooled(c2)
        self.assertEqual(got, want)
        self.assertEqual(scanned, 100 - 48)   # only the files not kept
        self.assertEqual(out.splitlines()[0], 'progress: 48/100')   # the count starts from what is done
        self.assertEqual(out.splitlines()[-1], 'progress: 100/100')

    def test_second_list_keys_by_its_offset(self):   # main's second pooled call (the RomFS files after the archive ranges) starts at len(big)
        c = ss.ScanCache(self.d, 'D')
        self.run_pooled(c, self.jobs[:10], 0, 100, 1); self.run_pooled(c, self.jobs[10:], 10, 100, 16)
        got, out, scanned = self.run_pooled(self.cache('D'), self.jobs[10:], 10, 100, 16)
        self.assertEqual(scanned, 0); self.assertEqual([r[0] for r in got], [j[1] for j in self.jobs[10:]])
        self.assertIn('progress: 100/100', out)

    def test_a_cut_file_is_not_taken(self):
        c = ss.ScanCache(self.d, 'D'); self.run_pooled(c)
        files = sorted(f for f in os.listdir(self.d) if f.endswith('.pkl'))
        p = Path(self.d, files[0]); p.write_bytes(p.read_bytes()[:len(p.read_bytes()) // 2])   # a reboot cut it
        Path(self.d, '7.pkl.tmp').write_bytes(b'half')   # a temporary file a kill left
        c2 = self.cache('D')
        self.assertFalse(p.exists()); self.assertFalse(Path(self.d, '7.pkl.tmp').exists())
        want, _, _ = self.run_pooled(None)
        got, _, scanned = self.run_pooled(c2)
        self.assertEqual(got, want); self.assertGreater(scanned, 0)

    def test_other_inputs_start_over(self):
        self.run_pooled(self.cache('D'))
        c = ss.ScanCache(self.d, 'E')
        self.assertEqual(c.have, {}); self.assertEqual(sorted(os.listdir(self.d)), ['digest'])
        self.assertEqual(self.run_pooled(c)[2], 100)

    def test_digest_names_the_files(self):
        f = self.tmp / 'a.bin'; f.write_bytes(b'x')
        a = ss.scan_digest(['x'], [(str(f), 'a.bin')])
        self.assertEqual(a, ss.scan_digest(['x'], [(str(f), 'a.bin')]))
        f.write_bytes(b'xy')
        self.assertNotEqual(a, ss.scan_digest(['x'], [(str(f), 'a.bin')]))
        self.assertNotEqual(a, ss.scan_digest(['y'], [(str(f), 'a.bin')]))
        with mock.patch.dict(os.environ, DM_PACKER_COMMIT='0123abcd'): self.assertNotEqual(a, ss.scan_digest(['y'], [(str(f), 'a.bin')]))

    def test_a_tool_or_profile_change_starts_over(self):   # the digest covers the modules the scan imports and the profile's tables, not only shader_split.py
        names = {os.path.basename(p) for p in ss.digest_sources()}
        for want in ('shader_split.py', 'romfs.py', 'unityfs.py', 'uepak.py', 'cpk.py', 'frostbite.py', 'smash_arc.py', 'profile_guess.py', 'game.py', 'nso.py', 'game.tsv'):
            self.assertIn(want, names)
        mod = self.tmp / 'romfs.py'; mod.write_text('A = 1\n')
        a = ss.scan_digest(['x'], [], [str(mod)])
        mod.write_text('A = 2\n')
        b = ss.scan_digest(['x'], [], [str(mod)])
        self.assertNotEqual(a, b)
        self.run_pooled(self.cache(a))
        self.assertEqual(self.run_pooled(self.cache(b))[2], 100)   # every file scanned again

    def test_a_foreign_class_is_refused(self):   # a planted file (shared storage) must not run code: it is dropped, nothing is called
        os.makedirs(self.d); Path(self.d, 'digest').write_text('D')
        class Boom:
            def __reduce__(self): return (os.system, ('echo planted > ' + str(self.tmp / 'ran'),))
        Boom.tmp = self.tmp
        Path(self.d, '0.pkl').write_bytes(pickle.dumps({0: Boom()}))
        Path(self.d, '1.pkl').write_bytes(pickle.dumps({1: collections.Counter('ab')}))
        Path(self.d, '2.pkl').write_bytes(pickle.dumps({2: fake_scan(('/rom/f2', 'f2'))})[:20])   # cut by a reboot: dropped without a word
        with contextlib.redirect_stdout(io.StringIO()) as said: c = ss.ScanCache(self.d, 'D')
        self.assertEqual(c.have, {}); self.assertFalse((self.tmp / 'ran').exists())
        self.assertEqual(sorted(os.listdir(self.d)), ['digest'])
        reds = [l for l in said.getvalue().splitlines() if l.startswith('[RED] ')]   # a refused class is named, never silent
        self.assertEqual(len(reds), 2, said.getvalue())
        self.assertTrue(any('posix.system' in l or 'os.system' in l for l in reds), reds); self.assertTrue(any('collections.Counter' in l for l in reds), reds)
        with self.assertRaises(pickle.UnpicklingError): ss.ResultUnpickler(io.BytesIO(pickle.dumps(os.system))).load()

    def test_a_result_type_the_allow_list_lacks_turns_the_cache_off(self):   # checked on the first write: named, nothing kept, the split goes on
        c = self.cache('D'); c.EVERY = 0
        odd = lambda job: (job[1], {}, set(), collections.OrderedDict(a=1))
        out = io.StringIO()
        with mock.patch.object(ss, 'scan_file', side_effect=odd), contextlib.redirect_stdout(out):
            got = ss.pooled(ss.InProcess(lambda: None, ()), self.jobs, 100, 0, 16, c)
        self.assertEqual(len(got), 100); self.assertIsNone(c.d)
        reds = [l for l in out.getvalue().splitlines() if l.startswith('[RED] ')]
        self.assertEqual(len(reds), 1); self.assertIn('collections.OrderedDict', reds[0])
        self.assertEqual(sorted(os.listdir(self.d)), ['digest'])

    def test_profile_tables_the_scan_does_not_read_are_not_hashed(self):
        names = {os.path.basename(p) for p in ss.digest_sources()}
        self.assertIn('game.tsv', names)
        for no in ('README.md', 'shader_map.tsv', 'shader_facts.tsv', 'c1_full.tsv', 'cb_extent.tsv'): self.assertNotIn(no, names)

    def test_unity_trees_load(self):   # what a Unity scan result holds goes through the allow-list
        import unityfs
        n = unityfs.Node('Shader', 'Base', -1, 0); n.kids.append(unityfs.Node('int', 'x', 4, 0))
        r = ('a', {'k': ('w', 'vs', b'c', b'k')}, {('a', 'b'), ('c', 'd', 'prog')}, {(1, b'h'): n})
        got = ss.ResultUnpickler(io.BytesIO(pickle.dumps({5: r}, pickle.HIGHEST_PROTOCOL))).load()[5]
        self.assertEqual(got[:3], r[:3]); t = got[3][(1, b'h')]
        self.assertEqual((t.type, t.name, t.kids[0].name, t.kids[0].size), ('Shader', 'Base', 'x', 4))

    def test_a_folder_in_the_cache_is_removed(self):
        os.makedirs(Path(self.d, 'sub/deeper')); Path(self.d, 'digest').write_text('D'); os.makedirs(Path(self.d, 'other'))
        self.cache('D'); self.assertEqual(sorted(os.listdir(self.d)), ['digest'])
        os.makedirs(Path(self.d, 'sub/deeper'))
        self.cache('E'); self.assertEqual(sorted(os.listdir(self.d)), ['digest'])

    def test_a_write_error_goes_on_without_the_cache(self):
        c = self.cache('D'); c.EVERY = 0
        out = io.StringIO()
        with mock.patch.object(ss.ScanCache, '_write', side_effect=OSError(28, 'No space left on device')), contextlib.redirect_stdout(out), \
             mock.patch.object(ss, 'scan_file', side_effect=fake_scan):
            got = ss.pooled(ss.InProcess(lambda: None, ()), self.jobs, 100, 0, 16, c)
        self.assertEqual([r[0] for r in got], [j[1] for j in self.jobs])
        self.assertEqual(out.getvalue().count('[RED]'), 1); self.assertIn('No space left on device', out.getvalue())
        self.assertIsNone(c.d)

    def test_process_pool(self):   # J > 1: results come from worker processes; kept batches are not submitted again
        self.enterContext(mock.patch.dict(os.environ, DM_GAME='01007BB017812000'))   # the workers import shader_split, which loads the profile
        rom = self.tmp / 'rom'; rom.mkdir()
        jobs = [(str(rom / f'f{i}.bin'), f'f{i}.bin') for i in range(40)]
        for p, _ in jobs: Path(p).write_bytes(b'no shaders')
        def run(ex, js, cache):
            with contextlib.redirect_stdout(io.StringIO()): return ss.pooled(ex, js, 40, 0, 4, cache)
        with ss.InProcess(ss._pool_init, (None, {}, {})) as ex: want = run(ex, jobs, None)
        with ss.InProcess(ss._pool_init, (None, {}, {})) as ex: run(ex, jobs[:20], self.cache('D'))   # half kept, by the in-process form
        for p, _ in jobs[:20]: os.unlink(p)   # scanned again, these would come back unreadable
        with ss.pool(2, None, {}, {}) as ex: got = run(ex, jobs, self.cache('D'))
        self.assertEqual(got, want)
        for p, _ in jobs[20:]: os.unlink(p)
        with ss.pool(2, None, {}, {}) as ex: again = run(ex, jobs, self.cache('D'))   # the pool's own results were kept too
        self.assertEqual(again, want)

    def test_whole_split_resumes_byte_identical(self):
        rom = self.tmp / 'RomFS'; (rom / 'sub').mkdir(parents=True)
        for i in range(40): (rom / f'sub/f{i}.bin').write_bytes(b'no shaders %d' % i)
        main = self.tmp / 'main'; main.write_bytes(b'NSO0' + bytes(0xfc))   # an NSO header of three empty segments
        env = dict(os.environ, DM_GAME='01007BB017812000', J='1', DM_SPLIT_RESUME=self.d)
        def split(tag):
            out = [self.tmp / tag / n for n in ('pkg', 'map.tsv', 'facts.tsv', 'pairs.tsv', 'pso.tsv')]; (self.tmp / tag).mkdir()
            r = subprocess.run([sys.executable, REPO / 'dm/gfx/tools/shader_split.py', rom, main, *out], capture_output=True, text=True, env=env)
            self.assertEqual(r.returncode, 0, r.stderr)
            return r.stdout, [p.read_bytes() for p in out[1:]]
        out1, t1 = split('a')
        self.assertNotIn('resuming', out1)
        out2, t2 = split('b')
        self.assertIn('resuming the split: 40 file(s) already scanned', out2)
        self.assertEqual(t1, t2)


if __name__ == '__main__': unittest.main()
