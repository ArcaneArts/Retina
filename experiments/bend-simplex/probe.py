#!/usr/bin/env python3
"""Separately confirm Bend dispatches real Metal commands. Never time this as production."""
import json
from pathlib import Path
import re
import statistics
import sys
import subprocess
import bench

grain = int(sys.argv[1]) if len(sys.argv)>1 else 256
source = bench.generate(grain)
cpath = bench.BUILD / 'metal-dispatch-probe.c'
bench.command([bench.DEFAULT_BEND, source, '-o', cpath])
code = cpath.read_text()
needle = '[cb waitUntilCompleted];'
assert code.count(needle) == 1
code = code.replace(needle, needle + '\n    fprintf(stderr, "BEND_METAL_DISPATCH device_ms=%.6f\\n", ([cb GPUEndTime]-[cb GPUStartTime])*1000.0);')
cpath.write_text(code)
binary = bench.BUILD / 'metal-dispatch-probe'
bench.command(['nice','-n','10',bench.ENV.get('CC','clang'), '-DBEND_METAL=1', '-x','objective-c',
               '-fobjc-arc','-fmodules','-std=c11','-O3',cpath,'-lpthread','-lm','-o',binary])
# Build THIS program's matching GPU archive; never copy another generated program's cache.
bench.command([binary, '--gpu-build'])
result = dict(grain=grain, diagnostic_only=True, cases=[])
for n in (262144, 1048576, 4194304):
    p = subprocess.run([str(binary),str((n//grain).bit_length()-1),'13', '--gpu','on','--threads','4'],
                       env=bench.ENV,cwd=bench.ROOT,capture_output=True,text=True,check=True,timeout=60)
    times = [float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',p.stderr)]
    assert len(times) == 13, p.stderr
    result['cases'].append(dict(n=n, metal_command_ms=times, median_warm_ms=statistics.median(times[3:]),
                                stdout=p.stdout, stderr=p.stderr))
path = bench.HERE / 'metal-dispatch-proof.json'
path.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
