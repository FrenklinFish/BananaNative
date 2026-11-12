#!/usr/bin/env python3
"""Scene resolution overlays for gsys / agl games: the game's own dynamic resolution palette, rewritten so its 3D scene is drawn at a fixed scale (or never below one)
while the window crop stays where the operation mode and graphics scale put it; agl's upscaler (NDSR, or the host's FSR stand-in) takes the scene to the crop.

The palette is a BYML (.bgyml) inside a SARC pack of the RomFS that the profile names (game.tsv scene_scale_palette, dm/games/README.md): Ocarina of Time keeps it in
Pack/Bootup.Nin_Ounce_NVN2.pack.zs, System/GameGraphics/ModelSceneExtensionParam/Default.game__gfx__ModelSceneExtensionParam.bgyml, as
DynamicResolutionParam / LevelPaletteDict / <palette> / DynamicResolutionLevelParamList / [level] / ResolutionReduceScale (a float a level, highest first). The pack is
one zstd frame compressed with a dictionary of Pack/ZsDic.pack.zs (pack.zsdic, ID 4 there). The scales are found by key name and their float words rewritten in place, so
the member, the SARC and the decompressed pack keep their sizes (the game's resource size table holds the decompressed sizes); the pack is compressed again with the same
dictionary, its ID in the frame, and the content size and checksum flags of the original frame. The new frame is then decompressed and compared with the original: only
the rewritten words may differ. The original scales must be one of the profile's expected lists (another build of the game stops the tool rather than guessing).

Modes: fixed (every level takes the scale: the scene is one size) and floor (levels below the scale are raised to it: the game still lowers its resolution under load, never
below the scale). The overlay is a folder that mirrors the RomFS path of the pack: the host reads it first with DM_ROMFS_OVERLAY=<folder> (dm/host/hle_fs.c). It is made
from the player's own files (scripts/pack_android.py's pack step on the phone or the Mac) and never shipped: one folder per scene_scale row (Advanced › 3D scene, fixed
scales for testers) and per res_preset row with a floor or fixed scene (the launcher's Resolution presets). A res_preset names a window (a graphics_scale choice) and a scene
height; its scale is that height over the window's upscaler output height (the profile's scene_output row for the window): the scene is the output times the scale.

Usage:
  scene_res.py <romfs> --show [--game ID]                         the palette's scales as found, checked against the profile
  scene_res.py <romfs> <out> [--game ID]                          every overlay of the profile into <out>/<name>/, and <out>/scene_res.tsv
  scene_res.py --presets [--game ID]                              the profile's Resolution presets with their windows and scales (no RomFS)
  scene_res.py <romfs> <out> --scale S [--mode fixed|floor] [--name N] [--game ID]   one overlay of that scale into <out>/<N>/ (default name: custom)
<romfs> is a RomFS folder, a RomFS image or a .nup's <file>.nup:update/<version>/romfs (dm/tools/romfs.py). The game is --game, else DM_GAME. Exit 1 on error."""
import argparse, hashlib, os, struct, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE)); sys.path.insert(0, str(HERE.parent / 'games'))
import romfs   # noqa: E402
import nup   # noqa: E402

ZSTD_MAGIC = b'\x28\xb5\x2f\xfd'
DICT_MAGIC = b'\x37\xa4\x30\xec'
ZSDIC = 'Pack/ZsDic.pack.zs'
LEVEL = 19   # compression level of the rewritten pack: any level decompresses the same; 19 keeps it below the original's size (Ocarina of Time: 110 KB for 118 KB) in 0.1 s
MANIFEST = 'scene_res.tsv'
MODES = ('fixed', 'floor')
PATH_KEYS = ('DynamicResolutionParam', 'LevelPaletteDict', None, 'DynamicResolutionLevelParamList', None, 'ResolutionReduceScale')   # None: every child


class Error(Exception): pass


def guarded(what, f, *a):
    """f(*a), with any failure but an Error turned into an Error that names what was read and what went wrong: what a damaged or unexpected file raises from the
    parsers (struct.error, IndexError, KeyError, ValueError, UnicodeDecodeError) and from the zstd module (ZstdError)"""
    try: return f(*a)
    except Error: raise
    except nup.Error as e: raise Error(f'{what}: {e}') from None
    except Exception as e: raise Error(f'{what}: {type(e).__name__}: {e}') from None


