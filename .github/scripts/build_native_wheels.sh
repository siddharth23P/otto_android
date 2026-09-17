#!/usr/bin/env bash
# Chaquopy's libxml2, libxslt (with libexslt) and freetype, rebuilt with 16 KB page alignment.
#
# lxml's and Pillow's own modules from Chaquopy's index are 16 KB-aligned, but the helper libraries
# they load (chaquopy-libxml2 2.9.8, chaquopy-libxslt 1.1.32, chaquopy-freetype 2.9.1, last built in
# 2019) are 4 KB-aligned and would not load on a 16 KB-page phone. These are drop-in replacements:
# the same versions and configure flags as Chaquopy's recipes (chaquo/chaquopy
# server/pypi/packages/<name>/build.sh), the same file layout and SONAMEs, and an android_24 tag,
# which pip prefers over the originals' android_21. check_native_wheels.py compares each with
# Chaquopy's own build before the set is published.
#
# usage: build_native_wheels.sh <outdir>      (needs ANDROID_NDK_LATEST_HOME or ANDROID_NDK_HOME)
set -euo pipefail

out=$(realpath -m "${1:?output directory}")
ndk=${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:?no NDK}}
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
api=24
build=9   # the wheel build tag; Chaquopy's newest builds are 2
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
mkdir -p "$out" "$work/src" "$work/build" "$work/prefix"

fetch() {  # name url sha256
    local file="$work/src/$(basename "$2")"
    curl -fsSL --retry 3 -o "$file" "$2"
    echo "$3  $file" | sha256sum -c --quiet
    mkdir -p "$work/src/$1"
    tar -xf "$file" -C "$work/src/$1" --strip-components=1
}
fetch libxml2 https://download.gnome.org/sources/libxml2/2.9/libxml2-2.9.8.tar.xz \
    dcca21d624bbbe094fcc104e1f15f2eacfb65aecd0e38ed220aeca56b62c81e2
fetch libxslt https://download.gnome.org/sources/libxslt/1.1/libxslt-1.1.32.tar.xz \
    b7da90eaa6b0dae9e9a3769e29a757342eef0edb9a7b431424814375414422af
fetch freetype https://download.savannah.gnu.org/releases/freetype/freetype-2.9.1.tar.gz \
    ec391504e55498adceb30baceebd147a6e963f636eb617424bcfc47a169898ce

# Chaquopy names every library plainly (libxml2.so, not libxml2.so.2): libtool is told to do the same.
plain_names() {
    sed -i -e 's/^soname_spec=.*/soname_spec="\\$libname\\$shared_ext"/' \
           -e 's/^library_names_spec=.*/library_names_spec="\\$libname\\$shared_ext"/' libtool
}

for abi in arm64_v8a x86_64; do
    case $abi in
        arm64_v8a) host=aarch64-linux-android ;;
        x86_64) host=x86_64-linux-android ;;
    esac
    export CC="$toolchain/bin/$host$api-clang" CXX="$toolchain/bin/$host$api-clang++"
    export AR="$toolchain/bin/llvm-ar" RANLIB="$toolchain/bin/llvm-ranlib" STRIP="$toolchain/bin/llvm-strip"
    # 16 KB pages; and libxml2's version script names symbols this configuration leaves out, which
    # the 2019 linker only warned about.
    export LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,--undefined-version"
    export CFLAGS="-O2"

    for name in libxml2 libxslt freetype; do
        prefix="$work/prefix/$name-$abi/chaquopy"
        tree="$work/build/$name-$abi"
        rm -rf "$tree" && cp -a "$work/src/$name" "$tree"
        (
            cd "$tree"
            case $name in
                libxml2)
                    ./configure --host=$host --prefix="$prefix" --without-python --without-lzma
                    plain_names
                    make -j"$(nproc)"
                    make install
                    rm -rf "$prefix"/{bin,share} "$prefix"/lib/{*.a,*.la,*.sh}
                    ;;
                libxslt)
                    xml="$work/prefix/libxml2-$abi/chaquopy"
                    ./configure --host=$host --prefix="$prefix" --without-crypto --without-python \
                        --with-libxml-prefix="$xml" --with-libxml-include-prefix="$xml/include/libxml2" \
                        --with-libxml-libs-prefix="$xml/lib"
                    plain_names
                    make -j"$(nproc)"
                    make install
                    rm -rf "$prefix"/{bin,share} "$prefix"/lib/{*.a,*.la,*.sh}
                    ;;
                freetype)
                    ./configure --host=$host --prefix="$prefix" --without-harfbuzz --without-png \
                        --without-bzip2 --without-brotli
                    ( cd builds/unix && plain_names ) || true
                    make -j"$(nproc)"
                    make install
                    mv "$prefix"/include/freetype2/* "$prefix"/include/
                    rmdir "$prefix"/include/freetype2
                    rm -rf "$prefix"/share "$prefix"/lib/*.a "$prefix"/lib/*.la
                    ;;
            esac
            # Only the real libraries: no symlinks, no leftover versioned names.
            find "$prefix/lib" -maxdepth 1 -type l -delete
            "$STRIP" --strip-unneeded "$prefix"/lib/*.so
        )
        python3 "$here/pack_native_wheel.py" "$name" "$abi" "$api" "$build" "$prefix/.." \
            "$work/src/$name" "$out"
    done
done
ls -l "$out"
