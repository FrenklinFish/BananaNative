#!/usr/bin/env python3
"""The profile key mesh_prepass_cut's rows (dm/games/README.md; vka_draw.c mesh_cut, a workaround: docs/reference/rendering.md "Depth-only mesh prepass drawn as spikes
on Qualcomm's driver"): which mesh shader draws a material's depth-only prepass and which its colour pass, and where the colour pass's mesh module writes what the
prepass's mesh module writes.

Pairs. A BNSH of a mesh-shaded shader archive (.bfsha) holds a material's programs as variations. Ocarina of Time's foliage archives have four: variation 0 the colour
pass (task, mesh and pixel shaders), 2 the depth prepass, 1 the shadow pass (a program of its own, or the prepass's, in the task-shaded ones), 3 another pass. The pipeline
keys the phone runs made (vk_prewarm.bin) agree: variation 2 draws into the scene's depth and colour targets with no colour written, variation 1 of the programs without a
task shader into a 16-bit depth image with no colour target. So a pair is (variation 2's mesh shader, variation 0's) where both have one and they differ. The replay
applies a row only to a depth-only draw of its prepass mesh into the depth image the colour mesh's EQUAL draws tested against, so a row for a pair the game does not draw
that way does nothing.

Maps. Both mesh modules (the Android library's SPIR-V) run in spv_mesh_run.py on the same pseudo-random inputs: first the renaming of the colour module's storage and
uniform buffers that gives it the prepass module's positions (the two programs bind the same data at other slots), then each prepass output location's components are
matched against the colour module's outputs over several workgroups and input seeds. A location maps to the colour location whose components all match (a constant
component may differ only where it is the default (0, 0, 0, 1)); one with no match is left out (the remapped pixel shader reads (0, 0, 0, 1) there).

Usage (DM_GAME set):
  mesh_prepass_pairs.py pairs <RomFS>                 the candidate pairs: prepass mesh, colour mesh, their pixel and task shaders, the BNSH (TSV)
  mesh_prepass_pairs.py keys <pairs.tsv>              the keys to build: DMSH_VK_LOOSE=1 vk_android_build.py --target vulkan <package dir> <out> $(... keys pairs.tsv)
  mesh_prepass_pairs.py maps <spv dir> <pairs.tsv>    each pair's map and its evidence (<spv dir>: that build's v-<V>/spv; spirv-dis on PATH)
  mesh_prepass_pairs.py rows <spv dir> <pairs.tsv> [--write]   the profile rows (the evidence on stderr); --write replaces the mesh_prepass_cut rows of dm/games/$DM_GAME/game.tsv
<RomFS> is a folder or a NUP package (game.nup:update/<version>/romfs). J sets the processes for maps / rows (default 8). Exit 1 on an error.
"""
import concurrent.futures as cf, itertools, os, re, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__)); REPO = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
import spv_mesh_run as R

DEFAULT = (0, 0, 0, 0x3f800000)


def bnsh_variations(romfs):
    """{BNSH label: {variation: {stage: key}}} of the BNSHs with a mesh shader in the RomFS's .bfsha files (loose, zstd, or inside SARC archives)"""
    sys.path.insert(0, os.path.join(REPO, 'dm', 'tools'))
    import shader_split as S
    S.ZDICTS.update(S.zs_dicts(romfs))
    out = {}
    def scan(d, label):
        o = 0
        while (o := d.find(b'BNSH\0\0\0\0', o)) >= 0:
            for w, st, ctl, code in S.bnsh_shaders(d, o, label):
                b, v = w.rsplit('#var', 1)
                out.setdefault(b, {}).setdefault(int(v), {})[st] = S.skey(ctl, code)
            o += 8
    for top, _, files in S.os.walk(romfs):
        for f in sorted(files):
            p = os.path.join(top, f); rel = os.path.relpath(p, romfs)
            if not re.search(r'\.(bfsha|sarc)(\.zs)?$', f): continue
            d = S.unzs(p) if f.endswith('.zs') else S.open(p, 'rb').read()
            if d[:4] == b'SARC':
                for n, m in S.sarc_members(d, rel):
                    if m[:4] == S.ZSTD_MAGIC: m = S.zstd_mod().decompress(m)
                    if n.endswith('.bfsha'): scan(m, rel + '/' + n)
            else: scan(d, rel)
    return {b: vs for b, vs in out.items() if any('ms' in s for s in vs.values())}


