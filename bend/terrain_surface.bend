import Base
import ./density.bend as Q
import ./density_lattice.bend as L
import ./precise.bend as D
import ./climate.bend as C
import ./registry.bend as P
import ./reader.bend as R
import ./binary.bend as B
import ./compression.bend as Z

# One immutable, bounded surface tile. Density values never leave the worker:
# the GPU interpolates each X/Z column before finding its highest solid interval.
# Heights are first-free continuous Y values, not interpolated corner heights.
type Config is Data:
  Config{minimum: U32,height: U32,sea: U32,horizontal: U32,vertical: U32,explicit: Bool}
type Tile is Data:
  Tile{x: U32,z: U32,width: U32,depth: U32,low: U32,high: U32}
type Row is Data:
  Row{height: F32,climate: C.Axes,biome: U32}
type Grid is Data:
  Grid{tile: Tile,values: Q.Table<Row>}
type State is Data:
  State{config: Config,columns: Maybe<&2,Grid>}
type Computed is Type:
  Computed{model: Q.Model,programs: Q.Table<Q.Program>,density: L.Grid,climate: C.Model,columns: Grid}
type Query is Data:
  Query{x: U32,z: U32,low: U32,high: U32}
type Input is Type:
  Input{queries: Array<Query>,count: U32,valid: Bool}

def empty() -> Row:
  Row{0.0,C.Axes{0.0,0.0,0.0,0.0,0.0,0.0},4294967295}

def total(+tile: Tile) -> U32:
  match tile:
    case Tile{_,_,+width,+depth,_,_}: U32.mul(width,depth)

def bounds(+config: Config) -> D.Number & D.Number:
  match config:
    case Config{+minimum,+height,_,_,_,_}: (D.integer(minimum),D.add(D.integer(minimum),D.unsigned(height)))

@unsafe
def tile_valid(+ok: Bool,+tile: Tile) -> Result<&1,&1,U32,Tile>:
  match ok:
    case False{}: Fail{720}
    case True{}: Done{tile}

@unsafe
def tile_shape(+valid: Bool,+tile: Tile) -> Result<&1,&1,U32,Tile>:
  match tile:
    case Tile{+x,+z,+width,+depth,_,_}:
      tile_valid(Bool.and(valid,Bool.and(Bool.and(U32.is_ge(width,1),U32.is_le(width,1024)),
        Bool.and(Bool.and(U32.is_ge(depth,1),U32.is_le(depth,1024)),
          Bool.and(Bool.not(D.less(D.integer(2147483647),D.add(D.integer(x),D.unsigned(U32.sub(width,1))))),
            Bool.not(D.less(D.integer(2147483647),D.add(D.integer(z),D.unsigned(U32.sub(depth,1))))))))),tile)

@unsafe
def decode(bytes: Z.Bytes) -> Result<&1,&1,U32,Tile>:
  R.word(Result<&1,&1,U32,Tile>,R.start(bytes),cursor => x => R.word(Result<&1,&1,U32,Tile>,cursor,cursor => z =>
    R.word(Result<&1,&1,U32,Tile>,cursor,cursor => width => R.word(Result<&1,&1,U32,Tile>,cursor,cursor => depth =>
      R.word(Result<&1,&1,U32,Tile>,cursor,cursor => low => R.word(Result<&1,&1,U32,Tile>,cursor,cursor => high =>
        tile_shape(R.end(cursor),Tile{x,z,width,depth,low,high})))))))

def point(+tile: Tile,+i: U32) -> Q.Point:
  match tile:
    case Tile{+x,+z,+width,_,_,_}: Q.shifted(Q.world(x,0,z),D.unsigned(U32.mod(i,width)),D.scalar(0.0),D.unsigned(U32.div(i,width)))

