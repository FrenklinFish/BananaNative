#!/usr/bin/env python3
"""dm/tools/issue_digest_comment.py without the network: the comment's text (the mark, one summary line per log, a fence no log line can close, the cut to
Forgejo's limit, nothing for an issue without a log), and issue_digest.py leaving the bot's own comment out."""
import sys
import unittest
import unittest.mock
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import issue_digest_comment as C   # noqa: E402

I = C.I


def log(source, rule='gpu-hang', doc='docs/reference/rendering.md#x', lines=10):
    return {'file': '/tmp/x/issue-a.log', 'source': source, 'digest': {
        'verdict': {'label': 'driver lost', 'sentence': 'The GPU driver stopped responding.', 'rule': rule, 'cause': 'The GPU stopped.', 'doc': doc, 'line': 5}}}


class Body(unittest.TestCase):
    def render(self, text):
        return unittest.mock.patch.object(I.log_digest, 'render', lambda d: text)

    def test_no_log_no_comment(self):
        self.assertIsNone(C.body(1, {'logs': []}))

    def test_mark_summary_and_link(self):
        with self.render('== log digest\n-- verdict\n'):
            b = C.body(82, {'logs': [log('attached in issue: A.log')]})
        self.assertTrue(b.startswith(I.BOT_MARK))
        self.assertIn('- **Attached in issue: A.log**: driver lost: The GPU driver stopped responding.', b)
        self.assertIn(f'({I.FORGEJO}/{I.REPO}/src/branch/{C.BRANCH}/docs/reference/rendering.md#x)', b)
        self.assertIn('<summary>Digest of A.log</summary>', b)
        self.assertNotIn('/tmp/x', b)

    def test_fence_outlasts_backticks(self):
        with self.render('line ```` four\n'):
            b = C.body(1, {'logs': [log('pasted in issue', rule=None)]})
        self.assertIn('`````text\n', b); self.assertNotIn('Likely cause', b)

    def test_cut_to_limit(self):
        with self.render('x' * 50 + '\n' + ('y' * 99 + '\n') * 2000):
            b = C.body(1, {'logs': [log('attached in issue: A.log'), log('attached in c1: B.log')]})
        self.assertLess(len(b), 65535)
        self.assertEqual(b.count('[… cut to fit the comment]'), 2)


class Comments(unittest.TestCase):
    def test_bot_comment_left_out(self):
        page = [{'id': 1, 'body': 'hi'}, {'id': 2, 'body': I.BOT_MARK + '\n  L1: [dm] [RED] x'}]
        with unittest.mock.patch.object(I, 'api', lambda path: page):
            self.assertEqual([c['id'] for c in I.comments(5)], [1])


if __name__ == '__main__':
    unittest.main()
