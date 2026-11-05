#!/usr/bin/env python3
# The Android shader library's delta update (android/SHADERS.md#delta-updates): after a tools change (a new DMSH_VK_CHAIN), a phone keeps every shader whose library entry is
# the same in the new version and translates only the rest. Three parts, all in this importable module (vk_android_build.py is a script; the tests and the app's checks use this):
#   - the entry hash: one shader's library entry as the library stores it (the 64-byte entry with its positions in the file zeroed, its binding records, its c1 bytes, and each
#     module's variant, SPIR-V size and SPIR-V words), without the header's version fields and without the modules' codec or stored bytes, so a zstd of another version
#     (the phone's) gives the same hash;
#   - the manifest (dm/games/<id>/vk_delta.tsv, in the APK's tools): a full Mac build's hash of every shader key, hashes only (no game bytes), checked by manifest_problems
#     before it goes into the profile;
#   - the ABI digest (DMSH_VK_ABI): what the replay reads of a library and its modules (dmsh_abi_vk.h and dmsh_abi.h without comments). A library whose header carries
#     the host's ABI digest can be played while its update runs, whatever its versions (vka_shlib_check).
# This file is one of DMSH_VK_CHAIN's files (vk_android_build.py VK_FILES): it decides which old entries a build reuses.
import concurrent.futures as cf, hashlib, mmap, os, re, struct

HERE = os.path.dirname(os.path.abspath(__file__)); GFX = os.path.dirname(HERE)
ABI_VK = os.path.join(GFX, 'nvn', 'dmsh_abi_vk.h'); ABI = os.path.join(GFX, 'nvn', 'dmsh_abi.h')
_DEFINE = re.compile(r'#define DMSH_VK_([A-Z][A-Z0-9_]*)[ \t]+(0x[0-9A-Fa-f]+|[1-9][0-9]*|0|"[^"\\]*")(?:[ \t]+//.*)?')


def vk_values(path=ABI_VK):
    """{name without DMSH_VK_: int or str} of dmsh_abi_vk.h's #define lines (its structs are for C)"""
    out = {}
    with open(path) as f: lines = f.read().splitlines()
    for line in lines:
        s = line.strip()
        if not s.startswith('#define DMSH_VK_'): continue
        m = _DEFINE.fullmatch(s)
        if not m: raise ValueError(f'{path}: not a definition this tool can read: {s}')
        out[m.group(1)] = m.group(2)[1:-1] if m.group(2).startswith('"') else int(m.group(2), 0)
    return out


VK = vk_values()
HEAD = struct.Struct('<II16s16s16s16sIIIIIIQQHH12s')   # dmsh_vk_header; the last 12 bytes: abi (DMSH_VK_ABI, 12 characters, no NUL; zero in a library made before it)
ENTRY = struct.Struct('<QQBBHIIHH3HBBIIII8x')            # dmsh_vk_entry; the last 8 bytes: c1_off_hi (a position, below) and reserved
MOD = struct.Struct('<IIIBBH')                            # dmsh_vk_module; the last field: off_hi, bits 32-47 of the offset
POS = (20, 24, 44, 56)   # the entry's positions in its file: first module, first binding, c1 offset and its high 32 bits (zeroed in the hash and in a reused entry, set again by write_library)


def mod_off(off, off_hi): return off_hi << 32 | off   # a module record's offset in the blob (dmsh_abi_vk.h: off_hi is 0 unless the blob passes 4 GiB)
def c1_off(entry): return struct.unpack_from('<I', entry, 56)[0] << 32 | struct.unpack_from('<I', entry, 44)[0]   # an entry's c1 offset in the blob
assert HEAD.size == VK['HEADER_SIZE'] and ENTRY.size == VK['ENTRY_SIZE'] and MOD.size == VK['MOD_SIZE']
HASH_LEN = 16   # hex digits of an entry hash in the manifest: 64 bits, against at most tens of thousands of entries a game


def field(b): return bytes(b).rstrip(b'\0').decode('ascii', 'replace')


