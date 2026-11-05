"""The Vulkan library's compute modules with their subgroup shuffles through workgroup memory (vk_android_spv.py shuffle_lds, DMSH_VK_MOD_SHUFFLE_LDS): the rewrite of a
compute shader with the translator's shuffle patterns (LDSM's four reads of one address in the lane's 32-lane segment, MOVM's two, shuffles in a branch, in a loop and
around a function call), its structure (one store a value per block, the barriers), spirv-val of the result, what it leaves alone, and on this Mac's GPU (spirv-cross to
MSL, 32-lane SIMD-groups) the rewritten module's output against the original's, bit for bit."""
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parents[0] / 'tools'
sys.path.insert(0, str(TOOLS))
import vk_android_spv as spv  # noqa: E402

SPIRV_CROSS = TOOLS / 'spirv_cross_bin' / 'spirv-cross'

SHADER = '''#version 450
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_shuffle : require
layout(local_size_x = 128) in;
layout(set = 0, binding = 0, std430) readonly buffer In { uint w[]; } src;
layout(set = 0, binding = 1, std430) writeonly buffer Out { uint w[]; } dst;
shared uint other[256];

uint seg() { return gl_SubgroupInvocationID & ~31u; }

uint movm(uint v)   // MOVM.16.MT88: two reads of one value
{
    uint l = gl_SubgroupInvocationID & 31u, r = l >> 2, t = l & 3u, h0, h1;
    h0 = bitfieldExtract(subgroupShuffle(v, seg() | ((t * 2u) * 4u + (r >> 1))), int((r & 1u) * 16u), 16);
    h1 = bitfieldExtract(subgroupShuffle(v, seg() | ((t * 2u + 1u) * 4u + (r >> 1))), int((r & 1u) * 16u), 16);
    return h0 | (h1 << 16);
}

void main()
{
    uint gid = gl_GlobalInvocationID.x, l = gl_SubgroupInvocationID & 31u, g = l >> 2;
    uint a = src.w[gid * 4u], b = src.w[gid * 4u + 1u], c = src.w[gid * 4u + 2u];
    float f = uintBitsToFloat(src.w[gid * 4u + 3u]);
    other[gl_LocalInvocationIndex] = a ^ c;
    barrier();
    uint acc = 0u;
    for (uint m = 0u; m < 4u; m++) acc += subgroupShuffle(a, seg() | (g + 8u * m)) * (m + 1u);   // LDSM.x4: one address, four rows
    uint ld0 = subgroupShuffle(a, seg() | g), ld1 = subgroupShuffle(a, seg() | (g + 8u)), ld2 = subgroupShuffle(a, seg() | (g + 16u)), ld3 = subgroupShuffle(a, seg() | (g + 24u));
    uint x = subgroupShuffle(b, seg() | (31u - l)) ^ subgroupShuffle(c, seg() | ((l + 5u) & 31u)) ^ subgroupShuffle(b, seg() | (l ^ 3u));
    float y = subgroupShuffle(f, seg() | ((l * 7u) & 31u));
    uint z = movm(b) + movm(c);
    if ((a & 1u) == 0u) x += subgroupShuffle(c, seg() | l);
    uint w = ld0 ^ subgroupShuffle(a, seg() | ((l + 1u) & 31u));
    for (uint i = 0u; i < (gl_WorkGroupID.x & 3u) + 1u; i++) w += subgroupShuffle(w, seg() | ((l + i) & 31u));   // (a uniform trip count: a lane that left reads as undefined)
    dst.w[gid * 4u] = acc ^ ld1 ^ ld2 ^ ld3; dst.w[gid * 4u + 1u] = x + other[(gl_LocalInvocationIndex + 1u) & 127u];
    dst.w[gid * 4u + 2u] = floatBitsToUint(y) ^ z; dst.w[gid * 4u + 3u] = w;
}
'''

