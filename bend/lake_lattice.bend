import Base
import ./lake_data.bend as LD
import ./lake_candidates.bend as LC
import ./density.bend as Q
import ./density_lattice.bend as L
import ./terrain_surface.bend as S
import ./cave_lattice.bend as CV
import ./climate.bend as C
import ./geology.bend as G
import ./precise.bend as D
import ./noise.bend as N
import ./binary.bend as B
import ./reader.bend as R

type Computed is Type:
  Computed{cache: LD.Cache}
type Rows is Type:
  Rows{values: Array<Maybe<&2,LD.Basin>>}

def size(layout: LD.Layout) -> U32:
  match layout:
    case LD.Layout{_,_,nx,nz,_,_}: U32.mul(nx,nz)

# One neighboring cell on every side covers the largest elliptical basin.
# Centers, probes and raw density positions retain compensated coordinates.
@unsafe
def layout(tile: S.Tile) -> LD.Layout:
  match tile:
    case S.Tile{+x,+z,width,depth,low,high}:
      dimensions(LC.cell(x),LC.cell(z),LC.cell(U32.add(x,U32.sub(width,1))),LC.cell(U32.add(z,U32.sub(depth,1))),low,high)

def dimensions(+x: U32,+z: U32,tx: U32,tz: U32,low: U32,high: U32) -> LD.Layout:
  LD.Layout{U32.sub(x,1),U32.sub(z,1),U32.add(U32.sub(tx,x),3),U32.add(U32.sub(tz,z),3),low,high}

@unsafe
def candidate(layout: LD.Layout,+i: U32,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,biomes: Q.Table<G.Biome>) -> LC.Candidate:
  match layout:
    case LD.Layout{x,z,+nx,_,low,high}: LC.at(U32.add(x,U32.mod(i,nx)),U32.add(z,U32.div(i,nx)),low,high,model,programs,climate,biomes)

@unsafe
def candidates(+n: U32,+base: U32,+layout: LD.Layout,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+biomes: Q.Table<G.Biome>) -> Q.Table<LC.Candidate>:
  match n:
    case 1: Q.Leaf{candidate(layout,base,model,programs,climate,biomes)}
    case _: 
      +half = U32.div(n,2)
      a b = candidates(half,base,layout,model,programs,climate,biomes) candidates(U32.sub(n,half),U32.add(base,half),layout,model,programs,climate,biomes)
      Q.Branch{n,a,b}

def empty_candidate() -> LC.Candidate:
  LC.Candidate{False{},0,0,Q.world(0,0,0),0.0,4294967295,False{},False{},0,0,0.0}

@unsafe
def probe(point: Q.Point,+radius: F32,which: U32) -> Q.Point:
  match which:
    case 1: Q.shifted(point,D.scalar(F32.add(radius,5.0)),D.scalar(0.0),D.scalar(0.0))
    case 2: Q.shifted(point,D.scalar(F32.neg(F32.add(radius,5.0))),D.scalar(0.0),D.scalar(0.0))
    case 3: Q.shifted(point,D.scalar(0.0),D.scalar(0.0),D.scalar(F32.div(F32.add(radius,5.0),0.78)))
    case 4: Q.shifted(point,D.scalar(0.0),D.scalar(0.0),D.scalar(F32.neg(F32.div(F32.add(radius,5.0),0.78))))
    case _: point

@unsafe
def bank(value: LC.Candidate,which: U32,+config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,layout: LD.Layout) -> U32:
  match value config layout:
    case LC.Candidate{True{},_,_,point,radius,_,True{},_,_,_,_} _ LD.Layout{_,_,_,_,low,high}:
      CV.word(D.axis(CV.global_height(config,density,model,programs,probe(point,radius,which),low,high)))
    case _ S.Config{minimum,_,_,_,_,_} _: minimum

