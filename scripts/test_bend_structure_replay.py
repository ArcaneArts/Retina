#!/usr/bin/env python3
"""Independent complete-start replay, final plant repair and decoded chunk checks."""
import argparse,copy,hashlib,json,os,struct,time
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,command,metal_compiler_command
from test_bend_registry_noise import fixture_wire
from test_bend_structures import Planner,Random,MASK,rotate,add,intersects,signed
from test_bend_structure_processors import Oracle,name
from test_bend_structure_blocks import mixed,rol,i64
from test_bend_structure_entities import expected as entity_expected,ls
from test_bend_structure_nbt import expected as nbt_expected
from test_bend_chunks import unpack,MAP_NAMES
from test_bend_binary import NbtReader
from test_bend_formats import read_region

SEEDS=[123456789,MASK,(1<<63)|0xffffffff]
COORD=[0,-15,1874000]
def ground(context,x,z):
 x=max(0,min(511,x-(COORD[context]*16-256)));z=max(0,min(511,z-(-COORD[context]*16-256)))
 return 64 if context==0 else 40+x//16+z//24 if context==1 else 50+(x//8+z//8)%11

def controls(source):
 """Keep original profiles; add multipart overlaps and registered plant controls."""
 p=source['structures'];names=list(map(name,source['materials']))
 def mat(n):return names.index('minecraft:'+n)
 ids=[0,mat('oak_planks'),mat('chest'),mat('short_grass'),mat('dirt_path'),mat('gravel')]
 def make(label,second):
  raw=[[x,0,z,1 if not second else 4] for x in range(20) for z in range(4)]
  raw += [[x,1,1,2 if x%3==0 else 3] for x in range(20)]
  raw += [[1,1,1,2],[1,1,1,0]] if second else []
  tags={str(i):[10,{'id':[8,'minecraft:chest'],'LootTable':[8,'minecraft:chests/village/village_plains_house']}] for i,b in enumerate(raw) if b[3]==2}
  entity=[10,{'nbt':[10,{'id':[8,'minecraft:item_frame'],'block_pos':[11,[99,3,99]],'Facing':[1,2],'Rotation':ls(5,[0,0])}],'pos':ls(6,[1.5,2,1.5]),'blockPos':ls(3,[1,2,1])}]
  p['templates'].append(dict(id='test:'+label,size=[20,4,4],ground=0,palettes=[[[v]*4 for v in ids]],blocks=sum(raw,[]),tags=tags,entities=[entity],joints=[]))
  return len(p['templates'])-1
 a=make('first',False);b=make('second',True)
 cap={'processor_type':'capped','limit':7,'delegate':{'processor_type':'rule','rules':[{'input_predicate':{'predicate_type':'block_match','block':'minecraft:oak_planks'},'output_state':{'retina_rotations':[ids[5]]*4}}]}}
 for i in range(3):
  key='test:replay_'+str(i);e={'parts':[{'template':a,'ignore_air':False,'processors':[cap]},{'template':b,'ignore_air':i==1,'processors':[]}],'terrain_matching':i==2,'weight':1}
  p['pools'][key]={'fallback':key,'entries':[e]}
  p['definitions'].append(dict(id=key,kind='jigsaw',pool=key,biomes=[],config={'start_height':{'absolute':4},'project_start_to_heightmap':'WORLD_SURFACE_WG','terrain_adaptation':'none' if i==1 else 'beard_thin','size':0}))
 return source

