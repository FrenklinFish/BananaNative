"""dm/gfx/tools/mesh_prepass_pairs.py (the profile key mesh_prepass_cut's rows) and its interpreter dm/gfx/tools/spv_mesh_run.py: pairs from a BNSH's variations, and a
location map derived by running two small mesh modules (SPIR-V text as spirv-dis --raw-id prints it) whose buffers sit at other slots and outputs at other locations."""
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
import mesh_prepass_pairs as P  # noqa: E402
import spv_mesh_run as R  # noqa: E402


def mesh(cb, sbs, pos_sb, out):
    """A 4-lane mesh module: lane i writes vertex i. Its position is (uniform buffer cb row i .x, storage buffer pos_sb word i + 4, same .x, 1); out is
    {location: (storage buffer, use)}: use 'q' writes (that buffer's word i + 4, the uniform's row i .x, 0, 1), 'junk' (that buffer's word i + 8, 0, 0, 1)"""
    ids = iter(range(100, 1000))
    L = ['OpCapability MeshShadingEXT', 'OpEntryPoint MeshEXT %1 "main"', 'OpExecutionMode %1 LocalSize 4 1 1', 'OpExecutionMode %1 OutputVertices 4',
         'OpName %%10 "cp_%s"' % cb, 'OpDecorate %11 BuiltIn LocalInvocationIndex', 'OpMemberDecorate %30 0 BuiltIn Position']
    sbv = {}
    for s in sbs:
        sbv[s] = '%%%d' % next(ids); L.append('OpName %s "cp_%s"' % (sbv[s], s))
    outv = {}
    for loc in out:
        outv[loc] = '%%%d' % next(ids); L.append('OpDecorate %s Location %d' % (outv[loc], loc))
    L += ['%2 = OpTypeVoid', '%3 = OpTypeFunction %2', '%4 = OpTypeInt 32 0', '%5 = OpTypeFloat 32', '%6 = OpTypeVector %5 4',
          '%20 = OpConstant %4 0', '%21 = OpConstant %4 1', '%22 = OpConstant %4 4', '%23 = OpConstant %4 8', '%24 = OpConstant %5 0', '%25 = OpConstant %5 1',
          '%26 = OpTypeArray %6 %22', '%27 = OpTypeStruct %26', '%28 = OpTypePointer Uniform %27', '%29 = OpTypePointer Uniform %5',
          '%30 = OpTypeStruct %6', '%31 = OpTypeArray %30 %22', '%32 = OpTypePointer Output %31', '%33 = OpTypePointer Output %6',
          '%34 = OpTypePointer Output %26', '%35 = OpTypeRuntimeArray %5', '%36 = OpTypeStruct %35', '%37 = OpTypePointer StorageBuffer %36',
          '%38 = OpTypePointer StorageBuffer %5', '%39 = OpTypePointer Input %4',
          '%10 = OpVariable %28 Uniform', '%11 = OpVariable %39 Input', '%12 = OpVariable %32 Output']
    L += ['%s = OpVariable %%37 StorageBuffer' % v for v in sbv.values()] + ['%s = OpVariable %%34 Output' % v for v in outv.values()]
    L += ['%1 = OpFunction %2 None %3', '%40 = OpLabel', 'OpSetMeshOutputsEXT %22 %21', '%41 = OpLoad %4 %11',
          '%42 = OpAccessChain %29 %10 %20 %41 %20', '%43 = OpLoad %5 %42', '%44 = OpIAdd %4 %41 %22', '%45 = OpIAdd %4 %41 %23']
    def word(s, idx):
        a, v = '%%%d' % next(ids), '%%%d' % next(ids)
        L.extend(['%s = OpAccessChain %%38 %s %%20 %s' % (a, sbv[s], idx), '%s = OpLoad %%5 %s' % (v, a)]); return v
    y = word(pos_sb, '%44')
    p, pp = '%%%d' % next(ids), '%%%d' % next(ids)
    L += ['%s = OpCompositeConstruct %%6 %%43 %s %%43 %%25' % (p, y), '%s = OpAccessChain %%33 %%12 %%41 %%20' % pp, 'OpStore %s %s' % (pp, p)]
    for loc, (s, use) in out.items():
        w = word(s, '%44' if use == 'q' else '%45')
        q, qp = '%%%d' % next(ids), '%%%d' % next(ids)
        L += ['%s = OpCompositeConstruct %%6 %s %s %%24 %%25' % (q, w, '%43' if use == 'q' else '%24'), '%s = OpAccessChain %%33 %s %%41' % (qp, outv[loc]),
              'OpStore %s %s' % (qp, q)]
    L += ['OpReturn', 'OpFunctionEnd']
    return R.Module('\n'.join(L))


