"""vk_android_build.py's worker interpreters (DMSH_VK_PROCS): the parent's Pool and the workers' loop (serve), taken from the tool's source by name and run in a tiny
fixture build whose batches write one file per shader from its package record (and, for a vertex shader, its pixel shaders' inputs, as the real build pads it).
Checked: every shader's file is what one thread writes, the progress counts every batch once, the translator gate holds across interpreters, a worker's error ends the
build with its message, workers that cannot start leave the build to threads, and a stop (the phone's Pause: KeyboardInterrupt) ends the workers after the shader in hand.
Skipped without concurrent.interpreters (Python 3.14)."""
import json
import os
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOL = REPO / 'dm/gfx/tools/vk_android_build.py'

try:
    from concurrent import interpreters   # noqa: F401
    HAVE = True
except ImportError:
    HAVE = False

# The fixture build: the parent and each worker run this file (Pool runs it by its path as the real tool runs itself). It defines what vk_android_build.py's worker
# section uses (loc, packs, PS_INPUTS, PAIRS, STAGE_OF, run_batch, stopped, progress, ...) and takes that section from the tool's source
FIXTURE = textwrap.dedent('''
    import ast, json, os, signal, sys, threading, time
    E = os.environ
    TOOL, dm_dir, RES, w = E['FIX_TOOL'], E['FIX_PKG'], E['FIX_RES'], E['FIX_W']
    WORKER = globals().get('VK_BUILD_WORKER')
    if WORKER and E.get('FIX_SETUP_FAIL'): sys.exit('[RED] this worker cannot start')
    NJ, PROCS, XLATE_J, DELAY = int(E['FIX_J']), int(E['FIX_PROCS']), int(E['FIX_XLATE_J']), float(E.get('FIX_DELAY', '0'))
    MAP = json.loads(E['FIX_MAP'])   # key -> [file, record]
    loc = {k: tuple(v) for k, v in MAP.items()}
    STAGE_OF = {k: v[0][:2] for k, v in MAP.items()}
    PAIRS = json.loads(E['FIX_PAIRS'])
    PS_INPUTS = {}
    XLATE_GATE = threading.Semaphore(XLATE_J)
    packs = {}

    def _rec(name, k):
        fd, offs = packs[name]
        return os.pread(fd, offs[k + 1] - offs[k], offs[k])

    def stopped(): return False

    def progress(stage, add=0): print(f'progress {add} {stage}', flush=True)

    def run_batch(batch, count=True):
        for key in batch:
            if key == 'boom': raise RuntimeError('the translator failed here')
            data = _rec(*loc[key])
            with XLATE_GATE:   # each holder leaves a file: their count is the calls at once
                mine = os.path.join(RES, 'gate', f'{os.getpid()}_{threading.get_native_id()}_{key}')
                open(mine, 'w').close()
                n = len(os.listdir(os.path.join(RES, 'gate')))
                with open(os.path.join(RES, 'gate_max'), 'a') as f: f.write(f'{n}\\n')
                time.sleep(0.005)
                os.unlink(mine)
            if stopped(): break
            if STAGE_OF[key] == 'ps':
                PS_INPUTS[key] = set(data[:3])
                out = ''
            else: out = json.dumps(sorted(set().union(*[PS_INPUTS.get(p, set()) for p in PAIRS.get(key, ())])))
            with open(os.path.join(RES, key + '.tmp'), 'w') as f: f.write(data.hex() + ' ' + out)
            os.replace(os.path.join(RES, key + '.tmp'), os.path.join(RES, key))
            time.sleep(DELAY)
        if count: progress('translate', len(batch))

    _tree = ast.parse(open(TOOL).read(), TOOL)
    def _take(names, worker_block=False):
        body = [n for n in _tree.body if (isinstance(n, (ast.ClassDef, ast.FunctionDef)) and n.name in names)
                or (isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id in names for t in n.targets))
                or (worker_block and isinstance(n, ast.If) and isinstance(n.test, ast.Name) and n.test.id == 'WORKER')]
        exec(compile(ast.Module(body=body, type_ignores=[]), TOOL, 'exec'), globals())
    _take({'Stop', 'worker_message'}, worker_block=True)   # a worker serves here and ends
    _take({'WORKER_SETUP', 'WORKER_RUN', 'WorkerSetupFailed', 'Pool', 'POOL', 'pool_for', 'close_pool'})

    signal.signal(signal.SIGINT, signal.default_int_handler)
    for name in sorted({v[0] for v in MAP.values()}):
        p = os.path.join(dm_dir, name); d = open(p, 'rb').read()
        offs = [i for i in range(0, len(d), 8)] + [len(d)]
        packs[name] = (os.open(p, os.O_RDONLY), offs)
    BATCH = int(E['FIX_BATCH'])
    try:
        for phase in ([k for k in MAP if STAGE_OF[k] != 'vs'], [k for k in MAP if STAGE_OF[k] == 'vs']):
            if phase and STAGE_OF[phase[0]] == 'vs':   # the pixel shaders' inputs from their result files, as the tool's parent takes them from the result heads
                for k in MAP:
                    if STAGE_OF[k] == 'ps' and os.path.exists(os.path.join(RES, k)): PS_INPUTS[k] = set(bytes.fromhex(open(os.path.join(RES, k)).read().split()[0])[:3])
            batches = [phase[i:i + BATCH] for i in range(0, len(phase), BATCH)]
            pool = pool_for(-(-len(MAP) // BATCH))
            if pool: pool.run(batches, True)
            else:
                print('threads', flush=True)
                for b in batches: run_batch(b)
    finally:
        close_pool()
    print('built', flush=True)
''')


