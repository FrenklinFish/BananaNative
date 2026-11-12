"""shader_split's pool workers send large results back through a temporary file (scan_batch_spilled / unspill), not the pipe: on Windows one pipe write of a
very large result fails with WinError 87 and the split stopped (issue #202, Pokemon Pokopia at 44/48). No game files needed."""
import contextlib
import io
import os
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


def fake_scan(job):   # stands in for scan_file: a result that names its job, with a set (ResultUnpickler must build it)
    return job[1], {job[1]: ('w', 'vs', b'c' * 1000, b'k')}, {('a', 'b')}, {}


class Spill(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(); self.addCleanup(lambda: __import__('shutil').rmtree(self.tmp))
        self.jobs = [(f'/rom/f{i}', f'f{i}') for i in range(5)]

    def roundtrip(self, spill):
        with mock.patch.object(ss, 'scan_file', side_effect=fake_scan), mock.patch.object(ss, 'SPILL', spill), \
             mock.patch.object(tempfile, 'tempdir', self.tmp):
            v = ss.scan_batch_spilled(self.jobs)
            return v, ss.unspill(v)

    def test_small_result_in_memory(self):
        v, r = self.roundtrip(1 << 30)
        self.assertEqual(v[0], 'b')
        self.assertEqual(r, [fake_scan(j) for j in self.jobs])

    def test_large_result_through_a_file_deleted_after(self):
        v, r = self.roundtrip(0)
        self.assertEqual(v[0], 'f')
        self.assertEqual(r, [fake_scan(j) for j in self.jobs])
        self.assertFalse(os.path.exists(v[1]))
        self.assertEqual(os.listdir(self.tmp), [])

    def test_pool_uses_the_file_path(self):   # real worker processes; DM_SPLIT_SPILL=0 makes every result go through a file
        jobs = [(str(REPO / 'README.md'), 'README.md')]
        with mock.patch.dict(os.environ, {'DM_SPLIT_SPILL': '0', 'DM_GAME': '01007BB017812000'}), mock.patch.object(ss, 'SPILL', 0), contextlib.redirect_stdout(io.StringIO()):
            with ss.pool(1, None, {}, {}) as ex: want = ss.pooled(ex, jobs, 1)
            with ss.pool(2, None, {}, {}) as ex: got = ss.pooled(ex, jobs, 1)
        self.assertEqual(got, want)


if __name__ == '__main__':
    unittest.main()
