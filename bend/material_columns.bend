import Base
import ./density.bend as Q
import ./density_lattice.bend as L
import ./terrain_surface.bend as S
import ./material_program.bend as M
import ./material_lazy.bend as ML
import ./material_batch.bend as MB
import ./material_columns_data.bend as MD
import ./precise.bend as D
import ./reader.bend as R
import ./registry.bend as P
import ./compression.bend as Z
import ./binary.bend as B
import ./noise.bend as N

type Computed is Type:
  Computed{model: Q.Model,programs: Q.Table<Q.Program>,density: L.Grid,surface: S.Grid,materials: M.Model,grid: MD.Grid}
# Cache each column's bilinear density layers once, then interpolate only Y.
# This avoids fetching eight six-root lattice outputs for every single block.
type Vertical is Data:
  Vertical{origin: D.Number,step: U32,values: Q.Table<D.Number>,explicit: Maybe<&2,D.Number>}
# Immutable pre-material spans plus exact heights for the central tile and its
# one-column slope halo. Both device steps remain inside command 22.
type Geometry is Data:
  Geometry{runs: List<&2,MD.Run>,height: U32}
type GeometryCache is Data:
  GeometryCache{tile: S.Tile,values: Q.Table<Geometry>}
type Query is Data:
  Query{x: U32,y: U32,z: U32,low: U32,high: U32}
type Input is Type:
  Input{queries: Array<Query>,count: U32,valid: Bool}

def empty() -> MD.Column:
  MD.Column{0,4294967295,0.0,0.0,0.0,0.0,0.0,[],0,False{}}

# A one-column density halo is enough for the central-difference slope. Signed
# additions are compensated and validated, including both i32 extrema.
@unsafe
def compatible(+config: S.Config,+surface: S.Grid,+density: L.Grid) -> Bool:
  match config surface:
    case S.Config{_,+span,_,_,_,_} S.Grid{+tile,_}:
      Bool.and(U32.is_le(S.total(tile),U32.div(134217728,span)),halo_valid(config,tile,density))

@unsafe
def halo_valid(+config: S.Config,+tile: S.Tile,+density: L.Grid) -> Bool:
  match tile:
    case S.Tile{+x,+z,+width,+depth,+lo,+hi}:
      Bool.and(Bool.and(U32.is_ne(x,2147483648),U32.is_ne(z,2147483648)),
        Bool.and(Bool.and(Bool.not(D.less(D.integer(2147483647),D.add(D.integer(x),D.unsigned(width)))),
          Bool.not(D.less(D.integer(2147483647),D.add(D.integer(z),D.unsigned(depth))))),
          S.compatible(config,S.Tile{U32.sub(x,1),U32.sub(z,1),U32.add(width,2),U32.add(depth,2),lo,hi},density)))

def y(+config: S.Config,+offset: U32) -> U32:
  match config:
    case S.Config{minimum,_,_,_,_,_}: U32.add(minimum,offset)

def at(+point: Q.Point,+world_y: U32) -> Q.Point:
  match point:
    case Q.Point{x,_,z}: Q.Point{x,D.integer(world_y),z}

@unsafe
def vertical_values(+n: U32,+base: U32,+values: Q.Table<Q.Outputs>,+ix: U32,+iz: U32,+nx: U32,+ny: U32,+tx: D.Number,+tz: D.Number) -> Q.Table<D.Number>:
  match n:
    case 1: Q.Leaf{S.layer(values,ix,iz,nx,ny,base,tx,tz)}
    case _: Q.Branch{n,vertical_values(U32.div(n,2),base,values,ix,iz,nx,ny,tx,tz),
      vertical_values(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),values,ix,iz,nx,ny,tx,tz)}

@unsafe
def vertical_relative(relative: Q.Point,+origin: Q.Point,+nx: U32,+ny: U32,+nz: U32,+v: U32,+values: Q.Table<Q.Outputs>) -> Vertical:
  match relative origin:
    case Q.Point{+x,_,+z} Q.Point{_,+oy,_}:
      +ix = U32.min(F32.to_u32(D.value(D.floor(x))),U32.sub(nx,2))
      +iz = U32.min(F32.to_u32(D.value(D.floor(z))),U32.sub(nz,2))
      Vertical{oy,v,vertical_values(ny,0,values,ix,iz,nx,ny,D.sub(x,D.unsigned(ix)),D.sub(z,D.unsigned(iz))),None{}}

