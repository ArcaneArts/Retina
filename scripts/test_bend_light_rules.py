#!/usr/bin/env python3
"""Start Bend lighting with registry-derived optical/face rules, not full lighting.

An explicit loaded registry export supplies every trait and face-pair bit. The
independent Python reference checks 65,536 transitions/sky seeds/nibble packs on
CPU and GPU-required execution. This is correctness evidence, not a benchmark.
"""
import argparse,hashlib,json,re,struct
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,build,command
from test_bend_engine import compile_metal_observer
from test_bend_registry_noise import fixture_wire

DRIVER='''import Base
import ../../bend/light_rules.bend as L
import ../../bend/binary.bend as B
import ../../bend/tests/binary-fixtures.bend as T
import ../../bend/registry.bend as P
import ../../bend/registry_values.bend as V
import ../../bend/registry_cave_features.bend as F
import ../../bend/density.bend as Q

type Row is Data:
  Row{blocked: U32,light: U32,sun: U32,next: U32,packed: U32}
type Rows is Type:
  Leaf{values: Array<Row>}
  Node{left: Rows,right: Rows}

def coverage() -> Array<U32>:
  COVERAGE

def bit(value: Bool) -> U32:
  match value:
    case False{}: 0
    case True{}: 1

def field(index: U32,offset: U32,states: Q.Table<U32>) -> U32:
  Q.get(U32,states,U32.add(U32.mul(index,8),offset),0)

def row(+i: U32,+states: Q.Table<U32>) -> Row:
  +a = U32.mod(i,COUNT)
  +b = U32.mod(U32.mul(i,4051),COUNT)
  +direction = U32.mod(i,6)
  +blocked = L.occludes(field(a,U32.inc(direction),states),field(b,U32.inc(U32.xor(direction,1)),states),STRIDE,coverage())
  +traits = field(b,0,states)
  +current = U32.and(U32.shrn(i,4n),255)
  +incoming = U32.and(U32.mul(i,73),255)
  +next = L.propagate(current,incoming,traits,blocked,U32.and(U32.shrn(i,8n),3))
  finish(L.seed(U32.is_zero(U32.and(i,1)),U32.is_zero(U32.and(i,2)),traits,blocked),blocked,next,current,incoming,U32.is_zero(U32.and(i,4)))

def finish(seed: L.Seed,blocked: Bool,+next: U32,current: U32,incoming: U32,sky: Bool) -> Row:
  match seed:
    case L.Seed{+light,sun}: Row{bit(blocked),light,bit(sun),next,L.pack4(current,incoming,light,next,sky)}

def fill(+n: Nat,+base: U32,+states: Q.Table<U32>,values: Array<Row>) -> Array<Row>:
  match n:
    case 0n: values
    case 1n+p:
      +i = U32.from_nat(p)
      fill(p,base,states,Array.set(Row,values,i,row(U32.add(base,i),states)))

def batch(+depth: Nat,+base: U32,+states: Q.Table<U32>) -> Rows:
  match depth:
    case 0n: Leaf{fill(256n,base,states,Array.new(Row,8n,Row{0,0,0,0,0}))}
    case 1n+p:
      a b = batch(p,base,states) batch(p,U32.add(base,U32.shln(256,p)),states)
      Node{a,b}

def write(row: Row,w: B.Writer) -> B.Writer:
  match row:
    case Row{a,b,c,d,e}: B.int(B.int(B.int(B.int(B.int(w,a),b),c),d),e)

def array(n: Nat,values: Array<Row>,+i: U32,w: B.Writer) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p:
      array_row(Array.get(Row,values,i),p,i,w)

def array_row(pair: Array<Row> & Row,n: Nat,+i: U32,w: B.Writer) -> B.Writer:
  (values,row) = pair
  array(n,values,U32.inc(i),write(row,w))

def encode(rows: Rows,w: B.Writer) -> B.Writer:
  match rows:
    case Leaf{values}: array(256n,values,0,w)
    case Node{a,b}: encode(b,encode(a,w))

def generate(valid: Bool,states: Q.Table<U32>,output: String) -> IO(Unit):
  match valid:
    case False{}: IO.die(Unit,2,"invalid optical table length")
    case True{}:
      do IO<Unit>:
        _ : Nat <- IO.now()
        rows : Rows = batch!(8n,0,states)
        T.unwrapped(B.done(encode(rows,B.new())),output)

def prepared(result: Result<&1,&1,U32,Q.Table<U32>>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid optical table")
    case Done{+states}: generate(U32.is_eq(Q.table_size(U32,states),STATE_WORDS),states,output)

def loaded(result: Result<&1,&1,U32,P.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid registry transport")
    case Done{doc}: V.root(IO(Unit),doc,doc => root => V.field(IO(Unit),doc,root,"states",doc => found =>
      F.array(IO(Unit),found,doc,0,doc => result => prepared(result,output))))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,P.Document> <- P.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: light-rules input.rbp output-file")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
'''

