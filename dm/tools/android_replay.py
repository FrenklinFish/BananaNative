#!/usr/bin/env python3
"""The phone's frame replay loop (android/TESTING.md "Frame capture and replay"): build the replayer, push it, run it on a capture, pull its pictures.

  android_replay.py build                      # cmake --build of libnxhost.so + nxreplay (NDK, the GAME profile) into --build
  android_replay.py push                       # both to /data/local/tmp/nxreplay on the phone (no APK install)
  android_replay.py run <capture> [args...]    # adb shell: nxreplay <capture> args (a capture name alone is looked up in the phone's captures folder)
  android_replay.py pull <dir>                 # the PNGs the replays wrote beside the captures, into dir
  android_replay.py all <capture> [args...]    # build, push, run

The phone is shared: hold the lock folder while running (android/HANDOFF.md). The replayer touches nothing of the app: it runs as the shell user from /data/local/tmp/nxreplay,
reads the capture and the pack's shader library from /sdcard, keeps its own pipeline cache there (seeded once from the app's), and writes PNGs beside the capture."""
import argparse, os, shlex, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SDK = os.path.expanduser('~/Library/Android/sdk')
NDK = os.path.join(SDK, 'ndk', '28.2.13676358')
CMAKE = os.path.join(SDK, 'cmake', '3.31.5', 'bin')
PHONE_DIR = '/data/local/tmp/nxreplay'
CAPTURES = '/sdcard/Switch2/{game}-work/captures'


def sh(cmd, **kw):
    print('+ ' + ' '.join(shlex.quote(c) for c in cmd), flush=True)
    return subprocess.run(cmd, **kw)


def build(a):
    if not os.path.exists(os.path.join(a.build, 'build.ninja')):
        sh([os.path.join(CMAKE, 'cmake'), '-S', os.path.join(ROOT, 'android', 'host'), '-B', a.build, '-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + os.path.join(CMAKE, 'ninja'),
            '-DCMAKE_TOOLCHAIN_FILE=' + os.path.join(NDK, 'build', 'cmake', 'android.toolchain.cmake'), '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=33',
            '-DGAME=' + a.game, '-DCMAKE_BUILD_TYPE=Release', '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON'], check=True)
    sh(['nice', os.path.join(CMAKE, 'cmake'), '--build', a.build, '--target', 'nxreplay', '-j6'], check=True)


def adb(a, *args, **kw):
    return sh(['adb'] + (['-s', a.serial] if a.serial else []) + list(args), **kw)


def push(a):
    adb(a, 'shell', 'mkdir -p ' + PHONE_DIR, check=True)
    for f in ('libnxhost.so', 'nxreplay'):
        adb(a, 'push', os.path.join(a.build, f), PHONE_DIR + '/' + f, check=True, stdout=subprocess.DEVNULL)
    adb(a, 'shell', 'chmod 755 %s/nxreplay' % PHONE_DIR, check=True)


def run(a):
    cap = a.capture if '/' in a.capture else CAPTURES.format(game=a.game) + '/' + a.capture
    env = ' '.join(shlex.quote(e) for e in a.env)
    cmd = 'cd %s && %s LD_LIBRARY_PATH=%s ./nxreplay %s %s' % (PHONE_DIR, env, PHONE_DIR, shlex.quote(cap), ' '.join(shlex.quote(x) for x in a.args))
    return adb(a, 'shell', cmd).returncode


def pull(a):
    os.makedirs(a.dir, exist_ok=True)
    d = CAPTURES.format(game=a.game)
    ls = adb(a, 'shell', 'ls %s' % d, capture_output=True, text=True).stdout.split()
    for f in ls:
        if f.endswith('.png'):
            adb(a, 'pull', d + '/' + f, os.path.join(a.dir, f), stdout=subprocess.DEVNULL)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    p.add_argument('--game', default='040074A01BF12000')
    p.add_argument('--build', default=os.environ.get('NXREPLAY_BUILD', os.path.expanduser('~/switch-dumps/040074A01BF12000-work/replay-agent/build')))
    p.add_argument('--env', action='append', default=[], help='KEY=VALUE for the replayer (DM_VK_NANSCAN=1, ...); repeatable')
    s = p.add_subparsers(dest='cmd', required=True)
    s.add_parser('build'); s.add_parser('push')
    for n in ('run', 'all'):
        r = s.add_parser(n); r.add_argument('capture'); r.add_argument('args', nargs=argparse.REMAINDER)
    q = s.add_parser('pull'); q.add_argument('dir')
    a = p.parse_args()
    if a.cmd in ('build', 'all'): build(a)
    if a.cmd in ('push', 'all'): push(a)
    if a.cmd in ('run', 'all'): sys.exit(run(a))
    if a.cmd == 'pull': pull(a)


if __name__ == '__main__':
    main()