@unsafe
def banks(+n: U32,+base: U32,+values: Q.Table<LC.Candidate>,+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+layout: LD.Layout) -> Q.Table<U32>:
  match n:
    case 1: Q.Leaf{bank(Q.get(LC.Candidate,values,U32.div(base,5),empty_candidate()),U32.mod(base,5),config,density,model,programs,layout)}
    case _:
      +half = U32.div(n,2)
      a b = banks(half,base,values,config,density,model,programs,layout) banks(U32.sub(n,half),U32.add(base,half),values,config,density,model,programs,layout)
      Q.Branch{n,a,b}

@unsafe
def five(+n: U32,+base: U32,+values: Q.Table<U32>) -> Q.Table<U32>:
  match n:
    case 1: Q.Leaf{Q.get(U32,values,base,0)}
    case _: Q.Branch{n,five(U32.div(n,2),base,values),five(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),values)}

def minimum(values: Q.Table<U32>) -> D.Number:
  match values:
    case Q.Empty{}: D.integer(2147483647)
    case Q.Leaf{x}: D.integer(x)
    case Q.Branch{_,a,b}: D.minimum(minimum(a),minimum(b))

@unsafe
def reduced(value: LC.Candidate,+banks: Q.Table<U32>,+floor: U32) -> LD.Basin:
  match value:
    case LC.Candidate{present,_,_,point,radius,biome,eligible,lava,barrier,flags,_}:
      LD.Basin{point,radius,CV.word(D.axis(D.maximum(D.integer(floor),D.sub(minimum(banks),D.scalar(2.0))))),banks,biome,Bool.and(present,eligible),lava,barrier,flags}

@unsafe
def basins(+n: U32,+base: U32,+values: Q.Table<LC.Candidate>,+banks: Q.Table<U32>,+floor: U32) -> Q.Table<LD.Basin>:
  match n:
    case 1: Q.Leaf{reduced(Q.get(LC.Candidate,values,base,empty_candidate()),five(5,U32.mul(base,5),banks),floor)}
    case _:
      +half = U32.div(n,2)
      a b = basins(half,base,values,banks,floor) basins(U32.sub(n,half),U32.add(base,half),values,banks,floor)
      Q.Branch{n,a,b}

def halo(tile: S.Tile) -> S.Tile:
  match tile:
    case S.Tile{x,z,width,depth,low,high}: S.Tile{U32.sub(x,1),U32.sub(z,1),U32.add(width,2),U32.add(depth,2),low,high}

@unsafe
def flag_biome(choice: C.Choice,biomes: Q.Table<G.Biome>) -> U32:
  match choice:
    case C.Missing{}: 4294967295
    case C.Choice{_,biome,_,_}: biome_flags(Q.get(G.Biome,biomes,biome,G.empty_biome()))

def biome_flags(biome: G.Biome) -> U32:
  match biome:
    case G.Biome{_,LD.Params{_,_,_,_,flags}}: flags

@unsafe
def flags(+n: U32,+base: U32,+tile: S.Tile,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+biomes: Q.Table<G.Biome>) -> Q.Table<U32>:
  match n tile:
    case 1 S.Tile{_,_,_,_,low,high}: Q.Leaf{flag_biome(C.lookup(climate,C.Query{S.axes(Q.run_index(model,programs,0,S.point(tile,base),low,high)),False{},False{},False{},False{}}),biomes)}
    case _ _:
      +half = U32.div(n,2)
      a b = flags(half,base,tile,model,programs,climate,biomes) flags(U32.sub(n,half),U32.add(base,half),tile,model,programs,climate,biomes)
      Q.Branch{n,a,b}

def floor(config: S.Config) -> U32:
  match config:
    case S.Config{minimum,_,_,_,_,_}: minimum

