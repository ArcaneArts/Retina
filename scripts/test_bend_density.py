#!/usr/bin/env python3
"""Check pure Bend numeric density programs against independent scalar equations.

Raw fixture input is test-only. Production resident tape projection, material
predicates, density lattices and region integration are separate pending work.
"""
import argparse
import copy
import functools
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import struct
import subprocess

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_noise import mix_hash, MASK, signed, f32
from test_bend_engine import compile_metal_observer


def ins(op,a=0,b=0,c=0,p=(0.,0.,0.,0.)):
    return dict(op=op,a=a,b=b,c=c,p=list(p))


def perlin(point,seed):
    # Independent double-precision coordinate decomposition and weighted sum.
    cells=[math.floor(x) for x in point];parts=[x-y for x,y in zip(point,cells)]
    fade=[t*t*t*(t*(t*6-15)+10) for t in parts]
    result=0.
    for k in range(8):
        c=[(cells[j]+((k>>j)&1))&MASK for j in range(3)]
        d=[parts[j]-((k>>j)&1) for j in range(3)]
        h=mix_hash(((c[0]*2654435769)^(c[1]*2246822507)^(c[2]*3266489909)^seed)&MASK)&15
        a=d[0] if h<8 else d[1]
        b=d[1] if h<4 else d[0] if h in (12,14) else d[2]
        value=(-a if h&1 else a)+(-b if h&2 else b)
        result+=value*math.prod(fade[j] if (k>>j)&1 else 1-fade[j] for j in range(3))
    return result*1.6


