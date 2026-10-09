import Base
import ./ore_spatial.bend as P
import ./ore_raster.bend as R
import ./ore_geometry.bend as G
import ./ore_data.bend as O
import ./ore_random.bend as Random
import ./word64.bend as W
import ./density.bend as Q
import ./terrain_surface.bend as S
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./cave_lattice.bend as CV
import ./lake_terrain.bend as LT
import ./precise.bend as D
import ./reader.bend as Reader
import ./cave_carve.bend as Carve

type Computed is Type:
  Computed{grid: MD.Grid}

# The source contains a one-block exposure halo. Only requested core columns
# change. Exposure tests use the immutable pre-ore snapshot, like Rust.
@unsafe
def in_world(+value: D.Number) -> Bool:
  Bool.and(Bool.not(D.less(value,D.integer(2147483648))),Bool.not(D.less(D.integer(2147483647),value)))

@unsafe
def neighbor_air(+ok: Bool,+source: MD.Grid,+point: Q.Point,+low: U32,+high: U32) -> Bool:
  match ok point:
    case False{} _: True{}
    case True{} Q.Point{x,y,z}: air(MC.voxel(source,MC.Query{P.coord(x),P.coord(y),P.coord(z),low,high}))

def air(+material: U32) -> Bool:
  Bool.or(U32.is_eq(material,0),U32.is_eq(material,4294967295))

@unsafe
def air_at(+source: MD.Grid,+point: Q.Point,+low: U32,+high: U32) -> Bool:
  match point:
    case Q.Point{+x,+y,+z}: neighbor_air(Bool.and(in_world(x),Bool.and(in_world(y),in_world(z))),source,point,low,high)

@unsafe
def exposed(+source: MD.Grid,+point: Q.Point,+low: U32,+high: U32) -> Bool:
  Bool.or(air_at(source,Q.shifted(point,D.scalar(1.0),D.scalar(0.0),D.scalar(0.0)),low,high),
    Bool.or(air_at(source,Q.shifted(point,D.scalar(F32.neg(1.0)),D.scalar(0.0),D.scalar(0.0)),low,high),
      Bool.or(air_at(source,Q.shifted(point,D.scalar(0.0),D.scalar(1.0),D.scalar(0.0)),low,high),
        Bool.or(air_at(source,Q.shifted(point,D.scalar(0.0),D.scalar(F32.neg(1.0)),D.scalar(0.0)),low,high),
          Bool.or(air_at(source,Q.shifted(point,D.scalar(0.0),D.scalar(0.0),D.scalar(1.0)),low,high),
            air_at(source,Q.shifted(point,D.scalar(0.0),D.scalar(0.0),D.scalar(F32.neg(1.0))),low,high))))))

@unsafe
def discard(+test: Bool,+store: O.Store,+id: U32,+y: U32,+host: U32,+random: U32,+source: MD.Grid,+point: Q.Point,+low: U32,+high: U32) -> U32:
  match test:
    case False{}: O.replacement(store,id,y,host,random,False{})
    case True{}: O.replacement(store,id,y,host,random,exposed(source,point,low,high))

