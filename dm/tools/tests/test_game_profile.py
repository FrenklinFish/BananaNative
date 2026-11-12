# dm/games/game.py's profile-key validation, on a synthetic profile: pso_gpu (the GPU architectures the app's offline pipeline archive is made for, beside pso_mtl4) takes, none and no others,
# and a profile without the key generates its header unchanged; romfs_check (a RomFS file line that tells a version's data apart) takes <path> <line> <expected | main>;
# scene_arg (a 3D scene choice passed as game arguments) takes <name> <scale | -> <arguments> <label>;
# the low-RAM keys (low_ram_place_part_mb, low_ram_img_cache_max_mb, low_ram_app_pool_mb: Android, devices of 12 GB or less) take MiB in their ranges and generate 0 when unset;
# low_ram_app_pool_mb from 1024 MiB to below the profile's app_pool; rt_pool (a render target pool's movz, loader.c) takes a checked word and needs render_cfg and a low-RAM key
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


RC = 'render_cfg\t0123456789abcdef0123456789abcdef\t0x223e0b0\t2\t16\t0x0\t0x8\t2048x1152/1024x576,1920x1080/960x540\toriginal=2048x1152\tmax_render=1280x720\tmin=720\n'
RQ = 'render_quality\tquality=boost:2\tperformance=normal:1\n'


