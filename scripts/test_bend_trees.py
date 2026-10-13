#!/usr/bin/env python3
"""Registered tree geometry and ordered voxel writes on CPU and observed Metal.

Independent Python replay follows the native approximation's shape rules using
Bend's F32/local-coordinate arithmetic and RNG. Unchanged registry profiles,
obstructions, signed/distant coordinates, connected leaf distances, roots, vines,
cocoa, repeated placement and invalid recipes are checked. Playable vegetation
coordination, structures and complete lit-region benchmarks remain separate.
"""
import argparse
from collections import Counter, deque
import copy
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import subprocess

from test_bend_compression import ROOT, DEFAULT_BEND, build, command, metal_compiler_command
from test_bend_decoration_counts import Random, MASK64
from test_bend_engine import compile_metal_observer
from test_bend_noise import MASK, f32, mix_hash
from test_bend_registry_noise import fixture_wire
from test_bend_placement_walker import shift
from test_bend_placement_merge import sources

DIRECTIONS = [(1,0,0),(-1,0,0),(0,0,1),(0,0,-1)]
NEIGHBORS = [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]
TRUNKS = ['fancy_trunk_placer','dark_oak_trunk_placer','mega_jungle_trunk_placer',
          'forking_trunk_placer','upwards_branching_trunk_placer','giant_trunk_placer']
FOLIAGES = ['blob_foliage_placer','fancy_foliage_placer','bush_foliage_placer','spruce_foliage_placer',
            'pine_foliage_placer','mega_pine_foliage_placer','acacia_foliage_placer','jungle_foliage_placer','dark_oak_foliage_placer']
add=lambda a,b:f32(f32(a)+f32(b))
sub=lambda a,b:f32(f32(a)-f32(b))
mul=lambda a,b:f32(f32(a)*f32(b))
div=lambda a,b:f32(f32(a)/f32(b))
sqrt=lambda a:f32(math.sqrt(f32(a)))
sin=lambda a:f32(math.sin(f32(a)))
cos=lambda a:f32(math.cos(f32(a)))


def features(node):
    if isinstance(node,dict):
        if node.get('kind')=='tree':yield node
        for v in node.values():yield from features(v)
    elif isinstance(node,list):
        for v in node:yield from features(v)


def origin(context):
    return [(29990000,-30000000),(-128,713),((1<<31)-32,-(1<<31)),(-(1<<31),(1<<31)-32)][context%4]


def ground(context):return 315 if context==3 else -32 if context==8 else 0


def point(context):
    x,z=origin(context)
    return (x+({4:0,5:31,6:32}.get(context,16)),ground(context),z+(31 if context==5 else 16))


class World:
    def __init__(self,source,context):
        self.source,self.context=source,context;self.ox,self.oz=origin(context);self.patch={}
        if context==11:
            x,y,z=point(context);self.put(x,y+3,z,1)

    def column(self,x,z):
        dx,dz=x-self.ox,z-self.oz
        return dx+dz*32 if 0<=dx<32 and 0<=dz<32 and (self.context>=12 or dx+dz*32!=1023) else None

    def ground(self,x,z):return ground(self.context)+(5 if self.context==9 and x-self.ox>=17 else 0)

    @lru_cache(maxsize=2048)
    def base_height(self,x,z):
        if self.column(x,z) is None:return None
        return max(self.ground(x,z),4 if self.context==1 else -64,9 if self.context==2 else -64)

    def above(self,p):
        h=self.base_height(p[0],p[2]);return h is not None and p[1]>=h

    def material(self,x,y,z):
        if not -64<=y<320:return 0
        if (x,y,z) in self.patch:return self.patch[x,y,z]
        if self.column(x,z) is None:return None
        h=self.ground(x,z)
        if y==-64:return self.source['bedrock']
        if y<h-2:return 1
        if y==h-2:return 10
        if y==h-1:return 1 if self.context==7 else 5 if self.context==10 else 9
        if self.context==1 and y<4:return self.source['water']
        if self.context==2 and y==8:return 1
        return 0

    def put(self,x,y,z,material):
        if self.column(x,z) is not None and -64<=y<320 and 0<=material<len(self.source['materials']):self.patch[x,y,z]=material

    def flags(self,m):return self.source['material_flags'][m] if m is not None else 0


