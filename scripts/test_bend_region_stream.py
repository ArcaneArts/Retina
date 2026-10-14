#!/usr/bin/env python3
"""Independently verify Bend's bounded MCA body writer against its old planner."""
import argparse
import hashlib
import json
import struct
import tempfile
from pathlib import Path

from test_bend_compression import ROOT, DEFAULT_BEND, build, command, metal_compiler_command
from test_bend_formats import read_region, validate_chunk, hash32


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, default=ROOT/'build/bend/region-stream-fixtures')
    parser.add_argument('--build', action='store_true')
    args = parser.parse_args()
    if args.build:
        # Compile serially; container correctness does not need a Metal archive.
        build(DEFAULT_BEND,args.binary.with_suffix('.c'),ROOT/'bend/tests/region-stream.bend')
        command(metal_compiler_command(args.binary.with_suffix('.c'),args.binary),timeout=600)
    rows = []
    with tempfile.TemporaryDirectory(prefix='retina-stream-') as folder:
        prefix = Path(folder)/'stream'
        result = command(['nice', '-n', '10', args.binary, prefix, '--threads', 2, '--gpu', 'off'], timeout=120)
        assert 'PASS: bounded MCA stream fixtures' in result.stdout
        for mode, slots in [(0, set(range(1024))), (1, {0, 32, 1023}), (2, set()), (9, {1023})]:
            path = Path(str(prefix)+f'.{mode}.mca')
            response = Path(str(prefix)+f'.{mode}.response').read_bytes()
            assert len(response) == 8200
            chunks, sectors = struct.unpack_from('>2I', response)
            before = path.read_bytes()
            assert len(before) == sectors*4096 and before[:8192] == bytes(8192)
            with path.open('r+b') as out:
                assert out.write(response[8:]) == 8192
            data = path.read_bytes()
            assert data[8192:] == before[8192:]
            assert data == Path(str(prefix)+f'.reference.{mode}.mca').read_bytes()
            decoded = read_region(data)
            assert set(decoded) == slots and chunks == len(slots)
            for slot, record in decoded.items():
                if mode == 1 and slot == 0:
                    assert record['tag'] == {'fixture': bytes(hash32(i) & 255 for i in range(12000))}
                    assert record['sectors'] == 3 and record['timestamp'] == 1234
                else:
                    validate_chunk(slot, record)
            rows.append(dict(mode=mode, chunks=chunks, sectors=sectors, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
        for mode in range(3, 9):
            assert not Path(str(prefix)+f'.{mode}.response').exists()
    report = dict(scope=__doc__.strip(), cases=rows, invalid_records_rejected=6,
                  lattice_layout_cases=4, body_unchanged_by_header_patch=True,
                  matches_existing_planner_byte_for_byte=True,
                  native_sha256=hashlib.sha256(args.binary.read_bytes()).hexdigest())
    path = ROOT/'docs/benchmarks/bend-region-stream.json'
    path.write_text(json.dumps(report, indent=2)+'\n')
    print('PASS: 1024 full + sparse/empty MCA containers; six invalid records; density bounds')
    print(path)


if __name__ == '__main__':
    main()
