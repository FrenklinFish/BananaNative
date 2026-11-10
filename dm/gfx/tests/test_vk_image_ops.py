"""Two image-instruction rewrites of the Vulkan library. (1) Non-constant Offset image operands on image samples moved into the coordinates (vk_android_spv.py lower_sample_offsets, VUID-RuntimeSpirv-Offset-10213): a pixel
shader with an implicit-lod sample, an explicit-lod sample of a 2D array and a depth-compare sample whose offsets come from an input, assembled with spirv-as; before, spirv-val
refuses it; after, it passes, no Offset operand is left, and each sample's coordinates gain float(offset) / float(level size). (2) A size query's component past the image's dimensions
(vk_android_spv.py txq_extra_components): the z of a 2D texture's size becomes the constant 1, the module valid, the components in range untouched."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[0] / 'tools'))
import vk_android_spv as spv  # noqa: E402

ASM = '''
               OpCapability Shader
               OpCapability ImageGatherExtended
               OpMemoryModel Logical GLSL450
               OpEntryPoint Fragment %main "main" %uv %o %out %t2 %ta %ts
               OpExecutionMode %main OriginUpperLeft
               OpDecorate %uv Location 0
               OpDecorate %o Flat
               OpDecorate %o Location 1
               OpDecorate %out Location 0
               OpDecorate %t2 DescriptorSet 0
               OpDecorate %t2 Binding 0
               OpDecorate %ta DescriptorSet 0
               OpDecorate %ta Binding 1
               OpDecorate %ts DescriptorSet 0
               OpDecorate %ts Binding 2
       %void = OpTypeVoid
         %fn = OpTypeFunction %void
      %float = OpTypeFloat 32
        %int = OpTypeInt 32 1
      %v2f = OpTypeVector %float 2
      %v3f = OpTypeVector %float 3
      %v4f = OpTypeVector %float 4
      %v2i = OpTypeVector %int 2
       %img = OpTypeImage %float 2D 0 0 0 1 Unknown
      %imga = OpTypeImage %float 2D 0 1 0 1 Unknown
      %imgs = OpTypeImage %float 2D 1 0 0 1 Unknown
        %si = OpTypeSampledImage %img
       %sia = OpTypeSampledImage %imga
       %sis = OpTypeSampledImage %imgs
      %psi = OpTypePointer UniformConstant %si
     %psia = OpTypePointer UniformConstant %sia
     %psis = OpTypePointer UniformConstant %sis
      %pv2f = OpTypePointer Input %v2f
      %pv2i = OpTypePointer Input %v2i
      %pv4f = OpTypePointer Output %v4f
       %f2 = OpConstant %float 2
      %half = OpConstant %float 0.5
        %t2 = OpVariable %psi UniformConstant
        %ta = OpVariable %psia UniformConstant
        %ts = OpVariable %psis UniformConstant
        %uv = OpVariable %pv2f Input
         %o = OpVariable %pv2i Input
       %out = OpVariable %pv4f Output
       %main = OpFunction %void None %fn
          %l = OpLabel
          %c = OpLoad %v2f %uv
        %off = OpLoad %v2i %o
          %s = OpLoad %si %t2
          %a = OpImageSampleImplicitLod %v4f %s %c Bias|Offset %half %off
         %sa = OpLoad %sia %ta
         %c3 = OpCompositeConstruct %v3f %c %f2
          %b = OpImageSampleExplicitLod %v4f %sa %c3 Lod|Offset %f2 %off
         %ss = OpLoad %sis %ts
          %d = OpImageSampleDrefImplicitLod %float %ss %c %half Offset %off
         %ab = OpFAdd %v4f %a %b
         %r = OpVectorTimesScalar %v4f %ab %d
               OpStore %out %r
               OpReturn
               OpFunctionEnd
'''


@unittest.skipUnless(shutil.which('spirv-as') and shutil.which('spirv-val'), 'needs SPIRV-Tools')
class SampleOffsets(unittest.TestCase):
    def test_lowered_and_valid(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); (d / 'a.spvasm').write_text(ASM)
            subprocess.run(['spirv-as', '--target-env', 'vulkan1.3', str(d / 'a.spvasm'), '-o', str(d / 'a.spv')], check=True)
            r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'a.spv')], capture_output=True, text=True)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn('Offset-10213', r.stdout + r.stderr)
            m = spv.Module((d / 'a.spv').read_bytes())
            self.assertTrue(spv.lower_image_offsets(m))
            ops = [x[0] & 0xffff for x in m.ins]
            self.assertEqual(ops.count(105), 2)   # OpImageQueryLod: the two implicit-lod samples
            self.assertEqual(ops.count(103), 3)   # OpImageQuerySizeLod: one a sample
            self.assertEqual(ops.count(136), 6)   # OpFDiv: x and y of each
            for x in m.ins:
                op = x[0] & 0xffff
                if op in (87, 88, 89, 90) and len(x) > (5 if op < 89 else 6): self.assertFalse(x[5 if op < 89 else 6] & 0x10, 'an Offset operand is left')
            explicit = next(x for x in m.ins if x[0] & 0xffff == 88)
            self.assertEqual(explicit[5], 0x02)   # Lod stays, Offset goes
            (d / 'b.spv').write_bytes(m.data())
            r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'b.spv')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertFalse(spv.lower_image_offsets(spv.Module(m.data())))   # nothing left to do


TXQ = '''
               OpCapability Shader
               OpCapability ImageQuery
               OpMemoryModel Logical GLSL450
               OpEntryPoint Vertex %main "main" %out %t2
               OpDecorate %out Location 0
               OpDecorate %t2 DescriptorSet 0
               OpDecorate %t2 Binding 0
       %void = OpTypeVoid
         %fn = OpTypeFunction %void
      %float = OpTypeFloat 32
        %int = OpTypeInt 32 1
       %v2i = OpTypeVector %int 2
       %v3i = OpTypeVector %int 3
       %img = OpTypeImage %float 2D 0 0 0 1 Unknown
        %si = OpTypeSampledImage %img
      %psi = OpTypePointer UniformConstant %si
      %pv3i = OpTypePointer Output %v3i
        %c0 = OpConstant %int 0
        %t2 = OpVariable %psi UniformConstant
       %out = OpVariable %pv3i Output
       %main = OpFunction %void None %fn
          %l = OpLabel
          %s = OpLoad %si %t2
          %i = OpImage %img %s
         %sz = OpImageQuerySizeLod %v2i %i %c0
          %x = OpCompositeExtract %int %sz 0
          %y = OpCompositeExtract %int %sz 1
          %z = OpCompositeExtract %int %sz 2
          %r = OpCompositeConstruct %v3i %x %y %z
               OpStore %out %r
               OpReturn
               OpFunctionEnd
'''


@unittest.skipUnless(shutil.which('spirv-as') and shutil.which('spirv-val'), 'needs SPIRV-Tools')
class TxqComponents(unittest.TestCase):
    def test_past_the_dimensions(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); (d / 'a.spvasm').write_text(TXQ)
            subprocess.run(['spirv-as', '--target-env', 'vulkan1.3', str(d / 'a.spvasm'), '-o', str(d / 'a.spv')], check=True)
            r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'a.spv')], capture_output=True, text=True)
            self.assertIn('Vector access is out of bounds', r.stdout + r.stderr)
            m = spv.Module((d / 'a.spv').read_bytes())
            self.assertEqual(spv.txq_extra_components(m), 1)
            ex = [x for x in m.ins if x[0] & 0xffff == spv.OP_COMPOSITE_EXTRACT]
            self.assertEqual([x[4] for x in ex], [0, 1])   # x and y stay
            cp = next(x for x in m.ins if x[0] & 0xffff == spv.OP_COPY_OBJECT)
            self.assertEqual(m.consts[cp[3]][1], [1])
            (d / 'b.spv').write_bytes(m.data())
            r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'b.spv')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(spv.txq_extra_components(spv.Module(m.data())), 0)


if __name__ == '__main__':
    unittest.main()