def f32(x): return struct.unpack('<f', struct.pack('<f', x))[0]
def f32_bits(x): return struct.pack('<f', x)


# ---- zstd ----

def frame_header(d):
    """A zstd frame header's fields: {'dict_id', 'content_size' (None when not recorded), 'checksum', 'single'}"""
    if len(d) < 6 or d[:4] != ZSTD_MAGIC: raise Error('not a zstd frame')
    fhd = d[4]; single = fhd >> 5 & 1; did_n = (0, 1, 2, 4)[fhd & 3]; fcs_n = (single, 2, 4, 8)[fhd >> 6]
    o = 5 + (0 if single else 1)
    did = int.from_bytes(d[o:o + did_n], 'little'); o += did_n
    fcs = int.from_bytes(d[o:o + fcs_n], 'little') + (256 if fcs_n == 2 else 0) if fcs_n else None
    return {'dict_id': did, 'content_size': fcs, 'checksum': fhd >> 2 & 1, 'single': single}


def sarc_members(d, label):
    """[(name, start, end)] of a SARC's members, absolute offsets in d"""
    if d[:4] != b'SARC' or len(d) < 0x14: raise Error(f'{label}: not a SARC')
    hl, bom, size, doff = struct.unpack_from('<HHII', d, 4)
    if bom != 0xFEFF: raise Error(f'{label}: a big-endian SARC')
    if size != len(d): raise Error(f'{label}: the SARC says {size} bytes, it has {len(d)}')
    if d[hl:hl + 4] != b'SFAT': raise Error(f'{label}: no SFAT')
    n = struct.unpack_from('<H', d, hl + 6)[0]
    names = hl + 12 + 16 * n
    if d[names:names + 4] != b'SFNT': raise Error(f'{label}: no SFNT')
    names += 8; out = []
    for i in range(n):
        _, attr, s, e = struct.unpack_from('<IIII', d, hl + 12 + 16 * i)
        if not s <= e <= len(d) - doff: raise Error(f'{label}: member {i} lies outside the data')
        nm = ''
        if attr >> 24:
            o = names + (attr & 0xffffff) * 4; z = d.find(b'\0', o)
            nm = d[o:z].decode('utf-8', 'replace')
        out.append((nm, doff + s, doff + e))
    return out


def dictionaries(root, z):
    """{ID: dictionary bytes} of the RomFS's Pack/ZsDic.pack.zs (a plain zstd SARC of .zsdic members), {} without it"""
    p = os.path.join(root, ZSDIC)
    if not romfs.exists(p): return {}
    d = z.decompress(romfs.open(p, 'rb').read())
    out = {}
    for _, s, e in sarc_members(d, ZSDIC):
        if d[s:s + 4] == DICT_MAGIC: out[struct.unpack_from('<I', d, s + 4)[0]] = d[s:e]
    return out


# ---- BYML ----

class Byml:
    """The parts of a little-endian BYML (version 2 to 7) this tool reads: dictionaries (0xC1, and 0x20 keyed by 32-bit hashes), arrays (0xC0), and the
    float values (0xD2) under a key path, with the offset of each float's word"""
    def __init__(self, b, label):
        self.b, self.label = b, label
        if b[:2] != b'YB': raise Error(f'{label}: not a little-endian BYML')
        self.version, kt, st, self.root = struct.unpack_from('<HIII', b, 2)
        if not 2 <= self.version <= 7: raise Error(f'{label}: BYML version {self.version}')
        self.keys = self.strings(kt) if kt else []

    def strings(self, off):
        b = self.b
        if b[off] != 0xC2: raise Error(f'{self.label}: no string table at {off:#x}')
        n = struct.unpack_from('<I', b, off)[0] >> 8
        offs = struct.unpack_from(f'<{n + 1}I', b, off + 4)
        return [b[off + o:b.index(b'\0', off + o)].decode('utf-8', 'replace') for o in offs[:-1]]

    def children(self, off):
        """[(key or None, type, value word, offset of the value word)] of a container node"""
        b = self.b; t = b[off]; n = struct.unpack_from('<I', b, off)[0] >> 8; out = []
        if t == 0xC1:
            for i in range(n):
                e = off + 4 + 8 * i
                k = int.from_bytes(b[e:e + 3], 'little')
                out.append((self.keys[k] if k < len(self.keys) else None, b[e + 3], struct.unpack_from('<I', b, e + 4)[0], e + 4))
        elif t == 0xC0:
            vo = off + 4 + ((n + 3) & ~3)
            out = [(None, b[off + 4 + i], struct.unpack_from('<I', b, vo + 4 * i)[0], vo + 4 * i) for i in range(n)]
        elif t == 0x20:   # a dictionary keyed by 32-bit hashes (version 7): n (hash, value) pairs, then the n value types
            out = [(None, b[off + 4 + 8 * n + i], struct.unpack_from('<I', b, off + 8 + 8 * i)[0], off + 8 + 8 * i) for i in range(n)]
        else: raise Error(f'{self.label}: node type {t:#x} at {off:#x} is not a container')
        return out

    def floats(self, path):
        """[(offset, value)] of every float word under the key path (None: every child), in the order found; a node shared by several parents counts once"""
        seen, out = set(), []
        def walk(off, i):
            for k, t, v, at in self.children(off):
                if path[i] is not None and k != path[i]: continue
                if i == len(path) - 1:
                    if t != 0xD2: raise Error(f'{self.label}: {path[i]} at {at:#x} is of type {t:#x}, not a float')
                    if at not in seen: seen.add(at); out.append((at, struct.unpack_from('<f', self.b, at)[0]))
                elif t in (0xC0, 0xC1, 0x20): walk(v, i + 1)
                elif path[i] is not None: raise Error(f'{self.label}: {path[i]} at {at:#x} is of type {t:#x}, not a container')
        walk(self.root, 0)
        return out


