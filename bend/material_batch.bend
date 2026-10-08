import Base
import ./material_program.bend as M
import ./density.bend as Q
import ./precise.bend as D
import ./registry.bend as P
import ./reader.bend as R
import ./compression.bend as Z

type Request is Data:
  Request{program: U32,x: U32,y: U32,z: U32,low: U32,high: U32,context: M.Context}
type Input is Type:
  Input{queries: Array<Request>,count: U32,valid: Bool}
type Computed is Type:
  Computed{model: Q.Model,materials: M.Model,rows: Array<Q.Outputs>}

@unsafe
def inactive() -> Request:
  Request{4294967295,0,0,0,0,0,M.Context{0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0}}

@unsafe
def context(-A: Type,cursor: R.Cursor,next: R.Cursor -> M.Context -> A) -> A:
  R.word(A,cursor,cursor => a => R.word(A,cursor,cursor => b => R.word(A,cursor,cursor => c => R.word(A,cursor,cursor => d =>
  R.word(A,cursor,cursor => e => R.word(A,cursor,cursor => f => R.word(A,cursor,cursor => g => R.word(A,cursor,cursor => h =>
    next(cursor,M.Context{R.float(a),R.float(b),R.float(c),R.float(d),R.float(e),R.float(f),R.float(g),R.float(h)})))))))))

@unsafe
def finite(+c: M.Context) -> Bool:
  match c:
    case M.Context{+a,+b,+c,+d,+e,+f,+g,+h}: Bool.and(Bool.and(Bool.and(D.finite(a),D.finite(b)),Bool.and(D.finite(c),D.finite(d))),
      Bool.and(Bool.and(D.finite(e),D.finite(f)),Bool.and(D.finite(g),D.finite(h))))

@unsafe
def requests(n: Nat,cursor: R.Cursor,a: Array<Request>,+i: U32,+count: U32,+limit: U32,+valid: Bool) -> Input:
  match n:
    case 0n: Input{a,count,Bool.and(valid,R.end(cursor))}
    case 1n+p: R.word(Input,cursor,cursor => +program => R.word(Input,cursor,cursor => x => R.word(Input,cursor,cursor => y =>
      R.word(Input,cursor,cursor => z => R.word(Input,cursor,cursor => low => R.word(Input,cursor,cursor => high =>
      context(Input,cursor,cursor => +c => requests(p,cursor,Array.set(Request,a,i,Request{program,x,y,z,low,high,c}),
        U32.inc(i),count,limit,Bool.and(valid,Bool.and(U32.is_lt(program,limit),finite(c)))))))))))

@unsafe
def size(+ok: Bool,cursor: R.Cursor,+count: U32,+limit: U32) -> Input:
  match ok:
    case False{}: Input{[inactive():Request*1n],0,False{}}
    case True{}: requests(U32.to_nat(count),cursor,Array.new(Request,P.capacity(count,1,0n),inactive()),0,count,limit,True{})

@unsafe
def decode(bytes: Z.Bytes,+limit: U32) -> Input:
  R.word(Input,R.start(bytes),cursor => +count => size(U32.is_le(count,4096),cursor,count,limit))

@unsafe
def row(request: Request,+model: Q.Model,+materials: M.Model) -> Q.Outputs:
  match request:
    case Request{+program,+x,+y,+z,+low,+high,+context}: active(U32.is_ne(program,4294967295),program,x,y,z,low,high,context,model,materials)

@unsafe
def active(+ok: Bool,+program: U32,+x: U32,+y: U32,+z: U32,+low: U32,+high: U32,+context: M.Context,+model: Q.Model,+materials: M.Model) -> Q.Outputs:
  match ok:
    case False{}: Q.Outputs{D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0)}
    case True{}: M.run(model,materials,program,Q.world(x,y,z),low,high,context)

@unsafe
def calculate(+model: Q.Model,+materials: M.Model,queries: Array<Request>) -> Array<Q.Outputs>:
  match queries:
    case ALeaf{+request}: ALeaf{row(request,model,materials)}
    case ANode{a,b}:
      a b = calculate(model,materials,a) calculate(model,materials,b)
      ANode{a,b}

@unsafe
def execute(+model: Q.Model,+materials: M.Model,queries: Array<Request>) -> Computed:
  rows : Array<Q.Outputs> = calculate!(model,M.rules(materials),queries)
  Computed{model,materials,rows}