@unsafe
def vertical(+config: S.Config,+grid: L.Grid,+point: Q.Point) -> Vertical:
  match config grid:
    case S.Config{_,_,_,_,_,True{}} _: Vertical{D.scalar(0.0),1,Q.Empty{},Some{D.floor(S.height(config,point,grid))}}
    case _ L.Grid{L.Layout{+origin,+nx,+ny,+nz,+h,+v,_,_,_},+values}:
      vertical_relative(L.relative(point,origin,h,v),origin,nx,ny,nz,v,values)

@unsafe
def vertical_at(+field: Vertical,+py: D.Number) -> Bool:
  match field:
    case Vertical{_,_,_,Some{first_free}}: D.less(py,first_free)
    case Vertical{+origin,+step,+values,None{}}:
      +relative = D.divide(D.sub(py,origin),D.unsigned(step))
      +i = U32.min(F32.to_u32(D.value(D.floor(relative))),U32.sub(Q.table_size(D.Number,values),2))
      D.less(D.scalar(0.0),D.mix(Q.get(D.Number,values,i,D.scalar(0.0)),Q.get(D.Number,values,U32.inc(i),D.scalar(0.0)),D.sub(relative,D.unsigned(i))))

@unsafe
def geometry(+n: U32,+end: U32,+previous: U32,acc: List<&2,MD.Run>,+field: Vertical,+minimum: U32,+sea: U32) -> List<&2,MD.Run>:
  match n:
    case 0: List.reverse(&2,MD.Run,MD.Run{0,end,previous} <> acc)
    case _:
      +py = D.integer(U32.add(minimum,U32.sub(n,1)))
      geometry_next(Z.word(vertical_at(field,py),2,Z.word(D.less(py,D.integer(sea)),1,0)),n,end,previous,acc,field,minimum,sea)

@unsafe
def geometry_next(+value: U32,+n: U32,+end: U32,+previous: U32,acc: List<&2,MD.Run>,field: Vertical,minimum: U32,sea: U32) -> List<&2,MD.Run>:
  match previous:
    case 4294967295: geometry(U32.sub(n,1),end,value,acc,field,minimum,sea)
    case _: geometry_change(U32.is_ne(value,previous),value,n,end,previous,acc,field,minimum,sea)

@unsafe
def geometry_change(changed: Bool,value: U32,+n: U32,end: U32,previous: U32,acc: List<&2,MD.Run>,field: Vertical,minimum: U32,sea: U32) -> List<&2,MD.Run>:
  match changed:
    case False{}: geometry(U32.sub(n,1),end,previous,acc,field,minimum,sea)
    case True{}: geometry(U32.sub(n,1),n,value,MD.Run{n,end,previous} <> acc,field,minimum,sea)

@unsafe
def highest(runs: List<&2,MD.Run>,minimum: U32) -> U32:
  match runs:
    case Nil{}: minimum
    case MD.Run{_,end,2} <> _: U32.add(minimum,end)
    case _ <> rest: highest(rest,minimum)

# Find the highest positive *integer* voxel, skipping fully negative linear
# cells. A positive density above the world ceiling must not invent a surface.
@unsafe
def height(+config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,point: Q.Point,low: U32,high: U32) -> U32:
  match config:
    case S.Config{+minimum,+span,_,_,_,_}: height_field(vertical(config,density,point),minimum,span)

@unsafe
def height_field(+field: Vertical,+minimum: U32,+span: U32) -> U32:
  match field:
    case Vertical{_,_,_,Some{first_free}}: U32.add(minimum,F32.to_u32(D.value(D.maximum(D.scalar(0.0),D.minimum(D.unsigned(span),D.sub(first_free,D.integer(minimum)))))))
    case Vertical{_,_,_,None{}}: highest_cell(span,field,minimum)

@unsafe
def highest_cell(+end: U32,+field: Vertical,+minimum: U32) -> U32:
  match end field:
    case 0 _: minimum
    case _ Vertical{+origin,+step,_,_}:
      +top = D.integer(U32.add(minimum,U32.sub(end,1)))
      +i = D.floor(D.divide(D.sub(top,origin),D.unsigned(step)))
      +start = F32.to_u32(D.value(D.maximum(D.scalar(0.0),D.sub(D.add(origin,D.multiply(i,D.unsigned(step))),D.integer(minimum)))))
      highest_top(vertical_at(field,top),start,end,field,minimum)

