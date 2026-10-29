#!/bin/bash
set -euo pipefail
case "${1:-ustc}" in
  official) archive=https://deb.debian.org/debian; security=https://security.debian.org/debian-security ;;
  ustc) archive=https://mirrors.ustc.edu.cn/debian; security=https://mirrors.ustc.edu.cn/debian-security ;;
  *) echo 'Unsupported repository'; exit 2 ;;
esac
mkdir -p /desktop-state/apt-lists/partial
cat > /desktop-state/desktop.sources <<SOURCES
Types: deb
URIs: $archive
Suites: trixie trixie-updates
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg

Types: deb
URIs: $security
Suites: trixie-security
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
SOURCES
options=(-o Dir::Etc::sourcelist=/desktop-state/desktop.sources -o Dir::Etc::sourceparts=-
  -o Dir::State::lists=/desktop-state/apt-lists -o Acquire::Languages=none
  -o Acquire::PDiffs=false -o Acquire::Retries=2 -o Acquire::https::Timeout=25)
export DEBIAN_FRONTEND=noninteractive
dpkg --configure -a
apt-get "${options[@]}" update
apt-get "${options[@]}" --yes --no-install-recommends install \
  xfce4-session xfwm4 xfce4-panel xfdesktop4 xfce4-settings xfconf \
  thunar xfce4-terminal mousepad dbus-x11 tigervnc-standalone-server tigervnc-tools \
  novnc websockify x11-xserver-utils fonts-dejavu-core fonts-noto-cjk \
  hicolor-icon-theme chromium
for program in Xtigervnc xfce4-session websockify chromium; do command -v "$program" >/dev/null; done
dpkg-query -W '-f=${binary:Package}\t${Version}\n' > /desktop-state/installed-packages.tsv
printf 'Desktop packages installed and verified.\n'
