#!/usr/bin/env python3
"""Check whole MCA files written by Bend from resident generated terrain.

This is unlit base/material output, not full generator parity or a throughput
comparison. Independent zlib/NBT/MCA decoding covers every one of 1024 records;
selected chunks are compared byte-for-byte with the existing chunk command and
their generated block/query/heightmap data. Minecraft decoding is a separate
Gradle task. Optional loaded registry profiles exercise actual registered data.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import signal
import struct
import time
import zlib

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_generated_chunks import fixtures as chunk_fixtures, request as chunk_request, validate
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_formats import read_region
from test_bend_chunks import palette, MAP_NAMES
from test_bend_surface import tile_bytes, query_bytes
from test_bend_material_columns import decode
from test_bend_density_lattice import LOW, HIGH
from test_bend_noise import MASK


def request(x, z, path, low=LOW, high=HIGH, version=5023, timestamp=0x80000001):
    codes = [ord(c) for c in str(path.resolve())]
    return struct.pack('>7I',x&MASK,z&MASK,low,high,version,timestamp,len(codes))+struct.pack(f'>{len(codes)}I',*codes)


def fixtures(folder):
    sources, _ = chunk_fixtures(folder/'chunks')
    result=[]
    for name, source, _ in sources:
        if name not in ('ramp','islands'): continue
        source=copy.deepcopy(source);source.update(geology_min_y=-16,geology_height=32)
        path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,True))
        result.append((name,source,path))
    return result


def compressed(data, record):
    at=record['offset']*4096;size=int.from_bytes(data[at:at+4],'big')
    return data[at+5:at+4+size]


def exercise(binary, profiles, gpu, folder, version, repeat=True):
    worker=Worker(binary,gpu);rows=[];dispatches=0;hashes=[];blocks=0
    def call(op,data=b'',status=0):
        nonlocal dispatches
        if op in (13,16,22) and status==0:dispatches+=1
        return worker.call(op,data,status=status)
    def reject(data, code):
        assert call(28,data,1)==struct.pack('>I',code),code
    try:
        reject(b'',716)
        for name, source, wire in profiles:
            signal.alarm(1800);print(f'{"GPU" if gpu else "CPU"}: whole generated region {name}',flush=True)
            call(5,path_request(wire));call(11);call(19);call(21);call(9);call(15)
            reject(b'',731);call(25);reject(b'',731)
            rx,rz=(-1,0) if name=='ramp' else (58593,-58594) if name=='islands' else (-2,3)
            low,high=(MASK,0x80000000) if name=='islands' else (LOW,HIGH)
            ox,oz=rx*512,rz*512
            started=time.perf_counter()
            descriptor=call(18,tile_bytes((ox-1,oz-1,514,514,low,high)))
            call(13,descriptor);call(16,tile_bytes((ox,oz,512,512,low,high)));call(22)
            generation_ms=(time.perf_counter()-started)*1000
            print(f'  generated resident tile in {generation_ms:.1f} ms; serializing 1024 chunks',flush=True)
            out=folder/('gpu' if gpu else 'cpu')/name;out.mkdir(parents=True,exist_ok=True)
            destination=out/f'r.{rx}.{rz}.mca'
            bad=out/'should-not-exist.mca';valid=request(rx,rz,bad,low,high,version)
            for body,code in [(b'',603),(valid+b'\0',603),(valid[:-1],603),
                    (request(rx,rz,bad,low^1,high,version),732),(request(rx+1,rz,bad,low,high,version),732),
                    (request(4194304,0,bad,low,high,version),733),(request(-4194305,0,bad,low,high,version),733),
                    (valid[:24]+struct.pack('>I',0),603),(valid[:24]+struct.pack('>I',4097),603),
                    (valid[:24]+struct.pack('>2I',1,0),603),(valid[:24]+struct.pack('>2I',1,0xd800),603),
                    (valid[:24]+struct.pack('>2I',1,0x110000),603)]:
                reject(body,code);assert not bad.exists()
            started=time.perf_counter();ack=call(28,request(rx,rz,destination,low,high,version))
            write_ms=(time.perf_counter()-started)*1000;data=destination.read_bytes()
            print(f'  staged {len(data)} MCA bytes in {write_ms:.1f} ms; independently decoding',flush=True)
            assert struct.unpack('>2I',ack)==(1024,len(data)//4096)
            records=read_region(data);assert set(records)==set(range(1024))
            for slot,record in records.items():
                tag=record['tag'];cx,cz=rx*32+slot%32,rz*32+slot//32
                assert (tag['xPos'],tag['zPos'],tag['yPos'],tag['DataVersion'])==(cx,cz,source['geology_min_y']//16,version)
                assert tag['Status']=='minecraft:features' and tag['isLightOn']==0
                assert record['timestamp']==0x80000001
                assert len(tag['sections'])==source['geology_height']//16
                assert set(tag['Heightmaps'])==set(MAP_NAMES)
                for s,section in enumerate(tag['sections']):
                    assert section['Y']==source['geology_min_y']//16+s
                    assert 'SkyLight' not in section and 'BlockLight' not in section
                    # Decode every packed index independently, including entries
                    # crossing 64-bit words. Complete block comparison below.
                    palette(section['block_states'],4096,4);palette(section['biomes'],64,1)
            samples=(0,31,32,511,512,992,1023)
            for slot in samples:
                cx,cz=rx*32+slot%32,rz*32+slot//32
                raw=call(26,chunk_request(cx,cz,low,high,version))
                encoded=call(27,chunk_request(cx,cz,low,high,version))
                assert encoded==compressed(data,records[slot]) and zlib.decompress(encoded)==raw
                columns=decode(call(24,query_bytes([(cx*16+i%16,cz*16+i//16,low,high) for i in range(256)])))
                blocks+=validate(raw,source,columns,cx,cz,version)
            # A reordered read does not mutate the snapshot or output. Keep a
            # Unicode path to cover scalar path transport and a second full write.
            identical=True
            if repeat:
                again=out/f'repeat é 🌍.{rx}.{rz}.mca'
                assert call(28,request(rx,rz,again,low,high,version))==ack
                identical=again.read_bytes()==data;assert identical;again.unlink()
            digest=hashlib.sha256(data).hexdigest();hashes.append(digest)
            rows.append(dict(name=name,region=[rx,rz],chunks=1024,world_min=source['geology_min_y'],
                world_height=source['geology_height'],mca_bytes=len(data),sha256=digest,
                generation_response_host_ms=generation_ms,encode_compress_write_response_host_ms=write_ms,
                repeated_file_identical=identical if repeat else None))
        call(4);assert worker.process.wait(timeout=10)==0
        diagnostic=worker.process.stderr.read().decode()
        return dict(gpu_required=gpu,regions=rows,chunk_streams_decoded=len(rows)*1024,
            blocks_compared_to_generated_queries=blocks,requests_in_one_process=worker.id,
            expected_gpu_dispatches=dispatches,
            metal_commands_ms=[float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostic)],
            sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest())
    finally:signal.alarm(0);worker.close()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    p.add_argument('--skip-build',action='store_true');p.add_argument('--prove-metal',action='store_true')
    p.add_argument('--profile',nargs=2,action='append');p.add_argument('--data-version',type=int,default=5023)
    args=p.parse_args();folder=ROOT/'build/bend/generated-regions';folder.mkdir(parents=True,exist_ok=True)
    profiles=fixtures(folder);actual=[]
    for js,wire in args.profile or []:actual.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine';version='existing executable' if args.skip_build else build(args.bend,binary,ROOT/'bend/engine.bend')
    report=dict(scope='Whole generated unlit base/material MCA output; no full-world parity or performance comparison',
        timing_scope='Observational correctness-test response times; uncontrolled host load, not comparative benchmarks',
        bend_version=version,runs=[exercise(binary,profiles,False,folder,args.data_version),
                                 exercise(binary,profiles+actual,True,folder,args.data_version)])
    if args.prove_metal:
        probe=ROOT/'build/bend/generated-regions-metal-probe';compile_metal_observer(args.bend,ROOT/'bend/engine.bend',probe)
        # Reobserve synthetic full tiles plus the first actual registry profile.
        # Normal CPU/GPU-required checks above still cover every supplied input.
        # Avoid another complete repeated write of every expensive loaded tile.
        result=exercise(probe,profiles+actual[:1],True,folder,args.data_version,repeat=False)
        assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches']
        expected={r['name']:r['sha256'] for r in report['runs'][1]['regions']}
        assert all(r['sha256']==expected[r['name']] for r in result['regions'])
        report['actual_metal_proof']=result
    report['source_sha256']={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(w.read_bytes()).hexdigest()) for n,s,w in profiles+actual]
    path=ROOT/'build/bend/generated-region-tests.json';path.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: generated whole MCA regions; {path}')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('whole generated region timeout')));main()
