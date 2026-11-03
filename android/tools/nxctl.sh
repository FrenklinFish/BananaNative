#!/bin/sh
# Live control of a running game on the phone (android/TESTING.md "Live control"): appends one control line to the app's ctl.txt, which the host reads every 100 ms
# (DM_CTL, hle_hid.c; the replay's commands in dm/gfx/vk_android/vka_ctl.c), then prints the log lines that answer it.
#   nxctl.sh env DM_VK_DRAW=0       a switch, applied at the next frame boundary ("env KEY=" clears it)
#   nxctl.sh capture 30             one log line per frame for the next 30 frames
#   nxctl.sh screenshot [file.png]  the next game frame as a PNG; pulled to file.png (default ./ctl-shot-<frame>.png)
#   nxctl.sh stats                  averages of the last 60 frames, the switches set live
#   nxctl.sh dump                   any other DM_CTL line as it is (key presses "A~0.2", "dump", ...)
#   nxctl.sh log [n]                the last n (40) lines of the run's log, nothing sent
# NX_SERIAL (default: adb's, ANDROID_SERIAL or the only phone) picks the phone, NX_PKG (default dev.nxnative.android) the app, NX_WAIT (default 3) the seconds to wait for the answer.
set -eu
PKG=${NX_PKG:-dev.nxnative.android}
WAIT=${NX_WAIT:-3}
FILES=/sdcard/Android/data/$PKG/files
A="adb${NX_SERIAL:+ -s $NX_SERIAL}"
[ $# -ge 1 ] || { sed -n '2,10p' "$0"; exit 2; }

newest_log() { $A shell "ls -t $FILES/logs/*.log 2>/dev/null | head -1" | tr -d '\r'; }
LOG=$(newest_log)
if [ "$1" = log ]; then [ -n "$LOG" ] || { echo "no log in $FILES/logs" >&2; exit 1; }; $A shell "tail -n ${2:-40} '$LOG'"; exit 0; fi
$A shell pidof "$PKG:game" >/dev/null 2>&1 || { echo "the game ($PKG:game) is not running" >&2; exit 1; }

shot_local=
if [ "$1" = screenshot ] && [ $# -ge 2 ]; then shot_local=$2; set -- screenshot; fi
line=$*
case "$line" in *"'"*) echo "a control line cannot hold a single quote" >&2; exit 2;; esac
n0=$($A shell "wc -l < '$LOG'" | tr -d '\r ')
$A shell "f=$FILES/ctl.txt; echo '$line' >> \$f; chmod 666 \$f 2>/dev/null; true"

# The answer: the log lines after the ones there before, from the replay's control ("[vk] ctl:") and the host's reader ("DM_CTL"), and any [RED] line
want=1; case "$1" in capture) want=$(( ${2:-60} + 2 ));; esac
t=0; got=0
while [ "$t" -lt $((WAIT * 4 + want / 6)) ]; do
    sleep 0.25; t=$((t + 1))
    out=$($A shell "tail -n +$((n0 + 1)) '$LOG'" | tr -d '\r' | grep -E '\[vk\] ctl:|DM_CTL|\[RED\]|new swapchain' || true)
    got=$(printf '%s' "$out" | grep -c 'ctl: \(env .* applied\|capture done\|stats at\|screenshot of frame\|screenshot:\)\|DM_CTL: press\|DM_CTL: env' || true)
    [ "$got" -ge 1 ] && { [ "$1" != capture ] || printf '%s' "$out" | grep -q 'capture done'; } && break
done
printf '%s\n' "${out:-(no answer in the log yet: nxctl.sh log)}"
if [ "$1" = screenshot ]; then
    remote=$(printf '%s' "$out" | sed -n 's/.*screenshot of frame [0-9]*, [0-9x]* → //p' | tail -1)
    [ -n "$remote" ] || { echo "no screenshot written" >&2; exit 1; }
    $A pull "$remote" "${shot_local:-$(basename "$remote")}" >/dev/null && echo "pulled ${shot_local:-$(basename "$remote")}"
fi
