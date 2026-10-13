#!/usr/bin/env python3
"""Actual registered surface, plant, aquatic and bamboo writes on CPU and observed Metal.

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

from test_bend_compression import ROOT, DEFAULT_BEND, build, command, metal_compiler_command
from test_bend_decoration_counts import Random, provider as integer, MASK64
from test_bend_engine import compile_metal_observer
from test_bend_noise import MASK, mix_hash, f32, world_simplex
from test_bend_placement_programs import fixtures as placement_fixtures, origin as old_origin, signed
from test_bend_placement_walker import World as BaseWorld, predicate, shift
from test_bend_block_providers import sample
from test_bend_registry_noise import fixture_wire
from test_bend_placement_merge import sources

KINDS = ('simple_block', 'block_column', 'disk', 'plant', 'aquatic', 'bamboo')
CONTEXTS = 20


def origin(context):
    return {16:(-424,-1008),17:(29990064,-30000008)}.get(context,old_origin(context))


def band(x,z):
    return 1 if world_simplex(x&MASK,z&MASK,f32(.005),2345,0)<f32(-.8) else 2


def features(node):
    if isinstance(node, dict):
        if node.get('kind') in KINDS: yield node
        for child in node.values(): yield from features(child)
    elif isinstance(node, list):
        for child in node: yield from features(child)


class World(BaseWorld):
    def __init__(self,source,context):
        super().__init__(source,context)
        self.ox,self.oz=origin(context)

    def material(self, x, y, z):
        if not -64 <= y < 64: return 0
        if (x,y,z) in self.patch: return self.patch[x,y,z]
        if self.column(x,z) is None: return None
        offset = y+64
        if offset == 0: return self.source['bedrock']
        if offset < 55: return 1
        if offset < 63: return 10 if len(self.source['materials']) > 8 else 1
        if offset == 63: return 9 if len(self.source['materials']) > 8 else (7 if 8 <= self.context < 16 else 1)
        if self.context % 4 == 1 and offset < 68: return self.source['water']
        if self.context % 4 == 2 and offset == 66: return 1
        return 0


def point(context):
    x,z=origin(context);i=context%8
    if context>=16: return signed((x+8)&MASK),0,signed((z+8)&MASK)
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
    if kind in ('plant','aquatic'):
        selected_band = band(at[0],at[2]) if world.column(at[0],at[2]) is not None else None
        states = [s for s in f['states'] if selected_band is not None and s['band'] in (0,selected_band)]
        scale = max([f32(s['weight']) for s in states],default=0.)
        scale = max(scale,f32(1.17549435e-38));total=0.
        for s in states: total=f32(total+f32(f32(s['weight'])/scale))
        choice=f32(rng.unit()*total);row=None
        for s in states:
            row=s;weight=f32(f32(s['weight'])/scale)
            if choice<weight:break
            choice=f32(choice-weight)
        if row is None:return False
        above=shift(at,(0,1,0));paired=row['upper']!=0
        if kind=='plant':
            ground=shift(at,(0,-1,0));soil=world.material(*ground) if ground is not None else None
            if not -64<=at[1]<64 or world.material(*at)!=0 or soil is None or not source['material_flags'][soil]&(2 if row['dry'] else 1):return False
            if paired and (above is None or not -64<=above[1]<64 or world.material(*above)!=0):return False
        elif not test(row['survival'],at) or paired and (above is None or not test(row['upper_allowed'],above)):return False
        if paired:world.put(*above,row['upper'])
        world.put(*at,row['lower']);return True
    if kind=='bamboo':
        if world.material(*at)!=0 or not test(f['survival'],at):return False
        height=rng.integer(5,16);writes=[]
        if rng.unit()<f32(f['probability']):
            radius=rng.integer(1,4)
            for dx in range(-radius,radius+1):
                for dz in range(-radius,radius+1):
                    if dx*dx+dz*dz>radius*radius:continue
                    pos=shift(at,(dx,0,dz))
                    if pos is None:continue
                    h=world.height(pos[0],pos[2],1)
                    pos=shift((pos[0],h,pos[2]),(0,-1,0)) if h is not None else None
                    if pos is not None and test(f['podzol_allowed'],pos):writes.append((pos,f['podzol']))
        count=0
        while count<height:
            pos=shift(at,(0,count,0))
            if pos is None or world.material(*pos)!=0:break
            writes.append((pos,f['trunk']));count+=1
        if count>=3:
            for dy,material in enumerate(f['crown']):
                pos=shift(at,(0,count-dy,0))
                if pos is not None:writes.append((pos,material))
        for pos,m in writes:world.put(*pos,m)
        return True
    raise AssertionError(kind)


def expected(source):
    fs=list(features(source.get('decorations',[])));rows=[]
    for i,f in enumerate(fs):
        for context in range(CONTEXTS):
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
        assert actual==patch, ('voxels',i,fs[i//(CONTEXTS*2)]['kind'],list(actual.items())[:8], list(patch.items())[:8])
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
    sprout=lambda lower=5,upper=0,weight=1.,band=0,dry=False:dict(lower=lower,upper=upper,weight=weight,band=band,dry=dry)
    land=lambda rows:dict(kind='plant',states=rows)
    aquatic=lambda rows:dict(kind='aquatic',states=rows)
    waterplant=lambda lower=5,upper=0,weight=1.,band=0,survival=mat([2]),allowed=mat([2]):dict(lower=lower,upper=upper,weight=weight,band=band,survival=survival,upper_allowed=allowed)
    bamboo=lambda **kw:dict(kind='bamboo',probability=kw.get('probability',1.),trunk=kw.get('trunk',5),crown=kw.get('crown',[4,5,7]),podzol=kw.get('podzol',7),survival=kw.get('survival',mat([1,7],(0,-1,0))),podzol_allowed=kw.get('podzol_allowed',mat([1,7])))
    fs += [land([sprout()]),land([sprout(5,4)]),land([sprout(dry=True)]),
           land([sprout(5,weight=.25,band=1),sprout(7,weight=.75,band=2)]),
           land([sprout(band=1)]),land([sprout(band=2)]),
           land([sprout(5,weight=3e38),sprout(7,weight=3e38)]),
           land([sprout(5,weight=1e-30),sprout(7,weight=3e-30)]),
           aquatic([waterplant()]),aquatic([waterplant(5,7)]),
           aquatic([waterplant(5,7,allowed=mat([1]))]),
           aquatic([waterplant(5,weight=.25,band=1,survival=yes),waterplant(7,weight=.75,band=2,survival=yes)]),
           bamboo(),bamboo(probability=0.),bamboo(probability=.5),bamboo(survival=yes),
           # Podzol removes a heightmap block; all probes must still read the
           # pre-feature snapshot, even for unusual registered materials.
           bamboo(podzol=0),bamboo(podzol_allowed=mat([1],(-1,0,0)))]
    source['material_flags'][1]=1
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
    for f in [land([]),aquatic([]),land([sprout(lower=8)]),land([sprout(upper=8)]),
              land([sprout(weight=0.)]),land([sprout(weight=-1.)]),land([sprout(weight=1e40)]),
              land([sprout(band=3)]),land([sprout(dry=1)]),
              aquatic([waterplant(survival=mat([8]))]),aquatic([waterplant(allowed=mat([0],(18,0,0)))]),
              bamboo(probability=-.1),bamboo(probability=1.1),bamboo(trunk=8),bamboo(podzol=8),
              bamboo(crown=[4,5]),bamboo(crown=[4,5,8]),bamboo(survival=mat([8])),bamboo(podzol_allowed=mat([8]))]:bad(f)
    for key,value in [('direction' ,[1,0]),('prioritize_tip',1),('layers',None)]:
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
    def get(i,c,r=0):return rows[i*(CONTEXTS*2)+c*2+r]
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
    assert get(24,0)[1] and not get(24,0,1)[1], 'ordinary plants cannot overwrite earlier plants'
    assert not get(24,10)[1] and get(26,10)[1], 'dry material flag has a distinct substrate'
    assert [band(point(c)[0],point(c)[2]) for c in (16,17,18,19)]==[1,1,2,2], 'independent near/distant noise bands'
    assert get(27,16)[2][point(16)]==5 and get(27,18)[2][point(18)]==7, 'procedural low/high bands'
    assert get(35,17)[2][point(17)]==5, 'distant low-band aquatic selection'
    assert not get(28,2)[1] and not get(29,16)[1], 'ineligible bands never fall back to another state'
    seed=((((0x80000000^(28*7919))&MASK)<<32)|mix_hash((2*811+28)&MASK))
    assert get(28,2)[0]==(seed+0x9e3779b97f4a7c15)&MASK64, 'empty band still consumes one selection draw'
    assert get(30,0)[1] and get(31,0)[1], 'large and small finite weights remain selectable'
    assert get(33,1)[2][point(1)]==5 and get(33,1)[2][shift(point(1),(0,1,0))]==7, 'aquatic pair replaces water'
    assert not get(34,1)[1] and not get(34,1)[2], 'aquatic upper predicate rejects both halves'
    assert get(35,4)[1] and get(35,4)[2], 'aquatic feature replacement can write a nonair target'
    x,y,z=point(0);stem=get(37,0)[2];top=max(py for px,py,pz in stem if px==x and pz==z)
    assert (stem[x,top,z],stem[x,top-1,z],stem[x,top-2,z])==(4,5,7), 'complete bamboo crown'
    seed=((((0x80000000^(37*7919))&MASK)<<32)|mix_hash(37))
    assert get(37,0)[0]==(seed+2*0x9e3779b97f4a7c15)&MASK64, 'probability zero still consumes the chance draw'
    assert get(37,0,1)[0]==get(37,0)[0] and not get(37,0,1)[1], 'bamboo rejects occupied origin before RNG'
    assert len(get(37,2)[2])==2 and shift(point(2),(0,2,0)) not in get(37,2)[2], 'blocked bamboo has no crown'
    assert get(40,0)[2][x,-1,z]==0 and get(40,0)[2][x,0,z]==5, 'podzol snapshot preserves the stem anchor'
    # Neighbor tests must not see a preceding podzol write in this same patch.
    soil_patch=get(41,0)[2]
    assert all(m==7 for (px,py,pz),m in soil_patch.items() if py==-1)
    assert sum(py==-1 for px,py,pz in soil_patch)>5, 'snapshot podzol circle is not an alternating scan'
    return dict(ordinary_plant_replacement='pass',dry_substrates='pass',weighted_bands='pass',
                procedural_near_and_distant_bands='pass',empty_band_rng='pass',
                finite_weight_scaling='pass',aquatic_pair_predicates='pass',aquatic_replacement='pass',
                bamboo_crown='pass',bamboo_rng='pass',bamboo_obstruction='pass',podzol_snapshot='pass',
                waterlogged_pairs='pass',upper_obstruction='pass',current_survival='pass',nullable='pass',
                base_tip_truncation='pass',unsupported_current_state='pass',live_repeated_writes='pass',unknown_columns='pass',
                build_height_clipping='pass',ordered_disk_writes='pass',signed_overflow='pass')


def exercise(binary,folder,name,source,gpu,threads=2):
    print(f'{name}: {"Metal" if gpu else "CPU"}, {threads} threads',flush=True)
    path=folder/f'{name}.rbp';path.write_bytes(fixture_wire(source,True))
    output=folder/f'{name}-{int(gpu)}-{threads}.bin'
    run=command(['nice','-n','10',binary,path,output,'--threads',threads,'--gpu','on' if gpu else 'off'],timeout=600)
    data=output.read_bytes();return check(data,source),data,run.stderr


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--profile',action='append',type=Path,required=True)
    parser.add_argument('--skip-build',action='store_true',help='Reuse both normal and observer binaries');parser.add_argument('--prove-metal',action='store_true');args=parser.parse_args()
    folder=ROOT/'build/bend/surface-feature-tests';folder.mkdir(parents=True,exist_ok=True)
    binary=ROOT/'build/bend/surface-features';driver=ROOT/'bend/tests/surface-features.bend'
    if not args.skip_build:
        print('Compiling stock Bend surface fixture at nice +10 (one compiler)',flush=True)
        generated=binary.with_suffix('.c');build(DEFAULT_BEND,generated,driver)
        command(metal_compiler_command(generated,binary),timeout=600,cwd=ROOT)
        command(['nice','-n','10',binary,'--threads','2','--gpu-build'],timeout=1800,cwd=ROOT)
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
    paths=sorted(sources(driver)|{Path(__file__).resolve()})
    report=dict(scope=__doc__.strip(),bend_version=command([DEFAULT_BEND,'version']).stdout.strip(),runs=runs,semantic_controls=semantics,
                malformed_rejected=len(invalid),cpu_gpu_bytes_equal=True,one_cpu_thread='pass',metal_observation=observed,
                profiles={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in args.profile},
                sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
                native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),gpu_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-surface-features-correctness.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('runs','malformed_rejected','metal_observation')},indent=2))


if __name__=='__main__':main()