@unittest.skipUnless(HAVE, 'no concurrent.interpreters (Python 3.14)')
class Workers(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix='vkworkers_'))
        (self.tmp / 'pkg').mkdir(); (self.tmp / 'res' / 'gate').mkdir(parents=True); (self.tmp / 'w').mkdir()
        (self.tmp / 'fixture_build.py').write_text(FIXTURE)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def package(self, nps, nvs, extra=()):
        """ps.bin / vs.bin of 8-byte records; vertex shader i pairs with pixel shaders i and i + 1"""
        ps = bytes(range(256))[:8 * nps] if nps <= 32 else os.urandom(8 * nps)
        vs = bytes((i * 7 + 3) % 256 for i in range(8 * nvs))
        (self.tmp / 'pkg' / 'ps.bin').write_bytes(ps + b'\xee' * 8 * len(extra)); (self.tmp / 'pkg' / 'vs.bin').write_bytes(vs)
        m = {f'p{i:02d}': ['ps.bin', i] for i in range(nps)}
        m.update({k: ['ps.bin', nps + j] for j, k in enumerate(extra)})
        m.update({f'v{i:02d}': ['vs.bin', i] for i in range(nvs)})
        pairs = {f'v{i:02d}': [f'p{i:02d}', f'p{(i + 1) % nps:02d}'] for i in range(nvs)}
        return m, pairs, ps, vs

    def env(self, m, pairs, **kw):
        e = dict(os.environ, FIX_TOOL=str(TOOL), FIX_PKG=str(self.tmp / 'pkg'), FIX_RES=str(self.tmp / 'res'), FIX_W=str(self.tmp / 'w'), FIX_MAP=json.dumps(m),
                 FIX_PAIRS=json.dumps(pairs), FIX_J='4', FIX_PROCS='3', FIX_XLATE_J='1', FIX_BATCH='2')
        e.update({k: str(v) for k, v in kw.items()})
        return e

    def run_fixture(self, env, timeout=120):
        return subprocess.run([sys.executable, str(self.tmp / 'fixture_build.py')], env=env, capture_output=True, text=True, timeout=timeout)

    def expected(self, ps, vs, nps, nvs):
        """What one thread writes: each record's bytes, and for a vertex shader the inputs of its pixel shaders"""
        rec = lambda d, i: d[8 * i:8 * i + 8]
        out = {f'p{i:02d}': rec(ps, i).hex() + ' ' for i in range(nps)}
        for i in range(nvs):
            ins = set(rec(ps, i)[:3]) | set(rec(ps, (i + 1) % nps)[:3])
            out[f'v{i:02d}'] = rec(vs, i).hex() + ' ' + json.dumps(sorted(ins))
        return out

    def results(self):
        return {p.name: p.read_text() for p in (self.tmp / 'res').iterdir() if p.is_file() and p.name not in ('gate_max',)}

    def test_results_as_one_thread_writes_them(self):
        m, pairs, ps, vs = self.package(14, 9)
        r = self.run_fixture(self.env(m, pairs, FIX_XLATE_J=2))
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn('shader workers: 3 interpreters of 1 threads, 2 translator calls at once', r.stdout)
        self.assertEqual(self.results(), self.expected(ps, vs, 14, 9))
        self.assertEqual(sum(int(l.split()[1]) for l in r.stdout.splitlines() if l.startswith('progress')), 23)   # each batch counted once
        self.assertLessEqual(max(int(x) for x in (self.tmp / 'res' / 'gate_max').read_text().split()), 2)

    def test_gate_holds_across_interpreters(self):
        m, pairs, ps, vs = self.package(24, 0)
        r = self.run_fixture(self.env(m, pairs, FIX_PROCS=4, FIX_J=8, FIX_XLATE_J=1))
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn('4 interpreters of 2 threads', r.stdout)
        self.assertEqual(max(int(x) for x in (self.tmp / 'res' / 'gate_max').read_text().split()), 1)

    def test_worker_error_ends_the_build(self):
        m, pairs, ps, vs = self.package(10, 2, extra=('boom',))
        r = self.run_fixture(self.env(m, pairs))
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('RuntimeError: shader worker, the batch from', r.stderr)
        self.assertIn('the translator failed here', r.stderr)
        self.assertNotIn('built', r.stdout)

    def test_workers_that_cannot_start_leave_threads(self):
        m, pairs, ps, vs = self.package(6, 3)
        r = self.run_fixture(self.env(m, pairs, FIX_SETUP_FAIL=1))
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertIn('[RED] shader workers (DMSH_VK_PROCS=3) did not start', r.stdout)
        self.assertIn('this worker cannot start', r.stdout)
        self.assertIn('threads', r.stdout.splitlines())
        self.assertEqual(self.results(), self.expected(ps, vs, 6, 3))

    def test_one_proc_is_threads(self):
        m, pairs, ps, vs = self.package(6, 3)
        r = self.run_fixture(self.env(m, pairs, FIX_PROCS=1))
        self.assertEqual(r.returncode, 0, r.stderr + r.stdout)
        self.assertNotIn('shader workers', r.stdout)
        self.assertEqual(self.results(), self.expected(ps, vs, 6, 3))

    def test_stop_ends_after_the_shader_in_hand(self):
        m, pairs, ps, vs = self.package(60, 0)
        p = subprocess.Popen([sys.executable, str(self.tmp / 'fixture_build.py')], env=self.env(m, pairs, FIX_DELAY=0.2, FIX_BATCH=10), stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.time() + 60
            while time.time() < deadline and not any(f.is_file() and f.name.startswith('p') for f in (self.tmp / 'res').iterdir()): time.sleep(0.02)
            p.send_signal(signal.SIGINT); t0 = time.time()
            out, err = p.communicate(timeout=30)
        finally:
            if p.poll() is None: p.kill()
        self.assertLess(time.time() - t0, 3, 'the workers did not stop after the shader in hand')
        self.assertNotEqual(p.returncode, 0)
        self.assertIn('KeyboardInterrupt', err)
        done = [k for k in self.results() if not k.endswith('.tmp')]
        self.assertLess(len(done), 60)
        exp = self.expected(ps, vs, 60, 0)
        for k in done: self.assertEqual(self.results()[k], exp[k])   # what was written is whole

    def test_messages(self):
        src = TOOL.read_text()
        self.assertIn("def worker_message(kind, n, text=''): return json.dumps([kind, n, text])", src)
        self.assertIn('self.jobs.put(json.dumps([sent, b, where, ps_in]))', src)   # [n, batch, {key: [file, record, offset, length]}, {pixel shader: inputs} or None]


if __name__ == '__main__':
    unittest.main()
