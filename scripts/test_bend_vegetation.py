#!/usr/bin/env python3
"""Coordinate real placement programs and feature writers on CPU/observed Metal.

Complete CPU replay and bounded observed-Metal replay use independent Python
heap/voxel checks for live heightmaps, RNG, recipe IDs, work-slice resumption,
unavailable families and biome membership. A seven-family fixture completes on
both backends; completed single-anchor overlays cross merge/NBT/zlib/MCA. Independent
anchors still need role-aware global merging before playable integration; this
fixture does not claim complete-region generation or lighting.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import re
import struct

from test_bend_compression import ROOT,DEFAULT_BEND,build,command,metal_compiler_command
from test_bend_engine import compile_metal_observer
from test_bend_registry_noise import fixture_wire
from test_bend_placement_merge import sources
from test_bend_placement_programs import World as SmallWorld
from test_bend_placement_walker import walk
from test_bend_surface_features import place,KINDS
from test_bend_trees import World as TreeWorld,origin,plan,replay,Random
from test_bend_noise import MASK
from test_bend_chunks import palette,unpack,MAP_NAMES
from test_bend_formats import read_region

KNOWN=(*KINDS,'tree')
SEED=0x80000000075bcd15


class World(TreeWorld):
    minimum,maximum=-64,320

    def flags(self,material):
        return 0 if material is None else self.source['material_flags'][material]

    def height(self,x,z,map_):
        if self.column(x,z) is None:return None
        for y in range(self.maximum-1,self.minimum-1,-1):
            if self.source['heightmap_masks'][self.material(x,y,z)]&(1<<map_):return y+1
        return self.minimum

    def biome(self,x,y,z):
        col=self.column(x,z)
        return None if col is None else col%len(self.source['biomes'])

    def ground(self,x,z,layer=None):
        return super().ground(x,z) if layer is None else SmallWorld.ground(self,x,z,layer)

    def empty(self,material):return SmallWorld.empty(self,material)


def cases(source):
    prefix=source.get('coordinator_prefix_recipe')
    known=[i for i,r in enumerate(source['decorations']) if r['kind'] in KNOWN and i!=prefix]
    missing=[i for i,r in enumerate(source['decorations']) if r['kind'] not in KNOWN]
    result=[[12,64,100000,*known],[12,1,1000000,*reversed(known)],
            [12,7,100000,*known,*known],[13,17,100000,*known],
            [14,31,100000,*known],[15,127,100000,*known],
            [12,1,1,*known],[12,0,1,*known],[12,1,1],
            [12,17,100000,*missing]]
    if prefix is not None:result += [[12,1,64,prefix],[12,8,8,prefix],[12,64,1,prefix]]
    return result


def gpu_cases(source):
    if 'coordinator_prefix_recipe' in source:return cases(source)
    known=[i for i,r in enumerate(source['decorations']) if r['kind'] in KNOWN]
    missing=[i for i,r in enumerate(source['decorations']) if r['kind'] not in KNOWN]
    return [[12,1,1],[12,64,32,*known],[12,1,2048,*reversed(known)],
            [12,8,256,*known,*known],[12,64,32,*missing]]


def fixture(base):
    source=copy.deepcopy(base)
    yes={'type':'true'}
    offset=lambda x,z:dict(type='offset',x=x,z=z)
    height=dict(type='heightmap',map=4)
    column=lambda h:dict(kind='block_column',layers=[dict(height=h,provider=dict(type='state',material=1))],
                         direction=[0,1,0],allowed=yes,prioritize_tip=False)
    simple=dict(kind='simple_block',provider=dict(type='state',material=1),
                states=[dict(source=1,lower=[1,1],upper=[0,0],survival=yes,upper_allowed=yes)],water=yes)
    tree=copy.deepcopy(next(r for r in base['decorations'] if r['kind']=='tree' and r['trunk_shape']=='fancy_trunk_placer'))
    plant=copy.deepcopy(next(r for r in base['decorations'] if r['kind']=='plant'))
    plant['states']=[dict(plant['states'][0],weight=1.,band=0,dry=False)]
    aquatic=copy.deepcopy(next(r for r in base['decorations'] if r['kind']=='aquatic'))
    aquatic['states']=[dict(aquatic['states'][0],weight=1.,band=0,survival=yes,upper_allowed=yes)]
    bamboo=copy.deepcopy(next(r for r in base['decorations'] if r['kind']=='bamboo'))
    bamboo.update(probability=0.,survival=yes,podzol_allowed=yes)
    rows=[(column(2),[offset(8,8),height]),
          (column(1),[offset(8,8),dict(type='count',count=3),height]),
          (simple,[offset(12,8),height,dict(type='count',count=3)]),
          (tree,[offset(20,20),height]),(plant,[offset(1,1),height]),
          (aquatic,[offset(3,3),height]),
          (dict(kind='disk',provider=dict(type='state',material=1),target=yes,radius=2,half_height=1),[offset(24,8),height]),
          (bamboo,[offset(16,8),height]),(dict(kind='retina:future_feature'),[offset(8,8),height])]
    source['coordinator_prefix_recipe']=len(rows)
    rows.append((column(1),[dict(type='count',count=4096),dict(type='count',count=4096),height]))
    source['decorations']=[dict(feature,source='retina:coordinator_'+str(i),salt=i+1,placement_salt=i+1,placement=program,
                                density=1.,low_density=1.,noise_count=False,rarity=1.,tries=1.,spread=[0,0,0],water_offsets=[])
                           for i,(feature,program) in enumerate(rows)]
    for biome in source['biomes']:biome['decorations']=list(range(len(rows)))
    source['ordered_decorations']=True
    return source


def members(source,world):
    ids=set()
    for dz in range(0,16,4):
        for dx in range(0,16,4):
            biome=world.biome(world.ox+dx,0,world.oz+dz)
            if biome is not None:ids.update(source['biomes'][biome]['decorations'])
    return sorted(ids)


def reference(source,spec):
    context,fuel,limit,*selected=spec
    world=World(source,context);last=SEED;placed=0;kinds=set()
    def visit(recipe,at,state):
        nonlocal last,placed
        feature=source['decorations'][recipe]
        if feature['kind'] not in KNOWN:return False
        rng=Random(state)
        if feature['kind']=='tree':
            ok,writes=plan(feature,rng,world,at);replay(writes,world)
        else:ok=place(feature,rng,world,at,source)
        placed+=bool(ok);last=rng.state;kinds.add(feature['kind'])
        return True
    result=walk(source,context,world=world,selection=selected,anchor=(world.ox,-64,world.oz),
                seed=SEED,visitor=visit,height=384,limit_steps=fuel*limit,limit_candidates=1<<30)
    status=1 if result['missing'] is not None else 0 if result['status']==0 else 2
    slices=limit if status==2 else max(1,math.ceil(result['steps']/fuel)) if fuel else 1
    header=[status,MASK if result['missing'] is None else result['missing'],slices,result['steps'],
            result['queue'],result['candidates'],placed,last>>32,last&MASK]
    return header,members(source,world),world,kinds


class Reader:
    def __init__(self,data):self.data,self.at=data,0
    def words(self,n):
        values=struct.unpack_from('>'+str(n)+'I',self.data,self.at);self.at+=n*4;return values


def check_case(data,source,spec):
    expected,ids,world,kinds=reference(source,spec);r=Reader(data)
    actual=r.words(9);assert actual==tuple(expected),('anchor state',spec[:3],actual,expected)
    n,=r.words(1);assert list(r.words(n))==ids,'anchor biome membership'
    n,=r.words(1);patch={};previous=None
    for _ in range(n):
        x,y,z,m=r.words(4);x,y,z=(v-(1<<32) if v&(1<<31) else v for v in (x,y,z))
        key=x,z,y
        assert previous is None or previous<key;previous=key
        patch[x,y,z]=m
    assert r.at==len(data)
    assert patch==world.patch,('anchor overlay',spec[:3],len(patch),len(world.patch),
                               list((patch.items()-world.patch.items()))[:5],list((world.patch.items()-patch.items()))[:5])
    return dict(status=expected[0],steps=expected[3],candidates=expected[5],placed=expected[6],
                writes=n,kinds=sorted(kinds),sha256=hashlib.sha256(data).hexdigest()),world


def repair(world):
    source=world.source
    for z in range(world.oz,world.oz+32):
        for x in range(world.ox,world.ox+32):
            if world.column(x,z) is None:continue
            for y in range(-64,320):
                material=world.material(x,y,z);mask=source['plant_floor_masks'][material]
                if mask and (y==-64 or not world.flags(world.material(x,y-1,z))&mask):world.put(x,y,z,0)
            for y in range(-64,320):
                material=world.material(x,y,z);half=source['plant_halves'][material]
                if half:
                    partner=y+(1 if half>0 else -1)
                    if not -64<=partner<320 or source['plant_halves'][world.material(x,partner,z)]!=-half:world.put(x,y,z,0)


def check_mca(path,source,world):
    repair(world);chunks=read_region(path.read_bytes());assert len(chunks)==4
    ox,oz=origin(12);slots={((ox//16+dx)%32)+((oz//16+dz)%32)*32 for dz in range(2) for dx in range(2)}
    assert set(chunks)==slots
    states=[{'id':s} if isinstance(s,str) else s for s in source['materials']]
    count=0;changed=0
    for slot,record in chunks.items():
        tag=record['tag'];cx=ox//512*32+slot%32;cz=oz//512*32+slot//32
        assert (tag['xPos'],tag['zPos'],tag['yPos'],tag['DataVersion'])==(cx,cz,-4,5023)
        assert tag['Status']=='minecraft:features' and tag['isLightOn']==0
        assert len(tag['sections'])==24 and tag['block_entities']==tag['entities']==[]
        assert record['timestamp']==1700000000
        heights=[[0]*256 for _ in range(6)]
        for sy,section in enumerate(tag['sections']):
            assert section['Y']==sy-4 and 'SkyLight' not in section and 'BlockLight' not in section
            blocks=palette(section['block_states'],4096,4)
            for i,state in enumerate(blocks):
                cell=i%256;x,z=cx*16+cell%16,cz*16+cell//16;y=-64+sy*16+i//256
                material=world.material(x,y,z)
                assert state==states[material],('coordinated MCA',slot,x,y,z,state,states[material])
                changed+=((x,y,z) in world.patch)
                for map_ in range(6):
                    if source['heightmap_masks'][material]&(1<<map_):heights[map_][cell]=y+65
            for i,biome in enumerate(palette(section['biomes'],64,1)):
                x,z=cx*16+i%4*4,cz*16+(i//4)%4*4
                assert biome==source['biomes'][world.biome(x,sy*16-64,z)]['id']
            count+=4096
        for map_,name in enumerate(MAP_NAMES):assert unpack(tag['Heightmaps'][name],256,(384).bit_length())==heights[map_]
    return dict(chunks=4,blocks_checked=count,changed_voxels=changed,heightmaps=24,sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def exercise(binary,folder,name,source,gpu,threads=2,specs=None):
    print(name,'Metal' if gpu else 'CPU',flush=True)
    specs=cases(source) if specs is None else specs;payload=copy.deepcopy(source);payload['vegetation_cases']=specs
    path=folder/(name+'.rbp');path.write_bytes(fixture_wire(payload,True));output=folder/(name+f'-{int(gpu)}-{threads}')
    run=command(['nice','-n','10',binary,path,output,'--threads',threads,'--gpu','on' if gpu else 'off'],timeout=1200)
    assert 'PASS: coordinated registered vegetation' in run.stdout
    data=[Path(str(output)+f'.{i}').read_bytes() for i in range(len(specs))];records=[];first=None
    for raw,spec in zip(data,specs):
        record,world=check_case(raw,source,spec);records.append(record)
        if first is None:first=world
    # Slicing, input order and duplicates alter no final world or cursor state.
    compare=(0,1,2) if specs==cases(source) else (1,2,3)
    for i in compare[1:]:
        baseline=data[compare[0]];raw=data[i]
        assert raw[:8]==baseline[:8] and raw[12:]==baseline[12:],'work slices/order/duplicates change results'
    if 'coordinator_prefix_recipe' in source:
        assert records[0]['kinds']==sorted(KNOWN),'all seven feature families must execute together'
        assert records[9]['status']==1,'unavailable feature must be explicit'
        assert all(r['status']==2 and r['steps']==64 for r in records[10:]),'large count stream must pause'
        for raw in data[11:13]:assert raw[:8]==data[10][:8] and raw[12:]==data[10][12:]
        assert first.height(first.ox+8,first.oz+8,4)==5,'child heightmaps must see prior writer blocks'
        assert first.height(first.ox+12,first.oz+8,4)==1,'parent heightmap must retain its shared cached result'
    mca=Path(str(output)+'.mca');mca_result=check_mca(mca,source,first);data.append(mca.read_bytes())
    raw=Path(str(output)+'.members').read_bytes();r=Reader(raw);n,=r.words(1);observed=list(r.words(n));assert r.at==len(raw)
    small=SmallWorld(source,0);expected=set(source['biomes'][small.biome(small.ox,0,small.oz)]['decorations'])
    if source.get('decoration_biome_3d',False):
        # Small.volume has nx=2 and samples indexed x + nx*(y + ny*z).
        # The only known x/z probe is (0,0); the three deep quart IDs are 0,2,4.
        for y in range(-64,-52,4):expected.update(source['biomes'][(2*((y+64)//4))%len(source['biomes'])]['decorations'])
    assert observed==sorted(expected),'3D cave-biome membership'
    data.append(raw)
    return data,dict(cases=records,mca=mca_result,cave_memberships=len(observed)),run.stderr


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,action='append',required=True)
    p.add_argument('--skip-build',action='store_true');p.add_argument('--prove-metal',action='store_true');args=p.parse_args()
    binary=ROOT/'build/bend/vegetation';driver=ROOT/'bend/tests/vegetation.bend';observer=ROOT/'build/bend/vegetation-observer'
    folder=ROOT/'build/bend/vegetation-tests';folder.mkdir(parents=True,exist_ok=True)
    if not args.skip_build:
        build(DEFAULT_BEND,binary.with_suffix('.c'),driver);command(metal_compiler_command(binary.with_suffix('.c'),binary),timeout=600)
        command(['nice','-n','10',binary,'--threads','2','--gpu-build'],timeout=1800)
    runs=[];observed=[]
    if args.prove_metal:compile_metal_observer(DEFAULT_BEND,driver,observer,gpu_archive=binary.with_suffix('.gpu'))
    profiles=[(f'profile-{i}',json.loads(path.read_text())) for i,path in enumerate(args.profile)]
    profiles.append(('fixture',fixture(profiles[0][1])))
    for i,(name,source) in enumerate(profiles):
        cpu,record,_=exercise(binary,folder,name,source,False);runs.append(dict(profile=name,workload='complete CPU replay',gpu=False,**record))
        if i==0:
            single,_,_=exercise(binary,folder,name,source,False,threads=1);assert single==cpu
        specs=gpu_cases(source)
        control,record,_=exercise(binary,folder,'gpu-control-'+name,source,False,specs=specs)
        runs.append(dict(profile=name,workload='GPU workload CPU control',gpu=False,**record))
        gpu,record,_=exercise(binary,folder,'gpu-'+name,source,True,specs=specs);assert control==gpu
        runs.append(dict(profile=name,workload='complete seven-family fixture' if name=='fixture' else 'bounded replay prefix',gpu=True,**record))
        if args.prove_metal:
            assert observer.with_suffix('.gpu').read_bytes()==binary.with_suffix('.gpu').read_bytes()
            output,record,stderr=exercise(observer,folder,'observer-'+name,source,True,specs=specs);assert output==control
            times=[float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)];assert times
            observed.append(dict(profile=i,command_buffers=len(times),device_ms=times))
    paths=sources(driver)|{Path(__file__).resolve(),ROOT/'scripts/test_bend_placement_walker.py',ROOT/'scripts/test_bend_surface_features.py',ROOT/'scripts/test_bend_trees.py'}
    report=dict(scope=__doc__.strip(),runs=runs,metal_observation=observed,matched_gpu_workloads_equal=True,one_cpu_thread=True,
                sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)},
                profiles={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in args.profile},native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),gpu_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-vegetation-coordinator-correctness.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(dict(runs=len(runs),command_buffers=sum(r['command_buffers'] for r in observed)),indent=2))


if __name__=='__main__':main()
