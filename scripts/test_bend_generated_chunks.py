#!/usr/bin/env python3
"""Decode chunks made from Bend's resident generated blocks, not fixture blocks.

Independent NBT/zlib readers verify every block, quart biome and heightmap.
This component still lacks final caves, features and lighting; it is neither a
complete region workload nor a world-generation performance benchmark.
"""
import argparse
import copy
import hashlib
import json
import re
import signal
import struct
import time
from pathlib import Path

from test_bend_compression import ROOT, DEFAULT_BEND, build, verify_stream
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_material_columns import fixtures as block_fixtures, decode
from test_bend_surface import tile_bytes, query_bytes
from test_bend_chunks import read_nbt, palette, unpack, MAP_NAMES
from test_bend_noise import MASK
from test_bend_density_lattice import LOW, HIGH


def fixtures(folder):
    folder.mkdir(parents=True, exist_ok=True)
    existing, _ = block_fixtures(folder)
    profiles = []
    for name, original, _ in existing:
        if name not in ('ramp', 'islands', 'solid', 'empty', 'non_power_cells'):
            continue
        source = copy.deepcopy(original)
        source.update(geology_min_y=-32, geology_height=64, heightmap_masks=[0, 63, 63, 63, 63, 51])
        source['biomes'] = [dict(v, id='minecraft:plains' if i == 0 else 'minecraft:forest')
                            for i, v in enumerate(source['biomes'])]
        source['materials'][2] = {'id':'minecraft:grass_block', 'properties':{'snowy':'false'}}
        source['materials'][5] = {'id':'minecraft:water', 'properties':{'level':'0'}}
        path = folder/(name+'.rbp'); path.write_bytes(fixture_wire(source, name != 'ramp'))
        profiles.append((name, source, path))
    # Encoding must preserve Java UTF-16 units, including surrogate pairs,
    # unpaired surrogates and modified-UTF8 NUL. These names are codec tests,
    # deliberately excluded from Minecraft resource-identifier validation.
    unicode = copy.deepcopy(profiles[2][1])
    unicode['materials'][2]['properties'] = {'test\0é🌍':'value\ud800\0'}
    path = folder/'unicode.rbp'; path.write_bytes(fixture_wire(unicode, True))
    profiles.append(('unicode', unicode, path))
    invalid = []
    base = profiles[0][1]
    def bad(name, change):
        source = copy.deepcopy(base); change(source)
        path = folder/('invalid-'+name+'.rbp'); path.write_bytes(fixture_wire(source, True)); invalid.append((name, path))
    for name, value in [('missing',None),('short',[0]),('long',[0]*7),('negative',[-1]*6),('large',[64]*6),('float',[0.]*6)]:
        bad('masks_'+name, lambda s,v=value:s.pop('heightmap_masks') if v is None else s.__setitem__('heightmap_masks',v))
    for name, value in [('type',42),('missing',{}),('id',{'id':3}),('properties',{'id':'minecraft:dirt','properties':[]}),
                        ('property_value',{'id':'minecraft:dirt','properties':{'x':1}})]:
        bad('state_'+name, lambda s,v=value:s['materials'].__setitem__(2,v))
    for name, value in [('type',42),('missing',{}),('id',{'id':False})]:
        bad('biome_'+name, lambda s,v=value:s['biomes'].__setitem__(0,v))
    return profiles, invalid


def request(x,z,lo=LOW,hi=HIGH,version=5023):
    return struct.pack('>5I',x&MASK,z&MASK,lo,hi,version)


def state(value):
    return {'id':value} if isinstance(value,str) else value


