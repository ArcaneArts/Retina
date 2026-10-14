#!/usr/bin/env python3
"""Check terrain supports plus subsequent template writes against a mutable world."""
import argparse,copy,hashlib,json,os,time
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,command,metal_compiler_command
from test_bend_registry_noise import fixture_wire
from test_bend_registry import Document
from test_bend_structure_blocks import BlockReader,mixed,i64
from test_bend_structure_processors import name
from test_bend_structures import rotate,add
from test_bend_structure_nbt import expected as expected_nbt
from test_bend_binary import NbtReader

def controls(p):
 names=list(map(name,p['materials']));ids=[]
 for n in ('stone','water','lava','grass_block','dirt','sandstone','oak_log','oak_leaves','short_grass','jigsaw','structure_void','structure_block'):
  full='minecraft:'+n
  if full in names:ids.append(names.index(full))
 palette=[[i]*4 for i in ids];raw=[[i,0,0,i] for i in range(len(ids))]+[[0,1,1,0]]
 ti=len(p['structures']['templates']);p['structures']['templates'].append(dict(id='test:support_floor',size=[len(ids),2,2],ground=0,palettes=[palette],blocks=sum(raw,[]),tags={},entities=[],joints=[]))
 rows=[];stone=names.index('minecraft:stone');dirt=names.index('minecraft:dirt');grass=names.index('minecraft:grass_block')
 for bi,b in enumerate(p['biomes']):
  for r in range(4):
   # Actual biome fillers and flags, with a deliberately different observed
   # soil to distinguish badlands fallback from column-selected material.
   for c,y in enumerate([62,66,80,321,-62]):
    rows.append([ti,bi,r,y,c!=3,29999984 if r%2 else -29999984,-16,stone,dirt,grass,[p['water'],p['lava'],0,stone,0][c],c==4])
 # Original exported structures also exercise tagged floors and nontrivial shapes.
 for t in range(min(20,ti)):
  for r in range(4):rows.append([t,0,r,70,True,-16,0,stone,dirt,grass,p['water'],False])
 p['support_cases']=rows
 return p

def expected(p,row):
 ti,bi,r,y,enabled,ox,oz,stone,soil,top,cover,matching=row;t=p['structures']['templates'][ti];names=list(map(name,p['materials']));origin=(ox+8,y,oz+8);writes=[];tags=[];world={}
 def inside(pos):return ox<=pos[0]<ox+16 and oz<=pos[2]<oz+16 and -64<=pos[1]<320
 def read(pos):return world.get(pos,stone if pos[1]<60 else soil if pos[1]<63 else top if pos[1]<64 else cover if pos[1]<69 else 0)
 def replaceable(mat):return mat in (0,p['water'],p['lava']) or bool(p['material_flags'][mat]&28)
 blocks=[]
 for i in range(len(t['blocks'])//4):
  raw=t['blocks'][4*i:4*i+4];local=rotate(raw[:3],r);original=t['palettes'][0][raw[3]][r];material=next((j['final'][r] for j in t['joints'] if j['pos']==raw[:3]),0) if names[original]=='minecraft:jigsaw' else original
  blocks.append((i,local,original,material))
 def write(pos,mat,tag):world[pos]=mat;writes.append((pos,mat));tags.append((pos,tag))
 if enabled and not matching:
  filler=p['biomes'][bi]['filler'] if p['biomes'][bi]['flags']&8 or soil==0 else soil
  for i,local,original,material in blocks:
   if local[1]!=0 or replaceable(original) or names[original] in ('minecraft:jigsaw','minecraft:structure_void','minecraft:structure_block'):continue
   x,y,z=add(origin,local)
   if not ox<=x<ox+16 or not oz<=z<oz+16:continue
   bottom=max(y-12,63,-64)
   for py in range(min(y,320)-1,bottom-1,-1):
    pos=(x,py,z)
    if not replaceable(read(pos)):break
    write(pos,filler,None)
 for i,local,original,material in blocks:
  pos=add(origin,local)
  if matching:pos=(pos[0],63+local[1],pos[2])
  if not inside(pos) or names[original] in ('minecraft:structure_void','minecraft:structure_block'):continue
  tag=copy.deepcopy(t['tags'].get(str(i))) if material else None
  if tag is not None:
   tag[1].update(x=[3,pos[0]],y=[3,pos[1]],z=[3,pos[2]],keepPacked=[1,1])
   if 'LootTable' in tag[1]:tag[1]['LootTableSeed']=[4,i64(mixed(123^((pos[1]+64)*256+(pos[2]-oz)*16+pos[0]-ox)))]
  write(pos,material,tag)
 return writes,tags

def verify(binary,path,folder):
 p=controls(json.loads(path.read_text()));wire=fixture_wire(p,True);inp=folder/(path.stem+'.rbp');inp.write_bytes(wire);out=folder/(path.stem+'.bin');start=time.monotonic()
 command(['nice','-n','10',binary,inp,out,'--threads','2','--gpu','off'],timeout=600,cwd=ROOT);elapsed=time.monotonic()-start
 r=BlockReader(out.read_bytes(),Document(wire).strings);assert r.u32()==len(p['support_cases']);total=0
 for i,row in enumerate(p['support_cases']):
  assert r.u32()==1,(path.name,i,'invalid');assert r.u32()==0
  writes=[(r.point(),r.u32()) for _ in range(r.u32())];tags=[(r.point(),r.node()) for _ in range(r.u32())];want=expected(p,row)
  assert (writes,tags)==want,(path.name,i,row,next(((a,b) for a,b in zip(writes,want[0]) if a!=b),None),len(writes),len(want[0]),next(((a,b) for a,b in zip(tags,want[1]) if a!=b),None))
  nbt=NbtReader(r.data);nbt.offset=r.at
  for _,tag in tags:
   present=nbt.number('>B');assert present==int(tag is not None)
   if present:assert nbt.number('>B')==10 and nbt.text()=='';assert nbt.tag(10)==expected_nbt(tag)
  r.at=nbt.offset;total+=len(writes)
 assert r.at==len(r.data)
 result=dict(profile=path.name,cases=len(p['support_cases']),writes=total,biomes=len(p['biomes']),worker_wall_seconds=elapsed,profile_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),input_sha256=hashlib.sha256(wire).hexdigest(),output_sha256=hashlib.sha256(r.data).hexdigest());print(json.dumps(result),flush=True);return result

def main():
 p=argparse.ArgumentParser();p.add_argument('--build',action='store_true');p.add_argument('--profiles',type=Path,default=ROOT/'build/bend/profiles');args=p.parse_args()
 folder=ROOT/'build/bend/structure-supports';folder.mkdir(parents=True,exist_ok=True);binary=folder/'fixture';generated=folder/'fixture.c'
 if args.build:
  command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/structure-supports.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
  command([v for v in metal_compiler_command(generated,binary) if v!='-DBEND_METAL=1'],timeout=600,cwd=ROOT)
 results=[verify(binary,path,folder) for path in sorted(args.profiles.glob('profile-*.json'))];assert len(results)==3
 report=dict(results=results,status='pass',executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'bend/structure_supports.bend',ROOT/'bend/structure_blocks.bend',ROOT/'bend/tests/structure-supports.bend',Path(__file__)]})
 (ROOT/'docs/benchmarks/bend-structure-supports.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':main()
