"""The Android Vulkan shader library ABI (dm/gfx/nvn/dmsh_abi_vk.h): the C structs have the sizes the build packs, every binding kind the build may write has a number,
the library version header is current, and the SPIR-V rewrites keep a module valid. No game files needed; the spirv-val check is skipped without SPIRV-Tools."""
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'dm/gfx/tools'
sys.path.insert(0, str(TOOLS))
import dmsh_abi as abi   # noqa: E402
import vk_android_spv as spv   # noqa: E402

HEADER = REPO / 'dm/gfx/nvn/dmsh_abi_vk.h'
METAL_ONLY = {abi.KIND_GEOMETRY, abi.KIND_BORDER}   # the geometry lowering's helpers and the border colour block: no Vulkan binding


def vk_values():
    out = {}
    for m in re.finditer(r'^#define DMSH_VK_(\w+)\s+(0x[0-9A-Fa-f]+|\d+|"[^"]*")', HEADER.read_text(), re.M):
        out[m.group(1)] = m.group(2)[1:-1] if m.group(2).startswith('"') else int(m.group(2), 0)
    return out


class Abi(unittest.TestCase):
    def test_struct_sizes(self):
        cc = shutil.which('cc') or shutil.which('clang')
        if not cc: self.skipTest('no C compiler')
        with tempfile.TemporaryDirectory() as d:
            src = Path(d) / 't.c'; src.write_text(f'#include "{HEADER}"\nint main(void) {{ return 0; }}\n')
            r = subprocess.run([cc, '-std=c11', '-c', str(src), '-o', str(Path(d) / 't.o')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)

    def test_pack_formats(self):
        v = vk_values()
        self.assertEqual(struct.calcsize('<II16s16s16s16sIIIIIIQQHH12s'), v['HEADER_SIZE'])
        self.assertEqual(struct.calcsize('<QQBBHIIHH3HBBIIII8x'), v['ENTRY_SIZE'])
        self.assertEqual(struct.calcsize('<BBHBBHhHBBhHHI'), v['BIND_SIZE'])
        self.assertEqual(struct.calcsize('<IIIBBH'), v['MOD_SIZE'])
        # the header, entry and module layouts are vk_android_delta.py's (the build and the delta update read and write them alike), the binding record the build's
        src = (TOOLS / 'vk_android_build.py').read_text() + (TOOLS / 'vk_android_delta.py').read_text()
        for fmt in ("'<II16s16s16s16sIIIIIIQQHH12s'", "'<QQBBHIIHH3HBBIIII8x'", "'<BBHBBHhHBBhHHI'", "'<IIIBBH'"):
            self.assertIn(fmt, src, 'the build packs another layout than this test checks')

    def test_every_kind_numbered(self):
        v = vk_values()
        names = {k[len('KIND_'):] for k in v if k.startswith('KIND_')}
        for k, s in abi.VALUES.items():
            if k.startswith('KIND_') and s not in METAL_ONLY:
                self.assertIn(k[len('KIND_'):], names, f'DMSH_{k} has no DMSH_VK_{k}')
        nums = [n for k, n in v.items() if k.startswith('KIND_')]
        self.assertEqual(len(nums), len(set(nums)), 'two kinds share a number')

    def test_stage_ranges(self):
        v = vk_values()
        self.assertLessEqual(max(v['STAGE_VS'], v['STAGE_TCS'], v['STAGE_TES'], v['STAGE_GS'], v['STAGE_FS']) * v['STAGE_BINDINGS'] + v['STAGE_BINDINGS'], 0x10000)

    def test_version_header_current(self):
        r = subprocess.run([sys.executable, str(TOOLS / 'vk_android_build.py'), '--version'], capture_output=True, text=True, check=True)
        self.assertIn(f'"{r.stdout.strip()}"', (REPO / 'dm/gfx/nvn/dmsh_chain_vk.h').read_text(),
                      'dm/gfx/nvn/dmsh_chain_vk.h is stale: run dm/gfx/tools/vk_android_build.py --header and commit it')

    def test_same_rows(self):
        """vk_android_same.tsv: each row a tools digest, the earlier library version it was shown to reproduce, and the evidence; a version is never itself a
        digest with a row (no chains: the row names the version the libraries carry), and --version is the row's version when the tree's digest has one"""
        rows = [l.split('\t') for l in (TOOLS / 'vk_android_same.tsv').read_text().splitlines() if l and not l.startswith('#')]
        digests = [r[0] for r in rows]
        self.assertEqual(len(digests), len(set(digests)), 'a tools digest has two rows')
        for r in rows:
            self.assertEqual(len(r), 3, f'{r}: tools digest, library version, evidence')
            self.assertRegex(r[0], r'^[0-9a-f]{12}$'); self.assertRegex(r[1], r'^[0-9a-f]{12}$'); self.assertTrue(r[2].strip(), f'{r[0]}: no evidence')
            self.assertNotIn(r[1], digests, f'{r[1]} is itself a digest with a row: name the version its libraries carry')
        run = lambda a: subprocess.run([sys.executable, str(TOOLS / 'vk_android_build.py'), a], capture_output=True, text=True, check=True).stdout.strip()
        d = run('--digest')
        self.assertEqual(run('--version'), dict((r[0], r[1]) for r in rows).get(d, d))


# A vertex shader as the translator writes one: a position in gl_PerVertex, one vec4 output at location 1, FMax of two floats, no GLSL import, an unused Float64 capability
VS = '''OpCapability Shader
OpCapability Float64
OpMemoryModel Logical GLSL450
OpEntryPoint Vertex %main "main" %pv %o1 %i0
OpDecorate %blk Block
OpMemberDecorate %blk 0 BuiltIn Position
OpDecorate %o1 Location 1
OpDecorate %i0 Location 0
%void = OpTypeVoid
%fn = OpTypeFunction %void
%f32 = OpTypeFloat 32
%v4 = OpTypeVector %f32 4
%blk = OpTypeStruct %v4
%pblk = OpTypePointer Output %blk
%pv = OpVariable %pblk Output
%pv4 = OpTypePointer Output %v4
%o1 = OpVariable %pv4 Output
%pi4 = OpTypePointer Input %v4
%i0 = OpVariable %pi4 Input
%int = OpTypeInt 32 1
%c0 = OpConstant %int 0
%main = OpFunction %void None %fn
%l = OpLabel
%x = OpLoad %v4 %i0
%p = OpAccessChain %pv4 %pv %c0
OpStore %p %x
OpReturn
OpFunctionEnd
'''


# A pixel shader as the translator writes one: Ryujinx's support buffer (a uint first), colour 0 written from a float, colour 1 component 0 from integer bits, read back once
FS = '''OpCapability Shader
OpMemoryModel Logical GLSL450
OpEntryPoint Fragment %main "main" %o0 %o1
OpExecutionMode %main OriginUpperLeft
OpName %sb "support_buffer"
OpDecorate %sbt Block
OpMemberDecorate %sbt 0 Offset 0
OpMemberDecorate %sbt 1 Offset 16
OpDecorate %sb DescriptorSet 0
OpDecorate %sb Binding 0
OpDecorate %o0 Location 0
OpDecorate %o1 Location 1
%void = OpTypeVoid
%fn = OpTypeFunction %void
%f32 = OpTypeFloat 32
%u32 = OpTypeInt 32 0
%int = OpTypeInt 32 1
%v4 = OpTypeVector %f32 4
%sbt = OpTypeStruct %u32 %v4
%psb = OpTypePointer Uniform %sbt
%sb = OpVariable %psb Uniform
%pv4 = OpTypePointer Output %v4
%pf = OpTypePointer Output %f32
%o0 = OpVariable %pv4 Output
%o1 = OpVariable %pv4 Output
%c0 = OpConstant %int 0
%u7 = OpConstant %u32 7
%f1 = OpConstant %f32 1
%one = OpConstantComposite %v4 %f1 %f1 %f1 %f1
%main = OpFunction %void None %fn
%l = OpLabel
OpStore %o0 %one
%bits = OpBitcast %f32 %u7
%p = OpAccessChain %pf %o1 %c0
OpStore %p %bits
%back = OpLoad %f32 %p
OpStore %p %back
OpReturn
OpFunctionEnd
'''

# A pixel shader drawn with a mesh program: flat input 0 (written per primitive), input 1 per vertex, a storage buffer read
MESH_FS = '''OpCapability Shader
OpMemoryModel Logical GLSL450
OpEntryPoint Fragment %main "main" %i0 %i1 %o0
OpExecutionMode %main OriginUpperLeft
OpDecorate %i0 Location 0
OpDecorate %i0 Flat
OpDecorate %i1 Location 1
OpDecorate %o0 Location 0
OpDecorate %ra ArrayStride 4
OpDecorate %sbt BufferBlock
OpMemberDecorate %sbt 0 Offset 0
OpDecorate %sb DescriptorSet 0
OpDecorate %sb Binding 0
%void = OpTypeVoid
%fn = OpTypeFunction %void
%f32 = OpTypeFloat 32
%int = OpTypeInt 32 1
%v4 = OpTypeVector %f32 4
%ra = OpTypeRuntimeArray %f32
%sbt = OpTypeStruct %ra
%psb = OpTypePointer Uniform %sbt
%pf = OpTypePointer Uniform %f32
%sb = OpVariable %psb Uniform
%pin = OpTypePointer Input %v4
%pout = OpTypePointer Output %v4
%i0 = OpVariable %pin Input
%i1 = OpVariable %pin Input
%o0 = OpVariable %pout Output
%c0 = OpConstant %int 0
%main = OpFunction %void None %fn
%l = OpLabel
%a = OpLoad %v4 %i0
%b = OpLoad %v4 %i1
%p = OpAccessChain %pf %sb %c0 %c0
%s = OpLoad %f32 %p
%ab = OpFAdd %v4 %a %b
%v = OpVectorTimesScalar %v4 %ab %s
OpStore %o0 %v
OpReturn
OpFunctionEnd
'''

# A passthrough geometry shader as Ryujinx emulates it: the viewport mask output set to all, then a primitive emitted only when it is not 0
GS = '''OpCapability Geometry
OpCapability ShaderViewportMaskNV
OpExtension "SPV_NV_viewport_array2"
OpMemoryModel Logical GLSL450
OpEntryPoint Geometry %main "main" %vm
OpExecutionMode %main Triangles
OpExecutionMode %main Invocations 1
OpExecutionMode %main OutputTriangleStrip
OpExecutionMode %main OutputVertices 3
OpDecorate %vm BuiltIn ViewportMaskNV
%void = OpTypeVoid
%fn = OpTypeFunction %void
%int = OpTypeInt 32 1
%u32 = OpTypeInt 32 0
%bool = OpTypeBool
%u1 = OpConstant %u32 1
%arr = OpTypeArray %int %u1
%parr = OpTypePointer Output %arr
%pi = OpTypePointer Output %int
%vm = OpVariable %parr Output
%c0 = OpConstant %int 0
%m1 = OpConstant %int -1
%main = OpFunction %void None %fn
%l = OpLabel
%p = OpAccessChain %pi %vm %c0
OpStore %p %m1
%v = OpLoad %int %p
%z = OpIEqual %bool %v %c0
OpSelectionMerge %end None
OpBranchConditional %z %end %emit
%emit = OpLabel
OpEmitVertex
OpBranch %end
%end = OpLabel
OpReturn
OpFunctionEnd
'''


@unittest.skipUnless(shutil.which('spirv-as') and shutil.which('spirv-val'), 'SPIRV-Tools not installed')
class Rewrites(unittest.TestCase):
    def module(self, text):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / 'a.spvasm').write_text(text)
            subprocess.run(['spirv-as', '--target-env', 'spv1.3', str(Path(d) / 'a.spvasm'), '-o', str(Path(d) / 'a.spv')], check=True)
            return spv.Module((Path(d) / "a.spv").read_bytes())   # SPIR-V 1.3, as the translator writes

    def valid(self, m):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / 'b.spv'; p.write_bytes(m.data())
            r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(p)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            return subprocess.run(['spirv-dis', str(p)], capture_output=True, text=True).stdout if shutil.which('spirv-dis') else ''

    def test_vertex_rewrites(self):
        m = self.module(VS)
        self.assertEqual(spv.strip_unused_caps(m), ['Float64'])
        self.assertEqual(spv.init_outputs(m, {1, 3}), [3])   # location 1 exists, 3 is added
        self.assertTrue(spv.gl_depth(m, 0))
        text = self.valid(m)
        if text:
            self.assertIn('Fma', text)
            self.assertIn('SpecId 0', text)
            self.assertIn('Location 3', text)
            self.assertNotIn('Float64', text)

    def test_precise(self):
        text = VS.replace('OpMemoryModel', '%glsl = OpExtInstImport "GLSL.std.450"\nOpMemoryModel').replace('OpStore %p %x', '%y = OpExtInst %v4 %glsl FMax %x %x\nOpStore %p %y')
        m = self.module(text)
        self.assertEqual(spv.precise_minmax(m), 1)
        out = self.valid(m)
        if out: self.assertIn('NMax', out)

    def test_fragment_variants(self):
        m = self.module(FS)
        self.assertEqual(spv.int_outputs(m), {1})
        self.assertEqual(spv.const_written_outputs(m), set())
        self.assertTrue(spv.alpha_test(m, 1))
        text = self.valid(m)
        if text: self.assertIn('SpecId 1', text); self.assertIn('OpKill', text)
        u = spv.Module(m.data()); self.assertEqual(spv.retype_outputs(u, {0, 1}), [0, 1])
        text = self.valid(u)
        if text: self.assertNotIn('OpVariable %_ptr_Output_v4float', text)
        mx = spv.Module(m.data()); self.assertEqual(spv.retype_outputs(mx, {1}), [1])
        text = self.valid(mx)
        if text: self.assertIn('OpVariable %_ptr_Output_v4uint', text); self.assertIn('OpVariable %_ptr_Output_v4float', text)
        d = spv.Module(m.data()); self.assertTrue(spv.dual_source(d))
        text = self.valid(d)
        if text: self.assertIn('Index 1', text)
        self.assertEqual(spv.const_written_outputs(self.module(FS.replace('OpStore %p %bits', 'OpStore %p %f1').replace('OpStore %p %back', ''))), {0, 1})

    def test_per_primitive_inputs(self):
        # a pixel shader of a mesh program: input 0 per primitive; its storage buffer (Uniform + BufferBlock in SPIR-V 1.3) becomes StorageBuffer + Block in 1.4
        m = self.module(MESH_FS)
        self.assertEqual(spv.per_primitive_inputs(m, {0, 5}), [0])
        self.assertEqual(m.header[1], 0x10400)
        self.assertIn(spv.CAP_MESH_SHADING, m.caps)
        self.assertEqual(m.exts, ['SPV_EXT_mesh_shader'])
        text = self.valid(m)
        if text:
            self.assertIn('PerPrimitiveEXT', text); self.assertIn('StorageBuffer', text); self.assertNotIn('BufferBlock', text)
        self.assertEqual(spv.per_primitive_inputs(self.module(MESH_FS), {3}), [])   # no such input: unchanged

    def test_viewport_mask_private(self):
        m = self.module(GS)
        self.assertTrue(spv.private_viewport_mask(m))
        text = self.valid(m)
        self.assertNotIn(5255, m.caps)
        self.assertEqual(m.exts, [])
        if text: self.assertIn('Private', text); self.assertNotIn('ViewportMaskNV', text)


class Dominance(unittest.TestCase):
    def test_same_as_set_fixed_point(self):
        """promote_locals' dominance test (linear memory) answers as the set fixed point it replaced, on random graphs with unreachable cycles, self loops and
        branches back to the entry"""
        import random
        def sets(blks, preds):
            dom = {b: set(blks) for b in blks}; dom[blks[0]] = {blks[0]}
            chg = True
            while chg:
                chg = False
                for b in blks[1:]:
                    d = ({b} | set.intersection(*(dom[p] for p in preds[b]))) if preds[b] else {b}
                    if d != dom[b]: dom[b] = d; chg = True
            return dom
        rnd = random.Random(1)
        for _ in range(3000):
            n = rnd.randint(1, 10); blks = rnd.sample(range(100, 200), n)
            preds = {b: set() for b in blks}
            for _ in range(rnd.randint(0, 3 * n)): preds[rnd.choice(blks)].add(rnd.choice(blks))
            d, f = sets(blks, preds), spv._dominance(blks, preds)
            for a in blks:
                for b in blks: self.assertEqual(a in d[b], f(a, b), (blks, preds, a, b))


if __name__ == '__main__':
    unittest.main()