class Oracle:
    def __init__(self,model):
        self.model=model

    def noise(self,point,index,low,high):
        n=self.model['noises'][index]
        seed=low^mix_hash(high)^(n['salt']&MASK)
        point=[x*n.get('horizontal_scale',1.) if j!=1 else x for j,x in enumerate(point)]
        freq=n['frequency'];total=0.
        for i,w in enumerate(n['coefficients']):
            if w:
                total+=(perlin([x*freq for x in point],(seed+i*1013)&MASK)+
                    perlin([x*f32(freq*f32(1.0181268882175227)) for x in point],((seed^0xa511e9b3)+i*1013)&MASK))*w
            freq=f32(freq*2)
        return total*n['amplitude']*.625

    def blended(self,point,p,low,high):
        a,b,c,d=p
        lim=[f32(f32(f32(684.412)*a)/32768),f32(f32(f32(684.412)*b)/32768)]
        main=[f32(f32(f32(f32(684.412)*a)/c)/128),f32(f32(f32(f32(684.412)*b)/d)/128)]
        limit=[v*lim[j==1] for j,v in enumerate(point)]
        main=[v*main[j==1] for j,v in enumerate(point)]
        seed=low^mix_hash(high);lo=hi=blend=total=0.
        for i in range(8):
            weight=2**-i;scale=2**i;total+=weight
            lo+=perlin([v*scale for v in limit],(seed+i*1013)&MASK)*weight
            hi+=perlin([v*scale for v in limit],(seed+7919+i*1013)&MASK)*weight
            blend+=perlin([v*scale for v in main],(seed+15838+i*1013)&MASK)*weight
        t=max(0,min(1,blend/total*.5+.5))
        return (lo*(1-t)+hi*t)/total

    @functools.lru_cache(maxsize=100000)
    def field_node(self,field,point,low,high):
        return self.evaluate(self.model['interpolations'][field]['input'],point,low,high)[0]

    def interpolate(self,index,point,low,high):
        h,v=self.model['interpolations'][index]['cell'];steps=(h,v,h)
        origin=[math.floor(x/s)*s for x,s in zip(point,steps)]
        t=[(x-a)/s for x,a,s in zip(point,origin,steps)]
        # Endpoints do not sample zero-weight corners. In particular an
        # overflowing unused field value must not turn a finite vertex into NaN.
        total = 0.
        for k in range(8):
            weight = math.prod(t[j] if (k>>j)&1 else 1-t[j] for j in range(3))
            if weight:
                total += self.field_node(index,tuple(origin[j]+steps[j]*((k>>j)&1) for j in range(3)),low,high)*weight
        return total

    def evaluate(self,program,point,low,high):
        values=[]
        def grad(x,p,mode):
            t=(x-p[0])/(p[1]-p[0])
            if mode==1: t%=1
            elif mode==2: t=1-abs((t*.5%1)*2-1)
            else: t=max(0,min(1,t))
            return p[2]*(1-t)+p[3]*t
        for n in program['nodes']:
            op,a,b,c,p=(n[k] for k in ('op','a','b','c','p'))
            av=values[a] if a<len(values) else 0.
            bv=values[b] if b<len(values) else 0.
            cv=values[c] if c<len(values) else 0.
            if op==0: r=p[0]
            elif op==1: r=self.noise([point[0]*p[1]+av,point[1]*p[2]+bv,point[2]*p[1]+cv],int(p[0]),low,high)
            elif op==2:
                q=point if b==0 else [point[0],0,point[2]] if b==1 else [point[2],point[0],0]
                r=self.noise([x*.25 for x in q],a,low,high)*4
            elif op==3: r=grad(point[a],p,b)
            elif op==4: r=av+bv
            elif op==5: r=av-bv
            elif op==6: r=av*bv
            elif op==7: r=av/(.000001 if abs(bv)<.000001 else bv)
            elif op==8: r=min(av,bv)
            elif op==9: r=max(av,bv)
            elif op==10: r=max(av,0)**bv
            elif op==11: r=abs(av)
            elif op==12: r=av*av
            elif op==13: r=av**3
            elif op==14: r=av if av>0 else av*.5
            elif op==15: r=av if av>0 else av*.25
            elif op==16:
                x=max(-1,min(1,av));r=x*.5-x**3/24
            elif op==17: r=1/(.000001 if abs(av)<.000001 else av)
            elif op==18: r=-av
            elif op==19: r=math.sqrt(max(av,0))
            elif op==20: r=math.log(max(av,.000001))
            elif op==21: r=(av>0)-(av<0)
            elif op==22: r=max(p[0],min(p[1],av))
            elif op==23: r=bv if p[0]<=av<p[1] else cv
            elif op==24: r=bv*(1-av)+cv*av
            elif op==25:
                points=self.model['points'][b:b+c];j=0
                for k in range(1,c):
                    if av>=points[k][0]: j=k
                x,ld,child,_=points[j];lv=values[int(child)]
                if av<x or j==c-1: r=lv+(av-x)*ld
                else:
                    rx,rd,child,_=points[j+1];rv=values[int(child)]
                    span=rx-x;t=(av-x)/span;delta=rv-lv
                    r=lv*(1-t)+rv*t+t*(1-t)*((ld*span-delta)*(1-t)+(-rd*span+delta)*t)
            elif op==26: r=self.blended(point,p,low,high)
            elif op==27: r=self.noise([av,bv,cv],int(p[0]),low,high)*p[1]
            elif op==28: r=self.interpolate(int(p[0]),[av,bv,cv],low,high)
            elif op==29: r=point[a]
            elif op==30: r=grad(av,p,b)
            elif op==31: r=self.blended([av,bv,cv],p,low,high)
            else: raise ValueError(op)
            values.append(r)
        return [values[i] for i in program['roots']+[0]*(6-len(program['roots']))]


def packed(model,programs,queries):
    data=bytearray()
    def word(*values): data.extend(struct.pack('>'+len(values)*'I',*(x&MASK for x in values)))
    def floats(values): data.extend(struct.pack('>'+len(values)*'f',*values))
    def program(p):
        word(len(p['nodes']),len(p['roots']));word(*p['roots'])
        for n in p['nodes']:
            word(n['op'],n['a'],n['b'],n['c']);floats(n['p'])
    word(len(model['noises']),len(model['points']),len(model.get('interpolations',[])),len(programs),len(queries))
    for n in model['noises']:
        floats([n.get('horizontal_scale',1.),n['frequency'],n['amplitude']]);word(n['salt'],len(n['coefficients']));floats(n['coefficients'])
    for p in model['points']: floats(p)
    for f in model.get('interpolations',[]): word(*f['cell']);program(f['input'])
    for p in programs: program(p)
    for q in queries: word(*q)
    return bytes(data)


