#!/usr/bin/env python3
"""GPU terrain selection -> cached CPU layouts, checked with a Python planner.

Original vanilla/Terralith/combined registries are retained. GPU biome samples
and height fields are test observations, not recomputed in Java/Rust. The
independent oracle checks candidate membership, weighted rejection, RNG state
handoff, every piece, canonical ordering, cache reuse and both seed words.
This is a coordinator component test, not playable structure parity.
"""
import argparse,copy,hashlib,json,os,re,struct,time
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,command,metal_compiler_command
from test_bend_registry_noise import fixture_wire
from test_bend_registry import Document
from test_bend_structures import Planner,Random,MASK,signed,weighted,element_offsets
from test_bend_structure_candidates import region,mixed,A,B
from test_bend_placement_merge import sources
from test_bend_density import ins

CASES=[(123456789,0,0),(123456789,0,0),(123456789,1,0),
       (MASK,-15,15),((1<<63)|0xffffffff,1874000,-1874000),(123456789,0,0)]

class Reader:
    def __init__(self,data):self.data=data;self.at=0
    def read(self,fmt):
        result=struct.unpack_from('>'+fmt,self.data,self.at);self.at+=struct.calcsize('>'+fmt);return result
    def word(self):return self.read('I')[0]

def selection(p,c,seed,biomes):
    s,x,z=c;initial=mixed((seed^(x*A)^(z*B)^p['sets'][s]['placement'].get('salt',0))&MASK)
    rng=Random(initial);entries=p['sets'][s]['entries'][:]
    while entries:
        chosen=weighted(entries,rng);entries.remove(chosen);d=chosen['definition']
        if biomes[d] in p['definitions'][d]['biomes']:return d,initial,rng
    return None,initial,rng

def verify(binary,path,folder,gpu):
    source=json.loads(path.read_text());p=source['structures'];wire=fixture_wire(source,True)
    stem=path.stem+('-gpu' if gpu else '-cpu');input=folder/(stem+'.rbp');input.write_bytes(wire)
    output=folder/(stem+'.bin');start=time.monotonic()
    result=command(['nice','-n','10',binary,input,output,'--threads','2','--gpu','4GB' if gpu else 'off'],timeout=1800,cwd=ROOT)
    elapsed=time.monotonic()-start;(folder/(stem+'.log')).write_text(result.stderr)
    return check_output(path,output,gpu,elapsed,result.stderr)

def check_output(path,output,gpu,elapsed=None,stderr=''):
    source=json.loads(path.read_text());p=source['structures'];wire=fixture_wire(source,True)
    raw=output.read_bytes();r=Reader(raw);assert r.word()==len(CASES)
    planner=Planner(p,element_offsets(Document(wire),p));cache=[];hits=misses=0;total=0;regions=[];heights_checked=0;history={};repeated=0
    for case,(seed,x,z) in enumerate(CASES):
        begin=r.at;cs=[];samples={}
        for _ in range(r.word()):
            s,a,b=r.read('III');c=(s,signed(a),signed(b));cs.append(c);samples[c]=r.read(str(len(p['definitions']))+'I')
        assert cs==region(p,seed,x,z,32),(case,'candidate region')
        if path.stem=='profile-control':
            for (s,_,_),v in samples.items():assert v[s]==(1 if s==2 else 0),(case,s,v)
        count,got_hits,got_misses,ns=r.read('IIII');actual=[];fields={}
        for _ in range(ns):
            d,a,b,start_seed,np=r.read('IIIQI');a=signed(a);b=signed(b)
            tile=None
            if r.word():
                ox,oz,width,n=r.read('IIII');ox=signed(ox);oz=signed(oz);values=r.read(str(n)+'I');assert n==width*width
                assert all(source['geology_min_y']<=signed(v)<=source['geology_min_y']+source['geology_height'] for v in values)
                tile=(ox,oz,width,values);heights_checked+=n
                if path.stem=='profile-control':assert set(values)=={source['sea_level'] if d==0 else 33}
            pieces=[]
            for _ in range(np):
                row=r.read('15I');pieces.append([row[0],*[signed(v) for v in row[1:11]],*row[11:]])
            actual.append((d,a,b,start_seed,pieces));fields[d,a,b,start_seed]=tile
            key=(seed,d,a,b,start_seed);value=(tile,pieces)
            if key in history:
                assert history[key]==value,(case,key,'request order changed terrain or layout');repeated+=1
            else:history[key]=value
        expected=[];snapshot=dict(cache)
        for c in cs:
            key=(seed,*c);d,start_seed,rng=selection(p,c,seed,samples[c]);plan=[]
            if d is not None:
                tile=fields.get((d,c[1],c[2],start_seed))
                # A sampled definition may legitimately create no root. Such
                # a layout does not consume terrain if the root pool is empty.
                def terrain(a,b):
                    assert tile is not None,(case,c,d,'missing projection')
                    ox,oz,width,values=tile
                    assert ox<=a<ox+width and oz<=b<oz+width,(case,c,d,'clamped height query',a,b,tile[:3])
                    return signed(values[(b-oz)*width+a-ox])
                _,plan=planner.run(d,rng=rng,coordinate=c[1],z=c[2],terrain=terrain)
                if plan:expected.append((d,c[1],c[2],start_seed,plan))
            # Frozen hit membership matches the coordinator even if earlier
            # misses in this same batch evict one of its hits.
            value=(d,start_seed,plan)
            if key in snapshot:
                hits+=1;assert snapshot[key]==value
            else:
                misses+=1;cache=[(key,value),*cache[:63]]
        expected.sort(key=lambda row:(row[2],row[1],row[0]))
        assert actual==expected,(case,'layout mismatch',len(actual),len(expected),next(((a,b) for a,b in zip(actual,expected) if a!=b),None))
        assert (count,got_hits,got_misses)==(len(cache),hits,misses),(case,'cache',count,got_hits,got_misses,len(cache),hits,misses)
        total+=sum(len(s[4]) for s in actual);regions.append(dict(case=case,candidates=len(cs),starts=len(actual),cache_hits=hits,cache_misses=misses))
    assert r.at==len(raw)
    if regions[0]['candidates']<=64:
        assert regions[1]['cache_misses']==regions[0]['cache_misses'],'warm request recomputed resident starts'
    assert regions[1]['cache_hits']>regions[0]['cache_hits'],'warm check was empty'
    times=[float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)]
    return dict(profile=path.name,profile_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),output_sha256=hashlib.sha256(raw).hexdigest(),gpu=gpu,wall_seconds=elapsed,pieces=total,height_values=heights_checked,repeated_starts_checked=repeated,regions=regions,metal_device_ms=times)

