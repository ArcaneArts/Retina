#!/usr/bin/env python3
"""Independently check exact registry number conversion on Bend CPU and GPU."""
import argparse
from fractions import Fraction
import hashlib
import json
import math
import random
import re
import struct
import subprocess
from pathlib import Path
from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import compile_metal_observer


def bits(x):
    try:
        return struct.unpack('>I',struct.pack('>f',x))[0]
    except OverflowError:
        return 0xff800000 if x < 0 else 0x7f800000


def integer_reference(value):
    # Choose the nearest representable float using exact rational distances.
    # The preliminary double conversion is only a search hint; it cannot decide
    # ties or lose the +/-1 cases around midpoints above 2**53.
    sign = 0x80000000 if value < 0 else 0
    magnitude = abs(value)
    hint = bits(float(magnitude))
    candidates = range(max(0,hint-2),hint+3)
    best = min(candidates,key=lambda b:(abs(Fraction(struct.unpack('>f',struct.pack('>I',b))[0])-magnitude),b&1))
    return best | sign


def fixtures():
    rng = random.Random(20261008)
    values = [0,1,1<<63,(1<<64)-1,0x7ff0000000000000,0xfff0000000000000,
              0x7ff8000000000001,0x7ff0000000000001]
    # Every double exponent, both signs, and explicit ties/carries/underflow.
    for exponent in range(2047):
        for fraction in (0,1,(1<<52)-1,1<<51):
            values.append((exponent<<52)|fraction)
            values.append((1<<63)|(exponent<<52)|fraction)
    for exponent in range(870,1153):
        for retained in (0,1,2,0x3fffff,0x7ffffe,0x7fffff):
            for tail in ((1<<28)-1,1<<28,(1<<28)+1):
                values.append((exponent<<52)|(retained<<29)|tail)
    for bit in range(24,63):
        for prefix in (1<<bit,(1<<bit)+(1<<(bit-1)),(1<<(bit+1))-(1<<(bit-23))):
            midpoint = prefix+(1<<(bit-24))
            for delta in (-1,0,1):
                value = midpoint+delta
                if value < 1<<63:
                    values.extend((value,(-value)&((1<<64)-1)))
    values += [rng.getrandbits(64) for _ in range(12000)]
    # Owned fixture arrays use a power-of-two count; unused rows remain useful
    # randomized conversions rather than relying on wrapped array indices.
    while len(values)&(len(values)-1): values.append(rng.getrandbits(64))
    return values


def verify(path,values):
    raw = path.read_bytes()
    assert len(raw) == len(values)*8
    for i,(d,l) in enumerate(struct.iter_unpack('>2I',raw)):
        value = values[i]
        source = struct.unpack('>d',struct.pack('>Q',value))[0]
        if math.isnan(source):
            assert d & 0x7fc00000 == 0x7fc00000
            assert (d>>31) == (value>>63)
        else:
            assert d == bits(source),('double',i,hex(value),hex(d),hex(bits(source)))
        signed = value if value < 1<<63 else value-(1<<64)
        expected = integer_reference(signed)
        assert l == expected,('integer',i,signed,hex(l),hex(expected))
    return {'rows':len(values),'scalar_conversions':len(values)*2,'sha256':hashlib.sha256(raw).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true')
    args = parser.parse_args()
    folder = ROOT/'build/bend'
    source = ROOT/'bend/tests/numeric-fixtures.bend'
    binary = folder/'numeric-fixtures'
    if not args.skip_build: build(args.bend,binary,source)
    values = fixtures()
    input = folder/'numeric-input.bin'
    input.write_bytes(struct.pack('>I',len(values))+b''.join(struct.pack('>Q',v) for v in values))
    report = {'scope':'Registry numeric conversion correctness; not terrain generation or a benchmark','runs':[]}
    for gpu,threads in [('off',1),('off',2),('on',2)]:
        output = folder/f'numeric-{gpu}-{threads}.bin'
        subprocess.run(['nice','-n','10',str(binary),str(input),str(output),'--threads',str(threads),'--gpu',gpu],check=True,timeout=120,cwd=ROOT)
        report['runs'].append({'requested_gpu':gpu,'threads':threads,**verify(output,values)})
    assert len({r['sha256'] for r in report['runs']}) == 1
    # This integer-only conversion should have exactly identical bits on GPU.
    report['cpu_gpu_bytes_equal'] = True
    if args.prove_metal:
        diagnostic = compile_metal_observer(args.bend,source,folder/'numeric-metal-probe')
        output = folder/'numeric-probe.bin'
        p = subprocess.run(['nice','-n','10',str(diagnostic),str(input),str(output),'--threads','2','--gpu','on'],check=True,capture_output=True,text=True,timeout=120,cwd=ROOT)
        times = [float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',p.stderr)]
        assert len(times) == 1 and times[0] > 0
        observed = verify(output,values)
        assert observed['sha256'] == report['runs'][2]['sha256']
        report['metal_observation'] = {'diagnostic_only':True,'command_buffers':1,'device_ms':times,'output':observed}
    for name in ('bend/numbers.bend','bend/tests/numeric-fixtures.bend'):
        report.setdefault('source_sha256',{})[name] = hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
    destination = folder/'numeric-tests.json'
    destination.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: {len(values)*2} conversions per CPU/GPU run; {destination}')


if __name__ == '__main__': main()
