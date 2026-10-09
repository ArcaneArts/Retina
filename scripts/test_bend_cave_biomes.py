#!/usr/bin/env python3
"""Validate resident Bend cave-biome volumes, biome-specific carving and NBT.

Independent climate interval search covers vertical strata and registered
profiles. This is unlit component QA, not complete worldgen or a speed claim.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import signal

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_caves import fixtures as cave_fixtures, exercise
from test_bend_density import ins
from test_bend_engine import compile_metal_observer
from test_bend_registry_noise import fixture_wire
from test_bend_shoreline import source_profile


def strata(source):
    source=copy.deepcopy(source)
    base=source['biomes'][0]
    source['biomes']=[dict(copy.deepcopy(base),id=id,flags=0 if i==0 else 16,carvers=[])
        for i,id in enumerate(('minecraft:plains','minecraft:lush_caves','minecraft:dripstone_caves','minecraft:deep_dark'))]
    programs=source['registry_program']['programs']
    source['registry_program']['programs']=programs[:3]+[copy.deepcopy(programs[3]) for _ in source['biomes']]
    source['climate_targets']=[dict(min=[-1]*4,max=[1]*4,weirdness=[-1,1],depth=list(bounds),offset=0,biome=i)
        for i,bounds in enumerate(((-.25,.25),(.5,1.),(1.,1.5),(1.5,3.)))]
    source['registry_program']['programs'][0]=dict(nodes=[ins(0),ins(29,1),ins(0,p=(-.0625,0,0,0)),ins(6,1,2)],roots=[0,0,0,0,0,3])
    return source


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True)
    old=cave_fixtures(folder/'caves')
    source=strata(old[0][1])
    source['registry_program']['programs'][2]=dict(nodes=[ins(0,p=(1,0,0,0))],roots=[0])
    # Only the lush layer has a tunnel carver. All cave noise is zero so its
    # wide tunnels carve reliably; other layers stay solid despite proximity.
    carver=copy.deepcopy(old[2][1]['biomes'][0]['carvers'][0])
    carver.update(probability=1.,count=7.,thickness=5.,horizontal=2.,vertical=1.,floor=-1.)
    source['biomes'][1]['carvers']=[carver]
    for n in source['cave_noises']:n.update(amplitude=0.)
    result=[]
    def add(name,s):
        p=folder/(name+'.rbp');p.write_bytes(fixture_wire(s,True));result.append((name,s,p))
    add('strata',source)
    nonpower=copy.deepcopy(source);nonpower['registry_program']['terrain_cell']=[3,5]
    nonpower['registry_program']['programs'][1]=dict(nodes=[ins(29,0),ins(0,p=(.5,0,0,0)),ins(6,0,1),ins(0,p=(9,0,0,0)),ins(4,2,3),ins(29,1),ins(5,4,5)],roots=[6])
    add('non_power_biomes',nonpower)
    explicit=copy.deepcopy(source);explicit['registry_program']['surface'][2]=1
    explicit['registry_program']['programs'][1]=dict(nodes=[ins(0,p=(11,0,0,0))],roots=[0])
    add('explicit_biomes',explicit)
    absent=copy.deepcopy(source)
    for b in absent['biomes']:b['flags']=0
    add('no_cave_targets',absent)
    coastal=source_profile(folder/'shore')
    coastal.update(cave_noises=copy.deepcopy(source['cave_noises']),
        carveable=[i not in (0,coastal['water']) for i in range(len(coastal['materials']))],
        lava=coastal['stone'],lava_level=-13)
    for b in coastal['biomes']:b['carvers']=[]
    coastal['biomes'].append(dict(id='minecraft:lush_caves',flags=16,carvers=[]))
    coastal['climate_targets'].append(dict(min=[-1]*4,max=[1]*4,weirdness=[-1,1],depth=[.5,3.],offset=0,biome=4))
    climate=coastal['registry_program']['programs'][0];base=len(climate['nodes'])
    climate['nodes'] += [ins(29,1),ins(0,p=(-.0625,0,0,0)),ins(6,base,base+1)]
    climate['roots'][5]=base+2
    coastal['registry_program']['programs'][2]=dict(nodes=[ins(0,p=(1,0,0,0))],roots=[0])
    coastal['registry_program']['programs'].append(copy.deepcopy(coastal['registry_program']['programs'][3]))
    add('shore_surface_biomes',coastal)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true')
    parser.add_argument('--metal-probe',type=Path);parser.add_argument('--profile',nargs=2,action='append')
    args=parser.parse_args();profiles=fixtures(ROOT/'build/bend/cave-biome-profiles')
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    report=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,False),exercise(binary,profiles,True)])
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/cave-biome-metal-probe'
        if args.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        result=exercise(probe,profiles,True)
        assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches']
        assert result['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=result
        report['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest()
    report['bend_version']=__import__('subprocess').check_output([str(DEFAULT_BEND),'version'],text=True).strip()
    output=ROOT/'build/bend/cave-biome-tests.json';output.write_text(json.dumps(report,indent=2)+'\n')
    print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('cave-biome test timeout')))
    main()
