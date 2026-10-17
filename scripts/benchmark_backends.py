#!/usr/bin/env python3
"""Complete lit-MCA workload, with the same registry/seed/coordinates per backend.

Run each backend separately, sequentially, after compiling. The first region is
reported separately; subsequent regions reuse the same engine/profile. This is
generation-to-file latency, not Minecraft activation or a codec microbenchmark.
Validation runs after measurement and does not enter timing/RSS summaries.
"""
import argparse
import ctypes as c
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import statistics
import struct
import subprocess
import threading
import time

from benchmark_support import percentile
from benchmark_limits import add_time_limit, supervise
from test_bend_compression import ROOT
from test_bend_chunks import palette, MAP_NAMES
from test_bend_formats import read_region
from test_bend_generated_regions import request as region_request
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_surface import tile_bytes
from test_bend_vegetation_worker import ObservedWorker


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    temporary = path.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


class Monitor:
    """Sum of generation-process RSS, sampled at 500 ms; not GPU allocation size."""
    def __init__(self):
        self.pids = [os.getpid()]
        self.peak = 0
        self.samples = 0
        self.load_min = [float('inf')] * 3
        self.load_max = [0.] * 3
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def run(self):
        while not self.stop.wait(.5):
            try:
                result = subprocess.run(['ps', '-p', ','.join(map(str, self.pids)), '-o', 'rss='],
                                        capture_output=True, text=True, timeout=2)
                self.peak = max(self.peak, sum(int(x) for x in result.stdout.split()) * 1024)
                self.samples += 1
                load = os.getloadavg()
                self.load_min = list(map(min, self.load_min, load))
                self.load_max = list(map(max, self.load_max, load))
            except (OSError, ValueError, subprocess.TimeoutExpired):
                pass

    def finish(self):
        self.stop.set()
        self.thread.join(timeout=3)
        return dict(sampled_peak_rss_bytes=self.peak, samples=self.samples,
                    load_min=self.load_min if self.samples else None, load_max=self.load_max,
                    scope='sum of driver and engine RSS; shared pages may be double-counted; GPU allocations are not measured')


class Bend:
    def __init__(self, artifact, profile, folder, monitor, event):
        # The wire conversion is deliberately counted in startup, as is Rust's
        # JSON parsing. Nothing substitutes generated data for either backend.
        self.event = event
        self.stages = []
        wire = folder / 'profile.rbp'
        wire.write_bytes(fixture_wire(profile, True))
        self.worker = ObservedWorker(artifact, True, folder / 'worker.log', '4GB')
        monitor.pids.append(self.worker.process.pid)
        self.event('worker', pid=self.worker.process.pid)
        try:
            handshake = struct.unpack('>5I', self.call('runtime handshake', 0))
            assert handshake == (1, 2, 0, 36, 1), handshake
            self.call('registry upload', 5, path_request(wire))
            for name, opcode in [('density programs', 11), ('material programs', 19),
                                 ('block palette', 21), ('geology recipes', 30), ('aquifer programs', 37),
                                 ('biome climate', 9), ('world bounds', 15), ('chunk catalog', 25),
                                 ('lighting optics', 60)]:
                self.call(name, opcode)
        except BaseException:
            self.abort()
            raise
        self.info = dict(execution='Bend 2.0.36 GPU_REQUIRED; irregular stages on CPU',
                         threads=2, gpu_heap='4GB', gpu_archive_sha256=sha(artifact.with_suffix('.gpu')),
                         startup_stages=self.stages)

    def call(self, name, opcode, payload=b''):
        self.event('stage_start', name=name, opcode=opcode)
        started = time.perf_counter()
        result = self.worker.call(opcode, payload)
        row = dict(name=name, opcode=opcode, host_ms=(time.perf_counter()-started)*1000)
        self.stages.append(row)
        self.event('stage_done', **row)
        return result

    def generate(self, seed, rx, rz, path, profile):
        self.stages = []
        x, z = rx*512, rz*512
        lo, hi = seed & 0xffffffff, (seed >> 32) & 0xffffffff
        descriptor = self.call('density descriptor', 18, tile_bytes((x-55,z-55,622,622,lo,hi)))
        self.call('density lattice', 13, descriptor)
        self.call('surface climate', 16, tile_bytes((x-33,z-33,578,578,lo,hi)))
        for name, opcode in [('shorelines',29), ('lakes',43), ('materials',22),
                             ('caves',33), ('aquifers',39), ('carving',35)]:
            self.call(name, opcode)
        expanded = tile_bytes((x-32,z-32,576,576,lo,hi))
        self.call('ores',50,expanded)
        self.call('cave decorations',51,expanded)
        core = tile_bytes((x,z,512,512,lo,hi))
        self.call('vegetation',53,core)
        self.call('structures',56)
        self.call('snow and freezing',54)
        assert struct.unpack('>4I',self.call('lighting',61,core)) == (512,512,900,25)
        ack = self.call('NBT/zlib/MCA',55,region_request(rx,rz,path,lo,hi,5023,0))
        assert len(ack) == 8200
        chunks, sectors = struct.unpack_from('>2I',ack)
        assert chunks == 1024 and path.stat().st_size == sectors*4096
        start = time.perf_counter()
        with path.open('r+b') as stream:
            assert stream.write(ack[8:]) == 8192
        self.stages.append(dict(name='opaque header publication',host_ms=(time.perf_counter()-start)*1000))
        return dict(stages=self.stages, generated_chunks=chunks)

    def close(self):
        if self.worker.process.poll() is None:
            self.worker.call(4)
        self.worker.close()

    def abort(self):
        process = self.worker.process
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
        process.stdin.close(); process.stdout.close(); self.worker.trace.close()