def synthetic():
    nodes=[ins(0,p=(-.75,0,0,0)),ins(0,p=(.125,0,0,0)),ins(0,p=(2.5,0,0,0))]
    nodes.extend(ins(29,a=i) for i in range(3))
    nodes.append(ins(1,0,1,2,(0,.25,.125,0)))
    nodes.extend(ins(2,0,i) for i in range(3))
    nodes.extend(ins(3,a=i,b=mode,p=(-128,256,-1,1)) for i in range(3) for mode in range(3))
    nodes.extend(ins(op,1 if op==10 else 0,2) for op in range(4,11))
    nodes.extend(ins(op,0,p=(-.5,.5,0,0)) for op in range(11,23))
    nodes.extend([ins(23,0,1,2,(-1,0,0,0)),ins(24,1,0,2),ins(25,0,0,3),
        ins(26,p=(1,1,80,160)),ins(27,3,4,5,(0,.5,0,0)),ins(28,3,4,5,(1,0,0,0))])
    nodes.extend(ins(30,0,b=mode,p=(-1,1,-2,2)) for mode in range(3))
    nodes.append(ins(31,3,4,5,(1,1,80,160)))
    zero=len(nodes);nodes.extend([ins(0),ins(7,2,zero),ins(17,zero)])
    # Field zero includes nonlinear lattice samples; field one nests it and adds y.
    f0={'nodes':[ins(29,a=0),ins(29,a=1),ins(29,a=2),ins(27,0,1,2,(0,1,0,0))],'roots':[3]}
    f1={'nodes':[ins(29,a=0),ins(29,a=1),ins(29,a=2),ins(28,0,1,2,(0,0,0,0)),ins(4,3,1)],'roots':[4]}
    model={'noises':[{'frequency':.0035,'amplitude':.8,'horizontal_scale':1.25,'salt':-12345,'coefficients':[1.,0.,-.25,.5]}],
        'points':[[-1.,.2,0.,0.],[0.,-.3,1.,0.],[1.,.1,2.,0.]],
        'interpolations':[{'cell':[4,8],'input':f0},{'cell':[16,16],'input':f1}]}
    programs=[{'nodes':nodes,'roots':list(range(i,min(i+6,len(nodes))))} for i in range(0,len(nodes),6)]
    # Retain precision after coordinate transforms, not merely direct noise calls.
    precise=[ins(29,a=0),ins(0,p=(30000000.,0,0,0)),ins(5,0,1),ins(0,p=(.0035,0,0,0)),
        ins(6,0,3),ins(0,p=(105000.,0,0,0)),ins(5,4,5)]
    programs.append({'nodes':precise,'roots':[2,6]})
    # Exercise implicit outputs from node zero and a one-point spline.
    programs.append({'nodes':[ins(0,p=(2.5,0,0,0))],'roots':[]})
    programs.append({'nodes':[ins(0,p=(2.5,0,0,0)),ins(25,0,0,1)],'roots':[1]})
    # Registered Terralith splines contain duplicate and nonmonotonic locations.
    model['points'] += [[-1.,.2,0.,0.],[0.,-.3,1.,0.],[0.,.5,2.,0.],[-.2,.1,1.,0.],[1.,.1,0.,0.]]
    programs.append({'nodes':[ins(29,a=1),ins(0,p=(.5,0,0,0)),ins(0,p=(1.5,0,0,0)),ins(25,0,3,5)],'roots':[3]})
    return model,programs


