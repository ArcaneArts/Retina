#!/usr/bin/env python3
"""Independently check resident Bend shoreline geometry and registered materials.

The oracle scans integer solid voxels and searches climate intervals linearly.
No Rust generation is used. Actual Metal execution is separately observed;
these component checks do not establish complete world-generation parity.
"""
import argparse
import copy
import functools
import hashlib
import json
import math
from pathlib import Path
import re
import signal
import struct
import time

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_material_columns import Reference as Blocks, fixtures as block_fixtures, decode
from test_bend_surface import tile_bytes, query_bytes, rows
from test_bend_density import ins
from test_bend_density_lattice import LOW, HIGH
from test_bend_climate import expected
from test_bend_noise import MASK


def source_profile(folder):
    sources, _ = block_fixtures(folder/'blocks')
    source = copy.deepcopy(sources[0][1])
    source.update(geology_min_y=-16, geology_height=48, sea_level=8,
        materials=['minecraft:air', 'minecraft:stone', 'minecraft:grass_block', 'minecraft:dirt',
                   'minecraft:sand', 'minecraft:water', 'minecraft:gravel', 'minecraft:snow_block'],
        heightmap_masks=[0,63,63,63,63,51,63,63])
    source['biomes'] = [dict(flags=flags, id=name) for flags,name in
        [(0,'minecraft:plains'), (64,'minecraft:beach'), (64,'minecraft:snowy_beach'), (64,'minecraft:stony_shore')]]
    source['climate_targets'] = []
    for biome, temp, humidity, continental in [(0,[-1,1],[-1,1],[.7,1]),
            (1,[-.1,1],[-1,.25],[0,0]), (2,[-1,-.1],[-1,.25],[0,0]), (3,[-1,1],[.25,1],[0,0])]:
        source['climate_targets'].append(dict(min=[temp[0],humidity[0],continental[0],-1],
            max=[temp[1],humidity[1],continental[1],1],weirdness=[-1,1],depth=[0,0],offset=0,biome=biome))
    climate = dict(nodes=[ins(29,0),ins(0,p=(.02,0,0,0)),ins(6,0,1),ins(29,2),ins(6,3,1),ins(0)],roots=[2,4,5,5,5,5])
    terrain = dict(nodes=[ins(29,0),ins(0,p=(.125,0,0,0)),ins(6,0,1),ins(0,p=(8,0,0,0)),
        ins(4,2,3),ins(29,1),ins(5,4,5)],roots=[6])
    program = source['registry_program'];program.update(surface=[-16,8,0],terrain_cell=[4,8])
    program['programs'] = [climate,terrain,terrain]
    # Distinct registered tops plus dirt underneath; the material oracle checks
    # every voxel, so a correct biome ID alone cannot hide unchanged materials.
    for top in (2,4,7,6):
        program['programs'].append(dict(nodes=[ins(45,1,0),ins(0,p=(top+1,0,0,0)),ins(40,0,1),
            ins(0,p=(4,0,0,0)),ins(41,2,3)],roots=[4]))
    return source


def fixtures(folder):
    folder.mkdir(parents=True,exist_ok=True);base=source_profile(folder);result=[]
    def add(name,change):
        s=copy.deepcopy(base);change(s);path=folder/(name+'.rbp');path.write_bytes(fixture_wire(s,True));result.append((name,s,path))
    def constant(s,height):
        p=dict(nodes=[ins(0,p=(height,0,0,0)),ins(29,1),ins(5,0,1)],roots=[2])
        s['registry_program']['programs'][1:3]=[p,p]
    add('coastal',lambda s:None)
    add('inland_low',lambda s:constant(s,8))
    add('open_shallow_ocean',lambda s:constant(s,7))
    add('high_land',lambda s:constant(s,20))
    add('negative_sea',lambda s:(s.update(sea_level=-3),s['registry_program']['programs'][1]['nodes'][3].update(p=[-3,0,0,0])))
    add('explicit_height',lambda s:(s['registry_program']['surface'].__setitem__(2,1),
        s['registry_program']['programs'][1].update(roots=[4])))
    # Avoid a mathematical zero at thirds/fifths: the compensated floating
    # lattice may legitimately resolve that sign differently from rational
    # oracle weights. Exact-zero tests above use exactly representable cells.
    add('non_power_cells',lambda s:(s['registry_program'].update(terrain_cell=[3,5]),
        s['registry_program']['programs'][1]['nodes'][3].update(p=[8.0625,0,0,0])))
    add('no_shores',lambda s:[b.update(flags=0) for b in s['biomes']])
    add('unreferenced_shores',lambda s:s.update(climate_targets=s['climate_targets'][:1]))
    add('shore_only',lambda s:s.update(climate_targets=s['climate_targets'][1:]))
    # Two disjoint solid intervals, including an exact-zero upper crossing.
    def island(s):
        p=dict(nodes=[ins(29,1),ins(0,p=(8,0,0,0)),ins(5,0,1),ins(11,2),ins(0,p=(3,0,0,0)),
            ins(5,4,3),ins(0,p=(-8,0,0,0)),ins(5,0,6),ins(11,7),ins(0,p=(4,0,0,0)),ins(5,9,8),ins(9,5,10)],roots=[11])
        s['registry_program']['programs'][1:3]=[p,p]
    add('floating_intervals',island)
    def ceiling(s):
        s.update(geology_height=31)
        p=dict(nodes=[ins(29,1),ins(0,p=(14.8,0,0,0)),ins(5,0,1),ins(0,p=(-8,0,0,0)),
            ins(5,0,3),ins(11,4),ins(0,p=(4,0,0,0)),ins(5,6,5),ins(9,2,7)],roots=[8])
        s['registry_program']['programs'][1:3]=[p,p]
    add('ceiling_sliver',ceiling)
    def tiny(s):
        p=s['registry_program']['programs'][1];p['nodes'][3]['p']=[8.0625,0,0,0]
        p['nodes'] += [ins(0,p=(1e-9,0,0,0)),ins(6,6,7)];p['roots']=[8]
    add('tiny_density',tiny)
    return result