RUNNER = r'''#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
int main(int argc, char **argv) {
    @autoreleasepool {
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice(); NSError *e = nil;
        if (!dev) { printf("no Metal device\n"); return 2; }
        NSData *lib_data = [NSData dataWithContentsOfFile:@(argv[1])];
        id<MTLLibrary> lib = [dev newLibraryWithData:dispatch_data_create(lib_data.bytes, lib_data.length, nil, DISPATCH_DATA_DESTRUCTOR_DEFAULT) error:&e];
        if (!lib) { printf("library: %s\n", e.localizedDescription.UTF8String); return 1; }
        id<MTLComputePipelineState> ps = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"check"] error:&e];
        if (!ps) { printf("pipeline: %s\n", e.localizedDescription.UTF8String); return 1; }
        const int groups = 64, tg = 128, n = groups * tg;
        id<MTLBuffer> in = [dev newBufferWithLength:n * 16 options:MTLResourceStorageModeShared], out = [dev newBufferWithLength:n * 16 options:MTLResourceStorageModeShared];
        uint32_t *w = in.contents; srand(7);
        for (int i = 0; i < n * 4; i++) w[i] = (uint32_t)rand() * 2654435761u ^ (uint32_t)rand();
        id<MTLCommandQueue> q = [dev newCommandQueue]; id<MTLCommandBuffer> cb = [q commandBuffer]; id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
        [ce setComputePipelineState:ps]; [ce setBuffer:in offset:0 atIndex:0]; [ce setBuffer:out offset:0 atIndex:1];
        [ce dispatchThreadgroups:MTLSizeMake(groups, 1, 1) threadsPerThreadgroup:MTLSizeMake(tg, 1, 1)]; [ce endEncoding]; [cb commit]; [cb waitUntilCompleted];
        fwrite(out.contents, 16, n, stdout);
        return 0;
    }
}
'''


def compile_module(d, src=SHADER):
    (d / 's.comp').write_text(src)
    subprocess.run(['glslangValidator', '-V', '--target-env', 'vulkan1.1', str(d / 's.comp'), '-o', str(d / 's.spv')], check=True, capture_output=True)
    return spv.Module((d / 's.spv').read_bytes())


def ops(m, op): return [x for x in m.ins if x[0] & 0xffff == op]


@unittest.skipUnless(shutil.which('glslangValidator') and shutil.which('spirv-val'), 'needs glslang and SPIRV-Tools')
class ShuffleLdsRewrite(unittest.TestCase):
    def test_rewritten_and_valid(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); m = compile_module(d)
            n = len(ops(m, spv.OP_GROUP_NON_UNIFORM_SHUFFLE)); before = spv.workgroup_bytes(m)
            self.assertEqual(spv.shuffle_lds(m, 32768), 8 * 128)   # two rows of a 128-invocation workgroup
            self.assertEqual(ops(m, spv.OP_GROUP_NON_UNIFORM_SHUFFLE), [])
            self.assertEqual(spv.workgroup_bytes(m), before + 8 * 128)
            xs = next(v for v, nm in m.names.items() if nm == 'dm_shuffle_rows')
            stores = [x for x in ops(m, spv.OP_STORE) if any(y[2] == x[1] and y[3] == xs for y in ops(m, spv.OP_ACCESS_CHAIN))]
            loads = [x for x in ops(m, spv.OP_LOAD) if any(y[2] == x[3] and y[3] == xs for y in ops(m, spv.OP_ACCESS_CHAIN))]
            self.assertEqual(len(loads), n)                  # every shuffle reads once
            self.assertEqual((n, len(stores)), (14, 9))     # LDSM's four reads of a share one store, MOVM's two of v one, the second read of b its first's
            (d / 'r.spv').write_bytes(m.data())
            r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'r.spv')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            for b in (spv.BUILTIN_SUBGROUP_ID, spv.BUILTIN_SUBGROUP_SIZE):
                self.assertIn(m.builtin_var(spv.SC_INPUT, b)[0], m.interface())

    def test_one_store_for_ldsm(self):
        src = SHADER[:SHADER.index('shared uint other')] + '''void main()
{
    uint l = gl_SubgroupInvocationID & 31u, g = l >> 2, a = src.w[gl_GlobalInvocationID.x], s = gl_SubgroupInvocationID & ~31u;
    dst.w[gl_GlobalInvocationID.x] = subgroupShuffle(a, s | g) ^ subgroupShuffle(a, s | (g + 8u)) ^ subgroupShuffle(a, s | (g + 16u)) ^ subgroupShuffle(a, s | (g + 24u));
}
'''
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); m = compile_module(d, src)
            self.assertTrue(spv.shuffle_lds(m, 32768))
            barriers = ops(m, spv.OP_CONTROL_BARRIER)
            self.assertEqual(len(barriers), 2)   # the store's, and the trailing one at the block's end
            xs = next(v for v, nm in m.names.items() if nm == 'dm_shuffle_rows')
            self.assertEqual(sum(1 for x in ops(m, spv.OP_STORE) if any(y[2] == x[1] and y[3] == xs for y in ops(m, spv.OP_ACCESS_CHAIN))), 1)

    def test_left_alone(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); m = compile_module(d)
            before = m.data()
            self.assertEqual(spv.shuffle_lds(m, spv.workgroup_bytes(m) + 8 * 128 - 4), 0)   # past the shared memory limit with the rows
            self.assertEqual(m.data(), before)
            m = compile_module(d, SHADER.replace('subgroupShuffle(', 'subgroupBroadcastFirst(').replace(', seg() |', ' + 0u *').replace('#extension GL_KHR_shader_subgroup_shuffle : require',
                                                                                                                                         '#extension GL_KHR_shader_subgroup_ballot : require'))
            before = m.data()
            self.assertEqual(spv.shuffle_lds(m, 32768), 0)   # no shuffle
            self.assertEqual(m.data(), before)


