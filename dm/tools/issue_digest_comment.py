#!/usr/bin/env python3
"""The automatic log analysis on a player's issue: scripts/issue_digest.py's digest of every log in the issue, posted as one comment that later runs edit.

    python3 dm/tools/issue_digest_comment.py <issue number> ... [--open] [--dry-run]

Run by .forgejo/workflows/issue-digest.yml when an issue is opened or edited or gets a comment, and with --open (every open issue) when the workflow is
run by hand (Actions › issue-digest › Run workflow); --dry-run prints the comment instead (no token needed).
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


GAME_RX = re.compile(r'^(.*?)\s*\b([0-9A-Fa-f]{16})\b')
VK_DRIVER_ID = {8: 'Qualcomm', 18: 'Turnip'}   # VkDriverId: QUALCOMM_PROPRIETARY, MESA_TURNIP


def vendor(h):
    """Qualcomm or Turnip (or None): by the driver ID the run reported, else by the driver's description"""
    if h.get('driver_id') in VK_DRIVER_ID: return VK_DRIVER_ID[h['driver_id']]
    d, g = (h.get('driver') or '').lower(), (h.get('gpu') or '').lower()
    if 'qualcomm' in d: return 'Qualcomm'
    return 'Turnip' if 'turnip' in d or ('mesa' in d and 'adreno' in g) else None


def driver_label(h):
    """'Qualcomm system driver', 'Qualcomm driver v863.1 (custom)', 'Turnip v39 (custom)'"""
    v, k = vendor(h), h.get('driver_kind') or ''
    m = re.match(r'custom \((?:DM_VK_DRIVER )?(.*)\)$', k)
    if k == 'system': return f'{v} system driver' if v else 'system driver'
    if m: return (f'{v} ' if v and v.lower() not in m.group(1).lower() else '') + ('' if v == 'Turnip' else 'driver ') + f'{m.group(1)} (custom)'
    return f'{v} driver' if v else None


def device_label(dev):
    """'Xiaomi 23049PCD8G, Android 17 (SDK 37), arm64-v8a' -> 'Xiaomi 23049PCD8G (Android 17)'"""
    if not dev: return None
    bits = [b.strip() for b in dev.split(',')]
    android = next((re.sub(r'\s*\(SDK \d+\)', '', b) for b in bits[1:] if b.startswith('Android')), None)
    return bits[0] + (f' ({android})' if android else '')


def game_label(h):
    m = GAME_RX.match(h.get('game') or '')
    if not m: return h.get('game')
    return f'{m.group(1)} (`{m.group(2).upper()}`)' if m.group(1) else f'`{m.group(2).upper()}`'


def fits(ref_text, h):
    """A reference written for one driver (its heading names Turnip or Qualcomm) is kept only for a run on that driver"""
    v, t = vendor(h), ref_text.lower()
    return ('turnip' not in t or v == 'Turnip') and ('qualcomm' not in t or v == 'Qualcomm')


def references(d):
    """(heading, doc) of the notes that may explain the run: symptom-index matches first, then the verdict's rule, those for the other driver left out"""
    h, out = d.get('header') or {}, []
    for x in d.get('symptoms') or []:
        out.append((x['heading'], x['doc']))
    v = d['verdict']
    if v.get('doc'):
        anchor = v['doc'].split('#', 1)[-1].replace('-', ' ')
        out.append((anchor[:1].upper() + anchor[1:], v['doc']))
    seen, keep = set(), []
    for head, doc in out:
        if doc in seen or not fits(head + ' ' + doc, h): continue
        seen.add(doc); keep.append((head, doc))
    return keep


def where_label(log):
    name = log['source'].split(': ', 1)[1] if ': ' in log['source'] else 'pasted log'
    return f"[{name}]({log['link']})" if log.get('link') else name


def summary_line(log):
    d = log['digest']; h, v, s, p = d.get('header') or {}, d['verdict'], d.get('stage') or {}, d.get('perf') or {}
    who = [f"**{log['poster']}**" if log.get('poster') else None, where_label(log),
           f"Banana Native {h['app_version'].split(' ')[0]}" if h.get('app_version') else None,
           ', '.join(x for x in (device_label(h.get('device')), (h.get('gpu') or '').split(',')[0].replace(' (TM)', '')) if x) or None,
           driver_label(h), game_label(h)]
    line = ' · '.join(x for x in who if x)
    when = 'in game' if s.get('number') == 8 else f"before the game started (stage {s['number']}, {s['name']})" if s else None
    fps = p.get('fps_game', {}).get('median')
    ctx = ', '.join(x for x in (when, f'about {fps:.0f} fps before it' if fps and v['kind'] not in ('normal_exit', 'stopped') else None) if x)
    what = v.get('cause') or v.get('sentence') or ''
    line += f"\n  **{v['label'][:1].upper() + v['label'][1:]}**" + (f' ({ctx})' if ctx else '') + (f': {what}' if what else '')
    return line


