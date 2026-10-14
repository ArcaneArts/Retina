#!/usr/bin/env python3
"""Persistent terrain worker -> CPU vegetation -> cropped MCA, without Rust.

An analytic forest geometry control preserves all registered recipes/materials.
This isolates observable vegetation in the complete stage sequence. Original
registry profiles and core partition invariance have separate fixture checks.
"""
import argparse,copy,hashlib,json,os,re,signal,struct,subprocess,time
from pathlib import Path
from test_bend_compression import ROOT
from test_bend_engine import Worker
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_density import ins
from test_bend_surface import tile_bytes,query_bytes
from test_bend_material_columns import decode
from test_bend_generated_regions import request
from test_bend_formats import read_region
from test_bend_chunks import palette,unpack,MAP_NAMES
from test_bend_placement_merge import sources

LO,HI=123456789,0x80000000


class ObservedWorker(Worker):
    """A regular file avoids a full diagnostic stderr pipe blocking the worker."""
    def __init__(self,binary,gpu,log,gpu_memory='on'):
        self.log=log;self.trace=log.open('wb')
        self.process=subprocess.Popen(['nice','-n','10',str(binary),'gpu' if gpu else 'cpu',
            '--threads','2','--gpu',gpu_memory if gpu else 'off'],cwd=ROOT,stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,stderr=self.trace,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
        self.id=0
    def dispatches(self):
        return [float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',self.log.read_text())]
    def close(self):
        self.process.stdin.close()
        try:
            assert self.process.wait(timeout=10)==0,self.log.read_text()
        finally:
            if self.process.poll() is None:self.process.kill();self.process.wait(timeout=10)
            self.process.stdout.close();self.trace.close()


def fixture(source):
    source=copy.deepcopy(source);forest=next(i for i,b in enumerate(source['biomes']) if b['id']=='minecraft:forest')
    source['climate_targets']=[dict(min=[-10000]*4,max=[10000]*4,weirdness=[-10000,10000],depth=[-10000,10000],offset=0,biome=forest)]
    source['registry_program']['programs'][1]=dict(nodes=[ins(29,1),ins(0,p=(80,0,0,0)),ins(5,1,0)],roots=[2])
    source['registry_program']['programs'][2]=dict(nodes=[ins(0,p=(1,0,0,0))],roots=[0])
    for n in source['cave_noises']:n['amplitude']=0.
    for b in source['biomes']:b['carvers']=[];b['ores']=[]
    source['ores']=[]
    return source


def check(path,source,columns):
    chunks=read_region(path.read_bytes());assert len(chunks)==1
    tag=next(iter(chunks.values()))['tag'];cx,cz=tag['xPos'],tag['zPos'];heights=[[0]*256 for _ in range(6)];changed=0
    assert (cx,cz)==(-4,2) and tag['DataVersion']==5023 and tag['isLightOn']==0
    for s,section in enumerate(tag['sections']):
        for i,state in enumerate(palette(section['block_states'],4096,4)):
            cell=i%256;y=s*16+i//256
            m=next(m for a,b,m in columns[cell]['runs'] if a<=y<b)
            want=source['materials'][m];want={'id':want} if isinstance(want,str) else want
            assert state==want,(s,i,state,want)
            changed+=bool(source['material_flags'][m]&(4|8|16))
            for k in range(6):
                if source['heightmap_masks'][m]&(1<<k):heights[k][cell]=y+1
    for k,key in enumerate(MAP_NAMES):assert unpack(tag['Heightmaps'][key],256,9)==heights[k]
    assert changed>0,'registered tree/plant states must reach final MCA'
    return dict(chunks=1,blocks_checked=384*256,vegetation_voxels=changed,sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def exercise(binary,path,gpu,folder,observe=False):
    source=fixture(json.loads(path.read_text()));folder.mkdir(parents=True,exist_ok=True);inp=folder/'profile.rbp';inp.write_bytes(fixture_wire(source,True))
    w=ObservedWorker(binary,gpu,folder/'dispatch.log') if observe else Worker(binary,gpu);stages=[]
    def call(op,data=b'',error=0):
        before=len(w.dispatches()) if observe else 0
        signal.alarm(1800);start=time.monotonic();out=w.call(op,data,status=int(bool(error)));stages.append(dict(opcode=op,wall_ms=(time.monotonic()-start)*1000))
        if observe:
            times=w.dispatches()[before:];stages[-1].update(command_buffers=len(times),device_ms=times)
        if error:assert out==struct.pack('>I',error),(op,out,error)
        return out
    try:
        call(53,tile_bytes((-64,32,16,16,LO,HI)),775)
        call(5,path_request(inp))
        for op in (11,19,21,30,37,9,15,25):call(op)
        x,z,width=-64,32,16;pad=32
        call(13,call(18,tile_bytes((x-pad-23,z-pad-23,width+2*pad+46,width+2*pad+46,LO,HI))))
        call(16,tile_bytes((x-pad-1,z-pad-1,width+2*pad+2,width+2*pad+2,LO,HI)))
        for op in (29,43,22,33,39,35):call(op)
        wide=tile_bytes((x-pad,z-pad,width+2*pad,width+2*pad,LO,HI))
        call(50,wide);call(51,wide)
        points=[(x+dx,z+dz,LO,HI) for dz in range(16) for dx in range(16)]
        before=decode(call(24,query_bytes(points)))
        call(53,tile_bytes((x,z,16,16,LO,HI^1)),776)
        assert decode(call(24,query_bytes(points)))==before
        ack=struct.unpack('>5I',call(53,tile_bytes((x,z,16,16,LO,HI))))
        assert ack[0]==9 and ack[2]>0 and ack[4]==0,ack
        after=decode(call(24,query_bytes(points)));assert after!=before
        mca=folder/'r.-1.0.mca';written=struct.unpack('>2I',call(52,request(-1,0,mca,LO,HI)))
        assert written[0]==1
        record=check(mca,source,after)
        again=folder/'again.mca';call(52,request(-1,0,again,LO,HI));assert mca.read_bytes()==again.read_bytes()
        call(53,tile_bytes((x,z,16,16,LO,HI)),776)
        call(4);assert w.process.wait(timeout=30)==0
        if observe:
            assert all(s['command_buffers']==0 for s in stages if s['opcode']==53),'vegetation unexpectedly dispatched GPU work'
            assert sum(s['command_buffers'] for s in stages if s['opcode']!=53)>0,'no actual Metal execution'
        return dict(gpu_required=gpu,acknowledgement=list(ack),stages=stages,mca=record,profile_sha256=hashlib.sha256(inp.read_bytes()).hexdigest(),vegetation_execution='CPU (unbanged)',metal_observed=observe)
    finally:signal.alarm(0);w.close()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--binary',type=Path,default=ROOT/'build/bend/engine');p.add_argument('--profile',type=Path,required=True);p.add_argument('--gpu',action='store_true');p.add_argument('--metal-probe',type=Path);a=p.parse_args()
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('Bend vegetation worker timeout')))
    folder=ROOT/'build/bend/vegetation-worker-tests'/('gpu' if a.gpu else 'cpu')
    report=dict(scope=__doc__.strip(),run=exercise(a.binary,a.profile,a.gpu,folder),source_sha256=hashlib.sha256(a.profile.read_bytes()).hexdigest(),native_sha256=hashlib.sha256(a.binary.read_bytes()).hexdigest())
    paths=sources(ROOT/'bend/engine.bend')|{Path(__file__).resolve()}
    report['sources']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}
    if a.gpu:report['gpu_archive_sha256']=hashlib.sha256(a.binary.with_suffix('.gpu').read_bytes()).hexdigest()
    if a.metal_probe:
        assert a.gpu,'Metal observer requires the GPU run'
        assert a.binary.with_suffix('.gpu').read_bytes()==a.metal_probe.with_suffix('.gpu').read_bytes()
        report['observer']=exercise(a.metal_probe,a.profile,True,folder/'observer',True)
        assert report['observer']['mca']['sha256']==report['run']['mca']['sha256'],'host logging changed the final MCA'
        report['observer_native_sha256']=hashlib.sha256(a.metal_probe.read_bytes()).hexdigest()
    output=ROOT/'docs/benchmarks'/('bend-vegetation-worker-'+('gpu' if a.gpu else 'cpu')+'.json');output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)

if __name__=='__main__':main()
