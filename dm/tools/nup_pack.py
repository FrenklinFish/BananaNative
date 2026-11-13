#!/usr/bin/env python3
# A small clean-room NUP writer (github.com/a55uka/nup SPEC.md, format 1.0-draft), for test fixtures: a folder dump in the layout
# scripts/pack_dump.py reads (base/, updates/<version>/, dlc/<title id>/<version>/, the console's or the flattened one) becomes one .nup.
# Not a tool for users: they make their .nup files with the format's reference implementation (github.com/a55uka/nup
# tools/nup). Written from the spec alone, so the host's and dm/tools/nup.py's readers are checked against
# a second implementation, not against themselves.
#
#   nup_pack.py <dump folder> <out.nup> [--level N] [--raw] [--only base,update,addon]
#
# --raw stores every file raw (codec 0); otherwise each file is one zstd frame with its content size (codec 1) unless that does not
# shrink it. Equal files (same SHA-256) share one extent. Needs the zstandard module, compression.zstd (Python 3.14) or
# backports.zstd unless --raw.
import argparse, hashlib, os, re, struct, sys
from pathlib import Path
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'scripts'))
import pack_dump   # the dump layout (package_root, dlc_romfs): one reading of it for the packer and this writer

KIND = {'base': 0, 'update': 1, 'addon': 2}
NONE = 0xFFFFFFFF
CHUNK = 8 << 20


class Dir:
    def __init__(self, name): self.name, self.kids, self.id, self.first = name, [], -1, 0


class File:
    def __init__(self, name, path, size): self.name, self.path, self.size, self.id = name, path, size, -1


def tree(name, path):
    d = Dir(name)
    with os.scandir(path) as it:
        for e in it:
            if e.name == '.listing': continue
            n = e.name.encode()
            if e.is_dir(): d.kids.append(tree(n, e.path))
            elif e.is_file(): d.kids.append(File(n, e.path, e.stat().st_size))
    d.kids.sort(key=lambda k: k.name)
    return d


def npdm_program_id(path):
    try: data = open(path, 'rb').read()
    except OSError: return 0
    off = data.find(b'ACI0')
    return struct.unpack_from('<Q', data, off + 0x10)[0] if 0 <= off and off + 0x18 <= len(data) else 0