def pairs_of(variations):
    """[(prepass mesh, colour mesh, prepass pixel, colour pixel, task, BNSH)]: variation 2's mesh shader and variation 0's, where both have one and they differ, in
    BNSHs of the four-variation layout (a BNSH with more variations, such as Ocarina of Time's one large Model program set, groups its passes otherwise)"""
    out = []
    for b, vs in variations.items():
        if sorted(vs) != [0, 1, 2, 3]: continue
        c, p = vs.get(0, {}), vs.get(2, {})
        if 'ms' in c and 'ms' in p and c['ms'] != p['ms'] and 'ps' in c:
            out.append((p['ms'], c['ms'], p.get('ps', '-'), c['ps'], p.get('ts', '-'), b))
    return out


def read_pairs(path):
    return [l.rstrip('\n').split('\t') for l in open(path) if l.strip() and not l.startswith('#')]


def load(spv_dir, key):
    p = os.path.join(spv_dir, key + '.spv')
    if not os.path.exists(p): raise SystemExit(f'[RED] {p}: not built (mesh_prepass_pairs.py keys, then a key build with DMSH_VK_LOOSE=1)')
    return R.Module(subprocess.run(['spirv-dis', '--raw-id', p], capture_output=True, text=True, check=True).stdout)


def names(mod, kind):
    """the module's storage (kind 's') or uniform (kind 'c') buffer names, without their stage prefix: s0, s1, ... / c0, c3, ..."""
    return sorted({n.split('_', 1)[1] for n in mod.names.values() if re.fullmatch(r'[a-z]+_' + kind + r'\d+', n)}, key=lambda n: int(n[1:]))


def positions(env, mod):
    """{vertex: position} of a run"""
    _, pos = mod.outputs()
    return R.per_vertex(env, pos, 0 if pos is not None and pos not in mod.builtin else None) if pos is not None else {}


def shown(env, mod):
    """{vertex: position} of the vertices a run's triangles use (a triangle with a repeated index is culled; a prepass module may cull triangles its colour
    module keeps); every vertex when the module writes no triangle indices"""
    p = positions(env, mod)
    tv = [k for k, n in mod.builtin.items() if n == 'PrimitiveTriangleIndicesEXT']
    if not tv: return p
    n, tris = env.counts[1] if env.counts else 0, {}
    for k, path, val in env.out:
        if k != tv[0] or not path: continue
        t = list(val) if len(path) == 1 else list(tris.get(path[0], (0, 0, 0)))
        if len(path) > 1: t[path[1]] = val
        tris[path[0]] = tuple(t)
    return {v: p.get(v, DEFAULT) for v in sorted({i for k, t in tris.items() if k < n and len(set(t)) == 3 for i in t})}


def renames(a, b):
    """the renamings of b's buffers to a's names to try, b's names kept first: uniform buffers c4 and up shifted together (c0-c3 are the driver's and the scene's),
    storage buffers matched in order (the programs come from one source, so their bindings keep their order; b's left over get names of their own)"""
    sa, sb = names(a, 's'), names(b, 's')
    cb = [c for c in names(b, 'c') if int(c[1:]) >= 4]
    for sh in (0, 1, -1, 2, -2):
        cmap = {c: 'c%d' % (int(c[1:]) - sh) for c in cb}
        if any(int(v[1:]) < 1 for v in cmap.values()): continue   # (c0 is the driver segment)
        if len(sb) >= len(sa): choices = [dict(zip(c, sa)) for c in itertools.combinations(sb, len(sa))]
        else: choices = [dict(zip(sb, c)) for c in itertools.combinations(sa, len(sb))]
        choices.sort(key=lambda m: sum(k != v for k, v in m.items()))
        for m in choices:
            yield cmap, {**{x: 'b.' + x for x in sb}, **m}


def runs(a, wgs, seeds):
    """(wg, seed, run) of runs of module a that emit a vertex off the default, up to three"""
    got = []
    for seed in seeds:
        for wg in wgs:
            env = R.run(a, wg, seed)
            if any(v != DEFAULT for v in shown(env, a).values()): got.append((wg, seed, env))
            if len(got) >= 3: return got
    return got


def columns(env, mod, verts, out):
    for loc, var in mod.outputs()[0].items():
        pv = R.per_vertex(env, var)
        for c in range(4): out.setdefault((loc, c), []).extend(pv.get(v, DEFAULT)[c] for v in verts)


