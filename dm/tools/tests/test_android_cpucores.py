"""The Android app's worker count for the shaders step (CpuCores.java, android/app/src/ondevice; PackService sets DMSH_VK_PROCS to it): the big and mid cores, those
whose highest clock is at least 0.75 of the fastest core's, at most 4; without clocks the cores less two. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/CpuCores.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/CpuCoresJvmTest.java'
SERVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackService.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Workers(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='cpucores_'))
        subprocess.run(['javac', '-d', str(cls.tmp / 'classes'), str(SRC), str(TEST)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def java(self, *args):
        r = subprocess.run(['java', '-cp', str(self.tmp / 'classes'), 'dev.nxnative.android.CpuCoresJvmTest', *map(str, args)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return dict(l.split(' ', 1) for l in r.stdout.splitlines())

    def workers(self, cores, *khz): return int(self.java('-', cores, *khz)['workers'])

    def test_big_and_mid_cores(self):
        self.assertEqual(self.workers(8, 3532800, 3532800, 3532800, 3532800, 3532800, 3532800, 4473600, 4473600), 4)   # 6 + 2, no little core (8 Elite): at most 4
        self.assertEqual(self.workers(8, 2016000, 2016000, 2016000, 2803200, 2803200, 2803200, 2803200, 3302400), 4)   # 3 little, 4 mid, 1 big: at most 4
        self.assertEqual(self.workers(8, 2000000, 2000000, 2000000, 2000000, 2400000, 2400000, 2400000, 2900000), 4)   # 4 little (0.69), 3 mid (0.83), 1 big
        self.assertEqual(self.workers(8, 1800000, 1800000, 1800000, 1800000, 2000000, 2000000, 2000000, 2900000), 1)   # (2.0 / 2.9 = 0.69: little too)
        self.assertEqual(self.workers(4, 1000000, 1000000, 1000000, 2000000), 1)

    def test_without_clusters(self):
        self.assertEqual(self.workers(8, *[2000000] * 8), 4)   # one clock: every core, at most 4
        self.assertEqual(self.workers(8, *[0] * 8), 4)   # unknown: cores less two, at most 4
        self.assertEqual(self.workers(3, 0, 0, 0), 1)
        self.assertEqual(self.workers(2, 1, 1), 2)

    def test_reads_cpufreq(self):
        d = self.tmp / 'cpu'
        for i, f in enumerate([1800000, 1800000, 2400000, None, 3000000, 3000000]):   # 0.6 little, 0.8 mid
            (d / f'cpu{i}/cpufreq').mkdir(parents=True)
            if f: (d / f'cpu{i}/cpufreq/cpuinfo_max_freq').write_text(f'{f}\n')
        got = self.java(d, 6)
        self.assertEqual(got['clocks'], '1800000,1800000,2400000,0,3000000,3000000')
        self.assertEqual(got['workers'], '3')

    def test_service_sets_it(self):
        self.assertIn('PackEngine.setenv("DMSH_VK_PROCS", String.valueOf(CpuCores.shaderWorkers()))', SERVICE.read_text())


if __name__ == '__main__':
    unittest.main()
