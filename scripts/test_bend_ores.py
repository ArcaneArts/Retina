#!/usr/bin/env python3
"""Check loaded Bend ore metadata and CPU/GPU ordered replacement independently.

This validates ore inputs and replacement policy, not vein rasterization or a
complete lit region generator. Java transports raw batches; all policy is Bend.
"""
import argparse
import copy
from collections import Counter
import hashlib
import json
from pathlib import Path
import random
import re
import signal
import struct
import threading

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import Document, path_request
from test_bend_registry_noise import fixture_wire
from test_bend_geology import fixtures as geology_fixtures
from test_bend_generated_chunks import request as chunk_request
from test_bend_surface import tile_bytes
from test_bend_noise import MASK, f32


def metadata(mode, ids=()):
    return struct.pack('>I', mode) if mode == 0 else struct.pack(f'>{len(ids)+2}I', mode, len(ids), *ids)


def queries(rows):
    return struct.pack('>I', len(rows)) + b''.join(struct.pack('>6I', *(x & MASK for x in row)) for row in rows)


def replacement(source, row):
    recipe, biome, y, host, bits, exposed = row
    if recipe not in source['biomes'][biome].get('ores', []):
        return 0
    ore = source['ores'][recipe]
    if exposed and (bits >> 8) / 16777216 < f32(ore['discard']):
        return 0
    for band in ore['replacement_bands']:
        if band['min'] <= y <= band['max']:
            return band['materials'][host]
    return 0


def fixtures(folder):
    profiles, _ = geology_fixtures(folder/'geology')
    base = copy.deepcopy(profiles[0][1])
    common = dict(id='test:ore', size=64, discard=.5, scattered=False,
        count_min=0, count_max=1024, rarity=2,
        height=dict(min=-2147483648, max=2147483647, triangle=True, plateau=2147483647),
        replacement_bands=[dict(min=-32, max=0, materials=[0, 2, 3, 0, 0, 0]),
            dict(min=-8, max=31, materials=[0, 4, 1, 0, 0, 0]),
            dict(min=32, max=32, materials=[1, 0, 0, 0, 0, 0])])
    base.update(ores=[common, dict(copy.deepcopy(common), id='test:scattered', size=7, discard=1.,
        scattered=True, count_min=4, count_max=8, rarity=1,
        height=dict(min=-17, max=5, triangle=False, plateau=0)),
        dict(copy.deepcopy(common), id='test:zero', size=0, discard=0., count_min=0, count_max=0)])
    for i, biome in enumerate(base['biomes']): biome['ores'] = [0, 1, 2, 0] if i%2 == 0 else []
    good, bad = [], []
    def add(name, change=lambda s: None, packed=True):
        source=copy.deepcopy(base);change(source)
        path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,packed));good.append((name,source,path))
    add('mixed')
    add('unpacked', packed=False)
    add('layout_one', lambda s:s.update(ore_layout=1))
    add('empty', lambda s:(s.update(ores=[]),[b.update(ores=[]) for b in s['biomes']]))
    add('optional', lambda s:(s.pop('ores'),[b.pop('ores') for b in s['biomes']]))
    def invalid(name, change):
        source=copy.deepcopy(base);change(source)
        path=folder/('bad-'+name+'.rbp');path.write_bytes(fixture_wire(source));bad.append((name,path))
    for key, value in [('id',''),('size',65),('size',-1),('size',True),('discard',-1.),('discard',1.01),
        ('discard',1e100),('scattered',1),('count_min',1025),('count_max',1025),('rarity',0),('rarity',False),
        ('height',dict(min=2,max=1,triangle=False,plateau=0)),
        ('height',dict(min=-1,max=1,triangle=0,plateau=0)),
        ('height',dict(min=-1,max=1,triangle=False,plateau=-1)),
        ('height',dict(min=-2147483649,max=1,triangle=False,plateau=0)),
        ('replacement_bands',[])]:
        invalid(f'{key}-{len(bad)}',lambda s,k=key,v=value:s['ores'][0].__setitem__(k,v))
    invalid('map_size',lambda s:s['ores'][0]['replacement_bands'][0].update(materials=[0]))
    invalid('map_negative',lambda s:s['ores'][0]['replacement_bands'][0]['materials'].__setitem__(1,-1))
    invalid('map_id',lambda s:s['ores'][0]['replacement_bands'][0]['materials'].__setitem__(1,6))
    invalid('band_bounds',lambda s:s['ores'][0]['replacement_bands'][0].update(min=1,max=0))
    invalid('band_signed',lambda s:s['ores'][0]['replacement_bands'][0].update(max=2147483648))
    invalid('member_id',lambda s:s['biomes'][0].update(ores=[3]))
    invalid('member_negative',lambda s:s['biomes'][0].update(ores=[-1]))
    invalid('member_bool',lambda s:s['biomes'][0].update(ores=[True]))
    invalid('layout_zero',lambda s:s.update(ore_layout=0))
    invalid('layout_three',lambda s:s.update(ore_layout=3))
    return good,bad


