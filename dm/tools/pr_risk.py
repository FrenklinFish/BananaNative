#!/usr/bin/env python3
"""What in a contributor's change could harm a player or the maintainer's computer, for a reviewer to read line by line (docs/agents/07-pr-review.md).

The check lists, per kind, every added line and every path of a diff that does one of these:
  network      reaches the network from code: a URL outside docs, a socket, an HTTP client, curl / wget
  run          starts another program or evaluates code: exec, ProcessBuilder, system, popen, subprocess, eval
  load         loads code at run time: DexClassLoader and kin, System.load / loadLibrary, dlopen
  android      asks Android for more: a new permission, an exported component, package installs
  host-build   runs on the maintainer's computer when it builds or packs: gradle files, scripts, makefiles, workflow files
  updater      touches the app's own updater or its downloads (UpdateCheck, GpuDriver downloads, release URLs)
  secrets      reads tokens, passwords or the keychain, or sends an Authorization header
  delete       removes files broadly: rm -rf, deleteRecursively, a wipe of a folder
  opaque       is hard to read: a long base64 or hex run, a numeric array of generated data, a very long line, char-code tricks, a binary file
A finding is not a verdict: most are fine (a PR that adds a driver download has to touch the network). Each one is read and said in the
review report (fine, and why; or a finding). It never replaces reading the whole diff.

Usage:  pr_risk.py <base> <head>      the diff of <head> against its merge-base with <base> (git diff base...head), as a review takes it
        pr_risk.py --diff <file>      a unified diff from a file ('-' for stdin)
Exit 0 when nothing is flagged, 1 when something is (the report lists it), 2 on a usage or git error.
"""
import re
import subprocess
import sys

DOC = re.compile(r'\.(md|txt|tsv)$|(^|/)LICEN[CS]E|(^|/)NOTICE')
HOST_BUILD = re.compile(r'\.gradle(\.kts)?$|gradle\.properties$|gradle-wrapper\.|(^|/)gradlew(\.bat)?$|\.(sh|bat|cmd|command|ps1|mk|cmake|csproj)$|(^|/)Makefile$|'
                        r'CMakeLists\.txt$|(^|/)setup\.py$|pyproject\.toml$|(^|/)requirements[^/]*\.txt$|package(-lock)?\.json$|Directory\.Build\.|'
                        r'(^|/)\.(github|forgejo|gitea)/workflows/|(^|/)scripts/|(^|/)\.git(attributes|modules)$')
UPDATER = re.compile(r'(?i)(update|download|release)[^/]*\.(java|kt|py|sh)$|GpuDriver[^/]*\.java$')
OWN_HOSTS = ('git.eden-emu.dev/moceure/BananaNative', 'github.com/moceure/BananaNative', 'schemas.android.com', 'www.w3.org', 'apache.org/licenses',
             'www.gnu.org/licenses', 'opensource.org/licenses', 'spdx.org')

LINE = [   # (kind, pattern, applies to docs too)
    ('network', re.compile(r'\bhttps?://[^\s"\'<>)]+'), False),
    ('network', re.compile(r'\b(HttpURLConnection|HttpsURLConnection|OkHttp\w*|URLConnection|new\s+URL\s*\(|Socket\s*\(|DatagramSocket|WebView|'
                           r'urllib|requests\.(get|post)|http\.client|socket\.socket|getaddrinfo|connect\s*\(\s*\w+\s*,\s*\(struct sockaddr)'), False),
    ('network', re.compile(r'(^|[\s;|&`$(])(curl|wget|nc|ncat|scp|rsync|ssh)\s'), False),
    ('run', re.compile(r'\b(Runtime\.getRuntime\(\)\.exec|ProcessBuilder|popen|system\s*\(|execv\w*\s*\(|execl\w*\s*\(|posix_spawn\w*|fork\s*\(\s*\)|'
                       r'subprocess\.|os\.system|os\.exec\w*|eval\s*\(|exec\s*\(|ScriptEngine)'), False),
    ('run', re.compile(r'(\|\s*(ba|z)?sh\b|\bsh\s+-c\b|\bbash\s+-c\b|\bchmod\s+\+?[0-7]*x)'), False),
    ('load', re.compile(r'\b(DexClassLoader|PathClassLoader|InMemoryDexClassLoader|BaseDexClassLoader|URLClassLoader|System\.load(Library)?\s*\(|'
                        r'dlopen\s*\(|android_dlopen_ext|ctypes\.(CDLL|cdll)|importlib\.import_module|__import__\s*\()'), False),
    ('android', re.compile(r'<uses-permission|android:exported\s*=\s*"true"|REQUEST_INSTALL_PACKAGES|PackageInstaller|ACTION_INSTALL_PACKAGE|'
                           r'android:sharedUserId|android:debuggable\s*=\s*"true"|usesCleartextTraffic\s*=\s*"true"|BIND_ACCESSIBILITY_SERVICE|'
                           r'DeviceAdminReceiver|QUERY_ALL_PACKAGES|SYSTEM_ALERT_WINDOW|<provider\b'), False),
    ('secrets', re.compile(r'(?i)(\btoken\b|password|passwd|secret|api[_-]?key|find-generic-password|keychain|Authorization\s*:|Bearer\s|'
                           r'\.ssh/|id_rsa|NX_SIGN_|release\.jks|keystore)'), False),
    ('delete', re.compile(r'\brm\s+-[a-zA-Z]*r[a-zA-Z]*f?\b|\brm\s+-[a-zA-Z]*f[a-zA-Z]*r\b|deleteRecursively|shutil\.rmtree|FileUtils\.delete|'
                          r'rmdir\s*/s|Remove-Item\s.*-Recurse|nftw\s*\('), False),
    ('opaque', re.compile(r'[A-Za-z0-9+/]{120,}={0,2}'), False),
    ('opaque', re.compile(r'(\\x[0-9a-fA-F]{2}){24,}|(0x[0-9a-fA-F]{2}\s*,\s*){48,}|String\.fromCharCode|\bchr\s*\(\s*\d+\s*\)\s*\+|'
                          r'base64\s*(-d|--decode)|b64decode|Base64\.decode|atob\s*\('), False),
]
LONG_LINE = 400
DATA = re.compile(r'^\s*((0x[0-9a-fA-F]+|\d+)[uUlL]*\s*,\s*){4,}((0x[0-9a-fA-F]+|\d+)[uUlL]*)?\s*,?\s*$')   # a line of a numeric array
DATA_MIN = 20   # lines of it in one file: generated data (a shader, a table, an image), one finding for the file


