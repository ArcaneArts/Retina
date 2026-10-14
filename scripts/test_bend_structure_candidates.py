#!/usr/bin/env python3
"""Independent candidate, exclusion, weighted-biome selection and halo checks."""
import argparse
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import subprocess
import time
from test_bend_structures import Random, MASK, signed, weighted
from test_bend_compression import ROOT, DEFAULT_BEND, command, metal_compiler_command
from test_bend_registry_noise import fixture_wire

A=341873128712;B=132897987541

def candidate(seed,x,z,p):
    spacing=p.get('spacing',32);separation=p.get('separation',8);gx=x//spacing;gz=z//spacing
    r=Random((seed+gx*A+gz*B+p.get('salt',0))&MASK);limit=spacing-separation
    def draw():return (r.integer(limit)+r.integer(limit))//2 if p.get('spread_type')=='triangular' else r.integer(limit)
    return gx*spacing+draw(),gz*spacing+draw()

def frequency(seed,x,z,p):
    probability=p.get('frequency',1.)
    if probability>=1:return True
    if probability<=0:return False
    method=p.get('frequency_reduction_method','default')
    if method=='legacy_type_1':
        r=Random((((x>>4)^signed((z>>4)<<4))^seed)&MASK);r.bits(32)
        return r.integer(min(0xffffffff,int(1/probability)))==0
    if method=='legacy_type_2':r=Random((seed+x*A+z*B+10387320)&MASK)
    elif method=='legacy_type_3':
        r=Random(seed);a=r.long();b=r.long();r=Random(((x*a)^(z*b)^seed)&MASK)
        return ((r.bits(26)<<27)+r.bits(27))/(1<<53)<probability
    else:r=Random((seed+p.get('salt',0)*A+x*B+z)&MASK)
    return r.bits(24)/(1<<24)<probability

