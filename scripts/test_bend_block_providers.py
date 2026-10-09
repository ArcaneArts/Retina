#!/usr/bin/env python3
"""Registered block-provider decisions/noise versus independent CPU references.

Original full profiles cross the registry wire unchanged. This component does
not place surface features or change the playable world worker. Metal command
buffers, not merely a runtime flag, establish actual GPU execution.
"""
import argparse
import copy
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path
import random
import re
import struct
import subprocess

from test_bend_compression import ROOT, DEFAULT_BEND, build, command
from test_bend_decoration_counts import Random, provider as int_provider, MASK64
from test_bend_engine import compile_metal_observer
from test_bend_noise import MASK, f32, mix_hash
from test_bend_placement_programs import fixtures as placement_fixtures, origin, signed
from test_bend_placement_walker import World, predicate
from test_bend_registry_noise import fixture_wire

KINDS = ('state', 'weighted', 'randomized_int', 'rule_based', 'rotated',
         'copy_properties', 'random_block', 'noise', 'dual_noise', 'noise_threshold')
GRADIENTS = [(1,1,0),(-1,1,0),(1,-1,0),(-1,-1,0),(1,0,1),(-1,0,1),(1,0,-1),(-1,0,-1),
             (0,1,1),(0,-1,1),(0,1,-1),(0,-1,-1),(1,1,0),(0,-1,1),(-1,1,0),(0,-1,-1)]
PHASE_MASK = (1 << 48)-1


def add(a, b): return f32(a+b)
def mul(a, b): return f32(a*b)
def lerp(a, b, t): return add(a, mul(t, f32(b-a)))
def fade(t): return mul(mul(mul(t,t),t), add(mul(t, f32(mul(t,6.)-15.)),10.))
def fixed(value): return math.floor((value % 256.) * (1 << 40) + .5) & PHASE_MASK


def phase(point, frequency, offset, scale):
    if scale:
        coordinate = f32(f32(point)*scale)
        # Independent rational multiplication of the rounded F32 coordinate
        # and original f64 frequency, truncated toward zero to Q40.
        p = int(Fraction(coordinate)*Fraction(frequency)*(1 << 40))
    else: p = point*fixed(frequency)
    return (p + fixed(offset)) & PHASE_MASK


def perlin(layer, at, scale):
    phases = [phase(p, layer['frequency'], o, scale) for p, o in zip(at, layer['offsets'])]
    cells = [p >> 40 for p in phases]
    fractions = [add(f32(((p >> 32) & 255)/256.), f32(f32(p & MASK)/(1 << 40))) for p in phases]
    perm = layer['permutation']
    def corner(dx, dy, dz):
        h = perm[(perm[(perm[(cells[0]+dx)&255]+cells[1]+dy)&255]+cells[2]+dz)&255] & 15
        p = [f32(f-v) for f, v in zip(fractions, (dx,dy,dz))]
        g = GRADIENTS[h]
        return add(add(mul(g[0],p[0]),mul(g[1],p[1])),mul(g[2],p[2]))
    x, y, z = map(fade, fractions)
    return lerp(lerp(lerp(corner(0,0,0),corner(1,0,0),x),lerp(corner(0,1,0),corner(1,1,0),x),y),
                lerp(lerp(corner(0,0,1),corner(1,0,1),x),lerp(corner(0,1,1),corner(1,1,1),x),y),z)


def noise(programs, index, at):
    result = 0.
    p = programs[index]
    for layer in p['layers']:
        result = add(result, mul(f32(layer['amplitude']), perlin(layer, at, f32(p.get('coordinate_scale', 0.)))))
    return result


def index(value, count): return int(mul(min(f32(.9999), max(0., f32(add(1.,value)/2.))),f32(count)))