# ---- The profile ----

def palette_rows(g):
    """The profile's scene_scale_palette rows: [(RomFS path of the pack, part of the member's name, expected scales)]"""
    out = []
    for v in g.values('scene_scale_palette'):
        if len(v) < 3: raise Error(f'{g.id}: scene_scale_palette takes <RomFS path> <member name part> <expected scales, comma-separated>: {v}')
        try: exp = [float(x) for x in v[2].split(',')]
        except ValueError: raise Error(f'{g.id}: scene_scale_palette expected scales are not numbers: {v[2]}') from None
        out.append((v[0].lstrip('/'), v[1], exp))
    return out


def scene_scale_rows(g):
    """The profile's scene_scale rows (Advanced › 3D scene, for testers): [(name, scale, mode, label)]"""
    out = []
    for v in g.values('scene_scale'):
        if len(v) < 3 or v[2] not in MODES: raise Error(f'{g.id}: scene_scale takes <name> <scale> <fixed|floor> <label>: {v}')
        try: s = float(v[1])
        except ValueError: raise Error(f'{g.id}: scene_scale {v[0]}: {v[1]} is not a number') from None
        if not 0.05 <= s <= 1.0: raise Error(f'{g.id}: scene_scale {v[0]}: {s} is outside 0.05..1')
        out.append((check_name(g, 'scene_scale', v[0]), s, v[2], ' '.join(v[3:]) or v[0]))
    return out


def check_name(g, key, name):
    if not name.replace('-', '').replace('_', '').isalnum(): raise Error(f'{g.id}: {key} name {name!r}: letters, digits, - and _ only (it names a folder)')
    return name


def size(text):
    """'WxH' → (w, h); None for anything else"""
    w, x, h = text.partition('x')
    return (int(w), int(h)) if x and w.isdigit() and h.isdigit() and int(w) > 0 and int(h) > 0 else None


def outputs(g):
    """The profile's scene_output rows: {window choice: (width, height, measured)}, the size the palette's scale multiplies at that window (agl's upscaler output); measured
    False: the best-supported value, open until a phone run measures the scene. A window choice is a graphics_scale row's first column (or any size for a window_fn game)"""
    choices = {v[0] for v in g.values('graphics_scale') if v}
    out = {}
    for v in g.values('scene_output'):
        if len(v) < 3 or size(v[1]) is None or v[2] not in ('measured', 'open'):
            raise Error(f'{g.id}: scene_output takes <window choice> <WxH> <measured|open>: {v}')
        if choices and v[0] not in choices: raise Error(f'{g.id}: scene_output {v[0]}: not a graphics_scale choice ({", ".join(sorted(choices))})')
        if v[0] in out: raise Error(f'{g.id}: scene_output {v[0]} repeats')
        out[v[0]] = (*size(v[1]), v[2] == 'measured')
    return out


