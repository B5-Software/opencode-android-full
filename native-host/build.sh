#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# PRoot build procedure adapted from DroidNative/DroidNativeApp native-host/build.sh
# at fa591010d6e33adbf5a79178b874fd4abdc58931 (GPL-3.0-or-later).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.cache/native"
NDK="${ANDROID_NDK:?Set ANDROID_NDK to a Linux Android NDK r28+ directory}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
PROOT_COMMIT=4ac94ecbb567608725e1d68d5ea617800a76c9c4
TALLOC_VERSION=2.5.0
TALLOC_SHA=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
SQLITE_VERSION=3540100
SQLITE_SHA=926ef8d3eab93fbf6e06011901273eec076b25594a4e1cdadd50c987f4f6d64e
mkdir -p "$CACHE"
fetch() {
    local url="$1" digest="$2" directory="$3" archive="$CACHE/$3.archive"
    if [ ! -d "$CACHE/$directory" ]; then
        curl --fail --location --retry 3 "$url" -o "$archive"
        printf '%s  %s\n' "$digest" "$archive" | sha256sum --check --status
        tar -xf "$archive" -C "$CACHE"
    fi
}
fetch "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" "$TALLOC_SHA" "talloc-$TALLOC_VERSION"
fetch "https://www.sqlite.org/2026/sqlite-autoconf-$SQLITE_VERSION.tar.gz" "$SQLITE_SHA" "sqlite-autoconf-$SQLITE_VERSION"
if [ ! -d "$CACHE/proot-source/.git" ]; then
    source="$ROOT/.cache/proot-src"
    [ -d "$source/.git" ] || source=https://github.com/DroidNative/proot.git
    git clone "$source" "$CACHE/proot-source"
fi
git -C "$CACHE/proot-source" cat-file -e "$PROOT_COMMIT^{commit}"
for abi in "${@:-arm64-v8a}"; do
    case "$abi" in arm64-v8a) triple=aarch64-linux-android29;; x86_64) triple=x86_64-linux-android29;; *) exit 2;; esac
    build="$CACHE/$abi"
    mkdir -p "$build/talloc/include"
    if [ ! -d "$build/proot/.git" ] && [ ! -f "$build/proot/.git" ]; then
        git -C "$CACHE/proot-source" worktree add --detach "$build/proot" "$PROOT_COMMIT"
    fi
    test "$(git -C "$build/proot" rev-parse HEAD)" = "$PROOT_COMMIT"
    for patch_file in "$ROOT/native-host/patches/"*.patch; do
        if git -C "$build/proot" apply --check "$patch_file" 2>/dev/null; then
            git -C "$build/proot" apply "$patch_file"
        elif ! git -C "$build/proot" apply --reverse --check "$patch_file" 2>/dev/null; then
            echo "Native source does not match the pinned lifecycle patch" >&2; exit 1
        fi
    done
    # A new syscall enum changes IDs in every object. Invalidate cached objects
    # when patches change, including when Windows/WSL timestamps differ.
    patch_digest="$(cat "$ROOT/native-host/patches/"*.patch | sha256sum | cut -d' ' -f1)"
    if [ "$(cat "$build/patch-digest" 2>/dev/null || true)" != "$patch_digest" ]; then
        make -C "$build/proot/src" clean
    fi
    "$TC/$triple-clang" -c -O2 -fPIC -D_GNU_SOURCE -D__STDC_WANT_LIB_EXT1__=1 \
        -I"$ROOT/native-host/talloc" -I"$CACHE/talloc-$TALLOC_VERSION/lib/replace" -I"$CACHE/talloc-$TALLOC_VERSION" \
        "$CACHE/talloc-$TALLOC_VERSION/talloc.c" -o "$build/talloc/talloc.o"
    "$TC/$triple-clang" -c -O2 -fPIC "$ROOT/native-host/talloc/replace_min.c" -o "$build/talloc/replace_min.o"
    "$TC/llvm-ar" rcs "$build/talloc/libtalloc.a" "$build/talloc/talloc.o" "$build/talloc/replace_min.o"
    cp "$CACHE/talloc-$TALLOC_VERSION/talloc.h" "$build/talloc/include/"
    CPPFLAGS="-I$build/talloc/include -DARG_MAX=131072 -DVERSION=\\\"ocaf-$PROOT_COMMIT\\\"" \
    LDFLAGS="-L$build/talloc -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    make -C "$build/proot/src" -j"$(nproc)" proot \
        CC="$TC/$triple-clang" STRIP="$TC/llvm-strip" OBJCOPY="$TC/llvm-objcopy" OBJDUMP="$TC/llvm-objdump" \
        PROOT_UNBUNDLE_LOADER=/nonexistent SQLITE_DIR="$CACHE/sqlite-autoconf-$SQLITE_VERSION"
    dst="$ROOT/app/src/main/jniLibs/$abi"
    mkdir -p "$dst"
    "$TC/llvm-strip" -o "$dst/libproot.so" "$build/proot/src/proot"
    cp "$build/proot/src/loader/loader" "$dst/libproot-loader.so"
    chmod 755 "$dst/"*.so
    python3 "$ROOT/tools/check_elf.py" "$dst/"*.so
    printf '%s\n' "$patch_digest" > "$build/patch-digest"
done
