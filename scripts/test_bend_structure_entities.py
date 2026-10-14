#!/usr/bin/env python3
"""Independent typed-NBT checks for structure entity placement and chunk ownership."""
import argparse,copy,hashlib,json,math,os,time
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,command,metal_compiler_command
from test_bend_registry_noise import fixture_wire
from test_bend_binary import NbtReader
from test_bend_structure_nbt import expected as expected_nbt
from test_bend_structure_blocks import mixed,rol
from test_bend_structures import rotate,add,signed

def ls(kind,items):return [9,[kind,[[kind,v] for v in items]]]
def field(tag,name):return tag[1].get(name) if isinstance(tag,list) and len(tag)==2 and tag[0]==10 else None
def values(tag):return tag[1][1] if isinstance(tag,list) and len(tag)==2 and tag[0]==9 else []
def number(tag):return float(tag[1]) if isinstance(tag,list) and len(tag)==2 and isinstance(tag[1],(int,float)) else 0.
def int32(v):return min(2147483647,max(-2147483648,int(v)))
def posed(wrapper,origin,r):
 tag=field(wrapper,'nbt');v=values(field(wrapper,'pos'))
 if tag is None or tag[0]!=10 or len(v)!=3:return None
 x,y,z=map(number,v);q=[(x,y,z),(1-z,y,x),(1-x,y,1-z),(z,y,1-x)][r];pos=tuple(a+b for a,b in zip(q,origin))
 v=values(field(wrapper,'blockPos'))
 anchor=add(origin,rotate(tuple(int32(number(x)) for x in v),r)) if len(v)==3 else tuple(int32(math.floor(x)) for x in pos)
 return tag,pos,anchor

def expected(templates,row):
 ti,origin,r,low,high,sh,sl,template=row;seed=(sh<<32)|sl;out=[]
 for i,wrapper in enumerate(templates[ti]):
  info=posed(wrapper,origin,r)
  if info is None:continue
  original,pos,anchor=info
  if not all(a<=v<=b for a,v,b in zip(low,anchor,high)):continue
  tag=copy.deepcopy(original);fields=tag[1]
  if 'block_pos' in fields:
   fields['block_pos']=[11,list(anchor)];id=fields.get('id',[8,''])[1]
   key='Facing' if id in ('minecraft:item_frame','minecraft:glow_item_frame') else 'facing' if id=='minecraft:painting' else ''
   if key in fields:
    v=int(number(fields[key]))
    if key=='facing' and 0<=v<4:fields[key][1]=(v+r)%4
    elif key=='Facing' and 2<=v<6:
     for _ in range(r):v=[0,1,5,4,2,3][v]
     fields[key][1]=v
  fields['Pos']=ls(6,pos)
  a=mixed(seed^template^rol(i,17)^(origin[0]&((1<<64)-1))^rol(origin[1]&((1<<64)-1),37)^rol(r,11));b=mixed(a^(origin[2]&((1<<64)-1)))
  fields['UUID']=[11,[signed(a&0xffffffff),signed(a>>32),signed(b&0xffffffff),signed(b>>32)]]
  orientation=values(fields.get('Rotation'))
  if len(orientation)==2:fields['Rotation']=[9,[5,[[5,number(orientation[0])+r*90.],orientation[1]]]]
  out.append(tag)
 return out

