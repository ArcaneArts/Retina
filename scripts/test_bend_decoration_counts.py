#!/usr/bin/env python3
"""Independently check registered Bend count/offset providers on CPU and Metal.

This is a library correctness test. It does not place surface features or claim
complete generator parity/performance. Full input profiles cross the registry
wire unchanged; all typed projection and sampling happens in Bend.
"""
import argparse
import copy
import hashlib
import json
import math
import re
import struct
from pathlib import Path

from test_bend_compression import ROOT, DEFAULT_BEND, build, command
from test_bend_engine import compile_metal_observer
from test_bend_noise import MASK, f32, axis, add_axes, mix_hash
from test_bend_registry_noise import fixture_wire

MASK64 = (1 << 64) - 1
GRADIENTS = [(1,1),(-1,1),(1,-1),(-1,-1),(1,0),(-1,0),(1,0),(-1,0),(0,1),(0,-1),(0,1),(0,-1)]


class Random:
    def __init__(self, state): self.state = state & MASK64

    def next(self):
        self.state = (self.state + 0x9e3779b97f4a7c15) & MASK64
        x = self.state
        x = ((x ^ (x >> 30)) * 0xbf58476d1ce4e5b9) & MASK64
        x = ((x ^ (x >> 27)) * 0x94d049bb133111eb) & MASK64
        return (x ^ (x >> 31)) & MASK64

    def integer(self, a, b): return a + (self.next() & MASK) % (b-a+1)
    def unit(self): return ((self.next() & MASK) >> 8) / 16777216


def provider(value, rng):
    if type(value) is int: return value
    kind = value['type']
    if kind == 'constant': return value['value']
    if kind == 'weighted_list':
        n = rng.next() % sum(e['weight'] for e in value['distribution'])
        for entry in value['distribution']:
            if n < entry['weight']: return provider(entry['data'], rng)
            n -= entry['weight']
        raise AssertionError('invalid reference weighted list')
    a = value.get('min_inclusive', value.get('min'))
    b = value.get('max_inclusive', value.get('max'))
    if kind == 'uniform': return rng.integer(a, b)
    if kind == 'biased_to_bottom': return a + rng.integer(0, rng.integer(0, b-a))
    if kind == 'trapezoid':
        if value['plateau'] >= b-a: return rng.integer(a, b)
        low = (b-a-value['plateau'])//2
        return a + rng.integer(0,low) + rng.integer(0,b-a-low)
    if kind == 'clamped': return min(b, max(a, provider(value['source'], rng)))
    if kind == 'clamped_normal':
        u, v = rng.unit(), rng.unit()
        g = f32(f32(math.sqrt(f32(-2*f32(math.log(max(u,f32(1.17549435e-38))))))) *
                f32(math.cos(f32(f32(2*math.pi)*v))))
        return int(min(b,max(a,f32(f32(value['mean'])+f32(g*f32(value['deviation']))))))
    raise AssertionError(kind)


def placement_noise(permutation, x, z, factor):
    frequency = f32(abs(f32(1/f32(factor))))
    skew = f32(frequency*f32(.3660254037844386))
    shift = add_axes(axis(x,skew),axis(z,skew))
    xx, zz = add_axes(axis(x,frequency),shift), add_axes(axis(z,frequency),shift)
    if factor < 0:
        def reverse(a):
            whole = math.floor(-a[1])
            return (-a[0]+whole)&MASK, f32(-a[1]-whole)
        xx,zz=reverse(xx),reverse(zz)
    cx,fx=xx;cz,fz=zz
    unskew=f32(f32(fx+fz)*f32(.21132486540518713))
    dx,dz=f32(fx-unskew),f32(fz-unskew)
    sx,sz=(1,0) if dx>dz else (0,1)
    def corner(x,z,dx,dz):
        gx,gz=GRADIENTS[permutation[(x+permutation[z&255])&255]%12]
        t=max(0.,f32(.5-f32(f32(dx*dx)+f32(dz*dz))))
        square=f32(t*t)
        return f32(f32(square*square)*f32(f32(gx*dx)+f32(gz*dz)))
    a=corner(cx,cz,dx,dz)
    b=corner(cx+sx,cz+sz,f32(f32(dx-sx)+f32(.21132486540518713)),f32(f32(dz-sz)+f32(.21132486540518713)))
    c=corner(cx+1,cz+1,f32(dx-f32(.5773502691896257)),f32(dz-f32(.5773502691896257)))
    return f32(70*f32(f32(a+b)+c))


def address(id, i):
    n=id*19+i*7
    return [(713+n,-1929-n),(29990000+n,-30000000+n),
            (2147483647-n,-2147483648+n),(-2147483648+n,2147483647-n)][i%4]


