#!/usr/bin/env python3
# A game's dump folder (its files already extracted, made from your own console) → the layout build_ipa_from_nsp.py builds from. build_ipa_from_nsp.py calls this for its
# dump folder, and goes on with its other steps; run on its own, this script does only this step.
# The dump folder holds the console's packages, one folder for each content meta record (the console's layout; scripts/dump_layout.py puts a dump in it):
#   base/program/<index>/{exefs,romfs,control,meta}/   the application (exefs: main, main.npdm, sdk, rtld, subsdk*); each content at its program index
#   updates/<version>/program/<index>/...               its updates (patches); <version> is the title version in decimal, 458752 = 0x70000
#   dlc/<DLC title ID>/<version>/base/program/<index>/romfs/                     add-ons (the DLC title ID in hex)
#   dlc/<DLC title ID>/<version>/updates/<patch version>/program/<index>/romfs/  their data patches: the newest one's RomFS, the patched tree, is used (dlc_romfs)
# Program 0 is the game (package_root); a Switch 2 Edition's program/1 is another program, not read. base/romfs may be left out when an update has its romfs: that
# one is the whole tree. The flattened layout is read too: base/exefs/, base/romfs/, updates/<version>/{exefs,romfs}/ (program 0's folders in the version folder)
# and dlc/<DLC title ID>/<version>/romfs/ (the add-on's RomFS with its patch applied); NSP-Box and nxnative take only that one.
# An update's RomFS in such a dump is the patched RomFS (base + the update's BKTR patch, the tree the game sees), not a patch: it is packed on its own, never laid over the base
# (Donkey Kong Bananza 458752 drops 42 base files, among them the Product.100 tables its Product.200 ones replace).
# Writes, under the build folder (<dump folder>/<title ID>_build unless --work-dir names another):
#   extract/v<version>/ExeFS/   the newest version's code modules (APFS clones, or copies on another volume)
#   extract/v<version>/RomFS.img   its RomFS as one image (dm/tools/romfs_image.py: the host's DM_ROMFS, the IPA's romfs.img)
#   aoc/<index>.img   each DLC's RomFS (index = DLC title ID − base title ID − 0x1000), the host's DM_AOC_DIR
# A dump has no control data, so there is no control.nacp or icon.jpg beside them.
# A NUP file (one file holding base, updates and add-ons, android/NUP.md) stands for such a folder: its base, update/<version> and addon/<title ID> packages are
# read in place (dm/tools/nup.py through romfs.py) as base/, updates/<version>/ and dlc/<title ID>/<n>/ are, and the build folder goes beside it.
# The title ID is the program ID in main.npdm. A repeat run keeps code modules that are the same and an image whose source tree, packer and image are unchanged (romfs_image.py --reuse).
# Usage: pack_dump.py <dump folder | .nup file> [--version N] [--work-dir DIR]   (exit 1 on error)
import argparse, filecmp, os, shutil, struct, subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'dm/tools'))
import cnmt, nup, romfs, romfs_image


class Error(Exception): pass


def program_id(npdm):   # the ACI0 program ID of main.npdm
    d = npdm.read_bytes()
    if d[:4] != b'META': raise Error(f'{npdm}: not an NPDM')
    off, size = struct.unpack_from('<II', d, 0x70)
    if d[off:off + 4] != b'ACI0' or size < 0x18: raise Error(f'{npdm}: no ACI0 section')
    return struct.unpack_from('<Q', d, off + 0x10)[0]


def in_nup(p): return nup.split(p) is not None   # a path inside a NUP file (romfs.Path), read through romfs.py


def clone(src, dst):   # an APFS clone on the same volume, else a copy (Windows has no cp); a file of a NUP package is written out
    if in_nup(src): dst.write_bytes(src.read_bytes()); return
    try: cloned = os.name != 'nt' and subprocess.run(['cp', '-c', src, dst], capture_output=True).returncode == 0
    except OSError: cloned = False
    if not cloned: shutil.copy2(src, dst)


def same(f, d): return d.is_file() and (f.read_bytes() == d.read_bytes() if in_nup(f) else filecmp.cmp(f, d, shallow=False))


def exefs(src, dst):
    files = sorted(f for f in src.iterdir() if f.is_file() and not f.name.startswith('.'))
    if dst.is_dir() and {f.name for f in files} == {f.name for f in dst.iterdir()} and all(same(f, dst / f.name) for f in files): return
    tmp = dst.with_name(dst.name + '.partial')
    shutil.rmtree(tmp, ignore_errors=True); tmp.mkdir(parents=True)
    for f in files: clone(f, tmp / f.name)
    shutil.rmtree(dst, ignore_errors=True); os.replace(tmp, dst)


def pack(src, img, say=print):
    img.parent.mkdir(parents=True, exist_ok=True)
    say(f'packing {src} → {img}')
    try: n = romfs_image.pack(src, img, reuse=True)
    except RuntimeError as e: raise Error(f'{src}: {e}') from e   # the files changed while packing
    say(f'  {n:,} entries, {img.stat().st_size / 2**30:.2f} GB')


