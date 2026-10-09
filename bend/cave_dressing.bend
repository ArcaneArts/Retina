import Base
import ./cave_features_data.bend as F
import ./geology.bend as G
import ./density.bend as Q
import ./terrain_surface.bend as S
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./cave_lattice.bend as CV
import ./lake_terrain.bend as LT
import ./cave_carve.bend as Carve
import ./precise.bend as D
import ./noise.bend as N

type Env is Data:
  Env{source: MD.Grid,core: S.Tile,biomes: Q.Table<G.Biome>,water: U32,lava: U32}
type Work is Data:
  Work{runs: List<&2,MD.Run>,plants: List<&2,U32>}
type Computed is Type:
  Computed{grid: MD.Grid}

@unsafe
def word(+x: D.Number) -> U32:
  CV.word(D.axis(x))

@unsafe
def random(+point: Q.Point,+y: U32,+low: U32,+high: U32,+salt: U32) -> U32:
  match point:
    case Q.Point{x,_,z}: N.hash(U32.xor(U32.xor(U32.mul(word(x),2654435769),U32.mul(y,2246822507)),
      U32.xor(U32.mul(word(z),3266489909),U32.xor(low,U32.xor(N.hash(high),salt)))))

@unsafe
def unit(+h: U32) -> F32:
  F32.div(U32.to_f32(U32.shrn(h,8n)),16777216.0)

@unsafe
def choice(+xs: Q.Table<U32>,+h: U32) -> U32:
  Q.get(U32,xs,U32.mod(h,U32.max(Q.table_size(U32,xs),1)),0)

def features(+biome: G.Biome) -> F.Config:
  match biome:
    case G.Biome{_,_,features}: features

@unsafe
def selected_biome(+id: Maybe<&2,U32>,+env: Env) -> F.Config:
  match id env:
    case Some{id} Env{_,_,biomes,_,_}: features(Q.get(G.Biome,biomes,id,G.empty_biome()))
    case None{} _: F.empty()

@unsafe
def config(+offset: U32,+point: Q.Point,+env: Env) -> F.Config:
  match point env:
    case Q.Point{x,_,z} Env{MD.Grid{_,minimum,_,_,Some{grid}},S.Tile{_,_,_,_,low,high},_,_,_}:
      selected_biome(CV.biome(grid,Q.Point{x,D.integer(U32.add(minimum,offset)),z},low,high),env)
    case _ _: F.empty()

@unsafe
def at(+work: Work,+offset: U32) -> U32:
  match work:
    case Work{runs,_}: MC.material_at(runs,offset)

@unsafe
def put(+work: Work,+offset: U32,+material: U32) -> Work:
  match work:
    case Work{runs,plants}: Work{LT.replace(runs,offset,U32.inc(offset),material,[]),plants}

@unsafe
def record(+work: Work,+offset: U32,+material: U32,+recipe: F.Config) -> Work:
  record_policy(work,offset,F.policy(recipe,material))

@unsafe
def record_policy(+work: Work,+offset: U32,+policy: F.Policy) -> Work:
  match policy:
    case F.Policy{_,half,mask}: recorded(Bool.or(U32.is_ne(half,0),U32.is_ne(mask,0)),work,offset)

def recorded(+ok: Bool,+work: Work,+offset: U32) -> Work:
  match ok work:
    case False{} _: work
    case True{} Work{runs,plants}: Work{runs,offset <> plants}

@unsafe
def plant(+ok: Bool,+work: Work,+offset: U32,+recipe: F.Config,+values: Q.Table<U32>,+h: U32) -> Work:
  match ok:
    case False{}: work
    case True{}: planted(work,offset,recipe,choice(values,N.hash(U32.xor(h,7151))))

@unsafe
def planted(+work: Work,+offset: U32,+recipe: F.Config,+material: U32) -> Work:
  record(put(work,offset,material),offset,material,recipe)

@unsafe
def drip(+n: U32,+i: U32,+start: U32,+down: Bool,+values: Q.Table<U32>,+work: Work) -> Work:
  match n:
    case 0: work
    case _: drip_at(n,i,start,down,values,work,drip_offset(start,i,down))

@unsafe
def drip_offset(+start: U32,+i: U32,+down: Bool) -> U32:
  match down:
    case False{}: U32.add(start,i)
    case True{}: U32.sub(start,i)