def rows(source):
    result=[]
    for recipe, spec in enumerate(source['decorations']):
        for modifier, op in enumerate(spec.get('placement',[])):
            if op['type'] in ('count','count_on_every_layer'):
                result.append((recipe,modifier,0 if op['type']=='count' else 4,op['count']))
            elif op['type']=='offset':
                result.extend((recipe,modifier,slot,op.get(axis,0)) for slot,axis in enumerate(('x','y','z'),1))
            elif op['type'] in ('noise_threshold_count','noise_based_count'):
                result.append((recipe,modifier,0,op))
    return result


def check(data, source):
    expected=rows(source);count,=struct.unpack_from('>I',data)
    assert count==len(expected),(count,len(expected))
    assert len(data)==4+count*(16+32*20)
    actual={};noise_error=0.;permutation=source['decoration_noise']['permutation']
    for offset in range(4,len(data),16+32*20):
        id,recipe,modifier,slot=struct.unpack_from('>4I',data,offset)
        er,em,es,spec=expected[id]
        assert id not in actual and (recipe,modifier,slot)==(er,em,es)
        values=[]
        for i in range(32):
            high,low,sh,sl,noise=struct.unpack_from('>4If',data,offset+16+i*20)
            x,z=address(id,i);x&=MASK;z&=MASK
            initial=(((0x80000000 ^ (id*7919))&MASK)<<32)|mix_hash((id*811+i)&MASK)
            rng=Random(initial)
            if type(spec) is dict and spec['type']=='noise_threshold_count':
                n=placement_noise(permutation,x,z,200.)
                value=spec['below_noise'] if n<f32(spec['noise_level']) else spec['above_noise']
            elif type(spec) is dict and spec['type']=='noise_based_count':
                n=placement_noise(permutation,x,z,spec['noise_factor'])
                value=max(0,min((1<<31)-1,math.ceil(f32(f32(n+f32(spec.get('noise_offset',0)))*f32(spec['noise_to_count_ratio'])))))
            else: value=provider(spec,rng)
            assert (high,low,sh,sl)==(0,value&MASK,rng.state>>32,rng.state&MASK),(id,i,spec,(high,low,sh,sl),value,rng.state)
            error=abs(noise-placement_noise(permutation,x,z,200.))
            assert math.isfinite(noise) and error<.00001,(id,i,noise,error)
            noise_error=max(noise_error,error)
            values.append((low,sh,sl))
        actual[id]=values
    canonical=json.dumps(actual,sort_keys=True).encode()
    return dict(rules=count,samples=count*32,maximum_noise_error=noise_error,
                sampling_sha256=hashlib.sha256(canonical).hexdigest())


def fixtures(permutation):
    providers=[0,4096,{'type':'constant','value':17},
        {'type':'uniform','min_inclusive':0,'max_inclusive':19},
        {'type':'uniform','min':3,'max':3},
        {'type':'biased_to_bottom','min_inclusive':0,'max_inclusive':19},
        {'type':'trapezoid','min':0,'max':31,'plateau':5},
        {'type':'trapezoid','min':0,'max':7,'plateau':100},
        {'type':'clamped','source':{'type':'uniform','min_inclusive':-5,'max_inclusive':7},'min_inclusive':0,'max_inclusive':3},
        {'type':'clamped_normal','mean':9.25,'deviation':3.5,'min_inclusive':0,'max_inclusive':20},
        {'type':'weighted_list','distribution':[{'data':1,'weight':4294967295},{'data':{'type':'uniform','min':3,'max':10},'weight':4294967295}]},
        {'type':'weighted_list','distribution':[{'data':{'type':'weighted_list','distribution':[{'data':0,'weight':9},{'data':1,'weight':1}]},'weight':9},{'data':2,'weight':1}]},
        {'type':'weighted_list','distribution':[{'data':9999,'weight':0},{'data':17,'weight':1}]}]
    modifiers=[{'type':'count','count':p} for p in providers]
    modifiers += [{'type':'offset','x':{'type':'uniform','min':-4096,'max':4096},'y':-7},
                  {'type':'count_on_every_layer','count':{'type':'uniform','min':0,'max':256}},
                  {'type':'noise_threshold_count','noise_level':-.8,'below_noise':5,'above_noise':17},
                  {'type':'noise_threshold_count','noise_level':.2,'below_noise':0,'above_noise':3}]
    modifiers += [dict(type='noise_based_count',noise_factor=factor,noise_offset=offset,noise_to_count_ratio=ratio)
                  for factor,offset,ratio in [(80,.3,17),(-80,.3,17),(30,-.5,-8),(13,0,50)]]
    source={'decorations':[{'placement':modifiers}],'decoration_noise':{'permutation':permutation}}
    invalid=[]
    bad_providers=[True,1.5,-1,4097,{'type':'unknown','value':1},
        {'type':'uniform','min':3,'max':2},
        {'type':'trapezoid','min':0,'max':3,'plateau':-1},
        {'type':'weighted_list','distribution':[]},
        {'type':'weighted_list','distribution':[{'data':1,'weight':0}]},
        {'type':'weighted_list','distribution':[{'data':1,'weight':-1}]},
        {'type':'weighted_list','distribution':[{'data':1,'weight':4294967296}]},
        {'type':'clamped_normal','mean':0,'deviation':-1,'min_inclusive':0,'max_inclusive':2},
        {'type':'clamped_normal','mean':1e200,'deviation':1,'min_inclusive':0,'max_inclusive':2},
        {'type':'clamped','source':9999,'min_inclusive':0,'max_inclusive':2}]
    for p in bad_providers:
        invalid.append({'decorations':[{'placement':[{'type':'count','count':p}]}],'decoration_noise':source['decoration_noise']})
    for op in [dict(type='count_on_every_layer',count=257),
        dict(type='noise_based_count',noise_factor=0,noise_to_count_ratio=1),
        dict(type='noise_based_count',noise_factor=80,noise_offset=1e200,noise_to_count_ratio=1),
        dict(type='noise_threshold_count',noise_level=0,below_noise=-1,above_noise=2)]:
        bad=copy.deepcopy(source);bad['decorations']=[{'placement':[op]}];invalid.append(bad)
    for perm in [permutation[:255],[0]*256,[-1]+permutation[1:]]:
        bad=copy.deepcopy(source);bad['decoration_noise']['permutation']=perm;invalid.append(bad)
    return source,invalid


