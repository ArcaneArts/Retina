#!/usr/bin/env python3
"""Independent cave-field, carving and compressed-chunk checks.

This component uses registered density/carvers, coherent tunnel noise and finite
rounded ravines. Cave biomes, aquifers, decorations and world integration remain
pending. Correctness-run timings are not a generator benchmark.
"""
import argparse
import copy
from collections import Counter
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
import subprocess
import threading
import zlib

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_geology import fixtures as geology_fixtures, encode
from test_bend_density import Oracle, ins
from test_bend_registry_climate import projected
from test_bend_climate import expected
from test_bend_surface import SurfaceReference
from test_bend_surface import tile_bytes, query_bytes
from test_bend_material_columns import decode as decode_columns
from test_bend_generated_chunks import request as chunk_request, validate as validate_chunk
from test_bend_noise import MASK, f32, mix_hash


def gradient_noise(point, frequency, seed):
    # Exact products of binary F32 factors in Python's wider arithmetic avoid
    # copying Bend's compensated implementation or its tree interpolation.
    scaled=[x*frequency for x in point]
    cells=[math.floor(x) for x in scaled];fractions=[x-i for x,i in zip(scaled,cells)]
    result=0.
    for bits in itertools.product((0,1),repeat=3):
        c=[(x+b)&MASK for x,b in zip(cells,bits)];d=[x-b for x,b in zip(fractions,bits)]
        h=mix_hash(((c[0]*2654435769)^(c[1]*2246822507)^(c[2]*3266489909)^seed)&MASK)&15
        a=d[0] if h<8 else d[1];b=d[1] if h<4 else d[0] if h in (12,14) else d[2]
        corner=(-a if h&1 else a)+(-b if h&2 else b)
        fade=[t*t*t*(t*(t*6-15)+10) for t in fractions]
        result+=corner*math.prod(t if b else 1-t for t,b in zip(fade,bits))
    return result*1.6


def stack(source, channel, point, seed):
    value=source['cave_noises'][channel];frequency=f32(value['frequency']);persistence=1.;total=weights=0.
    for i,modifier in enumerate(value['modifiers']):
        if frequency>.125:break
        weight=f32(max(0.,f32(modifier))*persistence)
        if weight>0:
            total+=gradient_noise(point,frequency,(seed+channel*7919+i*1013)&MASK)*weight
            weights=f32(weights+weight)
        frequency=f32(frequency*2);persistence=f32(persistence*.5)
    return total/max(weights,f32(.0001))*f32(value['amplitude'])


def canyon(settings, point, seed):
    if settings['kind']!=1 or settings['probability']==0:return 1000.
    cx,cz=math.floor(point[0]/128),math.floor(point[2]/128);best=1000.
    probability=f32(1.-f32(math.pow(f32(1.-f32(settings['probability'])),64.)))
    def u(value):return (value&MASK)
    def rnd(value):return f32((value&65535)/65535.)
    def lerp(a,b,t):return a+(b-a)*t
    def variation(name, fallback, t):
        a,b=settings.get(name,(0.,0.));return lerp(a,b,t) if b>0 else fallback
    for x in range(cx-1,cx+2):
        for z in range(cz-1,cz+2):
            h=mix_hash(u((u(x)*2654435769)^(u(z)*2246822507)^seed^17863))
            if rnd(h)>=probability:continue
            rx=f32(((h>>16)&255)/255.);ry=f32(((mix_hash(h)>>8)&255)/255.);rz=f32((mix_hash(u(h+1))&255)/255.)
            thickness=variation('thickness_range',settings['thickness'],rx)
            horizontal=variation('horizontal_range',settings['horizontal'],ry)
            vertical=variation('vertical_range',settings['vertical'],rz)
            length=max(1.,56.*variation('distance_range',settings['room'],rz))
            hr=rnd(mix_hash(u(h+3)));a,b=settings['height']['min'],settings['height']['max'];center_y=lerp(a,b,hr)
            plateau=settings['height']['plateau']
            if plateau>0:
                span=b-a;slope=(span-max(0.,min(span,plateau-1)))*.5
                center_y=a+hr*slope+rnd(mix_hash(u(h+4)))*(span-slope)
            dx=point[0]-(x+.25+ry*.5)*128;dz=point[2]-(z+.25+rz*.5)*128;dy=point[1]-center_y
            angle=f32(f32(((h>>8)&65535)/65535.)*f32(6.2831853))
            along=dx*math.cos(angle)+dz*math.sin(angle);side=-dx*math.sin(angle)+dz*math.cos(angle);t=along/length
            vdefault=settings.get('vertical_default',0.);vcenter=settings.get('vertical_center',0.);has=vdefault>0 or vcenter>0
            max_y=(1.5+thickness)*vertical*(max(.2,vdefault+vcenter) if has else 1.)*1.25+12.
            if abs(dy)>max_y or abs(t)>=1 or abs(side)>length*.18+(1.5+thickness)*horizontal*1.5+6:continue
            phase=h&31;rotation=lerp(*settings.get('rotation_range',(0.,0.)),hr)
            bend=math.sin(t*2.2+phase)*length*.10+gradient_noise((along*.035,phase,0.),1.,u(seed+19391))*4.
            height_bend=math.sin(t*2.7+phase)*3.+math.sin(rotation)*math.sin(t*1.8)*8.
            radius=1.5+thickness*math.sqrt(max(0.,1-t*t))
            edge=gradient_noise((along*.06,point[1]/(4.*max(settings.get('width_smoothness',0.),1.)),phase),1.,u(seed+24107))
            width=max(.5,radius*horizontal*(.82+edge*.18))
            factor=max(.2,vdefault+vcenter*(1-abs(t))) if has else 1.
            vr=max(2.,radius*vertical*factor*(.85+edge*.10))
            wall=gradient_noise(point,f32(.045),u(seed+31337))*.6
            best=min(best,((side-bend+wall)/width)**2+((dy-height_bend)/vr)**2+t*t)
    return best


