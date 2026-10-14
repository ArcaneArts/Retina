#!/usr/bin/env python3
"""Replay original templates/processors into Bend's resident terrain overlay.
Compare every accepted block, metadata update, and quota against an independent
Python world with the same bounds. This is a component test, not a live client.
"""
import argparse,copy,hashlib,json,os,struct,time
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,command,metal_compiler_command
from test_bend_registry_noise import fixture_wire
from test_bend_registry import Document
from test_bend_structures import rotate,add,Random,MASK,signed
from test_bend_structure_processors import Oracle,Reader,name
from test_bend_structure_nbt import expected as expected_nbt
from test_bend_binary import NbtReader

def mixed(n):
 n&=MASK;n=((n^(n>>30))*0xbf58476d1ce4e5b9)&MASK;n=((n^(n>>27))*0x94d049bb133111eb)&MASK;return n^(n>>31)
def rol(v,n):return ((v<<n)|(v>>(64-n)))&MASK
def i64(v):return (v+(1<<63))%(1<<64)-(1<<63)
class BlockReader(Reader):
 def text(self,i):
  return ['LootTable','x','y','z','keepPacked','LootTableSeed'][i-0xfffffff0] if 0xfffffff0<=i<=0xfffffff5 else self.strings[i]
 def point(self):return tuple(signed(self.u32()) for _ in range(3))