def exercise(binary,folder,name,source,gpu,reverse=False):
    path=folder/f'{name}.rbp';path.write_bytes(fixture_wire(source,True))
    output=folder/f'{name}-{int(gpu)}-{int(reverse)}.bin'
    result=command(['nice','-n','10',binary,path,output,*(['reverse'] if reverse else []),
                    '--threads','2','--gpu','on' if gpu else 'off'],timeout=300)
    return check(output.read_bytes(),source),result.stderr


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--profile',type=Path,action='append',required=True)
    p.add_argument('--skip-build',action='store_true');p.add_argument('--prove-metal',action='store_true')
    a=p.parse_args();folder=ROOT/'build/bend/decoration-count-tests';folder.mkdir(parents=True,exist_ok=True)
    binary=ROOT/'build/bend/decoration-counts';driver=ROOT/'bend/tests/decoration-counts.bend'
    if not a.skip_build:build(DEFAULT_BEND,binary,driver)
    profiles=[(f'profile-{i}',json.loads(path.read_text())) for i,path in enumerate(a.profile)]
    fixture,invalid=fixtures(profiles[0][1]['decoration_noise']['permutation'])
    profiles += [('fixture',fixture),('empty',{'decorations':[],'decoration_noise':fixture['decoration_noise']})]
    runs=[]
    for gpu in (False,True):
        for name,source in profiles:
            check_result,_=exercise(binary,folder,name,source,gpu)
            reverse,_=exercise(binary,folder,name,source,gpu,True)
            assert check_result['sampling_sha256']==reverse['sampling_sha256']
            runs.append(dict(profile=name,gpu_required=gpu,reverse_order='pass',**check_result))
        for i,source in enumerate(invalid):
            path=folder/f'invalid-{i}.rbp';path.write_bytes(fixture_wire(source,True));output=folder/'invalid.bin'
            command(['nice','-n','10',binary,path,output,'--threads','2','--gpu','on' if gpu else 'off'],timeout=120)
            assert output.read_bytes()==struct.pack('>2I',MASK,759),(i,output.read_bytes())
    for name,_ in profiles:
        hashes=[r['sampling_sha256'] for r in runs if r['profile']==name]
        assert len(set(hashes))==1,(name,hashes)
    observed=[]
    if a.prove_metal:
        probe=ROOT/'build/bend/decoration-counts-observer'
        compile_metal_observer(DEFAULT_BEND,driver,probe)
        checked,stderr=exercise(probe,folder,'observer',profiles[0][1],True)
        assert checked['sampling_sha256']==runs[0]['sampling_sha256']
        observed=[float(n) for n in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)]
        assert observed,'No actual Metal command buffers observed'
    sources=[ROOT/'bend'/f'{name}.bend' for name in ('placement_random','int_provider','registry_int_provider','decoration_counts','registry_decoration_counts','tests/decoration-counts')]
    sources.append(Path(__file__))
    report=dict(scope=__doc__.strip(),runs=runs,invalid_profiles_per_backend=len(invalid),observed_metal_device_ms=observed,
        profiles={str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in a.profile},
        sources={str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest())
    destination=folder/'results.json';destination.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',destination)


if __name__=='__main__':main()