@unsafe
def drip_at(+n: U32,+i: U32,+start: U32,+down: Bool,+values: Q.Table<U32>,+work: Work,+offset: U32) -> Work:
  drip_free(Bool.or(Bool.not(down),U32.is_zero(at(work,offset))),n,i,start,down,values,work,offset)

@unsafe
def drip_free(+ok: Bool,+n: U32,+i: U32,+start: U32,+down: Bool,+values: Q.Table<U32>,+work: Work,+offset: U32) -> Work:
  match ok:
    case False{}: work
    case True{}: drip(U32.sub(n,1),U32.inc(i),start,down,values,
      put(work,offset,Q.get(U32,values,U32.min(U32.sub(n,1),3),0)))

@unsafe
def drip_length(+minimum: U32,+maximum: U32,+h: U32,+room: U32) -> U32:
  U32.min(U32.add(minimum,U32.mod(N.hash(h),U32.inc(U32.sub(maximum,minimum)))),room)

@unsafe
def spikes(+ok: Bool,+minimum: U32,+maximum: U32,+h: U32,+start: U32,+end: U32,+down: Bool,+values: Q.Table<U32>,+work: Work) -> Work:
  match ok:
    case False{}: work
    case True{}: drip(drip_length(minimum,maximum,h,U32.sub(U32.sub(end,start),1)),0,spike_start(start,end,down),down,values,work)

@unsafe
def spike_start(+start: U32,+end: U32,+down: Bool) -> U32:
  match down:
    case False{}: start
    case True{}: U32.sub(end,1)

@unsafe
def clay(+floor: U32,+clay: U32,+h: U32) -> U32:
  clay_selected(Bool.and(U32.is_ne(clay,0),U32.is_zero(U32.mod(N.hash(h),19))),floor,clay)

def clay_selected(+ok: Bool,+floor: U32,+clay: U32) -> U32:
  match ok:
    case True{}: clay
    case False{}: floor

@unsafe
def floor_kind(+recipe: F.Config,+h: U32,+start: U32,+end: U32,+work: Work) -> Work:
  match recipe:
    case F.Config{1,_,F.Shape{floor,c,_,_,_,_,_,chance},F.Arrays{_,+ps,_,_,_,_},_}:
      plant(Bool.and(U32.is_gt(Q.table_size(U32,ps),0),F32.is_lt(unit(h),chance)),
        put(work,U32.sub(start,1),clay(floor,c,h)),start,recipe,ps,h)
    case F.Config{2,_,F.Shape{floor,_,_,minimum,maximum,_,density,_},F.Arrays{_,_,_,_,+up,_},_}:
      spikes(Bool.and(F32.is_lt(unit(h),F32.mul(density,0.18)),U32.is_gt(Q.table_size(U32,up),0)),
        minimum,maximum,h,start,end,False{},up,put(work,U32.sub(start,1),floor))
    case F.Config{_,_,F.Shape{floor,_,_,_,_,_,_,_},_,_}: put(work,U32.sub(start,1),floor)

@unsafe
def floor(+recipe: F.Config,+h: U32,+start: U32,+end: U32,+work: Work) -> Work:
  floor_valid(Bool.and(F.replaceable(recipe,at(work,U32.sub(start,1))),Bool.not(F.flagged(recipe,2))),recipe,h,start,end,work)

@unsafe
def floor_valid(+ok: Bool,+recipe: F.Config,+h: U32,+start: U32,+end: U32,+work: Work) -> Work:
  match ok:
    case False{}: work
    case True{}: floor_kind(recipe,h,start,end,work)

@unsafe
def vines(+n: U32,+i: U32,+end: U32,+bodies: Q.Table<U32>,+tips: Q.Table<U32>,+h: U32,+work: Work) -> Work:
  match n:
    case 0: work
    case _: vine_free(U32.is_zero(at(work,U32.sub(U32.sub(end,i),1))),n,i,end,bodies,tips,h,work)

@unsafe
def vine_free(+ok: Bool,+n: U32,+i: U32,+end: U32,+bodies: Q.Table<U32>,+tips: Q.Table<U32>,+h: U32,+work: Work) -> Work:
  match ok:
    case False{}: work
    case True{}: vines(U32.sub(n,1),U32.inc(i),end,bodies,tips,h,
      put(work,U32.sub(U32.sub(end,i),1),vine_material(n,bodies,tips,N.hash(U32.xor(h,i)))))

