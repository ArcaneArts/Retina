import Base
import ./climate.bend as C
import ./registry.bend as P
import ./reader.bend as R
import ./binary.bend as B
import ./compression.bend as Z

# Bounded raw query transport and reusable parallel map. Target projection and
# fitness live in registry_climate/climate, never in the Java bridge.
type Request is Data:
  Request{point: C.Axes,mode: U32}

type Row is Data:
  Row{present: U32,biome: U32,order: U32,fitness: U32,continentalness: U32}

type Input is Type:
  Input{queries: Array<Request>,count: U32,valid: Bool}

def axes(-A: Type,cursor: R.Cursor,next: R.Cursor -> C.Axes -> A) -> A:
  R.word(A,cursor,cursor => a => R.word(A,cursor,cursor => b => R.word(A,cursor,cursor => c =>
  R.word(A,cursor,cursor => d => R.word(A,cursor,cursor => e => R.word(A,cursor,cursor => f =>
    next(cursor,C.Axes{R.float(a),R.float(b),R.float(c),R.float(d),R.float(e),R.float(f)})))))))

def inactive() -> Request:
  Request{C.Axes{0.0,0.0,0.0,0.0,0.0,0.0},32}

@unsafe
def requests(n: Nat,cursor: R.Cursor,a: Array<Request>,+i: U32,+count: U32,+valid: Bool) -> Input:
  match n:
    case 0n: Input{a,count,Bool.and(valid,R.end(cursor))}
    case 1n+p: axes(Input,cursor,cursor => +point => R.word(Input,cursor,cursor => +mode =>
      requests(p,cursor,Array.set(Request,a,i,Request{point,mode}),U32.inc(i),count,
        Bool.and(valid,Bool.and(C.finite_axes(point),U32.is_le(mode,31))))))

def size(valid: Bool,cursor: R.Cursor,+count: U32) -> Input:
  match valid:
    case False{}: Input{[inactive() : Request*1n],0,False{}}
    case True{}: requests(U32.to_nat(count),cursor,Array.new(Request,P.capacity(count,1,0n),inactive()),0,count,True{})

def decode(bytes: Z.Bytes) -> Input:
  R.word(Input,R.start(bytes),cursor => +count => size(U32.is_le(count,65536),cursor,count))

def choice(value: C.Choice) -> Row:
  match value:
    case C.Missing{}: Row{0,4294967295,4294967295,0,0}
    case C.Choice{fitness,biome,order,continentalness}: Row{1,biome,order,F32.bits(fitness),F32.bits(continentalness)}

def query(coast: Bool,model: C.Model,point: C.Axes,+mode: U32) -> Row:
  match coast:
    case True{}: choice(C.coast(model,point))
    case False{}: choice(C.lookup(model,C.Query{point,U32.is_ne(U32.and(mode,1),0),U32.is_ne(U32.and(mode,2),0),
      U32.is_ne(U32.and(mode,4),0),U32.is_ne(U32.and(mode,8),0)}))

def active(yes: Bool,model: C.Model,point: C.Axes,+mode: U32) -> Row:
  match yes:
    case False{}: Row{0,4294967295,4294967295,0,0}
    case True{}: query(U32.is_ne(U32.and(mode,16),0),model,point,mode)

def row(+model: C.Model,request: Request) -> Row:
  match request:
    case Request{point,+mode}: active(U32.is_le(mode,31),model,point,mode)

def calculate(+model: C.Model,requests: Array<Request>) -> Array<Row>:
  match requests:
    case ALeaf{request}: ALeaf{row(model,request)}
    case ANode{a,b}:
      a b = calculate(model,a) calculate(model,b)
      ANode{a,b}

@unsafe
def encode(n: Nat,a: Array<Row>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: encoded(p,Array.get(Row,a,i),w,i)

@unsafe
def encoded(n: Nat,pair: Array<Row> & Row,w: B.Writer,+i: U32) -> B.Writer:
  (a,value) = pair
  encode_row(n,a,value,w,i)

@unsafe
def encode_row(n: Nat,a: Array<Row>,value: Row,w: B.Writer,+i: U32) -> B.Writer:
  match value:
    case Row{present,biome,order,fitness,continentalness}: encode(n,a,
      B.int(B.int(B.int(B.int(B.int(w,present),biome),order),fitness),continentalness),U32.inc(i))
