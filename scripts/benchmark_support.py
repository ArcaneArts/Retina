"""Shared correctness and statistics helpers for Retina benchmark scripts."""
from __future__ import annotations

import hashlib
import json
import random
import struct
import zlib
from pathlib import Path
from typing import Any, Iterable

RESULT_SCHEMA = "retina.native-region-benchmark"
RESULT_SCHEMA_VERSION = 1


def performance_interval(before: dict[str, Any], after: dict[str, Any]) -> dict[str, Any]:
    """Subtract warmup work without conflating repeated tiles or world identities."""
    result = {**after, "counters": {
        key: value - before["counters"][key] for key, value in after["counters"].items()
    }, "note": "Measured-interval deltas. Unique keys are first seen during this interval, excluding warmup keys. Recursive/concurrent durations overlap."}
    if "terrain_tiles" in after:
        def identity(row):
            return json.dumps({k: v for k, v in row.items() if k != "generated_count"}, sort_keys=True)
        previous = {identity(row): row["generated_count"] for row in before.get("terrain_tiles", [])}
        tiles = []
        for row in after["terrain_tiles"]:
            count = row["generated_count"] - previous.get(identity(row), 0)
            if count < 0:
                raise ValueError("Terrain generation counter decreased between snapshots")
            if count:
                tiles.append({**row, "generated_count": count})
        result["terrain_tiles"] = tiles
        result["terrain_tile_scope"] = "Measured-interval generation counts, including repeated tiles and all GPU guards; coordinates and side are chunks"
    return result


