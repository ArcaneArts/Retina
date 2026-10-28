#!/usr/bin/env python3
"""Measure real catalog publication/reopening without initializing Minecraft/GPU."""
import argparse
import ctypes as c
import hashlib
import json
import platform
import resource
import sys
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--catalog', type=Path, required=True)
    parser.add_argument('--variant', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--warmups', type=int, default=2)
    parser.add_argument('--count', type=int, default=10)
    parser.add_argument('--compare', type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    library = c.CDLL(str(args.library.resolve()))
    library.retina_materialize_world_definition.argtypes = [c.c_char_p, c.c_uint64, c.c_char_p, c.c_uint64, c.c_int64, c.c_char_p, c.c_uint64]
    library.retina_materialize_world_definition.restype = c.c_int
    library.retina_open_world_definition.argtypes = [c.c_char_p, c.c_uint64, c.POINTER(c.c_uint64)]
    library.retina_open_world_definition.restype = c.c_int
    library.retina_close_world_definition.argtypes = [c.c_uint64]
    library.retina_close_world_definition.restype = c.c_int
    library.retina_last_error.argtypes = [c.c_void_p, c.c_uint64]

    def check(code):
        if code:
            error = c.create_string_buffer(8192)
            library.retina_last_error(error, len(error))
            raise RuntimeError(error.value.decode(errors='replace'))

    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    source = str(args.catalog.resolve()).encode()
    variant = args.variant.encode()
    samples = []
    seeds = [0, -1, -(1 << 63), (1 << 63) - 1, 123456789]
    for index in range(args.warmups + args.count):
        root = args.out / str(index)
        destination = str(root.resolve()).encode()
        path = str((root / 'retina.dat').resolve()).encode()
        handle = c.c_uint64()
        before = resource.getrusage(resource.RUSAGE_SELF)
        start = time.perf_counter()
        check(library.retina_materialize_world_definition(source, len(source), variant, len(variant), seeds[index % len(seeds)], destination, len(destination)))
        published = time.perf_counter()
        try:
            check(library.retina_open_world_definition(path, len(path), c.byref(handle)))
            if not handle.value:
                raise AssertionError('Published definition was absent when reopened')
            reopened = time.perf_counter()
        finally:
            if handle.value:
                check(library.retina_close_world_definition(handle))
        closed = time.perf_counter()
        after = resource.getrusage(resource.RUSAGE_SELF)
        peak = after.ru_maxrss * (1 if sys.platform == 'darwin' else 1024)
        artifact = root / 'retina.dat'
        actual_hash = digest(artifact)
        if args.compare:
            expected = args.compare / str(index) / 'retina.dat'
            if artifact.read_bytes() != expected.read_bytes():
                raise AssertionError(f'Changed complete world definition at sample {index}')
        if index >= args.warmups:
            samples.append({'seed': seeds[index % len(seeds)], 'publish_ms': (published-start)*1000,
                            'reopen_ms': (reopened-published)*1000, 'close_ms': (closed-reopened)*1000,
                            'total_ms': (closed-start)*1000,
                            'cpu_ms': ((after.ru_utime+after.ru_stime)-(before.ru_utime+before.ru_stime))*1000,
                            'peak_rss_bytes': peak, 'definition_sha256': actual_hash})
    result = {'schema': 1, 'command': sys.argv, 'host': platform.platform(),
              'machine': platform.machine(), 'python': platform.python_version(),
              'library': str(args.library.resolve()), 'library_sha256': digest(args.library),
              'catalog': str(args.catalog.resolve()), 'catalog_sha256': digest(args.catalog),
              'variant': args.variant, 'warmups': args.warmups, 'samples': samples,
              'scope': 'Production FFI publish (including validation/fsync), reopen and close. No GPU/Minecraft. RSS includes Python and native process high water; hashes/comparison occur outside timing.',
              'compared_definitions': args.warmups + args.count if args.compare else 0}
    (args.out / 'measurements.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'variant': args.variant, 'samples': len(samples), 'compared': result['compared_definitions']}))


if __name__ == '__main__':
    main()
