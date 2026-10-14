#!/usr/bin/env python3
"""Real loaded terrain -> caves/ores/vegetation/weather -> streamed MCA.

Only the pending structure field is omitted, as in the playable bridge. No
analytic terrain/profile changes. This checks current feature integration and
bounded output; unlit output is not final generator parity or a lit Rust/Bend
throughput comparison. RSS is sampled from the worker, not total GPU memory.
"""
import argparse,hashlib,json,signal,struct,subprocess,threading,time
from pathlib import Path
from test_bend_compression import ROOT
from test_bend_engine import Worker
from test_bend_vegetation_worker import ObservedWorker
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_surface import tile_bytes,query_bytes
from test_bend_generated_regions import request,compressed
from test_bend_generated_chunks import request as chunk_request
from test_bend_material_columns import decode
from test_bend_formats import read_region
from test_bend_chunks import palette,MAP_NAMES
from test_bend_placement_merge import sources


def key(state):
    if isinstance(state,str):return state,()
    return state['id'],tuple(sorted(state.get('properties',{}).items()))


def check(data,source,rx,rz,size):
    records=read_region(data);expected={(z*32+x) for z in range(size//16) for x in range(size//16)}
    assert set(records)==expected
    materials={key(m) for m in source['materials']};biomes={b['id'] for b in source['biomes']}
    for slot,r in records.items():
        t=r['tag'];assert (t['xPos'],t['zPos'],t['yPos'],t['DataVersion'])==(rx*32+slot%32,rz*32+slot//32,source['geology_min_y']//16,5023)
        assert t['Status']=='minecraft:features' and t['isLightOn']==0 and set(t['Heightmaps'])==set(MAP_NAMES)
        assert len(t['sections'])==source['geology_height']//16
        for section in t['sections']:
            states=section['block_states']
            assert all(key(s) in materials for s in states['palette'])
            palette(states,4096,4) # independently unpack/bounds-check every index
            assert all(b in biomes for b in palette(section['biomes'],64,1))
    return records


def exercise(binary,path,gpu,folder,size,gpu_memory='on'):
    source=json.loads(path.read_text());source.pop('structures',None);folder.mkdir(parents=True,exist_ok=True)
    inp=folder/'profile.rbp';inp.write_bytes(fixture_wire(source,True));w=ObservedWorker(binary,gpu,folder/'worker.log',gpu_memory)
    rx,rz=-1,0;x,z=rx*512,rz*512;lo,hi=123456789,0;output=folder/f'r.{rx}.{rz}.mca'
    output.unlink(missing_ok=True)
    stages=[];peak=[0];growth=[];stopped=threading.Event();started=time.monotonic()
    def sample():
        while not stopped.wait(.5):
            try:
                p=subprocess.run(['ps','-p',str(w.process.pid),'-o','rss='],capture_output=True,text=True,timeout=2)
                if p.stdout.strip():peak[0]=max(peak[0],int(p.stdout.strip())*1024)
                if output.exists():
                    n=output.stat().st_size
                    if not growth or growth[-1]['bytes']!=n:growth.append(dict(elapsed_seconds=time.monotonic()-started,bytes=n))
            except (OSError,ValueError,subprocess.TimeoutExpired):pass
    monitor=threading.Thread(target=sample,daemon=True);monitor.start()
    def call(name,op,data=b''):
        before=len(w.dispatches())
        signal.alarm(3600);print('START',name,flush=True);start=time.monotonic();out=w.call(op,data)
        elapsed=(time.monotonic()-start)*1000;stages.append(dict(name=name,opcode=op,wall_ms=elapsed,sampled_peak_rss=peak[0],observed_metal_ms=w.dispatches()[before:]));print('DONE',name,round(elapsed,2),'ms',flush=True);return out
    try:
        call('profile',5,path_request(inp))
        for name,op in [('density model',11),('materials',19),('palette',21),('geology',30),('aquifers',37),('climate',9),('surface bounds',15),('chunk catalog',25)]:call(name,op)
        pad=32;source_width=size+pad*2+2
        descriptor=call('density descriptor',18,tile_bytes((x-pad-23,z-pad-23,size+pad*2+46,size+pad*2+46,lo,hi)))
        call('density lattice',13,descriptor);call('surface climate',16,tile_bytes((x-pad-1,z-pad-1,source_width,source_width,lo,hi)))
        for name,op in [('shorelines',29),('lakes',43),('block columns',22),('cave fields',33),('aquifer fields',39),('carving',35)]:call(name,op)
        expanded=tile_bytes((x-pad,z-pad,size+pad*2,size+pad*2,lo,hi))
        call('ores including halo',50,expanded);call('cave dressing including halo',51,expanded)
        call('vegetation core',53,tile_bytes((x,z,size,size,lo,hi)));call('weather',54)
        invalid=folder/'invalid.mca'
        invalid.unlink(missing_ok=True)
        assert w.call(55,request(rx,rz,invalid,lo^1,hi),status=1)==struct.pack('>I',732)
        assert w.call(55,request(4194304,rz,invalid,lo,hi),status=1)==struct.pack('>I',733)
        assert w.call(55,b'',status=1)==struct.pack('>I',603)
        assert not invalid.exists(),'invalid geometry or seed created a file'
        ack=call('stream NBT/zlib/MCA',55,request(rx,rz,output,lo,hi))
        assert len(ack)==8200;chunks,sectors=struct.unpack_from('>2I',ack)
        assert chunks==(size//16)**2 and output.stat().st_size==sectors*4096
        before=output.read_bytes();assert before[:8192]==bytes(8192)
        with output.open('r+b') as stream:assert stream.write(ack[8:])==8192
        data=output.read_bytes();assert data[8192:]==before[8192:],'bridge rewrote chunk bytes'
        records=check(data,source,rx,rz,size)
        materials={key(m):i for i,m in enumerate(source['materials'])};checked=0
        edge=size//16-1
        for cz in sorted({0,edge//2,edge}):
            for cx in sorted({0,edge//2,edge}):
                slot=cz*32+cx;tag=records[slot]['tag'];points=[(x+cx*16+dx,z+cz*16+dz,lo,hi) for dz in range(16) for dx in range(16)]
                columns=decode(call('selected block columns',24,query_bytes(points)))
                for s,section in enumerate(tag['sections']):
                    for j,state in enumerate(palette(section['block_states'],4096,4)):
                        column=columns[j%256];assert column is not None;y=s*16+j//256
                        m=next(m for a,b,m in column['runs'] if a<=y<b);assert materials[key(state)]==m;checked+=1
                standalone=call('selected independent chunk command',27,chunk_request(rx*32+cx,rz*32+cz,lo,hi))
                assert standalone==compressed(data,records[slot]),'stream and existing chunk encoder differ'
        call('stop',4);assert w.process.wait(timeout=30)==0
        return dict(gpu_required=gpu,gpu_memory=gpu_memory if gpu else 'off',size=size,chunks=chunks,sectors=sectors,bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),selected_blocks_checked=checked,invalid_requests_rejected=3,stages=stages,observed_metal_buffers=sum(len(s['observed_metal_ms']) for s in stages),sampled_peak_worker_rss=peak[0],body_growth_samples=growth,chunk_bytes_unchanged_by_header_patch=True,profile_sha256=hashlib.sha256(inp.read_bytes()).hexdigest())
    finally:
        stopped.set();monitor.join(timeout=3);signal.alarm(0)
        w.process.stdin.close()
        try:w.process.wait(timeout=10)
        except subprocess.TimeoutExpired:w.process.kill();w.process.wait(timeout=10)
        w.process.stdout.close();w.trace.close()
        if w.process.returncode:print('WORKER ERROR',w.log.read_text()[-12000:],flush=True)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,required=True);p.add_argument('--binary',type=Path,default=ROOT/'build/bend/engine');p.add_argument('--gpu',action='store_true');p.add_argument('--gpu-memory',default='4GB');p.add_argument('--size',type=int,default=512,choices=[64,128,256,512]);p.add_argument('--metal-probe',type=Path);p.add_argument('--label',choices=['vanilla','terralith','combined']);a=p.parse_args()
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('full region stage timed out')))
    name=(a.label+'-' if a.label else '')+('gpu' if a.gpu else 'cpu')+'-'+str(a.size);folder=ROOT/'build/bend/full-region-tests'/name
    paths=sources(ROOT/'bend/engine.bend')|{Path(__file__).resolve()}
    report=dict(scope=__doc__.strip(),native_sha256=hashlib.sha256(a.binary.read_bytes()).hexdigest())
    report['sources']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}
    report['run']=exercise(a.binary,a.profile,a.gpu,folder,a.size,a.gpu_memory)
    if a.gpu:report['gpu_archive_sha256']=hashlib.sha256(a.binary.with_suffix('.gpu').read_bytes()).hexdigest()
    if a.metal_probe:
        assert a.gpu and a.metal_probe.with_suffix('.gpu').read_bytes()==a.binary.with_suffix('.gpu').read_bytes()
        report['observer']=exercise(a.metal_probe,a.profile,True,folder/'observer',a.size,a.gpu_memory)
        assert report['observer']['observed_metal_buffers']>0,'no Metal execution observed'
        ore=next(s for s in report['observer']['stages'] if s['opcode']==50)
        assert bool(ore['observed_metal_ms'])==(a.size+64<=128),'large ore core execution policy differs'
        assert all(not s['observed_metal_ms'] for s in report['observer']['stages'] if s['opcode'] in (53,55))
        assert report['observer']['sha256']==report['run']['sha256'],'logging changed generated MCA bytes'
        report['observer_native_sha256']=hashlib.sha256(a.metal_probe.read_bytes()).hexdigest()
    out=ROOT/'docs/benchmarks'/('bend-full-region-'+name+'.json');out.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',out,flush=True)

if __name__=='__main__':main()
