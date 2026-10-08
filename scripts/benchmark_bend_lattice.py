#!/usr/bin/env python3
"""Compare direct and resident-lattice queries at identical graph vertices.

Compilation must finish first. This is warm component latency, including IPC,
not world-generation throughput. Cache construction is measured separately.
"""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import statistics
import struct
import time

from test_bend_compression import ROOT
from test_bend_engine import Worker
from test_bend_registry import path_request
from test_bend_registry_density import request
from test_bend_density_lattice import descriptor, encoded, layout, Reference, LOW, HIGH
from benchmark_bend_density import rss


def exercise(binary, profiles, repeats, gpu):
    worker=Worker(binary,gpu); rows=[]
    try:
        for source,path in profiles:
            signal.alarm(240)
            profile=json.loads(source.read_text());reference=Reference(profile)
            worker.call(5,path_request(path));worker.call(11)
            desc=descriptor(2,(-20,-64,-20),(12,-32,12));origin,counts,steps=layout(desc)
            start=time.perf_counter();ack=worker.call(13,encoded(desc));prepare=(time.perf_counter()-start)*1000
            assert ack==struct.pack('>4I',*counts,counts[0]*counts[1]*counts[2])
            # Distinct interior/edge vertices: cached and direct values must be
            # identical here. Between vertices the lattice is an approximation.
            points=[(2,origin[0]+(i%counts[0])*steps[0],origin[1]+((i//counts[0])%counts[1])*steps[1],
                origin[2]+((i*7)%counts[2])*steps[2],LOW,HIGH) for i in range(24)]
            body=request(points)
            direct=worker.call(12,body);cached=worker.call(14,body)
            assert direct==cached,(source,'vertex output changed')
            error=reference.verify(cached,points,desc)
            times={12:[],14:[]}
            for iteration in range(repeats):
                for op in ((12,14) if iteration%2==0 else (14,12)):
                    start=time.perf_counter();output=worker.call(op,body);elapsed=(time.perf_counter()-start)*1000
                    assert output==direct
                    times[op].append(elapsed)
            medians={op:statistics.median(values) for op,values in times.items()}
            rows.append({'profile':source.parent.name,'vertices':counts[0]*counts[1]*counts[2],
                'construction_host_ms':prepare,'queries_per_batch':len(points),'warmups_per_path':1,'repeats':repeats,
                'direct_host_batch_ms':times[12],'cached_host_batch_ms':times[14],
                'direct_median_ms':medians[12],'cached_median_ms':medians[14],
                'median_query_speed_ratio':medians[12]/medians[14],
                'output_bytes_equal_at_vertices':True,'reference_max_relative_or_absolute_error':error,
                'resident_rss_after_batches_bytes':rss(worker),'output_sha256':hashlib.sha256(cached).hexdigest(),
                'json_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                'wire_sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
            print(f'{"GPU" if gpu else "CPU"} {source.parent.name}: {medians[12]:.3f} -> {medians[14]:.3f} ms / 24 vertex queries; construction {prepare:.3f} ms',flush=True)
        worker.call(4)
        return {'mode':'gpu_required' if gpu else 'cpu','profiles':rows}
    finally:
        signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',type=Path,default=ROOT/'build/bend/engine')
    parser.add_argument('--profile',type=Path,nargs=2,action='append',required=True,metavar=('JSON','WIRE'))
    parser.add_argument('--repeats',type=int,default=3);parser.add_argument('--output',type=Path,default=ROOT/'build/bend/lattice-query-benchmark.json')
    args=parser.parse_args()
    if args.repeats<1:parser.error('repeats must be positive')
    report={'scope':'Warm resident versus direct numeric density vertex queries, not complete-region throughput',
        'cpu_workers':2,'process_nice':10,'compilation':'Separate; completed before this harness',
        'order':'Serial alternating direct/cached after one warmup each','memory':'Sampled RSS after batches, not peak',
        'binary_sha256':hashlib.sha256(args.binary.read_bytes()).hexdigest(),
        'runs':[exercise(args.binary,args.profile,args.repeats,gpu) for gpu in (False,True)]}
    report['source_sha256']={name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in
        ('bend/density_lattice.bend','bend/engine.bend','scripts/benchmark_bend_lattice.py')}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: independently checked, identical direct/cached vertex outputs; {args.output}')


def timeout(_signal,_frame):
    raise TimeoutError('lattice benchmark profile exceeded 240 seconds')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,timeout)
    main()
