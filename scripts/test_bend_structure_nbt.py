#!/usr/bin/env python3
"""Independently decode loaded template and processor NBT emitted in pure Bend."""
import argparse,hashlib,json,os,random,struct,subprocess,time
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,command,metal_compiler_command
from test_bend_registry_noise import fixture_wire
from test_bend_binary import NbtReader
from test_bend_structure_processors import synthetic,Oracle

def expected(tag):
 k,v=tag
 if k in (1,2,3,4):
  bits={1:8,2:16,3:32,4:64}[k];return (v+(1<<(bits-1)))%(1<<bits)-(1<<(bits-1))
 if k in (5,6):return struct.pack('>f' if k==5 else '>d',v)
 if k==7:return bytes(n&255 for n in v)
 if k==8:return v
 if k==9:return [expected(t) if v[0]!=10 or t[0]==10 else {'':expected(t)} for t in v[1]]
 if k==10:return {s:expected(t) for s,t in v.items()}
 if k in (11,12):return [expected([3 if k==11 else 4,n]) for n in v]
 raise AssertionError(k)

def controls():
 rng=random.Random(813)
 nums=[0,1,-1,-(1<<63),(1<<63)-1,1<<53,(1<<53)+1,(1<<53)+3]+[rng.randrange(-(1<<63),1<<63) for _ in range(512)]
 result=[[k,n] for n in nums for k in (4,5,6)]
 result.extend([[k,x] for x in [-0.0,1e-300,1e300,0.5,-129.25] for k in (6,)])
 result += [[10,{'byte':[1,-1],'short':[2,-32768],'int':[3,-2147483648],'long':[4,-9223372036854775808],'text':[8,'A\0é😀\ud800'],'bytes':[7,list(range(-128,128))],'ints':[11,nums[:8]],'longs':[12,nums[:8]],'list':[9,[10,[[10,{'v':[3,5]}],[8,'wrapped']]]],'empty':[9,[0,[]]]}], [8,'a'*65535], [8,'😀'*10922+'abc']]
 source,_=synthetic();seen=set()
 for program,rows in source['processor_cases']:
  oracle=Oracle(program,source['materials'])
  for row in rows:
   tag=oracle.row(row)[-1];key=json.dumps(tag,sort_keys=True)
   if tag is not None and key not in seen:seen.add(key);result.append(tag)
 return result

def verify(binary,source,path,folder):
 label=path.stem if path else 'synthetic';wire=fixture_wire(source,True);inp=folder/(label+'.rbp');inp.write_bytes(wire);out=folder/(label+'.nbt')
 start=time.monotonic();command(['nice','-n','10',binary,inp,out,'--threads','2','--gpu','off'],timeout=600,cwd=ROOT);duration=time.monotonic()-start
 data=out.read_bytes();r=NbtReader(data);assert r.number('>I')==len(source['nbt_cases'])
 for i,tag in enumerate(source['nbt_cases']):
  assert r.number('>B')==tag[0];assert r.text()=='';value=r.tag(tag[0]);assert value==expected(tag),(label,i,tag,value,expected(tag))
 assert r.offset==len(data)
 result=dict(profile=label,tags=len(source['nbt_cases']),bytes=len(data),worker_wall_seconds=duration,wire_sha256=hashlib.sha256(wire).hexdigest(),output_sha256=hashlib.sha256(data).hexdigest())
 if path:result['profile_sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
 print(json.dumps(result),flush=True);return result

def main():
 p=argparse.ArgumentParser();p.add_argument('--build',action='store_true');p.add_argument('--profiles',type=Path,default=ROOT/'build/bend/profiles');args=p.parse_args()
 folder=ROOT/'build/bend/structure-nbt';folder.mkdir(parents=True,exist_ok=True);binary=folder/'fixture';generated=folder/'fixture.c'
 if args.build:
  command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/structure-nbt.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
  command([v for v in metal_compiler_command(generated,binary) if v!='-DBEND_METAL=1'],timeout=600,cwd=ROOT)
 results=[verify(binary,{'nbt_cases':controls()},None,folder)]
 for path in sorted(args.profiles.glob('profile-*.json')):
  source=json.loads(path.read_text());tags=[]
  for t in source['structures']['templates']:tags.extend(t['tags'].values());tags.extend(t['entities'])
  source['nbt_cases']=tags;results.append(verify(binary,source,path,folder))
 assert len(results)>=4
 bad=[[13,0],[1,'x'],[4,.5],[8,3],[8,'a'*65536],[10,[]],[7,3],[11,[.5]],[12,[None]],[9,[1,[[3,2]]]],[9,[13,[]]],[9,[1,0]],[10,{'v':[0,2]}],[10,{'a'*65536:[1,0]}],[3]]
 for i,tag in enumerate(bad):
  path=folder/f'bad-{i}.rbp';path.write_bytes(fixture_wire({'nbt_cases':[tag]},True))
  result=subprocess.run(['nice','-n','10',str(binary),str(path),str(folder/f'bad-{i}.nbt'),'--threads','2','--gpu','off'],capture_output=True,timeout=30)
  assert result.returncode!=0,(i,tag,result.stdout,result.stderr)
 report=dict(results=results,invalid_cases=len(bad),executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'bend/structure_nbt.bend',ROOT/'bend/structure_values.bend',ROOT/'bend/tests/structure-nbt.bend',Path(__file__)]})
 (ROOT/'docs/benchmarks/bend-structure-nbt.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps({'invalid_cases':len(bad),'status':'pass'}),flush=True)
if __name__=='__main__':main()
