#!/usr/bin/env python3
"""Compare Bend's entity-coordinate binary64 math against host IEEE arithmetic."""
import argparse,hashlib,json,math,os,random,struct
from test_bend_compression import ROOT,DEFAULT_BEND,command,metal_compiler_command
from test_bend_registry_noise import fixture_wire

def bits(v): return struct.unpack('>Q',struct.pack('>d',v))[0]
def number(v): return struct.unpack('>d',struct.pack('>Q',v))[0]
def i32(v,floor):
 if math.isnan(v):return 0
 if v>=2147483647:return 2147483647
 if v<=-2147483648:return -2147483648
 return math.floor(v) if floor else int(v)
def cases():
 rng=random.Random(40719)
 edge=[0,1,2,(1<<52)-1,1<<52,(1<<52)+1,0x3fefffffffffffff,0x3ff0000000000000,0x3ff0000000000001,0x7fefffffffffffff,0x7ff0000000000000,0x7ff8000000000000,0x7ff0000000000001]
 edge += [bits(v) for v in (2147483647.,2147483648.,30000000.1,16.1,1e-200,1e200)]
 edge += [v^(1<<63) for v in edge]
 out=[(a,b) for a in edge for b in edge]
 out += [(rng.getrandbits(64),rng.getrandbits(64)) for _ in range(24000)]
 for _ in range(10000):
  a=rng.getrandbits(64)&0x7fefffffffffffff
  out.append((a,((a+rng.randrange(-32,33))&0x7fffffffffffffff)^(1<<63)))
 for _ in range(10000):
  out.append((bits(rng.uniform(-512,512)),bits(float(rng.randint(-30000000,30000000)))))
 return out

def main():
 p=argparse.ArgumentParser();p.add_argument('--build',action='store_true');args=p.parse_args()
 folder=ROOT/'build/bend/float64';folder.mkdir(parents=True,exist_ok=True);binary=folder/'fixture';generated=folder/'fixture.c'
 if args.build:
  command(['nice','-n','10',DEFAULT_BEND,ROOT/'bend/tests/float64.bend','-o',generated],timeout=600,cwd=ROOT,env=dict(os.environ,BEND_NO_TELEMETRY='1'))
  command([v for v in metal_compiler_command(generated,binary) if v!='-DBEND_METAL=1'],timeout=600,cwd=ROOT)
 rows=cases();wire=fixture_wire({'cases':[[[a>>32,a&0xffffffff],[b>>32,b&0xffffffff]] for a,b in rows]},True)
 inp=folder/'cases.rbp';inp.write_bytes(wire);out=folder/'result.bin'
 command(['nice','-n','10',binary,inp,out,'--threads','2','--gpu','off'],timeout=600,cwd=ROOT)
 data=out.read_bytes();assert struct.unpack_from('>I',data)[0]==len(rows);assert len(data)==4+32*len(rows)
 for i,(a,b) in enumerate(rows):
  x,y=number(a),number(b);got=struct.unpack_from('>QQiiii',data,4+i*32)
  for op,actual,want in [('add',got[0],x+y),('sub',got[1],x-y)]:
   assert (math.isnan(want) and math.isnan(number(actual))) or actual==bits(want),(i,op,hex(a),hex(b),hex(actual),hex(bits(want)))
  assert got[2:]==(i32(x,False),i32(x,True),i32(y,False),i32(y,True)),(i,a,b,got)
 report=dict(cases=len(rows),arithmetic_results=2*len(rows),integer_results=4*len(rows),status='pass',executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),input_sha256=hashlib.sha256(wire).hexdigest(),output_sha256=hashlib.sha256(data).hexdigest(),sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [ROOT/'bend/float64.bend',ROOT/'bend/tests/float64.bend',ROOT/'scripts/test_bend_float64.py']})
 (ROOT/'docs/benchmarks/bend-float64.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)
if __name__=='__main__':main()