@unsafe
def replaced(+id: U32,+offset: U32,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match id:
    case 0: runs
    case _: LT.replace(runs,offset,U32.inc(offset),id,[])

@unsafe
def place(+ok: Bool,+env: P.Env,+point: Q.Point,+id: U32,+random: U32,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match ok env point:
    case False{} _ _: runs
    case True{} P.Env{_,_,_,_,_,+source,+store,S.Tile{_,_,_,_,low,high},_,_} Q.Point{_,+y,_}:
      place_y(source,store,point,id,random,P.coord(y),low,high,runs)

@unsafe
def place_y(+source: MD.Grid,+store: O.Store,+point: Q.Point,+id: U32,+random: U32,+y: U32,+low: U32,+high: U32,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match source:
    case MD.Grid{_,minimum,_,_,_}:
      +offset = U32.sub(y,minimum)
      replaced(discard(F32.is_lt(Random.unit(random),Reader.float(O.word(store,U32.add(O.recipe_at(id),2)))),store,id,y,MC.material_at(runs,offset),random,source,point,low,high),offset,runs)

@unsafe
def within(+point: Q.Point,+minimum: U32,+height: U32) -> Bool:
  match point:
    case Q.Point{+x,+y,+z}: Bool.and(Bool.and(in_world(x),in_world(z)),
      Bool.and(Bool.not(D.less(y,D.integer(minimum))),D.less(y,D.add(D.integer(minimum),D.unsigned(height)))))

@unsafe
def regular_bits(+n: U32,+local: U32,+nx: U32,+nz: U32,+words: Q.Table<U32>,+origin: Q.Point,+env: P.Env,+id: U32,+random: W.U64,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match n:
    case 0: runs
    case _: regular_bit(U32.is_ne(U32.and(Q.get(U32,words,U32.div(local,32),0),U32.shln(1,U32.to_nat(U32.mod(local,32)))),0),
      U32.sub(n,1),local,nx,nz,words,origin,env,id,random,runs)

@unsafe
def regular_bit(+hit: Bool,+n: U32,+local: U32,+nx: U32,+nz: U32,+words: Q.Table<U32>,+origin: Q.Point,+env: P.Env,+id: U32,+random: W.U64,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match hit env:
    case False{} _: regular_bits(n,U32.add(local,U32.mul(nx,nz)),nx,nz,words,origin,env,id,random,runs)
    case True{} P.Env{_,_,_,_,_,MD.Grid{_,minimum,height,_,_},_,_,_,_}:
      +point = Q.shifted(origin,D.unsigned(U32.mod(local,nx)),D.unsigned(U32.div(local,U32.mul(nx,nz))),D.unsigned(U32.mod(U32.div(local,nx),nz)))
      regular_bits(n,U32.add(local,U32.mul(nx,nz)),nx,nz,words,origin,env,id,random,
        place(within(point,minimum,height),env,point,id,Random.mixed(W.xor(random,W.U64{0,local})),runs))

@unsafe
def regular_at(+ok: Bool,+layout: R.Layout,+relative: Q.Point,+origin: Q.Point,+words: Q.Table<U32>,+env: P.Env,+id: U32,+random: W.U64,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match ok layout relative:
    case False{} _ _: runs
    case True{} R.Layout{_,_,_,+nx,ny,+nz} Q.Point{x,_,z}:
      regular_bits(ny,U32.add(F32.to_u32(D.value(x)),U32.mul(F32.to_u32(D.value(z)),nx)),nx,nz,words,origin,env,id,random,runs)

@unsafe
def regular(+layout: R.Layout,+words: Q.Table<U32>,+origin: Q.Point,+point: Q.Point,+env: P.Env,+id: U32,+random: W.U64,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match layout origin point:
    case R.Layout{x,y,z,+nx,_,+nz} Q.Point{a,b,c} Q.Point{px,_,pz}:
      +start : Q.Point = Q.Point{D.add(a,D.integer(x)),D.add(b,D.integer(y)),D.add(c,D.integer(z))}
      relative(layout,words,start,Q.Point{D.sub(px,D.add(a,D.integer(x))),D.scalar(0.0),D.sub(pz,D.add(c,D.integer(z)))},env,id,random,runs)

@unsafe
def relative(+layout: R.Layout,+words: Q.Table<U32>,+origin: Q.Point,+point: Q.Point,+env: P.Env,+id: U32,+random: W.U64,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match layout point:
    case R.Layout{_,_,_,nx,_,nz} Q.Point{+x,_,+z}:
      regular_at(Bool.and(Bool.and(Bool.not(D.less(x,D.scalar(0.0))),D.less(x,D.unsigned(nx))),
        Bool.and(Bool.not(D.less(z,D.scalar(0.0))),D.less(z,D.unsigned(nz)))),layout,point,origin,words,env,id,random,runs)

@unsafe
def scatter(+xs: List<&2,G.Piece>,+origin: Q.Point,+column: Q.Point,+env: P.Env,+id: U32,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match xs:
    case Nil{}: runs
    case G.Piece{x,y,z,random} <> rest:
      scattered(rest,origin,column,env,id,runs,Q.shifted(origin,D.integer(x),D.integer(y),D.integer(z)),random)

@unsafe
def scattered(+rest: List<&2,G.Piece>,+origin: Q.Point,+column: Q.Point,+env: P.Env,+id: U32,+runs: List<&2,MD.Run>,+point: Q.Point,+random: U32) -> List<&2,MD.Run>:
  match column env point:
    case Q.Point{cx,_,cz} P.Env{_,_,_,_,_,MD.Grid{_,minimum,height,_,_},_,_,_,_} Q.Point{+x,_,+z}:
      scatter(rest,origin,column,env,id,place(Bool.and(Bool.and(Bool.not(D.less(x,cx)),Bool.not(D.less(cx,x))),
        Bool.and(Bool.and(Bool.not(D.less(z,cz)),Bool.not(D.less(cz,z))),within(point,minimum,height))),env,point,id,random,runs))

@unsafe
def vein(+value: P.Vein,+point: Q.Point,+env: P.Env,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match value:
    case P.Vein{id,origin,random,R.Mask{layout,words}}: regular(layout,words,origin,point,env,id,random,runs)
    case P.Vein{id,origin,_,R.Scatter{_,points}}: scatter(points,origin,point,env,id,runs)

# Tree traversal is sequential only within one column to preserve overlapping
# recipe and attempt priority. Different columns replay in parallel on the GPU.
@unsafe
def replay(+values: Q.Table<P.Vein>,+point: Q.Point,+env: P.Env,+runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match values:
    case Q.Empty{}: runs
    case Q.Leaf{value}: vein(value,point,env,runs)
    case Q.Branch{_,a,b}: replay(b,point,env,replay(a,point,env,runs))

@unsafe
def column(+column: MD.Column,+bucket: P.Bucket,+point: Q.Point,+env: P.Env) -> MD.Column:
  match column bucket:
    case MD.Column{height,biome,depth,slope,band,secondary,preliminary,runs,_,valid} P.Bucket{veins}:
      completed(replay(veins,point,env,runs),env,biome,depth,slope,band,secondary,preliminary,valid)

@unsafe
def completed(+runs: List<&2,MD.Run>,+env: P.Env,+biome: U32,+depth: F32,+slope: F32,+band: F32,+secondary: F32,+preliminary: F32,+valid: Bool) -> MD.Column:
  match env:
    case P.Env{_,_,_,_,_,MD.Grid{_,minimum,_,_,_},_,_,water,lava}:
      MD.Column{Carve.height(runs,minimum,water,lava,minimum),biome,depth,slope,band,secondary,preliminary,runs,U32.from_nat(List.length(&2,MD.Run,runs)),valid}

@unsafe
def selected(+ok: Bool,+value: MD.Column,+buckets: Q.Table<P.Bucket>,+env: P.Env,+point: Q.Point,+dx: D.Number,+dz: D.Number,+width: U32) -> MD.Column:
  match ok:
    case False{}: value
    case True{}: column(value,Q.get(P.Bucket,buckets,U32.add(U32.div(F32.to_u32(D.value(dx)),16),
      U32.mul(U32.div(width,16),U32.div(F32.to_u32(D.value(dz)),16))),P.empty()),point,env)

@unsafe
def at_column(+i: U32,+values: Q.Table<MD.Column>,+tile: S.Tile,+buckets: Q.Table<P.Bucket>,+env: P.Env) -> MD.Column:
  match env:
    case P.Env{_,_,_,_,_,_,_,S.Tile{+x,+z,+width,+depth,_,_},_,_}:
      column_relative(Q.get(MD.Column,values,i,MC.empty()),buckets,env,S.point(tile,i),x,z,width,depth)

@unsafe
def column_relative(+value: MD.Column,+buckets: Q.Table<P.Bucket>,+env: P.Env,+point: Q.Point,+x: U32,+z: U32,+width: U32,+depth: U32) -> MD.Column:
  match point:
    case Q.Point{px,_,pz}:
      +dx = D.sub(px,D.integer(x))
      +dz = D.sub(pz,D.integer(z))
      selected(Bool.and(Bool.and(Bool.not(D.less(dx,D.scalar(0.0))),D.less(dx,D.unsigned(width))),
        Bool.and(Bool.not(D.less(dz,D.scalar(0.0))),D.less(dz,D.unsigned(depth)))),value,buckets,env,point,dx,dz,width)

@unsafe
def columns(+n: U32,+base: U32,+values: Q.Table<MD.Column>,+tile: S.Tile,+buckets: Q.Table<P.Bucket>,+env: P.Env) -> Q.Table<MD.Column>:
  match n:
    case 1: Q.Leaf{at_column(base,values,tile,buckets,env)}
    case _:
      +half = U32.div(n,2)
      a b = columns(half,base,values,tile,buckets,env) columns(U32.sub(n,half),U32.add(base,half),values,tile,buckets,env)
      Q.Branch{n,a,b}

@unsafe
def calculate(+env: P.Env) -> MD.Grid:
  match env:
    case P.Env{_,_,_,_,_,MD.Grid{+tile,minimum,+height,values,biomes},_,+core,_,_}:
      +layout = P.layout(core)
      +anchors = P.anchors(P.total(layout),0,layout,env)
      +buckets = P.buckets(U32.div(S.total(core),256),0,core,anchors,layout,minimum,height)
      MD.Grid{tile,minimum,height,columns(S.total(tile),0,values,tile,buckets,env),biomes}

@unsafe
def execute(+env: P.Env) -> Computed:
  grid : MD.Grid = calculate!(env)
  Computed{grid}

# A whole-chunk core plus its actual resident exposure halo is required. Halo
# coordinates clamp only to signed-world limits; generated samples never clamp.
@unsafe
def compatible(+core: S.Tile,+source: MD.Grid) -> Bool:
  match core source:
    case S.Tile{+x,+z,+width,+depth,+low,+high} MD.Grid{S.Tile{+ox,+oz,+ow,+od,lo,hi},_,_,_,_}:
      Bool.and(Bool.and(U32.is_eq(U32.and(x,15),0),U32.is_eq(U32.and(z,15),0)),
        Bool.and(Bool.and(U32.is_eq(U32.mod(width,16),0),U32.is_eq(U32.mod(depth,16),0)),
          Bool.and(Bool.and(U32.is_le(width,512),U32.is_le(depth,512)),
            Bool.and(Bool.and(U32.is_eq(low,lo),U32.is_eq(high,hi)),
              Bool.and(Bool.and(Bool.not(D.less(D.maximum(D.integer(2147483648),D.sub(D.integer(x),D.scalar(1.0))),D.integer(ox))),
                Bool.not(D.less(D.maximum(D.integer(2147483648),D.sub(D.integer(z),D.scalar(1.0))),D.integer(oz)))),
                Bool.and(Bool.not(D.less(D.add(D.integer(ox),D.unsigned(ow)),D.minimum(D.add(D.integer(2147483647),D.scalar(1.0)),D.add(D.integer(x),D.unsigned(U32.inc(width)))))),
                  Bool.not(D.less(D.add(D.integer(oz),D.unsigned(od)),D.minimum(D.add(D.integer(2147483647),D.scalar(1.0)),D.add(D.integer(z),D.unsigned(U32.inc(depth))))))))))))