@unsafe
def execute(+layout: LD.Layout,+tile: S.Tile,+config: S.Config,density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+biomes: Q.Table<G.Biome>) -> Computed:
  +candidates : Q.Table<LC.Candidate> = candidates!(size(layout),0,layout,model,programs,climate,biomes)
  banks : Q.Table<U32> = banks!(U32.mul(size(layout),5),0,candidates,config,density,model,programs,layout)
  basins : Q.Table<LD.Basin> = basins!(size(layout),0,candidates,banks,floor(config))
  +expanded = halo(tile)
  flags : Q.Table<U32> = flags!(S.total(expanded),0,expanded,model,programs,climate,biomes)
  Computed{LD.Cache{packed(U32.add(U32.add(10,U32.mul(size(layout),16)),S.total(expanded)),0,layout,expanded,basins,flags)}}

# A compact resident word buffer keeps the persistent state's captured arity
# below the stock compiler limit. This is Bend metadata packing, not JNI work.
@unsafe
def packed(+n: U32,+base: U32,+layout: LD.Layout,+tile: S.Tile,+basins: Q.Table<LD.Basin>,+flags: Q.Table<U32>) -> Q.Table<U32>:
  match n:
    case 1: Q.Leaf{packed_word(base,layout,tile,basins,flags)}
    case _: Q.Branch{n,packed(U32.div(n,2),base,layout,tile,basins,flags),packed(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),layout,tile,basins,flags)}

def header(layout: LD.Layout,i: U32) -> U32:
  match layout i:
    case LD.Layout{x,_,_,_,_,_} 0: x
    case LD.Layout{_,z,_,_,_,_} 1: z
    case LD.Layout{_,_,nx,_,_,_} 2: nx
    case LD.Layout{_,_,_,nz,_,_} 3: nz
    case LD.Layout{_,_,_,_,low,_} 4: low
    case LD.Layout{_,_,_,_,_,high} _: high

def tile_word(tile: S.Tile,i: U32) -> U32:
  match tile i:
    case S.Tile{x,_,_,_,_,_} 6: x
    case S.Tile{_,z,_,_,_,_} 7: z
    case S.Tile{_,_,width,_,_,_} 8: width
    case S.Tile{_,_,_,depth,_,_} _: depth

@unsafe
def packed_word(+i: U32,+layout: LD.Layout,tile: S.Tile,basins: Q.Table<LD.Basin>,flags: Q.Table<U32>) -> U32:
  packed_kind(U32.is_lt(i,10),i,layout,tile,basins,flags)

@unsafe
def packed_kind(is_header: Bool,+i: U32,+layout: LD.Layout,tile: S.Tile,basins: Q.Table<LD.Basin>,flags: Q.Table<U32>) -> U32:
  match is_header:
    case True{}: packed_header(U32.is_lt(i,6),i,layout,tile)
    case False{}: packed_body(U32.is_lt(i,U32.add(10,U32.mul(size(layout),16))),U32.sub(i,10),layout,basins,flags)

def packed_header(cell: Bool,i: U32,layout: LD.Layout,tile: S.Tile) -> U32:
  match cell:
    case True{}: header(layout,i)
    case False{}: tile_word(tile,i)

@unsafe
def packed_body(is_basin: Bool,+i: U32,layout: LD.Layout,basins: Q.Table<LD.Basin>,flags: Q.Table<U32>) -> U32:
  match is_basin:
    case True{}: basin_word(Q.get(LD.Basin,basins,U32.div(i,16),LD.empty_basin()),U32.mod(i,16))
    case False{}: Q.get(U32,flags,U32.sub(i,U32.mul(size(layout),16)),4294967295)

def number_word(value: D.Number,i: U32) -> U32:
  match value i:
    case D.Number{high,_} 0: F32.bits(high)
    case D.Number{_,low} _: F32.bits(low)