def target(text):
    """A res_preset scene target: 'default' → ('default', 0); 'floor:<H>' / 'fixed:<H>' → (mode, H); None when malformed"""
    if text == 'default': return ('default', 0)
    m, c, h = text.partition(':')
    return (m, int(h)) if c and m in MODES and h.isdigit() and int(h) > 0 else None


def preset_scale(height, output_h):
    """The palette scale that draws the scene <height> lines high at a window whose upscaler output is <output_h> lines (the scene is the output times the scale)"""
    return height / output_h


def res_presets(g):
    """The profile's res_preset rows (the launcher's Resolution; the first is the default): [(id, window choice, mode ('default', 'floor' or 'fixed'), scene height or 0,
    scale or None, label)]. A floor or fixed target needs a scene_scale_palette row and the window's scene_output row; its scale is the height over that output's height"""
    choices = {v[0] for v in g.values('graphics_scale') if v}
    outs = outputs(g) if g.values('scene_output') else {}
    out = []
    for v in g.values('res_preset'):
        if len(v) < 3 or target(v[2]) is None: raise Error(f'{g.id}: res_preset takes <id> <window choice> <default|floor:H|fixed:H> <label>: {v}')
        pid, win = check_name(g, 'res_preset', v[0]), v[1]
        if choices and win not in choices: raise Error(f'{g.id}: res_preset {pid}: window {win} is not a graphics_scale choice ({", ".join(sorted(choices))})')
        mode, h = target(v[2]); scale = None
        if mode != 'default':
            if not g.values('scene_scale_palette'): raise Error(f'{g.id}: res_preset {pid}: a {mode} scene needs a scene_scale_palette row')
            if win not in outs: raise Error(f'{g.id}: res_preset {pid}: no scene_output row for window {win}')
            scale = preset_scale(h, outs[win][1])
            if not 0.05 <= scale <= 1.0: raise Error(f'{g.id}: res_preset {pid}: {h} lines at a {outs[win][1]}-line output is scale {scale:.4g}, outside 0.05..1')
        out.append((pid, win, mode, h, scale, ' '.join(v[3:]) or pid))
    if len({p[0] for p in out}) != len(out): raise Error(f'{g.id}: res_preset ids repeat')
    ids = {p[0] for p in out}
    for v in g.values('res_preset_from'):   # the choices that earlier profiles had: a stored one takes the preset it names (launcher: ResPresets.mapStored)
        if len(v) < 2: raise Error(f'{g.id}: res_preset_from takes <old id> <res_preset id>: {v}')
        if v[1] not in ids: raise Error(f'{g.id}: res_preset_from {v[0]}: {v[1]} is not a res_preset id')
        if v[0] in ids: raise Error(f'{g.id}: res_preset_from {v[0]}: still a res_preset id')
    return out


def presets(g):
    """Every overlay the profile asks for: its scene_scale rows, then its res_preset rows with a floor or fixed scene: [(name, scale, mode, label)]"""
    out = scene_scale_rows(g) + [(pid, scale, mode, label) for pid, _, mode, _, scale, label in res_presets(g) if scale is not None]
    if len({p[0] for p in out}) != len(out): raise Error(f'{g.id}: scene_scale names and res_preset ids repeat (they share the overlay folders)')
    return out


# ---- The palette ----