def sample(p, rng, at, world, programs, optional=True):
    def nested(p, optional=True): return sample(p, rng, at, world, programs, optional)
    def choose(states): return states[rng.integer(0,len(states)-1)] if states else None
    kind = p['type']
    if kind == 'state': result = p['material']
    elif kind == 'weighted':
        n = rng.next() % sum(e['weight'] for e in p['entries'])
        for e in p['entries']:
            if n < e['weight']:
                result = nested(e['provider'], False); break
            n -= e['weight']
        else: raise AssertionError('invalid weight selection')
    elif kind in ('randomized_int','rotated','copy_properties'):
        if kind == 'rotated': direction = p['direction'] if 'direction' in p else rng.integer(0,5)
        material = nested(p['source'], False)
        variant = next((v for v in p['variants'] if v['source'] == material), None)
        if variant is None:
            result = (world.material(*at) or 0) if kind == 'copy_properties' else material
        elif kind == 'randomized_int':
            result = material if variant.get('passthrough',False) else variant['states'][int_provider(p['values'],rng)-variant['minimum']]
        elif kind == 'rotated': result = variant['states'][direction]
        else: result = dict(variant.get('replacements',[])).get(world.material(*at) or 0, material)
    elif kind == 'rule_based':
        result = None
        for rule in p['rules']:
            if predicate(rule['predicate'],world,at) is True:
                result = nested(rule['provider'])
                if result is not None: break
        if result is None and 'fallback' in p: result = nested(p['fallback'])
    elif kind == 'random_block': result = choose(p['states'])
    elif kind == 'noise': result = p['states'][index(noise(programs,p['program'],at),len(p['states']))]
    elif kind == 'dual_noise':
        a, b = p['variety']
        count = int(min(1.,max(0.,(noise(programs,p['slow'],at)+1.)/2.))*(b+1-a)+a)
        selected = index(noise(programs,p['fast'],at),count)
        shifted = (signed((at[0]+selected*54545)&MASK),at[1],signed((at[2]+selected*34234)&MASK))
        result = p['states'][index(noise(programs,p['slow'],shifted),len(p['states']))]
    elif kind == 'noise_threshold':
        if noise(programs,p['program'],at) < f32(p['threshold']): result = choose(p['low_states'])
        elif f32(rng.unit()) < f32(p['high_chance']): result = choose(p['high_states'])
        else: result = p['default_state']
    else: raise AssertionError(kind)
    return (world.material(*at) or 0) if result is None and not optional else result


def providers(value):
    result = []
    if isinstance(value, dict):
        if value.get('type') in KINDS: result.append(value)
        for child in value.values(): result.extend(providers(child))
    elif isinstance(value, list):
        for child in value: result.extend(providers(child))
    return result


def expected(source):
    ps, ns = providers(source.get('decorations',[])), source.get('decoration_provider_noises',[])
    results = []
    for i, p in enumerate(ps):
        for context in range(8):
            w = World(source,context)
            ox, oz = origin(context)
            at = (signed((ox+(32 if context==7 else context*3 % 32)) & MASK), context*17-64,
                  signed((oz+context*5 % 32)&MASK))
            seed = (((0x80000000 ^ (i*7919))&MASK)<<32) | mix_hash((context*811+i)&MASK)
            for edited in (False,True):
                if edited: w.put(*at,(i+3)%len(source['materials']))
                for optional in (True,False):
                    rng = Random(seed)
                    result = sample(p,rng,at,w,ns,optional)
                    results.extend([MASK if result is None else result, rng.state >> 32, rng.state & MASK])
    values = [noise(ns,i,(signed(mix_hash(j*2654435769&MASK)),j*29-512,signed(mix_hash(j*2246822507&MASK))))
              for i in range(len(ns)) for j in range(64)]
    return ps, results, values


def check(data, source):
    ps, reference, values = expected(source)
    count, n = struct.unpack_from('>2I',data)
    assert (count,n)==(len(ps),len(values)), (count,n,len(ps),len(values))
    assert len(data)==8+len(reference)*4+len(values)*4
    got = list(struct.unpack_from('>'+str(len(reference))+'I',data,8))
    if got != reference:
        at = next(i for i,(a,b) in enumerate(zip(got,reference)) if a!=b)
        p, local = divmod(at,8*4*3)
        raise AssertionError(dict(provider=p,spec=ps[p],context=local//12,lane=(local%12)//3,
                                  field=local%3,observed=got[at],expected=reference[at]))
    observed = struct.unpack_from('>'+str(n)+'f',data,8+len(reference)*4) if n else ()
    differences = [abs(a-b) for a,b in zip(observed,values)]
    assert all(math.isfinite(a) for a in observed)
    assert max(differences,default=0.) <= 0.000004, max(differences)
    return dict(providers=count,decisions=count*32,noise_queries=n,max_noise_error=max(differences,default=0.),sha256=hashlib.sha256(data).hexdigest())


