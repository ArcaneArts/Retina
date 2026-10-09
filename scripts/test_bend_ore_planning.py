#!/usr/bin/env python3
"""Independent registered Bend attempt/vein-geometry CPU and GPU checks.

These are raw planning components. They do not yet filter by spatial biome,
rasterize masks or replay ore into generated blocks, and are not region timings.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import random
import re
import signal
import struct
import sys
import threading

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_ores import fixtures as ore_fixtures, metadata
from test_bend_noise import MASK, f32

MASK64 = (1 << 64) - 1


def signed(value):
    return value if value < 1 << 31 else value - (1 << 32)


class Random64:
    def __init__(self, state):
        self.state = state & MASK64

    def next(self):
        self.state = (self.state + 0x9e3779b97f4a7c15) & MASK64
        z = self.state
        z = ((z ^ (z >> 30)) * 0xbf58476d1ce4e5b9) & MASK64
        z = ((z ^ (z >> 27)) * 0x94d049bb133111eb) & MASK64
        return (z ^ (z >> 31)) & MASK

    def integer(self, low, high):
        return low + self.next() % (high - low + 1)

    def unit(self):
        return (self.next() >> 8) / 16777216

    def height(self, spec):
        span = spec['max'] - spec['min']
        if spec['triangle'] and spec['plateau'] < span:
            a = (span - spec['plateau']) // 2
            return spec['min'] + self.integer(0, a) + self.integer(0, span-a)
        return self.integer(spec['min'], spec['max'])


def anchor_seed(world, x, z, recipe):
    return (world ^ (x * 0x632be59bd9b4e019) ^ (z * 0x9e3779b97f4a7c15)
            ^ (recipe * 0x94d049bb133111eb)) & MASK64


def attempts(source, query):
    recipe, x, z, low, high = query
    ore = source['ores'][recipe]
    seed = anchor_seed(low | high << 32, x, z, recipe)
    rng = Random64(seed)
    if rng.next() % ore['rarity']:
        return []
    count = rng.integer(ore['count_min'], ore['count_max'])
    result = []
    for i in range(count):
        xx, zz = x * 16 + rng.integer(0, 15), z * 16 + rng.integer(0, 15)
        y = rng.height(ore['height'])
        local = seed ^ (((i + 1) * 0xd6e8feb86659fd93) & MASK64)
        result.append((i, xx, y, zz, local >> 32, local & MASK))
    return result


def rounded(value):
    return math.copysign(math.floor(abs(value) + .5), value)


def geometry(source, query):
    recipe, high, low = query
    ore = source['ores'][recipe]
    rng = Random64(low | high << 32)
    size = ore['size']
    if ore['scattered']:
        result = []
        for i in range(rng.integer(0, size)):
            spread = min(i, 7)
            values = [int(rounded(f32(f32(rng.unit() - rng.unit()) * spread))) for _ in range(3)]
            result.append((*values, rng.next()))
        return 1, result
    angle = f32(rng.unit() * f32(math.pi))
    dx = f32(f32(f32(math.sin(angle)) * size) / 8)
    dz = f32(f32(f32(math.cos(angle)) * size) / 8)
    a, b = rng.integer(-2, 0), rng.integer(-2, 0)
    result = []
    for i in range(size):
        t = f32(i / size)
        radius = f32(f32(f32(f32(f32(math.sin(f32(f32(math.pi)*t))) + 1) * rng.unit()) * size) / 32 + .5)
        result.append((f32(dx + f32(f32(dx * -2) * t)), f32(a + f32((b-a)*t)),
                       f32(dz + f32(f32(dz * -2) * t)), radius))
    pruned = []
    def squared(a, b): return f32(f32(a-b) ** 2)
    for x, y, z, r in result:
        inside = any(s > r and squared(s,r) > f32(f32(squared(x,a)+squared(y,b))+squared(z,c))
                     for a,b,c,s in result)
        pruned.append((x,y,z,0. if inside else r))
    return 0, pruned


def request(rows, words):
    return struct.pack('>I', len(rows)) + b''.join(struct.pack(f'>{words}I', *(v & MASK for v in row)) for row in rows)


def decode_attempts(raw, count):
    result, at = [], 0
    for _ in range(count):
        n, = struct.unpack_from('>I', raw, at); at += 4
        row = []
        for _ in range(n):
            i,x,y,z,high,low = struct.unpack_from('>6I',raw,at); at += 24
            row.append((i,signed(x),signed(y),signed(z),high,low))
        result.append(row)
    assert at == len(raw)
    return result


def decode_geometry(raw, count):
    result, at = [], 0
    for _ in range(count):
        kind,n = struct.unpack_from('>2I',raw,at); at += 8
        assert kind in (0,1)
        row = []
        for _ in range(n):
            row.append(struct.unpack_from('>4f' if kind == 0 else '>3iI',raw,at)); at += 16
        result.append((kind,row))
    assert at == len(raw)
    return result


def fixtures(folder):
    good,bad = ore_fixtures(folder)
    base = good[0][1]
    for name, height in [
        ('uniform_full',dict(min=-(1<<31),max=(1<<31)-1,triangle=False,plateau=0)),
        ('triangle',dict(min=-64,max=320,triangle=True,plateau=0)),
        ('flat',dict(min=17,max=17,triangle=True,plateau=0)),
        ('plateau',dict(min=-10,max=10,triangle=True,plateau=21))]:
        source = copy.deepcopy(base)
        source['ores'][0].update(height=height,count_min=7,count_max=7,rarity=1)
        path = folder/(name+'.rbp'); path.write_bytes(fixture_wire(source));good.append((name,source,path))
    source = copy.deepcopy(base)
    source['ores'][0].update(count_min=1024,count_max=1024,rarity=1)
    path=folder/'maximum_count.rbp';path.write_bytes(fixture_wire(source));good.append(('maximum_count',source,path))
    return good


def exercise(binary,profiles,gpu):
    worker = Worker(binary,gpu); diagnostics = bytearray()
    reader = threading.Thread(target=lambda:diagnostics.extend(worker.process.stderr.read()),daemon=True);reader.start()
    dispatches = checked = pieces = regular = scattered = pruned = 0
    digest = hashlib.sha256();attempt_digest=hashlib.sha256();records=[];maximum_error=0.
    def call(op, data=b'', error=0):
        nonlocal dispatches
        signal.alarm(300);raw=worker.call(op,data,status=int(bool(error)))
        if error: assert raw==struct.pack('>I',error),(op,error,raw)
        elif op in (47,48): dispatches+=1
        return raw
    try:
        call(47,request([],5),754);call(48,request([],3),754)
        for name,source,path in profiles:
            print('checking','GPU' if gpu else 'CPU',name,flush=True)
            call(5,path_request(path));call(11);call(19);call(21);call(30)
            ids=list(range(len(source.get('ores',[]))));queries=[]
            coords=[(0,0),(-1,-1),(1800000,-1800000),(-(1<<27),(1<<27)-1),((1<<27)-1,-(1<<27))]
            seeds=[0,1,0x7fffffff00000001,0x80000000ffffffff,MASK64]
            for id in ids:
                queries.extend((id,x,z,world&MASK,world>>32) for x,z in coords for world in seeds)
            shape_queries=[];profile_checked=profile_pieces=0
            for start in range(0,len(queries),256):
                part=queries[start:start+256];raw=call(47,request(part,5));actual=decode_attempts(raw,len(part))
                expected=[attempts(source,q) for q in part]
                assert actual==expected,(name,start,next(((q,a,b) for q,a,b in zip(part,actual,expected) if a!=b),None))
                attempt_digest.update(raw);digest.update(raw)
                reverse=decode_attempts(call(47,request(list(reversed(part)),5)),len(part))
                assert list(reversed(reverse))==actual,'request ordering changes globally anchored attempts'
                for query,row in zip(part,actual):
                    profile_checked+=len(row)
                    shape_queries.extend((query[0],a[4],a[5]) for a in row[:2])
            rng=random.Random(123456789)
            shape_queries.extend((id,rng.randrange(1<<32),rng.randrange(1<<32)) for id in ids for _ in range(17))
            for start in range(0,len(shape_queries),256):
                part=shape_queries[start:start+256];raw=call(48,request(part,3));actual=decode_geometry(raw,len(part))
                for query,(kind,row) in zip(part,actual):
                    want_kind,want=geometry(source,query)
                    assert kind==want_kind and len(row)==len(want),(name,query,kind,len(row),want_kind,len(want))
                    if kind==1:
                        assert row==want,(name,query,row,want);scattered+=len(row)
                        assert all(abs(v)<=7 for item in row for v in item[:3])
                    else:
                        regular+=len(row);pruned+=sum(item[3]==0 for item in row)
                        for a,b in zip(row,want):
                            assert all(math.isfinite(v) for v in a)
                            error=max((abs(x-y) for x,y in zip(a,b)),default=0.)
                            maximum_error=max(maximum_error,error)
                            assert error<=0.000008,(name,query,a,b,error)
                    profile_pieces+=len(row)
                digest.update(raw)
                assert call(48,request(part,3))==raw,'geometry is not repeatable'
            assert call(47,request([],5))==b'' and call(48,request([],3))==b''
            if ids:
                # Maximum batch count without creating a 256*1024 diagnostic payload.
                zero=next((i for i,o in enumerate(source['ores']) if o['count_max']==0),ids[0])
                q=(zero,0,0,0,0)
                actual=decode_attempts(call(47,request([q]*256,5)),256)
                assert actual==[attempts(source,q)]*256
                g=(ids[0],MASK,MASK);assert decode_geometry(call(48,request([g]*256,3)),256)==[decode_geometry(call(48,request([g],3)),1)[0]]*256
                for op,data in [(47,request([(len(ids),0,0,0,0)],5)),(47,request([(0,1<<27,0,0,0)],5)),
                    (47,request([(0,-(1<<27)-1,0,0,0)],5)),(48,request([(len(ids),0,0)],3))]:call(op,data,603)
            for op in (47,48):
                for data in (b'',b'\0',struct.pack('>I',257),request([],5)+b'\0'):call(op,data,603)
            before=call(45,metadata(0));call(9);call(15);assert call(45,metadata(0))==before
            call(5,path_request(path));call(47,request([],5),754);call(48,request([],3),754)
            records.append(dict(name=name,attempts=profile_checked,pieces=profile_pieces));checked+=profile_checked;pieces+=profile_pieces
        call(4);assert worker.process.wait(timeout=20)==0;reader.join(timeout=20);assert not reader.is_alive()
        text=diagnostics.decode(errors='replace');assert not any(x in text for x in ('ERR_', 'GPU execution failed')),text
        return dict(gpu_required=gpu,profiles=records,attempts_checked=checked,pieces_checked=pieces,
            regular_spheres=regular,contained_spheres=pruned,scattered_points=scattered,
            maximum_geometry_error=maximum_error,attempt_sha256=attempt_digest.hexdigest(),sha256=digest.hexdigest(),
            expected_gpu_dispatches=dispatches,metal_commands_ms=[float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',text)])
    finally:
        signal.alarm(0)
        failed=sys.exc_info()[0] is not None
        try:
            worker.close()
        except BaseException:
            if not failed:raise
        finally:
            reader.join(timeout=5)
            if failed:print(diagnostics.decode(errors='replace'),flush=True)
            (ROOT/'build/bend'/('ore-planning-gpu-stderr.log' if gpu else 'ore-planning-cpu-stderr.log')).write_bytes(diagnostics)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--metal-probe',type=Path)
    parser.add_argument('--profile',nargs=2,action='append',default=[],metavar=('JSON','RBP'))
    parser.add_argument('--output',type=Path);args=parser.parse_args()
    binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    folder=ROOT/'build/bend/ore-planning-profiles';folder.mkdir(parents=True,exist_ok=True)
    profiles=fixtures(folder)
    for source,wire in args.profile:profiles.append((Path(source).parent.name,json.loads(Path(source).read_text()),Path(wire)))
    report={'scope':'Raw registered Bend ore attempts and local vein geometry; spatial membership, rasterization and block replay remain pending',
        'runs':[exercise(binary,profiles,False),exercise(binary,profiles,True)],
        'dispatch_accounting':'CPU counters are logical batches; only observer diagnostics establish actual Metal work.'}
    assert report['runs'][0]['attempt_sha256']==report['runs'][1]['attempt_sha256']
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/ore-planning-metal-probe'
        if args.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        proof=exercise(probe,profiles,True);assert len(proof['metal_commands_ms'])==proof['expected_gpu_dispatches']
        assert proof['sha256']==report['runs'][1]['sha256']
        report['actual_metal_proof']=proof;report['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest();report['bend_version']='2.0.36'
    output=args.output or ROOT/'build/bend/ore-planning-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('ore planning test timeout')))
    main()
