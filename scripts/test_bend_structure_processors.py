#!/usr/bin/env python3
"""Compare pure Bend structure rules with an independent ordered Python oracle.
Original registry exports stay intact. Additional test queries use the actual
registered processor payloads, materials and template metadata without rewriting
those payloads. Synthetic cases cover less-common supported processor forms.
"""
import argparse,copy,hashlib,json,math,os,random,struct,time
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,command,metal_compiler_command
from test_bend_registry_noise import fixture_wire
from test_bend_registry import Document
from test_bend_structures import Random,MASK,signed

M48=(1<<48)-1

def kind(v,k,default=''):return v.get(k,default).removeprefix('minecraft:')
def name(v):return v if isinstance(v,str) else v.get('id','')
def f32(x):return struct.unpack('>f',struct.pack('>f',x))[0]

class Oracle:
 def __init__(self,p,materials):
  self.names=list(map(name,materials));self.n=0;self.p=self.compile(p);self.counts=[0]*self.n
 def compile(self,p):
  if isinstance(p,list):return ['list',[self.compile(v) for v in p]]
  if not isinstance(p,dict):return ['noop']
  if 'processors' in p:return self.compile(p['processors'])
  if kind(p,'processor_type')=='capped':
   i=self.n;self.n+=1;limit=p.get('limit',1)
   if isinstance(limit,dict):limit=limit.get('value',1)
   return ['capped',i,limit,self.compile(p.get('delegate'))]
  return [kind(p,'processor_type'),p]
 def pred(self,p,m,rot,r):
  k=kind(p,'predicate_type','minecraft:always_true')
  yes=(k=='always_true' or k in ('block_match','random_block_match') and self.names[m]==p.get('block') or
   k in ('blockstate_match','random_blockstate_match') and p.get('block_state',{}).get('retina_rotations',[None]*4)[rot]==m or
   k=='tag_match' and m in p.get('retina_matching',[]))
  return yes and (not k.startswith('random_') or r.bits(24)/(1<<24)<p.get('probability',1))
 def position(self,p,pos,origin,r):
  k=kind(p,'predicate_type','minecraft:always_true')
  if k=='always_true':return True
  if k not in ('linear_pos','axis_aligned_linear_pos'):return False
  delta=[abs(a-b) for a,b in zip(pos,origin)]
  distance=delta[{'x':0,'z':2}.get(p.get('axis'),1)] if k=='axis_aligned_linear_pos' else sum(delta)
  # The Bend approximation uses F32 for distance interpolation; registered
  # random probabilities elsewhere retain exact original F64 comparisons.
  lo=f32(p.get('min_dist',0));hi=f32(p.get('max_dist',100))
  blend=min(1,max(0,f32(f32(f32(distance)-lo)/max(1,f32(hi-lo)))))
  prob=f32(f32(f32(p.get('min_chance',0))*f32(1-blend))+f32(f32(p.get('max_chance',0))*blend))
  return r.bits(24)/(1<<24)<prob
 def apply(self,proc,m,tag,world,rotation,pos,origin,r):
  k=proc[0]
  if k=='list':
   for p in proc[1]:
    keep,m,tag=self.apply(p,m,tag,world,rotation,pos,origin,r)
    if not keep:return keep,m,tag
  elif k=='capped':
   _,i,limit,delegate=proc
   if self.counts[i]<limit:
    before=(m,copy.deepcopy(tag));keep,m,tag=self.apply(delegate,m,tag,world,rotation,pos,origin,r)
    if not keep or before!=(m,tag):self.counts[i]+=1
    return keep,m,tag
  elif k=='block_rot':
   p=proc[1]
   if ('retina_rottable' not in p or m in p['retina_rottable']) and r.bits(24)/(1<<24)>p.get('integrity',1):return False,m,tag
  elif k=='protected_blocks':
   if world in proc[1].get('retina_protected',[]):return False,m,tag
  elif k=='rule':
   for rule in proc[1].get('rules',[]):
    if not (self.pred(rule.get('input_predicate',{}),m,rotation,r) and self.pred(rule.get('location_predicate',{}),world,rotation,r) and self.position(rule.get('position_predicate',{}),pos,origin,r)):continue
    out=rule.get('output_state',{});ids=out.get('retina_rotations',[None]*4);mat=ids[rotation]
    if mat is not None:
     if self.names[m]!=self.names[mat]:tag=copy.deepcopy(out.get('retina_entity'))
     m=mat
    modifier=rule.get('block_entity_modifier',{});ty=kind(modifier,'type')
    if ty=='clear':tag=None
    if ty=='append_static' and 'retina_nbt' in modifier:
     if tag is None:tag=[10,{}]
     tag[1].update(copy.deepcopy(modifier['retina_nbt'][1]))
    if isinstance(modifier.get('loot_table'),str):
     if tag is None:tag=[10,{}]
     tag[1]['LootTable']=[8,modifier['loot_table']]
    break
  return True,m,tag
 def row(self,row):
  m,world,rot,hi,lo,*_=row;r=Random(0);r.state=(hi<<32)|lo
  keep,m,tag=self.apply(self.p,m,copy.deepcopy(row[11]),world,rot,row[5:8],row[8:11],r)
  return int(keep),m,r.state,list(self.counts),tag

