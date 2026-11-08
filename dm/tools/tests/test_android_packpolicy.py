"""The on-device job's automatic pause (android/app/src/ondevice PackPolicy.java, used by PackService) on the JVM: battery below 20 % and not charging; thermal
status SEVERE or worse, held until MODERATE or better; Go on anyway lifts the heat pause below CRITICAL (GitHub #38: a handheld that reports SEVERE while cool).
Also the readings each pause writes to the step's log. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackPolicy.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/PackPolicyJvmTest.java'
SERVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackService.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Policy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='packpolicy_'))
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(cls.tmp), str(SRC), str(TEST)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def run_on(self, *lines):
        r = subprocess.run(['java', '-cp', str(self.tmp), 'dev.nxnative.android.PackPolicyJvmTest', *lines], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.splitlines()

    def reasons(self, *lines):
        return [l.split('\t')[:2] for l in self.run_on(*lines)[:-1]]

    def test_heat(self):   # the documented rule: pause at SEVERE (3), hold until MODERATE (2) or better
        self.assertEqual(self.reasons('80 0 2 - 0', '80 0 3 - 0', '80 0 3 thermal 0', '80 0 2 thermal 0', '80 1 6 - 0'),
                         [['-', 'false'], ['thermal', 'true'], ['thermal', 'true'], ['-', 'false'], ['thermal', 'false']])

    def test_go_on_anyway(self):   # lifted at SEVERE, not at CRITICAL (4) or worse; once paused there, held until SEVERE or better
        self.assertEqual(self.reasons('80 0 3 thermal 1', '80 0 4 - 1', '80 0 4 thermal 1', '80 0 3 thermal 1'),
                         [['-', 'false'], ['thermal', 'false'], ['thermal', 'false'], ['-', 'false']])

    def test_battery(self):   # below 20 % on battery, whatever the heat or the override
        self.assertEqual(self.reasons('19 0 0 - 0', '19 1 0 - 0', '20 0 0 - 0', '10 0 3 - 1'),
                         [['battery', 'false'], ['-', 'false'], ['-', 'false'], ['battery', 'false']])

    def test_readings(self):
        out = self.run_on('80 1 3 - 0')
        self.assertEqual(out[0].split('\t')[2], 'thermal status 3 (severe), headroom 0.50, battery 80 %, charging')
        self.assertEqual(out[-1], 'thermal status 1 (light), headroom not known, battery 50 %')

    def test_service_uses_policy(self):   # PackService decides with PackPolicy, and logs each pause and each going on to the step's log
        src = SERVICE.read_text(encoding='utf-8')
        self.assertIn('PackPolicy.reason(batteryPct, charging, thermal, autoReason, heatOverride)', src)
        self.assertIn('stepLog(j, "pausing: "', src)
        self.assertIn('stepLog(j, "going on after the "', src)
        self.assertNotIn('THERMAL_STATUS_SEVERE', src)

    def test_every_pause_logged(self):   # every way the job pauses or stops by itself writes a [pack] line to the step's log (stepLog), not only logcat
        src = SERVICE.read_text(encoding='utf-8')
        for what in ('"pausing: timeout:', '"pausing: refused:', '"paused: " + stopReason', '"the tool did not stop within 20 s of the "', '"stopping: the step was ended by Android "',
                     '"going on after Android ended the job\'s process', '"pause asked (from "', '"going on anyway: the heat pause is lifted'):
            self.assertIn('stepLog(', src[src.index(what) - 40:src.index(what)], what)

    def test_keep_screen_on(self):   # Settings › Keep the screen on while preparing: on by default, applied by Home's card while the job runs
        prefs = (REPO / 'android/app/src/main/java/dev/nxnative/android/Prefs.java').read_text(encoding='utf-8')
        self.assertIn('getBoolean("keep_screen_preparing", true)', prefs)
        card = (REPO / 'android/app/src/ondevice/java/dev/nxnative/android/Ondevice.java').read_text(encoding='utf-8')
        self.assertIn('keepScreen(running && Prefs.keepScreenPreparing(a));', card)
        self.assertIn('FLAG_KEEP_SCREEN_ON', card[card.index('void keepScreen('):])


if __name__ == '__main__':
    unittest.main()