def verify_metadata(raw, source, strings, ids):
    at=0
    for i in ids:
        ore=source['ores'][i];h=ore['height']
        row=struct.unpack_from('>2If9I',raw,at);at+=48
        assert strings[row[0]] == ore['id']
        assert row[1:] == (ore['size'],f32(ore['discard']),int(ore['scattered']),ore['count_min'],ore['count_max'],
            ore['rarity'],h['min']&MASK,h['max']&MASK,int(h['triangle']),h['plateau'],len(ore['replacement_bands'])),(i,row,ore)
        for band in ore['replacement_bands']:
            assert struct.unpack_from('>2i',raw,at)==(band['min'],band['max']);at+=8
    assert at==len(raw)


def exercise(binary,profiles,bad,gpu):
    worker=Worker(binary,gpu);diagnostics=bytearray()
    reader=threading.Thread(target=lambda:diagnostics.extend(worker.process.stderr.read()),daemon=True);reader.start()
    dispatches=checked=members=recipes=0;records=[];classes=Counter();digest=hashlib.sha256()
    def call(op,data=b'',error=0):
        nonlocal dispatches
        signal.alarm(300);raw=worker.call(op,data,status=int(bool(error)))
        if error:assert raw==struct.pack('>I',error),(op,error,raw)
        elif op in (13,16,46):dispatches+=1
        elif op==22:dispatches+=2 if gpu else 1
        return raw
    def prepare(path):
        call(5,path_request(path));call(11);call(19);call(21);call(30)
    try:
        call(45,metadata(0),754);call(46,queries([]),754)
        for name,source,path in profiles:
            print('checking','GPU' if gpu else 'CPU',name,flush=True)
            prepare(path);ore_count=len(source.get('ores',[]));biome_count=len(source['biomes']);palette=len(source['materials'])
            info=call(45,metadata(0));layout,count,bc,pc,base,flagbase,words=struct.unpack('>7I',info)
            assert (layout,count,bc,pc,base)==(source.get('ore_layout',2),ore_count,biome_count,palette,6+13*ore_count)
            assert words==6+13*ore_count+2*biome_count+sum((palette+2)*len(r['replacement_bands']) for r in source.get('ores',[]))+sum(len(b.get('ores',[])) for b in source['biomes'])+palette
            assert flagbase==words-palette
            digest.update(info);strings=Document(path.read_bytes()).strings
            ids=list(range(ore_count))
            for start in range(0,len(ids),256):
                chunk=ids[start:start+256];raw=call(45,metadata(1,chunk));verify_metadata(raw,source,strings,chunk);digest.update(raw);recipes+=len(chunk)
            for start in range(0,biome_count,256):
                ids=list(range(start,min(start+256,biome_count)));raw=call(45,metadata(2,ids));at=0
                for i in ids:
                    n,=struct.unpack_from('>I',raw,at);at+=4
                    row=struct.unpack_from(f'>{n}I',raw,at);at+=4*n
                    assert list(row)==source['biomes'][i].get('ores',[]);members+=n
                assert at==len(raw);digest.update(raw)
            rows=[];rng=random.Random(918731)
            for i,ore in enumerate(source.get('ores',[])):
                biome=next((b for b,v in enumerate(source['biomes']) if i in v.get('ores',[])),None)
                if biome is None:continue
                for band in ore['replacement_bands']:
                    y=(band['min']+band['max'])//2
                    rows += [(i,biome,y,host,0,0) for host in range(palette)]
                    for y in (band['min']-1,band['min'],band['max'],band['max']+1):
                        if not -2147483648<=y<=2147483647:continue
                        for host in sorted({0,1,palette-1}):
                            rows += [(i,biome,y,host,bits,exposed) for bits in (0,0x7fffffff,MASK) for exposed in (0,1)]
                rows += [(i,rng.randrange(biome_count),rng.randrange(-2147483648,2147483648),rng.randrange(palette),rng.randrange(1<<32),rng.randrange(2)) for _ in range(64)]
            for start in range(0,len(rows),4096):
                chunk=rows[start:start+4096];raw=call(46,queries(chunk))
                actual=[v for v, in struct.iter_unpack('>I',raw)];want=[replacement(source,row) for row in chunk]
                assert actual==want,(name,start,next(((r,a,b) for r,a,b in zip(chunk,actual,want) if a!=b),None))
                classes['placed']+=sum(bool(v) for v in actual);classes['skipped']+=sum(not v for v in actual);checked+=len(chunk);digest.update(raw)
            sample=rows[:101]
            raw=call(46,queries(sample));assert call(46,queries(sample[::-1]))==b''.join(raw[i:i+4] for i in range(len(raw)-4,-1,-4))
            assert call(46,queries(sample))==raw;assert call(46,queries([]))==b''
            if sample:assert call(46,queries([sample[0]]*4096))==raw[:4]*4096
            for body in (b'',struct.pack('>I',3),metadata(0)+b'\0',metadata(1,[ore_count]),metadata(2,[biome_count]),struct.pack('>2I',1,257)):
                call(45,body,603)
            for body in (b'',queries(sample)+b'\0',struct.pack('>I',4097),queries([(ore_count,0,0,0,0,0)]),queries([(0,biome_count,0,0,0,0)]),queries([(0,0,0,palette,0,0)]),queries([(0,0,0,0,0,2)])):
                call(46,body,603)
            assert call(45,metadata(0))==info and call(46,queries(sample))==raw
            if name=='mixed':
                seed=0x8000000187654321;low=seed&MASK;high=seed>>32
                call(9);call(15);call(25);desc=call(18,tile_bytes((-1,-1,18,18,low,high)));call(13,desc)
                call(16,tile_bytes((0,0,16,16,low,high)));call(22);chunk=chunk_request(0,0,low,high,5023);saved=call(26,chunk)
                assert call(46,queries(sample))==raw;assert call(45,metadata(0))==info;assert call(26,chunk)==saved
                call(37);assert call(45,metadata(0))==info
                call(16,tile_bytes((0,0,16,16,low,high)));assert call(45,metadata(0))==info
                call(30,b'\0',603);assert call(46,queries(sample))==raw
            call(30);assert call(45,metadata(0))==info and call(46,queries(sample))==raw
            call(5,path_request(path));call(45,metadata(0),754);call(46,queries([]),754)
            records.append(dict(name=name,recipes=ore_count,biomes=biome_count,palette=palette,replacement_queries=len(rows),packed_words=words))
        for name,path in bad:
            call(5,path_request(path));call(11);call(19);call(21);call(30,error=753);call(45,metadata(0),754)
        call(4);assert worker.process.wait(timeout=10)==0;reader.join(timeout=5);assert not reader.is_alive()
        return dict(gpu_required=gpu,profiles=records,recipes_checked=recipes,memberships_checked=members,replacements_checked=checked,
            classes=dict(classes),invalid_profiles=len(bad),expected_gpu_dispatches=dispatches,
            metal_commands_ms=[float(x) for x in re.findall(rb'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostics)],sha256=digest.hexdigest())
    finally:signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--skip-build',action='store_true');parser.add_argument('--prove-metal',action='store_true')
    parser.add_argument('--metal-probe',type=Path);parser.add_argument('--profile',nargs=2,action='append');parser.add_argument('--output',type=Path);args=parser.parse_args()
    profiles,bad=fixtures(ROOT/'build/bend/ore-profiles')
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(DEFAULT_BEND,binary,ROOT/'bend/engine.bend')
    report=dict(scope=__doc__.strip(),runs=[exercise(binary,profiles,bad,False),exercise(binary,profiles,bad,True)])
    assert report['runs'][0]['sha256']==report['runs'][1]['sha256']
    if args.prove_metal:
        probe=args.metal_probe or ROOT/'build/bend/ore-metal-probe'
        if args.metal_probe is None:compile_metal_observer(DEFAULT_BEND,ROOT/'bend/engine.bend',probe)
        result=exercise(probe,profiles,bad,True);assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches'];assert result['sha256']==report['runs'][1]['sha256']
        report['actual_metal_proof']=result;report['metal_probe_sha256']=hashlib.sha256(probe.read_bytes()).hexdigest()
    report['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'bend').glob('*.bend'))}
    report['inputs']=[dict(name=n,rbp_sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for n,s,p in profiles]
    report['executable_sha256']=hashlib.sha256(binary.read_bytes()).hexdigest();report['bend_version']='2.0.36'
    output=args.output or ROOT/'build/bend/ore-tests.json';output.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',output,flush=True)


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('ore test timeout')))
    main()
