#!/usr/bin/env python3
"""Validate resident Bend density lattices with an independent scalar oracle.

These are component checks, not region-generation performance measurements.
The top-level lattice intentionally approximates the graph between vertices;
registered op28 interpolation remains part of that graph at each sampled vertex.
"""
import argparse
import functools
import hashlib
import itertools
import json
import math
from pathlib import Path
import random
import re
import signal
import struct
import time

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_density import fixtures, request
from test_bend_registry_noise import fixture_wire
from test_bend_density import Oracle, ins
from test_bend_noise import f32

MASK = 0xffffffff
LOW, HIGH = 0x87654321, 0x80000001


def descriptor(program, minimum, maximum, h=4, v=8, low=LOW, high=HIGH):
    return (program, *minimum, *maximum, h, v, low, high)


def encoded(desc):
    return struct.pack('>11I', *(x & MASK for x in desc))


def layout(desc):
    pid, *rest = desc
    minimum, maximum, steps = rest[:3], rest[3:6], (desc[7], desc[8], desc[7])
    origin = tuple(a // s * s for a, s in zip(minimum, steps))
    counts = tuple(max(2, math.ceil((b-a)/s)+1) for a,b,s in zip(origin, maximum, steps))
    return origin, counts, steps


class Reference:
    def __init__(self, source):
        model = json.loads(json.dumps(source['registry_program']), parse_float=lambda x:f32(float(x)))
        self.model = model
        self.oracle = Oracle(model)

    @functools.lru_cache(maxsize=100000)
    def vertex(self, pid, point, low, high):
        return self.oracle.evaluate(self.model['programs'][pid], point, low, high)

    def query(self, q, desc):
        pid, x, y, z, low, high = q
        origin, counts, steps = layout(desc)
        p = (x,y,z)
        relative = [(a-b)/s for a,b,s in zip(p, origin, steps)]
        if pid != desc[0] or (low,high) != desc[-2:] or not all(0 <= t <= n-1 for t,n in zip(relative, counts)):
            return self.vertex(pid,p,low,high)
        indices = [min(math.floor(t), n-2) for t,n in zip(relative, counts)]
        fractions = [t-i for t,i in zip(relative, indices)]
        result = [0.] * 6
        for bits in itertools.product((0,1),repeat=3):
            point = tuple(a+(i+b)*s for a,i,b,s in zip(origin,indices,bits,steps))
            weight = math.prod(t if b else 1-t for b,t in zip(bits,fractions))
            for j,value in enumerate(self.vertex(pid,point,low,high)):
                result[j] += value*weight
        return result

    def verify(self, data, points, desc):
        assert len(data) == len(points)*24, (len(data),len(points))
        maximum = 0.
        for q, actual in zip(points,struct.iter_unpack('>6f',data)):
            for channel,(a,b) in enumerate(zip(actual,self.query(q,desc))):
                assert math.isfinite(a), (q,channel,a,b)
                error = abs(a-b)/max(1.,abs(b)); maximum=max(maximum,error)
                assert error < .0002, (q,channel,a,b,error,desc)
        return maximum


def points_for(desc):
    origin, counts, steps = layout(desc)
    pid = desc[0]; seed = desc[-2:]
    top = [a+(n-1)*s for a,n,s in zip(origin,counts,steps)]
    points = [(pid,*p,*seed) for p in itertools.product(*[(a,b) for a,b in zip(origin,top)])
              if all(-(1<<31)<=v<(1<<31) for v in p)]
    rng=random.Random(20261008)
    for _ in range(24):
        p=[rng.randint(a,b) for a,b in zip(desc[1:4],desc[4:7])]
        points.append((pid,*p,*seed))
    # Program and full-seed misses must use Bend's direct sampler, never a
    # stale/foreign lattice. Outside coordinates also keep the direct path.
    p=desc[1:4]
    points.extend([((pid+1)%3,*p,*seed),(pid,*p,seed[0]^1,seed[1]),(pid,*p,seed[0],seed[1]^1)])
    outside = min((1<<31)-1,top[0]+steps[0])
    if outside>top[0]:points.append((pid,outside,p[1],p[2],*seed))
    return points


def exercise(binary, profiles, gpu):
    worker=Worker(binary,gpu); dispatches=0; hashes=[]; rows=[]; maximum=0.; invalid_count=0
    def call(op,data=b'',status=0):
        nonlocal dispatches
        if status==0 and op in (12,13,14):dispatches+=1
        return worker.call(op,data,status=status)
    def create(desc):
        counts=layout(desc)[1]
        assert call(13,encoded(desc))==struct.pack('>4I',*counts,math.prod(counts))
    try:
        signal.alarm(240)
        assert call(13,b'',1)==struct.pack('>I',716)
        assert call(14,request([]),1)==struct.pack('>I',716)
        for name,source,path in profiles:
            signal.alarm(240)
            call(5,path_request(path)); call(11)
            assert call(14,request([]),1)==struct.pack('>I',718)
            reference=Reference(source)
            # Nonaligned negative origins, whole seed words, variable cell
            # sizes, signed-i32 extreme endpoints and distinct query ordering.
            grids=[descriptor(2,(-9,-61,23),(7,-29,41)),
                   descriptor(2,(29999991,57,-30000007),(30000007,73,-29999991),h=8,v=16),
                   descriptor(1,(-(1<<31),-5,(1<<31)-8),(-(1<<31)+7,5,(1<<31)-1),h=3,v=5),
                   descriptor(0,(7,-1,-9),(7,-1,-9),h=7,v=3,high=0xffffffff)]
            prepared=[]; queried=[]; checked=0
            for grid_index,desc in enumerate(grids):
                print(f'{"GPU" if gpu else "CPU"} {name}: grid {grid_index+1}/{len(grids)}',flush=True)
                start=time.perf_counter();create(desc);prepared.append((time.perf_counter()-start)*1000)
                points=points_for(desc); start=time.perf_counter(); data=call(14,request(points));queried.append((time.perf_counter()-start)*1000)
                maximum=max(maximum,reference.verify(data,points,desc));checked+=len(points);hashes.append(hashlib.sha256(data).hexdigest())
                assert call(14,request(points))==data
                reverse=call(14,request(points[::-1]));assert reverse==b''.join(data[i:i+24] for i in range(len(data)-24,-1,-24))
                assert call(14,request([]))==b''
            # Identical cells in overlapping aligned caches must give identical
            # samples regardless of which tile was requested first.
            a=descriptor(2,(-20,-64,-20),(12,-32,12));b=descriptor(2,(-8,-64,-8),(24,-32,24))
            common=[(2,x,y,z,LOW,HIGH) for x,y,z in itertools.product(range(-7,12,3),range(-63,-32,7),range(-7,12,4))]
            create(a);left=call(14,request(common));create(b);right=call(14,request(common))
            assert left==right,(name,'overlapping lattice seam')
            maximum=max(maximum,reference.verify(left,common,a)); checked+=len(common);hashes.append(hashlib.sha256(left).hexdigest())
            # Invalid layouts cannot replace a good resident grid.
            invalid=[b'',encoded(b)+b'\0',encoded(b)[:-1]]
            for index,value in [(0,3),(7,0),(8,0),(7,4097),(8,4097),(4,b[1]-1),(5,b[2]-1),(6,b[3]-1),(4,b[1]+4097)]:
                bad=list(b);bad[index]=value;invalid.append(encoded(bad))
            invalid.append(encoded(descriptor(2,(0,0,0),(1024,1024,1024),h=1,v=1)))
            for bad in invalid:assert call(13,bad,1)==struct.pack('>I',717);invalid_count+=1
            assert call(14,request(common))==right
            assert call(14,b'\0',1)==struct.pack('>I',603)
            truncated=path.parent/'lattice-bad.rbp';truncated.write_bytes(b'RBP1')
            assert call(5,path_request(truncated),1)==struct.pack('>I',703)
            assert call(14,request(common))==right
            # Valid profile replacement and successful numeric preparation
            # invalidate caches; rejected transport does not.
            call(11);assert call(14,request(common),1)==struct.pack('>I',718)
            call(5,path_request(path));assert call(14,request(common),1)==struct.pack('>I',716)
            call(11);assert call(14,request(common),1)==struct.pack('>I',718)
            rows.append({'name':name,'lattices':len(grids)+2,'validated_queries':checked,
                         'grid_prepare_host_ms':prepared,'mixed_query_host_ms':queried,
                         'repeat_reorder_empty':'pass','overlapping_tiles':'byte-identical',
                         'program_seed_outside_misses':'direct Bend sampler','rejected_request_retains_cache':'pass',
                         'profile_and_numeric_prepare_invalidation':'pass'})
            print(f'{"GPU" if gpu else "CPU"} {name}: {checked} checked lattice/fallback queries',flush=True)
        # Stress the bounded 4096-query path with cheap analytic coordinates.
        name,source,path=profiles[-1];call(5,path_request(path));call(11)
        desc=descriptor(2,(-8,-8,-8),(8,8,8));create(desc)
        many=[(2,i%17-8,(i//17)%17-8,(i//289)%17-8,LOW,HIGH) for i in range(4096)]
        data=call(14,request(many));maximum=max(maximum,Reference(source).verify(data,many,desc));hashes.append(hashlib.sha256(data).hexdigest())
        assert call(14,request(many+many[:1]),1)==struct.pack('>I',603)
        call(4);assert worker.process.wait(timeout=10)==0
        diagnostic=worker.process.stderr.read().decode()
        return {'mode':'gpu_required' if gpu else 'cpu','profiles':rows,'maximum_batch_queries':4096,
                'invalid_layouts':invalid_count,'requests_in_one_process':worker.id,'expected_dispatches':dispatches,
                'max_relative_or_absolute_error':maximum,
                'aggregate_sha256':hashlib.sha256(''.join(hashes).encode()).hexdigest(),
                'observed_metal_command_ms':[float(t) for t in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostic)]}
    finally:
        signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND);parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--profile',nargs=2,action='append',default=[],metavar=('JSON','WIRE'))
    args=parser.parse_args();binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(args.bend,binary,ROOT/'bend/engine.bend')
    folder=ROOT/'build/bend/lattice-profiles';generated,_=fixtures(folder)
    profiles=[generated[0]]
    for source,path in args.profile:
        source,path=Path(source),Path(path);profiles.append((source.parent.name,json.loads(source.read_text()),path))
    # Last profile is deliberately inexpensive for the maximum query batch.
    nodes=[ins(29,0),ins(29,1),ins(29,2),ins(12,1),ins(6,0,2)]
    source={'registry_program':{'noises':[],'points':[],'programs':[dict(nodes=nodes,roots=[0,1,2,3,4,1]) for _ in range(3)]}}
    path=folder/'analytic.rbp';path.write_bytes(fixture_wire(source,True));profiles.append(('analytic',source,path))
    report={'scope':'Resident top-level GPU density lattice and interpolation; no integrated terrain or complete-region benchmark',
            'vertex_capacity':1048576,'maximum_axis_span_blocks':4096,'cpu_workers':2,'process_nice':10,
            'runs':[exercise(binary,profiles,gpu) for gpu in (False,True)]}
    report['cpu_gpu_bytes_equal']=report['runs'][0]['aggregate_sha256']==report['runs'][1]['aggregate_sha256']
    if args.prove_metal:
        diagnostic=compile_metal_observer(args.bend,ROOT/'bend/engine.bend',ROOT/'build/bend/density-lattice-metal-probe')
        observed=exercise(diagnostic,profiles,True);times=observed['observed_metal_command_ms']
        assert len(times)==observed['expected_dispatches'],(len(times),observed['expected_dispatches'])
        assert all(t>0 for t in times)
        assert observed['aggregate_sha256']==report['runs'][1]['aggregate_sha256']
        report['metal_observation']={'diagnostic_only':True,'command_buffers':len(times),
                                    'device_ms':{'min':min(times),'max':max(times),'sum':sum(times)},'output_bytes_equal':True}
    report['profiles']=[{'name':name,'wire_sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for name,source,path in profiles]
    report['source_sha256']={name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in
        ('bend/density_lattice.bend','bend/registry_density.bend','bend/engine.bend','scripts/test_bend_density_lattice.py')}
    destination=ROOT/'build/bend/density-lattice-tests.json';destination.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: resident bounded lattices, independent interpolation and CPU/GPU queries; {destination}')


def timeout(_signal, _frame):
    raise TimeoutError('density lattice profile exceeded 240 seconds')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,timeout)
    main()
