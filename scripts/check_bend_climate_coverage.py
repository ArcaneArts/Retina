#!/usr/bin/env python3
"""Opt-in, bounded CPU correctness check of Bend registry climate coverage.

Builds a small standalone driver, never the terrain engine or GPU program.
An external supervisor owns the total deadline, including the single compiler,
two-thread CPU fixture process and independent Python result verification.
This is not a terrain or throughput benchmark. Do not run without authorization
while local compilation/runtime checks are stopped.
"""
import argparse
import hashlib
import json
from pathlib import Path
import random
import struct
import subprocess

from benchmark_limits import positive_seconds, supervise
from bend_compiler import compiler_environment
from test_bend_compression import ROOT, DEFAULT_BEND
from test_bend_registry_climate import fixtures, projected, request
from test_bend_registry_noise import fixture_wire
from test_bend_climate import queries, verify


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def climate_only(source):
    """Copy used registry fields unchanged; do not synthesize placement data."""
    result = {'biomes': [{key: b[key] for key in ('id','flags','climate') if key in b}
                         for b in source['biomes']]}
    if 'climate_targets' in source:
        result['climate_targets'] = source['climate_targets']
    return result


def build_cases(output, profile_paths):
    profiles, invalid = fixtures(output/'fixtures')
    originals = []
    for i, path in enumerate(profile_paths):
        source = climate_only(json.loads(path.read_text()))
        wire = output/f'exported-climate-{i}.rbp'
        wire.write_bytes(fixture_wire(source,True))
        profiles.append((f'exported-climate-{i}',source,wire))
        originals.append(dict(path=str(path.resolve()),sha256=digest(path),
                              climate_subset_sha256=digest(wire)))
    cases = []
    rng = random.Random(20261017)
    for name, source, profile in profiles:
        targets = projected(source)
        points = queries(rng,64)
        points += [(t['lo'].copy(),mode) for t in targets if t.get('unmapped')
                   for mode in (0,1,2,8,16)]
        # Each driver request has an actual 512-query input bound. Split larger
        # custom profiles without silently dropping any unmapped-biome center.
        for offset in range(0,len(points),512):
            batch = points[offset:offset+512]
            for suffix, group in (('original',batch),('reversed',batch[::-1]),('empty',[])):
                stem = f'{name}-{offset}-{suffix}'
                query = output/(stem+'.queries');query.write_bytes(request(group))
                cases.append(dict(name=stem,source=source,profile=profile,query=query,
                                  result=output/(stem+'.result'),points=group,error=0))
    empty = output/'invalid.queries';empty.write_bytes(request([]))
    for i, profile in enumerate(invalid):
        cases.append(dict(name=f'invalid-{i}',profile=profile,query=empty,
                          result=output/f'invalid-{i}.result',error=713))
    return cases, originals


def check_cases(cases):
    records = []
    canonical = {}
    for case in cases:
        raw = case['result'].read_bytes()
        assert len(raw) >= 12,case['name']
        status, count, biomes = struct.unpack_from('>3I',raw)
        assert status == case['error'],(case['name'],status,case['error'])
        if status:
            assert (count,biomes,len(raw)) == (0,0,12),case['name']
            records.append(dict(name=case['name'],rejected_with=status))
            continue
        source = case['source']
        assert (count,biomes) == (len(source.get('climate_targets',[])),len(source['biomes']))
        verification = verify(raw[12:],projected(source),case['points'])
        stem, suffix = case['name'].rsplit('-',1)
        if suffix == 'original': canonical[stem] = raw[12:]
        elif suffix == 'reversed':
            original = canonical[stem]
            assert raw[12:] == b''.join(original[i:i+20] for i in range(len(original)-20,-1,-20))
        else: assert raw[12:] == b''
        records.append(dict(name=case['name'],**verification))
    return records


def run(args):
    output = args.output.resolve()
    cases, originals = build_cases(output,args.profile)
    source = ROOT/'bend/tests/climate-coverage.bend'
    binary = output/'climate-coverage'
    env = compiler_environment()
    xcode = Path('/Applications/Xcode.app/Contents/Developer')
    if xcode.exists():
        env['CC'] = str(xcode/'Toolchains/XcodeDefault.xctoolchain/usr/bin/clang')
        env['SDKROOT'] = str(xcode/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk')
    build_command = ['nice','-n','10',str(args.bend.resolve()),str(source),'-o',str(binary)]
    with (output/'compile.log').open('w') as log:
        subprocess.run(build_command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    command = ['nice','-n','10',str(binary)]
    for case in cases:
        command += [str(case[key]) for key in ('profile','query','result')]
    command += ['--threads','2','--gpu','off']
    with (output/'fixture.log').open('w') as log:
        subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    records = check_cases(cases)
    report = dict(scope=__doc__.strip(),status='passed',runtime='cpu',threads=2,
                  native_build_command=build_command,compiler_sha256=digest(args.bend),
                  binary_sha256=digest(binary),profiles=originals,cases=records,
                  source_sha256={str(p.relative_to(ROOT)):digest(p) for p in
                                 sorted((ROOT/'bend').rglob('*.bend'))},
                  limitations=['CPU component validation only; no GPU or terrain engine execution.',
                               'Exported profiles contribute unchanged climate fields, not full world-generation data.',
                               'No region output, surface integration or performance claim.'])
    (output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: {len(records)} CPU climate fixture cases; {output / "report.json"}',flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--output',type=Path,required=True,help='New directory for logs and results')
    parser.add_argument('--profile',type=Path,action='append',default=[],help='Saved registry JSON; copy only climate fields unchanged')
    parser.add_argument('--max-seconds',type=positive_seconds,default=30.,help='Total build/run/verification limit (default: 30 seconds)')
    args = parser.parse_args()
    supervised = supervise(str(Path(__file__).resolve()),args.max_seconds,args.output)
    if supervised is not None: raise SystemExit(supervised)
    run(args)


if __name__ == '__main__':
    main()
