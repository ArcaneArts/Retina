#!/usr/bin/env python3
"""Opt-in, bounded CPU correctness check for Bend lighting changes.

Emit stock C for the small lighting driver, compile it once, and compare its
two-thread CPU output to the independent flood fill. Do not build the GPU
archive or terrain engine. A separate supervisor limits the complete sequence;
no retries, throughput benchmark, or Minecraft launch is performed.

Obtain authorization before running while native checks are stopped.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from benchmark_limits import positive_seconds, supervise
from test_bend_compression import DEFAULT_BEND, ROOT, metal_compiler_command


def run(args):
    output = args.output.resolve()
    source = ROOT/'bend/tests/light-volume.bend'
    generated, binary = output/'volume.c', output/'volume'
    env = dict(os.environ,BEND_NO_TELEMETRY='1',RAYON_NUM_THREADS='2')
    # -o *.c only emits stock compiler output. Direct native compilation avoids
    # Bend's automatic GPU archive build for entry points containing ! calls.
    commands = [
        ['nice','-n','10',str(args.bend.resolve()),str(source),'-o',str(generated)],
        metal_compiler_command(generated,binary) if sys.platform == 'darwin' else
        ['nice','-n','10',env.get('CC','clang'),'-std=c11','-O3',str(generated),'-lpthread','-lm','-o',str(binary)],
        ['nice','-n','10',sys.executable,str(ROOT/'scripts/test_bend_light_volume.py'),
         '--binary',str(binary),'--profile',str(args.profile.resolve()),'--output',str(output/'fixtures')],
    ]
    for command, name in zip(commands,('emit','compile','fixtures')):
        with (output/(name+'.log')).open('w') as log:
            subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    result = json.loads((output/'fixtures/report.json').read_text())
    assert result['cpu_threads'] == 2 and result['gpu_requested'] is False
    report = dict(scope=__doc__.strip(),status='passed',commands=commands,
                  compiler_sha256=hashlib.sha256(args.bend.read_bytes()).hexdigest(),
                  harness_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  generated_c_sha256=hashlib.sha256(generated.read_bytes()).hexdigest(),
                  result=result,limitations=['CPU fixture correctness only.',
                      'No GPU execution, full region, or speedup claim.'])
    (output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(f"PASS: {len(result['results'])} CPU lighting fixtures; {output / 'report.json'}",flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--profile',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True,help='New directory for source, logs and results')
    parser.add_argument('--max-seconds',type=positive_seconds,default=30.,help='Total emit/build/check deadline (default: 30 seconds)')
    args = parser.parse_args()
    supervised = supervise(str(Path(__file__).resolve()),args.max_seconds,args.output)
    if supervised is not None: raise SystemExit(supervised)
    run(args)


if __name__ == '__main__':
    main()