def parse(diff):
    """{path: [(line number in the new file, text)]} of the added lines, and the paths a diff adds, changes, renames or deletes"""
    added, paths, cur, n = {}, [], None, 0
    for l in diff.splitlines():
        if l.startswith('diff --git '):
            m = re.match(r'diff --git a/(.*) b/(.*)$', l)
            cur = m.group(2) if m else None
            if cur: paths.append(cur); added.setdefault(cur, [])
        elif l.startswith('Binary files ') and cur: added[cur].append((0, '(binary file)'))
        elif l.startswith('@@'):
            m = re.match(r'@@ -\S+ \+(\d+)', l); n = int(m.group(1)) if m else 0
        elif l.startswith('+++') or l.startswith('---'): continue
        elif l.startswith('+') and cur is not None: added[cur].append((n, l[1:])); n += 1
        elif l.startswith(' '): n += 1
    return added, paths


def findings(diff):
    """[(kind, path, line, text)] in the order of the diff"""
    added, paths = parse(diff)
    out = []
    for p in paths:
        if HOST_BUILD.search(p): out.append(('host-build', p, 0, 'runs on the computer that builds or packs'))
        if UPDATER.search(p): out.append(('updater', p, 0, "the app's updater or its downloads"))
        doc = bool(DOC.search(p))
        data = [n for n, t in added.get(p, []) if DATA.match(t)]
        if len(data) >= DATA_MIN: out.append(('opaque', p, data[0], f'{len(data)} lines of numeric array data: rebuild it from its source here and compare byte for byte'))
        data = set(data) if len(data) >= DATA_MIN else set()
        for n, t in added.get(p, []):
            if n in data: continue
            if t == '(binary file)': out.append(('opaque', p, 0, 'a binary file: no readable diff')); continue
            seen = set()
            for kind, rx, in_docs in LINE:
                if (doc and not in_docs) or kind in seen: continue
                m = rx.search(t)
                if not m: continue
                if kind == 'network' and m.group(0).startswith('http') and any(h in m.group(0) for h in OWN_HOSTS): continue
                seen.add(kind); out.append((kind, p, n, t.strip()))
            if not doc and len(t) > LONG_LINE and 'opaque' not in seen: out.append(('opaque', p, n, f'a line of {len(t)} characters'))
    return out


def report(fs):
    if not fs: return 'pr_risk: nothing flagged (still read the whole diff)'
    kinds = sorted({k for k, *_ in fs}, key=lambda k: [x[0] for x in LINE].index(k) if k in [x[0] for x in LINE] else -1)
    lines = [f'pr_risk: {len(fs)} lines to read, each said in the review report (fine and why, or a finding):']
    for k in kinds:
        lines.append(f'  {k}:')
        for kind, p, n, t in fs:
            if kind == k: lines.append(f'    {p}{":" + str(n) if n else ""}: {t[:160]}{"…" if len(t) > 160 else ""}')
    return '\n'.join(lines)


def main(argv):
    if len(argv) == 3 and argv[1] == '--diff':
        diff = sys.stdin.read() if argv[2] == '-' else open(argv[2], encoding='utf-8', errors='replace').read()
    elif len(argv) == 3:
        r = subprocess.run(['git', 'diff', '--no-color', '--no-ext-diff', '-M', f'{argv[1]}...{argv[2]}'], capture_output=True, text=True, errors='replace')
        if r.returncode: print(r.stderr.strip() or 'git diff failed', file=sys.stderr); return 2
        diff = r.stdout
    else:
        print(__doc__.split('Usage:')[1].split('Exit')[0].strip(), file=sys.stderr); return 2
    fs = findings(diff)
    print(report(fs))
    return 1 if fs else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