# The bridge can request the exact globally aligned density descriptor. This is
# metadata only; all profile interpretation and coordinate math remain Bend.
def descriptor(+config: Config,+tile: Tile) -> B.Writer:
  match config tile:
    case Config{+minimum,+height,_,+h,+v,False{}} Tile{+x,+z,+width,+depth,+lo,+hi}:
      B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.new(),1),x),minimum),z),
        U32.add(x,U32.sub(width,1))),U32.add(minimum,height)),U32.add(z,U32.sub(depth,1))),h),v),lo),hi)
    case Config{_,_,_,+h,+v,True{}} Tile{+x,+z,+width,+depth,+lo,+hi}:
      B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.new(),1),x),0),z),
        U32.add(x,U32.sub(width,1))),0),U32.add(z,U32.sub(depth,1))),h),v),lo),hi)

def y_bounds(+config: Config) -> D.Number & D.Number:
  match config:
    case Config{_,_,_,_,_,True{}}: (D.scalar(0.0),D.scalar(0.0))
    case _: bounds(config)

@unsafe
def covers_y(pair: D.Number & D.Number,+origin: Q.Point,+ny: U32,+v: U32) -> Bool:
  (low,high) = pair
  match origin:
    case Q.Point{_,+y,_}: Bool.and(Bool.not(D.less(low,y)),Bool.not(D.less(D.add(y,D.unsigned(U32.mul(U32.sub(ny,1),v))),high)))

def covered_xz(+tile: Tile,+origin: Q.Point,+nx: U32,+nz: U32,+h: U32) -> Bool:
  match tile origin:
    case Tile{+x,+z,+width,+depth,_,_} Q.Point{+ox,_,+oz}:
      Bool.and(Bool.and(Bool.not(D.less(D.integer(x),ox)),Bool.not(D.less(D.integer(z),oz))),
        Bool.and(Bool.not(D.less(D.add(ox,D.unsigned(U32.mul(U32.sub(nx,1),h))),D.add(D.integer(x),D.unsigned(U32.sub(width,1))))),
          Bool.not(D.less(D.add(oz,D.unsigned(U32.mul(U32.sub(nz,1),h))),D.add(D.integer(z),D.unsigned(U32.sub(depth,1)))))))

def compatible(+config: Config,+tile: Tile,+grid: L.Grid) -> Bool:
  match config tile grid:
    case Config{_,_,_,+ch,+cv,_} Tile{_,_,_,_,+lo,+hi} L.Grid{L.Layout{+origin,+nx,+ny,+nz,+h,+v,+program,+glo,+ghi},_}:
      Bool.and(U32.is_eq(program,1),Bool.and(Bool.and(U32.is_eq(lo,glo),U32.is_eq(hi,ghi)),
        Bool.and(Bool.and(U32.is_eq(h,ch),U32.is_eq(v,cv)),
          Bool.and(covered_xz(tile,origin,nx,nz,h),covers_y(y_bounds(config),origin,ny,v)))))

def first(+value: Q.Outputs) -> D.Number:
  match value:
    case Q.Outputs{+a,_,_,_,_,_}: a

# All indices below were checked once in compatible(). The inclusive upper X/Z
# endpoints use the final cell with fraction one, matching lattice queries.
def layer(+values: Q.Table<Q.Outputs>,+ix: U32,+iz: U32,+nx: U32,+ny: U32,+y: U32,+tx: D.Number,+tz: D.Number) -> D.Number:
  +i = U32.add(ix,U32.mul(nx,U32.add(y,U32.mul(ny,iz))))
  D.mix(D.mix(first(L.value_at(values,i)),first(L.value_at(values,U32.inc(i))),tx),
    D.mix(first(L.value_at(values,U32.add(i,U32.mul(nx,ny)))),first(L.value_at(values,U32.inc(U32.add(i,U32.mul(nx,ny))))),tx),tz)