def fixtures(permutation):
    source, _, _ = placement_fixtures(permutation)
    state = lambda m: dict(type='state',material=m)
    yes = {'type':'true'}
    material = lambda ids, offset=(0,0,0): dict(type='material',offset=list(offset),allowed=ids)
    nullable = dict(type='rule_based',rules=[])
    variants = [dict(source=1,minimum=-1,states=[2,3,4])]
    rng = random.Random(8177)
    shuffled = list(range(256)); rng.shuffle(shuffled)
    def layer(frequency, offset=(0.,0.,0.), amplitude=.75):
        return dict(frequency=float(frequency),amplitude=amplitude,offsets=list(offset),permutation=shuffled[:])
    source['decoration_provider_noises'] = [
        dict(layers=[layer(.005, (194.4869384765625,162.6790771484375,181.18211364746094)),layer(.010181268654606134,(.125,.25,.5),.125)]),
        dict(layers=[layer(.0010000000474974513, (32.25,120.5,255.875))],coordinate_scale=.125),
        dict(layers=[]),
        dict(layers=[layer(-.03125,(-1.125,-256.5,257.875),-.25)]),
        dict(layers=[layer(1.e-300,(0.,0.,0.))]),
        dict(layers=[layer(1.e200,(-1.e200,1.e200,1.e-300))],coordinate_scale=1.e-10),
        dict(layers=[layer(2.**-41,(2.**-41,-2.**-41,255.99999999999955))]),
        dict(layers=[layer(2.**-32,(.5,.25,.125))],coordinate_scale=.0033333333333333335)]
    ps = [state(1),dict(type='weighted',entries=[dict(weight=0,provider=state(0)),dict(weight=MASK,provider=state(1)),dict(weight=MASK,provider=state(2))]),
          dict(type='randomized_int',source=state(1),values=dict(type='uniform',min_inclusive=-1,max_inclusive=1),variants=variants),
          dict(type='randomized_int',source=state(1),values=9,variants=[dict(source=1,minimum=0,states=[1],passthrough=True)]),
          dict(type='rotated',source=dict(type='weighted',entries=[dict(weight=1,provider=state(1))]),variants=[dict(source=1,states=[1,2,3,4,5,6])]),
          dict(type='rotated',source=state(1),direction=5,variants=[dict(source=1,states=[1,2,3,4,5,6])]),
          dict(type='copy_properties',source=state(1),variants=[dict(source=1,states=[1,2,3],replacements=[[0,2],[3,3]])]),
          nullable,dict(type='random_block',states=[]),dict(type='random_block',states=[0,1,2,7]),
          dict(type='rule_based',rules=[dict(predicate=yes,provider=dict(type='random_block',states=[])),dict(predicate=yes,provider=state(3))],fallback=state(4)),
          dict(type='rule_based',rules=[dict(predicate=material([0]),provider=state(2)),dict(predicate=material([1]),provider=state(3))]),
          dict(type='rule_based',rules=[dict(predicate=material([0],(1,0,0)),provider=state(2))],fallback=state(7)),
          dict(type='randomized_int',source=nullable,values=1,variants=[]),
          dict(type='rotated',source=nullable,variants=[]),
          dict(type='copy_properties',source=dict(type='randomized_int',source=nullable,values=0,variants=[dict(source=1,minimum=0,states=[2])]),variants=[])]
    for i in range(len(source['decoration_provider_noises'])):
        ps.extend([dict(type='noise',program=i,states=list(range(8))),
                   dict(type='dual_noise',fast=0,slow=i,variety=[1,3],states=list(range(8))),
                   dict(type='noise_threshold',program=i,threshold=0.,high_chance=.33333334,default_state=1,low_states=[2,3],high_states=[4,5])])
    for chance in (0.,1.): ps.append(dict(type='noise_threshold',program=2,threshold=-1.,high_chance=chance,default_state=1,low_states=[2],high_states=[3]))
    ps.append(dict(type='dual_noise',fast=2,slow=2,variety=[1,64],states=list(range(8))))
    source['decorations'] = [dict(kind='simple_block',salt=i,placement=[],provider=p) for i,p in enumerate(ps)]
    source['biomes'] = [dict(decorations=list(range(len(ps))))]
    source['ordered_decorations'] = True
    empty = copy.deepcopy(source); empty['decorations']=[];empty['biomes']=[{'decorations':[]}];empty['decoration_provider_noises']=[]
    invalid = []
    def bad(p):
        x = copy.deepcopy(source);x['decorations'][0]['provider']=p;invalid.append(x)
    for p in [state(8),dict(type='weighted',entries=[]),dict(type='weighted',entries=[dict(weight=0,provider=state(1))]),
              dict(type='weighted',entries=[dict(weight=1,provider=nullable)]),
              dict(type='weighted',entries=[dict(weight=1,provider={'type':'unsupported'})]),
              dict(type='randomized_int',source=nullable,values=2,variants=variants),
              dict(type='randomized_int',source=state(1),values=2,variants=variants),
              dict(type='randomized_int',source=state(1),values=0,variants=[]),
              dict(type='randomized_int',source=state(1),values=0,variants=[dict(source=1,states=[2],passthrough=True)]),
              dict(type='rotated',source=state(1),direction=6,variants=[dict(source=1,states=list(range(6)))]),
              dict(type='rotated',source=state(1),variants=[dict(source=1,states=[1])]),
              dict(type='copy_properties',source=state(1),variants=[dict(source=1,states=[1,2],replacements=[[1,2],[0,1]])]),
              dict(type='copy_properties',source=state(1),variants=[dict(source=1,states=[1],replacements=[[0,2]])]),
              dict(type='noise',program=8,states=[1]),dict(type='noise',program=0,states=[]),
              dict(type='dual_noise',fast=0,slow=0,variety=[0,3],states=[1]),
              dict(type='dual_noise',fast=0,slow=0,variety=[4,3],states=[1]),
              dict(type='dual_noise',fast=0,slow=0,variety=[1,65],states=[1]),
              dict(type='noise_threshold',program=0,threshold=1.1,high_chance=.5,default_state=1,low_states=[1],high_states=[2]),
              dict(type='noise_threshold',program=0,threshold=0.,high_chance=1.1,default_state=1,low_states=[1],high_states=[2]),
              dict(type='rule_based',rules=[dict(predicate=material([8]),provider=state(1))]),
              dict(type='copy_properties',source=state(1),variants=[dict(source=1,states=[1],replacements=[[0]])])]: bad(p)
    for field, value in [('permutation',[0]*256),('permutation',list(range(255))),('offsets',[0.,0.]),('amplitude',1.e100)]:
        x=copy.deepcopy(source);x['decoration_provider_noises'][0]['layers'][0][field]=value;invalid.append(x)
    for scale in (0.,-1.,1.e100):
        x=copy.deepcopy(source);x['decoration_provider_noises'][0]['coordinate_scale']=scale;invalid.append(x)
    return source, empty, invalid