def abi_digest(paths=(ABI_VK, ABI)):
    """DMSH_VK_ABI: the hash of what the replay reads of a library and of the modules in it, the two ABI headers with their comments and blank space taken out (a comment
    or a reflowed line does not change it; a number, a struct field or DMSH_VK_ABI_REV does). 12 hex digits, the library header's abi field"""
    h = hashlib.sha256(b'dmsh_vk_abi\0')
    for p in paths:
        with open(p) as f: t = re.sub(r'/\*.*?\*/', ' ', f.read(), flags=re.S)
        t = re.sub(r'//[^\n]*', '', t)
        h.update(os.path.basename(p).encode() + b'\0' + ' '.join(t.split()).encode() + b'\0')
    return h.hexdigest()[:12]


def zeroed(entry):
    """An entry's 64 bytes with its positions in the file zeroed"""
    e = bytearray(entry)
    for o in POS: struct.pack_into('<I', e, o, 0)
    return bytes(e)


def entry_hash(entry, binds, c1, modules):
    """One shader's library entry → its manifest hash. entry: the 64 bytes (positions zeroed here), binds: its binding records, c1: its c1 bytes, modules: [(variant, SPIR-V)]
    in the library's order"""
    h = hashlib.sha256(b'DMVKENT1')
    h.update(zeroed(entry)); h.update(struct.pack('<I', len(binds))); h.update(binds); h.update(struct.pack('<I', len(c1))); h.update(c1)
    for variant, spirv in modules: h.update(struct.pack('<II', variant, len(spirv))); h.update(spirv)
    return h.hexdigest()[:HASH_LEN]


def spirv(stored, codec, zstd):
    if codec == VK['CODEC_ZSTD']:
        if zstd is None: raise ValueError('a zstd module and no zstd in this Python')
        try: return zstd.decompress(bytes(stored))
        except Exception as e: raise ValueError(f'zstd: {e}') from e   # a damaged frame (ZstdError is no ValueError): the entry is not reused
    if codec != VK['CODEC_RAW']: raise ValueError(f'codec {codec}')
    return bytes(stored)


class Library:
    """A dmsh_vk.lib read as the replay reads it (mapped; every range checked when an entry is taken). .version, .chain, .game, .inputs, .abi from the header; .index
    {key: entry number}; entry(i) → (key, entry bytes, binding records, c1 bytes, [(variant, size, stored bytes, codec)])"""
    def __init__(self, path):
        self.path = path
        with open(path, 'rb') as f:
            self.size = os.fstat(f.fileno()).st_size
            if self.size < HEAD.size: raise ValueError(f'{path}: shorter than the header')
            self.m = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        (magic, version, chain, vkc, game, inputs, self.ne, self.eo, self.nb, self.bo, self.nm, self.mo, self.blob_off, self.blob_size, self.sg, self.sa,
         abi) = HEAD.unpack_from(self.m, 0)
        if magic != VK['LIB_MAGIC'] or version != VK['LIB_VERSION']: raise ValueError(f'{path}: magic {magic:#x} version {version}, not a library this tool reads')
        if self.eo + self.ne * ENTRY.size > self.size or self.bo + self.nb * VK['BIND_SIZE'] > self.size or self.mo + self.nm * MOD.size > self.size \
                or self.blob_off + self.blob_size > self.size: raise ValueError(f'{path}: a table out of the file')
        self.chain, self.version, self.game, self.inputs = field(chain), field(vkc), field(game), field(inputs)
        self.abi = field(abi) if abi.strip(b'\0') and all(48 <= c < 58 or 97 <= c < 103 for c in abi) else ''
        self.index = {f'{struct.unpack_from("<Q", self.m, self.eo + ENTRY.size * i)[0]:016x}': i for i in range(self.ne)}

    def entry(self, i):
        o = self.eo + ENTRY.size * i
        e = bytes(self.m[o:o + ENTRY.size])
        key, _, _, mc, _, m0, b0, bc = struct.unpack_from('<QQBBHIIH', e)
        c1o, c1_len = c1_off(e), struct.unpack_from('<I', e, 48)[0]
        if m0 + mc > self.nm or b0 + bc > self.nb or c1o + c1_len > self.blob_size: raise ValueError(f'{self.path}: entry {key:016x} out of range')
        binds = bytes(self.m[self.bo + VK['BIND_SIZE'] * b0:self.bo + VK['BIND_SIZE'] * (b0 + bc)])
        c1 = bytes(self.m[self.blob_off + c1o:self.blob_off + c1o + c1_len])
        mods = []
        for j in range(m0, m0 + mc):
            off, size, stored, variant, codec, off_hi = MOD.unpack_from(self.m, self.mo + MOD.size * j)
            off = mod_off(off, off_hi)
            if off + stored > self.blob_size: raise ValueError(f'{self.path}: entry {key:016x}: a module out of the blob')
            mods.append((variant, size, self.m[self.blob_off + off:self.blob_off + off + stored], codec))
        return f'{key:016x}', e, binds, c1, mods

    def hash_of(self, i, zstd):
        """(key, entry hash, the entry as entry() gives it)"""
        k, e, b, c1, mods = self.entry(i)
        sp = [(v, spirv(st, codec, zstd)) for v, size, st, codec in mods]
        for (v, size, _, _), (_, s) in zip(mods, sp):
            if len(s) != size: raise ValueError(f'{self.path}: entry {k}: module {v} is {len(s)} bytes, its record says {size}')
        return k, entry_hash(e, b, c1, sp), (k, e, b, c1, mods)

    def close(self): self.m.close()


