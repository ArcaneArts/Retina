#!/usr/bin/env python3
"""Independently validate Bend cave-profile projection and actual GPU noise.

This is a registered subsurface component check. It does not claim carved
terrain, cave biome volumes, fluids, or complete world-generator integration.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import random
import re
import signal
import struct
import zlib

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import Document, path_request
from test_bend_registry_noise import fixture_wire
from test_bend_material_columns import fixtures as material_fixtures, decode as decode_columns
from test_bend_generated_chunks import request as chunk_request, validate as validate_chunk
from test_bend_surface import tile_bytes, query_bytes
from test_bend_noise import MASK, f32, mix_hash, perlin


def encode(queries):
    return struct.pack('>I', len(queries)) + b''.join(struct.pack('>5I', *(v & MASK for v in q)) for q in queries)


def metadata_request(biomes):
    return struct.pack(f'>{len(biomes)+1}I', len(biomes), *biomes)


def reference(noise, channel, query):
    x, y, z, low, high = (v & MASK for v in query)
    frequency, amplitude = f32(noise['frequency']), f32(noise['amplitude'])
    total, weights, persistence = 0., 0., 1.
    seed = low ^ mix_hash(high)
    for octave, modifier in enumerate(noise['modifiers']):
        if frequency > .125:
            break
        weight = f32(max(0., f32(modifier)) * persistence)
        if weight > 0:
            value = perlin(x, y, z, frequency, (seed + channel*7919 + octave*1013) & MASK, 0)
            total += value * weight
            weights = f32(weights + weight)
        frequency, persistence = f32(frequency*2), f32(persistence*.5)
    return f32(total / max(weights, f32(.0001)) * amplitude)


def expected_carver(value):
    h = value['height']
    words = [value['kind'], h['min'] & MASK, h['max'] & MASK, int(h['triangle']), h['plateau']]
    floats = [value['probability'], value['thickness'], value['horizontal'], value['vertical'], value['count'],
              value['floor'], value['room'], value.get('width_smoothness', 0), value.get('vertical_default', 0),
              *value.get('thickness_range', [0, 0]), *value.get('horizontal_range', [0, 0]),
              *value.get('distance_range', [0, 0]), value.get('vertical_center', 0), 0.,
              *value.get('vertical_range', [0, 0]), *value.get('rotation_range', [0, 0])]
    return words, [f32(x) for x in floats]


def verify_carvers(raw, source, dictionary, indices):
    at, checked = 0, 0
    for index in indices:
        count, = struct.unpack_from('>I', raw, at); at += 4
        want = source['biomes'][index]['carvers']
        assert count == len(want), (index, count, len(want))
        for value in want:
            row = struct.unpack_from('>6I21f', raw, at); at += 108
            assert dictionary[row[0]] == value['id']
            words, floats = expected_carver(value)
            assert list(row[1:6]) == words
            assert list(row[6:]) == floats, (index, value['id'], row[6:], floats)
            checked += 1
    assert at == len(raw)
    return checked


def fixtures(folder):
    folder.mkdir(parents=True, exist_ok=True)
    base = copy.deepcopy(material_fixtures(folder/'materials')[0][0][1])
    base.update(geology_min_y=-32, geology_height=64)
    base.update(cave_noises=[dict(frequency=.00390625*(i+1), amplitude=.7+i*.03,
        modifiers=[.5, 1., 2., 1., 0., -2., .5, 1.]) for i in range(6)],
        carveable=[False, True, True, True, True, False], lava=4,
        lava_level=base['geology_min_y']+3, heightmap_masks=[0, 63, 63, 63, 63, 1])
    common = dict(id='retina:cave', kind=0, probability=.15, height=dict(min=-56, max=180, triangle=False, plateau=0),
        count=7., thickness=1.5, horizontal=1.05, vertical=.9, floor=-.7, room=.5)
    canyon = dict(common, id='retina:canyon', kind=1, probability=.02,
        height=dict(min=-32, max=256, triangle=True, plateau=3), count=1.,
        thickness_range=[.25, 2.75], horizontal_range=[.5, 1.5], distance_range=[.8, 1.2],
        width_smoothness=3., vertical_default=.7, vertical_center=.2, vertical_range=[.2, 1.8], rotation_range=[-.125, .125])
    for i, biome in enumerate(base['biomes']):
        biome['id'] = f'retina:test_{i}'
        biome['carvers'] = copy.deepcopy([common, canyon][:2-i%3]) if i%3 != 2 else []
    profiles = []
    def good(name, change=lambda _: None):
        source=copy.deepcopy(base); change(source)
        path=folder/(name+'.rbp'); path.write_bytes(fixture_wire(source, len(profiles)%2 == 0)); profiles.append((name, source, path))
    good('mixed')
    good('nyquist', lambda s:s['cave_noises'].__setitem__(0, dict(frequency=.125, amplitude=1., modifiers=[1., 1e20, 1e30])))
    good('filtered', lambda s:s['cave_noises'].__setitem__(1, dict(frequency=.126, amplitude=1., modifiers=[1., 1.])))
    good('zero', lambda s:s['cave_noises'].__setitem__(2, dict(frequency=0., amplitude=0., modifiers=[-1., 0., -2.])))
    good('large_finite_weight', lambda s:s['cave_noises'].__setitem__(0, dict(frequency=.125, amplitude=1., modifiers=[3.4e38])))
    good('empty_carvers', lambda s:[b.update(carvers=[]) for b in s['biomes']])
    invalid=[]
    def bad(name, change):
        source=copy.deepcopy(base);change(source)
        path=folder/('invalid-'+name+'.rbp');path.write_bytes(fixture_wire(source, True));invalid.append((name, path))
    for field in ('cave_noises', 'carveable', 'lava', 'lava_level', 'geology_min_y', 'geology_height'):
        bad('missing_'+field, lambda s,f=field:s.pop(f))
    for name, value in [('negative',-1),('outside',len(base['materials'])),('float',4.)]:
        bad('lava_'+name, lambda s,v=value:s.update(lava=v))
    for name, value in [('zero',0),('large',4097),('float',64.)]:
        bad('height_'+name, lambda s,v=value:s.update(geology_height=v))
    bad('y_overflow', lambda s:s.update(geology_min_y=2147483647, lava_level=2147483647))
    bad('lava_below_world', lambda s:s.update(lava_level=s['geology_min_y']-1))
    bad('lava_above_world', lambda s:s.update(lava_level=s['geology_min_y']+s['geology_height']))
    bad('noises_short', lambda s:s['cave_noises'].pop())
    bad('noises_long', lambda s:s['cave_noises'].append(copy.deepcopy(s['cave_noises'][0])))
    for field, values in [('frequency',[-.1,1e39,-1e39,1073741952.]),
                          ('amplitude',[-1.,1e39,-1e39,1000001.]),
                          ('modifiers',[[],[0.]*33,[1.,-1e39],[3.4e38,3.4e38]])]:
        for i, value in enumerate(values):bad(f'noise_{field}_{i}', lambda s,f=field,v=value:s['cave_noises'][0].__setitem__(f,v))
    for name, value in [('short',[True]),('wrong_type',[0]*len(base['materials']))]:
        bad('flags_'+name, lambda s,v=value:s.update(carveable=v))
    for name, change in [('missing', lambda b:b.pop('carvers')),('too_many',lambda b:b.update(carvers=[copy.deepcopy(common) for _ in range(5)]))]:
        bad('carvers_'+name, lambda s,c=change:c(s['biomes'][0]))
    for field, values in [('id',['',1]),('kind',[2,-1,0.]),('probability',[-.1,1.1,1e39]),
                          ('thickness',[-1.,-1e39]),('horizontal',[0.,-1.]),('vertical',[0.,1e39]),
                          ('count',[-1.]),('floor',[1e39]),('room',[-1e39]),
                          ('height',[dict(min=180,max=-56,triangle=False,plateau=0),dict(min=-56,max=180,triangle=0,plateau=0),
                                     dict(min=-56,max=180,triangle=False,plateau=-1)]),
                          ('distance_range',[[1.,0.],[0.],[0.,1e39]]),('rotation_range',[[.5,-.5]])]:
        for i, value in enumerate(values):bad(f'carver_{field}_{i}', lambda s,f=field,v=value:s['biomes'][0]['carvers'][0].__setitem__(f,v))
    return profiles, invalid


def exercise(binary, profiles, invalid, gpu):
    worker=Worker(binary,gpu);hashes=[];checked=carvers=dispatches=0;maximum=0.;records=[]
    def call(op, data=b'', status=0):
        nonlocal dispatches
        signal.alarm(300)
        result=worker.call(op,data,status=status)
        if op==31 and status==0:dispatches+=1
        return result
    def error(op, data, code):
        assert call(op,data,1)==struct.pack('>I',code),(op,code)
    def prepare(path):
        call(5,path_request(path));call(11);call(19);call(21)
    try:
        error(30,b'',727);error(31,encode([]),737);error(32,metadata_request([]),737)
        rng=random.Random(20261008)
        points=[(-2147483648,-64,2147483647,0,0),(-30000000,319,29999999,0x12345678,0x80000001),
                (-513,-1,-512,MASK,MASK),(0,0,0,0,0),(1001,65,2003,0,1),(1001,65,2003,0,0)]
        points += [(rng.randrange(-(1<<31),1<<31),rng.randrange(-128,512),rng.randrange(-(1<<31),1<<31),
                    rng.getrandbits(32),rng.getrandbits(32)) for _ in range(58)]
        extremes=[(3,3,3,seed,0) for seed in range(2048) if abs(perlin(3,3,3,.125,seed,0))>1.05][:16]
        assert extremes
        points += extremes
        points += [(x, y, z,0x12345678,0x80000001) for x in range(-8,8) for y in (-60,-12,40,100) for z in range(-8,8)]
        for name,source,path in profiles:
            print('checking', 'GPU' if gpu else 'CPU', name, flush=True)
            prepare(path);error(31,encode([]),737)
            want=[6,len(source['biomes']),sum(len(b['carvers']) for b in source['biomes']),len(source['materials']),
                  source['lava'],source['lava_level']&MASK,source['geology_min_y']&MASK,source['geology_height']]
            assert list(struct.unpack('>8I',call(30)))==want,(name,want)
            dictionary=Document(path.read_bytes()).strings
            indices=list(range(len(source['biomes'])))
            raw_meta=call(32,metadata_request(indices))
            carvers+=verify_carvers(raw_meta,source,dictionary,indices);hashes.append(hashlib.sha256(raw_meta).hexdigest())
            raw=call(31,encode(points));rows=list(struct.iter_unpack('>6f',raw));assert len(rows)==len(points)
            for query, row in zip(points,rows):
                for channel, value in enumerate(row):
                    want=reference(source['cave_noises'][channel],channel,query);err=abs(value-want)
                    assert math.isfinite(value) and err<.00001,(name,query,channel,value,want,err)
                    maximum=max(maximum,err);checked+=1
            assert raw==call(31,encode(points)),(name,'repeat')
            order=list(range(len(points)));rng.shuffle(order)
            reordered=list(struct.iter_unpack('>6f',call(31,encode([points[i] for i in order]))))
            assert reordered==[rows[i] for i in order],(name,'query-order')
            hashes.append(hashlib.sha256(raw).hexdigest())
            assert call(31,encode([points[0]]*4096))==raw[:24]*4096
            assert call(31,encode([]))==b'' and call(32,metadata_request([]))==b''
            for op,data in [(30,b'\0'),(31,b''),(31,encode(points[:1])+b'\0'),(31,struct.pack('>I',4097)),
                            (32,metadata_request([len(source['biomes'])])),(32,struct.pack('>I',257)),(32,b''),(32,metadata_request([])+b'\0')]:
                error(op,data,603)
            assert raw==call(31,encode(points))
            # Related preparations and diagnostic queries retain the cave model.
            call(21);call(25);call(9);call(15)
            assert raw_meta==call(32,metadata_request(indices)) and raw==call(31,encode(points))
            # A successful replacement density model invalidates its dependents.
            call(11);error(31,encode([]),737);error(32,metadata_request([]),737)
            records.append(dict(name=name,noise_scalars=len(points)*6,carvers=sum(len(b['carvers']) for b in source['biomes'])))
        # Preparing geology clears stale generated blocks, while ordinary cave
        # queries leave blocks resident. Rebuilding material columns retains it.
        name,source,path=profiles[0];prepare(path);call(25);call(9);call(15)
        tile=(0,0,16,16,0x12345678,0x80000001)
        desc=call(18,tile_bytes((-1,-1,18,18,*tile[-2:])));call(13,desc);call(16,tile_bytes(tile));call(22)
        query=query_bytes([(0,0,*tile[-2:])]);before=call(24,query)
        chunk_bytes=chunk_request(0,0,*tile[-2:],5023);before_chunk=call(26,chunk_bytes)
        call(30);error(24,query,727);error(26,chunk_bytes,731);call(22)
        assert before==call(24,query) and before_chunk==call(26,chunk_bytes)
        assert zlib.decompress(call(27,chunk_bytes))==before_chunk
        columns=decode_columns(call(24,query_bytes([(i%16,i//16,*tile[-2:]) for i in range(256)])))
        blocks=validate_chunk(before_chunk,source,columns,0,0,5023)
        call(31,encode(points));call(32,metadata_request([0]));assert before==call(24,query)
        call(30);error(24,query,727)
        retained=call(31,encode(points))
        malformed=ROOT/'build/bend/geology-profiles/malformed.rbp'
        for value in (float('nan'),float('inf')):
            broken=copy.deepcopy(source);broken['cave_noises'][0]['frequency']=value
            malformed.write_bytes(fixture_wire(broken,True))
            error(5,path_request(malformed),701)
            assert retained==call(31,encode(points))
        error(5,path_request(malformed.with_name('missing.rbp')),702)
        assert retained==call(31,encode(points))
        for name,path in invalid:
            try:
                prepare(path);error(30,b'',736);error(31,encode([]),737);error(32,metadata_request([]),737)
            except AssertionError as failure:
                raise AssertionError(name) from failure
        call(5,path_request(profiles[0][2]));error(30,b'',727)
        call(4);assert worker.process.wait(timeout=10)==0
        diagnostics=worker.process.stderr.read().decode()
        return dict(gpu_required=gpu,profiles=records,noise_scalars_checked=checked,carvers_checked=carvers,
            invalid_profiles_rejected=len(invalid),maximum_reference_error=maximum,expected_gpu_dispatches=dispatches+6,
            cave_gpu_dispatches=dispatches,metal_commands_ms=[float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostics)],
            sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest(),requests_in_one_process=worker.id,
            reload_and_block_invalidation='pass',query_reordering='byte-identical',
            generated_chunk_blocks_checked=blocks,geology_retained_through_chunk_encoding='pass')
    finally:
        signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true')
    parser.add_argument('--metal-probe',type=Path,help='Reuse an already compiled diagnostic worker; output and device commands are still verified')
    parser.add_argument('--profile',nargs=2,action='append');args=parser.parse_args()
    profiles,invalid=fixtures(ROOT/'build/bend/geology-profiles')
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine'
    version='existing executable' if args.skip_build else build(args.bend,binary,ROOT/'bend/engine.bend')
    report=dict(scope='Typed cave/carver projection and six-channel GPU sampling; cave carving and world integration remain pending',
                bend_version=version,runs=[exercise(binary,profiles,invalid,False),exercise(binary,profiles,invalid,True)])
    assert report['runs'][0]['sha256']==report['runs'][1]['sha256']
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/geology-metal-probe'
        if args.metal_probe is None:compile_metal_observer(args.bend,ROOT/'bend/engine.bend',probe)
        result=exercise(probe,profiles,invalid,True)
        assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches'],(len(result['metal_commands_ms']),result['expected_gpu_dispatches'])
        assert result['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=result
        report['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest()
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    path=ROOT/'build/bend/geology-tests.json';path.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: {report["runs"][0]["noise_scalars_checked"]} cave samples and {report["runs"][0]["carvers_checked"]} carvers/backend; {path}')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('geology test timeout')))
    main()
