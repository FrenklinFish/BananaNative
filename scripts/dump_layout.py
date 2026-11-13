#!/usr/bin/env python3
# Puts a game's dump folder in the console's layout, the one scripts/pack_dump.py describes: one folder for each content meta record (base/, updates/<version>/,
# dlc/<DLC title ID>/<version>/base/ and .../updates/<patch version>/), each content at its program index (program/<index>/exefs, romfs, control, meta). OunceCake
# serves a game this way; a flattened dump keeps program 0's folders in the version folder (base/exefs/), another program as program<index>/, and an add-on's
# RomFS as dlc/<DLC title ID>/<version>/romfs/. The files are moved, not copied (the same drive: a rename each), and every move is written to
# <dump folder>/.layout-moves.tsv first, so --undo puts each one back. The run checks that pack_dump.py reads the same game before and after, and undoes its moves
# when it does not. Without --apply it only lists the moves.
# NSP-Box and nxnative read only the flattened layout: --undo gives a folder they take back.
# Usage: dump_layout.py <dump folder> [--apply | --undo]   (exit 1 on error)
import argparse, re, sys
from pathlib import Path
import pack_dump

SECTIONS = ('exefs', 'romfs', 'control', 'meta', 'logo')   # a content's folders as OunceCake names them (its program content: exefs, romfs, logo; meta; control)
LOG = '.layout-moves.tsv'


class Error(Exception): pass


def plan(dump):
    """The moves that put a flattened dump in the console's layout, (from, to) relative to the dump folder; none for a folder already in it"""
    moves = []
    def version(v):   # a game version folder (base/ or updates/<version>/): program 0's folders, and program<index>/ folders of other programs
        moves.extend((v / s, v / 'program/0' / s) for s in SECTIONS if (v / s).is_dir())
        for p in sorted(v.iterdir()):
            if p.is_dir() and (m := re.fullmatch(r'program(\d+)', p.name)): moves.append((p, v / 'program' / str(int(m.group(1)))))
    if (dump / 'base').is_dir(): version(dump / 'base')
    for u in sorted(p for p in (dump / 'updates').iterdir() if p.is_dir() and p.name.isdigit()) if (dump / 'updates').is_dir() else []: version(u)
    for d in sorted(p for p in (dump / 'dlc').iterdir() if p.is_dir()) if (dump / 'dlc').is_dir() else []:
        moves.extend((d / s, d / '0/base/program/0' / s) for s in SECTIONS if (d / s).is_dir())   # no version folder: version 0
        for n in sorted(p for p in d.iterdir() if p.is_dir() and p.name not in SECTIONS):
            if not n.name.isdigit():
                if any((n / s).is_dir() for s in SECTIONS): raise Error(f'{n}: an add-on version folder is named by its version number (dlc/<DLC title ID>/<version>/)')
                continue
            moves.extend((n / s, n / 'base/program/0' / s) for s in SECTIONS if (n / s).is_dir())
    rel = [(a.relative_to(dump), b.relative_to(dump)) for a, b in moves]
    clash = [b for a, b in moves if b.exists()] + [b for i, (_, b) in enumerate(rel) if b in [t for _, t in rel[:i]]]
    if clash: raise Error('these folders would be overwritten, the dump has both layouts there: sort them out by hand\n' + '\n'.join(f'  {c}' for c in clash))
    return rel


def read(dump):
    """What pack_dump.py finds in the dump, for the check: the title ID; each version's main.npdm and RomFS (what its top holds); each add-on's RomFS (what its
    top holds). The paths are left out: they are what changes"""
    app, vers, dlc = pack_dump.scan(dump)
    top = lambda d: sorted(p.name for p in d.iterdir()) if d.is_dir() else None
    def npdm(v):
        f = pack_dump.version_part(dump, vers, v, 'exefs') / 'main.npdm'
        return f.read_bytes() if f.is_file() else None
    return app, {v: (npdm(v), top(pack_dump.version_part(dump, vers, v, 'romfs'))) for v in [0, *vers]}, {t: top(r) for t, r in dlc.items()}


def tidy(dump, p):   # removes the empty folders a move left, up to the dump folder
    while p != dump and p.is_dir() and not any(p.iterdir()):
        p.rmdir(); p = p.parent


def undo(dump, say=print):
    log = dump / LOG
    if not log.is_file(): raise Error(f'{dump}: no {LOG}: nothing to undo')
    rows = [l.split('\t') for l in log.read_text(encoding='utf-8').splitlines() if l and not l.startswith('#')]
    for a, b in reversed(rows):
        src, dst = dump / a, dump / b
        if not dst.exists():
            if src.exists(): continue   # not moved (a run stopped before it)
            raise Error(f'{dst}: missing, cannot move it back to {src}')
        if src.exists(): raise Error(f'{src}: exists, cannot move {dst} back there')
        src.parent.mkdir(parents=True, exist_ok=True); dst.rename(src); tidy(dump, dst.parent)
        say(f'  {b} -> {a}')
    log.unlink()


def apply(dump, moves, say=print):
    before = read(dump)
    with open(dump / LOG, 'a', encoding='utf-8') as log:
        if log.tell() == 0: log.write('# moves of scripts/dump_layout.py: from <TAB> to, relative to this folder (dump_layout.py --undo puts them back)\n')
        for a, b in moves:
            log.write(f'{a.as_posix()}\t{b.as_posix()}\n'); log.flush()   # before the move: an interrupted run is undone all the same
            (dump / b).parent.mkdir(parents=True, exist_ok=True); (dump / a).rename(dump / b); tidy(dump, (dump / a).parent)
            say(f'  {a.as_posix()} -> {b.as_posix()}')
    try: after = read(dump)
    except pack_dump.Error as e: after = e
    if after != before:
        say('the dump does not read the same after the moves; undoing them')
        undo(dump, say)
        raise Error(f'{dump}: read differently in the console layout ({after}); left as it was')


def main(argv=None):
    ap = argparse.ArgumentParser(description='Put a dump folder in the console\'s layout (base/program/0/..., updates/<v>/program/<i>/..., dlc/<id>/<v>/base/program/0/...)')
    ap.add_argument('dump', type=Path)
    g = ap.add_mutually_exclusive_group()
    g.add_argument('--apply', action='store_true', help='make the moves (without it they are only listed)')
    g.add_argument('--undo', action='store_true', help=f'put back every move in <dump>/{LOG}')
    a = ap.parse_args(argv)
    dump = a.dump.resolve()
    try:
        if not dump.is_dir(): raise Error(f'{dump}: not a folder (a .nup file has no folder layout)')
        if a.undo: undo(dump); print('done: moved back'); return 0
        moves = plan(dump)
        if not moves: print(f'{dump}: already in the console layout'); return 0
        print(f'{len(moves)} move(s){"" if a.apply else " (run with --apply to make them)"}:')
        if not a.apply:
            for m, n in moves: print(f'  {m.as_posix()} -> {n.as_posix()}')
            return 0
        apply(dump, moves)
        print(f'done: {dump} is in the console layout; {LOG} lists the moves (--undo puts them back)')
        return 0
    except (Error, pack_dump.Error, OSError) as e:
        print(f'error: {e}', file=sys.stderr); return 1


if __name__ == '__main__':
    sys.exit(main())
