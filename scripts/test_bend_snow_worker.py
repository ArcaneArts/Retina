#!/usr/bin/env python3
"""Actual persistent terrain/vegetation -> registered snow/freezing -> final MCA.

Analytic forest/ocean geometry and a cold-forest temperature control isolate
observable final weather placement. Registry recipe/material/support tables stay
loaded; original unmodified-profile palette/biome checks are separate. No Rust,
Java block generation or lighting claim; this is not a throughput benchmark.
"""
import argparse,hashlib,json,re,signal,struct,time
from pathlib import Path
from test_bend_compression import ROOT
from test_bend_engine import Worker
from test_bend_vegetation_worker import fixture,ObservedWorker,LO,HI
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_density import ins
from test_bend_surface import tile_bytes,query_bytes
from test_bend_material_columns import decode
from test_bend_generated_regions import request
from test_bend_snow import oracle,check_mca
from test_bend_placement_merge import sources


def exercise(binary,path,gpu,folder,mode,observe=False):
    source=fixture(json.loads(path.read_text()));folder.mkdir(parents=True,exist_ok=True)
    biome=next(i for i,b in enumerate(source['biomes']) if b['id']==('minecraft:frozen_ocean' if mode=='ocean' else 'minecraft:forest'))
    if mode in ('land','invalid'):source['biomes'][biome]['temperature']=-.5
    for b in source['biomes']:b['lakes']=[0,0]
    source['climate_targets'][0]['biome']=biome
    top=48 if mode=='ocean' else 80
    source['registry_program']['programs'][1]=dict(nodes=[ins(29,1),ins(0,p=(top,0,0,0)),ins(5,1,0)],roots=[2])
    if mode=='invalid':source['snow_support']=[False]
    inp=folder/'profile.rbp';inp.write_bytes(fixture_wire(source,True));w=ObservedWorker(binary,gpu,folder/'dispatch.log') if observe else Worker(binary,gpu);stages=[]
    def call(op,data=b'',error=0):
        before=len(w.dispatches()) if observe else 0;signal.alarm(1800);start=time.monotonic();out=w.call(op,data,status=int(bool(error)))
        row=dict(opcode=op,wall_ms=(time.monotonic()-start)*1000)
        if observe:row['device_ms']=w.dispatches()[before:]
        stages.append(row)
        if error:assert out==struct.pack('>I',error),(op,out,error)
        return out
    try:
        call(54,error=778);call(54,b'\0',603);call(5,path_request(inp))
        for op in (11,19,21,30,37,9,15,25):call(op)
        x,z,pad=-64,32,32
        call(13,call(18,tile_bytes((x-pad-23,z-pad-23,16+2*pad+46,16+2*pad+46,LO,HI))))
        call(16,tile_bytes((x-pad-1,z-pad-1,16+2*pad+2,16+2*pad+2,LO,HI)))
        for op in (29,43,22,33,39,35):call(op)
        wide=tile_bytes((x-pad,z-pad,16+2*pad,16+2*pad,LO,HI));call(50,wide);call(51,wide)
        call(53,tile_bytes((x,z,16,16,LO,HI)))
        query=query_bytes([(x+dx,z+dz,LO,HI) for dz in range(16) for dx in range(16)])
        before=decode(call(24,query))
        call(54,b'\0',603);assert decode(call(24,query))==before
        if mode=='invalid':
            call(54,error=777);assert decode(call(24,query))==before
            return dict(mode=mode,rejected_model_preserves_snapshot=True,stages=stages)
        ack=struct.unpack('>4I',call(54));assert ack[:2]==(16,16) and ack[3]==0
        after=decode(call(24,query));columns=[];snow=ice=changed=0
        for i,(a,b) in enumerate(zip(before,after)):
            cells=lambda row:[m for start,end,m in row['runs'] for _ in range(start,end)]
            assert a is not None and b is not None
            old,new=cells(a),cells(b);want=oracle(source,old,a['biome'],True,-64,a['height'],x+i%16,z+i//16)
            assert new==want,(mode,i,next((y for y,(a,b) in enumerate(zip(new,want)) if a!=b),None))
            changed+=sum(a!=b for a,b in zip(old,new));snow+=sum(a!=b and b==source['terrain_features']['snow_layer'] for a,b in zip(old,new));ice+=sum(a!=b and b==source['ice'] for a,b in zip(old,new));columns.append(new)
        call(54);assert decode(call(24,query))==after,'weather pass must be idempotent'
        if mode=='land':assert snow>0
        elif mode=='ocean':assert ice>0 and snow==0,'regular frozen-ocean ice must remain clear'
        else:assert changed==0,'warm forest received weather blocks'
        mca=folder/'r.-1.0.mca';call(52,request(-1,0,mca,LO,HI));record=check_mca(mca,source,columns)
        if observe:assert any(s.get('device_ms') for s in stages if s['opcode']==54),'weather pass did not execute on Metal'
        call(4);assert w.process.wait(timeout=30)==0
        return dict(mode=mode,gpu_required=gpu,acknowledgement=list(ack),snow_layers=snow,frozen_water=ice,changed=changed,mca=record,stages=stages)
    finally:signal.alarm(0);w.close()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,required=True);p.add_argument('--gpu',action='store_true');p.add_argument('--metal-probe',type=Path);a=p.parse_args()
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('snow worker timeout')))
    binary=ROOT/'build/bend/engine';folder=ROOT/'build/bend/snow-worker-tests'/('gpu' if a.gpu else 'cpu')
    runs=[exercise(binary,a.profile,a.gpu,folder/m,m) for m in ('land','ocean','warm','invalid')]
    report=dict(scope=__doc__.strip(),runs=runs,native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),profile_sha256=hashlib.sha256(a.profile.read_bytes()).hexdigest())
    if a.metal_probe:
        assert a.gpu and binary.with_suffix('.gpu').read_bytes()==a.metal_probe.with_suffix('.gpu').read_bytes()
        report['observer']=exercise(a.metal_probe,a.profile,True,folder/'observer','land',True)
        assert report['observer']['mca']==runs[0]['mca']
    paths=sources(ROOT/'bend/engine.bend')|{Path(__file__).resolve(),ROOT/'scripts/test_bend_snow.py'}
    report['sources']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}
    (ROOT/'docs/benchmarks'/('bend-snow-worker-'+('gpu' if a.gpu else 'cpu')+'.json')).write_text(json.dumps(report,indent=2)+'\n');print('PASS: persistent snow worker',flush=True)

if __name__=='__main__':main()
