#!/usr/bin/env python3
"""Compare pinned pure Bend packages on actual generated chunk NBT.

The compiler and file input/output are outside internal warm intervals. Each
sample performs 16 serial operations, including fresh input cloning and full
output checksum consumption. Compression includes all representation adapters.
Byte replay is a buffer microbenchmark, not complete NBT/tag/palette encoding.
CPU only, two threads, nice +10. No game, native codec or GPU claim.
"""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import random
import re
import statistics
import subprocess
import time
import zlib

from test_bend_binary import NbtReader
from test_bend_compression import DEFAULT_BEND, ROOT, build, verify_stream

PACKAGES = {
    'bytes': dict(version='0.3.2.0', content='0x185ae03c75e3e75be1171471f68b43cb',
                  sha256='07a046795be85c891390055013bca17bb6e4e88a91e836eec0df4a9e1dc5cabe'),
    'zlib': dict(version='0.2.0.1', content='0xcc180113489c489d3f5cdc852e6806f9',
                 sha256='4ee0558b7f987c788c7c78f8697b060ff55396f98b4a7c81f7c005278bfd045a'),
}
MODES = ('local_zlib', 'package_zlib', 'local_writer', 'packed_writer', 'packed_adapted',
         'local_presized', 'packed_growing', 'packed_growing_adapted')
BATCH = 16


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inputs(manifest):
    rows = []
    for record in json.loads(manifest.read_text())['chunks']:
        data = zlib.decompress(base64.b64decode(record['fixture_zlib_base64']))
        assert len(data) == record['bytes'] and digest(data) == record['sha256']
        reader = NbtReader(data)
        assert reader.number('>B') == 10
        reader.text()
        nbt = reader.tag(10)
        assert reader.offset == len(data) and nbt['DataVersion'] == 5023
        rows.append((record['name'], data, dict(data_version=nbt['DataVersion'],
                    sections=len(nbt['sections']), source=record['source'])))
    return rows


def run(binary, folder, name, data, mode, samples):
    source, output = folder/(name+'.input'), folder/(name+'.'+MODES[mode]+'.output')
    source.write_bytes(data)
    # A separate process per lane keeps the high-water memory figures meaningful.
    cmd = ['nice', '-n', '10', str(binary), str(source), str(output), str(mode),
           str(samples), '--threads', '2', '--gpu', 'off']
    timed = ['/usr/bin/time', '-l', *cmd] if platform.system() == 'Darwin' else cmd
    started = time.perf_counter()
    result = subprocess.run(timed, check=True, capture_output=True, text=True,
                            timeout=180, env=dict(os.environ, BEND_NO_TELEMETRY='1'))
    driver_ms = (time.perf_counter()-started)*1000
    lines = result.stdout.splitlines()
    assert lines[-1] == 'complete' and len(lines) == samples+1, result.stdout
    rows = [list(map(int, line.split())) for line in lines[:-1]]
    assert all(len(row) == 4 and row[0] == i for i, row in enumerate(rows))
    assert len({(row[2], row[3]) for row in rows}) == 1
    if mode in (3, 6):
        assert not output.exists()
        expected = data
        encoded_sha = None
    else:
        expected = output.read_bytes()
        encoded_sha = digest(expected)
        if mode <= 1:
            verify_stream(expected, data)
        else:
            assert expected == data, (name, mode, 'byte replay changed contents')
    assert rows[0][2] == len(expected)
    assert rows[0][3] == (zlib.adler32(expected)*BATCH) & 0xffffffff
    rss = re.search(r'(\d+)\s+maximum resident set size', result.stderr)
    return dict(samples_ms=[row[1]/BATCH for row in rows], output_bytes=len(expected),
                output_sha256=encoded_sha, aggregate_adler32=rows[0][3],
                process_peak_rss_bytes=int(rss[1]) if rss else None,
                driver_wall_ms=driver_ms, complete=True)


