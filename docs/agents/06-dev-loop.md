# 06 — The developer loop: which tool when, and before a pull request

One page for people and agents: the tools this repository has for building, running, debugging, measuring and checking a change, when to use which, and what a pull request needs. It links to where each tool is described instead of repeating it. Game files, builds and runs stay outside the repository ([AGENTS.md](../../AGENTS.md)).

Banana Native's target is Android: a change is checked with the unit tests, the Python tests and short runs on a phone ([Checking a change for Android](#checking-a-change-for-android)). The Mac tools in sections 1-5 are nxnative's upstream toolchain, used for bring-up, shader builds and debugging; they are not a required check.

## Checking a change for Android

1. **Unit tests**: `make -C dm/host/tests GAME=<id> run` for the host; `make -C dm/gfx/tests GAME=<id> run` when the change reaches graphics or the shader chain.
2. **Python tests**: `python3 -m unittest discover -s dm/tools/tests` (with `DM_GAME=<id>`). They include the launcher's plain-Java parts, checked on the JVM by the `test_android_*.py` and `test_launcher_settings.py` tests (front ends, shader libraries, saves, pack finding, driver zips, the readiness check), and the pack script (`test_pack_android.py`).
3. **A phone run** ([android/TESTING.md](../../android/TESTING.md)): install and run the test app `dev.nxnative.android`, never the play app `dev.nxnative.android.play` or the release. Never delete or overwrite a save folder: test runs use a copied save ([Saves](../../android/TESTING.md#saves)). About 20 s of roaming play after the level loads is enough ([Fast test runs](../../android/TESTING.md#fast-test-runs)); read the log for new `[RED]` lines, crashes and frame times. Every round includes one run on the system driver and one on a Turnip driver through `DM_VK_DRIVER` ([Custom GPU drivers](../../android/README.md#custom-gpu-drivers)). Force-stop the test app afterwards (`adb shell am force-stop dev.nxnative.android`).
4. **The pull request** names the tests that ran, the games and versions played on the phone, the phone and both drivers, measurements before and after where the change is about speed, memory, power or heat, and what was not checked.

## 1. Build and run on the Mac (upstream toolchain)

| Step | Tool | Details |
|---|---|---|
| Pack a game for the Mac | `scripts/build_ipa_from_nsp.py <dump folder> --platform mac` | Extraction, rewriter, shader split and chain, the Mac host; prints the command that runs the game. [A Mac-only build](../development.md#a-mac-only-build-for-development) |
| A double-clickable app | the same with `--platform mac-app` (adds the offline pipeline archive) | [A Mac app](../development.md#a-mac-app) |
| An iPhone build | the same without `--platform` (an unsigned IPA the user sideloads) | [The packing script in detail](../development.md#the-packing-script-in-detail) |
| Rebuild only the host | `make -C dm/host GAME=<id>` | [Build by hand (Mac)](../development.md#build-by-hand-mac) |
| Rebuild only the shaders | `DM_GAME=<id> dm/gfx/tools/dmsh_build.py` (after a chain change: `dmsh_chain.py --header` first) | the chain procedure in [AGENTS.md](../../AGENTS.md); [04](04-graphics.md) |
| Run with scripted input | `DM_KEYS` (button presses at fixed seconds or frames), `DM_CTL` (presses, screenshots and dumps while it runs), `DM_REC` (records what the player does as `DM_KEYS` steps, to replay a bug report's path or make a route) | [05, Scripted input](05-debugging.md#scripted-input-and-thread-scheduling) |

Start Mac runs with the Metal performance HUD (`DM_METAL_HUD=1`). A new title starts from the [checklist](README.md#checklist-for-a-new-title) and [03](03-bringup.md).

## 2. When something is wrong

1. Search the [symptom index](symptoms.md) for the `[RED]` line, abort text or what you see; it leads to the reference entry and the games it was found on.
2. Crashes: the host's `CRASH sig=…` report, the macOS crash report, lldb scripts, Ghidra / objdump on the rewritten modules: [05](05-debugging.md#reading-a-crash-report).
3. Hangs and threads: `DM_WATCH=<s>` / `DM_WATCH_STALL=<s>` dump every thread ([03, Reading the log](03-bringup.md#reading-the-log)); `DM_THR=1`, `DM_SVC=1`, `DM_FS=1` log thread SVCs, memory SVCs and file opens; `sample` / `xctrace` name the guest threads during a hitch ([05](05-debugging.md#scripted-input-and-thread-scheduling)); `DM_HLECOUNT=<file>` counts every HLE function and SVC the game called.
4. Guest state without a debugger: `DM_PROBE` and its watch and heap variants ([05](05-debugging.md#probes-without-a-debugger-dm_probe)).
5. Wrong picture: `DM_NVN_SHOT` screenshots, `DM_NVN_PASSDUMP` (every pass, its attachments, bindings and draw state), `DM_NVN_TEXDUMP` / `DM_NVN_TEXSNAP` (render targets as PNG), `DM_NVN_CBDUMP` (uniform buffers, vertex streams), `DM_NVN_HILITE` / `DM_NVN_DEPTHOVR` / `DM_NVN_SKIP_FS` (isolate a draw), `DM_NVN_KEYDUMP` and `DM_NVN_MAGSCAN` on the phone: [04, Diagnostic switches](04-graphics.md#7-diagnostic-switches-host) and [05, Intermittent rendering glitches](05-debugging.md#intermittent-rendering-glitches).
6. Slow frames: the `[longframe]` lines and the hitch dump (`DM_HITCH_DUMP`), pipeline compile waits ([05](05-debugging.md#pipeline-compile-waits)), GPU time per pass (`DM_NVN_GPUPROF`, `DM_AB` A/B phases, `DM_GPU_CAPTURE` for Xcode; read with `dm/tools/gpuprof.py`): [05, GPU time on the device](05-debugging.md#gpu-time-on-the-device). Attachment traffic: `dm/gfx/tools/tbdr_*.py` over a pass dump.
7. Memory: the host's memory line, `footprint`, `vmmap`, `DM_NVN_ALLOCPROF=1`: [05, Memory growth](05-debugging.md#memory-growth).
8. Shaders: `dm/gfx/tools/shader_regressions.py` (shaders that built in the previous chain and fail now), `DM_NVN_SHDUMP` (programs missing from the map), the offline link check ([05](05-debugging.md#offline-pipeline-link-check)).
9. On the phone: switches in the app's `env.txt`, results in its `files/logs/` ([Diagnostic switches](../../android/TESTING.md#diagnostic-switches)); on an iPhone, `Documents/env.txt` and `Documents/log.txt` ([05, On-device diagnostics](05-debugging.md#on-device-diagnostics-ios)).
10. Game updates and fixed addresses: `dm/tools/nso_fnmap.py`, `nso_syms.py`, `insn_census.py`, `sdis.py`, `nso2elf.py` ([Tools](../development.md#tools-dmtools)).

## 3. Measuring

`scripts/bench.py <id>_build --route <name>` runs a route of the profile's `bench.tsv` from a fixed save and gives frame-time percentiles, GPU time and screenshots; variants (`--variant`) compare two hosts, shader libraries or sets of switches in one invocation, which is the only comparison it supports ([A benchmark on the Mac](../development.md#a-benchmark-on-the-mac)). Power and energy per frame: [Power](../development.md#power). Pass `--lock <file>` when other agents or people measure on the same Mac: the lock is held only while a game runs, and heavy builds should wait for it.

## 4. The upstream regression harness: `scripts/regress.py`

nxnative's `scripts/regress.py` compares a baseline revision with a candidate on every game the Mac has a `--platform mac` build of: shader builds, a boot along each profile's `smoke.tsv` route, frame captures. It runs the Metal host, so it checks the Mac path, not Android: Banana Native does not require it for a pull request. `regress.py chain`, which compares each shader's translation, can still help after a change to the shared front half of the chain. Full description: [The regression harness](../development.md#the-regression-harness).

## 5. Which games a change can reach

`python3 scripts/regress.py impact <base>..<head>` maps a change to the games whose usage manifests (`dm/games/<id>/usage.tsv`) name what it touches. It reads only the repository's history and the committed manifests, so it needs no game files and no Mac build. Use it to choose which games to play on the phone.

## 6. Continuous integration

`.github/workflows/tests.yml` runs on every pull request: the host's unit tests and ThreadSanitizer targets, the host and CPU texture tests again under ASan, UBSan and TSan (a ratchet over the findings listed in `dm/sanitizers/`: `make -C dm/host/tests GAME=<id> asan tsan-threads` and `make -C dm/gfx/tex/tests asan` locally), line coverage of those tests and the tool tests with the diff coverage of the pull request in the job summary (report only; `make coverage` in both folders and `dm/tools/coverage_report.py` locally), the CPU-only graphics tests, the Python tool tests (`dm/tools/tests`, `dm/ios/tests`, `dm/gfx/tests`) including the translator's synthetic-shader tests, Mac host builds for an NVN, a Vulkan and an OpenGL profile, and the iOS source compile and smoke project ([Continuous integration](../development.md#continuous-integration)). `.github/workflows/fuzz.yml` fuzzes the file parsers nightly from synthetic seeds; a change to a parser in `romfs.py`, `nacp.py`, `shader_split.py` or `uepak.py` can run its harness locally (`dm/tools/fuzz/fuzz_<parser>.py --time 300`). GitHub's Macs have no Metal 4 and no game files, so these stay local: `make -C dm/gfx/tests GAME=<id> run` (the Metal graphics tests), the translator tests with `DMSH_TEST_METAL=1` / `DMSH_TEST_GPU=1`, `profile_guess.py --check`, and everything `regress.py` does.

## 7. Before you open a pull request

By what the change touches (the largest that applies):

- **Docs only:** `python3 -m unittest dm/tools/tests/test_docs.py`; after a change to `docs/reference`, `python3 dm/tools/docs_symptoms.py`; after editing `docs/images/gallery.tsv`, `python3 dm/tools/docs_gallery.py` (also resolves conflicts inside the generated gallery of `docs/nxnative.md`).
- **A Python tool or script** (`scripts/`, the tools under `dm/`): the ratchet `lint-python.yml` runs, ruff and pyright with no new finding: `<venv>/bin/python3 dm/tools/lint_python.py`, from a venv with `.github/requirements/lint.txt` installed, kept on the drive of the NSP folders, not in the home folder ([Continuous integration](../development.md#continuous-integration)). The system Python has neither tool, and the check stops at a `[RED]` line there: that is not a pass. A generated page (`docs_*.py`) is compared in CI on a clean checkout, so it must not read untracked or git-ignored files such as `dm/host/gen`.
- **One game's profile or note** (`dm/games/<id>/`): the Python tool tests; a full shader build of that game (and its Android library) after a change to its shader inputs; the game on the phone ([Checking a change for Android](#checking-a-change-for-android)), with numbers when the change is about speed; the finding in the game's note ([AGENTS.md](../../AGENTS.md)).
- **The host, the chain, the tools or the launcher** (`dm/host`, `dm/gfx`, `dm/tools`, `dm/gfx/tools`, `android`, `scripts`): everything above, the unit tests (`make -C dm/host/tests GAME=<id> run`, `make -C dm/gfx/tests GAME=<id> run`), `dmsh_chain.py --header` and `vk_android_build.py --header` after a chain change, and phone runs on the games the change most likely reaches, on the system driver and on Turnip ([Checking a change for Android](#checking-a-change-for-android)); name the games and why you chose them: the others are unchecked. After a change to C or Objective-C in `dm/host` or `dm/gfx`, `dm/tools/lint_c.py compdb` and `tidy` with LLVM's release run what the `lint-c` workflow runs: no new finding (fix it, or `// NOLINT(<check>)` with a reason on a false positive), and `tidy --update` after fixing one ([Continuous integration](../development.md#continuous-integration)).
