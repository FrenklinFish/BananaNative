"""NSP script: a build folder on an exFAT / FAT external drive. macOS writes an AppleDouble `._<name>` beside every file there: rmtree must treat an entry that vanished between
the listing and its unlink (the `._x` that goes with `x`) as removed. No game files needed."""
import errno
import importlib.util
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('build_ipa_from_nsp', REPO / 'scripts/build_ipa_from_nsp.py')
if spec is None or spec.loader is None: raise ImportError('scripts/build_ipa_from_nsp.py')
nsp = importlib.util.module_from_spec(spec); spec.loader.exec_module(nsp)

class ExternalDrive(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp()); self.addCleanup(shutil.rmtree, self.tmp)
        cm = mock.patch.object(nsp, 'say', lambda m: None); cm.__enter__(); self.addCleanup(cm.__exit__, None, None, None)

    def test_rmtree_tolerates_partner_vanishing(self):
        d = self.tmp / 'tree'; (d / 'sub').mkdir(parents=True)
        for n in ('a', '._a', 'sub/b', 'sub/._b'): (d / n).write_bytes(b'x')
        real = os.unlink

        def unlink(path, *a, **kw):   # deleting `x` takes `._x` with it (and the other way round), as on an exFAT volume
            real(path, *a, **kw)
            head, name = os.path.split(path); partner = os.path.join(head, name[2:] if name.startswith('._') else '._' + name)   # a bare name under dir_fd, a full path otherwise
            try: real(partner, *a, **kw)
            except FileNotFoundError: pass
        with mock.patch('os.unlink', unlink), mock.patch('os.remove', unlink):
            nsp.rmtree(d)
        self.assertFalse(d.exists())

    def test_rmtree_reports_other_errors(self):
        d = self.tmp / 'tree'; d.mkdir(); (d / 'a').write_bytes(b'x')
        def unlink(path, *a, **kw): raise PermissionError(errno.EACCES, 'denied', str(path))
        with mock.patch('os.unlink', unlink), mock.patch('os.remove', unlink):
            with self.assertRaises(PermissionError): nsp.rmtree(d)
        self.assertTrue(d.exists())


if __name__ == '__main__':
    unittest.main()