@unsafe
def highest_top(solid: Bool,+start: U32,+end: U32,+field: Vertical,+minimum: U32) -> U32:
  match solid:
    case True{}: U32.add(minimum,end)
    case False{}: highest_bottom(vertical_at(field,D.integer(U32.add(minimum,start))),start,U32.sub(end,1),field,minimum)

@unsafe
def highest_bottom(solid: Bool,+start: U32,+top: U32,+field: Vertical,+minimum: U32) -> U32:
  match solid:
    case False{}: highest_cell(start,field,minimum)
    case True{}: crossing_search(start,top,field,minimum)

@unsafe
def crossing_search(+low: U32,+high: U32,+field: Vertical,+minimum: U32) -> U32:
  crossing_done(U32.is_le(U32.sub(high,low),1),low,high,field,minimum)

@unsafe
def crossing_done(done: Bool,+low: U32,+high: U32,+field: Vertical,+minimum: U32) -> U32:
  match done:
    case True{}: U32.add(minimum,high)
    case False{}:
      +middle = U32.div(U32.add(low,high),2)
      crossing_half(vertical_at(field,D.integer(U32.add(minimum,middle))),low,high,middle,field,minimum)

@unsafe
def crossing_half(solid: Bool,low: U32,high: U32,+middle: U32,field: Vertical,minimum: U32) -> U32:
  match solid:
    case True{}: crossing_search(middle,high,field,minimum)
    case False{}: crossing_search(low,middle,field,minimum)

@unsafe
def slope(+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+point: Q.Point,+low: U32,+high: U32) -> F32:
  F32.max(D.value(D.absolute(D.sub(D.integer(height(config,density,model,programs,Q.shifted(point,D.scalar(1.0),D.scalar(0.0),D.scalar(0.0)),low,high)),
      D.integer(height(config,density,model,programs,Q.shifted(point,D.scalar(F32.neg(1.0)),D.scalar(0.0),D.scalar(0.0)),low,high))))),
    D.value(D.absolute(D.sub(D.integer(height(config,density,model,programs,Q.shifted(point,D.scalar(0.0),D.scalar(0.0),D.scalar(1.0)),low,high)),
      D.integer(height(config,density,model,programs,Q.shifted(point,D.scalar(0.0),D.scalar(0.0),D.scalar(F32.neg(1.0))),low,high))))))

def geometry_halo(tile: S.Tile) -> S.Tile:
  match tile:
    case S.Tile{x,z,width,depth,low,high}: S.Tile{U32.sub(x,1),U32.sub(z,1),U32.add(width,2),U32.add(depth,2),low,high}

@unsafe
def geometry_column(+config: S.Config,density: L.Grid,point: Q.Point) -> Geometry:
  match config:
    case S.Config{+minimum,+span,+sea,_,_,_}:
      +runs = geometry(span,span,4294967295,[],vertical(config,density,point),minimum,sea)
      Geometry{runs,highest(runs,minimum)}

@unsafe
def geometry_rows(+limit: U32,+n: U32,+base: U32,+config: S.Config,+density: L.Grid,+tile: S.Tile) -> Q.Table<Geometry>:
  match n:
    case 1: Q.Leaf{geometry_column(config,density,S.point(tile,base))}
    case _: geometry_split(U32.is_le(n,limit),limit,n,base,config,density,tile)

@unsafe
def geometry_split(serial: Bool,+limit: U32,+n: U32,+base: U32,+config: S.Config,+density: L.Grid,+tile: S.Tile) -> Q.Table<Geometry>:
  match serial:
    case True{}: Q.Branch{n,geometry_rows(limit,U32.div(n,2),base,config,density,tile),
      geometry_rows(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),config,density,tile)}
    case False{}:
      +half = U32.div(n,2)
      a b = geometry_rows(limit,half,base,config,density,tile)
        geometry_rows(limit,U32.sub(n,half),U32.add(base,half),config,density,tile)
      Q.Branch{n,a,b}

@unsafe
def geometry_at(cache: GeometryCache,point: Q.Point) -> Geometry:
  match cache point:
    case GeometryCache{S.Tile{x,z,width,_,_,_},values} Q.Point{px,_,pz}:
      Q.get(Geometry,values,U32.add(F32.to_u32(D.value(D.sub(px,D.integer(x)))),
        U32.mul(width,F32.to_u32(D.value(D.sub(pz,D.integer(z)))))),Geometry{[],0})

def geometry_height(column: Geometry) -> U32:
  match column:
    case Geometry{_,height}: height

