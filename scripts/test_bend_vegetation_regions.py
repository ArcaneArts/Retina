#!/usr/bin/env python3
"""Loaded vegetation on independently planned neighboring cores, with MCA output.

Real recipes/palette stay unchanged; a uniform registered forest substrate
isolates CPU composition, journals and partition invariance from terrain noise.
Actual worker terrain and GPU execution have separate integration checks.
"""
import argparse,copy,hashlib,json,struct
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,build,command,metal_compiler_command
from test_bend_registry_noise import fixture_wire
from test_bend_placement_walker import walk,shift
from test_bend_trees import plan,Random,DIRECTIONS
from test_bend_surface_features import place
from bend_nested_reference import place as nested_place,KINDS as NESTED
from bend_attachment_reference import place as attachment_place
from bend_forest_reference import place as forest_place
from test_bend_placement_merge import sources
from test_bend_formats import read_region
from test_bend_chunks import palette,unpack,MAP_NAMES

SEED=0x80000000075bcd15
MINIMUM,MAXIMUM=-64,320
MASK=(1<<32)-1

class World:
    minimum,maximum=MINIMUM,MAXIMUM
    def __init__(self,source,spec):
        self.source,self.spec=source,spec;self.ox,self.oz,self.width,self.depth=spec[:4];self.biome_id=spec[8]
        self.patch={};self.commands=[];self.plant=None
    def column(self,x,z):
        dx,dz=x-self.ox,z-self.oz
        return dx+dz*self.width if 0<=dx<self.width and 0<=dz<self.depth else None
    def material(self,x,y,z):
        if not self.minimum<=y<self.maximum:return 0
        if self.column(x,z) is None:return None
        if (x,y,z) in self.patch:return self.patch[x,y,z]
        if y==-64:return self.spec[10]
        return self.spec[9] if y<78 else self.spec[12] if y==78 else self.spec[11] if y==79 else 0
    def put_role(self,x,y,z,m,r,e=0):
        if self.column(x,z) is not None and self.minimum<=y<self.maximum and 0<=m<len(self.source['materials']):
            self.patch[x,y,z]=m;self.commands.append(((x,y,z),m,r,e))
    def put(self,x,y,z,m):
        before=len(self.commands);self.put_role(x,y,z,m,6)
        if self.plant is not None and len(self.commands)>before:self.plant.append(self.commands.pop())
    def flags(self,m):return self.source['material_flags'][m] if m is not None else 0
    def biome(self,x,y,z):return None if self.column(x,z) is None else self.biome_id
    def base_height(self,x,z):return None if self.column(x,z) is None else 80
    def above(self,p):return self.column(p[0],p[2]) is not None and p[1]>=80
    def height(self,x,z,kind):
        if self.column(x,z) is None:return None
        top=max([80]+[p[1]+1 for p in self.patch if p[0]==x and p[2]==z])
        for y in range(min(top,self.maximum)-1,self.minimum-1,-1):
            if self.source['heightmap_masks'][self.material(x,y,z)]&(1<<kind):return y+1
        return self.minimum
    def empty(self,m):return m==0 or bool(self.flags(m)&64) or m in (self.source['water'],self.source['lava'])
    def ground(self,x,z,layer=None):
        if layer is None:return 80
        start=self.height(x,z,4)
        if start is None:return None
        current=self.material(x,start,z)
        for y in range(start,self.minimum,-1):
            below=self.material(x,y-1,z)
            if self.empty(current) and not self.empty(below) and below!=self.source['bedrock'] and not self.flags(below)&128:
                if layer==0:return y
                layer-=1
            current=below
        return None


def replay_tree(writes,world):
    for phase in range(5):
        for at,m,r,e in writes:
            if r!=phase:continue
            previous=world.material(*at)
            if previous is None:continue
            ok=(bool(world.flags(previous)&1) or previous==world.source['snow']) if r==0 else (previous==0 or bool(world.flags(previous)&20)) if r==1 else previous==0
            if not ok:continue
            if r>=3:
                d=DIRECTIONS[e];support=shift(at,tuple(-v for v in d));above=shift(at,(0,1,0))
                supported=support is not None and world.flags(world.material(*support))&(32 if r==3 else 12)
                hanging=r==4 and above is not None and world.material(*above)==m
                if not supported and not hanging:continue
            world.put_role(*at,m,r,world.source['snow'] if r==0 else e)


