#!/usr/bin/env python3
"""Bounded CPU codec correctness: exhaustive codes, streams and optional saved NBT.

No terrain engine, GPU, benchmark repetitions or throughput comparison. The
optional corpus is compressed once and compared with retained golden bytes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess

from benchmark_limits import positive_seconds, supervise
from bend_compiler import compiler_environment
from test_bend_compression import DEFAULT_BEND, ROOT, run_case, test, verify_stream


def digest(data):
    return hashlib.sha256(data).hexdigest()


def expected_codes():
    # RFC 1951 section 3.2.5 tables, independent of production group arithmetic.
    length_bases = (3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27,
                    31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    length_widths = (0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
                     3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    distance_bases = (1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129,
                      193, 257, 385, 513, 769, 1025, 1537, 2049, 3073,
                      4097, 6145, 8193, 12289, 16385, 24577)
    distance_widths = (0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
                       7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    for bases, widths, first, end in ((length_bases, length_widths, 257, 259),
                                      (distance_bases, distance_widths, 0, 32769)):
        for i, (base, width) in enumerate(zip(bases, widths)):
            limit = bases[i + 1] if i + 1 < len(bases) else end
            for value in range(base, limit):
                yield first + i, value - base, width


def run(args):
    output = args.output.resolve()
    report = dict(status='running', commands=[],
                  scope='CPU correctness only; no terrain, GPU or benchmark.')

    def save():
        (output/'report.json').write_text(json.dumps(report, indent=2)+'\n')

    def execute(name, command):
        report['active_step'] = name
        report['commands'].append(command)
        save()
        result = subprocess.run(command, cwd=ROOT, env=compiler_environment(), capture_output=True)
        (output/(name+'.stdout')).write_bytes(result.stdout)
        (output/(name+'.stderr')).write_bytes(result.stderr)
        result.check_returncode()

    save()
    try:
        binaries = {}
        for fixture in ('compression-codes', 'compress-file'):
            source = ROOT/'bend/tests'/f'{fixture}.bend'
            generated, binary = output/f'{fixture}.c', output/fixture
            execute(f'{fixture}-emit', ['nice', '-n', '10', str(args.bend.resolve()),
                                       str(source), '-o', str(generated)])
            execute(f'{fixture}-compile', ['nice', '-n', '10', os.environ.get('CC', 'cc'),
                    '-std=c11', '-O1', str(generated), '-lpthread', '-lm', '-o', str(binary)])
            binaries[fixture] = binary
        codes = output/'codes.bin'
        execute('codes', ['nice', '-n', '10', str(binaries['compression-codes']),
                         str(codes), '--threads', '2', '--gpu', 'off'])
        expected = list(expected_codes())
        actual = list(struct.iter_unpack('>3I', codes.read_bytes()))
        assert len(actual) == len(expected) == 33024
        for i, (got, want) in enumerate(zip(actual, expected)):
            assert got == want, (i, got, want)
        report.update(length_codes=256, distance_codes=32768, codes_sha256=digest(codes.read_bytes()))
        report['active_step'] = 'stream-correctness'
        save()
        fixtures = output/'fixtures'
        fixtures.mkdir()
        streams = test(binaries['compress-file'], fixtures, parallel_threads=(1, 2))
        for row in streams['payload_cases']:
            row.pop('driver_wall_ms')  # This run is not a performance comparison.
        report['streams'] = streams
        report['saved_nbt'] = []
        save()
        if args.corpus:
            corpus = args.corpus.resolve()
            manifest_bytes = (corpus/'corpus/manifest.json').read_bytes()
            manifest = json.loads(manifest_bytes)
            golden_bytes = (corpus/'bend/report.json').read_bytes()
            golden = json.loads(golden_bytes)
            assert golden['corpus_sha256'] == digest(manifest_bytes)
            output_rows = {row['file']: row for row in golden['outputs']}
            report['corpus'] = dict(path=str(corpus), manifest_sha256=digest(manifest_bytes),
                                    golden_report_sha256=digest(golden_bytes))
            for row in manifest['samples']:
                report['active_step'] = row['file']
                save()
                raw = (corpus/'corpus'/row['file']).read_bytes()
                assert len(raw) == row['bytes'] and digest(raw) == row['sha256']
                name = row['file']+'.zlib'
                expected = (corpus/'bend'/name).read_bytes()
                expected_row = output_rows[name]
                assert len(expected) == expected_row['bytes'] and digest(expected) == expected_row['sha256']
                verify_stream(expected, raw)
                encoded, _ = run_case(binaries['compress-file'], fixtures, raw)
                assert encoded == expected, row['file']
                report['saved_nbt'].append(dict(file=row['file'], input_sha256=digest(raw),
                                                compressed_sha256=digest(encoded),
                                                byte_identical=True, lit=row['lit']))
        report.update(status='passed', active_step=None,
                      compiler_sha256=digest(args.bend.read_bytes()),
                      artifacts={p.name: digest(p.read_bytes()) for p in output.iterdir()
                                 if p.suffix == '.c' or p.name in binaries},
                      sources={str(p.relative_to(ROOT)): digest(p.read_bytes()) for p in [
                          ROOT/'bend/compression.bend', ROOT/'bend/binary.bend',
                          ROOT/'bend/tests/compression-codes.bend', ROOT/'bend/tests/compress-file.bend',
                          Path(__file__), ROOT/'scripts/test_bend_compression.py']})
        save()
        print(f"PASS: 33024 codes, {len(streams['payload_cases'])} payloads, "
              f"{streams['backreference_cases']} backreferences, "
              f"{streams['parallel_streams_verified']} parallel streams, "
              f"{len(report['saved_nbt'])} unchanged saved NBT streams")
    except BaseException as error:
        report.update(status='failed', error=repr(error))
        save()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend', type=Path, default=DEFAULT_BEND)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--corpus', type=Path,
                        help='Retained finished-NBT comparison directory containing corpus/ and bend/.')
    parser.add_argument('--max-seconds', type=positive_seconds, default=30.)
    args = parser.parse_args()
    result = supervise(str(Path(__file__).resolve()), args.max_seconds, args.output)
    if result is not None:
        raise SystemExit(result)
    run(args)


if __name__ == '__main__':
    main()
