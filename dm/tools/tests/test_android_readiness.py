"""The Android launcher's readiness check: Readiness.java's decisions on the JVM (javac compiles it with android/app/src/jvmtest's ReadinessJvmTest), and every profile's
readiness.tsv: well-formed rows (required / degraded, a number or version as the minimum, a title), each naming a fact that the host's nxhost_vk_readiness
(dm/gfx/vk_android/vka_readiness.c) or the launcher (ram_gib, android.sdk) reports, so a typo cannot turn into a check that always fails. The rows go through the same
conversion as build.gradle's reqsOf into Readiness.parseReqs. Skipped without a JDK (the table checks run anyway)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/ReadinessJvmTest.java'
PROBE = REPO / 'dm/gfx/vk_android/vka_readiness.c'
FIXTURE = REPO / 'android/app/src/jvmtest/honor-pnm-n49.readiness.txt'   # the tested phone's facts on its own driver (nxhost_vk_readiness, 2026-11-03)
VK_HEADER = REPO / 'dm/third_party/vulkan-headers/include/vulkan/vulkan_core.h'


def tables():
    return sorted((REPO / 'dm/games').glob('*/readiness.tsv'))


def rows(path):
    return [line.split('\t') for line in path.read_text(encoding='utf-8').splitlines() if line.strip() and not line.startswith('#')]


def gradle_rows(path):   # build.gradle reqsOf: level|fact|min|what, ';' between rows, with tabs, newlines, \\ and " made spaces, ; and | commas
    def text(s): return re.sub(r'[;|]', ',', re.sub(r'[\t\n\\"]', ' ', s)).strip()
    return ';'.join('|'.join(text(x) for x in c[:3] + [' '.join(c[3:])]) for c in rows(path) if len(c) >= 4 and c[0] in ('required', 'degraded'))


def probe_facts():
    """The fact keys vka_readiness.c can write (a set of exact keys, and prefixes whose names come from a table in the file)"""
    src = PROBE.read_text(encoding='utf-8')
    keys = set(re.findall(r'"((?:vk|gpu|driver|queue|limit|sparse|memory|format|ext)\.[A-Za-z0-9_]+)=', src))
    keys |= set(re.findall(r'put_(?:ver|str)\(&o, "([\w.]+)"', src))
    keys |= {'feature.' + m for m in re.findall(r'B\("([A-Za-z0-9_]+)"', src)}
    keys |= {'sparse.format.' + m for m in re.findall(r'\{"([a-z0-9]+)", VK_FORMAT_', src)}
    keys |= {'format.' + m for m in re.findall(r'\{"([a-z0-9]+)", VK_FORMAT_', src)}
    for line in re.findall(r'"limit\.[^"]*"', src):
        keys |= set(re.findall(r'(limit\.[A-Za-z0-9_]+)=', line))
    ext = re.search(r'k_exts\[\] = \{(.*?)\};', src, re.S).group(1)
    names = dict(re.findall(r'#define (VK_\w+_EXTENSION_NAME)\s+"(\w+)"', VK_HEADER.read_text(encoding='utf-8')))   # the macros' strings, as the driver lists them
    for macro in re.findall(r'\b(VK_\w+_EXTENSION_NAME)\b', ext): keys.add('ext.' + names[macro])
    keys |= {'ext.' + m for m in re.findall(r'"(VK_[A-Za-z0-9_]+)"', ext)}
    return keys | {'ram_gib', 'android.sdk'}


class Tables(unittest.TestCase):
    def test_host_needs(self):   # Readiness.HOST names only reported facts: a misspelt extension would fail every phone
        facts = probe_facts()
        host = re.findall(r'new Req\(true, "([^"]+)"', (SRC / 'Readiness.java').read_text(encoding='utf-8'))
        self.assertGreater(len(host), 5)
        for f in host: self.assertIn(f, facts, f'Readiness.HOST: {f} is no fact the host or the launcher reports')
        if FIXTURE.is_file():   # and the tested phone, which runs the games, reports every one of them
            have = dict(l.split('=', 1) for l in FIXTURE.read_text(encoding='utf-8').splitlines() if '=' in l)
            for f in host:
                if f not in ('ram_gib', 'android.sdk'): self.assertIn(f, have, f'the tested phone reports no {f}')

    def test_rows(self):
        facts = probe_facts()
        self.assertIn('feature.textureCompressionBC', facts)
        self.assertIn('limit.maxComputeSharedMemorySize', facts)
        self.assertIn('sparse.format.bc6h', facts)
        self.assertTrue(tables(), 'no profile has a readiness.tsv')
        for t in tables():
            st = [c for c in rows(t) if c[0] == 'status']
            self.assertLessEqual(len(st), 1, f'{t}: one status row at most')
            for c in st: self.assertIn(c[1].strip(), ('playable', 'bring-up'), f'{t}: status')
            for c in rows(t):
                if c[0] == 'status': continue
                self.assertIn(c[0], ('required', 'degraded'), f'{t}: {c}')
                self.assertGreaterEqual(len(c), 4, f'{t}: {c}: level, fact, minimum, title')
                self.assertIn(c[1], facts, f'{t}: {c[1]} is no fact the host or the launcher reports')
                self.assertRegex(c[2], r'^(0x[0-9a-fA-F]+|\d+(\.\d+)*)$', f'{t}: {c}: the minimum')
                self.assertRegex(c[3], r'^[A-Z0-9][^:]*: \S', f'{t}: {c}: "<title>: <why>"')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ReadinessJvm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.classes = Path(cls.tmp.name) / 'classes'
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(cls.classes), str(SRC / 'Readiness.java'), str(TEST)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def java(self, *args):
        return subprocess.run(['java', '-cp', str(self.classes), 'dev.nxnative.android.ReadinessJvmTest', *map(str, args)], capture_output=True, text=True)

    def test_decisions(self):
        r = self.java()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertRegex(r.stdout, r'^ok \d+')

    def test_tested_phone(self):   # the tested phone's facts against each profile: what its players see
        if not FIXTURE.is_file(): self.skipTest('no facts of the tested phone')
        for t in tables():
            with self.subTest(game=t.parent.name):
                r = self.java(FIXTURE, gradle_rows(t))
                self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
                self.assertIn('[readiness] profile: ', r.stdout)
                self.assertNotIn('[FAIL]', r.stdout, 'a required row fails on the tested phone, where the games run: a false failure')


if __name__ == '__main__':
    unittest.main()
