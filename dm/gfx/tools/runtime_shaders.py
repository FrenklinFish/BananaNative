#!/usr/bin/env python3
# NVN2 (Switch 2) shaders a game binds at run time: which control section + code area pairs its files hold, and whether the shader split takes every one of them.
# Ported from azmasm/switch2mac (MIT License, Copyright (c) 2026 azmasm), tools/find_runtime_shaders.py: its search for a saved shader's code and control section in the
# game files, and its key (sha256(control[:0xd8] + code)[:16]), here only to compare with its tables. The rest (the pairs found by the split's own readers, the coverage
# check) is this repository's.
#
# switch2mac needed a table of these shaders (games/mario-kart-world/runtime_shaders.tsv: 321 for Mario Kart World 1.0.0) because its split read only BNSH: the shaders
# live in agl's BAHS containers (Lib/agl, Lib/gsys, Lib/Terrain, Shader/Drive*.sarc), which shader_split.py reads (bahs_shaders), and a few in BNSH files. This tool
# checks that on an NVN2 game: it runs the split's readers over the game's NVN2 resource files and main, and reports (control sections inside members a SARC compresses
# again, or in files that need a zstd dictionary, are not searched)
#   · every NVN2 shader they find (key = the split's and the host's, skey), by the split's stage and by the control section's stage byte (+0x56: 0 vertex, 1 pixel,
#     5 compute, 6 task, 7 mesh), so task and mesh programs the split files under their SPH's stage (vertex or domain) show;
#   · every NVN2 control section (magic + version 0xc9) in those files that none of the readers paired with a code area: what a reader still misses;
#   · with --switch2mac TSV: how many of switch2mac's keys these pairs reproduce (by its key), by stage;
#   · with --map shader_map.tsv: how many of the pairs are in that map;
#   · with --dump DIR (shaders the host saved, DM_NVN_SHDUMP: <key>.ctl + <key>.code; switch2mac's dumps have the same form): where in the files each one's code area
#     and control section are, as switch2mac's finder did, and which are in no file (the shaders split.py reads from <version dir>/nvn_shdump).
# Usage: runtime_shaders.py <RomFS dir> <ExeFS main> [--switch2mac TSV] [--map TSV] [--dump DIR ...] [--name SUBSTRING ...] [--out TSV]   Environment DM_GAME (required, as shader_split.py), J (default 8)
#   --name: only RomFS files whose path holds this text, in any case (default ounce: the Switch 2 graphics resources, *.Nin_Ounce_NVN2.* and Lib/Visualizer/ounce/); --name '' reads every file (as the split does)
#   --out: one row per shader found: key, switch2mac key, split stage, control stage byte, where
import collections, concurrent.futures as cf, hashlib, os, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shader_split as ss

CTL_STAGE = {0: 'vertex', 1: 'pixel', 5: 'compute', 6: 'task', 7: 'mesh'}   # control +0x56 (switch2mac's nx2_split.py; agrees with the SPH where there is one)
NVN2_CTL = ss.CTL_MAGIC + b'\xc9\0\0\0'
USAGE = 'usage: runtime_shaders.py <RomFS dir> <ExeFS main> [--switch2mac TSV] [--map TSV] [--dump DIR ...] [--name SUBSTRING ...] [--out TSV]'

def s2m_key(ctl, code, mem=False):
    # switch2mac's key, sha256(control[:0xd8] + code): of the copy as the file holds it, or with mem, as memory holds it (+0x30, which BNSH files fill with the section's length,
    # as 0: shader_split.py nvn2_ctl). switch2mac's table has both kinds (its keys of BNSH effect shaders take the file's +0x30), so a match is tried with each
    c = bytearray(ctl[:0xd8])
    if mem and ss.nvn2_ctl(ctl): c[0x30:0x34] = bytes(4)
    return hashlib.sha256(bytes(c) + code).hexdigest()[:16]
