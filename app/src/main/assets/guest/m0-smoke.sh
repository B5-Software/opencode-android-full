#!/bin/bash
set -euo pipefail
pass() { printf 'PASS %s\n' "$1"; }
test "$(dpkg --print-architecture)" = "$(case "$(uname -m)" in aarch64) echo arm64;; x86_64) echo amd64;; esac)"
grep -q '^VERSION_ID="13"' /etc/os-release
pass 'Debian 13 architecture'
/bin/bash --noprofile --norc -c 'printf "PASS dynamic glibc bash\n"'
/usr/bin/true
getconf GNU_LIBC_VERSION
pass 'dynamic ELF and glibc'
test -d /proc/self/fd
test -r /proc/self/status
pass 'proc and file descriptors'
printf '%s\n' '#!/bin/sh' 'printf "PASS shebang\n"' > /tmp/opencode-shebang
chmod 700 /tmp/opencode-shebang
/tmp/opencode-shebang
rm /tmp/opencode-shebang
/bin/sh -c 'exit 37' & child=$!
status=0; wait "$child" || status=$?
test "$status" = 37
pass 'fork exec wait exit status'
/bin/sleep 120 & child=$!
kill -TERM "$child"
status=0; wait "$child" || status=$?
test "$status" -gt 128
pass 'signal delivery'
printf 'shared-workspace\n' > /workspace/.m0-path-test
test "$(cat /workspace/.m0-path-test)" = shared-workspace
rm /workspace/.m0-path-test
pass 'workspace path mapping'
apt-get --version | head -1
pass 'apt executable (network install is a separate test)'
