#!/bin/sh
# Banana Native's packer on macOS, ready to use: double-click this file in Finder for the window (scripts/pack_gui.py), or run it in a terminal.
# On first use it finds a working environment (android/environment.yml: Python 3.14 with Tk, Capstone 5, lz4, zbic, spirv-val, the .NET 10 runtime) or makes one:
# it downloads micromamba (github.com/mamba-org/micromamba-releases) when there is none on PATH, and the packages from conda-forge, about 400 MB in all,
# into <folder>/bananative/ (asked once; the drive with your dumps is a good place). The environment's path is remembered in .bananative-env in the repository
# (not committed). Usage:
#   scripts/bananative.command                       the window
#   scripts/bananative.command pack <game> [...]     scripts/pack_android.py <game folder | .nup file> [its options]
#   scripts/bananative.command check                 check the environment's tools
#   scripts/bananative.command setup [<folder>]      make (or remake) the environment in <folder>/bananative/
#   scripts/bananative.command python [...]          the environment's Python, set up as for the packer
# Environment: BANANATIVE_ENV names the environment to use (the prefix of a conda environment made from android/environment.yml).
set -e
REPO=$(cd "$(dirname "$0")/.." && pwd)
CONF="$REPO/.bananative-env"
YML="$REPO/android/environment.yml"

say() { printf '%s\n' "$*" >&2; }
die() { say "[RED] $*"; exit 1; }

usable() {   # an environment whose Python runs and whose tools pass bananative_env.py's check
	[ -n "$1" ] && [ -x "$1/bin/python" ] && "$1/bin/python" "$REPO/scripts/bananative_env.py" --quiet >/dev/null 2>&1
}

remember() { printf '%s\n' "$1" > "$CONF"; }

find_env() {   # BANANATIVE_ENV, the remembered one, an active conda environment, then a micromamba / conda environment named bananative*
	for e in "${BANANATIVE_ENV:-}" "$(cat "$CONF" 2>/dev/null || true)" "${CONDA_PREFIX:-}"; do
		if usable "$e"; then printf '%s\n' "$e"; return 0; fi
	done
	for mm in micromamba mamba conda; do
		command -v $mm >/dev/null 2>&1 || continue
		for e in $($mm env list 2>/dev/null | awk '{print $NF}' | grep -i '/bananative[^/]*$' || true); do
			if usable "$e"; then printf '%s\n' "$e"; return 0; fi
		done
	done
	return 1
}

get_micromamba() {   # → the path of a micromamba: one on PATH, one downloaded earlier into <base>, else downloaded now
	base=$1
	if command -v micromamba >/dev/null 2>&1; then command -v micromamba; return; fi
	if [ -x "$base/micromamba" ]; then printf '%s\n' "$base/micromamba"; return; fi
	case $(uname -m) in arm64) arch=osx-arm64 ;; x86_64) arch=osx-64 ;; *) die "no micromamba for $(uname -m)" ;; esac
	say "downloading micromamba ($arch) into $base"
	curl -fL --progress-bar -o "$base/micromamba.part" "https://github.com/mamba-org/micromamba-releases/releases/latest/download/micromamba-$arch" || die "the download failed"
	chmod +x "$base/micromamba.part"; mv "$base/micromamba.part" "$base/micromamba"
	printf '%s\n' "$base/micromamba"
}

setup() {   # make the environment in <folder>/bananative/env (asked when no folder is given)
	folder=$1
	if [ -z "$folder" ]; then
		def=$(dirname "$REPO")
		say "The packer needs its tools (Python 3.14, spirv-val, the .NET 10 runtime: about 400 MB)."
		say "They go into <folder>/bananative/. The drive with your game dumps is a good place."
		printf 'Folder [%s]: ' "$def" >&2; read -r folder || true
		folder=${folder:-$def}
	fi
	folder=$(cd "$folder" 2>/dev/null && pwd) || die "no folder $1"
	base="$folder/bananative"; mkdir -p "$base"
	mm=$(get_micromamba "$base")
	say "making the environment $base/env from android/environment.yml (a few minutes)"
	MAMBA_ROOT_PREFIX="$base/mamba" PIP_CACHE_DIR="$base/mamba/pip" "$mm" create -y -p "$base/env" -f "$YML" || die "micromamba create failed"
	usable "$base/env" || { "$base/env/bin/python" "$REPO/scripts/bananative_env.py"; die "the new environment's tools do not all work (above)"; }
	remember "$base/env"; say "ready: $base/env"
	ENV_DIR="$base/env"
}

ENV_DIR=""
cmd=${1:-gui}; [ $# -gt 0 ] && shift
if [ "$cmd" = setup ]; then setup "${1:-}"; exit 0; fi
if ENV_DIR=$(find_env); then
	[ "$(cat "$CONF" 2>/dev/null || true)" = "$ENV_DIR" ] || remember "$ENV_DIR"
else
	old=$(cat "$CONF" 2>/dev/null || true)
	if [ -n "$old" ] && [ -x "$old/bin/python" ]; then
		say "the environment $old lacks a tool:"; "$old/bin/python" "$REPO/scripts/bananative_env.py" || true
	fi
	case $old in
	*/bananative/env) say "remaking it (its packages are cached)"; setup "$(dirname "$(dirname "$old")")" ;;   # one this script made
	*)
		setup "" ;;
	esac
fi
PY="$ENV_DIR/bin/python"
PATH="$ENV_DIR/bin:$PATH"; export PATH
[ -d "$ENV_DIR/lib/dotnet" ] && { DOTNET_ROOT="$ENV_DIR/lib/dotnet"; export DOTNET_ROOT; }
case $cmd in
	gui) exec "$PY" "$REPO/scripts/pack_gui.py" "$@" ;;
	pack) exec "$PY" "$REPO/scripts/pack_android.py" "$@" ;;
	check) exec "$PY" "$REPO/scripts/bananative_env.py" ;;
	python) exec "$PY" "$@" ;;
	*) die "unknown command $cmd (gui, pack, check, setup, python)" ;;
esac
