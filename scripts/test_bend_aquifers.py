#!/usr/bin/env python3
"""Verify registered Bend aquifer fields and bounded preliminary surface search.

This tests pure Bend CPU/GPU graph evaluation, not fluid placement or a complete
world generator. Independent scalar equations and exported registry profiles
are compared before integrating these fields into resident fluid centers.
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
import threading

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_caves import fixtures as cave_fixtures, encode
from test_bend_density import ins, Oracle
from test_bend_noise import MASK, f32
from test_bend_surface import tile_bytes
from test_bend_generated_chunks import request as chunk_request


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True)
    source=copy.deepcopy(cave_fixtures(folder/'caves')[0][1])
    programs=source['registry_program']['programs'];base=len(programs)
    programs += [
        dict(nodes=[ins(29,0),ins(0,p=(.03125,0,0,0)),ins(6,0,1),ins(29,1),ins(0,p=(.015625,0,0,0)),ins(6,3,4),ins(4,2,5),ins(29,2),ins(0,p=(.0625,0,0,0)),ins(6,7,8)],roots=[6,9]),
        dict(nodes=[ins(29,1),ins(0,p=(.125,0,0,0)),ins(6,0,1)],roots=[2]),
        dict(nodes=[ins(29,0),ins(0,p=(.0625,0,0,0)),ins(6,0,1),ins(29,2),ins(0,p=(.03125,0,0,0)),ins(6,3,4),ins(5,2,5)],roots=[6]),
        dict(nodes=[ins(29,0),ins(29,2),ins(4,0,1),ins(0,p=(.00390625,0,0,0)),ins(6,2,3)],roots=[4]),
        dict(nodes=[ins(29,0),ins(0,p=(.03125,0,0,0)),ins(6,0,1),ins(29,2),ins(0,p=(.015625,0,0,0)),ins(6,3,4),ins(4,2,5),ins(0,p=(9.5,0,0,0)),ins(4,6,7),ins(29,1),ins(5,8,9),ins(0,p=(48,0,0,0))],roots=[10,11])]
    source['registry_program']['aquifer']=dict(enabled=True,program=base,surface=[-32,4,0])
    result=[];bad=[]
    def good(name,s):
        path=folder/(name+'.rbp');path.write_bytes(fixture_wire(s,True));result.append((name,s,path))
    good('analytic',source)
    s=copy.deepcopy(source);s['registry_program']['aquifer']['surface']=[-31,3,0];good('non_power_step',s)
    s=copy.deepcopy(source);s['registry_program']['aquifer']['surface'][2]=1
    s['registry_program']['programs'][base+4]['roots']=[8];good('explicit_height',s)
    s=copy.deepcopy(source);s['registry_program']['aquifer']['enabled']=False;good('disabled',s)
    s=copy.deepcopy(source);del s['registry_program']['aquifer'];good('absent',s)
    # Zero/constant fields exercise no-positive-density and world ceiling bounds.
    s=copy.deepcopy(source);s['registry_program']['programs'][base+4]=dict(nodes=[ins(0,p=(-1,0,0,0)),ins(0,p=(1e9,0,0,0))],roots=[0,1]);good('empty_search',s)
    s=copy.deepcopy(source);s['registry_program']['programs'][base+4]=dict(nodes=[ins(0,p=(1,0,0,0)),ins(0,p=(1e9,0,0,0))],roots=[0,1]);good('ceiling_search',s)
    # Independent registered noise uses both seed words and distant coordinates.
    s=copy.deepcopy(source)
    s['registry_program']['noises'].append(dict(frequency=.00390625,amplitude=.7,horizontal_scale=1.,salt=92717,coefficients=[1.,.5,.25]))
    ni=len(s['registry_program']['noises'])-1
    s['registry_program']['programs'][base]=dict(nodes=[ins(0),ins(1,0,0,0,(ni,1.,1.,0.)),ins(18,1)],roots=[1,2]);good('seeded_noise',s)
    def invalid(name,change):
        s=copy.deepcopy(source);change(s['registry_program']['aquifer'])
        p=folder/('bad_'+name+'.rbp');p.write_bytes(fixture_wire(s,True));bad.append((name,p))
    for key,value in [('enabled','yes'),('program',-1),('program',base+1),('program',1.5),('surface',[-32,0,0]),('surface',[-32,4097,0]),('surface',[-32,4,2]),('surface',[-32,4]),('surface',[1<<31,4,0])]:
        invalid(key+'_'+str(value),lambda a,k=key,v=value:a.update({k:v}))
    invalid('missing_enabled',lambda a:a.pop('enabled'))
    invalid('missing_program',lambda a:a.pop('program'))
    invalid('missing_surface',lambda a:a.pop('surface'))
    def graph_bad(name,change):
        s=copy.deepcopy(source);change(s['registry_program']['programs'])
        p=folder/('bad_'+name+'.rbp');p.write_bytes(fixture_wire(s,True));bad.append((name,p))
    graph_bad('flood_roots',lambda ps:ps[base].update(roots=[6]))
    graph_bad('surface_roots',lambda ps:ps[base+4].update(roots=[10]))
    graph_bad('empty_roots',lambda ps:ps[base+1].update(roots=[]))
    graph_bad('root_range',lambda ps:ps[base+1].update(roots=[100]))
    graph_bad('material_opcode',lambda ps:ps[base+1]['nodes'][0].update(op=45))
    graph_bad('forward_dependency',lambda ps:ps[base+1]['nodes'][0].update(op=4,a=1,b=1))
    graph_bad('nonfinite_parameter',lambda ps:ps[base+1]['nodes'][1].update(p=[float('nan'),0,0,0]))
    return result,bad


def expected(source,query):
    a=source['registry_program'].get('aquifer')
    if not a or not a['enabled']:return [0.]*6
    x,y,z,lo,hi=query;model=source['registry_program'];oracle=Oracle(model);programs=model['programs'];base=a['program']
    def evaluate(pid,point):return oracle.evaluate(programs[pid],point,lo,hi)
    flood=evaluate(base,(x,y,z));values=flood[:2]+[evaluate(base+i,(x,y,z))[0] for i in (1,2,3)]
    bottom,step,explicit=a['surface'];value=evaluate(base+4,(x,0,z))
    if explicit:height=math.floor(value[0])
    else:
        top=math.floor(min(value[1],source['geology_min_y']+source['geology_height']-1)/step)*step
        height=next((yy for yy in range(top,max(bottom,source['geology_min_y'])-1,-step) if evaluate(base+4,(x,yy,z))[0]>0),bottom)
    return values+[height]


def exercise(binary,profiles,bad,gpu):
    worker=Worker(binary,gpu)
    diagnostics=[]
    def drain():
        for line in iter(worker.process.stderr.readline,b''):diagnostics.append(line)
    reader=threading.Thread(target=drain,daemon=True);reader.start()
    dispatches=0;scalars=0;maximum=0.;hashes=[];records=[]
    def call(op,data=b'',status=0):
        nonlocal dispatches
        signal.alarm(300)
        reply=worker.call(op,data,status=int(status!=0))
        if status:assert reply==struct.pack('>I',status),(op,reply,status)
        if status==0:dispatches+={13:1,16:1,22:2 if gpu else 1,31:1,33:2,35:1,38:1}.get(op,0)
        return reply
    def prepare(path):
        call(5,path_request(path));call(11);call(19);call(21);call(30)
    try:
        call(37,status=743);call(38,encode([]),744)
        for name,source,path in profiles:
            print('checking','GPU' if gpu else 'CPU',name,flush=True)
            prepare(path);call(38,encode([]),744)
            meta=struct.unpack('>7I',call(37));a=source['registry_program'].get('aquifer')
            want=([int(a['enabled']),a['program'],a['surface'][0]&MASK,a['surface'][1],a['surface'][2]] if a else [0,0,source['geology_min_y']&MASK,1,0])+[source['geology_min_y']&MASK,source['geology_height']]
            assert list(meta)==want,(meta,want)
            rng=random.Random(91871);lo,hi=0x87654321,0x80000001
            qs=[(rng.randrange(-512,513),rng.randrange(-32,32),rng.randrange(-512,513),lo,hi) for _ in range(48)]
            qs += [(x,y,z,lo,hi) for x,y,z in [(-30000000,-20,29999999),(-2147483648,-32,2147483647),(2147483647,31,-2147483648),(0,0,0),(16,-12,8)]]
            qs += [(16,-12,8,lo^1,hi),(16,-12,8,lo,hi^1)]
            raw=call(38,encode(qs));rows=list(struct.iter_unpack('>6f',raw))
            assert len(rows)==len(qs)
            for q,row in zip(qs,rows):
                wanted=expected(source,q)
                for i,(got,want) in enumerate(zip(row,wanted)):
                    err=abs(got-want)/max(1.,abs(want));maximum=max(maximum,err);scalars+=1
                    assert math.isfinite(got) and err<.002,(name,q,i,got,want,err)
                    if i==5:assert got==f32(want),(name,q,'serialized preliminary height',got,want)
            assert call(38,encode(qs[::-1]))==b''.join(struct.pack('>6f',*r) for r in rows[::-1])
            assert call(38,encode(qs))==raw
            assert call(38,encode([]))==b''
            assert call(38,encode([qs[-1]]*4096))==raw[-24:]*4096
            for op,data in [(37,b'\0'),(38,b''),(38,encode(qs[:1])+b'\0'),(38,struct.pack('>I',4097))]:call(op,data,603)
            assert call(38,encode(qs))==raw
            call(25);assert call(38,encode(qs))==raw # catalog preparation keeps immutable numeric inputs
            call(31,encode(qs));assert call(38,encode(qs))==raw # cave noise query retains aquifer
            if name=='analytic':
                call(9);call(15)
                desc=call(18,tile_bytes((-1,-1,26,26,lo,hi)));call(13,desc)
                call(16,tile_bytes((0,0,24,24,lo,hi)));call(22);call(33);call(35)
                chunk=chunk_request(0,0,lo,hi,5023);saved=call(26,chunk)
                assert call(38,encode(qs))==raw
                call(37);assert saved==call(26,chunk)
            call(30);call(38,encode(qs),744);call(37);assert call(38,encode(qs))==raw
            call(5,path_request(path));call(37,status=743);call(38,encode(qs),744)
            hashes.append(hashlib.sha256(raw).hexdigest());records.append(dict(name=name,queries=len(qs),sha256=hashes[-1],metadata=list(meta)))
        for name,path in bad:
            if name=='nonfinite_parameter':
                call(5,path_request(path),701)
                continue
            prepare(path);call(37,status=742);call(38,encode([]),744)
        call(4);assert worker.process.wait(timeout=10)==0;reader.join(timeout=5);assert not reader.is_alive()
        metal=[float(x) for x in re.findall(rb'BEND_METAL_DISPATCH device_ms=([0-9.]+)',b''.join(diagnostics))]
        return dict(gpu_required=gpu,profiles=records,scalars_checked=scalars,maximum_relative_reference_error=maximum,invalid_profiles=len(bad),expected_gpu_dispatches=dispatches,metal_commands_ms=metal,sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest(),requests_in_one_process=worker.id)
    finally:signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--metal-probe',type=Path);parser.add_argument('--profile',nargs=2,action='append')
    args=parser.parse_args();profiles,bad=fixtures(ROOT/'build/bend/aquifer-profiles')
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    report=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,bad,False),exercise(binary,profiles,bad,True)])
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/aquifer-metal-probe'
        if args.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        result=exercise(probe,profiles,bad,True);assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches'];assert result['sha256']==report['runs'][1]['sha256']
        report['actual_metal_proof']=result;report['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest()
    output=ROOT/'build/bend/aquifer-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('aquifer test timeout')))
    main()