def library_hashes(path, zstd, nj=8):
    """(Library, {key: entry hash}) of every entry of a library file"""
    L = Library(path)
    with cf.ThreadPoolExecutor(nj) as ex: out = {k: h for k, h, _ in ex.map(lambda i: L.hash_of(i, zstd), range(L.ne))}   # zstd and hashlib drop the GIL
    return L, out


# ---- the manifest: dm/games/<id>/vk_delta.tsv
MANIFEST = 'vk_delta.tsv'
_HEAD_TEXT = ('# The Android shader library delta manifest (dm/gfx/tools/vk_android_delta.py, android/SHADERS.md#delta-updates): the hash of every shader\'s library entry in\n'
              '# the version below, from a full Mac build; "-" for a shader that did not build. Hashes only. "from" rows: shaders a phone with that older library translates\n')
_ROW = {'version': re.compile(r'[0-9a-f]{12}'), 'game': re.compile(r'[0-9A-F]{16}'), 'inputs': re.compile(r'[0-9a-f]{12}')}
_KEY_ROW = re.compile(r'([0-9a-f]{16})\t([0-9a-f]{%d}|-)' % HASH_LEN)
_FROM_ROW = re.compile(r'from\t([0-9a-f]{12})\t(\d+)')
# "stamp" rows: an older library that is this version's byte for byte but for the header's version and abi fields (STAMP_FIELDS), by the SHA-256 of the whole older file
# and of the whole file this version's build wrote. A phone with that older library writes this version's by rewriting those fields (ShaderLibs.restamp: no shader
# translated, no split) and takes it only when the result's SHA-256 is the second one. A later version whose shaders change has no such row: phones update as before
_STAMP_ROW = re.compile(r'stamp\t([0-9a-f]{12})\t([0-9a-f]{64})\t([0-9a-f]{64})')
STAMP_FIELDS = ((24, 16), (116, 12))   # dmsh_vk_header: vk_chain (the library's version, NUL padded), abi (12 hex digits; zero before the field existed)


def stamp_only(old, new, chunk=1 << 22):
    """Whether two library files differ only inside STAMP_FIELDS (same size, every other byte equal)"""
    if os.path.getsize(old) != os.path.getsize(new): return False
    with open(old, 'rb') as a, open(new, 'rb') as b:
        ha, hb = bytearray(a.read(128)), bytearray(b.read(128))
        for off, n in STAMP_FIELDS: ha[off:off + n] = hb[off:off + n] = bytes(n)
        if ha != hb: return False
        while True:
            x, y = a.read(chunk), b.read(chunk)
            if x != y: return False
            if not x: return True


def restamped_header(head, version, abi):
    """The first 128 bytes of a library with its version and abi fields set, as ShaderLibs.restamp writes them"""
    h = bytearray(head[:128])
    (vo, vn), (ao, an) = STAMP_FIELDS
    h[vo:vo + vn] = version.encode('ascii').ljust(vn, b'\0')
    h[ao:ao + an] = abi.encode('ascii').ljust(an, b'\0')
    return bytes(h)


