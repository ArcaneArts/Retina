#!/usr/bin/env python3
"""Compile pure Bend compression, then check streams with independent decoders.

The file driver includes process startup, file IO and Bend byte-list conversion;
its wall times are deliberately not presented as codec or generator benchmarks.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import tempfile
import time
import zlib

ROOT = Path(__file__).resolve().parents[1]
VERSION = "2.0.36"
DEFAULT_BEND = Path.home() / f".local/share/retina-bend/{VERSION}/bin/bend"


def command(args, timeout=120, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, capture_output=True,
                          text=True, timeout=timeout, **kwargs)


def build(bend, binary, source=None):
    env = dict(os.environ, BEND_NO_TELEMETRY="1")
    xcode = Path("/Applications/Xcode.app/Contents/Developer")
    if xcode.exists():
        env["CC"] = str(xcode / "Toolchains/XcodeDefault.xctoolchain/usr/bin/clang")
        env["SDKROOT"] = str(xcode / "Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk")
    # Compile before running any checks. One native compiler at reduced priority.
    # The persistent worker is much larger than the compression fixture, and a
    # concurrent limited Cargo build can extend its single-compiler wall time.
    binary.parent.mkdir(parents=True, exist_ok=True)
    version = command([bend, "version"], env=env).stdout.strip()
    command(["nice", "-n", "10", bend, source or ROOT / "bend/tests/compress-file.bend",
             "-o", binary], env=env, cwd=ROOT, timeout=600)
    return version


def verify_stream(encoded, expected):
    stream = zlib.decompressobj()
    actual = stream.decompress(encoded) + stream.flush()
    assert stream.eof and not stream.unused_data and not stream.unconsumed_tail
    assert actual == expected
    assert int.from_bytes(encoded[-4:], "big") == zlib.adler32(expected)
    assert encoded[0] == 0x78 and int.from_bytes(encoded[:2], "big") % 31 == 0
    assert not encoded[1] & 32  # no external dictionary


def run_case(binary, folder, data, extra=(), threads=2):
    source, destination = folder / "input", folder / "output"
    source.write_bytes(data)
    started = time.perf_counter()
    command(["nice", "-n", "10", binary, source, destination, *extra,
             "--threads", threads], cwd=ROOT)
    if extra == ("parallel",):
        outputs = [destination.with_name(destination.name + suffix).read_bytes()
                   for suffix in (".a.a", ".a.b", ".b.a", ".b.b")]
        for encoded in outputs:
            verify_stream(encoded, data)
        assert len(set(outputs)) == 1
        return outputs[0], (time.perf_counter() - started) * 1000
    encoded = destination.read_bytes()
    verify_stream(encoded, data)
    return encoded, (time.perf_counter() - started) * 1000


def test(binary, folder):
    rng = random.Random(20261008)
    cases = [
        ("empty", b""), ("one_zero", b"\0"), ("one_ff", b"\xff"),
        ("all_literals", bytes(range(256))),
        ("overlapping_distance_one", b"a" * 10000),
        ("overlapping_distance_three", b"abc" * 10000),
        ("long_period", bytes(range(256)) * 4096),
        ("nbt_like", (b"\x0a\0\0\x03\0\x0bDataVersion\0\0\x13\xff"
                      b"\x08\0\x04Name\0\x0fminecraft:stone\0" * 8192)),
    ]
    for size in (2, 3, 257, 258, 259, 32767, 32768, 32769,
                 65534, 65535, 65536, 131070, 131071, 1048576):
        cases.append((f"random_{size}", rng.randbytes(size)))
    for i in range(24):
        motif = rng.randbytes(rng.randrange(1, 301))
        data = bytearray(motif * rng.randrange(1, 120))
        for _ in range(len(data) // 20):
            data[rng.randrange(len(data))] = rng.randrange(256)
        cases.append((f"mixed_{i}", bytes(data)))

    rows = []
    for name, data in cases:
        encoded, wall = run_case(binary, folder, data)
        assert len(encoded) <= len(data) + 6 + 5 * max(1, (len(data) + 65534) // 65535)
        if name in ("overlapping_distance_one", "long_period", "nbt_like"):
            assert len(encoded) < len(data) // 10
            assert (encoded[2] >> 1) & 3 == 1  # real fixed Huffman, not stored
        if name == "random_1048576":
            assert (encoded[2] >> 1) & 3 == 0  # bounded-size stored fallback
        rows.append({"name": name, "input_bytes": len(data), "zlib_bytes": len(encoded),
                     "driver_wall_ms": wall, "sha256": hashlib.sha256(encoded).hexdigest()})

    # Test the encoder directly, independent of the heuristic match finder.
    # Every legal length plus both ends of every distance-code range.
    references = [(length, 1) for length in range(3, 259)]
    bases = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129,
             193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097,
             6145, 8193, 12289, 16385, 24577, 32769]
    distances = sorted(set(bases[:-1] + [value - 1 for value in bases[1:]]))
    references += [(258, distance) for distance in distances]
    for length, distance in references:
        data = bytearray(rng.randbytes(distance))
        for _ in range(length):
            data.append(data[-distance])
        run_case(binary, folder, bytes(data), (length, distance))

    # Decode every leaf, compare independent streams at 1/2/4 worker settings.
    parallel = (bytes(range(256)) + b"minecraft:deepslate\0" * 64) * 32
    results = [run_case(binary, folder, parallel, ("parallel",), workers)[0]
               for workers in (1, 2, 4)]
    assert len(set(results)) == 1

    # Invalid calls must fail rather than wrap array indices or emit a stream.
    source, destination = folder / "input", folder / "invalid-output"
    source.write_bytes(b"abc")
    invalid = [(2, 1), (259, 1), (3, 0), (3, 32769), (3, 32768), ("unknown",)]
    for extra in invalid:
        result = subprocess.run([str(x) for x in
                                 [binary, source, destination, *extra, "--threads", 2]],
                                capture_output=True, timeout=10)
        assert result.returncode != 0 and not destination.exists()
    with source.open("wb") as file:
        file.truncate(16777217)
    result = subprocess.run([str(x) for x in [binary, source, destination, "--threads", 2]],
                            capture_output=True, timeout=10)
    assert result.returncode != 0 and not destination.exists()
    return {"payload_cases": rows, "backreference_cases": len(references),
            "parallel_streams_verified": 12,
            "invalid_requests_rejected": len(invalid) + 1,
            "decoder": f"Python zlib {zlib.ZLIB_RUNTIME_VERSION}"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bend", type=Path, default=DEFAULT_BEND)
    parser.add_argument("--binary", type=Path, default=ROOT / "build/bend/compress-file")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--report", type=Path, default=ROOT / "build/bend/compression-tests.json")
    args = parser.parse_args()
    version = "existing executable" if args.skip_build else build(args.bend, args.binary)
    with tempfile.TemporaryDirectory(prefix="retina-bend-zlib-") as temp:
        report = test(args.binary.resolve(), Path(temp))
    report.update(bend_version=version,
                  source_sha256=hashlib.sha256((ROOT / "bend/compression.bend").read_bytes()).hexdigest(),
                  scope="compression correctness; not full region performance")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {len(report['payload_cases'])} payloads, "
          f"{report['backreference_cases']} length/distance cases, 12 parallel streams")
    print(args.report)


if __name__ == "__main__":
    main()
