#!/usr/bin/env python3
"""A short report of a player's issue on Forgejo: its text and comments without their logs, and a digest of every log in it (pasted in the text or attached).

    python3 scripts/issue_digest.py <issue number> [<issue number> ...] [--since COMMENT_ID] [--json]
    python3 scripts/issue_digest.py --open                          (open issues, one line each, with how many logs each has)

Reads the public API only (no login, nothing is written to Forgejo). The logs are kept in $BN_ISSUES_DIR/<number>/ (default: <system temp>/bananative-issues),
never in this repository: digests show line numbers, and the raw log there is the place to read around one. Logs attached as .log, .txt or .zip (a bug report) are
fetched; the launcher's own .digest.txt attachments are kept beside them; other attachments (videos, pictures) are listed, not fetched.
Each log's digest is dm/tools/log_digest.py's (scripts/log_digest.py), with its graphics section.
Environment: BN_FORGEJO (default https://git.eden-emu.dev), BN_FORGEJO_REPO (default moceure/BananaNative), BN_ISSUES_DIR.
Exit code: 0, 2 when an issue cannot be read."""
import argparse
import json
import os
import re
import sys
import tempfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'dm/tools'))
import log_digest  # noqa: E402

FORGEJO = os.environ.get('BN_FORGEJO', 'https://git.eden-emu.dev').rstrip('/')
REPO = os.environ.get('BN_FORGEJO_REPO', 'moceure/BananaNative')
OUT = Path(os.environ.get('BN_ISSUES_DIR') or Path(tempfile.gettempdir()) / 'bananative-issues')
FETCH_EXT = ('.log', '.txt', '.zip')
MAX_BYTES = 64 << 20
LOG_HINT = re.compile(r'^\[(nxlaunch|dm)\] ', re.M)
UA = {'User-Agent': 'bananative-issue-digest', 'Accept': 'application/json'}


def get(url, raw=False):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        data = r.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES: raise OSError(f'{url}: over {MAX_BYTES >> 20} MiB')
    return data if raw else json.loads(data)


def api(path): return get(f'{FORGEJO}/api/v1/repos/{REPO}/{path}')


BOT_MARK = '<!-- bananative-issue-digest -->'   # dm/tools/issue_digest_comment.py's own comment: left out, it holds digests, not logs


def comments(n):
    out, page = [], 1
    while True:
        batch = api(f'issues/{n}/comments?page={page}&limit=50')
        out += [c for c in batch if BOT_MARK not in (c.get('body') or '')]
        if len(batch) < 50: return out
        page += 1


def safe_name(s): return re.sub(r'[^A-Za-z0-9._-]+', '_', s)[:120] or 'file'


LOG_START = re.compile(r'^(```[\w-]*)?\[?(nxlaunch|dm)\] ')
LOG_END = re.compile(r'^(### |</?details>|<summary>|```\s*$)')


def split_body(text, where, d, logs):
    """The text with each pasted log (a run of [nxlaunch] / [dm] lines, fenced or not, up to a heading, </details> or a closing fence) saved to a file and
    replaced by a pointer"""
    lines, out, i = (text or '').replace('\r\n', '\n').split('\n'), [], 0
    while i < len(lines):
        m = LOG_START.match(lines[i])
        if not m: out.append(lines[i]); i += 1; continue
        j = i
        while j < len(lines) and (j == i or not LOG_END.match(lines[j])): j += 1
        block = lines[i:j]
        block[0] = LOG_START.sub(lambda mm: f'[{mm.group(2)}] ', block[0], count=1)
        if sum(1 for x in block if LOG_START.match(x)) < 3: out += lines[i:j]; i = j; continue
        if out and re.match(r'^```[\w-]*\s*$', out[-1]): out.pop()
        p = d / f'{where}-pasted-{len(logs) + 1}.log'
        p.write_text('\n'.join(block) + '\n', encoding='utf-8')
        logs.append((p, f'pasted in {where}'))
        out.append(f'[log pasted here: {len(block)} lines → {p.name}]')
        i = j + (1 if j < len(lines) and lines[j].startswith('```') else 0)
    text = '\n'.join(out)
    text = re.sub(r'<details>\s*<summary>[^<]*</summary>\s*(\[log pasted here[^\]]*\])\s*</details>', r'\1', text)
    return re.sub(r'\n{3,}', '\n\n', text).strip()


def assets(items, where, d, logs, others):
    for a in items or []:
        name, size, url = a.get('name', ''), a.get('size', 0), a.get('browser_download_url', '')
        low = name.lower()
        if not low.endswith(FETCH_EXT) or size > MAX_BYTES:
            others.append(f'{where}: {name} ({size / 1e6:.1f} MB) {url}'); continue
        p = d / f'{where}-{safe_name(name)}'
        if not (p.exists() and p.stat().st_size == size): p.write_bytes(get(url, raw=True))
        if low.endswith('.digest.txt'): others.append(f'{where}: {name} (the launcher\'s digest; ours below is fuller) → {p.name}')
        else: logs.append((p, f'attached in {where}: {name}'))


