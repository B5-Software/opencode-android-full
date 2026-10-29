#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get -o APT::Sandbox::User=root -o Acquire::Retries=1 -o Acquire::http::Timeout=30 update
apt-get -o APT::Sandbox::User=root -o Acquire::Retries=1 -o Acquire::http::Timeout=30 install -y --no-install-recommends gcc libc6-dev make python3 ca-certificates
mkdir -p /workspace/m0-acceptance
cd /workspace/m0-acceptance
cat > answer.h <<'EOF'
int answer(void);
EOF
cat > answer.c <<'EOF'
#include "answer.h"
int answer(void) { return 42; }
EOF
cat > main.c <<'EOF'
#include "answer.h"
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>
int main(void) {
    pid_t child = fork();
    if (child == 0) { execl("/bin/true", "true", (char *)0); _exit(127); }
    int status = 0;
    if (child < 0 || waitpid(child, &status, 0) != child || !WIFEXITED(status) || WEXITSTATUS(status)) return 1;
    if (answer() != 42) return 2;
    puts("PASS compiled ELF fork exec waitpid");
    return 0;
}
EOF
cat > Makefile <<'EOF'
all: dynamic static
dynamic: main.c answer.c answer.h
	$(CC) -Wall -Werror main.c answer.c -o dynamic
static: main.c answer.c answer.h
	$(CC) -static -Wall -Werror main.c answer.c -o static
EOF
make -j2
./dynamic
./static
python3 - <<'PY'
import os, pty, signal, subprocess, termios, fcntl, struct
master, slave = pty.openpty()
fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 32, 100, 0, 0))
child = subprocess.Popen(['/bin/bash', '-c', 'test -t 0 && test -t 1 && stty size'], stdin=slave, stdout=slave, stderr=slave)
os.close(slave)
data = os.read(master, 4096)
assert child.wait(timeout=15) == 0
assert b'32 100' in data, data
os.close(master)
child = subprocess.Popen(['/bin/sleep', '120'])
child.send_signal(signal.SIGTERM)
assert child.wait(timeout=15) == -signal.SIGTERM
print('PASS guest PTY resize and subprocess signals')
PY
printf 'PASS apt install and multi-file dynamic/static build\n'
