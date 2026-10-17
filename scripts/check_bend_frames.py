#!/usr/bin/env python3
"""Bounded CPU checks of the production Bend frame decoder and stream reader."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import time

from benchmark_limits import positive_seconds, supervise
from bend_compiler import compiler_environment
from test_bend_compression import DEFAULT_BEND, ROOT


def run(args):
    output = args.output.resolve()
    report = dict(status='running', commands=[], steps=[],
                  scope='CPU frame correctness only; no terrain, GPU or benchmark.')

    def save():
        (output/'report.json').write_text(json.dumps(report, indent=2)+'\n')

    def execute(name, command, *, data=None, valid=True):
        report['active_step'] = name
        report['commands'].append(command)
        save()
        start = time.monotonic()
        result = subprocess.run(command, cwd=ROOT, env=compiler_environment(),
                                input=data, capture_output=True)
        (output/(name+'.stdout')).write_bytes(result.stdout)
        (output/(name+'.stderr')).write_bytes(result.stderr)
        report['steps'].append(dict(name=name, seconds=time.monotonic()-start,
                                    exit_code=result.returncode))
        save()
        if valid:
            result.check_returncode()
        else:
            assert result.returncode != 0 and result.stdout == b'', name
        return result.stdout

    save()
    try:
        binaries = []
        for fixture in ('frame', 'frame-read'):
            source = ROOT/'bend/tests'/f'{fixture}.bend'
            generated = output/f'{fixture}.c'
            binary = output/fixture
            execute(f'{fixture}-emit', ['nice', '-n', '10', str(args.bend.resolve()),
                                       str(source), '-o', str(generated)])
            execute(f'{fixture}-compile', ['nice', '-n', '10', os.environ.get('CC', 'cc'),
                    '-std=c11', '-O1', str(generated), '-lpthread', '-lm', '-o', str(binary)])
            binaries.append(['nice', '-n', '10', str(binary), '--threads', '2', '--gpu', 'off'])
        assert b'PASS: 24 frame header cases and 13 buffer capacity cases' in execute('headers', binaries[0])

        def frame(body=b'', identity=0, opcode=0, magic=0x52424e44, length=None):
            return struct.pack('>4I', magic, len(body) if length is None else length,
                               identity, opcode)+body

        # Empty, unsigned IDs/opcodes, consecutive frames, exact array capacities,
        # and the transport's 64 KiB read boundary. Compare opaque bytes exactly.
        sizes = (0, 1, 2, 255, 256, 257, 511, 512, 513, 65535, 65536, 65537)
        requests = b''.join(frame((bytes(range(256))*((size+255)//256))[:size],
                                  0xffffffff-i, 0xffffffff-i) for i, size in enumerate(sizes))
        assert execute('round-trip', binaries[1], data=requests) == requests
        assert execute('clean-eof', binaries[1], data=b'') == b''
        for i, data in enumerate((frame(magic=0), frame(length=16777217),
                                  frame(length=0xffffffff), frame()[:7], frame(b'ab')[:-1])):
            execute(f'reject-{i}', binaries[1], data=data, valid=False)
        report.update(status='passed', active_step=None, header_cases=24,
                      capacity_cases=13, stream_frames=len(sizes), payload_sizes=sizes,
                      malformed_streams=5, clean_eof=True,
                      compiler_sha256=hashlib.sha256(args.bend.read_bytes()).hexdigest(),
                      artifacts={p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                 for p in output.iterdir() if p.suffix == '.c' or p.name in ('frame', 'frame-read')},
                      sources={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in [ROOT/'bend/frame.bend', ROOT/'bend/engine.bend',
                                         ROOT/'bend/binary.bend', ROOT/'bend/transport.bend',
                                         ROOT/'bend/tests/frame.bend', ROOT/'bend/tests/frame-read.bend']})
        save()
        print('PASS: 24 headers, 13 capacities, 12 round-trip frames, clean EOF and 5 malformed streams')
    except BaseException as error:
        report.update(status='failed', error=repr(error))
        save()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend', type=Path, default=DEFAULT_BEND)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--max-seconds', type=positive_seconds, default=30.)
    args = parser.parse_args()
    result = supervise(str(Path(__file__).resolve()), args.max_seconds, args.output)
    if result is not None:
        raise SystemExit(result)
    run(args)


if __name__ == '__main__':
    main()
