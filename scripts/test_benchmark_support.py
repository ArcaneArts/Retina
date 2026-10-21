import tempfile
import unittest
import zlib
from pathlib import Path
import sys
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
from benchmark_support import performance_interval, compare_corpora, corpus_coverage, decode_nbt, first_difference, mca_records, percentile, lz4_block_stream, profile_request_context, region_coordinates


def fixture(records):
    header = bytearray(8192)
    body = bytearray()
    sector = 2
    for slot, payload in records.items():
        compressed = zlib.compress(payload)
        record = len(compressed + b"\x02").to_bytes(4, "big") + b"\x02" + compressed
        sectors = (len(record) + 4095) // 4096
        header[slot * 4:slot * 4 + 4] = ((sector << 8) | sectors).to_bytes(4, "big")
        body.extend(record)
        body.extend(bytes(sectors * 4096 - len(record)))
        sector += sectors
    return header + body


class BenchmarkSupportTest(unittest.TestCase):
    def test_performance_interval_counts_repeated_tiles_and_keeps_world_identity(self):
        tile = {"seed": 42, "profile": 1, "origin_x": -19, "origin_z": 13,
                "side": 22, "min_y": 0, "height": 256, "generated_count": 3}
        untouched = {**tile, "origin_x": 100, "generated_count": 1}
        before = {"counters": {"terrain_generated_tiles": 4}, "terrain_tiles": [tile, untouched]}
        after = {"counters": {"terrain_generated_tiles": 9}, "terrain_tiles": [
            {**tile, "generated_count": 5}, untouched,
            {**tile, "profile": 2, "generated_count": 1},
            {**tile, "height": 128, "generated_count": 1},
            {**tile, "side": 38, "generated_count": 1}]}
        result = performance_interval(before, after)
        self.assertEqual(result["counters"]["terrain_generated_tiles"], 5)
        self.assertEqual([r["generated_count"] for r in result["terrain_tiles"]], [2, 1, 1, 1])
        self.assertEqual(sum(r["generated_count"] for r in result["terrain_tiles"]), 5)
        self.assertEqual(tile["generated_count"], 3)
        legacy = performance_interval({"counters": {"n": 1}}, {"counters": {"n": 3}})
        self.assertEqual(legacy["counters"], {"n": 2})
        self.assertNotIn("terrain_tiles", legacy)
        with self.assertRaisesRegex(ValueError, "decreased"):
            performance_interval(after, before)

    def test_request_order_preserves_names_and_negative_coordinates(self):
        forward = region_coordinates(20, -2, -2, 5)
        reverse = region_coordinates(20, -2, -2, 5, "reverse")
        shuffled = region_coordinates(20, -2, -2, 5, "shuffled", 42)
        self.assertEqual(reverse, forward[::-1])
        self.assertEqual(sorted(shuffled), sorted(forward))
        self.assertNotEqual(shuffled, forward)
        self.assertEqual(shuffled, region_coordinates(20, -2, -2, 5, "shuffled", 42))
        self.assertEqual(forward[0], ("0", -2, -2))
        self.assertEqual(forward[-1], ("19", 2, 1))

    def test_coverage_counts_generated_chunks_and_deduplicates_starts(self):
        section = {"biomes": {"palette": ["minecraft:soul_sand_valley"]}}
        start = {"id": "minecraft:nether_fossil", "ChunkX": -12, "ChunkZ": 3}
        chunks = [
            {"": {"xPos": x, "zPos": 0, "sections": [section, section],
                  "structures": {"starts": {"minecraft:nether_fossil": start,
                                            "minecraft:fortress": {"id": "INVALID"}}}}}
            for x in [0, 1]
        ]
        with patch("benchmark_support.mca_records", return_value={0: b"a", 1: b"b"}), \
             patch("benchmark_support.decode_nbt", side_effect=chunks):
            coverage = corpus_coverage(Path("."), ["0"])
        self.assertEqual(coverage, {
            "chunks": 2,
            "biome_chunk_membership": {"minecraft:soul_sand_valley": 2},
            "unique_structure_starts": {"minecraft:nether_fossil": 1},
        })

    def test_requests_preserve_storage_above_the_noise_ceiling(self):
        nether = {
            "generation_bounds": {"storage_min_y": 0, "storage_height": 256, "min_y": 0, "height": 128},
            "biomes": [{"id": "minecraft:warped_forest"}],
        }
        self.assertEqual({"min_y": 0, "height": 256, "fallback_biome": "minecraft:warped_forest"},
                         profile_request_context(nether))
        nether["generation_bounds"].update(storage_min_y=96, storage_height=160)
        self.assertEqual({"min_y": 96, "height": 160, "fallback_biome": "minecraft:warped_forest"},
                         profile_request_context(nether))

    def test_legacy_profile_requests_keep_existing_defaults(self):
        self.assertEqual({"min_y": -64, "height": 384, "fallback_biome": "minecraft:plains"},
                         profile_request_context({}))

    @staticmethod
    def chunk_nbt(y):
        return (b"\x0a\x00\x00\x03\x00\x0bDataVersion\x00\x00\x00\x2a"
                b"\x09\x00\x08sections\x0a\x00\x00\x00\x01\x01\x00\x01Y"
                + bytes([y & 255]) + b"\x08\x00\x04name\x00\x05stone\x00\x00")

    def test_decode_reads_named_compounds_in_stream_order(self):
        data = self.chunk_nbt(-2)
        self.assertEqual({"": {"DataVersion": 42, "sections": [{"Y": -2, "name": "stone"}]}}, decode_nbt(data))
        for broken in [data[:-1], data + b"\x00"]:
            with self.assertRaises(ValueError):
                decode_nbt(broken)

    def test_changed_nbt_reports_the_decoded_field(self):
        with tempfile.TemporaryDirectory() as directory:
            old, new = Path(directory) / "old", Path(directory) / "new"
            old.mkdir(); new.mkdir()
            (old / "0.mca").write_bytes(fixture({0: self.chunk_nbt(-2)}))
            (new / "0.mca").write_bytes(fixture({0: self.chunk_nbt(-1)}))
            with self.assertRaisesRegex(AssertionError, r"sections\[0\]\.Y: -2 != -1"):
                compare_corpora(old, new, ["0"])

    def test_lz4_blocks_and_integrity(self):
        try:
            import lz4.block
            import xxhash
        except ImportError:
            self.skipTest("LZ4 benchmark reader requires lz4 and xxhash")
        import struct
        raw = b"Minecraft chunk data" * 2000
        compressed = lz4.block.compress(raw, store_size=False)
        checksum = xxhash.xxh32(raw, seed=0x9747b28c).intdigest() & 0x0fffffff
        terminal = struct.pack("<8sBIII", b"LZ4Block", 0x16, 0, 0, 0)
        for token, block in [(0x16, raw), (0x26, compressed)]:
            stream = struct.pack("<8sBIII", b"LZ4Block", token, len(block), len(raw), checksum) + block + terminal
            self.assertEqual(raw, lz4_block_stream(stream))
            for invalid in [stream[:-1], stream + b"x", stream[:17] + bytes(4) + stream[21:]]:
                with self.assertRaises(ValueError):
                    lz4_block_stream(invalid)

    def test_percentile_interpolates(self):
        self.assertEqual(25, percentile([10, 20, 30, 40], 50))
        self.assertEqual(38.5, percentile([10, 20, 30, 40], 95))

    def test_mca_records_allows_empty_slots(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "0.mca"
            path.write_bytes(fixture({0: b"first", 17: b"second"}))
            self.assertEqual({0: b"first", 17: b"second"}, mca_records(path))

    def test_difference_reports_nested_path(self):
        self.assertEqual("root.Level.sections[1].Y: 2 != 3", first_difference(
            {"Level": {"sections": [{"Y": 1}, {"Y": 2}]}},
            {"Level": {"sections": [{"Y": 1}, {"Y": 3}]}}))

    def test_compare_rejects_changed_membership(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old, new = root / "old", root / "new"
            old.mkdir(); new.mkdir()
            (old / "0.mca").write_bytes(fixture({0: b"first"}))
            (new / "0.mca").write_bytes(fixture({1: b"first"}))
            with self.assertRaisesRegex(AssertionError, "Changed MCA membership"):
                compare_corpora(old, new, ["0"])


if __name__ == "__main__":
    unittest.main()
