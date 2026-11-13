#!/usr/bin/env python3
# A content meta record (.cnmt): the console's description of one package, as a dump keeps it in a content's meta/ folder (OunceCake:
# <package>/program/<index>/meta/<Type>_<ID>.cnmt). Read with switchbrew's layout (CNMT, 15.0.0+); the Switch 2 writes the same one (Super Mario Bros. Wonder's
# add-on and data patch records match it byte for byte). Only what the dump tools need: the record's ID, version and type, the extended header's links (an
# application's patch, a patch's or an add-on's application, an add-on's data patch, a data patch's add-on) and required game version, and the contents.
#   header 0x20: Id u64, Version u32, ContentMetaType u8, (u8), ExtendedHeaderSize u16, ContentCount u16, ContentMetaCount u16, ...
#   extended header, ContentCount x PackagedContentInfo (0x38: hash 0x20, ContentId 0x10, Size 5, attributes, ContentType, IdOffset), ContentMetaCount x 0x10,
#   extended data (a patch's or data patch's ExtendedDataSize), digest 0x20
# Usage: cnmt.py <file.cnmt>...   (prints each record; exit 1 on a bad one)
import struct, sys
from pathlib import Path

APPLICATION, PATCH, ADDON, DELTA, DATA_PATCH = 0x80, 0x81, 0x82, 0x83, 0x84
TYPES = {APPLICATION: 'Application', PATCH: 'Patch', ADDON: 'AddOnContent', DELTA: 'Delta', DATA_PATCH: 'DataPatch'}
CONTENT = {0: 'Meta', 1: 'Program', 2: 'Data', 3: 'Control', 4: 'HtmlDocument', 5: 'LegalInformation', 6: 'DeltaFragment'}


class Error(ValueError): pass


class Record:
    def __init__(self, id, version, type):
        self.id, self.version, self.type = id, version, type
        self.application_id = self.patch_id = self.data_patch_id = self.data_id = None
        self.required_version = 0   # RequiredApplicationVersion: the game version an add-on or a data patch needs (0: none)
        self.contents = []          # (ContentType, IdOffset, size)

    def __repr__(self):
        links = ' '.join(f'{k}={v:016X}' for k, v in (('application', self.application_id), ('patch', self.patch_id), ('data_patch', self.data_patch_id),
                                                       ('data', self.data_id)) if v is not None)
        return f'<{TYPES.get(self.type, hex(self.type))} {self.id:016X} v{self.version} {links} requires v{self.required_version}>'


def parse(b, name='record'):
    if len(b) < 0x20: raise Error(f'{name}: {len(b)} bytes, shorter than a content meta header')
    rid, ver, typ, _, exs, cc, cmc = struct.unpack_from('<QIBBHHH', b, 0)
    end = 0x20 + exs + 0x38 * cc + 0x10 * cmc + 0x20
    if len(b) < end: raise Error(f'{name}: {len(b)} bytes, its header names {end} or more')
    r, e = Record(rid, ver, typ), b[0x20:0x20 + exs]
    if typ == APPLICATION and exs >= 0x10: r.patch_id, _, r.required_version = struct.unpack_from('<QII', e)
    elif typ == PATCH and exs >= 0x8: r.application_id = struct.unpack_from('<Q', e)[0]
    elif typ == ADDON and exs >= 0xC:
        r.application_id, r.required_version = struct.unpack_from('<QI', e)
        if exs >= 0x18: r.data_patch_id = struct.unpack_from('<Q', e, 0x10)[0] or None   # 15.0.0+
    elif typ == DATA_PATCH and exs >= 0x14: r.data_id, r.application_id, r.required_version = struct.unpack_from('<QQI', e)
    for i in range(cc):
        o = 0x20 + exs + 0x38 * i
        r.contents.append((b[o + 0x36], b[o + 0x37], int.from_bytes(b[o + 0x30:o + 0x35], 'little')))
    return r


def read(path): return parse(Path(path).read_bytes(), str(path))


def records(package):
    """The content meta records of a package folder in the console's layout (<package>/program/<index>/meta/*.cnmt, or .../program/<index>/<program|data>/meta/)"""
    return [read(f) for f in sorted(Path(package).glob('program/*/meta/*.cnmt')) + sorted(Path(package).glob('program/*/*/meta/*.cnmt'))]


def main():
    bad = 0
    for p in sys.argv[1:]:
        try: r = read(p)
        except (Error, OSError) as e: print(f'[RED] {e}'); bad += 1; continue
        print(f'{p}: {r}')
        for t, off, size in r.contents: print(f'  {CONTENT.get(t, t)} IdOffset {off} {size:,} bytes')
    return 1 if bad or not sys.argv[1:] else 0


if __name__ == '__main__':
    sys.exit(main())