class Reference:
    def __init__(self,source):
        self.source=source;self.model=json.loads(json.dumps(source['registry_program']),parse_float=lambda x:f32(float(x)))
        self.oracle=Oracle(self.model);self.targets=projected(source)
        self.surface=SurfaceReference(source);self.has_caves=any(t["flags"]&16 for t in self.targets)
    @functools.lru_cache(maxsize=100000)
    def node_biome(self,point,low,high):
        if not self.has_caves:return MASK
        x,y,z=point;source=self.source;bottom=source['geology_min_y'];top=bottom+source['geology_height']
        if source['registry_program']['surface'][2]:bottom=top=0
        desc=(1,x,bottom,z,x,top,z,*source['registry_program']['terrain_cell'],low,high)
        height=self.integer_height(x,z,low,high,desc)
        if y>=height-12:return MASK
        climate=self.oracle.evaluate(self.model['programs'][0],point,low,high)
        choice=expected(self.targets,list(climate),2)
        return MASK if choice is None else choice[0]['biome']
    @functools.lru_cache(maxsize=100000)
    def integer_height(self,x,z,low,high,desc):
        minimum=self.source['geology_min_y'];maximum=minimum+self.source['geology_height']
        height=math.floor(self.surface.height(x,z,desc))
        if self.source['registry_program']['surface'][2]:return height
        # Continuous crossings only supply a search hint. Independent integer
        # density tests decide the exact first-free block, including zero density.
        while height>minimum and self.surface.query((1,x,height-1,z,low,high),desc)[0]<=0:height-=1
        while height<maximum and self.surface.query((1,x,height,z,low,high),desc)[0]>0:height+=1
        return height
    def biome(self,q,layout):
        x,y,z,low,high=q;origin,counts,seeds=layout
        if (low,high)!=seeds or not all(o<=p<=o+4*(n-1) for p,o,n in zip((x,y,z),origin,counts)):return (0,MASK)
        return (1,self.node_biome(tuple(o+math.floor((p-o)/4)*4 for p,o in zip((x,y,z),origin)),low,high))
    @functools.lru_cache(maxsize=100000)
    def node(self,point,low,high):
        seed=low^mix_hash(high);rough=stack(self.source,4,point,seed)
        climate=self.oracle.evaluate(self.model['programs'][0],(point[0],0,point[2]),low,high)
        choice=expected(self.targets,list(climate),0)
        id=self.node_biome(point,low,high)
        if id==MASK:id=MASK if choice is None else choice[0]['biome']
        carvers=[] if id==MASK else self.source['biomes'][id]['carvers']
        return [self.oracle.evaluate(self.model['programs'][2],point,low,high)[0],
            stack(self.source,1,(point[0]*f32(1.6),point[1],point[2]*f32(1.6)),seed)+rough*f32(.06),
            stack(self.source,2,(point[0]*f32(1.6),point[1],point[2]*f32(1.6)),seed)-rough*f32(.06),
            min([1000.]+[canyon(c,point,seed) for c in carvers])]
    def query(self,q,layout):
        x,y,z,low,high=q;origin,counts,seeds=layout
        relative=[(p-o)/4 for p,o in zip((x,y,z),origin)]
        if (low,high)!=seeds or not all(0<=p<=n-1 for p,n in zip(relative,counts)):return [0.,0.,0.,0.]
        indices=[min(math.floor(p),n-2) for p,n in zip(relative,counts)];fractions=[p-i for p,i in zip(relative,indices)]
        out=[0.]*4
        for bits in itertools.product((0,1),repeat=3):
            p=tuple(o+(i+b)*4 for o,i,b in zip(origin,indices,bits));weight=math.prod(t if b else 1-t for t,b in zip(fractions,bits))
            for k,value in enumerate(self.node(p,low,high)):out[k]+=weight*value
        return out


