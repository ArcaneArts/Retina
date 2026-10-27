"""Compare actual public-API snapshots to independently generated native chunks."""
import argparse
import ctypes as c
import gzip
import io
import json
import struct
from pathlib import Path

from benchmark_support import decode_nbt
from saved_definition import SavedDefinition, check


def compare(probe: Path, definition: Path, library_path: Path, expect_test_edits=False):
    source = io.BytesIO(gzip.decompress(probe.read_bytes()))

    def read(count):
        result = source.read(count)
        if len(result) != count:
            raise ValueError("Truncated Bukkit probe")
        return result

    def integer(): return struct.unpack(">i", read(4))[0]
    def text(): return read(struct.unpack(">H", read(2))[0]).decode("utf-8")

    if text() != "retina.bukkit.probe.v1": raise ValueError("Unsupported Bukkit probe format")
    library = c.CDLL(str(library_path.resolve()))
    library.retina_world_dimension_chunk.argtypes = [c.c_uint64, c.c_char_p, c.c_uint64, c.c_int32, c.c_int32,
                                                    c.POINTER(c.c_void_p), c.POINTER(c.c_uint64)]
    dimensions = {"world": "overworld", "world_nether": "the_nether", "world_the_end": "the_end"}
    edits = {("world", 1, 100, 1): "minecraft:diamond_block", ("world_nether", -1, 130, -1): "minecraft:emerald_block",
             ("world_the_end", 1, 100, 1): "minecraft:gold_block"} if expect_test_edits else {}
    results = []
    for _ in range(integer()):
        name = text(); seed = struct.unpack(">q", read(8))[0]; minimum, maximum = integer(), integer()
        with SavedDefinition(library, definition, "minecraft:" + dimensions[name]) as world:
            assert seed == world.metadata["seed"], (name, seed, world.metadata["seed"])
            for _ in range(integer()):
                x, z = integer(), integer()
                palette, defaults = [], {}
                for _ in range(integer()):
                    state, default = text(), text(); palette.append(state)
                    block_id, properties = parse_state(default); defaults[block_id] = properties
                block_ids = [integer() for _ in range(integer())]
                biome_palette = [text() for _ in range(integer())]
                biome_ids = [integer() for _ in range(integer())]
                pointer, length = c.c_void_p(), c.c_uint64()
                check(library, library.retina_world_dimension_chunk(world.handle, world.key, len(world.key), x, z, c.byref(pointer), c.byref(length)))
                try: native = next(iter(decode_nbt(c.string_at(pointer, length.value)).values()))
                finally: library.retina_free_bytes(pointer, length)
                sections = {s["Y"] * 16: s for s in native["sections"] if "block_states" in s}
                assert len(block_ids) == (maximum - minimum) * 256
                assert len(biome_ids) == (maximum - minimum) * 4
                differences, examples, edited = 0, [], 0
                for i, actual_id in enumerate(block_ids):
                    y, local = divmod(i, 256); y += minimum
                    block_x, block_z = local & 15, local >> 4
                    section = sections[y // 16 * 16]
                    block = unpack(section["block_states"], (y & 15) * 256 + local, 4)
                    if isinstance(block, str): block = {"id": block}
                    identity = block["id"]
                    properties = dict(defaults.get(identity, {})); properties.update(block.get("properties", {}))
                    expected = state_text(identity, properties)
                    edit = edits.get((name, x * 16 + block_x, y, z * 16 + block_z))
                    if edit is not None:
                        expected = edit; edited += 1
                    if palette[actual_id] != expected:
                        differences += 1
                        if len(examples) < 5: examples.append([block_x, y, block_z, expected, palette[actual_id]])
                biome_differences = 0
                for i, actual_id in enumerate(biome_ids):
                    y, local = divmod(i, 16); y = minimum + y * 4
                    section = sections[y // 16 * 16]
                    expected = unpack(section["biomes"], ((y & 15) // 4) * 16 + local, 1)
                    biome_differences += expected != biome_palette[actual_id]
                result = {"world": name, "chunk": [x, z], "blocks": len(block_ids), "quart_biomes": len(biome_ids),
                          "player_edits": edited, "block_differences": differences, "biome_differences": biome_differences,
                          "examples": examples}
                results.append(result)
    if source.read(1): raise ValueError("Trailing Bukkit probe bytes")
    return results


def unpack(container, index, minimum_bits):
    palette = container["palette"]
    if len(palette) == 1: return palette[0]
    bits = max(minimum_bits, (len(palette) - 1).bit_length()); per = 64 // bits
    return palette[(container["data"][index // per] >> (index % per * bits)) & ((1 << bits) - 1)]


def parse_state(text):
    if "[" not in text: return text, {}
    identity, properties = text[:-1].split("[", 1)
    return identity, dict(p.split("=", 1) for p in properties.split(","))


def state_text(identity, properties):
    return identity + ("[" + ",".join(f"{key}={value}" for key, value in sorted(properties.items())) + "]" if properties else "")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe", type=Path, required=True)
    parser.add_argument("--definition", type=Path, required=True)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--expect-test-edits", action="store_true", help="Expect the three documented restart-fixture marker blocks")
    args = parser.parse_args()
    results = compare(args.probe, args.definition, args.library, args.expect_test_edits)
    args.report.write_text(json.dumps(results, indent=2) + "\n")
    assert not any(r["block_differences"] or r["biome_differences"] for r in results), args.report
    print(json.dumps({"status": "pass", "chunks": len(results), "blocks": sum(r["blocks"] for r in results),
                      "quart_biomes": sum(r["quart_biomes"] for r in results), "player_edits": sum(r["player_edits"] for r in results)}))
