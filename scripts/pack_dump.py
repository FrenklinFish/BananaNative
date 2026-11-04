#!/usr/bin/env python3
# Game files already extracted (a console dump: folders, not NSPs) → the layout build_ipa_from_nsp.py extracts NSPs into. build_ipa_from_nsp.py calls this when it is given such a folder, and
# goes on with its other steps; run on its own, this script does only this step.
# The dump folder holds:
#   base/exefs/ (main, main.npdm, sdk, rtld, subsdk*), base/romfs/   (base/romfs may be left out when an update has its romfs/: that one is the whole tree)
#   updates/<version>/exefs/, updates/<version>/romfs/   (optional; <version> is the title version as a decimal number, 458752 = 0x70000)
#   dlc/<DLC title ID>/<n>/romfs/   (optional; the DLC title ID in hex)
# An update's RomFS in such a dump is the patched RomFS (base + the update's BKTR patch, the tree the game sees), not a patch: it is packed on its own, never laid over the base
# (Donkey Kong Bananza 458752 drops 42 base files, among them the Product.100 tables its Product.200 ones replace).
# Writes, under the build folder (<dump folder>/<title ID>_build unless --work-dir names another):
#   extract/v<version>/ExeFS/   the newest version's code modules (APFS clones, or copies on another volume)
#   extract/v<version>/RomFS.img   its RomFS as one image (dm/tools/romfs_image.py: the host's DM_ROMFS, the IPA's romfs.img)
#   aoc/<index>.img   each DLC's RomFS (index = DLC title ID − base title ID − 0x1000), the host's DM_AOC_DIR
# A dump has no control data, so there is no control.nacp or icon.jpg beside them, as there is after nx_extract.
# A NUP file (one file holding base, updates and add-ons, android/NUP.md) stands for such a folder: its base, update/<version> and addon/<title ID> packages are
# read in place (dm/tools/nup.py through romfs.py) as base/, updates/<version>/ and dlc/<title ID>/<n>/ are, and the build folder goes beside it.
# The title ID is the program ID in main.npdm. A repeat run keeps code modules that are the same and an image whose source tree, packer and image are unchanged (romfs_image.py --reuse).
# Usage: pack_dump.py <dump folder | .nup file> [--version N] [--work-dir DIR]   (exit 1 on error)
import argparse, filecmp, shutil, struct, subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'dm/tools'))
import nup, romfs, romfs_image


class Error(Exception): pass


def program_id(npdm):   # the ACI0 program ID of main.npdm
    d = npdm.read_bytes()
    if d[:4] != b'META': raise Error(f'{npdm}: not an NPDM')
    off, size = struct.unpack_from('<II', d, 0x70)
    if d[off:off + 4] != b'ACI0' or size < 0x18: raise Error(f'{npdm}: no ACI0 section')
    return struct.unpack_from('<Q', d, off + 0x10)[0]


def in_nup(p): return nup.split(p) is not None   # a path inside a NUP file (romfs.Path), read through romfs.py


def clone(src, dst):   # an APFS clone on the same volume, else a copy; a file of a NUP package is written out
    if in_nup(src): dst.write_bytes(src.read_bytes())
    elif subprocess.run(['cp', '-c', src, dst], capture_output=True).returncode != 0: shutil.copy2(src, dst)


def same(f, d): return d.is_file() and (f.read_bytes() == d.read_bytes() if in_nup(f) else filecmp.cmp(f, d, shallow=False))


def exefs(src, dst):
    files = sorted(f for f in src.iterdir() if f.is_file() and not f.name.startswith('.'))
    if dst.is_dir() and {f.name for f in files} == {f.name for f in dst.iterdir()} and all(same(f, dst / f.name) for f in files): return
    tmp = dst.with_name(dst.name + '.partial')
    shutil.rmtree(tmp, ignore_errors=True); tmp.mkdir(parents=True)
    for f in files: clone(f, tmp / f.name)
    shutil.rmtree(dst, ignore_errors=True); tmp.rename(dst)


def pack(src, img, say=print):
    img.parent.mkdir(parents=True, exist_ok=True)
    say(f'packing {src} → {img}')
    try: n = romfs_image.pack(src, img, reuse=True)
    except RuntimeError as e: raise Error(f'{src}: {e}') from e   # the files changed while packing
    say(f'  {n:,} entries, {img.stat().st_size / 2**30:.2f} GB')


def updates(dump):   # {version: its folder} of updates/<version>/
    return {int(p.name): p for p in (dump / 'updates').iterdir() if p.is_dir() and p.name.isdigit()} if (dump / 'updates').is_dir() else {}


def is_dump(folder):   # whether a folder has the layout above: base/exefs/main.npdm, and a RomFS in base/ or in an update (whose RomFS is the whole patched tree, so a dump
    # that keeps only the update's, as on a phone with little room, is complete for that update; packing the base version itself then fails on its missing romfs)
    return (folder / 'base/exefs/main.npdm').is_file() and ((folder / 'base/romfs').is_dir() or any((v / 'romfs').is_dir() for v in updates(folder).values()))