@unsafe
def descend(+n: U32,+y: D.Number,+upper_y: D.Number,+upper: D.Number,+minimum: D.Number,+maximum: D.Number,
    +values: Q.Table<Q.Outputs>,+ix: U32,+iz: U32,+nx: U32,+ny: U32,+v: U32,+tx: D.Number,+tz: D.Number) -> D.Number:
  match n:
    case 0: D.add(minimum,D.scalar(1.0))
    case _:
      +lower = layer(values,ix,iz,nx,ny,U32.sub(n,1),tx,tz)
      crossing(D.less(D.scalar(0.0),lower),n,y,lower,upper_y,upper,minimum,maximum,values,ix,iz,nx,ny,v,tx,tz)

@unsafe
def crossing(+solid: Bool,+n: U32,+y: D.Number,+lower: D.Number,+upper_y: D.Number,+upper: D.Number,+minimum: D.Number,+maximum: D.Number,
    +values: Q.Table<Q.Outputs>,+ix: U32,+iz: U32,+nx: U32,+ny: U32,+v: U32,+tx: D.Number,+tz: D.Number) -> D.Number:
  match solid:
    case True{}: D.minimum(maximum,D.maximum(D.add(minimum,D.scalar(1.0)),D.add(D.add(y,
      D.divide(D.multiply(D.sub(upper_y,y),lower),D.maximum(D.sub(lower,upper),D.scalar(0.000001)))),D.scalar(1.0))))
    case False{}: descend(U32.sub(n,1),D.sub(y,D.unsigned(v)),y,lower,minimum,maximum,values,ix,iz,nx,ny,v,tx,tz)

@unsafe
def top_solid(+solid: Bool,+upper: D.Number,+upper_y: D.Number,+top: U32,+origin_y: D.Number,+minimum: D.Number,+maximum: D.Number,
    +values: Q.Table<Q.Outputs>,+ix: U32,+iz: U32,+nx: U32,+ny: U32,+v: U32,+tx: D.Number,+tz: D.Number) -> D.Number:
  match solid:
    case True{}: maximum
    case False{}: descend(top,D.add(origin_y,D.unsigned(U32.mul(U32.sub(top,1),v))),upper_y,upper,minimum,maximum,values,ix,iz,nx,ny,v,tx,tz)

@unsafe
def top_density(pair: D.Number & D.Number,+top: U32,+origin_y: D.Number,+values: Q.Table<Q.Outputs>,+ix: U32,+iz: U32,+nx: U32,+ny: U32,+v: U32,+tx: D.Number,+tz: D.Number) -> D.Number:
  (minimum,+maximum) = pair
  +y = D.add(origin_y,D.unsigned(U32.mul(top,v)))
  +below = layer(values,ix,iz,nx,ny,U32.sub(top,1),tx,tz)
  +above = layer(values,ix,iz,nx,ny,top,tx,tz)
  +upper = D.mix(below,above,D.divide(D.sub(maximum,D.sub(y,D.unsigned(v))),D.unsigned(v)))
  top_solid(D.less(D.scalar(0.0),upper),upper,maximum,top,origin_y,minimum,maximum,values,ix,iz,nx,ny,v,tx,tz)

@unsafe
def density_height(pair: D.Number & D.Number,+relative: Q.Point,+origin: Q.Point,+nx: U32,+ny: U32,+nz: U32,+values: Q.Table<Q.Outputs>,+v: U32) -> D.Number:
  match relative origin:
    case Q.Point{+x,_,+z} Q.Point{_,+oy,_}:
      +ix = U32.min(F32.to_u32(D.value(D.floor(x))),U32.sub(nx,2))
      +iz = U32.min(F32.to_u32(D.value(D.floor(z))),U32.sub(nz,2))
      density_top(pair,oy,values,ix,iz,nx,ny,v,D.sub(x,D.unsigned(ix)),D.sub(z,D.unsigned(iz)))

@unsafe
def density_top(pair: D.Number & D.Number,+oy: D.Number,+values: Q.Table<Q.Outputs>,+ix: U32,+iz: U32,+nx: U32,+ny: U32,+v: U32,+tx: D.Number,+tz: D.Number) -> D.Number:
  (+minimum,+maximum) = pair
  top_density((minimum,maximum),U32.max(1,F32.to_u32(F32.ceil(D.value(D.divide(D.sub(maximum,oy),D.unsigned(v)))))),oy,values,ix,iz,nx,ny,v,tx,tz)

