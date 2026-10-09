import Base
import ./ore_data.bend as O
import ./ore_attempts.bend as A
import ./ore_geometry.bend as G
import ./ore_raster.bend as R
import ./density.bend as Q
import ./density_lattice.bend as L
import ./terrain_surface.bend as S
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./cave_lattice.bend as CV
import ./shoreline.bend as Shore
import ./climate.bend as C
import ./geology.bend as Geo
import ./precise.bend as D
import ./word64.bend as W

# Globally anchored contributions have stable Z/X/recipe/attempt priority.
# All shapes are local: adding an origin uses compensated integer arithmetic.
type Env is Data:
  Env{config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,source: MD.Grid,ores: O.Store,core: S.Tile,water: U32,lava: U32}
type Vein is Data:
  Vein{recipe: U32,origin: Q.Point,random: W.U64,raster: R.Row}
type Bucket is Data:
  Bucket{veins: Q.Table<Vein>}
type Layout is Data:
  Layout{x: U32,z: U32,nx: U32,nz: U32}

def empty() -> Bucket:
  Bucket{Q.Empty{}}

@unsafe
def join(+a: Q.Table<Vein>,+b: Q.Table<Vein>) -> Q.Table<Vein>:
  joined(Q.table_size(Vein,a),Q.table_size(Vein,b),a,b)

@unsafe
def joined(+na: U32,+nb: U32,+a: Q.Table<Vein>,+b: Q.Table<Vein>) -> Q.Table<Vein>:
  match na nb:
    case 0 _: b
    case _ 0: a
    case _ _: Q.Branch{U32.add(na,nb),a,b}

def coord(+value: D.Number) -> U32:
  CV.word(D.axis(value))

@unsafe
def chunk(+value: U32) -> U32:
  coord(D.floor(D.scale(D.integer(value),0.0625)))

@unsafe
def layout(+tile: S.Tile) -> Layout:
  match tile:
    case S.Tile{x,z,width,depth,_,_}: Layout{U32.sub(chunk(x),1),U32.sub(chunk(z),1),U32.add(U32.div(width,16),2),U32.add(U32.div(depth,16),2)}

def total(+layout: Layout) -> U32:
  match layout:
    case Layout{_,_,nx,nz}: U32.mul(nx,nz)

@unsafe
def overlaps(+a: D.Number,+an: U32,+b: D.Number,+bn: U32) -> Bool:
  Bool.and(D.less(a,D.add(b,D.unsigned(bn))),D.less(b,D.add(a,D.unsigned(an))))

@unsafe
def bounds(+layout: R.Layout,+origin: Q.Point,+tile: S.Tile,+minimum: U32,+height: U32) -> Bool:
  match layout origin tile:
    case R.Layout{x,y,z,+nx,+ny,+nz} Q.Point{a,b,c} S.Tile{tx,tz,width,depth,_,_}:
      Bool.and(Bool.and(U32.is_gt(nx,0),Bool.and(U32.is_gt(ny,0),U32.is_gt(nz,0))),
        Bool.and(overlaps(D.add(a,D.integer(x)),nx,D.integer(tx),width),
          Bool.and(overlaps(D.add(b,D.integer(y)),ny,D.integer(minimum),height),overlaps(D.add(c,D.integer(z)),nz,D.integer(tz),depth))))

def raster_layout(+row: R.Row) -> R.Layout:
  match row:
    case R.Mask{layout,_}: layout
    case R.Scatter{layout,_}: layout

@unsafe
def surface(+found: Maybe<&2,MD.Column>,+env: Env,+point: Q.Point) -> U32:
  match found env:
    case Some{MD.Column{_,biome,_,_,_,_,_,_,_,_}} _: biome
    case None{} Env{config,density,model,programs,climate,_,_,S.Tile{_,_,_,_,low,high},_,_}:
      surface_id(Shore.global_row(config,density,model,programs,climate,CV.horizontal(point),low,high))

def surface_id(+row: S.Row) -> U32:
  match row:
    case S.Row{_,_,id}: id

@unsafe
def quart(+point: Q.Point) -> Q.Point:
  match point:
    case Q.Point{x,y,z}: Q.Point{Q.lattice(x,4),Q.lattice(y,4),Q.lattice(z,4)}

@unsafe
def global_cave(+env: Env,+point: Q.Point) -> U32:
  match env:
    case Env{config,density,model,programs,+climate,_,_,S.Tile{_,_,_,_,low,high},_,_}:
      CV.selected_biome(C.has_underground(climate),CV.Surface{CV.surface_height(C.has_underground(climate),config,density,model,programs,CV.horizontal(point),low,high),Geo.empty_biome()},point,model,programs,climate,low,high)