def match(cols_a, cols_b, la, lb):
    """{prepass location: (colour location, notes)} and {prepass location: why not} from the output columns of both modules"""
    got, miss = {}, {}
    for loc in sorted(la):
        const = [len(set(cols_a[(loc, c)])) <= 1 for c in range(4)]
        dflt = [const[c] and cols_a[(loc, c)][0] == DEFAULT[c] for c in range(4)]
        if all(dflt): miss[loc] = '(0, 0, 0, 1) everywhere'; continue
        cands = []
        for l2 in sorted(lb):
            if not all(dflt[c] or cols_a[(loc, c)] == cols_b[(l2, c)] for c in range(4)): continue
            if not any(not const[c] and cols_a[(loc, c)] == cols_b[(l2, c)] for c in range(4)): continue
            notes = ['%s: the prepass writes %s, the colour module other values' % ('xyzw'[c], '0' if c < 3 else '1')
                     for c in range(4) if dflt[c] and cols_a[(loc, c)] != cols_b[(l2, c)]]
            cands.append((l2 != loc, len(notes), l2, notes))
        if cands:
            _, _, l2, notes = min(cands); got[loc] = (l2, notes)
        else:
            lost = ['xyzw'[c] for c in range(4) if not dflt[c] and not any(cols_a[(loc, c)] == col for col in cols_b.values())]
            miss[loc] = 'no colour location holds it (%s)' % ('components found nowhere: ' + ', '.join(lost) if lost else 'its components are spread over several locations')
    return got, miss


def same_vertices(sa, pb):
    """the colour run's vertex for each vertex the prepass run shows, by position: the same index when it holds the same position, else the one vertex that does
    (a prepass module may compact the triangles it keeps); None when a position is missing or ambiguous"""
    at = {}
    for u, p in pb.items(): at.setdefault(p, []).append(u)
    out = []
    for v, p in sa.items():
        if pb.get(v) == p: out.append(v); continue
        us = at.get(p, [])
        if len(us) != 1: return None
        out.append(us[0])
    return out


def derive(a, b, most=4):
    """(map {prepass location: colour location}, evidence lines) for prepass module a and colour module b; map None when no renaming gives equal positions.
    Renamings of buffers the positions do not read are not pinned down by them: every one that gives equal positions (up to most) is followed, and a location maps
    only where all of them agree"""
    base = runs(a, range(8), (1, 2, 3, 4))
    if not base: return None, ['the prepass module emits no vertex off (0, 0, 0, 1) in workgroups 0-7 with input seeds 1-4: nothing to compare']
    la, lb = a.outputs()[0], b.outputs()[0]
    cols_a = {}
    for w, s, ea in base: columns(ea, a, list(shown(ea, a)), cols_a)
    found = []
    for cmap, smap in renames(a, b):
        ebs = []
        for w, s, ea in base:
            eb = R.run(b, w, s, smap, cmap)
            vs = same_vertices(shown(ea, a), positions(eb, b))
            if vs is None: break
            ebs.append((eb, vs))
        else:
            cols_b = {}
            for eb, vs in ebs: columns(eb, b, vs, cols_b)
            found.append((cmap, smap, match(cols_a, cols_b, la, lb)))
            if len(found) >= most: break
    if not found: return None, ['no renaming of the colour module\'s buffers gives the prepass module\'s positions']
    ev = ['positions equal in %d runs (%s) under %d renaming(s) of the colour module\'s buffers: %s' % (
        len(base), ', '.join('workgroup %d seed %d: %d vertices' % (w, s, len(shown(e, a))) for w, s, e in base), len(found),
        '; '.join('uniform %s, storage %s' % ({k: v for k, v in c.items() if k != v} or 'as named', {k: v for k, v in m.items() if not v.startswith('b.') and k != v} or 'as named')
                  for c, m, _ in found))]
    out = {}
    for loc in sorted(la):
        alls = [f[2][0].get(loc) for f in found]
        if alls[0] and all(x and x[0] == alls[0][0] for x in alls):
            out[loc] = alls[0][0]
            ev.append('location %d = colour location %d%s' % (loc, alls[0][0], ''.join('; ' + n for n in alls[0][1])))
        elif any(alls):
            ev.append('location %d: the renamings disagree (%s): left out, the pixel shader reads (0, 0, 0, 1)' % (loc, ', '.join(str(x[0]) if x else 'none' for x in alls)))
        else:
            why = found[0][2][1][loc]
            ev.append('location %d: %s: left out%s' % (loc, why, '' if why.startswith('(0') else ', the pixel shader reads (0, 0, 0, 1)'))
    return out, ev


