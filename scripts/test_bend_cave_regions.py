#!/usr/bin/env python3
"""Decode complete Bend MCA output after GPU/CPU cave carving.

The analytic chamber crosses many chunks in a full 512x512 resident tile. All
1024 records are independently decoded; selected chunks must match resident
carved queries and heightmaps. This is unlit component output, not completed
generation or a Rust-versus-Bend benchmark.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import signal
import struct

import test_bend_generated_regions as regions
from test_bend_caves import fixtures as cave_fixtures, encode, Reference
from test_bend_cave_biomes import strata
from test_bend_density import ins
from test_bend_registry_noise import fixture_wire
from test_bend_compression import ROOT


class CaveWorker(regions.Worker):
    def call(self, opcode, data=b'', status=0, fragment=False):
        result=super().call(opcode,data,status,fragment)
        if opcode==25 and status==0:
            assert struct.unpack('>8I',super().call(30))[0]==6
        if opcode==22 and status==0:
            # Probe an interior chamber, protected foundation, intact roof and
            # distant outside point before and after the actual carving call.
            low,high=regions.LOW,regions.HIGH
            points=[(-768,-4,1792,low,high),(-768,-15,1792,low,high),
                    (-768,7,1792,low,high),(-1016,-4,1544,low,high)]
            before=list(struct.iter_unpack('>2I',super().call(23,encode(points))))
            assert all(present and material for present,material in before)
            ack=struct.unpack('>8I',super().call(33))
            assert ack==(0xfffffc00,0xfffffff0,1536,129,9,129,149769,4)
            carved=struct.unpack('>4I',super().call(35))
            assert carved[:3]==(512,512,262144)
            after=list(struct.iter_unpack('>2I',super().call(23,encode(points))))
            assert after[0]==(1,0),after
            assert after[1:]==before[1:],(before,after)
        return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metal-probe',type=Path)
    parser.add_argument('--biomes',action='store_true')
    args=parser.parse_args()
    folder=ROOT/('build/bend/cave-biome-regions' if args.biomes else 'build/bend/cave-regions');folder.mkdir(parents=True,exist_ok=True)
    _,source,_=regions.fixtures(folder/'fixtures')[0];source=copy.deepcopy(source)
    geology=cave_fixtures(folder/'caves')[0][1]
    source.update(sea_level=0,cave_noises=geology['cave_noises'],
        carveable=[i not in (0,source['water']) for i in range(len(source['materials']))],
        lava=source['stone'],lava_level=source['geology_min_y']+3)
    for biome in source['biomes']:biome['carvers']=[]
    source['registry_program']['programs'][1]=dict(nodes=[ins(29,1),ins(0,p=(8,0,0,0)),ins(5,1,0)],roots=[2])
    nodes=[];terms=[]
    def node(op,a=0,b=0,p=(0,0,0,0)):
        nodes.append(ins(op,a,b,p=p));return len(nodes)-1
    for axis,center,radius in [(0,-768.,160.),(1,-4.,5.),(2,1792.,160.)]:
        position=node(29,axis);c=node(0,p=(center,0,0,0));d=node(5,position,c)
        squared=node(6,d,d);scale=node(0,p=(1/(radius*radius),0,0,0));terms.append(node(6,squared,scale))
    xy=node(4,terms[0],terms[1]);xyz=node(4,xy,terms[2]);one=node(0,p=(1,0,0,0));root=node(5,xyz,one)
    source['registry_program']['programs'][2]=dict(nodes=nodes,roots=[root])
    biome_reference=None
    if args.biomes:
        source=strata(source);ref=Reference(source)
        ids={y:ref.node_biome((-1024,y,1536),regions.LOW,regions.HIGH) for y in range(-16,16,4)}
        # This analytic profile has X/Z-independent climate and a flat height.
        # Check every saved quart entry against independently computed Y strata.
        def biome_reference(x,y,z,low,high):
            assert (low,high)==(regions.LOW,regions.HIGH)
            id=ids[y];return 0 if id==0xffffffff else id
        assert set(biome_reference(-1024,y,1536,regions.LOW,regions.HIGH) for y in ids)=={0,1}
    wire=folder/'caves.rbp';wire.write_bytes(fixture_wire(source,True));profiles=[('caves',source,wire)]
    regions.Worker=CaveWorker;binary=ROOT/'build/bend/engine'
    report=dict(scope=__doc__.strip(),runs=[])
    for gpu in (False,True):
        run=regions.exercise(binary,profiles,gpu,folder,5023,repeat=False,biome_reference=biome_reference)
        run['expected_gpu_dispatches']+=3
        report['runs'].append(run)
    assert report['runs'][0]['sha256']==report['runs'][1]['sha256']
    if args.metal_probe:
        result=regions.exercise(args.metal_probe,profiles,True,folder,5023,repeat=False,biome_reference=biome_reference)
        result['expected_gpu_dispatches']+=3
        assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches']
        assert result['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=result
        report['metal_probe_sha256']=hashlib.sha256(args.metal_probe.read_bytes()).hexdigest()
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest()
    report['input_sha256']=hashlib.sha256(wire.read_bytes()).hexdigest()
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    output=ROOT/('build/bend/cave-biome-region-tests.json' if args.biomes else 'build/bend/cave-region-tests.json');output.write_text(json.dumps(report,indent=2)+'\n')
    print('PASS: complete carved MCA decoding;',output)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('carved region test timeout')))
    main()
