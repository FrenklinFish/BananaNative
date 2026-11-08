"""shader_split DM_SPLIT_TARGETED=1 (the phone's split, pack_android.py's): when main's build is one of the profile's, only the RomFS files its shader_map.tsv names
(a member's source reduced to its RomFS file) are scanned, and the result is kept only when its tables are the profile's byte for byte; otherwise every file is scanned,
with a line saying why. Either way the outputs are the full split's. A file that holds only shaders found earlier but pairs them anew makes the targeted pairs differ,
so that dump takes the full scan; with DM_SPLIT_RESUME a stopped fall back resumes as the full scan. No game files needed: a synthetic RomFS and profile."""
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '01007BB017812000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss   # its magics and skey; the splits below run it as a program, on this test's own profile
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had

GAME = '0100F00DF00D0000'   # a profile only this test has (DM_GAMES_DIR)
BUILD = bytes(range(0x10, 0x20))
TABLES = ('shader_map.tsv', 'shader_facts.tsv', 'shader_pairs.tsv', 'pso_pairs.tsv')


def sarc(members):   # a SARC of (name, bytes) members (dm/tools/fuzz/fuzz_shader_split.py's)
    names = b''.join(n + b'\0' + bytes(-(len(n) + 1) % 4) for n, _ in members)
    sfat = b'SFAT' + struct.pack('<HHI', 0xc, len(members), 0x65)
    data, at, offs = b'', 0, []
    for n, m in members:
        offs.append(at); data += m + bytes(-len(m) % 0x100); at = len(data)
    noff = 0
    for (n, m), o in zip(members, offs):
        sfat += struct.pack('<IIII', 0, 0x01000000 | noff // 4, o, o + len(m)); noff += len(n) + 1 + (-(len(n) + 1) % 4)
    head = 0x14 + len(sfat) + 8 + len(names); doff = (head + 0xff) & ~0xff
    d = b'SARC' + struct.pack('<HHII', 0x14, 0xfeff, doff + len(data), doff) + bytes(4) + sfat + b'SFNT' + struct.pack('<HH', 8, 0) + names
    return d + bytes(doff - len(d)) + data


def stage(sph_type, n):
    """(control section, code area) of one stage as dm/tools/fuzz/fuzz_shader_split.py's stage makes it, with n in an instruction word so each n is another shader"""
    code = bytearray(ss.GFX_MAGIC + bytes(0x2c)); code += struct.pack('<I', sph_type) + bytes(0x4c); code += bytes.fromhex('0f00000000000000') * 7 + struct.pack('<Q', n)
    ctl = bytearray(0x880); ctl[:4] = ss.CTL_MAGIC; struct.pack_into('<I', ctl, 0x20, 0x880); struct.pack_into('<I', ctl, 0x704, len(code))
    return bytes(ctl), bytes(code)


def vs(n): return stage(0x0461, n)
def ps(n): return stage(0x1462, n)


def bnsh(v, p):
    """A BNSH of one variation: vertex v, pixel p (dm/tools/fuzz/fuzz_shader_split.py's layout)"""
    d = bytearray(0x300); d[:8] = b'BNSH\0\0\0\0'; d[0x60:0x64] = b'grsc'
    struct.pack_into('<IQ', d, 0x7c, 1, 0x100); struct.pack_into('<Q', d, 0x110, 0x140)
    for si, desc, (ctl, code) in ((0, 0x200, v), (4, 0x240, p)):
        struct.pack_into('<Q', d, 0x148 + 8 * si, desc)
        at = len(d); d += ctl + code
        struct.pack_into('<QQII', d, desc + 8, at, at + len(ctl), len(code), len(ctl))
    struct.pack_into('<I', d, 0x1c, len(d))
    return bytes(d)


class Targeted(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp()); self.addCleanup(lambda: shutil.rmtree(self.tmp))
        self.rom = self.tmp / 'romfs'
        self.write('Shader/a.bnsh', bnsh(vs(1), ps(1)))
        self.write('Shader/pack.sarc', sarc([(b'b.bnsh', bnsh(vs(2), ps(2)))]))   # a member: its source is /Shader/pack.sarc/b.bnsh#...
        for i in range(20): self.write(f'Model/m{i:02}.bin', b'no shaders here %d' % i)
        self.write('Zdup/dup.bnsh', bnsh(vs(1), ps(1)))   # only copies of shaders found earlier, paired the same way: named by no map row
        self.main = self.tmp / 'main'; self.main.write_bytes(b'NSO0' + bytes(0x3c) + BUILD + bytes(0x100 - 0x50))
        self.prof = self.tmp / 'profiles' / GAME; self.prof.mkdir(parents=True)
        self.game_tsv(BUILD.hex())

    def write(self, rel, data):
        p = self.rom / rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)

    def game_tsv(self, build):
        (self.prof / 'game.tsv').write_text(f'title_id\t{GAME}\nbuild\tv0\t1.0.0\tmain={build}\nshader_groups\t/Shader/=shader\nshader_note\tTest shaders\n')

    def split(self, name, targeted, resume=None, files=None):
        out = self.tmp / name
        env = dict(os.environ, DM_GAME=GAME, DM_GAMES_DIR=str(self.tmp / 'profiles'), J='1', DM_SPLIT_SELECTED='0')
        for k in ('DM_SPLIT_RESUME', 'DM_SPLIT_TARGETED', 'DM_SPLIT_FILES'): env.pop(k, None)
        if files: env['DM_SPLIT_FILES'] = str(files)
        if targeted: env['DM_SPLIT_TARGETED'] = '1'
        if resume: env['DM_SPLIT_RESUME'] = str(resume)
        r = subprocess.run([sys.executable, REPO / 'dm/gfx/tools/shader_split.py', self.rom, self.main, out / 'pkg', *[out / t for t in TABLES]],
                           capture_output=True, text=True, env=env)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return out, r.stdout

    def profile_from(self, out):   # the profile's tables: a full split's
        for t in TABLES: shutil.copyfile(out / t, self.prof / t)

    def same(self, a, b):
        self.assertEqual(sorted(os.listdir(a / 'pkg')), sorted(os.listdir(b / 'pkg')))
        for f in os.listdir(a / 'pkg'): self.assertEqual((a / 'pkg' / f).read_bytes(), (b / 'pkg' / f).read_bytes(), f)
        for t in TABLES: self.assertEqual((a / t).read_bytes(), (b / t).read_bytes(), t)

    def test_targeted_equals_full(self):
        full, said = self.split('full', False)
        self.assertNotIn('targeted split', said)
        self.assertIn('/Shader/pack.sarc/b.bnsh#BNSH', (full / 'shader_map.tsv').read_text())
        self.profile_from(full)
        t, said = self.split('targeted', True)
        self.assertIn('targeted split: 2 of 23 RomFS files hold the shaders', said)
        self.assertIn('progress: 2/2', said)   # the count is of the files it reads
        self.assertIn("targeted split: its tables are the profile's, byte for byte", said)
        self.same(full, t)

    def test_a_copy_that_pairs_anew_takes_the_full_scan(self):
        self.write('Zdup/cross.bnsh', bnsh(vs(1), ps(2)))   # no shader of its own, but a pair no other file has
        full, _ = self.split('full', False); self.profile_from(full)
        self.assertIn(f'{self.key(vs(1))}\t{self.key(ps(2))}\n', (full / 'shader_pairs.tsv').read_text())
        scan = self.tmp / 'scan'
        t, said = self.split('targeted', True, scan)
        self.assertIn("not taken, the targeted scan made a shader_pairs.tsv that differs from the profile's: every file is scanned", said)
        self.assertIn('progress: 24/24', said)
        self.same(full, t)
        self.assertEqual(sorted(os.listdir(scan)), ['full', 'targeted', 'targeted.failed'])
        t2, said = self.split('again', True, scan)   # a rerun (the phone's after a stop) goes straight to the full scan, from its kept results
        self.assertIn('an earlier run on these inputs found the targeted scan', said)
        self.assertIn('resuming the split: 24 file(s) already scanned', said)
        self.assertNotIn('only these are scanned', said)
        self.same(full, t2)

    def test_the_profiles_file_list_takes_such_copies(self):   # shader_files.tsv (DM_SPLIT_FILES of a full split): every file a shader was found in, so the targeted scan reads them
        self.write('Zdup/cross.bnsh', bnsh(vs(1), ps(2)))
        full, _ = self.split('full', False, files=self.prof / 'shader_files.tsv'); self.profile_from(full)
        listed = [ln for ln in (self.prof / 'shader_files.tsv').read_text().splitlines() if not ln.startswith('#')]
        self.assertEqual(listed, ['Shader/a.bnsh', 'Shader/pack.sarc', 'Zdup/cross.bnsh', 'Zdup/dup.bnsh'])
        t, said = self.split('targeted', True)
        self.assertIn('targeted split: 4 of 24 RomFS files hold the shaders', said)
        self.assertIn("its tables are the profile's, byte for byte", said)
        self.same(full, t)

    def test_another_build_takes_the_full_scan(self):
        full, _ = self.split('full', False); self.profile_from(full)
        self.game_tsv('00' * 16)
        t, said = self.split('targeted', True)
        self.assertIn(f"targeted split: not taken, main's build {BUILD.hex()} is not one the profile was made from", said)
        self.same(full, t)

    def test_a_named_file_missing_takes_the_full_scan(self):
        full, _ = self.split('full', False); self.profile_from(full)
        (self.rom / 'Shader/a.bnsh').unlink()
        full2, _ = self.split('full2', False)
        t, said = self.split('targeted', True)
        self.assertIn("names /Shader/a.bnsh#BNSH", said); self.assertIn('and the dump has no such file: every file is scanned', said)
        self.same(full2, t)

    def test_no_map_takes_the_full_scan(self):
        full, _ = self.split('full', False)
        t, said = self.split('targeted', True)
        self.assertIn('not taken, the profile has no shader_map.tsv', said)
        self.same(full, t)

    @staticmethod
    def key(s): return ss.skey(*s)


if __name__ == '__main__': unittest.main()
