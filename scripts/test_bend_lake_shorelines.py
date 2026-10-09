#!/usr/bin/env python3
"""Check lake exclusions against finalized coastal selection, before carving.

An independent integer-height/disk-probe oracle and linear climate search check
raw-surface and shoreline-finalized material paths. This remains unlit component
QA; it is not a complete Bend world type or a Rust performance comparison.
"""
import argparse
from collections import Counter
import copy
import hashlib
import json
import math
from pathlib import Path
import re
import signal
import struct
import subprocess
import threading

from test_bend_compression import ROOT,DEFAULT_BEND,build
from test_bend_engine import Worker,compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_lake_basins import fixtures as basin_fixtures,Reference,LO,HI
from test_bend_material_columns import decode
from test_bend_surface import tile_bytes,query_bytes,rows
from test_bend_density import ins
from test_bend_noise import MASK


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True)
    base=copy.deepcopy(basin_fixtures(folder/'basins')[0][1]);base['sea_level']=8
    base['registry_program']['programs'][0]=dict(nodes=[ins(0)],roots=[0]*6)
    base['climate_targets']=[dict(min=[0.,0.,v,0.],max=[0.,0.,v,0.],weirdness=[0.,0.],depth=[0.,0.],offset=0.,biome=i) for i,v in enumerate((0.,.5))]
    for i,b in enumerate(base['biomes']):b.update(flags=64 if i==0 else 8,id='minecraft:beach' if i==0 else 'minecraft:swamp')
    ref=Reference(base);cx,cz=ref.candidates.candidate((0,0,LO,HI))['point'];radius=ref.candidates.candidate((0,0,LO,HI))['radius']
    notch=math.floor((cx+radius-5)/4+.5)*4
    # A narrow pre-lake gully reaches the sea near a basin edge. The five bank
    # probes remain on height20, so waterline18 alone cannot identify the coast.
    pit=dict(nodes=[ins(29,0),ins(0,p=(notch,0,0,0)),ins(5,0,1),ins(11,2),ins(0,p=(4,0,0,0)),ins(6,3,4),ins(0,p=(16,0,0,0)),ins(8,5,6),ins(0,p=(4,0,0,0)),ins(4,7,8),ins(29,1),ins(5,9,10)],roots=[11])
    result=[]
    def add(name,change=lambda s:None):
        s=copy.deepcopy(base);change(s);p=folder/(name+'.rbp');p.write_bytes(fixture_wire(s,True));result.append((name,s,p))
    add('inland_excluded')
    add('inland_allowed',lambda s:s['biomes'][1].update(flags=0,id='minecraft:plains'))
    add('shore_only',lambda s:s.update(climate_targets=s['climate_targets'][:1]))
    add('no_shores',lambda s:[b.update(flags=0) for b in s['biomes']])
    add('coast_allowed',lambda s:s['registry_program']['programs'].__setitem__(slice(1,3),[pit,pit]))
    def coast_blocked(s):
        s['registry_program']['programs'][1:3]=[pit,pit];s['biomes'][0]['flags']=72;s['biomes'][1]['flags']=0
    add('coast_excluded',coast_blocked)
    return result,(cx,cz,notch)


