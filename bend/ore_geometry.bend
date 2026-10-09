import Base
import ./ore_data.bend as O
import ./ore_random.bend as Random
import ./word64.bend as W
import ./density.bend as Q
import ./reader.bend as R
import ./registry.bend as P
import ./binary.bend as B
import ./compression.bend as Z
import ./precise.bend as D

# Local offsets keep sphere geometry independent of distant world coordinates.
# Kind 0: F32 sphere X/Y/Z/radius bits (radius zero means contained).
# Kind 1: signed scattered X/Y/Z offsets plus the exposure-random word.
type Piece is Data:
  Piece{x: U32,y: U32,z: U32,value: U32}
type Row is Data:
  Row{kind: U32,pieces: List<&2,Piece>}
type Query is Data:
  Query{active: Bool,recipe: U32,random: W.U64}
type Input is Type:
  Input{queries: Array<Query>,count: U32,valid: Bool}
type Computed is Type:
  Computed{rows: Array<Row>}
type Ends is Data:
  Ends{dx: F32,dz: F32,a: F32,b: F32}

@unsafe
def inactive() -> Query:
  Query{False{},0,W.U64{0,0}}

@unsafe
def requests(n: Nat,cursor: R.Cursor,a: Array<Query>,+i: U32,count: U32,+store: O.Store) -> Input:
  match n:
    case 0n: Input{a,count,R.end(cursor)}
    case 1n+p: R.word(Input,cursor,cursor => +id => R.word(Input,cursor,cursor => high =>
      R.word(Input,cursor,cursor => low => valid(U32.is_lt(id,O.recipes(store)),p,cursor,a,i,count,store,Query{True{},id,W.U64{high,low}}))))

@unsafe
def valid(ok: Bool,n: Nat,cursor: R.Cursor,a: Array<Query>,+i: U32,count: U32,store: O.Store,query: Query) -> Input:
  match ok:
    case False{}: Input{a,count,False{}}
    case True{}: requests(n,cursor,Array.set(Query,a,i,query),U32.inc(i),count,store)

@unsafe
def size(ok: Bool,cursor: R.Cursor,+n: U32,store: O.Store) -> Input:
  match ok:
    case False{}: Input{[inactive():Query*1n],0,False{}}
    case True{}: requests(U32.to_nat(n),cursor,Array.new(Query,P.capacity(n,1,0n),inactive()),0,n,store)

@unsafe
def decode(bytes: Z.Bytes,+store: O.Store) -> Input:
  R.word(Input,R.start(bytes),cursor => +n => size(U32.is_le(n,256),cursor,n,store))

# Explicit symmetric rounding avoids CPU/GPU round-to-even differences at ties.
@unsafe
def rounded(+x: F32) -> U32:
  rounded_sign(F32.is_lt(x,0.0),F32.to_u32(F32.floor(F32.add(F32.abs(x),0.5))))

@unsafe
def rounded_sign(negative: Bool,value: U32) -> U32:
  match negative:
    case True{}: U32.sub(0,value)
    case False{}: value

@unsafe
def offset(state: W.U64,spread: F32) -> Random.Draw:
  offset_a(Random.next(state),spread)

@unsafe
def offset_a(draw: Random.Draw,spread: F32) -> Random.Draw:
  match draw:
    case Random.Draw{state,a}: offset_b(Random.next(state),spread,a)

@unsafe
def offset_b(draw: Random.Draw,spread: F32,a: U32) -> Random.Draw:
  match draw:
    case Random.Draw{state,b}: Random.Draw{state,rounded(F32.mul(F32.sub(Random.unit(a),Random.unit(b)),spread))}

@unsafe
def scattered(n: U32,+i: U32,state: W.U64) -> List<&2,Piece>:
  match n:
    case 0: []
    case _: scatter_x(offset(state,U32.to_f32(U32.min(i,7))),n,i)

@unsafe
def scatter_x(draw: Random.Draw,n: U32,+i: U32) -> List<&2,Piece>:
  match draw:
    case Random.Draw{state,x}: scatter_y(offset(state,U32.to_f32(U32.min(i,7))),n,i,x)

@unsafe
def scatter_y(draw: Random.Draw,n: U32,+i: U32,x: U32) -> List<&2,Piece>:
  match draw:
    case Random.Draw{state,y}: scatter_z(offset(state,U32.to_f32(U32.min(i,7))),n,i,x,y)

@unsafe
def scatter_z(draw: Random.Draw,n: U32,i: U32,x: U32,y: U32) -> List<&2,Piece>:
  match draw:
    case Random.Draw{state,z}: scatter_random(Random.next(state),n,i,x,y,z)

@unsafe
def scatter_random(draw: Random.Draw,n: U32,+i: U32,x: U32,y: U32,z: U32) -> List<&2,Piece>:
  match draw:
    case Random.Draw{state,value}: Piece{x,y,z,value} <> scattered(U32.sub(n,1),U32.inc(i),state)

@unsafe
def spheres(n: U32,+i: U32,+count: U32,+ends: Ends,state: W.U64) -> List<&2,Piece>:
  match n ends:
    case 0 _: []
    case _ Ends{+dx,+dz,+a,b}:
      +t = F32.div(U32.to_f32(i),U32.to_f32(count))
      sphere_draw(Random.next(state),n,i,count,ends,t,dx,dz,a,b)