@unsafe
def cave(+found: Maybe<&2,U32>,+env: Env,+point: Q.Point) -> Maybe<&2,U32>:
  match found:
    case Some{id}: Some{id}
    case None{}: Some{global_cave(env,quart(point))}

@unsafe
def cached_cave(+cache: Maybe<&2,CV.Grid>,+env: Env,+point: Q.Point,+low: U32,+high: U32) -> Maybe<&2,U32>:
  match cache:
    case None{}: cave(None{},env,point)
    case Some{grid}: cave(CV.biome(grid,point,low,high),env,point)

@unsafe
def biome(+env: Env,+point: Q.Point) -> U32:
  match env:
    case Env{_,_,_,_,_,+source,_,S.Tile{_,_,_,_,+low,+high},_,_}:
      biome_source(source,env,point,low,high)

@unsafe
def biome_source(+source: MD.Grid,+env: Env,+point: Q.Point,+low: U32,+high: U32) -> U32:
  match source point:
    case MD.Grid{_,_,_,_,cache} Q.Point{x,_,z}:
      CV.inherit(cached_cave(cache,env,point,low,high),surface(MC.lookup(source,S.Query{coord(x),coord(z),low,high}),env,point))

@unsafe
def masked(+active: Bool,+id: U32,+point: Q.Point,+random: W.U64,+store: O.Store,+core: S.Tile,+minimum: U32,+height: U32) -> Q.Table<Vein>:
  match active:
    case False{}: Q.Empty{}
    case True{}: clipped(R.row(G.Query{True{},id,random},store),id,point,random,core,minimum,height)

@unsafe
def clipped(+raster: R.Row,+id: U32,+point: Q.Point,+random: W.U64,+core: S.Tile,+minimum: U32,+height: U32) -> Q.Table<Vein>:
  clip(bounds(raster_layout(raster),point,core,minimum,height),Vein{id,point,random,raster})

def clip(+ok: Bool,+vein: Vein) -> Q.Table<Vein>:
  match ok:
    case False{}: Q.Empty{}
    case True{}: Q.Leaf{vein}

@unsafe
def eligible(+ok: Bool,+env: Env,+id: U32,+point: Q.Point,+random: W.U64,+minimum: U32,+height: U32) -> Q.Table<Vein>:
  match ok env:
    case False{} _: Q.Empty{}
    case True{} Env{_,_,_,_,_,_,+store,core,_,_}:
      member(biome(env,point),id,point,random,store,core,minimum,height)

@unsafe
def attempt(+value: A.Attempt,+env: Env,+id: U32) -> Q.Table<Vein>:
  match value env:
    case A.Attempt{_,x,y,z,random} Env{_,_,_,_,_,MD.Grid{_,+minimum,+height,_,_},_,+core,_,_}:
      +point = Q.world(x,y,z)
      eligible(bounds(R.Layout{4294967280,4294967280,4294967280,33,33,33},point,core,minimum,height),env,id,point,random,minimum,height)

@unsafe
def attempts(+values: Q.Table<A.Attempt>,+env: Env,+id: U32) -> Q.Table<Vein>:
  match values:
    case Q.Empty{}: Q.Empty{}
    case Q.Leaf{value}: attempt(value,env,id)
    case Q.Branch{_,a,b}:
      a b = attempts(a,env,id) attempts(b,env,id)
      join(a,b)

def row(+value: A.Row,+env: Env,+id: U32) -> Q.Table<Vein>:
  match value:
    case A.Row{_,values}: attempts(values,env,id)

@unsafe
def recipe(+env: Env,+id: U32,+x: U32,+z: U32) -> Q.Table<Vein>:
  match env:
    case Env{_,_,_,_,_,_,store,S.Tile{_,_,_,_,low,high},_,_}:
      row(A.row(A.Query{True{},id,x,z,W.U64{high,low}},store),env,id)

@unsafe
def recipes(+n: U32,+base: U32,+env: Env,+x: U32,+z: U32) -> Q.Table<Vein>:
  match n:
    case 0: Q.Empty{}
    case 1: recipe(env,base,x,z)
    case _:
      +half = U32.div(n,2)
      a b = recipes(half,base,env,x,z) recipes(U32.sub(n,half),U32.add(base,half),env,x,z)
      join(a,b)

