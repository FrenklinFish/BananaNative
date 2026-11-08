"""dm/tools/vk_prewarm.py: prewarm lists (dm/gfx/vk_android/vka_pwlist.h) merged into a profile's seed, each record once in first-seen order, lists of another key
layout refused; and dm/games/game.py seed, which compiles a profile's seed into the Android host's vk_prewarm_seed.h byte for byte."""
import re
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools')); sys.path.insert(0, str(REPO / 'dm/games'))
import vk_prewarm  # noqa: E402

GSZ, LAYOUT = 1048, 0x1234abcd


def rec(kind, keys, klen=0, fill=0):
    keys = (list(keys) + [0] * 5)[:5]
    body = bytes([fill]) * klen + b'\0' * (((klen + 7) & ~7) - klen)
    return vk_prewarm.REC.pack(kind, 0, 0, 0, klen, *keys) + body


def lst(recs, layout=LAYOUT, gsz=GSZ, magic=vk_prewarm.MAGIC2):
    if magic == vk_prewarm.MAGIC1:
        return vk_prewarm.HDR1.pack(magic, gsz, len(recs)) + b''.join(recs)
    return vk_prewarm.HDR2.pack(magic, gsz, len(recs), layout, 0) + b''.join(recs)


class Formats(unittest.TestCase):
    def test_c_sizes(self):
        """The record and header as vka_pwlist.h lays them out (pwrec 48 bytes, pwhdr2 24)"""
        self.assertEqual(vk_prewarm.REC.size, 48)
        self.assertEqual(vk_prewarm.HDR2.size, 24)
        h = (REPO / 'dm/gfx/vk_android/vka_pwlist.h').read_text()
        self.assertIn('typedef struct { uint8_t kind, bad, inst[2]; uint32_t klen; uint64_t key[5]; } pwrec;', h)
        self.assertIn('typedef struct { char magic[8]; uint32_t gkey_size, n, layout, pad; } pwhdr2;', h)
        kinds = dict((int(v), k) for k, v in re.findall(r'VKA_PW_(GFX|CS|MESH) = (\d)', h))
        self.assertEqual(kinds, {0: 'GFX', 1: 'CS', 2: 'MESH'})

    def test_merge_order_and_dedupe(self):
        a, b, c, m = rec(0, [1, 2], 152, 7), rec(1, [3]), rec(0, [4, 5], 152, 9), rec(2, [6, 7, 8], 152, 1)
        out = vk_prewarm.merge([('one', lst([a, b])), ('two', lst([b, c, a, m]))])
        gsz, layout, recs = vk_prewarm.parse(out)
        self.assertEqual((gsz, layout), (GSZ, LAYOUT))
        self.assertEqual([r for r, _ in recs], [a, b, c, m])
        self.assertEqual([k for _, k in recs], [0, 1, 0, 2])
        self.assertIn('4 keys (1 compute, 1 mesh, 2 vertex)', vk_prewarm.show('x', out))

    def test_bad_flag_not_identity(self):
        a = rec(0, [1, 2], 16, 3); bad = bytearray(a); bad[1] = 1
        _, _, recs = vk_prewarm.parse(lst([a]))
        self.assertEqual(len(recs), 1)
        _, _, recs = vk_prewarm.parse(lst([bytes(bad)]))
        self.assertEqual(recs, [], 'a record marked bad is left out')

    def test_refusals(self):
        a = rec(0, [1], 16)
        with self.assertRaisesRegex(vk_prewarm.Error, 'key layout'):
            vk_prewarm.merge([('one', lst([a])), ('two', lst([a], layout=LAYOUT + 1))])
        with self.assertRaisesRegex(vk_prewarm.Error, 'NXVKPW1'):
            vk_prewarm.merge([('old', lst([a], magic=vk_prewarm.MAGIC1))])
        with self.assertRaisesRegex(vk_prewarm.Error, 'not a prewarm list'):
            vk_prewarm.parse(b'garbage garbage garbage garbage')
        with self.assertRaisesRegex(vk_prewarm.Error, 'runs past'):
            vk_prewarm.parse(lst([a])[:-8])

    def test_cli_merge(self):
        with tempfile.TemporaryDirectory() as d:
            p1, p2, out = Path(d, 'a.keys'), Path(d, 'b.keys'), Path(d, 'out.keys')
            p1.write_bytes(lst([rec(1, [5])])); p2.write_bytes(lst([rec(1, [5]), rec(1, [6])]))
            r = subprocess.run([sys.executable, str(REPO / 'dm/tools/vk_prewarm.py'), 'merge', '-o', str(out), str(p1), str(p2)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(len(vk_prewarm.parse(out.read_bytes())[2]), 2)
            p2.write_bytes(lst([rec(1, [6])], layout=1))
            r = subprocess.run([sys.executable, str(REPO / 'dm/tools/vk_prewarm.py'), 'merge', '-o', str(out), str(p1), str(p2)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 1)


class SeedHeader(unittest.TestCase):
    def test_header_bytes(self):
        """game.py seed: the profile's vk_prewarm.bin byte for byte, or an empty seed for a profile without one"""
        import game
        for g in game.all_games():
            text = game.seed_header(g)
            n = int(re.search(r'dm_vk_prewarm_seed_len = (\d+);', text).group(1))
            body = text.split('= {', 1)[1].split('};', 1)[0]
            vals = [int(x) for x in re.findall(r'\d+', body)]
            p = g.dir / game.SEED
            if p.is_file():
                self.assertEqual(bytes(vals[:n]), p.read_bytes(), g.id)
                vk_prewarm.parse(p.read_bytes(), str(p))   # readable (the C test t_android_pwlist checks its key layout against the host's)
            else:
                self.assertEqual(n, 0, g.id)


if __name__ == '__main__':
    unittest.main()