@unsafe
def cached_slope(+cache: GeometryCache,+point: Q.Point) -> F32:
  +xp = geometry_height(geometry_at(cache,Q.shifted(point,D.scalar(1.0),D.scalar(0.0),D.scalar(0.0))))
  +xm = geometry_height(geometry_at(cache,Q.shifted(point,D.scalar(F32.neg(1.0)),D.scalar(0.0),D.scalar(0.0))))
  +zp = geometry_height(geometry_at(cache,Q.shifted(point,D.scalar(0.0),D.scalar(0.0),D.scalar(1.0))))
  +zm = geometry_height(geometry_at(cache,Q.shifted(point,D.scalar(0.0),D.scalar(0.0),D.scalar(F32.neg(1.0)))))
  F32.max(D.value(D.absolute(D.sub(D.integer(xp),D.integer(xm)))),D.value(D.absolute(D.sub(D.integer(zp),D.integer(zm)))))

@unsafe
def push(+start: U32,+end: U32,+material: U32,runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match runs:
    case Nil{}: [MD.Run{start,end,material}]
    case MD.Run{+a,+b,+m} <> rest: merged(Bool.and(U32.is_eq(a,end),U32.is_eq(m,material)),start,end,material,a,b,m,rest)

@unsafe
def merged(same: Bool,start: U32,end: U32,material: U32,a: U32,b: U32,m: U32,rest: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match same:
    case True{}: MD.Run{start,b,material} <> rest
    case False{}: MD.Run{start,end,material} <> MD.Run{a,b,m} <> rest

@unsafe
def selected(+output: Q.Outputs,+stone: U32,+count: U32) -> U32 & Bool:
  match output:
    case Q.Outputs{value,_,_,_,_,_}: selected_value(D.value(value),stone,count)

@unsafe
def selected_value(+value: F32,+stone: U32,+count: U32) -> U32 & Bool:
  selected_valid(Bool.and(D.finite(value),Bool.or(F32.is_le(value,0.0),Bool.and(F32.is_eq(value,F32.floor(value)),F32.is_le(value,U32.to_f32(count))))),value,stone)

@unsafe
def selected_valid(ok: Bool,+value: F32,stone: U32) -> U32 & Bool:
  match ok:
    case False{}: (stone,False{})
    case True{}: (Z.word(F32.is_gt(value,0.0),F32.to_u32(F32.sub(F32.max(value,1.0),1.0)),stone),True{})

@unsafe
def coat_solid(+n: U32,+start: U32,+end: U32,runs: List<&2,MD.Run>,+valid: Bool,+config: S.Config,+palette: MD.Config,+model: Q.Model,+materials: ML.Prepared,+point: Q.Point,+low: U32,+high: U32,+biome: U32,+context: M.Context) -> List<&2,MD.Run> & Bool:
  match n context:
    case 0 _: (runs,valid)
    case _ M.Context{+above,+depth,+slope,+band,_,+secondary,+water,+preliminary}:
      coat_value(selected_value(D.value(ML.run(model,materials,biome,at(point,y(config,U32.sub(U32.add(start,n),1))),low,high,
        M.Context{F32.add(above,U32.to_f32(U32.sub(U32.inc(end),U32.add(start,n)))),depth,slope,band,U32.to_f32(n),secondary,water,preliminary})),stone(palette),palette_size(palette)),
        n,start,end,runs,valid,config,palette,model,materials,point,low,high,biome,context)

def stone(config: MD.Config) -> U32:
  match config:
    case MD.Config{stone,_,_,_,_,_}: stone

def palette_size(config: MD.Config) -> U32:
  match config:
    case MD.Config{_,_,count,_,_,_}: count

@unsafe
def coat_value(pair: U32 & Bool,+n: U32,+start: U32,end: U32,runs: List<&2,MD.Run>,valid: Bool,config: S.Config,palette: MD.Config,model: Q.Model,materials: ML.Prepared,point: Q.Point,low: U32,high: U32,biome: U32,context: M.Context) -> List<&2,MD.Run> & Bool:
  (material,ok) = pair
  coat_solid(U32.sub(n,1),start,end,push(U32.sub(U32.add(start,n),1),U32.add(start,n),material,runs),Bool.and(valid,ok),config,palette,model,materials,point,low,high,biome,context)

@unsafe
def coat(spans: List<&2,MD.Run>,runs: List<&2,MD.Run>,+valid: Bool,+config: S.Config,+palette: MD.Config,+model: Q.Model,+materials: ML.Prepared,+point: Q.Point,+low: U32,+high: U32,+biome: U32,+context: M.Context) -> List<&2,MD.Run> & Bool:
  match spans:
    case Nil{}: (runs,valid)
    case MD.Run{+start,+end,+kind} <> rest:
      coat_kind(kind,start,end,rest,runs,valid,config,palette,model,materials,point,low,high,biome,context)

@unsafe
def coat_kind(+kind: U32,+start: U32,+end: U32,rest: List<&2,MD.Run>,runs: List<&2,MD.Run>,valid: Bool,+config: S.Config,+palette: MD.Config,+model: Q.Model,+materials: ML.Prepared,+point: Q.Point,+low: U32,+high: U32,+biome: U32,+context: M.Context) -> List<&2,MD.Run> & Bool:
  match kind palette context:
    case 2 _ _: coat_next(coat_solid(U32.sub(end,start),start,end,runs,valid,config,palette,model,materials,point,low,high,biome,context),rest,config,palette,model,materials,point,low,high,biome,advance(context,U32.sub(end,start)))
    case _ MD.Config{_,+water,_,_,_,_} M.Context{+above,+depth,+slope,+band,+below,+secondary,+level,+preliminary}:
      coat(rest,push(start,end,Z.word(U32.is_eq(kind,1),water,0),runs),valid,config,palette,model,materials,point,low,high,biome,
        M.Context{N.pick(U32.is_eq(kind,0),0.0,above),depth,slope,band,below,secondary,N.pick(U32.is_eq(kind,0),F32.neg(2147483648.0),
          N.pick(F32.is_eq(level,F32.neg(2147483648.0)),D.value(D.integer(y(config,end))),level)),preliminary})

def advance(context: M.Context,count: U32) -> M.Context:
  match context:
    case M.Context{above,depth,slope,band,below,secondary,water,preliminary}: M.Context{F32.add(above,U32.to_f32(count)),depth,slope,band,below,secondary,water,preliminary}

@unsafe
def coat_next(pair: List<&2,MD.Run> & Bool,rest: List<&2,MD.Run>,config: S.Config,palette: MD.Config,model: Q.Model,materials: ML.Prepared,point: Q.Point,low: U32,high: U32,biome: U32,context: M.Context) -> List<&2,MD.Run> & Bool:
  (runs,valid) = pair
  coat(rest,runs,valid,config,palette,model,materials,point,low,high,biome,context)

@unsafe
def completed(pair: List<&2,MD.Run> & Bool,+height: U32,+biome: U32,context: M.Context) -> MD.Column:
  (+runs,valid) = pair
  match context:
    case M.Context{_,depth,slope,band,_,secondary,_,preliminary}: MD.Column{height,biome,depth,slope,band,secondary,preliminary,runs,U32.from_nat(List.length(&2,MD.Run,runs)),valid}

@unsafe
def column_ready(+geometry: List<&2,MD.Run>,+config: S.Config,+palette: MD.Config,+model: Q.Model,+materials: ML.Prepared,+point: Q.Point,+low: U32,+high: U32,+biome: U32,+context: M.Context) -> MD.Column:
  column_valid(Bool.and(U32.is_lt(biome,material_count(materials)),MB.finite(context)),geometry,config,palette,model,materials,point,low,high,biome,context)

@unsafe
def column_valid(ok: Bool,+geometry: List<&2,MD.Run>,+config: S.Config,palette: MD.Config,model: Q.Model,materials: ML.Prepared,point: Q.Point,low: U32,high: U32,+biome: U32,+context: M.Context) -> MD.Column:
  match ok config:
    case False{} S.Config{minimum,_,_,_,_,_}: completed(([],False{}),highest(geometry,minimum),biome,context)
    case True{} S.Config{+minimum,_,_,_,_,_}: completed(coat(geometry,[],True{},config,palette,model,materials,point,low,high,biome,context),highest(geometry,minimum),biome,context)

def material_count(materials: ML.Prepared) -> U32:
  ML.count(materials)

@unsafe
def context(config: S.Config,palette: MD.Config,+cache: GeometryCache,model: Q.Model,programs: Q.Table<Q.Program>,+point: Q.Point,low: U32,high: U32,first_free: U32) -> M.Context:
  context_at(palette,model,point,low,high,first_free,cached_slope(cache,point))

@unsafe
def context_at(+palette: MD.Config,+model: Q.Model,+point: Q.Point,+low: U32,+high: U32,+first_free: U32,slope: F32) -> M.Context:
  match palette model:
    case MD.Config{_,_,_,+a,+b,+c} Q.Model{+noises,_,_}:
      +depth = M.trunc(F32.add(F32.add(F32.mul(D.value(Q.noise_at(noises,a,point,low,high)),2.75),3.0),
        F32.mul(F32.div(U32.to_f32(U32.and(M.cell_hash(point,U32.add(low,1381),high),65535)),65535.0),0.25)))
      M.Context{0.0,depth,slope,F32.round(F32.mul(D.value(Q.noise_at(noises,c,point,low,high)),4.0)),
        0.0,D.value(Q.noise_at(noises,b,point,low,high)),F32.neg(2147483648.0),F32.add(F32.sub(D.value(D.integer(first_free)),9.0),depth)}

@unsafe
def column(+config: S.Config,+palette: MD.Config,+cache: GeometryCache,+surface: S.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+materials: ML.Prepared,+i: U32) -> MD.Column:
  match config surface:
    case S.Config{_,+span,_,_,_,_} S.Grid{+tile,+values}:
      sampled(Q.get(S.Row,values,i,S.empty()),config,palette,cache,model,programs,materials,S.point(tile,i),tile,span)

@unsafe
def sampled(row: S.Row,+config: S.Config,+palette: MD.Config,+cache: GeometryCache,+model: Q.Model,+programs: Q.Table<Q.Program>,+materials: ML.Prepared,+point: Q.Point,tile: S.Tile,+span: U32) -> MD.Column:
  match row config tile:
    case S.Row{_,_,+biome} S.Config{+minimum,_,+sea,_,_,_} S.Tile{_,_,_,_,+low,+high}:
      +spans = cached_runs(geometry_at(cache,point))
      column_ready(spans,config,palette,model,materials,point,low,high,biome,
        context(config,palette,cache,model,programs,point,low,high,highest(spans,minimum)))

@unsafe
def columns(+limit: U32,+n: U32,+base: U32,+config: S.Config,+palette: MD.Config,+cache: GeometryCache,+surface: S.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+materials: ML.Prepared) -> Q.Table<MD.Column>:
  match n:
    case 1: Q.Leaf{column(config,palette,cache,surface,model,programs,materials,base)}
    case _: split(U32.is_le(n,limit),limit,n,base,config,palette,cache,surface,model,programs,materials)

@unsafe
def split(serial: Bool,+limit: U32,+n: U32,+base: U32,+config: S.Config,+palette: MD.Config,+cache: GeometryCache,+surface: S.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+materials: ML.Prepared) -> Q.Table<MD.Column>:
  match serial:
    case True{}: Q.Branch{n,columns(limit,U32.div(n,2),base,config,palette,cache,surface,model,programs,materials),
      columns(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),config,palette,cache,surface,model,programs,materials)}
    case False{}:
      +half = U32.div(n,2)
      a b = columns(limit,half,base,config,palette,cache,surface,model,programs,materials)
        columns(limit,U32.sub(n,half),U32.add(base,half),config,palette,cache,surface,model,programs,materials)
      Q.Branch{n,a,b}

def cached_runs(column: Geometry) -> List<&2,MD.Run>:
  match column:
    case Geometry{runs,_}: runs

def cached_execute(+config: S.Config,+palette: MD.Config,+density: L.Grid,+surface: S.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+materials: M.Model) -> Computed:
  match config surface:
    case S.Config{+minimum,+span,_,_,_,_} S.Grid{+tile,_}:
      +prepared = ML.prepare(M.rules(materials))
      +expanded = geometry_halo(tile)
      geometry : Q.Table<Geometry> = geometry_rows!(U32.max(1,U32.div(U32.add(S.total(expanded),16383),16384)),S.total(expanded),0,config,density,expanded)
      values : Q.Table<MD.Column> = columns!(U32.max(1,U32.div(U32.add(S.total(tile),16383),16384)),S.total(tile),0,config,palette,GeometryCache{expanded,geometry},surface,model,programs,prepared)
      Computed{model,programs,density,surface,materials,MD.Grid{tile,minimum,span,values}}

@unsafe
def direct_column(+config: S.Config,+palette: MD.Config,+density: L.Grid,+surface: S.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+materials: ML.Prepared,+i: U32) -> MD.Column:
  match config surface:
    case S.Config{_,+span,_,_,_,_} S.Grid{+tile,+values}:
      direct_sampled(Q.get(S.Row,values,i,S.empty()),config,palette,density,model,programs,materials,S.point(tile,i),tile,span)

@unsafe
def direct_sampled(row: S.Row,+config: S.Config,+palette: MD.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+materials: ML.Prepared,+point: Q.Point,tile: S.Tile,+span: U32) -> MD.Column:
  match row config tile:
    case S.Row{_,_,+biome} S.Config{+minimum,_,+sea,_,_,_} S.Tile{_,_,_,_,+low,+high}:
      +spans = geometry(span,span,4294967295,[],vertical(config,density,point),minimum,sea)
      column_ready(spans,config,palette,model,materials,point,low,high,biome,
        context_at(palette,model,point,low,high,highest(spans,minimum),slope(config,density,model,programs,point,low,high)))

@unsafe
def direct_columns(+limit: U32,+n: U32,+base: U32,+config: S.Config,+palette: MD.Config,+density: L.Grid,+surface: S.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+materials: ML.Prepared) -> Q.Table<MD.Column>:
  match n:
    case 1: Q.Leaf{direct_column(config,palette,density,surface,model,programs,materials,base)}
    case _: direct_split(U32.is_le(n,limit),limit,n,base,config,palette,density,surface,model,programs,materials)

@unsafe
def direct_split(serial: Bool,+limit: U32,+n: U32,+base: U32,+config: S.Config,+palette: MD.Config,+density: L.Grid,+surface: S.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+materials: ML.Prepared) -> Q.Table<MD.Column>:
  match serial:
    case True{}: Q.Branch{n,direct_columns(limit,U32.div(n,2),base,config,palette,density,surface,model,programs,materials),
      direct_columns(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),config,palette,density,surface,model,programs,materials)}
    case False{}:
      +half = U32.div(n,2)
      a b = direct_columns(limit,half,base,config,palette,density,surface,model,programs,materials)
        direct_columns(limit,U32.sub(n,half),U32.add(base,half),config,palette,density,surface,model,programs,materials)
      Q.Branch{n,a,b}