def body(n, data):
    """The comment's text, or None when the issue has no log"""
    logs = data['logs']
    if not logs: return None
    parts = [I.BOT_MARK, '### Automatic log analysis', '',
             f'Made by a script from the {"log" if len(logs) == 1 else f"{len(logs)} logs"} in this issue; it is updated when a new log comes in. '
             'A maintainer reads the issue too.', '']
    kinds = {(lg['digest']['verdict']['kind'], lg['digest']['verdict'].get('rule')) for lg in logs}
    if len(logs) > 1 and len(kinds) == 1:
        parts += [f"{'Both' if len(logs) == 2 else f'All {len(logs)}'} logs end the same way: **{logs[0]['digest']['verdict']['label']}**.", '']
    parts += [f'- {summary_line(lg)}' for lg in logs]
    tips = list(dict.fromkeys(lg['digest']['verdict']['meaning'] for lg in logs if lg['digest']['verdict'].get('meaning')))
    if tips: parts += ['', '**What you can try**', ''] + [f'- {t}' for t in tips]
    refs = list(dict.fromkeys(r for lg in logs for r in references(lg['digest'])))
    if refs: parts += ['', '**Notes that may match** (for maintainers)', ''] + [f'- [{head}]({doc_url(doc)})' for head, doc in refs]
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


def open_issues():
    """Numbers of the open issues (not pull requests), oldest first"""
    out, page = [], 1
    while True:
        batch = I.api(f'issues?state=open&type=issues&page={page}&limit=50')
        out += [i['number'] for i in batch if not i.get('pull_request')]
        if len(batch) < 50: return sorted(out)
        page += 1


def one(n, dry_run):
    """Digest one issue and post or update its comment; the exit code"""
    try:
        iss = I.api(f'issues/{n}')
        if iss.get('pull_request'): print(f'issue_digest_comment: #{n} is a pull request: nothing to do'); return 0
        data = json.loads(I.report(n, as_json=True))
    except (OSError, ValueError, KeyError) as e:
        print(f'issue_digest_comment: issue {n}: {e}', file=sys.stderr); return 2
    text = body(n, data)
    if dry_run:
        sys.stdout.write(text if text else f'(issue #{n} has no log: no comment)\n'); return 0
    if not text: print(f'issue_digest_comment: #{n} has no log: no comment'); return 0
    token = os.environ.get('FORGEJO_TOKEN')
    if not token: print('issue_digest_comment: FORGEJO_TOKEN is not set', file=sys.stderr); return 2
    repo = f'repos/{I.REPO}'
    try:
        old = own_comment(n, token)
        if old and old.get('body', '').strip() == text.strip():
            print(f'issue_digest_comment: #{n}: comment {old["id"]} is current'); return 0
        if old:
            try:
                call('PATCH', f'{repo}/issues/comments/{old["id"]}', token, {'body': text})
                print(f'issue_digest_comment: #{n}: comment {old["id"]} updated'); return 0
            except urllib.error.HTTPError as e:
                if e.code not in (403, 404): raise
        c = call('POST', f'{repo}/issues/{n}/comments', token, {'body': text})
        print(f'issue_digest_comment: #{n}: comment {c["id"]} posted')
    except (OSError, ValueError, KeyError) as e:
        print(f'issue_digest_comment: #{n}: {e}', file=sys.stderr); return 2
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description='Post the automatic log analysis on Forgejo issues.')
    ap.add_argument('issue', type=int, nargs='*', help='issue numbers')
    ap.add_argument('--open', action='store_true', help='every open issue (the manual run of the workflow): issues without a log are skipped')
    ap.add_argument('--dry-run', action='store_true', help='print the comment, write nothing')
    a = ap.parse_args(argv)
    try: issues = a.issue + ([n for n in open_issues() if n not in a.issue] if a.open else [])
    except (OSError, ValueError, KeyError) as e:
        print(f'issue_digest_comment: the open issues cannot be read: {e}', file=sys.stderr); return 2
    if not issues: ap.error('give issue numbers or --open')
    return max(one(n, a.dry_run) for n in issues)


if __name__ == '__main__':
    sys.exit(main())
