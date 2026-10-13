#!/usr/bin/env python3
"""Registered vines and multiface attachments: independent voxel/RNG replay.

Captured profiles stay unchanged. CPU and actual observed Metal checks cover
face states, waterlogging, support, search/spread ordering, bounds and
repeated calls. These writers do not yet enable playable vegetation.
"""
import argparse
from collections import Counter
import copy
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess

from test_bend_compression import ROOT,DEFAULT_BEND,build,command,metal_compiler_command
from test_bend_engine import compile_metal_observer
from test_bend_registry_noise import fixture_wire
from test_bend_trees import World as TreeWorld,point as tree_point
from test_bend_decoration_counts import Random,MASK64
from test_bend_noise import MASK,mix_hash
from test_bend_placement_merge import sources
from bend_attachment_reference import place

KINDS=('attachment_growth',)


class World(TreeWorld):
    minimum,maximum=-64,320


def point(context):
    # The fixture constructs its anchor with U32 addition. Context six crosses
    # i32::MAX before checked feature offsets begin; replay that input exactly.
    x,y,z=tree_point(context)
    if context>=12:y=[319,-64,320,-65][context-12]
    return tuple(((v+(1<<31))&MASK)-(1<<31) for v in (x,y,z))


def features(node):
    if isinstance(node,dict):
        if node.get('kind') in KINDS:yield node
        for child in node.values():yield from features(child)
    elif isinstance(node,list):
        for child in node:yield from features(child)


def expected(source):
    fs=list(features(source.get('decorations',[])));rows=[]
    for i,feature in enumerate(fs):
        for context in range(16):
            seed=((((0x80000000^(i*7919))&MASK)<<32)|mix_hash((context*811+i)&MASK))
            rng=Random(seed);world=World(source,context)
            for _ in range(2):
                ok=place(feature,rng,world,point(context))
                rows.append((rng.state,ok,dict(world.patch)))
    return fs,rows