class Rust:
    def __init__(self, artifact, profile, folder, monitor, event):
        spec = importlib.util.spec_from_file_location('native_benchmark', ROOT/'scripts/native-region-benchmark.py')
        self.abi = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.abi)
        os.environ['RETINA_GPU_LIGHTING'] = '1'
        os.environ['RETINA_REGION_THREADS'] = '2'
        os.environ['RAYON_NUM_THREADS'] = '2'
        self.lib = c.CDLL(str(artifact))
        self.lib.retina_last_error.argtypes = [c.c_void_p,c.c_uint64]
        self.lib.retina_backend.argtypes = [c.c_void_p,c.c_uint64]
        self.check(self.lib.retina_initialize())
        self.lib.retina_register_profile.argtypes = [c.c_char_p,c.c_uint64,c.POINTER(c.c_uint32)]
        data = json.dumps(profile,separators=(',',':')).encode()
        self.profile = c.c_uint32()
        self.check(self.lib.retina_register_profile(data,len(data),c.byref(self.profile)))
        class Detailed(c.Structure):
            _fields_ = [('region',self.abi.Report),('stages',self.abi.Snapshot)]
        self.Detailed = Detailed
        self.lib.retina_generate_region_profiled.argtypes = [c.POINTER(self.abi.Request),c.c_char_p,c.c_uint64,
                c.c_int32,c.c_char_p,c.c_uint64,c.POINTER(Detailed),c.c_void_p]
        name = c.create_string_buffer(4096)
        self.lib.retina_backend(name,len(name))
        self.info = dict(execution=name.value.decode(), region_assembler_threads=2, global_rayon_threads=2,
                         note='Driver and asynchronous shader compilation can have additional threads')

    def check(self, code):
        if code:
            error = c.create_string_buffer(8192)
            self.lib.retina_last_error(error,len(error))
            raise RuntimeError(error.value.decode())

    def generate(self, seed, rx, rz, path, profile):
        request = self.abi.Request(seed,rx*32,rz*32,profile['geology_min_y'],profile['geology_height'],64,48,.008,self.profile.value)
        result = self.Detailed()
        raw = os.fsencode(path)
        self.check(self.lib.retina_generate_region_profiled(c.byref(request),raw,len(raw),5023,
                                                          b'minecraft:plains',16,c.byref(result),None))
        assert result.region.generated == 1024 and result.region.preserved == 0
        return dict(generated_chunks=result.region.generated, stage_flags=result.stages.flags,
                    stages_ms=dict(zip(self.abi.STAGES,(n/1e6 for n in result.stages.nanos))),
                    note='Rust stage counters mix parallel worker sums, host spans and overlapping GPU device time; do not sum them')

    def close(self):
        pass  # Process lifetime owns the native singleton.