def ctl_id(ctl): return bytes(ss.key_ctl(ctl))   # what the key hashes of a control section: two copies of one section match by it

def data(path):
    with open(path, 'rb') as f: return f.read()

def read(path):
    d = data(path)
    return ss.unzs(path) if d[:4] == ss.ZSTD_MAGIC else ss.yaz0(d, path) if d[:4] == b'Yaz0' else d

def leaves(d, label, depth=0):   # a file's SARC members, nested, else the file
    if d[:4] == b'SARC' and depth < 8:
        for nm, m in ss.sarc_members(d, label): yield from leaves(m, f'{label}/{nm}', depth + 1)
    else: yield label, d

def control_sections(d):   # (offset, the key bytes) of every NVN2 control section in d
    out, at = [], 0
    while (at := d.find(NVN2_CTL, at)) >= 0:
        if at + 0xd8 <= len(d): out.append((at, ctl_id(d[at:at + 0xd8])))
        at += 4
    return out

def scan(job):
    """One file → ({key: (where, stage, control, code)} of its NVN2 shaders, [(where, offset, control stage, code length)] of the NVN2 control sections no reader paired)"""
    path, label = job
    try: d = read(path) if label != 'main' else ss.nso_image(path)
    except (Exception, SystemExit) as e: return {}, [], f'{label}: {e}'
    if NVN2_CTL not in d: return {}, [], None
    found = []
    try:   # a damaged archive is one file left out, not the run
        ss.scan_blob(d, label, found)
        shaders = {ss.skey(c, k): (w, s, c[:0xd8], k) for w, s, c, k in found if ss.nvn2_ctl(c)}
        taken = {ctl_id(c) for _, _, c, _ in shaders.values()}
        loose = [(lb, at, d2[at + 0x56], struct.unpack_from('<I', d2, at + 0x44)[0]) for lb, d2 in leaves(d, label) for at, cid in control_sections(d2) if cid not in taken]
    except (Exception, SystemExit) as e: return {}, [], f'{label}: {e}'
    return shaders, loose, None

def dumps(dirs):   # {key: (control, code)} of the shaders saved in these folders
    out = {}
    for d in dirs:
        for f in sorted(os.listdir(d)):
            if f.endswith('.code') and os.path.exists(os.path.join(d, f[:-5] + '.ctl')):
                out.setdefault(f[:-5], (data(os.path.join(d, f[:-5] + '.ctl')), data(os.path.join(d, f))))
    return out

def locate(caps, jobs):
    """switch2mac's search: each saved shader's code area in the files, then its control section (usually in the same file) → {key: (control file, offset, code file, offset)}"""
    blobs = {label: read(path) if label != 'main' else ss.nso_image(path) for path, label in jobs}
    out = {}
    for k, (ctl, code) in caps.items():
        cb = ctl[:0x30] if ss.nvn2_ctl(ctl) else ctl   # up to +0x30, which BNSH files fill in and memory and BAHS files leave 0 (shader_split.py nvn2_ctl)
        for n, b in blobs.items():
            j = b.find(code)
            if j < 0: continue
            i = b.find(cb)
            loc = (n, i, n, j) if i >= 0 else next(((n2, b2.find(cb), n, j) for n2, b2 in blobs.items() if b2.find(cb) >= 0), None)
            if loc: out[k] = loc; break
    return out