def direct_execute(+config: S.Config,+palette: MD.Config,+density: L.Grid,+surface: S.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+materials: M.Model) -> Computed:
  match config surface:
    case S.Config{+minimum,+span,_,_,_,_} S.Grid{+tile,_}:
      +prepared = ML.prepare(M.rules(materials))
      values : Q.Table<MD.Column> = direct_columns!(U32.max(1,U32.div(U32.add(S.total(tile),16383),16384)),S.total(tile),0,config,palette,density,surface,model,programs,prepared)
      Computed{model,programs,density,surface,materials,MD.Grid{tile,minimum,span,values}}

def execute(cached: Bool,config: S.Config,palette: MD.Config,density: L.Grid,surface: S.Grid,model: Q.Model,programs: Q.Table<Q.Program>,materials: M.Model) -> Computed:
  match cached:
    case True{}: cached_execute(config,palette,density,surface,model,programs,materials)
    case False{}: direct_execute(config,palette,density,surface,model,programs,materials)

@unsafe
def summary(columns: Q.Table<MD.Column>) -> U32 & U32:
  match columns:
    case Q.Empty{}: (0,0)
    case Q.Leaf{MD.Column{_,_,_,_,_,_,_,_,count,valid}}: (count,Z.word(valid,0,1))
    case Q.Branch{_,a,b}: sum(summary(a),summary(b))

