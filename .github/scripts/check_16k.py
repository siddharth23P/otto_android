"""Every arm64-v8a native library in an APK loads on a 16 KB page-size phone (#14).

Reads the APK's lib/ and the ELF files inside Chaquopy's .imy archives (zips) and fails when a LOAD
segment is aligned below 16 KB. x86_64 is not checked: Chaquopy's x86_64 OpenBLAS is 4 KB-aligned,
which only an emulator ever meets.
    python3 check_16k.py app.apk [...]
"""
from __future__ import annotations

import io
import struct
import sys
import zipfile

PAGE = 16384
AARCH64 = 183


def load_alignment(data: bytes) -> int | None:
    """The smallest LOAD alignment of a 64-bit arm64 ELF, or None for anything else."""
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return None
    if struct.unpack_from("<H", data, 0x12)[0] != AARCH64:
        return None
    phoff = struct.unpack_from("<Q", data, 0x20)[0]
    size, count = struct.unpack_from("<HH", data, 0x36)
    aligns = [struct.unpack_from("<Q", data, phoff + i * size + 0x30)[0]
              for i in range(count) if struct.unpack_from("<I", data, phoff + i * size)[0] == 1]
    return min(aligns) if aligns else None


def walk(archive: zipfile.ZipFile, prefix: str):
    for name in archive.namelist():
        data = archive.read(name)
        if name.endswith(".imy"):
            yield from walk(zipfile.ZipFile(io.BytesIO(data)), f"{prefix}{name}!")
        else:
            yield f"{prefix}{name}", data


def main(paths: list[str]) -> int:
    bad = 0
    for path in paths:
        checked = 0
        for name, data in walk(zipfile.ZipFile(path), ""):
            align = load_alignment(data)
            if align is None:
                continue
            checked += 1
            if align < PAGE:
                bad += 1
                print(f"::error::{path}: {name} is aligned to {align} bytes, below {PAGE}")
        print(f"{path}: {checked} arm64 libraries checked")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
