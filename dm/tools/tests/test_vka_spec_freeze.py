"""vka_spv_spec_freeze (dm/gfx/vk_android/vka_spvpatch.c): a module that reads a subgroup lane built-in has its specialization constants made plain constants (the asked
SpecId takes the given value, the others their defaults, the SpecId decorations dropped), because Qualcomm's driver reads the lane as 0 in a compute or mesh module with any
specialization constant; a module without a lane built-in, without specialization constants or with a specialization constant operation is left alone. Built with the host's
C compiler; the spirv-val check is skipped without SPIRV-Tools."""
import shutil
import struct
import subprocess
import tempfile
from pathlib import Path
import unittest

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'dm/gfx/vk_android/vka_spvpatch.c'
MAIN = r'''
#include "vka_spvpatch.h"
#include <stdio.h>
#include <stdlib.h>
int main(int c, char **v) {
    FILE *f = fopen(v[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f);
    uint32_t *w = malloc(n); if (fread(w, 1, n, f) != (size_t)n) return 2; fclose(f);
    size_t on = 0; uint32_t *o = vka_spv_spec_freeze(w, n / 4, (uint32_t)atoi(v[2]), (uint32_t)atoi(v[3]), &on);
    if (!o) return 3;
    f = fopen(v[4], "wb"); fwrite(o, 4, on, f); fclose(f); return 0;
}
'''
OpSpecConstantTrue, OpSpecConstantFalse, OpSpecConstant, OpSpecConstantOp = 48, 49, 50, 52
OpConstantTrue, OpConstantFalse, OpConstant, OpDecorate = 41, 42, 43, 71


def ins(op, *a): return [(len(a) + 1) << 16 | op, *a]


def module(lane=True, spec_op=False):
    """A compute module: %10 bool spec constant (SpecId 0, false), %11 uint spec constant (SpecId 3, 7), %12 the lane variable (BuiltIn SubgroupLocalInvocationId)"""
    main, void, fn, b, u, pin, lane_v, s0, s1, lbl, s2, x = 1, 2, 3, 4, 5, 6, 12, 10, 11, 13, 14, 15
    w = ins(17, 1) + ins(17, 61) + ins(14, 0, 1)
    w += ins(15, 5, main, *struct.unpack('<2I', b'main\0\0\0\0'), lane_v) + ins(16, main, 17, 32, 1, 1)
    w += ins(OpDecorate, s0, 1, 0) + ins(OpDecorate, s1, 1, 3)
    if lane: w += ins(OpDecorate, lane_v, 11, 41)
    w += ins(19, void) + ins(33, fn, void) + ins(20, b) + ins(21, u, 32, 0) + ins(32, pin, 1, u) + ins(59, pin, lane_v, 1)
    w += ins(OpSpecConstantFalse, b, s0) + ins(OpSpecConstant, u, s1, 7)
    if spec_op: w += ins(OpSpecConstantOp, u, s2, 128, s1, s1)   # IAdd
    w += ins(54, void, main, 0, fn) + ins(248, lbl)
    if lane: w += ins(61, u, x, lane_v)
    w += ins(253) + ins(56)
    return struct.pack(f'<{5 + len(w)}I', 0x07230203, 0x00010300, 0, 16, 0, *w)


def walk(data):
    w = struct.unpack(f'<{len(data) // 4}I', data); i = 5
    while i < len(w):
        yield w[i] & 0xffff, w[i + 1:i + (w[i] >> 16)]; i += w[i] >> 16


class SpecFreeze(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cc = shutil.which('cc') or shutil.which('clang')
        if not cc: raise unittest.SkipTest('no C compiler')
        cls.d = tempfile.TemporaryDirectory(); d = Path(cls.d.name)
        (d / 'm.c').write_text(MAIN)
        r = subprocess.run([cc, '-std=c11', '-O1', '-I', str(SRC.parent), str(SRC), str(d / 'm.c'), '-o', str(d / 'freeze')], capture_output=True, text=True)
        if r.returncode: raise AssertionError(r.stderr)
        cls.exe = d / 'freeze'

    @classmethod
    def tearDownClass(cls): cls.d.cleanup()

    def run_freeze(self, data, spec_id, value):
        d = Path(self.d.name); (d / 'in.spv').write_bytes(data)
        r = subprocess.run([str(self.exe), str(d / 'in.spv'), str(spec_id), str(value), str(d / 'out.spv')])
        return (d / 'out.spv').read_bytes() if r.returncode == 0 else None

    def check_valid(self, data):
        val = shutil.which('spirv-val')
        if not val: return
        p = Path(self.d.name) / 'v.spv'; p.write_bytes(data)
        r = subprocess.run([val, '--target-env', 'vulkan1.1', str(p)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_asked_constant_takes_the_value(self):
        out = self.run_freeze(module(), 0, 1)
        self.assertIsNotNone(out)
        ops = list(walk(out))
        self.assertFalse([a for op, a in ops if op == OpDecorate and a[1] == 1], 'SpecId decorations left')
        self.assertIn((OpConstantTrue, (4, 10)), [(op, tuple(a)) for op, a in ops])
        self.assertIn((OpConstant, (5, 11, 7)), [(op, tuple(a)) for op, a in ops])   # SpecId 3 keeps its default
        self.assertFalse([op for op, _ in ops if op in (OpSpecConstantTrue, OpSpecConstantFalse, OpSpecConstant)])
        self.check_valid(out)

    def test_other_constant(self):
        ops = [(op, tuple(a)) for op, a in walk(self.run_freeze(module(), 3, 9))]
        self.assertIn((OpConstantFalse, (4, 10)), ops)
        self.assertIn((OpConstant, (5, 11, 9)), ops)

    def test_left_alone(self):
        self.assertIsNone(self.run_freeze(module(lane=False), 0, 1))   # no lane built-in: the driver reads nothing wrong
        self.assertIsNone(self.run_freeze(module(spec_op=True), 0, 1))   # a specialization constant operation is not folded here


if __name__ == '__main__':
    unittest.main()
