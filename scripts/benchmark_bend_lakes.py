#!/usr/bin/env python3
"""Warm alternating lake-stage A/B with exact geometry and final-block checks.

Both executables are pure Bend workers, not Rust. Timing covers command 43 and
its transport; density/surface preparation and material/serialization are outside
the timed interval. Compilation must finish before this harness starts. These
measurements do not establish complete lit/featured region throughput.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import signal
import re
import statistics
import struct
import threading
import time

from test_bend_compression import ROOT
from test_bend_engine import Worker
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_surface import tile_bytes,query_bytes
from test_bend_density_lattice import LOW,HIGH


def queries(worker,opcode,points):
    size=256 if opcode==24 else 4096
    return b''.join(worker.call(opcode,query_bytes(points[i:i+size])) for i in range(0,len(points),size))


def contents(worker,tile):
    x,z,width,depth,low,high=tile
    ack=worker.call(43);cx,cz,nx,nz=struct.unpack('>4i',ack)
    basins=queries(worker,44,[(xx*128,zz*128,low,high) for zz in range(cz,cz+nz) for xx in range(cx,cx+nx)])
    worker.call(22)
    columns=queries(worker,24,[(xx,zz,low,high) for zz in range(z,z+depth) for xx in range(x,x+width)])
    return ack+basins+columns


def compare(baseline,candidate,profiles,sizes,warmups,repeats,gpu,metal_probe=None):
    records=[]
    for name,path in profiles:
        workers=[Worker(baseline,gpu),Worker(candidate,gpu)]
        diagnostics=[bytearray(),bytearray()]
        readers=[threading.Thread(target=lambda w=w,d=d:d.extend(w.process.stderr.read()),daemon=True) for w,d in zip(workers,diagnostics)]
        for reader in readers:reader.start()
        try:
            signal.alarm(1800)
            for worker in workers:
                worker.call(5,path_request(path))
                for opcode in (11,19,21,30,9,15,25):worker.call(opcode)
            for size in sizes:
                tile=(-1024,1536,size,size,LOW,HIGH)
                print(f'{"GPU" if gpu else "CPU"} {name}: {size}x{size} lake A/B',flush=True)
                for worker in workers:
                    desc=worker.call(18,tile_bytes((tile[0]-23,tile[1]-23,size+46,size+46,LOW,HIGH)))
                    worker.call(13,desc);worker.call(16,tile_bytes(tile));worker.call(29)
                before=[contents(worker,tile) for worker in workers]
                assert before[0]==before[1],(name,size,gpu,'baseline/candidate geometry or final blocks differ')
                timings=[[],[]]
                for repetition in range(warmups+repeats):
                    signal.alarm(1800)
                    for index in ((0,1) if repetition%2==0 else (1,0)):
                        start=time.perf_counter();workers[index].call(43);elapsed=(time.perf_counter()-start)*1000
                        if repetition>=warmups:timings[index].append(elapsed)
                after=[contents(worker,tile) for worker in workers]
                assert after==before,(name,size,gpu,'repeated construction changed results')
                median=list(map(statistics.median,timings))
                records.append(dict(profile=name,width=size,depth=size,gpu_required=gpu,
                    worker_nice=[os.getpriority(os.PRIO_PROCESS,worker.process.pid) for worker in workers],
                    baseline_host_ms=timings[0],candidate_host_ms=timings[1],baseline_median_ms=median[0],candidate_median_ms=median[1],
                    baseline_p95_ms=sorted(timings[0])[math.ceil(repeats*.95)-1],candidate_p95_ms=sorted(timings[1])[math.ceil(repeats*.95)-1],
                    ratio=median[0]/median[1],geometry_and_columns_bytes=len(before[0]),sha256=hashlib.sha256(before[0]).hexdigest()))
                print(f'  median {median[0]:.2f} -> {median[1]:.2f} ms ({median[0]/median[1]:.2f}x)',flush=True)
        finally:
            for worker in workers:worker.close()
            for reader in readers:reader.join(timeout=5)
            signal.alarm(0)
        if gpu and metal_probe:
            # Outside all timed intervals, compare the normal candidate with
            # its host-logging observer using the same stock GPU archive.
            probe=Worker(metal_probe,True);observed=bytearray()
            reader=threading.Thread(target=lambda:observed.extend(probe.process.stderr.read()),daemon=True);reader.start()
            try:
                signal.alarm(1800);probe.call(5,path_request(path))
                for opcode in (11,19,21,30,9,15,25):probe.call(opcode)
                for record in records[-len(sizes):]:
                    size=record['width'];tile=(-1024,1536,size,size,LOW,HIGH)
                    desc=probe.call(18,tile_bytes((tile[0]-23,tile[1]-23,size+46,size+46,LOW,HIGH)))
                    probe.call(13,desc);probe.call(16,tile_bytes(tile));probe.call(29)
                    assert hashlib.sha256(contents(probe,tile)).hexdigest()==record['sha256'],(name,size,'observer changed output')
            finally:
                probe.close();reader.join(timeout=5);signal.alarm(0)
            device_ms=[float(x) for x in re.findall(rb'BEND_METAL_DISPATCH device_ms=([0-9.]+)',observed)]
            assert not reader.is_alive() and len(device_ms)>=len(sizes)*11,('missing device commands',name,len(device_ms))
            records[-1]['metal_proof']=dict(profile=name,observed_device_commands=len(device_ms),device_ms=device_ms,
                all_sizes_match_normal_candidate=True,scope='Outside measured intervals; stock runtime plus host command-buffer logging only')
    return records


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--baseline',type=Path,required=True)
    p.add_argument('--candidate',type=Path,default=ROOT/'build/bend/engine');p.add_argument('--profile',type=Path,action='append',required=True)
    p.add_argument('--sizes',type=int,nargs='+',default=[16]);p.add_argument('--warmups',type=int,default=5);p.add_argument('--repeats',type=int,default=20)
    p.add_argument('--metal-probe',type=Path,help='Optional exact-source stock-runtime observer; checked outside timed intervals.')
    p.add_argument('--backend',choices=['cpu','gpu','both'],default='both');p.add_argument('--output',type=Path,default=ROOT/'build/bend/lake-ab.json')
    args=p.parse_args();folder=ROOT/'build/bend/lake-ab-profiles';folder.mkdir(parents=True,exist_ok=True)
    profiles=[];inputs=[]
    for source in args.profile:
        name=source.parent.name if source.name=='profile.json' else source.stem
        wire=folder/(name+'.rbp');wire.write_bytes(fixture_wire(json.loads(source.read_text()),True));profiles.append((name,wire))
        inputs.append(dict(name=name,json_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),rbp_sha256=hashlib.sha256(wire.read_bytes()).hexdigest()))
    report=dict(scope=__doc__.strip(),complete=False,warmups=args.warmups,repetitions=args.repeats,cpu_workers=2,
        driver_nice=os.getpriority(os.PRIO_PROCESS,0),worker_nice_adjustment=10,
        own_compilation_and_other_tests_concurrent=False,other_host_load_controlled=False,
        executables={name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in [('baseline',args.baseline),('candidate',args.candidate)]},
        gpu_archives={name:hashlib.sha256(path.with_suffix('.gpu').read_bytes()).hexdigest() if path.with_suffix('.gpu').is_file() else None for name,path in [('baseline',args.baseline),('candidate',args.candidate)]},
        bend_sources_sha256={str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted((ROOT/'bend').glob('*.bend'))},
        inputs=inputs,results=[])
    args.output.parent.mkdir(parents=True,exist_ok=True)
    for gpu in ([False,True] if args.backend=='both' else [args.backend=='gpu']):
        for profile in profiles:
            report['results']+=compare(args.baseline,args.candidate,[profile],args.sizes,args.warmups,args.repeats,gpu,args.metal_probe)
            args.output.write_text(json.dumps(report,indent=2)+'\n')
    if args.metal_probe:report['metal_probe_sha256']=hashlib.sha256(args.metal_probe.read_bytes()).hexdigest()
    report['complete']=True
    args.output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',args.output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('lake A/B timeout')));main()
