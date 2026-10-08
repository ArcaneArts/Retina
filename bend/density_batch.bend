import Base
import ./density.bend as Q
import ./precise.bend as D
import ./registry.bend as P
import ./reader.bend as R
import ./binary.bend as B
import ./compression.bend as Z

type Request is Data:
  Request{program: U32,x: U32,y: U32,z: U32,low: U32,high: U32}
type Input is Type:
  Input{queries: Array<Request>,count: U32,valid: Bool}

@unsafe
def requests(n: Nat,cursor: R.Cursor,a: Array<Request>,+i: U32,+count: U32,+valid: Bool) -> Input:
  match n:
    case 0n: Input{a,count,Bool.and(valid,R.end(cursor))}
    case 1n+p: R.word(Input,cursor,cursor => +program => R.word(Input,cursor,cursor => x => R.word(Input,cursor,cursor => y =>
      R.word(Input,cursor,cursor => z => R.word(Input,cursor,cursor => low => R.word(Input,cursor,cursor => high =>
      requests(p,cursor,Array.set(Request,a,i,Request{program,x,y,z,low,high}),U32.inc(i),count,Bool.and(valid,U32.is_lt(program,3)))))))))

def size(valid: Bool,cursor: R.Cursor,+count: U32) -> Input:
  match valid:
    case False{}: Input{[Request{4294967295,0,0,0,0,0}:Request*1n],0,False{}}
    case True{}: requests(U32.to_nat(count),cursor,Array.new(Request,P.capacity(count,1,0n),Request{4294967295,0,0,0,0,0}),0,count,True{})

def decode(bytes: Z.Bytes) -> Input:
  R.word(Input,R.start(bytes),cursor => +count => size(U32.is_le(count,4096),cursor,count))

@unsafe
def row(active: Bool,model: Q.Model,programs: Q.Table<Q.Program>,request: Request) -> Q.Outputs:
  match active request:
    case False{} _: Q.Outputs{D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0)}
    case True{} Request{program,x,y,z,low,high}: Q.run(model,Q.get(Q.Program,programs,program,Q.Program{[],[],0}),Q.world(x,y,z),low,high)

@unsafe
def calculate(+model: Q.Model,+programs: Q.Table<Q.Program>,requests: Array<Request>) -> Array<Q.Outputs>:
  match requests:
    case ALeaf{+request}: ALeaf{row(active(request),model,programs,request)}
    case ANode{a,b}:
      a b = calculate(model,programs,a) calculate(model,programs,b)
      ANode{a,b}

@unsafe
def active(request: Request) -> Bool:
  match request:
    case Request{program,_,_,_,_,_}: U32.is_ne(program,4294967295)

@unsafe
def encode(n: Nat,a: Array<Q.Outputs>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: encoded(p,Array.get(Q.Outputs,a,i),w,i)

@unsafe
def encoded(n: Nat,pair: Array<Q.Outputs> & Q.Outputs,w: B.Writer,i: U32) -> B.Writer:
  (a,output) = pair
  encode_row(n,a,output,w,i)

@unsafe
def encode_row(n: Nat,a: Array<Q.Outputs>,output: Q.Outputs,w: B.Writer,i: U32) -> B.Writer:
  match output:
    case Q.Outputs{v0,v1,v2,v3,v4,v5}: encode(n,a,B.int(B.int(B.int(B.int(B.int(B.int(w,
      F32.bits(D.value(v0))),F32.bits(D.value(v1))),F32.bits(D.value(v2))),F32.bits(D.value(v3))),F32.bits(D.value(v4))),F32.bits(D.value(v5))),U32.inc(i))

