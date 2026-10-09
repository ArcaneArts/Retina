import Base
import ./lake_data.bend as LD
import ./geology.bend as G
import ./density.bend as Q
import ./precise.bend as D
import ./noise.bend as N
import ./climate.bend as C
import ./terrain_surface.bend as S
import ./aquifer_lattice.bend as AL
import ./binary.bend as B
import ./reader.bend as R
import ./compression.bend as Z

# Sparse surface lakes use one seeded candidate per global 128-block cell.
# This stage selects loaded budgets/materials and geometry. Bank probes, basin
# carving and fluid containment are subsequent stages; no terrain is changed.
type Candidate is Data:
  Candidate{present: Bool,cx: U32,cz: U32,point: Q.Point,radius: F32,biome: U32,eligible: Bool,lava: Bool,barrier: U32,flags: U32,chance: F32}
type Computed is Type:
  Computed{rows: Array<Candidate>}

@unsafe
def hash(+x: U32,+z: U32,+low: U32,high: U32) -> U32:
  N.hash(U32.xor(U32.xor(U32.mul(x,2654435769),U32.mul(z,2246822507)),U32.xor(U32.add(low,8647),N.hash(high))))

@unsafe
def center(+x: U32,+z: U32,+h: U32) -> Q.Point:
  Q.Point{D.add(D.scale(D.integer(x),128.0),D.scalar(F32.mul(F32.add(0.25,F32.div(U32.to_f32(U32.and(h,255)),510.0)),128.0))),
    D.scalar(0.0),D.add(D.scale(D.integer(z),128.0),D.scalar(F32.mul(F32.add(0.25,F32.div(U32.to_f32(U32.and(U32.shrn(h,8n),255)),510.0)),128.0)))}

@unsafe
def cell(+value: U32) -> U32:
  AL.word(D.floor(D.divide(D.integer(value),D.scalar(128.0))))

@unsafe
def row(query: S.Query,+model: Q.Model,+programs: Q.Table<Q.Program>,climate: C.Model,biomes: Q.Table<G.Biome>) -> Candidate:
  match query:
    case S.Query{+x,+z,+low,+high}: at(cell(x),cell(z),low,high,model,programs,climate,biomes)

@unsafe
def at(+x: U32,+z: U32,+low: U32,+high: U32,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,biomes: Q.Table<G.Biome>) -> Candidate:
  +h = hash(x,z,low,high)
  +point = center(x,z,h)
  selected(C.lookup(climate,C.Query{S.axes(Q.run_index(model,programs,0,point,low,high)),False{},False{},False{},False{}}),
    biomes,x,z,point,h)

@unsafe
def selected(choice: C.Choice,biomes: Q.Table<G.Biome>,x: U32,z: U32,point: Q.Point,h: U32) -> Candidate:
  match choice:
    case C.Missing{}: Candidate{False{},x,z,point,0.0,4294967295,False{},False{},0,0,0.0}
    case C.Choice{_,+id,_,_}: parameters(Q.get(G.Biome,biomes,id,G.empty_biome()),x,z,point,h,id)

@unsafe
def parameters(biome: G.Biome,x: U32,z: U32,point: Q.Point,+h: U32,id: U32) -> Candidate:
  match biome:
    case G.Biome{_,LD.Params{total,+lava,barrier,water,flags},_}:
      candidate(x,z,point,F32.add(24.0,U32.to_f32(U32.and(h,7))),id,F32.div(U32.to_f32(U32.and(U32.shrn(h,16n),65535)),65535.0),total,lava,barrier,water,flags)

@unsafe
def candidate(x: U32,z: U32,point: Q.Point,radius: F32,id: U32,+chance: F32,total: F32,+lava_chance: F32,barrier: U32,water: U32,flags: U32) -> Candidate:
  selected_kind(F32.is_lt(chance,lava_chance),x,z,point,radius,id,chance,F32.is_lt(chance,total),barrier,water,flags)

@unsafe
def selected_kind(+lava: Bool,x: U32,z: U32,point: Q.Point,radius: F32,id: U32,chance: F32,water_eligible: Bool,barrier: U32,water: U32,flags: U32) -> Candidate:
  Candidate{True{},x,z,point,F32.mul(radius,N.pick(lava,0.25,1.0)),id,Bool.or(lava,water_eligible),lava,N.word(lava,barrier,water),flags,chance}

