"""The Android launcher's save backup / restore (SaveStore.java: verified copies of the save and its device save, the automatic backup before a restore, the lock, the
roll-forward journal it shares with the host's plat_rename_swap) and its game table (Profile.java), on the JVM: javac compiles them with a stub BuildConfig and
android/app/src/jvmtest's LauncherJvmTest. Also: the lock held by another process, and the journal's recovery rules checked against the host's own plat_swap_recover
(its swap code cut out of dm/host/plat/plat_android.c and compiled for the Mac), case by case, so the two sides keep one format and one set of rules.
Skipped without a JDK (the C half: without cc)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/LauncherJvmTest.java'
PLAT = REPO / 'dm/host/plat/plat_android.c'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=aa";
}
'''
HARNESS = '''#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define LOG(...) (printf(__VA_ARGS__), printf("\\n"))
%s
int main(int argc, char **argv) { (void)argc; plat_swap_recover(argv[1]); return 0; }
'''


def swap_code():
    """plat_android.c's journal: swap_rm_cb … plat_swap_recover, without plat_rename_swap (Linux's renameat2). macOS's nftw refuses a file as its root (ENOTDIR),
    which bionic's and glibc's take, so on the Mac swap_rm_tree removes a file itself"""
    s = PLAT.read_text()
    a = s.index('static int swap_rm_cb'); b = s.index('// Linux keeps an application')
    code = re.sub(r'int plat_rename_swap\(.*?\n}\n', '', s[a:b], flags=re.S)
    rm = 'static int swap_rm_tree(const char *p) { return nftw(p, swap_rm_cb, 16, FTW_DEPTH | FTW_PHYS); }'
    assert rm in code
    return code.replace(rm, 'static int swap_rm_tree(const char *p) { struct stat st; if (!lstat(p, &st) && !S_ISDIR(st.st_mode)) return unlink(p); '
                            'return nftw(p, swap_rm_cb, 16, FTW_DEPTH | FTW_PHYS); }')


def tree(d):
    """{relative path: bytes or None for a folder} of everything under d (and beside it: the save's markers and siblings); d's own path in a marker reads ROOT, and the
    launcher's lock file (the host takes none) is left out"""
    out = {}
    for p in sorted(Path(d).rglob('*')):
        if p.name.endswith('.lock'): continue
        out[str(p.relative_to(d))] = None if p.is_dir() else p.read_bytes().replace(str(d).encode(), b'ROOT')
    return out


def scenarios(root):
    """name → a function making that state under root/<name>; returns the save folder"""
    def base(r, tag='old'):
        s = r / 'saves/040074A01BF12000'; s.mkdir(parents=True)
        (s / 'slot0.bin').write_text('slot0 ' + tag); (s / 'stale.bin').write_text('stale'); return s

    def copy(s, suffix, files):
        a = Path(str(s) + suffix)
        for k, v in files.items(): (a / k).parent.mkdir(parents=True, exist_ok=True); (a / k).write_text(v)
        return a

    def pend1(r):
        s = base(r); a = copy(s, '.restore', {'slot0.bin': 'new', 'd/x.bin': 'x'}); Path(str(s) + '.pend1').write_text(str(a)); return s

    def pend2_partial(r):
        s = base(r); a = copy(s, '.new', {'slot0.bin': 'newer', 'c.bin': 'c'}); (s / 'stale.bin').unlink()
        Path(str(s) + '.pend2').write_text(str(a)); (a / 'c.bin').rename(s / 'c.bin'); return s

    def empty_pend2(r):
        s = base(r); a = copy(s, '.new', {'only.bin': 'only'}); Path(str(s) + '.pend1').write_text(str(a)); Path(str(s) + '.pend2').write_text(''); return s

    def pend1_gone(r):
        s = base(r); Path(str(s) + '.pend1').write_text(str(s) + '.new'); return s

    def foreign(r):
        s = base(r); f = r / 'foreign'; f.mkdir(); (f / 'keep.bin').write_text('keep'); Path(str(s) + '.pend1').write_text(str(f)); return s

    def foreign_dir(r):
        s = base(r); f = r / 'elsewhere' / (s.name + '.new'); f.mkdir(parents=True); (f / 'keep.bin').write_text('keep')
        Path(str(s) + '.pend1').write_text(str(f)); return s

    def both_markers(r):   # a stop after .pend2 was written and before .pend1 went: .pend1 must go before stage 2
        s = base(r); a = copy(s, '.new', {'slot0.bin': 'new'}); (s / 'stale.bin').unlink()
        Path(str(s) + '.pend1').write_text(str(a)); Path(str(s) + '.pend2').write_text(str(a)); return s

    def emptied_copy(r):   # what an older recovery left by stopping after its stage 2, before deleting .pend1: stage 1 against the emptied a would delete the save
        s = base(r, 'new'); a = Path(str(s) + '.new'); (a / 'd/e').mkdir(parents=True); Path(str(s) + '.pend1').write_text(str(a)); return s

    def legacy_old(r):
        s = base(r); o = Path(str(s) + '.old'); shutil.move(str(s), str(o)); return s

    return {'pend1': pend1, 'pend2_partial': pend2_partial, 'empty_pend2': empty_pend2, 'pend1_gone': pend1_gone, 'foreign': foreign, 'foreign_dir': foreign_dir,
            'legacy_old': legacy_old, 'both_markers': both_markers, 'emptied_copy': emptied_copy}


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class LauncherJvm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        d = Path(cls.tmp.name)
        (d / 'stub').mkdir()
        (d / 'stub/BuildConfig.java').write_text(STUB)
        cls.classes = d / 'classes'
        subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-d', str(cls.classes), str(SRC / 'SaveStore.java'), str(SRC / 'Profile.java'), str(TEST), str(d / 'stub/BuildConfig.java')],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def java(self, *args, **kw):
        return subprocess.run(['java', '-cp', str(self.classes), 'dev.nxnative.android.LauncherJvmTest', *map(str, args)], capture_output=True, text=True, **kw)

    def test_saves_and_profiles(self):
        with tempfile.TemporaryDirectory() as d:
            r = self.java(d)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)

    def test_lock_held_by_another_process(self):
        with tempfile.TemporaryDirectory() as d:
            s = Path(d) / 'saves/040074A01BF12000'; s.mkdir(parents=True); (s / 'slot0.bin').write_text('x')
            (Path(d) / 'src').mkdir(); (Path(d) / 'src/a.bin').write_text('a')
            h = subprocess.Popen(['java', '-cp', str(self.classes), 'dev.nxnative.android.LauncherJvmTest', 'holdtwice', str(s)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
            try:
                self.assertEqual(h.stdout.readline().strip(), 'held')
                r = self.java('locked', s)
                self.assertEqual(r.stdout.strip(), 'refused 3 inUse', r.stdout + r.stderr)
                self.assertEqual((s / 'slot0.bin').read_text(), 'x')
            finally:
                h.stdin.close(); h.wait(10)
            r = self.java('locked', s)   # released when the holder ended
            self.assertEqual(r.stdout.strip(), 'refused 0', r.stdout + r.stderr)

    @unittest.skipUnless(shutil.which('cc'), 'no C compiler')
    def test_journal_matches_the_host(self):
        """Each stopped swap, recovered by the host's plat_swap_recover and by SaveStore.recover from the same state: the same files after, the same refusals"""
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'recover.c').write_text(HARNESS % swap_code())
            subprocess.run(['cc', '-std=gnu11', '-w', '-o', str(d / 'recover'), str(d / 'recover.c')], check=True, capture_output=True, text=True)
            refused = {'pend1_gone', 'foreign', 'foreign_dir'}
            for name, make in scenarios(d).items():
                with self.subTest(name):
                    host, app = d / 'host' / name, d / 'app' / name
                    sh, sa = make(host), make(app)
                    before = tree(host)
                    rh = subprocess.run([str(d / 'recover'), str(sh)], capture_output=True, text=True)
                    ra = self.java('recover', sa)
                    self.assertEqual(tree(host), tree(app), f'{name}: host {rh.stdout!r}, app {ra.stdout!r}')
                    if name in refused:
                        self.assertIn('[RED]', rh.stdout); self.assertTrue(ra.stdout.startswith('refused'), ra.stdout)
                        self.assertEqual(tree(host), before, f'{name}: a refused marker changes nothing')
                    else:
                        if name != 'emptied_copy': self.assertNotIn('[RED]', rh.stdout)   # the emptied copy is logged: stage 1 refused, the marker dropped
                        self.assertFalse(ra.stdout.startswith('refused'), ra.stdout)
                        if name == 'emptied_copy': self.assertEqual((sh / 'slot0.bin').read_text(), 'slot0 new', 'the save survives')
                        if name == 'both_markers': self.assertEqual((sh / 'slot0.bin').read_text(), 'new')
                        self.assertFalse(any(k.endswith(('.pend1', '.pend2', '.old', '.restore', '.new')) for k in tree(host)), f'{name}: {list(tree(host))}')


if __name__ == '__main__':
    unittest.main()