def sum(left: U32 & U32,right: U32 & U32) -> U32 & U32:
  (a,b) = left
  (c,d) = right
  (U32.add(a,c),U32.add(b,d))

@unsafe
def lookup(+grid: MD.Grid,query: S.Query) -> Maybe<&2,MD.Column>:
  match grid query:
    case MD.Grid{S.Tile{+ox,+oz,+width,+depth,+lo,+hi},_,_,+values} S.Query{+x,+z,+qlo,+qhi}:
      +dx = D.sub(D.integer(x),D.integer(ox))
      +dz = D.sub(D.integer(z),D.integer(oz))
      located(Bool.and(Bool.and(U32.is_eq(lo,qlo),U32.is_eq(hi,qhi)),Bool.and(L.contains(dx,width),L.contains(dz,depth))),values,dx,dz,width)

@unsafe
def located(ok: Bool,values: Q.Table<MD.Column>,dx: D.Number,dz: D.Number,width: U32) -> Maybe<&2,MD.Column>:
  match ok:
    case False{}: None{}
    case True{}: Some{Q.get(MD.Column,values,U32.add(F32.to_u32(D.value(dx)),U32.mul(width,F32.to_u32(D.value(dz)))),empty())}

@unsafe
def runs_encode(runs: List<&2,MD.Run>,w: B.Writer) -> B.Writer:
  match runs:
    case Nil{}: w
    case MD.Run{start,end,material} <> rest: runs_encode(rest,B.int(B.int(B.int(w,start),end),material))

