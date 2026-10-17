#!/usr/bin/env python3
"""Opt-in, bounded correctness check for Bend lighting changes.

Emit stock C for the small lighting driver, compile it once, and compare its
two-thread CPU output to the independent flood fill. CPU is the default and
does not build a GPU archive. Explicit --metal instead builds a diagnostic
fixture binary and its stock GPU archive, then checks two small Metal scenes.
Host-only command-buffer logging proves device execution; computation is still
the compiler's output. The supervisor limits emission, compilation and all
checks together. No retries, terrain engine, benchmark or game launch occurs.

Obtain authorization before running while native checks are stopped.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys

from benchmark_limits import positive_seconds, supervise
from test_bend_compression import DEFAULT_BEND, ROOT, metal_compiler_command


METAL_CASES = ('opaque-emitter-brick', 'registered-optics-gallery')
METAL_WAIT = '    [cb waitUntilCompleted];'
METAL_LOG = '''
    fprintf(stderr, "BEND_METAL_DISPATCH device_ms=%.6f status=%lu\\n",
      ([cb GPUEndTime]-[cb GPUStartTime])*1000.0, (unsigned long)[cb status]);'''


def instrument_metal(code):
    # This changes only host diagnostics after the existing wait. Leave the
    # compiler's application code, device algorithm and scheduling untouched.
    assert code.count(METAL_WAIT) == 1, 'Expected one stock Metal command-buffer wait'
    return code.replace(METAL_WAIT, METAL_WAIT+METAL_LOG)


def metal_evidence(log):
    rows = re.findall(r'^BEND_METAL_DISPATCH device_ms=(\S+) status=(\d+)$', log, re.MULTILINE)
    assert rows, 'No completed Metal command buffers observed'
    assert len(rows) == log.count('BEND_METAL_DISPATCH'), 'Malformed Metal observation'
    times = [float(ms) for ms, _ in rows]
    # MTLCommandBufferStatusCompleted is 4. A flag or an archive alone is not
    # device-execution evidence; require actual successful timestamped work.
    assert all(int(status) == 4 for _, status in rows), 'Metal command buffer did not complete'
    assert all(math.isfinite(ms) and ms >= 0 for ms in times) and any(ms > 0 for ms in times)
    return dict(completed_buffers=len(rows), device_ms=times)


def run(args):
    output = args.output.resolve()
    source = ROOT/'bend/tests/light-volume.bend'
    generated, binary = output/'volume.c', output/'volume'
    env = dict(os.environ,BEND_NO_TELEMETRY='1',RAYON_NUM_THREADS='2')
    commands = []
    def execute(command, name):
        commands.append(command)
        print('START '+name, flush=True)
        with (output/(name+'.log')).open('w') as log:
            subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)

    # -o *.c only emits stock compiler output. Direct native compilation avoids
    # the CLI's automatic archive build. Only explicit --metal requests it.
    execute(['nice','-n','10',str(args.bend.resolve()),str(source),'-o',str(generated)], 'emit')
    native_source = generated
    if args.metal:
        native_source = output/'volume-metal-diagnostic.c'
        native_source.write_text(instrument_metal(generated.read_text()))
    execute(metal_compiler_command(native_source,binary) if sys.platform == 'darwin' or args.metal else
            ['nice','-n','10',env.get('CC','clang'),'-std=c11','-O3',str(native_source),'-lpthread','-lm','-o',str(binary)], 'compile')
    if args.metal:
        execute(['nice','-n','10',str(binary),'--threads','2','--gpu-build'], 'gpu-build')
    checks = []
    for case in METAL_CASES if args.metal else (None,):
        folder = output/'fixtures'/case if case else output/'fixtures'
        command = ['nice','-n','10',sys.executable,str(ROOT/'scripts/test_bend_light_volume.py'),
                   '--binary',str(binary),'--profile',str(args.profile.resolve()),'--output',str(folder)]
        if args.metal:
            command += ['--gpu', '--case', case]
        execute(command, case or 'fixtures')
        result = json.loads((folder/'report.json').read_text())
        assert result['cpu_threads'] == 2 and result['gpu_requested'] is args.metal
        if args.metal:
            assert [row['name'] for row in result['results']] == [case]
            result['observed_metal'] = metal_evidence((folder/(case+'.log')).read_text())
        checks.append(result)
    report = dict(scope=__doc__.strip(),status='passed',commands=commands,mode='metal' if args.metal else 'cpu',
                  compiler_sha256=hashlib.sha256(args.bend.read_bytes()).hexdigest(),
                  harness_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  generated_c_sha256=hashlib.sha256(generated.read_bytes()).hexdigest(),
                  native_source_sha256=hashlib.sha256(native_source.read_bytes()).hexdigest(),
                  result=checks[0] if not args.metal else dict(checks=checks),
                  limitations=['Small fixture correctness only; no full region or speedup claim.',
                      'Metal mode uses host timing diagnostics; it is not a production performance measurement.' if args.metal else
                      'CPU execution only; GPU behavior unverified.'])
    if args.metal:
        report['gpu_archive_sha256'] = hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest()
    (output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(f"PASS: {sum(len(check['results']) for check in checks)} {report['mode']} lighting fixtures; {output / 'report.json'}",flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--profile',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True,help='New directory for source, logs and results')
    parser.add_argument('--metal',action='store_true',help='Explicitly build a diagnostic Metal fixture and GPU archive, then check two scenes; requires separate approval')
    parser.add_argument('--max-seconds',type=positive_seconds,default=30.,help='Total emit/build/check deadline (default: 30 seconds)')
    args = parser.parse_args()
    supervised = supervise(str(Path(__file__).resolve()),args.max_seconds,args.output)
    if supervised is not None: raise SystemExit(supervised)
    run(args)


if __name__ == '__main__':
    main()
