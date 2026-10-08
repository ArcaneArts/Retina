#!/usr/bin/env python3
"""Warm alternating material-stage A/B against the compiled PR41 Bend worker.

Includes geometry, material evaluation and command transport; excludes earlier
lattice, surface and coastal steps. Compilation and correctness tests must
finish first. This is not a complete Rust-versus-Bend generator benchmark.
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
from test_bend_surface import tile_bytes, query_bytes
from test_bend_density_lattice import LOW, HIGH


def block_bytes(worker, tile):
    x,z,w,d,lo,hi=tile
    points=[(x+i%w,z+i//w,lo,hi) for i in range(w*d)]
    return b''.join(worker.call(24,query_bytes(points[i:i+256])) for i in range(0,len(points),256))


def compare(baseline,candidate,profiles,sizes,repeats,gpu):
    records=[]
    for js,wire in profiles:
        signal.alarm(1800);name=Path(js).parent.name
        workers=[Worker(baseline,gpu),Worker(candidate,gpu)]
        try:
            for worker in workers:
                for op,data in [(5,path_request(Path(wire))),(11,b''),(19,b''),(21,b''),(9,b''),(15,b'')]:worker.call(op,data)
            for size in sizes:
                print(f'{"GPU" if gpu else "CPU"} {name}: {size}x{size} material A/B',flush=True)
                tile=(-1024,1536,size,size,LOW,HIGH)
                for worker in workers:
                    descriptor=worker.call(18,tile_bytes((-1030,1530,size+12,size+12,LOW,HIGH)))
                    worker.call(13,descriptor);worker.call(16,tile_bytes(tile));worker.call(29);worker.call(22)
                before=[block_bytes(worker,tile) for worker in workers];assert before[0]==before[1]
                timings=[[],[]]
                for repetition in range(repeats):
                    for index in ((0,1) if repetition%2==0 else (1,0)):
                        start=time.perf_counter();workers[index].call(22);timings[index].append((time.perf_counter()-start)*1000)
                assert [block_bytes(worker,tile) for worker in workers]==before
                medians=list(map(statistics.median,timings))
                records.append(dict(profile=name,width=size,depth=size,gpu_required=gpu,
                    baseline_host_ms=timings[0],candidate_host_ms=timings[1],
                    baseline_median_ms=medians[0],candidate_median_ms=medians[1],ratio=medians[0]/medians[1],
                    all_column_bytes_sha256=hashlib.sha256(before[0]).hexdigest()))
        finally:
            for worker in workers:worker.close()
            signal.alarm(0)
    return records


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--baseline',type=Path,required=True)
    p.add_argument('--candidate',type=Path,default=ROOT/'build/bend/engine');p.add_argument('--profile',nargs=2,action='append',required=True)
    p.add_argument('--sizes',nargs='+',type=int,default=[32,64]);p.add_argument('--repeats',type=int,default=5)
    p.add_argument('--execution',choices=['both','cpu','gpu'],default='both')
    p.add_argument('--output',type=Path,default=ROOT/'build/bend/geometry-ab.json')
    args=p.parse_args()
    if args.repeats < 1 or any(size < 1 for size in args.sizes):
        p.error('repeats and tile sizes must be positive')
    modes=(False,True) if args.execution=='both' else (args.execution=='gpu',)
    report=dict(scope=__doc__,baseline_commit='d78c81ca209b2b04c80e543df7b692ebe5b35aa5',
        warmups=1,repetitions=args.repeats,cpu_workers=2,nice=10,
        own_compilation_and_other_tests_concurrent=False,other_host_load_controlled=False,
        executables={name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in [('baseline',args.baseline),('candidate',args.candidate)]},
        gpu_companions={name:hashlib.sha256(path.with_suffix(path.suffix+'.gpu').read_bytes()).hexdigest() for name,path in [('baseline',args.baseline),('candidate',args.candidate)]},
        profiles=[dict(name=Path(js).parent.name,rbp_sha256=hashlib.sha256(Path(wire).read_bytes()).hexdigest()) for js,wire in args.profile],
        results=[row for gpu in modes for row in compare(args.baseline,args.candidate,args.profile,args.sizes,args.repeats,gpu)])
    path=args.output;path.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: alternating material geometry A/B; {path}')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('material geometry A/B timeout')));main()
