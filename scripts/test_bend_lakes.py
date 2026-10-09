#!/usr/bin/env python3
"""Verify loaded Bend surface-lake budgets and sparse seeded candidates.

Candidate planning runs in Bend on CPU/GPU. Bank probing, contained basins and
final fluid placement remain subsequent work. This is component correctness
QA, not a complete world generator or a throughput benchmark.
"""
import argparse
from collections import Counter
import copy
import hashlib
import json
import math
from pathlib import Path
import random
import re
import signal
import struct
import subprocess
import threading

from test_bend_compression import ROOT,DEFAULT_BEND,build
from test_bend_engine import Worker,compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_geology import fixtures as geology_fixtures,metadata_request
from test_bend_density import Oracle,ins
from test_bend_registry_climate import projected
from test_bend_climate import expected
from test_bend_surface import query_bytes,tile_bytes
from test_bend_generated_chunks import request as chunk_request
from test_bend_noise import MASK,f32,mix_hash,signed


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True);base=copy.deepcopy(geology_fixtures(folder/'geology')[0][0][1])
    for i,b in enumerate(base['biomes']):
        b.update(flags=0,lakes=[1.,float(i)],lake_barrier=1, lake_water_barrier=4)
    base['climate_targets']=[dict(min=[-1. if i==0 else 1.,0.,0.,0.],max=[-1. if i==0 else 1.,0.,0.,0.],weirdness=[0.,0.],depth=[0.,0.],offset=0.,biome=i) for i in range(2)]
    base['registry_program']['programs'][0]=dict(nodes=[ins(29,0),ins(0,p=(.00390625,0,0,0)),ins(6,0,1),ins(0)],roots=[2,3,3,3,3,3])
    good=[];bad=[]
    def add(name,change=lambda _:None):
        s=copy.deepcopy(base);change(s);p=folder/(name+'.rbp');p.write_bytes(fixture_wire(s,True));good.append((name,s,p))
    add('mixed')
    add('none',lambda s:[b.update(lakes=[0.,0.]) for b in s['biomes']])
    add('partial',lambda s:[b.update(lakes=[.25,.05] if i==0 else [.75,.3]) for i,b in enumerate(s['biomes'])])
    add('lava_priority',lambda s:[b.update(lakes=[.1,.8]) for b in s['biomes']])
    add('missing',lambda s:[b.pop(k,None) for b in s['biomes'] for k in ('lakes','lake_barrier','lake_water_barrier')])
    add('no_targets',lambda s:s.update(climate_targets=[]))
    def invalid(name,change):
        s=copy.deepcopy(base);change(s['biomes'][0]);p=folder/('bad_'+name+'.rbp');p.write_bytes(fixture_wire(s,True));bad.append((name,p))
    for i,v in enumerate([[],[0.],[0.,0.,0.],None,'bad',[1.1,0.],[0.,-.1],[True,0.],[float('nan'),0.]]):invalid('chance_'+str(i),lambda b,v=v:b.update(lakes=v))
    for key in ('lake_barrier','lake_water_barrier'):
        for i,v in enumerate([-1,6,1.,None,True]):invalid(key+str(i),lambda b,k=key,v=v:b.update({k:v}))
    for i,v in enumerate([-1,1.,None,True]):invalid('flags'+str(i),lambda b,v=v:b.update(flags=v))
    return good,bad


def settings(source,index):
    b=source['biomes'][index]
    return [*map(f32,b.get('lakes',[0.,0.])),b.get('lake_barrier',0),b.get('lake_water_barrier',0),b.get('flags',0)]


class Reference:
    def __init__(self,source):
        self.source=source;self.model=json.loads(json.dumps(source['registry_program']),parse_float=lambda v:f32(float(v)));self.oracle=Oracle(self.model);self.targets=projected(source)
    def candidate(self,q):
        x,z,lo,hi=q;cx,cz=x//128,z//128
        h=mix_hash((((cx&MASK)*2654435769)^((cz&MASK)*2246822507)^((lo+8647)&MASK)^mix_hash(hi))&MASK)
        offsets=[f32(f32(.25+f32(v/510.))*128.) for v in (h&255,(h>>8)&255)]
        p=(cx*128+offsets[0],0.,cz*128+offsets[1]);axes=self.oracle.evaluate(self.model['programs'][0],p,lo,hi)
        choice=expected(self.targets,list(map(f32,axes)),0)
        if choice is None:return dict(present=0,cell=(cx,cz),point=(p[0],p[2]),radius=0.,biome=MASK,eligible=0,lava=0,barrier=0,flags=0,chance=0.)
        id=choice[0]['biome'];total,lava,barrier,water,flags=settings(self.source,id);chance=f32(((h>>16)&65535)/65535.)
        is_lava=int(chance<lava)
        return dict(present=1,cell=(cx,cz),point=(p[0],p[2]),radius=(24+(h&7))*(.25 if is_lava else 1.),biome=id,eligible=int(is_lava or chance<total),lava=is_lava,barrier=barrier if is_lava else water,flags=flags,chance=chance)


def decode(raw):
    assert len(raw)%56==0
    return [dict(present=r[0],cell=(signed(r[1]),signed(r[2])),point=(r[3]+r[4],r[5]+r[6]),radius=r[7],biome=r[8],eligible=r[9],lava=r[10],barrier=r[11],flags=r[12],chance=r[13]) for r in struct.iter_unpack('>3I5f5If',raw)]


