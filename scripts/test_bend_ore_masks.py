#!/usr/bin/env python3
"""Independently check pure Bend local ore-union masks on CPU and actual GPU.

Masks remain a component: spatial biome filtering and resident block replay are
not implemented by this harness, and response times are not region throughput.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
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
from test_bend_ore_planning import request, decode_geometry, geometry, MASK64
from test_bend_noise import f32


def fixtures(folder):
    good, _ = ore_fixtures(folder)
    for name, changes in [('tiny', dict(size=1)),
                          ('large_scattered', dict(size=64, scattered=True))]:
        source = copy.deepcopy(good[0][1]);source['ores'][0].update(changes)
        path = folder/(name+'.rbp');path.write_bytes(fixture_wire(source))
        good.append((name, source, path))
    return good


def decode(raw, count):
    records, at = [], 0
    for _ in range(count):
        start = at
        kind,x,y,z,nx,ny,nz,n = struct.unpack_from('>I3i4I', raw, at);at += 32
        assert kind in (0,1)
        size = nx*ny*nz
        if kind == 0:
            assert nx <= 32 and ny <= 16 and nz <= 32
            assert n == (size+31)//32 and n <= 512
            payload = struct.unpack_from(f'>{n}I', raw, at);at += n*4
            if size%32:assert payload[-1] >> (size%32) == 0, 'nonzero mask padding'
        else:
            assert n <= 64 and nx <= 15 and ny <= 15 and nz <= 15
            payload = [struct.unpack_from('>3iI',raw,at+i*16) for i in range(n)];at += n*16
        if n == 0:assert (x,y,z,nx,ny,nz) == (0,0,0,0,0,0)
        records.append(dict(kind=kind,origin=(x,y,z),dimensions=(nx,ny,nz),payload=payload,raw=raw[start:at]))
    assert at == len(raw), 'trailing mask bytes'
    return records


def reference_bounds(kind, pieces):
    if kind == 0:
        pieces = [p for p in pieces if p[3] > 0]
        low = [min(f32(p[a]-p[3]) for p in pieces) for a in range(3)] if pieces else []
        high = [max(f32(p[a]+p[3]) for p in pieces) for a in range(3)] if pieces else []
    else:
        low = [min(p[a] for p in pieces) for a in range(3)] if pieces else []
        high = [max(p[a] for p in pieces) for a in range(3)] if pieces else []
    if not pieces:return (0,0,0),(0,0,0)
    origin = tuple(math.floor(v) for v in low)
    return origin,tuple(math.floor(v)-origin[a]+1 for a,v in enumerate(high))


def sphere_distance(point, sphere):
    inverse = f32(1/sphere[3])
    d = [f32(f32(point[a]-sphere[a])*inverse) for a in range(3)]
    squares = [f32(v*v) for v in d]
    return f32(f32(squares[1]+squares[2])+squares[0])


def check_mask(record, kind, pieces):
    origin, dimensions = reference_bounds(kind, pieces)
    assert record['kind'] == kind and record['origin'] == origin and record['dimensions'] == dimensions
    if kind == 1:
        assert record['payload'] == pieces, 'scattered order or exposure-random word changed'
        return 0,0,0,len(pieces)
    nx,ny,nz = dimensions
    live = [p for p in pieces if p[3] > 0]
    ambiguous = hits = 0
    for i in range(nx*ny*nz):
        point = (origin[0]+i%nx+.5,origin[1]+i//(nx*nz)+.5,origin[2]+i//nx%nz+.5)
        minimum = min(sphere_distance(point,p) for p in live)
        actual = (record['payload'][i//32] >> (i%32)) & 1
        hits += actual
        if abs(minimum-1) <= 0.00001:
            ambiguous += 1
        else:
            assert actual == int(minimum < 1), (point,minimum,actual)
    return nx*ny*nz,hits,ambiguous,0


def exercise(binary, profiles, gpu):
    worker = Worker(binary,gpu);diagnostics = bytearray()
    reader = threading.Thread(target=lambda:diagnostics.extend(worker.process.stderr.read()),daemon=True);reader.start()
    records=[];digest=hashlib.sha256();dispatches=voxels=hits=ambiguous=scattered=veins=maximum_bytes=0
    maximum_error=0.
    def call(op, data=b'', error=0):
        nonlocal dispatches, maximum_bytes
        signal.alarm(300);raw=worker.call(op,data,status=int(bool(error)))
        if error:assert raw == struct.pack('>I',error),(op,error,raw)
        elif op in (48,49):
            dispatches += 1
            if op == 49:maximum_bytes=max(maximum_bytes,len(raw))
        return raw
    try:
        call(49,request([],3),754)
        for name,source,path in profiles:
            print('checking','GPU' if gpu else 'CPU',name,flush=True)
            call(5,path_request(path));call(11);call(19);call(21);call(30)
            seeds=[0,1,MASK64,1<<63,0x123456789abcdef0,0x9e3779b97f4a7c15,0x80000000ffffffff]
            queries=[(id,seed>>32,seed&0xffffffff) for id in range(len(source.get('ores',[]))) for seed in seeds]
            profile_voxels=profile_hits=profile_scattered=0
            for start in range(0,len(queries),64):
                part=queries[start:start+64]
                shapes=decode_geometry(call(48,request(part,3)),len(part))
                raw=call(49,request(part,3));actual=decode(raw,len(part))
                for query,record,(kind,pieces) in zip(part,actual,shapes):
                    want_kind,want=geometry(source,query)
                    assert kind==want_kind and len(pieces)==len(want)
                    if kind == 1:assert pieces == want
                    else:
                        error=max((abs(a-b) for p,q in zip(pieces,want) for a,b in zip(p,q)),default=0.)
                        maximum_error=max(maximum_error,error);assert error <= .000008
                    v,h,a,s=check_mask(record,kind,pieces)
                    voxels+=v;hits+=h;ambiguous+=a;scattered+=s;veins+=1
                    profile_voxels+=v;profile_hits+=h;profile_scattered+=s
                digest.update(raw)
                assert call(49,request(part,3))==raw,'mask is not repeatable'
                reverse=decode(call(49,request(list(reversed(part)),3)),len(part))
                assert [r['raw'] for r in reversed(reverse)]==[r['raw'] for r in actual],'request order changed masks'
            assert call(49,request([],3))==b''
            if queries:
                q=queries[0];single=call(49,request([q],3))
                assert call(49,request([q]*256,3))==single*256,'maximum batch changed a mask'
                before=call(45,metadata(0));call(9);call(15)
                assert call(45,metadata(0))==before and call(49,request([q],3))==single
                call(49,request([(len(source['ores']),0,0)],3),603)
            for body in (b'',b'\0',struct.pack('>I',257),request([],3)+b'\0',struct.pack('>2I',1,0)):
                call(49,body,603)
            records.append(dict(name=name,veins=len(queries),mask_voxels=profile_voxels,set_voxels=profile_hits,scattered_points=profile_scattered))
            call(5,path_request(path));call(49,request([],3),754)
        call(4);assert worker.process.wait(timeout=20)==0;reader.join(timeout=20);assert not reader.is_alive()
        text=diagnostics.decode(errors='replace');assert not any(s in text for s in ('ERR_','GPU execution failed')),text
        return dict(gpu_required=gpu,profiles=records,veins_checked=veins,mask_voxels_checked=voxels,set_voxels=hits,
            ambiguous_boundary_voxels=ambiguous,scattered_points=scattered,maximum_geometry_error=maximum_error,
            maximum_batch_bytes=maximum_bytes,sha256=digest.hexdigest(),expected_gpu_dispatches=dispatches,
            metal_commands_ms=[float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',text)])
    finally:
        signal.alarm(0);failed=sys.exc_info()[0] is not None
        try:worker.close()
        except BaseException:
            if not failed:raise
        finally:
            reader.join(timeout=5)
            if failed:print(diagnostics.decode(errors='replace'),flush=True)
            (ROOT/'build/bend'/('ore-masks-gpu-stderr.log' if gpu else 'ore-masks-cpu-stderr.log')).write_bytes(diagnostics)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--metal-probe',type=Path)
    parser.add_argument('--profile',nargs=2,action='append',default=[]);parser.add_argument('--output',type=Path);args=parser.parse_args()
    binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    folder=ROOT/'build/bend/ore-mask-profiles';folder.mkdir(parents=True,exist_ok=True);profiles=fixtures(folder)
    for source,wire in args.profile:profiles.append((Path(source).parent.name,json.loads(Path(source).read_text()),Path(wire)))
    report=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,False),exercise(binary,profiles,True)],
        boundary_policy='Float classifications within 0.00001 of the unit sphere are recorded as ambiguous; all other voxels must match the independent F32 union. Repeat and reordered outputs must match exactly within each backend.',
        dispatch_accounting='CPU counts are logical batches; only observed command buffers establish actual Metal work.')
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/ore-masks-metal-probe'
        if args.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        proof=exercise(probe,profiles,True);assert len(proof['metal_commands_ms'])==proof['expected_gpu_dispatches']
        assert proof['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=proof
        report['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest();report['bend_version']='2.0.36'
    report['test_script_sha256']={name:hashlib.sha256((ROOT/'scripts'/name).read_bytes()).hexdigest() for name in ('test_bend_ore_masks.py','test_bend_ore_planning.py','test_bend_engine.py','test_bend_compression.py')}
    output=args.output or ROOT/'build/bend/ore-mask-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('ore mask test timeout')))
    main()
