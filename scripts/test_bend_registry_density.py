#!/usr/bin/env python3
"""Independently validate resident Bend numeric program projection and GPU queries.

Python supplies fixtures/reference equations only. Bend resolves the uploaded
registry tape, validates its numeric model and evaluates the resident programs.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import re
import signal
import struct
import time

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, configuration, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_density import Oracle, ins, queries, synthetic
from test_bend_noise import f32


def request(points):
    return struct.pack('>I', len(points)) + b''.join(struct.pack('>6I', *(x & 0xffffffff for x in q)) for q in points)


def verify(data, source, points):
    assert len(data) == len(points) * 24, (len(data), len(points))
    # Runtime parameters are F32; independently round the original registered
    # F64 values before applying the double-precision reference equations.
    model = json.loads(json.dumps(source['registry_program']), parse_float=lambda x: f32(float(x)))
    oracle = Oracle(model); maximum = 0.
    for q, actual in zip(points, struct.iter_unpack('>6f', data)):
        pid, x, y, z, low, high = q
        reference = oracle.evaluate(model['programs'][pid], (x, y, z), low, high)
        for channel, (a, b) in enumerate(zip(actual, reference)):
            assert math.isfinite(a), (q, channel, a, b)
            error = abs(a - b) / max(1., abs(b)); maximum = max(maximum, error)
            assert error < .00015, (q, channel, a, b, error)
    return maximum


def fixtures(folder):
    folder.mkdir(parents=True, exist_ok=True)
    model, programs = synthetic()
    nodes = programs[0]['nodes']
    # Extra programs are material programs, and are deliberately not numeric.
    # They remain losslessly resident without being interpreted by this command.
    model['programs'] = [dict(nodes=nodes, roots=[6, 10, 19, 26, 32, 37]), programs[-4], programs[-1],
                         dict(nodes=[ins(40)], roots=[0])]
    source = {'unrelated': '🌍 nodes is a schema key', 'registry_program': model,
              'biomes': [{'flags': 0}], 'climate_targets': []}
    constant = copy.deepcopy(source)
    constant['registry_program'] = {'noises': [], 'points': [[1<<40,0,0,0]],
        'programs': [dict(nodes=[ins(0, p=((1<<40) * (-1 if i%2 else 1), 0, 0, 0))], roots=[]) for i in range(3)]}
    default = copy.deepcopy(source)
    default['registry_program']['noises'][0].pop('horizontal_scale')
    result = []
    for name, profile, packed in [('mixed', source, False), ('packed', source, True),
                                  ('default_scale', default, True), ('constant', constant, True)]:
        path = folder / f'{name}.rbp'; path.write_bytes(fixture_wire(profile, packed))
        result.append((name, profile, path))
    invalid = []
    def bad(name, change):
        profile = copy.deepcopy(source); change(profile['registry_program'])
        invalid.append((name, profile))
    for name, profile in [('null', None), ('empty', {}), ('null_model', {'registry_program': None}),
                          ('list_model', {'registry_program': []})]: invalid.append((name, profile))
    for field in ('noises', 'points', 'programs'):
        bad('missing_' + field, lambda m, f=field: m.pop(f))
    for field in ('noises', 'points', 'programs', 'interpolations'):
        for value in (None, {}, [None]):
            bad(f'{field}_{str(value)}', lambda m, f=field, v=value: m.__setitem__(f, v))
    bad('too_few_programs', lambda m: m.__setitem__('programs', m['programs'][:2]))
    for field in ('nodes', 'roots'):
        bad('missing_' + field, lambda m, f=field: m['programs'][0].pop(f))
    for name, field, value in [('empty_nodes','nodes',[]), ('long_nodes','nodes',[ins(0)]*1025),
            ('float_root','roots',[0.0]), ('negative_root','roots',[-1]), ('outside_root','roots',[999]),
            ('too_many_roots','roots',[0]*7), ('boolean_root','roots',[True])]:
        bad(name, lambda m, f=field, v=value: m['programs'][0].__setitem__(f, v))
    for field in ('op', 'a', 'b', 'c', 'p'):
        bad('missing_node_' + field, lambda m, f=field: m['programs'][0]['nodes'][0].pop(f))
    for name, field, value in [('float_op','op',0.0), ('negative_op','op',-1), ('boolean_op','op',False),
            ('unsigned_overflow','a',1<<32), ('short_parameters','p',[0]*3),
            ('long_parameters','p',[0]*5), ('bool_parameter','p',[False]*4),
            ('large_parameter','p',[1.e200]*4)]:
        bad(name, lambda m, f=field, v=value: m['programs'][0]['nodes'][0].__setitem__(f, v))
    for name, node in [('unsupported_numeric',ins(40)), ('forward_dep',ins(4)), ('bad_axis',ins(29,3)),
            ('noise_index',ins(2,9)), ('field_forward',ins(28,0,0,0,(2,0,0,0))),
            ('bad_gradient',ins(3,p=(0,0,-1,1)))]:
        bad(name, lambda m, n=node: m['programs'][0]['nodes'].__setitem__(0,n))
    for field in ('frequency','amplitude','salt','coefficients'):
        bad('missing_noise_' + field, lambda m, f=field: m['noises'][0].pop(f))
    for name, field, value in [('float_salt','salt',1.0), ('large_salt','salt',1<<31),
            ('small_salt','salt',-(1<<31)-1), ('zero_frequency','frequency',0),
            ('large_frequency','frequency',1.e200), ('null_scale','horizontal_scale',None),
            ('zero_scale','horizontal_scale',0), ('empty_coefficients','coefficients',[]),
            ('long_coefficients','coefficients',[0]*33), ('bool_coefficient','coefficients',[True])]:
        bad(name, lambda m, f=field, v=value: m['noises'][0].__setitem__(f,v))
    bad('short_point', lambda m: m['points'].__setitem__(0,[0]*3))
    bad('bad_spline_child', lambda m: m['points'][0].__setitem__(2,.5))
    for field in ('input','cell'):
        bad('missing_field_' + field, lambda m, f=field: m['interpolations'][0].pop(f))
    for name, value in [('zero_cell',[0,8]), ('float_cell',[4.0,8.0]), ('long_cell',[4,8,4]),
                         ('large_cell',[1<<31,8])]:
        bad(name, lambda m, v=value: m['interpolations'][0].__setitem__('cell',v))
    bad('multiple_field_roots', lambda m: m['interpolations'][0]['input'].__setitem__('roots',[0,1]))
    paths = []
    for i, (name, profile) in enumerate(invalid):
        path = folder / f'invalid-{i}.rbp'; path.write_bytes(fixture_wire(profile, bool(i%2))); paths.append((name,path))
    return result, paths


def exercise(binary, profiles, invalid, gpu):
    worker = Worker(binary, gpu); rows = []; hashes = []; dispatches = 0; maximum = 0.
    folder = ROOT / 'build/bend/density-resident-profiles'
    broken = folder/'truncated.rbp'; broken.write_bytes(b'RBP1')
    def call(points):
        nonlocal dispatches
        dispatches += 1
        return worker.call(12, request(points))
    try:
        signal.alarm(240)
        assert worker.call(11,status=1) == struct.pack('>I',708)
        assert worker.call(12,request([]),status=1) == struct.pack('>I',716)
        assert worker.call(11,b'\0',status=1) == struct.pack('>I',603)
        worker.call(1,configuration()); grid = struct.pack('>5I',55,123,8,8,1)
        noise = worker.call(2,grid); dispatches += 1
        last = None
        for name, source, path in profiles:
            signal.alarm(240)
            worker.call(5,path_request(path))
            if name=='mixed':
                staged = folder/'deleted-after-ack.rbp'; staged.write_bytes(path.read_bytes())
                worker.call(5,path_request(staged)); staged.unlink()
            assert worker.call(12,request([]),status=1) == struct.pack('>I',716)
            assert worker.call(10,struct.pack('>I',0),status=1) == struct.pack('>I',714)
            # Independent climate and noise state must survive density operations.
            worker.call(9)
            model = source['registry_program']
            started = time.perf_counter()
            ack = worker.call(11)
            prepare_ms = (time.perf_counter()-started)*1000
            assert ack == struct.pack('>4I',3,len(model['noises']),len(model['points']),len(model.get('interpolations',[])))
            points = queries(model['programs'][:3], n=0)
            started = time.perf_counter(); data = call(points); query_ms = (time.perf_counter()-started)*1000
            maximum = max(maximum,verify(data,source,points)); hashes.append(hashlib.sha256(data).hexdigest())
            for selected in (points[:3],[],list(reversed(points))):
                current = call(selected)
                expected = data[:72] if len(selected)==3 else b'' if not selected else b''.join(data[i:i+24] for i in range(len(data)-24,-1,-24))
                assert current == expected; hashes.append(hashlib.sha256(current).hexdigest())
            assert worker.call(11,b'\0',status=1) == struct.pack('>I',603)
            assert worker.call(5,path_request(path.with_name('nonexistent.rbp')),status=1) == struct.pack('>I',702)
            assert worker.call(5,path_request(broken),status=1) == struct.pack('>I',703)
            assert call(points) == data
            for malformed in (b'', b'\0'*3, struct.pack('>I',4097), request(points)[:-1], request([])+b'\0',
                              request([(3,0,0,0,0,0)]), request([(-1,0,0,0,0,0)])):
                assert worker.call(12,malformed,status=1) == struct.pack('>I',603)
            assert worker.call(10,struct.pack('>I',0)) == b''; dispatches += 1
            assert worker.call(2,grid) == noise; dispatches += 1
            rows.append({'name':name,'queries':len(points),'validated_rows':3*len(points)+3,
                         'prepare_host_ms':prepare_ms,'first_batch_host_ms':query_ms,
                         'noise_climate_independence':'pass','failed_upload_retains_model':'pass',
                         'repeat_reorder_empty':'pass'})
            last = source,path,points,data
            print(f'{"GPU" if gpu else "CPU"}: {name}; {len(points)} resident numeric queries and retained state pass',flush=True)
        source,path = profiles[3][1:]; worker.call(5,path_request(path)); worker.call(11)
        small = queries(source['registry_program']['programs'],n=0)
        canonical = call(small); verify(canonical,source,small)
        large = (small*171)[:4096]
        assert len(large)==4096
        data = call(large); assert data == (canonical*171)[:4096*24]
        hashes.append(hashlib.sha256(data).hexdigest())
        for name,path in invalid:
            worker.call(5,path_request(path))
            assert worker.call(11,status=1) == struct.pack('>I',715),name
            assert worker.call(12,request([]),status=1) == struct.pack('>I',716),name
        source,path,points,data = last
        worker.call(5,path_request(path)); worker.call(11); assert call(points)==data
        worker.call(4); assert worker.process.wait(timeout=10)==0
        diagnostic = worker.process.stderr.read().decode()
        return {'mode':'gpu_required' if gpu else 'cpu','profiles':rows,'maximum_batch_queries':len(large),
                'invalid_typed_profiles':len(invalid),'requests_in_one_process':worker.id,
                'expected_dispatches':dispatches,'max_relative_or_absolute_error':maximum,
                'aggregate_sha256':hashlib.sha256(b''.join(bytes.fromhex(h) for h in hashes)).hexdigest(),
                'observed_metal_command_ms':[float(t) for t in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostic)]}
    finally:
        signal.alarm(0); worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND); parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true')
    parser.add_argument('--profile',nargs=2,action='append',metavar=('JSON','WIRE'),default=[])
    args=parser.parse_args(); binary=ROOT/'build/bend/engine'
    if not args.skip_build: build(args.bend,binary,ROOT/'bend/engine.bend')
    profiles,invalid=fixtures(ROOT/'build/bend/density-resident-profiles')
    report={'scope':'Resident numeric terrain program projection and bounded queries; material and region generation pending','profiles':[]}
    for source,path in args.profile:
        source,path=Path(source),Path(path); model=json.loads(source.read_text()); profiles.append((source.parent.name,model,path))
        report['profiles'].append({'name':source.parent.name,'json_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                                  'wire_sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    report['runs']=[exercise(binary,profiles,invalid,False),exercise(binary,profiles,invalid,True)]
    report['cpu_gpu_bytes_equal']=report['runs'][0]['aggregate_sha256']==report['runs'][1]['aggregate_sha256']
    if args.prove_metal:
        diagnostic=compile_metal_observer(args.bend,ROOT/'bend/engine.bend',ROOT/'build/bend/registry-density-metal-probe')
        observed=exercise(diagnostic,profiles,invalid,True); times=observed['observed_metal_command_ms']
        assert len(times)==observed['expected_dispatches'], (len(times),observed['expected_dispatches'])
        assert all(t>0 for t in times)
        assert observed['aggregate_sha256']==report['runs'][1]['aggregate_sha256']
        report['metal_observation']={'diagnostic_only':True,'command_buffers':len(times),
            'device_ms':{'min':min(times),'max':max(times),'sum':sum(times)},'output_bytes_equal':True}
    for name in ('bend/registry_density.bend','bend/density_batch.bend','bend/engine.bend','scripts/test_bend_registry_density.py'):
        report.setdefault('source_sha256',{})[name]=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
    destination=ROOT/'build/bend/registry-density-tests.json'; destination.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: resident numeric model, typed validation, invalidation and CPU/GPU queries; {destination}')


if __name__=='__main__': main()