@unsafe
def vine_material(+n: U32,+bodies: Q.Table<U32>,+tips: Q.Table<U32>,+h: U32) -> U32:
  match n:
    case 1: choice(tips,h)
    case _: choice(bodies,h)

@unsafe
def blossom(+ok: Bool,+material: U32,+end: U32,+work: Work) -> Work:
  match ok:
    case False{}: work
    case True{}: put(work,U32.sub(end,1),material)

@unsafe
def hanging(+ok: Bool,+maximum: U32,+bodies: Q.Table<U32>,+tips: Q.Table<U32>,+flower: U32,+h: U32,+start: U32,+end: U32,+work: Work) -> Work:
  match ok:
    case False{}: blossom(Bool.and(U32.is_ne(flower,0),U32.is_zero(U32.mod(h,101))),flower,end,work)
    case True{}: vines(U32.min(U32.inc(U32.mod(N.hash(h),U32.max(maximum,1))),U32.sub(U32.sub(end,start),1)),0,end,bodies,tips,h,work)

@unsafe
def coat(+ok: Bool,+material: U32,+end: U32,+work: Work) -> Work:
  match ok:
    case False{}: work
    case True{}: put(work,end,material)

@unsafe
def roof_kind(+recipe: F.Config,+h: U32,+start: U32,+end: U32,+work: Work) -> Work:
  match recipe:
    case F.Config{1,_,F.Shape{+floor,_,flower,_,_,maximum,_,_},F.Arrays{_,_,+bs,+ts,_,_},_}:
      roof_lush(U32.is_ne(floor,0),recipe,h,start,end,work,floor,flower,maximum,bs,ts)
    case F.Config{2,_,F.Shape{+floor,_,_,minimum,maximum,_,density,_},F.Arrays{_,_,_,_,_,+down},_}:
      roof_drip(U32.is_ne(floor,0),minimum,maximum,density,down,h,start,end,work,floor)
    case _: work

@unsafe
def roof_lush(+ok: Bool,+recipe: F.Config,+h: U32,+start: U32,+end: U32,+work: Work,+floor: U32,+flower: U32,+maximum: U32,+bs: Q.Table<U32>,+ts: Q.Table<U32>) -> Work:
  match ok:
    case False{}: work
    case True{}: hanging(Bool.and(Bool.not(F.flagged(recipe,1)),Bool.and(U32.is_gt(Q.table_size(U32,bs),0),
      Bool.and(U32.is_gt(Q.table_size(U32,ts),0),U32.is_zero(U32.mod(h,11))))),maximum,bs,ts,flower,h,start,end,
      coat(Bool.not(F.flagged(recipe,4)),floor,end,work))

@unsafe
def roof_drip(+ok: Bool,+minimum: U32,+maximum: U32,+density: F32,+values: Q.Table<U32>,+h: U32,+start: U32,+end: U32,+work: Work,+floor: U32) -> Work:
  match ok:
    case False{}: work
    case True{}: spikes(Bool.and(F32.is_lt(unit(h),F32.mul(density,0.18)),U32.is_gt(Q.table_size(U32,values),0)),
      minimum,maximum,h,start,end,True{},values,put(work,end,floor))

@unsafe
def roof(+recipe: F.Config,+h: U32,+start: U32,+end: U32,+work: Work) -> Work:
  roof_valid(F.replaceable(recipe,at(work,end)),recipe,h,start,end,work)

@unsafe
def roof_valid(+ok: Bool,+recipe: F.Config,+h: U32,+start: U32,+end: U32,+work: Work) -> Work:
  match ok:
    case False{}: work
    case True{}: roof_kind(recipe,h,start,end,work)

@unsafe
def span(+start: U32,+end: U32,+point: Q.Point,+env: Env,+work: Work) -> Work:
  span_config(config(start,point,env),start,end,point,env,work)

@unsafe
def span_config(+recipe: F.Config,+start: U32,+end: U32,+point: Q.Point,+env: Env,+work: Work) -> Work:
  match recipe env:
    case F.Config{+kind,_,F.Shape{+floor,_,_,_,_,_,_,_},_,_} Env{MD.Grid{_,+minimum,_,_,_},S.Tile{_,_,_,_,+low,+high},_,_,_}:
      span_active(Bool.and(U32.is_ne(kind,0),U32.is_ne(floor,0)),recipe,start,end,point,env,work,minimum,low,high)

