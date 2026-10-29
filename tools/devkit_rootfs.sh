#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds a clean rootfs; never exports the user's Android filesystem.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${DEVKIT_BUILD_DIR:-/root/.cache/opencodeandroid/developer-build}"
[[ "$BUILD" = /* && "$BUILD" != / && "$BUILD" != /root && "$BUILD" != /home ]] || { echo 'Invalid dedicated builder directory' >&2; exit 1; }
SYSROOT="$BUILD/rootfs"
phase="${1:?create, resolve, install or export}"
mkdir -p "$BUILD"
if [ "$(uname -m)" != aarch64 ]; then python3 "$ROOT/tools/register_arm64_binfmt.py"; fi
if [ ! -f "$BUILD/base-installed" ]; then
    [ ! -e "$SYSROOT" ] || { echo 'Unrecognized builder tree; preserved' >&2; exit 1; }
    mkdir "$SYSROOT"
    digest="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha256"])' "$ROOT/runtime/debian-arm64-v8a.json")"
    archive="$ROOT/.cache/downloads/debian-$digest.tar.gz"
    printf '%s  %s\n' "$digest" "$archive" | sha256sum -c --status
    tar --numeric-owner -xzf "$archive" -C "$SYSROOT"
    cp "$SYSROOT/etc/apt/sources.list.d/debian.sources" "$BUILD/original-debian.sources"
    touch "$BUILD/base-installed"
fi
test "$(chroot "$SYSROOT" /usr/bin/dpkg --print-architecture)" = arm64
if [ "$phase" = create ]; then echo 'Clean Debian ARM64 builder ready'; exit 0; fi
if [ "$phase" = export ]; then
    test -f "$BUILD/developer-installed"
    test -f "$SYSROOT/etc/apt/sources.list.d/debian.sources"
    test ! -f "$SYSROOT/etc/apt/sources.list.d/devkit-build.sources"
    ! mountpoint -q "$SYSROOT/proc"
    ! mountpoint -q "$SYSROOT/dev"
    # Builder logs contain timestamps and are unnecessary in the distributable.
    find "$SYSROOT/var/log" -type f -exec truncate -s 0 {} +
    tar --sort=name --numeric-owner --mtime='2026-10-27 00:00:00Z' --clamp-mtime \
        --exclude='./proc/*' --exclude='./sys/*' --exclude='./dev/*' --exclude='./opt/devkit-mirror' \
        --exclude='./tmp/*' --exclude='./var/tmp/*' --exclude='./var/lib/apt/lists/*' \
        -C "$SYSROOT" -cf - . | gzip -n -6 > "$ROOT/.cache/debian-developer-arm64.tar.gz"
    cp "$SYSROOT/usr/share/opencode-android/developer-packages.tsv" "$ROOT/runtime/developer-packages-arm64.tsv"
    echo 'Developer rootfs exported'
    exit 0
fi

mkdir -p "$SYSROOT/opt/devkit-mirror"
cp -a "$ROOT/.cache/developer-mirror/." "$SYSROOT/opt/devkit-mirror/"
rm -f "$SYSROOT/etc/apt/sources.list.d/debian.sources"
cat > "$SYSROOT/etc/apt/sources.list.d/devkit-build.sources" <<'SOURCES'
Types: deb
URIs: file:/opt/devkit-mirror/debian
Suites: trixie trixie-updates
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
Check-Valid-Until: no

Types: deb
URIs: file:/opt/devkit-mirror/debian-security
Suites: trixie-security
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
Check-Valid-Until: no
SOURCES
printf '#!/bin/sh\nexit 101\n' > "$SYSROOT/usr/sbin/policy-rc.d"
chmod 755 "$SYSROOT/usr/sbin/policy-rc.d"
cleanup() {
    if mountpoint -q "$SYSROOT/dev"; then umount "$SYSROOT/dev"; fi
    if mountpoint -q "$SYSROOT/proc"; then umount "$SYSROOT/proc"; fi
}
trap cleanup EXIT
mount --bind /proc "$SYSROOT/proc"
mount --bind /dev "$SYSROOT/dev"
mapfile -t packages < <(python3 -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))["packages"]))' "$ROOT/runtime/developer-profile.json")
for package in "${packages[@]}"; do [[ "$package" =~ ^[a-z0-9][a-z0-9+.-]*$ ]] || exit 1; done
if [ "$phase" = resolve ]; then
    chroot "$SYSROOT" apt-get -o Acquire::Languages=none -o Acquire::PDiffs=false -o Acquire::By-Hash=false update
    chroot "$SYSROOT" apt-get -o Acquire::ForceHash=sha256 --yes --no-install-recommends --print-uris install "${packages[@]}" > "$ROOT/.cache/developer-arm64-uris.txt"
    echo 'Developer dependency graph resolved against signed Debian indexes'
elif [ "$phase" = install ]; then
    # A locked dependency graph skips the optional resolve phase, but a clean
    # base image still needs apt's local package lists and signature checks.
    chroot "$SYSROOT" apt-get -o Acquire::Languages=none -o Acquire::PDiffs=false -o Acquire::By-Hash=false update
    chroot "$SYSROOT" /usr/bin/env DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::ForceHash=sha256 --yes --no-install-recommends install "${packages[@]}"
    mkdir -p "$SYSROOT/usr/share/opencode-android"
    chroot "$SYSROOT" dpkg-query -W '-f=${binary:Package}\t${Version}\t${Architecture}\n' > "$SYSROOT/usr/share/opencode-android/developer-packages.tsv"
    cp "$ROOT/runtime/developer-profile.json" "$SYSROOT/usr/share/opencode-android/"
    cp "$BUILD/original-debian.sources" "$SYSROOT/etc/apt/sources.list.d/debian.sources"
    rm -f "$SYSROOT/etc/apt/sources.list.d/devkit-build.sources" "$SYSROOT/usr/sbin/policy-rc.d"
    chroot "$SYSROOT" apt-get clean
    touch "$BUILD/developer-installed"
else echo "Unknown builder phase: $phase" >&2; exit 1
fi
