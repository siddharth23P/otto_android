"""Packs one rebuilt helper library as a wheel laid out like Chaquopy's own.

    chaquopy/include/..., chaquopy/lib/lib*.so, chaquopy/lib/pkgconfig/*.pc
    chaquopy_<name>-<version>.dist-info/{METADATA, WHEEL, RECORD, <license>}

usage: pack_native_wheel.py <name> <abi> <api> <build> <root> <source> <outdir>
where <root> holds the installed `chaquopy/` tree and <source> the unpacked sources (for the licence).
"""
from __future__ import annotations

import base64
import hashlib
import pathlib
import sys
import zipfile

#: name -> (distribution, version, licence file in the sources, requirements)
PACKAGES = {
    "libxml2": ("chaquopy-libxml2", "2.9.8", "COPYING", []),
    "libxslt": ("chaquopy-libxslt", "1.1.32", "COPYING", ["chaquopy-libxml2 (>=2.9.8)"]),
    "freetype": ("chaquopy-freetype", "2.9.1", "docs/FTL.TXT", []),
}


def _record_line(arcname: str, data: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
    return f"{arcname},sha256={digest},{len(data)}"


def main(name: str, abi: str, api: str, build: str, root: str, source: str, outdir: str) -> None:
    dist, version, licence, requires = PACKAGES[name]
    tag = f"py3-none-android_{api}_{abi}"
    stem = dist.replace("-", "_")
    info = f"{stem}-{version}.dist-info"
    files: dict[str, bytes] = {}
    base = pathlib.Path(root)
    for path in sorted((base / "chaquopy").rglob("*")):
        if path.is_file() and not path.is_symlink():
            files[path.relative_to(base).as_posix()] = path.read_bytes()
    if not any(n.endswith(".so") for n in files):
        raise SystemExit(f"{name} {abi}: no library was built")
    # Build paths in the .pc files mean nothing on a phone; keep the files, as Chaquopy does.
    lic = pathlib.Path(source) / licence
    files[f"{info}/{lic.name}"] = lic.read_bytes()
    files[f"{info}/METADATA"] = "\n".join(
        ["Metadata-Version: 1.2", f"Name: {dist}", f"Version: {version}", "Summary: ", "Download-URL: "]
        + [f"Requires-Dist: {r}" for r in requires]
    ).encode() + b"\n\n"
    files[f"{info}/WHEEL"] = (
        "Wheel-Version: 1.0\nRoot-Is-Purelib: false\nGenerator: otto_android pack_native_wheel.py\n"
        f"Build: {build}\nTag: {tag}\n\n"
    ).encode()
    record = [_record_line(n, d) for n, d in files.items()] + [f"{info}/RECORD,,"]
    files[f"{info}/RECORD"] = ("\n".join(record) + "\n").encode()

    out = pathlib.Path(outdir) / f"{stem}-{version}-{build}-{tag}.whl"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as whl:
        for arcname, data in files.items():
            whl.writestr(arcname, data)
    print(out)


if __name__ == "__main__":
    main(*sys.argv[1:8])
