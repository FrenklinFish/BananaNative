"""Mesh draws through compute (dm/gfx/vk_android/vka_memu*.c; docs/reference/rendering.md "Mesh draws through compute"): the generated headers are what their sources give
(the helper passes' SPIR-V from memu/*.comp, the test fixtures from dm/host/tests/memu/*.spvasm; skipped without glslangValidator / spirv-as), the record words the C header and
the GLSL passes share are one set, and the switches are documented. The SPIR-V conversion itself is checked by dm/host/tests t_android_memu."""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest

REPO = Path(__file__).resolve().parents[3]
VKA = REPO / 'dm/gfx/vk_android'


class Memu(unittest.TestCase):
    def regen(self, script, out):
        with tempfile.TemporaryDirectory() as td:
            copy = Path(td) / 'before.h'
            shutil.copy(out, copy)
            try:
                subprocess.run([sys.executable, str(script)], check=True, capture_output=True)
                self.assertEqual(out.read_text(), copy.read_text(), '%s is not what %s makes: run it and commit the header' % (out.name, script.name))
            finally:
                shutil.copy(copy, out)

    @unittest.skipUnless(shutil.which('glslangValidator') and shutil.which('spirv-val'), 'needs glslangValidator and spirv-val')
    def test_helper_header_current(self):
        self.regen(VKA / 'memu/spv.py', VKA / 'vka_memu_helpers_spv.h')

    @unittest.skipUnless(shutil.which('spirv-as') and shutil.which('spirv-val'), 'needs spirv-as and spirv-val')
    def test_fixture_header_current(self):
        self.regen(REPO / 'dm/host/tests/memu/gen.py', REPO / 'dm/host/tests/memu/memu_fixtures.h')

    def test_record_words(self):
        # Every record word has its own index below the record size, and the GLSL passes use only names the header defines
        abi = dict(re.findall(r'^#define (VKA_MEMU_\w+) (\d+)', (VKA / 'vka_memu_abi.h').read_text(), re.M))
        words = {k: int(v) for k, v in abi.items() if k.startswith('VKA_MEMU_R_')}
        size = int(abi['VKA_MEMU_REC_WORDS'])
        spans = {'VKA_MEMU_R_TASK_X': 3, 'VKA_MEMU_R_MESH_X': 3, 'VKA_MEMU_R_DRAW': 5}
        taken = {}
        for k, v in words.items():
            for w in range(v, v + spans.get(k, 1)):
                self.assertLess(w, size, k)
                self.assertNotIn(w, taken, '%s overlaps %s' % (k, taken.get(w)))
                taken[w] = k
        for src in (VKA / 'memu').glob('*'):
            if src.suffix in ('.comp', '.glsl'):
                for name in re.findall(r'VKA_MEMU_\w+', src.read_text()):
                    self.assertIn(name, abi, '%s uses %s' % (src.name, name))

    def test_switches_documented(self):
        env = (REPO / 'docs/env_switches.tsv').read_text()
        for name in ('DM_VK_MESH_EMU', 'DM_VK_MESH_EMU_MB'):
            self.assertRegex(env, r'(?m)^%s\t' % name)


if __name__ == '__main__':
    unittest.main()
