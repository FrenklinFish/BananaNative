#!/usr/bin/env python3
# The packer's environment (android/environment.yml, made by scripts/bananative.command or scripts/bananative.bat): where its tools are and whether they work.
# apply() puts the environment's programs first on PATH and points DOTNET_ROOT at its .NET runtime, so the packer's subprocesses (the rewriter, shader_split.py,
# vk_android_build.py with the translator and spirv-val) find them without the environment being activated; pack_gui.py calls it at start.
# Usage: <env python> scripts/bananative_env.py [--quiet]   check the tools of the environment this Python belongs to; exit 1 when one is missing or does not run
import os, shutil, subprocess, sys
from pathlib import Path
REPO = Path(__file__).resolve().parents[1]
WINDOWS = os.name == 'nt'


def bin_dirs(prefix):
    """The folders of a conda environment's programs (on Windows the Python is in the prefix itself, the libraries' tools in Library\\bin)"""
    p = Path(prefix)
    return [p, p / 'Library' / 'bin', p / 'Scripts'] if WINDOWS else [p / 'bin']


def dotnet_root(prefix):   # where conda-forge's dotnet-runtime puts the runtime (its activation script sets DOTNET_ROOT to it)
    return Path(prefix) / ('dotnet' if WINDOWS else 'lib/dotnet')


def apply(prefix=sys.prefix):
    """os.environ for the packer's subprocesses: the environment's programs first on PATH, DOTNET_ROOT at its runtime when it has one"""
    path = os.environ.get('PATH', '').split(os.pathsep)
    add = [str(d) for d in bin_dirs(prefix) if d.is_dir()]
    os.environ['PATH'] = os.pathsep.join(add + [d for d in path if d and d not in add])
    if dotnet_root(prefix).is_dir(): os.environ['DOTNET_ROOT'] = str(dotnet_root(prefix))


def check():
    """[(what, ok, detail)] for the tools the packer needs, in this Python and on PATH (after apply())"""
    out = [('Python 3.14', sys.version_info[:2] >= (3, 14), f'{sys.version.split()[0]} ({sys.executable})')]
    try:
        import capstone
        out.append(('Capstone 5', capstone.cs_version()[0] == 5, capstone.__version__))
    except ImportError: out.append(('Capstone 5', False, 'missing'))
    try:
        import lz4.block  # noqa: F401
        out.append(('lz4', True, ''))
    except ImportError: out.append(('lz4', False, 'missing'))
    try:
        import zbic  # pyright: ignore[reportMissingImports]  # noqa: F401  (nso.py: the ZBIC segments of system 22.0.0+ modules)
        out.append(('zbic', True, ''))
    except ImportError: out.append(('zbic', False, 'missing (python -m pip install zbic==1.0.1.post1)'))
    try:
        try: from compression import zstd  # noqa: F401
        except ImportError: from backports import zstd  # noqa: F401
        out.append(('zstd', True, ''))
    except ImportError: out.append(('zstd', False, 'needs Python 3.14 or backports.zstd'))
    try:
        import tkinter  # noqa: F401
        out.append(('Tk (the window)', True, f'{tkinter.TkVersion}'))
    except ImportError: out.append(('Tk (the window)', False, 'missing: the command line still works'))
    sv = os.environ.get('SPIRV_VAL') or shutil.which('spirv-val')
    out.append(('spirv-val', bool(sv), sv or 'not found'))
    try:
        sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
        import tool_lock
        emb = tool_lock.xlate_embedded()
        ok = not emb.startswith(tool_lock.NO_RUN)
        out.append(('.NET 10 and the translator', ok, f'DOTNET_ROOT {os.environ.get("DOTNET_ROOT", "not set")}' if ok else emb))
    except Exception as e: out.append(('.NET 10 and the translator', False, f'{type(e).__name__}: {e}'))
    return out


def main():
    quiet = '--quiet' in sys.argv[1:]
    apply()
    res = check()
    if not quiet or not all(ok for _, ok, _ in res):
        for what, ok, d in res: print(f'{"ok " if ok else "[RED] "}{what}' + (f': {d}' if d else ''))
    sys.exit(0 if all(ok for what, ok, _ in res if not what.startswith('Tk')) else 1)


if __name__ == '__main__':
    main()
