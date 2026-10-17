#!/usr/bin/env python3
"""Compare the actual Rust/Bend codecs on identical finished chunk NBT.

This measures serial persistent-process compression requests, including framing
and pipe transport, not codec-only time or parallel region throughput. Corpus
preparation, compilation, startup, independent validation and disk output are
outside the measured requests. Run after complete-region benchmarks finish.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import struct
import subprocess
import time
import zlib

from benchmark_backends import Monitor, write_json
from benchmark_support import percentile
from benchmark_limits import add_time_limit, supervise
from test_bend_binary import NbtReader
from test_bend_compression import ROOT
from test_bend_engine import Worker

SLOTS = (0, 33, 264, 528, 792, 1023)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inflate(data):
    decoder = zlib.decompressobj()
    result = decoder.decompress(data) + decoder.flush()
    assert decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail
    return result


def prepare(reports, corpus):
    corpus.mkdir(parents=True, exist_ok=False)
    sources, samples = [], []
    for folder in reports:
        report = json.loads((folder / 'report.json').read_text())
        source_index = len(sources)
        sources.append(dict(backend=report['backend'], profile_sha256=report['profile_sha256'],
                            report_sha256=digest((folder / 'report.json').read_bytes()),
                            artifact_sha256=report['artifact_sha256']))
        for row in report['regions']:
            path = Path(row['path'])
            data = path.read_bytes()
            region_hash = digest(data)
            assert region_hash == row['validation']['sha256'], f'Changed MCA: {path}'
            for slot in SLOTS:
                location = int.from_bytes(data[slot*4:slot*4+4], 'big')
                offset, sectors = location >> 8, location & 255
                assert offset >= 2 and sectors > 0
                at = offset*4096
                length = int.from_bytes(data[at:at+4], 'big')
                assert 1 < length <= sectors*4096-4 and data[at+4] == 2
                raw = inflate(data[at+5:at+4+length])
                reader = NbtReader(raw)
                assert reader.number('>B') == 10 and reader.text() == ''
                tag = reader.tag(10)
                assert reader.offset == len(raw) and tag['DataVersion'] == 5023
                assert (tag['xPos'], tag['zPos']) == (row['rx']*32+slot%32, row['rz']*32+slot//32)
                name = f'{source_index}-{report["backend"]}-{row["rx"]}-{row["rz"]}-{slot}.nbt'
                (corpus / name).write_bytes(raw)
                samples.append(dict(file=name, bytes=len(raw), sha256=digest(raw), source=source_index,
                                    region_file=str(path), region_sha256=region_hash, slot=slot,
                                    lit=bool(tag['isLightOn']), sections=len(tag['sections']),
                                    block_entities=len(tag['block_entities']),
                                    entities=len(tag.get('entities', []))))
    assert sources and samples
    write_json(corpus / 'manifest.json', dict(schema='retina.finished-nbt-corpus.v1',
               selection='six fixed slots per complete region; four interior and two boundary chunks',
               slots=SLOTS, sources=sources, samples=samples))
    print(f'Prepared {len(samples)} original NBT payloads in {corpus}', flush=True)


class Endpoint(Worker):
    def __init__(self, artifact, backend, log):
        self.trace = log.open('wb')
        command = ['nice', '-n', '10', str(artifact)]
        if backend == 'bend':
            command += ['cpu', '--threads', '2', '--gpu', 'off']
        try:
            self.process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.PIPE,
                                            stdout=subprocess.PIPE, stderr=self.trace,
                                            env=dict(os.environ, BEND_NO_TELEMETRY='1'))
        except BaseException:
            self.trace.close()
            raise
        self.id = 0

    def close(self):
        process = self.process
        process.stdin.close()
        try:
            code = process.wait(timeout=10)
            assert code == 0, f'Compression worker exited {code}; see worker.log'
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            process.stdout.close()
            self.trace.close()


def run(args):
    manifest_path = args.corpus / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    inputs = [(args.corpus / row['file']).read_bytes() for row in manifest['samples']]
    assert inputs
    for row, data in zip(manifest['samples'], inputs):
        assert len(data) == row['bytes'] and digest(data) == row['sha256'], row['file']
    artifact = args.artifact.resolve()
    report = dict(schema='retina.compression-corpus.v1', status='running', backend=args.backend,
                  max_seconds=args.max_seconds,
                  corpus_sha256=digest(manifest_path.read_bytes()), input_bytes=sum(map(len, inputs)),
                  chunks=len(inputs), artifact=str(artifact), artifact_sha256=digest(artifact.read_bytes()),
                  harness_sha256=digest(Path(__file__).read_bytes()),
                  source_revision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                  environment=dict(platform=platform.platform(), priority=os.getpriority(os.PRIO_PROCESS, 0)),
                  scope=__doc__.strip(), samples=[], warmups=args.warmups, repeats=args.repeats,
                  execution='CPU; serial compression requests; Bend runtime has two workers; Rust codec is single-threaded')
    output = args.out / 'report.json'
    write_json(output, report)
    worker, monitor = None, None
    try:
        start = time.perf_counter()
        worker = Endpoint(artifact, args.backend, args.out / 'worker.log')
        handshake = worker.call(0)
        if args.backend == 'bend':
            assert struct.unpack('>5I', handshake) == (1, 2, 0, 36, 0)
        else:
            assert handshake == b'Rust libdeflater; compression level 1; serial requests'
        report['startup_ms'] = (time.perf_counter()-start)*1000
        monitor = Monitor()
        monitor.pids.append(worker.process.pid)
        outputs, expected_hashes = [], None
        for iteration in range(args.warmups + args.repeats):
            # The clock covers only request construction, pipes, and compression.
            # Validation and hashing happen after the entire measured corpus.
            start = time.perf_counter()
            outputs = [worker.call(3, data) for data in inputs]
            elapsed = (time.perf_counter()-start)*1000
            hashes = [digest(data) for data in outputs]
            if expected_hashes is None:
                expected_hashes = hashes
            assert hashes == expected_hashes, 'Compression changed between identical requests'
            for raw, compressed in zip(inputs, outputs):
                assert inflate(compressed) == raw
            if iteration >= args.warmups:
                report['samples'].append(elapsed)
                write_json(output, report)
        report['memory'] = monitor.finish()
        monitor = None
        # Fast native runs may complete before the first 500 ms monitor tick.
        # A post-run live-process snapshot supplies an explicit lower bound.
        rss = subprocess.check_output(['ps', '-p', f'{os.getpid()},{worker.process.pid}', '-o', 'rss='], text=True)
        report['post_run_rss_bytes'] = sum(map(int, rss.split()))*1024
        worker.call(4)
        worker.close()
        worker = None
        report['outputs'] = []
        for row, data in zip(manifest['samples'], outputs):
            path = args.out / (row['file']+'.zlib')
            path.write_bytes(data)
            report['outputs'].append(dict(file=path.name, bytes=len(data), sha256=digest(data)))
        samples = report['samples']
        mean = statistics.mean(samples)
        report.update(status='passed', compressed_bytes=sum(map(len, outputs)),
                      mean_ms=mean, median_ms=statistics.median(samples), p95_ms=percentile(samples, 95),
                      chunks_per_second=len(inputs)*1000/mean,
                      mib_per_second=report['input_bytes']/(1024*1024)*1000/mean)
        write_json(output, report)
        print(json.dumps({k:report[k] for k in ('backend', 'chunks', 'mean_ms', 'compressed_bytes', 'mib_per_second')}), flush=True)
    except BaseException as failure:
        report.update(status='failed', error=repr(failure))
        write_json(output, report)
        raise
    finally:
        if monitor is not None:
            monitor.finish()
        if worker is not None:
            worker.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prep = commands.add_parser('prepare')
    prep.add_argument('--regions', nargs='+', type=Path, required=True)
    prep.add_argument('--corpus', type=Path, required=True)
    bench = commands.add_parser('run')
    bench.add_argument('--backend', choices=('rust', 'bend'), required=True)
    bench.add_argument('--artifact', type=Path, required=True)
    bench.add_argument('--corpus', type=Path, required=True)
    bench.add_argument('--out', type=Path, required=True)
    bench.add_argument('--warmups', type=int, default=5)
    bench.add_argument('--repeats', type=int, default=20)
    add_time_limit(bench)
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare(args.regions, args.corpus)
    else:
        if args.warmups < 0 or args.repeats < 1:
            parser.error('warmups must be nonnegative and repeats must be positive')
        result = supervise(__file__, args.max_seconds, args.out)
        if result is not None:
            return result
        run(args)


if __name__ == '__main__':
    raise SystemExit(main())
