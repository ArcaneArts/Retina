#!/usr/bin/env python3
"""Independent block-run/context checks for Bend CPU and actual GPU execution.

This stage consumes the existing top-level density approximation. Caves,
aquifers, 3D biome columns and complete world integration are
still required; this is not a complete-region benchmark.
"""
import argparse
import copy
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path
import re
import signal
import struct
import time

from test_bend_compression import ROOT,DEFAULT_BEND,build
from test_bend_engine import Worker,compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_surface import SurfaceReference,fixture_profiles,tile_bytes,query_bytes
from test_bend_material import Reference as Materials,request as material_bytes
from test_bend_density import ins
from test_bend_density_lattice import encoded,layout,LOW,HIGH
from test_bend_climate import expected
from test_bend_noise import mix_hash,MASK,f32


def fixtures(folder):
    profiles=[]
    for name,source,_ in fixture_profiles(folder/'surface'):
        source=copy.deepcopy(source)
        source.update(stone=1,water=5,materials=['minecraft:air','minecraft:stone','minecraft:grass_block','minecraft:dirt','minecraft:gravel','minecraft:water'])
        source['terrain_features']=dict(bands=[2,3,4])
        program=source['registry_program'];program['material_layers']=True
        program['surface_noises']=[0,1,2]
        program['noises']=[dict(frequency=.0035*(i+1),amplitude=.8,salt=-12345+i,coefficients=[1.,0.,.5]) for i in range(3)]
        # Top cap, soil depth, underside cap; every solid interval needs its own
        # depths. Floating islands expose mistakes a height-only slab misses.
        rules=dict(nodes=[ins(45,1,0),ins(0,p=(3,0,0,0)),ins(40,0,1),ins(45,1,1),ins(0,p=(4,0,0,0)),ins(40,3,4),ins(41,2,5),
            ins(45,0,0),ins(0,p=(5,0,0,0)),ins(40,7,8),ins(41,6,9)],roots=[10])
        program['programs']+= [copy.deepcopy(rules) for _ in source['biomes']]
        path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,name!='ramp'));profiles.append((name,source,path))
    for name,change in [('thin',lambda s:s.update(geology_height=1)),('submerged',lambda s:s.update(sea_level=18)),
                        ('no_water',lambda s:s.update(sea_level=-17))]:
        source=copy.deepcopy(profiles[0][1]);change(source)
        path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,True));profiles.append((name,source,path))
    source=copy.deepcopy(profiles[0][1])
    sliver=dict(nodes=[ins(29,1),ins(29,0),ins(0,p=(.125,0,0,0)),ins(6,1,2),ins(0,p=(17.75,0,0,0)),ins(4,3,4),ins(5,0,5)],roots=[6])
    source['registry_program']['programs'][1:3]=[sliver,sliver]
    path=folder/'top_sliver.rbp';path.write_bytes(fixture_wire(source,True));profiles.append(('top_sliver',source,path))
    invalid=[]
    base=profiles[0][1]
    def bad(name,change):
        source=copy.deepcopy(base);change(source);path=folder/('invalid-'+name+'.rbp');path.write_bytes(fixture_wire(source,True));invalid.append((name,path))
    for field in ('stone','water'):
        for label,value in [('missing',None),('float',1.),('zero',0),('negative',-1),('outside',6)]:
            bad(field+'_'+label,lambda s,f=field,v=value:s.pop(f) if v is None else s.__setitem__(f,v))
    for label,value in [('missing',None),('short',[0,1]),('long',[0,1,2,0]),('float',[0,1,2.]),('outside',[0,1,3]),('negative',[-1,1,2])]:
        bad('noises_'+label,lambda s,v=value:s['registry_program'].pop('surface_noises') if v is None else s['registry_program'].__setitem__('surface_noises',v))
    bad('air_not_zero',lambda s:s['materials'].__setitem__(0,'minecraft:stone'))
    bad('air_type',lambda s:s['materials'].__setitem__(0,{}))
    return profiles,invalid


def decode(data):
    result=[];at=0
    while at<len(data):
        present=struct.unpack_from('>I',data,at)[0];at+=4
        if not present:result.append(None);continue
        height,biome,*tail=struct.unpack_from('>2I5fI',data,at);at+=32
        count=tail[-1];runs=list(struct.iter_unpack('>3I',data[at:at+count*12]));at+=count*12
        assert len(runs)==count and at<=len(data)
        result.append(dict(height=height-(1<<32) if height&(1<<31) else height,biome=biome,context=tail[:-1],runs=runs))
    assert at==len(data)
    return result


def voxel_bytes(points):
    return struct.pack('>I',len(points))+b''.join(struct.pack('>5I',*(v&MASK for v in q)) for q in points)