def validate(path, profile, rx, rz):
    data = path.read_bytes()
    records = read_region(data)
    assert set(records) == set(range(1024))
    def key(state):
        return (state, ()) if isinstance(state,str) else (state['id'],tuple(sorted(state.get('properties',{}).items())))
    materials = {key(state) for state in profile['materials']}
    biomes = {biome['id'] for biome in profile['biomes']}
    first = profile['geology_min_y']//16
    end = first+profile['geology_height']//16
    for slot,record in records.items():
        tag = record['tag']
        lit = 0 < slot%32 < 31 and 0 < slot//32 < 31
        assert (tag['xPos'],tag['zPos'],tag['yPos'],tag['DataVersion']) == (rx*32+slot%32,rz*32+slot//32,first,5023)
        assert tag['Status'] == ('minecraft:light' if lit else 'minecraft:features') and tag['isLightOn'] == int(lit)
        assert set(tag['Heightmaps']) == set(MAP_NAMES)
        sections = tag['sections']
        assert len(sections) == end-first+(2 if lit else 0)
        assert {s['Y'] for s in sections} == set(range(first-int(lit),end+int(lit)))
        for section in sections:
            for channel in ('SkyLight','BlockLight'):
                # Minecraft permits absent all-zero light arrays. Rust omits
                # these; Bend emits explicit zero arrays. Both are valid.
                if channel in section: assert lit and len(section[channel]) == 2048
            if first <= section['Y'] < end:
                states = section['block_states']
                assert all(key(state) in materials for state in states['palette'])
                palette(states,4096,4)
                assert all(biome in biomes for biome in palette(section['biomes'],64,1))
        if lit and profile['lighting']['sky']:
            assert any(any(s.get('SkyLight',b'')) for s in sections), 'No sunlight in a lit Overworld chunk'
    return dict(chunks=len(records),lit_chunks=sum(r['tag']['isLightOn'] for r in records.values()),
                bytes=len(data),sha256=hashlib.sha256(data).hexdigest(),
                structure_starts=sum(len(r['tag']['structures']['starts']) for r in records.values()),
                block_entities=sum(len(r['tag']['block_entities']) for r in records.values()),
                entities=sum(len(r['tag'].get('entities',[])) for r in records.values()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend',choices=['rust','bend'],required=True)
    parser.add_argument('--artifact',type=Path,required=True)
    parser.add_argument('--profile',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--count',type=int,default=3,help='Warm regions, after one separately reported first region')
    parser.add_argument('--validate-only',action='store_true',help='Validate completed files without rerunning generation')
    add_time_limit(parser)
    args = parser.parse_args()
    if args.count < 1: parser.error('--count must be positive')
    args.artifact = args.artifact.resolve()
    args.out = args.out.resolve()
    if not args.validate_only:
        result = supervise(__file__, args.max_seconds, args.out)
        if result is not None:
            return result
    source = json.loads(args.profile.read_text())
    if args.validate_only:
        report = json.loads((args.out/'report.json').read_text())
        assert report['profile_sha256'] == sha(args.profile)
        assert report['backend'] == args.backend and report['artifact_sha256'] == sha(args.artifact)
        assert len(report['regions']) == args.count+1 and 'warm' in report
        for row in report['regions']:
            row['validation'] = validate(Path(row['path']),source,row['rx'],row['rz'])
        report['validation_harness_sha256'] = sha(Path(__file__))
        report.pop('error',None)
        report['status'] = 'passed'
        write_json(args.out/'report.json',report)
        print('PASS independent full-region validation',flush=True)
        return
    report = dict(schema='retina.complete-backend-benchmark.v1',status='running',backend=args.backend,
                  max_seconds=args.max_seconds,
                  artifact=str(args.artifact),artifact_sha256=sha(args.artifact),profile_sha256=sha(args.profile),
                  harness_sha256=sha(Path(__file__)),
                  seed=123456789,region_blocks=[512,512],min_y=source['geology_min_y'],height=source['geology_height'],
                  data_version=5023,registry=dict(biomes=len(source['biomes']),materials=len(source['materials']),
                  decorations=len(source['decorations']),structures=len(source['structures']['definitions'])),
                  environment=dict(platform=platform.platform(),cpu_count=os.cpu_count(),priority=os.getpriority(os.PRIO_PROCESS,0),
                                   overrides={k:v for k,v in os.environ.items() if k.startswith(('RETINA_','BEND_'))}),
                  cache_policy='fresh process; existing OS/driver/Bend executable-location caches retained',
                  timing_scope='startup through registry preparation; each region through closed lit compressed MCA and header publication; excludes validation and Minecraft activation',
                  regions=[])
    report['source_revision'] = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    report['source_diff_sha256'] = hashlib.sha256(subprocess.check_output(['git','diff','--binary','HEAD'],cwd=ROOT)).hexdigest()
    events = args.out/'events.jsonl'
    def event(event_type, **fields):
        row = dict(event=event_type,time=time.time(),**fields)
        with events.open('a') as stream: stream.write(json.dumps(row)+'\n')
        print(json.dumps(row),flush=True)
    engine = None
    monitor = Monitor()
    write_json(args.out/'report.json',report)
    try:
        start = time.perf_counter()
        engine = (Rust if args.backend == 'rust' else Bend)(args.artifact,source,args.out,monitor,event)
        report['startup_ms'] = (time.perf_counter()-start)*1000
        report['runtime'] = engine.info
        event('startup_done',ms=report['startup_ms'])
        # Exactly the same first/warm coordinates in both modes. Every output
        # directory is new, so neither backend can skip already generated chunks.
        for index in range(args.count+1):
            rx,rz = (8,8) if index == 0 else (index-2,0)
            folder = args.out/('first' if index == 0 else f'warm-{index}')
            folder.mkdir()
            path = folder/f'r.{rx}.{rz}.mca'
            event('region_start',index=index,rx=rx,rz=rz)
            start = time.perf_counter()
            result = engine.generate(report['seed'],rx,rz,path,source)
            ms = (time.perf_counter()-start)*1000
            row = dict(index=index,kind='first' if index == 0 else 'warm',rx=rx,rz=rz,path=str(path),ms=ms,bytes=path.stat().st_size,**result)
            report['regions'].append(row)
            write_json(args.out/'report.json',report)
            event('region_done',index=index,ms=ms,bytes=row['bytes'])
        report['memory'] = monitor.finish()
        engine.close(); engine = None
        warm = [r['ms'] for r in report['regions'] if r['kind']=='warm']
        report['warm'] = dict(samples=len(warm),mean_ms=statistics.mean(warm),median_ms=statistics.median(warm),
                             p95_ms=percentile(warm,95),chunks_per_second=1024000/statistics.mean(warm),
                             amortized_chunk_ms=statistics.mean(warm)/1024,
                             caveat='Small sample count; descriptive p95, not a stable tail-latency estimate')
        report['status'] = 'validating'
        write_json(args.out/'report.json',report)
        for row in report['regions']:
            row['validation'] = validate(Path(row['path']),source,row['rx'],row['rz'])
            event('region_validated',index=row['index'],**row['validation'])
        report['status'] = 'passed'
    except BaseException as error:
        report['status'] = 'failed'
        report['error'] = repr(error)
        raise
    finally:
        if not monitor.stop.is_set(): report['memory'] = monitor.finish()
        write_json(args.out/'report.json',report)
        if engine is not None:
            if report['status'] == 'failed' and isinstance(engine,Bend): engine.abort()
            else: engine.close()


if __name__ == '__main__':
    raise SystemExit(main())