def below(rng,n):return rng.integer(0,n-1) if n>1 else 0

def between(rng,r):return r[0]+below(rng,r[1]-r[0]+1)


def weighted(rng,states):
    scale=max([f32(s['weight']) for s in states]+[f32(1.17549435e-38)])
    total=0.
    for s in states:total=add(total,div(s['weight'],scale))
    n=mul(rng.unit(),total)
    for s in states:
        weight=div(s['weight'],scale)
        if n<weight:return s
        n=sub(n,weight)
    return states[-1]


def limb(wood,start,end):
    d=tuple(b-a for a,b in zip(start,end));steps=max(1,*map(abs,d))
    for i in range(steps+1):
        q=tuple(math.floor(add(.5,div(mul(i,v),steps))) for v in d)
        axis=0 if max(abs(q[0]),abs(q[2]))==0 else 1 if abs(q[0])>=abs(q[2]) else 2
        wood.append((tuple(a+b for a,b in zip(start,q)),axis))


def geometry(shape,height,width,rng):
    wood=[];crowns=[]
    if shape=='fancy_trunk_placer':
        h=height+2;trunk=math.floor(mul(h,.618));limb(wood,(0,0,0),(0,trunk,0))
        attachments=[((0,h-5,0),trunk)]
        for layer in range(h-5,-1,-1):
            if layer<mul(h,.3):continue
            radius=div(h,2);adjacent=sub(radius,layer)
            r=mul(mul(sqrt(max(0,sub(mul(radius,radius),mul(adjacent,adjacent)))),.5),add(rng.unit(),.328))
            angle=mul(rng.unit(),6.283185307179586)
            dx=max(-7,min(7,math.floor(add(mul(r,sin(angle)),.5))))
            dz=max(-7,min(7,math.floor(add(mul(r,cos(angle)),.5))))
            at=(dx,layer-1,dz);base=min(math.floor(sub(at[1],mul(sqrt(dx*dx+dz*dz),.381))),trunk)
            attachments.append((at,base))
        for at,base in attachments:
            if base>=mul(h,.2):limb(wood,(0,base,0),at);crowns.append((at,0,1))
        return wood,crowns
    if shape=='dark_oak_trunk_placer':
        dx,_,dz=DIRECTIONS[below(rng,4)];lean=height-below(rng,4);steps=2-below(rng,3);x=z=0
        for i in range(height):
            if i>=lean and steps>0:x+=dx;z+=dz;steps-=1
            for ox in range(2):
                for oz in range(2):wood.append(((x+ox,i,z+oz),0))
        crowns=[((x,height-1,z),0,2)]
        for ox in range(-1,3):
            for oz in range(-1,3):
                if (ox not in (0,1) or oz not in (0,1)) and below(rng,3)==0:
                    length=below(rng,3)+2
                    for i in range(length):wood.append(((ox,height-2-i,oz),0))
                    crowns.append(((ox,height-1,oz),0,1))
        return wood,crowns
    # Unknown registered placers retain the native simple-trunk approximation.
    dx,_,dz=DIRECTIONS[below(rng,4)];fork=shape=='forking_trunk_placer'
    for i in range(height):
        bend=i-(height-3)+1 if fork and i>=height-3 else 0
        for z in range(width):
            for x in range(width):
                if width==2 and i==height-1 and (x!=0 or z!=0):continue
                wood.append(((x+dx*bend,i,z+dz*bend),0))
    crowns=[((dx*3 if fork else 0,height,dz*3 if fork else 0),0,width)]
    if shape=='mega_jungle_trunk_placer':
        level=height-2-below(rng,4)
        while level>height//2:
            angle=mul(rng.unit(),6.283185307179586)
            for b in range(5):
                x,z=int(add(1.5,mul(cos(angle),b))),int(add(1.5,mul(sin(angle),b)))
                wood.append(((x,level-3+b//2,z),0))
            crowns.append(((x,level,z),-2,1));level-=2+below(rng,4)
    elif fork or shape=='upwards_branching_trunk_placer':
        for i in range(1,4):wood.append(((dx*i,height-2+i//2,dz*i),1 if dx else 2))
        crowns.append(((dx*3,height,dz*3),0,1))
    return wood,crowns


def foliage(crowns,shape,r,o,fh,rng):
    positions=[];conifer=shape in ('spruce_foliage_placer','pine_foliage_placer','mega_pine_foliage_placer')
    for (cx,cy,cz),ro,width in crowns:
        if shape=='dark_oak_foliage_placer':
            double=width==2;rows=[(-1,r+2),(0,r+3),(1,r+2)] if double else [(-1,r+2),(0,r+1)]
            if double and rng.unit()<.5:rows.append((2,r))
            for dy,rr in rows:
                for dx in range(-rr,rr+width):
                    for dz in range(-rr,rr+width):
                        if dy==0 and double and (dx==-rr or dx>=rr) and (dz==-rr or dz>=rr):continue
                        ax=min(abs(dx),abs(dx-width+1));az=min(abs(dz),abs(dz-width+1))
                        if dy==-1 and not double and ax==rr and az==rr or dy==1 and ax+az>rr*2-2:continue
                        positions.append((cx+dx,cy+o+dy,cz+dz))
            continue
        jungle=shape=='jungle_foliage_placer';fancy=shape=='fancy_foliage_placer';bush=shape=='bush_foliage_placer'
        layers=1+below(rng,2) if jungle and width==1 else 2 if shape=='acacia_foliage_placer' else fh
        for dy in range(layers+1):
            rr=(r+ro+1-o+dy if jungle else r+ro+int(dy!=0 and dy!=layers) if fancy else
                r+ro-1-o+dy if bush else r+ro-1-int((o-dy)/2) if shape=='blob_foliage_placer' else
                min(dy//2+1,r+1) if conifer else r+1-dy if shape=='acacia_foliage_placer' else min(r-1+dy//2,5))
            rr=max(0,min(6,rr))
            for dx in range(-rr,rr+width):
                for dz in range(-rr,rr+width):
                    ax=min(abs(dx),abs(dx-width+1));az=min(abs(dz),abs(dz-width+1))
                    skip=(ax+az>=7 or ax*ax+az*az>rr*rr if jungle else
                          (ax+.5)**2+(az+.5)**2>rr*rr if fancy else
                          ax==rr and az==rr and (conifer or not bush and dy==0 or below(rng,2)==0))
                    if not skip:positions.append((cx+dx,cy+o-dy,cz+dz))
    return positions


def mix(x):
    x&=MASK64;x=((x^(x>>30))*0xbf58476d1ce4e5b9)&MASK64;x=((x^(x>>27))*0x94d049bb133111eb)&MASK64
    return x^(x>>31)


def decorate(recipe,wood,leaf,occupied,seed,world):
    writes=[];base=min(p[1] for p,axis in wood)
    for index,d in enumerate(recipe['decorators']):
        local=seed^mix(index);kind=d['kind'];prob=f32(d['probability'])
        if kind=='cocoa' and Random(local).unit()>=prob:continue
        positions=leaf if kind=='leaf_vine' else [p for p,axis in wood]
        for x,y,z in sorted(set(positions),key=lambda p:(p[1],p[0],p[2])):
            if kind=='cocoa' and y-base>2:continue
            rng=Random(local^mix(x&MASK)^mix((z&MASK)<<32)^mix(y&MASK64))
            for side,(dx,_,dz) in enumerate(DIRECTIONS):
                if rng.unit()>=(.25 if kind=='cocoa' else prob):continue
                m=d['states'][side*3+below(rng,3) if kind=='cocoa' else side]
                for drop in range(5 if kind=='leaf_vine' else 1):
                    p=shift((x,y,z),(dx,-drop,dz))
                    if p is None or p in occupied or not world.above(p):break
                    occupied.add(p);writes.append((p,m,3 if kind=='cocoa' else 4,side))
    return writes


def plan(f,rng,world,at):
    groundpos=shift(at,(0,-1,0));m=world.material(*groundpos) if groundpos else None
    soil2=shift(at,(0,-2,0));snow=m==world.source['snow'] and soil2 is not None and world.flags(world.material(*soil2))&1
    if m is None or not world.flags(m)&1 and not snow:return False,[]
    h=max(1,f['height'][0]+below(rng,f['height'][1]+1)+below(rng,f['height'][2]+1))
    root=shift(at,(0,between(rng,f['root_offset']),0))
    if root is None or not -64<=root[1]+h+4<320:return False,[]
    log=weighted(rng,f['logs']);leaf=weighted(rng,f['leaves'])
    shape=f['trunk_shape'];width=2 if shape in (TRUNKS[1],TRUNKS[2],TRUNKS[5]) else 1
    wood,crowns=geometry(shape,h,width,rng)
    wood=[(shift(root,p),axis) for p,axis in wood]
    if any(p is None or not world.above(p) or (world.material(*p)!=0 and not world.flags(world.material(*p))&20) for p,axis in wood):return False,[]
    writes=[]
    if f['soil']:
        for dz in range(width):
            for dx in range(width):
                p=shift(at,(dx,-1,dz))
                if p is not None:writes.append((p,f['soil'],0,0))
    writes.extend((p,log['axes'][axis],1,0) for p,axis in wood)
    if f['root']:
        for dx,_,dz in DIRECTIONS:
            for distance in range(4):
                p=shift(root,(dx*distance,-(distance//2),dz*distance))
                if p is None or world.column(p[0],p[2]) is None:continue
                for y in range(world.ground(p[0],p[2]),p[1]+1):writes.append(((p[0],y,p[2]),f['root'],1,0))
    shape=f['foliage_shape'];r=max(0 if shape=='dark_oak_foliage_placer' else 1,min(4,between(rng,f['radius'])))
    o=between(rng,f['offset'])
    fh=(max(4,h-between(rng,f['trunk_height'])) if shape=='spruce_foliage_placer' else
        max(6,between(rng,f['foliage_height'])) if shape=='mega_pine_foliage_placer' else max(2,between(rng,f['foliage_height'])))
    positions=foliage(crowns,shape,r,o,min(h+2,fh),rng)
    remaining={p for offset in positions if (p:=shift(root,offset)) is not None and world.above(p)}
    occupied={p for p,axis in wood};remaining-=occupied;queue=deque((p,0) for p in dict.fromkeys(p for p,axis in wood));emitted=[]
    while queue:
        p,d=queue.popleft()
        if d>=6:continue
        for delta in NEIGHBORS:
            n=shift(p,delta)
            if n in remaining:
                remaining.remove(n);occupied.add(n);queue.append((n,d+1));emitted.append(n)
                writes.append((n,leaf['distances'][d],2,0))
    if f['decorators']:writes+=decorate(f,wood,emitted,occupied,rng.next(),world)
    return True,writes


def replay(writes,world):
    for phase in range(5):
        for at,m,role,side in writes:
            if role!=phase:continue
            previous=world.material(*at)
            if previous is None:continue
            flags=world.flags(previous)
            replaces=(bool(flags&1) or previous==world.source['snow'] if role==0 else
                      previous==0 or bool(flags&20) if role==1 else previous==0)
            if not replaces:continue
            if role>=3:
                d=DIRECTIONS[side];support=shift(at,tuple(-v for v in d));above=shift(at,(0,1,0))
                supported=support is not None and world.flags(world.material(*support))&(32 if role==3 else 12)
                hanging=role==4 and above is not None and world.material(*above)==m
                if not supported and not hanging:continue
            world.put(*at,m)


def expected(source):
    fs=list(features(source.get('decorations',[])));rows=[];metrics=Counter()
    for i,f in enumerate(fs):
        for context in range(12):
            seed=((((0x80000000^(i*7919))&MASK)<<32)|mix_hash((context*811+i)&MASK))
            rng=Random(seed);world=World(source,context);at=point(context)
            for repeat in range(2):
                placed,writes=plan(f,rng,world,at);replay(writes,world)
                rows.append((rng.state,placed,dict(world.patch)))
                metrics.update({f'role-{role}':1 for _,_,role,_ in writes})
    return fs,rows,dict(metrics)


def check(data,source):
    fs,rows,roles=expected(source);offset=0
    def word():
        nonlocal offset
        v=struct.unpack_from('>I',data,offset)[0];offset+=4;return v
    assert word()==len(fs)
    touched=0
    for i,(state,placed,patch) in enumerate(rows):
        observed=(word()<<32)|word();ok=bool(word());n=word();actual={};last=None
        for _ in range(n):
            x,y,z=(v-(1<<32) if v&(1<<31) else v for v in (word(),word(),word()));m=word();key=(x,z,y)
            assert last is None or last<key,('order',i,last,key)
            actual[x,y,z]=m;last=key
        assert observed==state,('rng',i,fs[i//24]['source'] if 'source' in fs[i//24] else fs[i//24],observed,state)
        assert ok==placed,('placed',i,ok,placed)
        assert actual==patch,('voxels',i,fs[i//24].get('source'),Counter(actual.values()),Counter(patch.values()),list(set(actual.items())^set(patch.items()))[:20])
        touched+=len(actual)
    assert offset==len(data),(offset,len(data))
    return dict(trees=len(fs),writer_calls=len(rows),successful_calls=sum(row[1] for row in rows),touched_blocks=touched,
                trunks=dict(Counter(f['trunk_shape'] for f in fs)),foliage=dict(Counter(f['foliage_shape'] for f in fs)),roles=roles,
                bytes=len(data),sha256=hashlib.sha256(data).hexdigest())


def fixtures(source):
    p=copy.deepcopy(source);templates=list(features(p['decorations']));base=copy.deepcopy(next(f for f in templates if f['trunk_shape']=='mega_jungle_trunk_placer'))
    base.update(height=[16,5,3],radius=[2,4],offset=[0,1],foliage_height=[4,8],trunk_height=[2,4],root_offset=[0,0])
    recipes=[]
    for trunk in ['straight_trunk_placer',*TRUNKS,'unknown_trunk_placer']:
        for foliage_ in FOLIAGES+['unknown_foliage_placer']:
            f=copy.deepcopy(base);f.update(trunk_shape=trunk,foliage_shape=foliage_,source=f'fixture/{trunk}/{foliage_}');recipes.append(f)
    for spec in [dict(height=[1,0,0]),dict(height=[80,80,80]),dict(radius=[0,0]),dict(radius=[256,256]),
                 dict(root_offset=[7,7]),dict(root_offset=[-3,-3]),dict(root_offset=[256,256]),dict(offset=[-2,-1]),dict(offset=[256,256])]:
        f=copy.deepcopy(base);f.update(spec,source='fixture/boundary-'+str(spec));recipes.append(f)
    # Weighted entries select complete axis/distance tables, not individual blocks.
    f=copy.deepcopy(base);other=next(t for t in templates if t['trunk_shape']=='dark_oak_trunk_placer')
    f['logs']=[dict(axes=base['logs'][0]['axes'],weight=1.e-38),dict(axes=other['logs'][0]['axes'],weight=3.e38)]
    f['leaves']=[dict(distances=base['leaves'][0]['distances'],weight=3.e38),dict(distances=other['leaves'][0]['distances'],weight=1.e-38)]
    f['source']='fixture/weights';recipes.append(f)
    p['decorations']=recipes
    for biome in p['biomes']:biome['decorations']=[]
    # All synthetic recipes use the same original palette/flags and parser.
    empty=copy.deepcopy(p);empty['decorations']=[]
    invalid=[]
    def bad(key,value):
        q=copy.deepcopy(p);f=copy.deepcopy(base);f[key]=value;q['decorations']=[f];invalid.append(q)
    for key,values in dict(height=[[-1,0,0],[81,0,0],[4,0],None],radius=[[-1,4],[4,2],[2],None],
                           offset=[[-257,0],[0,257],[0]],foliage_height=[[-1,2],[5,2]],trunk_height=[[2,1]],root_offset=[[-257,0],[0,257]],
                           logs=[[],None,[dict(axes=[1,2],weight=1)],[dict(axes=[1,2,999999],weight=1)],[dict(axes=[1,2,3],weight=0)],
                                 [dict(axes=[1,2,3],weight=-1)],[dict(axes=[1,2,3],weight=1.e100)]],
                           leaves=[[],[dict(distances=[1],weight=1)]],soil=[999999],root=[-1,999999],trunk_shape=[1],foliage_shape=[None],
                           decorators=[[dict(kind='unknown',probability=1.,states=[1]*4)],
                                       [dict(kind='trunk_vine',probability=-.1,states=[1]*4)],
                                       [dict(kind='leaf_vine',probability=1.1,states=[1]*4)],
                                       [dict(kind='cocoa',probability=1.,states=[1]*4)],
                                       [dict(kind='cocoa',probability=1.,states=[999999]*12)],None]).items():
        for v in values:bad(key,v)
    return p,empty,invalid


def semantic_controls(source):
    fs,rows,_=expected(source)
    def get(i,c,r=0):return rows[i*24+c*2+r]
    normal=next(i for i,f in enumerate(fs) if f['trunk_shape']=='straight_trunk_placer' and f['foliage_shape']=='blob_foliage_placer')
    assert get(normal,0)[1] and get(normal,8)[1] and get(normal,10)[1]
    assert not any(get(normal,c)[1] for c in (1,2,3,5,6,7,11)),'water/ceiling/build/unknown/substrate/solid obstruction'
    assert not get(normal,0,1)[1] and get(normal,0,1)[2]==get(normal,0)[2],'second tree cannot replace its trunk'
    assert all(World(source,c).column(p[0],p[2]) is not None and -64<=p[1]<320 for i in range(len(fs)) for c in range(12) for p in get(i,c)[2]),'clipped writes'
    rng=Random(42);wood,crowns=geometry('mega_jungle_trunk_placer',26,2,rng)
    assert len(crowns)>=3 and len({p[1] for p,_,_ in crowns})>=3,'jungle branches'
    assert ((0,25,0),0) in wood and ((1,25,1),0) not in wood,'tapered giant trunk'
    variants={tuple(geometry('mega_jungle_trunk_placer',26,2,Random(seed))[0]) for seed in range(64)};assert len(variants)>=60
    wood,crowns=geometry('fancy_trunk_placer',14,1,Random(42));assert len(crowns)>=5 and all(any(p==at for p,_ in wood) for at,_,_ in crowns)
    recipes=list(features(source['decorations']));leaf_ids={m for f in recipes for row in f['leaves'] for m in row['distances']}
    # Every actually written leaf lies within six cardinal steps of a supporting
    # log, and its registered distance never claims a longer route than exists.
    checked=0
    for i,f in enumerate(fs):
        patch=get(i,0)[2];log_ids={m for row in f['logs'] for m in row['axes']};leaf_dist={m:d for row in f['leaves'] for d,m in enumerate(row['distances'],1)}
        distance={p:0 for p,m in patch.items() if m in log_ids};queue=deque(distance)
        while queue:
            p=queue.popleft()
            if distance[p]>=6:continue
            for delta in NEIGHBORS:
                q=shift(p,delta)
                if q not in distance and q in patch and patch[q] in leaf_ids:distance[q]=distance[p]+1;queue.append(q)
        for p,m in patch.items():
            if m in leaf_dist:assert p in distance and leaf_dist[m]>=distance[p],(f['source'],p,m);checked+=1
    assert checked>1000
    return dict(soil_and_snow='pass',water_and_obstruction='pass',repeat_placement='pass',clipping='pass',
                giant_taper='pass',jungle_variants=len(variants),fancy_supported_crowns='pass',connected_leaves_checked=checked)


def exercise(binary,folder,name,source,gpu,threads=2):
    print(f'{name}: {"Metal" if gpu else "CPU"}, {threads} threads',flush=True)
    path=folder/f'{name}.rbp';path.write_bytes(fixture_wire(source,True));output=folder/f'{name}-{int(gpu)}-{threads}.bin'
    try:
        run=command(['nice','-n','10',binary,path,output,'--threads',threads,'--gpu','on' if gpu else 'off'],timeout=1800)
    except subprocess.CalledProcessError as error:
        raise AssertionError((name,gpu,error.returncode,error.stderr[-4000:])) from error
    data=output.read_bytes();return check(data,source),data,run.stderr


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--profile',action='append',type=Path,required=True)
    parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--fixture-only',action='store_true');args=parser.parse_args()
    folder=ROOT/'build/bend/tree-tests';folder.mkdir(parents=True,exist_ok=True);binary=ROOT/'build/bend/trees';driver=ROOT/'bend/tests/trees.bend'
    if not args.skip_build:
        print('Compiling stock Bend tree fixture at nice +10 (one compiler)',flush=True);generated=binary.with_suffix('.c');build(DEFAULT_BEND,generated,driver)
        command(metal_compiler_command(generated,binary),timeout=600,cwd=ROOT);command(['nice','-n','10',binary,'--threads','2','--gpu-build'],timeout=1800,cwd=ROOT)
    profiles=[(f'profile-{i}',json.loads(p.read_text())) for i,p in enumerate(args.profile)]
    fixture,empty,invalid=fixtures(profiles[0][1]);semantics=semantic_controls(fixture)
    if args.fixture_only:profiles=[]
    profiles += [('fixture',fixture),('empty',empty)];runs=[];outputs={}
    for gpu in (False,True):
        for name,source in profiles:
            result,data,_=exercise(binary,folder,name,source,gpu)
            if not gpu:outputs[name]=data
            else:assert data==outputs[name],(name,'CPU/Metal bytes differ')
            runs.append(dict(profile=name,gpu_required=gpu,**result))
    _,data,_=exercise(binary,folder,'fixture-one-thread',fixture,False,threads=1);assert data==outputs['fixture']
    for i,source in enumerate(invalid):
        path=folder/f'invalid-{i}.rbp';path.write_bytes(fixture_wire(source,True));output=folder/f'invalid-{i}.bin';output.unlink(missing_ok=True)
        run=subprocess.run(['nice','-n','10',str(binary),str(path),str(output),'--threads','2','--gpu','off'],capture_output=True,text=True,timeout=300)
        assert run.returncode and 'invalid surface feature' in run.stderr,(i,run.stderr)
        assert not output.exists(),i
    observed=[]
    if args.prove_metal:
        probe=ROOT/'build/bend/trees-observer'
        if not args.skip_build:compile_metal_observer(DEFAULT_BEND,driver,probe,gpu_archive=binary.with_suffix('.gpu'))
        assert probe.with_suffix('.gpu').read_bytes()==binary.with_suffix('.gpu').read_bytes()
        for name,source in profiles:
            result,data,stderr=exercise(probe,folder,'observer-'+name,source,True);assert data==outputs[name]
            commands=[float(n) for n in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)]
            if result['trees']:assert commands,(name,'no actual Metal command buffers')
            observed.append(dict(profile=name,device_ms=commands,command_buffers=len(commands),sha256=result['sha256']))
    paths=sorted(sources(driver)|{Path(__file__).resolve()})
    report=dict(scope=__doc__.strip(),bend_version=command([DEFAULT_BEND,'version']).stdout.strip(),runs=runs,semantic_controls=semantics,
                malformed_rejected=len(invalid),cpu_gpu_bytes_equal=True,one_cpu_thread='pass',metal_observation=observed,
                profiles={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in args.profile},sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
                native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),gpu_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-trees-correctness.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('runs','semantic_controls','malformed_rejected','metal_observation')},indent=2))


if __name__=='__main__':main()