class Reader:
 def __init__(self,data,strings):self.data=data;self.at=0;self.strings=strings
 def u32(self):v=struct.unpack_from('>I',self.data,self.at)[0];self.at+=4;return v
 def u64(self):return self.u32()<<32|self.u32()
 def text(self,i):return 'LootTable' if i==0xfffffff0 else self.strings[i]
 def node(self):
  ty=self.u32()
  if ty==6:return [self.node() for _ in range(self.u32())]
  if ty==7:
   result={}
   for _ in range(self.u32()):key=self.text(self.u32());result[key]=self.node()
   return result
  a,b=self.u32(),self.u32()
  if ty==0:return None
  if ty in (1,2):return ty==2
  if ty==3:return (((a<<32)|b)+(1<<63))%(1<<64)-(1<<63)
  if ty==4:return struct.unpack('>d',struct.pack('>II',a,b))[0]
  if ty==5:return self.text(a)
  raise AssertionError(ty)

def unique_programs(p):
 result={}
 for pool in p['structures']['pools'].values():
  for e in pool['entries']:
   for part in e['parts']:
    value=part.get('processors',[]);result.setdefault(json.dumps(value,sort_keys=True),value)
 return [result[k] for k in sorted(result)]

def walk(value):
 yield value
 if isinstance(value,dict):
  for v in value.values():yield from walk(v)
 elif isinstance(value,list):
  for v in value:yield from walk(v)

def input_materials(program,materials):
 names=list(map(name,materials));ids={0,1,2}
 for v in walk(program):
  if not isinstance(v,dict):continue
  if 'block' in v:ids.update(i for i,n in enumerate(names) if n==v['block'])
  for k in ('retina_matching','retina_rottable','retina_protected','retina_rotations'):
   if k in v:ids.update(v[k])
 return sorted(i for i in ids if 0<=i<len(materials))