def validate(raw, source, columns, x, z, version, biome_ids=None, fluid_materials=None):
    tag = read_nbt(raw); minimum = source['geology_min_y']; height = source['geology_height']
    assert (tag['xPos'],tag['zPos'],tag['yPos'],tag['DataVersion']) == (x,z,minimum//16,version)
    assert tag['Status'] == 'minecraft:features' and tag['isLightOn'] == 0
    assert tag['block_entities'] == tag['entities'] == []
    assert tag['structures'] == {'starts':{},'References':{}}
    assert tag['block_ticks'] == tag['fluid_ticks'] == tag['PostProcessing'] == []
    assert len(tag['sections']) == height//16
    masks = source['heightmap_masks']; heights = [[0]*256 for _ in range(6)]; count=0
    expected = [[0]*height for _ in range(256)]
    for c,col in enumerate(columns):
        assert col is not None
        for start,end,material in col['runs']: expected[c][start:end]=[material]*(end-start)
    for s,section in enumerate(tag['sections']):
        assert section['Y'] == minimum//16+s and 'SkyLight' not in section and 'BlockLight' not in section
        states = palette(section['block_states'],4096,4)
        for i,actual in enumerate(states):
            y=s*16+i//256; c=i%256; material=expected[c][y]
            assert actual == state(source['materials'][material]),(s,i,actual,material)
            for kind in range(6):
                if masks[material] & (1<<kind): heights[kind][c]=y+1
        biomes = palette(section['biomes'],64,1)
        for i,actual in enumerate(biomes):
            c=(i%4)*4+((i//4)%4)*4*16
            id=columns[c]['biome'] if biome_ids is None else biome_ids[s*64+i]
            assert actual == source['biomes'][id]['id'],(s,i,actual,id)
        count+=4096
    bits=height.bit_length()
    assert set(tag['Heightmaps']) == set(MAP_NAMES)
    for kind,name in enumerate(MAP_NAMES): assert unpack(tag['Heightmaps'][name],256,bits)==heights[kind],name
    # Every height-query value agrees with the actual integer solid surface.
    fluid_materials={source['water']} if fluid_materials is None else fluid_materials
    for c,col in enumerate(columns):
        solid=[i+1+minimum for i,m in enumerate(expected[c]) if m and m not in fluid_materials]
        assert col['height'] == (max(solid) if solid else minimum),(c,col['height'],solid[-1:])
    return count


def exercise(binary,profiles,invalid,gpu,folder,version):
    worker=Worker(binary,gpu);dispatches=0;hashes=[];records=[];checked=0
    def call(op,data=b'',status=0):
        nonlocal dispatches
        if status==0 and op in (13,16,22): dispatches+=2 if op==22 else 1
        return worker.call(op,data,status=status)
    def error(op,data,code): assert call(op,data,1)==struct.pack('>I',code),(op,code)
    def prepare(path):
        call(5,path_request(path));call(11);call(19);call(21);call(9);call(15)
    def tile(x,z,w,d,lo,hi):
        desc=call(18,tile_bytes((x-1,z-1,w+2,d+2,lo,hi)))
        call(13,desc);call(16,tile_bytes((x,z,w,d,lo,hi)));call(22)
    try:
        for op in (25,26,27): error(op,b'',716)
        for name,source,path in profiles:
            signal.alarm(600);print(f'{"GPU" if gpu else "CPU"}: generated {name} chunks',flush=True)
            call(5,path_request(path));call(11);error(25,b'',727);call(19);call(21);call(9);call(15)
            ack=struct.unpack('>2I',call(25));assert ack==(len(source['materials']),len(source['biomes']))
            error(26,request(0,0),731)
            coords=[(-2,3),(1875000,-1875001)] if name in ('ramp','islands') else [(-1,-1)]
            if name=='empty':coords.append((134217726,-134217727))
            times=[]
            for x,z in coords:
                # Offset the source tile by a block and cover adjacent chunks;
                # chunk extraction cannot assume tile/chunk origins coincide.
                lo,hi=(MASK,MASK) if x==134217726 else (LOW,HIGH)
                width=17 if x==134217726 else 33
                tile(x*16-1,z*16,width,16,lo,hi)
                queries=[(x*16+i%16,z*16+i//16,lo,hi) for i in range(256)]
                run_bytes=call(24,query_bytes(queries));columns=decode(run_bytes)
                begin=time.perf_counter();raw=call(26,request(x,z,lo,hi,version));elapsed=(time.perf_counter()-begin)*1000
                checked+=validate(raw,source,columns,x,z,version)
                encoded=call(27,request(x,z,lo,hi,version));verify_stream(encoded,raw)
                assert call(26,request(x,z,lo,hi,version))==raw
                assert call(27,request(x,z,lo,hi,version))==encoded
                assert call(24,query_bytes(queries))==run_bytes
                for op,data,code in [(26,b'',603),(27,request(x,z,lo,hi,version)+b'\0',603),
                        (26,request(x,z,lo^1,hi,version),732),(27,request(x,z,lo,hi^1,version),732),
                        (26,request(x,z-1,lo,hi,version),732),(26,request(134217728,0),733),
                        (26,request(-134217729,0),733),(25,b'\0',603)]: error(op,data,code)
                assert call(26,request(x,z,lo,hi,version))==raw
                if name!='unicode':
                    out=folder/('gpu' if gpu else 'cpu')/name;out.mkdir(parents=True,exist_ok=True)
                    stem=out/f'{x}.{z}';stem.with_suffix(stem.suffix+'.nbt').write_bytes(raw)
                    stem.with_suffix(stem.suffix+'.z').write_bytes(encoded)
                hashes.append(hashlib.sha256(raw+encoded).hexdigest())
                times.append(dict(chunk=[x,z],blocks=source['geology_height']*256,nbt_bytes=len(raw),zlib_bytes=len(encoded),
                    encode_response_host_ms=elapsed))
                # Catalog survives grid invalidation and same-profile reprepare.
                call(21);error(26,request(x,z,lo,hi,version),731);call(22)
                assert call(26,request(x,z,lo,hi,version))==raw
            records.append(dict(name=name,chunks=times))
        for name,path in invalid:
            signal.alarm(600);call(5,path_request(path));call(11);call(19);call(21);error(25,b'',730)
        # Vertical alignment is a serializer constraint, not a generation gate.
        source=copy.deepcopy(profiles[0][1]);source['geology_min_y']=-31
        path=folder/'unaligned.rbp';path.write_bytes(fixture_wire(source,True));prepare(path);call(25)
        tile(0,0,16,16,LOW,HIGH);error(26,request(0,0),733)
        # Catalog invalidates with a replacement material/numeric model.
        prepare(profiles[0][2]);tile(0,0,16,16,LOW,HIGH);error(26,request(0,0),731)
        call(25);call(26,request(0,0));call(19);error(26,request(0,0),731)
        call(4);assert worker.process.wait(timeout=10)==0
        stderr=worker.process.stderr.read().decode();metal=[float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)]
        return dict(gpu_required=gpu,profiles=records,blocks_verified=checked,invalid_catalogs_rejected=len(invalid),
            requests_in_one_process=worker.id,expected_gpu_dispatches=dispatches,metal_commands_ms=metal,
            sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest())
    finally:signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND);parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--profile',nargs=2,action='append')
    parser.add_argument('--data-version',type=int,default=5023);args=parser.parse_args()
    folder=ROOT/'build/bend/generated-chunks';profiles,invalid=fixtures(folder)
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine';version='existing executable' if args.skip_build else build(args.bend,binary,ROOT/'bend/engine.bend')
    report=dict(scope='Generated base/material chunks; complete region generation pending',bend_version=version,
        runs=[exercise(binary,profiles,invalid,False,folder,args.data_version),exercise(binary,profiles,invalid,True,folder,args.data_version)])
    if args.prove_metal:
        diagnostic=ROOT/'build/bend/generated-chunks-metal-probe';compile_metal_observer(args.bend,ROOT/'bend/engine.bend',diagnostic)
        result=exercise(diagnostic,profiles,invalid,True,folder,args.data_version)
        assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches']
        assert result['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=result
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,json_sha256=hashlib.sha256(json.dumps(s,ensure_ascii=True,sort_keys=True).encode()).hexdigest(),
        rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    path=ROOT/'build/bend/generated-chunk-tests.json';path.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: {report["runs"][0]["blocks_verified"]} generated blocks per backend; {path}')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('generated chunk test timeout')));main()
