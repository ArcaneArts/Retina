#!/usr/bin/env python3
"""Ordered accepted feature overlays -> resident runs -> NBT/zlib/MCA.

Independent dense Python voxels check every output cell, final plant support and
pair identity, source metadata, cave-biome queries, cropping and serialized
heightmaps. CPU/observed Metal component evidence; playable vegetation integration
and a complete lit-region performance comparison remain separate requirements.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import random
import re
import struct
import subprocess
import zlib

from test_bend_compression import ROOT, DEFAULT_BEND, build, command, verify_stream, metal_compiler_command
from test_bend_engine import compile_metal_observer
from test_bend_placement_programs import origin, signed
from test_bend_registry_noise import fixture_wire
from test_bend_chunks import read_nbt, palette, unpack, MAP_NAMES

MASK = (1 << 32)-1
MINIMUM, HEIGHT = -64, 128


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def name(material):
    return material if isinstance(material, str) else material['id']


def controls(source):
    flags, halves, floors = (source[k] for k in ('material_flags','plant_halves','plant_floor_masks'))
    groups = {}
    for material, half in enumerate(halves):
        if half: groups.setdefault(abs(half), {})[half > 0] = material
    pairs = [row for row in groups.values() if set(row) == {False, True}]
    assert len(pairs) > 1 and any(halves[row[True]] > 1 for row in pairs)
    soil = lambda mask: next((i for i, f in enumerate(flags) if f & mask), 1)
    path = next(i for i, m in enumerate(source['materials']) if name(m) == 'minecraft:dirt_path')
    grasses = [i for i, mask in enumerate(floors) if mask and not halves[i]]
    assert grasses and flags[path] == 0
    return pairs, grasses, soil, path


def fixtures(source):
    pairs, grasses, soil, path = controls(source)
    cases = []
    for context in range(8):
        ox, oz = origin(context)
        def write(patch, x, y, z, material):
            patch.append((signed((ox+x)&MASK), y, signed((oz+z)&MASK), material))
        patches = [[], [], [], []]
        for i, pair in enumerate(pairs):
            x, z, y = i % 4, (i//4) % 4, -40+(i//16)*5
            lower, upper = pair[True], pair[False]
            write(patches[0], x, y-1, z, soil(source['plant_floor_masks'][lower]))
            write(patches[0], x, y, z, lower)
            write(patches[0], x, y+1, z, upper)
            if i % 4 == 0: write(patches[2], x, y-1, z, path)
            elif i % 4 == 1: write(patches[2], x, y+1, z, 0)
            elif i % 4 == 2: write(patches[2], x, y+1, z, pairs[(i+1)%len(pairs)][False])
        # Unsupported grass on paths, supported grass, floor/build-height edges,
        # explicit air removal, duplicate updates and a deliberately invalid column.
        for i, g in enumerate(grasses[:16]):
            x,z,y=i%4,(i//4)%4,20
            write(patches[1],x,y-1,z,soil(source['plant_floor_masks'][g]))
            write(patches[1],x,y,z,g)
            if i%2 == 0: write(patches[3],x,y-1,z,path)
        lower, upper = pairs[0][True], pairs[0][False]
        for x,y,material in [(0,-64,lower),(0,-63,upper),(1,63,lower),(1,64,upper),(2,-64,upper),
                             (0,-60,0),(0,-60,1),(0,-60,0),(3,0,lower)]:
            write(patches[3],x,y,3 if x==3 else 0,material)
        write(patches[3],-1,0,0,1); write(patches[3],4,0,0,1)
        if context == 1: patches = []
        elif context == 2: patches.reverse()
        elif context >= 4:
            rng=random.Random(8191+context)
            for _ in range(512):
                write(patches[rng.randrange(4)],rng.randrange(-2,6),rng.randrange(-70,71),rng.randrange(-2,6),
                      rng.choice([0,1,source['water'],path,*grasses[:8]]))
        cases.append(patches)
    return cases


def base(source, width, context):
    voxels, metadata = [], []
    for i in range(width*width):
        if width == 4:
            cells=([source['bedrock']]+[1]*9+[0]*3+[1]*7+[source['water']]*4+[0]*104)
            meta=(-40,(15-i)%len(source['biomes']),6,int(i!=15))
        else:
            cells=([source['bedrock']]+[1]*54+[10]*8+[9]+[0]*64)
            meta=(0,i%len(source['biomes']),6|(1<<27),int(i!=1023))
        assert len(cells)==HEIGHT
        voxels.append(cells); metadata.append(meta)
    return voxels, metadata


def merge(source, context, patches, width=4):
    voxels, metadata=base(source,width,context)
    ox,oz=origin(context)
    # A dictionary models one anchor's last write. Anchor order is explicit,
    # independent of treap shape, parallel scheduling or the run representation.
    for patch in patches:
        for (x,y,z),material in {(x,y,z):m for x,y,z,m in patch}.items():
            dx,dz=(x-ox)&MASK,(z-oz)&MASK
            if dx<width and dz<width and MINIMUM<=y<MINIMUM+HEIGHT and metadata[dx+dz*width][3]:
                voxels[dx+dz*width][y-MINIMUM]=material
    removed=0
    for cells,meta in zip(voxels,metadata):
        if not meta[3]: continue
        for offset,material in enumerate(cells):
            mask=source['plant_floor_masks'][material]
            if mask and (offset==0 or not source['material_flags'][cells[offset-1]] & mask):
                cells[offset]=0; removed+=1
        for offset,material in enumerate(cells):
            half=source['plant_halves'][material]
            if half:
                partner=offset+(1 if half>0 else -1)
                if not 0<=partner<HEIGHT or source['plant_halves'][cells[partner]] != -half:
                    cells[offset]=0; removed+=1
    return voxels,metadata,removed


class Reader:
    def __init__(self,data): self.data,self.at=data,0
    def words(self,n):
        result=struct.unpack_from('>'+str(n)+'I',self.data,self.at);self.at+=n*4
        return result
    def grid(self):
        header=self.words(8);width,depth=header[2:4];rows=[]
        for _ in range(width*depth):
            *meta,n=self.words(5);runs=[self.words(3) for _ in range(n)]
            cells=[];previous=None
            for start,end,material in runs:
                assert start==len(cells) and start<end<=HEIGHT,(start,end,len(cells))
                assert previous!=material,('uncoalesced output',runs)
                cells += [material]*(end-start);previous=material
            assert len(cells)==HEIGHT
            rows.append((tuple(meta),cells))
        return header,rows


def check_output(data,source,context,patches):
    voxels,metadata,removed=merge(source,context,patches)
    reader=Reader(data);header,rows=reader.grid();ox,oz=origin(context)
    assert header==(ox&MASK,oz&MASK,4,4,123456789,2147483648,MINIMUM&MASK,HEIGHT)
    for i,(meta,cells) in enumerate(rows):
        assert meta==tuple(v&MASK for v in metadata[i]),(context,i,'metadata',meta,metadata[i])
        assert cells==voxels[i],(context,i,next((y for y in range(HEIGHT) if cells[y]!=voxels[i][y]),None))
    probes=reader.words(64)
    assert probes[::2]==probes[1::2],('cave/surface biome changed',context)
    if context%2==0:
        assert len(set(probes[::2]))>1,('cave fixture did not vary',context)
    crop,subset=reader.grid()
    assert crop==((ox+1)&MASK,(oz+1)&MASK,2,2,*header[4:])
    assert subset==[rows[z*4+x] for z in (1,2) for x in (1,2)]
    assert reader.at==len(data)
    return dict(voxels_checked=len(voxels)*HEIGHT,removed_plants=removed,biome_queries=32,crop_columns=4)


def check_mca(path,source,patches):
    data=path.read_bytes();assert len(data)%4096==0
    ox,oz=origin(0);cx,cz=ox//16,oz//16;slot=(cx%32)+(cz%32)*32
    locations=[int.from_bytes(data[i*4:i*4+4],'big') for i in range(1024)]
    assert [i for i,v in enumerate(locations) if v]==[slot]
    offset,count=locations[slot]>>8,locations[slot]&255
    assert offset==2 and (offset+count)*4096==len(data)
    assert struct.unpack_from('>I',data,4096+slot*4)[0]==1700000000
    length=struct.unpack_from('>I',data,offset*4096)[0];assert data[offset*4096+4]==2
    encoded=data[offset*4096+5:offset*4096+4+length];raw=zlib.decompress(encoded)
    verify_stream(encoded,raw);tag=read_nbt(raw)
    assert (tag['xPos'],tag['zPos'],tag['yPos'],tag['DataVersion'])==(cx,cz,-4,5023)
    assert tag['Status']=='minecraft:features' and tag['isLightOn']==0
    assert len(tag['sections'])==8 and tag['block_entities']==tag['entities']==[]
    # The final accepted overlay is emitted by four F.place writers in Bend.
    writer=[(ox+8,y,oz+8,1) for y in range(4)]
    # Same four writers as the Bend merge fixture. Weighted plants consume one
    # draw each; bamboo draws its height and probability even at probability 0.
    from test_bend_decoration_counts import Random
    rng=Random(123);rng.unit();rng.unit();bamboo_height=rng.integer(5,16);rng.unit()
    writer.extend([(ox+9,0,oz+8,1),(ox+10,0,oz+8,1),(ox+10,1,oz+8,1)])
    writer.extend((ox+11,y,oz+8,1) for y in range(bamboo_height+1))
    voxels,metadata,removed=merge(source,0,[*patches,writer],width=32)
    heights=[[0]*256 for _ in range(6)];checked=0
    for section_index,section in enumerate(tag['sections']):
        assert section['Y']==-4+section_index
        assert 'SkyLight' not in section and 'BlockLight' not in section
        states=palette(section['block_states'],4096,4)
        for i,state in enumerate(states):
            y=section_index*16+i//256;c=i%256;x,z=c%16,c//16
            material=voxels[x+z*32][y]
            expected=source['materials'][material]
            if isinstance(expected,str): expected={'id':expected}
            assert state==expected,(section_index,i,state,material)
            for kind in range(6):
                if source['heightmap_masks'][material]&(1<<kind):heights[kind][c]=y+1
        biomes=palette(section['biomes'],64,1)
        for i,biome in enumerate(biomes):
            x,z=i%4*4,(i//4)%4*4
            assert biome==source['biomes'][metadata[x+z*32][1]]['id']
        checked+=4096
    assert set(tag['Heightmaps'])==set(MAP_NAMES)
    for kind,key in enumerate(MAP_NAMES):assert unpack(tag['Heightmaps'][key],256,HEIGHT.bit_length())==heights[kind]
    assert heights[0][8+8*16]==68,('actual feature writer did not raise heightmap',heights[0][8+8*16])
    assert heights[0][9+8*16]==65 and heights[0][10+8*16]==66, 'plant and aquatic pair heightmaps'
    assert heights[0][11+8*16]==65+bamboo_height, 'bamboo crown heightmap'
    return dict(surface_writer_families=4,blocks_checked=checked,quart_biomes=512,heightmaps=6,compressed_bytes=len(encoded),mca_bytes=len(data),removed_plants=removed)


def exercise(binary,folder,name,source,cases,gpu,threads=2):
    output=folder/(name+('-gpu' if gpu else '-cpu')+'-'+str(threads));input_path=folder/(name+'.rbp')
    payload=copy.deepcopy(source)
    payload['placement_merge_cases']=[[[v for point in patch for v in point] for patch in patches] for patches in cases]
    input_path.write_bytes(fixture_wire(payload,True))
    print(f'{name}: {"Metal" if gpu else "CPU"}, {threads} threads',flush=True)
    result=command(['nice','-n','10',binary,input_path,output,'--threads',threads,'--gpu','on' if gpu else 'off'],timeout=600,cwd=ROOT)
    assert 'PASS: ordered overlay merge' in result.stdout
    outputs=[Path(str(output)+'.'+str(i)).read_bytes() for i in range(len(cases))]
    rows=[check_output(data,source,i,patches) for i,(data,patches) in enumerate(zip(outputs,cases))]
    mca_path=Path(str(output)+'.mca');mca=check_mca(mca_path,source,cases[0]);outputs.append(mca_path.read_bytes())
    return outputs,dict(cases=rows,mca=mca,input_sha256=digest(input_path),output_sha256=[hashlib.sha256(data).hexdigest() for data in outputs]),result.stderr


def sources(path,seen=None):
    seen=set() if seen is None else seen
    path=path.resolve()
    if path in seen:return seen
    seen.add(path)
    for local in re.findall(r'^import (\.[^ ]+)',path.read_text(),re.M):sources(path.parent/local,seen)
    return seen


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile',type=Path,action='append',required=True)
    parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--prove-metal',action='store_true')
    args=parser.parse_args()
    driver=ROOT/'bend/tests/placement-merge.bend';binary=ROOT/'build/bend/placement-merge';observer=ROOT/'build/bend/placement-merge-observer'
    folder=ROOT/'build/bend/placement-merge-tests';folder.mkdir(parents=True,exist_ok=True)
    if not args.skip_build:
        print('Compiling stock Bend fixture at nice +10 (one compiler)',flush=True)
        generated=binary.with_suffix('.c')
        build(DEFAULT_BEND,generated,driver)
        command(metal_compiler_command(generated,binary),timeout=600,cwd=ROOT)
        command(['nice','-n','10',binary,'--threads','2','--gpu-build'],timeout=1800,cwd=ROOT)
        if args.prove_metal:compile_metal_observer(DEFAULT_BEND,driver,observer,gpu_archive=binary.with_suffix('.gpu'))
    runs=[];observed=[]
    for i,path in enumerate(args.profile):
        source=json.loads(path.read_text());cases=fixtures(source);name='profile-'+str(i)
        cpu,record,_=exercise(binary,folder,name,source,cases,False);runs.append(dict(profile=str(path),gpu=False,**record))
        gpu,record,_=exercise(binary,folder,name,source,cases,True);assert gpu==cpu
        runs.append(dict(profile=str(path),gpu=True,**record))
        if i==0:
            single,_,_=exercise(binary,folder,name,source,cases,False,threads=1);assert single==cpu
        if args.prove_metal:
            assert observer.with_suffix('.gpu').read_bytes()==binary.with_suffix('.gpu').read_bytes()
            output,record,stderr=exercise(observer,folder,'observer-'+name,source,cases,True);assert output==cpu
            times=[float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',stderr)]
            assert times,('no actual Metal command buffers',path)
            observed.append(dict(profile=str(path),command_buffers=len(times),device_ms=times,output_sha256=record['output_sha256']))
    paths=sources(driver)|{Path(__file__).resolve()}
    report=dict(scope=__doc__.strip(),bend_version=command([DEFAULT_BEND,'version']).stdout.strip(),runs=runs,
                cpu_gpu_bytes_equal=True,one_cpu_thread='pass',metal_observation=observed,
                original_profiles={str(p):digest(p) for p in args.profile},
                sources={str(p.relative_to(ROOT)):digest(p) for p in sorted(paths)},native_sha256=digest(binary),gpu_sha256=digest(binary.with_suffix('.gpu')))
    (ROOT/'docs/benchmarks/bend-placement-merge-correctness.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(dict(runs=len(runs),cases=sum(len(r['cases']) for r in runs),metal_commands=sum(r['command_buffers'] for r in observed)),indent=2))


if __name__=='__main__':main()