class Reference(SurfaceReference):
    def __init__(self,source):
        super().__init__(source);self.materials=Materials(source)

    def solid(self,x,y,z,desc):
        if self.source['registry_program']['surface'][2]:return y<math.floor(self.height(x,z,desc))
        value=self.query((1,x,y,z,*desc[-2:]),desc)[0]
        if abs(value)>1e-10:return value>0
        # A floating sum of eight weights can turn an exact zero into tiny
        # positive noise (notably 3x5 cells). Recompute ambiguous signs with
        # exact rational weights; never snap actual small density to zero.
        origin,counts,steps=layout(desc)
        relative=[Fraction(p-o,s) for p,o,s in zip((x,y,z),origin,steps)]
        indices=[min(math.floor(t),n-2) for t,n in zip(relative,counts)]
        fractions=[t-i for t,i in zip(relative,indices)]
        result=Fraction(0)
        for dx in (0,1):
            for dy in (0,1):
                for dz in (0,1):
                    bits=(dx,dy,dz)
                    point=tuple(o+(i+b)*s for o,i,b,s in zip(origin,indices,bits,steps))
                    weight=math.prod(t if b else 1-t for b,t in zip(bits,fractions))
                    result+=Fraction(self.vertex(1,point,*desc[-2:])[0])*weight
        return result>0

    def first_free(self,x,z,desc):
        return next((y+1 for y in range(self.maximum-1,self.minimum-1,-1) if self.solid(x,y,z,desc)),self.minimum)

    def column(self,x,z,desc):
        low,high=desc[-2:];point=(x,0,z);s=self.source;p=s['registry_program']
        first=self.first_free(x,z,desc)
        choice=expected(self.targets,self.vertex(0,point,low,high),0);biome=MASK if choice is None else choice[0]['biome']
        a,b,c=p['surface_noises']
        h=mix_hash(((x*2654435769)^(z*2246822507)^((low+1381)&MASK)^mix_hash(high))&MASK)
        depth=math.trunc(f32(f32(f32(self.materials.noise(point,a,low,high))*f32(2.75))+f32(3.))+f32((h&65535)/65535*.25))
        secondary=self.materials.noise(point,b,low,high);band=math.floor(self.materials.noise(point,c,low,high)*4+.5)
        slope=max(abs(self.first_free(x+1,z,desc)-self.first_free(x-1,z,desc)),abs(self.first_free(x,z+1,desc)-self.first_free(x,z-1,desc)))
        preliminary=first-9+depth;water=-(1<<31);above=0;below=self.maximum;ids={};geometry={}
        for y in range(self.maximum-1,self.minimum-1,-1):
            solid=self.solid(x,y,z,desc);geometry[y]=solid
            if not solid:
                material=s['water'] if y<s['sea_level'] else 0
                if material==0:water=-(1<<31);above=0
                elif water==-(1<<31):water=y+1
            else:
                if below>=y:
                    below=next((v+1 for v in range(y-1,self.minimum-1,-1) if not self.solid(x,v,z,desc)),self.minimum)
                above+=1;ctx=(above,depth,slope,band,y-below+1,secondary,water,preliminary)
                selected=self.materials.material(biome,(x,y,z),low,high,ctx)[0]
                material=int(selected)-1 if selected>0 else s['stone']
            ids[y]=material
        runs=[]
        for offset in range(self.maximum-self.minimum):
            material=ids[self.minimum+offset]
            if runs and runs[-1][2]==material:runs[-1]=(runs[-1][0],offset+1,material)
            else:runs.append((offset,offset+1,material))
        return dict(height=first,biome=biome,context=[depth,slope,band,secondary,preliminary],runs=runs)


