"""dm/tools/scene_res.py on a synthetic RomFS: a Pack/ZsDic.pack.zs with a trained dictionary, and a pack (zstd with that dictionary) whose SARC holds a BYML
palette with two palettes sharing their level nodes, as Ocarina of Time's does. No game data: every byte is made here. Also scripts/pack_android.py's scene_overlays."""
import io
import os
import random
import struct
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools')); sys.path.insert(0, str(REPO / 'dm/games')); sys.path.insert(0, str(REPO / 'scripts'))
import scene_res   # noqa: E402
import game   # noqa: E402

try: from compression import zstd
except ImportError:
    try: from backports import zstd
    except ImportError: zstd = None

PACK = 'Pack/Bootup.Test.pack.zs'
MEMBER = 'System/GameGraphics/ModelSceneExtensionParam/Default.game__gfx__ModelSceneExtensionParam.bgyml'
SCALES = [0.75, 0.66667, 0.5, 0.33334]


def pad4(b): return b + b'\0' * (-len(b) % 4)


class BymlWriter:
    """A little-endian BYML version 7: a key table, then nodes appended children first (so every reference is known when its parent is written)"""
    def __init__(self, keys):
        self.keys = sorted(keys)
        kt = bytearray(struct.pack('<I', 0xC2 | len(self.keys) << 8)); offs, strs = [], bytearray()
        base = 4 + 4 * (len(self.keys) + 1)
        for k in self.keys: offs.append(base + len(strs)); strs += k.encode() + b'\0'
        offs.append(base + len(strs))
        kt += struct.pack(f'<{len(offs)}I', *offs) + strs
        self.b = bytearray(16) + pad4(bytes(kt))

    def node(self, data):
        at = len(self.b); self.b += pad4(data); return at

    def dict(self, items):   # [(key, type, value)]
        items = sorted(items, key=lambda x: self.keys.index(x[0]))
        return self.node(struct.pack('<I', 0xC1 | len(items) << 8) + b''.join(struct.pack('<I', self.keys.index(k) | t << 24) + struct.pack('<I', v) for k, t, v in items))

    def array(self, items):   # [(type, value)]
        return self.node(struct.pack('<I', 0xC0 | len(items) << 8) + pad4(bytes(t for t, _ in items)) + b''.join(struct.pack('<I', v) for _, v in items))

    def hashdict(self, items):   # [(hash, type, value)]
        return self.node(struct.pack('<I', 0x20 | len(items) << 8) + b''.join(struct.pack('<II', h, v) for h, _, v in items) + pad4(bytes(t for _, t, _ in items)))

    def done(self, root):
        struct.pack_into('<2sHIII', self.b, 0, b'YB', 7, 16, 0, root)
        return bytes(self.b)


def fbits(x): return struct.unpack('<I', struct.pack('<f', x))[0]


def palette_byml(scales):
    w = BymlWriter(['DynamicResolutionParam', 'LevelPaletteDict', 'DynamicResolutionLevelParamList', 'Priority', 'ResolutionReduceScale', 'LoadThreshold', 'Other'])
    levels = [w.dict([('Priority', 0xD1, i), ('ResolutionReduceScale', 0xD2, fbits(s))]) for i, s in enumerate(scales)]
    full = w.array([(0xC1, o) for o in levels]); part = w.array([(0xC1, o) for o in levels[:2]])   # the second palette shares the first two level nodes
    p1 = w.dict([('DynamicResolutionLevelParamList', 0xC0, full), ('LoadThreshold', 0xD2, fbits(95.0))])
    p2 = w.dict([('DynamicResolutionLevelParamList', 0xC0, part), ('LoadThreshold', 0xD2, fbits(108.0))])
    pal = w.hashdict([(0x2ebeca77, 0xC1, p1), (0x90e0a495, 0xC1, p2)])
    drp = w.dict([('LevelPaletteDict', 0x20, pal)])
    return w.done(w.dict([('DynamicResolutionParam', 0xC1, drp), ('Other', 0xD2, fbits(0.5))]))


