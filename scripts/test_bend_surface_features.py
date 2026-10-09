#!/usr/bin/env python3
"""Actual simple-block, block-column and disk writes on CPU and observed Metal.

Unmodified captured registry profiles cross the binary transport. Independent
Python voxel/RNG replay checks every touched block and successive writer calls.
This component is not yet wired into the playable vegetation pipeline.
"""
import argparse
import copy
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess

from test_bend_compression import ROOT, DEFAULT_BEND, build, command
from test_bend_decoration_counts import Random, provider as integer, MASK64
from test_bend_engine import compile_metal_observer
from test_bend_noise import MASK, mix_hash
from test_bend_placement_programs import fixtures as placement_fixtures, origin, signed
from test_bend_placement_walker import World as BaseWorld, predicate, shift
from test_bend_block_providers import sample
from test_bend_registry_noise import fixture_wire

KINDS = ('simple_block', 'block_column', 'disk')


def features(node):
    if isinstance(node, dict):
        if node.get('kind') in KINDS: yield node
        for child in node.values(): yield from features(child)
    elif isinstance(node, list):
        for child in node: yield from features(child)


class World(BaseWorld):
    def material(self, x, y, z):
        if not -64 <= y < 64: return 0
        if (x,y,z) in self.patch: return self.patch[x,y,z]
        if self.column(x,z) is None: return None
        offset = y+64
        if offset == 0: return self.source['bedrock']
        if offset < 55: return 1
        if offset < 63: return 10 if len(self.source['materials']) > 8 else 1
        if offset == 63: return 9 if len(self.source['materials']) > 8 else 1
        if self.context % 4 == 1 and offset < 68: return self.source['water']
        if self.context % 4 == 2 and offset == 66: return 1
        return 0


def point(context):
    x,z=origin(context);i=context%8
    return (signed((x+[8,8,8,8,0,31,32,8][i])&MASK),
            [1 if context>=8 else 0,0,0,63,-64,0,0,64 if context>=8 else -65][i],
            signed((z+(31 if i==5 else 8))&MASK))


def place(f, rng, world, at, source):
    def test(p, at): return predicate(p,world,at) is True
    def state(p, at, optional=True): return sample(p,rng,at,world,source.get('decoration_provider_noises',[]),optional)
    kind=f['kind']
    if kind=='simple_block':
        value=state(f['provider'],at)
        if value is None: return False
        row=next((s for s in f['states'] if s['source']==value),None)
        if row is None and f.get('current_survival',False):
            rows=source.get('simple_current_states',[])
            row=rows[value] if value<len(rows) else None
        if row is None or not test(row['survival'],at): return False
        above=shift(at,(0,1,0));paired=row['upper'][0]!=0
        if paired and (above is None or not test(row['upper_allowed'],above)): return False
        lower=row['lower'][int(test(f['water'],at))]
        upper=row['upper'][int(test(f['water'],above))] if above is not None else 0
        world.put(*at,lower)
        if paired: world.put(*above,upper)
        return True
    if kind=='block_column':
        heights=[integer(l['height'],rng) for l in f['layers']];total=sum(heights)
        for n in range(total):
            nxt=shift(at,tuple(d*(n+1) for d in f['direction']))
            if nxt is None or not test(f['allowed'],nxt):
                remove=total-n
                indices=range(len(heights)) if f['prioritize_tip'] else reversed(range(len(heights)))
                for i in indices:
                    take=min(remove,heights[i]);heights[i]-=take;remove-=take
                    if not remove: break
                break
        pos=at
        for layer,height in zip(f['layers'],heights):
            for _ in range(height):
                world.put(*pos,state(layer['provider'],pos,False))
                pos=shift(pos,tuple(f['direction']))
                if pos is None: return total!=0
        return total!=0
    if kind=='disk':
        radius=integer(f['radius'],rng);any_=False;half=f['half_height']
        for dz in range(-radius,radius+1):
            for dx in range(-radius,radius+1):
                if dx*dx+dz*dz>radius*radius: continue
                for dy in range(half,-half-1,-1):
                    pos=shift(at,(dx,dy,dz))
                    if pos is not None and test(f['target'],pos):
                        value=state(f['provider'],pos)
                        if value is not None:
                            world.put(*pos,value);any_=True
        return any_
    raise AssertionError(kind)


