#!/usr/bin/env python3
"""Measure Bend ingress with existing protocol rejection, without recompiling.

Each nonempty NBT payload is sent to the empty-only handshake command. The
engine consumes the entire frame, rejects it with error 603, and remains usable.
This includes pipe IO, input allocation/decoding, disposal and a four-byte error
reply. It excludes compression and normal compressed-output transport; it is
not an exact quantity to subtract from compression timings.
"""
import argparse
import json
from pathlib import Path
import statistics
import struct
import subprocess
import time

from benchmark_compression_corpus import Endpoint, ROOT, digest, write_json
from benchmark_limits import add_time_limit, supervise


def run(args):
    manifest_path = args.corpus / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    inputs = [(args.corpus / row['file']).read_bytes() for row in manifest['samples']]
    assert inputs and all(inputs), 'Empty payloads are valid handshakes, not rejection controls'
    for row, data in zip(manifest['samples'], inputs):
        assert len(data) == row['bytes'] and digest(data) == row['sha256']
    artifact = args.artifact.resolve()
    report = dict(status='running', scope=__doc__.strip(),
        source_revision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        corpus_sha256=digest(manifest_path.read_bytes()), artifact_sha256=digest(artifact.read_bytes()),
        harness_sha256=digest(Path(__file__).read_bytes()), threads=2, gpu=False,
        chunks=len(inputs), input_bytes=sum(map(len, inputs)), warmups=1, repeats=3, samples_ms=[])
    output = args.out / 'report.json'
    write_json(output, report)
    worker = Endpoint(artifact, 'bend', args.out / 'worker.log')
    try:
        assert struct.unpack('>5I', worker.call(0)) == (1, 2, 0, 37, 0)
        for iteration in range(4):
            started = time.perf_counter()
            replies = [worker.call(0, data, status=1) for data in inputs]
            elapsed = (time.perf_counter() - started) * 1000
            assert replies == [struct.pack('>I', 603)] * len(inputs)
            if iteration:
                report['samples_ms'].append(elapsed)
                write_json(output, report)
        assert struct.unpack('>5I', worker.call(0)) == (1, 2, 0, 37, 0)
        worker.call(4)
        report.update(status='passed', mean_ms=statistics.mean(report['samples_ms']))
    except BaseException as failure:
        report.update(status='failed', error=repr(failure))
        raise
    finally:
        worker.close()
        write_json(output, report)
    print(json.dumps(report), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    add_time_limit(parser)
    args = parser.parse_args()
    result = supervise(__file__, args.max_seconds, args.out)
    if result is not None:
        return result
    run(args)


if __name__ == '__main__':
    raise SystemExit(main())
