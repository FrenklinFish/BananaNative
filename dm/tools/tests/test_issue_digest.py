#!/usr/bin/env python3
"""scripts/issue_digest.py without the network: pasted logs (fenced, unfenced, a fence glued to the first line) are cut out of an issue's text into files, the
issue template's own lines stay out of them, a log pasted and also attached is digested once, and other attachments are listed, not fetched."""
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'scripts'))
import issue_digest as I   # noqa: E402

LOG = [f'[nxlaunch] env X{i}=1' for i in range(3)] + ['[dm] main: base 0x1', '[dm] [vk] lower vegetation detail: 1 mesh draws omitted']


class Body(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.TemporaryDirectory(); self.d = Path(self.t.name)

    def tearDown(self): self.t.cleanup()

    def split(self, text):
        logs = []
        return I.split_body(text, 'issue', self.d, logs), logs

    def test_unfenced_up_to_details_end(self):
        text, logs = self.split('### Log\n\n' + '\r\n'.join(LOG) + '\n<summary>Log (click to expand)</summary>\n\n```text\npaste the log here\n```\n</details>\n\n### Anything else\nno')
        self.assertEqual(len(logs), 1)
        self.assertEqual(logs[0][0].read_text().splitlines(), LOG)
        self.assertIn('[log pasted here: 5 lines → issue-pasted-1.log]', text)
        self.assertIn('### Anything else', text); self.assertNotIn('[dm] main', text)

    def test_fenced_and_glued_fence(self):
        text, logs = self.split('<details>\n<summary>Log</summary>\n\n```' + LOG[0][1:] + '\n' + '\n'.join(LOG[1:]) + '\n```\n</details>\n\nafter')
        self.assertEqual(logs[0][0].read_text().splitlines(), LOG)
        self.assertEqual(text, '[log pasted here: 5 lines → issue-pasted-1.log]\n\nafter')

    def test_short_blocks_stay(self):
        text, logs = self.split('see this line:\n[dm] [RED] one line\nthat is all')
        self.assertEqual(logs, []); self.assertIn('[dm] [RED] one line', text)

    def test_report_dedupes_and_lists(self):
        issue = {'title': 'T', 'state': 'open', 'user': {'login': 'p'}, 'created_at': 'now', 'labels': [], 'body': '\n'.join(LOG),
                 'assets': [{'name': 'run.log', 'size': 10, 'browser_download_url': 'u1'}, {'name': 'clip.mp4', 'size': 5_000_000, 'browser_download_url': 'u2'}]}
        def fake_get(url, raw=False):
            if raw: assert url == 'u1'; return ('\n'.join(LOG) + '\n').encode()
            return issue if url.endswith('/issues/7') else []
        with unittest.mock.patch.object(I, 'get', fake_get), unittest.mock.patch.object(I, 'OUT', self.d):
            out = I.report(7)
        self.assertIn('== issue #7: T', out)
        self.assertEqual(out.count('== log digest'), 1)
        self.assertIn('the same log as issue-pasted-1.log', out)
        self.assertIn('clip.mp4 (5.0 MB) u2', out)
        self.assertIn('-- graphics', out)


if __name__ == '__main__':
    unittest.main()
