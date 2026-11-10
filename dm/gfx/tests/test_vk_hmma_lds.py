"""The Vulkan library's lowering of the translator's HMMA.16816 helper at its call sites (vk_android_spv.py hmma_lds): the rewrite of a compute shader whose helper has
the translator's shape (straight-line GLSL written as HelperFunctionManager.GenerateHmma16816F16 emits it, compiled with glslang), the gathers shared by calls with the same
fragments, spirv-val of the result, a helper of another shape left alone, and on this Mac's GPU (spirv-cross to MSL) the lowered module bit for bit against the translator's
body on four dependent calls, and against the CPU reference of PTX mma.m16n8k16 (test_dmsh_hmma.py's runner) on one."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parents[0] / 'tools'
sys.path.insert(0, str(TOOLS)); sys.path.insert(0, str(HERE))
import vk_android_spv as spv  # noqa: E402

SPIRV_CROSS = TOOLS / 'spirv_cross_bin' / 'spirv-cross'


def helper_glsl():
    """The translator's helper as straight-line GLSL: the stores of the fragments into the lane's 256-word block, a subgroup barrier, D computed in f32, a barrier"""
    L = ['int dmsh_hmma16816_f16(int lane, int bse, int a0, int a1, int a2, int a3, int b0, int b1, int c0, int c1, out int d1)', '{',
         '    int g = int(uint(lane) >> 2u); int t = lane & 3; int g8 = g + 8;',
         '    scr[bse + (g << 3) + t] = uint(a0); scr[bse + (g8 << 3) + t] = uint(a1);',
         '    scr[bse + (g << 3) + t + 4] = uint(a2); scr[bse + (g8 << 3) + t + 4] = uint(a3);',
         '    scr[bse + 128 + (g << 3) + t] = uint(b0); scr[bse + 128 + (g << 3) + t + 4] = uint(b1);',
         '    scr[bse + 192 + (g << 2) + t] = uint(c0); scr[bse + 192 + (g8 << 2) + t] = uint(c1);',
         '    subgroupMemoryBarrierShared(); subgroupBarrier();']
    for q, (row, cw) in enumerate((('g', 'c0'), ('g8', 'c1'))):
        L.append(f'    float acc{q}0 = unpackHalf2x16(uint({cw})).x; float acc{q}1 = unpackHalf2x16(uint({cw})).y;')
        for j in range(2):
            for kw in range(8):
                a = f'scr[bse + ({row} << 3) + {kw}]'; b = f'scr[bse + 128 + (((t << 1) + {j}) << 3) + {kw}]'
                n = f'{q}{j}_{kw}'
                L.append(f'    uint a{n} = {a}; uint b{n} = {b}; acc{q}{j} = fma(unpackHalf2x16(a{n}).x, unpackHalf2x16(b{n}).x, acc{q}{j}); '
                         f'acc{q}{j} = fma(unpackHalf2x16(a{n}).y, unpackHalf2x16(b{n}).y, acc{q}{j});')
    L += ['    uint r0 = packHalf2x16(vec2(acc00, acc01)); uint r1 = packHalf2x16(vec2(acc10, acc11));',
          '    subgroupMemoryBarrierShared(); subgroupBarrier();', '    d1 = int(r1);', '    return int(r0);', '}']
    return '\n'.join(L)


HEAD = '''#version 450
#extension GL_KHR_shader_subgroup_basic : require
layout(local_size_x = 128) in;
layout(set = 0, binding = 0, std430) readonly buffer In { uint w[]; } src;
layout(set = 0, binding = 1, std430) writeonly buffer Out { uint w[]; } dst;
shared uint scr[1024];
'''
# one call, as test_dmsh_hmma.py's runner reads it: 8 words in (A, B, C), 2 out
ONE = HEAD + helper_glsl() + '''
void main()
{
    uint gid = gl_GlobalInvocationID.x; int l = int(gl_LocalInvocationID.x & 31u), blk = int(gl_LocalInvocationID.x >> 5) * 256;
    int a[8]; for (int i = 0; i < 8; i++) a[i] = int(src.w[gid * 8u + uint(i)]);
    int d1;
    int d0 = dmsh_hmma16816_f16(l, blk, a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], d1);
    dst.w[gid * 2u] = uint(d0); dst.w[gid * 2u + 1u] = uint(d1);
}
'''
# four calls in one block, as agl's network makes them: two A tiles by two B tiles, the last one accumulating on the first's D; a fifth after a store to A's register
FOUR = HEAD + helper_glsl() + '''
void main()
{
    uint gid = gl_GlobalInvocationID.x; int l = int(gl_LocalInvocationID.x & 31u), blk = int(gl_LocalInvocationID.x >> 5) * 256;
    int w[16]; for (int i = 0; i < 16; i++) w[i] = int(src.w[gid * 16u + uint(i)]);
    int a0 = w[0], a1 = w[1], a2 = w[2], a3 = w[3], e0 = w[4], e1 = w[5], e2 = w[6], e3 = w[7], b0 = w[8], b1 = w[9], f0 = w[10], f1 = w[11], c0 = w[12], c1 = w[13];
    int d[10];
    d[0] = dmsh_hmma16816_f16(l, blk, a0, a1, a2, a3, b0, b1, c0, c1, d[1]);
    d[2] = dmsh_hmma16816_f16(l, blk, a0, a1, a2, a3, f0, f1, c0, c1, d[3]);
    d[4] = dmsh_hmma16816_f16(l, blk, e0, e1, e2, e3, b0, b1, 0, 0, d[5]);
    d[6] = dmsh_hmma16816_f16(l, blk, e0, e1, e2, e3, f0, f1, d[0], d[1], d[7]);
    a0 = w[14];
    d[8] = dmsh_hmma16816_f16(l, blk, a0, a1, a2, a3, b0, b1, c0, c1, d[9]);
    for (int i = 0; i < 10; i++) dst.w[gid * 10u + uint(i)] = uint(d[i]);
}
'''

RUNNER = r'''#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
static uint16_t f2h(float f) { __fp16 v = (__fp16)f; uint16_t h; memcpy(&h, &v, 2); return h; }
int main(int argc, char **argv) {
    @autoreleasepool {
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice(); NSError *e = nil;
        if (!dev) { printf("no Metal device\n"); return 2; }
        NSData *lib_data = [NSData dataWithContentsOfFile:@(argv[1])];
        id<MTLLibrary> lib = [dev newLibraryWithData:dispatch_data_create(lib_data.bytes, lib_data.length, nil, DISPATCH_DATA_DESTRUCTOR_DEFAULT) error:&e];
        if (!lib) { printf("library: %s\n", e.localizedDescription.UTF8String); return 1; }
        id<MTLComputePipelineState> ps = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"check"] error:&e];
        if (!ps) { printf("pipeline: %s\n", e.localizedDescription.UTF8String); return 1; }
        const int groups = 32, tg = 128, n = groups * tg;
        id<MTLBuffer> in = [dev newBufferWithLength:n * 64 options:MTLResourceStorageModeShared], out = [dev newBufferWithLength:n * 40 options:MTLResourceStorageModeShared];
        uint32_t *w = in.contents; srand(5);
        for (int i = 0; i < n * 16; i++) w[i] = f2h((rand() % 2001 - 1000) / 1000.0f) | (uint32_t)f2h((rand() % 2001 - 1000) / 1000.0f) << 16;
        id<MTLCommandQueue> q = [dev newCommandQueue]; id<MTLCommandBuffer> cb = [q commandBuffer]; id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
        [ce setComputePipelineState:ps]; [ce setBuffer:in offset:0 atIndex:0]; [ce setBuffer:out offset:0 atIndex:1];
        [ce dispatchThreadgroups:MTLSizeMake(groups, 1, 1) threadsPerThreadgroup:MTLSizeMake(tg, 1, 1)]; [ce endEncoding]; [cb commit]; [cb waitUntilCompleted];
        fwrite(out.contents, 40, n, stdout);
        return 0;
    }
}
'''


def compile_module(d, src, name='h'):
    (d / f'{name}.comp').write_text(src)
    subprocess.run(['glslangValidator', '-V', '--target-env', 'vulkan1.1', str(d / f'{name}.comp'), '-o', str(d / f'{name}.spv')], check=True, capture_output=True)
    m = spv.Module((d / f'{name}.spv').read_bytes())
    for x in m.ins:   # glslang's mangled name → the translator's
        if x[0] & 0xffff == spv.OP_NAME and spv.string_at(x, 2)[0].startswith(spv.HMMA_NAME + '('):
            s = spv.lit_string(spv.HMMA_NAME); x[:] = [(2 + len(s)) << 16 | spv.OP_NAME, x[1], *s]
    m.index()
    return m


def ops(m, op): return [x for x in m.ins if x[0] & 0xffff == op]


@unittest.skipUnless(shutil.which('glslangValidator') and shutil.which('spirv-val'), 'needs glslang and SPIRV-Tools')
class HmmaLdsRewrite(unittest.TestCase):
    def test_lowered_and_valid(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); m = compile_module(d, FOUR)
            self.assertEqual(spv.hmma_lds(m, 4), 5)   # a limit above the 3 tiles this block has live, so every tile is gathered once (HMMA_KEEP: test_bounded_cache)
            self.assertNotIn(spv.HMMA_NAME, spv.functions_named(m))
            self.assertEqual(len(ops(m, spv.OP_FUNCTION_CALL)), 0)
            # gathers: A0 B0 (1st), B1 (2nd), A1 (3rd), none (4th), A0' (5th: a0 was stored again): 4 gathers, two barriers each
            self.assertEqual(len(ops(m, spv.OP_CONTROL_BARRIER)), 8)
            uv4 = m.find_type(spv.OP_TYPE_VECTOR, m.find_type(spv.OP_TYPE_INT, 32, 0), 4)
            self.assertEqual(sum(1 for x in ops(m, spv.OP_LOAD) if x[1] == uv4), 5 * 4)   # A0 + B0, B1, A1, A0': 4 uvec4 each
            self.assertEqual(sum(1 for x in ops(m, spv.OP_EXT_INST) if x[4] == spv.GLSL_FMA), 5 * 64)
            (d / 'r.spv').write_bytes(m.data())
            r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'r.spv')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(spv.hmma_lds(m), 0)   # no helper left

    def test_bounded_cache(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); uv4 = None
            for keep, loads in ((0, 5 * 8), (1, 8 + 4 + 8 + 4 + 8), (2, 8 + 4 + 4 + 8)):
                # keep 0: every call gathers both tiles; keep 1: the tile needed soonest stays (A0, then B1, then A1); keep 2: B0 is dropped for A1 and B1 and gathered again with A0'
                m = compile_module(d, FOUR)
                self.assertEqual(spv.hmma_lds(m, keep), 5)
                uv4 = m.find_type(spv.OP_TYPE_VECTOR, m.find_type(spv.OP_TYPE_INT, 32, 0), 4)
                self.assertEqual(sum(1 for x in ops(m, spv.OP_LOAD) if x[1] == uv4), loads, f'keep {keep}')
                self.assertEqual(sum(1 for x in ops(m, spv.OP_EXT_INST) if x[4] == spv.GLSL_FMA), 5 * 64)
                (d / 'r.spv').write_bytes(m.data())
                r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'r.spv')], capture_output=True, text=True)
                self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_other_shape_left_alone(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); m = compile_module(d, ONE)
            a, e = m.function_range(spv.functions_named(m)[spv.HMMA_NAME])
            k = next(k for k in range(a, e) if m.ins[k][0] & 0xffff == spv.OP_CONTROL_BARRIER)
            del m.ins[k]; m.index()   # one barrier fewer
            before = m.data()
            self.assertEqual(spv.hmma_lds(m), 0)
            self.assertEqual(m.data(), before)


@unittest.skipUnless(shutil.which('glslangValidator') and shutil.which('xcrun') and shutil.which('clang') and SPIRV_CROSS.exists(), 'needs glslang, Xcode and spirv-cross')
class HmmaLdsOnGpu(unittest.TestCase):
    def metallib(self, d, name, data):
        (d / f'{name}.spv').write_bytes(data)
        subprocess.run([str(SPIRV_CROSS), str(d / f'{name}.spv'), '--msl', '--msl-version', '20300', '--msl-decoration-binding', '--rename-entry-point', 'main', 'check', 'comp',
                        '--output', str(d / f'{name}.metal')], check=True)
        subprocess.run(['xcrun', '-sdk', 'macosx', 'metal', '-c', '-O1', str(d / f'{name}.metal'), '-o', str(d / f'{name}.air')], check=True)
        subprocess.run(['xcrun', '-sdk', 'macosx', 'metallib', str(d / f'{name}.air'), '-o', str(d / f'{name}.metallib')], check=True)
        return d / f'{name}.metallib'

    def tool(self, d, name, src):
        if not (d / name).exists():
            (d / f'{name}.m').write_text(src)
            subprocess.run(['clang', '-fobjc-arc', '-O1', str(d / f'{name}.m'), '-framework', 'Metal', '-framework', 'Foundation', '-o', str(d / name)], check=True)
        return d / name

    def test_same_bits_as_the_translator_body(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); m = compile_module(d, FOUR)
            run = self.tool(d, 'dump', RUNNER)
            ref = subprocess.run([str(run), str(self.metallib(d, 'translator', m.data()))], capture_output=True)
            if ref.returncode == 2: self.skipTest(ref.stdout.decode().strip())
            self.assertEqual(ref.returncode, 0, ref.stdout[:200])
            src = m.data()
            for keep in (None, 0, 1):   # the default, and caches that gather tiles again
                m = spv.Module(src)
                self.assertEqual(spv.hmma_lds(m, keep), 5)
                out = subprocess.run([str(run), str(self.metallib(d, f'lds{keep}', m.data()))], capture_output=True)
                self.assertEqual(out.returncode, 0, out.stdout[:200])
                diff = sum(1 for i in range(0, len(ref.stdout), 4) if ref.stdout[i:i + 4] != out.stdout[i:i + 4])
                self.assertEqual(diff, 0, f'keep {keep}: {diff} of {len(ref.stdout) // 4} words differ')

    def test_matches_reference(self):
        import test_dmsh_hmma
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); m = compile_module(d, ONE)
            self.assertEqual(spv.hmma_lds(m), 1)
            r = subprocess.run([str(self.tool(d, 'run', test_dmsh_hmma.RUNNER)), str(self.metallib(d, 'lds', m.data()))], capture_output=True, text=True)
            if r.returncode == 2: self.skipTest(r.stdout.strip())
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == '__main__':
    unittest.main()