def permitted(p,id,seed,x,z,visited=()):
    placement=p['sets'][id]['placement']
    if candidate(seed,x,z,placement)!=(x,z) or not frequency(seed,x,z,placement) or id in visited:return False
    visited=(*visited,id);exclusion=placement.get('exclusion_zone',{});other=next((i for i,s in enumerate(p['sets']) if s['id']==exclusion.get('other_set')),None)
    if other is not None:
        count=exclusion.get('chunk_count',0);spacing=p['sets'][other]['placement']['spacing']
        for gz in range((z-count)//spacing,(z+count)//spacing+1):
            for gx in range((x-count)//spacing,(x+count)//spacing+1):
                a,b=candidate(seed,gx*spacing,gz*spacing,p['sets'][other]['placement'])
                if x-count<=a<=x+count and z-count<=b<=z+count and permitted(p,other,seed,a,b,visited):return False
    return True

def mixed(x):
    x=((x^(x>>30))*0xbf58476d1ce4e5b9)&MASK;x=((x^(x>>27))*0x94d049bb133111eb)&MASK;return x^(x>>31)

def choice(p,id,seed,x,z,context):
    r=Random(mixed((seed^(x*A)^(z*B)^p['sets'][id]['placement'].get('salt',0))&MASK));entries=p['sets'][id]['entries'][:]
    while entries:
        chosen=weighted(entries,r);entries.remove(chosen);definition=chosen['definition'];biomes=p['definitions'][definition]['biomes']
        sample=biomes[0] if biomes and definition%3==context else 0xffffffff
        if sample in biomes:return r.state,definition
    return r.state,0xffffffff

def reach(p,d):
    if d['kind']!='jigsaw':return max(p['templates'][d['template']]['size'][::2])
    distance=d['config'].get('max_distance_from_center',80)
    if isinstance(distance,dict):distance=distance.get('horizontal',80)
    return distance+max((max(p['templates'][part['template']]['size'][::2]) for e in p['pools'].get(d['pool'],{}).get('entries',[]) for part in e['parts']),default=0)

def region(p,seed,x,z,side):
    result=[]
    for i,s in enumerate(p['sets']):
        halo=(max(reach(p,p['definitions'][e['definition']]) for e in s['entries'])+15)//16+1;spacing=s['placement']['spacing']
        for gz in range((z-halo)//spacing,(z+side+halo)//spacing+1):
            for gx in range((x-halo)//spacing,(x+side+halo)//spacing+1):
                a,b=candidate(seed,gx*spacing,gz*spacing,s['placement'])
                if x-halo<=a<=x+side+halo and z-halo<=b<=z+side+halo and permitted(p,i,seed,a,b):result.append((i,a,b))
    return result

def verify(binary,source,name,folder):
    wire=fixture_wire(source,True);input=folder/(name+'.rbp');input.write_bytes(wire);output=folder/(name+'.bin');start=time.monotonic()
    command(['nice','-n','10',binary,input,output,'--threads','2','--gpu','off'],timeout=300,cwd=ROOT)
    elapsed=time.monotonic()-start;data=output.read_bytes();n=struct.unpack_from('>I',data)[0];at=4;p=source['structures'];assert n==len(p['sets'])
    accepted=0;regions=[]
    for context in range(3):
        seed=[123456789,MASK,(1<<63)|0xffffffff][context];coordinate=[0,-15,1874000][context]
        for id,s in enumerate(p['sets']):
            for point in range(64):
                x,z,ok,state,definition=struct.unpack_from('>IIIQI',data,at);at+=24;x=signed(x);z=signed(z)
                a,b=candidate(seed,coordinate+(point%8)*37-148,-coordinate+(point//8)*53-212,s['placement'])
                expected=(a,b,int(permitted(p,id,seed,a,b)),*choice(p,id,seed,a,b,context))
                assert (x,z,ok,state,definition)==expected,(name,context,id,point,(x,z,ok,state,definition),expected)
                accepted+=ok
        count=struct.unpack_from('>I',data,at)[0];at+=4;actual=[]
        for _ in range(count):
            id,x,z=struct.unpack_from('>III',data,at);at+=12;actual.append((id,signed(x),signed(z)))
        assert actual==region(p,seed,coordinate,-coordinate,32),(name,context,actual,region(p,seed,coordinate,-coordinate,32))
        regions.append(len(actual))
    for i in range(256):
        r=Random((((i*2654435769)&0xffffffff)<<32)|((i*2135587861)&0xffffffff));bound=[1,2,3,1073741825,2147483647,2147483648,4294967295,37][i%8]
        if bound & (bound-1)==0:v=(bound*r.bits(31))>>31
        else:
            while True:
                b=r.bits(32 if bound>0x7fffffff else 31);v=b%bound
                if b-v+bound-1<=(0xffffffff if bound>0x7fffffff else 0x7fffffff):break
        state,actual=struct.unpack_from('>QI',data,at);at+=12
        assert (state,actual)==(r.state,v),(name,i,(state,actual),(r.state,v))
    assert at==len(data)
    return dict(name=name,candidates=n*3*64,accepted=accepted,region_starts=regions,input_sha256=hashlib.sha256(wire).hexdigest(),output_sha256=hashlib.sha256(data).hexdigest(),worker_wall_seconds=elapsed)


def synthetic():
    t=dict(id='test:house',ground=1,size=[5,6,5],palettes=[[[1,1,1,1]]],blocks=[0,0,0,0],tags={},entities=[],joints=[])
    d=dict(id='test:house',kind='jigsaw',biomes=[0],pool='test:pool',config=dict(size=2,max_distance_from_center=80,start_height={'absolute':0},project_start_to_heightmap='WORLD_SURFACE_WG'))
    pool=dict(fallback='minecraft:empty',entries=[dict(weight=1,terrain_matching=False,parts=[dict(template=0,ignore_air=False,processors=[])])])
    sets=[]
    for i,(method,p) in enumerate((m,p) for m in ('default','legacy_type_1','legacy_type_2','legacy_type_3') for p in (0.,.2,.5,.75,1.,math.nextafter(.5,0),math.nextafter(.5,1))):
        sets.append(dict(id=f'test:set_{i}',placement=dict(spacing=7,separation=2,salt=19234+i,spread_type='triangular' if i%2 else 'linear',frequency=p,frequency_reduction_method=method),entries=[dict(definition=0,weight=1)]))
    # A real exclusion, an unknown set, and a mutual-cycle fixture.
    sets[2]['placement']['exclusion_zone']=dict(other_set=sets[4]['id'],chunk_count=8)
    sets[3]['placement']['exclusion_zone']=dict(other_set='test:missing',chunk_count=8)
    sets[10]['placement']['frequency']=1.
    sets[17]['placement']=dict(sets[10]['placement'])
    sets[10]['placement']['exclusion_zone']=dict(other_set=sets[17]['id'],chunk_count=0)
    sets[17]['placement']['exclusion_zone']=dict(other_set=sets[10]['id'],chunk_count=0)
    return {'structures':dict(templates=[t],definitions=[d],pools={'test:pool':pool},sets=sets)}


def invalid(binary,folder):
    cases=[]
    def bad(name,fn):
        s=synthetic();fn(s['structures']);cases.append((name,s))
    bad('zero_spacing',lambda p:p['sets'][0]['placement'].update(spacing=0))
    bad('separation',lambda p:p['sets'][0]['placement'].update(separation=7))
    bad('weight',lambda p:p['sets'][0]['entries'][0].update(weight=0))
    bad('definition',lambda p:p['sets'][0]['entries'][0].update(definition=10))
    bad('template',lambda p:p['pools']['test:pool']['entries'][0]['parts'][0].update(template=10))
    bad('dimensions',lambda p:p['templates'][0].update(size=[5,0,5]))
    bad('palettes',lambda p:p['templates'][0].update(palettes=[]))
    bad('overflow_weight',lambda p:p['pools']['test:pool'].update(entries=[dict(weight=2147483647,parts=[]),dict(weight=1,parts=[])]))
    bad('fractional_spacing',lambda p:p['sets'][0]['placement'].update(spacing=7.5))
    bad('negative_depth',lambda p:p['definitions'][0]['config'].update(size=-1))
    bad('invalid_biome',lambda p:p['definitions'][0].update(biomes=[-1]))
    for name,s in cases:
        input=folder/(name+'.rbp');input.write_bytes(fixture_wire(s,True));output=folder/(name+'.bad')
        run=subprocess.run(['nice','-n','10',str(binary),str(input),str(output),'--threads','2','--gpu','off'],capture_output=True,text=True,timeout=60,cwd=ROOT)
        assert run.returncode and 'invalid structure planning schema' in run.stderr and not output.exists(),(name,run.returncode,run.stdout,run.stderr)
    return [name for name,_ in cases]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--build',action='store_true');parser.add_argument('--profiles',type=Path,default=ROOT/'build/bend/profiles');args=parser.parse_args();folder=ROOT/'build/bend/structure-candidates';folder.mkdir(parents=True,exist_ok=True);binary=folder/'fixture';generated=folder/'fixture.c'
    if args.build:
        command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/structure-candidates.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
        command([arg for arg in metal_compiler_command(generated,binary) if arg!='-DBEND_METAL=1'],timeout=600,cwd=ROOT)
    results=[]
    for path in sorted(args.profiles.glob('profile-*.json')):
        row=verify(binary,json.loads(path.read_text()),path.stem,folder);results.append(row);print(json.dumps(row),flush=True)
    assert len(results)>=3, 'supply all three original vanilla/Terralith/combined profiles'
    row=verify(binary,synthetic(),'synthetic',folder);results.append(row);print(json.dumps(row),flush=True)
    failures=invalid(binary,folder)
    report=dict(scope='CPU Bend structure candidates/selection only',complete=True,results=results,rejected=failures)
    report.update(bend_version=command([DEFAULT_BEND,'version']).stdout.strip(),threads=2,gpu='off',
                  executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                  sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in
                    [*sorted((ROOT/'bend').glob('structure_*.bend')),ROOT/'bend/tests/structure-candidates.bend',Path(__file__)]})
    (ROOT/'docs/benchmarks/bend-structure-candidates.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()
