#!/usr/bin/env python3
"""Prewarm lists of the Vulkan replay (dm/gfx/vk_android/vka_pwlist.h): the pipeline keys a run made, which the next start makes ahead on background threads.

A profile's seed list, dm/games/<id>/vk_prewarm.bin, is compiled into the Android host (dm/games/game.py seed) and gives a first start the keys of our own runs, so the
pipelines of the game's first scenes are made while it plays its logos and menus instead of all at once at its first 3D frame. It is made from the <cache>.keys files
that phone runs of the current host write beside their pipeline cache (DM_VK_PCACHE=<file> writes <file>.keys), one run per driver (the system driver and Turnip may
take different vertex input paths, so their keys differ), the earliest-needed keys first.

Usage:
  vk_prewarm.py show <list>...                      counts by kind, the header's key layout
  vk_prewarm.py merge -o <out> <list>...            the records of every list, each once, in the order of the first list, then the keys the next ones add
  vk_prewarm.py seed <id> <list>...                 merge into the profile's dm/games/<id>/vk_prewarm.bin (replaced), then show it
A list of another key layout than the first is refused (exit 1): every list must come from the same host build. After a change to vka_gkey (vka_gkey.h) the profiles'
seeds no longer load (the host logs a [RED] line; dm/host/tests t_android_pwlist fails): make them again from runs of the new host.
"""
import struct
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MAGIC1, MAGIC2 = b'NXVKPW1\0', b'NXVKPW2\0'
HDR1, HDR2 = struct.Struct('<8sII'), struct.Struct('<8sIIII')   # pwhdr, pwhdr2
REC = struct.Struct('<BBBBI5Q')                                  # pwrec: kind, bad, inst[2], klen, key[5]
KINDS = {0: 'vertex', 1: 'compute', 2: 'mesh'}


class Error(Exception):
    pass


def parse(data, name='list'):
    """(gkey_size, layout, [(record bytes with bad zeroed, kind)]) of a list's bytes"""
    if data[:8] == MAGIC2 and len(data) >= HDR2.size:
        _, gsz, n, layout, _ = HDR2.unpack_from(data); o = HDR2.size
    elif data[:8] == MAGIC1 and len(data) >= HDR1.size:
        _, gsz, n = HDR1.unpack_from(data); layout = None; o = HDR1.size
    else:
        raise Error(f'{name}: not a prewarm list (NXVKPW1 / NXVKPW2)')
    recs = []
    while len(recs) < n and o + REC.size <= len(data):
        kind, bad, i0, i1, klen, *keys = REC.unpack_from(data, o)
        size = REC.size + ((klen + 7) & ~7)
        if klen > gsz or o + size > len(data):
            raise Error(f'{name}: record {len(recs)} runs past the file')
        rec = bytearray(data[o:o + size]); rec[1] = 0
        if not bad:
            recs.append((bytes(rec), kind))
        o += size
    if len(recs) < n and o + REC.size <= len(data):
        raise Error(f'{name}: {n} records announced, {len(recs)} read')
    return gsz, layout, recs


def merge(lists):
    """[(name, bytes)] → one list's bytes (NXVKPW2): every record once, in first-seen order"""
    if not lists:
        raise Error('no list given')
    gsz0 = layout0 = None; seen = set(); out = []
    for name, data in lists:
        gsz, layout, recs = parse(data, name)
        if layout is None:
            raise Error(f'{name}: an NXVKPW1 list has no key layout fingerprint: take a list written by a current host')
        if gsz0 is None:
            gsz0, layout0 = gsz, layout
        elif (gsz, layout) != (gsz0, layout0):
            raise Error(f'{name}: key layout {layout:08x} (size {gsz}) is not the first list\'s {layout0:08x} (size {gsz0}): lists of different host builds')
        for rec, _ in recs:
            if rec not in seen:
                seen.add(rec); out.append(rec)
    return HDR2.pack(MAGIC2, gsz0, len(out), layout0, 0) + b''.join(out)


def show(name, data):
    gsz, layout, recs = parse(data, name)
    by = {}
    for _, k in recs:
        by[KINDS.get(k, f'kind {k}')] = by.get(KINDS.get(k, f'kind {k}'), 0) + 1
    lay = f'{layout:08x}' if layout is not None else 'none (NXVKPW1)'
    return f'{name}: {len(recs)} keys ({", ".join(f"{v} {k}" for k, v in sorted(by.items())) or "none"}), key size {gsz}, layout {lay}, {len(data)} bytes'


def main(argv):
    a = argv[1:]
    try:
        if len(a) >= 2 and a[0] == 'show':
            for p in a[1:]:
                print(show(p, Path(p).read_bytes()))
        elif len(a) >= 4 and a[0] == 'merge' and a[1] == '-o':
            out = Path(a[2]); data = merge([(p, Path(p).read_bytes()) for p in a[3:]])
            tmp = out.with_name(out.name + '.tmp'); tmp.write_bytes(data); tmp.replace(out); print(show(str(out), data))
        elif len(a) >= 3 and a[0] == 'seed':
            sys.path.insert(0, str(REPO / 'dm/games')); import game
            out = game.load(a[1]).dir / game.SEED; data = merge([(p, Path(p).read_bytes()) for p in a[2:]])
            tmp = out.with_name(out.name + '.tmp'); tmp.write_bytes(data); tmp.replace(out); print(show(str(out), data))
        else:
            print(__doc__, file=sys.stderr); return 1
    except (Error, OSError) as e:
        print(f'vk_prewarm.py: {e}', file=sys.stderr); return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
