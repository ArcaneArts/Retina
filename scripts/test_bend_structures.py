#!/usr/bin/env python3
"""Registry-derived pure Bend jigsaw layouts, compared with independent Python.

No native/Rust generator is called. This fixture validates planning, not block
placement or complete world integration. Original profile payloads are retained.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import time

from test_bend_compression import ROOT, DEFAULT_BEND, command, metal_compiler_command
from test_bend_registry_noise import fixture_wire
from test_bend_registry import Document

MASK = (1 << 64) - 1

def signed(n): return (n + (1 << 31)) % (1 << 32) - (1 << 31)

def trunc(n,d): return (abs(n)//d)*(-1 if n<0 else 1)

class Random:
    def __init__(self,seed): self.state=(seed ^ 0x5deece66d)&((1<<48)-1)
    def bits(self,n):
        self.state=(self.state*0x5deece66d+11)&((1<<48)-1)
        return self.state>>(48-n)
    def integer(self,n):
        assert 0<n<=0x7fffffff,n
        if n&(n-1)==0:return (n*self.bits(31))>>31
        while True:
            b=self.bits(31);v=b%n
            if b-v+n-1<=0x7fffffff:return v
    def long(self):return ((signed(self.bits(32))<<32)+signed(self.bits(32)))&MASK
    def shuffle(self,values):
        for i in range(len(values)-1,0,-1):
            j=self.integer(i+1);values[i],values[j]=values[j],values[i]
        return values

def add(a,b):return tuple(x+y for x,y in zip(a,b))
def sub(a,b):return tuple(x-y for x,y in zip(a,b))
def rotate(p,r):
    x,y,z=p
    return [(x,y,z),(-z,y,x),(-x,y,-z),(z,y,-x)][r]
def direction(d,r):return rotate([(0,-1,0),(0,1,0),(0,0,-1),(0,0,1),(-1,0,0),(1,0,0)][d],r)
def inside(b,p):return all(b[i]<=p[i]<=b[i+3] for i in range(3))
def contains(a,b):return inside(a,b[:3]) and inside(a,b[3:])
def intersects(a,b):return all(a[i]<=b[i+3] and a[i+3]>=b[i] for i in range(3))

def weighted(xs,r):
    total=sum(x.get('weight',1) for x in xs)
    if not total:return None
    p=r.integer(total)
    for x in xs:
        p-=x.get('weight',1)
        if p<0:return x
    raise AssertionError()

def anchor(v):
    if isinstance(v,int):return v
    if 'absolute' in v:return v['absolute']
    if 'above_bottom' in v:return -64+v['above_bottom']
    return 319-v.get('below_top',0)

def height(v,r):
    if v is None:return 0
    if isinstance(v,int) or 'absolute' in v:return anchor(v)
    if 'value' in v:return anchor(v['value'])
    low=anchor(v['min_inclusive']) if 'min_inclusive' in v else 0
    high=anchor(v['max_inclusive']) if 'max_inclusive' in v else low
    return low+r.integer(max(1,high-low+1))

def aliases(v,r,out):
    if isinstance(v,list):
        for x in v:aliases(x,r,out)
    elif isinstance(v,dict):
        ty=v.get('type','').removeprefix('minecraft:')
        if ty=='random_group':
            group=weighted(v.get('groups',[]),r)
            if group:aliases(group['data'],r,out)
        elif 'alias' in v:
            target=weighted(v.get('targets',[]),r) if ty=='random' else {'data':v.get('target')}
            if target and isinstance(target['data'],str):out[v['alias']]=target['data']

class Planner:
    def __init__(self,profile,offsets):self.p=profile;self.offsets=offsets
    def template(self,e):return self.p['templates'][e['parts'][0]['template']]
    def bounds(self,e,pos,rotation):
        points=[add(pos,rotate(q,rotation)) for part in e['parts'] for q in [(0,0,0),tuple(s-1 for s in self.p['templates'][part['template']]['size'])]]
        return tuple(min(p[i] for p in points) for i in range(3))+tuple(max(p[i] for p in points) for i in range(3))
    def pool(self,name):return self.p['pools'].get(name,{'fallback':'','entries':[]})
    def expansion(self,e,rotation,aliases):
        local=self.bounds(e,(0,0,0),rotation)
        if local[4]-local[1]+1>16:return 0
        answer=0
        for j in self.template(e)['joints']:
            if inside(local,add(rotate(j['pos'],rotation),direction(j['front'],rotation))):
                pool=self.pool(aliases.get(j['pool'],j['pool']))
                for e in pool['entries']+self.pool(pool['fallback'])['entries']:
                    if e['parts']:
                        b=self.bounds(e,(0,0,0),0);answer=max(answer,b[4]-b[1]+1)
        return answer
    def run(self,definition,context):
        seed=[123456789,MASK,(1<<63)|0xffffffff][context]
        coordinate=[0,-15,1874000][context];r=Random((seed+definition)&MASK)
        d=self.p['definitions'][definition];config=d['config']
        if d['kind']=='jigsaw':root=weighted(self.pool(d['pool'])['entries'],r)
        else:root={'parts':[{'template':d['template']}],'terrain_matching':False,'weight':1}
        if not root or not root['parts']:return r.state,[]
        rotation=r.integer(4);t=self.template(root)
        pos=(coordinate*16,height(config.get('start_height'),r),-coordinate*16)
        projected=d['kind']!='jigsaw' or 'project_start_to_heightmap' in config
        def terrain(x,z):
            x=max(0,min(511,x-(coordinate*16-256)));z=max(0,min(511,z-(-coordinate*16-256)))
            return 64 if context==0 else 40+x//16+z//24 if context==1 else 50+(x//8+z//8)%11
        if projected:
            b=self.bounds(root,pos,rotation)
            pos=add(pos,(0,terrain(trunc(b[0]+b[3],2),trunc(b[2]+b[5],2))-t['ground'],0))
        if 'start_jigsaw_name' in config:
            j=next((j for j in t['joints'] if j['name']==config['start_jigsaw_name']),None)
            if j is None:return r.state,[]
            pos=sub(pos,rotate(j['pos'],rotation))
        b=self.bounds(root,pos,rotation)
        if b[1]<-64 or b[4]>=320:return r.state,[]
        pieces=[dict(element=root,pos=pos,rotation=rotation,box=b,palette=r.integer(len(t['palettes'])),context=0,depth=0,priority=0)]
        if d['kind']=='jigsaw':
            center=(trunc(b[0]+b[3],2),trunc(b[2]+b[5],2));distance=config.get('max_distance_from_center',80)
            if isinstance(distance,dict):distance=distance.get('horizontal',80)
            limit=(center[0]-distance,-64,center[1]-distance,center[0]+distance,319,center[1]+distance)
            alias={};aliases(config.get('pool_aliases'),r,alias)
            queue=[0];max_depth=config.get('size',0)
            while queue:
                queue.sort(key=lambda i:-pieces[i]['priority']);index=queue.pop(0);source=pieces[index]
                joints=sorted(r.shuffle(list(self.template(source['element'])['joints'])),key=lambda j:-j['selection_priority'])
                for j in joints:
                    jp=add(source['pos'],rotate(j['pos'],source['rotation']));front=direction(j['front'],source['rotation']);target=add(jp,front)
                    pool=self.pool(alias.get(j['pool'],j['pool']));fallback=self.pool(pool['fallback'])
                    elements=r.shuffle([e for e in pool['entries'] for _ in range(e['weight'])]) if source['depth']<max_depth else []
                    elements+=r.shuffle([e for e in fallback['entries'] for _ in range(e['weight'])])
                    success=False
                    for e in elements:
                        if not e['parts']:break
                        for rotation in r.shuffle([0,1,2,3]):
                            t=self.template(e);expansion=self.expansion(e,rotation,alias) if config.get('use_expansion_hack',False) else 0
                            targets=sorted(r.shuffle(list(t['joints'])),key=lambda j:-j['selection_priority'])
                            for other in targets:
                                if direction(other['front'],rotation)!=tuple(-v for v in front) or j['target']!=other['name'] or (j['joint']=='aligned' and direction(j['top'],source['rotation'])!=direction(other['top'],rotation)):continue
                                pos=sub(target,rotate(other['pos'],rotation))
                                if projected and (source['element']['terrain_matching'] or e['terrain_matching']):pos=(pos[0],terrain(jp[0],jp[2])-other['pos'][1],pos[2])
                                b=list(self.bounds(e,pos,rotation))
                                if expansion>0:b[4]=max(b[4],b[1]+expansion+1)
                                if not contains(limit,b):continue
                                context_id=index+1 if inside(source['box'],target) else source['context']
                                if context_id and not contains(pieces[context_id-1]['box'],b):continue
                                if any(p['context']==context_id and intersects(p['box'],b) for p in pieces):continue
                                pieces.append(dict(element=e,pos=pos,rotation=rotation,box=b,palette=r.integer(len(t['palettes'])),context=context_id,depth=source['depth']+1,priority=j['placement_priority']))
                                if source['depth']<max_depth:queue.append(len(pieces)-1)
                                success=True;break
                            if success:break
                        if success:break
            if projected:
                for p in pieces:
                    if p['element']['terrain_matching']:
                        b=list(p['box']);ys=[terrain(x,z)-1 for z in range(b[2],b[5]+1) for x in range(b[0],b[3]+1)]
                        size=b[4]-b[1];b[1]=min(ys);b[4]=max(ys)+size;p['box']=b
        rows=[]
        for p in pieces:
            rows.append([self.offsets.get(id(p['element']),0),*p['pos'],p['rotation'],*p['box'],p['palette'],p['context'],p['depth'],p['priority']])
        return r.state,rows


def fields(doc,at):
    assert doc.word(at)==7
    cursor=at+3;result={}
    for _ in range(doc.word(at+2)):
        key=doc.strings[doc.word(cursor)];cursor+=1;result[key]=cursor;cursor+=doc.word(cursor+1)
    return result

def array_offsets(doc,at):
    assert doc.word(at)==6
    result=[];cursor=at+3
    for _ in range(doc.word(at+2)):
        result.append(cursor);cursor+=doc.word(cursor+1)
    return result

def element_offsets(doc,source):
    at=fields(doc,fields(doc,doc.root)['structures'])['pools'];pools=fields(doc,at);result={}
    for name,pool in source['pools'].items():
        for element,offset in zip(pool['entries'],array_offsets(doc,fields(doc,pools[name])['entries'])):result[id(element)]=offset
    return result

def verify(binary,profile,folder):
    source=json.loads(profile.read_text());wire=fixture_wire(source,True);path=folder/(profile.stem+'.rbp');path.write_bytes(wire)
    output=folder/(profile.stem+'.plans');start=time.monotonic()
    command(['nice','-n','10',binary,path,output,'--threads','2','--gpu','off'],timeout=600,cwd=ROOT)
    elapsed=time.monotonic()-start;doc=Document(wire);p=source['structures'];oracle=Planner(p,element_offsets(doc,p))
    data=output.read_bytes();count=struct.unpack_from('>I',data)[0];cursor=4;total=0;kinds={}
    assert count==3*len(p['definitions'])
    for _ in range(count):
        context,definition,state,n=struct.unpack_from('>IIQI',data,cursor);cursor+=20
        expected_state,expected=oracle.run(definition,context)
        actual=[]
        for i in range(n):
            row=list(struct.unpack_from('>15I',data,cursor));cursor+=60
            actual.append([row[0],*[signed(v) for v in row[1:11]],*row[11:]])
        assert (state,actual)==(expected_state,expected),(profile.name,context,p['definitions'][definition]['id'],hex(state),hex(expected_state),len(actual),len(expected),next(((a,b) for a,b in zip(actual,expected) if a!=b),None))
        total+=n;kinds[p['definitions'][definition]['kind']]=kinds.get(p['definitions'][definition]['kind'],0)+n
    assert cursor==len(data)
    return dict(profile=profile.name,profile_sha256=hashlib.sha256(profile.read_bytes()).hexdigest(),plans=count,pieces=total,kinds=kinds,worker_wall_seconds=elapsed,output_sha256=hashlib.sha256(data).hexdigest())


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--build',action='store_true');parser.add_argument('--profiles',type=Path,default=ROOT/'build/bend/profiles');args=parser.parse_args()
    folder=ROOT/'build/bend/structure-plans';folder.mkdir(parents=True,exist_ok=True);binary=folder/'fixture';generated=folder/'fixture.c'
    if args.build:
        command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/structure-plan.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
        command([arg for arg in metal_compiler_command(generated,binary) if arg != '-DBEND_METAL=1'],timeout=600,cwd=ROOT)
    results=[]
    for profile in sorted(args.profiles.glob('profile-*.json')):
        result=verify(binary,profile,folder);results.append(result);print(json.dumps(result),flush=True)
    assert len(results)>=3
    report=dict(scope='CPU Bend jigsaw/standalone layout only; no block replay or playable integration',profiles=results,complete=True)
    report.update(bend_version=command([DEFAULT_BEND,'version']).stdout.strip(),threads=2,gpu='off',
                  executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                  sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in
                    [*sorted((ROOT/'bend').glob('structure_*.bend')),ROOT/'bend/tests/structure-plan.bend',Path(__file__)]})
    (ROOT/'docs/benchmarks/bend-structure-plans.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()
