#!/usr/bin/env python3
"""Validate pure Bend chunk palettes, final heightmaps, metadata and MCA output.

This is serializer integration, not a world generator or performance benchmark.
Minecraft's own decoders are exercised separately by the bendChunkTest task.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

from test_bend_binary import NbtReader
from test_bend_compression import DEFAULT_BEND, ROOT, build, command
from test_bend_formats import read_region

MASKS = (0, 63, 63, 63, 51, 31, 63, 63)
STATES = [
    {"id": "minecraft:air"}, {"id": "minecraft:stone"}, {"id": "minecraft:dirt"},
    {"id": "minecraft:grass_block", "properties": {"snowy": "false"}},
    {"id": "minecraft:water", "properties": {"level": "0"}},
    {"id": "minecraft:oak_leaves", "properties": {
        "distance": "1", "persistent": "false", "waterlogged": "false"}},
    {"id": "minecraft:oak_log", "properties": {"axis": "x"}},
    {"id": "minecraft:chest", "properties": {
        "facing": "east", "type": "single", "waterlogged": "false"}},
]
BIOMES = ("minecraft:plains", "minecraft:forest", "minecraft:lush_caves")
MAP_NAMES = ("WORLD_SURFACE_WG", "WORLD_SURFACE", "OCEAN_FLOOR_WG",
             "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES")


def block(layer, column):
    h = 20 + column % 11
    if layer == 383 and column == 255:
        return 6
    if layer == 30 and column == 209:
        return 7
    if layer == h + 5 and column % 7 == 0:
        return 5
    if layer == h + 3 and column % 13 == 0:
        return 6
    if layer < h - 3:
        return 1
    if layer < h:
        return 2
    if layer == h:
        return 3
    return 4 if layer < 27 else 0


def unpack(words, count, bits):
    per = 64 // bits
    assert len(words) == (count + per - 1) // per
    mask = (1 << bits) - 1
    values = [(words[i // per] >> ((i % per) * bits)) & mask for i in range(count)]
    # Every unused high bit and trailing slot must be zero.
    for i, word in enumerate(words):
        used = min(per, count - i * per) * bits
        assert (word & ((1 << 64) - 1)) >> used == 0
    return values


def palette(container, count, minimum):
    p = container["palette"]
    assert p
    if len(p) == 1:
        assert "data" not in container
        return [p[0]] * count
    bits = max(minimum, (len(p) - 1).bit_length())
    ids = unpack(container["data"], count, bits)
    assert max(ids) < len(p)
    return [p[i] for i in ids]


def validate_palettes(data):
    offset = 0
    total = 0
    cases = 0
    for count, counts in ((4096, (1, 2, 3, 16, 17, 32, 33, 64, 127, 256, 257, 4096)),
                          (64, (1, 2, 3, 16, 17, 32, 33, 64))):
        for distinct in counts:
            header = struct.unpack_from(">IIII", data, offset)
            offset += 16
            assert header == (distinct, count, distinct, (distinct - 1).bit_length())
            globals_ = list(struct.unpack_from(f">{distinct}I", data, offset))
            offset += distinct * 4
            indices = list(struct.unpack_from(f">{count}I", data, offset))
            offset += count * 4
            values = [65535 - ((i * 13) % distinct) * 16 for i in range(count)]
            expected = list(dict.fromkeys(values))
            lookup = {v: i for i, v in enumerate(expected)}
            assert globals_ == expected
            assert indices == [lookup[v] for v in values]
            total += count
            cases += 1
    assert offset == len(data)
    return {"cases": cases, "indices_verified": total}


def read_nbt(data):
    reader = NbtReader(data)
    assert reader.number(">B") == 10 and reader.text() == ""
    tag = reader.tag(10)
    assert reader.offset == len(data)
    return tag


def validate_chunk(tag, version, lit=False):
    assert tag["DataVersion"] == version
    assert (tag["xPos"], tag["zPos"], tag["yPos"]) == (-34 if lit else -33, 63, -4)
    assert tag["Status"] == ("minecraft:light" if lit else "minecraft:features")
    assert tag["isLightOn"] == int(lit)
    assert tag["LastUpdate"] == tag["InhabitedTime"] == 0
    assert tag["block_ticks"] == tag["fluid_ticks"] == tag["PostProcessing"] == []
    sections = tag["sections"]
    assert len(sections) == (26 if lit else 24)
    heights = [[0] * 256 for _ in range(6)]
    for section in sections:
        y = section["Y"]
        if lit:
            assert section["BlockLight"] == bytes(2048)
            assert section["SkyLight"] == b"\xff" * 2048
        else:
            assert "BlockLight" not in section and "SkyLight" not in section
        if y in (-5, 20):
            assert lit and set(section) == {"Y", "BlockLight", "SkyLight"}
            continue
        s = y + 4
        assert 0 <= s < 24
        states = palette(section["block_states"], 4096, 4)
        for i, actual in enumerate(states):
            layer, column = s * 16 + i // 256, i % 256
            expected = 0 if lit else block(layer, column)
            assert actual == STATES[expected], (s, i, actual, STATES[expected])
            for kind in range(6):
                if MASKS[expected] & (1 << kind):
                    heights[kind][column] = layer + 1
        actual_biomes = palette(section["biomes"], 64, 1)
        assert actual_biomes == [BIOMES[0 if lit or s % 3 == 0 else (i + s) % 3] for i in range(64)]
    assert [s["Y"] for s in sections] == list(range(-5 if lit else -4, 21 if lit else 20))
    assert set(tag["Heightmaps"]) == set(MAP_NAMES)
    for kind, name in enumerate(MAP_NAMES):
        assert unpack(tag["Heightmaps"][name], 256, 9) == heights[kind], name
    assert tag["entities"] == [] and tag["structures"]["starts"] == {}
    if lit:
        assert tag["block_entities"] == [] and tag["structures"]["References"] == {}
    else:
        assert tag["structures"]["References"] == {"minecraft:village_plains": [-33]}
        assert tag["block_entities"] == [{"id": "minecraft:chest", "x": -527, "y": -34, "z": 1021,
            "LootTable": "minecraft:chests/village/village_plains_house", "LootTableSeed": -(1 << 63) + 1}]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bend", type=Path, default=DEFAULT_BEND)
    parser.add_argument("--data-version", type=int, default=12345,
                        help="explicit fixture metadata; Minecraft harness supplies actual runtime version")
    parser.add_argument("--report", type=Path, default=ROOT / "build/bend/chunk-tests.json")
    args = parser.parse_args()
    binary = ROOT / "build/bend/chunk-fixtures"
    version = build(args.bend, binary, ROOT / "bend/tests/chunk-fixtures.bend")
    with tempfile.TemporaryDirectory(prefix="retina-bend-chunks-") as folder:
        prefix = Path(folder) / "fixture"
        result = command(["nice", "-n", "10", binary, prefix, args.data_version, "--threads", 2], cwd=ROOT)
        assert "PASS: chunk fixtures" in result.stdout
        raw = prefix.with_suffix(".nbt").read_bytes()
        tag = read_nbt(raw)
        validate_chunk(tag, args.data_version)
        mca_bytes = prefix.with_suffix(".mca").read_bytes()
        mca = read_region(mca_bytes)
        assert set(mca) == {1022, 1023} and mca[1023]["tag"] == tag
        validate_chunk(mca[1022]["tag"], args.data_version, lit=True)
        palettes = validate_palettes(prefix.with_suffix(".palettes").read_bytes())
        for name, y in (("min", -128), ("max", 127)):
            edge = read_nbt(Path(str(prefix) + f".{name}.nbt").read_bytes())
            assert edge["yPos"] == edge["sections"][0]["Y"] == y
        report = {"bend_version": version, "palettes": palettes, "chunks": 2,
                  "blocks_verified": 24 * 4096 * 2, "biomes_verified": 24 * 64 * 2,
                  "heightmap_values_verified": 6 * 256 * 2,
                  "invalid_requests_rejected": 27,
                  "uncompressed_chunk_bytes": len(raw), "mca_bytes": len(mca_bytes),
                  "mca_sha256": hashlib.sha256(mca_bytes).hexdigest(),
                  "scope": "chunk serializer and supplied lighting; not terrain generation or lighting computation"}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {palettes['cases']} local palettes, two complete serialized chunks, six final heightmaps, 27 invalid inputs")
    print(args.report)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stdout, error.stderr, file=sys.stderr)
        raise