def sarc(members):   # [(name, bytes)]
    names = bytearray(); ents = []
    data = bytearray()
    for nm, b in members:
        while len(data) % 8: data += b'\0'
        ents.append((len(names) // 4, len(data), len(data) + len(b))); data += b
        names += pad4(nm.encode() + b'\0')
    sfat = b'SFAT' + struct.pack('<HHI', 12, len(members), 0x65) + b''.join(struct.pack('<IIII', i, 0x01000000 | no, s, e) for i, (no, s, e) in enumerate(ents))
    sfnt = b'SFNT' + struct.pack('<HH', 8, 0) + bytes(names)
    doff = 0x14 + len(sfat) + len(sfnt); doff += -doff % 0x10
    head = b'SARC' + struct.pack('<HHIIHH', 0x14, 0xFEFF, doff + len(data), doff, 0x100, 0)
    body = head + sfat + sfnt
    return body + b'\0' * (doff - len(body)) + bytes(data)


def train():
    r = random.Random(7)
    words = [b'YB', b'SARC', b'ResolutionReduceScale', b'Priority', b'LoadThreshold', b'Sound', b'Reaction', b'\x00\x00\x40\x3f']
    samples = [b''.join(r.choice(words) for _ in range(r.randint(40, 300))) for _ in range(400)]
    return zstd.train_dict(samples, 4096)


def romfs(root, scales=SCALES, with_dict=True):
    """A RomFS folder: Pack/ZsDic.pack.zs and the palette's pack. → the dictionary (None: the pack is plain zstd)"""
    zd = train() if with_dict else None
    (root / 'Pack').mkdir(parents=True)
    filler = bytes(random.Random(3).randrange(256) for _ in range(3000))
    s = sarc([('Sound/A.bgyml', filler), (MEMBER, palette_byml(scales)), ('Z/Last.bgyml', filler[::-1])])
    if zd is not None:
        (root / scene_res.ZSDIC).write_bytes(zstd.compress(sarc([('zs.zsdic', b'\x37\xa4\x30\xec' + b'\0' * 60), ('pack.zsdic', zd.dict_content)])))
        P = zstd.CompressionParameter
        (root / PACK).write_bytes(zstd.compress(s, options={P.content_size_flag: 1, P.checksum_flag: 0, P.dict_id_flag: 1}, zstd_dict=zd))
    else: (root / PACK).write_bytes(zstd.compress(s))
    return zd, s


def profile(d, rows):
    p = Path(d) / '0123456789ABCDEF'; p.mkdir()
    (p / 'game.tsv').write_text('title_id\t0123456789ABCDEF\n' + ''.join('\t'.join(r) + '\n' for r in rows))
    return game.Game(p)


PAL = ('scene_scale_palette', PACK, 'ModelSceneExtensionParam', ','.join(map(str, SCALES)))
PRESETS = [('scene_scale', '720p', '0.625', 'fixed', 'About', '720p'), ('scene_scale', 'min', '0.6', 'floor', 'At least 720p')]


@unittest.skipUnless(zstd, 'no zstd module (Python 3.14 compression.zstd or backports.zstd)')
class SceneRes(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.TemporaryDirectory(); self.d = Path(self.t.name); self.root = self.d / 'romfs'
        self.zd, self.sarc = romfs(self.root)
        self.g = profile(self.d, [PAL, *PRESETS])

    def tearDown(self): self.t.cleanup()

    def sub(self, name):
        d = self.d / 'profiles' / name; d.mkdir(parents=True); return d

    def decoded(self, path):
        return zstd.decompress(path.read_bytes(), zstd_dict=self.zd) if self.zd else zstd.decompress(path.read_bytes())

    def test_finds_every_shared_word_once(self):
        p = scene_res.find(str(self.root), self.g)
        self.assertEqual([round(v, 5) for v in p.scales()], SCALES)
        self.assertTrue(p.matches())
        self.assertEqual(p.head['dict_id'], self.zd.dict_id)

    def test_make_fixed_and_floor(self):
        out = self.d / 'out'
        with redirect_stdout(io.StringIO()): made = scene_res.make(str(self.root), out, self.g)
        self.assertEqual([m[0] for m in made], ['720p', 'min'])
        orig = (self.root / PACK).read_bytes(); oh = scene_res.frame_header(orig)
        for name, want in (('720p', [0.625] * 4), ('min', [0.75, 0.66667, 0.6, 0.6])):
            f = out / name / PACK
            h = scene_res.frame_header(f.read_bytes())
            self.assertEqual((h['dict_id'], h['content_size'], h['checksum']), (oh['dict_id'], oh['content_size'], oh['checksum']), name)
            new = self.decoded(f)
            self.assertEqual(len(new), len(self.sarc), 'the pack keeps its size')
            diff = [i for i in range(0, len(new), 4) if new[i:i + 4] != self.sarc[i:i + 4]]
            p = scene_res.Palette(str(out / name), PAL[1:], dict_root=str(self.root))
            self.assertEqual([round(v, 5) for v in p.scales()], want, name)
            self.assertLessEqual(set(diff), {p.start + at for at, _ in p.words}, 'only scale words differ')
            self.assertEqual(len(diff), sum(1 for a, b in zip(want, SCALES) if round(a, 5) != round(b, 5)), name)
        rows = (out / scene_res.MANIFEST).read_text().splitlines()
        self.assertTrue(rows[0].startswith(f'source\t{PACK}\t{MEMBER}\t'))
        self.assertIn('preset\tmin\t0.6\tfloor\t0.75,0.66667,0.6,0.6\tAt least 720p', rows)

    def test_another_build_is_refused(self):
        g = profile(self.sub('other'), [('scene_scale_palette', PACK, 'ModelSceneExtensionParam', '0.75,0.7,0.5,0.33334'), *PRESETS])
        with self.assertRaisesRegex(scene_res.Error, 'another build'): scene_res.make(str(self.root), self.d / 'out', g)
        self.assertFalse((self.d / 'out' / scene_res.MANIFEST).exists())

    def test_member_not_found(self):
        g = profile(self.sub('m'), [('scene_scale_palette', PACK, 'NoSuchParam', '0.75'), *PRESETS])
        with self.assertRaisesRegex(scene_res.Error, '0 members'): scene_res.find(str(self.root), g)

    def test_bad_preset_rows(self):
        for row in (('scene_scale', 'x', '2', 'fixed'), ('scene_scale', 'x', '0.5', 'sideways'), ('scene_scale', 'a/b', '0.5', 'fixed')):
            with self.assertRaises(scene_res.Error, msg=row): scene_res.presets(profile(self.sub(row[1].replace('/', '_') + row[3]), [PAL, row]))

    def test_cli_exit_codes(self):
        env = dict(os.environ, DM_GAMES_DIR=str(self.d), DM_GAMES_FIRST='1')
        tool = str(REPO / 'dm/tools/scene_res.py')
        r = subprocess.run([sys.executable, tool, str(self.root), str(self.d / 'cli'), '--game', self.g.id], env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertTrue((self.d / 'cli' / '720p' / PACK).is_file())
        r = subprocess.run([sys.executable, tool, str(self.root), str(self.d / 'one'), '--game', self.g.id, '--scale', '0.4', '--name', 'x'], env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual([round(v, 5) for v in scene_res.Palette(str(self.d / 'one' / 'x'), PAL[1:], dict_root=str(self.root)).scales()], [0.4] * 4)
        (self.root / PACK).write_bytes(b'not zstd')
        r = subprocess.run([sys.executable, tool, str(self.root), '--show', '--game', self.g.id], env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn('[RED]', r.stderr)

    def test_plain_zstd_pack(self):
        root = self.d / 'plain'; zd, s = romfs(root, with_dict=False)
        with redirect_stdout(io.StringIO()): scene_res.make(str(root), self.d / 'po', self.g)
        new = zstd.decompress((self.d / 'po' / '720p' / PACK).read_bytes())
        self.assertEqual(len(new), len(s))
        self.assertEqual(scene_res.frame_header((self.d / 'po' / '720p' / PACK).read_bytes())['dict_id'], 0)

    def test_pack_step_overlays(self):
        import pack_android
        out = self.d / 'pack'; out.mkdir(); (out / 'scene_res' / 'gone').mkdir(parents=True)
        with redirect_stdout(io.StringIO()): rows = pack_android.scene_overlays(self.g, self.root, out)
        self.assertEqual(rows, [('720p', 'scene_res/720p'), ('min', 'scene_res/min')])
        self.assertFalse((out / 'scene_res' / 'gone').exists(), 'a preset the profile no longer has is removed')
        self.assertTrue((out / 'scene_res' / 'min' / PACK).is_file())
        (self.root / PACK).write_bytes(b'not zstd')
        buf = io.StringIO()
        with redirect_stdout(buf): rows = pack_android.scene_overlays(self.g, self.root, out)
        self.assertEqual(rows, []); self.assertIn('[RED] scene resolution overlays not made', buf.getvalue())
        self.assertFalse((out / 'scene_res').exists(), 'no stale overlay after a failure')
        g = profile(self.sub('none'), [])
        self.assertEqual(pack_android.scene_overlays(g, self.root, out), [])


    def test_preset_overlays(self):
        import pack_android
        g = profile(self.sub('res'), [PAL, *GS, *OUTS, *RES])
        out = self.d / 'rp'; out.mkdir()
        with redirect_stdout(io.StringIO()): rows = pack_android.scene_overlays(g, self.root, out)
        self.assertEqual(rows, [('balanced', 'scene_res/balanced'), ('fast', 'scene_res/fast')], 'one overlay per preset with a scene of its own; Quality has none')
        for name, want in (('balanced', [0.75, 0.66667, 2 / 3, 2 / 3]), ('fast', [0.5] * 4)):
            got = scene_res.Palette(str(out / 'scene_res' / name), PAL[1:], dict_root=str(self.root)).scales()
            self.assertEqual([round(v, 5) for v in got], [round(v, 5) for v in want], name)
        man = (out / 'scene_res' / scene_res.MANIFEST).read_text()
        self.assertIn('preset\tbalanced\t0.666667\tfloor\t', man)
        self.assertIn('preset\tfast\t0.5\tfixed\t0.5,0.5,0.5,0.5\tMax performance', man)

    def corrupt_cases(self):
        """(what, pack bytes): a truncated frame, a frame that is not zstd past its magic, and a SARC whose BYML root offset points past the member"""
        orig = (self.root / PACK).read_bytes()
        yield 'truncated zstd frame', orig[:len(orig) // 2]
        yield 'garbage after the magic', orig[:4] + bytes(range(256)) * 4
        s = bytearray(self.sarc); at = s.find(b'YB\x07\x00'); struct.pack_into('<I', s, at + 12, 0x7FFFFFF0)
        P = zstd.CompressionParameter
        yield 'bad BYML root offset', zstd.compress(bytes(s), options={P.content_size_flag: 1, P.dict_id_flag: 1}, zstd_dict=self.zd)
        s = bytearray(self.sarc); struct.pack_into('<I', s, at + 4, len(self.sarc) * 4)   # the key table past the end
        yield 'bad BYML key table offset', zstd.compress(bytes(s), options={P.content_size_flag: 1, P.dict_id_flag: 1}, zstd_dict=self.zd)

    def test_corrupt_packs_are_errors(self):
        for what, data in self.corrupt_cases():
            (self.root / PACK).write_bytes(data)
            with self.assertRaises(scene_res.Error, msg=what) as cm: scene_res.make(str(self.root), self.d / 'bad', self.g)
            self.assertIn(PACK, str(cm.exception), what)

    def test_corrupt_packs_never_fail_the_pack_step(self):
        import pack_android
        out = self.d / 'pack2'; out.mkdir(); good = (self.root / PACK).read_bytes()
        for what, data in self.corrupt_cases():
            with redirect_stdout(io.StringIO()): self.assertTrue(pack_android.scene_overlays(self.g, self.root, out), 'a good pack first')
            (self.root / PACK).write_bytes(data)
            buf = io.StringIO()
            with redirect_stdout(buf): rows = pack_android.scene_overlays(self.g, self.root, out)
            self.assertEqual(rows, [], what)
            self.assertIn('[RED] scene resolution overlays not made', buf.getvalue(), what)
            self.assertFalse((out / 'scene_res').exists(), what)
            (self.root / PACK).write_bytes(good)   # the good pack back for the next case

    def test_unexpected_exception_never_fails_the_pack_step(self):
        import pack_android
        real = scene_res.make
        def boom(*a, **k): raise RuntimeError('something new')
        scene_res.make = boom
        try:
            buf = io.StringIO()
            with redirect_stdout(buf): rows = pack_android.scene_overlays(self.g, self.root, self.d)
        finally: scene_res.make = real
        self.assertEqual(rows, [])
        self.assertIn('[RED] scene resolution overlays not made: RuntimeError: something new', buf.getvalue())
        rows = [('format', '1'), *[('scene_res', f'{n}\t{p}') for n, p in rows]]   # what main() writes into pack.tsv from it
        self.assertFalse(any(k == 'scene_res' for k, _ in rows))


GS = [('graphics_scale', '1920x1080', '0', 'handheld'), ('graphics_scale', '1280x720', '2', 'handheld'), ('graphics_scale', 'original', '1', 'console')]
OUTS = [('scene_output', '1920x1080', '1920x1080', 'open'), ('scene_output', '1280x720', '1280x720', 'measured')]
RES = [('res_preset', 'quality', '1920x1080', 'default', 'Quality'), ('res_preset', 'balanced', '1920x1080', 'floor:720', 'Balanced'),
       ('res_preset', 'fast', '1280x720', 'fixed:360', 'Max', 'performance')]


class Rows(unittest.TestCase):
    """The rows the launcher's Resolution presets come from (scene_output, res_preset) and the scale computation: no RomFS needed"""
    def setUp(self): self.t = tempfile.TemporaryDirectory(); self.d = Path(self.t.name)
    def tearDown(self): self.t.cleanup()

    def g(self, rows, name='p'):
        d = self.d / name; d.mkdir(); return profile(d, rows)

    def test_scale(self):
        self.assertAlmostEqual(scene_res.preset_scale(720, 1080), 2 / 3)
        self.assertEqual(scene_res.preset_scale(540, 720), 0.75)
        self.assertEqual(scene_res.preset_scale(360, 720), 0.5)

    def test_targets(self):
        self.assertEqual(scene_res.target('default'), ('default', 0))
        self.assertEqual(scene_res.target('floor:720'), ('floor', 720))
        self.assertEqual(scene_res.target('fixed:360'), ('fixed', 360))
        for bad in ('fixed', 'fixed:', 'fixed:-1', 'fixed:0', 'sideways:720', 'floor:7x'):
            self.assertIsNone(scene_res.target(bad), bad)

    def test_rows(self):
        g = self.g([PAL, *GS, *OUTS, *RES, *PRESETS])
        self.assertEqual(scene_res.outputs(g), {'1920x1080': (1920, 1080, False), '1280x720': (1280, 720, True)})
        ps = scene_res.res_presets(g)
        self.assertEqual([(p[0], p[1], p[2], p[3], p[5]) for p in ps], [('quality', '1920x1080', 'default', 0, 'Quality'), ('balanced', '1920x1080', 'floor', 720, 'Balanced'),
                                                                         ('fast', '1280x720', 'fixed', 360, 'Max performance')])
        self.assertIsNone(ps[0][4]); self.assertAlmostEqual(ps[1][4], 2 / 3); self.assertEqual(ps[2][4], 0.5)
        self.assertEqual([(n, m) for n, _, m, _ in scene_res.presets(g)], [('720p', 'fixed'), ('min', 'floor'), ('balanced', 'floor'), ('fast', 'fixed')],
                         'the overlays: scene_scale rows, then the presets with a scene of their own')

    def test_bad_rows(self):
        cases = [
            ('a window that is no graphics_scale choice', [('res_preset', 'x', '2560x1440', 'default', 'X')]),
            ('a malformed target', [('res_preset', 'x', '1920x1080', 'fixed:abc', 'X')]),
            ('a bad id', [('res_preset', 'a/b', '1920x1080', 'default', 'X')]),
            ('a repeated id', [RES[0], RES[0]]),
            ('a scene without an output row', [('res_preset', 'x', 'original', 'fixed:540', 'X')]),
            ('a scale above 1', [('res_preset', 'x', '1280x720', 'fixed:1080', 'X')]),
            ('an id that is a scene_scale name', [('res_preset', 'min', '1280x720', 'fixed:360', 'X'), *PRESETS]),
            ('a bad output row', [('scene_output', '1920x1080', '1920', 'open')]),
            ('an output for no choice', [('scene_output', '2560x1440', '2560x1440', 'open')]),
            ('an output that is neither measured nor open', [('scene_output', '1920x1080', '1920x1080', 'maybe')]),
        ]
        for k, (what, rows) in enumerate(cases):
            g = self.g([PAL, *GS, *OUTS[1:], *rows], f'b{k}')
            with self.assertRaises(scene_res.Error, msg=what): scene_res.presets(g)
        g = self.g([*GS, *OUTS, ('res_preset', 'x', '1280x720', 'fixed:360', 'X')], 'nopal')
        with self.assertRaisesRegex(scene_res.Error, 'scene_scale_palette'): scene_res.res_presets(g)
        g = self.g([*GS, ('res_preset', 'q', '1920x1080', 'default', 'Q'), ('res_preset', 'p', '1280x720', 'default', 'P')], 'windows')
        self.assertEqual([p[4] for p in scene_res.res_presets(g)], [None, None], 'a game without a palette: window-only presets')
        self.assertEqual(scene_res.presets(g), [])

    def test_cli_presets(self):
        env = dict(os.environ, DM_GAMES_DIR=str(self.d), DM_GAMES_FIRST='1')
        self.g([PAL, *GS, *OUTS, *RES], 'cli')
        r = subprocess.run([sys.executable, str(REPO / 'dm/tools/scene_res.py'), '--presets', '--game', str(self.d / 'cli' / '0123456789ABCDEF')], env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('fast\tMax performance\twindow 1280x720\tfixed 360 lines: scale 0.5 of 1280x720 (measured), about 640x360', r.stdout)


class Profiles(unittest.TestCase):
    """Every profile's res_preset, scene_output and scene_scale rows parse, and Ocarina of Time's presets have the scales its notes give"""
    def test_every_profile(self):
        for g in game.all_games():
            if g.values('res_preset') or g.values('scene_output') or g.values('scene_scale'):
                scene_res.presets(g); scene_res.res_presets(g)

    def test_oot(self):
        g = game.load('0400486024DEC000')
        ps = {p[0]: p for p in scene_res.res_presets(g)}
        self.assertEqual(list(ps), ['quality', 'balanced', 'performance', 'max-performance'])
        self.assertEqual((ps['quality'][1], ps['quality'][4]), ('1920x1080', None), 'the default is today\'s start: 1080p and the game\'s own palette')
        self.assertEqual((ps['balanced'][1], ps['balanced'][2]), ('1920x1080', 'floor')); self.assertAlmostEqual(ps['balanced'][4], 720 / 1080)
        self.assertEqual((ps['performance'][1], ps['performance'][2], ps['performance'][4]), ('1280x720', 'fixed', 0.75))
        self.assertEqual((ps['max-performance'][1], ps['max-performance'][2], ps['max-performance'][4]), ('1280x720', 'fixed', 0.5))
        self.assertEqual([p[0] for p in scene_res.presets(g)], ['720p', '720p-min', '540p', '480p', '360p', 'balanced', 'performance', 'max-performance'])


if __name__ == '__main__':
    unittest.main()
