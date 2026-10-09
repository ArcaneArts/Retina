import Base
import ./ore_data.bend as O
import ./density.bend as Q
import ./registry.bend as P
import ./reader.bend as R
import ./binary.bend as B
import ./compression.bend as Z

type Request is Data:
  Request{active: Bool,recipe: U32,biome: U32,y: U32,host: U32,random: U32,exposed: Bool}
type Input is Type:
  Input{queries: Array<Request>,count: U32,valid: Bool}
type Computed is Type:
  Computed{rows: Array<U32>}

def inactive() -> Request:
  Request{False{},0,0,0,0,0,False{}}

@unsafe
def requests(n: Nat,cursor: R.Cursor,a: Array<Request>,+i: U32,+count: U32,+store: O.Store) -> Input:
  match n:
    case 0n: Input{a,count,R.end(cursor)}
    case 1n+p: R.word(Input,cursor,cursor => +recipe => R.word(Input,cursor,cursor => +biome =>
      R.word(Input,cursor,cursor => y => R.word(Input,cursor,cursor => +host => R.word(Input,cursor,cursor => random =>
        R.word(Input,cursor,cursor => +exposed => request_valid(Bool.and(U32.is_lt(recipe,O.recipes(store)),
          Bool.and(U32.is_lt(biome,O.biomes(store)),Bool.and(U32.is_lt(host,O.palette(store)),U32.is_le(exposed,1)))),
            p,cursor,a,i,count,store,Request{True{},recipe,biome,y,host,random,U32.is_eq(exposed,1)})))))))

@unsafe
def request_valid(ok: Bool,n: Nat,cursor: R.Cursor,a: Array<Request>,+i: U32,count: U32,store: O.Store,query: Request) -> Input:
  match ok:
    case False{}: Input{a,count,False{}}
    case True{}: requests(n,cursor,Array.set(Request,a,i,query),U32.inc(i),count,store)

@unsafe
def size(ok: Bool,cursor: R.Cursor,+count: U32,store: O.Store) -> Input:
  match ok:
    case False{}: Input{[inactive():Request*1n],0,False{}}
    case True{}: requests(U32.to_nat(count),cursor,Array.new(Request,P.capacity(count,1,0n),inactive()),0,count,store)

@unsafe
def decode(bytes: Z.Bytes,+store: O.Store) -> Input:
  R.word(Input,R.start(bytes),cursor => +count => size(U32.is_le(count,4096),cursor,count,store))

@unsafe
def row(query: Request,+store: O.Store) -> U32:
  match query:
    case Request{False{},_,_,_,_,_,_}: 0
    case Request{True{},+id,biome,y,host,random,exposed}: eligible(O.member(store,biome,id),store,id,y,host,random,exposed)

@unsafe
def eligible(ok: Bool,store: O.Store,id: U32,y: U32,host: U32,random: U32,exposed: Bool) -> U32:
  match ok:
    case False{}: 0
    case True{}: O.replacement(store,id,y,host,random,exposed)

@unsafe
def calculate(+store: O.Store,queries: Array<Request>) -> Array<U32>:
  match queries:
    case ALeaf{query}: ALeaf{row(query,store)}
    case ANode{a,b}:
      a b = calculate(store,a) calculate(store,b)
      ANode{a,b}

def execute(store: O.Store,queries: Array<Request>) -> Computed:
  rows : Array<U32> = calculate!(store,queries)
  Computed{rows}

