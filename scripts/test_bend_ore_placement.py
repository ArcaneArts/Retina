#!/usr/bin/env python3
"""Check Bend spatial ore replay and ore-bearing NBT/zlib on CPU and actual GPU.

The independent replay starts from the captured pre-ore material snapshot,
uses separately implemented attempts/geometry/biome selection and verifies all
final voxels. These component timings are not complete region throughput.
"""
import argparse
from collections import Counter
import copy
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import re
import signal
import struct
import sys
import threading
import time

from test_bend_compression import ROOT, DEFAULT_BEND, build, verify_stream
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_ores import fixtures as ore_fixtures, replacement
from test_bend_ore_planning import attempts, geometry, MASK64
from test_bend_ore_masks import reference_bounds, sphere_distance
from test_bend_material_columns import decode as decode_columns
from test_bend_lake_basins import Reference as TerrainReference
from test_bend_cave_biomes import strata
from test_bend_climate import expected
from test_bend_density import ins
from test_bend_surface import tile_bytes, query_bytes
from test_bend_generated_chunks import request as chunk_request, validate as validate_chunk
from test_bend_noise import MASK

LO,HI=0x87654321,0x80000001


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True)
    base=copy.deepcopy(ore_fixtures(folder/'inputs')[0][0][1])
    base['registry_program']['programs'][1:3]=[dict(nodes=[ins(0,p=(20,0,0,0)),ins(29,1),ins(5,0,1)],roots=[2])]*2
    recipe=dict(id='test:regular',size=32,discard=0.,scattered=False,count_min=8,count_max=12,rarity=1,
        height=dict(min=-8,max=8,triangle=False,plateau=0),
        replacement_bands=[dict(min=-32,max=31,materials=[0,2,0,0,0,0])])
    base['ores']=[recipe]
    for b in base['biomes']:b['ores']=[0]
    result=[]
    def add(name,change=lambda _:None):
        s=copy.deepcopy(base);change(s);p=folder/(name+'.rbp');p.write_bytes(fixture_wire(s));result.append((name,s,p))
    add('regular')
    add('scattered',lambda s:s['ores'][0].update(scattered=True,size=32))
    def priority(s):
        s['ores'].append(dict(copy.deepcopy(recipe),id='test:later',
            replacement_bands=[dict(min=-32,max=31,materials=[0,4,3,0,0,0])]))
        for b in s['biomes']:b['ores']=[0,1,0]
    add('priority',priority)
    add('exposed',lambda s:s['ores'][0].update(discard=1.,height=dict(min=16,max=24,triangle=False,plateau=0),
        replacement_bands=[dict(min=-32,max=31,materials=[0,4,4,4,0,0])]))
    add('air_replacement',lambda s:s['ores'][0].update(height=dict(min=20,max=24,triangle=False,plateau=0),
        replacement_bands=[dict(min=-32,max=31,materials=[1,0,0,0,0,0])]))
    add('no_membership',lambda s:[b.update(ores=[]) for b in s['biomes']])
    add('empty',lambda s:(s.update(ores=[]),[b.update(ores=[]) for b in s['biomes']]))
    s=strata(base)
    for i,b in enumerate(s['biomes']):b['ores']=[0] if i==1 else []
    s['ores'][0].update(height=dict(min=-28,max=24,triangle=False,plateau=0))
    p=folder/'cave_membership.rbp';p.write_bytes(fixture_wire(s));result.append(('cave_membership',s,p))
    return result


class Reference(TerrainReference):
    @lru_cache(maxsize=50000)
    def ore_biome(self,x,y,z,lo,hi):
        surface=self.finalized_biome(x,z,lo,hi)
        if not any(b.get('flags',0)&16 for b in self.source['biomes']):return surface
        qx,qy,qz=x//4*4,y//4*4,z//4*4
        if qy >= self.height_at(qx,qz,lo,hi)-12:return surface
        choice=expected(self.targets,list(self.vertex(0,(qx,qy,qz),lo,hi)),2)
        return surface if choice is None else choice[0]['biome']