@unsafe
def calculate(+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+biomes: Q.Table<G.Biome>,queries: Array<S.Query>) -> Array<Candidate>:
  match queries:
    case ALeaf{query}: ALeaf{row(query,model,programs,climate,biomes)}
    case ANode{a,b}:
      a b = calculate(model,programs,climate,biomes,a) calculate(model,programs,climate,biomes,b)
      ANode{a,b}

def execute(model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,biomes: Q.Table<G.Biome>,queries: Array<S.Query>) -> Computed:
  rows : Array<Candidate> = calculate!(model,programs,climate,biomes,queries)
  Computed{rows}

def number(value: D.Number,writer: B.Writer) -> B.Writer:
  match value:
    case D.Number{high,low}: B.int(B.int(writer,F32.bits(high)),F32.bits(low))

@unsafe
def encoded_row(value: Candidate,writer: B.Writer) -> B.Writer:
  match value:
    case Candidate{present,cx,cz,Q.Point{x,_,z},radius,biome,eligible,lava,barrier,flags,chance}:
      B.int(B.int(B.int(B.int(B.int(B.int(B.int(number(z,number(x,B.int(B.int(B.int(writer,N.word(present,1,0)),cx),cz))),
        F32.bits(radius)),biome),N.word(eligible,1,0)),N.word(lava,1,0)),barrier),flags),F32.bits(chance))

@unsafe
def encode(n: Nat,rows: Array<Candidate>,writer: B.Writer,+index: U32) -> B.Writer:
  match n:
    case 0n: writer
    case 1n+p: encoded(p,Array.get(Candidate,rows,index),writer,index)

@unsafe
def encoded(n: Nat,pair: Array<Candidate> & Candidate,writer: B.Writer,index: U32) -> B.Writer:
  (rows,value) = pair
  encode(n,rows,encoded_row(value,writer),U32.inc(index))

def metadata_row(params: LD.Params,writer: B.Writer) -> B.Writer:
  match params:
    case LD.Params{total,lava,barrier,water,flags}: B.int(B.int(B.int(B.int(B.int(writer,F32.bits(total)),F32.bits(lava)),barrier),water),flags)

@unsafe
def metadata(n: Nat,cursor: R.Cursor,+biomes: Q.Table<G.Biome>,writer: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match n:
    case 0n: metadata_valid(R.end(cursor),writer)
    case 1n+p: R.word(Result<&1,&1,U32,B.Writer>,cursor,cursor => +id =>
      metadata_id(U32.is_lt(id,Q.table_size(G.Biome,biomes)),p,cursor,biomes,id,writer))

def metadata_valid(ok: Bool,writer: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match ok:
    case True{}: Done{writer}
    case False{}: Fail{603}

@unsafe
def metadata_id(ok: Bool,n: Nat,cursor: R.Cursor,+biomes: Q.Table<G.Biome>,id: U32,writer: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match ok:
    case False{}: Fail{603}
    case True{}: metadata_biome(Q.get(G.Biome,biomes,id,G.empty_biome()),n,cursor,biomes,writer)

@unsafe
def metadata_biome(biome: G.Biome,n: Nat,cursor: R.Cursor,biomes: Q.Table<G.Biome>,writer: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match biome:
    case G.Biome{_,params,_}: metadata(n,cursor,biomes,metadata_row(params,writer))

@unsafe
def metadata_count(ok: Bool,n: U32,cursor: R.Cursor,biomes: Q.Table<G.Biome>) -> Result<&1,&1,U32,B.Writer>:
  match ok:
    case False{}: Fail{603}
    case True{}: metadata(U32.to_nat(n),cursor,biomes,B.new())

@unsafe
def metadata_decode(body: Z.Bytes,biomes: Q.Table<G.Biome>) -> Result<&1,&1,U32,B.Writer>:
  R.word(Result<&1,&1,U32,B.Writer>,R.start(body),cursor => +count => metadata_count(U32.is_le(count,256),count,cursor,biomes))
