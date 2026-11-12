# dm/games/game.py's profile-key validation, on a synthetic profile: pso_gpu (the GPU architectures the app's offline pipeline archive is made for, beside pso_mtl4) takes, none and no others,
# and a profile without the key generates its header unchanged; romfs_check (a RomFS file line that tells a version's data apart) takes <path> <line> <expected | main>;
# scene_arg (a 3D scene choice passed as game arguments) takes <name> <scale | -> <arguments> <label>;
# the low-RAM keys (low_ram_place_part_mb, low_ram_img_cache_max_mb, low_ram_app_pool_mb: Android, devices of 12 GB or less) take MiB in their ranges and generate 0 when unset;
# low_ram_app_pool_mb from 1024 MiB to below the profile's app_pool
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



class SceneArg(unittest.TestCase):
    def test_valid_rows(self):
        for rows in ('scene_arg\ts075\t0.75\t-a={s} -b={s}\t75%: 3D 1440x810\n', 'scene_arg\tfixed\t-\t-no_render_dynres_enabled\tNo dynamic resolution\n'):
            self.assertEqual(header(rows), (0, ''), rows)

    def test_rejected_rows(self):
        for rows, msg in (('scene_arg\tx\t0.75\t-a={s}\n', 'scene_arg takes'),                     # no label
                          ('scene_arg\ta/b\t0.75\t-a={s}\tX\n', 'scene_arg takes'),
                          ('scene_arg\tx\t0.75\ta={s}\tX\n', 'scene_arg takes'),                   # not an argument
                          ('scene_arg\tx\t0.75\t-a={s}|b\tX\n', 'scene_arg takes'),
                          ('scene_arg\tx\tbig\t-a={s}\tX\n', 'scene_arg takes'),
                          ('scene_arg\tx\t0.75\t-a=1\tX\n', 'goes into the arguments'),             # a scale it does not use
                          ('scene_arg\tx\t5\t-a={s}\tX\n', 'goes into the arguments'),
                          ('scene_arg\tx\t-\t-a={s}\tX\n', 'needs a scale'),
                          ('scene_arg\tx\t0.5\t-a={s}\tX\nscene_arg\tx\t0.6\t-a={s}\tY\n', 'the name is taken')):
            rc, out = header(rows)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(msg, out, rows)


class LowRam(unittest.TestCase):
    def macros(self, rows, tsv=TSV):
        with tempfile.TemporaryDirectory() as d:
            Path(d, ID).mkdir()
            (Path(d, ID) / 'game.tsv').write_text(tsv + rows)
            r = subprocess.run([sys.executable, str(GAME), 'header', ID, str(Path(d, ID) / 'game.h')], env={**os.environ, 'DM_GAMES_DIR': d}, capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            h = (Path(d, ID) / 'game.h').read_text()
        return [ln for ln in h.splitlines() if 'LOW_RAM' in ln]

    def test_unset_is_zero(self):
        self.assertEqual(self.macros(''), ['#define DM_GAME_LOW_RAM_PLACE_PART_MB 0', '#define DM_GAME_LOW_RAM_IMG_CACHE_MAX_MB 0', '#define DM_GAME_LOW_RAM_APP_POOL_MB 0'])

    def test_valid(self):
        self.assertEqual(self.macros('low_ram_place_part_mb\t256\nlow_ram_img_cache_max_mb\t1024\n'),
                         ['#define DM_GAME_LOW_RAM_PLACE_PART_MB 256', '#define DM_GAME_LOW_RAM_IMG_CACHE_MAX_MB 1024', '#define DM_GAME_LOW_RAM_APP_POOL_MB 0'])
        for rows in ('low_ram_place_part_mb\t16\n', 'low_ram_place_part_mb\t0\n', 'low_ram_img_cache_max_mb\t512\n', 'low_ram_img_cache_max_mb\t65536\n'):
            self.assertEqual(header(rows), (0, ''), rows)

    def test_rejected(self):
        for rows, msg in (('low_ram_place_part_mb\t8\n', 'low_ram_place_part_mb must be'), ('low_ram_place_part_mb\t0x100\n', 'low_ram_place_part_mb must be'),
                          ('low_ram_place_part_mb\t-1\n', 'low_ram_place_part_mb must be'), ('low_ram_img_cache_max_mb\t256\n', 'low_ram_img_cache_max_mb must be'),
                          ('low_ram_img_cache_max_mb\t1 GB\n', 'low_ram_img_cache_max_mb must be'), ('low_ram_img_cache_max_mb\t70000\n', 'low_ram_img_cache_max_mb must be')):
            rc, out = header(rows)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(msg, out, rows)

    def test_app_pool(self):   # a 9 GiB app_pool (9216 MiB), as Fire Emblem Fortune's Weave's
        big = TSV.replace('app_pool\t0x100000', 'app_pool\t0x240000000')
        self.assertEqual(self.macros('low_ram_app_pool_mb\t5632\n', big)[-1], '#define DM_GAME_LOW_RAM_APP_POOL_MB 5632')
        for rows in ('low_ram_app_pool_mb\t0\n', 'low_ram_app_pool_mb\t1024\n', 'low_ram_app_pool_mb\t9215\n'):
            self.assertEqual(self.macros(rows, big)[-1].split()[-1], rows.split()[-1], rows)
        with tempfile.TemporaryDirectory() as d:
            for rows in ('low_ram_app_pool_mb\t1023\n', 'low_ram_app_pool_mb\t9216\n', 'low_ram_app_pool_mb\t10000\n', 'low_ram_app_pool_mb\t0x1600\n', 'low_ram_app_pool_mb\t-1\n'):
                Path(d, ID).mkdir(exist_ok=True)
                (Path(d, ID) / 'game.tsv').write_text(big + rows)
                r = subprocess.run([sys.executable, str(GAME), 'header', ID, str(Path(d, ID) / 'game.h')], env={**os.environ, 'DM_GAMES_DIR': d}, capture_output=True, text=True)
                self.assertNotEqual(r.returncode, 0, rows)
                self.assertIn('low_ram_app_pool_mb must be', r.stderr + r.stdout, rows)
        rc, out = header('low_ram_app_pool_mb\t1024\n')   # the synthetic profile's 1 MiB pool leaves no value
        self.assertNotEqual(rc, 0)
        self.assertIn("below the profile's app_pool (1 MiB)", out)


if __name__ == '__main__':
    unittest.main()