def check(data,source):
    fs,rows=expected(source);at=0
    def word():
        nonlocal at
        result=struct.unpack_from('>I',data,at)[0];at+=4;return result
    assert word()==len(fs)
    changed=0
    for i,(state,ok,patch) in enumerate(rows):
        actual_state=(word()<<32)|word();actual_ok=bool(word());n=word();actual={};previous=None
        for _ in range(n):
            x,y,z,m=word(),word(),word(),word()
            x,y,z=(v-(1<<32) if v&(1<<31) else v for v in (x,y,z))
            key=x,z,y;assert previous is None or previous<key;previous=key
            actual[x,y,z]=m
        assert actual_state==state,('RNG',i,fs[i//32]['kind'],actual_state,state)
        assert actual_ok==ok,('placed',i,actual_ok,ok)
        assert actual==patch,('voxels',i,fs[i//32]['kind'],len(actual),len(patch),
                               list(actual.items()-patch.items())[:6],list(patch.items()-actual.items())[:6])
        changed+=len(patch)
    assert at==len(data)
    return dict(features=len(fs),kinds=dict(Counter(f['kind'] for f in fs)),writer_calls=len(rows),
                successful_calls=sum(row[1] for row in rows),touched_blocks=changed,sha256=hashlib.sha256(data).hexdigest())


def fixtures(base):
    from itertools import permutations
    source=copy.deepcopy(base);yes={'type':'true'};no=dict(type='not',predicate=yes)
    mat=lambda ids,offset=(0,0,0):dict(type='material',offset=list(offset),allowed=ids)
    originals=[copy.deepcopy(f) for f in features(base['decorations'])]
    def recipe(vines=False,mask=63):
        states=[dict(source=base+m,faces=m,added=[base+(m|(1<<d)) if mask&(1<<d) else base+m for d in range(6)])
                for base in (100,164) for m in range(64) if not m&~mask]
        return dict(kind='attachment_growth',vines=vines,initial=100,wet_initial=164,face_mask=mask,states=states,
                    directions=[d for d in range(6) if mask&(1<<d)],search_range=0 if vines else 16,spread_chance=0.,spread_order=[0,1,2],sculk=False,
                    air=mat([0]),water=mat([2]),source_water=mat([2]),placed_on=yes,spread_replaceable=yes,spread_support=yes,support=[yes]*6,sturdy=[no]*6)
    rows=originals+[recipe(True,62),recipe(False),recipe(False,3),recipe(False,0)]
    for chance in (0.,.5,1.):
        for order in permutations((0,1,2)):
            rows.append(dict(recipe(),spread_chance=chance,spread_order=list(order)))
    for key in ('air','water','placed_on','spread_replaceable','spread_support'):
        rows.append(dict(recipe(),sculk=True,spread_chance=1.,**{key:no}))
    rows += [dict(recipe(),sculk=True,spread_chance=1.,sturdy=[yes]*6),
             dict(recipe(),sculk=True,spread_chance=1.,air=yes,water=no,placed_on=mat([1,9,10])),
             dict(recipe(),directions=[]),dict(recipe(),directions=[0],support=[no,yes,yes,yes,yes,yes]),
             dict(recipe(),directions=[0,1],support=[no,yes,yes,yes,yes,yes]),
             dict(recipe(),directions=[0],air=yes,spread_chance=1.,spread_order=[1,2]),
             dict(recipe(),directions=[0],air=yes,spread_chance=1.,spread_order=[2,1]),
             dict(recipe(),spread_chance=1.,spread_order=[0,0,2,1]),
             dict(recipe(),source_water=yes,spread_chance=1.),
             dict(recipe(True,62),air=yes,support=[no,no,no,no,no,yes])]
    source['decorations']=[dict(r,source='retina:attachment_fixture_'+str(i),salt=i+1,placement=[]) for i,r in enumerate(rows)]
    for biome in source['biomes']:biome['decorations']=list(range(len(rows)))
    empty=copy.deepcopy(source);empty['decorations']=[]
    for biome in empty['biomes']:biome['decorations']=[]
    invalid=[]
    def bad(r):
        payload=copy.deepcopy(source);payload['decorations']=[dict(r,source='retina:invalid_attachment',salt=1,placement=[])]
        for biome in payload['biomes']:biome['decorations']=[0]
        invalid.append(payload)
    count=len(source['materials'])
    for key,value in [('vines',1),('sculk',0),('initial',count),('wet_initial',count),('face_mask',64),('face_mask',-1),
                      ('directions',[0,0]),('directions',[6]),('directions',[-1]),('search_range',0),('search_range',65),
                      ('spread_chance',-.1),('spread_chance',1.1),('spread_order',[3]),('spread_order',[-1])]:bad(dict(recipe(),**{key:value}))
    bad(dict(recipe(True),search_range=1))
    for key in ('air','water','source_water','placed_on','spread_replaceable','spread_support'):bad(dict(recipe(),**{key:mat([count])}))
    for key in ('support','sturdy'):
        bad(dict(recipe(),**{key:[yes]*5}));bad(dict(recipe(),**{key:[mat([count])]*6}))
    for change in ('empty','unsorted','duplicate','source','faces','added_size','added_missing'):
        r=recipe()
        if change=='empty':r['states']=[]
        elif change=='unsorted':r['states'].reverse()
        elif change=='duplicate':r['states'].insert(0,copy.deepcopy(r['states'][0]))
        elif change=='source':r['states'][-1]['source']=count
        elif change=='faces':r['states'][0]['faces']=64
        elif change=='added_size':r['states'][0]['added']=[100]*5
        elif change=='added_missing':r['states'][0]['added'][0]=99
        bad(r)
    for key in ('states','directions','spread_order','support','sturdy'):
        r=recipe();del r[key];bad(r)
    return source,empty,invalid


def controls(source):
    fs,rows=expected(source)
    def get(i,c,r=0):return rows[i*32+c*2+r]
    blocked=next(i for i,f in enumerate(fs) if f['directions']==[0] and f['support'][0]['type']=='not')
    assert not get(blocked,0)[1] and not get(blocked,0)[2],'first allowed unsupported neighbor ends attempt'
    outside=next(i for i,f in enumerate(fs) if f['directions']==[0] and f['air']['type']=='true' and f['spread_order']==[1,2])
    around=next(i for i,f in enumerate(fs) if f['directions']==[0] and f['air']['type']=='true' and f['spread_order']==[2,1])
    assert get(outside,14)[1] and not get(outside,14)[2],'selected out-of-height spread skips remaining types'
    assert get(around,14)[1] and get(around,14)[2],'around-corner order can reach valid height'
    wet=next(i for i,f in enumerate(fs) if not f['vines'] and f['source_water']['type']=='true')
    assert any(m>=164 for m in get(wet,0)[2].values()),'wet face-state table must be used'
    assert any(get(i,0)[2]!=get(i,0,1)[2] for i,f in enumerate(fs) if not f['vines']),'repeated placements see previous face states'
    vines=next(i for i,f in enumerate(fs) if f['vines'] and f['air']['type']=='true')
    seed=((((0x80000000^(vines*7919))&MASK)<<32)|mix_hash(vines))
    assert get(vines,0)[0]==seed,'vines do not draw RNG'
    return dict(first_allowed_neighbor='pass',out_of_height_spread_order='pass',wet_states='pass',repeated_faces='pass',vine_rng='pass')


def exercise(binary,folder,name,source,gpu,threads=2):
    print(name,'Metal' if gpu else 'CPU',flush=True)
    path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,True));output=folder/(name+f'-{int(gpu)}-{threads}.bin')
    try:result=command(['nice','-n','10',binary,path,output,'--threads',threads,'--gpu','on' if gpu else 'off'],timeout=1800)
    except subprocess.CalledProcessError as error:raise AssertionError((name,gpu,error.returncode,error.stderr[-5000:])) from error
    raw=output.read_bytes();return check(raw,source),raw,result.stderr


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,action='append',required=True)
    p.add_argument('--skip-build',action='store_true');p.add_argument('--prove-metal',action='store_true');args=p.parse_args()
    binary=ROOT/'build/bend/attachment-features';driver=ROOT/'bend/tests/attachment-features.bend';observer=ROOT/'build/bend/attachment-features-observer'
    folder=ROOT/'build/bend/attachment-feature-tests';folder.mkdir(parents=True,exist_ok=True)
    if not args.skip_build:
        build(DEFAULT_BEND,binary.with_suffix('.c'),driver);command(metal_compiler_command(binary.with_suffix('.c'),binary),timeout=600)
        command(['nice','-n','10',binary,'--threads','2','--gpu-build'],timeout=1800)
    profiles=[(f'profile-{i}',json.loads(path.read_text())) for i,path in enumerate(args.profile)]
    fixture,empty,invalid=fixtures(profiles[0][1]);semantics=controls(fixture);profiles += [('fixture',fixture),('empty',empty)]
    runs=[];outputs={}
    for gpu in (False,True):
        for name,source in profiles:
            result,raw,_=exercise(binary,folder,name,source,gpu)
            if not gpu:outputs[name]=raw
            else:assert outputs[name]==raw,(name,'CPU/Metal mismatch')
            runs.append(dict(profile=name,gpu=gpu,**result))
    _,raw,_=exercise(binary,folder,'fixture-one-thread',fixture,False,threads=1);assert raw==outputs['fixture']
    for i,source in enumerate(invalid):
        path=folder/f'invalid-{i}.rbp';path.write_bytes(fixture_wire(source,True));output=folder/f'invalid-{i}.bin';output.unlink(missing_ok=True)
        result=subprocess.run(['nice','-n','10',str(binary),str(path),str(output),'--threads','2','--gpu','off'],capture_output=True,text=True,timeout=300)
        assert result.returncode and 'invalid surface feature' in result.stderr,(i,result.stderr)
        assert not output.exists(),i
    observed=[]
    if args.prove_metal:
        compile_metal_observer(DEFAULT_BEND,driver,observer,gpu_archive=binary.with_suffix('.gpu'))
        assert observer.with_suffix('.gpu').read_bytes()==binary.with_suffix('.gpu').read_bytes()
        for name,source in profiles:
            result,raw,stderr=exercise(observer,folder,'observer-'+name,source,True);assert outputs[name]==raw
            times=[float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)]
            if result['features']:assert times,(name,'no actual Metal command buffers')
            observed.append(dict(profile=name,command_buffers=len(times),device_ms=times))
    paths=sources(driver)|{Path(__file__).resolve(),ROOT/'scripts/bend_attachment_reference.py',ROOT/'scripts/test_bend_placement_walker.py',ROOT/'scripts/test_bend_decoration_counts.py',ROOT/'scripts/test_bend_noise.py',ROOT/'scripts/test_bend_trees.py',ROOT/'scripts/test_bend_registry_noise.py',ROOT/'scripts/test_bend_compression.py',ROOT/'scripts/test_bend_engine.py'}
    report=dict(scope=__doc__.strip(),runs=runs,semantic_controls=semantics,malformed_rejected=len(invalid),
                metal_observation=observed,cpu_gpu_bytes_equal=True,one_cpu_thread=True,
                sources={str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)},
                profiles={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in args.profile},
                native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),gpu_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-attachment-features-correctness.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(dict(writer_calls_per_backend=sum(row['writer_calls'] for row in runs if not row['gpu']),malformed_rejected=len(invalid),metal_commands=sum(row['command_buffers'] for row in observed)),indent=2))


if __name__=='__main__':main()
