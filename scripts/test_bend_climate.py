#!/usr/bin/env python3
"""Validate pure Bend climate indices against independent linear interval search.

The fixture driver consumes raw interval data for QA. Resident registry-tape
projection, climate density programs and world generation are not implemented by
this harness. Actual exported JSON is only copied into numeric fixture records.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import re
import struct
import subprocess

from test_bend_compression import ROOT,DEFAULT_BEND,build
from test_bend_engine import compile_metal_observer


def f32(value):
    try: return struct.unpack('>f',struct.pack('>f',value))[0]
    except OverflowError: return math.copysign(math.inf,value)


def target(lo,hi,offset,biome,flags,order):
    return {'lo':list(map(f32,lo)),'hi':list(map(f32,hi)),'offset':f32(offset),
            'biome':biome,'flags':flags,'order':order}


def fitness(t,p,mode):
    deltas = [f32(f32(x-min(max(x,lo),hi))**2) for x,lo,hi in zip(p,t['lo'],t['hi'])]
    weights = (2.5,1.5,2.,.5) if mode&1 else (1.,)*4
    a,b,c,d = [f32(deltas[i]*weights[i]) for i in range(4)]
    distance = f32(f32(a+b)+f32(c+d))
    result = f32(f32(distance+deltas[4])+f32((deltas[5] if mode&2 else 0.)+f32(t['offset']**2)))
    if mode&4 and bool(t['flags']&4) != (p[2]<f32(-.25)): result = f32(result+8.)
    return result


def expected(targets,p,mode):
    candidates = []
    if mode&16:
        for t in targets:
            if t['flags']&16 or not t['flags']&64: continue
            q = dict(t,lo=t['lo'].copy(),hi=t['hi'].copy())
            q['lo'][5],q['hi'][5] = q['lo'][2],q['hi'][2]
            q['lo'][2] = q['hi'][2] = 0.
            candidates.append(q)
        p = p.copy();p[2]=0.;mode=0
    else:
        candidates = [t for t in targets if (mode&2 or not t['flags']&16) and not (mode&8 and t['flags']&64)]
    if not candidates: return None
    best = min(candidates,key=lambda t:(fitness(t,p,mode),t['order']))
    return best,fitness(best,p,mode),f32(f32(best['lo'][5]*.5)+f32(best['hi'][5]*.5))


def queries(rng,count=512):
    result = []
    for i in range(count):
        p = [f32(rng.randrange(-1536,1537)/1024) for _ in range(6)]
        if i < 32: p = [0.]*6
        result.append((p,i%32))
    return result


def datasets(profiles):
    rng = random.Random(20261008)
    values = []
    for i in range(1003):
        lo = [rng.randrange(-1024,1025)/512 for _ in range(6)]
        hi = [x+rng.randrange(0,513)/512 for x in lo]
        flags = (4 if i%7 == 0 else 0)|(16 if i%23 == 0 else 0)|(64 if i%13 == 0 else 0)
        values.append(target(lo,hi,rng.randrange(-64,65)/1024,i%67,flags,i))
    values += [dict(values[17],order=1003),dict(values[17],order=1004)]
    rng.shuffle(values)  # Original ordinals, not traversal/list order, decide ties.
    result = [('synthetic',values,queries(rng)),('empty',[],queries(rng,32)),('no_queries',values,[])]
    # Zero-distance ties, every flag combination, and finite values whose
    # squared distances overflow F32. Missing-first selection must still work.
    adversarial = [target([0.]*6,[1.]*6,0.,i,(i&1)*4+(i&2)*8+(i&4)*16,31-i) for i in range(32)]
    adversarial += [target([1.e30]*6,[2.e30]*6,1.e30,33,0,32)]
    result.append(('ties',adversarial,queries(rng,128)))
    result.append(('overflow',[target([1.e30]*6,[2.e30]*6,1.e30,33,0,5),
        target([-2.e30]*6,[-1.e30]*6,-1.e30,34,0,2)],queries(rng,32)))
    # Non-dyadic values exercise rounded distance bounds and exact interval ends.
    rounded=[]
    for i in range(257):
        lo=[f32(rng.uniform(-2.,2.)) for _ in range(6)]
        hi=[f32(x+rng.uniform(0.,1.)) for x in lo]
        rounded.append(target(lo,hi,rng.uniform(-.2,.2),i,0,i))
    ends=[(t[key].copy(),mode) for t in rounded[:16] for key in ('lo','hi') for mode in range(16)]
    result.append(('rounded',rounded,ends))
    for path in profiles:
        data=json.loads(path.read_text())
        targets=[target(t['min']+t['weirdness'][:1]+t.get('depth',[0.,0.])[:1],
                        t['max']+t['weirdness'][1:]+t.get('depth',[0.,0.])[1:],
                        t['offset'],t['biome'],data['biomes'][t['biome']]['flags'],i)
                 for i,t in enumerate(data['climate_targets'])]
        result.append((path.parent.name,targets,queries(rng,256)))
    return result


def fixture(targets,queries):
    data=bytearray(struct.pack('>2I',len(targets),len(queries)))
    for t in targets:
        data+=struct.pack('>13f3I',*t['lo'],*t['hi'],t['offset'],t['biome'],t['flags'],t['order'])
    for point,mode in queries:data+=struct.pack('>6fI',*point,mode)
    return bytes(data)


def verify(raw,targets,queries):
    assert len(raw) == len(queries)*20
    maximum=0.
    for i,(present,biome,order,bits,coast_bits) in enumerate(struct.iter_unpack('>5I',raw)):
        result=expected(targets,*queries[i])
        if result is None:
            assert (present,biome,order,bits,coast_bits) == (0,0xffffffff,0xffffffff,0,0)
            continue
        t,fit,coast=result
        actual=struct.unpack('>f',struct.pack('>I',bits))[0]
        assert (present,biome,order) == (1,t['biome'],t['order']),(i,queries[i],(present,biome,order),(t['biome'],t['order']),fit,actual)
        if math.isinf(fit): assert actual == fit
        else:
            error=abs(actual-fit);maximum=max(maximum,error)
            assert error <= max(1e-6,abs(fit)*1e-6),(i,actual,fit)
        assert coast_bits == struct.unpack('>I',struct.pack('>f',coast))[0]
    return {'queries':len(queries),'max_fitness_error':maximum,'sha256':hashlib.sha256(raw).hexdigest()}


def run(binary,input,output,targets,queries,gpu,threads):
    p=subprocess.run(['nice','-n','10',str(binary),str(input),str(output),'--gpu',gpu,'--threads',str(threads)],
                     check=True,capture_output=True,text=True,cwd=ROOT,timeout=120)
    return {**verify(output.read_bytes(),targets,queries),'observed_metal_command_ms':
        [float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',p.stderr)]}


def malformed(binary,folder):
    good=target([0.]*6,[1.]*6,0.,1,0,0)
    points=[([0.]*6,0)]
    cases={'empty':b'', 'short_header':b'\0'*7,
           'target_limit':struct.pack('>2I',65537,0),
           'query_limit':struct.pack('>2I',0,4097),
           'truncated_target':fixture([good],points)[:71],
           'truncated_query':fixture([good],points)[:-1],
           'trailing':fixture([good],points)+b'\0',
           'mode':fixture([good],[([0.]*6,32)]),
           'query_nan':fixture([good],[([float('nan')]+[0.]*5,0)]),
           'query_inf':fixture([good],[([float('inf')]+[0.]*5,0)]),
           'biome_limit':fixture([dict(good,biome=65536)],points),
           'offset_nan':fixture([dict(good,offset=float('nan'))],points),
           'offset_inf':fixture([dict(good,offset=float('inf'))],points)}
    for i in range(6):
        lo=good['lo'].copy();lo[i]=float('nan')
        cases[f'low_nan_{i}']=fixture([dict(good,lo=lo)],points)
        hi=good['hi'].copy();hi[i]=float('-inf')
        cases[f'high_inf_{i}']=fixture([dict(good,hi=hi)],points)
        lo=good['lo'].copy();lo[i]=2.
        cases[f'inverted_{i}']=fixture([dict(good,lo=lo)],points)
    output=folder/'rejected-output.bin'
    for gpu in ('off','on'):
        for name,data in cases.items():
            input=folder/f'invalid-{name}.bin';input.write_bytes(data)
            output.unlink(missing_ok=True)
            p=subprocess.run(['nice','-n','10',str(binary),str(input),str(output),'--gpu',gpu,'--threads','2'],
                             capture_output=True,cwd=ROOT,timeout=15)
            assert p.returncode != 0 and not output.exists(),(gpu,name,p.returncode,p.stderr)
    return {'cases_per_backend':len(cases),'backends':['off','on'],'successful_partial_outputs':0}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true')
    parser.add_argument('--profile',type=Path,action='append',default=[])
    args=parser.parse_args()
    source=ROOT/'bend/tests/climate-fixtures.bend';binary=ROOT/'build/bend/climate-fixtures'
    if not args.skip_build:build(args.bend,binary,source)
    folder=ROOT/'build/bend/climate-input';folder.mkdir(parents=True,exist_ok=True)
    report={'scope':'Climate index correctness; resident projection/density programs/world generation pending','datasets':[]}
    cases=datasets(args.profile)
    for name,targets,points in cases:
        input=folder/f'{name}.bin';input.write_bytes(fixture(targets,points))
        runs=[]
        for gpu,threads in [('off',1),('off',2),('on',2)]:
            output=folder/f'{name}-{gpu}-{threads}.bin'
            runs.append({'requested_gpu':gpu,'threads':threads,**run(binary,input,output,targets,points,gpu,threads)})
        assert runs[0]['sha256'] == runs[1]['sha256']
        report['datasets'].append({'name':name,'targets':len(targets),'input_sha256':hashlib.sha256(input.read_bytes()).hexdigest(),
            'runs':runs,'cpu_gpu_bytes_equal':runs[0]['sha256']==runs[2]['sha256']})
        print(f'{name}: {len(targets)} targets; {len(points)} queries on CPU/GPU',flush=True)
    if args.prove_metal:
        diagnostic=compile_metal_observer(args.bend,source,ROOT/'build/bend/climate-metal-probe')
        report['metal_observation']=[]
        for name,targets,points in cases:
            observed=run(diagnostic,folder/f'{name}.bin',folder/f'{name}-probe.bin',targets,points,'on',2)
            times=observed['observed_metal_command_ms']
            assert len(times)==1 and times[0]>0,(name,times)
            normal=next(x for x in report['datasets'] if x['name']==name)['runs'][2]
            assert observed['sha256']==normal['sha256']
            report['metal_observation'].append({'name':name,'diagnostic_only':True,'command_buffers':1,'device_ms':times,'output_bytes_equal':True})
    report['rejected_inputs']=malformed(binary,folder)
    for name in ('bend/climate.bend','bend/tests/climate-fixtures.bend'):
        report.setdefault('source_sha256',{})[name]=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
    destination=ROOT/'build/bend/climate-tests.json';destination.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: registered interval fitness, flags, depth, coasts and original-order ties; {destination}')


if __name__ == '__main__':main()
