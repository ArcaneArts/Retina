#!/usr/bin/env python3
"""Independently check registry-derived material DAGs on Bend CPU and GPU.

Supplied contexts are component inputs. Bulk voxel layer/context generation and
final terrain integration remain separate work, not claimed by these checks.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import random
import re
import signal
import struct
import time

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_density import Oracle, ins, perlin
from test_bend_noise import f32, mix_hash, MASK

LOW,HIGH=0x12345678,0x80000001


def request(points):
    return struct.pack('>I',len(points))+b''.join(struct.pack('>6I8f',*(v&MASK for v in q[:6]),*q[6]) for q in points)


class Reference(Oracle):
    def __init__(self,source):
        self.source=source
        super().__init__(json.loads(json.dumps(source['registry_program']),parse_float=lambda x:f32(float(x))))

    def material(self,program,point,low,high,ctx):
        above,depth,slope,offset,below,secondary,water,preliminary=ctx
        bands=self.source['terrain_features']['bands'];sea=self.source['sea_level']
        layered=self.model['material_layers'];values=[];x,y,z=point
        for n in self.model['programs'][program+3]['nodes']:
            op,a,b,c,p=(n[k] for k in ('op','a','b','c','p'))
            av=values[a] if a<len(values) else 0.;bv=values[b] if b<len(values) else 0.
            if op<40:
                # Reuse the independently verified numeric equations, replacing
                # previous material expressions with their already computed scalars.
                prefix=[ins(0,p=(v,0,0,0)) for v in values]+[n]
                r=super().evaluate(dict(nodes=prefix,roots=[len(values)]),point,low,high)[0]
            elif op==40:r=bv if av else 0.
            elif op==41:r=av if av else bv
            elif op==42:r=bands[(y+math.trunc(offset))%len(bands)]+1 if bands else 0.
            elif op==43:r=float(av==0)
            elif op==44:r=float(p[0]<=av<=p[1])
            elif op==45:
                if layered:r=float((above if a==1 else below)<=1+p[0]+(depth if b==1 else 0)+math.trunc((secondary+1)*.5*p[1]))
                else:r=float(a==1 and above<=1+p[0]+(depth if b==1 else 0)+p[1]*.5)
            elif op==46:
                if layered:r=float(water==-(1<<31) or y+(above if a==1 else 0)>=water+p[0]+depth*p[1])
                else:r=float(y+above>=sea or y+(above if a==1 else 0)>=sea+p[0]+depth*p[1])
            elif op==47:r=float(y+(above if a==1 else 0)>=p[0]+depth*p[1])
            elif op==48:
                h=mix_hash(((x*0x9e3779b9)^(z*0x85ebca6b)^((low+(program+3)*7919)&MASK)^mix_hash(high))&MASK)
                r=float((h&65535)/65535<max(0,min(1,(p[1]-y)/max(p[1]-p[0],1))))
            elif op==49:r=float(slope>3)
            elif op==50:r=float(depth<=0)
            elif op==51:
                temp=p[0]
                if y>sea+17:temp-=(perlin((x*.125,0,z*.125),1234)*8+y-sea-17)*.00125
                r=float(temp<.15)
            elif op==52:
                r=math.ceil((perlin((x*f32(1/p[0]),0,z*f32(1/p[0])),2345)*.625+p[1])*p[2])
            elif op==53:
                lo,hi=(y+above,y+above) if c&2 else p[:2];span=max(1,hi-lo+1)
                if c&1:lo=max(lo,y+above);hi=min(hi,sea-1)
                eligible=max(0,min(hi,y+p[3])-max(lo,y-p[3])+1)
                if av>0 and eligible>0:
                    coverage=-math.expm1(-av*max(1,math.pi*p[2]**2)*min(1,eligible/span)/256)
                    scale=f32(1/max(2,p[2]*2));field=perlin((x*scale,0,z*scale),low^mix_hash(high)^b)*.625
                    t=max(0,min(1,field+.5));r=float(t*t*(3-2*t)<coverage)
                else:r=0.
            elif op==54:r=float(not layered or y>=preliminary)
            else:raise AssertionError(op)
            values.append(r)
        roots=self.model['programs'][program+3]['roots']
        return [values[i] for i in roots]+[values[0]]*(6-len(roots))

    def verify(self,data,points):
        assert len(data)==len(points)*24
        maximum=0.
        for q,actual in zip(points,struct.iter_unpack('>6f',data)):
            pid,x,y,z,low,high,context=q
            expected=self.material(pid,(x,y,z),low,high,context)
            for channel,(a,b) in enumerate(zip(actual,expected)):
                error=abs(a-b)/max(1,abs(b));maximum=max(maximum,error)
                assert math.isfinite(a) and error<.0002,(q,channel,a,b,error)
        return maximum


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True)
    programs=[]
    def add(nodes,roots=None):programs.append(dict(nodes=nodes,roots=roots or [len(nodes)-1]))
    add([ins(0,p=(0,0,0,0)),ins(0,p=(7,0,0,0)),ins(40,0,1),ins(41,2,1),ins(43,0),ins(44,1,p=(7,7,0,0))],[2,3,4,5])
    add([ins(42)])
    for a in (0,1):
        for b in (0,1):add([ins(45,a,b,p=(2,5,0,0))])
    for a in (0,1):
        add([ins(46,a,p=(-2,1,0,0))]);add([ins(47,a,p=(64,.5,0,0))])
    for op,p in [(48,(-64,128,0,0)),(49,(0,0,0,0)),(50,(0,0,0,0)),(51,(.18,0,0,0)),(52,(64,.25,8,0)),(54,(0,0,0,0))]:add([ins(op,p=p)])
    for mode in range(4):
        for count in (0.,1.,30.,10000.):add([ins(0,p=(count,0,0,0)),ins(53,0,0x9abcdef0,mode,(-64,100,6,3))])
    add([ins(0,p=(0,0,0,0)),ins(1,0,0,0,(0,1,1,0)),ins(44,1,p=(-.25,.25,0,0)),ins(0,p=(4,0,0,0)),ins(40,2,3)], [1,2,4])
    ramp=dict(nodes=[ins(0,p=(64,0,0,0)),ins(29,1),ins(5,0,1)],roots=[2])
    base=dict(geology_min_y=-64,geology_height=384,sea_level=63,biomes=[{'flags':0} for _ in programs],
        materials=[{} for _ in range(17)],climate_targets=[],terrain_features=dict(bands=[2,5,7,11,16]),
        registry_program=dict(material_layers=True,surface=[-64,8,0],terrain_cell=[4,8],
            noises=[dict(frequency=.0035,amplitude=.8,salt=-12345,coefficients=[1.,0.,.5])],points=[],programs=[ramp]*3+programs))
    result=[]
    for name,layered,bands,packed in [('layered',True,base['terrain_features']['bands'],False),('packed',True,base['terrain_features']['bands'],True),
                                    ('legacy',False,base['terrain_features']['bands'],True),('no_bands',True,[],True)]:
        source=copy.deepcopy(base);source['registry_program']['material_layers']=layered;source['terrain_features']['bands']=bands
        path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,packed));result.append((name,source,path))
    invalid=[]
    def bad(name,change):
        source=copy.deepcopy(base);change(source);path=folder/('invalid-'+name+'.rbp');path.write_bytes(fixture_wire(source,True));invalid.append((name,path))
    for field in ('materials','biomes','sea_level','terrain_features'):
        bad('missing_'+field,lambda s,f=field:s.pop(f))
    for value in (None,1,False,[True],[-1],[17]):bad('bands_'+str(value),lambda s,v=value:s['terrain_features'].__setitem__('bands',v))
    for value in (None,1,[],{}):bad('layered_'+str(value),lambda s,v=value:s['registry_program'].__setitem__('material_layers',v))
    bad('missing_program',lambda s:s['registry_program']['programs'].pop())
    for label,field,value in [('outside_root','roots',[999]),('float_root','roots',[0.0]),('missing_roots','roots',None),
                              ('empty_nodes','nodes',[]),('null_nodes','nodes',None)]:
        bad(label,lambda s,f=field,v=value:s['registry_program']['programs'][3].__setitem__(f,v))
    for label,node in [('op32',ins(32)),('op55',ins(55)),('forward40',ins(40)),('forward43',ins(43)),
            ('forward44',ins(44)),('forward53',ins(53)),('flag45a',ins(45,2)),('flag45b',ins(45,0,2)),
            ('flag46',ins(46,2)),('flag47',ins(47,2)),('zero52',ins(52)),('negative52',ins(52,p=(-1,0,0,0))),
            ('mode53',ins(53,0,0,4,(0,1,1,1))),('radius53',ins(53,0,0,0,(0,1,-1,1))),
            ('vertical53',ins(53,0,0,0,(0,1,1,-1)))]:
        bad(label,lambda s,n=node:s['registry_program']['programs'].__setitem__(3,dict(nodes=[ins(0),n],roots=[1]) if n['op']==53 and label.startswith(('mode','radius','vertical')) else dict(nodes=[n],roots=[0])))
    return result,invalid


def queries(source,actual=False):
    rng=random.Random(20261008);qs=[]
    count=len(source['biomes'])
    contexts=[(1.,3.,0.,-7.,5.,-.9,-2147483648.,60.),(4.,0.,4.,3.,1.,.5,63.,64.),
              (9.,6.,3.,-3.75,4.,1.,70.,72.),(2.,-1.,3.01,8.,9.,-1.,63.,58.)]
    coordinates=[(-1,-64,-1),(0,63,0),(29999999,80,-30000001),(-(1<<31),128,(1<<31)-1),
                 (1,-(1<<31),-1),(-1,(1<<31)-1,1)]
    for pid in range(count):
        for j in range(4 if actual else 12):
            x,y,z=coordinates[j] if j<len(coordinates) else (rng.randrange(-10000,10000),rng.randrange(-64,321),rng.randrange(-10000,10000))
            qs.append((pid,x,y,z,LOW if j%3 else MASK,HIGH if j%2 else 0,contexts[j%4]))
    return qs


def exercise(binary,profiles,invalid,gpu):
    worker=Worker(binary,gpu);dispatches=0;maximum=0.;records=[];hashes=[];checked=0
    def call(op,data=b'',status=0):
        nonlocal dispatches
        if op in (12,13,14,16,20) and status==0:dispatches+=1
        return worker.call(op,data,status=status)
    def error(op,data,code):assert call(op,data,1)==struct.pack('>I',code),(op,code)
    try:
        error(19,b'',716);error(20,request([]),716)
        for name,source,path in profiles:
            signal.alarm(300);print(f'{"GPU" if gpu else "CPU"}: {name} material programs',flush=True)
            call(5,path_request(path));call(11);error(20,request([]),725)
            begin=time.perf_counter();ack=call(19);prepare=(time.perf_counter()-begin)*1000
            assert ack==struct.pack('>4I',len(source['biomes']),len(source['terrain_features']['bands']),source['sea_level']&MASK,int(source['registry_program']['material_layers']))
            points=queries(source,name not in ('layered','packed','legacy','no_bands'));ref=Reference(source)
            begin=time.perf_counter();data=call(20,request(points));elapsed=(time.perf_counter()-begin)*1000
            maximum=max(maximum,ref.verify(data,points));checked+=len(points);hashes.append(hashlib.sha256(data).hexdigest())
            assert call(20,request(points))==data
            assert call(20,request(points[::-1]))==b''.join(data[i:i+24] for i in range(len(data)-24,-1,-24))
            assert call(20,request([]))==b''
            for malformed in (b'',b'\0',struct.pack('>I',4097),request([])+b'\0',request(points[:1])[:-1],
                    request([(len(source['biomes']),*points[0][1:])]),request([(MASK,*points[0][1:])]),
                    request([(*points[0][:6],(float('nan'),*points[0][6][1:]))]),
                    request([(*points[0][:6],(*points[0][6][:-1],float('inf')))])):
                error(20,malformed,603)
            error(19,b'\0',603);error(5,path_request(path.with_name('does-not-exist.rbp')),702)
            assert call(20,request(points[:1]))==data[:24]
            # Surface/lattice state and material state remain independent.
            call(15);call(9);tile=struct.pack('>6I',-8&MASK,8,4,4,LOW,HIGH)
            descriptor=call(18,tile);call(13,descriptor);call(16,tile)
            surface_query=struct.pack('>5I',1,-8&MASK,8,LOW,HIGH);surface=call(17,surface_query)
            call(19);assert call(17,surface_query)==surface
            call(12,struct.pack('>7I',1,0,0,0,0,LOW,HIGH));call(14,struct.pack('>7I',1,1,0,0,0,LOW,HIGH))
            assert call(20,request(points[:1]))==data[:24]
            call(15);assert call(20,request(points[:1]))==data[:24]
            if name=='layered':
                large=[points[0]]*4096;raw=call(20,request(large));assert raw==data[:24]*4096;checked+=4096
            call(11);error(20,request([]),725);call(19);assert call(20,request(points[:1]))==data[:24]
            records.append(dict(name=name,programs=len(source['biomes']),queries=len(points),prepare_host_ms=prepare,first_batch_host_ms=elapsed,
                repeat_reorder_empty='pass',numeric_reprepare_invalidates='pass',surface_lattice_independence='pass'))
        for name,path in invalid:
            signal.alarm(300);call(5,path_request(path));call(11);error(19,b'',724);error(20,request([]),725)
        call(4);assert worker.process.wait(timeout=10)==0
        stderr=worker.process.stderr.read().decode();times=[float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)]
        return dict(gpu_required=gpu,profiles=records,queries_checked=checked,max_normalized_error=maximum,
            requests_in_one_process=worker.id,invalid_profiles_rejected=len(invalid),expected_gpu_dispatches=dispatches,
            sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest(),metal_commands_ms=times)
    finally:signal.alarm(0);worker.close()


def timeout(*_):raise TimeoutError('Bend material test timeout')


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true')
    parser.add_argument('--profile',action='append',nargs=2,metavar=('JSON','RBP'));args=parser.parse_args()
    profiles,invalid=fixtures(ROOT/'build/bend/material-profiles')
    for source,wire in args.profile or []:profiles.append((Path(source).parent.name,json.loads(Path(source).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine';version='existing executable' if args.skip_build else build(args.bend,binary,ROOT/'bend/engine.bend')
    report=dict(scope='Resident material-program component; bulk context generation and complete regions pending',bend_version=version,
        runs=[exercise(binary,profiles,invalid,False),exercise(binary,profiles,invalid,True)])
    if args.prove_metal:
        diagnostic=ROOT/'build/bend/material-metal-probe';compile_metal_observer(args.bend,ROOT/'bend/engine.bend',diagnostic)
        result=exercise(diagnostic,profiles,invalid,True)
        assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches'],(len(result['metal_commands_ms']),result['expected_gpu_dispatches'])
        assert result['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=result
    path=ROOT/'build/bend/material-tests.json';path.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k!='runs' and k!='actual_metal_proof'}));print(f'Validated {report["runs"][0]["queries_checked"]} queries per backend; {path}')


if __name__=='__main__':signal.signal(signal.SIGALRM,timeout);main()