@unsafe
def height(+config: Config,+point: Q.Point,+grid: L.Grid) -> D.Number:
  match config grid:
    case Config{_,_,_,_,_,False{}} L.Grid{L.Layout{+origin,+nx,+ny,+nz,+h,+v,_,_,_},+values}:
      density_height(bounds(config),L.relative(point,origin,h,v),origin,nx,ny,nz,values,v)
    case Config{_,_,_,_,_,True{}} L.Grid{L.Layout{+origin,+nx,+ny,+nz,+h,+v,_,_,_},+values}:
      explicit_height(bounds(config),L.relative(point,origin,h,v),nx,ny,nz,values)

@unsafe
def explicit_height(pair: D.Number & D.Number,+relative: Q.Point,+nx: U32,+ny: U32,+nz: U32,+values: Q.Table<Q.Outputs>) -> D.Number:
  (minimum,maximum) = pair
  match relative:
    case Q.Point{+x,+y,+z}:
      +ix = U32.min(F32.to_u32(D.value(D.floor(x))),U32.sub(nx,2))
      +iy = U32.min(F32.to_u32(D.value(D.floor(y))),U32.sub(ny,2))
      +iz = U32.min(F32.to_u32(D.value(D.floor(z))),U32.sub(nz,2))
      D.minimum(maximum,D.maximum(D.add(minimum,D.scalar(1.0)),D.add(D.scalar(1.0),first(L.interpolate(values,
        U32.add(ix,U32.mul(nx,U32.add(iy,U32.mul(ny,iz)))),nx,U32.mul(nx,ny),D.sub(x,D.unsigned(ix)),D.sub(y,D.unsigned(iy)),D.sub(z,D.unsigned(iz)))))))

def axes(+output: Q.Outputs) -> C.Axes:
  match output:
    case Q.Outputs{+a,+b,+c,+d,+e,+f}: C.Axes{D.value(a),D.value(b),D.value(c),D.value(d),D.value(e),D.value(f)}

def biome(+choice: C.Choice) -> U32:
  match choice:
    case C.Missing{}: 4294967295
    case C.Choice{_,+biome,_,_}: biome

@unsafe
def row(+config: Config,+tile: Tile,+grid: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+i: U32) -> Row:
  match tile:
    case Tile{_,_,_,_,+low,+high}: sampled(height(config,point(tile,i),grid),axes(Q.run_index(model,programs,0,point(tile,i),low,high)),climate)

def sampled(+height: D.Number,+point: C.Axes,+climate: C.Model) -> Row:
  Row{D.value(height),point,biome(C.lookup(climate,C.Query{point,False{},False{},False{},False{}}))}

@unsafe
def columns(+limit: U32,+n: U32,+base: U32,+config: Config,+tile: Tile,+grid: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model) -> Q.Table<Row>:
  match n:
    case 1: Q.Leaf{row(config,tile,grid,model,programs,climate,base)}
    case _: column_split(U32.is_le(n,limit),limit,n,base,config,tile,grid,model,programs,climate)

@unsafe
def column_split(+serial: Bool,+limit: U32,+n: U32,+base: U32,+config: Config,+tile: Tile,+grid: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model) -> Q.Table<Row>:
  match serial:
    case True{}: Q.Branch{n,columns(limit,U32.div(n,2),base,config,tile,grid,model,programs,climate),
      columns(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),config,tile,grid,model,programs,climate)}
    case False{}:
      +half = U32.div(n,2)
      a b = columns(limit,half,base,config,tile,grid,model,programs,climate)
        columns(limit,U32.sub(n,half),U32.add(base,half),config,tile,grid,model,programs,climate)
      Q.Branch{n,a,b}