def reference(source,spec):
    x,z,w,d=spec[4:8];anchors=[];candidates=placed=0;kinds=set()
    for ax in range(x-16,x+w+16,16):
        for az in range(z-16,z+d+16,16):
            world=World(source,spec)
            def visit(recipe,at,state):
                nonlocal placed
                f=source['decorations'][recipe];kind=f['kind'];rng=Random(state);kinds.add(kind)
                if kind=='tree':ok,writes=plan(f,rng,world,at);replay_tree(writes,world)
                elif kind in NESTED:ok=nested_place(f,rng,world,at,source)
                elif kind=='attachment_growth':ok=attachment_place(f,rng,world,at)
                elif kind in ('huge_mushroom','fallen_tree'):ok=forest_place(f,rng,world,at)
                else:
                    if kind=='plant':world.plant=[]
                    ok=place(f,rng,world,at,source)
                    if kind=='plant':
                        commands=world.plant;world.plant=None
                        if commands:
                            assert len(commands)<=2
                            at,m,_,_=commands[-1];upper=commands[0][1] if len(commands)==2 else 0
                            world.commands.append((at,m,5,upper))
                placed+=bool(ok);return True
            ids=source['biomes'][spec[8]]['decorations']
            result=walk(source,0,world=world,selection=ids,anchor=(ax,-64,az),seed=SEED,visitor=visit,height=384,limit_steps=1<<30,limit_candidates=1<<30)
            assert result['status']==0 and result['missing'] is None
            candidates+=result['candidates'];anchors.append(((ax,az),world.commands))
    merged=World(source,spec)
    for _,commands in sorted(anchors):
        for at,m,r,e in commands:
            previous=merged.material(*at)
            if previous is None:continue
            ok=r==6 or ((bool(merged.flags(previous)&1) or previous==e) if r==0 else (previous==0 or bool(merged.flags(previous)&20)) if r==1 else previous==0)
            if not ok:continue
            if r in (3,4):
                delta=DIRECTIONS[e];support=shift(at,tuple(-v for v in delta));above=shift(at,(0,1,0))
                ok=support is not None and bool(merged.flags(merged.material(*support))&(32 if r==3 else 12))
                if r==4:ok=ok or above is not None and merged.material(*above)==m
                if not ok:continue
            if r==5 and e:
                above=shift(at,(0,1,0))
                if above is None or not merged.minimum<=above[1]<merged.maximum or merged.material(*above)!=0:continue
                merged.put(*above,e)
            merged.put(*at,m)
    positions=sorted(merged.patch,key=lambda p:(p[0],p[2],p[1]))
    for at in positions:
        m=merged.material(*at);floor=source['plant_floor_masks'][m]
        if floor and (at[1]==merged.minimum or not merged.flags(merged.material(at[0],at[1]-1,at[2]))&floor):merged.put(*at,0)
    for at in positions:
        m=merged.material(*at);half=source['plant_halves'][m]
        if half:
            py=at[1]+(1 if half>0 else -1)
            if not merged.minimum<=py<merged.maximum or source['plant_halves'][merged.material(at[0],py,at[2])]!=-half:merged.put(*at,0)
    return (len(anchors),candidates,placed),merged,sorted(kinds)


