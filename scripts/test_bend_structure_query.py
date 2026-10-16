#!/usr/bin/env python3
"""Persistent structure queries before any terrain, with an independent planner.

Known analytic climate/heights retain real registry material/policy data. Checks
candidate RNG, weighted/rejected/empty layouts, full seeds, cache eviction,
malformed input and profile reload. No MCA or Java/Rust generator is involved.
"""
import argparse,copy,hashlib,json,signal,struct,time
from pathlib import Path
from test_bend_compression import ROOT
from test_bend_vegetation_worker import ObservedWorker
from test_bend_structure_region import control,selection
from test_bend_structure_candidates import candidate,permitted
from test_bend_structures import Planner,MASK,element_offsets
from test_bend_registry import Document,path_request
from test_bend_registry_noise import fixture_wire
from test_bend_placement_merge import sources
from test_bend_binary import NbtReader


def payload(seed,points):
    words=[seed&0xffffffff,seed>>32,len(points),*(v&0xffffffff for row in points for v in row)]
    return struct.pack('>'+str(len(words))+'I',*words)


def fixture(original):
    s=control(original);p=s['structures']
    empty=copy.deepcopy(p['definitions'][0]);empty.update(id='test:empty',pool='minecraft:empty');p['definitions'].append(empty)
    for i,d in enumerate([4,0,0]):
        p['sets'].append(dict(id=f'test:extra_{i}',placement=dict(spacing=24,separation=4,salt=1234,frequency=0. if i==1 else 1.),entries=[dict(definition=d,weight=1)]))
    p['sets'][-1]['placement']['exclusion_zone']=dict(other_set=p['sets'][0]['id'],chunk_count=0)
    p['sets'].append(dict(id='test:weighted',placement=dict(spacing=24,separation=4,salt=789,frequency=.6,spread_type='triangular'),entries=[dict(definition=3,weight=7),dict(definition=1,weight=2)]))
    return s


def expected(source,wire,seed,points):
    p=source['structures'];planner=Planner(p,element_offsets(Document(wire),p));result=[]
    for x,z,s in points:
        if not permitted(p,s,seed,x,z):result.append(-1);continue
        biomes=[0,0,1,0,0]
        d,_,rng=selection(p,(s,x,z),seed,biomes)
        if d is None:result.append(-1);continue
        _,pieces=planner.run(d,rng=rng,coordinate=x,z=z,terrain=lambda a,b:source['sea_level'] if d in (0,4) else 33)
        result.append(d if pieces else -1)
    return result