def smooth(a,b,x):
    t=max(0.,min(1.,(x-a)/(b-a)));return t*t*(3-2*t)


def expected_carve(source,column,x,y,z,values,low,high,biome=None):
    height=column['height'];minimum=source['geology_min_y'];material=next(m for a,b,m in column['runs'] if a<=y-minimum<b)
    if not source['carveable'][material] or y<minimum+5 or y>=height or (height<=source['sea_level'] and y>=height-4):return material
    seed=low^mix_hash(high)
    aperture=gradient_noise((x,0,z),f32(.012),(seed+43117)&MASK)+gradient_noise((x,0,z),f32(.026),(seed+53731)&MASK)*.25
    roof=(1-smooth(.18,.45,aperture))*(1-smooth(0,24,height-1-y))
    chamber,a,b,ribbon=values;carved=y<height-1 and chamber<-.20*roof
    for c in source['biomes'][column['biome'] if biome is None or biome==MASK else biome]['carvers']:
        if c['probability']<=0:continue
        if c['kind']==1:carved|=ribbon<1.-roof*.25;continue
        margin=max(4.,c['thickness']*c['vertical']*4);lower=c['height']['min']-margin;upper=c['height']['max']+margin
        if not lower<=y<=upper:continue
        fade=max(0.,min(1.,min(y-lower,upper-y)*.125));strength=max(0.,min(2.,c['probability']*c['count']))
        width=(.042+c['thickness']*.028)*c['horizontal']*math.sqrt(max(strength,.001))*(1-roof*.92)
        floor=max(0.,min(.06,(c['floor']+1)*.03))
        carved|=max(abs(a),abs(b)*c['vertical'])<width*fade-floor
    return (source['lava'] if y<source['lava_level'] else 0) if carved else material


def fixtures(folder):
    source=copy.deepcopy(geology_fixtures(folder/'geology')[0][0][1]);source['sea_level']=-20
    terrain=dict(nodes=[ins(29,1),ins(0,p=(12,0,0,0)),ins(5,1,0)],roots=[2])
    source['registry_program']['programs'][1]=terrain
    # One analytic ellipsoid and its opposite sign give unambiguous cave and
    # protected-surface fixtures; the graph is not a copy of the Bend algorithm.
    nodes=[]
    def node(op,a=0,b=0,p=(0,0,0,0)):
        nodes.append(ins(op,a,b,p=p));return len(nodes)-1
    terms=[]
    for axis,center,radius in [(0,12.,10.),(1,-8.,7.),(2,12.,10.)]:
        v=node(29,axis);c=node(0,p=(center,0,0,0));d=node(5,v,c);square=node(6,d,d);factor=node(0,p=(1/(radius*radius),0,0,0));terms.append(node(6,square,factor))
    xy=node(4,terms[0],terms[1]);xyz=node(4,xy,terms[2]);one=node(0,p=(1,0,0,0));root=node(5,xyz,one)
    source['registry_program']['programs'][2]=dict(nodes=nodes,roots=[root])
    result=[]
    def add(name,change=lambda _:None):
        v=copy.deepcopy(source);change(v);p=folder/(name+'.rbp');p.write_bytes(fixture_wire(v,True));result.append((name,v,p))
    add('ellipsoid',lambda s:[b.update(carvers=[]) for b in s['biomes']])
    add('protected',lambda s:s.update(carveable=[False]*len(s['materials'])))
    add('tunnels')
    def ravines(s):
        s['registry_program']['programs'][2]=dict(nodes=[ins(0,p=(1,0,0,0))],roots=[0])
        c=copy.deepcopy(source['biomes'][0]['carvers'][1]);c.update(probability=1.,height=dict(min=-16,max=-4,triangle=True,plateau=2),
            thickness=4.,horizontal=2.,vertical=2.,room=1.,thickness_range=[4.,4.],horizontal_range=[2.,2.],
            vertical_range=[2.,2.],distance_range=[1.,1.],vertical_default=1.,vertical_center=.2)
        for b in s['biomes']:b['carvers']=[copy.deepcopy(c)]
    add('ravines',ravines)
    def roof(s):
        s['registry_program']['programs'][2]=dict(nodes=[ins(0,p=(-1,0,0,0))],roots=[0])
        s['lava_level']=-10
    add('dry_roof',roof)
    def wet(s):
        roof(s);s['sea_level']=20
    add('wet_roof',wet)
    return result