def check(data,source,spec):
    stats,world,kinds=reference(source,spec);at=0
    def words(n):
        nonlocal at
        result=struct.unpack_from('>'+str(n)+'I',data,at);at+=4*n;return result
    assert words(3)==stats
    x,z,w,d=spec[4:8];assert words(8)==(x&MASK,z&MASK,w,d,123456789,2147483648,MINIMUM&MASK,384)
    output={}
    for dz in range(d):
        for dx in range(w):
            h,b,count,valid,n=words(5);assert (h,b,valid,count)==(80,spec[8],1,n)
            cells=[]
            for _ in range(n):
                a,end,m=words(3);assert a==len(cells) and a<end<=384;cells.extend([m]*(end-a))
            expected=[world.material(x+dx,y,z+dz) for y in range(MINIMUM,MAXIMUM)]
            assert cells==expected,(dx,dz,next((y for y in range(384) if cells[y]!=expected[y]),None))
            output[x+dx,z+dz]=cells
    assert at==len(data) and stats[2]>0
    return output,dict(anchors=stats[0],candidates=stats[1],placed=stats[2],kinds=kinds,voxels=w*d*384,sha256=hashlib.sha256(data).hexdigest())


def check_mca(path,source,columns):
    chunks=read_region(path.read_bytes());checked=0
    assert len(chunks)==len(columns)//256
    for record in chunks.values():
        tag=record['tag'];cx,cz=tag['xPos'],tag['zPos'];heights=[[0]*256 for _ in range(6)]
        for s,section in enumerate(tag['sections']):
            for i,state in enumerate(palette(section['block_states'],4096,4)):
                x,z=cx*16+i%16,cz*16+(i%256)//16;y=s*16+i//256;m=columns[x,z][y]
                want=source['materials'][m];want={'id':want} if isinstance(want,str) else want
                assert state==want
                for k in range(6):
                    if source['heightmap_masks'][m]&(1<<k):heights[k][i%256]=y+1
                checked+=1
        for k,key in enumerate(MAP_NAMES):assert unpack(tag['Heightmaps'][key],256,9)==heights[k]
    return dict(chunks=len(chunks),blocks_checked=checked,sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,action='append',required=True);p.add_argument('--skip-build',action='store_true');a=p.parse_args()
    driver=ROOT/'bend/tests/vegetation-region.bend';binary=ROOT/'build/bend/vegetation-region';folder=ROOT/'build/bend/vegetation-region-tests';folder.mkdir(parents=True,exist_ok=True)
    if not a.skip_build:build(DEFAULT_BEND,binary.with_suffix('.c'),driver);command(metal_compiler_command(binary.with_suffix('.c'),binary),timeout=600)
    runs=[]
    for i,path in enumerate(a.profile):
        source=json.loads(path.read_text());forest=next(i for i,b in enumerate(source['biomes']) if b['id']=='minecraft:forest');b=source['biomes'][forest]
        x,z=29990000,-30000000;tail=[forest,source['stone'],source['bedrock'],b['top'],b['filler']]
        specs=[[x,z,96,80,x+32,z+32,32,16,*tail],[x,z,80,80,x+32,z+32,16,16,*tail],[x+16,z,80,80,x+48,z+32,16,16,*tail]]
        payload=copy.deepcopy(source);payload['vegetation_region_cases']=specs;inp=folder/f'profile-{i}.rbp';inp.write_bytes(fixture_wire(payload,True));out=folder/f'profile-{i}'
        result=command(['nice','-n','10',binary,inp,out,'--threads','2','--gpu','off'],timeout=1800);assert 'PASS: composed vegetation cores' in result.stdout
        outputs=[];records=[]
        for j,spec in enumerate(specs):
            data=Path(str(out)+'.'+str(j)).read_bytes();columns,row=check(data,source,spec);row['mca']=check_mca(Path(str(out)+'.'+str(j)+'.mca'),source,columns);outputs.append(columns);records.append(row)
        assert outputs[0]==outputs[1]|outputs[2],'core partition seam'
        runs.append(dict(profile=str(path),cases=records,partition_equal=True));print('profile',i,'partition pass',flush=True)
    paths=sources(driver)|{Path(__file__).resolve(),ROOT/'scripts/test_bend_trees.py',ROOT/'scripts/test_bend_surface_features.py',ROOT/'scripts/test_bend_placement_walker.py',ROOT/'scripts/bend_nested_reference.py',ROOT/'scripts/bend_forest_reference.py',ROOT/'scripts/bend_attachment_reference.py'}
    report=dict(scope=__doc__.strip(),execution_mode='cpu',runs=runs,sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)},native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-vegetation-regions-correctness.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()