def semantic_checks(source):
    ps, rows, _ = expected(source)
    roots = [r['provider'] for r in source['decorations']]
    def row(root, context, lane):
        i = next(i for i,p in enumerate(ps) if p is roots[root])
        at = (i*32+context*4+lane)*3
        return i, rows[at:at+3]
    draws = {0:0,1:1,2:1,3:0,4:2,5:0,6:0,7:0,8:0,9:1,10:0,13:0,14:1,15:0,
             len(roots)-3:1,len(roots)-2:2,len(roots)-1:0}
    for root, count in draws.items():
        for context in range(8):
            for lane in range(4):
                i, observed = row(root,context,lane)
                seed = (((0x80000000 ^ (i*7919))&MASK)<<32) | mix_hash((context*811+i)&MASK)
                state = (seed+count*0x9e3779b97f4a7c15)&MASK64
                assert observed[1:]==[state>>32,state&MASK], (root,context,lane,count)
    for context in range(8):
        assert row(7,context,0)[1][0]==MASK, 'nullable getOptionalState must stay None'
        assert row(8,context,0)[1][0]==MASK, 'empty random blocks consume no draw'
        assert row(10,context,0)[1][0]==3, 'a nullable matching rule must allow later rules'
        i, observed = row(4,context,0)
        rng = Random((((0x80000000 ^ (i*7919))&MASK)<<32)|mix_hash((context*811+i)&MASK))
        assert observed[0]==rng.integer(0,5)+1, 'direction draw must precede nested weighted selection'
        assert row(len(roots)-3,context,0)[1][0]==1
        assert row(len(roots)-2,context,0)[1][0]==3
        assert row(len(roots)-1,context,0)[1][0]==4
    assert {row(6,c,l)[1][0] for c in range(8) for l in range(4)}=={1,2,3}, 'copy properties sees current material'
    return dict(full_64bit_weights='pass',optional_rule_fallthrough='pass',nullable_substrate_fallback='pass',
                passthrough_draws='pass',rotation_draw_order='pass',live_property_copy='pass',threshold_draws='pass',
                maximum_dual_variety='pass',draw_count_controls=len(draws)*8*4)