def map_text(m):
    return ','.join('%d:%d' % (k, v) for k, v in sorted(m.items())) if m else '-'


def pixel_inputs(mod):
    """the locations a pixel module reads (its user Input variables)"""
    return sorted(mod.location[v] for v, a in mod.gvars.items() if a[1] == 'Input' and v in mod.location)


def one(args):
    """one pair's (pair, map, evidence). A prepass pixel shader that reads no input location needs no map: '-' (it reads what it reads in the game, nothing)"""
    spv, p = args
    if p[2] != '-' and not pixel_inputs(load(spv, p[2])):
        return p, {}, ['the prepass pixel shader %s reads no input location: nothing to move' % p[2]]
    try:
        m, ev = derive(load(spv, p[0]), load(spv, p[1]))
    except R.Unsupported as e:
        m, ev = None, ['the interpreter stopped: %s' % e]
    if m is not None and p[2] != '-':
        reads = pixel_inputs(load(spv, p[2]))
        ev.append('the prepass pixel shader %s reads location%s %s%s' % (p[2], 's' if len(reads) > 1 else '', ', '.join(map(str, reads)),
                  '' if all(r in m for r in reads) else ' (not all mapped: those read (0, 0, 0, 1))'))
    return p, m, ev


def maps(spv, pairs):
    """(pair, map, evidence) of each pair, in order, each as soon as it and those before it are done"""
    with cf.ProcessPoolExecutor(int(os.environ.get('J', '8'))) as ex:
        yield from ex.map(one, [(spv, p) for p in pairs])


def rows_text(results):
    out = []
    for p, m, ev in results:
        out.append('# %s: prepass %s (pixel %s) / colour %s (pixel %s)%s' % (p[5], p[0], p[2], p[1], p[3], '' if m is not None else ': no map (' + ev[-1] + ')'))
        out.append('mesh_prepass_cut\t%s\t%s%s' % (p[0], p[1], '\t' + map_text(m) if m is not None else ''))
    return out


def write(game, lines):
    path = os.path.join(REPO, 'dm', 'games', game, 'game.tsv')
    with open(path) as f: old = f.read().splitlines()
    idx = [i for i, l in enumerate(old) if l.startswith(('mesh_prepass_cut\t', '# mesh_prepass_pairs.py'))]
    at = idx[0] if idx else len(old)
    keep = [l for i, l in enumerate(old) if i not in set(idx) and not (idx and idx[0] <= i <= idx[-1] and l.startswith('# ') and ': prepass ' in l)]
    at = min(at, len(keep))
    new = keep[:at] + ['# mesh_prepass_pairs.py rows (dm/gfx/tools/mesh_prepass_pairs.py): generated, do not edit by hand'] + lines + keep[at:]
    with open(path, 'w') as f: f.write('\n'.join(new) + '\n')


def main(argv):
    game = os.environ.get('DM_GAME')
    if not game: raise SystemExit('[RED] DM_GAME is not set')
    if len(argv) == 2 and argv[0] == 'pairs':
        for p in pairs_of(bnsh_variations(argv[1])): print('\t'.join(p))
    elif len(argv) == 2 and argv[0] == 'keys':
        print('\n'.join(sorted({k for p in read_pairs(argv[1]) for k in p[:5] if k != '-'})))
    elif len(argv) == 3 and argv[0] == 'maps':
        for p, m, ev in maps(argv[1], read_pairs(argv[2])):
            print('%s -> %s  %s  (%s)' % (p[0], p[1], map_text(m) if m is not None else 'NO MAP', p[5]))
            for e in ev: print('    ' + e)
            sys.stdout.flush()
    elif len(argv) in (3, 4) and argv[0] == 'rows' and argv[3:] in ([], ['--write']):
        res = []
        for p, m, ev in maps(argv[1], read_pairs(argv[2])):   # the evidence on stderr, as maps prints it
            res.append((p, m, ev))
            print('%s -> %s  %s\n%s' % (p[0], p[1], map_text(m) if m is not None else 'NO MAP', '\n'.join('    ' + e for e in ev)), file=sys.stderr, flush=True)
        lines = rows_text(res)
        if argv[3:]: write(game, lines)
        print('\n'.join(lines))
    else:
        raise SystemExit(__doc__)


if __name__ == '__main__':
    main(sys.argv[1:])
