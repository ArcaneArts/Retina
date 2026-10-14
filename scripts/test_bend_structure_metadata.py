#!/usr/bin/env python3
"""Read structure-bearing chunk NBT produced by Bend's actual chunk serializer."""
import argparse,hashlib,json,os,random,struct,time
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,command,metal_compiler_command
from test_bend_registry_noise import fixture_wire
from test_bend_binary import NbtReader
from test_bend_structure_nbt import expected as expected_nbt
from test_bend_structures import Planner,intersects

def cases(source):
 p=source['structures'];mapping={id(e):e['parts'][0]['template'] for pool in p['pools'].values() for e in pool['entries'] if e['parts']};planner=Planner(p,mapping);out=[]
 for context,c in enumerate([0,-15,1874000]):
  for d in range(len(p['definitions'])):
   _,pieces=planner.run(d,context)
   parts=[[row[0] if p['definitions'][d]['kind']=='jigsaw' else p['definitions'][d]['template'],row[5:11],row[13]] for row in pieces]
   starts=[[d,c,-c,parts]];chunks={(c,-c),(c+100,-c+100)}
   for _,bounds,_ in parts:
    chunks.add((bounds[0]//16,bounds[2]//16));chunks.add((bounds[3]//16,bounds[5]//16))
   # Multiple same-type references must retain both signed packed chunk keys;
   # only the actual owner receives its start record.
   starts.append([d,c-1,-c+1,parts])
   for cx,cz in sorted(chunks):
    pos=[cx*16,4,cz*16];tag=[10,{'id':[8,'test:block'],'x':[3,pos[0]],'y':[3,4],'z':[3,pos[2]],'LootTableSeed':[4,-(1<<63)+1]}]
    # Replacement, deletion, reinsertion, negative sort order and same x/z.
    updates=[[pos,tag],[[pos[0],3,pos[2]+1],tag],[pos,None],[[pos[0],3,pos[2]],tag],[pos,tag],[[pos[0],3,pos[2]+1],None]]
    entities=[[10,{'id':[8,'test:entity'],'Pos':[9,[6,[[6,pos[0]+.1],[6,5.5],[6,pos[2]-.1]]]],'custom':[8,'A\0é😀']}]]
    out.append([cx,cz,starts,updates,entities])
 return out

def expected(source,row):
 cx,cz,starts,updates,entities=row;p=source['structures'];s={};refs={};bounds=[cx*16,-64,cz*16,cx*16+15,319,cz*16+15]
 for d,x,z,pieces in starts:
  id=p['definitions'][d]['id']
  if (x,z)==(cx,cz):s[id]={'id':id,'ChunkX':x,'ChunkZ':z,'references':0,'Children':[{'id':'retina:template','BB':b,'O':-1,'GD':depth,'template':p['templates'][ti]['id']} for ti,b,depth in pieces]}
  if any(intersects(bounds,b) for _,b,_ in pieces):
   v=((z&0xffffffff)<<32)|(x&0xffffffff);v=(v+(1<<63))%(1<<64)-(1<<63);refs.setdefault(id,[]).append(v)
 blocks={}
 for pos,tag in updates:
  if tag is None:blocks.pop(tuple(pos),None)
  else:blocks[tuple(pos)]=expected_nbt(tag)
 return {'structures':{'starts':s,'References':refs},'entities':[expected_nbt(t) for t in entities],'block_entities':[tag for _,tag in sorted(blocks.items())]}

def verify(binary,path,folder):
 source=json.loads(path.read_text());rows=cases(source);source['metadata_cases']=rows;wire=fixture_wire(source,True);inp=folder/(path.stem+'.rbp');inp.write_bytes(wire);out=folder/(path.stem+'.chunks')
 start=time.monotonic();command(['nice','-n','10',binary,inp,out,'--threads','2','--gpu','off'],timeout=600,cwd=ROOT);elapsed=time.monotonic()-start
 data=out.read_bytes();r=NbtReader(data);assert r.number('>I')==len(rows);starts=refs=0
 for i,row in enumerate(rows):
  payload=r.take(r.number('>I'));c=NbtReader(payload);assert c.number('>B')==10;assert c.text()=='';root=c.tag(10);assert c.offset==len(payload)
  for key,want in expected(source,row).items():assert root[key]==want,(path.name,i,key,root[key],want)
  assert (root['xPos'],root['zPos'])==tuple(row[:2]);assert root['DataVersion']==5023
  assert root['block_ticks']==root['fluid_ticks']==root['PostProcessing']==[]
  assert root['isLightOn']==0;assert len(root['sections'])==1
  assert root['sections'][0]['block_states']['palette']==[{'id':'minecraft:stone'}]
  assert len(root['Heightmaps'])>=4
  starts+=len(root['structures']['starts']);refs+=sum(map(len,root['structures']['References'].values()))
  if i<4:(folder/f'{path.stem}-{i}.nbt').write_bytes(payload)
 assert r.offset==len(data)
 result=dict(profile=path.name,cases=len(rows),starts=starts,references=refs,worker_wall_seconds=elapsed,profile_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),input_sha256=hashlib.sha256(wire).hexdigest(),output_sha256=hashlib.sha256(data).hexdigest())
 print(json.dumps(result),flush=True);return result

def main():
 p=argparse.ArgumentParser();p.add_argument('--build',action='store_true');p.add_argument('--profiles',type=Path,default=ROOT/'build/bend/profiles');args=p.parse_args()
 folder=ROOT/'build/bend/structure-metadata';folder.mkdir(parents=True,exist_ok=True);binary=folder/'fixture';generated=folder/'fixture.c'
 if args.build:
  command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/structure-metadata.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
  command([v for v in metal_compiler_command(generated,binary) if v!='-DBEND_METAL=1'],timeout=600,cwd=ROOT)
 results=[verify(binary,path,folder) for path in sorted(args.profiles.glob('profile-*.json'))];assert len(results)==3
 report=dict(results=results,status='pass',executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'bend/structure_metadata.bend',ROOT/'bend/structure_nbt.bend',ROOT/'bend/chunk.bend',ROOT/'bend/generated_chunk.bend',ROOT/'bend/tests/structure-metadata.bend',Path(__file__)]})
 (ROOT/'docs/benchmarks/bend-structure-metadata.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':main()
