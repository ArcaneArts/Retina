#!/usr/bin/env python3
"""Independently check resident GPU surface heights, climate and biome selection.

Surface geometry is a top-level density-lattice approximation, including for
composition-mode profiles. This is not complete terrain/MCA generation parity.
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
from test_bend_density import ins
from test_bend_density_lattice import Reference, encoded, layout, LOW, HIGH, MASK
from test_bend_registry_density import request as density_request
from test_bend_registry_climate import projected
from test_bend_climate import expected
from test_bend_noise import f32


def tile_bytes(tile):
    return struct.pack('>6I', *(v & MASK for v in tile))


def query_bytes(points):
    return struct.pack('>I',len(points))+b''.join(struct.pack('>4I',*(v & MASK for v in p)) for p in points)


def rows(raw):
    return list(struct.iter_unpack('>I7fI',raw))


def fixture_profiles(folder):
    folder.mkdir(parents=True,exist_ok=True)
    # Nonaligned world limits exercise clipping at both ends of global Y cells.
    climate=dict(nodes=[ins(0,p=(.125,0,0,0)),ins(29,0),ins(0,p=(.001,0,0,0)),ins(6,1,2)],roots=[0,0,0,0,0,0])
    ramp=dict(nodes=[ins(29,0),ins(29,1),ins(29,2),ins(0,p=(.125,0,0,0)),ins(0,p=(.0625,0,0,0)),
        ins(6,0,3),ins(6,2,4),ins(4,5,6),ins(0,p=(4,0,0,0)),ins(4,7,8),ins(5,9,1)],roots=[10])
    islands=dict(nodes=[ins(29,1),ins(0,p=(8,0,0,0)),ins(5,0,1),ins(11,2),ins(0,p=(3,0,0,0)),ins(5,4,3),
        ins(0,p=(-8,0,0,0)),ins(5,0,6),ins(11,7),ins(0,p=(4,0,0,0)),ins(5,9,8),ins(9,5,10)],roots=[11])
    targets=[dict(min=[-1]*4,max=[1]*4,weirdness=[-1,1],depth=[0,0],offset=0,biome=0),
             dict(min=[-1]*4,max=[1]*4,weirdness=[-1,1],depth=[0,0],offset=0,biome=1)]
    base={'geology_min_y':-17,'geology_height':36,'sea_level':1,'biomes':[{'flags':0},{'flags':0}],
          'climate_targets':targets,'registry_program':{'surface':[-17,8,0],'terrain_cell':[4,8],
          'noises':[],'points':[],'programs':[climate,ramp,ramp]}}
    result=[]
    for name,program,explicit,packed in [('ramp',ramp,0,False),('islands',islands,0,True),
            ('solid',dict(nodes=[ins(0,p=(1,0,0,0))],roots=[0]),0,True),
            ('empty',dict(nodes=[ins(0,p=(-1,0,0,0))],roots=[0]),0,True),
            ('explicit',ramp,1,True)]:
        source=copy.deepcopy(base);source['registry_program']['programs'][1:]=[program,program]
        source['registry_program']['surface'][2]=explicit
        path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,packed));result.append((name,source,path))
    for name,bottom,cell in [('probe_minimum',-9,[4,8]),('non_power_cells',-17,[3,5])]:
        source=copy.deepcopy(base);source['registry_program']['surface'][0]=bottom;source['registry_program']['terrain_cell']=cell
        path=folder/(name+'.rbp');path.write_bytes(fixture_wire(source,True));result.append((name,source,path))
    return result


def invalid_profiles(folder,base):
    invalid=[]
    def add(name,field,value,nested=False):
        source=copy.deepcopy(base);target=source['registry_program'] if nested else source
        if value=='missing':target.pop(field)
        else:target[field]=value
        path=folder/('invalid-'+name+'.rbp');path.write_bytes(fixture_wire(source,True));invalid.append((name,path))
    for field in ('geology_min_y','geology_height','sea_level'):
        for label,value in [('missing','missing'),('null',None),('bool',True),('float',1.)]:add(field+'_'+label,field,value)
    for label,value in [('zero',0),('large',4097),('negative',-1)]:add('height_'+label,'geology_height',value)
    add('world_top_overflow','geology_min_y',(1<<31)-1)
    for label,value in [('missing','missing'),('null',None),('short',[-17,8]),('long',[-17,8,0,0]),
                        ('float',[-17,8.,0]),('bool',[-17,8,False]),('step_zero',[-17,0,0]),('mode',[-17,8,2])]:
        add('surface_'+label,'surface',value,True)
    for label,value in [('missing','missing'),('zero',[0,8]),('large',[4,4097]),('float',[4.,8]),('short',[4])]:
        add('cell_'+label,'terrain_cell',value,True)
    return invalid


class SurfaceReference(Reference):
    def __init__(self,source):
        super().__init__(source)
        self.source=source;self.targets=projected(source)
        self.minimum=source['geology_min_y'];self.maximum=self.minimum+source['geology_height']

    def height(self,x,z,desc):
        if self.source['registry_program']['surface'][2]:
            return max(self.minimum+1,min(self.maximum,self.query((1,x,0,z,*desc[-2:]),desc)[0]+1))
        origin,counts,steps=layout(desc)
        upper_y=self.maximum;upper=self.query((1,x,upper_y,z,*desc[-2:]),desc)[0]
        if upper>0:return self.maximum
        for y in reversed(range(origin[1],self.maximum,steps[1])):
            lower=self.query((1,x,y,z,*desc[-2:]),desc)[0]
            if lower>0:
                return max(self.minimum+1,min(self.maximum,y+(upper_y-y)*lower/max(lower-upper,.000001)+1))
            upper_y,upper=y,lower
        return self.minimum+1

    def verify(self,raw,points,desc,tile):
        assert len(raw)==len(points)*36
        maximum=0.
        for p,row in zip(points,rows(raw)):
            x,z,low,high=p;present,h,*tail=row;climate,biome=tail[:6],tail[6]
            covered=(low,high)==tuple(tile[-2:]) and tile[0]<=x<tile[0]+tile[2] and tile[1]<=z<tile[1]+tile[3]
            assert present==int(covered),(p,row,tile)
            if not covered:
                assert row[1:-1]==(0.,)*7 and biome==MASK
                continue
            wanted=[self.height(x,z,desc),*self.vertex(0,(x,0,z),low,high)]
            for a,b in zip([h,*climate],wanted):
                error=abs(a-b)/max(1.,abs(b));maximum=max(maximum,error)
                assert math.isfinite(a) and error<.0003,(p,a,b,error)
            choice=expected(self.targets,list(climate),0)
            assert biome==(MASK if choice is None else choice[0]['biome']),(p,biome,choice)
        return maximum


def exercise(binary,profiles,invalid,gpu):
    worker=Worker(binary,gpu);dispatches=0;hashes=[];records=[];maximum=0.;checked=0
    def call(op,data=b'',status=0):
        nonlocal dispatches
        if status==0 and op in (12,13,14,16):dispatches+=1
        return worker.call(op,data,status=status)
    def error(op,data,code):
        assert call(op,data,1)==struct.pack('>I',code),(op,code)
    def generate(tile,source):
        desc=struct.unpack('>11I',call(18,tile_bytes(tile)))
        desc=tuple(v-(1<<32) if i in range(1,7) and v&(1<<31) else v for i,v in enumerate(desc))
        assert desc[0]==1 and desc[7:9]==tuple(source['registry_program']['terrain_cell'])
        if source['registry_program']['surface'][2]:assert desc[2]==desc[5]==0
        else:assert (desc[2],desc[5])==(source['geology_min_y'],source['geology_min_y']+source['geology_height'])
        counts=layout(desc)[1];assert call(13,encoded(desc))==struct.pack('>4I',*counts,math.prod(counts))
        assert call(16,tile_bytes(tile))==struct.pack('>3I',tile[2],tile[3],tile[2]*tile[3])
        return desc
    try:
        signal.alarm(300)
        for op in (15,16,17,18):error(op,b'',716)
        for name,source,path in profiles:
            signal.alarm(300);print(f'{"GPU" if gpu else "CPU"} {name}: surface tiles',flush=True)
            call(5,path_request(path));call(11)
            error(18,tile_bytes((0,0,8,8,LOW,HIGH)),722)
            config=struct.unpack('>6I',call(15))
            assert config==(source['geology_min_y']&MASK,source['geology_height'],source['sea_level']&MASK,
                           *source['registry_program']['terrain_cell'],source['registry_program']['surface'][2])
            error(16,tile_bytes((0,0,8,8,LOW,HIGH)),714);call(9)
            error(16,tile_bytes((0,0,8,8,LOW,HIGH)),718)
            reference=SurfaceReference(source);times=[];count=0
            tiles=[(-35,61,9,7,LOW,HIGH),(29999991,-30000007,8,8,LOW,HIGH),
                   (-(1<<31),(1<<31)-7,7,7,MASK,MASK)]
            for tile in tiles:
                start=time.perf_counter();desc=generate(tile,source);times.append((time.perf_counter()-start)*1000)
                points=[(x,z,*tile[-2:]) for z in range(tile[1],tile[1]+tile[3]) for x in range(tile[0],tile[0]+tile[2])]
                points += [(tile[0]-1 if tile[0]>-(1<<31) else tile[0]+tile[2],tile[1],*tile[-2:]),
                           (tile[0],tile[1],tile[-2]^1,tile[-1]),(tile[0],tile[1],tile[-2],tile[-1]^1)]
                raw=call(17,query_bytes(points));maximum=max(maximum,reference.verify(raw,points,desc,tile));checked+=len(points);count+=len(points)
                hashes.append(hashlib.sha256(raw).hexdigest())
                assert call(17,query_bytes(points))==raw
                assert call(17,query_bytes(points[::-1]))==b''.join(raw[i:i+36] for i in range(len(raw)-36,-1,-36))
                assert call(17,query_bytes([]))==b''
                error(15,b'\0',603);error(16,b'',720);error(17,b'\0',603)
                for invalid_tile in [(tile[0],tile[1],0,8,LOW,HIGH),(0,0,1025,8,LOW,HIGH),
                                (0,0,8,0,LOW,HIGH),(0,0,8,1025,LOW,HIGH),((1<<31)-1,0,2,1,LOW,HIGH)]:
                    error(18,tile_bytes(invalid_tile),720)
                error(16,tile_bytes((*tile[:4],tile[-2]^1,tile[-1])),721)
                assert call(17,query_bytes(points))==raw
                # Direct numeric and lattice query commands retain columns.
                q=[(1,tile[0],0,tile[1],*tile[-2:])]
                call(12,density_request(q));call(14,density_request(q))
                assert call(17,query_bytes(points))==raw
            a=(-35,61,9,7,LOW,HIGH);b=(-31,61,9,7,LOW,HIGH)
            common=[(x,z,LOW,HIGH) for x in range(-31,-26) for z in range(61,68)]
            generate(a,source);left=call(17,query_bytes(common));generate(b,source);right=call(17,query_bytes(common))
            assert left==right,(name,'surface tile seam');hashes.append(hashlib.sha256(left).hexdigest());checked+=len(common)
            # A deliberately larger Y cache must still clip to profile max Y.
            if not source['registry_program']['surface'][2]:
                desc=list(struct.unpack('>11I',call(18,tile_bytes(b))));desc[5]+=16
                call(13,struct.pack('>11I',*desc));error(17,query_bytes(common),723)
                call(16,tile_bytes(b));assert call(17,query_bytes(common))==right
            if name=='ramp':
                good=list(struct.unpack('>11I',call(18,tile_bytes(b))))
                wrong=[]
                for index,value in [(0,2),(7,3),(8,5),(5,0),(4,good[1])]:
                    bad=good.copy();bad[index]=value;wrong.append(bad)
                for bad in wrong:
                    call(13,struct.pack('>11I',*bad));error(16,tile_bytes(b),721);error(17,query_bytes(common),723)
                generate(b,source)
            call(15);error(17,query_bytes(common),723)
            generate(b,source);call(11);error(17,query_bytes(common),723);error(18,tile_bytes(b),722)
            call(5,path_request(path));error(17,query_bytes(common),716)
            records.append({'name':name,'validated_queries':count+len(common),'tiles':len(tiles)+2,
                'density_and_surface_host_ms':times,'negative_distant_i32_endpoints':'pass','repeat_reorder':'pass',
                'overlapping_tiles':'byte-identical','rejected_request_retention':'pass','reload_invalidation':'pass'})
        for name,path in invalid:
            call(5,path_request(path));call(11);error(15,b'',719)
            error(18,tile_bytes((0,0,8,8,LOW,HIGH)),722)
        # A complete 512x512 column tile with inexpensive analytic equations.
        name,source,path=profiles[0];call(5,path_request(path));call(11);call(9);call(15)
        tile=(0,0,512,512,LOW,HIGH);start=time.perf_counter();desc=generate(tile,source);wall=(time.perf_counter()-start)*1000
        points=[(i%512,i//512,LOW,HIGH) for i in range(0,512*512,67)][:4096]
        points += points[:4096-len(points)]
        raw=call(17,query_bytes(points));maximum=max(maximum,SurfaceReference(source).verify(raw,points,desc,tile));checked+=len(points);hashes.append(hashlib.sha256(raw).hexdigest())
        error(17,query_bytes(points+points[:1]),603)
        call(4);assert worker.process.wait(timeout=10)==0
        diagnostic=worker.process.stderr.read().decode()
        return {'mode':'gpu_required' if gpu else 'cpu','profiles':records,'validated_queries':checked,'requests':worker.id,
            'max_relative_or_absolute_error':maximum,'analytic_full_tile_columns':262144,'analytic_full_tile_host_ms':wall,
            'invalid_profile_configs':len(invalid),'expected_gpu_dispatches':dispatches,'aggregate_sha256':hashlib.sha256(''.join(hashes).encode()).hexdigest(),
            'observed_metal_command_ms':[float(v) for v in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',diagnostic)]}
    finally:
        signal.alarm(0);worker.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend',type=Path,default=DEFAULT_BEND);parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true');parser.add_argument('--profile',nargs=2,action='append',default=[])
    args=parser.parse_args();binary=ROOT/'build/bend/engine'
    if not args.skip_build:build(args.bend,binary,ROOT/'bend/engine.bend')
    folder=ROOT/'build/bend/surface-profiles';profiles=fixture_profiles(folder);invalid=invalid_profiles(folder,profiles[0][1])
    for source,path in args.profile:
        source,path=Path(source),Path(path);profiles.append((source.parent.name,json.loads(source.read_text()),path))
    report={'scope':'Resident GPU lattice surface heights, climate and surface biome IDs; no materials, caves or complete-region output',
        'column_capacity':1048576,'cpu_workers':2,'nice':10,'runs':[exercise(binary,profiles,invalid,gpu) for gpu in (False,True)]}
    if args.prove_metal:
        probe=compile_metal_observer(args.bend,ROOT/'bend/engine.bend',ROOT/'build/bend/surface-metal-probe')
        observed=exercise(probe,profiles,invalid,True);times=observed['observed_metal_command_ms']
        assert len(times)==observed['expected_gpu_dispatches'],(len(times),observed['expected_gpu_dispatches'])
        assert all(t>0 for t in times)
        assert observed['aggregate_sha256']==report['runs'][1]['aggregate_sha256']
        report['metal_observation']={'diagnostic_only':True,'command_buffers':len(times),'device_ms':{'min':min(times),'max':max(times),'sum':sum(times)},'normal_output_bytes_equal':True}
    report['cpu_gpu_bytes_equal']=report['runs'][0]['aggregate_sha256']==report['runs'][1]['aggregate_sha256']
    report['profiles']=[{'name':name,'wire_sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for name,_,path in profiles]
    report['source_sha256']={name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in
        ('bend/terrain_surface.bend','bend/registry_surface.bend','bend/registry_density.bend','bend/engine.bend','scripts/test_bend_surface.py')}
    destination=ROOT/'build/bend/surface-tests.json';destination.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: independent CPU/GPU surface tiles; {destination}')


def timeout(_signal,_frame):
    raise TimeoutError('surface profile exceeded 300 seconds')


if __name__=='__main__':
    signal.signal(signal.SIGALRM,timeout);main()