def main():
    args = sys.argv[1:]; opt = collections.defaultdict(list)
    for o in ('--switch2mac', '--map', '--dump', '--name', '--out'):
        while o in args: i = args.index(o); opt[o].append(args[i + 1]); del args[i:i + 2]
    if len(args) != 2: sys.exit(USAGE)
    rom, mainnso = args
    names = opt['--name'] or ['ounce']
    jobs = sorted((p, r) for dp, _, fs in os.walk(rom) for f in fs if not f.startswith('.') and not f.endswith('.json')
                  for p in [os.path.join(dp, f)] for r in [os.path.relpath(p, rom)] if any(n.lower() in r.lower() for n in names))
    jobs.append((mainnso, 'main'))
    print(f'{len(jobs) - 1} RomFS files and main to read', flush=True)
    shaders, loose, bad, hold = {}, [], [], []
    with cf.ProcessPoolExecutor(int(os.environ.get('J', '8'))) as ex:
        for job, (sh, lo, why) in zip(jobs, ex.map(scan, jobs, chunksize=16)):
            for k, v in sh.items(): shaders.setdefault(k, v)
            loose += lo
            if why: bad.append(why)
            if sh or lo: hold.append(job)
    for why in bad: print(f'[RED] {why} (left out)')
    by = collections.Counter((st, CTL_STAGE.get(c[0x56], f'byte {c[0x56]}')) for _, st, c, _ in shaders.values())
    print(f'NVN2 shaders found: {len(shaders)} in {len(hold)} files; split stage / control stage: ' + ', '.join(f'{ss.STAGE_NAME[a]}/{b} {n}' for (a, b), n in sorted(by.items())))
    off = {(a, b): n for (a, b), n in by.items() if ss.STAGE_NAME[a] != b}
    if off: print('[RED] programs the split files under another stage than their control section names (its SPH\'s; task and mesh have no stage of their own there): ' + ', '.join(f'{b} as {ss.STAGE_NAME[a]} {n}' for (a, b), n in sorted(off.items())))
    uniq = {}
    for lb, at, st, n in loose: uniq.setdefault((lb.rsplit('/', 1)[-1], st, n), (lb, at))
    print(f'NVN2 control sections no reader pairs with a code area: {len(loose)}' + (f' ({len(uniq)} distinct)' if loose else ''))
    for (_, st, n), (lb, at) in list(uniq.items())[:20]: print(f'  {lb} @{at:#x}: {CTL_STAGE.get(st, st)}, code area {n:#x} bytes')
    s2m = {s2m_key(c, code, mem): k for k, (_, _, c, code) in shaders.items() for mem in (False, True)}
    for t in opt['--switch2mac']:
        rows = [ln.split('\t') for ln in data(t).decode().splitlines() if ln.strip() and not ln.startswith('#')]
        hit = [r for r in rows if r[0] in s2m]
        st = collections.Counter(ss.STAGE_NAME[shaders[s2m[r[0]]][1]] for r in hit)
        print(f'{t}: {len(hit)} of its {len(rows)} keys are among the pairs found (' + ', '.join(f'{a} {n}' for a, n in sorted(st.items())) + ')')
        miss = collections.Counter(r[1] for r in rows if r[0] not in s2m)
        if miss: print('  not found (another build of the file, or a shader in no file): ' + ', '.join(f'{f} {n}' for f, n in miss.most_common()))
    for m in opt['--map']:
        have = {ln.split('\t')[3] for ln in data(m).decode().splitlines() if ln.count('\t') >= 4 and not ln.startswith(('#', 'package'))}
        print(f'{m}: {sum(k in have for k in shaders)} of the {len(shaders)} pairs found are in it')
    if opt['--dump']:
        caps = dumps(opt['--dump']); loc = locate(caps, hold)
        print(f'saved shaders: {len(caps)}, {len(loc)} located in the files, {len(caps) - len(loc)} in no file (shader_split.py takes them from <version dir>/nvn_shdump)')
    for o in opt['--out']:
        with open(o, 'w') as f:
            f.write('# key\tswitch2mac key\tsplit stage\tcontrol stage\twhere\n')
            for k, (w, st, c, code) in sorted(shaders.items()): f.write(f'{k}\t{s2m_key(c, code)}\t{ss.STAGE_NAME[st]}\t{CTL_STAGE.get(c[0x56], c[0x56])}\t{w}\n')
        print(o)

if __name__ == '__main__':
    main()
