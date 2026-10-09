#!/usr/bin/env python3
"""Independently check resident Bend aquifer centers, pressure and carved fluids.

This is unlit component QA. It does not claim a complete world type or a
Rust-versus-Bend performance result. All production computation stays in Bend.
"""
import argparse
from collections import Counter
import copy
from functools import lru_cache
import hashlib
import itertools
import json
import math
from pathlib import Path
import random
import re
import signal
import struct
import threading
import zlib

from test_bend_compression import ROOT,DEFAULT_BEND,build
from test_bend_engine import Worker,compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_aquifers import fixtures as field_fixtures
from test_bend_density import Oracle,ins
from test_bend_geology import encode
from test_bend_surface import tile_bytes,query_bytes
from test_bend_material_columns import decode as decode_columns
from test_bend_caves import expected_carve
from test_bend_generated_chunks import request as chunk_request,validate as validate_chunk
from test_bend_noise import MASK,f32,mix_hash

OFFSETS=((0,0),(-2,-1),(-1,-1),(0,-1),(1,-1),(-3,0),(-2,0),(-1,0),(1,0),(-2,1),(-1,1),(0,1),(1,1))
DRY=-32512


class AquiferReference:
    def __init__(self,source):
        self.source=source;self.model=json.loads(json.dumps(source['registry_program']),parse_float=lambda x:f32(float(x)))
        self.oracle=Oracle(self.model);self.a=self.model.get('aquifer',dict(enabled=False))
        self.water=source['water'];self.lava=source['lava'];self.sea=source['sea_level'];self.level=source['lava_level']
        value=source['materials'][self.water]
        self.water_barriers=(value if isinstance(value,str) else value['id'])=='minecraft:water'
    def run(self,i,p,lo,hi):return self.oracle.evaluate(self.model['programs'][self.a['program']+i],p,lo,hi)
    @lru_cache(maxsize=200000)
    def surface(self,x,z,lo,hi):
        bottom,step,explicit=self.a['surface'];value=self.run(4,(x,0,z),lo,hi)
        if explicit:return math.floor(value[0])
        top=math.floor(min(value[1],self.source['geology_min_y']+self.source['geology_height']-1)/step)*step
        return next((y for y in range(top,max(bottom,self.source['geology_min_y'])-1,-step) if self.run(4,(x,y,z),lo,hi)[0]>0),bottom)
    def global_status(self,y):return (-54,self.lava) if y<self.level else (self.sea,self.water)
    @staticmethod
    def material(status,y):return status[1] if y<status[0] else 0
    @lru_cache(maxsize=200000)
    def center(self,cell,lo,hi):
        x,y,z=cell
        h=mix_hash(((((x&MASK)*2654435769)^((y&MASK)*2246822507)^((z&MASK)*3266489909)^lo^((mix_hash(hi)+91871)&MASK)))&MASK)
        p=(x*16+h%10,y*12+mix_hash((h+1)&MASK)%9,z*16+mix_hash((h+2)&MASK)%10)
        return p,self.status(p,lo,hi)
    def status(self,p,lo,hi):
        x,y,z=p;global_status=self.global_status(y);lowest=2147483647;under=False
        for i,(dx,dz) in enumerate(OFFSETS):
            surface=self.surface((x+dx*16)//4*4,(z+dz*16)//4*4,lo,hi);adjusted=surface+8
            if i==0 and y-12>adjusted:return global_status
            reaches=y+12>adjusted
            wet=self.global_status(adjusted)
            if (reaches or i==0) and self.material(wet,adjusted)!=0:
                if i==0:under=True
                if reaches:return wet
            lowest=min(lowest,surface)
        flood,erosion=self.run(0,p,lo,hi)[:2];level=DRY
        if erosion<=0:
            factor=max(0.,min(1.,1.-(lowest+8-y)/64.)) if under else 0.
            flood=max(-1.,min(1.,flood))
            if flood>.8+(-.3-.8)*factor:level=global_status[0]
            elif flood>.4+(-.8-.4)*factor:
                cell=(x//16,y//40,z//16);spread=self.run(1,cell,lo,hi)[0]*10.
                level=min(lowest,cell[1]*40+20+math.floor(spread/3.)*3)
        kind=global_status[1]
        if level<=-10 and level!=DRY and kind!=self.lava:
            if abs(self.run(2,(x//64,y//40,z//64),lo,hi)[0])>.3:kind=self.lava
        return level,kind
    @lru_cache(maxsize=500000)
    def barrier_node(self,p,lo,hi):return f32(self.run(3,p,lo,hi)[0])
    def barrier(self,p,lo,hi):
        anchor=tuple(v//4*4 for v in p);t=[(v-o)/4 for v,o in zip(p,anchor)]
        values={bits:self.barrier_node(tuple(o+b*4 for o,b in zip(anchor,bits)),lo,hi) for bits in itertools.product((0,1),repeat=3)}
        def lerp(a,b,t):return f32(a+f32(f32(b-a)*t))
        return lerp(lerp(lerp(values[0,0,0],values[1,0,0],t[0]),lerp(values[0,1,0],values[1,1,0],t[0]),t[1]),
                    lerp(lerp(values[0,0,1],values[1,0,1],t[0]),lerp(values[0,1,1],values[1,1,1],t[0]),t[1]),t[2])
    def pressure(self,y,noise,a,b):
        ta,tb=self.material(a,y),self.material(b,y)
        if self.water_barriers and ((ta==self.water and tb==self.lava) or (tb==self.water and ta==self.lava)):return 2.
        difference=abs(a[0]-b[0])
        if difference==0:return 0.
        distance=y+.5-(a[0]+b[0])*.5;edge=difference*.5-abs(distance)
        gradient=f32(edge/(1.5 if edge>0 else 2.5)) if distance>0 else f32((edge+3.)/(3. if edge+3>0 else 10.))
        return f32(2.*f32(gradient+(noise if -2<=gradient<=2 else 0.)))
    def substance(self,p,lo,hi,density):
        x,y,z=p
        if self.material(self.global_status(y),y)==self.lava:return 2
        anchor=((x-5)//16,(y+1)//12,(z-5)//16);candidates=[]
        for i,(dx,dy,dz) in enumerate(itertools.product(range(2),range(-1,2),range(2))):
            position,status=self.center(tuple(a+b for a,b in zip(anchor,(dx,dy,dz))),lo,hi)
            candidates.append((sum((a-b)**2 for a,b in zip(position,p)),-i,status))
        closest=sorted(candidates)[:3];d=[row[0] for row in closest];a,b,c=[row[2] for row in closest]
        kind=self.material(a,y);result=2 if kind==self.lava else 1 if kind else 0
        s12=f32(1.-f32((d[1]-d[0])/25.))
        if s12<=0:return result
        if self.water_barriers and kind==self.water and self.material(self.global_status(y-1),y-1)==self.lava:return result
        noise=self.barrier(p,lo,hi);s13=f32(1.-f32((d[2]-d[0])/25.));s23=f32(1.-f32((d[2]-d[1])/25.));density=min(f32(-.02),density)
        if f32(density+f32(s12*self.pressure(y,noise,a,b)))>0:return 3
        if s13>0 and f32(density+f32(f32(s12*s13)*self.pressure(y,noise,a,c)))>0:return 3
        if s23>0 and f32(density+f32(f32(s12*s23)*self.pressure(y,noise,b,c)))>0:return 3
        return result


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True);base=copy.deepcopy(field_fixtures(folder/'fields')[0][0][1]);base.update(geology_min_y=-64,geology_height=96,sea_level=0,lava_level=-54)
    base['materials'][base['lava']]='minecraft:lava';base['heightmap_masks'][base['lava']]=1;base['carveable'][base['lava']]=False
    for biome in base['biomes']:biome['carvers']=[]
    programs=base['registry_program']['programs'];programs[2]=dict(nodes=[ins(0,p=(-1,0,0,0))],roots=[0])
    first=base['registry_program']['aquifer']['program'];base['registry_program']['aquifer']['surface']=[-64,4,0]
    programs[first+4]=dict(nodes=[ins(0,p=(12,0,0,0)),ins(29,1),ins(5,0,1),ins(0,p=(31,0,0,0))],roots=[2,3])
    result=[]
    def add(name,flood,erosion,spread=0.,lava=0.,barrier=0.,change=lambda _:None):
        s=copy.deepcopy(base);ps=s['registry_program']['programs'];ps[first]=dict(nodes=[ins(0,p=(flood,0,0,0)),ins(0,p=(erosion,0,0,0))],roots=[0,1])
        for i,value in ((1,spread),(2,lava),(3,barrier)):ps[first+i]=dict(nodes=[ins(0,p=(value,0,0,0))],roots=[0])
        change(s);p=folder/(name+'.rbp');p.write_bytes(fixture_wire(s,True));result.append((name,s,p))
    add('full',1.,0.);add('dry',-1.,0.);add('spread',.6,0.)
    add('lava_spread',.6,0.,lava=.75,barrier=.5)
    add('erosion',1.,1.)
    def mixed(s):
        s['registry_program']['programs'][first]=dict(nodes=[ins(29,0),ins(0,p=(.03125,0,0,0)),ins(6,0,1),ins(0,p=(-1,0,0,0))],roots=[2,3])
        s['registry_program']['programs'][first+3]=dict(nodes=[ins(29,0),ins(29,2),ins(4,0,1),ins(0,p=(.00390625,0,0,0)),ins(6,2,3)],roots=[4])
    add('mixed',0.,0.,change=mixed)
    add('disabled',0.,0.,change=lambda s:s['registry_program'].update(aquifer=dict(enabled=False)))
    add('absent',0.,0.,change=lambda s:s['registry_program'].pop('aquifer'))
    return result


def exercise(binary,profiles,gpu):
    worker=Worker(binary,gpu);diagnostics=bytearray();reader=threading.Thread(target=lambda:diagnostics.extend(worker.process.stderr.read()),daemon=True);reader.start()
    dispatches=0;records=[];hashes=[];checked=blocks=0;classes=Counter()
    def call(op,data=b'',error=0,dispatch_count=None):
        nonlocal dispatches
        signal.alarm(300);reply=worker.call(op,data,status=int(error!=0))
        if error:assert reply==struct.pack('>I',error),(op,error,reply)
        else:dispatches+=dispatch_count if dispatch_count is not None else {13:1,16:1,22:2 if gpu else 1,33:2,34:1,35:1,36:1,38:1,39:3,40:1}.get(op,0)
        return reply
    def columns(points):return decode_columns(b''.join(call(24,query_bytes(points[i:i+256])) for i in range(0,len(points),256)))
    def generate(tile,enabled=True):
        x,z,w,d,lo,hi=tile;desc=call(18,tile_bytes((x-1,z-1,w+2,d+2,lo,hi)));call(13,desc);call(16,tile_bytes(tile));call(22);call(33)
        ack=struct.unpack('>2I',call(39,dispatch_count=3 if enabled else 0));assert all(ack) if enabled else ack==(0,0)
        return ack
    try:
        call(39,error=745);call(40,encode([]),747)
        for name,source,path in profiles:
            print('checking','GPU' if gpu else 'CPU',name,flush=True)
            for op,data in ((5,path_request(path)),(11,b''),(19,b''),(21,b''),(25,b''),(9,b''),(15,b''),(30,b''),(37,b'')):call(op,data)
            lo,hi=0x87654321,0x80000001;enabled=source['registry_program'].get('aquifer',{}).get('enabled',False);ref=AquiferReference(source)
            tile=(0,0,24,24,lo,hi);ack=generate(tile,enabled);points=[(x,z,lo,hi) for z in range(24) for x in range(24)];before=columns(points)
            bottom=source['geology_min_y'];top=bottom+source['geology_height'];actual_profile=name in ('vanilla','terralith','combined')
            rng=random.Random(91871)
            voxels=[(rng.randrange(24),rng.randrange(bottom,top),rng.randrange(24),lo,hi) for _ in range(48)] if actual_profile else [(x,y,z,lo,hi) for z in range(24) for x in range(24) for y in range(bottom,top)]
            sampled=[];substances=[]
            for i in range(0,len(voxels),4096):
                qs=voxels[i:i+4096];fs=list(struct.iter_unpack('>I4f',call(34,encode(qs))));sampled+=fs
                if enabled:
                    rows=list(struct.iter_unpack('>2I',call(40,encode(qs))));substances+=rows
                    for q,f,row in zip(qs,fs,rows):
                        want=ref.substance(q[:3],lo,hi,f[1]);assert row==(1,want),(name,q,row,want,f);classes[want]+=1;checked+=1
            if enabled:
                probes=voxels[:12]+[(0,bottom,0,lo^1,hi),(0,bottom,0,lo,hi^1),(-16,bottom,0,lo,hi)]
                raw=call(40,encode(probes));assert list(struct.iter_unpack('>2I',raw))[-3:]==[(0,0)]*3
                assert call(40,encode(probes[::-1]))==b''.join(raw[i:i+8] for i in range(len(raw)-8,-1,-8))
                assert call(40,encode([probes[0]]*4096))==raw[:8]*4096;assert call(40,encode([]))==b''
                for op,data in ((39,b'\0'),(40,b''),(40,encode(probes[:1])+b'\0'),(40,struct.pack('>I',4097))):call(op,data,603)
                assert call(40,encode(probes))==raw
            call(35);after=columns(points)
            for i,(q,f) in enumerate(zip(voxels,sampled)):
                x,y,z,_,_=q;original=next(m for a,b,m in before[z*24+x]['runs'] if a<=y-bottom<b)
                want=expected_carve(source,before[z*24+x],x,y,z,f[1:],lo,hi)
                if want!=original:
                    if enabled:want=(0,source['water'],source['lava'],original)[substances[i][1]]
                    else:want=ref.material(ref.global_status(y),y)
                got=next(m for a,b,m in after[z*24+x]['runs'] if a<=y-bottom<b)
                assert got==want,(name,q,got,want);blocks+=1
            chunk=chunk_request(0,0,lo,hi,5023);raw_chunk=call(26,chunk)
            chunk_columns=[after[z*24+x] for z in range(16) for x in range(16)];chunk_biomes=None
            if actual_profile:
                quarts=[(i%4*4,bottom+sy*16+i//16*4,(i//4)%4*4,lo,hi) for sy in range(source['geology_height']//16) for i in range(64)]
                ids=list(struct.iter_unpack('>2I',call(36,encode(quarts))));assert all(row[0] for row in ids)
                chunk_biomes=[row[1] if row[1]!=MASK else chunk_columns[q[2]*16+q[0]]['biome'] for q,row in zip(quarts,ids)]
            validate_chunk(raw_chunk,source,chunk_columns,0,0,5023,chunk_biomes)
            assert zlib.decompress(call(27,chunk))==raw_chunk
            if enabled:
                call(39);assert call(26,chunk)==raw_chunk
                call(25);assert call(40,encode(voxels[:12]))==b''.join(struct.pack('>2I',*v) for v in substances[:12])
                call(37);call(40,encode([]),747);assert call(26,chunk)==raw_chunk
                call(35,error=742);call(39)
            digest=hashlib.sha256(raw_chunk).hexdigest();hashes.append(digest);records.append(dict(name=name,cache_counts=list(ack),queries=len(voxels) if enabled else 0,voxels=len(voxels),chunk_sha256=digest))
            if name=='mixed':
                common=[(x,y,z,lo,hi) for x in (12,16,20) for y in (-63,-31,-8,11,31) for z in (12,16,20)]
                generate((8,8,17,17,lo,hi));first=call(40,encode(common));generate((12,12,9,9,lo,hi));assert call(40,encode(common))==first
                generate((8,8,17,17,lo,hi));assert call(40,encode(common))==first
                for x,z,w,d in [(-513,-511,17,19),(-30000000,29999968,17,17),(-2147483644,2147483620,17,17),(2147483620,-2147483644,17,17),(7,7,1,9),(7,7,9,1),(3,-3,1,1)]:
                    generate((x,z,w,d,lo,hi));qs=[(px,y,pz,lo,hi) for px in (x,x+w-1) for pz in (z,z+d-1) for y in (-63,-31,-8,11,31)]
                    fs=list(struct.iter_unpack('>I4f',call(34,encode(qs))));rows=list(struct.iter_unpack('>2I',call(40,encode(qs))))
                    assert rows==[(1,ref.substance(q[:3],lo,hi,f[1])) for q,f in zip(qs,fs)],(x,z,rows)
                    checked+=len(qs)
            call(30);call(40,encode([]),747);call(39,error=745)
        call(4);assert worker.process.wait(timeout=10)==0;reader.join(timeout=5);assert not reader.is_alive()
        return dict(gpu_required=gpu,profiles=records,substance_queries_checked=checked,carved_voxels_checked=blocks,substance_counts=dict(classes),expected_gpu_dispatches=dispatches,metal_commands_ms=[float(x) for x in re.findall(rb'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostics)],sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest())
    finally:signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--metal-probe',type=Path);parser.add_argument('--profile',nargs=2,action='append');args=parser.parse_args()
    profiles=fixtures(ROOT/'build/bend/aquifer-placement-profiles')
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    report=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,False),exercise(binary,profiles,True)])
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/aquifer-placement-metal-probe'
        if args.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        proof=exercise(probe,profiles,True);assert len(proof['metal_commands_ms'])==proof['expected_gpu_dispatches'];assert proof['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=proof
        report['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest();output=ROOT/'build/bend/aquifer-placement-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('aquifer placement test timeout')))
    main()