def exercise(binary,profiles,bad,gpu):
    worker=Worker(binary,gpu);diagnostics=bytearray();reader=threading.Thread(target=lambda:diagnostics.extend(worker.process.stderr.read()),daemon=True);reader.start()
    dispatches=checked=metadata=0;records=[];hashes=[];classes=Counter()
    def call(op,data=b'',error=0):
        nonlocal dispatches
        signal.alarm(300);reply=worker.call(op,data,status=int(error!=0))
        if error:assert reply==struct.pack('>I',error),(op,error,reply)
        else:dispatches+={13:1,16:1,22:2 if gpu else 1,42:1}.get(op,0)
        return reply
    def prepare(path):
        call(5,path_request(path));call(11);call(19);call(21);call(30)
    try:
        call(41,metadata_request([]),748);call(42,query_bytes([]),749)
        for name,source,path in profiles:
            print('checking','GPU' if gpu else 'CPU',name,flush=True)
            prepare(path);call(42,query_bytes([]),749);count=len(source['biomes']);indices=list(range(count))+[count-1,0]
            raw_settings=b''.join(call(41,metadata_request(indices[i:i+256])) for i in range(0,len(indices),256))
            assert list(struct.iter_unpack('>2f3I',raw_settings))==[tuple(settings(source,i)) for i in indices]
            metadata+=len(indices)
            for raw in (b'',metadata_request([count]),metadata_request([])+b'\0',struct.pack('>I',257)):call(41,raw,603)
            assert call(41,metadata_request([]))==b''
            call(9);rng=random.Random(91871);lo,hi=0x87654321,0x80000001
            coords=[(rng.randrange(-100000,100001),rng.randrange(-100000,100001)) for _ in range(192)]
            coords += [(x,z) for x in (-2147483648,-30000000,-257,-129,-128,-1,0,1,127,128,255,29999999,2147483647) for z in (-2147483648,-1,0,128,2147483647)]
            qs=[(x,z,lo,hi) for x,z in coords]+[(16,-12,lo^1,hi),(16,-12,lo,hi^1)];ref=Reference(source)
            raw=call(42,query_bytes(qs));rows=decode(raw)
            for q,row in zip(qs,rows):
                want=ref.candidate(q)
                for key in row:
                    if key=='point':assert all(abs(a-b)<1e-5 for a,b in zip(row[key],want[key])),(name,q,key,row[key],want[key])
                    else:assert row[key]==want[key],(name,q,key,row[key],want[key])
                classes[(row['present'],row['eligible'],row['lava'])]+=1;checked+=1
            assert call(42,query_bytes(qs[::-1]))==b''.join(raw[i:i+56] for i in range(len(raw)-56,-1,-56))
            assert call(42,query_bytes(qs))==raw
            assert call(42,query_bytes([qs[0]]*4096))==raw[:56]*4096
            assert call(42,query_bytes([]))==b''
            # Any point in the same global cell describes the same candidate.
            within=[(0,0,lo,hi),(127,127,lo,hi),(-1,-1,lo,hi),(-128,-128,lo,hi)]
            cells=call(42,query_bytes(within));assert cells[:56]==cells[56:112] and cells[112:168]==cells[168:]
            for body in (b'',query_bytes(qs[:1])+b'\0',struct.pack('>I',4097)):call(42,body,603)
            assert call(42,query_bytes(qs))==raw
            if name=='mixed':
                call(15);call(25);desc=call(18,tile_bytes((-1,-1,18,18,lo,hi)));call(13,desc);call(16,tile_bytes((0,0,16,16,lo,hi)));call(22)
                chunk=chunk_request(0,0,lo,hi,5023);saved=call(26,chunk)
                assert call(42,query_bytes(qs))==raw;call(41,metadata_request(indices));assert call(26,chunk)==saved
            call(30);assert call(42,query_bytes(qs))==raw
            call(5,path_request(path));call(41,metadata_request([]),748);call(42,query_bytes([]),749)
            digest=hashlib.sha256(raw_settings+raw).hexdigest();hashes.append(digest);records.append(dict(name=name,queries=len(qs),metadata=len(indices),sha256=digest))
        for name,path in bad:
            if name=='chance_8':call(5,path_request(path),701);continue
            call(5,path_request(path));call(11);call(19);call(21);call(30,error=736);call(41,metadata_request([]),748)
        call(4);assert worker.process.wait(timeout=10)==0;reader.join(timeout=5);assert not reader.is_alive()
        return dict(gpu_required=gpu,profiles=records,candidates_checked=checked,metadata_checked=metadata,classes={str(k):v for k,v in sorted(classes.items())},invalid_profiles=len(bad),expected_gpu_dispatches=dispatches,metal_commands_ms=[float(x) for x in re.findall(rb'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostics)],sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest())
    finally:signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--metal-probe',type=Path);parser.add_argument('--profile',nargs=2,action='append');args=parser.parse_args()
    profiles,bad=fixtures(ROOT/'build/bend/lake-profiles')
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    report=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,bad,False),exercise(binary,profiles,bad,True)])
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/lake-metal-probe'
        if args.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        result=exercise(probe,profiles,bad,True);assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches'];assert result['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=result
        report['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest()
    report['bend_version']=subprocess.check_output([str(DEFAULT_BEND),'version'],text=True).strip()
    output=ROOT/'build/bend/lake-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('lake test timeout')))
    main()
