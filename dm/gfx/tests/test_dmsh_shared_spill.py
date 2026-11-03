#!/usr/bin/env python3
"""Shared memory past the host's limit (job shared_max, the Android build's DMSH_VK_SHARED_MAX; Ryujinx StructuredIr/SharedSpillInfo.cs): Mario Kart World 1.8.0's 168
compute programs declare 49152 bytes, the Adreno 840 allows 32768. The program's shared memory moves to a storage buffer under the sentinel slot 0xff at offset 2
(dmsh_abi.h DMSH_SHARED_SPILL_CB_OFFSET), one slice per workgroup at (linear workgroup index mod the bound slices) * the slice's words; barriers order uniform memory
too. Without shared_max (the Apple build) nothing changes. Needs DMSH_TEST_XLATE (the translator) like the other translator tests, and spirv-val / spirv-dis on PATH."""
import json, os, re, shutil, struct, subprocess, tempfile, unittest
from pathlib import Path


def inst(hi, lo): return struct.pack('<QQ', lo, hi)


EXIT = (0x000fea0003800000, 0x000000000000794d)
SUATOM = (0x002ea200001ee000, 0x00000c0c030c7593)       # SUATOM.ADD.U32 R12, [R3], R12 on image slot 1: keeps R12 alive
BAR = (0x000fec0000010000, 0x0000000000007b1d)          # BAR.SYNC 0
LDS = (0x000fe20000000800, 0x00000000030c7984)          # LDS R12, [R3]
ATOMS_ADD = (0x004e680000000000, 0x0007fc0cff0c738c)    # ATOMS.ADD.U32 R12, [RZ + 0x7fc], R12


ATOMS_CAS = (0x000e640001800001, 0x00000400ff01738d)   # ATOMS.CAS R1, [RZ + 4], R0, R1


def mov(rd, value): return inst(0x000fc60000000f00, value << 32 | rd << 16 | 0x7802)                    # MOV Rd, imm32
def sts(offset, rb): return inst(0x000fe80000000800, offset << 40 | rb << 32 | 0xff << 24 | 0x7388)    # STS.U32 [RZ + offset], Rb


def sts8(offset, rb): return inst(0x000fe80000000000, offset << 40 | rb << 32 | 0xff << 24 | 0x7388)   # STS.U8 [RZ + offset], Rb (a CAS loop in a helper function)


# STS past 32 KiB, a barrier, LDS, ATOMS: every kind of shared memory access, one of them beyond the device's limit
PROGRAM = mov(3, 0xA000) + sts(0xA000, 3) + inst(*BAR) + inst(*LDS) + inst(*ATOMS_ADD) + inst(*SUATOM) + inst(*EXIT)


@unittest.skipUnless(os.environ.get('DMSH_TEST_XLATE') and shutil.which('spirv-val') and shutil.which('spirv-dis'), 'set DMSH_TEST_XLATE; needs spirv-val and spirv-dis')
class SharedSpill(unittest.TestCase):
    def translate(self, shared, program=PROGRAM, **extra):
        with tempfile.TemporaryDirectory(prefix='shared_spill_') as d:
            work = Path(d); (work / 'k.bin').write_bytes(program)
            job = [dict(key='k', stage='cs', arch='sm86', code=str(work / 'k.bin'), tex={}, img={}, attr={}, fout={}, local=[64, 1, 1], debug=True, nvn2_cs=[0, 0, 3, 0x40, 0],
                        shared=shared, subgroup_size=64, ssbo_align=64, gs_passthrough=False, **extra)]
            (work / 'jobs.json').write_text(json.dumps(job))
            r = subprocess.run([os.environ['DMSH_TEST_XLATE'], str(work / 'jobs.json'), d], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            v = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(work / 'k.spv')], capture_output=True, text=True)
            self.assertEqual(v.returncode, 0, v.stdout + v.stderr)
            dis = subprocess.run(['spirv-dis', '--raw-id', str(work / 'k.spv')], capture_output=True, text=True, check=True).stdout
            return dis, json.loads((work / 'k.res.json').read_text())

    def test_over_the_limit_moves_to_a_storage_buffer(self):
        dis, res = self.translate(0xC000, shared_max=32768)
        self.assertNotRegex(dis, r'OpVariable %\d+ Workgroup')   # no workgroup memory left (no HMMA scratch in this program)
        spill = [b for b in res['sbufs'] if b['cb_slot'] == 0xff and b['cb_offset'] == 2]
        self.assertEqual(len(spill), 1, res['sbufs'])
        self.assertTrue(spill[0]['flags'] & 1, 'the spill buffer is written')
        self.assertTrue(any('moved to a storage buffer' in n and '49152' in n for n in res['notes']), res['notes'])
        self.assertIn('BuiltIn NumWorkgroups', dis)
        self.assertIn('BuiltIn WorkgroupId', dis)
        self.assertIn('OpArrayLength', dis)
        self.assertIn('OpUMod', dis)
        self.assertRegex(dis, r'OpDecorate %\d+ Coherent')
        # the barrier: workgroup scope, AcquireRelease | UniformMemory | WorkgroupMemory (0x148)
        consts = dict(re.findall(r'%(\d+) = OpConstant %\d+ (\d+)', dis))
        barriers = re.findall(r'OpControlBarrier %(\d+) %(\d+) %(\d+)', dis)
        self.assertTrue(barriers, 'a barrier')
        for b in barriers: self.assertEqual([int(consts[x]) for x in b], [2, 2, 0x148])
        self.assertIn('OpAtomicIAdd', dis)

    def test_byte_stores_and_cas_go_to_the_slice(self):   # STS.U8's helper function (a CAS loop) reads the slice base too; ATOMS.CAS's pointer is a slice word
        program = mov(3, 0x7F) + sts8(0xB003, 3) + mov(0, 1) + mov(1, 2) + inst(*ATOMS_CAS) + mov(12, 0) + inst(*ATOMS_ADD) + inst(*SUATOM) + inst(*EXIT)
        dis, res = self.translate(0xC000, program, shared_max=32768)
        self.assertNotRegex(dis, r'OpVariable %\d+ Workgroup')
        for m in re.finditer(r'OpAtomicCompareExchange %\d+ %(\d+)', dis):   # every CAS on a pointer into the spill buffer (Uniform storage)
            ptr = re.search(r'%' + m.group(1) + r' = OpAccessChain %(\d+) ', dis)
            self.assertIsNotNone(ptr, m.group(0))
            self.assertRegex(dis, r'%' + ptr.group(1) + r' = OpTypePointer Uniform ')
        self.assertGreaterEqual(len(re.findall(r'OpAtomicCompareExchange', dis)), 2)

    def test_under_the_limit_stays_in_workgroup_memory(self):
        dis, res = self.translate(0x400, shared_max=32768)
        self.assertRegex(dis, r'OpVariable %\d+ Workgroup')
        self.assertFalse([b for b in res['sbufs'] if b['cb_slot'] == 0xff], res['sbufs'])
        self.assertNotIn('NumWorkgroups', dis)

    def test_without_a_limit_nothing_moves(self):   # the Apple build: no shared_max
        dis, res = self.translate(0xC000)
        self.assertRegex(dis, r'OpVariable %\d+ Workgroup')
        self.assertFalse([b for b in res['sbufs'] if b['cb_slot'] == 0xff], res['sbufs'])
        self.assertNotIn('NumWorkgroups', dis)


if __name__ == '__main__':
    unittest.main()