class Reference(Blocks):
    @functools.lru_cache(maxsize=100000)
    def first_free(self,x,z,desc):
        return super().first_free(x,z,desc)

    def selected(self,x,z,desc,axes):
        height=self.first_free(x,z,desc);sea=self.source['sea_level']
        base=expected(self.targets,list(axes),8) or expected(self.targets,list(axes),0)
        shore=expected(self.targets,list(axes),16)
        opposite=False
        if shore is not None and sea-2<=height<=sea+3:
            for radius in (2,4,6):
                diagonal=math.floor(radius/math.sqrt(2)+.5)
                for dx,dz in [(radius,0),(-radius,0),(0,radius),(0,-radius),
                        (diagonal,diagonal),(-diagonal,diagonal),(diagonal,-diagonal),(-diagonal,-diagonal)]:
                    probe=self.first_free(x+dx,z+dz,desc)
                    if (probe<sea) != (height<sea):opposite=True;break
                if opposite:break
        if opposite:
            point=list(axes);point[2]=shore[2];base=expected(self.targets,point,0) or base
        return height,MASK if base is None else base[0]['biome']

    def selected_column(self,x,z,desc,biome):
        original=self.targets
        try:
            self.targets=[t for t in original if t['biome']==biome]
            return self.column(x,z,desc)
        finally:self.targets=original