def exercise(binary,profiles,anchor,gpu):
    worker=Worker(binary,gpu);diagnostics=bytearray()
    reader=threading.Thread(target=lambda:[diagnostics.extend(line) for line in worker.process.stderr],daemon=True);reader.start()
    dispatches=checked=surface_checked=0;resident=False;records=[];hashes=[];classes=Counter()
    def call(op,body=b'',error=None):
        nonlocal dispatches,resident
        signal.alarm(300);r=worker.call(op,body,status=int(error is not None))
        if error is not None:assert r==struct.pack('>I',error),(op,error,r)
        else:
            dispatches+={13:1,16:1,22:2 if gpu or resident else 1,29:2,43:4,44:1}.get(op,0)
            if op in (5,13,16,29,30):resident=False
            if op==43:resident=True
        return r
    def prepare(path):
        for op,body in ((5,path_request(path)),(11,b''),(9,b''),(19,b''),(15,b''),(21,b''),(25,b''),(30,b'')):call(op,body)
    def tile(t,halo):
        x,z,w,d,lo,hi=t;raw=call(18,tile_bytes((x-halo,z-halo,w+2*halo,d+2*halo,lo,hi)));call(13,raw);call(16,tile_bytes(t))
        return tuple(v-(1<<32) if i in range(1,7) and v&(1<<31) else v for i,v in enumerate(struct.unpack('>11I',raw)))
    def verify(ref,t,desc,finalized):
        nonlocal checked,surface_checked
        x,z,w,d,lo,hi=t;pts=[(xx,zz,lo,hi) for zz in range(z,z+d) for xx in range(x,x+w)]
        surf=rows(call(17,query_bytes(pts)))
        call(43);call(22);raw=call(24,query_bytes(pts));cols=decode(raw)
        for pt,row,col in zip(pts,surf,cols):
            xx,zz,lo,hi=pt;biome=ref.finalized_biome(*pt) if finalized else None
            if finalized:
                assert row[0]==1 and row[1]==ref.height_at(*pt) and row[-1]==biome,(pt,row,biome);surface_checked+=1
            want=ref.column(xx,zz,desc,biome)
            assert (col['height'],col['biome'],col['runs'])==(want['height'],want['biome'],want['runs']),(pt,finalized,col,want)
            for a,b in zip(col['context'],want['context']):assert abs(a-b)<.002*max(1,abs(b)),(pt,a,b)
            sh=ref.shape(*pt);classes['lake' if sh is not None else 'unchanged']+=1;checked+=1
        call(43);assert call(24,query_bytes(pts))==raw;call(22);assert call(24,query_bytes(pts))==raw
        return raw,pts,surf
    try:
        cx,cz,notch=anchor
        for name,source,path in profiles:
            print('checking','GPU' if gpu else 'CPU',name,flush=True);prepare(path);ref=Reference(source)
            actual=name in ('vanilla','terralith','combined')
            t=(512,-512,3,3,LO,HI) if actual else (notch-8,math.floor(cz)-4,16,8,LO,HI)
            desc=tile(t,7);raw,pts,before=verify(ref,t,desc,False)
            call(29);call(44,query_bytes([]),752);call(24,query_bytes([]),727)
            final,_,after=verify(ref,t,desc,True)
            call(29);call(43);call(22);assert call(24,query_bytes(pts))==final
            ids=Counter(row[-1] for row in after)
            if name=='inland_excluded':assert ids=={1:128} and all(ref.shape(*q) is None for q in pts)
            if name=='inland_allowed':assert ids=={1:128} and any(ref.shape(*q) for q in pts)
            if name=='shore_only':assert ids=={0:128} and any(ref.shape(*q) for q in pts)
            if name in ('coast_allowed','coast_excluded'):
                assert ids[0]>0 and ids[1]>0,(name,ids)
                coastal=[q for q,row in zip(pts,after) if row[-1]==0];inland=[q for q,row in zip(pts,after) if row[-1]==1]
                if name=='coast_allowed':assert any(ref.shape(*q) for q in coastal) and all(ref.shape(*q) is None for q in inland)
                else:assert all(ref.shape(*q) is None for q in coastal) and any(ref.shape(*q) for q in inland)
            # One-column density coverage is enough for the material pass. A
            # coastal probe outside it must evaluate globally, never clamp.
            if not actual:
                small=(t[0]+3,t[1]+2,4,3,LO,HI);d=tile(small,1);a,common,_=verify(ref,small,d,False)
                if name=='no_shores':
                    call(29);call(43);call(22);assert call(24,query_bytes(common))==a
                else:
                    call(29,error=735);assert call(24,query_bytes(common))==a
                other=(small[0]-2,small[1]-1,8,5,LO,HI);d=tile(other,1);call(43);call(22);assert call(24,query_bytes(common))==a
                if name in ('shore_only','no_shores'):
                    for edge in ((-514,511,3,2,LO,HI),(29999997,-30000005,3,2,LO,HI),(-(1<<31)+1,(1<<31)-4,2,2,LO,HI)):
                        d=tile(edge,1);verify(ref,edge,d,False)
            h=hashlib.sha256(raw+final).hexdigest();hashes.append(h);records.append(dict(name=name,sha256=h,finalized_biome_counts={str(k):v for k,v in ids.items()}))
            prepare(path);call(44,query_bytes([]),752)
        assert classes['lake'] and classes['unchanged'],classes
        call(4);assert worker.process.wait(timeout=10)==0;reader.join(timeout=5);assert not reader.is_alive()
        return dict(gpu_required=gpu,profiles=records,material_columns_checked=checked,finalized_surface_columns_checked=surface_checked,classes=dict(classes),expected_gpu_dispatches=dispatches,metal_commands_ms=[float(v) for v in re.findall(rb'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostics)],sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest())
    finally:signal.alarm(0);worker.close()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--skip-build',action='store_true');p.add_argument('--prove-metal',action='store_true');p.add_argument('--metal-probe',type=Path);p.add_argument('--profile',nargs=2,action='append');p.add_argument('--output',type=Path,help='Separate report path for an actual-registry run alongside the Gradle control run.');a=p.parse_args()
    profiles,anchor=fixtures(ROOT/'build/bend/lake-shoreline-profiles')
    for js,wire in a.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine'
    if not a.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    r=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,anchor,False),exercise(binary,profiles,anchor,True)])
    assert r['runs'][0]['sha256']==r['runs'][1]['sha256']
    if a.prove_metal:
        probe=a.metal_probe or ROOT/'build/bend/lake-shoreline-metal-probe'
        if a.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        q=exercise(probe,profiles,anchor,True);assert len(q['metal_commands_ms'])==q['expected_gpu_dispatches'];assert q['sha256']==r['runs'][1]['sha256'];r['actual_metal_proof']=q;r['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    r.update(source_sha256={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted((ROOT/'bend').glob('*.bend'))},inputs=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles],executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),bend_version=subprocess.check_output([str(DEFAULT_BEND),'version'],text=True).strip())
    out=a.output or ROOT/'build/bend/lake-shoreline-tests.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(r,indent=2)+'\n');print('PASS:',out,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('lake shoreline timeout')));main()
