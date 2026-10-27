#!/usr/bin/env python3
"""Compare interpreter reuse across saved world-definition variants on one engine.

Create inputs with interpreterReuseDefinitionFixtures. The climate and material
variants must affect generation; returning to the original world must reproduce
its bytes. All generation settings come from each actual retina.dat file.
"""
import argparse
from contextlib import ExitStack
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--definitions", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args()
    os.environ["RETINA_SPECIALIZED_TERRAIN"] = "0"
    args.out.mkdir(parents=True, exist_ok=False)
    manifest = json.loads((args.definitions / "variants.json").read_bytes())
    dimension = manifest["dimension"].encode()
    library = c.CDLL(str(args.library.resolve()))
    library.retina_initialize.restype = c.c_int
    library.retina_open_world_definition.argtypes = [c.c_char_p, c.c_uint64, c.POINTER(c.c_uint64)]
    library.retina_bind_world_dimension_execution.argtypes = [c.c_uint64, c.c_char_p, c.c_uint64,
        c.c_uint32, c.c_uint32, c.POINTER(c.c_uint32)]
    library.retina_world_dimension_request.argtypes = [c.c_uint64, c.c_char_p, c.c_uint64,
        c.c_int32, c.c_int32, c.POINTER(Request)]
    library.retina_close_world_definition.argtypes = [c.c_uint64]
    library.retina_generate_chunk_columns_u16.argtypes = [c.POINTER(Request),
        c.POINTER(c.c_uint16), c.c_uint64, c.POINTER(Column)]
    library.retina_last_error.argtypes = [c.c_void_p, c.c_uint64]
    library.retina_last_error.restype = c.c_uint64

    def check(status):
        if status:
            message = c.create_string_buffer(8192)
            library.retina_last_error(message, len(message))
            raise RuntimeError(message.value.decode())

    check(library.retina_initialize())
    reports = []
    definitions = []
    identical_blocks = identical_columns = 0
    with ExitStack() as owners:
        worlds = {}
        for variant in manifest["variants"]:
            name = variant["name"]
            path = (args.definitions / variant["definition"]).resolve()
            if path not in worlds:
                handle = c.c_uint64()
                encoded = os.fsencode(path)
                check(library.retina_open_world_definition(encoded, len(encoded), c.byref(handle)))
                if not handle.value:
                    raise FileNotFoundError(path)
                owners.callback(lambda value=handle.value: check(library.retina_close_world_definition(value)))
                profile = c.c_uint32()
                check(library.retina_bind_world_dimension_execution(handle.value, dimension,
                    len(dimension), 2, 1, c.byref(profile)))
                worlds[path] = handle.value
                definitions.append(dict(file=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
            handle = worlds[path]
            different = 0
            for x, z in [(-3, -2), (-1, 1)]:
                request = Request()
                check(library.retina_world_dimension_request(handle, dimension, len(dimension), x, z, c.byref(request)))
                blocks = (c.c_uint16*(256*request.height))()
                columns = (Column*256)()
                start = time.perf_counter()
                check(library.retina_generate_chunk_columns_u16(c.byref(request), blocks, len(blocks), columns))
                elapsed = (time.perf_counter()-start)*1000
                result = bytes(blocks)+bytes(columns)
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
                reports.append(dict(name=name, x=x, z=z, profile=request.profile, seed=request.seed,
                    min_y=request.min_y, height=request.height, ms=elapsed,
                    sha256=hashlib.sha256(result).hexdigest()))
            if name in ("climate", "material"):
                assert different, f"{name} fixture did not affect the sampled chunks"
    report = dict(library=str(args.library), library_sha256=hashlib.sha256(args.library.read_bytes()).hexdigest(),
        definitions=definitions, dimension=manifest["dimension"], chunks=reports,
        identical_blocks=identical_blocks, identical_columns=identical_columns)
    (args.out/"measurements.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report))


if __name__ == "__main__":
    main()
