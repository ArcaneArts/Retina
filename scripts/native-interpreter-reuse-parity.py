#!/usr/bin/env python3
"""Compare compact interpreter reuse with a retained library across profile switches.

Requires an actual exported Overworld profile. Adds registered interpolation to
climate, one material rule and the aquifer barrier, then returns to the original
profile on the same engine. All output goes to a fresh private directory.
"""
import argparse
import copy
import ctypes as c
import hashlib
import json
import os
from pathlib import Path
import time


class Request(c.Structure):
    _fields_ = [("seed", c.c_uint64), ("x", c.c_int32), ("z", c.c_int32),
                ("min_y", c.c_int32), ("height", c.c_uint32), ("base", c.c_float),
                ("amplitude", c.c_float), ("frequency", c.c_float), ("profile", c.c_uint32)]


class Column(c.Structure):
    _fields_ = [("height", c.c_int32), ("packed", c.c_uint32), ("materials", c.c_uint32)]


def interpolated(profile, program_id, constant=None):
    value = copy.deepcopy(profile)
    registry = value["registry_program"]
    graph = registry["programs"][program_id]
    field = {"input": copy.deepcopy(graph), "cell": [7, 5]}
    field["input"]["roots"] = [graph["roots"][0]]
    if constant is not None:
        # Nonzero fixtures distinguish a real interpolation evaluation from the
        # depth-zero interpreter's unsupported-opcode result, even on flat land.
        field["input"] = dict(nodes=[dict(op=0, a=0, b=0, c=0, p=[constant, 0, 0, 0])], roots=[0])
    field_id = len(registry["interpolations"])
    registry["interpolations"].append(field)
    n = len(graph["nodes"])
    for axis in range(3):
        graph["nodes"].append(dict(op=29, a=axis, b=0, c=0, p=[0, 0, 0, 0]))
    graph["nodes"].append(dict(op=28, a=n, b=n+1, c=n+2, p=[field_id, 0, 0, 0]))
    graph["roots"][0] = n+3
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args()
    os.environ["RETINA_DENSITY_COMPOSITION"] = "0"
    os.environ["RETINA_SPECIALIZED_TERRAIN"] = "0"
    args.out.mkdir(parents=True, exist_ok=False)
    profile = json.loads(args.profile.read_bytes())
    profile["program_execution"] = "interpreter"
    variants = ["original", "climate", "material"]
    aquifer = profile["registry_program"].get("aquifer")
    if aquifer and aquifer["enabled"]:
        variants.append("barrier")
    variants.append("original-again")
    library = c.CDLL(str(args.library.resolve()))
    library.retina_initialize.restype = c.c_int
    library.retina_register_profile.argtypes = [c.c_char_p, c.c_uint64, c.POINTER(c.c_uint32)]
    library.retina_generate_chunk_columns_u16.argtypes = [c.POINTER(Request),
        c.POINTER(c.c_uint16), c.c_uint64, c.POINTER(Column)]
    library.retina_last_error.argtypes = [c.c_void_p, c.c_uint64]

    def check(status):
        if status:
            message = c.create_string_buffer(8192)
            library.retina_last_error(message, len(message))
            raise RuntimeError(message.value.decode())

    check(library.retina_initialize())
    reports = []
    identical_blocks = identical_columns = 0
    original_biomes = set()
    for name in variants:
        value = profile
        if name == "climate":
            value = interpolated(profile, 0, 2.0)
        elif name == "material":
            for biome in sorted(original_biomes):
                value = interpolated(value, 3+biome, profile["stone"]+1)
        elif name == "barrier":
            value = interpolated(profile, aquifer["program"]+3)
        data = json.dumps(value, separators=(",", ":")).encode()
        (args.out/f"{name}.json").write_bytes(data)
        handle = c.c_uint32()
        check(library.retina_register_profile(data, len(data), c.byref(handle)))
        different = 0
        for x, z in [(-3, -2), (-1, 1)]:
            request = Request(123456789, x, z, -64, 384, 64, 48, .008, handle.value)
            blocks = (c.c_uint16*(256*384))()
            columns = (Column*256)()
            start = time.perf_counter()
            check(library.retina_generate_chunk_columns_u16(c.byref(request), blocks, len(blocks), columns))
            elapsed = (time.perf_counter()-start)*1000
            result = bytes(blocks)+bytes(columns)
            if name == "original":
                original_biomes.update(column.packed & 65535 for column in columns)
            filename = f"{name}-{x}-{z}.bin"
            (args.out/filename).write_bytes(result)
            if name == "original-again":
                assert result == (args.out/f"original-{x}-{z}.bin").read_bytes(), filename
            elif name != "original":
                different += result != (args.out/f"original-{x}-{z}.bin").read_bytes()
            if args.compare:
                assert result == (args.compare/filename).read_bytes(), filename
                identical_blocks += len(blocks)
                identical_columns += len(columns)
            reports.append(dict(name=name, x=x, z=z, profile=handle.value, ms=elapsed,
                                sha256=hashlib.sha256(result).hexdigest()))
        if name in ("climate", "material"):
            assert different, f"{name} fixture did not affect the sampled chunks"
    report = dict(library=str(args.library), library_sha256=hashlib.sha256(args.library.read_bytes()).hexdigest(),
        profile=str(args.profile), chunks=reports, identical_blocks=identical_blocks,
        identical_columns=identical_columns)
    (args.out/"measurements.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == "__main__":
    main()