@unittest.skipUnless(shutil.which('glslangValidator') and shutil.which('xcrun') and shutil.which('clang') and SPIRV_CROSS.exists(), 'needs glslang, Xcode and spirv-cross')
class ShuffleLdsOnGpu(unittest.TestCase):
    def run_module(self, d, name, data):
        (d / f'{name}.spv').write_bytes(data)
        subprocess.run([str(SPIRV_CROSS), str(d / f'{name}.spv'), '--msl', '--msl-version', '20300', '--msl-decoration-binding', '--rename-entry-point', 'main', 'check', 'comp',
                        '--output', str(d / f'{name}.metal')], check=True)
        subprocess.run(['xcrun', '-sdk', 'macosx', 'metal', '-c', '-O1', str(d / f'{name}.metal'), '-o', str(d / f'{name}.air')], check=True)
        subprocess.run(['xcrun', '-sdk', 'macosx', 'metallib', str(d / f'{name}.air'), '-o', str(d / f'{name}.metallib')], check=True)
        if not (d / 'run').exists():
            (d / 'run.m').write_text(RUNNER)
            subprocess.run(['clang', '-fobjc-arc', '-O1', str(d / 'run.m'), '-framework', 'Metal', '-framework', 'Foundation', '-o', str(d / 'run')], check=True)
        r = subprocess.run([str(d / 'run'), str(d / f'{name}.metallib')], capture_output=True)
        if r.returncode == 2: self.skipTest(r.stdout.decode().strip())
        self.assertEqual(r.returncode, 0, r.stdout[:200])
        return r.stdout

    def test_same_output_as_the_instruction(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); m = compile_module(d)
            ref = self.run_module(d, 'native', m.data())
            self.assertTrue(spv.shuffle_lds(m, 32768))
            out = self.run_module(d, 'lds', m.data())
            self.assertEqual(len(out), len(ref))
            diff = sum(1 for i in range(0, len(ref), 4) if ref[i:i + 4] != out[i:i + 4])
            self.assertEqual(diff, 0, f'{diff} of {len(ref) // 4} words differ')
            self.assertGreater(len(set(struct.unpack(f'<{len(ref) // 4}I', ref))), 1000)   # (the outputs carry data)


if __name__ == '__main__':
    unittest.main()
