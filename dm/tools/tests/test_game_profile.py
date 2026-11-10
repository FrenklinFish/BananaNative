# dm/games/game.py's profile-key validation, on a synthetic profile: pso_gpu (the GPU architectures the app's offline pipeline archive is made for, beside pso_mtl4) takes, none and no others,
# and a profile without the key generates its header unchanged; romfs_check (a RomFS file line that tells a version's data apart) takes <path> <line> <expected | main>
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
GAME = REPO / 'dm/games/game.py'
ID = '0100000000F0F000'
TSV = ('title_id\t' + ID + '\nname\tTest\nprogram_id\t' + ID + '\nmodules\tmain\nromfs_marker\tromfs.img\n'   # what a header needs beside the keys under test
       'mem_reserve\t0x100000\nmem_alias\t0x100000\nmem_stack\t0x100000\napp_pool\t0x100000\n')


def header(rows):
    """game.py header of a synthetic profile with these extra rows: (exit code, its output)"""
    with tempfile.TemporaryDirectory() as d:
        Path(d, ID).mkdir()
        (Path(d, ID) / 'game.tsv').write_text(TSV + rows)
        r = subprocess.run([sys.executable, str(GAME), 'header', ID, str(Path(d, ID) / 'game.h')],
                           env={**os.environ, 'DM_GAMES_DIR': d}, capture_output=True, text=True)
    return r.returncode, (r.stderr + r.stdout).strip()


class PsoGPU(unittest.TestCase):
    def test_valid_tokens(self):
        for rows in ('', 'pso_mtl4\ton\npso_gpu\tg18p\n', 'pso_mtl4\ton\npso_gpu\tapplegpu_g19p\n',   # without the key: the header is made as before, no message
                     'pso_mtl4\ton\npso_gpu\tg18p g19p\n', 'pso_mtl4\ton\npso_gpu\tg14s g15s g16s g17p\n'):
            rc, out = header(rows)
            self.assertEqual((rc, out), (0, ''), rows)

    def test_rejected_tokens(self):
        for rows in ('pso_gpu\tnone\n',                     # none is not a value: absence of the key says no archive of its own
                     'pso_mtl4\ton\npso_gpu\tg18p none\n', 'pso_gpu\tx g18p!\n', 'pso_gpu\tg14s g15s g16s g17p g18p\n',
                     'pso_gpu\tapplegpu_g19p,x\n'):
            rc, out = header(rows)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(': pso_gpu takes 1-4 whitespace-separated GPU architectures', out, rows)


class RomfsCheck(unittest.TestCase):
    def test_valid_rows(self):
        for rows in ('romfs_check\tSystem/RegionLangMask.txt\t4\tmain\n', 'romfs_check\tSystem/RegionLangMask.txt\t2\tNormal\nromfs_check\ta/b.txt\t1\tJPja USen\n'):
            self.assertEqual(header(rows), (0, ''), rows)

    def test_rejected_rows(self):
        for rows in ('romfs_check\tSystem/RegionLangMask.txt\t4\n', 'romfs_check\t/abs.txt\t1\tmain\n', 'romfs_check\ta/../b.txt\t1\tmain\n',
                     'romfs_check\ta.txt\t0\tmain\n', 'romfs_check\ta.txt\tx\tmain\n', 'romfs_check\ta.txt\t1\ta|b\n', 'romfs_check\ta.txt\t1\t \n'):
            rc, out = header(rows)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(': romfs_check takes <path in the RomFS> <line, from 1>', out, rows)


if __name__ == '__main__':
    unittest.main()
