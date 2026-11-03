#!/usr/bin/env python3
# Turn one of the host's Mach-O assembly files (dm/host/entry.S, dm/gfx/nvn/nvn_entry.S, the generated nvn_thunks.S) into ELF assembly for the Android build, so the Apple
# sources stay as they are. Usage: macho_asm_to_elf.py <in.S> <out.S>
# Rules: a C symbol loses Mach-O's leading underscore; sym@PAGE / sym@PAGEOFF become sym / :lo12:sym; .private_extern becomes .hidden; .zerofill becomes a .bss block
# (the current section is kept, as Mach-O's .zerofill keeps it); .cstring becomes a mergeable string section; a call to a Darwin function goes to its platform-layer twin.
import re, sys

RENAME = {'mach_absolute_time': 'plat_abs_time'}   # plat_android.c: CLOCK_MONOTONIC nanoseconds (nvn_prof_ticks then count ns, timebase 1/1)
SYM = re.compile(r'(?<![\w.$])_([A-Za-z_]\w*)')

def sym(m):
    name = m.group(1)
    return RENAME.get(name, name)

def convert(text):
    out = []
    for line in text.splitlines():
        code, sep, comment = line.partition('//')
        z = re.match(r'(\s*)\.zerofill\s+[^,]+,[^,]+,\s*(\w+)\s*,\s*([^,]+?)\s*,\s*(\d+)\s*$', code)
        if z:
            ind, name, size, align = z.groups()
            name = SYM.sub(sym, name)
            out += [f'{ind}.pushsection .bss', f'{ind}.p2align {align}', f'{name}:', f'{ind}.space {size}', f'{ind}.popsection' + (f'   //{comment}' if sep else '')]
            continue
        code = re.sub(r'(\w+)@PAGEOFF', r':lo12:\1', code)
        code = re.sub(r'(\w+)@PAGE\b', r'\1', code)
        code = re.sub(r'^(\s*)\.private_extern\b', r'\1.hidden', code)
        code = re.sub(r'^(\s*)\.cstring\b', r'\1.section .rodata.str1.1,"aMS",@progbits,1', code)
        code = SYM.sub(sym, code)
        out.append(code + sep + comment)
    out.append('    .section .note.GNU-stack,"",@progbits')
    return '\n'.join(out) + '\n'

if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    with open(src, encoding='utf-8') as f: text = f.read()
    with open(dst, 'w', encoding='utf-8') as f: f.write(convert(text))
