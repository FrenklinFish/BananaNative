#!/usr/bin/env python3
"""The automatic log analysis on a player's issue: scripts/issue_digest.py's digest of every log in the issue, posted as one comment that later runs edit.

    python3 dm/tools/issue_digest_comment.py <issue number> [--dry-run]

Run by .forgejo/workflows/issue-digest.yml when an issue is opened or edited or gets a comment; --dry-run prints the comment instead (no token needed).
The comment carries BOT_MARK; issue_digest.py leaves such comments out, and a run edits the one it made before (found by the mark and the token's user)
instead of adding another, and does nothing when the text would not change. An issue without a log gets no comment (an earlier one stays).
Each digest is log_digest.py's, redacted as it is (home and app folders shortened, key-like lines dropped). The text is cut to fit Forgejo's limit.
Environment: FORGEJO_TOKEN (a token that may comment on the repository's issues), BN_FORGEJO, BN_FORGEJO_REPO (as issue_digest.py), BN_DOCS_BRANCH
(the branch the doc links point at, default android), BN_ISSUES_DIR (where the logs are fetched to; the workflow gives a temporary folder).
Exit code: 0 (also when nothing is posted), 2 the issue cannot be read or the comment cannot be written."""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import issue_digest as I   # noqa: E402

BRANCH = os.environ.get('BN_DOCS_BRANCH', 'android')
LIMIT = 60000   # Forgejo keeps comments up to 65,535 bytes; room left for the parts around the digests


def doc_url(doc):
    return f'{I.FORGEJO}/{I.REPO}/src/branch/{BRANCH}/{doc}'


def fence(text):
    """A fence longer than any run of backticks in the text, so a log line cannot close it"""
    runs = [len(m) for m in re.findall(r'`+', text)]
    return '`' * max(3, max(runs, default=0) + 1)


def summary_line(log):
    v = log['digest']['verdict']
    s = f"**{log['source'][:1].upper() + log['source'][1:]}**: {v['label']}" + (f": {v['sentence']}" if v.get('sentence') else '')
    if v.get('rule'):
        cause = v.get('cause') or v['rule']
        s += f"\n  Likely cause: {cause}" + (f" ([reference]({doc_url(v['doc'])}))" if v.get('doc') else '')
    return s


def body(n, data):
    """The comment's text, or None when the issue has no log"""
    logs = data['logs']
    if not logs: return None
    parts = [I.BOT_MARK, '### Automatic log analysis', '',
             f'Made by a script from the {"log" if len(logs) == 1 else f"{len(logs)} logs"} in this issue; it is updated when a new log comes in. '
             'A maintainer reads the issue too.', '']
    parts += [f'- {summary_line(lg)}' for lg in logs]
    digests = []
    for lg in logs:
        txt = I.log_digest.render(lg['digest']).rstrip('\n')
        digests.append((lg['source'].split(': ', 1)[-1], txt))
    room = LIMIT - sum(len(p) + 1 for p in parts) - 200 * len(digests)
    each = max(2000, room // max(1, len(digests)))
    for name, txt in digests:
        if len(txt) > each: txt = txt[:each].rsplit('\n', 1)[0] + '\n[… cut to fit the comment]'
        f = fence(txt)
        parts += ['', f'<details><summary>Digest of {name}</summary>', '', f + 'text', txt, f, '', '</details>']
    return '\n'.join(parts) + '\n'


def call(method, path, token, payload=None):
    req = urllib.request.Request(f'{I.FORGEJO}/api/v1/{path}', method=method,
                                 data=json.dumps(payload).encode() if payload is not None else None,
                                 headers={**I.UA, 'Authorization': f'token {token}', 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    return json.loads(data) if data else None


def own_comment(n, token):
    """The comment an earlier run made: carries the mark and, when the token's user can be read, is that user's"""
    try: me = call('GET', 'user', token)['login']
    except (OSError, KeyError, TypeError, ValueError): me = None
    page = 1
    while True:
        batch = I.api(f'issues/{n}/comments?page={page}&limit=50')
        for c in batch:
            if I.BOT_MARK in (c.get('body') or '') and (me is None or c['user']['login'] == me): return c
        if len(batch) < 50: return None
        page += 1


def main(argv=None):
    ap = argparse.ArgumentParser(description='Post the automatic log analysis on a Forgejo issue.')
    ap.add_argument('issue', type=int)
    ap.add_argument('--dry-run', action='store_true', help='print the comment, write nothing')
    a = ap.parse_args(argv)
    try:
        iss = I.api(f'issues/{a.issue}')
        if iss.get('pull_request'): print(f'issue_digest_comment: #{a.issue} is a pull request: nothing to do'); return 0
        data = json.loads(I.report(a.issue, as_json=True))
    except (OSError, ValueError, KeyError) as e:
        print(f'issue_digest_comment: issue {a.issue}: {e}', file=sys.stderr); return 2
    text = body(a.issue, data)
    if a.dry_run:
        sys.stdout.write(text if text else f'(issue #{a.issue} has no log: no comment)\n'); return 0
    if not text: print(f'issue_digest_comment: #{a.issue} has no log: no comment'); return 0
    token = os.environ.get('FORGEJO_TOKEN')
    if not token: print('issue_digest_comment: FORGEJO_TOKEN is not set', file=sys.stderr); return 2
    repo = f'repos/{I.REPO}'
    try:
        old = own_comment(a.issue, token)
        if old and old.get('body', '').strip() == text.strip():
            print(f'issue_digest_comment: #{a.issue}: comment {old["id"]} is current'); return 0
        if old:
            try:
                call('PATCH', f'{repo}/issues/comments/{old["id"]}', token, {'body': text})
                print(f'issue_digest_comment: #{a.issue}: comment {old["id"]} updated'); return 0
            except urllib.error.HTTPError as e:
                if e.code not in (403, 404): raise
        c = call('POST', f'{repo}/issues/{a.issue}/comments', token, {'body': text})
        print(f'issue_digest_comment: #{a.issue}: comment {c["id"]} posted')
    except (OSError, ValueError, KeyError) as e:
        print(f'issue_digest_comment: #{a.issue}: {e}', file=sys.stderr); return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