def exercise(binary,profiles,gpu):
    worker=Worker(binary,gpu);dispatches=0;hashes=[];records=[];checks=blocks=biome_checks=0;maximum=0.
    diagnostic_bytes=bytearray()
    # A full loaded-profile run observes hundreds of commands. Drain actual
    # diagnostics while the process runs so stderr pipe capacity cannot stall it.
    diagnostics_reader=threading.Thread(target=lambda:diagnostic_bytes.extend(worker.process.stderr.read()),daemon=True)
    diagnostics_reader.start()
    def call(op,data=b'',status=0):
        nonlocal dispatches
        signal.alarm(300)
        out=worker.call(op,data,status=status)
        if status==0:dispatches+={13:1,16:1,22:2 if gpu else 1,29:2,33:2,34:1,35:1,36:1}.get(op,0)
        return out
    def error(op,data,code):assert call(op,data,1)==struct.pack('>I',code),(op,code)
    def fields(points):
        return list(struct.iter_unpack('>I4f',call(34,encode(points))))
    def columns(points):
        # Run-length column replies have an existing 256-column bound.
        return b''.join(call(24,query_bytes(points[i:i+256])) for i in range(0,len(points),256))
    def generate(tile):
        x,z,width,depth,low,high=tile
        desc=call(18,tile_bytes((x-1,z-1,width+2,depth+2,low,high)))
        call(13,desc);call(16,tile_bytes(tile));call(22)
        return struct.unpack('>8I',call(33))
    def verify_fields(ref,qs,ack,low,high):
        nonlocal checks,maximum,biome_checks
        origin=tuple(v-(1<<32) if v&(1<<31) else v for v in ack[:3]);counts=ack[3:6]
        layout=(origin,counts,(low,high));raw=call(34,encode(qs));actual=list(struct.iter_unpack('>I4f',raw))
        for q,row in zip(qs,actual):
            covered=q[3:]==(low,high) and all(o<=p<=o+4*(n-1) for p,o,n in zip(q[:3],origin,counts))
            assert row[0]==covered,(q,ack,row)
            want=ref.query(q,layout)
            for k,(a,b) in enumerate(zip(row[1:],want)):
                error_value=abs(a-b)/max(1,abs(b));maximum=max(maximum,error_value)
                assert math.isfinite(a) and error_value<.002,(q,k,a,b,error_value)
                checks+=1
        biome_rows=list(struct.iter_unpack('>2I',call(36,encode(qs))))
        assert biome_rows==[ref.biome(q,layout) for q in qs],[(q,row,ref.biome(q,layout)) for q,row in zip(qs,biome_rows) if row!=ref.biome(q,layout)]
        biome_checks+=len(qs)
        assert call(36,encode(qs[::-1]))==b''.join(struct.pack('>2I',*row) for row in biome_rows[::-1])
        return raw,actual
    try:
        error(33,b'',739);error(34,encode([]),740);error(36,encode([]),740);error(35,b'',741)
        for name,source,path in profiles:
            print('checking', 'GPU' if gpu else 'CPU',name,flush=True)
            call(5,path_request(path));call(11);call(19);call(21);call(25);call(9);call(15);call(30)
            low,high=0x87654321,0x80000001
            ox,oz=(32,32) if name=='ravines' else ((-16,0) if name=='shore_surface_biomes' else (0,0))
            tile=(ox,oz,24,24,low,high)
            halo=6 if name=='shore_surface_biomes' else 1
            desc=call(18,tile_bytes((ox-halo,oz-halo,24+2*halo,24+2*halo,low,high)));call(13,desc);call(16,tile_bytes(tile))
            if name=='shore_surface_biomes':call(29)
            call(22)
            points=[(ox+i%24,oz+i//24,low,high) for i in range(24*24)]
            before_bytes=columns(points);before=decode_columns(before_bytes)
            ack=struct.unpack('>8I',call(33));origin=tuple(v-(1<<32) if v&(1<<31) else v for v in ack[:3]);counts=ack[3:6]
            assert ack[6:]==(math.prod(counts),4)
            assert origin==(ox,source['geology_min_y']//4*4,oz)
            assert counts==(7,math.ceil((source['geology_min_y']+source['geology_height']-origin[1])/4)+1,7)
            ref=Reference(source);rng=random.Random(20261008)
            qs=[(rng.randrange(ox,ox+25),rng.randrange(source['geology_min_y'],source['geology_min_y']+source['geology_height']),rng.randrange(oz,oz+25),low,high) for _ in range(100)]
            qs+=[(ox,origin[1],oz,low,high),(ox+24,origin[1]+(counts[1]-1)*4,oz+24,low,high)]
            qs+=[(ox-1,origin[1],oz,low,high),(ox,origin[1],oz,low^1,high),(ox,origin[1],oz,low,high^1)]
            raw,actual=verify_fields(ref,qs,ack,low,high)
            assert raw==call(34,encode(qs))
            assert fields(qs[::-1])==actual[::-1]
            assert call(34,encode([qs[0]]*4096))==raw[:20]*4096
            assert call(34,encode([]))==b''
            assert call(36,encode([]))==b''
            assert call(36,encode([qs[0]]*4096))==struct.pack('>2I',*ref.biome(qs[0],(origin,counts,(low,high))))*4096
            for op,data in [(33,b'\0'),(34,b''),(34,encode(qs[:1])+b'\0'),(34,struct.pack('>I',4097)),(35,b'\0'),(36,b''),(36,encode(qs[:1])+b'\0'),(36,struct.pack('>I',4097))]:error(op,data,603)
            assert columns(points)==before_bytes
            # Check every carved voxel against independently written rules using
            # separately verified sampled field values. No threshold epsilon or
            # classification mismatch is permitted in the carved block result.
            voxels=[(x,y,z,low,high) for z in range(oz,oz+24) for x in range(ox,ox+24) for y in range(source['geology_min_y'],source['geology_min_y']+source['geology_height'])]
            sampled=[];biomes=[]
            for start in range(0,len(voxels),4096):
                group=voxels[start:start+4096];sampled+=fields(group)
                actual_biomes=list(struct.iter_unpack('>2I',call(36,encode(group))))
                wanted_biomes=[ref.biome(q,(origin,counts,(low,high))) for q in group]
                assert actual_biomes==wanted_biomes,(name,group[0],actual_biomes[:8],wanted_biomes[:8])
                biomes+=actual_biomes;biome_checks+=len(group)
            call(35);after=decode_columns(columns(points))
            removed=0
            for q,row,biome in zip(voxels,sampled,biomes):
                x,y,z,_,_=q;index=(z-oz)*24+x-ox;col=before[index];want=expected_carve(source,col,x,y,z,row[1:],low,high,biome[1])
                actual_block=next(m for a,b,m in after[index]['runs'] if a<=y-source['geology_min_y']<b)
                assert actual_block==want,(name,q,actual_block,want,row,col)
                removed+=want!=next(m for a,b,m in col['runs'] if a<=y-source['geology_min_y']<b);blocks+=1
            if name=='shore_surface_biomes':
                assert any(source['biomes'][col['biome']]['flags']&64 for col in before)
                assert any(row[1]==4 for row in biomes)
            if name=='ellipsoid':assert removed>100
            if name=='protected':assert removed==0
            if name=='strata':
                assert removed>100
                assert set(row[1] for row in biomes)=={0,1,2,3,MASK}
            if name=='ravines':assert removed>100,removed
            if name in ('dry_roof','wet_roof'):
                assert any(m==source['lava'] for c in after for a,b,m in c['runs'])
                if name=='wet_roof':
                    assert all(next(m for a,b,m in c['runs'] if a<=10-source['geology_min_y']<b)!=0 for c in after)
                else:assert all(next(m for a,b,m in c['runs'] if a<=10-source['geology_min_y']<b)==0 for c in after)
            # Final NBT/heightmaps and zlib consume the carved resident columns.
            chunk=chunk_request(ox//16,oz//16,low,high,5023);raw_chunk=call(26,chunk)
            chunk_columns=[after[z*24+x] for z in range(16) for x in range(16)]
            chunk_biomes=[]
            for sy in range(source['geology_height']//16):
                for i in range(64):
                    q=(ox+(i%4)*4,source['geology_min_y']+sy*16+(i//16)*4,oz+((i//4)%4)*4,low,high)
                    id=ref.biome(q,(origin,counts,(low,high)))[1]
                    chunk_biomes.append(chunk_columns[(i%4)*4+((i//4)%4)*4*16]['biome'] if id==MASK else id)
            validate_chunk(raw_chunk,source,chunk_columns,ox//16,oz//16,5023,chunk_biomes)
            assert zlib.decompress(call(27,chunk))==raw_chunk
            old=columns(points);call(35);assert old==columns(points)
            call(25);assert raw==call(34,encode(qs))
            call(22);error(34,encode(qs),740)
            call(33);call(21);error(34,encode(qs),740);error(36,encode(qs),740)
            hashes.extend([hashlib.sha256(raw).hexdigest(),hashlib.sha256(raw_chunk).hexdigest()])
            records.append(dict(name=name,cave_layout=list(ack),voxels_checked=len(voxels),changed_voxels=removed,biome_id_counts=dict(Counter(row[1] for row in biomes)),
                field_sha256=hashlib.sha256(raw).hexdigest(),chunk_sha256=hashlib.sha256(raw_chunk).hexdigest()))
            if name in ('ellipsoid','non_power_biomes'):
                # Global node alignment must make overlapping tile results
                # byte-identical regardless of which tile was requested first.
                common=[(x,y,z,low,high) for x in (13,16,19) for z in (13,16,19) for y in (-31,-8,11)]
                a=generate((8,8,17,17,low,high));first,_=verify_fields(ref,common,a,low,high)
                b=generate((12,12,9,9,low,high));second,_=verify_fields(ref,common,b,low,high)
                assert first==second
                generate((8,8,17,17,low,high));assert first==call(34,encode(common))
                for x,z,width,depth in [(-513,-511,17,19),(-30000000,29999968,24,24),
                        (-2147483644,2147483620,17,17),(0,0,1,1),(3,-3,1,9),(3,-3,9,1),(7,7,1,9),(7,7,9,1)]:
                    edge=generate((x,z,width,depth,low,high))
                    probes=[(px,py,pz,low,high) for px in (x,x+width-1) for pz in (z,z+depth-1) for py in (-32,-9,31)]
                    data,_=verify_fields(ref,probes,edge,low,high);hashes.append(hashlib.sha256(data).hexdigest())
                changed=generate((8,8,17,17,low,high^1));other,_=verify_fields(ref,[(x,y,z,lo,hi^1) for x,y,z,lo,hi in common],changed,low,high^1)
                if name=='ellipsoid':assert other!=first
                error(35,b'\0',603)
        call(4);assert worker.process.wait(timeout=10)==0
        diagnostics_reader.join(timeout=5);assert not diagnostics_reader.is_alive()
        diagnostics=diagnostic_bytes.decode()
        return dict(gpu_required=gpu,profiles=records,field_scalars_checked=checks,carved_voxels_checked=blocks,biome_ids_checked=biome_checks,maximum_relative_reference_error=maximum,
            expected_gpu_dispatches=dispatches,metal_commands_ms=[float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostics)],
            sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest(),requests_in_one_process=worker.id)
    finally:signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true')
    parser.add_argument('--metal-probe',type=Path);parser.add_argument('--profile',nargs=2,action='append');args=parser.parse_args()
    profiles=fixtures(ROOT/'build/bend/cave-profiles')
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    report=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,False),exercise(binary,profiles,True)])
    # F32 transcendentals may differ between CPU and Metal. Every backend must
    # meet the numerical oracle and exact voxel rules and repeat within itself;
    # cross-backend byte determinism is explicitly outside the generator goal.
    report['cross_backend_byte_identical']=report['runs'][0]['sha256']==report['runs'][1]['sha256']
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/cave-metal-probe'
        if args.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        result=exercise(probe,profiles,True)
        assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches'],(len(result['metal_commands_ms']),result['expected_gpu_dispatches'])
        assert result['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=result
        report['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest()
    report['bend_version']=subprocess.check_output([str(DEFAULT_BEND),'version'],text=True).strip()
    output=ROOT/'build/bend/cave-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('cave test timeout')))
    main()
