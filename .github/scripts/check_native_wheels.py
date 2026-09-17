"""Checks the rebuilt helper wheels (build_native_wheels.sh) against Chaquopy's own builds.

A replacement must load wherever the original did and offer everything lxml and Pillow link against:
for every library, the same SONAME, no dependency the original lacked, every symbol version and every
exported symbol the original defines -- and LOAD segments aligned to at least 16 KB.

usage: check_native_wheels.py <wheel>...        (needs pyelftools; downloads the originals)
"""
from __future__ import annotations

import io
import re
import sys
import urllib.request
import zipfile

from elftools.elf.dynamic import DynamicSection
from elftools.elf.elffile import ELFFile
from elftools.elf.gnuversions import GNUVerDefSection

INDEX = "https://chaquo.com/pypi-13.1"
#: Symbols the linker defines rather than the library: nothing links against them.
LINKER_SYMBOLS = frozenset({"_end", "_edata", "__bss_start", "__bss_start__", "__bss_end__", "_bss_end__", "__end__"})
#: Chaquopy's newest builds of the versions we replace.
ORIGINAL_BUILD = "2"
WHEEL = re.compile(r"(?P<stem>chaquopy_[a-z0-9]+)-(?P<version>[^-]+)-(?P<build>\d[^-]*)-py3-none-android_\d+_(?P<abi>\w+)\.whl$")


def libraries(data: bytes) -> dict[str, dict]:
    found = {}
    with zipfile.ZipFile(io.BytesIO(data)) as whl:
        for name in whl.namelist():
            if name.startswith("chaquopy/lib/") and name.endswith(".so"):
                found[name.rsplit("/", 1)[1]] = describe(whl.read(name))
    return found


def describe(blob: bytes) -> dict:
    elf = ELFFile(io.BytesIO(blob))
    soname, needed, versions, exported = None, set(), set(), set()
    for section in elf.iter_sections():
        if isinstance(section, DynamicSection):
            for tag in section.iter_tags():
                if tag.entry.d_tag == "DT_NEEDED":
                    needed.add(tag.needed)
                elif tag.entry.d_tag == "DT_SONAME":
                    soname = tag.soname
        elif isinstance(section, GNUVerDefSection):
            for verdef, aux in section.iter_versions():
                if not verdef["vd_flags"] & 1:  # VER_FLG_BASE only names the library itself
                    versions.add(next(aux).name)
    dynsym = elf.get_section_by_name(".dynsym")
    for symbol in dynsym.iter_symbols():
        # The 2019 linker also emitted each version name as an absolute symbol; the versions are compared above.
        if (symbol["st_shndx"] not in ("SHN_UNDEF", "SHN_ABS") and symbol.name not in LINKER_SYMBOLS
                and symbol["st_info"]["bind"] in ("STB_GLOBAL", "STB_WEAK") and symbol.name):
            exported.add(symbol.name)
    align = min(seg["p_align"] for seg in elf.iter_segments() if seg["p_type"] == "PT_LOAD")
    return {"soname": soname, "needed": needed, "versions": versions, "exported": exported, "align": align}


def original(stem: str, version: str, abi: str) -> bytes:
    dist = stem.replace("_", "-")
    url = f"{INDEX}/{dist}/{stem}-{version}-{ORIGINAL_BUILD}-py3-none-android_21_{abi}.whl"
    with urllib.request.urlopen(url, timeout=60) as reply:
        return reply.read()


def main(paths: list[str]) -> int:
    problems = []
    for path in paths:
        match = WHEEL.search(path)
        if not match:
            problems.append(f"{path}: not a helper wheel name")
            continue
        ours = libraries(open(path, "rb").read())
        theirs = libraries(original(match["stem"], match["version"], match["abi"]))
        if set(ours) != set(theirs):
            problems.append(f"{path}: libraries {sorted(ours)}, Chaquopy's {sorted(theirs)}")
        for lib in sorted(set(ours) & set(theirs)):
            new, old = ours[lib], theirs[lib]
            where = f"{path}: {lib}"
            before = len(problems)
            if new["align"] < 16384:
                problems.append(f"{where}: aligned to {new['align']}")
            if new["soname"] != old["soname"]:
                problems.append(f"{where}: SONAME {new['soname']}, Chaquopy's {old['soname']}")
            if extra := new["needed"] - old["needed"]:
                problems.append(f"{where}: needs {sorted(extra)}, which Chaquopy's does not")
            if lost := old["versions"] - new["versions"]:
                problems.append(f"{where}: lacks symbol versions {sorted(lost)}")
            if lost := old["exported"] - new["exported"]:
                problems.append(f"{where}: lacks {len(lost)} exported symbols, e.g. {sorted(lost)[:8]}")
            if len(problems) == before:
                print(f"ok {where}: soname {new['soname']}, needs {sorted(new['needed'])}, "
                      f"{len(new['versions'])} versions, {len(new['exported'])} symbols, align {new['align']}")
    for problem in problems:
        print(f"::error::{problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
