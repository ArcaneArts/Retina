#!/usr/bin/env python3
"""Build the stock Bend worker in separately bounded native and Metal stages.

Native mode emits the complete engine, compiles one binary at reduced priority,
and checks CPU protocol startup/shutdown. Metal mode copies that binary into a
new output directory and asks its stock runtime to build its matching archive.
Neither mode generates terrain, installs a worker, or launches Minecraft.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time

from benchmark_limits import positive_seconds, supervise
from bend_compiler import compiler_environment
from test_bend_compression import DEFAULT_BEND, ROOT, metal_compiler_command


MAGIC = 0x52424e44


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def smoke_bytes():
    requests = b''.join(struct.pack('>4I', MAGIC, 0, i, op)
                        for i, op in ((1, 0), (2, 999), (3, 4)))
    version = struct.pack('>5I', 1, 2, 0, 36, 0)
    replies = b''.join(struct.pack('>5I', MAGIC, len(payload), i, op, status)+payload
                       for i, op, status, payload in
                       ((1, 0, 0, version), (2, 999, 1, struct.pack('>I', 605)),
                        (3, 4, 0, version)))
    return requests, replies


def record_control(output):
    report_path = output/'report.json'
    control_path = output/'run-control.json'
    if report_path.exists() and control_path.exists():
        report = json.loads(report_path.read_text())
        control = json.loads(control_path.read_text())
        report['run_control'] = control
        if control['status'] != 'completed':
            report['status'] = control['status']
        report_path.write_text(json.dumps(report, indent=2)+'\n')


def run(args):
    output = args.output.resolve()
    binary = output/'engine'
    env = compiler_environment()
    report = dict(scope=__doc__.strip(), status='running', stage=args.stage,
                  harness_sha256=digest(Path(__file__)),
                  commands=[], steps=[], limitations=[
                      'Build and protocol smoke only; no terrain correctness or performance claim.',
                      'Metal archive construction does not prove device execution.',
                      'No installation or packaging is performed.'])

    def save():
        (output/'report.json').write_text(json.dumps(report, indent=2)+'\n')

    def execute(name, command, **kwargs):
        report['active_step'] = name
        report['commands'].append(command)
        save()
        print('START', name, flush=True)
        started = time.monotonic()
        with (output/(name+'.log')).open('wb') as log:
            result = subprocess.run(command, cwd=ROOT, env=env, stderr=log,
                                    stdout=subprocess.PIPE if 'input' in kwargs else log,
                                    check=True, **kwargs)
        report['steps'].append(dict(name=name, elapsed_seconds=time.monotonic()-started))
        save()
        return result

    save()
    try:
        if args.stage == 'native':
            report['compiler_sha256'] = digest(args.bend)
            report['sources'] = {str(p.relative_to(ROOT)): digest(p)
                                 for p in sorted((ROOT/'bend').rglob('*.bend'))}
            generated = output/'engine.c'
            execute('emit', ['nice', '-n', '10', str(args.bend.resolve()),
                             str(ROOT/'bend/engine.bend'), '-o', str(generated)])
            report['generated_c_sha256'] = digest(generated)
            command = metal_compiler_command(generated, binary) if sys.platform == 'darwin' else [
                'nice', '-n', '10', os.environ.get('CC', 'cc'), '-std=c11', '-O3',
                str(generated), '-lpthread', '-lm', '-o', str(binary)]
            execute('compile', command)
            request, expected = smoke_bytes()
            result = execute('cpu-protocol', ['nice', '-n', '10', str(binary), 'cpu',
                             '--threads', '2', '--gpu', 'off'], input=request)
            (output/'cpu-protocol.bin').write_bytes(result.stdout)
            assert result.stdout == expected, 'Worker protocol startup/error/shutdown response mismatch'
            report['cpu_protocol'] = dict(requests=3, threads=2, gpu=False,
                                          output_sha256=digest(output/'cpu-protocol.bin'))
        else:
            report['input_binary'] = str(args.binary.resolve())
            report['input_binary_sha256'] = digest(args.binary)
            shutil.copy2(args.binary, binary)
            execute('gpu-build', ['nice', '-n', '10', str(binary), '--threads', '2', '--gpu-build'])
            report['gpu_archive_sha256'] = digest(binary.with_suffix('.gpu'))
        report['binary_sha256'] = digest(binary)
        report.update(status='passed', active_step=None)
        save()
        print(f'PASS: {args.stage} worker build; {output / "report.json"}', flush=True)
    except BaseException as error:
        report.update(status='failed', error=repr(error))
        save()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=('native', 'metal'))
    parser.add_argument('--bend', type=Path, default=DEFAULT_BEND)
    parser.add_argument('--binary', type=Path, help='Native engine to copy and build an archive for (Metal stage)')
    parser.add_argument('--output', type=Path, required=True, help='New output directory')
    parser.add_argument('--max-seconds', type=positive_seconds, default=30.)
    args = parser.parse_args()
    if args.stage == 'metal' and args.binary is None:
        parser.error('metal stage requires --binary')
    try:
        supervised = supervise(str(Path(__file__).resolve()), args.max_seconds, args.output)
    except FileExistsError:
        # The supervisor did not acquire this output directory. Preserve any
        # earlier run's reports, including its original interrupted status.
        raise
    except BaseException:
        record_control(args.output)
        raise
    if supervised is not None:
        record_control(args.output)
        raise SystemExit(supervised)
    run(args)


if __name__ == '__main__':
    main()
