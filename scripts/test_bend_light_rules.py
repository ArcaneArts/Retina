#!/usr/bin/env python3
"""Start Bend lighting with registry-derived optical/face rules, not full lighting.

An explicit loaded registry export supplies every trait and face-pair bit. The
independent Python reference checks 65,536 transitions/sky seeds/nibble packs on
CPU and GPU-required execution. This is correctness evidence, not a benchmark.
"""
import argparse,hashlib,json,struct
from pathlib import Path
from test_bend_compression import ROOT,DEFAULT_BEND,build,command

DRIVER='''import Base
import ../../bend/light_rules.bend as L
import ../../bend/binary.bend as B
import ../../bend/tests/binary-fixtures.bend as T

type Row is Data:
  Row{blocked: U32,light: U32,sun: U32,next: U32,packed: U32}
type Rows is Type:
  Leaf{values: Array<Row>}
  Node{left: Rows,right: Rows}

STATE_PAGES

def coverage() -> Array<U32>:
  COVERAGE

def bit(value: Bool) -> U32:
  match value:
    case False{}: 0
    case True{}: 1

def field(index: U32,offset: U32) -> U32:
  state_word(U32.add(U32.mul(index,8),offset))

def field_value(pair: Array<U32> & U32) -> U32:
  (_,value) = pair
  value

def row(+i: U32) -> Row:
  +a = U32.mod(i,COUNT)
  +b = U32.mod(U32.mul(i,4051),COUNT)
  +direction = U32.mod(i,6)
  +blocked = L.occludes(field(a,U32.inc(direction)),field(b,U32.inc(U32.xor(direction,1))),STRIDE,coverage())
  +traits = field(b,0)
  +current = U32.and(U32.shrn(i,4n),255)
  +incoming = U32.and(U32.mul(i,73),255)
  +next = L.propagate(current,incoming,traits,blocked,U32.and(U32.shrn(i,8n),3))
  finish(L.seed(U32.is_zero(U32.and(i,1)),U32.is_zero(U32.and(i,2)),traits,blocked),blocked,next,current,incoming,U32.is_zero(U32.and(i,4)))

def finish(seed: L.Seed,blocked: Bool,+next: U32,current: U32,incoming: U32,sky: Bool) -> Row:
  match seed:
    case L.Seed{+light,sun}: Row{bit(blocked),light,bit(sun),next,L.pack4(current,incoming,light,next,sky)}

def fill(+n: Nat,+base: U32,values: Array<Row>) -> Array<Row>:
  match n:
    case 0n: values
    case 1n+p:
      +i = U32.from_nat(p)
      fill(p,base,Array.set(Row,values,i,row(U32.add(base,i))))

def batch(+depth: Nat,+base: U32) -> Rows:
  match depth:
    case 0n: Leaf{fill(256n,base,Array.new(Row,8n,Row{0,0,0,0,0}))}
    case 1n+p:
      a b = batch(p,base) batch(p,U32.add(base,U32.shln(256,p)))
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

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> output <> Nil{}:
      T.unwrapped(B.done(encode(batch(8n,0),B.new())),output)
    case _: IO.die(Unit,2,"usage: light-rules output-file")

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

def state_pages(values):
    # Keep literals bounded: a single 32K-word literal exhausts Bend's proof
    # machine stack before compilation. Paging changes fixture storage only.
    pages=[values[i:i+256] for i in range(0,len(values),256)]
    branches='\n'.join(f'    case {i}: field_value(Array.get(U32,{constant(page)},offset))' for i,page in enumerate(pages))
    return '''def state_word(+index: U32) -> U32:
  state_page(U32.div(index,256),U32.mod(index,256))

def state_page(page: U32,offset: U32) -> U32:
  match page:
'''+branches+'\n    case _: 0\n'

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
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--profile',type=Path,required=True);p.add_argument('--skip-build',action='store_true');a=p.parse_args()
    source=json.loads(a.profile.read_text());lighting=source['lighting'];states=lighting['states']
    assert states and all(len(row)==8 for row in states)
    assert len(lighting['blocked'])==lighting['faces']*((lighting['faces']+31)//32)
    folder=ROOT/'build/bend';folder.mkdir(parents=True,exist_ok=True)
    driver=folder/'light-rules-fixtures.bend';binary=folder/'light-rules'
    driver.write_text(DRIVER.replace('STATE_PAGES',state_pages([n for row in states for n in row])).replace('\ndef ','\n@unsafe\ndef ').replace('COVERAGE',constant(lighting['blocked'])).replace('COUNT',str(len(states))).replace('STRIDE',str((lighting['faces']+31)//32)))
    if not a.skip_build:build(DEFAULT_BEND,binary,driver)
    runs=[]
    for gpu in (False,True):
        output=folder/('light-rules-gpu.bin' if gpu else 'light-rules-cpu.bin')
        command(['nice','-n','10',binary,output,'--threads','2','--gpu','on' if gpu else 'off'],timeout=180)
        runs.append(dict(gpu_required=gpu,**check(output.read_bytes(),lighting)))
    assert runs[0]['sha256']==runs[1]['sha256']
    report=dict(scope=__doc__.strip(),profile_sha256=hashlib.sha256(a.profile.read_bytes()).hexdigest(),
                sources={str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in (ROOT/'bend/light_rules.bend',Path(__file__).resolve())},
                executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                states=len(states),face_shapes=lighting['faces'],runs=runs)
    destination=folder/'light-rules-tests.json';destination.write_text(json.dumps(report,indent=2)+'\n');print('PASS:',destination)
if __name__=='__main__':main()