class Palette:
    """The pack of a RomFS that holds the palette, found by a profile row: the compressed file, its decompressed SARC, the member and its scale words"""
    def __init__(self, root, row, z=None, dict_root=None):   # dict_root: where Pack/ZsDic.pack.zs is, when not in root (an overlay folder read back)
        self.z = z or nup.zstd()
        self.path, self.part, self.expected = row
        src = os.path.join(root, self.path)
        if not romfs.exists(src): raise Error(f'{src}: missing (the profile\'s scene_scale_palette names it)')
        self.raw = romfs.open(src, 'rb').read()
        self.head = frame_header(self.raw)
        did = self.head['dict_id']
        if did:
            dicts = dictionaries(dict_root or root, self.z)
            if did not in dicts: raise Error(f'{self.path}: its frame needs zstd dictionary {did}, which {ZSDIC} does not have')
            self.zdict = self.z.ZstdDict(dicts[did])
        else: self.zdict = None
        self.sarc = self.z.decompress(self.raw, zstd_dict=self.zdict) if self.zdict else self.z.decompress(self.raw)
        if self.head['content_size'] is not None and self.head['content_size'] != len(self.sarc):
            raise Error(f'{self.path}: the frame says {self.head["content_size"]} bytes, it gives {len(self.sarc)}')
        hits = [m for m in sarc_members(self.sarc, self.path) if self.part in m[0]]
        if len(hits) != 1: raise Error(f'{self.path}: {len(hits)} members are named *{self.part}*, the profile needs exactly one: {", ".join(h[0] for h in hits) or "none"}')
        self.member, self.start, self.end = hits[0]
        self.byml = Byml(self.sarc[self.start:self.end], f'{self.path}/{self.member}')
        self.words = self.byml.floats(PATH_KEYS)   # (offset in the member, value)
        if not self.words: raise Error(f'{self.path}/{self.member}: no {"/".join(k or "*" for k in PATH_KEYS)}')

    def scales(self): return [v for _, v in self.words]

    def matches(self):
        """Whether the scales are the profile's expected list, word for word (as 32-bit floats)"""
        return len(self.words) == len(self.expected) and all(f32_bits(v) == f32_bits(e) for (_, v), e in zip(self.words, self.expected))

    def rewrite(self, scale, mode):
        """→ (the new compressed pack, the new scales). Checks the new frame: the same size and flags, decompresses to the original but for the rewritten words"""
        if mode not in MODES: raise Error(f'mode {mode}: {" or ".join(MODES)}')
        new = bytearray(self.sarc); out = []
        for at, v in self.words:
            w = f32(scale) if mode == 'fixed' or v < f32(scale) else v
            struct.pack_into('<f', new, self.start + at, w); out.append(w)
        z = self.z; P = z.CompressionParameter
        opts = {P.compression_level: LEVEL, P.content_size_flag: int(self.head['content_size'] is not None), P.checksum_flag: self.head['checksum'],
                P.dict_id_flag: int(bool(self.head['dict_id']))}
        packed = z.compress(bytes(new), options=opts, zstd_dict=self.zdict) if self.zdict else z.compress(bytes(new), options=opts)
        self.check(packed, bytes(new))
        return packed, out

    def check(self, packed, new):
        h = frame_header(packed)
        for k in ('dict_id', 'checksum'):
            if h[k] != self.head[k]: raise Error(f'{self.path}: the new frame\'s {k} is {h[k]}, the original\'s {self.head[k]}')
        if (h['content_size'] is None) != (self.head['content_size'] is None) or (h['content_size'] is not None and h['content_size'] != len(self.sarc)):
            raise Error(f'{self.path}: the new frame records content size {h["content_size"]}, the original {self.head["content_size"]}')
        d = self.z.decompress(packed, zstd_dict=self.zdict) if self.zdict else self.z.decompress(packed)
        if len(d) != len(self.sarc): raise Error(f'{self.path}: the new pack decompresses to {len(d)} bytes, the original to {len(self.sarc)}')
        if d != new: raise Error(f'{self.path}: the new pack does not decompress to the rewritten bytes')
        back = bytearray(d)   # the scale words put back: the rest must be the original byte for byte
        for at, _ in self.words: back[self.start + at:self.start + at + 4] = self.sarc[self.start + at:self.start + at + 4]
        if back != self.sarc:
            first = next(i for i in range(len(back)) if back[i] != self.sarc[i])
            raise Error(f'{self.path}: the new pack differs outside the scale words, first at {first:#x}')
        m = sarc_members(d, self.path)
        if [x[0] for x in m] != [x[0] for x in sarc_members(self.sarc, self.path)] or any(x[1:] != y[1:] for x, y in zip(m, sarc_members(self.sarc, self.path))):
            raise Error(f'{self.path}: the new SARC\'s members moved')


def find(root, g, z=None):
    """The palette of the profile's rows that this RomFS matches; Error naming what was found when none does (another build of the game)"""
    rows = palette_rows(g)
    if not rows: raise Error(f'{g.id}: the profile has no scene_scale_palette row')
    seen = []
    for row in rows:
        try: p = guarded(row[0], Palette, root, row, z)
        except Error as e: seen.append(str(e)); continue
        if p.matches(): return p
        seen.append(f'{p.path}/{p.member}: scales {",".join(f"{v:.6g}" for v in p.scales())}, the profile expects {",".join(f"{v:.6g}" for v in p.expected)}')
    raise Error('no scene_scale_palette row of the profile matches this RomFS (another build?): ' + '; '.join(seen))


