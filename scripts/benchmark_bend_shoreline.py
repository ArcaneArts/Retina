#!/usr/bin/env python3
"""Warm alternating shoreline-only A/B; compilation must finish beforehand.

The baseline is the unmerged direct-scan shoreline implementation, not Rust.
All raw surface rows are restored before each timed finalization. Transport is
included; lattice/surface/material construction is excluded. Complete lit and
featured Rust-versus-Bend benchmarks remain separate unfinished work.
"""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import statistics
import time

from test_bend_compression import ROOT
from test_bend_engine import Worker
from test_bend_registry import path_request
from test_bend_surface import tile_bytes,query_bytes
from test_bend_density_lattice import LOW,HIGH


def surface_bytes(worker,tile):
    x,z,w,d,lo,hi=tile
    points=[(x+i%w,z+i//w,lo,hi) for i in range(w*d)]
    return b''.join(worker.call(17,query_bytes(points[i:i+4096])) for i in range(0,len(points),4096))


def compare(baseline,candidate,profiles,sizes,repeats,gpu):
    records=[]
    for js,wire in profiles:
        signal.alarm(1800);name=Path(js).parent.name
        workers=[Worker(baseline,gpu),Worker(candidate,gpu)]
        try:
            for worker in workers:
                worker.call(5,path_request(Path(wire)));worker.call(11);worker.call(19);worker.call(21);worker.call(9);worker.call(15)
            for size in sizes:
                print(f'{"GPU" if gpu else "CPU"} {name}: {size}x{size} shore A/B',flush=True)
                tile=(-1024,1536,size,size,LOW,HIGH)
                for worker in workers:
                    descriptor=worker.call(18,tile_bytes((-1030,1530,size+12,size+12,LOW,HIGH)))
                    worker.call(13,descriptor);worker.call(16,tile_bytes(tile));worker.call(29)
                before=[surface_bytes(worker,tile) for worker in workers];assert before[0]==before[1]
                timings=[[],[]]
                for repetition in range(repeats):
                    for index in ((0,1) if repetition%2==0 else (1,0)):
                        worker=workers[index];worker.call(16,tile_bytes(tile))
                        start=time.perf_counter();worker.call(29);timings[index].append((time.perf_counter()-start)*1000)
                after=[surface_bytes(worker,tile) for worker in workers];assert after==before
                samples=[(tile[0],tile[1],LOW,HIGH),(tile[0]+size-1,tile[1]+size-1,LOW,HIGH),(-1024+size//2,1536+size//2,LOW,HIGH)]
                for worker in workers:worker.call(22)
                assert workers[0].call(24,query_bytes(samples))==workers[1].call(24,query_bytes(samples))
                medians=list(map(statistics.median,timings))
                records.append(dict(profile=name,width=size,depth=size,gpu_required=gpu,
                    baseline_host_ms=timings[0],candidate_host_ms=timings[1],
                    baseline_median_ms=medians[0],candidate_median_ms=medians[1],ratio=medians[0]/medians[1],
                    all_surface_bytes_sha256=hashlib.sha256(before[0]).hexdigest()))
        finally:
            for worker in workers:worker.close()
            signal.alarm(0)
    return records


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--baseline',type=Path,required=True)
    p.add_argument('--candidate',type=Path,default=ROOT/'build/bend/engine');p.add_argument('--profile',nargs=2,action='append',required=True)
    p.add_argument('--sizes',nargs='+',type=int,default=[64,128]);p.add_argument('--repeats',type=int,default=5)
    args=p.parse_args();report=dict(scope=__doc__,warmups=1,repetitions=args.repeats,cpu_workers=2,nice=10,
        own_compilation_and_other_tests_concurrent=False,other_host_load_controlled=False,
        executables={name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in [('baseline',args.baseline),('candidate',args.candidate)]},
        profiles=[dict(name=Path(js).parent.name,rbp_sha256=hashlib.sha256(Path(wire).read_bytes()).hexdigest()) for js,wire in args.profile],
        results=compare(args.baseline,args.candidate,args.profile,args.sizes,args.repeats,False)+
                compare(args.baseline,args.candidate,args.profile,args.sizes,args.repeats,True))
    path=ROOT/'build/bend/shoreline-ab.json';path.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: alternating shoreline A/B; {path}')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('shoreline A/B timeout')));main()
