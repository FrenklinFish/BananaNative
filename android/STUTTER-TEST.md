# Stutter: phone test plan (branch of the stutter agent, 2026-11-03)

What changed on this branch, what each run checks, and which lines answer it. Test app only (`dev.nxnative.android`, "Banana Native test"); never install or start
`dev.nxnative.android.play`. Phone rules: [HANDOFF.md](HANDOFF.md#phone-rules) (`--user 0` only, never uninstall or clear data).

## What is on the branch

| Change | Switch (default) | Reference |
|---|---|---|
| GPU time per submission part: `GPU x ms (busy; span y ms, z waiting for the recording)` in `[longframe]`; overlay and ADPF read the busy time | always | [rendering.md](../docs/reference/rendering.md#gpu-time-counted-the-wait-for-the-recording-on-the-vulkan-replay) |
| `[longframe]` attribution: GPU fences, acquire, game thread CPU, process CPU, page faults, file reads, write faults, replay worker / encoder waits, cache save, prewarm wait; upload MB / ms measured (were stubs) | `DM_LONGFRAME=0` hides the lines | game note, Open, "Stutter on the phone" |
| `draws with a hybrid pipeline` = draws with a fast-linked GPL pipeline (was a Mac stub, always 0) | | [rendering.md](../docs/reference/rendering.md#pipelines-made-on-the-frame-path-on-the-vulkan-replay) |
| GPL: probe at the first refused pre-rasterization library (a one-off diagnostic run: it costs a 1-6 s hitch on the replay thread), adopt what works, else GPL off for the run after 3 refusals | `DM_VK_GPL` (1), `DM_VK_GPL_PROBE` (**0** since the review; 1 in step 1 only), `DM_VK_GPL_GIVEUP` (3) | same |
| Prewarm list `vk_pipelines.keys` in the game's temp folder; next run makes those pipelines on background threads | `DM_VK_PREWARM` (2 threads; 0 off, restart), `DM_VK_PREWARM_MAX` (8192), `DM_VK_PREWARM_KEEP` (16384) | same |
| Pipeline cache saved every 20 s on a saver thread (was once a minute on the replay thread, under the lookup lock) | `DM_VK_PCACHE_SAVE_S` (20) | same |
| `DM_VK_PCACHE=0` = no cache file (was a file named `0`: the old "cold" runs were warm from the second one on) | `DM_VK_PCACHE` | same |
| Window frame rate held at the highest rate of the last 5 s (no panel switch to 30 Hz at every 60 -> 30 switch); refresh drift < 1 % ignored | `DM_VK_FRAME_RATE_HOLD` (5000 ms; 0 = old behaviour) | [frame-rate.md](../docs/reference/frame-rate.md#the-android-windows-frame-rate-held-through-interval-switches) |
| `dm/tools/longframes.py <log>`: the long frames by cause | | |

## 0. Build and install (Mac, about 10 minutes)

Branch `worktree-agent-a5d5bc25503fee760` (worktree `nxnative-android/.claude/worktrees/agent-a5d5bc25503fee760`, on top of android-int 8fac22d9). The agent already
built the test APK from it (`android/app/out/nxnative-android.apk` in that worktree, 2026-11-03 00:49); rebuild after any change (`WT` = that worktree's path; `W` = the work folder, as in [TESTING.md](TESTING.md)):

```sh
cd "${WT:?set WT to that worktree}"
cd android/app && ./gradlew apk && cd ../..                       # -> android/app/out/nxnative-android.apk (test app)
adb install --user 0 -r android/app/out/nxnative-android.apk
```

The build tag on the main screen and the first log line must name this branch and commit.

## Helpers (paste once per shell)

```sh
a() { adb "$@"; }
P=dev.nxnative.android
F=/sdcard/Android/data/$P/files/env.txt
T=/sdcard/Android/data/$P/files/temp/040074A01BF12000          # DM_TEMP: vk_pipelines.bin, vk_pipelines.keys
G=/storage/emulated/0/Switch2/DonkeyKongBananza
OUT=${W:?set W, the work folder (TESTING.md)}/stutter-agent/phone-$(date +%m%d)
mkdir -p "$OUT"
newest() { a shell "ls -t /sdcard/Android/data/$P/files/logs/*.log | head -1"; }
start() {   # $1 = run name, $2... = env lines; a fresh level-1 save, fastboot, presses every 2 s into the level for 3 minutes
  a shell rm -r /sdcard/Switch2/saves-test/040074A01BF12000
  K=$(python3 -c "print(','.join('%d:A'%t for t in range(15,200,2)))")
  a shell "printf 'DM_WATCH_STALL=25\nDM_FASTBOOT=1\nDM_MARK_KEYS_STOP=0\nDM_KEYS=$K\n' > $F"
  for e in "${@:2}"; do a shell "echo '$e' >> $F"; done
  a shell "chmod 666 $F"
  ( while true; do echo "T $(date +%s) caps $(a shell 'cat /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq' | tr '\n' ' ') $(a shell dumpsys thermalservice | grep -m1 'Thermal Status')"; sleep 4; done > $OUT/$1.samples.txt ) &
  SAMPLER=$!
  a shell am start --user 0 -n $P/.MainActivity --es game $G --ez run true
}
stop() {    # $1 = run name: after about 4 minutes
  kill $SAMPLER 2>/dev/null
  a shell am force-stop --user 0 $P
  a pull "$(newest)" $OUT/$1.log
  a shell "printf 'DM_WATCH_STALL=25\n' > $F"
  python3 dm/tools/longframes.py $OUT/$1.log --top 15 > $OUT/$1.longframes.txt; head -12 $OUT/$1.longframes.txt
}
```

`am force-stop` ends the process without the exit path's save; the saver thread has written the cache and the list every 20 s, so at most the last 20 s are lost
(check the last `pipeline cache: … written` line of each log). Start every run with the phone cool: **thermal status 0 or 1** in `samples.txt` (the old runs all
ended at status 4 with the CPU capped at about 1 GHz, which makes every number 3-5 times worse). Same charging state and performance mode for every run.

If the presses do not reach the same places each time, play the same short route by hand instead (the first level from the seed save, into the tunnel and the
area after it), about 3 minutes, and keep `DM_KEYS` out of `start`.

## 1. GPL: why no hybrid draws (needs a cold cache)

```sh
start gpl-cold DM_VK_PCACHE=0 DM_VK_PREWARM=0 DM_VK_GPL_PROBE=1   # no cache file, nothing made ahead; the probe on (off by default: a 1-6 s hitch)
# ~4 minutes
stop gpl-cold
grep -a "graphics pipeline librar\|cache-only" $OUT/gpl-cold.log | head
grep -a "\[nvn\] pipelines up to" $OUT/gpl-cold.log | tail -1
```

- `[vk] graphics pipeline library probe (pre-rasterization refused with -13; …): <variant> made / refused, …; <verdict>`: the answer to "why no linked pipelines".
  - a verdict `adopted: …` -> the following misses are linked: `draws with a hybrid pipeline` > 0 in `[longframe]` lines, `… linked (… ms)` > 0 in the pipelines line,
    and per-miss cost in `new pipelines n …, x ms in all` should drop to a few ms. **Confirms the GPL fix.**
  - `nothing adoptable` and `[vk] graphics pipeline libraries: off for this run: 3 pre-rasterization libraries refused` -> the driver's GPL does not work for these
    shaders; the variants that were `made` say what it needs (send the line back). A cold miss then costs the whole pipeline only (compare per-pipeline time with step 2).
- `cache-only tries H hit / M missed (X ms in the misses)`: if X / M is tens of ms, the driver compiles before it answers COMPILE_REQUIRED (the try is useless on it).

Then the same without GPL, for the cost of a miss on its own:

```sh
start nogpl-cold DM_VK_PCACHE=0 DM_VK_PREWARM=0 DM_VK_GPL=0
stop nogpl-cold
```

Compare `pipelines up to frame …: … ms creating` and the longframes summaries (`pipelines` row) of `gpl-cold` and `nogpl-cold`.

## 2. Prewarm across runs

Step 1 wrote `vk_pipelines.keys` (`a shell ls -l $T`). A run with no cache but the list:

```sh
start prewarm-cold DM_VK_PCACHE=0                 # DM_VK_PREWARM defaults to 2 threads
stop prewarm-cold
grep -a "prewarm" $OUT/prewarm-cold.log
```

- `[vk] prewarm list …: N pipeline keys of earlier runs`, `prewarm: N … made ahead on 2 background threads`, and at the end
  `prewarm: N of N pipeline keys in S s …: M made, …; the frame path waited W ms for them`.
- **Confirms:** the `pipelines` row of `longframes.py` and the frame path's count (`… on the frame path` in the pipelines line) far below `gpl-cold` / `nogpl-cold` for the
  same route; `waited for prewarm` in `[longframe]` lines small. Cost: the boot to `[mark]` (`grep -a "\[mark\]"`) no more than a few seconds longer than in step 1.
- Then the normal case, cache and list both warm:

```sh
start warm                                         # no switches
stop warm
```

Expect almost no `new pipelines` > 0 in `[longframe]` lines and `prewarm: … M made` with M small (most already in the driver's cache: about 1-4 ms each).

## 3. The "GPU" spikes and the frames with no cause (any run above, warm best)

```sh
cat $OUT/warm.longframes.txt
grep -a "\[longframe\]" $OUT/warm.log | awk -F'whole frame ' '{split($2,a," "); if (a[1]+0 > 150) print}' | head -20
```

Each long frame now says where its time went:

- `GPU x ms (busy; span y ms, z waiting for the recording)` with z large: the replay thread stalled in the middle of a submission (not the GPU). Look at the same
  line's `replay recording`, `replay waited for the encoder`, `new pipelines`, `texture uploads … ms`, `cache save`.
- `waits: GPU fences x`: the game waited on the GPU (real GPU time if `busy` is large too).
- `this thread's CPU x` close to the frame: the game's own CPU work (loading, decompressing); `process CPU` far above it: other threads busy.
- `page faults N major`: memory read from storage or swap (the phone had 3.8 GB of swap in use): with hundreds of major faults in a multi-second frame, the cause is
  swap-in or file-backed memory, not the replay.
- `files: read … ms`: the host's file reads in that frame (all threads).
- `game waited for the replay worker x`: the game's submit blocked on the replay thread a frame behind.
- `unexplained` in `longframes.py` after all this: send the lines back.

## 4. 60 <-> 30 switches and pacing

```sh
start hold                                          # DM_VK_FRAME_RATE_HOLD defaults to 5000
stop hold
start nohold DM_VK_FRAME_RATE_HOLD=0                # the old behaviour
stop nohold
for r in hold nohold; do echo "$r: $(grep -ac 'window frame rate' $OUT/$r.log) window rate changes, $(grep -ac 'display refresh 30' $OUT/$r.log) panel switches to 30 Hz, $(grep -ac 'display refresh' $OUT/$r.log) refresh lines"; done
```

- **Confirms:** `hold` has a few `window frame rate` lines (60 at the start, 20/30 only after 5 s at that rate) and few or no `display refresh 30` lines; `nohold` has
  dozens of each (base runs: 52-80 and 29-46).
- By eye (and the overlay's fps): in a busy spot where the game switches 60 <-> 30, the switch back to 60 should no longer hitch.
- The steady alternative on a phone that cannot hold 60: the 30 cap (in-game panel, or live `android/tools/nxctl.sh fps 30`, or `DM_FPS_TARGET=30` in `env.txt`).
  Compare a minute of `nxctl.sh stats` (fps, frame ms) at the game's own rate and at 30: at 30 the frame time should be a flat 33.3 ms.

## 5. Overnight performance switches (perf agent, 2026-11-03)

Built, not phone-checked. All live: switch them with `android/tools/nxctl.sh env KEY=VALUE` in one warm run, standing still in the level (cool phone, same caps), and read
`nxctl.sh stats` (fps, frame ms, render passes, full barriers, recording ms) about 20 s after each switch. Default-on changes have an off value for the A/B.

| Change | Switch (default) | Expect |
|---|---|---|
| Vertex buffers bound only when they change, runs of slots in one call | `DM_VK_VB_FILTER` (1; 0 = every slot every draw) | recording ms a frame lower with 1 (profile: `vin_bind` was 13 % of the replay thread) |
| Encoder fed in 4 KB batches instead of a wake per call | `DM_VK_ENC_BATCH` (4096; 0 = each call) | same or lower recording; `command encoder per frame: … busy` lower; vk-enc's CPU time (`top -H`) lower (heat) |
| Driver segments (c0 / c2 / push bank, 2.5 KB each) and the gmem table bound again when their bytes equal the last draw's, instead of a fresh scratch fill per draw | `DM_VK_DRV_MEMO` (1; 0 = a fill per draw) | recording and encoder ms lower; the stats line `descriptors: driver segments N % the same …; push descriptor sets M % skipped` (M was 21-24 % before) |
| Vertex input state (`vkCmdSetVertexInputEXT`) set only when it changes | `DM_VK_VB_FILTER` (with the vertex buffers) | as above |
| The recording thread's address lookup cache outside TLS | `DM_VK_LC_GLOBAL` (1; restart only: put `DM_VK_LC_GLOBAL=0` in `env.txt`) | recording ms slightly lower |
| Index buffer bound only when it changes | `DM_VK_VB_FILTER` | as the vertex buffers |
| Partial push descriptor updates (only the descriptors that changed) | `DM_VK_PUSH_DELTA` (**0**; 1 = on) | encoder busy ms lower with 1 (stats `partial pushes … wrote N % of their descriptors`); the picture unchanged (a driver that mishandles incremental pushes shows wrong textures) |
| Big texture uploads deswizzled on 3 helper threads | `DM_VK_UPLOAD_MT` (3; 0 = replay thread only), `DM_VK_UPLOAD_MT_MIN` (262144 bytes) | in loading frames `texture uploads … MB / … ms` in `[longframe]` lower (compare `longframes.py` of a cold area entry with 3 and 0); textures intact |
| Game barriers taken inside the open draw pass | `DM_VK_BAR_KEEP` (**0**; 1 = on) | fewer `render passes` and `full barriers` a frame with 1, GPU ms lower; the picture unchanged |
| Images by content and what would fit pool storage | (stats only) | a `ctl: images not GPU-written: …` line after each `stats` |
| GPU clock floor only from each submission's commit to its completion, the governor while the frame records (the fixed floor at 100 holds the highest GPU clock whenever there is work: heat) | `DM_VK_GPU_FLOOR_AUTO` (**0** = fixed `DM_VK_GPU_FLOOR`; 2 = tail only) | same fps and frame ms as 0 (`nxctl.sh stats`), GPU busy ms a bit higher, temperature / caps better over 3 minutes; **revert if** frames drop from 30 to 20 |
| ADPF reports the frame's span (recording start to GPU end) against 90 % of the period (at most 33.3 ms), instead of the period against the period | `DM_VK_ADPF_SPAN` (**0**; 90 = on) | a log line `ADPF: frames report their span …`; at 30 fps the same fps, lower CPU clocks / temperature over a few minutes (`samples.txt` `cur`), or more frames at 30 under a cap; **revert if** fps falls (frames at 20 that were at 30). Run it 3 minutes each way, same spot, same starting temperature |

```sh
android/tools/nxctl.sh stats                                  # baseline (defaults)
android/tools/nxctl.sh env DM_VK_BAR_KEEP=1;  sleep 20; android/tools/nxctl.sh stats; android/tools/nxctl.sh screenshot $OUT/barkeep1.png
android/tools/nxctl.sh env DM_VK_BAR_KEEP=0;  sleep 20; android/tools/nxctl.sh stats; android/tools/nxctl.sh screenshot $OUT/barkeep0.png
android/tools/nxctl.sh env DM_VK_VB_FILTER=0; sleep 20; android/tools/nxctl.sh stats; android/tools/nxctl.sh env DM_VK_VB_FILTER=
android/tools/nxctl.sh env DM_VK_PUSH_DELTA=1; sleep 20; android/tools/nxctl.sh stats; android/tools/nxctl.sh screenshot $OUT/pushdelta1.png; android/tools/nxctl.sh env DM_VK_PUSH_DELTA=
android/tools/nxctl.sh env DM_VK_DRV_MEMO=0; sleep 20; android/tools/nxctl.sh stats; android/tools/nxctl.sh env DM_VK_DRV_MEMO=
android/tools/nxctl.sh env DM_VK_ENC_BATCH=0; sleep 20; android/tools/nxctl.sh stats; android/tools/nxctl.sh env DM_VK_ENC_BATCH=
a pull "$(newest)" $OUT/live.log; grep -a "game barriers inside a draw pass\|command encoder per frame\|replay up to frame\|ctl: " $OUT/live.log | tail -16
```

- `DM_VK_BAR_KEEP`: the replay statistics line `game barriers inside a draw pass per frame: X, Y taken inside it, Z … ended it later` (every 300 frames) says what it can
  do: with X near 0 the switch cannot help this game; with Y large and Z small the pass count should fall by about Y - Z. **Compare the two screenshots** (same spot,
  DK standing still): any difference beyond animation (missing light, wrong reflections, flicker) means a hazard `bar_hazard` does not see: leave it off and send the
  shots. If it is clean and faster, make 1 the default.
- `DM_VK_DRV_MEMO=0` / `DM_VK_VB_FILTER=0` / `DM_VK_ENC_BATCH=0` bring back the old behaviour if either shows a wrong picture or a hang (`[RED] command encoder: the replay thread has waited`).

## 5b. Loading hitches and fence waits (perf3, 2026-11-03)

Branch `perf3-fence-hitch` (on cc3551e0), APK `040074A01BF12000-work/perf3-build/nxnative-android.apk`. Same route as the morning runs (warm cache and list), 3-4 minutes
into the level and through its load at 36-50 s.

| Change | Switch (default) | Expect |
|---|---|---|
| Pool placements outside the segment table's write lock | `DM_VK_MEM_LOCK_WHOLE` (**0**; 1 = old locking, restart) | the 100-515 ms `GPU fences` frames of the load gone or short; `the pool table's lock waited for: replay x, others y` near 0 with 0, large with 1 |
| Pool placements take page protections from a snapshot of /proc/self/maps (was a full read per pool: 1-5 ms each, 75 % of placement time) | `DM_VK_MAPS_CACHE` (**1**; 0 = text each time) | the level load's 1.6-2.3 s frame (`game thread waiting on a game thread`, `pool placement` > 1 s) gone; `placed memory: … protections read` small |
| `[longframe]` attribution: `guest waits … (longest …: <kind>, ended by <thread>)`, `sleeps`, `service waits`, `replay recording x (its CPU y)`, `pool placement x ms`, `busiest threads over … ms` | `DM_LONGFRAME_THREADS` (250 ms; 0 = no sampler) | the `unexplained` frames get a cause in `longframes.py` |
| Frame-path pipelines say why the prewarm list missed them | `DM_VK_PREWARM_WHY` (64) | `pipeline made on the frame path (… ms), not in the prewarm list: …` lines |
| A view format an image's list lacks is learned for the images made later and the next runs (`$DM_TEMP/vk_view_formats.txt`) | `DM_VK_VIEW_LEARN` (**1**; 0 = as before) | first run: one `… learned` line instead of the `[RED]`; next runs: no such line (the 2304x1296 target made with R11G11B10 in its list); HDR bloom / lighting in the level may change slightly |

```sh
start perf3                                         # defaults
stop perf3
start perf3-oldlock DM_VK_MEM_LOCK_WHOLE=1          # the old locking, same route
stop perf3-oldlock
for r in perf3 perf3-oldlock; do echo "== $r"; head -12 $OUT/$r.longframes.txt; done
grep -a "not in the prewarm list\|learned view formats\|asked for a view" $OUT/perf3.log | head -20
```

Send back both logs and `longframes.txt`. A/B verdict: the `waiting for a pool placement (lock)` and `GPU fences: replay blocked in a submission` rows should be
(near) empty with `perf3` and present with `perf3-oldlock`. If `GPU fences: replay blocked in a submission` remains with `perf3`, the replay is blocked elsewhere (its CPU far below
its recording time): send the lines. For `game thread waiting on a game thread` frames, the `ended by` thread and `busiest threads` name the game's loader (its own load).

## 6. What to send back

`$OUT/*.log`, `$OUT/*.samples.txt`, `$OUT/*.longframes.txt`; and these lines from each log:

```sh
for r in gpl-cold nogpl-cold prewarm-cold warm hold nohold; do echo "== $r"; grep -a "graphics pipeline librar\|prewarm\|pipelines up to\|pipeline cache\|\[mark\]" $OUT/$r.log | tail -8; done
```

## Not changed (findings only)

- Texture first uploads: in the 425 s session they came in loading frames, 1-22 textures in frames of 50-3,500 ms with no relation between count and time; their time
  was a stub (0). Now measured (`texture uploads … MB / … ms`): move them off the replay thread only if that time turns out large.
- The 30 cap is not the default: the game's own 60 <-> 30 is kept; the window hold removes the panel switches. Making 30 the Android default is a product decision.