@unsafe
def span_active(+ok: Bool,+recipe: F.Config,+start: U32,+end: U32,+point: Q.Point,+env: Env,+work: Work,+minimum: U32,+low: U32,+high: U32) -> Work:
  match ok:
    case False{}: work
    case True{}: roof(config(U32.sub(end,1),point,env),random(point,U32.add(minimum,end),low,high,9283),start,end,
      floor(recipe,random(point,U32.add(minimum,start),low,high,4139),start,end,work))

# Original air runs delimit enclosed cavities. Dressing mutates only the owned
# column and never invents a cavity from surface air or fluid runs.
@unsafe
def scan(+xs: List<&2,MD.Run>,+point: Q.Point,+env: Env,+height: U32,+work: Work) -> Work:
  match xs:
    case Nil{}: work
    case MD.Run{+start,+end,material} <> rest:
      scanned(Bool.and(U32.is_zero(material),Bool.and(U32.is_lt(end,height),U32.is_ge(U32.sub(end,U32.min(end,U32.max(start,6))),2))),
        rest,point,env,height,work,U32.max(start,6),end)

@unsafe
def scanned(+ok: Bool,+rest: List<&2,MD.Run>,+point: Q.Point,+env: Env,+height: U32,+work: Work,+start: U32,+end: U32) -> Work:
  match ok:
    case False{}: scan(rest,point,env,height,work)
    case True{}: scan(rest,point,env,height,span(start,end,point,env,work))

@unsafe
def support(+indices: List<&2,U32>,+policy: Q.Table<F.Policy>,+work: Work) -> Work:
  match indices:
    case Nil{}: work
    case +offset <> rest: supported(rest,offset,policy,work,Q.get(F.Policy,policy,at(work,offset),F.empty_policy()))

@unsafe
def supported(+rest: List<&2,U32>,+offset: U32,+policy: Q.Table<F.Policy>,+work: Work,+material: F.Policy) -> Work:
  match material:
    case F.Policy{_,_,+mask}: support(rest,policy,remove(Bool.and(U32.is_ne(mask,0),Bool.or(U32.is_zero(offset),
      U32.is_zero(U32.and(policy_flags(Q.get(F.Policy,policy,at(work,U32.sub(offset,1)),F.empty_policy())),mask)))),offset,work))

def policy_flags(+policy: F.Policy) -> U32:
  match policy:
    case F.Policy{flags,_,_}: flags

def policy_half(+policy: F.Policy) -> U32:
  match policy:
    case F.Policy{_,half,_}: half

@unsafe
def remove(+ok: Bool,+offset: U32,+work: Work) -> Work:
  match ok:
    case False{}: work
    case True{}: put(work,offset,0)

@unsafe
def pairs(+indices: List<&2,U32>,+policy: Q.Table<F.Policy>,+height: U32,+work: Work) -> Work:
  match indices:
    case Nil{}: work
    case +offset <> rest: paired(rest,offset,policy,height,work,policy_half(Q.get(F.Policy,policy,at(work,offset),F.empty_policy())))

@unsafe
def paired(+rest: List<&2,U32>,+offset: U32,+policy: Q.Table<F.Policy>,+height: U32,+work: Work,+half: U32) -> Work:
  match half:
    case 0: pairs(rest,policy,height,work)
    case _: partner(rest,offset,policy,height,work,half,partner_offset(offset,half))

@unsafe
def partner_offset(+offset: U32,+half: U32) -> U32:
  partner_side(U32.is_lt(half,2147483648),offset)

def partner_side(+positive: Bool,+offset: U32) -> U32:
  match positive:
    case True{}: U32.inc(offset)
    case False{}: U32.sub(offset,1)

@unsafe
def partner(+rest: List<&2,U32>,+offset: U32,+policy: Q.Table<F.Policy>,+height: U32,+work: Work,+half: U32,+other: U32) -> Work:
  pairs(rest,policy,height,remove(Bool.or(U32.is_ge(other,height),U32.is_ne(
    policy_half(Q.get(F.Policy,policy,at(work,other),F.empty_policy())),U32.sub(0,half))),offset,work))

@unsafe
def repaired(+work: Work,+policy: Q.Table<F.Policy>,+height: U32) -> Work:
  match work:
    case Work{_,+indices}: pairs(indices,policy,height,support(indices,policy,work))