def discover(src, only):
    pk = []   # (kind, title id, version, flags, root Dir)
    def content(p):
        root = Dir(b'')
        for sub in ('exefs', 'romfs'):
            if os.path.isdir(os.path.join(p, sub)): root.kids.append(tree(sub.encode(), os.path.join(p, sub)))
        return root
    # the packages where scripts/pack_dump.py finds them, in either layout (the console's: <version folder>/program/0/, or the flattened one)
    root = lambda p: str(pack_dump.package_root(Path(p)))
    base = root(os.path.join(src, 'base'))
    base_tid = npdm_program_id(os.path.join(base, 'exefs', 'main.npdm'))
    has_base = os.path.isdir(os.path.join(src, 'base'))
    if has_base and 'base' in only: pk.append((0, base_tid, 0, 0, content(base)))
    up = os.path.join(src, 'updates')
    if os.path.isdir(up) and 'update' in only:
        for v in sorted((v for v in os.listdir(up) if re.fullmatch(r'\d+', v)), key=int):
            r = root(os.path.join(up, v))
            tid = npdm_program_id(os.path.join(r, 'exefs', 'main.npdm')) or base_tid
            pk.append((1, tid, int(v), 0 if has_base else 1, content(r)))
    dl = os.path.join(src, 'dlc')
    if os.path.isdir(dl) and 'addon' in only:
        for t in sorted(t for t in os.listdir(dl) if re.fullmatch(r'[0-9a-fA-F]{16}', t)):
            try: rom = pack_dump.dlc_romfs(Path(dl, t))   # the add-on's RomFS (its newest data patch's in the console layout)
            except pack_dump.Error as e: sys.exit(str(e))
            pk.append((2, int(t, 16), 0, 0, content(str(rom.parent))))
    if not pk: sys.exit(f'{src}: no base/, updates/<version>/ or dlc/<title id>/<n>/ to pack')
    return pk


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('src'); ap.add_argument('out')
    ap.add_argument('--level', type=int, default=3); ap.add_argument('--raw', action='store_true')
    ap.add_argument('--only', default='base,update,addon')
    a = ap.parse_args()
    if os.path.exists(a.out): sys.exit(f'{a.out} exists')
    zc = None
    if not a.raw:
        try:
            import zstandard
            zc = zstandard.ZstdCompressor(level=a.level, write_content_size=True, threads=-1)
        except ImportError:   # compression.zstd (Python 3.14) or backports.zstd: the frame's content size from the pledged size
            try: from compression import zstd
            except ImportError: from backports import zstd
            class Std:
                @staticmethod
                def copy_stream(src, out, size):
                    c = zstd.ZstdCompressor(level=a.level); c.set_pledged_input_size(size); n = 0
                    while b := src.read(CHUNK): o = c.compress(b); out.write(o); n += len(o)
                    o = c.flush(); out.write(o)
                    return size, n + len(o)
            zc = Std()
    pkgs = discover(a.src, set(a.only.split(',')))

    # Node ids: every directory first (breadth first, so each directory's children are one contiguous run of slots), then every file
    dirs, files, order = [], [], []
    for p in pkgs: order.append(p[4])
    queue = list(order)
    for d in queue: d.id = len(dirs); dirs.append(d)
    i = 0
    while i < len(dirs):
        for k in dirs[i].kids:
            if isinstance(k, Dir): k.id = len(dirs); dirs.append(k)
        i += 1
    for d in dirs:
        for k in d.kids:
            if isinstance(k, File): k.id = len(files); files.append(k)
    children = []
    for d in dirs:
        d.first = len(children)
        children += [k.id if isinstance(k, Dir) else len(dirs) + k.id for k in d.kids]
    pool, where = bytearray(), {}
    def intern(s):
        if s not in where: where[s] = len(pool); pool.extend(s)
        return where[s]
    for d in dirs: intern(d.name)
    for f in files: intern(f.name)

    toc_hdr = 0x30
    off_pk = toc_hdr; off_dir = off_pk + 0x40 * len(pkgs); off_file = off_dir + 0x18 * len(dirs)
    off_kids = off_file + 0x50 * len(files); off_pool = off_kids + 4 * len(children)
    toc_size = off_pool + len(pool)
    blob = (0x80 + toc_size + 15) & ~15

    ext = {}   # file id -> (offset, stored size, codec, sha256)
    seen = {}
    total = sum(f.size for f in files)
    with open(a.out, 'w+b') as out:
        pos = blob; out.seek(pos); done = 0
        for f in files:
            if not f.size: ext[f.id] = (0, 0, 0, hashlib.sha256().digest()); continue
            start = pos; h = hashlib.sha256()
            out.seek(start); out.truncate(start)
            codec = 0
            if zc:
                class Hashing:
                    def __init__(s, fh): s.fh = fh
                    def read(s, n=-1):
                        b = s.fh.read(n); h.update(b); return b
                with open(f.path, 'rb') as src:
                    _, stored = zc.copy_stream(Hashing(src), out, size=f.size)
                codec = 1
                if stored >= f.size:   # does not shrink: store it raw
                    out.seek(start); out.truncate(start); stored = 0; h = hashlib.sha256(); codec = 0
            if codec == 0:
                with open(f.path, 'rb') as src:
                    while b := src.read(CHUNK): out.write(b); h.update(b)
                stored = f.size
            sha = h.digest()
            if sha in seen and seen[sha][1] == f.size:   # an equal file already has an extent: share it, drop this copy
                out.seek(start); out.truncate(start); o, _, st, cd = seen[sha]; ext[f.id] = (o, st, cd, sha)
            else:
                ext[f.id] = (start, stored, codec, sha); seen[sha] = (start, f.size, stored, codec)
                pos = (start + stored + 15) & ~15
            done += f.size
            if f.id % 2000 == 0: print(f'{done / 2**30:.1f} / {total / 2**30:.1f} GiB, {pos / 2**30:.1f} GiB written', flush=True)
        out.seek(0, 2); end = out.tell()
        if end < pos: out.write(b'\0' * (pos - end))   # the last extent's alignment padding is not needed; keep the file ending at the last byte
        out.truncate(max(blob, max((o + s for o, s, _, _ in ext.values()), default=blob)))
        file_size = out.seek(0, 2)

        toc = bytearray(toc_size)
        struct.pack_into('<4sIQQQQQ', toc, 0, b'TOC\x01', len(pkgs), len(dirs), len(files), off_pool, len(pool), 0)
        for i, (kind, tid, ver, flags, root) in enumerate(pkgs):
            ids = {k.name: k.id for k in root.kids if isinstance(k, Dir)}
            struct.pack_into('<QIIIIBBHIQI20s', toc, off_pk + 0x40 * i, tid, root.id, ids.get(b'exefs', NONE), ids.get(b'romfs', NONE),
                             ver, kind, flags, 0, 0, 0, 0, b'')
        for d in dirs: struct.pack_into('<IIIIQ', toc, off_dir + 0x18 * d.id, where[d.name], len(d.name), d.first, len(d.kids), 0)
        for f in files:
            o, st, cd, sha = ext[f.id]
            struct.pack_into('<IIQQQ32sBBH12s', toc, off_file + 0x50 * f.id, where[f.name], len(f.name), f.size, o, st, sha, cd, 0, 0, b'')
        struct.pack_into(f'<{len(children)}I', toc, off_kids, *children)
        toc[off_pool:] = pool
        u = hashlib.sha256(b''.join(struct.pack('<QIB', tid, ver, kind) for kind, tid, ver, _, _ in pkgs)).digest()[:16]
        sb = struct.pack('<4sHHQQ32sQQII16s32s', b'NUP\x01', 0x80, 1, 0x80, toc_size, hashlib.sha256(toc).digest(), blob, file_size,
                         len(pkgs), 0, u, b'')
        assert len(sb) == 0x80
        out.seek(0); out.write(sb); out.write(toc)
    raw = sum(1 for f in files if f.size and ext[f.id][2] == 0)
    print(f'{a.out}: {len(pkgs)} packages, {len(dirs)} directories, {len(files)} files ({raw} raw), {file_size / 2**30:.2f} GiB '
          f'(the folders hold {total / 2**30:.2f} GiB)')


if __name__ == '__main__':
    main()