@unsafe
def encoded(n: Nat,rows: Array<U32>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: Z.read(B.Writer,rows,i,rows => value => encoded(p,rows,B.int(w,value),U32.inc(i)))

@unsafe
def words(n: U32,+store: O.Store,+at: U32,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: words(U32.sub(n,1),store,U32.inc(at),B.int(w,O.word(store,at)))

@unsafe
def bounds(n: U32,+store: O.Store,+at: U32,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: bounds(U32.sub(n,1),store,U32.add(at,U32.add(O.palette(store),2)),words(2,store,at,w))

@unsafe
def metadata_row(mode: U32,+store: O.Store,id: U32,w: B.Writer) -> B.Writer:
  match mode:
    case 1: recipe_row(store,O.recipe_at(id),w)
    case _: biome_row(store,O.biome_at(store,id),w)

@unsafe
def recipe_row(+store: O.Store,+at: U32,w: B.Writer) -> B.Writer:
  bounds(O.word(store,U32.add(at,11)),store,O.word(store,U32.add(at,12)),words(12,store,at,w))

@unsafe
def biome_row(+store: O.Store,+at: U32,w: B.Writer) -> B.Writer:
  words(O.word(store,U32.inc(at)),store,O.word(store,at),B.int(w,O.word(store,U32.inc(at))))

@unsafe
def metadata(n: Nat,cursor: R.Cursor,+mode: U32,+store: O.Store,w: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match n:
    case 0n: ended(R.end(cursor),w)
    case 1n+p: R.word(Result<&1,&1,U32,B.Writer>,cursor,cursor => +id =>
      metadata_valid(U32.is_lt(id,Z.word(U32.is_eq(mode,1),O.recipes(store),O.biomes(store))),p,cursor,mode,store,id,w))

@unsafe
def metadata_valid(ok: Bool,n: Nat,cursor: R.Cursor,+mode: U32,+store: O.Store,id: U32,w: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match ok:
    case False{}: Fail{603}
    case True{}: metadata_room(n,cursor,mode,store,id,w)

@unsafe
def row_words(mode: U32,+store: O.Store,id: U32) -> U32:
  match mode:
    case 1: U32.add(12,U32.mul(O.word(store,U32.add(O.recipe_at(id),11)),2))
    case _: U32.inc(O.word(store,U32.inc(O.biome_at(store,id))))

@unsafe
def metadata_room(n: Nat,cursor: R.Cursor,+mode: U32,+store: O.Store,+id: U32,w: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match w:
    case B.Writer{a,+size,capacity,error}: metadata_fits(U32.is_le(row_words(mode,store,id),U32.div(U32.sub(16777216,size),4)),n,cursor,mode,store,id,B.Writer{a,size,capacity,error})

@unsafe
def metadata_fits(ok: Bool,n: Nat,cursor: R.Cursor,+mode: U32,+store: O.Store,id: U32,w: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match ok:
    case False{}: Fail{603}
    case True{}: metadata(n,cursor,mode,store,metadata_row(mode,store,id,w))

@unsafe
def ended(ok: Bool,w: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match ok:
    case False{}: Fail{603}
    case True{}: Done{w}

@unsafe
def metadata_size(ok: Bool,n: U32,cursor: R.Cursor,mode: U32,store: O.Store) -> Result<&1,&1,U32,B.Writer>:
  match ok:
    case False{}: Fail{603}
    case True{}: metadata(U32.to_nat(n),cursor,mode,store,B.new())

@unsafe
def metadata_mode(mode: U32,cursor: R.Cursor,+store: O.Store) -> Result<&1,&1,U32,B.Writer>:
  match mode:
    case 0: ended(R.end(cursor),B.int(words(6,store,0,B.new()),table_words(store)))
    case 1: R.word(Result<&1,&1,U32,B.Writer>,cursor,cursor => +count => metadata_size(U32.is_le(count,256),count,cursor,1,store))
    case 2: R.word(Result<&1,&1,U32,B.Writer>,cursor,cursor => +count => metadata_size(U32.is_le(count,256),count,cursor,2,store))
    case _: Fail{603}

def table_words(store: O.Store) -> U32:
  match store:
    case O.Store{words}: Q.table_size(U32,words)

@unsafe
def metadata_decode(bytes: Z.Bytes,store: O.Store) -> Result<&1,&1,U32,B.Writer>:
  R.word(Result<&1,&1,U32,B.Writer>,R.start(bytes),cursor => mode => metadata_mode(mode,cursor,store))