def execute(+config: Config,+tile: Tile,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model) -> Computed:
  values : Q.Table<Row> = columns!(U32.max(1,U32.div(U32.add(total(tile),16383),16384)),total(tile),0,config,tile,density,model,programs,climate)
  Computed{model,programs,density,climate,Grid{tile,values}}

@unsafe
def queries(n: Nat,cursor: R.Cursor,a: Array<Query>,+i: U32,+count: U32) -> Input:
  match n:
    case 0n: Input{a,count,R.end(cursor)}
    case 1n+p: R.word(Input,cursor,cursor => x => R.word(Input,cursor,cursor => z =>
      R.word(Input,cursor,cursor => low => R.word(Input,cursor,cursor => high =>
        queries(p,cursor,Array.set(Query,a,i,Query{x,z,low,high}),U32.inc(i),count)))))

def query_count(+ok: Bool,cursor: R.Cursor,+count: U32) -> Input:
  match ok:
    case False{}: Input{[Query{0,0,0,0}:Query*1n],0,False{}}
    case True{}: queries(U32.to_nat(count),cursor,Array.new(Query,P.capacity(count,1,0n),Query{0,0,0,0}),0,count)

def query_decode(bytes: Z.Bytes) -> Input:
  R.word(Input,R.start(bytes),cursor => +count => query_count(U32.is_le(count,4096),cursor,count))

@unsafe
def lookup(+grid: Grid,query: Query) -> Maybe<&2,Row>:
  match grid query:
    case Grid{Tile{+ox,+oz,+width,+depth,+lo,+hi},+values} Query{+x,+z,+qlo,+qhi}:
      +dx = D.sub(D.integer(x),D.integer(ox))
      +dz = D.sub(D.integer(z),D.integer(oz))
      selected(Bool.and(Bool.and(U32.is_eq(lo,qlo),U32.is_eq(hi,qhi)),
        Bool.and(L.contains(dx,width),L.contains(dz,depth))),values,dx,dz,width)

@unsafe
def selected(+ok: Bool,+values: Q.Table<Row>,+dx: D.Number,+dz: D.Number,+width: U32) -> Maybe<&2,Row>:
  match ok:
    case False{}: None{}
    case True{}: Some{Q.get(Row,values,U32.add(F32.to_u32(D.value(dx)),U32.mul(width,F32.to_u32(D.value(dz)))),empty())}

@unsafe
def encode(n: Nat,a: Array<Query>,+grid: Grid,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: encoded(p,Array.get(Query,a,i),grid,w,i)

@unsafe
def encoded(n: Nat,pair: Array<Query> & Query,+grid: Grid,w: B.Writer,+i: U32) -> B.Writer:
  (a,query) = pair
  encode_row(n,a,grid,lookup(grid,query),w,i)

@unsafe
def encode_row(n: Nat,a: Array<Query>,+grid: Grid,found: Maybe<&2,Row>,w: B.Writer,+i: U32) -> B.Writer:
  match found:
    case None{}: encode(n,a,grid,B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(w,0),0),0),0),0),0),0),0),4294967295),U32.inc(i))
    case Some{Row{+height,C.Axes{+a0,+a1,+a2,+a3,+a4,+a5},+biome}}:
      encode(n,a,grid,B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(w,1),F32.bits(height)),F32.bits(a0)),
        F32.bits(a1)),F32.bits(a2)),F32.bits(a3)),F32.bits(a4)),F32.bits(a5)),biome),U32.inc(i))

# Replacing the numeric lattice drops only its dependent column cache.
def clear(state: Maybe<&2,State>) -> Maybe<&2,State>:
  match state:
    case None{}: None{}
    case Some{State{+config,_}}: Some{State{config,None{}}}

def config_ack(+config: Config) -> B.Writer:
  match config:
    case Config{+minimum,+height,+sea,+h,+v,+explicit}:
      B.int(B.int(B.int(B.int(B.int(B.int(B.new(),minimum),height),sea),h),v),Z.word(explicit,1,0))