def constant(values):
    size=1<<(max(1,len(values))-1).bit_length()
    def tree(xs):
        if len(xs)==1:return 'ALeaf{'+str(xs[0])+'}'
        mid=len(xs)//2
        return 'ANode{'+tree(xs[:mid])+','+tree(xs[mid:])+'}'
    return tree(values+[0]*(size-len(values)))

def check(data,lighting):
    assert len(data)==65536*20
    states,faces=lighting['states'],lighting['blocked'];stride=(lighting['faces']+31)//32
    blocked_count=sky_count=emission_count=0
    for i,actual in enumerate(struct.iter_unpack('>5I',data)):
        a,b,d=i%len(states),(i*4051)%len(states),i%6
        blocked=(faces[states[a][1+d]*stride+states[b][1+(d^1)]//32]>>(states[b][1+(d^1)]%32))&1
        opacity,emission=states[b][0]&15,(states[b][0]>>4)&15
        sun=int(i&3==0 and opacity==0 and not blocked)
        light=emission|(240 if sun else 0)
        current,incoming,channels=(i>>4)&255,(i*73)&255,(i>>8)&3
        result=current
        if not blocked and opacity<15:
            loss=max(1,opacity)
            block=max(current&15,max(0,(incoming&15)-loss)) if channels&1 else current&15
            sky=max(current>>4,max(0,(incoming>>4)-loss)) if channels&2 else current>>4
            result=block|sky<<4
        shift=4 if i&4==0 else 0
        packed=sum(((v>>shift)&15)<<(j*4) for j,v in enumerate((current,incoming,light,result)))
        assert actual==(blocked,light,sun,result,packed),(i,actual,(blocked,light,sun,result,packed))
        blocked_count+=blocked;sky_count+=sun;emission_count+=emission>0
    assert blocked_count and sky_count and emission_count
    return dict(rows=65536,blocked_edges=blocked_count,direct_sky=sky_count,emissive_states=emission_count,sha256=hashlib.sha256(data).hexdigest())

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,required=True);p.add_argument('--skip-build',action='store_true');p.add_argument('--prove-metal',action='store_true');a=p.parse_args()
    source=json.loads(a.profile.read_text());lighting=source['lighting'];states=lighting['states']
    assert states and all(len(row)==8 for row in states)
    assert len(lighting['blocked'])==lighting['faces']*((lighting['faces']+31)//32)
    folder=ROOT/'build/bend';folder.mkdir(parents=True,exist_ok=True)
    driver=folder/'light-rules-fixtures.bend';binary=folder/'light-rules'
    inputs=folder/'light-rules-input.rbp'
    inputs.write_bytes(fixture_wire({'states':[n for row in states for n in row]},True))
    driver.write_text(DRIVER.replace('\ndef ','\n@unsafe\ndef ').replace('COVERAGE',constant(lighting['blocked'])).replace('COUNT',str(len(states))).replace('STATE_WORDS',str(len(states)*8)).replace('STRIDE',str((lighting['faces']+31)//32)))
    if not a.skip_build:build(DEFAULT_BEND,binary,driver)
    runs=[]
    for gpu in (False,True):
        output=folder/('light-rules-gpu.bin' if gpu else 'light-rules-cpu.bin')
        command(['nice','-n','10',binary,inputs,output,'--threads','2','--gpu','on' if gpu else 'off'],timeout=180)
        runs.append(dict(gpu_required=gpu,**check(output.read_bytes(),lighting)))
    assert runs[0]['sha256']==runs[1]['sha256']
    observed=[]
    if a.prove_metal:
        probe=folder/'light-rules-observer';compile_metal_observer(DEFAULT_BEND,driver,probe,binary.with_suffix('.gpu'))
        output=folder/'light-rules-observed.bin'
        result=command(['nice','-n','10',probe,inputs,output,'--threads','2','--gpu','on'],timeout=180)
        assert check(output.read_bytes(),lighting)['sha256']==runs[0]['sha256']
        observed=[float(n) for n in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',result.stderr)]
        assert observed,'No actual Metal command buffers observed'
    report=dict(scope=__doc__.strip(),profile_sha256=hashlib.sha256(a.profile.read_bytes()).hexdigest(),
                sources={str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in (ROOT/'bend/light_rules.bend',Path(__file__).resolve())},
                executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                states=len(states),face_shapes=lighting['faces'],runs=runs,observed_metal_device_ms=observed)
    destination=folder/'light-rules-tests.json';destination.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',destination)
if __name__=='__main__':main()
