#!/usr/bin/env python3
"""Exercise Bend's sparse MCA writer on CPU and GPU, including untouched snapshots."""
import hashlib,json,signal,struct
from pathlib import Path
from test_bend_compression import ROOT
from test_bend_engine import Worker
from test_bend_registry import path_request
from test_bend_generated_regions import request,compressed
from test_bend_cave_dressing import fixtures,tile_bytes,LO,HI
from test_bend_generated_chunks import request as chunk_request
from test_bend_formats import read_region

def exercise(gpu):
    folder=ROOT/'build/bend/partial-region-tests'/('gpu' if gpu else 'cpu');folder.mkdir(parents=True,exist_ok=True)
    profiles,_=fixtures(folder/'profiles');source=next(p for p in profiles if p[0]=='lush')
    w=Worker(ROOT/'build/bend/engine',gpu)
    def call(op,data=b'',error=0):
        signal.alarm(180);raw=w.call(op,data,status=int(bool(error)))
        if error:assert raw==struct.pack('>I',error),(op,raw,error)
        return raw
    try:
        call(5,path_request(source[2]))
        for op in (11,19,21,30,9,15,25):call(op)
        x,z=-64,32
        call(13,call(18,tile_bytes((x-7,z-7,46,46,LO,HI))))
        call(16,tile_bytes((x-1,z-1,34,34,LO,HI)));call(29);call(22);call(33);call(35);call(51,tile_bytes((x,z,32,32,LO,HI)))
        path=folder/'r.-1.0.mca';body=request(-1,0,path,LO,HI)
        path.unlink(missing_ok=True)
        for bad,code in [(body+b'\0',603),(request(-1,0,path,LO,HI^1),732),(request(0,0,path,LO,HI),732),(request(4194304,0,path,LO,HI),733)]:
            call(52,bad,code);assert not path.exists()
        ack=call(52,body);data=path.read_bytes();records=read_region(data)
        slots={zz*32+xx for zz in (2,3) for xx in (28,29)}
        assert set(records)==slots and struct.unpack('>2I',ack)==(4,len(data)//4096)
        for slot,record in records.items():
            cx=-32+slot%32;cz=slot//32
            assert record['tag']['xPos']==cx and record['tag']['zPos']==cz
            assert compressed(data,record)==call(27,chunk_request(cx,cz,LO,HI,5023))
        again=folder/'again.mca';call(52,request(-1,0,again,LO,HI));assert data==again.read_bytes()
        call(4);assert w.process.wait(timeout=20)==0
        return dict(gpu_required=gpu,chunks=4,slots=sorted(slots),sha256=hashlib.sha256(data).hexdigest(),bytes=len(data))
    finally:signal.alarm(0);w.close()

if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('partial MCA timeout')))
    report=dict(runs=[exercise(False),exercise(True)])
    assert report['runs'][0]['sha256']==report['runs'][1]['sha256']
    output=ROOT/'build/bend/partial-region-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)