def region_coordinates(count: int, origin_x: int, origin_z: int, grid_width: int,
                       order: str = "forward", seed: int = 0) -> list[tuple[str, int, int]]:
    """Stable output names let different request orders compare the same chunks."""
    coordinates = [(str(i), origin_x + i % grid_width, origin_z + i // grid_width)
                   for i in range(count)]
    if order == "reverse":
        coordinates.reverse()
    elif order == "shuffled":
        random.Random(seed).shuffle(coordinates)
    elif order != "forward":
        raise ValueError(f"unknown request order: {order}")
    return coordinates


def profile_request_context(profile: dict[str, Any]) -> dict[str, Any]:
    """Use dimension storage for MCA requests; noise bounds only constrain terrain."""
    bounds = profile.get("generation_bounds", {})
    biomes = profile.get("biomes", [])
    return {
        "min_y": bounds.get("storage_min_y", -64),
        "height": bounds.get("storage_height", 384),
        "fallback_biome": biomes[0].get("id", "minecraft:plains") if biomes else "minecraft:plains",
    }


def percentile(values: Iterable[float], percent: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("percentile needs at least one value")
    position = (len(ordered) - 1) * percent / 100.0
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def mca_records(path: Path) -> dict[int, bytes]:
    data = path.read_bytes()
    if len(data) < 8192:
        raise ValueError(f"Truncated MCA header: {path}")
    records: dict[int, bytes] = {}
    for slot in range(1024):
        location = int.from_bytes(data[slot * 4:slot * 4 + 4], "big")
        if location == 0:
            continue
        offset = (location >> 8) * 4096
        sectors = location & 0xff
        if offset < 8192 or offset + 5 > len(data):
            raise ValueError(f"Invalid MCA location: {path}:{slot}")
        length = int.from_bytes(data[offset:offset + 4], "big")
        if length < 2 or length > sectors * 4096 - 4 or offset + 4 + length > len(data):
            raise ValueError(f"Invalid MCA record length: {path}:{slot}")
        compression = data[offset + 4]
        payload = data[offset + 5:offset + 4 + length]
        if compression == 2:
            records[slot] = zlib.decompress(payload)
        elif compression == 4:
            records[slot] = lz4_block_stream(payload)
        else:
            raise ValueError(f"Unsupported MCA compression {compression}: {path}:{slot}")
    return records


def lz4_block_stream(payload: bytes) -> bytes:
    """Independent benchmark reader; optional dependencies only for type-4 files."""
    import lz4.block
    import xxhash
    output = bytearray()
    cursor = 0
    while cursor + 21 <= len(payload):
        magic, token, packed, raw, checksum = struct.unpack_from("<8sBIII", payload, cursor)
        cursor += 21
        method, limit = token & 0xf0, 1 << ((token & 15) + 10)
        if magic != b"LZ4Block" or method not in (0x10, 0x20) or raw > limit:
            raise ValueError("Invalid LZ4Block header")
        if packed == 0 or raw == 0:
            if packed != 0 or raw != 0 or checksum != 0 or cursor != len(payload):
                raise ValueError("Invalid LZ4Block terminal header")
            return bytes(output)
        if cursor + packed > len(payload) or (method == 0x10 and packed != raw):
            raise ValueError("Invalid LZ4Block payload size")
        block = payload[cursor:cursor + packed]
        if method == 0x20:
            block = lz4.block.decompress(block, uncompressed_size=raw)
        if len(block) != raw or xxhash.xxh32(block, seed=0x9747b28c).intdigest() & 0x0fffffff != checksum:
            raise ValueError("Invalid LZ4Block checksum or decoded size")
        output.extend(block)
        cursor += packed
    raise ValueError("Truncated LZ4Block stream")


def corpus_manifest(directory: Path, names: Iterable[str]) -> dict[str, Any]:
    digest = hashlib.sha256()
    files: list[dict[str, Any]] = []
    for name in sorted(names):
        file_digest = hashlib.sha256()
        count = 0
        for slot, payload in sorted(mca_records(directory / f"{name}.mca").items()):
            chunk_hash = hashlib.sha256(payload).hexdigest()
            key = f"{name}.mca:{slot}"
            digest.update(key.encode())
            digest.update(b"\0")
            digest.update(bytes.fromhex(chunk_hash))
            file_digest.update(slot.to_bytes(2, "big"))
            file_digest.update(bytes.fromhex(chunk_hash))
            count += 1
        files.append({"file": f"{name}.mca", "records": count, "sha256": file_digest.hexdigest()})
    return {"algorithm": "sha256", "records": sum(item["records"] for item in files),
            "sha256": digest.hexdigest(), "files": files}


class _NbtReader:
    def __init__(self, data: bytes):
        self.data = data
        self.offset = 0

    def take(self, size: int) -> bytes:
        end = self.offset + size
        if end > len(self.data):
            raise ValueError("truncated NBT")
        value = self.data[self.offset:end]
        self.offset = end
        return value

    def unpack(self, fmt: str):
        return struct.unpack(">" + fmt, self.take(struct.calcsize(">" + fmt)))[0]

    def string(self) -> str:
        return self.take(self.unpack("H")).decode("utf-8", errors="replace")

    def payload(self, tag: int):
        if tag == 1: return self.unpack("b")
        if tag == 2: return self.unpack("h")
        if tag == 3: return self.unpack("i")
        if tag == 4: return self.unpack("q")
        if tag == 5: return self.unpack("f")
        if tag == 6: return self.unpack("d")
        if tag == 7: return self.take(self.unpack("i"))
        if tag == 8: return self.string()
        if tag == 9:
            child = self.unpack("B")
            return [self.payload(child) for _ in range(self.unpack("i"))]
        if tag == 10:
            result = {}
            while True:
                child = self.unpack("B")
                if child == 0: return result
                # Assignment evaluates its RHS first; read the name before the
                # payload so compound fields consume the NBT stream in order.
                name = self.string()
                result[name] = self.payload(child)
        if tag == 11: return [self.unpack("i") for _ in range(self.unpack("i"))]
        if tag == 12: return [self.unpack("q") for _ in range(self.unpack("i"))]
        raise ValueError(f"unsupported NBT tag {tag}")


def decode_nbt(data: bytes):
    reader = _NbtReader(data)
    tag = reader.unpack("B")
    if tag == 0:
        raise ValueError("NBT root cannot be TAG_End")
    name = reader.string()
    value = reader.payload(tag)
    if reader.offset != len(data):
        raise ValueError(f"trailing NBT bytes: {len(data) - reader.offset}")
    return {name: value}


def first_difference(old: Any, new: Any, path: str = "root") -> str | None:
    if type(old) is not type(new):
        return f"{path}: type {type(old).__name__} != {type(new).__name__}"
    if isinstance(old, dict):
        old_keys, new_keys = set(old), set(new)
        if old_keys != new_keys:
            return f"{path}: keys missing={sorted(old_keys-new_keys)!r} extra={sorted(new_keys-old_keys)!r}"
        for key in old:
            difference = first_difference(old[key], new[key], f"{path}.{key}")
            if difference: return difference
        return None
    if isinstance(old, (list, tuple, bytes, bytearray)):
        if len(old) != len(new):
            return f"{path}: length {len(old)} != {len(new)}"
        for index, (left, right) in enumerate(zip(old, new)):
            if left != right:
                difference = first_difference(left, right, f"{path}[{index}]")
                return difference or f"{path}[{index}]: {left!r} != {right!r}"
        return None
    if old != new:
        return f"{path}: {old!r} != {new!r}"
    return None


def compare_corpora(baseline: Path, candidate: Path, names: Iterable[str]) -> dict[str, Any]:
    identical = 0
    for name in sorted(names):
        old = mca_records(baseline / f"{name}.mca")
        new = mca_records(candidate / f"{name}.mca")
        if old.keys() != new.keys():
            raise AssertionError(
                f"Changed MCA membership: {name}.mca missing={sorted(old.keys()-new.keys())} "
                f"extra={sorted(new.keys()-old.keys())}"
            )
        for slot in old:
            if old[slot] != new[slot]:
                try:
                    detail = first_difference(decode_nbt(old[slot]), decode_nbt(new[slot]))
                except (ValueError, struct.error) as error:
                    detail = f"NBT decode failed while diagnosing mismatch: {error}"
                raise AssertionError(f"Changed NBT: {name}.mca slot {slot}: {detail}")
            identical += 1
    return {"identical_nbt_chunks": identical, "membership_identical": True}


def corpus_coverage(directory: Path, names: Iterable[str]) -> dict[str, Any]:
    """Inspect generated NBT, not profile declarations; outside timed intervals.

    Biome counts count chunk membership (including section palettes), not voxels.
    Structure counts deduplicate start coordinates across region references.
    """
    biomes: dict[str, int] = {}
    starts: dict[str, set[tuple[int, int]]] = {}
    chunks = 0
    for name in names:
        for payload in mca_records(directory / f"{name}.mca").values():
            chunk = next(iter(decode_nbt(payload).values()))
            chunks += 1
            present = {biome for section in chunk.get("sections", [])
                       for biome in section.get("biomes", {}).get("palette", [])}
            for biome in present:
                biomes[biome] = biomes.get(biome, 0) + 1
            for structure, start in chunk.get("structures", {}).get("starts", {}).items():
                if start.get("id") != "INVALID":
                    starts.setdefault(structure, set()).add((
                        start.get("ChunkX", chunk["xPos"]), start.get("ChunkZ", chunk["zPos"])))
    return {"chunks": chunks, "biome_chunk_membership": dict(sorted(biomes.items())),
            "unique_structure_starts": {k: len(v) for k, v in sorted(starts.items())}}


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
