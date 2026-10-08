#!/usr/bin/env python3
"""Check exact Bend word arithmetic and all NBT types with independent readers."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import tempfile

from test_bend_compression import DEFAULT_BEND, ROOT, build, command


class NbtReader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def take(self, count):
        assert count >= 0 and self.offset + count <= len(self.data)
        result = self.data[self.offset:self.offset + count]
        self.offset += count
        return result

    def number(self, format):
        return struct.unpack(format, self.take(struct.calcsize(format)))[0]

    def text(self):
        raw = self.take(self.number(">H"))
        # Java modified UTF-8 encodes NUL as C0 80 and supplementary characters
        # as separately encoded UTF-16 surrogate code units.
        return (raw.replace(b"\xc0\x80", b"\0").decode("utf-8", "surrogatepass")
                .encode("utf-16-be", "surrogatepass").decode("utf-16-be", "surrogatepass"))

    def tag(self, kind):
        if kind in (1, 2, 3, 4):
            return self.number({1: ">b", 2: ">h", 3: ">i", 4: ">q"}[kind])
        if kind in (5, 6):
            return self.take({5: 4, 6: 8}[kind])  # preserve NaN payload bits
        if kind == 7:
            return self.take(self.number(">i"))
        if kind == 8:
            return self.text()
        if kind == 9:
            element, size = self.number(">B"), self.number(">i")
            assert size >= 0 and 0 <= element <= 12
            return [self.tag(element) for _ in range(size)]
        if kind == 10:
            result = {}
            while (element := self.number(">B")):
                name = self.text()
                result[name] = self.tag(element)
            return result
        if kind in (11, 12):
            count = self.number(">i")
            assert count >= 0
            return [self.number({11: ">i", 12: ">q"}[kind]) for _ in range(count)]
        raise AssertionError(f"Unexpected NBT type {kind}")


def validate_nbt(data):
    reader = NbtReader(data)
    assert reader.number(">B") == 10
    assert reader.text() == "root"
    result = reader.tag(10)
    assert reader.offset == len(data)
    expected = {
        "byte": -1, "short": -32768, "int": -1, "long": -(1 << 63),
        "float": struct.pack(">f", 1.5), "double": struct.pack(">d", -123.5),
        "nan_payload": bytes.fromhex("7ff8000012345678"),
        "bytes": bytes(range(256)) * 32, "unicode": "A\0é😀",
        "surrogate": "\ud800",
        "max_ascii": "a" * 65535, "max_supplementary": "😀" * 10922 + "aaa",
        "ints": [-1, -(1 << 31), (1 << 31) - 1, 0],
        "longs": [-(1 << 63), -1, 0, 0], "list": [{"x": 1}, {"x": -1}],
        "empty": [], "compound": {},
    }
    assert result == expected, {key: (result[key], value) for key, value in expected.items()
                                if result[key] != value}
    assert b"\0\x0bA\xc0\x80\xc3\xa9\xed\xa0\xbd\xed\xb8\x80" in data
    return {"tag_types": list(range(1, 13)), "bytes": len(data),
            "modified_utf8": "NUL, accented BMP, supplementary surrogate pair",
            "sha256": hashlib.sha256(data).hexdigest()}


def validate_words(data):
    format = ">QQI7Q"
    width = struct.calcsize(format)
    assert len(data) % width == 0
    mask = (1 << 64) - 1
    shifts = set()
    for offset in range(0, len(data), width):
        a, b, n, *actual = struct.unpack_from(format, data, offset)
        shift = n & 63
        expected = [(a + b) & mask, (a - b) & mask, (a * b) & mask,
                    a ^ b, a & b, (a << shift) & mask, a >> shift]
        assert actual == expected, (offset, hex(a), hex(b), n, actual, expected)
        shifts.add(shift)
    assert shifts == set(range(64))
    return {"rows": len(data) // width, "operations_per_row": 7,
            "shift_counts": sorted(shifts), "sha256": hashlib.sha256(data).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bend", type=Path, default=DEFAULT_BEND)
    parser.add_argument("--report", type=Path, default=ROOT / "build/bend/binary-tests.json")
    args = parser.parse_args()
    binary = ROOT / "build/bend/binary-fixtures"
    version = build(args.bend, binary, ROOT / "bend/tests/binary-fixtures.bend")
    with tempfile.TemporaryDirectory(prefix="retina-bend-binary-") as temp:
        prefix = Path(temp) / "fixture"
        output = command(["nice", "-n", "10", binary, prefix, "--threads", 2], cwd=ROOT)
        assert "PASS: binary fixtures and invalid NBT" in output.stdout
        report = {"nbt": validate_nbt(prefix.with_suffix(".nbt").read_bytes()),
                  "word64": validate_words(prefix.with_suffix(".words").read_bytes()),
                  "invalid_inputs_rejected": 9, "bend_version": version,
                  "scope": "binary/NBT primitives; not generated Minecraft terrain"}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: all NBT tags, modified UTF-8, {report['word64']['rows'] * 7} exact word operations, 9 invalid inputs")
    print(args.report)


if __name__ == "__main__":
    main()
