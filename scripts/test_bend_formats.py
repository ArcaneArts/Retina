#!/usr/bin/env python3
"""Independently validate Bend packed longs and streamed Anvil region records."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import zlib

from test_bend_binary import NbtReader
from test_bend_compression import DEFAULT_BEND, ROOT, build, command


def validate_packed(data):
    offset = 0
    entries = 0
    cases = 0
    for bits in range(16, 0, -1):
        for count in (0, 1, 63, 64, 65, 255, 256, 4095, 4096):
            actual_bits, actual_count, size = struct.unpack_from(">III", data, offset)
            offset += 12
            per = 64 // bits
            assert (actual_bits, actual_count, size) == (bits, count, (count + per - 1) // per)
            mask = (1 << bits) - 1
            expected = [0] * size
            for i in range(count):
                value = (((i * 2654435761) & 0xffffffff) ^ (i >> 3)) & mask
                expected[i // per] |= value << ((i % per) * bits)
            actual = list(struct.unpack_from(f">{size}Q", data, offset))
            offset += size * 8
            assert actual == expected, (bits, count)
            cases += 1
            entries += count
    assert offset == len(data)
    return {"cases": cases, "entries_verified": entries, "bits": list(range(1, 17)),
            "sha256": hashlib.sha256(data).hexdigest()}


def read_region(data):
    assert len(data) >= 8192 and len(data) % 4096 == 0
    claimed = {0, 1}
    chunks = {}
    for slot in range(1024):
        location = int.from_bytes(data[slot * 4:slot * 4 + 4], "big")
        timestamp = int.from_bytes(data[4096 + slot * 4:4100 + slot * 4], "big")
        if not location:
            assert timestamp == 0
            continue
        offset, sectors = location >> 8, location & 255
        assert offset >= 2 and sectors > 0 and offset + sectors <= len(data) // 4096
        allocation = set(range(offset, offset + sectors))
        assert not claimed & allocation
        claimed |= allocation
        record = data[offset * 4096:(offset + sectors) * 4096]
        length = int.from_bytes(record[:4], "big")
        assert 1 < length <= sectors * 4096 - 4 and record[4] == 2
        compressed = record[5:4 + length]
        stream = zlib.decompressobj()
        nbt = stream.decompress(compressed) + stream.flush()
        assert stream.eof and not stream.unused_data and not stream.unconsumed_tail
        assert not any(record[4 + length:])
        reader = NbtReader(nbt)
        assert reader.number(">B") == 10 and reader.text() == ""
        tag = reader.tag(10)
        assert reader.offset == len(nbt)
        chunks[slot] = {"tag": tag, "offset": offset, "sectors": sectors, "timestamp": timestamp}
    assert claimed == set(range(len(data) // 4096))
    return chunks


def validate_chunk(slot, chunk):
    assert chunk["tag"] == {"xPos": -64 + (slot & 31), "zPos": 96 + (slot >> 5),
                            "DataVersion": 1, "Status": "minecraft:empty"}
    assert chunk["timestamp"] == 1700000000 + slot


def hash32(x):
    a = ((x ^ (x >> 16)) * 2146121005) & 0xffffffff
    b = ((a ^ (a >> 15)) * 2221713035) & 0xffffffff
    return b ^ (b >> 16)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bend", type=Path, default=DEFAULT_BEND)
    parser.add_argument("--report", type=Path, default=ROOT / "build/bend/format-tests.json")
    args = parser.parse_args()
    binary = ROOT / "build/bend/format-fixtures"
    version = build(args.bend, binary, ROOT / "bend/tests/format-fixtures.bend")
    with tempfile.TemporaryDirectory(prefix="retina-bend-formats-") as temp:
        prefix = Path(temp) / "fixture"
        result = command(["nice", "-n", "10", binary, prefix, "--threads", 2], cwd=ROOT)
        assert "PASS: packed and region fixtures" in result.stdout
        packed = validate_packed(prefix.with_suffix(".packed").read_bytes())
        full_bytes = prefix.with_suffix(".mca").read_bytes()
        full = read_region(full_bytes)
        assert set(full) == set(range(1024))
        inverse = pow(73, -1, 1024)
        for slot, chunk in full.items():
            validate_chunk(slot, chunk)
            assert chunk["sectors"] == 1 and chunk["offset"] == 2 + (slot * inverse) % 1024
        sparse_bytes = Path(str(prefix) + ".sparse.mca").read_bytes()
        sparse = read_region(sparse_bytes)
        assert set(sparse) == {0, 32, 1023}
        for slot in (32, 1023):
            validate_chunk(slot, sparse[slot])
        assert sparse[1023]["offset"] == 2 and sparse[0]["offset"] == 3
        assert sparse[0]["sectors"] == 3 and sparse[0]["timestamp"] == 1234
        assert sparse[0]["tag"] == {"fixture": bytes(hash32(i) & 255 for i in range(12000))}
        assert sparse[32]["offset"] == 6
        empty = Path(str(prefix) + ".empty.mca").read_bytes()
        assert len(empty) == 8192 and not read_region(empty) and not any(empty)
        report = {"bend_version": version, "packed": packed,
                  "mca": {"full_chunks": len(full), "sparse_chunks": len(sparse),
                          "full_bytes": len(full_bytes), "sparse_bytes": len(sparse_bytes),
                          "full_sha256": hashlib.sha256(full_bytes).hexdigest(),
                          "sparse_sha256": hashlib.sha256(sparse_bytes).hexdigest()},
                  "invalid_requests_rejected": 9,
                  "scope": "packed indices and new-file MCA containers; not generated terrain or promotion"}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {packed['cases']} packed cases / {packed['entries_verified']} indices, "
          "1024 full + 3 sparse MCA records, 9 invalid requests")
    print(args.report)


if __name__ == "__main__":
    main()