def expected(profile,row):
 ti,program,palette,rotation,matching,ignore_air,x,y,z,cx,cz,hi,lo=row;t=profile['structures']['templates'][ti];names=list(map(name,profile['materials']));origin=(x,y,z);start=(hi<<32)|lo
 oracle=Oracle(program,profile['materials']);world={};writes=[];tags=[]
 def inside(pos):return cx*16<=pos[0]<cx*16+16 and cz*16<=pos[2]<cz*16+16 and -64<=pos[1]<320
 def read(pos):
  if pos in world:return world[pos]
  index=(pos[2]-cz*16)*16+pos[0]-cx*16
  return 1 if pos[1]<64 else 2 if pos[1]<68 and index%7==0 else 0
 for i in range(len(t['blocks'])//4):
  raw=t['blocks'][i*4:i*4+4];local=rotate(raw[:3],rotation);original=t['palettes'][palette%len(t['palettes'])][raw[3]][rotation]
  mat=next((j['final'][rotation] for j in t['joints'] if j['pos']==raw[:3]),0) if names[original]=='minecraft:jigsaw' else original
  pos=add(origin,local)
  if matching:
   tx=max(0,min(63,pos[0]-(x-32)));tz=max(0,min(63,pos[2]-(z-32)));pos=(pos[0],63+(tx+tz)%5+local[1],pos[2])
  within=inside(pos)
  if not within and not oracle.n:continue
  if names[original] in ('minecraft:structure_void','minecraft:structure_block') or ignore_air and original==0:continue
  tag=copy.deepcopy(t['tags'].get(str(i)));r=Random(start^mixed(pos[0])^rol(mixed(pos[1]),21)^rol(mixed(pos[2]),42))
  keep,mat,tag=oracle.apply(oracle.p,mat,tag,read(pos) if within else 1,rotation,pos,origin,r)
  if not keep or not within:continue
  world[pos]=mat;writes.append((pos,mat))
  if mat==0:tag=None
  if tag is not None:
   tag[1].update(x=[3,pos[0]],y=[3,pos[1]],z=[3,pos[2]],keepPacked=[1,1])
   if 'LootTable' in tag[1]:
    index=(pos[1]+64)*256+(pos[2]-cz*16)*16+pos[0]-cx*16
    tag[1]['LootTableSeed']=[4,i64(mixed(start^index))]
  tags.append((pos,tag))
 return oracle.counts,writes,tags

def registered_cases(profile):
 chosen={}
 for pool in profile['structures']['pools'].values():
  for e in pool['entries']:
   for part in e['parts']:
    p=part.get('processors',[]);key=json.dumps(p,sort_keys=True);chosen.setdefault(key,(part,e.get('terrain_matching',False)))
 # Include temple traps, tagged chests, paths, and both rigid/terrain projection.
 for d in profile['structures']['definitions']:
  if d['kind']!='jigsaw':chosen['standalone:'+d['id']]=({'template':d['template'],'processors':[],'ignore_air':False},False)
 rows=[]
 for i,(part,matching) in enumerate(chosen.values()):
  for rotation in range(4):
   for context in range(2):
    cx=[-2,1874000][context];cz=[3,-1874000][context];seed=[123456789,(1<<63)|0xffffffff][context]
    rows.append([part['template'],part.get('processors',[]),context+1,rotation,matching if context==0 else not matching,part.get('ignore_air',False),cx*16+8,62,cz*16+8,cx,cz,seed>>32,seed&0xffffffff])
 return rows

def synthetic():
 mats=['minecraft:air','minecraft:stone','minecraft:water','minecraft:jigsaw','minecraft:structure_void','minecraft:structure_block','minecraft:chest','minecraft:gold_block',{'id':'minecraft:chest','properties':{'facing':'east'}}]
 palette=[[i]*4 for i in range(len(mats))];palette[6]=[6,8,6,8]
 raw=[[x,y,z,(x+y+z)%len(mats)] for y in range(4) for z in range(6) for x in range(20)]
 # Duplicate a chest position then erase it: the replay journal must retain removal.
 raw += [[1,3,1,6],[1,3,1,0]]
 t=dict(id='test:template',size=[20,4,6],ground=1,palettes=[palette,list(reversed(palette))],blocks=sum(raw,[]),tags={'0':[10,{'id':[8,'minecraft:chest'],'LootTable':[8,'test:loot']}],str(len(raw)-2):[10,{'id':[8,'minecraft:chest'],'LootTable':[8,'test:loot']}]},entities=[],joints=[dict(pos=b[:3],final=[6,7,8,1]) for b in raw if b[3]==3])
 output={'retina_rotations':[6,8,7,1],'retina_entity':[10,{'id':[8,'minecraft:chest'],'LootTable':[8,'test:other']}]}
 rule={'processor_type':'rule','rules':[{'input_predicate':{'predicate_type':'random_block_match','block':'minecraft:stone','probability':.7},'output_state':output}]}
 processors=[[],rule,{'processor_type':'capped','limit':7,'delegate':rule},[{'processor_type':'protected_blocks','retina_protected':[2]},rule],{'processor_type':'block_rot','integrity':.7}]
 rows=[]
 for i,p in enumerate(processors):
  for rotation in range(4):
   for context in range(4):
    cx=[-1,0,1874000,-1874000][context];cz=-cx;rows.append([0,p,context,rotation,bool(context%2),bool(context//2),cx*16+8,62,cz*16+8,cx,cz,0xffffffff,0x80000000])
 return dict(materials=mats,structures={'templates':[t]},block_cases=rows)

def verify(binary,source,path,folder,label=None):
 label=label or (path.stem if path else 'synthetic');wire=fixture_wire(source,True);inp=folder/(label+'.rbp');inp.write_bytes(wire);out=folder/(label+'.bin')
 start=time.monotonic();command(['nice','-n','10',binary,inp,out,'--threads','2','--gpu','off'],timeout=600,cwd=ROOT);elapsed=time.monotonic()-start
 r=BlockReader(out.read_bytes(),Document(wire).strings);assert r.u32()==len(source['block_cases']);total=metadata=0
 for i,row in enumerate(source['block_cases']):
  assert r.u32()==1,(label,i,'invalid template');n=r.u32();counts=[r.u32() for _ in range(n)]
  writes=[(r.point(),r.u32()) for _ in range(r.u32())];tags=[(r.point(),r.node()) for _ in range(r.u32())]
  wanted=expected(source,row)
  assert (counts,writes,tags)==wanted,(label,i,row,counts,wanted[0],len(writes),len(wanted[1]),next(((a,b) for a,b in zip(writes,wanted[1]) if a!=b),None),next(((a,b) for a,b in zip(tags,wanted[2]) if a!=b),None))
  nbt=NbtReader(r.data);nbt.offset=r.at
  for _,tag in tags:
   present=nbt.number('>B');assert present==int(tag is not None)
   if present:
    assert nbt.number('>B')==10;assert nbt.text()=='';assert nbt.tag(10)==expected_nbt(tag)
  r.at=nbt.offset
  total+=len(writes);metadata+=sum(tag is not None for _,tag in tags)
 assert r.at==len(r.data)
 result=dict(profile=label,cases=len(source['block_cases']),block_writes=total,block_entity_writes=metadata,worker_wall_seconds=elapsed,wire_sha256=hashlib.sha256(wire).hexdigest(),output_sha256=hashlib.sha256(r.data).hexdigest())
 if path:result['profile_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
 print(json.dumps(result),flush=True);return result

def main():
 p=argparse.ArgumentParser();p.add_argument('--build',action='store_true');p.add_argument('--profiles',type=Path,default=ROOT/'build/bend/profiles');args=p.parse_args()
 folder=ROOT/'build/bend/structure-blocks';folder.mkdir(parents=True,exist_ok=True);binary=folder/'fixture';generated=folder/'fixture.c'
 if args.build:
  command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/structure-blocks.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
  command([v for v in metal_compiler_command(generated,binary) if v!='-DBEND_METAL=1'],timeout=600,cwd=ROOT)
 results=[verify(binary,synthetic(),None,folder)]
 blank=synthetic()
 for tag in blank['structures']['templates'][0]['tags'].values():tag[1].pop('LootTable',None)
 blank['block_cases']=[blank['block_cases'][0]]
 blank['block_cases'][0][1]={'processor_type':'rule','rules':[{'block_entity_modifier':{'type':'append_loot','loot_table':'test:generated'}}]}
 results.append(verify(binary,blank,None,folder,'generated-metadata-keys'))
 for path in sorted(args.profiles.glob('profile-*.json')):
  source=json.loads(path.read_text());source['block_cases']=registered_cases(source);results.append(verify(binary,source,path,folder))
 assert len(results)>=5
 bad=[]
 base=synthetic();base['block_cases']=[base['block_cases'][0]];base['block_cases'][0][2]=0
 for field,value in [('palettes',[]),('size',[0,4,6]),('size',[513,4,6]),('blocks',[0,0,0]),('tags',{'not-an-index':[10,{}]}),('tags',{'99999999999999999999':[10,{}]}),('tags',{'99999':[10,{}]})]:
  v=copy.deepcopy(base);v['structures']['templates'][0][field]=value;bad.append(v)
 for index,value in [(0,-1),(1,4),(2,6),(3,9999)]:
  v=copy.deepcopy(base);v['structures']['templates'][0]['blocks'][index]=value;bad.append(v)
 v=copy.deepcopy(base);v['structures']['templates'][0]['palettes'][0][0][0]=9999;bad.append(v)
 v=copy.deepcopy(base);v['block_cases'][0][3]=4;bad.append(v)
 v=copy.deepcopy(base);v['structures']['templates'][0]['joints'][0]['final'][0]=9999;bad.append(v)
 for i,source in enumerate(bad):
  inp=folder/f'bad-{i}.rbp';inp.write_bytes(fixture_wire(source,True));out=folder/f'bad-{i}.bin'
  command(['nice','-n','10',binary,inp,out,'--threads','2','--gpu','off'],timeout=30,cwd=ROOT)
  r=BlockReader(out.read_bytes(),Document(inp.read_bytes()).strings);assert r.u32()==1;assert r.u32()==0,(i,'invalid template accepted');n=r.u32();assert [r.u32() for _ in range(n)]==[0]*n;assert r.u32()==0;assert r.u32()==0;assert r.at==len(r.data)
 print(json.dumps({'invalid_templates':len(bad),'status':'pass'}),flush=True)
 report=dict(results=results,invalid_templates=len(bad),executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sorted((ROOT/'bend').glob('structure_*.bend')),ROOT/'bend/tests/structure-blocks.bend',Path(__file__)]})
 (ROOT/'docs/benchmarks/bend-structure-blocks.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':main()
