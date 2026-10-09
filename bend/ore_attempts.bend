import Base
import ./ore_data.bend as O
import ./ore_random.bend as Random
import ./word64.bend as W
import ./density.bend as Q
import ./registry.bend as P
import ./reader.bend as R
import ./binary.bend as B
import ./compression.bend as Z

# Reusable globally anchored attempt generation. Biome membership, geometric
# clipping and block replay run later, retaining the original attempt indices.
type Query is Data:
  Query{active: Bool,recipe: U32,x: U32,z: U32,seed: W.U64}
type Attempt is Data:
  Attempt{index: U32,x: U32,y: U32,z: U32,random: W.U64}
type Row is Data:
  Row{count: U32,attempts: Q.Table<Attempt>}
type Input is Type:
  Input{queries: Array<Query>,count: U32,valid: Bool}
type Computed is Type:
  Computed{rows: Array<Row>}

@unsafe
def inactive() -> Query:
  Query{False{},0,0,0,W.U64{0,0}}

# Chunk anchors must have all 16 block coordinates representable as signed i32.
@unsafe
def anchor(+value: U32) -> Bool:
  Bool.or(U32.is_le(value,134217727),U32.is_ge(value,4160749568))

@unsafe
def requests(n: Nat,cursor: R.Cursor,a: Array<Query>,+i: U32,count: U32,+store: O.Store) -> Input:
  match n:
    case 0n: Input{a,count,R.end(cursor)}
    case 1n+p: R.word(Input,cursor,cursor => +id => R.word(Input,cursor,cursor => +x =>
      R.word(Input,cursor,cursor => +z => R.word(Input,cursor,cursor => low => R.word(Input,cursor,cursor => high =>
        valid(Bool.and(U32.is_lt(id,O.recipes(store)),Bool.and(anchor(x),anchor(z))),p,cursor,a,i,count,store,Query{True{},id,x,z,W.U64{high,low}}))))))

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

@unsafe
def attempts(+n: U32,+index: U32,+id: U32,+x: U32,+z: U32,+store: O.Store,+seed: W.U64,+state: W.U64,+draws: U32) -> Q.Table<Attempt>:
  match n:
    case 0: Q.Empty{}
    case 1: Q.Leaf{attempt_x(Random.integer(Random.advance(state,U32.mul(index,draws)),0,15),index,id,x,z,store,seed)}
    case _:
      +half = U32.div(n,2)
      a b = attempts(half,index,id,x,z,store,seed,state,draws) attempts(U32.sub(n,half),U32.add(index,half),id,x,z,store,seed,state,draws)
      Q.Branch{n,a,b}

@unsafe
def attempt_x(draw: Random.Draw,index: U32,id: U32,x: U32,z: U32,store: O.Store,seed: W.U64) -> Attempt:
  match draw:
    case Random.Draw{state,dx}: attempt_z(Random.integer(state,0,15),index,id,x,z,store,seed,dx)

@unsafe
def attempt_z(draw: Random.Draw,index: U32,id: U32,x: U32,z: U32,store: O.Store,seed: W.U64,dx: U32) -> Attempt:
  match draw:
    case Random.Draw{state,dz}: attempt_y(Random.height(store,id,state),index,x,z,seed,dx,dz)

@unsafe
def attempt_y(draw: Random.Draw,+index: U32,x: U32,z: U32,seed: W.U64,dx: U32,dz: U32) -> Attempt:
  match draw:
    case Random.Draw{state,y}:
      Attempt{index,U32.add(U32.mul(x,16),dx),y,U32.add(U32.mul(z,16),dz),Random.local(seed,index)}

@unsafe
def budget(eligible: Bool,+store: O.Store,+id: U32,x: U32,z: U32,seed: W.U64,state: W.U64) -> Row:
  match eligible:
    case False{}: Row{0,Q.Empty{}}
    case True{}:
      +at = O.recipe_at(id)
      budget_draw(Random.integer(state,O.word(store,U32.add(at,4)),O.word(store,U32.add(at,5))),store,id,x,z,seed)

@unsafe
def budget_draw(draw: Random.Draw,+store: O.Store,+id: U32,x: U32,z: U32,seed: W.U64) -> Row:
  match draw:
    case Random.Draw{state,+n}: Row{n,attempts(n,0,id,x,z,store,seed,state,Random.attempt_draws(store,id))}

@unsafe
def row(query: Query,+store: O.Store) -> Row:
  match query:
    case Query{False{},_,_,_,_}: Row{0,Q.Empty{}}
    case Query{True{},+id,+x,+z,world}:
      +seed = Random.seed(world,x,z,id)
      rarity(Random.next(seed),store,id,x,z,seed)

@unsafe
def rarity(draw: Random.Draw,+store: O.Store,+id: U32,x: U32,z: U32,seed: W.U64) -> Row:
  match draw:
    case Random.Draw{state,value}: budget(U32.is_zero(U32.mod(value,O.word(store,U32.add(O.recipe_at(id),6)))),store,id,x,z,seed,state)

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
def output(n: U32,+xs: Q.Table<Attempt>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0: w
    case _: output_next(Q.get(Attempt,xs,i,Attempt{0,0,0,0,W.U64{0,0}}),U32.sub(n,1),xs,w,i)

@unsafe
def output_next(attempt: Attempt,n: U32,xs: Q.Table<Attempt>,w: B.Writer,i: U32) -> B.Writer:
  match attempt:
    case Attempt{index,x,y,z,W.U64{high,low}}:
      output(n,xs,B.int(B.int(B.int(B.int(B.int(B.int(w,index),x),y),z),high),low),U32.inc(i))

@unsafe
def output_row(row: Row,w: B.Writer) -> B.Writer:
  match row:
    case Row{+n,xs}: output(n,xs,B.int(w,n),0)

@unsafe
def encode(n: Nat,rows: Array<Row>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: encoded(p,Array.get(Row,rows,i),w,i)

@unsafe
def encoded(n: Nat,pair: Array<Row> & Row,w: B.Writer,+i: U32) -> B.Writer:
  (rows,row) = pair
  encode(n,rows,output_row(row,w),U32.inc(i))
