#!/usr/bin/env python3
"""Independent bank/containment/material-run QA for resident Bend lake basins.

CPU, GPU and an observed stock Metal runtime execute the application algorithms
in Bend. This is unlit component QA, not a usable world type or a performance
comparison with complete Rust regions.
"""
import argparse
from collections import Counter
import copy
from functools import lru_cache
from fractions import Fraction
import hashlib
import itertools
import json
import math
from pathlib import Path
import re
import signal
import struct
import subprocess
import threading
import zlib

from test_bend_compression import ROOT,DEFAULT_BEND,build
from test_bend_engine import Worker,compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_lakes import fixtures as candidates_fixtures,Reference as Candidates
from test_bend_density import ins
from test_bend_density_lattice import layout,encoded
from test_bend_material_columns import Reference as Materials,decode as columns_decode
from test_bend_surface import tile_bytes,query_bytes
from test_bend_noise import MASK,f32,world_simplex
from test_bend_generated_chunks import request as chunk_request,validate as validate_chunk
from test_bend_climate import expected

LO,HI=0x87654321,0x80000001


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True)
    base=copy.deepcopy(candidates_fixtures(folder/'candidates')[0][0][1])
    # A level plateau gives independent, exact bank heights and a contained
    # waterline. Material predicates remain the real loaded rule interpreter.
    base['registry_program']['programs'][1:3]=[dict(nodes=[ins(0,p=(20,0,0,0)),ins(29,1),ins(5,0,1)],roots=[2])]*2
    base['materials'].append({'id':'minecraft:lava','properties':{'level':'0'}});base['carveable'].append(False);base['lava']=6
    base['heightmap_masks']=[0,63,63,63,63,51,51]
    for i,b in enumerate(base['biomes']):b.update(id='minecraft:plains' if i==0 else 'minecraft:forest')
    result=[]
    def add(name,change=lambda _:None):
        s=copy.deepcopy(base);change(s);p=folder/(name+'.rbp');p.write_bytes(fixture_wire(s,True));result.append((name,s,p))
    def water(s):
        for b in s['biomes']:b.update(lakes=[1.,0.],lake_water_barrier=4)
    add('water',water)
    add('lava',lambda s:[b.update(lakes=[0.,1.],lake_barrier=1) for b in s['biomes']])
    add('partial',lambda s:[b.update(lakes=[.5,.1]) for b in s['biomes']])
    add('none',lambda s:[b.update(lakes=[0.,0.]) for b in s['biomes']])
    add('no_barrier',lambda s:[b.update(lakes=[1.,0.],lake_water_barrier=0) for b in s['biomes']])
    for flag in (2,4,8,16):add('blocked_'+str(flag),lambda s,f=flag:[b.update(lakes=[1.,0.],flags=f) for b in s['biomes']])
    add('sea',lambda s:s.update(sea_level=18))
    def rough(s):
        water(s);s['registry_program']['programs'][1:3]=[dict(nodes=[ins(29,0),ins(29,1),ins(0,p=(.03125,0,0,0)),ins(6,0,2),ins(0,p=(20,0,0,0)),ins(4,3,4),ins(5,5,1)],roots=[6])]*2
    add('ramp',rough)
    add('thin',lambda s:s.update(geology_height=4))
    def empty(s):
        s.update(geology_min_y=-(1<<31),geology_height=64,lava_level=-(1<<31),sea_level=-(1<<31));s['registry_program']['surface'][0]=-(1<<31)
        s['registry_program']['programs'][1:3]=[dict(nodes=[ins(0,p=(-1,0,0,0))],roots=[0])]*2
    add('empty_extreme_y',empty)
    return result


def descriptor(source,x,z,lo,hi):
    s=source['registry_program'];explicit=s['surface'][2]
    return (1,math.floor(x),0 if explicit else source['geology_min_y'],math.floor(z),math.ceil(x),
        0 if explicit else source['geology_min_y']+source['geology_height'],math.ceil(z),*s['terrain_cell'],lo,hi)