@unsafe
def anchor(+ok: Bool,+env: Env,+x: U32,+z: U32) -> Bucket:
  match ok env:
    case False{} _: empty()
    case True{} Env{_,_,_,_,_,_,store,_,_,_}: Bucket{recipes(O.recipes(store),0,env,x,z)}

@unsafe
def at_anchor(+layout: Layout,+env: Env,+i: U32) -> Bucket:
  match layout:
    case Layout{x,z,+nx,_}: anchored(env,U32.add(x,U32.mod(i,nx)),U32.add(z,U32.div(i,nx)))

@unsafe
def anchored(+env: Env,+x: U32,+z: U32) -> Bucket:
  anchor(Bool.and(A.anchor(x),A.anchor(z)),env,x,z)

@unsafe
def anchors(+n: U32,+base: U32,+layout: Layout,+env: Env) -> Q.Table<Bucket>:
  match n:
    case 1: Q.Leaf{at_anchor(layout,env,base)}
    case _:
      +half = U32.div(n,2)
      a b = anchors(half,base,layout,env) anchors(U32.sub(n,half),U32.add(base,half),layout,env)
      Q.Branch{n,a,b}

@unsafe
def filter(+values: Q.Table<Vein>,+tile: S.Tile,+minimum: U32,+height: U32) -> Q.Table<Vein>:
  match values:
    case Q.Empty{}: Q.Empty{}
    case Q.Leaf{+vein}: filtered(vein,tile,minimum,height)
    case Q.Branch{_,a,b}: join(filter(a,tile,minimum,height),filter(b,tile,minimum,height))

@unsafe
def filtered(+vein: Vein,+tile: S.Tile,+minimum: U32,+height: U32) -> Q.Table<Vein>:
  match vein:
    case Vein{_,origin,_,raster}: clip(bounds(raster_layout(raster),origin,tile,minimum,height),vein)

@unsafe
def neighbors(+n: U32,+values: Q.Table<Bucket>,+layout: Layout,+core: S.Tile,+minimum: U32,+height: U32,+x: U32,+z: U32) -> Q.Table<Vein>:
  match n layout:
    case 9 _: Q.Empty{}
    case _ Layout{_,_,+nx,_}:
      neighbor(Q.get(Bucket,values,U32.add(U32.add(x,U32.mod(n,3)),U32.mul(nx,U32.add(z,U32.div(n,3)))),empty()),
        neighbors(U32.inc(n),values,layout,core,minimum,height,x,z),core,minimum,height)

def neighbor(+value: Bucket,+rest: Q.Table<Vein>,+tile: S.Tile,+minimum: U32,+height: U32) -> Q.Table<Vein>:
  match value:
    case Bucket{veins}: join(filter(veins,tile,minimum,height),rest)

@unsafe
def target(+i: U32,+core: S.Tile,+values: Q.Table<Bucket>,+layout: Layout,+minimum: U32,+height: U32) -> Bucket:
  match core:
    case S.Tile{+x,+z,+width,_,+low,+high}:
      Bucket{neighbors(0,values,layout,S.Tile{U32.add(x,U32.mul(U32.mod(i,U32.div(width,16)),16)),
        U32.add(z,U32.mul(U32.div(i,U32.div(width,16)),16)),16,16,low,high},minimum,height,
        U32.mod(i,U32.div(width,16)),U32.div(i,U32.div(width,16)))}

@unsafe
def buckets(+n: U32,+base: U32,+core: S.Tile,+values: Q.Table<Bucket>,+layout: Layout,+minimum: U32,+height: U32) -> Q.Table<Bucket>:
  match n:
    case 1: Q.Leaf{target(base,core,values,layout,minimum,height)}
    case _:
      +half = U32.div(n,2)
      a b = buckets(half,base,core,values,layout,minimum,height) buckets(U32.sub(n,half),U32.add(base,half),core,values,layout,minimum,height)
      Q.Branch{n,a,b}

@unsafe
def member(+biome: U32,+id: U32,+point: Q.Point,+random: W.U64,+store: O.Store,+core: S.Tile,+minimum: U32,+height: U32) -> Q.Table<Vein>:
  member_valid(U32.is_lt(biome,O.biomes(store)),biome,id,point,random,store,core,minimum,height)

@unsafe
def member_valid(+ok: Bool,+biome: U32,+id: U32,+point: Q.Point,+random: W.U64,+store: O.Store,+core: S.Tile,+minimum: U32,+height: U32) -> Q.Table<Vein>:
  match ok:
    case False{}: Q.Empty{}
    case True{}: masked(O.member(store,biome,id),id,point,random,store,core,minimum,height)
