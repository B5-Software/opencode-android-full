"""Fail closed on non-64-bit, non-16KB-aligned or writable/executable PT_LOAD segments."""
import pathlib
import struct
import sys


def check(path):
    data = pathlib.Path(path).read_bytes()
    check_data(data, str(path))


def check_data(data, path):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{path}: expected little-endian ELF64")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    loads = 0
    for i in range(count):
        p_type, flags, pos, vaddr, _, _, _, align = struct.unpack_from("<IIQQQQQQ", data, offset + size * i)
        if p_type != 1:
            continue
        loads += 1
        if align < 16384 or (vaddr - pos) % 16384:
            raise ValueError(f"{path}: PT_LOAD alignment {align} is incompatible with 16KB pages")
        if flags & 3 == 3:
            raise ValueError(f"{path}: writable and executable PT_LOAD")
    if not loads:
        raise ValueError(f"{path}: no PT_LOAD")
    print(f"PASS {path}: {loads} load segments, 16KB alignment")


if __name__ == "__main__":
    for name in sys.argv[1:]:
        check(name)