class Reference(Materials):
    def __init__(self,source):
        super().__init__(source);self.candidates=Candidates(source)
    def density_at(self,x,y,z,lo,hi,desc):
        value=self.query((1,x,y,z,lo,hi),desc)[0]
        if abs(value)>1e-9:return value
        origin,counts,steps=layout(desc);coords=(x,y,z)
        relative=[(Fraction(p)-o)/step for p,o,step in zip(coords,origin,steps)]
        indices=[min(math.floor(t),n-2) for t,n in zip(relative,counts)];fractions=[t-i for t,i in zip(relative,indices)]
        result=Fraction(0)
        for bits in itertools.product((0,1),repeat=3):
            point=tuple(o+(i+b)*step for o,i,b,step in zip(origin,indices,bits,steps));weight=math.prod(t if b else 1-t for b,t in zip(bits,fractions))
            result+=Fraction(self.vertex(1,point,lo,hi)[0])*weight
        return result
    @lru_cache(maxsize=50000)
    def height_at(self,x,z,lo,hi):
        desc=descriptor(self.source,x,z,lo,hi)
        if self.source['registry_program']['surface'][2]:return math.floor(self.height(x,z,desc))
        origin,counts,steps=layout(desc)
        # Piecewise linear Y layers can skip wholly negative cells, exactly as
        # an independent voxel scan would. Python's double precision is used.
        upper_y=self.maximum-1
        if self.density_at(x,upper_y,z,lo,hi,desc)>0:return self.maximum
        for i in range(counts[1]-2,-1,-1):
            start=max(self.minimum,origin[1]+i*steps[1]);end=min(upper_y,origin[1]+(i+1)*steps[1]-1)
            if end<start:continue
            if self.density_at(x,end,z,lo,hi,desc)>0:return end+1
            if self.density_at(x,start,z,lo,hi,desc)<=0:continue
            a,b=start,end
            while b-a>1:
                m=(a+b)//2
                if self.density_at(x,m,z,lo,hi,desc)>0:a=m
                else:b=m
            return a+1
        return self.minimum
    @lru_cache(maxsize=10000)
    def basin(self,cx,cz,lo,hi):
        c=self.candidates.candidate((cx*128,cz*128,lo,hi));x,z=c['point'];r=c['radius'];minimum=self.minimum
        points=[(x,z),(x+r+5,z),(x-r-5,z),(x,z+(r+5)/f32(.78)),(x,z-(r+5)/f32(.78))]
        banks=[self.height_at(px,pz,lo,hi) for px,pz in points] if c['eligible'] else [minimum]*5
        return dict(c,level=max(minimum,min(banks)-2),banks=banks)
    def shape(self,x,z,lo,hi):
        original=self.height_at(x,z,lo,hi)
        choice=expected(self.targets,self.vertex(0,(x,0,z),lo,hi),0)
        if choice is None or self.source['biomes'][choice[0]['biome']].get('flags',0)&30:return None
        noise=f32(world_simplex(x&MASK,z&MASK,f32(.055),(lo+9913)&MASK,hi)*f32(.09));best=None;previous=f32(1.35)
        for dz in (-1,0,1):
            for dx in (-1,0,1):
                b=self.basin(x//128+dx,z//128+dz,lo,hi)
                if not b['eligible']:continue
                px,pz=b['point'];sx=f32(x-px);sz=f32(f32(z-pz)*f32(.78))
                d=f32(f32(f32(math.sqrt(f32(f32(sx*sx)+f32(sz*sz))))/max(b['radius'],1.))+noise)
                if d<previous:previous=d;best=b
        if best is None or self.maximum-self.minimum<8 or best['level']<=self.minimum+6 or best['level']<=self.source['sea_level']+3 or abs(original-best['level'])>18:return None
        d=previous;level=best['level'];inner=d<1
        if inner:target=level-min(7,max(1,math.ceil(f32(f32(1-f32(d*d))*6))))
        else:
            t=f32(max(0.,min(1.,f32(f32(d-1)/f32(.35)))));smooth=f32(f32(t*t)*f32(3-f32(2*t)))
            target=math.floor(level+(original-level)*smooth+.5)
        target=max(self.minimum+6,min(self.maximum-1,target))
        return dict(best,inner=inner,original=original,height=target,distance=d)
    def column(self,x,z,desc):
        lo,hi=desc[-2:];sh=self.shape(x,z,lo,hi)
        if sh is None:return super().column(x,z,desc)
        base=super().column(x,z,desc);geometry={y:self.solid(x,y,z,desc) for y in range(self.minimum,self.maximum)}
        fill=min(sh['original'],sh['height']-2)
        for y in range(fill,sh['height']):geometry[y]=True
        for y in range(sh['height'],self.maximum):geometry[y]=False
        slope=max(abs(self.new_height(x+1,z,lo,hi)-self.new_height(x-1,z,lo,hi)),abs(self.new_height(x,z+1,lo,hi)-self.new_height(x,z-1,lo,hi)))
        depth,_,band,secondary,_=base['context'];preliminary=sh['height']-9+depth;level=-(1<<31);above=0;below=self.maximum;ids={}
        for y in range(self.maximum-1,self.minimum-1,-1):
            if not geometry[y]:
                fluid=(sh['inner'] and sh['height']<=y<sh['level']) or (y<self.source['sea_level'] and y<sh['height'])
                material=self.source['water'] if fluid else 0
                if material==0:level=-(1<<31);above=0
                elif level==-(1<<31):level=y+1
            else:
                if below>=y:below=next((v+1 for v in range(y-1,self.minimum-1,-1) if not geometry[v]),self.minimum)
                above+=1;ctx=(above,depth,slope,band,y-below+1,secondary,level,preliminary)
                selected=self.materials.material(base['biome'],(x,y,z),lo,hi,ctx)[0];material=int(selected)-1 if selected>0 else self.source['stone']
            if sh['inner'] and sh['height']<=y<sh['level'] and sh['lava']:material=self.source['lava']
            if sh['inner'] and sh['height']-2<=y<sh['height'] and sh['barrier']:material=sh['barrier']
            ids[y]=material
        runs=[]
        for i in range(self.maximum-self.minimum):
            material=ids[self.minimum+i]
            if runs and runs[-1][2]==material:runs[-1]=(runs[-1][0],i+1,material)
            else:runs.append((i,i+1,material))
        return dict(height=sh['height'],biome=base['biome'],context=[depth,slope,band,secondary,preliminary],runs=runs)
    def new_height(self,x,z,lo,hi):
        shape=self.shape(x,z,lo,hi);return self.height_at(x,z,lo,hi) if shape is None else shape['height']


def decode(raw):
    assert len(raw)%48==0
    return [dict(coverage=r[0],level=r[1]-(1<<32) if r[1]&(1<<31) else r[1],banks=[v-(1<<32) if v&(1<<31) else v for v in r[2:7]],biome=r[7],eligible=r[8],lava=r[9],barrier=r[10],radius=r[11]) for r in struct.iter_unpack('>11If',raw)]


def exercise(binary,profiles,gpu):
    worker=Worker(binary,gpu);diagnostics=bytearray();reader=threading.Thread(target=lambda:diagnostics.extend(worker.process.stderr.read()),daemon=True);reader.start()
    dispatches=checked=banks_checked=chunks=0;records=[];hashes=[];classes=Counter();resident=False
    def call(op,data=b'',error=0):
        nonlocal dispatches,resident
        signal.alarm(300);r=worker.call(op,data,status=int(error!=0))
        if error:
            assert r==struct.pack('>I',error),(op,error,r)
            if op==22 and error==729:dispatches+=2 if gpu or resident else 1
        else:
            dispatches+={13:1,16:1,22:2 if gpu or resident else 1,43:4,44:1}.get(op,0)
            if op in (5,13,16,30):resident=False
            if op==43:resident=True
        return r
    def prepare(path):
        for op,body in ((5,path_request(path)),(11,b''),(9,b''),(19,b''),(15,b''),(21,b''),(25,b''),(30,b'')):call(op,body)
    def tile(tile):
        x,z,w,d,lo,hi=tile;raw=call(18,tile_bytes((x-1,z-1,w+2,d+2,lo,hi)));call(13,raw);call(16,tile_bytes(tile))
        desc=list(struct.unpack('>11I',raw));return tuple(v-(1<<32) if i in range(1,7) and v&(1<<31) else v for i,v in enumerate(desc))
    try:
        call(43,error=750);call(44,query_bytes([]),752)
        for name,source,path in profiles:
            print('checking','GPU' if gpu else 'CPU',name,flush=True);prepare(path);ref=Reference(source)
            if name=='water':
                t=(1,1,16,16,LO,HI);raw=call(18,tile_bytes(t));call(13,raw);call(16,tile_bytes(t))
                call(43,error=751);call(44,query_bytes([]),752)
            center=ref.candidates.candidate((0,0,LO,HI))['point'];x,z=map(math.floor,center)
            tiles=[(x-4,z-4,8,8,LO,HI)]
            if name in ('water','lava','ramp','partial'):
                r=ref.candidates.candidate((0,0,LO,HI))['radius'];tiles += [(x+math.floor(r)-2,z-2,8,4,LO,HI),(-20,-20,4,3,LO,HI),(29999997,-30000005,3,4,LO,HI),(-(1<<31)+1,(1<<31)-5,2,3,LO,HI)]
            if name=='water':
                for low,high in ((LO^1,HI),(LO,HI^1)):
                    px,pz=map(math.floor,ref.candidates.candidate((0,0,low,high))['point']);tiles.append((px-2,pz-2,4,4,low,high))
                tiles.append((508,52,8,8,LO+38,HI))
            if name in ('vanilla','terralith','combined'):tiles=[(512,-512,4,4,LO,HI)]
            local=[]
            for t in tiles:
                desc=tile(t);tx,tz,w,d,lo,hi=t
                call(44,query_bytes([]),752);call(43,b'\0',603)
                ack=struct.unpack('>4I',call(43));want=(tx//128-1,tz//128-1,(tx+w-1)//128-tx//128+3,(tz+d-1)//128-tz//128+3)
                assert ack==tuple(v&MASK for v in want),(name,ack,want)
                qs=[(cx*128,cz*128,lo,hi) for cz in range(want[1],want[1]+want[3]) for cx in range(want[0],want[0]+want[2]) if -(1<<31)<=cx*128<(1<<31) and -(1<<31)<=cz*128<(1<<31)]
                raw=call(44,query_bytes(qs));rows=decode(raw)
                for q,row in zip(qs,rows):
                    b=ref.basin(q[0]//128,q[1]//128,lo,hi)
                    want_row=dict(coverage=1,level=b['level'],banks=b['banks'],biome=b['biome'],eligible=b['eligible'],lava=b['lava'],barrier=b['barrier'],radius=b['radius'])
                    assert row==want_row,(name,q,row,want_row);banks_checked+=5
                assert call(44,query_bytes(qs[::-1]))==b''.join(raw[i:i+48] for i in range(len(raw)-48,-1,-48))
                if name=='water' and t==tiles[0]:assert call(44,query_bytes([qs[0]]*4096))==raw[:48]*4096
                assert call(44,query_bytes([(tx,tz,lo^1,hi)]))==bytes(48)
                assert call(44,query_bytes([(tx,tz,lo,hi^1)]))==bytes(48)
                assert call(44,query_bytes([]))==b''
                for bad in (b'',query_bytes(qs[:1])+b'\0',struct.pack('>I',4097)):call(44,bad,603)
                if name=='blocked_16':
                    call(22,error=729);assert call(44,query_bytes(qs))==raw
                    local.append(hashlib.sha256(raw).hexdigest());continue
                call(22);pts=[(xx,zz,lo,hi) for zz in range(tz,tz+d) for xx in range(tx,tx+w)]
                data=call(24,query_bytes(pts));cols=columns_decode(data)
                for p,col in zip(pts,cols):
                    want_col=ref.column(*p[:2],desc)
                    assert col['height']==want_col['height'] and col['biome']==want_col['biome'] and col['runs']==want_col['runs'],(name,p,col,want_col)
                    for a,b in zip(col['context'],want_col['context']):assert abs(a-b)<.002*max(1,abs(b)),(name,p,a,b)
                    runs=col['runs'];assert runs[0][0]==0 and runs[-1][1]==source['geology_height']
                    assert all(a[1]==b[0] and a[2]!=b[2] for a,b in zip(runs,runs[1:]))
                    sh=ref.shape(*p);classes['unchanged' if sh is None else 'lava' if sh['inner'] and sh['lava'] else 'water' if sh['inner'] else 'rim']+=1;checked+=1
                call(43);assert call(24,query_bytes(pts))==data;call(22);assert call(24,query_bytes(pts))==data
                local.append(hashlib.sha256(raw+data).hexdigest())
            # Complete chunk serialization sees the final fluid/material runs.
            if name in ('water','lava','no_barrier'):
                t=(x//16*16,z//16*16,16,16,LO,HI);desc=tile(t);call(43);call(22)
                pts=[(xx,zz,LO,HI) for zz in range(t[1],t[1]+16) for xx in range(t[0],t[0]+16)]
                cols=columns_decode(call(24,query_bytes(pts)));nbt=call(26,chunk_request(t[0]//16,t[1]//16,LO,HI,5023));compressed=call(27,chunk_request(t[0]//16,t[1]//16,LO,HI,5023))
                assert zlib.decompress(compressed)==nbt
                validate_chunk(nbt,source,cols,t[0]//16,t[1]//16,5023,fluid_materials={source['water'],source['lava']})
                call(43);assert call(26,chunk_request(t[0]//16,t[1]//16,LO,HI,5023))==nbt;chunks+=1
            # Adjacent and overlapping tiles must retain identical shapes,
            # materials and slope context regardless of evaluation order.
            if name=='water':
                t=(x-4,z-4,8,8,LO,HI);tile(t);call(43);call(22);qs=[(xx,zz,LO,HI) for zz in range(z-2,z+2) for xx in range(x-2,x+2)];a=call(24,query_bytes(qs))
                tile((x-2,z-2,8,8,LO,HI));call(43);call(22);assert call(24,query_bytes(qs))==a
            prepare(path);call(44,query_bytes([]),752);call(43,error=750)
            digest=hashlib.sha256(''.join(local).encode()).hexdigest();hashes.append(digest);records.append(dict(name=name,tiles=len(tiles),sha256=digest))
        assert classes['water']>0 and classes['lava']>0 and classes['rim']>0 and classes['unchanged']>0,classes
        call(4);assert worker.process.wait(timeout=10)==0;reader.join(timeout=5);assert not reader.is_alive()
        return dict(gpu_required=gpu,profiles=records,columns_checked=checked,bank_heights_checked=banks_checked,serialized_chunks=chunks,classes=dict(classes),expected_gpu_dispatches=dispatches,metal_commands_ms=[float(x) for x in re.findall(rb'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostics)],sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest())
    finally:signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--metal-probe',type=Path);parser.add_argument('--profile',nargs=2,action='append');args=parser.parse_args()
    profiles=fixtures(ROOT/'build/bend/lake-basin-profiles')
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    report=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,False),exercise(binary,profiles,True)])
    assert report['runs'][0]['sha256']==report['runs'][1]['sha256']
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/lake-basin-metal-probe'
        if args.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        result=exercise(probe,profiles,True);assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches'];assert result['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=result
        report['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    report.update(source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))},inputs=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles],executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),bend_version=subprocess.check_output([str(DEFAULT_BEND),'version'],text=True).strip())
    output=ROOT/'build/bend/lake-basin-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('lake basin timeout')))
    main()