@unsafe
def column(+value: MD.Column,+point: Q.Point,+env: Env) -> MD.Column:
  match value env:
    case MD.Column{_,biome,depth,slope,band,secondary,preliminary,+runs,_,valid} Env{MD.Grid{_,minimum,+height,_,_},_,biomes,water,lava}:
      completed(repaired(scan(runs,point,env,height,Work{runs,[]}),policies(features(Q.get(G.Biome,biomes,0,G.empty_biome()))),height),
        minimum,water,lava,biome,depth,slope,band,secondary,preliminary,valid)

def policies(+config: F.Config) -> Q.Table<F.Policy>:
  match config:
    case F.Config{_,_,_,_,policy}: policy

@unsafe
def completed(+work: Work,+minimum: U32,+water: U32,+lava: U32,+biome: U32,+depth: F32,+slope: F32,+band: F32,+secondary: F32,+preliminary: F32,+valid: Bool) -> MD.Column:
  match work:
    case Work{+runs,_}: MD.Column{Carve.height(runs,minimum,water,lava,minimum),biome,depth,slope,band,secondary,preliminary,runs,U32.from_nat(List.length(&2,MD.Run,runs)),valid}

@unsafe
def inside(+point: Q.Point,+tile: S.Tile) -> Bool:
  match point tile:
    case Q.Point{px,_,pz} S.Tile{x,z,width,depth,_,_}: Bool.and(
      Bool.and(Bool.not(D.less(px,D.integer(x))),D.less(px,D.add(D.integer(x),D.unsigned(width)))),
      Bool.and(Bool.not(D.less(pz,D.integer(z))),D.less(pz,D.add(D.integer(z),D.unsigned(depth)))))

@unsafe
def at_column(+i: U32,+env: Env) -> MD.Column:
  match env:
    case Env{MD.Grid{+tile,_,_,values,_},+core,_,_,_}:
      selected(inside(S.point(tile,i),core),Q.get(MD.Column,values,i,MC.empty()),S.point(tile,i),env)

@unsafe
def selected(+ok: Bool,+value: MD.Column,+point: Q.Point,+env: Env) -> MD.Column:
  match ok:
    case False{}: value
    case True{}: column(value,point,env)

@unsafe
def columns(+n: U32,+base: U32,+env: Env) -> Q.Table<MD.Column>:
  match n:
    case 1: Q.Leaf{at_column(base,env)}
    case _:
      +half = U32.div(n,2)
      a b = columns(half,base,env) columns(U32.sub(n,half),U32.add(base,half),env)
      Q.Branch{n,a,b}

@unsafe
def calculate(+env: Env) -> MD.Grid:
  match env:
    case Env{MD.Grid{+tile,minimum,height,_,biomes},_,_,_,_}:
      MD.Grid{tile,minimum,height,columns(S.total(tile),0,env),biomes}

@unsafe
def execute(+env: Env) -> Computed:
  grid : MD.Grid = calculate!(env)
  Computed{grid}

@unsafe
def compatible(+core: S.Tile,+source: MD.Grid) -> Bool:
  match core source:
    case S.Tile{+x,+z,+width,+depth,low,high} MD.Grid{+tile,_,_,_,_}:
      compatible_seed(Bool.and(Bool.and(U32.is_zero(U32.and(x,15)),U32.is_zero(U32.and(z,15))),
        Bool.and(Bool.and(U32.is_zero(U32.mod(width,16)),U32.is_zero(U32.mod(depth,16))),Bool.and(U32.is_le(width,512),U32.is_le(depth,512)))),core,tile,low,high)

@unsafe
def compatible_seed(+ok: Bool,+core: S.Tile,+source: S.Tile,+low: U32,+high: U32) -> Bool:
  match core source:
    case S.Tile{x,z,width,depth,_,_} S.Tile{ox,oz,ow,od,lo,hi}: Bool.and(ok,Bool.and(Bool.and(U32.is_eq(low,lo),U32.is_eq(high,hi)),
      Bool.and(inside(Q.world(x,0,z),source),inside(Q.Point{D.add(D.integer(x),D.unsigned(U32.sub(width,1))),D.scalar(0.0),D.add(D.integer(z),D.unsigned(U32.sub(depth,1)))},source))))