@unsafe
def encode(n: Nat,a: Array<S.Query>,+grid: MD.Grid,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: encoded(p,Array.get(S.Query,a,i),grid,w,i)

@unsafe
def encoded(n: Nat,pair: Array<S.Query> & S.Query,+grid: MD.Grid,w: B.Writer,+i: U32) -> B.Writer:
  (a,query) = pair
  encode_column(n,a,grid,lookup(grid,query),w,i)

@unsafe
def encode_column(n: Nat,a: Array<S.Query>,grid: MD.Grid,found: Maybe<&2,MD.Column>,w: B.Writer,+i: U32) -> B.Writer:
  match found:
    case None{}: encode(n,a,grid,B.int(w,0),U32.inc(i))
    case Some{MD.Column{height,biome,depth,slope,band,secondary,preliminary,runs,count,_}}:
      encode(n,a,grid,runs_encode(runs,B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(w,1),height),biome),
        F32.bits(depth)),F32.bits(slope)),F32.bits(band)),F32.bits(secondary)),F32.bits(preliminary)),count)),U32.inc(i))

@unsafe
def material_at(runs: List<&2,MD.Run>,+offset: U32) -> U32:
  match runs:
    case Nil{}: 4294967295
    case MD.Run{+start,+end,material} <> rest: run_at(Bool.and(U32.is_ge(offset,start),U32.is_lt(offset,end)),material,rest,offset)

