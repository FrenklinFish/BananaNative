#!/usr/bin/env python3
# The translator's source tree digest: every C# source and project file the translator library is compiled from (dmsh_xlate/Program.cs and its csproj, the
# Android library's own files in dmsh_xlate_android/, and the Ryujinx projects it references: Ryujinx.Graphics.Shader, Ryujinx.Common, Spv.Generator), by path from
# the repository root, in sorted order. dmsh_xlate_android/build.sh embeds it in libdmsh_xlate.so (resource "tree", printed by --tree-sha); vk_android_build.py
# refuses a translator library whose digest is not this tree's. The program form's check is the lock's dll fingerprints (tool_lock); the library form has no dlls,
# and its embedded Program.cs digest (--source-sha) alone let a library built before a Ryujinx change pass (found 2026-11-03: a Mac library from before the
# Ampere tensor fix made 28 compute shaders differ). Not a DMSH_CHAIN file: it changes no translation.
# Usage: xlate_tree.py   print the digest (16 hex digits)
import hashlib, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, '../../..'))
DIRS = ['dm/gfx/tools/dmsh_xlate_android', 'dm/third_party/ryujinx/src/Ryujinx.Graphics.Shader', 'dm/third_party/ryujinx/src/Ryujinx.Common',
        'dm/third_party/ryujinx/src/Spv.Generator']
FILES = ['dm/gfx/tools/dmsh_xlate/Program.cs', 'dm/gfx/tools/dmsh_xlate/dmsh_xlate.csproj']


def files():
    out = list(FILES)
    for d in DIRS:
        for root, dirs, names in os.walk(os.path.join(REPO, d)):
            dirs[:] = sorted(x for x in dirs if x not in ('bin', 'obj'))
            out += [os.path.relpath(os.path.join(root, n), REPO).replace(os.sep, '/') for n in names if n.endswith(('.cs', '.csproj'))]
    return sorted(out)


def sha():
    h = hashlib.sha256()
    for f in files(): h.update(f.encode() + b'\0' + open(os.path.join(REPO, f), 'rb').read() + b'\0')
    return h.hexdigest()[:16]


if __name__ == '__main__':
    if sys.argv[1:]: sys.exit('usage: xlate_tree.py')
    print(sha())