def cases(profile):
 programs=unique_programs(profile);out=[];rng=random.Random(517322)
 tags=[None]+[v for t in profile['structures']['templates'] for v in t['tags'].values()][:12]
 for program in programs:
  ids=input_materials(program,profile['materials'])
  for rotation in range(4):
   rows=[]
   for j in range(max(128,len(ids)*3)):
    mat=ids[j%len(ids)];world=ids[(j//len(ids))%len(ids)];state=rng.randrange(1<<48)
    xyz=[-30000000+j,-64+j%384,29999900-j]
    rows.append([mat,world,rotation,state>>32,state&0xffffffff,*xyz,-29999980,20,29999880,copy.deepcopy(tags[j%len(tags)])])
   out.append([program,rows])
 return out,len(programs)

def synthetic():
 materials=['minecraft:air','minecraft:stone',{'id':'minecraft:stone','properties':{'variant':'test'}},'minecraft:chest','minecraft:gold_block','minecraft:bedrock']
 output={'retina_rotations':[4,3,2,1],'retina_entity':[10,{'id':[8,'minecraft:chest'],'exact':[4,-9223372036854775808]}]}
 rule={'processor_type':'minecraft:rule','rules':[{'input_predicate':{'predicate_type':'minecraft:always_true'},'output_state':output}]}
 progs=[[],{'processors':[rule]}, {'processor_type':'unrecognized'},
  {'processor_type':'protected_blocks','retina_protected':[1,5]},
  {'processor_type':'capped','limit':0,'delegate':rule},
  {'processor_type':'capped','limit':{'value':3},'delegate':rule},
  {'processor_type':'capped','limit':5,'delegate':{'processor_type':'capped','limit':2,'delegate':rule}}]
 for p in [0,-0.0,-.2,math.nextafter(.5,0),.5,math.nextafter(.5,1),1,1.1,1e-300]:
  progs+=[{'processor_type':'block_rot','integrity':p},{'processor_type':'block_rot','integrity':p,'retina_rottable':[1,2]},
    {'processor_type':'rule','rules':[{'input_predicate':{'predicate_type':'random_block_match','block':'minecraft:stone','probability':p},'output_state':output}]}]
 for k in ['blockstate_match','random_blockstate_match','tag_match','unknown']:
  progs.append({'processor_type':'rule','rules':[{'input_predicate':{'predicate_type':k,'block_state':{'retina_rotations':[1,2,3,4]},'retina_matching':[1,2],'probability':.4},'location_predicate':{'predicate_type':'random_block_match','block':'minecraft:stone','probability':.7},'output_state':output},rule['rules'][0]]})
 for axis in ['x','y','z','other']:
  for ty in ['linear_pos','axis_aligned_linear_pos','never']:
   progs.append({'processor_type':'rule','rules':[{'position_predicate':{'predicate_type':ty,'axis':axis,'min_dist':2,'max_dist':22,'min_chance':.1,'max_chance':.9},'output_state':output}]})
 for modifier in [{'type':'append_static','retina_nbt':[10,{}]},{'type':'append_static','retina_nbt':[10,{'zero':[6,0.0]}]},{'type':'append_static','retina_nbt':[10,{'same':[3,17]}]},{'type':'clear'},{'type':'append_static','retina_nbt':[10,{'same':[3,17],'text':[8,'A\0é😀\ud800'],'bytes':[7,[-1,0,127]],'double':[6,-0.0]}]}, {'type':'append_static'}, {'type':'loot','loot_table':'test:loot'}]:
  progs.append({'processor_type':'capped','limit':4,'delegate':{'processor_type':'rule','rules':[{'block_entity_modifier':modifier}]}})
 progs.extend([[{'processor_type':'block_rot','integrity':-.1},rule],{'processor_type':'capped','limit':2,'delegate':{'processor_type':'rule','rules':[{'output_state':{'retina_rotations':[1]*4,'retina_entity':[10,{'unused':[1,1]}]}}]}}])
 # Force nextFloat exactly 0, 0.5, and the largest draw, plus varied states.
 states=[((v<<24)-11)*pow(0x5deece66d,-1,1<<48)&M48 for v in [0,1,1<<23,(1<<24)-1]]
 rng=random.Random(109);states += [rng.randrange(1<<48) for _ in range(20)]
 tags=[None,[10,{}],[10,{'same':[3,17],'zero':[6,-0.0],'nested':[10,{'large':[4,9223372036854775807]}]}]]
 result=[]
 for p in progs:
  for rotation in range(4):
   rows=[]
   for j in range(144):
    state=states[j%len(states)];rows.append([j%6,(j//6)%6,rotation,state>>32,state&0xffffffff,j%43-21,j%31-15,j%17-8,0,0,0,tags[j%3]])
   result.append([p,rows])
 return dict(materials=materials,processor_cases=result),len(progs)

def verify(binary,source,folder,label,original=None,program_count=0):
 wire=fixture_wire(source,True);path=folder/(label+'.rbp');path.write_bytes(wire);out=folder/(label+'.bin')
 start=time.monotonic();command(['nice','-n','10',binary,path,out,'--threads','2','--gpu','off'],timeout=600,cwd=ROOT);elapsed=time.monotonic()-start
 r=Reader(out.read_bytes(),Document(wire).strings);assert r.u32()==len(source['processor_cases']);rows=changed=removed=metadata=0
 for i,(program,inputs) in enumerate(source['processor_cases']):
  oracle=Oracle(program,source['materials']);n=r.u32();assert n==oracle.n;assert r.u32()==len(inputs)
  for j,row in enumerate(inputs):
   expected=oracle.row(row);actual=(r.u32(),r.u32(),r.u64(),[r.u32() for _ in range(n)],r.node())
   assert actual==expected,(label,i,j,program,row,actual,expected)
   rows+=1;changed+=actual[1]!=row[0];removed+=not actual[0];metadata+=actual[4]!=row[11]
 assert r.at==len(r.data)
 result=dict(profile=label,programs=program_count,cases=len(source['processor_cases']),placements=rows,changed_materials=changed,rejected=removed,metadata_changes=metadata,worker_wall_seconds=elapsed,wire_sha256=hashlib.sha256(wire).hexdigest(),output_sha256=hashlib.sha256(r.data).hexdigest())
 if original:result['profile_sha256']=hashlib.sha256(original.read_bytes()).hexdigest()
 print(json.dumps(result),flush=True);return result

def main():
 p=argparse.ArgumentParser();p.add_argument('--build',action='store_true');p.add_argument('--profiles',type=Path,default=ROOT/'build/bend/profiles');args=p.parse_args()
 folder=ROOT/'build/bend/structure-processors';folder.mkdir(parents=True,exist_ok=True);binary=folder/'fixture';generated=folder/'fixture.c'
 if args.build:
  command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/structure-processors.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
  command([v for v in metal_compiler_command(generated,binary) if v!='-DBEND_METAL=1'],timeout=600,cwd=ROOT)
 source,n=synthetic();results=[verify(binary,source,folder,'synthetic',program_count=n)]
 profiles=sorted(args.profiles.glob('profile-*.json'));assert len(profiles)>=3
 for path in profiles:
  source=json.loads(path.read_text());source['processor_cases'],n=cases(source);results.append(verify(binary,source,folder,path.stem,path,n))
 report=dict(results=results,executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sorted((ROOT/'bend').glob('structure_*.bend')),ROOT/'bend/tests/structure-processors.bend',Path(__file__)]})
 (ROOT/'docs/benchmarks/bend-structure-processors.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':main()
