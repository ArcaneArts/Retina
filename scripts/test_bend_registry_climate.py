#!/usr/bin/env python3
"""Check resident Bend climate projection and repeated CPU/GPU interval queries.

No Java/Rust generation or target projection runs on the backend path. Python
only builds test inputs and independently searches original registered intervals.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import random
import re
import signal
import struct

from test_bend_compression import ROOT,DEFAULT_BEND,build
from test_bend_engine import Worker,configuration,compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_climate import target,queries,verify


def projected(source):
    return [target(t['min']+t['weirdness'][:1]+t.get('depth',[0.,0.])[:1],
                   t['max']+t['weirdness'][1:]+t.get('depth',[0.,0.])[1:],t['offset'],t['biome'],
                   source['biomes'][t['biome']]['flags'],i) for i,t in enumerate(source.get('climate_targets',[]))]


def request(points):
    return struct.pack('>I',len(points))+b''.join(struct.pack('>6fI',*p,mode) for p,mode in points)


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True)
    rng=random.Random(20261008)
    base={'unrelated':'🌍 min is a schema key but this is not',
          'biomes':[{'flags':v} for v in (0,4,16,64,68,80,84)],'climate_targets':[]}
    for i in range(129):
        lo=[rng.randrange(-8,4)/4 for _ in range(4)]
        hi=[x+rng.randrange(0,7)/4 for x in lo]
        t={'offset':rng.randrange(-2,3)/16,'weirdness':[-1,1],
           'max':hi,'biome':i%7,'min':lo}
        if i%2: t['depth']=[rng.randrange(-8,0)/4,rng.randrange(0,8)/4]
        base['climate_targets'].append(t)
    # A lexical integer vector exercises packed-i32 parameter projection;
    # large finite integer bounds exercise the packed-i64 path.
    base['climate_targets'] += [{'min':[-1]*4,'max':[1]*4,'weirdness':[-1,1],
        'depth':[0,0],'offset':0,'biome':0}, {'min':[1<<40]*4,'max':[1<<41]*4,
        'weirdness':[-1,1],'offset':0,'biome':1}]
    result=[]
    for name,source,packed in [('mixed',base,False),('packed',base,True),
        ('empty',{'biomes':[{'flags':0}],'climate_targets':[]},True),
        ('missing_targets',{'biomes':[{'flags':0}]},False)]:
        path=folder/f'{name}.rbp';path.write_bytes(fixture_wire(source,packed));result.append((name,source,path))
    bad=[None,{}, {'biomes':[]}, {'biomes':[{}]}, {'biomes':[{'flags':True}]},
        {'biomes':[{'flags':-1}]}, {'biomes':[{'flags':1.0}]}, {'biomes':[{'flags':1<<32}]},
        dict(base,climate_targets=None),dict(base,climate_targets=[None]),dict(base,climate_targets={})]
    for field,value in [('biome',True),('biome',-1),('biome',1.0),('biome',7),('biome',1<<32),
        ('min',[-1]*3),('min',[-1]*5),('min',[-1]*3+[True]),('max','bad'),
        ('weirdness',[1.,-1.]),('depth',[1.,-1.]),('depth',None),('offset',None),
        ('offset',1.e200),('min',[1.e200]*4),('max',[-3.]*4)]:
        source=copy.deepcopy(base);source['climate_targets'][0][field]=value;bad.append(source)
    for field in ('min','max','weirdness','offset','biome'):
        source=copy.deepcopy(base);del source['climate_targets'][0][field];bad.append(source)
    invalid=[]
    for i,source in enumerate(bad):
        path=folder/f'invalid-{i}.rbp';path.write_bytes(fixture_wire(source,bool(i%2)));invalid.append(path)
    return result,invalid


def exercise(binary,profiles,invalid,gpu):
    worker=Worker(binary,gpu);rng=random.Random(20261009);rows=[];hashes=[]
    folder=ROOT/'build/bend/climate-profiles'
    broken=folder/'truncated.rbp';broken.write_bytes(b'RBP1')
    try:
        signal.alarm(240)
        assert worker.call(9,status=1)==struct.pack('>I',708)
        assert worker.call(10,request([]),status=1)==struct.pack('>I',714)
        assert worker.call(9,b'\0',status=1)==struct.pack('>I',603)
        worker.call(1,configuration())
        noise_request=struct.pack('>5I',55,123,8,8,1);noise=worker.call(2,noise_request)
        last=None
        for name,source,path in profiles:
            signal.alarm(240)
            worker.call(5,path_request(path))
            if name=='mixed':
                staged=folder/'deleted-after-ack.rbp';staged.write_bytes(path.read_bytes())
                worker.call(5,path_request(staged));staged.unlink()
            assert worker.call(10,request([]),status=1)==struct.pack('>I',714),'profile reload retained stale climate'
            expected=struct.pack('>2I',len(source.get('climate_targets',[])),len(source['biomes']))
            assert worker.call(9)==expected
            targets=projected(source);points=queries(rng,256)
            canonical=None
            for group in (points,points[:13],[],points):
                data=worker.call(10,request(group))
                if canonical is None:
                    verify(data,targets,group);canonical=data
                else: assert data==canonical[:len(group)*20]
                hashes.append(hashlib.sha256(data).hexdigest())
            assert worker.call(9,b'\0',status=1)==struct.pack('>I',603)
            old=worker.call(10,request(points));assert old==canonical
            hashes.append(hashlib.sha256(old).hexdigest())
            # A structurally rejected upload must retain both tape and index.
            absent=path.with_name('nonexistent.rbp')
            assert worker.call(5,path_request(absent),status=1)==struct.pack('>I',702)
            assert worker.call(5,path_request(broken),status=1)==struct.pack('>I',703)
            assert worker.call(10,request(points))==old
            for payload in (b'',b'\0'*3,struct.pack('>I',65537),request(points)[:-1],request([])+b'\0',
                request([([0.]*6,32)]),request([([float('nan')]+[0.]*5,0)]),
                request([([float('inf')]+[0.]*5,0)])):
                assert worker.call(10,payload,status=1)==struct.pack('>I',603)
            assert worker.call(10,request(points))==old
            worker.call(1,configuration())
            assert worker.call(2,noise_request)==noise,'climate preparation changed noise state'
            rows.append({'name':name,'targets':len(targets),'biomes':len(source['biomes']),
                'independent_reference_queries':len(points),'validated_query_rows':5*len(points)+13,
                'repeated_query_and_failed_upload':'pass','noise_and_climate_state_independent':'pass'})
            last=(source,path,points,old)
            print(f'{"GPU" if gpu else "CPU"}: {name}; {len(targets)} resident targets; repeated queries pass',flush=True)
        # Small target set, maximum bounded query count, independent reference.
        source,path=profiles[0][1:];worker.call(5,path_request(path));worker.call(9)
        small=queries(rng,64);large=(small*1024)
        canonical=worker.call(10,request(small));verify(canonical,projected(source),small)
        data=worker.call(10,request(large));assert data==canonical*1024
        hashes.append(hashlib.sha256(data).hexdigest())
        for path in invalid:
            worker.call(5,path_request(path))
            assert worker.call(9,status=1)==struct.pack('>I',713),path
            assert worker.call(10,request([]),status=1)==struct.pack('>I',714),'invalid profile silently reused previous index'
        # Explicit reprepare after reload restores normal operation in this process.
        source,path,points,old=last
        worker.call(5,path_request(path));worker.call(9)
        assert worker.call(10,request(points))==old
        worker.call(4);assert worker.process.wait(timeout=10)==0
        diagnostics=worker.process.stderr.read().decode()
        return {'mode':'gpu_required' if gpu else 'cpu','profiles':rows,'maximum_batch_queries':len(large),
            'invalid_typed_profiles':len(invalid),'requests_in_one_process':worker.id,
            'aggregate_sha256':hashlib.sha256(b''.join(bytes.fromhex(h) for h in hashes)).hexdigest(),
            'observed_metal_command_ms':[float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostics)]}
    finally:
        signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--profile',nargs=2,action='append',metavar=('JSON','WIRE'),default=[])
    parser.add_argument('--prove-metal',action='store_true');args=parser.parse_args()
    binary=ROOT/'build/bend/engine'
    if not args.skip_build: build(args.bend,binary,ROOT/'bend/engine.bend')
    profiles,invalid=fixtures(ROOT/'build/bend/climate-profiles')
    report={'scope':'Resident climate projection/index lookup; density fields and terrain integration pending','profiles':[]}
    for source,path in args.profile:
        source,path=Path(source),Path(path);model=json.loads(source.read_text());profiles.append((source.parent.name,model,path))
        report['profiles'].append({'name':source.parent.name,'json_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
            'wire_sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    report['runs']=[exercise(binary,profiles,invalid,False),exercise(binary,profiles,invalid,True)]
    report['cpu_gpu_bytes_equal']=report['runs'][0]['aggregate_sha256']==report['runs'][1]['aggregate_sha256']
    if args.prove_metal:
        diagnostic=compile_metal_observer(args.bend,ROOT/'bend/engine.bend',ROOT/'build/bend/registry-climate-metal-probe')
        observed=exercise(diagnostic,profiles,invalid,True);times=observed['observed_metal_command_ms']
        assert len(times)==len(profiles)*8+4,(len(times),len(profiles))
        assert all(t>0 for t in times)
        assert observed['aggregate_sha256']==report['runs'][1]['aggregate_sha256']
        report['metal_observation']={'diagnostic_only':True,'command_buffers':len(times),
            'device_ms':{'min':min(times),'max':max(times),'sum':sum(times)},'output_bytes_equal':True}
    for name in ('bend/registry_climate.bend','bend/climate_batch.bend','bend/engine.bend'):
        report.setdefault('source_sha256',{})[name]=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
    destination=ROOT/'build/bend/registry-climate-tests.json';destination.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: resident typed climate projection, invalidation, repeated CPU/GPU queries; {destination}')


if __name__=='__main__':main()