def queries(programs,n=12):
    rng=random.Random(20261008);result=[]
    points=[(0,0,0),(-1,-64,-1),(4,8,4),(-4,-8,-4),(29999999,67,-29999999),
        (30000000,68,-30000000),(30000001,69,-30000001),(2147483647,0,-2147483648)]
    points += [(rng.randrange(-20000,20000),rng.randrange(-64,320),rng.randrange(-20000,20000)) for _ in range(n)]
    for i in range(len(programs)):
        for j,(x,y,z) in enumerate(points): result.append((i,x,y,z,0x12345678 if j%3 else 0,0x98765432 if j%2 else 0))
    return result


def run(binary,folder,name,model,programs,qs,gpu,threads=2):
    source=folder/f'{name}.in';dest=folder/f'{name}-{gpu}-{threads}.out'
    source.write_bytes(packed(model,programs,qs))
    process=subprocess.run(['nice','-n','10',str(binary),str(source),str(dest),'--threads',str(threads),'--gpu',gpu],
        cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'),capture_output=True,text=True,timeout=240)
    if process.returncode: raise RuntimeError(process.stdout+process.stderr)
    data=dest.read_bytes();assert len(data)==len(qs)*24,(name,len(data),len(qs))
    oracle=Oracle(model);maximum=0.;worst=None
    for q,actual in zip(qs,struct.iter_unpack('>6f',data)):
        pid,x,y,z,lo,hi=q;reference=oracle.evaluate(programs[pid],(x,y,z),lo,hi)
        for channel,(a,b) in enumerate(zip(actual,reference)):
            assert math.isfinite(a),(name,q,channel,a,b)
            error=abs(a-b)/max(1.,abs(b));maximum=max(maximum,error)
            if error==maximum: worst=(q,channel,a,b)
            assert error<.00015,(name,q,channel,a,b,error)
    return {'name':name,'queries':len(qs),'scalar_results':len(qs)*6,'gpu':gpu,'threads':threads,
        'max_relative_or_absolute_error':maximum,'sha256':hashlib.sha256(data).hexdigest(),
        'metal_commands_ms':[float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',process.stderr)]}


def invalid_cases(model,programs):
    result=[]
    for change in ('empty','long','root','roots','forward','op','axis','noise','field','fractional_field','spline','fractional_spline','nan','gradient','blend'):
        m,p=copy.deepcopy((model,programs[:1]));n=p[0]['nodes']
        if change=='empty': n.clear();p[0]['roots']=[]
        elif change=='long': p[0]['nodes']=[ins(0)]*1025
        elif change=='root': p[0]['roots']=[len(n)]
        elif change=='roots': p[0]['roots']=[0]*7
        elif change=='forward': n[0]=ins(4,0,0)
        elif change=='op': n[0]=ins(40)
        elif change=='axis': n[0]=ins(29,3)
        elif change=='noise': n[6]['p'][0]=1
        elif change=='field': n[-7]=ins(28,0,1,2,(2,0,0,0))
        elif change=='fractional_field': n[-7]=ins(28,0,1,2,(.5,0,0,0))
        elif change=='spline': m['points'][0][2]=1024.
        elif change=='fractional_spline': m['points'][1][2]=.5
        elif change=='nan': n[0]['p'][0]=float('nan')
        elif change=='gradient': n[0]=ins(3,0,p=(1,1,0,1))
        elif change=='blend': n[0]=ins(26,p=(1,1,0,160))
        result.append((change,m,p))
    for field,value in [('horizontal_scale',0.),('frequency',0.),('amplitude',float('inf')),('coefficients',[]),('coefficients',[1.]*33)]:
        m,p=copy.deepcopy((model,programs[:1]));m['noises'][0][field]=value;result.append((field,m,p))
    for change in ('cycle','cell','roots'):
        m,p=copy.deepcopy((model,programs[:1]))
        if change=='cycle': m['interpolations'][1]['input']['nodes'][3]['p'][0]=1
        elif change=='cell': m['interpolations'][0]['cell'][0]=0
        else: m['interpolations'][0]['input']['roots']=[]
        result.append((change,m,p))
    return result


def reject(binary,folder,invalid,gpu,threads):
    source=folder/'invalid.in';dest=folder/'invalid.out'
    for name,m,p in invalid:
        source.write_bytes(packed(m,p,[(0,0,0,0,0,0)]))
        proc=subprocess.run(['nice','-n','10',str(binary),str(source),str(dest),'--threads',str(threads),'--gpu',gpu],
            cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'),capture_output=True,text=True,timeout=30)
        assert proc.returncode!=0,(name,gpu,'accepted invalid model')
        assert 'invalid density fixture' in proc.stderr,(name,proc.stdout,proc.stderr)
    # Raw transport rejects truncation and trailing data as well.
    good=packed(*synthetic(),[(0,0,0,0,0,0)])
    for data in (good[:-1],good+b'\0',good[:19]):
        source.write_bytes(data)
        proc=subprocess.run(['nice','-n','10',str(binary),str(source),str(dest),'--threads',str(threads),'--gpu',gpu],
            cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'),capture_output=True,text=True,timeout=30)
        assert proc.returncode!=0 and 'invalid density fixture' in proc.stderr
    return len(invalid)+3


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true')
    parser.add_argument('--profile',type=Path,action='append',default=[]);args=parser.parse_args()
    folder=ROOT/'build/bend/density-profiles';folder.mkdir(parents=True,exist_ok=True)
    source=ROOT/'bend/tests/density-fixtures.bend';binary=ROOT/'build/bend/density-fixtures'
    if not args.skip_build: build(args.bend,binary,source)
    m,p=json.loads(json.dumps(synthetic()),parse_float=lambda x:f32(float(x)));qs=queries(p)
    qs+=list(reversed(qs[:32]))+qs[:16]
    cases=[('synthetic',m,p,qs),('empty_batch',m,p,[])]
    profiles=[]
    for path in args.profile:
        model=json.loads(path.read_text())['registry_program']
        # Copy the registered graph/parameters verbatim. No Java/Rust evaluation.
        model=json.loads(json.dumps(model),parse_float=lambda x:f32(float(x)))
        programs=model['programs'][:3]
        cases.append((path.parent.name,model,programs,queries(programs,4)))
        profiles.append({'name':path.parent.name,'json_sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    report={'scope':'Numeric density evaluator 0..31; material predicates, resident projection and region integration pending',
        'profiles':profiles,'runs':[],'invalid_cases':[]}
    invalid=invalid_cases(m,p)
    for gpu,threads in [('off',1),('off',2),('on',2)]:
        for name,model,programs,qs in cases:
            row=run(binary,folder,name,model,programs,qs,gpu,threads);report['runs'].append(row)
            print(f'{name} {gpu}/{threads}: {row["queries"]} queries, error {row["max_relative_or_absolute_error"]:.8g}',flush=True)
        report['invalid_cases'].append({'gpu':gpu,'threads':threads,'rejected':reject(binary,folder,invalid,gpu,threads)})
    for i in range(len(cases)):
        assert report['runs'][i]['sha256']==report['runs'][len(cases)+i]['sha256'],'CPU scheduling changed output'
    if args.prove_metal:
        diagnostic=compile_metal_observer(args.bend,source,ROOT/'build/bend/density-metal-probe')
        observed=[]
        for i,(name,model,programs,qs) in enumerate(cases):
            row=run(diagnostic,folder,name,model,programs,qs,'on');observed.extend(row['metal_commands_ms'])
            assert row['metal_commands_ms'] and row['sha256']==report['runs'][2*len(cases)+i]['sha256']
        report['metal_observation']={'diagnostic_only':True,'command_buffers':len(observed),'device_ms':observed,'output_bytes_equal':True}
    for name in ('bend/density.bend','bend/precise.bend','bend/tests/density-fixtures.bend'):
        report.setdefault('source_sha256',{})[name]=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
    dest=ROOT/'build/bend/density-tests.json';dest.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: registered numeric density CPU/GPU/reference checks; {dest}')


if __name__=='__main__': main()