def sha256_file(path, chunk=1 << 22):
    d = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(chunk), b''): d.update(b)
    return d.hexdigest()


def format_manifest(version, game, inputs, hashes, froms=(), stamps=()):
    """The manifest's text: hashes {key: hash or '-'}, froms [(older version, shaders to translate from it)], stamps [(older version, its file's SHA-256, this
    version's file's SHA-256)]"""
    out = [_HEAD_TEXT, f'version\t{version}\n', f'game\t{game}\n', f'inputs\t{inputs}\n']
    out += [f'from\t{v}\t{n}\n' for v, n in sorted(froms)]
    out += [f'stamp\t{v}\t{o}\t{n}\n' for v, o, n in sorted(stamps)]
    out += [f'{k}\t{hashes[k]}\n' for k in sorted(hashes)]
    text = ''.join(out)
    bad = manifest_problems(text)
    if bad: raise ValueError('manifest: ' + '; '.join(bad[:3]))
    return text


def manifest_problems(text):
    """The leak check before a manifest goes into a profile (and the APK): every line is the comment above, a version / game / inputs / from row or a key and a hash, all
    hex in fixed widths, so no path, name or other text can ride along. → [problems]"""
    bad, seen = [], set()
    lines = text.split('\n')
    if lines and lines[-1] == '': lines.pop()
    head = _HEAD_TEXT.rstrip('\n').split('\n')
    if lines[:len(head)] != head: bad.append('the comment at the top is not the one format_manifest writes')
    for n, l in enumerate(lines[len(head):], len(head) + 1):
        c = l.split('\t')
        if c[0] in _ROW:
            if len(c) != 2 or not _ROW[c[0]].fullmatch(c[1]): bad.append(f'line {n}: a {c[0]} row that is not one value of its form')
            elif c[0] in seen: bad.append(f'line {n}: {c[0]} twice')
            seen.add(c[0])
        elif not (_KEY_ROW.fullmatch(l) or _FROM_ROW.fullmatch(l) or _STAMP_ROW.fullmatch(l)): bad.append(f'line {n}: not a key and a hash ({len(l)} characters)')
    for r in _ROW:
        if r not in seen: bad.append(f'no {r} row')
    return bad


def parse_manifest(text):
    """→ {'version', 'game', 'inputs', 'from': {version: count}, 'stamp': {version: (old SHA-256, new SHA-256)}, 'hashes': {key: hash or '-'}}; ValueError when
    manifest_problems finds any"""
    bad = manifest_problems(text)
    if bad: raise ValueError('; '.join(bad[:3]))
    out = {'from': {}, 'stamp': {}, 'hashes': {}}
    for l in text.split('\n'):
        c = l.split('\t')
        if c[0] in _ROW: out[c[0]] = c[1]
        elif c[0] == 'from': out['from'][c[1]] = int(c[2])
        elif c[0] == 'stamp' and len(c) == 4: out['stamp'][c[1]] = (c[2], c[3])
        elif len(c) == 2 and _KEY_ROW.fullmatch(l): out['hashes'][c[0]] = c[1]
    return out


def to_translate(old, new):
    """The keys of the new manifest's hashes a library with the old hashes does not have as they are ({key: hash} each): what a delta build translates"""
    return sorted(k for k, h in new.items() if h == '-' or old.get(k) != h)


def not_built_there(manifest, keys):
    """The keys of a build's failures that the manifest (a full Mac build of this version, or None) has as "-": shaders that did not build there either. A build that
    must hold every shader (DMSH_VK_REQUIRE_ALL, the phone's job) must hold every shader the Mac's build of the same version holds; these do not count against it. → set"""
    if not manifest: return set()
    return {k for k in keys if manifest['hashes'].get(k) == '-'}


def entry_as_manifest(want, got):
    """Whether a library entry's hash (got; None when the library has no entry for the key) is what the manifest has (want: a hash, "-" for a shader that did not build
    in the Mac's build, None when the manifest does not have the key)"""
    return want == got or got is None and want in (None, '-')