@unsafe
def basin_word(basin: LD.Basin,i: U32) -> U32:
  match basin i:
    case LD.Basin{Q.Point{x,_,_},_,_,_,_,_,_,_,_} 0: number_word(x,0)
    case LD.Basin{Q.Point{x,_,_},_,_,_,_,_,_,_,_} 1: number_word(x,1)
    case LD.Basin{Q.Point{_,_,z},_,_,_,_,_,_,_,_} 2: number_word(z,0)
    case LD.Basin{Q.Point{_,_,z},_,_,_,_,_,_,_,_} 3: number_word(z,1)
    case LD.Basin{_,radius,_,_,_,_,_,_,_} 4: F32.bits(radius)
    case LD.Basin{_,_,level,_,_,_,_,_,_} 5: level
    case LD.Basin{_,_,_,_,biome,_,_,_,_} 11: biome
    case LD.Basin{_,_,_,_,_,eligible,_,_,_} 12: N.word(eligible,1,0)
    case LD.Basin{_,_,_,_,_,_,lava,_,_} 13: N.word(lava,1,0)
    case LD.Basin{_,_,_,_,_,_,_,barrier,_} 14: barrier
    case LD.Basin{_,_,_,_,_,_,_,_,flags} 15: flags
    case LD.Basin{_,_,_,banks,_,_,_,_,_} _: Q.get(U32,banks,U32.sub(i,6),0)

@unsafe
def cache_layout(cache: LD.Cache) -> LD.Layout:
  match cache:
    case LD.Cache{+words}: LD.Layout{Q.get(U32,words,0,0),Q.get(U32,words,1,0),Q.get(U32,words,2,0),Q.get(U32,words,3,0),Q.get(U32,words,4,0),Q.get(U32,words,5,0)}

@unsafe
def basin(cache: LD.Cache,+i: U32) -> LD.Basin:
  match cache:
    case LD.Cache{+words}: unpacked(words,U32.add(10,U32.mul(i,16)))

@unsafe
def unpacked(+words: Q.Table<U32>,+base: U32) -> LD.Basin:
  LD.Basin{Q.Point{D.Number{R.float(Q.get(U32,words,base,0)),R.float(Q.get(U32,words,U32.inc(base),0))},D.scalar(0.0),
    D.Number{R.float(Q.get(U32,words,U32.add(base,2),0)),R.float(Q.get(U32,words,U32.add(base,3),0))}},
    R.float(Q.get(U32,words,U32.add(base,4),0)),Q.get(U32,words,U32.add(base,5),0),five(5,U32.add(base,6),words),
    Q.get(U32,words,U32.add(base,11),0),U32.is_ne(Q.get(U32,words,U32.add(base,12),0),0),U32.is_ne(Q.get(U32,words,U32.add(base,13),0),0),
    Q.get(U32,words,U32.add(base,14),0),Q.get(U32,words,U32.add(base,15),0)}

@unsafe
def lookup(+cache: LD.Cache,x: U32,z: U32,low: U32,high: U32) -> Maybe<&2,LD.Basin>:
  lookup_layout(cache_layout(cache),cache,x,z,low,high)

@unsafe
def lookup_layout(layout: LD.Layout,cache: LD.Cache,x: U32,z: U32,low: U32,high: U32) -> Maybe<&2,LD.Basin>:
  match layout:
    case LD.Layout{cx,cz,+nx,+nz,lo,hi}:
      relative(Bool.and(U32.is_eq(low,lo),U32.is_eq(high,hi)),D.sub(D.integer(x),D.integer(cx)),D.sub(D.integer(z),D.integer(cz)),nx,nz,cache)

@unsafe
def relative(ok: Bool,+x: D.Number,+z: D.Number,+nx: U32,nz: U32,cache: LD.Cache) -> Maybe<&2,LD.Basin>:
  inside(Bool.and(ok,Bool.and(Bool.and(Bool.not(D.less(x,D.scalar(0.0))),D.less(x,D.unsigned(nx))),
    Bool.and(Bool.not(D.less(z,D.scalar(0.0))),D.less(z,D.unsigned(nz))))),cache,U32.add(F32.to_u32(D.value(x)),U32.mul(nx,F32.to_u32(D.value(z)))))

@unsafe
def inside(ok: Bool,cache: LD.Cache,index: U32) -> Maybe<&2,LD.Basin>:
  match ok:
    case True{}: Some{basin(cache,index)}
    case False{}: None{}