def exercise(binary,profiles,invalid,gpu):
    worker=Worker(binary,gpu);dispatches=0;records=[];hashes=[];checked=0;maximum=0.
    def call(op,data=b'',status=0):
        nonlocal dispatches
        if status==0 and op in (13,16,20,22):dispatches+=2 if op==22 else 1
        return worker.call(op,data,status=status)
    def error(op,data,code):
        nonlocal dispatches
        # Invalid evaluated material results are rejected after the GPU work.
        # Decode/preparation/halo failures never dispatch that work.
        if op==22 and code==729:dispatches+=2
        assert call(op,data,1)==struct.pack('>I',code),(op,code)
    def build_tile(tile):
        x,z,w,d,lo,hi=tile
        desc=struct.unpack('>11I',call(18,tile_bytes((x-1,z-1,w+2,d+2,lo,hi))))
        desc=tuple(v-(1<<32) if i in range(1,7) and v&(1<<31) else v for i,v in enumerate(desc))
        call(13,encoded(desc));call(16,tile_bytes(tile));ack=struct.unpack('>4I',call(22))
        assert ack[:3]==(w,d,w*d) and ack[3]>=w*d
        return desc,ack
    try:
        for op in (21,22,23,24):error(op,b'',716)
        for name,source,path in profiles:
            signal.alarm(300);print(f'{"GPU" if gpu else "CPU"}: {name} block columns',flush=True)
            call(5,path_request(path));call(11);error(21,b'',725);call(19);call(9);call(15)
            error(22,b'',727);error(23,voxel_bytes([]),727);error(24,query_bytes([]),727)
            ack=struct.unpack('>6I',call(21));assert ack==(source['stone'],source['water'],len(source['materials']),*source['registry_program']['surface_noises'])
            ref=Reference(source);times=[]
            tiles=[(-8,-8,4,3,LOW,HIGH),(29999997,-30000005,3,4,LOW,HIGH),(-(1<<31)+1,(1<<31)-5,2,3,MASK,MASK)]
            if name in ('vanilla','terralith','combined'):tiles=tiles[:2]
            if name=='top_sliver':tiles=[(0,0,8,3,LOW,HIGH)]
            if name in ('ramp','islands','explicit'):
                tiles += [(0,0,1,1,LOW,HIGH),(3,-7,1,11,LOW,HIGH),(-9,4,13,1,LOW,HIGH)]
            for tile in tiles:
                begin=time.perf_counter();desc,ack=build_tile(tile);times.append((time.perf_counter()-begin)*1000)
                x,z,w,d,lo,hi=tile
                pts=[(x,z,lo,hi),(x+w-1,z+d-1,lo,hi)]
                if name not in ('vanilla','terralith','combined'):pts += [(x+w//2,z+d//2,lo,hi)]
                raw=call(24,query_bytes(pts));columns=decode(raw);hashes.append(hashlib.sha256(raw).hexdigest())
                assert call(24,query_bytes(pts))==raw
                ctx=columns[0]['context'];depth,slope,band,secondary,preliminary=ctx
                q=(columns[0]['biome'],x,source['geology_min_y'],z,lo,hi,(1,depth,slope,band,1,secondary,-2147483648.,preliminary))
                ref.materials.verify(call(20,material_bytes([q])),[q])
                assert call(24,query_bytes(pts))==raw
                assert decode(call(24,query_bytes(pts[::-1])))==columns[::-1]
                voxels=[];expected_voxels=[]
                for pt,col in zip(pts,columns):
                    want=ref.column(pt[0],pt[1],desc)
                    assert (col['height'],col['biome'],col['runs'])==(want['height'],want['biome'],want['runs']),(name,pt,col,want)
                    for a,b in zip(col['context'],want['context']):
                        err=abs(a-b)/max(1,abs(b));maximum=max(maximum,err);assert err<.0002,(name,pt,a,b)
                    runs=col['runs'];assert runs[0][0]==0 and runs[-1][1]==source['geology_height']
                    assert all(a[1]==b[0] and a[2]!=b[2] for a,b in zip(runs,runs[1:]))
                    for start,end,material in runs:
                        for y in range(start,end):voxels.append((pt[0],source['geology_min_y']+y,pt[1],lo,hi));expected_voxels.append((1,material))
                for at in range(0,len(voxels),4096):assert list(struct.iter_unpack('>2I',call(23,voxel_bytes(voxels[at:at+4096]))))==expected_voxels[at:at+4096]
                checked+=len(voxels)
                misses=[(x-1,z,lo,hi),(x+w,z,lo,hi),(x,z,lo^1,hi),(x,z,lo,hi^1)]
                assert decode(call(24,query_bytes(misses)))==[None]*len(misses)
                missvox=[(x,source['geology_min_y']-1,z,lo,hi),(x,source['geology_min_y']+source['geology_height'],z,lo,hi),(x-1,0,z,lo,hi)]
                assert call(23,voxel_bytes(missvox))==struct.pack('>2I',0,MASK)*len(missvox)
                error(22,b'\0',603);error(21,b'\0',603)
                for op,data in [(23,b''),(23,struct.pack('>I',4097)),(24,query_bytes([pts[0]]*257)),(24,query_bytes(pts)+b'\0')]:error(op,data,603)
                assert call(24,query_bytes(pts))==raw
                assert call(24,query_bytes([]))==b'' and call(23,voxel_bytes([]))==b''
                assert decode(call(24,query_bytes([pts[0]]*256)))==[columns[0]]*256
                assert call(23,voxel_bytes([voxels[0]]*4096))==struct.pack('>2I',*expected_voxels[0])*4096
                # Rejected operations preserve data; accepted dependencies drop it.
                error(5,path_request(path.with_name('not-found')),702);error(13,b'',717)
                assert call(24,query_bytes(pts))==raw
                call(21);error(23,voxel_bytes([]),727);call(22)
                call(16,tile_bytes(tile));error(24,query_bytes([]),727);call(22)
                call(15);error(24,query_bytes([]),727)
            # Overlapping aligned tiles preserve compact runs and exact biome/context.
            if name=='islands':
                a=(-12,-12,8,8,LOW,HIGH);b=(-8,-8,8,8,LOW,HIGH);common=[(-7,-7,LOW,HIGH),(-5,-6,LOW,HIGH)]
                build_tile(a);left=call(24,query_bytes(common));build_tile(b);assert call(24,query_bytes(common))==left
                desc,_=build_tile((-32,-32,64,64,LOW,HIGH))
                points=[(-32,-32,LOW,HIGH),(31,31,LOW,HIGH),(0,0,LOW,HIGH)]
                for point,col in zip(points,decode(call(24,query_bytes(points)))):
                    want=ref.column(point[0],point[1],desc)
                    assert (col['height'],col['runs'])==(want['height'],want['runs'])
                hashes.append(hashlib.sha256(call(24,query_bytes(points))).hexdigest())
            # A lattice without the slope halo must reject actual block generation.
            central=(0,0,5,5,LOW,HIGH);desc=call(18,tile_bytes(central));call(13,desc);call(16,tile_bytes(central));error(22,b'',728)
            records.append(dict(name=name,tiles=len(tiles),build_surface_and_blocks_host_ms=times))
        for name,path in invalid:
            signal.alarm(300);call(5,path_request(path));call(11);call(19);error(21,b'',726)
        # Material validation occurs after evaluation; malformed results never
        # become a usable column cache. Missing biome targets behave likewise.
        base=copy.deepcopy(profiles[0][1])
        for name,change in [('result_outside_palette',lambda s:s['registry_program']['programs'].__setitem__(3,dict(nodes=[ins(0,p=(100,0,0,0))],roots=[0]))),
                ('result_fraction',lambda s:s['registry_program']['programs'].__setitem__(3,dict(nodes=[ins(0,p=(2.5,0,0,0))],roots=[0]))),
                ('result_nonfinite',lambda s:s['registry_program']['programs'].__setitem__(3,dict(nodes=[ins(0,p=(1e20,0,0,0)),ins(12)],roots=[1]))),
                ('context_nonfinite',lambda s:s['registry_program']['noises'][0].update(amplitude=1e38,coefficients=[1e38])),
                ('missing_biome',lambda s:s.__setitem__('climate_targets',[]))]:
            source=copy.deepcopy(base);change(source);path=ROOT/'build/bend/material-column-profiles'/('invalid-evaluated-'+name+'.rbp')
            path.write_bytes(fixture_wire(source,True));call(5,path_request(path));call(11);call(19);call(21);call(9);call(15)
            tile=(0,0,2,2,LOW,HIGH);desc=call(18,tile_bytes((-1,-1,4,4,LOW,HIGH)));call(13,desc);call(16,tile_bytes(tile));error(22,b'',729);error(23,voxel_bytes([]),727)
        call(4);assert worker.process.wait(timeout=10)==0
        stderr=worker.process.stderr.read().decode();times=[float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)]
        return dict(gpu_required=gpu,profiles=records,voxel_queries_checked=checked,max_context_normalized_error=maximum,invalid_profiles_rejected=len(invalid),
            requests_in_one_process=worker.id,expected_gpu_dispatches=dispatches,metal_commands_ms=times,sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest())
    finally:signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--profile',nargs=2,action='append');args=parser.parse_args()
    profiles,invalid=fixtures(ROOT/'build/bend/material-column-profiles')
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine';version='existing executable' if args.skip_build else build(args.bend,binary,ROOT/'bend/engine.bend')
    report=dict(scope='Derived material context and compact block-run component; complete generated regions pending',bend_version=version,
        runs=[exercise(binary,profiles,invalid,False),exercise(binary,profiles,invalid,True)])
    if args.prove_metal:
        diagnostic=ROOT/'build/bend/material-columns-metal-probe';compile_metal_observer(args.bend,ROOT/'bend/engine.bend',diagnostic)
        result=exercise(diagnostic,profiles,invalid,True);assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches']
        assert result['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=result
    path=ROOT/'build/bend/material-columns-tests.json';path.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: {report["runs"][0]["voxel_queries_checked"]} voxel queries per backend; {path}')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('material columns test timeout')));main()