@unsafe
def run_at(found: Bool,material: U32,rest: List<&2,MD.Run>,offset: U32) -> U32:
  match found:
    case True{}: material
    case False{}: material_at(rest,offset)

@unsafe
def voxel(+grid: MD.Grid,+query: Query) -> U32:
  match grid query:
    case MD.Grid{_,+minimum,+height,_} Query{+x,+py,+z,+low,+high}:
      voxel_column(lookup(grid,S.Query{x,z,low,high}),D.sub(D.integer(py),D.integer(minimum)),height)

@unsafe
def voxel_column(found: Maybe<&2,MD.Column>,+offset: D.Number,+height: U32) -> U32:
  match found:
    case None{}: 4294967295
    case Some{MD.Column{_,_,_,_,_,_,_,runs,_,_}}: voxel_in(Bool.and(Bool.not(D.less(offset,D.scalar(0.0))),D.less(offset,D.unsigned(height))),runs,offset)

@unsafe
def voxel_in(ok: Bool,runs: List<&2,MD.Run>,offset: D.Number) -> U32:
  match ok:
    case False{}: 4294967295
    case True{}: material_at(runs,F32.to_u32(D.value(offset)))

@unsafe
def queries(n: Nat,cursor: R.Cursor,a: Array<Query>,+i: U32,+count: U32) -> Input:
  match n:
    case 0n: Input{a,count,R.end(cursor)}
    case 1n+p: R.word(Input,cursor,cursor => x => R.word(Input,cursor,cursor => y => R.word(Input,cursor,cursor => z =>
      R.word(Input,cursor,cursor => low => R.word(Input,cursor,cursor => high =>
        queries(p,cursor,Array.set(Query,a,i,Query{x,y,z,low,high}),U32.inc(i),count))))))

def query_count(ok: Bool,cursor: R.Cursor,+count: U32) -> Input:
  match ok:
    case False{}: Input{[Query{0,0,0,0,0}:Query*1n],0,False{}}
    case True{}: queries(U32.to_nat(count),cursor,Array.new(Query,P.capacity(count,1,0n),Query{0,0,0,0,0}),0,count)

def decode(bytes: Z.Bytes) -> Input:
  R.word(Input,R.start(bytes),cursor => +count => query_count(U32.is_le(count,4096),cursor,count))

@unsafe
def voxel_encode(n: Nat,a: Array<Query>,+grid: MD.Grid,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: voxel_encoded(p,Array.get(Query,a,i),grid,w,i)

@unsafe
def voxel_encoded(n: Nat,pair: Array<Query> & Query,+grid: MD.Grid,w: B.Writer,+i: U32) -> B.Writer:
  (a,query) = pair
  +material = voxel(grid,query)
  voxel_encode(n,a,grid,B.int(B.int(w,Z.word(U32.is_ne(material,4294967295),1,0)),material),U32.inc(i))