@unsafe
def query(cache: LD.Cache,value: S.Query) -> Maybe<&2,LD.Basin>:
  match value:
    case S.Query{x,z,low,high}: lookup(cache,LC.cell(x),LC.cell(z),low,high)

@unsafe
def rows(+cache: LD.Cache,queries: Array<S.Query>) -> Array<Maybe<&2,LD.Basin>>:
  match queries:
    case ALeaf{q}: ALeaf{query(cache,q)}
    case ANode{a,b}:
      a b = rows(cache,a) rows(cache,b)
      ANode{a,b}

def queries(cache: LD.Cache,input: Array<S.Query>) -> Rows:
  values : Array<Maybe<&2,LD.Basin>> = rows!(cache,input)
  Rows{values}

@unsafe
def encoded_banks(+n: U32,+i: U32,+banks: Q.Table<U32>,writer: B.Writer) -> B.Writer:
  match n:
    case 0: writer
    case _: encoded_banks(U32.sub(n,1),U32.inc(i),banks,B.int(writer,Q.get(U32,banks,i,0)))

@unsafe
def encoded_row(value: Maybe<&2,LD.Basin>,writer: B.Writer) -> B.Writer:
  match value:
    case None{}: B.int(B.int(B.int(B.int(B.int(encoded_banks(5,0,Q.Empty{},B.int(B.int(writer,0),0)),0),0),0),0),0)
    case Some{LD.Basin{_,radius,level,banks,biome,eligible,lava,barrier,_}}:
      B.int(B.int(B.int(B.int(B.int(encoded_banks(5,0,banks,B.int(B.int(writer,1),level)),biome),N.word(eligible,1,0)),N.word(lava,1,0)),barrier),F32.bits(radius))

@unsafe
def encoded(+n: U32,+i: U32,values: Array<Maybe<&2,LD.Basin>>,writer: B.Writer) -> B.Writer:
  match n:
    case 0: writer
    case _: next(Array.get(Maybe<&2,LD.Basin>,values,i),n,i,writer)

def next(pair: Array<Maybe<&2,LD.Basin>> & Maybe<&2,LD.Basin>,n: U32,i: U32,writer: B.Writer) -> B.Writer:
  (values,value) = pair
  encoded(U32.sub(n,1),U32.inc(i),values,encoded_row(value,writer))

# Finalized per-column biome flags include the one-column slope halo. The
# climate lookup is identical to S.row, including missing-target rejection.
@unsafe
def column_flags(cache: LD.Cache,point: Q.Point) -> U32:
  match cache point:
    case LD.Cache{+words} Q.Point{px,_,pz}:
      flags_relative(D.sub(px,D.integer(Q.get(U32,words,6,0))),D.sub(pz,D.integer(Q.get(U32,words,7,0))),
        Q.get(U32,words,8,0),Q.get(U32,words,9,0),U32.add(10,U32.mul(16,U32.mul(Q.get(U32,words,2,0),Q.get(U32,words,3,0)))),words)

@unsafe
def flags_relative(+x: D.Number,+z: D.Number,+width: U32,+depth: U32,base: U32,words: Q.Table<U32>) -> U32:
  flags_inside(Bool.and(Bool.and(Bool.not(D.less(x,D.scalar(0.0))),D.less(x,D.unsigned(width))),
    Bool.and(Bool.not(D.less(z,D.scalar(0.0))),D.less(z,D.unsigned(depth)))),x,z,width,base,words)

@unsafe
def flags_inside(ok: Bool,x: D.Number,z: D.Number,width: U32,base: U32,words: Q.Table<U32>) -> U32:
  match ok:
    case False{}: 4294967295
    case True{}: Q.get(U32,words,U32.add(base,U32.add(F32.to_u32(D.value(x)),U32.mul(width,F32.to_u32(D.value(z))))),4294967295)