class RenderCfg(unittest.TestCase):   # render_cfg + render_quality (hle_render.c): the header's rows, the row checks and the keys it excludes
    def header_text(self, rows):
        with tempfile.TemporaryDirectory() as d:
            Path(d, ID).mkdir()
            (Path(d, ID) / 'game.tsv').write_text(TSV + rows)
            r = subprocess.run([sys.executable, str(GAME), 'header', ID, str(Path(d, ID) / 'game.h')], env={**os.environ, 'DM_GAMES_DIR': d}, capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            return (Path(d, ID) / 'game.h').read_text()

    def test_rows(self):
        h = self.header_text(RC + RQ + 'android_window\toriginal\n')
        self.assertIn('#define DM_GAME_RENDER_CFG {{"0123456789abcdef0123456789abcdef", 0x223e0b0, 2, 16, 0x0, 0x8, {{2048, 1152, 1024, 576}, {1920, 1080, 960, 540}}, 2048, 1152, 1280, 720, 720}}', h)
        self.assertIn('#define DM_GAME_NRENDER_CFG 1', h)
        self.assertIn('#define DM_GAME_RENDER_QUALITY {1, 2, 0, 1}', h)
        self.assertIn('#define DM_GAME_RENDER_DEFAULT_OUT "original"', h)

    def test_none(self):   # without the keys: no row, the default output 1920x1080
        h = self.header_text('')
        self.assertIn('#define DM_GAME_NRENDER_CFG 0', h)
        self.assertIn('#define DM_GAME_RENDER_QUALITY {0, 0, 0, 0}', h)
        self.assertIn('#define DM_GAME_RENDER_DEFAULT_OUT "1920x1080"', h)

    def test_rejected_rows(self):
        bad = [RC.replace('\t2\t16', '\t3\t16'),                  # 3 slots, 2 sizes
               RC.replace('\t16\t', '\t12\t'),                    # stride not a multiple of 8
               RC.replace('\t0x8\t', '\t0x4\t'),                  # fields overlap
               RC.replace('min=720', 'min=700'),
               RC.replace('\tmin=720', ''),
               RC.replace('original=2048x1152', 'original=0x1152'),
               RC.replace('/960x540', '/960'),
               RC.replace('0123456789abcdef', 'xyz', 1)]
        for rows in bad:
            rc, out = header(rows + RQ)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(': render_cfg takes <main build ID (32 hex)>', out, rows)

    def test_quality_row(self):
        for rows in (RC, RC + RQ + RQ, RC + 'render_quality\tquality=boost:4\tperformance=normal:1\n', RC + 'render_quality\tquality=fast:2\tperformance=normal:1\n',
                     RC + 'render_quality\tquality=boost:2\n', RC + 'render_quality\tquality=boost:2\tperformance=normal:1\tx=1\n'):
            rc, out = header(rows)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(': render_cfg needs one render_quality row', out, rows)
        rc, out = header(RQ)
        self.assertNotEqual(rc, 0)
        self.assertIn(': render_quality needs a render_cfg row', out)

    def test_excluded_keys(self):
        for k, row in (('graphics_scale', 'graphics_scale\t1920x1080\t0\thandheld\n'), ('window_fn', 'window_fn\t0123456789abcdef0123456789abcdef\t0x10\t0x20\t1920x1080\n'),
                       ('res_levels', 'res_levels\t0123456789abcdef0123456789abcdef\t0x10\t4\t0x40:4\n')):
            rc, out = header(RC + RQ + row)
            self.assertNotEqual(rc, 0, k)
            self.assertIn(f': render_cfg and {k} are two levers for one setting: keep one', out, k)


RP = 'rt_pool\t0123456789abcdef0123456789abcdef\tmain\t0x646f88\t52a7e009\t1008\t1536=1920x1080\t2048=2560x1440\tout_h=1440\n'
LR = 'low_ram_place_part_mb\t256\n'


class RtPool(unittest.TestCase):   # rt_pool (loader.c rt_pool_apply, rt_pool.h): the row, its word check and what it needs (render_cfg of the build, a low-RAM key)
    def macros(self, rows):
        with tempfile.TemporaryDirectory() as d:
            Path(d, ID).mkdir()
            (Path(d, ID) / 'game.tsv').write_text(TSV + rows)
            r = subprocess.run([sys.executable, str(GAME), 'header', ID, str(Path(d, ID) / 'game.h')], env={**os.environ, 'DM_GAMES_DIR': d}, capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            return [ln for ln in (Path(d, ID) / 'game.h').read_text().splitlines() if 'RT_POOL' in ln]

    def test_row(self):
        self.assertEqual(self.macros(RC + RQ + LR + RP), ['#define DM_GAME_RT_POOL {{"0123456789abcdef0123456789abcdef", "main", 0x646f88, 0x52a7e009u, 1008, 2, {1536, 2048}, '
                                                          '{1920, 2560}, {1080, 1440}, 1440}}', '#define DM_GAME_NRT_POOL 1'])
        self.assertEqual(self.macros(RC + RQ + LR + RP.replace('2048=', '4095=')).count('#define DM_GAME_NRT_POOL 1'), 1)
        # one larger size, no out_h; four sizes; the same max_render twice
        self.assertEqual(self.macros(RC + RQ + LR + RP.replace('\t2048=2560x1440\tout_h=1440', ''))[0],
                         '#define DM_GAME_RT_POOL {{"0123456789abcdef0123456789abcdef", "main", 0x646f88, 0x52a7e009u, 1008, 1, {1536}, {1920}, {1080}, 0}}')
        self.assertIn('1008, 4, {1100, 1536, 2048, 3000}, {1280, 1920, 2560, 2560}, {720, 1080, 1440, 1440}, 1440}}',
                      self.macros(RC + RQ + LR + RP.replace('\t1536=', '\t1100=1280x720\t1536=').replace('\tout_h', '\t3000=2560x1440\tout_h'))[0])

    def test_none(self):
        self.assertEqual(self.macros(''), ['#define DM_GAME_RT_POOL {{"", "", 0, 0, 0, 0, {0}, {0}, {0}, 0}}', '#define DM_GAME_NRT_POOL 0'])

    def test_rejected_rows(self):
        bad = [RP.replace('52a7e009', '52a7e00'),             # not 8 hex
               RP.replace('52a7e009', '52ac0009'),            # the word's immediate is not the game's own size
               RP.replace('52a7e009', 'd2a7e009'),            # movz x: not the w form
               RP.replace('52a7e009', '52807e09'),            # no lsl #16
               RP.replace('\t1536=', '\t1008='),             # not larger
               RP.replace('\t2048=', '\t1536='),             # not ascending
               RP.replace('\t2048=', '\t4096='),             # past movz w, lsl #16
               RP.replace('2560x1440', '1600x900'),             # a max_render smaller than the one before
               RP.replace('\t1536=1920x1080\t2048=2560x1440', ''),   # no larger size
               RP.replace('\tout_h=1440', '\t2100=2560x1440\t2200=2560x1440\t2300=2560x1440\tout_h=1440'),   # five
               RP.replace('\tout_h=1440', '\tout_h=x'),
               RP.replace('\tout_h=1440', '\tout_h=1440\tout_h=1080'),
               RP.replace('\t2048=2560x1440\tout_h=1440', '\tout_h=1440\t2048=2560x1440'),   # out_h before a size
               RP.replace('0x646f88', '0x646f8a'),            # not a word
               RP.replace('0x646f88', '646f88'),
               RP.replace('\tmain\t', '\tsdk\t'),             # not a module of the profile
               RP.replace('1536=1920x1080', '1536=1920'),
               RP.replace('1536=1920x1080', '1536'),
               RP.replace('1536=1920x1080', 'max_render=1920x1080'),
               RP.replace('0123456789abcdef', 'xyz', 1)]
        for rows in bad:
            rc, out = header(RC + RQ + LR + rows)
            self.assertNotEqual(rc, 0, rows)
            self.assertIn(': rt_pool takes <build ID (32 hex)> <module>', out, rows)

    def test_needs(self):
        rc, out = header(LR + RP)
        self.assertNotEqual(rc, 0)
        self.assertIn(': rt_pool needs a render_cfg row of the same main build', out)
        rc, out = header(RC.replace('0123456789abcdef', 'fedcba9876543210', 1) + RQ + LR + RP)
        self.assertNotEqual(rc, 0)
        self.assertIn(': rt_pool needs a render_cfg row of the same main build', out)
        rc, out = header(RC + RQ + LR + RP.replace('1536=1920x1080', '1536=1280x540'))
        self.assertNotEqual(rc, 0)
        self.assertIn(": rt_pool max_render must be at least render_cfg's 1280x720", out)
        rc, out = header(RC + RQ + RP)
        self.assertNotEqual(rc, 0)
        self.assertIn(': rt_pool needs a low-RAM key', out)
        rc, out = header(RC + RQ + LR + RP + RP)
        self.assertNotEqual(rc, 0)
        self.assertIn(': rt_pool: one row per build and module', out)


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