def control(original):
    from test_bend_registry_climate import complete_control_mapping
    s=copy.deepcopy(original)
    s['biomes'][0]['flags']=0;s['biomes'][1]['flags']=16
    s['climate_targets']=[dict(min=[-10000]*4,max=[10000]*4,weirdness=[-10000,10000],depth=depth,offset=0,biome=b) for b,depth in [(0,[100,100]),(1,[0,0])]]
    complete_control_mapping(s)
    s['registry_program']['programs'][0]=dict(nodes=[ins(0)],roots=[0]*6)
    s['registry_program']['programs'][1]=dict(nodes=[ins(29,1),ins(0,p=(32.25,0,0,0)),ins(5,1,0)],roots=[2])
    t=dict(id='test:house',ground=1,size=[5,6,5],palettes=[[[1,1,1,1]]],blocks=[0,0,0,0],tags={},entities=[],joints=[])
    pool=dict(fallback='minecraft:empty',entries=[dict(weight=1,terrain_matching=False,parts=[dict(template=0,ignore_air=False,processors=[])])])
    ds=[];sets=[]
    for i in range(4):
        cfg=dict(size=0,max_distance_from_center=16,start_height={'absolute':0 if i<2 else 20 if i==2 else 21})
        if i<2:cfg['project_start_to_heightmap']='WORLD_SURFACE_WG' if i==0 else 'OCEAN_FLOOR_WG'
        ds.append(dict(id=f'test:definition_{i}',kind='jigsaw',biomes=[0 if i<2 else 1],pool='test:pool',config=cfg))
        sets.append(dict(id=f'test:set_{i}',placement=dict(spacing=24,separation=4,salt=1234+i,frequency=1.),entries=[dict(definition=i,weight=1)]))
    s['structures']=dict(templates=[t],definitions=ds,pools={'test:pool':pool},sets=sets)
    return s

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--build',action='store_true');parser.add_argument('--gpu',action='store_true');parser.add_argument('--profiles',type=Path,default=ROOT/'build/bend/profiles');parser.add_argument('--binary',type=Path);args=parser.parse_args()
    folder=ROOT/'build/bend/structure-region';folder.mkdir(parents=True,exist_ok=True);binary=args.binary or folder/'fixture';generated=folder/'fixture.c'
    if args.build:
        command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/structure-region.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
        command(metal_compiler_command(generated,binary),timeout=1200,cwd=ROOT)
    results=[]
    profiles=sorted(args.profiles.glob('profile-*.json'));control_path=folder/'profile-control.json'
    control_path.write_text(json.dumps(control(json.loads(profiles[0].read_text()))))
    for path in [*profiles,control_path]:
        print('START',path.name,'gpu' if args.gpu else 'cpu',flush=True)
        result=verify(binary,path,folder,args.gpu);results.append(result);print(json.dumps(result),flush=True)
    assert results and sum(r['pieces'] for r in results)>0 and sum(r['height_values'] for r in results)>0
    report=dict(scope=__doc__,bend_version=command([DEFAULT_BEND,'version']).stdout.strip(),threads=2,profiles=results,executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),generated_c_sha256=hashlib.sha256(generated.read_bytes()).hexdigest(),sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources(ROOT/'bend/tests/structure-region.bend')|{Path(__file__).resolve(),ROOT/'scripts/test_bend_structures.py'}})
    (ROOT/'docs/benchmarks'/('bend-structure-region-'+('gpu' if args.gpu else 'cpu')+'.json')).write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()