def exercise(binary,profiles,gpu):
    worker=Worker(binary,gpu);dispatches=0;checked=voxels=0;hashes=[];records=[]
    def call(op,data=b'',status=0):
        nonlocal dispatches
        if op in (13,16,22,29) and status==0:dispatches+=2 if op==29 else 1
        return worker.call(op,data,status=status)
    def error(op,data,code):
        assert call(op,data,1)==struct.pack('>I',code),(op,code)
    def prepare(path):
        call(5,path_request(path));call(11);call(19);call(21);call(9);call(15)
    def surface(tile,halo=6):
        x,z,w,d,lo,hi=tile
        raw=call(18,tile_bytes((x-halo,z-halo,w+halo*2,d+halo*2,lo,hi)))
        words=struct.unpack('>11I',raw)
        desc=tuple(v-(1<<32) if i in range(1,7) and v&(1<<31) else v for i,v in enumerate(words))
        call(13,raw);call(16,tile_bytes(tile));return desc
    def verify(source,tile,desc,all_columns=True):
        nonlocal checked,voxels
        x,z,w,d,lo,hi=tile;ref=Reference(source)
        pts=[(x+i%w,z+i//w,lo,hi) for i in range(w*d)] if all_columns else [(x,z,lo,hi),(x+w-1,z+d-1,lo,hi)]
        raw=call(17,query_bytes(pts));before=rows(raw)
        assert call(29)==struct.pack('>3I',w,d,w*d)
        refined=call(17,query_bytes(pts));after=rows(refined)
        error(24,query_bytes([]),727)
        assert call(29)==struct.pack('>3I',w,d,w*d)
        assert call(17,query_bytes(pts))==refined
        call(22);columns=decode(call(24,query_bytes(pts)))
        counts={}
        for pt,old,row,column in zip(pts,before,after,columns):
            present,height,*tail=row;axes,biome=tail[:6],tail[6]
            assert present==1 and tuple(axes)==old[2:8],(pt,old,row)
            want_height,want_biome=ref.selected(pt[0],pt[1],desc,axes)
            assert (height,biome)==(want_height,want_biome),(pt,row,want_height,want_biome)
            want=ref.selected_column(pt[0],pt[1],desc,biome)
            assert (column['height'],column['biome'],column['runs'])==(want['height'],want['biome'],want['runs']),(pt,column,want)
            counts[str(biome)]=counts.get(str(biome),0)+1;voxels+=source['geology_height'];checked+=1
        # A rejected body preserves both resident surface and derived block runs.
        block_bytes=call(24,query_bytes(pts));error(29,b'\0',603)
        assert call(17,query_bytes(pts))==refined and call(24,query_bytes(pts))==block_bytes
        assert rows(call(17,query_bytes(pts[::-1])))==after[::-1]
        # Successful finalization invalidates blocks, but regenerating them is
        # byte-identical. Rebuilding the raw source restores its own selection.
        call(29);error(24,query_bytes([]),727);call(22)
        assert call(24,query_bytes(pts))==block_bytes
        call(16,tile_bytes(tile));assert call(17,query_bytes(pts))==raw
        call(29);assert call(17,query_bytes(pts))==refined
        hashes.append(hashlib.sha256(refined+block_bytes).hexdigest())
        return counts
    try:
        error(29,b'',716);error(29,b'\0',603)
        call(5,path_request(profiles[0][2]));call(11);error(29,b'',714)
        call(9);error(29,b'',734);call(15);error(29,b'',734)
        for name,source,path in profiles:
            signal.alarm(600);print(f'{"GPU" if gpu else "CPU"}: shoreline {name}',flush=True);prepare(path)
            actual=name in ('vanilla','terralith','combined')
            tile=(-12,-4,32,8,LOW,HIGH) if not actual else (-30000,30000,2,2,LOW,HIGH)
            started=time.perf_counter();desc=surface(tile);counts=verify(source,tile,desc,not actual)
            if name=='coastal':
                assert '0' in counts and '1' in counts and '2' in counts,counts
                # An overlapping tile must agree through chunk and region edges.
                common=[(-1,0,LOW,HIGH),(0,0,LOW,HIGH),(1,0,LOW,HIGH)]
                keep=call(17,query_bytes(common));other=(-4,-2,12,6,LOW,HIGH)
                surface(other);call(29);assert call(17,query_bytes(common))==keep
                # Too-small density coverage must fail, not manufacture a shore
                # by clamping its probes to the last available lattice vertex.
                surface((1,1,2,2,LOW,HIGH),0);call(22)
                old=call(24,query_bytes([(1,1,LOW,HIGH)]));error(29,b'',735)
                assert call(24,query_bytes([(1,1,LOW,HIGH)]))==old
                for far in [(29999990,-30000001,2,2,MASK,HIGH),(-(1<<31)+7,(1<<31)-9,2,2,MASK,MASK)]:
                    d=surface(far);verify(source,far,d)
                for edge in [(-(1<<31)+1,0,2,2,LOW,HIGH),((1<<31)-3,0,2,2,LOW,HIGH)]:
                    surface(edge,1);error(29,b'',735)
                    assert rows(call(17,query_bytes([(edge[0],0,LOW,HIGH)])))[0][0]==1
            if name in ('inland_low','open_shallow_ocean','high_land','floating_intervals'):assert counts=={'0':256},(name,counts)
            if name in ('no_shores','unreferenced_shores'):
                small=(-2,-2,2,2,LOW,HIGH);d=surface(small,0)
                assert call(29)==struct.pack('>3I',2,2,4)
            records.append(dict(name=name,biome_counts=counts,observational_host_ms=(time.perf_counter()-started)*1000))
            call(5,path_request(path));error(29,b'',716)
        call(4);assert worker.process.wait(timeout=10)==0
        stderr=worker.process.stderr.read().decode()
        return dict(gpu_required=gpu,profiles=records,surface_columns_checked=checked,material_voxels_checked=voxels,
            requests_in_one_process=worker.id,expected_gpu_dispatches=dispatches,
            metal_commands_ms=[float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)],
            sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest())
    finally:signal.alarm(0);worker.close()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bend',type=Path,default=DEFAULT_BEND)
    p.add_argument('--skip-build',action='store_true');p.add_argument('--prove-metal',action='store_true')
    p.add_argument('--observer',type=Path);p.add_argument('--profile',nargs=2,action='append');args=p.parse_args()
    profiles=fixtures(ROOT/'build/bend/shoreline-profiles')
    for js,wire in args.profile or []:profiles.append((Path(js).parent.name,json.loads(Path(js).read_text()),Path(wire)))
    binary=ROOT/'build/bend/engine'
    version='existing executable' if args.skip_build else build(args.bend,binary,ROOT/'bend/engine.bend')
    report=dict(scope='Exact resident pre-cave heights and registered narrow shoreline selection; full generator parity pending',
        bend_version=version,runs=[exercise(binary,profiles,False),exercise(binary,profiles,True)])
    if args.prove_metal or args.observer:
        probe=args.observer or ROOT/'build/bend/shoreline-metal-probe'
        if not args.observer:compile_metal_observer(args.bend,ROOT/'bend/engine.bend',probe)
        result=exercise(probe,profiles,True);assert len(result['metal_commands_ms'])==result['expected_gpu_dispatches']
        assert result['sha256']==report['runs'][1]['sha256'];report['actual_metal_proof']=result
    report['source_sha256']={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted((ROOT/'bend').glob('*.bend'))}
    path=ROOT/'build/bend/shoreline-tests.json';path.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: {report["runs"][0]["surface_columns_checked"]} shoreline columns per backend; {path}')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,lambda *_:(_ for _ in ()).throw(TimeoutError('shoreline test timeout')));main()