def write_atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.partial')
    with open(tmp, 'wb') as f: f.write(data)
    os.replace(tmp, path)


def make(root, out, g, chosen=None, z=None):
    """Overlays of the presets (default: every scene_scale row of the profile) into <out>/<name>/<pack path>, then <out>/scene_res.tsv (last: a folder without it is
    unfinished). → [(name, scale, mode, label, scales)]. Error when the palette is not the profile's or a check fails; nothing is half written over a good overlay"""
    p = find(root, g, z)
    ps = chosen if chosen is not None else presets(g)
    if not ps: raise Error(f'{g.id}: the profile has no scene_scale row and no res_preset row with a floor or fixed scene')
    out = Path(out); (out / MANIFEST).unlink(missing_ok=True)
    made = []
    for name, scale, mode, label in ps:
        packed, scales = guarded(f'{p.path} ({name})', p.rewrite, scale, mode)
        write_atomic(out / name / p.path, packed)
        made.append((name, scale, mode, label, scales))
        print(f'  {name}: {mode} {scale:g} → {",".join(f"{v:.6g}" for v in scales)} ({len(packed)} bytes, the original {len(p.raw)})', flush=True)
    rows = [f'source\t{p.path}\t{p.member}\t{len(p.raw)}\t{hashlib.sha256(p.raw).hexdigest()}\n', f'original\t{",".join(f"{v:.6g}" for v in p.scales())}\n']
    rows += [f'preset\t{n}\t{s:g}\t{m}\t{",".join(f"{v:.6g}" for v in sc)}\t{lb}\n' for n, s, m, lb, sc in made]
    write_atomic(out / MANIFEST, ''.join(rows).encode())
    return made


def main():
    ap = argparse.ArgumentParser(description='Scene resolution overlays: the game\'s dynamic resolution palette rewritten (a RomFS overlay folder per preset)')
    ap.add_argument('romfs', nargs='?', help='the RomFS: a folder, a RomFS image, or <file>.nup:update/<version>/romfs')
    ap.add_argument('out', nargs='?', help='the folder the overlays go into (one folder per preset)')
    ap.add_argument('--game', help='the profile (default: DM_GAME)')
    ap.add_argument('--show', action='store_true', help='print the palette\'s scales and stop')
    ap.add_argument('--presets', action='store_true', help='print the profile\'s Resolution presets (res_preset rows) and stop; no RomFS needed')
    ap.add_argument('--scale', type=float, help='one overlay of this scale instead of the profile\'s presets')
    ap.add_argument('--mode', choices=MODES, default='fixed', help='with --scale: fixed (every level) or floor (levels below it raised to it)')
    ap.add_argument('--name', default='custom', help='with --scale: the overlay\'s folder name')
    a = ap.parse_args()
    import game
    try:
        try: g = game.load(a.game)
        except SystemExit as e: raise Error(str(e)) from None
        if a.presets:
            outs = outputs(g)
            for pid, win, mode, h, scale, label in res_presets(g):
                o = outs.get(win)
                scene = 'the game\'s own palette' if scale is None else f'{mode} {h} lines: scale {scale:.6g} of {o[0]}x{o[1]} ({"measured" if o[2] else "open"}), about {round(o[0] * scale)}x{h}'
                print(f'{pid}\t{label}\twindow {win}\t{scene}')
            return
        if not a.romfs: raise Error('no RomFS (or --presets)')
        if a.show:
            p = find(a.romfs, g)
            print(f'{p.path}/{p.member}: {len(p.words)} scales {",".join(f"{v:.6g}" for v in p.scales())} (the profile\'s); frame dictionary {p.head["dict_id"]}, '
                  f'{len(p.raw)} bytes, {len(p.sarc)} decompressed')
            return
        if not a.out: raise Error('no output folder (or --show)')
        if a.scale is not None and not 0.05 <= a.scale <= 1.0: raise Error(f'--scale {a.scale}: outside 0.05..1')
        chosen = [(a.name, a.scale, a.mode, a.name)] if a.scale is not None else None
        made = make(a.romfs, a.out, g, chosen)
        print(f'done: {len(made)} overlays in {a.out}')
    except (Error, OSError, nup.Error) as e: sys.exit(f'[RED] {e}')


if __name__ == '__main__':
    main()