def stats(values):
    return dict(median_ms=statistics.median(values),
                p95_ms=sorted(values)[math.ceil(len(values)*.95)-1],
                min_ms=min(values), max_ms=max(values))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, default=ROOT/'build/bend/package-evaluation/package-evaluation')
    parser.add_argument('--bend', type=Path, default=DEFAULT_BEND)
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--manifest', type=Path, default=ROOT/'scripts/fixtures/bend-package-nbt.json')
    parser.add_argument('--output', type=Path, default=ROOT/'build/bend/package-evaluation/results.json')
    parser.add_argument('--warmups', type=int, default=5)
    parser.add_argument('--repeats', type=int, default=20)
    parser.add_argument('--guard-lane', choices=['local', 'package'],
                        help='Stable-output equivalent compression driver for perf_guard.py capture.')
    args = parser.parse_args()
    version = build(args.bend, args.binary, ROOT/'bend/tests/package-evaluation.bend') if args.build else 'bend 2.0.36 (prebuilt; see executable hash)'
    folder = args.output.parent/'outputs'
    folder.mkdir(parents=True, exist_ok=True)
    payloads = inputs(args.manifest)
    if args.guard_lane:
        mode = 0 if args.guard_lane == 'local' else 1
        for name, data, _ in payloads:
            result = run(args.binary, folder, name, data, mode, args.warmups+args.repeats)
            print(name, result['output_sha256'], result['output_bytes'])
        return
    report = dict(scope=__doc__.strip(), packages=PACKAGES, bend_version=version,
        manifest_sha256=digest(args.manifest.read_bytes()), executable_sha256=digest(args.binary.read_bytes()),
        fixture_sha256=digest((ROOT/'bend/tests/package-evaluation.bend').read_bytes()),
        production_sources={str(p.relative_to(ROOT)):digest(p.read_bytes())
                            for p in [ROOT/'bend/compression.bend',ROOT/'bend/binary.bend']},
        environment=dict(platform=platform.platform(), machine=platform.machine(),
            cpu_threads=2, nice_adjustment=10, load_start=os.getloadavg(),
            own_compilation_concurrent=False, other_host_load_controlled=False,
            clock_resolution_ms=1),
        warmups=args.warmups, repetitions=args.repeats, operations_per_sample=BATCH,
        complete=False, correctness=[], results=[])
    # Test the published pure codec independently on empty, literal, overlapping,
    # incompressible and block/window-boundary cases. Not a performance dataset.
    rng = random.Random(20261009)
    cases = [('empty', b''), ('all_octets', bytes(range(256))),
             ('distance_one', b'a'*10000), ('distance_three', b'abc'*10000)]
    cases += [(f'random_{n}', rng.randbytes(n))
              for n in (1, 2, 257, 258, 259, 32767, 32768, 32769, 65534, 65535, 65536)]
    for name, data in cases:
        row = dict(name=name, bytes=len(data), lanes={})
        for mode in range(len(MODES)):
            result = run(args.binary, folder, name, data, mode, 1)
            row['lanes'][MODES[mode]] = {k:v for k,v in result.items() if k not in ('samples_ms','driver_wall_ms')}
        report['correctness'].append(row)
    for index, (name, data, metadata) in enumerate(payloads):
        lanes = {}
        # Reverse order across payloads to limit systematic lane ordering bias.
        for mode in (range(len(MODES)) if index % 2 == 0 else reversed(range(len(MODES)))):
            result = run(args.binary, folder, name, data, mode, args.warmups+args.repeats)
            result['samples_ms'] = result['samples_ms'][args.warmups:]
            result.update(stats(result['samples_ms']))
            lanes[MODES[mode]] = result
        report['results'].append(dict(name=name, input_bytes=len(data), input_sha256=digest(data),
            metadata=metadata, lanes=lanes,
            compression_median_ratio=lanes['package_zlib']['median_ms']/lanes['local_zlib']['median_ms'],
            compression_size_ratio=lanes['package_zlib']['output_bytes']/lanes['local_zlib']['output_bytes'],
            packed_payload_slots=1 << max(0, ((len(data)+3)//4-1).bit_length()),
            local_payload_slots=1 << max(8, (len(data)-1).bit_length())))
        print(name, {key:round(value['median_ms'],4) for key,value in lanes.items()}, flush=True)
        args.output.write_text(json.dumps(report,indent=2)+'\n')
    report['environment']['load_end'] = os.getloadavg()
    report['complete'] = True
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    print('PASS:', args.output)


if __name__ == '__main__':
    main()