def expected(source):
    fs=list(features(source.get('decorations',[])));rows=[]
    for i,f in enumerate(fs):
        for context in range(16):
            seed=((((0x80000000^(i*7919))&MASK)<<32)|mix_hash((context*811+i)&MASK))
            rng=Random(seed);world=World(source,context);at=point(context)
            for repeat in range(2):
                placed=place(f,rng,world,at,source)
                rows.append((rng.state,placed,dict(world.patch)))
    return fs,rows


def check(data,source):
    fs,rows=expected(source);at=0
    def word():
        nonlocal at
        value=struct.unpack_from('>I',data,at)[0];at+=4;return value
    assert word()==len(fs)
    changed=0
    for i,(state,placed,patch) in enumerate(rows):
        observed=(word()<<32)|word();ok=bool(word());n=word();actual={};last=None
        for _ in range(n):
            x,y,z=map(signed,(word(),word(),word()));m=word();key=(x,z,y)
            assert last is None or last<key, ('patch order',i,last,key)
            assert (x,y,z) not in actual
            actual[x,y,z]=m;last=key
        assert observed==state, ('rng',i,observed,state)
        assert ok==placed, ('placed',i,ok,placed)
        assert actual==patch, ('voxels',i,fs[i//32]['kind'],list(actual.items())[:8], list(patch.items())[:8])
        changed+=len(patch)
    assert at==len(data),(at,len(data))
    return dict(features=len(fs),kinds=dict(Counter(f['kind'] for f in fs)),writer_calls=len(rows),
                successful_calls=sum(row[1] for row in rows),touched_blocks=changed,bytes=len(data),sha256=hashlib.sha256(data).hexdigest())


def fixtures(permutation):
    source=placement_fixtures(permutation)[0];yes={'type':'true'}
    mat=lambda ids,offset=(0,0,0):dict(type='material',offset=list(offset),allowed=ids)
    state=lambda m:dict(type='state',material=m)
    row=lambda s,lower,upper=(0,0),survival=yes,allowed=yes:dict(source=s,lower=list(lower),upper=list(upper),survival=survival,upper_allowed=allowed)
    plant=lambda p,rows,**kwargs:dict(kind='simple_block',provider=p,states=rows,water=mat([2]),**kwargs)
    disk=lambda p,**kwargs:dict(kind='disk',provider=p,target=kwargs.get('target',yes),radius=kwargs.get('radius',2),half_height=kwargs.get('half_height',1))
    layer=lambda h,p:dict(height=h,provider=p)
    column=lambda ls,d=(0,1,0),tip=False,allowed=mat([0]):dict(kind='block_column',layers=ls,direction=list(d),allowed=allowed,prioritize_tip=tip)
    nullable=dict(type='rule_based',rules=[])
    weighted=dict(type='weighted',entries=[dict(weight=1,provider=state(5)),dict(weight=3,provider=state(7))])
    live=dict(type='rule_based',rules=[dict(predicate=mat([5],(0,-1,0)),provider=state(7))],fallback=state(5))
    fs=[plant(state(5),[row(5,(5,4))]),plant(state(5),[row(5,(5,4),(7,6),mat([1],(0,-1,0)),mat([0,2]))]),
        plant(nullable,[]),plant(state(1),[],current_survival=True),
        plant(dict(type='random_block',states=[1,4,5]),[],current_survival=True),
        plant(dict(type='random_block',states=[1,5]),[row(1,(1,7)),row(5,(5,4),(7,6))]),
        column([layer(2,state(5)),layer(3,state(7))]),column([layer(2,state(5)),layer(3,state(7))],tip=True),
        column([layer(dict(type='uniform',min_inclusive=0,max_inclusive=4),weighted),layer(3,live)]),
        column([layer(0,state(5))]),column([layer(5,nullable)],allowed=yes),
        disk(weighted),disk(live),disk(nullable),disk(state(5),radius=0,half_height=0),
        disk(state(7),radius=8,half_height=4,target=mat([1])),
        disk(weighted,radius=dict(type='uniform',min_inclusive=1,max_inclusive=4)),
        disk(dict(type='rule_based',rules=[dict(predicate=mat([7],(-1,0,0)),provider=state(5))],fallback=state(7)))]
    for direction in ((0,-1,0),(1,0,0),(-1,0,0),(0,0,1),(0,0,-1)):
        fs.append(column([layer(3,weighted)],direction,allowed=yes))
    # A long valid vertical column exercises clipped writes without clipping RNG.
    fs.append(column([layer(192,weighted)],allowed=yes))
    fs += [plant(state(5),[row(5,(5,4),(7,6),yes,mat([1]))]),
           plant(state(4),[],current_survival=True)]
    source['decorations']=[dict(f,salt=i,placement=[]) for i,f in enumerate(fs)]
    source['biomes']=[dict(decorations=list(range(len(fs))))];source['ordered_decorations']=True
    source['decoration_provider_noises']=[]
    source['simple_current_states']=[None,row(1,(1,7)),None,None,None,row(5,(5,4),(7,6)),None,None]
    empty=copy.deepcopy(source);empty['decorations']=[];empty['biomes']=[dict(decorations=[])];empty.pop('simple_current_states')
    invalid=[]
    def bad(f):
        p=copy.deepcopy(source);p['decorations'][0]=dict(f,salt=0,placement=[]);invalid.append(p)
    for f in [plant(state(8),[row(8,(8,8))]),plant(state(5),[]),plant(state(5),[row(5,(5,))]),
              plant(state(5),[row(5,(5,5),(8,8))]),plant(state(5),[row(5,(5,5),survival=mat([8]))]),
              plant(state(5),[row(5,(5,5),allowed=mat([0],(18,0,0)))]),
              column([]),column([layer(1,state(5))],(0,0,0)),column([layer(1,state(5))],(1,1,0)),
              column([layer(-1,state(5))]),column([layer(4096,state(5)),layer(1,state(5))]),
              column([layer(16,state(5))],(1,0,0)),column([layer(1,state(8))]),
              disk(state(5),radius=-1),disk(state(5),radius=9),disk(state(5),half_height=5),
              disk(state(5),target=mat([8]))]: bad(f)
    for key,value in [('direction',[1,0]),('prioritize_tip',1),('layers',None)]:
        f=copy.deepcopy(fs[6]);f[key]=value;bad(f)
    for mutate in ('length','index','bounds','null'):
        p=copy.deepcopy(source)
        if mutate=='length':p['simple_current_states'].pop()
        if mutate=='index':p['simple_current_states'][1]['source']=5
        if mutate=='bounds':p['simple_current_states'][1]['lower'][0]=8
        if mutate=='null':p['simple_current_states']=None
        invalid.append(p)
    return source,empty,invalid


def controls(source):
    fs,rows=expected(source)
    def get(i,c,r=0):return rows[i*32+c*2+r]
    p=point(1);above=shift(p,(0,1,0))
    assert get(1,1)[2][p]==4 and get(1,1)[2][above]==6, 'waterlogged paired plant'
    assert not get(1,3)[2], 'paired support rejects top boundary'
    assert not get(2,0)[1] and not get(2,0)[2], 'nullable provider places nothing'
    assert get(3,0)[2][point(0)]==1, 'shared current survival lookup'
    assert get(6,2)[2][point(2)]==5 and get(7,2)[2][point(2)]==7, 'base/tip truncation'
    assert len(get(6,2)[2])==1, 'checks next position, leaves blocked block intact'
    assert get(9,0)[1] is False, 'zero column'
    assert get(6,0,1)[1] and get(6,0,1)[2]==get(6,0)[2], 'second placement sees existing column'
    assert all(not get(i,5)[2] and not get(i,6)[2] for i in range(6)), 'simple writes in unknown columns'
    assert all(World(source,c).column(p[0],p[2]) is not None for i in range(len(fs)) for c in (5,6) for p in get(i,c)[2]), 'unknown columns never published'
    long_column=next(i for i,f in enumerate(fs) if f['kind']=='block_column' and f['layers'][0]['height']==192)
    assert get(long_column,0)[2] and len(get(long_column,0)[2])==64, 'build-height clipping'
    assert get(long_column,0)[0]!=get(long_column,0,1)[0], 'clipped column still consumes provider draws'
    assert not get(len(fs)-2,0)[1] and not get(len(fs)-2,0)[2], 'upper space predicate rejects a pair'
    assert not get(len(fs)-1,0)[1] and not get(len(fs)-1,0)[2], 'unsupported shared survival never guesses'
    x,y,z=point(0)
    assert get(17,0)[2][x,y+1,z-1]==5, 'disk sees previous X write in this row'
    return dict(waterlogged_pairs='pass',upper_obstruction='pass',current_survival='pass',nullable='pass',
                base_tip_truncation='pass',unsupported_current_state='pass',live_repeated_writes='pass',unknown_columns='pass',
                build_height_clipping='pass',ordered_disk_writes='pass',signed_overflow='pass')


def exercise(binary,folder,name,source,gpu,threads=2):
    path=folder/f'{name}.rbp';path.write_bytes(fixture_wire(source,True))
    output=folder/f'{name}-{int(gpu)}-{threads}.bin'
    run=command(['nice','-n','10',binary,path,output,'--threads',threads,'--gpu','on' if gpu else 'off'],timeout=600)
    data=output.read_bytes();return check(data,source),data,run.stderr


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--profile',action='append',type=Path,required=True)
    parser.add_argument('--skip-build',action='store_true',help='Reuse both normal and observer binaries');parser.add_argument('--prove-metal',action='store_true');args=parser.parse_args()
    folder=ROOT/'build/bend/surface-feature-tests';folder.mkdir(parents=True,exist_ok=True)
    binary=ROOT/'build/bend/surface-features';driver=ROOT/'bend/tests/surface-features.bend'
    if not args.skip_build:build(DEFAULT_BEND,binary,driver)
    profiles=[(f'profile-{i}',json.loads(p.read_text())) for i,p in enumerate(args.profile)]
    fixture,empty,invalid=fixtures(profiles[0][1]['decoration_noise']['permutation']);semantics=controls(fixture)
    profiles += [('fixture',fixture),('empty',empty)];runs=[];outputs={}
    for gpu in (False,True):
        for name,source in profiles:
            result,data,_=exercise(binary,folder,name,source,gpu)
            if not gpu:outputs[name]=data
            else:assert data==outputs[name],(name,'CPU/Metal bytes differ')
            runs.append(dict(profile=name,gpu_required=gpu,**result))
    _,data,_=exercise(binary,folder,'fixture-one-thread',fixture,False,threads=1);assert data==outputs['fixture']
    for i,source in enumerate(invalid):
        path=folder/f'invalid-{i}.rbp';path.write_bytes(fixture_wire(source,True));output=folder/f'invalid-{i}.bin'
        output.unlink(missing_ok=True)
        run=subprocess.run(['nice','-n','10',str(binary),str(path),str(output),'--threads','2','--gpu','off'],capture_output=True,text=True,timeout=300)
        assert run.returncode and 'invalid surface feature' in run.stderr,(i,run.stderr)
        assert not output.exists(),i
    observed=[]
    if args.prove_metal:
        probe=ROOT/'build/bend/surface-features-observer'
        if not args.skip_build:compile_metal_observer(DEFAULT_BEND,driver,probe,gpu_archive=binary.with_suffix('.gpu'))
        assert probe.with_suffix('.gpu').read_bytes()==binary.with_suffix('.gpu').read_bytes()
        for name,source in profiles:
            result,data,stderr=exercise(probe,folder,'observer-'+name,source,True);assert data==outputs[name]
            commands=[float(n) for n in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)]
            if result['features']:assert commands,(name,'no actual Metal command buffers')
            observed.append(dict(profile=name,device_ms=commands,command_buffers=len(commands),sha256=result['sha256']))
    paths=[ROOT/p for p in ('bend/surface_feature.bend','bend/registry_surface_feature.bend','bend/tests/surface-features.bend','bend/tests/surface-feature-world.bend','scripts/test_bend_surface_features.py')]
    report=dict(scope=__doc__.strip(),bend_version=command([DEFAULT_BEND,'version']).stdout.strip(),runs=runs,semantic_controls=semantics,
                malformed_rejected=len(invalid),cpu_gpu_bytes_equal=True,one_cpu_thread='pass',metal_observation=observed,
                profiles={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in args.profile},
                sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
                native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),gpu_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-surface-features-correctness.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('runs','malformed_rejected','metal_observation')},indent=2))


if __name__=='__main__':main()