def rows(templates,ids):
 out=[]
 for ti,entities in enumerate(templates):
  for r in range(4):
   for ci,origin in enumerate([(0,64,0),(29999984,200,-29999984),(-29999984,-40,29999984)]):
    chunks=set()
    for entity in entities:
     info=posed(entity,origin,r)
     if info is not None:chunks.add((info[2][0]//16,info[2][2]//16))
    if not chunks:chunks.add((0,0))
    # Every owner chunk, plus a known disjoint chunk. Fractional positions may
    # cross these bounds; the normalized blockPos owns the entity.
    chunks.add((origin[0]//16+100,origin[2]//16+100))
    for cx,cz in sorted(chunks):out.append([ti,origin,r,[cx*16,-64,cz*16],[cx*16+15,319,cz*16+15],0xffffffff,0x80000001,ids[ti]])
    # Full box provides the independent no-crop control for chunk partitioning.
    out.append([ti,origin,r,[origin[0]-600,-64,origin[2]-600],[origin[0]+600,319,origin[2]+600],0xffffffff,0x80000001,ids[ti]])
 return out

def synthetic():
 entities=[]
 for id,key,count in [('minecraft:item_frame','Facing',6),('minecraft:glow_item_frame','Facing',6),('minecraft:painting','facing',4)]:
  for direction in range(count):
   entities.append([10,{'nbt':[10,{'id':[8,id],'block_pos':[11,[1234,99,-5678]],key:[1,direction],'Rotation':ls(5,[-90.125,11.5]),'custom':[10,{'text':[8,'A\0é😀\ud800']}]}],'pos':ls(6,[16.1,1.5,.5]),'blockPos':ls(3,[15,1,0])}])
 # Floor fallback for negative fractions, exact high-coordinate translation,
 # default generated keys, and normalized attachment vs original capture data.
 for x in (-.1,-16.0000000001,15.999999999,16.1,1e-200):
  entities.append([10,{'nbt':[10,{'id':[8,'test:entity']}],'pos':ls(6,[x,1.01,-.25])}])
 entities += [[10,{}],[10,{'nbt':[10,{}],'pos':ls(6,[1,2])}],[10,{'nbt':[3,1],'pos':ls(6,[1,2,3])}]]
 return [entities]

def verify(binary,templates,ids,label,folder,path=None):
 cases=rows(templates,ids);source={'entity_cases':[templates,cases]};wire=fixture_wire(source,True);inp=folder/(label+'.rbp');inp.write_bytes(wire);out=folder/(label+'.nbt')
 start=time.monotonic();command(['nice','-n','10',binary,inp,out,'--threads','2','--gpu','off'],timeout=600,cwd=ROOT);elapsed=time.monotonic()-start
 data=out.read_bytes();reader=NbtReader(data);assert reader.number('>I')==len(cases);total=0
 for i,row in enumerate(cases):
  want=expected(templates,row);n=reader.number('>I');assert n==len(want),(label,i,n,len(want),row)
  for entity in want:
   assert reader.number('>B')==10;assert reader.text()=='';got=reader.tag(10);assert got==expected_nbt(entity),(label,i,row,got,expected_nbt(entity))
  total+=n
 assert reader.offset==len(data)
 report=dict(profile=label,templates=len(templates),cases=len(cases),entities=total,worker_wall_seconds=elapsed,input_sha256=hashlib.sha256(wire).hexdigest(),output_sha256=hashlib.sha256(data).hexdigest())
 if path:report['profile_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
 print(json.dumps(report),flush=True);return report

def main():
 p=argparse.ArgumentParser();p.add_argument('--build',action='store_true');p.add_argument('--profiles',type=Path,default=ROOT/'build/bend/profiles');args=p.parse_args()
 folder=ROOT/'build/bend/structure-entities';folder.mkdir(parents=True,exist_ok=True);binary=folder/'fixture';generated=folder/'fixture.c'
 if args.build:
  command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/structure-entities.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
  command([v for v in metal_compiler_command(generated,binary) if v!='-DBEND_METAL=1'],timeout=600,cwd=ROOT)
 results=[verify(binary,synthetic(),[0],'synthetic',folder)]
 for path in sorted(args.profiles.glob('profile-*.json')):
  profile=json.loads(path.read_text());chosen=[(i,t['entities']) for i,t in enumerate(profile['structures']['templates']) if t['entities']]
  results.append(verify(binary,[e for _,e in chosen],[i for i,_ in chosen],path.stem,folder,path))
 assert len(results)==4
 report=dict(results=results,status='pass',executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'bend/float64.bend',ROOT/'bend/structure_entities.bend',ROOT/'bend/structure_nbt.bend',ROOT/'bend/tests/structure-entities.bend',Path(__file__)]})
 (ROOT/'docs/benchmarks/bend-structure-entities.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps({'status':'pass','cases':sum(r['cases'] for r in results),'entities':sum(r['entities'] for r in results)}),flush=True)
if __name__=='__main__':main()
