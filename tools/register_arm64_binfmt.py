"""Enable a task-owned ARM64 QEMU rule in the Linux build host (not Android)."""
import pathlib

base = pathlib.Path("/proc/sys/fs/binfmt_misc")
interpreter = pathlib.Path("/usr/bin/qemu-aarch64-static")
assert interpreter.is_file()
rule = base / "opencode-arm64"
if rule.exists():
    assert str(interpreter) in rule.read_text()
else:
    magic = b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 8 + b"\x02\x00\xb7\x00"
    mask = b"\xff" * 7 + b"\x00" * 9 + b"\xfe\xff\xff\xff"
    escaped = lambda data: "".join(f"\\x{value:02x}" for value in data)
    content = f":opencode-arm64:M::{escaped(magic)}:{escaped(mask)}:{interpreter}:F\n"
    (base / "register").write_text(content)
print("ARM64 builder interpreter ready")
