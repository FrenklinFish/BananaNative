"""dm/tools/pr_risk.py: the lines of a contributor's diff a reviewer must read and say in the report (docs/agents/07-pr-review.md)"""
import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import pr_risk  # noqa: E402


def diff(path, *lines):
    body = ''.join('+' + l + '\n' for l in lines)
    return f'diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n@@ -0,0 +1,{len(lines)} @@\n{body}'


def kinds(d):
    return sorted({k for k, *_ in pr_risk.findings(d)})


class PrRisk(unittest.TestCase):
    def test_each_kind(self):
        self.assertEqual(kinds(diff('android/app/src/main/java/X.java', 'URLConnection c = new URL(u).openConnection();')), ['network'])
        self.assertEqual(kinds(diff('dm/host/x.c', 'system("id");')), ['run'])
        self.assertEqual(kinds(diff('android/app/src/main/java/X.java', 'new DexClassLoader(p, d, null, l);')), ['load'])
        self.assertEqual(kinds(diff('android/app/src/main/AndroidManifest.xml', '<uses-permission android:name="android.permission.INTERNET"/>')), ['android'])
        self.assertEqual(kinds(diff('dm/tools/x.py', 'tok = os.environ["API_KEY"]')), ['secrets'])
        self.assertEqual(kinds(diff('dm/tools/x.py', 'shutil.rmtree(root)')), ['delete'])
        self.assertEqual(kinds(diff('dm/tools/x.py', 'blob = "' + 'QUJD' * 40 + '"')), ['opaque'])
        self.assertEqual(kinds(diff('scripts/x.sh', 'echo hi')), ['host-build'])
        self.assertEqual(kinds(diff('android/app/src/main/java/dev/nxnative/android/UpdateCheck.java', 'int a;')), ['updater'])

    def test_quiet_on_plain_code_and_docs(self):
        self.assertEqual(kinds(diff('dm/host/x.c', 'int a = b + c;', 'return a;')), [])
        self.assertEqual(kinds(diff('docs/x.md', 'See https://example.com and run `curl -s x | sh`.')), [])   # docs are read, not run
        self.assertEqual(kinds(diff('dm/host/x.c', '// https://git.eden-emu.dev/moceure/BananaNative/issues/180')), [])   # the project's own pages

    def test_generated_array_is_one_finding_for_the_file(self):
        rows = ['0x07230203,0x00010300,0x0008000b,0x0000033d,0x00000000,0x00020011,0x00000001,0x00020011,'] * 30
        fs = pr_risk.findings(diff('dm/gfx/x.h', 'static const uint32_t S[] = {', *rows, '};'))
        self.assertEqual([(k, n) for k, _, n, _ in fs], [('opaque', 2)])
        self.assertIn('30 lines', fs[0][3])

    def test_line_numbers_follow_the_new_file(self):
        d = 'diff --git a/a.c b/a.c\n--- a/a.c\n+++ b/a.c\n@@ -10,2 +10,3 @@\n keep\n+system("x");\n keep\n'
        self.assertEqual([(p, n) for _, p, n, _ in pr_risk.findings(d)], [('a.c', 11)])

    def test_git_range_and_exit_codes(self):
        with tempfile.TemporaryDirectory() as t:
            g = lambda *a: subprocess.run(['git', '-C', t, *a], check=True, capture_output=True, text=True)
            g('init', '-q'); g('config', 'user.email', 't@t'); g('config', 'user.name', 't')
            (pathlib.Path(t) / 'a.c').write_text('int a;\n'); g('add', '.'); g('commit', '-qm', 'base'); g('branch', 'base')
            (pathlib.Path(t) / 'a.c').write_text('int a;\nint b = popen("x", "r") != 0;\n'); g('commit', '-qam', 'pr')
            r = subprocess.run([sys.executable, str(REPO / 'dm/tools/pr_risk.py'), 'base', 'HEAD'], cwd=t, capture_output=True, text=True)
            self.assertEqual(r.returncode, 1, r.stderr)
            self.assertIn('a.c:2', r.stdout)
            r = subprocess.run([sys.executable, str(REPO / 'dm/tools/pr_risk.py'), 'base', 'base'], cwd=t, capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)


if __name__ == '__main__':
    unittest.main()