def mixed(value):
    value=((value^(value>>30))*0xbf58476d1ce4e5b9)&MASK64
    value=((value^(value>>27))*0x94d049bb133111eb)&MASK64
    return (value^(value>>31))&MASK


def expanded(columns,minimum,height):
    result={}
    for (x,z),c in columns.items():
        a=[None]*height
        for start,end,material in c['runs']:a[start:end]=[material]*(end-start)
        assert all(v is not None for v in a)
        result[x,z]=a
    return result


def replay(source,base,core,lo,hi):
    x,z,width,depth=core;minimum=source['geology_min_y'];height=source['geology_height']
    output={p:v[:] for p,v in base.items()};reference=Reference(source);counts=Counter()
    def exposed(px,py,pz):
        return any(base.get((px+dx,pz+dz),[])[py+dy-minimum] == 0
            if (px+dx,pz+dz) in base and 0<=py+dy-minimum<height else True
            for dx,dy,dz in [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)])
    def emit(id,biome,px,py,pz,bits,ax,az):
        if not (x<=px<x+width and z<=pz<z+depth and minimum<=py<minimum+height):return
        counts['candidates']+=1
        offset=py-minimum;host=output[px,pz][offset]
        material=replacement(source,(id,biome,py,host,bits,exposed(px,py,pz)))
        if material:
            output[px,pz][offset]=material;counts['writes']+=1
            if ax!=px//16 or az!=pz//16:counts['neighbor_anchor_writes']+=1
            if host not in (0,source['stone']):counts['nonstone_writes']+=1
    for az in range(z//16-1,(z+depth)//16+1):
        for ax in range(x//16-1,(x+width)//16+1):
            if not (-134217728<=ax<=134217727 and -134217728<=az<=134217727):continue
            for id,ore in enumerate(source.get('ores',[])):
                for index,px,py,pz,high,low in attempts(source,(id,ax,az,lo,hi)):
                    if not (x-16<=px<x+width+16 and z-16<=pz<z+depth+16 and minimum-16<=py<minimum+height+16):continue
                    biome=reference.ore_biome(px,py,pz,lo,hi)
                    if biome==MASK or id not in source['biomes'][biome].get('ores',[]):continue
                    kind,pieces=geometry(source,(id,high,low));counts['eligible_attempts']+=1
                    if kind==1:
                        for dx,dy,dz,bits in pieces:emit(id,biome,px+dx,py+dy,pz+dz,bits,ax,az)
                        continue
                    origin,dims=reference_bounds(kind,pieces);nx,ny,nz=dims
                    live=[p for p in pieces if p[3]>0]
                    seed=(high<<32)|low
                    for yy in range(ny):
                        by=py+origin[1]+yy
                        if not minimum<=by<minimum+height:continue
                        for zz in range(nz):
                            bz=pz+origin[2]+zz
                            if not z<=bz<z+depth:continue
                            for xx in range(nx):
                                bx=px+origin[0]+xx
                                if not x<=bx<x+width:continue
                                point=(origin[0]+xx+.5,origin[1]+yy+.5,origin[2]+zz+.5)
                                distance=min(sphere_distance(point,p) for p in live)
                                if abs(distance-1)<=.00001:counts['near_float_boundary']+=1
                                if distance<1:emit(id,biome,bx,by,bz,mixed(seed^((yy*nz+zz)*nx+xx)),ax,az)
    return output,counts


def exercise(binary,profiles,gpu):
    worker=Worker(binary,gpu);diagnostics=bytearray();reader=threading.Thread(target=lambda:diagnostics.extend(worker.process.stderr.read()),daemon=True);reader.start()
    digest=hashlib.sha256();records=[];totals=Counter();dispatches=0;voxels=chunks=0;use_caves=False;active_name=''
    def call(op,data=b'',error=0):
        nonlocal dispatches
        start=time.monotonic()
        signal.alarm(600);raw=worker.call(op,data,status=int(bool(error)))
        if active_name in ('vanilla','terralith','combined') and op in (13,16,22,29,50) and not error:
            print(f'  opcode {op}: {time.monotonic()-start:.3f}s (QA under concurrent load)',flush=True)
        if error:assert raw==struct.pack('>I',error),(op,error,raw)
        elif op in (13,16,35,50):dispatches+=1
        elif op in (29,33):dispatches+=2
        elif op==22:dispatches+=2 if gpu else 1
        return raw
    def read_columns(pts):
        return b''.join(call(24,query_bytes(pts[i:i+256])) for i in range(0,len(pts),256))
    def prepare(path):
        call(5,path_request(path))
        for op in (11,19,21,30,9,15,25):call(op)
    def tile(core,lo,hi):
        x,z,w,d=core
        # Resident density covers neighboring ore anchors/quart lookups and the
        # coastal disk, avoiding repeated full density rebuilds at halo anchors.
        halo=23 if active_name in ('vanilla','terralith','combined') else 7
        desc=call(18,tile_bytes((x-halo,z-halo,w+2*halo,d+2*halo,lo,hi)));call(13,desc)
        call(16,tile_bytes((x-1,z-1,w+2,d+2,lo,hi)));call(29);call(22)
        if use_caves:call(33);call(35)
        pts=[(xx,zz,lo,hi) for zz in range(z-1,z+d+1) for xx in range(x-1,x+w+1)]
        cols=decode_columns(read_columns(pts));return {(q[0],q[1]):c for q,c in zip(pts,cols)}
    try:
        call(50,tile_bytes((0,0,16,16,LO,HI)),755)
        for name,source,path in profiles:
            active_name=name
            print('checking','GPU' if gpu else 'CPU',name,flush=True);prepare(path);use_caves=name=='cave_membership'
            cores=[(0,0,16,16)]
            if name=='regular':cores=[(-16,-16,32,32),(29999984,-30000000,16,16),(-2147483632,2147483616,16,16)]
            profile_counts=Counter()
            for core in cores:
                before=tile(core,LO,HI);base=expanded(before,source['geology_min_y'],source['geology_height'])
                want,counts=replay(source,base,core,LO,HI)
                x,z,w,d=core;pts=[(xx,zz,LO,HI) for zz in range(z-1,z+d+1) for xx in range(x-1,x+w+1)]
                ack=struct.unpack('>4I',call(50,tile_bytes((*core,LO,HI))))
                assert ack[:3]==(w+2,d+2,(w+2)*(d+2))
                raw=read_columns(pts);after=decode_columns(raw);actual=expanded({(q[0],q[1]):c for q,c in zip(pts,after)},source['geology_min_y'],source['geology_height'])
                assert actual==want,(name,core,next(((p,i,a,b) for p in actual for i,(a,b) in enumerate(zip(actual[p],want[p])) if a!=b),None))
                for p,col in zip(pts,after):
                    start=0;previous=None
                    for a,b,m in col['runs']:assert a==start and a<b and m!=previous;start=b;previous=m
                    assert start==source['geology_height']
                    if x<=p[0]<x+w and z<=p[1]<z+d:
                        ids=actual[p[0],p[1]];solid=[source['geology_min_y']+i+1 for i,m in enumerate(ids) if m not in (0,source['water'],source['lava'])]
                        assert col['height']==(max(solid) if solid else source['geology_min_y'])
                voxels+=(w+2)*(d+2)*source['geology_height'];digest.update(raw);profile_counts.update(counts)
                if name in ('regular','priority','air_replacement','cave_membership') and core==cores[0]:
                    cx,cz=x//16,z//16;chunk=chunk_request(cx,cz,LO,HI,5023)
                    selected=[after[(zz-(z-1))*(w+2)+(xx-(x-1))] for zz in range(z,z+16) for xx in range(x,x+16)]
                    biome_ids=None
                    if use_caves:
                        ref=Reference(source)
                        biome_ids=[ref.ore_biome(cx*16+qx*4,yy,cz*16+qz*4,LO,HI)
                            for yy in range(source['geology_min_y'],source['geology_min_y']+source['geology_height'],4)
                            for qz in range(4) for qx in range(4)]
                    nbt=call(26,chunk);validate_chunk(nbt,source,selected,cx,cz,5023,biome_ids=biome_ids,fluid_materials={source['water'],source['lava']})
                    compressed=call(27,chunk);verify_stream(compressed,nbt);chunks+=1
                # Rebuild exactly the pre-ore snapshot before repeated/reordered transforms.
                tile(core,LO,HI);call(50,tile_bytes((*core,LO,HI)));assert read_columns(pts)==raw
                for body in (b'',b'\0',tile_bytes((*core,LO,HI))+b'\0'):call(50,body,603)
                call(50,tile_bytes((x+1,z,w,d,LO,HI)),756)
                call(50,tile_bytes((*core,LO^1,HI)),756)
                assert read_columns(pts)==raw,'rejected core changed snapshot'
                if name=='regular' and w==32:
                    for px,pz in [(x+16,z+16),(x,z+16),(x+16,z),(x,z)]:
                        small=(px,pz,16,16);tile(small,LO,HI);call(50,tile_bytes((*small,LO,HI)))
                        q=[(xx,zz,LO,HI) for zz in range(pz,pz+16) for xx in range(px,px+16)]
                        small_cols=decode_columns(read_columns(q))
                        for pos,c in zip(q,small_cols):
                            assert c['runs']==after[(pos[1]-(z-1))*(w+2)+(pos[0]-(x-1))]['runs'],'tile order or boundary seam'
                    totals['independent_adjacent_cores']+=4
            if name in ('no_membership','empty'):assert profile_counts['writes']==0
            if name in ('regular','scattered','priority','air_replacement'):assert profile_counts['writes']>0
            if name=='regular':assert profile_counts['neighbor_anchor_writes']>0
            records.append(dict(name=name,**profile_counts));totals.update(profile_counts)
            call(5,path_request(path));call(50,tile_bytes((0,0,16,16,LO,HI)),755)
        call(4);assert worker.process.wait(timeout=20)==0;reader.join(timeout=20);assert not reader.is_alive()
        text=diagnostics.decode(errors='replace');assert not any(s in text for s in ('ERR_','GPU execution failed')),text
        return dict(gpu_required=gpu,profiles=records,totals=dict(totals),voxels_checked=voxels,nbt_zlib_chunks=chunks,sha256=digest.hexdigest(),minimum_gpu_commands=dispatches,
            metal_commands_ms=[float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',text)])
    finally:
        signal.alarm(0);failed=sys.exc_info()[0] is not None
        try:worker.close()
        except BaseException:
            if not failed:raise
        finally:
            reader.join(timeout=5)
            if failed:print(diagnostics.decode(errors='replace'),flush=True)
            (ROOT/'build/bend'/('ore-placement-gpu-stderr.log' if gpu else 'ore-placement-cpu-stderr.log')).write_bytes(diagnostics)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--metal-probe',type=Path)
    parser.add_argument('--profile',nargs=2,action='append',default=[]);parser.add_argument('--output',type=Path);args=parser.parse_args()
    binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    profiles=fixtures(ROOT/'build/bend/ore-placement-profiles')
    for js,rbp in args.profile:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(rbp)))
    report=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,False),exercise(binary,profiles,True)])
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/ore-placement-metal-probe'
        if args.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        proof=exercise(probe,profiles,True);assert len(proof['metal_commands_ms'])>=proof['minimum_gpu_commands']
        assert proof['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=proof
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest();report['bend_version']='2.0.36'
    output=args.output or ROOT/'build/bend/ore-placement-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('ore placement test timeout')))
    main()
