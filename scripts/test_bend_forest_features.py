#!/usr/bin/env python3
"""Registered huge mushrooms and fallen logs: independent voxel/RNG replay.

Captured profiles stay unchanged. CPU and actual observed Metal checks cover
cap faces, axes, live providers/decorators, failed horizontal logs, bounds and
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
from bend_forest_reference import place

KINDS=('huge_mushroom','fallen_tree')


class World(TreeWorld):
    minimum,maximum=-64,320


def point(context):
    # The fixture constructs its anchor with U32 addition. Context six crosses
    # i32::MAX before checked feature offsets begin; replay that input exactly.
    return tuple(((v+(1<<31))&MASK)-(1<<31) for v in tree_point(context))


def features(node):
    if isinstance(node,dict):
        if node.get('kind') in KINDS:yield node
        for child in node.values():yield from features(child)
    elif isinstance(node,list):
        for child in node:yield from features(child)


def expected(source):
    fs=list(features(source.get('decorations',[])));rows=[]
    for i,feature in enumerate(fs):
        for context in range(12):
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
        assert actual_state==state,('RNG',i,fs[i//24]['kind'],actual_state,state)
        assert actual_ok==ok,('placed',i,actual_ok,ok)
        assert actual==patch,('voxels',i,fs[i//24]['kind'],len(actual),len(patch),
                               list(actual.items()-patch.items())[:6],list(patch.items()-actual.items())[:6])
        changed+=len(patch)
    assert at==len(data)
    return dict(features=len(fs),kinds=dict(Counter(f['kind'] for f in fs)),writer_calls=len(rows),
                successful_calls=sum(row[1] for row in rows),touched_blocks=changed,sha256=hashlib.sha256(data).hexdigest())


def fixtures(base):
    source=copy.deepcopy(base);yes={'type':'true'}
    mat=lambda ids,offset=(0,0,0):dict(type='material',offset=list(offset),allowed=ids)
    state=lambda m:dict(type='state',material=m)
    brown=copy.deepcopy(next(f for f in features(base['decorations']) if f['kind']=='huge_mushroom' and not f['red']))
    red=copy.deepcopy(next(f for f in features(base['decorations']) if f['kind']=='huge_mushroom' and f['red']))
    fallen=copy.deepcopy(next(f for f in features(base['decorations']) if f['kind']=='fallen_tree'))
    # Distinct synthetic states make the face mask, axis and decorator direction
    # observable independently of Minecraft's actual cap palettes.
    def mushroom(is_red,radius=2):
        return dict(kind='huge_mushroom',red=is_red,foliage_radius=radius,cap=state(1),stem=state(2),
                    faces=[dict(source=1,states=list(range(100,132)))],support=yes,clearance=yes,replaceable=yes)
    def log(length=7):
        return dict(kind='fallen_tree',trunk=state(1),log_length=length,axes=[dict(source=1,states=[4,5])],
                    stump_decorators=[],log_decorators=[],clearance=mat([0,6,7]),
                    sturdy=mat([1,9,10],(0,-1,0)),air=mat([0]),replaceable=yes,water=mat([2]),shelf=mat([8]))
    weighted=dict(type='weighted',entries=[dict(weight=1,provider=state(1)),dict(weight=3,provider=state(3))])
    rows=[brown,red,fallen]
    for is_red in (False,True):
        for radius in (0,1,2,3,15):rows.append(mushroom(is_red,radius))
    rows += [dict(mushroom(True),clearance=mat([0])),dict(mushroom(False),clearance=mat([0])),
             dict(mushroom(True),support=mat([2])),dict(mushroom(False),replaceable=mat([0])),
             dict(mushroom(True),replaceable=mat([0])),dict(mushroom(False),faces=[dict(source=1,states=list(range(132,164)))])]
    mixed=mushroom(False);mixed.update(cap=weighted,faces=[dict(source=1,states=list(range(100,132))),dict(source=3,states=list(range(132,164)))])
    rows.append(mixed)
    current=dict(type='rule_based',rules=[])
    rows += [dict(mushroom(True),cap=current,faces=[]),dict(mushroom(False),stem=current)]
    live=dict(type='rule_based',rules=[dict(predicate=mat([2]),provider=state(3))],fallback=state(2))
    rows.append(dict(mushroom(True),stem=live))
    cap_live=dict(type='rule_based',rules=[dict(predicate=mat(list(range(100,132)),(-2,4,0)),provider=state(3))],fallback=state(1))
    cap=mushroom(False);cap.update(cap=cap_live,faces=[dict(source=1,states=list(range(100,132))),dict(source=3,states=list(range(132,164)))])
    rows.append(cap)
    for length in (0,1,2,3,7,14,15):rows.append(log(length))
    vine=dict(type='trunk_vine',states=[6,7,8,11])
    attached=lambda p=1.,ds=((0,1,0),):dict(type='attached_to_logs',probability=p,provider=weighted,directions=[list(d) for d in ds])
    shelf=lambda p=1.:dict(type='shelf_mushroom',probability=p,states=[[6,7],[8,11],[12,13],[14,15]])
    for decorator in (vine,attached(),attached(0.),attached(.5,((1,0,0),(-1,0,0),(0,0,1),(0,-1,0))),shelf(),shelf(0.),shelf(.5)):
        rows.append(dict(log(15),stump_decorators=[decorator],log_decorators=[decorator]))
    rows += [dict(log(),clearance=mat([2])),dict(log(),sturdy=mat([2])),
             dict(log(0),log_decorators=[shelf()]),dict(log(1),stump_decorators=[vine,attached(),shelf()],log_decorators=[shelf()]),
             dict(log(15),stump_decorators=[attached()],log_decorators=[vine,attached(),shelf()]),
             dict(log(),trunk=current,axes=[]),dict(log(15),trunk=weighted,axes=[dict(source=1,states=[4,5]),dict(source=3,states=[6,7])])]
    source['decorations']=[dict(r,source='retina:forest_fixture_'+str(i),salt=i+1,placement=[]) for i,r in enumerate(rows)]
    for biome in source['biomes']:biome['decorations']=list(range(len(rows)))
    empty=copy.deepcopy(source);empty['decorations']=[]
    for biome in empty['biomes']:biome['decorations']=[]
    invalid=[]
    def bad(recipe):
        payload=copy.deepcopy(source);payload['decorations']=[dict(recipe,source='retina:invalid_forest',salt=1,placement=[])]
        for biome in payload['biomes']:biome['decorations']=[0]
        invalid.append(payload)
    count=len(source['materials'])
    for key,value in [('red',1),('foliage_radius',-1),('foliage_radius',16),('cap',state(count)),('stem',state(count)),('support',mat([count]))]:
        bad(dict(mushroom(True),**{key:value}))
    for faces in ([],[dict(source=1,states=[1]*31)],[dict(source=1,states=[count]*32)],[dict(source=65536,states=[1]*32)]):bad(dict(mushroom(False),faces=faces))
    for length in (-1,16,dict(type='uniform',min_inclusive=-1,max_inclusive=7)):bad(log(length))
    for axes in ([],[dict(source=1,states=[1])],[dict(source=1,states=[count,count])]):bad(dict(log(),axes=axes))
    for key in ('clearance','sturdy','air','replaceable','water','shelf'):bad(dict(log(),**{key:mat([count])}))
    for decorator in (dict(vine,states=[1]*3),dict(vine,states=[count]*4),attached(-.1),attached(1.1),attached(ds=()),
                      attached(ds=((1,1,0),)),attached(ds=((0,0,0),)),dict(attached(),provider=state(count)),
                      dict(shelf(),states=[[1,1]]*3),dict(shelf(),states=[[1]]*4),dict(shelf(),states=[[count,count]]*4),
                      dict(shelf(),probability=-1.),dict(type='unknown_forest_decorator')):
        bad(dict(log(),log_decorators=[decorator]))
    return source,empty,invalid


def controls(source):
    fs,rows=expected(source)
    def get(i,c,r=0):return rows[i*24+c*2+r]
    red=next(i for i,f in enumerate(fs) if f['kind']=='huge_mushroom' and f['red'] and f.get('cap')==dict(type='state',material=1) and f['foliage_radius']==2)
    assert get(red,0)[1] and len({m for m in get(red,0)[2].values() if 100<=m<132})>4,'cap faces must vary'
    assert not get(red,3)[1],'mushroom at world ceiling must fail'
    failed=next(i for i,f in enumerate(fs) if f['kind']=='fallen_tree' and f['clearance']==dict(type='material',offset=[0,0,0],allowed=[2]))
    assert get(failed,0)[1] and get(failed,0)[2]=={point(0):1},'failed horizontal log keeps stump'
    empty_shelf=next(i for i,f in enumerate(fs) if f['kind']=='fallen_tree' and f['log_length']==0 and f['log_decorators'] and f['log_decorators'][0]['type']=='shelf_mushroom')
    seed=((((0x80000000^(empty_shelf*7919))&MASK)<<32)|mix_hash(empty_shelf))
    assert get(empty_shelf,0)[0]==(seed+3*0x9e3779b97f4a7c15)&MASK64,'empty-log shelf still draws chance'
    assert any(get(i,0)[2]!=get(i,0,1)[2] for i,f in enumerate(fs) if f['kind']=='huge_mushroom'),'repeat sees previous material'
    return dict(cap_faces='pass',mushroom_ceiling='pass',retained_failed_stump='pass',empty_log_rng='pass',repeated_live_writes='pass')


def exercise(binary,folder,name,source,gpu,threads=2):
    print(name,'Metal' if gpu else 'CPU',flush=True)
    path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,True));output=folder/(name+f'-{int(gpu)}-{threads}.bin')
    try:result=command(['nice','-n','10',binary,path,output,'--threads',threads,'--gpu','on' if gpu else 'off'],timeout=1800)
    except subprocess.CalledProcessError as error:raise AssertionError((name,gpu,error.returncode,error.stderr[-5000:])) from error
    raw=output.read_bytes();return check(raw,source),raw,result.stderr


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,action='append',required=True)
    p.add_argument('--skip-build',action='store_true');p.add_argument('--prove-metal',action='store_true');args=p.parse_args()
    binary=ROOT/'build/bend/forest-features';driver=ROOT/'bend/tests/forest-features.bend';observer=ROOT/'build/bend/forest-features-observer'
    folder=ROOT/'build/bend/forest-feature-tests';folder.mkdir(parents=True,exist_ok=True)
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
    paths=sources(driver)|{Path(__file__).resolve(),ROOT/'scripts/bend_forest_reference.py'}
    report=dict(scope=__doc__.strip(),runs=runs,semantic_controls=semantics,malformed_rejected=len(invalid),
                metal_observation=observed,cpu_gpu_bytes_equal=True,one_cpu_thread=True,
                sources={str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)},
                profiles={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in args.profile},
                native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),gpu_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-forest-features-correctness.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(dict(writer_calls_per_backend=sum(row['writer_calls'] for row in runs if not row['gpu']),malformed_rejected=len(invalid),metal_commands=sum(row['command_buffers'] for row in observed)),indent=2))


if __name__=='__main__':main()