def package_root(v):
    """A version folder's package (base/ or updates/<version>/ of a folder dump), the folder that holds its exefs/ and romfs/: the version folder itself, or in
    OunceCake's layout program/0/ in it (each content at program/<index>/, with program/ below that when a data content shares the index). Program 0 is the
    application's own program; a Switch 2 Edition's program 1 (program/1/) is another program, which no profile of the program-0 title runs"""
    for r in (v, v / 'program/0', v / 'program/0/program'):
        if (r / 'exefs').is_dir() or (r / 'romfs').is_dir(): return r
    return v


def base_dir(dump): return package_root(dump / 'base')   # a folder dump's base package


def updates(dump):   # {version: its package} of updates/<version>/
    return {int(p.name): package_root(p) for p in (dump / 'updates').iterdir() if p.is_dir() and p.name.isdigit()} if (dump / 'updates').is_dir() else {}


def is_dump(folder):   # whether a folder has the layout above: a main.npdm in base/exefs or an update's exefs (an update-only dump carries the whole game), and a RomFS
    # in base/ or in an update (whose RomFS is the whole patched tree, so a dump that keeps only the update's, as on a phone with little room, is complete for that update;
    # packing the base version itself then fails on its missing romfs)
    ups, base = updates(folder).values(), base_dir(folder)
    return ((base / 'exefs/main.npdm').is_file() or any((v / 'exefs/main.npdm').is_file() for v in ups)) \
        and ((base / 'romfs').is_dir() or any((v / 'romfs').is_dir() for v in ups))


def main_npdm(folder):   # the dump's main.npdm the title ID is read from: the base's, else the newest update's that has one (an update's NPDM carries the base's program ID)
    base = base_dir(folder) / 'exefs/main.npdm'
    if base.is_file(): return base
    ups = [v for _, v in sorted(updates(folder).items()) if (v / 'exefs/main.npdm').is_file()]
    return ups[-1] / 'exefs/main.npdm' if ups else base


def nup_scan(path):
    """scan() of a NUP file: its packages as romfs.Path folders named as android/NUP.md names them (`<file>.nup:update/<version>`, `<file>.nup:addon/<tid>/romfs`)"""
    try: n = nup.open_nup(path)
    except nup.Error as e: raise Error(str(e)) from None
    pkg = lambda p, sub='': romfs.Path(f'{n.path}:{p.selector}{sub}')
    base = [p for p in n.packages if p.kind == nup.BASE]
    # the title ID: the base's main.npdm, else the newest update's (a NUP file with only an update package that carries the whole game)
    code = [p for p in base + sorted((p for p in n.packages if p.kind == nup.UPDATE), key=lambda p: -p.version) if p.exefs != nup.NONE and (pkg(p) / 'exefs/main.npdm').is_file()]
    if not code: raise Error(f'{path}: no package with exefs/main.npdm (neither the base nor an update)')
    app = program_id(pkg(code[0]) / 'exefs/main.npdm')
    if app & 0xFFF: raise Error(f'{path}: base program ID {app:016X} is not a base title ID')
    vers = {p.version: pkg(p) for p in n.packages if p.kind == nup.UPDATE}
    if not any(p.romfs != nup.NONE for p in n.packages if p.kind in (nup.BASE, nup.UPDATE)): raise Error(f'{path}: no RomFS in the base or an update')
    dlc = {}
    for p in n.packages:
        if p.kind != nup.ADDON: continue
        if not 0 < p.title_id - app - 0x1000 <= 2000: raise Error(f'{path}: add-on {p.title_id:016X} is not a DLC of {app:016X}')
        if p.romfs == nup.NONE: raise Error(f'{path}: add-on {p.title_id:016X} has no RomFS')
        dlc[p.title_id] = pkg(p, '/romfs')
    return app, vers, dlc


def version_dir(dump, vers, ver):   # the folder of a version scan() found: updates/<version>/ (its entry), or the base's
    return vers[ver] if ver else romfs.Path(f'{nup.open_nup(dump).path}:base') if nup.is_nup(dump) else base_dir(dump)


def version_part(dump, vers, ver, part):   # a version's exefs or romfs: its own when it has one (exefs with main, romfs with files), else the base's (an update overrides
    # the base part by part: the Android launcher's DumpLayout reads dumps the same way)
    own = version_dir(dump, vers, ver) / part
    try: has = (own / 'main').is_file() if part == 'exefs' else own.is_dir() and any(True for _ in own.iterdir())
    except (OSError, nup.Error): has = False
    if has or not ver: return own
    base = version_dir(dump, vers, 0) / part
    try: return base if base.is_dir() else own
    except (OSError, nup.Error): return own


