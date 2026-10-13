#!/usr/bin/env python3
"""Actual registered tree writers -> accepted overlay merge -> compressed MCA.

Tree placement and the accepted overlay merge run in one selected-backend
evaluation, including actual observed Metal. Serialization runs on Bend CPU.
Independent Python reconstructs all final blocks, biomes and six heightmaps.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

from test_bend_compression import ROOT, DEFAULT_BEND, build, command, metal_compiler_command
from test_bend_engine import compile_metal_observer
from test_bend_registry_noise import fixture_wire
from test_bend_trees import World, features, origin, point, plan, replay, Random, TRUNKS
from test_bend_chunks import palette, unpack, MAP_NAMES
from test_bend_formats import read_region
from test_bend_placement_merge import sources


def check(path,source,kind):
    trees=list(features(source['decorations']));recipe=next(f for f in trees if f['trunk_shape']==TRUNKS[kind-1])
    world=World(source,12);placed,writes=plan(recipe,Random(123),world,point(12));assert placed
    replay(writes,world);chunks=read_region(path.read_bytes());assert len(chunks)==4
    ox,oz=origin(0);count=0;changed=0;leaf_states=0
    expected_slots={((ox//16+dx)%32)+((oz//16+dz)%32)*32 for dz in range(2) for dx in range(2)}
    assert set(chunks)==expected_slots
    states=[({'id':s} if isinstance(s,str) else s) for s in source['materials']]
    for slot,record in chunks.items():
        tag=record['tag'];cx=ox//512*32+slot%32;cz=oz//512*32+slot//32
        assert (tag['xPos'],tag['zPos'],tag['yPos'],tag['DataVersion'])==(cx,cz,-4,5023)
        assert tag['Status']=='minecraft:features' and tag['isLightOn']==0
        assert len(tag['sections'])==24 and tag['block_entities']==tag['entities']==[]
        assert record['timestamp']==1700000000
        heights=[[0]*256 for _ in range(6)]
        for sy,section in enumerate(tag['sections']):
            assert section['Y']==sy-4 and 'SkyLight' not in section and 'BlockLight' not in section
            blocks=palette(section['block_states'],4096,4)
            for i,state in enumerate(blocks):
                cell=i%256;x,z=cx*16+cell%16,cz*16+cell//16;y=-64+sy*16+i//256
                material=world.material(x,y,z);assert material is not None
                assert state==states[material],(kind,slot,section['Y'],x,y,z,state,states[material])
                if (x,y,z) in world.patch:changed+=1
                if 'distance' in state.get('properties',{}):leaf_states+=1
                for map_ in range(6):
                    if source['heightmap_masks'][material]&(1<<map_):heights[map_][cell]=y+65
            biomes=palette(section['biomes'],64,1)
            for i,biome in enumerate(biomes):
                x,z=cx*16+i%4*4,cz*16+(i//4)%4*4
                col=world.column(x,z);assert biome==source['biomes'][col%len(source['biomes'])]['id']
            count+=4096
        for map_,name in enumerate(MAP_NAMES):assert unpack(tag['Heightmaps'][name],256,(384).bit_length())==heights[map_]
    assert changed>100 and leaf_states>30,(changed,leaf_states)
    # The anchor is on a chunk corner: the complete crown must serialize into
    # multiple chunk records with identical registered distance/axis properties.
    occupied_chunks={(x//16,z//16) for (x,y,z),m in world.patch.items() if world.flags(m)&(4|8)}
    assert len(occupied_chunks)>=2
    return dict(shape=recipe['trunk_shape'],chunks=len(chunks),blocks_checked=count,tree_voxels=changed,leaf_properties=leaf_states,
                occupied_chunks=len(occupied_chunks),heightmaps=24,bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def exercise(binary,folder,name,source,gpu):
    path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,True));output=folder/(name+('-metal' if gpu else '-cpu'))
    print(name, 'Metal tree + merge' if gpu else 'CPU',flush=True)
    run=command(['nice','-n','10',binary,path,output,'--threads','2','--gpu','on' if gpu else 'off'],timeout=1200)
    assert 'PASS: registered trees' in run.stdout
    paths=[Path(str(output)+f'.{i}.mca') for i in range(1,4)]
    return [p.read_bytes() for p in paths],[check(p,source,i) for i,p in enumerate(paths,1)],run.stderr


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,action='append',required=True)
    p.add_argument('--skip-build',action='store_true');p.add_argument('--prove-metal',action='store_true');args=p.parse_args()
    driver=ROOT/'bend/tests/tree-mca.bend';binary=ROOT/'build/bend/tree-mca';observer=ROOT/'build/bend/tree-mca-observer'
    folder=ROOT/'build/bend/tree-mca-tests';folder.mkdir(parents=True,exist_ok=True)
    if not args.skip_build:
        generated=binary.with_suffix('.c');build(DEFAULT_BEND,generated,driver);command(metal_compiler_command(generated,binary),timeout=600,cwd=ROOT)
        command(['nice','-n','10',binary,'--threads','2','--gpu-build'],timeout=1800,cwd=ROOT)
        if args.prove_metal:compile_metal_observer(DEFAULT_BEND,driver,observer,gpu_archive=binary.with_suffix('.gpu'))
    runs=[];observed=[]
    for i,path in enumerate(args.profile):
        source=json.loads(path.read_text());name=f'profile-{i}';cpu,record,_=exercise(binary,folder,name,source,False);runs.append(dict(profile=i,execution='CPU',cases=record))
        ox,oz=origin(0)
        for shape,data in enumerate(cpu,1):
            loader=ROOT/'build/bend/tree-mca-loader'/f'trees-{i}-{shape}';loader.mkdir(parents=True,exist_ok=True)
            (loader/f'r.{ox//512}.{oz//512}.mca').write_bytes(data)
        gpu,record,_=exercise(binary,folder,name,source,True);assert gpu==cpu;runs.append(dict(profile=i,execution='Metal tree + merge',cases=record))
        if args.prove_metal:
            assert observer.with_suffix('.gpu').read_bytes()==binary.with_suffix('.gpu').read_bytes()
            output,record,stderr=exercise(observer,folder,'observer-'+name,source,True);assert output==cpu
            times=[float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)];assert times
            observed.append(dict(profile=i,command_buffers=len(times),device_ms=times,output_sha256=[r['sha256'] for r in record]))
    paths=sources(driver)|{Path(__file__).resolve(),ROOT/'scripts/test_bend_trees.py'}
    report=dict(scope=__doc__.strip(),runs=runs,metal_observation=observed,cpu_gpu_bytes_equal=True,
                sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)},
                profiles={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in args.profile},native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),gpu_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-tree-mca-correctness.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))


if __name__=='__main__':main()
