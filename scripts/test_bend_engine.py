#!/usr/bin/env python3
"""Exercise the persistent Bend binary worker, independently decoding its output."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import struct
import subprocess
import zlib

from test_bend_compression import ROOT, DEFAULT_BEND, build, metal_compiler_command
from test_bend_noise import f32, world_simplex

MAGIC = 0x52424e44


def read_exact(stream, size):
    data = bytearray()
    while len(data) < size:
        part = stream.read(size-len(data))
        assert part, ('worker EOF', size, len(data))
        data.extend(part)
    return bytes(data)


class Worker:
    def __init__(self, binary, gpu):
        self.process = subprocess.Popen(['nice','-n','10',str(binary),'gpu' if gpu else 'cpu',
            '--threads','2','--gpu','on' if gpu else 'off'], cwd=ROOT,
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
            env=dict(os.environ,BEND_NO_TELEMETRY='1'))
        self.id = 0

    def call(self, opcode, data=b'', status=0, fragment=False):
        self.id += 1
        frame = struct.pack('>4I',MAGIC,len(data),self.id,opcode)+data
        # Fragment a real frame to exercise repeated reads, not only full pipes.
        for at in range(0,len(frame),3 if fragment else len(frame)):
            self.process.stdin.write(frame[at:at+(3 if fragment else len(frame))])
            self.process.stdin.flush()
        magic, size, request, op, result = struct.unpack('>5I',read_exact(self.process.stdout,20))
        assert (magic,request,op,result) == (MAGIC,self.id,opcode,status)
        assert size <= 17*1024*1024
        return read_exact(self.process.stdout,size)

    def close(self):
        self.process.stdin.close()
        try:
            assert self.process.wait(timeout=10) == 0, self.process.stderr.read().decode()
        finally:
            self.process.kill() if self.process.poll() is None else None
            self.process.stdout.close()
            self.process.stderr.close()


LOW, HIGH = 0x12345678, 0x80000000
FREQUENCY, AMPLITUDE, CHANNEL = f32(.0035), f32(.8), 2
MODIFIERS = [1.,0.,.25,1.,-1.,.5]


def configuration():
    return struct.pack('>2I2f2I6f',LOW,HIGH,FREQUENCY,AMPLITUDE,CHANNEL,6,*MODIFIERS)


def reference(x, z):
    values, weights = [], []
    for i, modifier in enumerate(MODIFIERS):
        if modifier > 0:
            weight = modifier / (1 << i)
            weights.append(weight)
            values.append(world_simplex(x & 0xffffffff,z & 0xffffffff,f32(FREQUENCY*(1 << i)),
                (LOW+CHANNEL*7919+i*1013) & 0xffffffff,HIGH)*weight)
    return min(1.,max(-1.,sum(values)/sum(weights)*AMPLITUDE*f32(1.6)))


def exercise(binary, gpu):
    worker = Worker(binary,gpu)
    rows, maximum = [], 0.
    try:
        assert struct.unpack('>5I',worker.call(0,fragment=True)) == (1,2,0,36,int(gpu))
        assert worker.call(2,struct.pack('>5I',0,0,16,16,1),status=1) == struct.pack('>I',604)
        assert worker.call(1,b'\0'*8,status=1) == struct.pack('>I',603)
        assert worker.call(0,b'\0',status=1) == struct.pack('>I',603)
        assert worker.call(999,status=1) == struct.pack('>I',605)
        worker.call(1,configuration(),fragment=True)
        for x,z,width,height,step in [(-33,63,16,16,1),(29999000,-30000000,13,17,1),
                                     (-30000000,29999000,64,64,3),(123,-456,512,512,1)]:
            request = struct.pack('>5I',x & 0xffffffff,z & 0xffffffff,width,height,step)
            data = worker.call(2,request)
            assert len(data) == width*height*4
            values = struct.unpack(f'>{width*height}f',data)
            # Full checks for small grids; fixed independent probes for the region.
            indices = range(len(values)) if len(values) < 10000 else range(0,len(values),127)
            for i in indices:
                value = values[i]
                assert math.isfinite(value) and -1 <= value <= 1
                error = abs(value-reference(x+(i % width)*step,z+(i//width)*step))
                maximum = max(maximum,error)
                assert error < .00001,(i,error)
            assert data == worker.call(2,request), 'persistent repeat changed results'
            rows.append({'width':width,'height':height,'step':step,'sha256':hashlib.sha256(data).hexdigest()})
        for invalid in [struct.pack('>5I',0,0,0,16,1),struct.pack('>5I',0,0,513,16,1),
                        struct.pack('>5I',0,0,16,16,0),b'\0'*19,b'\0'*21]:
            assert worker.call(2,invalid,status=1) == struct.pack('>I',603)
        old = worker.call(2,struct.pack('>5I',0,0,1,1,1))
        bad = bytearray(configuration());bad[8:12] = struct.pack('>f',float('nan'))
        assert worker.call(1,bad,status=1) == struct.pack('>I',501)
        assert old == worker.call(2,struct.pack('>5I',0,0,1,1,1)), 'invalid config replaced resident state'
        rng = random.Random(20261008)
        compressed = []
        for data in [b'',b'minecraft:stone\0'*8192,rng.randbytes(65536),rng.randbytes(1048576)]:
            encoded = worker.call(3,data)
            decoder = zlib.decompressobj()
            assert decoder.decompress(encoded)+decoder.flush() == data
            assert decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail
            compressed.append({'input_bytes':len(data),'compressed_bytes':len(encoded)})
        for _ in range(100):
            worker.call(0)
        worker.call(4)
        assert worker.process.wait(timeout=10) == 0
        diagnostics = worker.process.stderr.read().decode()
        return {'gpu_required':gpu,'grids':rows,'max_reference_error':maximum,
                'compression':compressed,'requests_in_one_process':worker.id,
                'observed_metal_command_ms':[float(x) for x in re.findall(
                    r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostics)]}
    finally:
        worker.close()


def malformed_streams(binary):
    cases = [b'\0',b'\0'*15,struct.pack('>4I',MAGIC,10,1,3)+b'abc',
             struct.pack('>4I',MAGIC,16777217,1,3),struct.pack('>4I',0,0,1,0)]
    for data in cases:
        p = subprocess.run([str(binary),'cpu','--threads','2','--gpu','off'],input=data,
                           capture_output=True,timeout=10)
        assert p.returncode != 0 and not p.stdout,(p.returncode,p.stdout,p.stderr)
    p = subprocess.run([str(binary),'cpu','--threads','2','--gpu','off'],input=b'',capture_output=True,timeout=10)
    assert p.returncode == 0 and not p.stdout
    return len(cases)


def compile_metal_observer(bend,source,binary):
    # Diagnostic observation only; exactly the same one-line stock-runtime
    # observer used by the earlier simplex experiment and noise verification.
    cpath = binary.with_suffix('.c')
    build(bend,cpath,source)
    code = cpath.read_text()
    needle = '[cb waitUntilCompleted];'
    assert code.count(needle) == 1
    cpath.write_text(code.replace(needle,needle+'\n    fprintf(stderr, "BEND_METAL_DISPATCH device_ms=%.6f\\n", ([cb GPUEndTime]-[cb GPUStartTime])*1000.0);'))
    xcode = Path('/Applications/Xcode.app/Contents/Developer')
    env = dict(os.environ,BEND_NO_TELEMETRY='1',SDKROOT=str(xcode/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk'))
    subprocess.run(metal_compiler_command(cpath,binary),check=True,env=env,timeout=600)
    subprocess.run(['nice','-n','10',str(binary),'--threads','2','--gpu-build'],check=True,cwd=ROOT,env=env,capture_output=True,timeout=300)
    return binary


def metal_observer(bend,source,binary):
    compile_metal_observer(bend,source,binary)
    result = exercise(binary,True)
    assert len(result['observed_metal_command_ms']) == 10, result['observed_metal_command_ms']
    return {'diagnostic_only':True,**result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true')
    args = parser.parse_args()
    binary = ROOT/'build/bend/engine'
    version = 'existing executable' if args.skip_build else build(args.bend,binary,ROOT/'bend/engine.bend')
    report = {'scope':'Persistent component transport; full registry/world generator integration pending',
              'bend_version':version,'runs':[exercise(binary,False),exercise(binary,True)],
              'malformed_streams_rejected':malformed_streams(binary),'clean_eof':'pass'}
    report['cpu_gpu_grid_bytes_equal'] = report['runs'][0]['grids'] == report['runs'][1]['grids']
    if args.prove_metal:
        report['metal_observation'] = metal_observer(args.bend,ROOT/'bend/engine.bend',ROOT/'build/bend/engine-metal-probe')
        assert report['metal_observation']['grids'] == report['runs'][1]['grids']
    path = ROOT/'build/bend/engine-tests.json'
    path.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: persistent CPU/GPU worker, compressed streams and framing; {path}')


if __name__ == '__main__':
    main()
