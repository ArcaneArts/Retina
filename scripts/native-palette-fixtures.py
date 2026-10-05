#!/usr/bin/env python3
"""Extract final registered block sections for the native palette A/B benchmark.

Input is an exported registry profile and private MCAs from native-region-benchmark.py.
Only fixtures are written; the source regions are never modified.
"""
import argparse
import json
import struct
import zlib
from pathlib import Path


class Nbt:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def read(self, size):
        result = self.data[self.offset:self.offset + size]
        self.offset += size
        if len(result) != size:
            raise ValueError("truncated NBT")
        return result

    def number(self, fmt):
        return struct.unpack(">" + fmt, self.read(struct.calcsize(fmt)))[0]

    def text(self):
        # Preserve encoded modified-UTF-8 names; material IDs/property values
        # are ASCII. Other text, such as signs, need not be decoded here.
        return self.read(self.number("H"))

    def value(self, kind):
        if 1 <= kind <= 6:
            return self.number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        if kind == 7:
            return self.read(self.number("i"))
        if kind == 8:
            return self.text()
        if kind == 9:
            child, count = self.number("B"), self.number("i")
            return [self.value(child) for _ in range(count)]
        if kind == 10:
            result = {}
            while (child := self.number("B")) != 0:
                name = self.text()
                result[name] = self.value(child)
            return result
        if kind in (11, 12):
            return [self.number("i" if kind == 11 else "q") for _ in range(self.number("i"))]
        raise ValueError(f"unsupported NBT type {kind}")

    def root(self):
        kind = self.number("B")
        self.text()
        return self.value(kind)


def material_key(value):
    if isinstance(value, str):
        return value, ()
    if isinstance(value, bytes):
        return value.decode("ascii"), ()
    if b"id" in value:
        return value[b"id"].decode("ascii"), tuple(sorted(
            (k.decode("ascii"), v.decode("ascii")) for k, v in value.get(b"properties", {}).items()))
    return value["id"], tuple(sorted(value.get("properties", {}).items()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--regions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    profile = json.loads(args.profile.read_bytes())
    materials = {material_key(value): index for index, value in enumerate(profile["materials"])}
    section_count = 0
    with args.output.open("wb") as output:
        for region in sorted(args.regions.glob("[0-9]*.mca")):
            data = region.read_bytes()
            # Four separated final chunks per measured region preserve air,
            # underground palettes, feature crowns and structure intersections.
            for slot in (0, 341, 682, 1023):
                start = (int.from_bytes(data[slot * 4:slot * 4 + 4], "big") >> 8) * 4096
                size = int.from_bytes(data[start:start + 4], "big")
                if not start or data[start + 4] != 2:
                    raise ValueError(f"expected inline zlib chunk: {region}:{slot}")
                chunk = Nbt(zlib.decompress(data[start + 5:start + 4 + size])).root()
                for section in chunk[b"sections"]:
                    states = section[b"block_states"]
                    palette = [materials[material_key(value)] for value in states[b"palette"]]
                    if len(palette) == 1:
                        blocks = [palette[0]] * 4096
                    else:
                        bits = max(4, (len(palette) - 1).bit_length())
                        per_word, mask = 64 // bits, (1 << bits) - 1
                        packed = states[b"data"]
                        blocks = [palette[(packed[i // per_word] >> (i % per_word * bits)) & mask]
                                  for i in range(4096)]
                    output.write(struct.pack("<4096H", *blocks))
                    section_count += 1
    print(f"{section_count} final block sections: {args.output}")


if __name__ == "__main__":
    main()
