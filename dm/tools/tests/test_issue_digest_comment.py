#!/usr/bin/env python3
"""dm/tools/issue_digest_comment.py without the network: the comment's text (the mark, one summary line per log with its poster, device, driver and game,
the advice once, references kept only for the driver they were written for, a fence no log line can close, the cut to Forgejo's limit, nothing for an issue
without a log), and issue_digest.py leaving the bot's own comment out."""
import sys
import unittest
import unittest.mock
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import issue_digest_comment as C   # noqa: E402

I = C.I


QCOM = {'app_version': '0.3.14 (df5b43b32a98)', 'device': 'Xiaomi 23049PCD8G, Android 17 (SDK 37), arm64-v8a', 'gpu': 'Adreno (TM) 725, Vulkan 1.3.295',
        'driver': 'Qualcomm Technologies Inc. Adreno Vulkan Driver', 'driver_kind': 'system',
        'game': 'The Legend of Zelda: Ocarina of Time 0400486024DEC000 version 131072'}
TURNIP = {**QCOM, 'driver': 'turnip Mesa driver 26.0.0', 'driver_kind': 'custom (DM_VK_DRIVER Turnip_Gen8_V39)'}
PANVK = {**QCOM, 'gpu': 'Mali-G710 MC7, Vulkan 1.3', 'driver': 'Mesa 25.1 PanVK', 'driver_kind': 'custom (DM_VK_DRIVER PanVK_v1.1)'}


def log(source, rule='gpu-hang', doc='docs/reference/rendering.md#x', header=None, symptoms=(), poster='alice', link='https://example.test/i/1#c2'):
    return {'file': '/tmp/x/issue-a.log', 'source': source, 'poster': poster, 'link': link, 'digest': {
        'header': header if header is not None else QCOM, 'stage': {'number': 8, 'name': 'frame loop'}, 'perf': {'fps_game': {'median': 30.1}},
        'symptoms': [{'heading': hd, 'doc': dc} for hd, dc in symptoms],
        'verdict': {'kind': 'driver_lost', 'label': 'driver lost', 'sentence': 'The GPU driver stopped responding.', 'rule': rule,
                    'cause': 'The GPU stopped.' if rule else None, 'meaning': 'Try another GPU driver.' if rule else None, 'doc': doc if rule else None, 'line': 5}}}


class Body(unittest.TestCase):
    def render(self, text):
        return unittest.mock.patch.object(I.log_digest, 'render', lambda d: text)

    def test_no_log_no_comment(self):
        self.assertIsNone(C.body(1, {'logs': []}))

    def test_mark_summary_and_link(self):
        with self.render('== log digest\n-- verdict\n'):
            b = C.body(82, {'logs': [log('attached in issue: A.log')]})
        self.assertTrue(b.startswith(I.BOT_MARK))
        self.assertIn('- **alice** · [A.log](https://example.test/i/1#c2) · Banana Native 0.3.14 · Xiaomi 23049PCD8G (Android 17), Adreno 725 · '
                      'Qualcomm system driver · The Legend of Zelda: Ocarina of Time (`0400486024DEC000`)\n'
                      '  **Driver lost** (in game, about 30 fps before it): The GPU stopped.', b)
        self.assertIn('**What you can try**\n\n- Try another GPU driver.', b)
        self.assertIn(f'({I.FORGEJO}/{I.REPO}/src/branch/{C.BRANCH}/docs/reference/rendering.md#x)', b)
        self.assertIn('<summary>Digest of A.log</summary>', b)
        self.assertNotIn('/tmp/x', b)

    def test_same_ending_said_once(self):
        with self.render('x\n'):
            b = C.body(1, {'logs': [log('attached in issue: A.log'), log('attached in c1: B.log')]})
        self.assertIn('Both logs end the same way: **driver lost**.', b)
        self.assertEqual(b.count('- Try another GPU driver.'), 1)
        self.assertEqual(b.count('rendering.md#x)'), 1)

    def test_references_by_driver(self):
        turnip_doc = 'docs/reference/rendering.md#turnip-hangs-in-the-first-3d-frame-of-a-cold-run'
        sym = [('A geometry shader that emits nothing hangs Qualcomm\'s driver (Android)', 'docs/reference/rendering.md#qc')]
        with self.render('x\n'):
            qc = C.body(1, {'logs': [log('pasted in issue', doc=turnip_doc, symptoms=sym)]})
            tu = C.body(1, {'logs': [log('pasted in issue', doc=turnip_doc, symptoms=sym, header=TURNIP)]})
            pv = C.body(1, {'logs': [log('pasted in issue', doc=turnip_doc, header=PANVK)]})
        self.assertNotIn('turnip-hangs', qc); self.assertIn('rendering.md#qc)', qc)
        self.assertIn('turnip-hangs', tu); self.assertNotIn('rendering.md#qc)', tu)
        self.assertNotIn('turnip-hangs', pv)
        self.assertIn('· Turnip_Gen8_V39 (custom) ·', tu)
        self.assertIn('· driver PanVK_v1.1 (custom) ·', pv)

    def test_fence_outlasts_backticks(self):
        with self.render('line ```` four\n'):
            b = C.body(1, {'logs': [log('pasted in issue', rule=None)]})
        self.assertIn('`````text\n', b); self.assertNotIn('What you can try', b)

    def test_cut_to_limit(self):
        with self.render('x' * 50 + '\n' + ('y' * 99 + '\n') * 2000):
            b = C.body(1, {'logs': [log('attached in issue: A.log'), log('attached in c1: B.log')]})
        self.assertLess(len(b), 65535)
        self.assertEqual(b.count('[… cut to fit the comment]'), 2)


class Main(unittest.TestCase):
    def test_open_adds_open_issues_once(self):
        done = []
        with unittest.mock.patch.object(C, 'open_issues', lambda: [3, 5, 9]), unittest.mock.patch.object(C, 'one', lambda n, dry: done.append(n) or 0):
            self.assertEqual(C.main(['5', '--open', '--dry-run']), 0)
        self.assertEqual(done, [5, 3, 9])

    def test_worst_exit_code(self):
        with unittest.mock.patch.object(C, 'one', lambda n, dry: 2 if n == 4 else 0):
            self.assertEqual(C.main(['1', '4', '6']), 2)

    def test_open_issues_pages_and_skips_pulls(self):
        pages = {1: [{'number': i} for i in range(50, 0, -1)], 2: [{'number': 70}, {'number': 71, 'pull_request': {'merged': False}}]}
        with unittest.mock.patch.object(I, 'api', lambda path: pages[int(path.split('page=')[1].split('&')[0])]):
            got = C.open_issues()
        self.assertEqual(got[:2], [1, 2]); self.assertEqual(got[-1], 70); self.assertEqual(len(got), 51)


class Comments(unittest.TestCase):
    def test_bot_comment_left_out(self):
        page = [{'id': 1, 'body': 'hi'}, {'id': 2, 'body': I.BOT_MARK + '\n  L1: [dm] [RED] x'}]
        with unittest.mock.patch.object(I, 'api', lambda path: page):
            self.assertEqual([c['id'] for c in I.comments(5)], [1])


if __name__ == '__main__':
    unittest.main()
