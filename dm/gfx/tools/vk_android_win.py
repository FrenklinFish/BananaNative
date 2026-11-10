#!/usr/bin/env python3
# vk_android_build.py on Windows: the same build, with the arguments and environment it takes (scripts/pack_android.py starts this in its place there).
# vk_android_build.py is one of the files of the Vulkan library version (DMSH_VK_CHAIN, VK_FILES): a change to it, even one that only runs on Windows, is a new version
# and every library is built again. So what Windows needs is put around it here, in this process, and the script itself runs unchanged (runpy):
#   the translator   it starts XLATE (default dmsh_xlate_bin/dmsh_xlate, the Mac's apphost) as a program; on Windows that is tool_lock.xlate_command's
#                    command: dmsh_xlate.exe when one is beside it, else `dotnet dmsh_xlate.dll` (the dlls are portable; the lock still checks them)
#   spirv-val        it writes each module to a NamedTemporaryFile and has spirv-val open it by name, which Windows refuses while the file is open for
#                    delete on close: the files are made with delete_on_close=False (still deleted when the with block ends)
#   text files       a file opened for writing in text mode gets '\n' line ends and UTF-8 (Windows would write CR LF and the ANSI code page): the stamp
#                    inputs.tsv, which goes into the pack, and the manifest are then byte for byte the Mac's. Text read is UTF-8 too
#   package reads    record_index opens each .bin with os.open(..., os.O_RDONLY) and _rec reads records with os.pread: on Windows os.open needs O_BINARY
#                    (without it CR LF is read as LF and 0x1a ends the read) and os.pread is a locked lseek + read, as in dm/tools/nup.py
# Elsewhere (macOS, Linux) nothing is changed: this only runs vk_android_build.py. When vk_android_build.py changes for another reason, these belong in it.
# Usage: as vk_android_build.py (vk_android_win.py --target vulkan <package dir> <output cache dir> [key...])
import builtins, functools, io, os, runpy, subprocess, sys, tempfile, threading
HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(HERE, 'vk_android_build.py')


def install():
    """The four adaptations above, in this process"""
    if getattr(install, '_done', False): return
    install._done = True
    sys.path.insert(0, HERE)
    import dmsh_chain, tool_lock
    xlate = os.path.normcase(os.path.abspath(os.environ.get('XLATE', os.path.join(dmsh_chain.XLATE_DIR, 'dmsh_xlate'))))

    run = subprocess.run
    @functools.wraps(run)
    def run_(args, *a, **k):
        if isinstance(args, (list, tuple)) and args and isinstance(args[0], str) and os.path.normcase(os.path.abspath(args[0])) == xlate:
            args = tool_lock.xlate_command(args[0]) + list(args[1:])
        return run(args, *a, **k)
    subprocess.run = run_

    ntf = tempfile.NamedTemporaryFile
    @functools.wraps(ntf)
    def ntf_(*a, **k):
        k.setdefault('delete_on_close', False)
        return ntf(*a, **k)
    tempfile.NamedTemporaryFile = ntf_

    op = io.open
    @functools.wraps(op)
    def open_(file, mode='r', buffering=-1, encoding=None, errors=None, newline=None, closefd=True, opener=None):
        if 'b' not in mode:
            if encoding is None or encoding == 'locale': encoding = 'utf-8'
            if newline is None and any(c in mode for c in 'wax+'): newline = '\n'
        return op(file, mode, buffering, encoding, errors, newline, closefd, opener)
    builtins.open = io.open = open_

    oopen = os.open
    @functools.wraps(oopen)
    def os_open(path, flags, mode=0o777, *, dir_fd=None):
        if not (flags & getattr(os, 'O_TEXT', 0)): flags |= getattr(os, 'O_BINARY', 0)
        return oopen(path, flags, mode) if dir_fd is None else oopen(path, flags, mode, dir_fd=dir_fd)
    os.open = os_open

    if not hasattr(os, 'pread'):
        plock = threading.Lock()
        def pread(fd, n, offset):
            out = []
            with plock:
                os.lseek(fd, offset, os.SEEK_SET)
                while n > 0:
                    b = os.read(fd, min(n, 1 << 30))
                    if not b: break
                    out.append(b); n -= len(b)
            return b''.join(out)
        os.pread = pread


if __name__ == '__main__':
    if os.name == 'nt': install()
    sys.argv = [BUILD] + sys.argv[1:]
    runpy.run_path(BUILD, run_name='__main__')