class ReplayOracle:
 def __init__(self,source):
  self.source=source;self.p=source['structures'];self.names=list(map(name,source['materials']));self.ids=[self.names.index('minecraft:'+v) for v in ('stone','dirt','grass_block')]
  self.elements={i:e for i,e in enumerate((e for pool in self.p['pools'].values() for e in pool['entries']),1)}
  self.planner=Planner(self.p,{id(e):i for i,e in self.elements.items()});self.layouts={};self.programs={};self.blocks={}
 def layout(self,d,c):
  if (d,c) not in self.layouts:
   _,rows=self.planner.run(d,c);ps=[]
   for r in rows:
    e=self.elements[r[0]] if r[0] else {'parts':[{'template':self.p['definitions'][d]['template']}],'terrain_matching':False}
    ps.append(dict(element=e,pos=tuple(r[1:4]),rotation=r[4],box=r[5:11],palette=r[11],depth=r[13]))
   self.layouts[d,c]=ps
  return self.layouts[d,c]
 def groups(self):
  groups=[]
  for d in range(len(self.p['definitions'])):
   c=d%3;ps=self.layout(d,c);chunks={(COORD[c],-COORD[c])}
   choices=sorted({((p['box'][0]+p['box'][3])//32,(p['box'][2]+p['box'][5])//32) for p in ps})
   if len(choices)>4:choices=[choices[i*(len(choices)-1)//3] for i in range(4)]
   chunks.update(choices);chunks.add((COORD[c]+100,-COORD[c]+100))
   groups.append([c,[d],sorted(chunks)])
  # Combined starts and reversed request order; no state from one chunk can leak.
  n=len(self.p['definitions'])
  for c in range(3):
   chunks=[(COORD[c]+dx,-COORD[c]+dz) for dz in (-1,0,1) for dx in (-1,0,1)]
   groups.append([c,[n-3,n-2,n-1],chunks+list(reversed(chunks))])
  # Warm cache replay of original groups must have identical bytes and quotas.
  groups += copy.deepcopy(groups[:3])
  return groups
 def base(self,c,pos):
  h=ground(c,pos[0],pos[2]);y=pos[1]
  return self.ids[0] if y<h-4 else self.ids[1] if y<h-1 else self.ids[2] if y<h else 0
 def rotated(self,ti,pal,r):
  t=self.p['templates'][ti];key=(ti,pal%len(t['palettes']),r)
  if key not in self.blocks:
   out=[]
   for i in range(len(t['blocks'])//4):
    raw=t['blocks'][4*i:4*i+4];original=t['palettes'][key[1]][raw[3]][r]
    material=next((j['final'][r] for j in t['joints'] if j['pos']==raw[:3]),0) if self.names[original]=='minecraft:jigsaw' else original
    out.append((i,rotate(raw[:3],r),original,material))
   self.blocks[key]=out
  return self.blocks[key]
 def expected(self,group,chunk):
  context,ds,_=group;cx,cz=chunk;bounds=[cx*16,-64,cz*16,cx*16+15,319,cz*16+15];world={};tags={};entities=[];starts={};refs={};count=0;writes=0
  flags=self.source['material_flags'];water=self.source['water'];lava=self.source['lava'];filler=self.source['biomes'][0]['filler'] if self.source['biomes'][0]['flags']&8 else self.ids[1]
  def inside(pos):return all(bounds[i]<=pos[i]<=bounds[i+3] for i in range(3))
  def read(pos):return world.get(pos,self.base(context,pos))
  def replaceable(m):return m in (0,water,lava) or bool(flags[m]&28)
  def write(pos,m,tag=None):
   nonlocal writes
   world[pos]=m;tags.pop(pos,None);writes+=1
   if tag is not None:tags[pos]=tag
  for d in ds:
   definition=self.p['definitions'][d];ps=self.layout(d,context);seed=(SEEDS[context]+d)&MASK
   if chunk==(COORD[context],-COORD[context]):
    starts[definition['id']]={'id':definition['id'],'ChunkX':chunk[0],'ChunkZ':chunk[1],'references':0,'Children':[{'id':'retina:template','BB':p['box'],'O':-1,'GD':p['depth'],'template':self.p['templates'][p['element']['parts'][0]['template']]['id']} for p in ps]}
   relevant=[p for p in ps if intersects(p['box'],bounds)]
   if relevant:refs.setdefault(definition['id'],[]).append(i64(((-COORD[context]&0xffffffff)<<32)|(COORD[context]&0xffffffff)))
   terrain=definition['kind']!='jigsaw' or 'project_start_to_heightmap' in definition['config']
   support=definition['kind']!='jigsaw' or definition['config'].get('terrain_adaptation')!='none'
   for p in relevant:
    count+=1;origin=p['pos'];r=p['rotation'];matching=p['element'].get('terrain_matching',False)
    for part in p['element']['parts']:
     ti=part['template'];t=self.p['templates'][ti];blocks=self.rotated(ti,p['palette'],r);oracle=Oracle(part.get('processors'),self.source['materials'])
     if support and not matching and terrain:
      for _,local,original,_ in blocks:
       if local[1]!=0 or replaceable(original) or self.names[original] in ('minecraft:jigsaw','minecraft:structure_void','minecraft:structure_block'):continue
       x,y,z=add(origin,local)
       if not cx*16<=x<cx*16+16 or not cz*16<=z<cz*16+16:continue
       for y in range(min(y,320)-1,max(y-12,ground(context,x,z)-1,-64)-1,-1):
        pos=(x,y,z)
        if not replaceable(read(pos)):break
        write(pos,filler)
     for i,local,original,mat in blocks:
      pos=add(origin,local)
      if matching and terrain:pos=(pos[0],ground(context,pos[0],pos[2])-1+local[1],pos[2])
      within=inside(pos)
      if not within and not oracle.n:continue
      if self.names[original] in ('minecraft:structure_void','minecraft:structure_block') or part.get('ignore_air',False) and original==0:continue
      tag=copy.deepcopy(t['tags'].get(str(i)));rng=Random(seed^mixed(pos[0])^rol(mixed(pos[1]),21)^rol(mixed(pos[2]),42))
      keep,mat,tag=oracle.apply(oracle.p,mat,tag,read(pos) if within else self.ids[0],r,pos,origin,rng)
      if not keep or not within:continue
      if mat==0:tag=None
      if tag is not None:
       tag[1].update(x=[3,pos[0]],y=[3,pos[1]],z=[3,pos[2]],keepPacked=[1,1])
       if 'LootTable' in tag[1]:tag[1]['LootTableSeed']=[4,i64(mixed(seed^((pos[1]+64)*256+(pos[2]-cz*16)*16+pos[0]-cx*16)))]
      write(pos,mat,tag)
     entities+=entity_expected([t['entities']],[0,origin,r,bounds[:3],bounds[3:],seed>>32,seed&0xffffffff,ti])
  # Independent mutable final-world support/pair scans, after all starts.
  floors=self.source['plant_floor_masks'];halves=self.source['plant_halves'];repairs=0
  for mode in range(2):
   for pos,m in list(sorted(world.items(),key=lambda e:(e[0][0],e[0][2],e[0][1]))):
    m=read(pos);x,y,z=pos
    ok=not floors[m] or y>-64 and bool(flags[read((x,y-1,z))]&floors[m]) if mode==0 else not halves[m] or -64<=y+(1 if halves[m]>0 else -1)<320 and halves[read((x,y+(1 if halves[m]>0 else -1),z))]==-halves[m]
    if not ok:world[pos]=0;repairs+=1
  meta={'structures':{'starts':starts,'References':refs},'entities':[nbt_expected(t) for t in entities],'block_entities':[nbt_expected(v) for _,v in sorted(tags.items())]}
  return world,meta,count,writes,repairs

def decoded_blocks(root,source):
 states={json.dumps({'id':m} if isinstance(m,str) else m,sort_keys=True):i for i,m in enumerate(source['materials'])}
 values=[]
 for section in root['sections']:
  b=section['block_states'];ids=[states[json.dumps(p,sort_keys=True)] for p in b['palette']]
  values.extend([ids[0]]*4096 if len(ids)==1 else [ids[i] for i in unpack(b['data'],4096,max(4,(len(ids)-1).bit_length()))])
 return values

def verify(binary,path,folder):
 source=controls(json.loads(path.read_text()));oracle=ReplayOracle(source);groups=oracle.groups();source['replay_cases']=[oracle.ids,groups]
 wire=fixture_wire(source,True);inp=folder/(path.stem+'.rbp');inp.write_bytes(wire);output=folder/(path.stem+'.bin')
 start=time.monotonic();command(['nice','-n','10',binary,inp,output,'--threads','2','--gpu','off'],timeout=1200,cwd=ROOT);elapsed=time.monotonic()-start
 data=output.read_bytes();r=NbtReader(data);assert r.number('>I')==len(groups);count=pieces=writes=repairs=entities=bes=0;seen={}
 for gi,group in enumerate(groups):
  assert r.number('>I')==len(group[2])
  for chunk in map(tuple,group[2]):
   assert r.number('>I')==1,(path.name,gi,chunk,'invalid replay');np=r.number('>I');raw=r.take(r.number('>I'));region=read_region(raw);slot=(chunk[0]&31)+32*(chunk[1]&31);assert list(region)==[slot];assert region[slot]['timestamp']==1700000000;root=region[slot]['tag']
   world,meta,want_p,nw,nr=oracle.expected(group,chunk);assert np==want_p,(path.name,gi,chunk,np,want_p)
   for k,v in meta.items():assert root[k]==v,(path.name,gi,chunk,k,root[k],v)
   blocks=decoded_blocks(root,source);assert len(blocks)==98304
   for y in range(384):
    for col in range(256):
     pos=(chunk[0]*16+col%16,y-64,chunk[1]*16+col//16);want=world.get(pos,oracle.base(group[0],pos));got=blocks[y*256+col]
     assert got==want,(path.name,gi,chunk,pos,oracle.names[got],oracle.names[want])
   for i,name_ in enumerate(MAP_NAMES):
    got=unpack(root['Heightmaps'][name_],256,9)
    for col in range(256):
     want=next((y+1 for y in range(383,-1,-1) if source['heightmap_masks'][blocks[y*256+col]]&(1<<i)),0)
     assert got[col]==want,(path.name,gi,chunk,name_,col,got[col],want)
   key=(group[0],tuple(group[1]),chunk)
   if key in seen:assert raw==seen[key],(path.name,gi,'order/cache dependent output')
   seen[key]=raw
   if count<3:
    (folder/f'{path.stem}-{count}.mca').write_bytes(raw)
    loader=ROOT/'build/bend/structure-mca-loader'/f'{path.stem}-{count}';loader.mkdir(parents=True,exist_ok=True)
    (loader/f'r.{chunk[0]//32}.{chunk[1]//32}.mca').write_bytes(raw)
   count+=1;pieces+=np;writes+=nw;repairs+=nr;entities+=len(meta['entities']);bes+=len(meta['block_entities'])
 ts,ps,th,ph=[r.number('>I') for _ in range(4)];assert r.offset==len(data);assert th>0 and ph>0
 result=dict(profile=path.name,groups=len(groups),chunks=count,voxels=count*98304,heightmap_values=count*256*6,failure_checks=2,pieces=pieces,writes=writes,repairs=repairs,entities=entities,block_entities=bes,template_cache_entries=ts,program_cache_entries=ps,template_cache_hits=th,program_cache_hits=ph,worker_wall_seconds=elapsed,profile_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),input_sha256=hashlib.sha256(wire).hexdigest(),output_sha256=hashlib.sha256(data).hexdigest())
 print(json.dumps(result),flush=True);return result

def main():
 p=argparse.ArgumentParser();p.add_argument('--build',action='store_true');p.add_argument('--profiles',type=Path,default=ROOT/'build/bend/profiles');args=p.parse_args();folder=ROOT/'build/bend/structure-replay';folder.mkdir(parents=True,exist_ok=True);binary=folder/'fixture';generated=folder/'fixture.c'
 if args.build:
  command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/structure-replay.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
  command([v for v in metal_compiler_command(generated,binary) if v!='-DBEND_METAL=1'],timeout=600,cwd=ROOT)
 results=[verify(binary,path,folder) for path in sorted(args.profiles.glob('profile-*.json'))];assert len(results)==3
 report=dict(status='pass',results=results,scope='CPU ordered complete-start replay, compressed MCA records and independent full voxel/metadata/heightmap checks; no live world or GPU claims',executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),sources={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [*sorted((ROOT/'bend').glob('structure_*.bend')),ROOT/'bend/placement_merge.bend',ROOT/'bend/tests/structure-replay.bend',Path(__file__)]})
 (ROOT/'docs/benchmarks/bend-structure-replay.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':main()
