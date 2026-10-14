#!/usr/bin/env python3
"""Registered vegetation patches and nested programs: independent CPU voxel/RNG replay.

Captured profiles stay unchanged. Complete CPU checks cover patch filling,
waterlogging, selectors/composites and shared-stream nested placement with live
queries, bounds and repeated calls. This is a library check; playable integration
and GPU evidence are separate.
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
from test_bend_vegetation import World as TreeWorld
from test_bend_trees import point as tree_point
from test_bend_decoration_counts import Random,MASK64
from test_bend_noise import MASK,mix_hash
from test_bend_placement_walker import shift
from test_bend_placement_merge import sources
from bend_nested_reference import place,KINDS



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
    source=copy.deepcopy(base);yes={'type':'true'};no=dict(type='not',predicate=yes)
    mat=lambda ids,offset=(0,0,0):dict(type='material',offset=list(offset),allowed=ids)
    state=lambda m:dict(type='state',material=m)
    simple=lambda m=1,survival=yes:dict(kind='simple_block',provider=state(m),states=[dict(source=m,lower=[m,m],upper=[0,0],survival=survival,upper_allowed=yes)],water=yes)
    column=lambda m=1:dict(kind='block_column',layers=[dict(height=1,provider=state(m))],direction=[0,1,0],allowed=yes,prioritize_tip=False)
    row=lambda f,ops=():dict(placement=list(ops),feature=f)
    wrap=lambda f,ops=():dict(kind='overlay',features=[row(f,ops)])
    offset=lambda **kw:dict(type='offset',**kw)
    count=lambda n:dict(type='count',count=n)
    height=dict(type='heightmap',map=4)
    def patch():
        return dict(kind='vegetation_patch',ground=state(4),same_blocks=[dict(source=4,predicate=mat([4]))],replaceable=yes,air=mat([0]),sturdy=yes,
                    direction=-1,depth=3,extra_bottom_block_chance=0.,vertical_range=32,vegetation_chance=0.,xz_radius=0,extra_edge_column_chance=0.,
                    vegetation=row(simple()),water_pool=False,enclosed=[],waterlogged_states=[])
    roots=[]
    def add(label,value):roots.append(dict(value,_case=label))
    for i,f in enumerate(base['decorations']):
        if f['kind'] in KINDS:add('original_'+str(i),copy.deepcopy(f))
    seq=[row(simple()),row(simple(4,no)),row(simple(4),[offset(x=1)])]
    add('sequence_fail',dict(kind='sequence',features=seq));add('overlay_all',dict(kind='overlay',features=seq))
    add('random_failure',dict(kind='random_selector',options=[dict(chance=0.,placed=row(simple(4))),dict(chance=1.,placed=row(simple(1,no)))],default=row(simple(4))))
    add('random_fallback',dict(kind='random_selector',options=[dict(chance=0.,placed=row(simple(4)))],default=row(simple())))
    add('simple_singleton',dict(kind='simple_selector',features=[row(simple())]))
    add('simple_multiple',dict(kind='simple_selector',features=[row(simple()),row(simple(4))]))
    add('boolean',dict(kind='boolean_selector',when_true=row(simple()),when_false=row(simple(4))))
    add('weighted_singleton',dict(kind='weighted_selector',entries=[dict(weight=1,placed=row(simple()))]))
    add('weighted_wide',dict(kind='weighted_selector',entries=[dict(weight=MASK,placed=row(simple())),dict(weight=0,placed=row(simple(4))),dict(weight=MASK,placed=row(simple(4)))]))
    add('live_height',wrap(column(),[count(3),height]));add('parent_height',wrap(column(),[height,count(3)]))
    for n in (0,1,4096,dict(type='uniform',min_inclusive=0,max_inclusive=3)):
        add('count_'+str(n),wrap(simple(1,no),[count(n)]))
    for edges,inside in ((False,False),(False,True),(True,False),(True,True)):
        add('cube_'+str((edges,inside)),wrap(simple(4),[dict(type='cuboid',xz_size=2,y_size=2,include_edges=edges,include_interior=inside)]))
    add('cube_maximum',wrap(simple(4),[dict(type='cuboid',xz_size=16,y_size=16)]))
    uniform=dict(type='uniform',min_inclusive=1,max_inclusive=3)
    add('cube_sample_order',wrap(simple(),[dict(type='cuboid',xz_size=uniform,y_size=uniform)]))
    add('layers',wrap(simple(4,no),[dict(type='count_on_every_layer',count=uniform)]))
    add('layer_zero',wrap(simple(),[dict(type='count_on_every_layer',count=0)]))
    programs=[[],[offset(y=1)],[offset()], [offset(x=uniform,y=-2,z=uniform)],
              [dict(type='in_square')],[dict(type='random_chance',chance=0.)],[dict(type='random_chance',chance=1.)],
              [dict(type='rarity_filter',chance=1)],[dict(type='rarity_filter',chance=7)],
              [dict(type='height_range',height=dict(type='uniform',min_inclusive={'above_bottom':0},max_inclusive={'below_top':0}))],
              [dict(type='height_range',height={'above_bottom':0})],
              [dict(type='environment_scan',direction=-1,max_steps=32,target_condition=mat([1,9,10]))],
              [dict(type='environment_scan',direction=1,max_steps=32,target_condition=mat([1]),allowed_search_condition=mat([0]))],
              [dict(type='block_predicate_filter',predicate=no)],
              [dict(type='surface_water_depth_filter',max_water_depth=0),height],
              [dict(type='surface_relative_threshold_filter',map=4)],
              [dict(type='surface_relative_threshold_filter',map=4,min_inclusive=-2,max_inclusive=1)]]
    for i,ops in enumerate(programs):add('program_'+str(i),wrap(simple(),ops))
    for radius in (0,1,14,dict(type='uniform',min_inclusive=0,max_inclusive=2)):
        for edge in (0.,1.):add('radius_'+str(radius)+'_'+str(edge),dict(patch(),xz_radius=radius,extra_edge_column_chance=edge))
    add('deep_maximum',dict(patch(),depth=128,xz_radius=14,extra_edge_column_chance=1.))
    add('ceiling',dict(patch(),direction=1))
    add('same_identity',dict(patch(),ground=state(9),same_blocks=[dict(source=9,predicate=mat([9]))],vegetation_chance=1.))
    add('nullable',dict(patch(),ground=dict(type='rule_based',rules=[]),same_blocks=[],vegetation_chance=1.))
    add('partial_fill',dict(patch(),replaceable=mat([9]),vegetation_chance=1.))
    add('failed_fill',dict(patch(),replaceable=no,vegetation_chance=1.))
    add('bottom_chance',dict(patch(),extra_bottom_block_chance=.5,depth=uniform,vegetation_chance=.5))
    weighted=dict(type='weighted',entries=[dict(weight=1,provider=state(9)),dict(weight=1,provider=state(4))])
    add('ground_draw_order',dict(patch(),ground=weighted,same_blocks=[dict(source=4,predicate=mat([4])),dict(source=9,predicate=mat([9]))],depth=uniform,xz_radius=1,extra_edge_column_chance=.5,vegetation_chance=.5))
    pool=dict(patch(),water_pool=True,enclosed=[yes]*5,waterlogged_states=[[1,4]],vegetation_chance=1.)
    add('pool_success',pool)
    add('pool_child_failure',dict(pool,vegetation=row(dict(kind='sequence',features=[row(simple()),row(simple(4,no))]))))
    add('pool_excluded',dict(pool,enclosed=[no]*5))
    add('pool_second_order',dict(pool,xz_radius=2,extra_edge_column_chance=1.,enclosed=[mat([4],(-1,0,0)),yes,yes,yes,yes]))
    add('nested_patch',dict(patch(),xz_radius=1,vegetation_chance=1.,vegetation=row(dict(patch(),xz_radius=0,vegetation_chance=1.))))
    source['decorations']=[dict(f,source='retina:nested_fixture_'+str(i),salt=i+1,placement=[]) for i,f in enumerate(roots)]
    for biome in source['biomes']:biome['decorations']=list(range(len(roots)))
    empty=copy.deepcopy(source);empty['decorations']=[]
    for biome in empty['biomes']:biome['decorations']=[]
    invalid=[]
    def bad(f):
        p=copy.deepcopy(source);p['decorations']=[dict(f,source='retina:invalid_nested',salt=1,placement=[])]
        for biome in p['biomes']:biome['decorations']=[0]
        invalid.append(p)
    for kind in ('sequence','overlay','simple_selector'):bad(dict(kind=kind,features=[]))
    bad(dict(kind='random_selector',options=[],default=None))
    bad(dict(kind='random_selector',options=[dict(chance=1.1,placed=row(simple()))],default=row(simple())))
    bad(dict(kind='random_selector',options=[dict(chance=-.1,placed=row(simple()))],default=row(simple())))
    bad(dict(kind='boolean_selector',when_true=row(simple())))
    for entries in ([],[dict(weight=0,placed=row(simple()))],[dict(weight=-1,placed=row(simple()))],[dict(weight=MASK+1,placed=row(simple()))]):bad(dict(kind='weighted_selector',entries=entries))
    for op in [count(-1),count(4097),dict(type='count_on_every_layer',count=257),dict(type='cuboid',xz_size=0,y_size=1),dict(type='cuboid',xz_size=17,y_size=1),
               dict(type='biome'),dict(type='select',min=0.,max=1.),dict(type='noise_threshold_count',noise_level=0.,below_noise=1,above_noise=1),
               dict(type='height_range',height=0),dict(type='heightmap',map=6),dict(type='environment_scan',direction=0,max_steps=1,target_condition=yes),
               dict(type='rarity_filter',chance=0),dict(type='random_chance',chance=1.1),dict(type='offset',x=True)]:bad(wrap(simple(),[op]))
    bad(dict(kind='overlay',features=[dict(feature=simple())]));bad(dict(kind='overlay',features=[row(dict(kind='tree'))]))
    count_=len(source['materials'])
    for key,value in [('direction',0),('depth',0),('depth',129),('xz_radius',-1),('xz_radius',15),('vertical_range',0),('vertical_range',257),
                      ('extra_bottom_block_chance',-.1),('vegetation_chance',1.1),('extra_edge_column_chance',1.1),('water_pool',1),('same_blocks',[]),
                      ('ground',state(count_)),('replaceable',mat([count_])),('waterlogged_states',[[1]]),('waterlogged_states',[[1,count_]])]:bad(dict(patch(),**{key:value}))
    bad(dict(pool,enclosed=[yes]*4));bad(dict(pool,enclosed=[mat([count_])]*5))
    for key in ('ground','same_blocks','vegetation','waterlogged_states','enclosed'):
        f=patch();del f[key];bad(f)
    return source,empty,invalid


def controls(source):
    from test_bend_vegetation import World
    from test_bend_decoration_counts import Random
    labels={f['_case']:f for f in source['decorations']}
    results={}
    def run(label,seed=12345):
        w=World(source,0);rng=Random(seed);ok=place(labels[label],rng,w,point(0));return ok,rng.state,w
    at=point(0)
    ok,state,w=run('sequence_fail');assert not ok and w.patch=={at:1}
    ok,state,w=run('overlay_all');assert ok and w.patch=={at:1,shift(at,(1,0,0)):4}
    ok,state,w=run('random_failure');assert not ok and not w.patch and state==(12345+2*0x9e3779b97f4a7c15)&MASK64
    assert run('simple_singleton')[1]==12345
    assert run('weighted_singleton')[1]==(12345+0x9e3779b97f4a7c15)&MASK64
    assert run('live_height')[2].height(at[0],at[2],4)==3
    assert run('parent_height')[2].height(at[0],at[2],4)==1
    ok,state,w=run('pool_success');assert ok and w.material(at[0],at[1]-1,at[2])==4
    ok,state,w=run('pool_child_failure');assert ok and w.material(at[0],at[1]-1,at[2])==1
    ok,state,w=run('pool_excluded');assert not ok and w.patch
    assert run('nullable')[0]
    return dict(sequence_retains_writes='pass',overlay_replays_all='pass',random_failure_stops='pass',singleton_rng='pass',live_and_parent_height='pass',waterlogging_success_only='pass',excluded_pool_retains_ground='pass',nullable_ground='pass')


def exercise(binary,folder,name,source,gpu,threads=2):
    print(name,'Metal' if gpu else 'CPU',flush=True)
    path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,True));output=folder/(name+f'-{int(gpu)}-{threads}.bin')
    try:result=command(['nice','-n','10',binary,path,output,'--threads',threads,'--gpu','on' if gpu else 'off'],timeout=1800)
    except subprocess.CalledProcessError as error:raise AssertionError((name,gpu,error.returncode,error.stderr[-5000:])) from error
    raw=output.read_bytes();return check(raw,source),raw,result.stderr


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,action='append',required=True);p.add_argument('--skip-build',action='store_true');args=p.parse_args()
    binary=ROOT/'build/bend/nested-features';driver=ROOT/'bend/tests/nested-features.bend';folder=ROOT/'build/bend/nested-feature-tests';folder.mkdir(parents=True,exist_ok=True)
    if not args.skip_build:
        build(DEFAULT_BEND,binary.with_suffix('.c'),driver);command(metal_compiler_command(binary.with_suffix('.c'),binary),timeout=600)
    profiles=[(f'profile-{i}',json.loads(path.read_text())) for i,path in enumerate(args.profile)]
    fixture,empty,invalid=fixtures(profiles[0][1]);semantics=controls(fixture);profiles += [('fixture',fixture),('empty',empty)]
    runs=[];outputs={}
    for name,source in profiles:
        result,raw,_=exercise(binary,folder,name,source,False);outputs[name]=raw;runs.append(dict(profile=name,gpu=False,**result))
    _,raw,_=exercise(binary,folder,'profile-0-one-thread',profiles[0][1],False,threads=1);assert raw==outputs['profile-0']
    for i,source in enumerate(invalid):
        path=folder/f'invalid-{i}.rbp';path.write_bytes(fixture_wire(source,True));output=folder/f'invalid-{i}.bin';output.unlink(missing_ok=True)
        result=subprocess.run(['nice','-n','10',str(binary),str(path),str(output),'--threads','2','--gpu','off'],capture_output=True,text=True,timeout=300)
        assert result.returncode and 'invalid surface feature' in result.stderr,(i,result.stderr)
        assert not output.exists(),i
    files=sources(driver)|{Path(__file__).resolve(),ROOT/'scripts/bend_nested_reference.py',ROOT/'scripts/bend_attachment_reference.py',ROOT/'scripts/test_bend_surface_features.py',ROOT/'scripts/test_bend_placement_walker.py',ROOT/'scripts/test_bend_decoration_counts.py',ROOT/'scripts/test_bend_decoration_heights.py',ROOT/'scripts/test_bend_noise.py',ROOT/'scripts/test_bend_trees.py',ROOT/'scripts/test_bend_vegetation.py',ROOT/'scripts/test_bend_block_providers.py'}
    report=dict(scope=__doc__.strip(),execution_mode='cpu',runs=runs,semantic_controls=semantics,malformed_rejected=len(invalid),one_cpu_thread=True,
                sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)},profiles={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in args.profile},native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-nested-features-correctness.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(dict(writer_calls=sum(r['writer_calls'] for r in runs),malformed_rejected=len(invalid)),indent=2))


if __name__=='__main__':main()