def exercise(binary,gpu,folder):
    source=fixture(json.loads((ROOT/'build/bend/profiles/profile-0.json').read_text()));wire=fixture_wire(source,True)
    inp=folder/'profile.rbp';inp.write_bytes(wire);w=ObservedWorker(binary,gpu,folder/'worker.log','4GB')
    rows=[]
    def call(op,data=b'',status=0):
        signal.alarm(1800);return w.call(op,data,status=status)
    def prepare():
        call(5,path_request(inp))
        for op in (11,9,15):call(op)
    def run(seed,cells):
        t=time.monotonic();raw=call(57,payload(seed,cells));cs=list(struct.iter_unpack('>ii',raw))
        assert cs==[candidate(seed,x,z,source['structures']['sets'][s]['placement']) for x,z,s in cells]
        points=[(x,z,row[2]) for (x,z),row in zip(cs,cells)]
        actual=list(struct.unpack('>'+str(len(points))+'i',call(58,payload(seed,points))))
        assert actual==expected(source,wire,seed,points),(seed,points,actual,expected(source,wire,seed,points))
        rows.append(dict(seed=str(seed),count=len(cells),accepted=sum(d>=0 for d in actual),sha256=hashlib.sha256(raw).hexdigest(),wall_ms=(time.monotonic()-t)*1000))
        return points,actual
    try:
        assert call(58,payload(1,[]),1)==struct.pack('>I',881)
        prepare()
        assert call(57,payload(1,[]))==b'' and call(58,payload(1,[]))==b''
        for seed,coord in [(123456789,0),(MASK,-15),((1<<63)|0xffffffff,1874000)]:
            cells=[(coord+(i%4)*31,-coord+(i//4)*29,i%8) for i in range(32)]
            points,actual=run(seed,cells)
            for x,z,_ in points[:8]:
                raw=call(59,payload(seed,[(x,z,0)]));reader=NbtReader(raw)
                assert reader.number('>B')==10 and reader.text()==''
                tag=reader.tag(10);assert reader.offset==len(raw)
                assert set(tag)=={'structures','entities','block_entities'} and tag['entities']==[] and tag['block_entities']==[]
                rows_at=[(x,z,i) for i in range(len(source['structures']['sets']))]
                ids=expected(source,wire,seed,rows_at)
                want={source['structures']['definitions'][i]['id'] for i in ids if i>=0}
                assert set(tag['structures']['starts'])==want and tag['structures']['References']=={}
                for start in tag['structures']['starts'].values():
                    assert (start['ChunkX'],start['ChunkZ'],start['references'])==(x,z,0) and start['Children']
            assert list(struct.unpack('>32i',call(58,payload(seed,list(reversed(points))))))==list(reversed(actual))
            # Deliberately noncandidate points must fail without polluting cache.
            shifted=[(x+1,z,s) for x,z,s in points]
            assert list(struct.unpack('>32i',call(58,payload(seed,shifted))))==expected(source,wire,seed,shifted)
        # Fill/evict the 64-entry FIFO; a frozen earlier hit must survive in the
        # output even if later misses evict it during this same request.
        points,actual=run(123456789,[(i*24,0,0) for i in range(64)])
        run(123456789,[(0,0,0),*((i*24,24,0) for i in range(63))])
        assert list(struct.unpack('>64i',call(58,payload(123456789,points))))==actual
        for op in (57,58,59):
            for bad in (b'',b'\x01',payload(1,[])+b'\x00',payload(1,[(0,0,0)])[:-1]):
                assert call(op,bad,1)==struct.pack('>I',603)
            for bad in (payload(1,[(0,0,999)]),payload(1,[(0,0,0)]*65)):
                assert call(op,bad,1)==struct.pack('>I',883)
        assert list(struct.unpack('>64i',call(58,payload(123456789,points))))==actual
        # Same candidate/seed with a replaced registry cannot inherit acceptance.
        for d in source['structures']['definitions']:d['biomes']=[]
        inp.write_bytes(fixture_wire(source,True));prepare()
        assert list(struct.unpack('>64i',call(58,payload(123456789,points))))==[-1]*64
        call(4);w.close();signal.alarm(0)
    finally:
        if w.process.poll() is None:w.process.kill();w.process.wait(timeout=10)
        w.trace.close();signal.alarm(0)
    return dict(gpu_required=gpu,rows=rows,before_first_region=True,cache_eviction=True,profile_reload=True,malformed_rejections=18,metadata_before_terrain=True,binary_sha256=hashlib.sha256(binary.read_bytes()).hexdigest())


def original_profiles(binary,gpu,folder,reference):
    from test_bend_structure_region import Reader,CASES,check_output
    from test_bend_structures import signed
    results=[]
    for profile in sorted((ROOT/'build/bend/profiles').glob('profile-*.json')):
        source=json.loads(profile.read_text());p=source['structures']
        observed=reference/(profile.stem+('-gpu' if gpu else '-cpu')+'.bin')
        # Independently recheck the original coordinator output, including all
        # piece transforms and GPU height observations, before using its actual
        # nonempty layouts as a comparison for the query-only protocol.
        evidence=check_output(profile,observed,gpu)
        reader=Reader(observed.read_bytes());assert reader.word()==len(CASES)
        batches=[]
        for seed,_,_ in CASES:
            candidates=[]
            for _ in range(reader.word()):
                st,x,z=reader.read('III');biomes=reader.read(str(len(p['definitions']))+'I')
                candidates.append(((st,signed(x),signed(z)),biomes))
            _,_,_,count=reader.read('IIII');starts=set()
            for _ in range(count):
                d,x,z,_,pieces=reader.read('IIIQI')
                if reader.word():
                    _,_,_,n=reader.read('IIII');reader.read(str(n)+'I')
                reader.read(str(pieces*15)+'I');starts.add((d,signed(x),signed(z)))
            expected=[];points=[]
            for c,biomes in candidates:
                d,_,_=selection(p,c,seed,biomes);st,x,z=c
                points.append((x,z,st));expected.append(d if (d,x,z) in starts else -1)
            batches.append((seed,points,expected))
        assert reader.at==len(reader.data)
        path=folder/(profile.stem+'.rbp');path.write_bytes(fixture_wire(source,True))
        w=ObservedWorker(binary,gpu,folder/(profile.stem+'.log'),'4GB')
        try:
            signal.alarm(1800);w.call(5,path_request(path))
            for op in (11,9,15):w.call(op)
            for seed,points,expected in batches:
                actual=[]
                for at in range(0,len(points),64):
                    part=points[at:at+64];signal.alarm(1800)
                    actual.extend(struct.unpack('>'+str(len(part))+'i',w.call(58,payload(seed,part))))
                assert actual==expected,(profile.name,seed,actual,expected)
            w.call(4);w.close()
        finally:
            if w.process.poll() is None:w.process.kill();w.process.wait(timeout=10)
            w.trace.close();signal.alarm(0)
        results.append(dict(profile=profile.name,reference_sha256=evidence['output_sha256'],profile_sha256=evidence['profile_sha256'],candidates=sum(len(b[1]) for b in batches),matches=sum(sum(v>=0 for v in b[2]) for b in batches)))
        print('PASS original profile',results[-1],flush=True)
    return results


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--binary',type=Path,required=True);p.add_argument('--gpu',action='store_true');p.add_argument('--reference',type=Path);a=p.parse_args()
    folder=ROOT/'build/bend/structure-locate'/('gpu' if a.gpu else 'cpu');folder.mkdir(parents=True,exist_ok=True)
    result=exercise(a.binary,a.gpu,folder)
    if a.reference:result['original_profiles']=original_profiles(a.binary,a.gpu,folder,a.reference)
    result['sources']={str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest() for x in sources(ROOT/'bend/engine.bend')|{Path(__file__).resolve()}}
    (folder/'report.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS structure queries',json.dumps(result['rows']),flush=True)

if __name__=='__main__':main()
