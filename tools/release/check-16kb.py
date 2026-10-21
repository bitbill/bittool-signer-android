#!/usr/bin/env python3
"""16 KB page-size check of every native library in an APK or App Bundle (docs/release.md, ownbit-build-release §7.2.8).

    python3 tools/release/check-16kb.py app-apk-release.apk [app-play-release.aab ...]

For every `.so` (APK: `lib/<abi>/*.so`; AAB: `<module>/lib/<abi>/*.so`) it prints

    OK   16384 arm64-v8a  lib/arm64-v8a/libbittoolsigner_engine.so   (zip offset 16384-aligned, stored)

and fails when
  - a PT_LOAD segment of the ELF file is aligned to less than 16 KB (min p_align < 16384): such a library cannot be
    loaded on a 16 KB-page device (Android 15+, a Google Play requirement for 64-bit ABIs). Every ABI is checked,
    32-bit ones too: the engine is linked with `-z max-page-size=16384` for all of them, so a 4 KB library is new;
  - in an APK, the library is compressed or its data does not start on a 16 KB boundary of the ZIP file
    (`packaging.jniLibs.useLegacyPackaging = false`: the system maps the library straight out of the APK, which needs
    the same alignment that `zipalign -c -P 16 4` checks). An App Bundle is not checked for ZIP alignment: Play and
    bundletool align the APKs they generate.
Exit status 0 when every library is OK and at least one was found.
"""
import struct
import sys
import zipfile

PAGE = 16384
PT_LOAD = 1
MACHINES = {0x03: 'x86', 0x28: 'arm', 0x3E: 'x86_64', 0xB7: 'aarch64'}


def min_load_align(elf: bytes):
    """(min p_align over PT_LOAD segments, ELF class bits, machine) of an ELF image; raises ValueError if not ELF."""
    if len(elf) < 52 or elf[:4] != b'\x7fELF':
        raise ValueError('not an ELF file')
    cls, data = elf[4], elf[5]
    endian = '<' if data == 1 else '>'
    machine = struct.unpack_from(endian + 'H', elf, 0x12)[0]
    if cls == 2:  # ELF64
        phoff = struct.unpack_from(endian + 'Q', elf, 0x20)[0]
        phentsize, phnum = struct.unpack_from(endian + 'HH', elf, 0x36)
        align_at, align_fmt = 48, 'Q'
    elif cls == 1:  # ELF32
        phoff = struct.unpack_from(endian + 'I', elf, 0x1C)[0]
        phentsize, phnum = struct.unpack_from(endian + 'HH', elf, 0x2A)
        align_at, align_fmt = 28, 'I'
    else:
        raise ValueError(f'unknown ELF class {cls}')
    aligns = []
    for i in range(phnum):
        base = phoff + i * phentsize
        p_type = struct.unpack_from(endian + 'I', elf, base)[0]
        if p_type == PT_LOAD:
            aligns.append(struct.unpack_from(endian + align_fmt, elf, base + align_at)[0])
    if not aligns:
        raise ValueError('no PT_LOAD segment')
    return min(aligns), 64 if cls == 2 else 32, MACHINES.get(machine, hex(machine))


def data_offset(path: str, info: zipfile.ZipInfo) -> int:
    """Offset of the entry's data in the ZIP file (after its local header, whose extra field can differ)."""
    with open(path, 'rb') as f:
        f.seek(info.header_offset)
        header = f.read(30)
    if header[:4] != b'PK\x03\x04':
        raise ValueError('bad local header')
    name_len, extra_len = struct.unpack_from('<HH', header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check(path: str) -> tuple[int, int]:
    """(libraries checked, libraries failing) for one APK/AAB."""
    is_bundle = path.endswith('.aab')
    checked = bad = 0
    print(f'== {path}')
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            name = info.filename
            parts = name.split('/')
            if not name.endswith('.so'):
                continue
            # lib/<abi>/x.so (APK) or <module>/lib/<abi>/x.so (AAB)
            if 'lib' not in parts or len(parts) < 3:
                continue
            abi = parts[parts.index('lib') + 1]
            checked += 1
            problems = []
            try:
                align, bits, machine = min_load_align(z.read(info))
                if align < PAGE:
                    problems.append(f'LOAD p_align {align} < {PAGE}')
            except ValueError as e:
                align, bits, machine = 0, 0, '?'
                problems.append(str(e))
            placement = ''
            if not is_bundle:
                if info.compress_type != zipfile.ZIP_STORED:
                    problems.append('compressed in the APK (expected stored, useLegacyPackaging = false)')
                    placement = 'compressed'
                else:
                    off = data_offset(path, info)
                    if off % PAGE:
                        problems.append(f'zip data offset {off} not {PAGE}-aligned')
                    placement = f'stored at zip offset {off}' + (' (16 KB-aligned)' if off % PAGE == 0 else '')
            status = 'BAD' if problems else 'OK '
            bad += bool(problems)
            detail = '; '.join(problems) if problems else placement
            print(f'  {status} {align:>6} {abi:<12} {bits}-bit {machine:<8} {name}' + (f'  [{detail}]' if detail else ''))
    if checked == 0:
        print('  BAD no native library found (the engine library must be present)')
    return checked, bad


def main(argv):
    if len(argv) < 2:
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2
    total = failures = 0
    for path in argv[1:]:
        n, b = check(path)
        total += n
        failures += b + (n == 0)
    print(f'{total} native libraries checked, {failures} problem(s)')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
