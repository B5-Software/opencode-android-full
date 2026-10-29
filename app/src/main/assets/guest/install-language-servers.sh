#!/bin/bash
set -euo pipefail
case "${1:-ustc}" in
  official) archive=https://deb.debian.org/debian; security=https://security.debian.org/debian-security ;;
  ustc) archive=https://mirrors.ustc.edu.cn/debian; security=https://mirrors.ustc.edu.cn/debian-security ;;
  *) echo 'Unsupported repository'; exit 2 ;;
esac
mkdir -p /android-runtime/lsp-apt/lists/partial
cat > /android-runtime/lsp-apt/language-servers.sources <<SOURCES
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
options=(-o Dir::Etc::sourcelist=/android-runtime/lsp-apt/language-servers.sources -o Dir::Etc::sourceparts=-
  -o Dir::State::lists=/android-runtime/lsp-apt/lists -o Acquire::Languages=none
  -o Acquire::PDiffs=false -o Acquire::Retries=2 -o Acquire::https::Timeout=25)
export DEBIAN_FRONTEND=noninteractive
dpkg --configure -a
apt-get "${options[@]}" update
apt-get "${options[@]}" --yes --no-install-recommends install clangd python3-pylsp python3-pyflakes python3-pycodestyle
clangd --version
pylsp --version
dpkg-query -W '-f=${binary:Package}\t${Version}\n' clangd clangd-19 python3-pylsp python3-pyflakes python3-pycodestyle > /android-runtime/lsp-apt/installed.tsv
printf 'C/C++ and Python language servers installed and verified.\n'
