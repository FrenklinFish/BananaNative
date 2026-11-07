#!/usr/bin/env python3
"""c0 + 0x644 in a vertex shader through the offline translator: the Android Vulkan build (a job with ssbo_align) reads the BaseInstance builtin, which holds an indirect draw's base instance
too (the replay can write only the recorded one into c0: docs/reference/shaders.md "The base instance of indirect draws comes from the device on Android"). Apple's jobs stay on c0, and so do the
words around it. Set DMSH_TEST_XLATE to the offline translator."""
import json, os, struct, subprocess, tempfile, unittest
from pathlib import Path

EXIT = (0x000fea0003800000, 0x000000000000794d)   # hi, lo: EXIT
STG = (0x000fe2000c101904, 0x0000000002007986)    # STG.E [R2.64], R0: keeps the value alive


def inst(hi, lo): return struct.pack('<QQ', lo, hi)
def mov_c0(offset): return inst(0x000fe20000000f00, 0x0000000000007a02 | (offset // 4) << 40)   # MOV R0, c[0x0][offset]
def program(offset): return bytes(0x80) + mov_c0(offset) + inst(*STG) + inst(*EXIT)   # a zero SPH (no local memory), as the code area of a graphics stage


def decorated_builtins(spv):
    w = struct.unpack(f'<{len(spv) // 4}I', spv); i = 5; out = set()
    while i < len(w):
        n, op = w[i] >> 16, w[i] & 0xffff
        if op == 71 and w[i + 2] == 11: out.add(w[i + 3])   # OpDecorate <id> BuiltIn <value>
        i += n
    return out


BASE_INSTANCE, BASE_VERTEX, DRAW_INDEX = 4425, 4424, 4426


@unittest.skipUnless(os.environ.get('DMSH_TEST_XLATE'), 'set DMSH_TEST_XLATE to the offline translator')
class BaseInstance(unittest.TestCase):
    def builtins(self, offset, **extra):
        with tempfile.TemporaryDirectory(prefix='base_instance_') as d:
            work = Path(d); (work / 'k.bin').write_bytes(program(offset))
            job = dict(key='k', stage='vs', code=str(work / 'k.bin'), arch='sm86', tex={}, img={}, attr={}, fout={}, debug=True, **extra)
            (work / 'jobs.json').write_text(json.dumps([job]))
            r = subprocess.run([os.environ['DMSH_TEST_XLATE'], str(work / 'jobs.json'), d], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            return decorated_builtins((work / 'k.spv').read_bytes())

    ANDROID = dict(subgroup_size=64, ssbo_align=64, gs_passthrough=False)

    def test_android_reads_the_builtin(self): self.assertIn(BASE_INSTANCE, self.builtins(0x644, **self.ANDROID))
    def test_apple_stays_on_c0(self): self.assertNotIn(BASE_INSTANCE, self.builtins(0x644))
    def test_base_vertex_stays_on_c0(self): self.assertNotIn(BASE_VERTEX, self.builtins(0x640, **self.ANDROID))   # VertexIndex holds it already
    def test_draw_index_stays_on_c0(self): self.assertNotIn(DRAW_INDEX, self.builtins(0x648, **self.ANDROID))   # the replay draws a multi-draw's records one at a time


if __name__ == '__main__': unittest.main()