class Pairs(unittest.TestCase):
    def test_variation_two_and_zero(self):
        v = {
            'a.bfsha#BNSH@0x100': {0: {'ms': 'c1', 'ps': 'pc'}, 1: {'ms': 's1', 'ps': 'pp'}, 2: {'ms': 'p1', 'ps': 'pp'}, 3: {'ms': 'x1', 'ps': 'px'}},
            'a.bfsha#BNSH@0x200': {0: {'ts': 't', 'ms': 'c2', 'ps': 'pc2'}, 1: {'ts': 't', 'ms': 'p2', 'ps': 'pp2'}, 2: {'ts': 't', 'ms': 'p2', 'ps': 'pp2'},
                                   3: {'ts': 't', 'ms': 'x2', 'ps': 'px2'}},
            'a.bfsha#BNSH@0x300': {0: {'ms': 'c3', 'ps': 'pc3'}, 1: {'ms': 's3', 'ps': 'pc3'}},          # no variation 2
            'a.bfsha#BNSH@0x400': {0: {'ms': 'c4', 'ps': 'pc4'}, 2: {'ms': 'c4', 'ps': 'pc4'}},          # the same mesh shader
            'a.bfsha#BNSH@0x500': {0: {'ms': 'c5'}, 1: {'ms': 's5'}, 2: {'ms': 'p5'}, 3: {'ms': 'x5'}},  # no colour pixel shader
            'a.bfsha#BNSH@0x600': {k: {'ms': 'm%d' % k, 'ps': 'p%d' % k} for k in range(9)},           # another layout
        }
        self.assertEqual(P.pairs_of(v), [('p1', 'c1', 'pp', 'pc', '-', 'a.bfsha#BNSH@0x100'), ('p2', 'c2', 'pp2', 'pc2', 't', 'a.bfsha#BNSH@0x200')])


class Interpreter(unittest.TestCase):
    def test_run_outputs(self):
        m = mesh('c4', ['s0'], 's0', {6: ('s0', 'q')})
        env = R.run(m, 0, 1)
        self.assertEqual(env.counts, (4, 1))
        locs, pos = m.outputs()
        self.assertEqual(sorted(locs), [6]); self.assertIsNotNone(pos)
        p, q = P.positions(env, m), R.per_vertex(env, locs[6])
        self.assertEqual(sorted(p), [0, 1, 2, 3])
        for v in range(4): self.assertEqual((q[v][0], q[v][1], q[v][2], q[v][3]), (p[v][1], p[v][0], 0, 0x3f800000))
        self.assertEqual(P.positions(R.run(m, 0, 1), m), p)          # deterministic
        self.assertNotEqual(P.positions(R.run(m, 0, 2), m), p)       # another seed, other inputs

    def test_unsupported(self):
        m = R.Module('OpEntryPoint MeshEXT %1 "main"\n%2 = OpTypeVoid\n%3 = OpTypeFunction %2\n%4 = OpTypeFloat 32\n%5 = OpConstant %4 1\n'
                     '%1 = OpFunction %2 None %3\n%6 = OpLabel\n%7 = OpDPdx %4 %5\nOpReturn\nOpFunctionEnd')
        with self.assertRaises(R.Unsupported): R.run(m)

    def test_pixel_inputs(self):
        m = R.Module('OpDecorate %10 Location 5\nOpDecorate %11 Location 0\nOpDecorate %12 Location 2\n%1 = OpTypeFloat 32\n%2 = OpTypeVector %1 4\n'
                     '%3 = OpTypePointer Input %2\n%4 = OpTypePointer Output %2\n%10 = OpVariable %3 Input\n%11 = OpVariable %4 Output\n%12 = OpVariable %3 Input')
        self.assertEqual(P.pixel_inputs(m), [2, 5])

    def test_float_constants(self):
        self.assertEqual(R.float_bits('1'), 0x3f800000)
        self.assertEqual(R.float_bits('-0x1p+128'), 0xff800000)
        self.assertEqual(R.float_bits('0x1.8p+128'), 0x7fc00000)