def scan(dump):
    """What a dump folder (or a NUP file) holds: (title ID, {update version: its folder}, {DLC title ID: its RomFS folder})"""
    if nup.is_nup(dump): return nup_scan(dump)
    if not is_dump(dump): raise Error(f'{dump}: no base/exefs/main.npdm (or an update\'s exefs/main.npdm) and base/romfs (or an update\'s romfs), '
                                      'in the console\'s layout base/program/0/ and updates/<version>/program/0/ or in the flattened one')
    app = program_id(main_npdm(dump))
    if app & 0xFFF: raise Error(f'{dump}: base program ID {app:016X} is not a base title ID')
    vers = updates(dump)
    dlc = {}
    for d in sorted(p for p in (dump / 'dlc').iterdir() if p.is_dir()) if (dump / 'dlc').is_dir() else []:
        try: tid = int(d.name, 16)
        except ValueError: raise Error(f'{d}: not a DLC title ID') from None
        if not 0 < tid - app - 0x1000 <= 2000: raise Error(f'{d}: {tid:016X} is not a DLC of {app:016X}')
        dlc[tid] = dlc_romfs(d)
    return app, vers, dlc


def dlc_romfs(d):
    """A DLC folder's RomFS (dlc/<DLC title ID>/). The console's layout (scripts/dump_layout.py): <version>/base/program/<index>/romfs, the add-on of that version
    (data/ or program/ below the index when a program and a data content share it), and <version>/updates/<patch version>/program/<index>/romfs, its data patches.
    The newest data patch's RomFS is used, else the base's: a data patch's RomFS in a dump is the patched tree (the add-on with the patch applied, the storage the
    game mounts), like a game update's, so it is used on its own. The flattened layout: <version>/romfs (or romfs), the add-on's RomFS as it is. The console keeps
    one version of an add-on installed; of several numbered version folders the highest is taken. The content meta records in the packages' meta/ folders
    (dm/tools/cnmt.py), when the dump has them, are checked: the add-on's is this add-on, and each data patch's names it (DataId) and is the one the add-on names
    (DataPatchId); a folder of another package stops the scan instead of being packed as this add-on"""
    tid = int(d.name, 16)
    def recs(v, typ):
        try: return [r for r in cnmt.records(v) if r.type == typ]
        except cnmt.Error as e: raise Error(str(e)) from None
    def checked(n):   # an add-on version folder in the console's layout, its records checked
        addon = recs(n / 'base', cnmt.ADDON)
        if wrong := [r for r in addon if r.id != tid]: raise Error(f'{n / "base"}: its content meta record is of add-on {wrong[0].id:016X}, the folder is {tid:016X}')
        want = next((r.data_patch_id for r in addon if r.data_patch_id), None)
        for u in (n / 'updates').iterdir() if (n / 'updates').is_dir() else []:
            for r in recs(u, cnmt.DATA_PATCH):
                if r.data_id != tid or (want is not None and r.id != want):
                    raise Error(f'{u}: its content meta record is data patch {r.id:016X} of add-on {r.data_id:016X}; this is add-on {tid:016X}'
                                + (f', whose data patch is {want:016X}' if want is not None else ''))
    def found(roms):
        if len(roms) != 1: raise Error(f'{d}: expected one RomFS folder (<version>/base/program/<index>/romfs and its updates/<version>/, or <version>/romfs), found {len(roms)}'
                                       + ''.join(f'\n  {r}' for r in roms))
        return roms[0]
    def package(v):   # one content package's RomFS folders: program/<index>/romfs, or program/<index>/data/romfs when the index also has a program
        return sorted(v.glob('program/*/romfs')) + sorted(v.glob('program/*/data/romfs'))
    def version(n):   # one add-on version folder's RomFS folders: its own romfs/ (flattened), else its newest data patch's, else its base's
        if (n / 'romfs').is_dir(): return [n / 'romfs']
        checked(n)
        ups = sorted(((int(u.name), u) for u in (n / 'updates').iterdir() if u.is_dir() and u.name.isdigit()), reverse=True) if (n / 'updates').is_dir() else []
        return next((r for r in (package(v) for _, v in ups + [(0, n / 'base')]) if r), [])
    vers = [(n, r) for n in sorted(p for p in d.iterdir() if p.is_dir() and p.name != 'romfs') if (r := version(n))]
    if len(vers) > 1 and all(n.name.isdigit() for n, _ in vers): vers = [max(vers, key=lambda t: int(t[0].name))]
    if not vers: return found([d / 'romfs'] if (d / 'romfs').is_dir() else [])
    return found([r for _, rs in vers for r in rs])


def dlc_required_version(rom):
    """The game version an add-on's RomFS needs: RequiredApplicationVersion of the content meta record beside it (a data patch's, else the add-on's: the package
    the RomFS came from), 0 when there is none (the flattened layout, a NUP file). On the console an add-on below it is not used"""
    if in_nup(rom): return 0
    metas = [m for m in (rom.parent / 'meta', rom.parent.parent / 'meta') if m.is_dir()][:1]
    try: return max((cnmt.read(f).required_version for m in metas for f in sorted(m.glob('*.cnmt'))), default=0)
    except cnmt.Error as e: raise Error(str(e)) from None


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
