# Reviewing a pull request

What an agent checks before a contributor's pull request is merged into the Android branch, and what it never takes. The rules come from [AGENTS.md](../../AGENTS.md); the recipes here are the ones that found the problems so far. The reviewer reads and reports; the maintainer merges, and the maintainer posts every reply.

## What a PR may bring

- **Source, data tables, documentation, tests.** Code under `dm/`, `android/`, `scripts/`, a game profile under `dm/games/<id>/`, notes and reference entries.
- **Nothing compiled.** No binary of any kind: not the translator (`dm/gfx/tools/dmsh_xlate_bin/*.dll`, `.pdb`), not a `.so`, an APK, a GPU driver, a shader library or a prewarm seed. A binary has no readable diff, cannot be tied to the source it claims to come from, and has already carried a private path out of this project once. Every binary is built here from source: the translator by the pinned procedure (`tool_lock.py --update-lock`, `dmsh_chain.py --header`, `vk_android_build.py --header`), the host and the app by the publish pipeline. A contributor who changed the translator sends the `.cs` diff and says which shaders it fixes.
- **No change to `dm/gfx/tools/dmsh_chain.lock`, `dm/gfx/nvn/dmsh_chain.h` or `dmsh_chain_vk.h`.** They fingerprint our tools and move every game's shader library; they are regenerated here when the maintainer rebuilds the chain.
- **No edit under `dm/third_party/`.** Vendored code is pinned at a version listed in [dm/third_party/README.md](../../dm/third_party/README.md). A needed change arrives as a patch in the PR text or a separate commit the maintainer applies as a documented version bump, and the README row says the folder is modified.
- **No game data, keys or anything extracted from a game**, no private paths, hostnames or serials, in files or inside blobs.
- **A history that starts from the current public branch.** A branch forked before a history rewrite carries old copies of public commits (same patch, other hash): the merge would put them back. The author re-creates the branch from the current branch and cherry-picks their own commits.

## What to check, in order

1. **Metadata.** Author, base, head, `mergeable`, comments and reviews; the number of files and commits. More than one idea, or more than about 1,000 lines, is asked to be split before anything else is read.
2. **History.** The merge-base with the integration branch; the commits the PR adds, by author; whether any hash is a pre-rewrite copy (the maintainer keeps the list) or any author address is one of ours.
3. **File list.** Every path, with binaries marked (`git diff --numstat` prints `-` for them). Any of the forbidden kinds above stops the review with that finding.
4. **Risk lines.** `python3 dm/tools/pr_risk.py <integration branch> <PR head>` lists every added line and path that reaches the network, starts a program, loads code at run time, asks Android for more permissions, runs on the maintainer's computer at build or pack time (gradle files, `scripts/`, shell scripts), touches the app's updater or downloads, reads secrets, deletes broadly, or is hard to read (base64, generated arrays, very long lines). Each one is read and named in the report: fine and why, or a finding. A contributor's change is code the maintainer builds and signs for every player, so any of these that the PR's stated purpose does not need stops the review. Generated data (shader headers, tables) is rebuilt here from its source and compared byte for byte before a merge, as PR #151's FSR headers were. Run it on direct pushes too: `sync_public.sh` prints it for the commits it brings in.
5. **Apply.** `git merge-tree --write-tree <integration branch> <PR head>` for conflicts; the PR's diff against its own merge-base, not against the branch tip (a newer tip makes unrelated files look reverted).
6. **Read the code** against the rules: general fixes over game-specific rules, root cause over workaround, fixed addresses gated by build ID, no debugging left on by default, no new switch without its row in `docs/env_switches.tsv`, no duplicate of a path the branch already has. For a large PR, one reader for the host and Vulkan replay, one for the shader chain.
7. **Build and test on the merged tree**, never on the PR's own tree: `make -C dm/host/tests GAME=<id> run`, `make -C dm/gfx/tests GAME=<id> run`, the Python suite (`python3 -m unittest discover -s dm/tools/tests`), `dm/tools/env_switches.py --check`, and an NDK syntax check of the touched C files with the host's flags. Compare with the same run on the branch without the PR: a failure the branch already has is reported as such, not as the PR's. New tests must run on macOS, where these checks run.
8. **Phone runs** only through the maintainer's test rounds, on the system driver and a Turnip driver ([06](06-dev-loop.md#checking-a-change-for-android)); a PR's own run on one driver does not replace them.
9. **Scan** every blob the PR adds or changes for private strings and game data, binaries included.

## The report

Ranked findings, most severe first, each with `file:line`, what, why it matters and a confidence; then every line `pr_risk.py` listed, each with its verdict; then one of: mergeable as-is, mergeable after named fixes, or split. Say what ran, on which tree and driver, and what did not run. A reply to the author is a draft for the maintainer; an agent posts nothing.