def exercise(binary, folder, name, source, gpu, threads=2):
    path=folder/f'{name}.rbp';path.write_bytes(fixture_wire(source,True))
    output=folder/f'{name}-{int(gpu)}-{threads}.bin'
    try: run=command(['nice','-n','10',binary,path,output,'--threads',threads,'--gpu','on' if gpu else 'off'],timeout=300)
    except subprocess.CalledProcessError as e: raise AssertionError((name,gpu,e.returncode,e.stderr)) from e
    return check(output.read_bytes(),source), output.read_bytes(),run.stderr


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile',action='append',type=Path,required=True)
    parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true')
    args=parser.parse_args()
    folder=ROOT/'build/bend/block-provider-tests';folder.mkdir(parents=True,exist_ok=True)
    binary=ROOT/'build/bend/block-providers';driver=ROOT/'bend/tests/block-providers.bend'
    if not args.skip_build: build(DEFAULT_BEND,binary,driver)
    profiles=[(f'profile-{i}',json.loads(p.read_text())) for i,p in enumerate(args.profile)]
    fixture, empty, invalid=fixtures(profiles[0][1]['decoration_noise']['permutation'])
    profiles += [('fixture',fixture),('empty',empty)]
    assert set(p['type'] for p in providers(fixture['decorations']))==set(KINDS)
    semantics=semantic_checks(fixture)
    runs=[];outputs={}
    for gpu in (False,True):
        for name,source in profiles:
            checked,data,_=exercise(binary,folder,name,source,gpu)
            if not gpu: outputs[name]=data
            else: assert data==outputs[name], (name,'CPU/Metal outputs differ')
            runs.append(dict(profile=name,gpu_required=gpu,**checked))
    one,data,_=exercise(binary,folder,'fixture-one-thread',fixture,False,threads=1)
    assert data==outputs['fixture']
    for i,source in enumerate(invalid):
        path=folder/f'invalid-{i}.rbp';path.write_bytes(fixture_wire(source,True))
        output=folder/f'invalid-{i}.bin'
        run=subprocess.run(['nice','-n','10',str(binary),str(path),str(output),'--threads','2','--gpu','off'],capture_output=True,text=True,timeout=300)
        assert run.returncode, (i,'accepted invalid provider')
        assert 'invalid block provider' in run.stderr or 'invalid provider noise' in run.stderr, (i,run.stderr)
        assert not output.exists(), i
    observed=[]
    if args.prove_metal:
        probe=ROOT/'build/bend/block-providers-observer'
        compile_metal_observer(DEFAULT_BEND,driver,probe,gpu_archive=binary.with_suffix('.gpu'))
        assert probe.with_suffix('.gpu').read_bytes()==binary.with_suffix('.gpu').read_bytes()
        for name,source in profiles:
            checked,data,stderr=exercise(probe,folder,'observer-'+name,source,True)
            assert data==outputs[name]
            commands=[float(n) for n in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)]
            # Empty inputs legitimately have no GPU work to dispatch.
            if checked['providers'] or checked['noise_queries']: assert commands, (name,'no Metal command buffers')
            observed.append(dict(profile=name,device_ms=commands,command_buffers=len(commands),sha256=checked['sha256']))
    source_paths=[ROOT/'bend'/f'{name}.bend' for name in ('provider_noise','registry_provider_noise','block_provider','registry_block_provider','tests/block-providers')]+[Path(__file__)]
    report=dict(scope=__doc__.strip(),bend_version=command([DEFAULT_BEND,'version']).stdout.strip(),runs=runs,
                provider_kinds=list(KINDS),semantic_controls=semantics,malformed_rejected=len(invalid),cpu_gpu_bytes_equal=True,one_cpu_thread='pass',
                metal_observation=observed,profiles={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in args.profile},
                sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in source_paths},
                native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),gpu_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-block-providers-correctness.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ('runs','malformed_rejected','metal_observation')},indent=2))


if __name__=='__main__': main()