def report(n, since=0, as_json=False):
    iss = api(f'issues/{n}')
    d = OUT / str(n); d.mkdir(parents=True, exist_ok=True)
    logs, others, o = [], [], []
    o.append(f"== issue #{n}: {iss['title']}")
    o.append(f"   {iss['state']}, by {iss['user']['login']} {iss['created_at']}, labels: {', '.join(l['name'] for l in iss.get('labels') or []) or 'none'}; "
             f"{FORGEJO}/{REPO}/issues/{n}; files in {d}")
    who = {'issue': (iss['user']['login'], iss.get('html_url') or f'{FORGEJO}/{REPO}/issues/{n}')}   # where a log came from -> (poster, link)
    body = split_body(iss.get('body'), 'issue', d, logs)
    assets(iss.get('assets'), 'issue', d, logs, others)
    if not since: o += ['-- text', body]
    for c in comments(n):
        if c['id'] <= since: continue
        where = f"c{c['id']}"
        who[where] = (c['user']['login'], c.get('html_url') or f'{FORGEJO}/{REPO}/issues/{n}#issuecomment-{c["id"]}')
        text = split_body(c.get('body'), where, d, logs)
        assets(c.get('assets'), where, d, logs, others)
        o += [f"-- comment {c['id']} by {c['user']['login']} {c['created_at']}", text]
    if others: o.append('-- other attachments'); o += [f'  {x}' for x in others]
    digests, seen = [], {}
    for p, src in logs:
        try: head = tuple(l.rstrip('\r\n') for l in p.read_text(encoding='utf-8', errors='replace').split('\n')[:200] if l.strip())
        except OSError: head = None
        if head and not p.name.endswith('.zip'):
            twin = next((q for h, q in seen.items() if h[:len(head)] == head or head[:len(h)] == h), None)
            if twin: o.append(f'-- {p.name} ({src}): the same log as {twin.name} (its first 200 lines), not digested again'); continue
            seen[head] = p
        try:
            dg = log_digest.digest(log_digest.resolve(str(p)), tail=8)
        except (OSError, KeyError, UnicodeError, ValueError) as e:
            o.append(f'-- {p.name} ({src}): cannot read: {e}'); continue
        txt = log_digest.render(dg)
        (p.parent / (p.name + '.digest')).write_text(txt, encoding='utf-8')
        poster, link = who.get(p.name.split('-', 1)[0], (None, None))
        digests.append({'file': str(p), 'source': src, 'poster': poster, 'link': link, 'digest': dg})
        o.append(f'-- log {p.name} ({src})')
        o.append(txt.rstrip('\n'))
    if as_json: return json.dumps({'issue': n, 'title': iss['title'], 'dir': str(d), 'text': body, 'logs': digests}, indent=1, ensure_ascii=False) + '\n'
    return '\n'.join(o) + '\n'


def open_list():
    out, page = [], 1
    while True:
        batch = api(f'issues?state=open&type=issues&page={page}&limit=50')
        for i in batch:
            n_logs = len(LOG_HINT.findall(i.get('body') or '')) >= 3
            att = sum(1 for a in i.get('assets') or [] if a['name'].lower().endswith(FETCH_EXT))
            out.append(f"#{i['number']:<4} {i['updated_at'][:16]} {i['comments']:>2} comments  logs: {int(n_logs) + att}+  {i['title'][:90]}")
        if len(batch) < 50: return '\n'.join(out) + '\n'
        page += 1


def main(argv=None):
    ap = argparse.ArgumentParser(description='A short report of a Forgejo issue with a digest of every log in it.')
    ap.add_argument('issues', nargs='*', type=int, help='issue numbers')
    ap.add_argument('--open', action='store_true', help='list the open issues instead')
    ap.add_argument('--since', type=int, default=0, metavar='COMMENT_ID', help='only comments after this one (and their logs); the issue text is left out')
    ap.add_argument('--json', action='store_true', help='JSON instead of text')
    a = ap.parse_args(argv)
    if not a.issues and not a.open: ap.error('give an issue number or --open')
    rc = 0
    try:
        if a.open: sys.stdout.write(open_list())
        for n in a.issues:
            try: sys.stdout.write(report(n, a.since, a.json))
            except (OSError, ValueError, KeyError) as e: print(f'issue_digest: issue {n}: {e}', file=sys.stderr); rc = 2
    except OSError as e:
        print(f'issue_digest: {e}', file=sys.stderr); return 2
    return rc


if __name__ == '__main__':
    sys.exit(main())
