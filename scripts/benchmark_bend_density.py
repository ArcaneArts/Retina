#!/usr/bin/env python3
"""A/B warm resident density batches; explicitly not complete-region throughput.

Compile executables before invoking this harness. Work is serial, reduced
priority, two CPU workers; A/B order alternates. Model loading/preparation and
independent reference checks are outside each measured batch.
"""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import statistics
import subprocess
import time

from test_bend_compression import ROOT
from test_bend_engine import Worker
from test_bend_registry import path_request
from test_bend_registry_density import request, verify
from test_bend_density import queries


def rss(worker):
    result = subprocess.run(['ps', '-o', 'rss=', '-p', str(worker.process.pid)],
                            check=True, capture_output=True, text=True)
    return int(result.stdout.strip()) * 1024


def exercise(baseline, candidate, profiles, repeats, gpu):
    workers = []; rows = []
    try:
        for executable in (baseline,candidate): workers.append(Worker(executable,gpu))
        for source, wire in profiles:
            signal.alarm(240)
            model = json.loads(source.read_text()); points = queries(model['registry_program']['programs'][:3],n=0)
            batch = request(points)
            for worker in workers:
                worker.call(5,path_request(wire)); worker.call(11)
            reference = workers[0].call(12,batch)
            current = workers[1].call(12,batch)
            assert current == reference, (source,'warm output differs')
            error = verify(reference,model,points)
            times = [[],[]]
            for iteration in range(repeats):
                for index in ([0,1] if iteration%2==0 else [1,0]):
                    start = time.perf_counter(); output = workers[index].call(12,batch)
                    elapsed = (time.perf_counter()-start)*1000
                    assert output == reference, (source,index,iteration,'repeated output differs')
                    times[index].append(elapsed)
            medians = [statistics.median(values) for values in times]
            row = {'profile':source.parent.name,'queries_per_batch':len(points),'warmups_per_executable':1,
                   'repeats':repeats,'baseline_host_batch_ms':times[0],'candidate_host_batch_ms':times[1],
                   'baseline_median_ms':medians[0],'candidate_median_ms':medians[1],
                   'median_speed_ratio':medians[0]/medians[1], 'output_bytes_identical':True,
                   'reference_max_relative_or_absolute_error':error,
                   'output_sha256':hashlib.sha256(reference).hexdigest(),
                   'resident_rss_after_batches_bytes':[rss(worker) for worker in workers],
                   'json_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                   'wire_sha256':hashlib.sha256(wire.read_bytes()).hexdigest()}
            rows.append(row)
            print(f'{"GPU" if gpu else "CPU"} {source.parent.name}: {medians[0]:.3f} -> {medians[1]:.3f} ms / {len(points)} queries; {row["median_speed_ratio"]:.2f}x',flush=True)
        for worker in workers: worker.call(4)
        return {'mode':'gpu_required' if gpu else 'cpu','profiles':rows}
    finally:
        signal.alarm(0)
        failures = []
        for worker in workers:
            try: worker.close()
            except Exception as failure: failures.append(failure)
        if failures: raise failures[0]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline',type=Path,required=True); parser.add_argument('--candidate',type=Path,required=True)
    parser.add_argument('--profile',type=Path,nargs=2,action='append',required=True,metavar=('JSON','WIRE'))
    parser.add_argument('--repeats',type=int,default=3); parser.add_argument('--output',type=Path,default=ROOT/'build/bend/density-ab.json')
    args=parser.parse_args()
    if args.repeats < 1: parser.error('repeats must be positive')
    report={'scope':'Warm resident numeric density batches only; no complete-region throughput, lighting or compression benchmark',
            'cpu_workers_per_process':2,'process_nice':10,'order':'serial alternating A/B after one warmup per executable',
            'memory':'RSS sampled after batches, not peak memory',
            'baseline_binary_sha256':hashlib.sha256(args.baseline.read_bytes()).hexdigest(),
            'candidate_binary_sha256':hashlib.sha256(args.candidate.read_bytes()).hexdigest(),
            'runs':[exercise(args.baseline,args.candidate,args.profile,args.repeats,gpu) for gpu in (False,True)]}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: independently checked, byte-identical warm A/B batches; {args.output}')


def timeout(_signal, _frame):
    raise TimeoutError('resident density A/B workload exceeded 240 seconds')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,timeout)
    main()