@unsafe
def sphere_draw(draw: Random.Draw,n: U32,+i: U32,+count: U32,ends: Ends,+t: F32,+dx: F32,+dz: F32,+a: F32,b: F32) -> List<&2,Piece>:
  match draw:
    case Random.Draw{state,value}:
      Piece{F32.bits(F32.add(dx,F32.mul(F32.mul(dx,F32.neg(2.0)),t))),F32.bits(F32.add(a,F32.mul(F32.sub(b,a),t))),
            F32.bits(F32.add(dz,F32.mul(F32.mul(dz,F32.neg(2.0)),t))),
            F32.bits(F32.add(F32.div(F32.mul(F32.mul(F32.add(F32.sin(F32.mul(3.141592653589793,t)),1.0),Random.unit(value)),U32.to_f32(count)),32.0),0.5))} <>
              spheres(U32.sub(n,1),U32.inc(i),count,ends,state)

@unsafe
def distance(+a: F32,b: F32) -> F32:
  +d = F32.sub(a,b)
  F32.mul(d,d)

@unsafe
def hidden(piece: Piece,other: Piece) -> Bool:
  match piece other:
    case Piece{x,y,z,+r} Piece{a,b,c,+s}:
      Bool.and(F32.is_gt(R.float(s),R.float(r)),F32.is_gt(distance(R.float(s),R.float(r)),
        F32.add(F32.add(distance(R.float(x),R.float(a)),distance(R.float(y),R.float(b))),distance(R.float(z),R.float(c)))))

@unsafe
def contained(xs: List<&2,Piece>,+piece: Piece) -> Bool:
  match xs:
    case Nil{}: False{}
    case other <> rest: contained_next(hidden(piece,other),rest,piece)

@unsafe
def contained_next(found: Bool,xs: List<&2,Piece>,piece: Piece) -> Bool:
  match found:
    case True{}: True{}
    case False{}: contained(xs,piece)

@unsafe
def pruned(inside: Bool,piece: Piece) -> Piece:
  match inside piece:
    case True{} Piece{x,y,z,_}: Piece{x,y,z,0}
    case False{} _: piece

@unsafe
def prune(xs: List<&2,Piece>,+all: List<&2,Piece>) -> List<&2,Piece>:
  match xs:
    case Nil{}: []
    case +piece <> rest: pruned(contained(all,piece),piece) <> prune(rest,all)

@unsafe
def regular(count: U32,state: W.U64) -> Row:
  regular_angle(Random.next(state),count)

@unsafe
def regular_angle(draw: Random.Draw,count: U32) -> Row:
  match draw:
    case Random.Draw{state,value}: regular_a(Random.integer(state,4294967294,0),count,F32.mul(Random.unit(value),3.141592653589793))

@unsafe
def regular_a(draw: Random.Draw,count: U32,angle: F32) -> Row:
  match draw:
    case Random.Draw{state,a}: regular_b(Random.integer(state,4294967294,0),count,angle,a)

@unsafe
def regular_b(draw: Random.Draw,+count: U32,+angle: F32,a: U32) -> Row:
  match draw:
    case Random.Draw{state,b}:
      +xs = spheres(count,0,count,Ends{F32.div(F32.mul(F32.sin(angle),U32.to_f32(count)),8.0),
        F32.div(F32.mul(F32.cos(angle),U32.to_f32(count)),8.0),D.value(D.integer(a)),D.value(D.integer(b))},state)
      Row{0,prune(xs,xs)}

@unsafe
def geometry(scatter: Bool,count: U32,state: W.U64) -> Row:
  match scatter:
    case False{}: regular(count,state)
    case True{}: scatter_count(Random.integer(state,0,count))

@unsafe
def scatter_count(draw: Random.Draw) -> Row:
  match draw:
    case Random.Draw{state,n}: Row{1,scattered(n,0,state)}

@unsafe
def row(query: Query,+store: O.Store) -> Row:
  match query:
    case Query{False{},_,_}: Row{0,[]}
    case Query{True{},id,state}:
      +at = O.recipe_at(id)
      geometry(U32.is_eq(O.word(store,U32.add(at,3)),1),O.word(store,U32.inc(at)),state)

@unsafe
def calculate(+store: O.Store,queries: Array<Query>) -> Array<Row>:
  match queries:
    case ALeaf{query}: ALeaf{row(query,store)}
    case ANode{a,b}:
      a b = calculate(store,a) calculate(store,b)
      ANode{a,b}

@unsafe
def execute(store: O.Store,queries: Array<Query>) -> Computed:
  rows : Array<Row> = calculate!(store,queries)
  Computed{rows}

@unsafe
def output(xs: List<&2,Piece>,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case Piece{x,y,z,value} <> rest: output(rest,B.int(B.int(B.int(B.int(w,x),y),z),value))

@unsafe
def output_row(row: Row,w: B.Writer) -> B.Writer:
  match row:
    case Row{kind,+xs}: output(xs,B.int(B.int(w,kind),U32.from_nat(List.length(&2,Piece,xs))))

@unsafe
def encode(n: Nat,rows: Array<Row>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: encoded(p,Array.get(Row,rows,i),w,i)

@unsafe
def encoded(n: Nat,pair: Array<Row> & Row,w: B.Writer,+i: U32) -> B.Writer:
  (rows,row) = pair
  encode(n,rows,output_row(row,w),U32.inc(i))