class Maps(unittest.TestCase):
    def test_renamed_buffers_moved_location(self):
        # the prepass reads its material at c4 and its instances at s0, writes the value at location 6 and a factor (from s2) at location 0; the colour module reads
        # the same data at c5 and s1 (its s0 is another buffer, its s3 takes s2's place), writes the value at location 8, and no location holds the factor
        a = mesh('c4', ['s0', 's2'], 's0', {6: ('s0', 'q'), 0: ('s2', 'junk')})
        b = mesh('c5', ['s0', 's1', 's3'], 's1', {8: ('s1', 'q'), 3: ('s0', 'junk')})
        m, ev = P.derive(a, b)
        self.assertEqual(m, {6: 8}, ev)
        self.assertEqual(P.map_text(m), '6:8')
        self.assertTrue(any(e.startswith('location 0: no colour location') for e in ev), ev)
        self.assertTrue(any("1 renaming(s)" in e and "'c5': 'c4'" in e and "'s1': 's0', 's3': 's2'" in e for e in ev), ev)

    def test_renamings_disagree(self):
        # the positions read only s0: s1 and s2 of the colour module could each be the prepass's s1, so location 0 is not mapped
        a = mesh('c4', ['s0', 's1'], 's0', {6: ('s0', 'q'), 0: ('s1', 'junk')})
        b = mesh('c4', ['s0', 's1', 's2'], 's0', {8: ('s0', 'q'), 2: ('s1', 'junk'), 4: ('s2', 'junk')})
        m, ev = P.derive(a, b)
        self.assertEqual(m, {6: 8}, ev)
        self.assertTrue(any(e.startswith('location 0: the renamings disagree (2, 4)') for e in ev), ev)

    def test_no_renaming(self):
        a = mesh('c4', ['s0'], 's0', {6: ('s0', 'q')})
        self.assertEqual(P.derive(a, mesh('c4', ['s0'], 's0', {6: ('s0', 'junk')}))[0], {}, 'positions equal, location 6 holds other values: no map entry')
        self.assertEqual(P.derive(a, mesh('c4', ['s0', 's1'], 's1', {6: ('s1', 'q')}))[0], {6: 6})
        m, ev = P.derive(a, mesh('c4', ['s0'], 's0', {}))
        self.assertEqual(m, {}); self.assertTrue(ev[-1].startswith('location 6: no colour location'), ev)
        self.assertEqual(P.derive(a, mesh('c3', ['s0'], 's0', {6: ('s0', 'q')}))[0], None, 'the position reads c3, which no renaming makes c4')


class Rows(unittest.TestCase):
    def test_rows_and_write(self):
        res = [(('p1', 'c1', 'pp', 'pc', '-', 'x#BNSH@0x100'), {6: 8}, []), (('p2', 'c2', 'pp2', 'pc2', 't', 'x#BNSH@0x200'), None, ['why'])]
        lines = P.rows_text(res)
        self.assertEqual([l for l in lines if not l.startswith('#')], ['mesh_prepass_cut\tp1\tc1\t6:8', 'mesh_prepass_cut\tp2\tc2'])
        with tempfile.TemporaryDirectory() as t:
            g = Path(t) / 'dm' / 'games' / 'G'; g.mkdir(parents=True)
            (g / 'game.tsv').write_text('a\t1\n# mesh_prepass_pairs.py rows (old)\n# x: prepass q\nmesh_prepass_cut\tq\tr\t1:2\nb\t2\n')
            old = P.REPO; P.REPO = t
            try: P.write('G', lines)
            finally: P.REPO = old
            got = (g / 'game.tsv').read_text().splitlines()
            self.assertEqual(got[0], 'a\t1'); self.assertEqual(got[-1], 'b\t2')
            self.assertEqual([l for l in got if l.startswith('mesh_prepass_cut')], ['mesh_prepass_cut\tp1\tc1\t6:8', 'mesh_prepass_cut\tp2\tc2'])
            self.assertEqual(sum(l.startswith('# mesh_prepass_pairs.py') for l in got), 1)


if __name__ == '__main__':
    unittest.main()
