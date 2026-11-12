"""Game settings › Game language (android/app GameLanguage.java) on the JVM: the phone's language (the default) mapped to the console's language code as the host maps
DM_LANG (hle_oe.c dm_lang_code, tested by dm/host/tests/t_oe.c), a picked code passed as it is, and nothing set for a phone language the console does not have.
The tag pairs of t_oe.c are read from it and must map alike here. Skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/GameLanguage.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/GameLanguageJvmTest.java'
T_OE = REPO / 'dm/host/tests/t_oe.c'
HOST = REPO / 'dm/host/hle_oe.c'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class GameLanguage(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='gamelang_'))
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(cls.tmp), str(SRC), str(TEST)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def run_on(self, *args):
        r = subprocess.run(['java', '-cp', str(self.tmp), 'dev.nxnative.android.GameLanguageJvmTest', *args], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return [l.split('\t') for l in r.stdout.splitlines()]

    def test_phone_language(self):
        self.assertEqual(self.run_on('- fr-FR', '- tr-TR', 'de fr-FR', '- zh-Hant-TW', '- pl-PL', '- th-TH'), [
            ['DM_LANG=fr', "Phone's language · French"],
            ['-', "Phone's language · not a console language, so English (US)"],
            ['DM_LANG=de', 'German'],
            ['DM_LANG=zh-Hant', "Phone's language · Chinese (Traditional)"],
            ['DM_LANG=pl', "Phone's language · Polish"],
            ['DM_LANG=th', "Phone's language · Thai"]])

    def test_same_mapping_as_host(self):   # every tag t_oe.c checks maps to the same console code here
        pairs = re.findall(r'\{"([^"]+)", "([^"]+)"\}', T_OE.read_text(encoding='utf-8').split('static const char *const lang_in')[1].split('};')[0])
        self.assertGreater(len(pairs), 30)
        got = self.run_on(*[f'- {t}' for t, _ in pairs])
        self.assertEqual([g[0] for g in got], [f'DM_LANG={c}' for _, c in pairs])

    def test_codes_are_the_hosts(self):   # the picker offers only codes the host's settings table has (hle_sdk16.c k_lang_code, zh-CN / zh-TW as zh-Hans / zh-Hant)
        src = SRC.read_text(encoding='utf-8')
        codes = re.findall(r'"([^"]+)"', re.search(r'CODES = \{([^}]*)\}', src).group(1))
        table = re.findall(r'"([^"]+)"', re.search(r'k_lang_code\[\] = \{([^}]*)\}', (REPO / 'dm/host/hle_sdk16.c').read_text(encoding='utf-8')).group(1))
        self.assertEqual(sorted(set(codes) - set(table)), [])
        self.assertEqual(len(codes), 18)


if __name__ == '__main__':
    unittest.main()
