#!/usr/bin/env python3
"""Build a diagnostic shader library with selected compute modules simplified.

Uses the library's ABI and per-entry content hashes. The original library stays
available for A/B runs. Requires SPIRV-Tools and Python zstandard on the build host.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'dm/gfx/tools'))
import vk_android_delta as delta
import zstandard  # pyright: ignore[reportMissingImports]  # Optional build-host dependency.


def optimize(source, destination, keys):
    if destination.exists():
        raise ValueError(f'output already exists: {destination}')
    library = delta.Library(source)
    try:
        selected = [library.entry(library.index[key]) for key in sorted(set(keys))]
        if any(entry[16] != delta.VK['STAGE_CS'] for _, entry, _, _, _ in selected):
            raise ValueError('select compute shaders only')
        report = []
        with tempfile.TemporaryDirectory(dir=destination.parent) as scratch:
            scratch = Path(scratch)
            staged = scratch / 'library.tmp'
            shutil.copyfile(source, staged)
            with staged.open('r+b') as stream:
                stream.seek(0, 2)
                end = stream.tell()
                for key, entry, bindings, constants, modules in selected:
                    changed = []
                    first = struct.unpack_from('<I', entry, 20)[0]
                    for index, (variant, _, stored, codec) in enumerate(modules):
                        original = delta.spirv(stored, codec, zstandard.ZstdDecompressor())
                        input_file, output_file = scratch / 'input.spv', scratch / 'output.spv'
                        input_file.write_bytes(original)
                        subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(input_file)], check=True)
                        subprocess.run(['spirv-opt', '--redundancy-elimination', '--simplify-instructions',
                                        str(input_file), '-o', str(output_file)], check=True)
                        subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(output_file)], check=True)
                        binary = output_file.read_bytes()
                        compressed = zstandard.ZstdCompressor(level=3).compress(binary)
                        end = (end + 15) // 16 * 16
                        offset = end - library.blob_off
                        stream.seek(end)
                        stream.write(compressed)
                        stream.seek(library.mo + (first + index) * delta.MOD.size)
                        stream.write(delta.MOD.pack(offset & 0xffffffff, len(binary), len(compressed),
                                                    variant, delta.VK['CODEC_ZSTD'], offset >> 32))
                        end += len(compressed)
                        changed.append({'variant': variant, 'before_bytes': len(original), 'after_bytes': len(binary),
                                        'before_sha256': hashlib.sha256(original).hexdigest(),
                                        'after_sha256': hashlib.sha256(binary).hexdigest()})
                    report.append({'key': key, 'modules': changed})
                header = list(delta.HEAD.unpack_from(library.m))
                header[13] = end - library.blob_off
                stream.seek(0)
                stream.write(delta.HEAD.pack(*header))
            check = delta.Library(staged)
            try:
                for key in keys:
                    check.hash_of(check.index[key], zstandard.ZstdDecompressor())
            finally:
                check.close()
            # Publish atomically without replacing a file created during the build.
            os.link(staged, destination)
        return report
    finally:
        library.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--key', action='append', required=True)
    args = parser.parse_args()
    try:
        keys = [f'{int(key, 16):016x}' for key in args.key]
        report = optimize(args.source, args.destination, keys)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'optimization failed: {error}\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
