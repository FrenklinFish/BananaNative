"""Prepare's words for the player on the JVM (android/app/src/main PrepareText.java, Profile.missing): javac compiles them with a stub BuildConfig and
android/app/src/jvmtest's PrepareTextJvmTest. Main texts carry no file path or tool output (those go to the card's Details); a failure offers Retry, Try anyway or
nothing; the progress line never says "0 of 0"; a job of a game the APK does not include gets one sentence and no Retry. Also that the launcher keeps to it: the job
card hides Retry and Play for such a job, and the service refuses to run it. Skipped without a JDK (the source checks run anyway)."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
ONDEVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/PrepareTextJvmTest.java'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=aa";
}
'''


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Words(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='preparetext_') as t:
            stub = Path(t) / 'BuildConfig.java'
            stub.write_text(STUB)
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', t, str(SRC / 'PrepareText.java'), str(SRC / 'Profile.java'), str(stub), str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.nxnative.android.PrepareTextJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Wiring(unittest.TestCase):
    def test_service_refuses_a_game_not_included(self):   # the trigger: a remembered job of a game this APK has no profile for ran its steps on Retry
        src = (ONDEVICE / 'PackService.java').read_text(encoding='utf-8')
        work = src[src.index('private void work()'):]
        self.assertLess(work.index('Profile.missing(j.titleId(), j.name())'), work.index('dropOtherSplitScans(j)'), 'checked before any step runs')
        self.assertIn('j.fail(PrepareText.notIncluded(missing))', work)
        self.assertNotIn('j.state("failed"', src, 'every failure goes through Job.fail (short text, Details, the offer)')

    def test_card_offers_only_what_can_work(self):
        src = (ONDEVICE / 'Ondevice.java').read_text(encoding='utf-8')
        self.assertIn('go.setVisibility(noFix ? View.GONE : View.VISIBLE)', src, 'no Retry that cannot succeed')
        self.assertIn('play.setVisibility(noFix ? View.GONE : View.VISIBLE)', src)
        self.assertIn('PrepareText.FIX_ANYWAY.equals(job.fix())', src, 'Try anyway only where the job can go on with a check relaxed')
        self.assertNotIn('" · update " + job.version()', src, 'no raw version number in the card\'s title line')

    def test_try_anyway_relaxes_only_named_checks(self):
        src = (ONDEVICE / 'PackService.java').read_text(encoding='utf-8')
        self.assertIn('DumpCheck.check(j.dump(), false, j.tryAnyway())', src)
        self.assertIn('j.tryAnyway() ? null : splitMatchesProfile(j, split)', src)
        self.assertIn('PackEngine.setenv("DMSH_VK_REQUIRE_ALL", j.tryAnyway() ? "0" : "1")', src)


if __name__ == '__main__':
    unittest.main()
