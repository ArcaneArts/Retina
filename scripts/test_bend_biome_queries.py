#!/usr/bin/env python3
"""Final biome queries against independent climate/shore oracles and saved MCA.

No terrain generation is required by these queries. Analytic profiles cover
coasts, cave strata, signed quart cells and vertical bounds. Original registry
profiles are checked against existing generated chunk biome palettes. Timings
are functional observations, not a Rust/Bend performance comparison.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import random
import signal
import struct
import time
import zlib

from test_bend_compression import ROOT
from test_bend_vegetation_worker import ObservedWorker
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_cave_biomes import fixtures as cave_fixtures
from test_bend_shoreline import fixtures as shore_fixtures, Reference as ShoreReference
from test_bend_caves import Reference as CaveReference
from test_bend_binary import NbtReader
from test_bend_chunks import palette
from test_bend_placement_merge import sources


def payload(seed, points):
    words=[seed&0xffffffff,seed>>32,len(points),*(v&0xffffffff for p in points for v in p)]
    return struct.pack('>'+str(len(words))+'I',*words)


def query(call, seed, points):
    result=[]
    for start in range(0,len(points),64):
        batch=points[start:start+64]
        result.extend(struct.unpack('>'+str(len(batch))+'I',call(63,payload(seed,batch))))
    return result


def oracle(source, seed, points):
    shore=ShoreReference(source);cave=CaveReference(source)
    minimum=source['geology_min_y'];maximum=minimum+source['geology_height']
    bottom,top=(0,0) if source['registry_program']['surface'][2] else (minimum,maximum)
    lo,hi=seed&0xffffffff,seed>>32
    result=[]
    for x,y,z in points:
        x=x//4*4;y=max(minimum,min(maximum-1,y))//4*4;z=z//4*4
        id=cave.node_biome((x,y,z),lo,hi)
        if id==0xffffffff:
            axes=shore.oracle.evaluate(shore.model['programs'][0],(x,0,z),lo,hi)
            desc=(1,x-24,bottom,z-24,x+24,top,z+24,*source['registry_program']['terrain_cell'],lo,hi)
            id=shore.selected(x,z,desc,axes)[1]
        result.append(id)
    return result


def saved_samples(path, per_biome=12):
    """Decode one chunk at a time; keep bounded samples across each observed ID."""
    data=path.read_bytes();samples=defaultdict(list);seen=defaultdict(int);rng=random.Random(70133)
    chunks=0
    for slot in range(1024):
        loc=int.from_bytes(data[slot*4:slot*4+4],'big')
        if not loc:continue
        at=(loc>>8)*4096;length=int.from_bytes(data[at:at+4],'big')
        assert data[at+4]==2
        raw=zlib.decompress(data[at+5:at+4+length]);reader=NbtReader(raw)
        assert reader.number('>B')==10 and reader.text()==''
        tag=reader.tag(10);assert reader.offset==len(raw);chunks+=1
        for section in tag['sections']:
            if 'biomes' not in section:continue
            for i,id in enumerate(palette(section['biomes'],64,1)):
                point=(tag['xPos']*16+(i%4)*4,section['Y']*16+(i//16)*4,tag['zPos']*16+((i//4)%4)*4)
                seen[id]+=1;row=(point,id)
                if len(samples[id])<per_biome:samples[id].append(row)
                else:
                    replace=rng.randrange(seen[id])
                    if replace<per_biome:samples[id][replace]=row
    return [p for name in sorted(samples) for p in samples[name]],dict(seen),chunks


def exercise(args):
    folder=args.output.parent;folder.mkdir(parents=True,exist_ok=True)
    worker=ObservedWorker(args.binary,args.gpu,folder/('query-gpu.log' if args.gpu else 'query-cpu.log'),'4GB')
    report=dict(scope=__doc__.strip(),gpu_required=args.gpu,cpu_threads=2,analytic=[],saved=[])
    def call(op,data=b'',status=0):
        signal.alarm(1800)
        return worker.call(op,data,status=status)
    def prepare(source):
        path=folder/'query-profile.rbp';path.write_bytes(fixture_wire(source,True))
        call(5,path_request(path))
        for op in (11,9,15):call(op)
    try:
        assert call(63,payload(0,[]),1)==struct.pack('>I',881)
        fixtures=cave_fixtures(folder/'analytic-caves')
        fixtures += [f for f in shore_fixtures(folder/'analytic-shores') if f[0] in ('coastal','no_shores','non_power_cells','explicit_height','floating_intervals')]
        for name,source,_ in fixtures:
            print('Analytic',name,flush=True);prepare(source)
            assert call(63,payload(1,[]))==b''
            points=[(x,y,z) for x,z in [(-20,-20),(-3,-3),(0,0),(7,7),(12,20),(20,-20),(29999003,-29999997),(-29999999,29999001)]
                    for y in (-99,-16,-12,-8,-4,0,31,99)]
            rows=[]
            for seed in (123456789,0x80000001ffffffff):
                actual=query(call,seed,points);wanted=oracle(source,seed,points)
                assert actual==wanted,(name,seed,[(p,a,b) for p,a,b in zip(points,actual,wanted) if a!=b][:8])
                assert query(call,seed,list(reversed(points)))==list(reversed(actual))
                rows.append(dict(seed=str(seed),points=len(points),ids=sorted(set(actual))))
            # The registry is replaced at each iteration. No previous profile's
            # result or lattice may determine these otherwise identical inputs.
            report['analytic'].append(dict(name=name,rows=rows))
        invalid=[b'',b'\x01',payload(1,[])+b'\x00',payload(1,[(0,0,0)])[:-1]]
        for data in invalid:assert call(63,data,1)==struct.pack('>I',603)
        assert call(63,payload(1,[(0,0,0)]*65),1)==struct.pack('>I',887)
        report['invalid_rejected']=len(invalid)+1
        for profile,region in args.saved:
            source=json.loads(profile.read_text());prepare(source)
            rows,counts,chunks=saved_samples(region);points=[p for p,_ in rows]
            names=[source['biomes'][id]['id'] for id in query(call,123456789,points)]
            wanted=[id for _,id in rows]
            assert names==wanted,[(p,a,b) for p,a,b in zip(points,names,wanted) if a!=b][:12]
            # Raw positions within a quart must resolve to the stored anchor.
            shifted=[(x+3,y+3,z+3) for x,y,z in points]
            assert query(call,123456789,shifted)==query(call,123456789,points)
            report['saved'].append(dict(profile_sha256=hashlib.sha256(profile.read_bytes()).hexdigest(),
                region_sha256=hashlib.sha256(region.read_bytes()).hexdigest(),chunks_decoded=chunks,
                biome_counts=counts,points_checked=len(points)*2))
            print('Saved',region,'PASS',len(points)*2,'points',flush=True)
        call(4);worker.close();signal.alarm(0)
    finally:
        if worker.process.poll() is None:worker.process.kill();worker.process.wait(timeout=10)
        worker.trace.close();signal.alarm(0)
    report['binary_sha256']=hashlib.sha256(args.binary.read_bytes()).hexdigest()
    if args.binary.with_suffix('.gpu').exists():report['gpu_archive_sha256']=hashlib.sha256(args.binary.with_suffix('.gpu').read_bytes()).hexdigest()
    report['sources']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources(ROOT/'bend/engine.bend')}
    report['test_sha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    print('PASS:',args.output,flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,required=True);parser.add_argument('--gpu',action='store_true')
    parser.add_argument('--saved',nargs=2,type=Path,action='append',default=[],metavar=('PROFILE','MCA'))
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('biome query timeout')))
    exercise(args)