def nup_scan(path):
    """scan() of a NUP file: its packages as romfs.Path folders named as android/NUP.md names them (`<file>.nup:update/<version>`, `<file>.nup:addon/<tid>/romfs`)"""
    try: n = nup.open_nup(path)
    except nup.Error as e: raise Error(str(e)) from None
    pkg = lambda p, sub='': romfs.Path(f'{n.path}:{p.selector}{sub}')
    base = [p for p in n.packages if p.kind == nup.BASE]
    if not base or base[0].exefs == nup.NONE or not (pkg(base[0]) / 'exefs/main.npdm').is_file():
        raise Error(f'{path}: no base package with exefs/main.npdm' + (' (an update-only NUP file: the base is in another one)' if not base else ''))
    app = program_id(pkg(base[0]) / 'exefs/main.npdm')
    if app & 0xFFF: raise Error(f'{path}: base program ID {app:016X} is not a base title ID')
    vers = {p.version: pkg(p) for p in n.packages if p.kind == nup.UPDATE}
    if base[0].romfs == nup.NONE and not any(p.romfs != nup.NONE for p in n.packages if p.kind == nup.UPDATE): raise Error(f'{path}: no RomFS in the base or an update')
    dlc = {}
    for p in n.packages:
        if p.kind != nup.ADDON: continue
        if not 0 < p.title_id - app - 0x1000 <= 2000: raise Error(f'{path}: add-on {p.title_id:016X} is not a DLC of {app:016X}')
        if p.romfs == nup.NONE: raise Error(f'{path}: add-on {p.title_id:016X} has no RomFS')
        dlc[p.title_id] = pkg(p, '/romfs')
    return app, vers, dlc


def version_dir(dump, vers, ver):   # the folder of a version scan() found: updates/<version>/ (its entry), or the base's
    return vers[ver] if ver else romfs.Path(f'{nup.open_nup(dump).path}:base') if nup.is_nup(dump) else dump / 'base'


def scan(dump):
    """What a dump folder (or a NUP file) holds: (title ID, {update version: its folder}, {DLC title ID: its RomFS folder})"""
    if nup.is_nup(dump): return nup_scan(dump)
    if not is_dump(dump): raise Error(f'{dump}: no base/exefs/main.npdm and base/romfs (or an update\'s romfs)')
    app = program_id(dump / 'base/exefs/main.npdm')
    if app & 0xFFF: raise Error(f'{dump}: base program ID {app:016X} is not a base title ID')
    vers = updates(dump)
    dlc = {}
    for d in sorted(p for p in (dump / 'dlc').iterdir() if p.is_dir()) if (dump / 'dlc').is_dir() else []:
        try: tid = int(d.name, 16)
        except ValueError: raise Error(f'{d}: not a DLC title ID') from None
        if not 0 < tid - app - 0x1000 <= 2000: raise Error(f'{d}: {tid:016X} is not a DLC of {app:016X}')
        roms = sorted(d.glob('*/romfs')) or ([d / 'romfs'] if (d / 'romfs').is_dir() else [])
        if len(roms) != 1: raise Error(f'{d}: expected one <n>/romfs folder, found {len(roms)}')
        dlc[tid] = roms[0]
    return app, vers, dlc


def pack_version(src, app, out, say=print):   # one version's folder (base/ or updates/<version>/) → out/ExeFS and out/RomFS.img
    if not (src / 'exefs/main').is_file() or not (src / 'exefs/main.npdm').is_file() or not (src / 'romfs').is_dir(): raise Error(f'{src}: no exefs/main, exefs/main.npdm and romfs')
    if (pid := program_id(src / 'exefs/main.npdm')) != app: raise Error(f'{src}: program ID {pid:016X}, the base is {app:016X}')
    exefs(src / 'exefs', out / 'ExeFS')
    pack(src / 'romfs', out / 'RomFS.img', say)


def pack_dlc(app, dlc, aoc, say=print):   # {DLC title ID: its RomFS folder} → aoc/<index>.img; images of DLC no longer in the folder go
    want = {f'{tid - app - 0x1000}.img': roms for tid, roms in dlc.items()}
    if aoc.is_dir():
        for f in aoc.iterdir():
            if f.name.removesuffix('.inputs.json') not in want: f.unlink()
    for name, roms in want.items(): pack(roms, aoc / name, say)


def main():
    ap = argparse.ArgumentParser(description='Pack a dumped game (base/, updates/<version>/, dlc/) into the extraction layout of build_ipa_from_nsp.py')
    ap.add_argument('dump', type=Path, help='the dump folder (holds base/exefs, and base/romfs or an update\'s romfs), or a .nup file')
    ap.add_argument('--version', type=int, help='the update version to pack (default: the newest in updates/; 0 = the base game)')
    ap.add_argument('--work-dir', type=Path, help='the build folder (default: <dump>/<title ID>_build)')
    a = ap.parse_args()
    dump = a.dump.resolve()
    try:
        app, vers, dlc = scan(dump)
        ver = a.version if a.version is not None else max(vers, default=0)
        if ver and ver not in vers: raise Error(f'no updates/{ver} (have: {", ".join(map(str, sorted(vers))) or "none"})')
        src = version_dir(dump, vers, ver)
        work = (a.work_dir or (dump.parent if nup.is_nup(dump) else dump) / f'{app:016X}_build').resolve()
        out = work / 'extract' / f'v{ver}'
        print(f'{app:016X} version {ver}: {src}', flush=True)
        say = lambda s: print(s, flush=True)
        pack_version(src, app, out, say)
        pack_dlc(app, dlc, work / 'aoc', say)
    except Error as e: sys.exit(f'[RED] {e}')
    print(f'done: {out}' + (f', {len(dlc)} DLC in {work / "aoc"}' if dlc else ''))


if __name__ == '__main__':
    main()
