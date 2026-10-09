import Base
import ./aquifer.bend as A
import ./density.bend as Q
import ./precise.bend as D
import ./noise.bend as N
import ./terrain_surface.bend as S

# Three selected-backend dispatches: preliminary surfaces, randomized fluid
# centers and globally aligned barrier vertices. Only the final cache remains.
type Computed is Type:
  Computed{prepared: A.Prepared}

@unsafe
def word(value: D.Number) -> U32:
  axis_word(D.axis(value))

def axis_word(axis: N.Axis) -> U32:
  match axis:
    case N.Axis{value,_}: value

def floor_cell(value: D.Number,step: U32) -> D.Number:
  D.floor(D.divide(value,D.unsigned(step)))

def size(layout: A.Layout) -> U32:
  match layout:
    case A.Layout{_,nx,ny,nz,_,_,_,_,_}: U32.mul(U32.mul(nx,ny),nz)

@unsafe
def count(last: D.Number,origin: D.Number,step: U32) -> U32:
  U32.inc(word(D.floor(D.divide(D.sub(last,origin),D.unsigned(step)))))

@unsafe
def layout(tile: S.Tile,minimum: U32,height: U32) -> A.Layout:
  center_layout(barrier_layout(tile,minimum,height))

@unsafe
def center_layout(layout: A.Layout) -> A.Layout:
  match layout:
    case A.Layout{Q.Point{+x,+y,+z},nx,ny,nz,_,_,_,low,high}:
      +ox = floor_cell(D.sub(x,D.scalar(5.0)),16)
      +oy = D.sub(floor_cell(D.add(y,D.scalar(1.0)),12),D.scalar(1.0))
      +oz = floor_cell(D.sub(z,D.scalar(5.0)),16)
      A.Layout{Q.Point{ox,oy,oz},count(D.add(floor_cell(D.sub(D.add(x,D.unsigned(U32.mul(U32.sub(nx,1),4))),D.scalar(5.0)),16),D.scalar(1.0)),ox,1),
        count(D.add(floor_cell(D.add(D.add(y,D.unsigned(U32.mul(U32.sub(ny,1),4))),D.scalar(1.0)),12),D.scalar(1.0)),oy,1),
        count(D.add(floor_cell(D.sub(D.add(z,D.unsigned(U32.mul(U32.sub(nz,1),4))),D.scalar(5.0)),16),D.scalar(1.0)),oz,1),1,1,1,low,high}

@unsafe
def point(layout: A.Layout,+index: U32) -> Q.Point:
  match layout:
    case A.Layout{origin,+nx,+ny,_,dx,dy,dz,_,_}: Q.shifted(origin,D.unsigned(U32.mul(U32.mod(index,nx),dx)),
      D.unsigned(U32.mul(U32.mod(U32.div(index,nx),ny),dy)),D.unsigned(U32.mul(U32.div(index,U32.mul(nx,ny)),dz)))

@unsafe
def surface_layout(layout: A.Layout) -> A.Layout:
  match layout:
    case A.Layout{Q.Point{+x,_,+z},nx,_,nz,_,_,_,low,high}:
      A.Layout{Q.Point{D.sub(D.scale(x,16.0),D.scalar(48.0)),D.scalar(0.0),D.sub(D.scale(z,16.0),D.scalar(16.0))},
        U32.add(U32.mul(nx,4),18),1,U32.add(U32.mul(nz,4),10),4,1,4,low,high}

@unsafe
def barrier_layout(tile: S.Tile,+minimum: U32,height: U32) -> A.Layout:
  match tile:
    case S.Tile{+x,+z,width,depth,low,high}:
      +ox = Q.lattice(D.integer(x),4)
      +oy = Q.lattice(D.integer(minimum),4)
      +oz = Q.lattice(D.integer(z),4)
      A.Layout{Q.Point{ox,oy,oz},U32.inc(count(D.add(D.integer(x),D.unsigned(U32.sub(width,1))),ox,4)),
        U32.inc(count(D.add(D.integer(minimum),D.unsigned(U32.sub(height,1))),oy,4)),
        U32.inc(count(D.add(D.integer(z),D.unsigned(U32.sub(depth,1))),oz,4)),4,4,4,low,high}

@unsafe
def bounded(+layout: A.Layout) -> Bool:
  match layout:
    case A.Layout{_,nx,+ny,+nz,_,_,_,_,_}: Bool.and(Bool.and(U32.is_gt(ny,0),U32.is_gt(nz,0)),U32.is_le(nx,U32.div(2097152,U32.mul(ny,nz))))

@unsafe
def compatible(+tile: S.Tile,+minimum: U32,+height: U32) -> Bool:
  +centers = layout(tile,minimum,height)
  Bool.and(bounded(centers),Bool.and(bounded(surface_layout(centers)),bounded(barrier_layout(tile,minimum,height))))

@unsafe
def relative(point: Q.Point,layout: A.Layout) -> Q.Point:
  match point layout:
    case Q.Point{x,y,z} A.Layout{Q.Point{ox,oy,oz},_,_,_,dx,dy,dz,_,_}:
      Q.Point{D.divide(D.sub(x,ox),D.unsigned(dx)),D.divide(D.sub(y,oy),D.unsigned(dy)),D.divide(D.sub(z,oz),D.unsigned(dz))}

@unsafe
def index(point: Q.Point,+layout: A.Layout) -> U32:
  index_relative(relative(point,layout),layout)

@unsafe
def index_relative(relative: Q.Point,layout: A.Layout) -> U32:
  match relative layout:
    case Q.Point{x,y,z} A.Layout{_,nx,ny,_,_,_,_,_,_}: U32.add(word(D.floor(x)),U32.mul(nx,U32.add(word(D.floor(y)),U32.mul(ny,word(D.floor(z))))))

@unsafe
def level(volume: A.Volume,point: Q.Point) -> D.Number:
  match volume:
    case A.Volume{+layout,values}: Q.get(D.Number,values,index(A.at(point,D.scalar(0.0)),layout),D.scalar(F32.neg(32512.0)))

@unsafe
def surface(config: A.Config,model: Q.Model,programs: Q.Table<Q.Program>,+layout: A.Layout,index: U32) -> D.Number:
  match layout:
    case A.Layout{_,_,_,_,_,_,_,low,high}: A.surface(config,model,programs,point(layout,index),low,high)

@unsafe
def surfaces(+limit: U32,+n: U32,+base: U32,+config: A.Config,+model: Q.Model,+programs: Q.Table<Q.Program>,+layout: A.Layout) -> Q.Table<D.Number>:
  match n:
    case 1: Q.Leaf{surface(config,model,programs,layout,base)}
    case _: surface_split(U32.is_le(n,limit),limit,n,base,config,model,programs,layout)

@unsafe
def surface_split(serial: Bool,+limit: U32,+n: U32,+base: U32,+config: A.Config,+model: Q.Model,+programs: Q.Table<Q.Program>,+layout: A.Layout) -> Q.Table<D.Number>:
  match serial:
    case True{}: Q.Branch{n,surfaces(limit,U32.div(n,2),base,config,model,programs,layout),surfaces(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),config,model,programs,layout)}
    case False{}:
      +half = U32.div(n,2)
      a b = surfaces(limit,half,base,config,model,programs,layout) surfaces(limit,U32.sub(n,half),U32.add(base,half),config,model,programs,layout)
      Q.Branch{n,a,b}

@unsafe
def global_status(+params: A.Params,y: D.Number) -> A.Status:
  match params:
    case A.Params{sea,water,lava,level,_}: global_lava(D.less(y,D.integer(level)),sea,water,lava)

def global_lava(is_lava: Bool,sea: U32,water: U32,lava: U32) -> A.Status:
  match is_lava:
    case True{}: A.Status{D.scalar(F32.neg(54.0)),lava}
    case False{}: A.Status{D.integer(sea),water}

def material(status: A.Status,y: D.Number) -> U32:
  match status:
    case A.Status{level,kind}: N.word(D.less(y,level),kind,0)

@unsafe
def offset(+i: U32) -> Q.Point:
  match i:
    case 0: Q.world(0,0,0)
    case 1: Q.world(4294967264,0,4294967280)
    case 2: Q.world(4294967280,0,4294967280)
    case 3: Q.world(0,0,4294967280)
    case 4: Q.world(16,0,4294967280)
    case 5: Q.world(4294967248,0,0)
    case 6: Q.world(4294967264,0,0)
    case 7: Q.world(4294967280,0,0)
    case 8: Q.world(16,0,0)
    case 9: Q.world(4294967264,0,16)
    case 10: Q.world(4294967280,0,16)
    case 11: Q.world(0,0,16)
    case _: Q.world(16,0,16)

def shifted(point: Q.Point,offset: Q.Point) -> Q.Point:
  match offset:
    case Q.Point{x,y,z}: Q.shifted(point,x,y,z)

@unsafe
def status(+point: Q.Point,+params: A.Params,model: Q.Model,programs: Q.Table<Q.Program>,surfaces: A.Volume,low: U32,high: U32) -> A.Status:
  match point:
    case Q.Point{_,y,_}: scan(0,point,params,model,programs,surfaces,low,high,global_status(params,y),D.scalar(2147483647.0),False{})

@unsafe
def scan(+i: U32,+point: Q.Point,+params: A.Params,+model: Q.Model,+programs: Q.Table<Q.Program>,+surfaces: A.Volume,+low: U32,+high: U32,+global: A.Status,+lowest: D.Number,+under: Bool) -> A.Status:
  match i:
    case 13: flooding(Q.run_index(model,programs,0,point,low,high),point,params,model,programs,low,high,global,lowest,under)
    case _: probe(level(surfaces,shifted(point,offset(i))),i,point,params,model,programs,surfaces,low,high,global,lowest,under)

@unsafe
def probe(+surface: D.Number,+i: U32,+point: Q.Point,+params: A.Params,model: Q.Model,programs: Q.Table<Q.Program>,surfaces: A.Volume,low: U32,high: U32,global: A.Status,lowest: D.Number,under: Bool) -> A.Status:
  match point:
    case Q.Point{_,+y,_}: probe_above(Bool.and(U32.is_eq(i,0),D.less(D.add(surface,D.scalar(8.0)),D.sub(y,D.scalar(12.0)))),
      D.less(D.add(surface,D.scalar(8.0)),D.add(y,D.scalar(12.0))),surface,i,point,params,model,programs,surfaces,low,high,global,lowest,under)

@unsafe
def probe_above(above: Bool,+reaches: Bool,+surface: D.Number,+i: U32,+point: Q.Point,+params: A.Params,model: Q.Model,programs: Q.Table<Q.Program>,surfaces: A.Volume,low: U32,high: U32,+global: A.Status,lowest: D.Number,under: Bool) -> A.Status:
  match above:
    case True{}: global
    case False{}: probe_wet(Bool.and(Bool.or(reaches,U32.is_eq(i,0)),U32.is_ne(material(global_status(params,D.add(surface,D.scalar(8.0))),D.add(surface,D.scalar(8.0))),0)),
      reaches,surface,i,point,params,model,programs,surfaces,low,high,global,lowest,under)

@unsafe
def probe_wet(+wet: Bool,+reaches: Bool,+surface: D.Number,+i: U32,+point: Q.Point,+params: A.Params,model: Q.Model,programs: Q.Table<Q.Program>,surfaces: A.Volume,low: U32,high: U32,+global: A.Status,lowest: D.Number,under: Bool) -> A.Status:
  match wet reaches:
    case True{} True{}: global_status(params,D.add(surface,D.scalar(8.0)))
    case _ _: scan(U32.inc(i),point,params,model,programs,surfaces,low,high,global,D.minimum(lowest,surface),Bool.or(under,Bool.and(wet,U32.is_eq(i,0))))

@unsafe
def flooding(+value: Q.Outputs,+point: Q.Point,+params: A.Params,+model: Q.Model,+programs: Q.Table<Q.Program>,+low: U32,+high: U32,+global: A.Status,+lowest: D.Number,under: Bool) -> A.Status:
  match value point:
    case Q.Outputs{flood,erosion,_,_,_,_} Q.Point{_,y,_}: thresholds(D.less(D.scalar(0.0),erosion),D.value(flood),
      N.pick(under,F32.min(1.0,F32.max(0.0,F32.sub(1.0,F32.div(D.value(D.sub(D.add(lowest,D.scalar(8.0)),y)),64.0)))),0.0),
      point,params,model,programs,low,high,global,lowest)

@unsafe
def thresholds(dry: Bool,+flood: F32,+factor: F32,+point: Q.Point,+params: A.Params,+model: Q.Model,+programs: Q.Table<Q.Program>,+low: U32,+high: U32,+global: A.Status,lowest: D.Number) -> A.Status:
  match dry:
    case True{}: select_lava(D.scalar(F32.neg(32512.0)),global,point,params,model,programs,low,high)
    case False{}: threshold_global(F32.is_gt(F32.min(1.0,F32.max(F32.neg(1.0),flood)),N.mix(0.8,F32.neg(0.3),factor)),
      F32.is_gt(F32.min(1.0,F32.max(F32.neg(1.0),flood)),N.mix(0.4,F32.neg(0.8),factor)),point,params,model,programs,low,high,global,lowest)

@unsafe
def threshold_global(full: Bool,spread: Bool,+point: Q.Point,+params: A.Params,+model: Q.Model,+programs: Q.Table<Q.Program>,+low: U32,+high: U32,+global: A.Status,lowest: D.Number) -> A.Status:
  match full spread global:
    case True{} _ A.Status{+level,kind}: select_lava(level,A.Status{level,kind},point,params,model,programs,low,high)
    case False{} True{} _: spread_level(point,params,model,programs,low,high,global,lowest)
    case _ _ _: select_lava(D.scalar(F32.neg(32512.0)),global,point,params,model,programs,low,high)

@unsafe
def spread_level(+point: Q.Point,params: A.Params,+model: Q.Model,+programs: Q.Table<Q.Program>,+low: U32,+high: U32,global: A.Status,lowest: D.Number) -> A.Status:
  match point:
    case Q.Point{x,y,z}:
      +cell : Q.Point = Q.Point{floor_cell(x,16),floor_cell(y,40),floor_cell(z,16)}
      spread_value(A.first(Q.run_index(model,programs,1,cell,low,high)),cell,point,params,model,programs,low,high,global,lowest)

@unsafe
def spread_value(value: D.Number,cell: Q.Point,point: Q.Point,params: A.Params,model: Q.Model,programs: Q.Table<Q.Program>,low: U32,high: U32,global: A.Status,lowest: D.Number) -> A.Status:
  match cell:
    case Q.Point{_,y,_}: select_lava(D.minimum(lowest,D.add(D.add(D.scale(y,40.0),D.scalar(20.0)),D.scale(D.floor(D.divide(D.scale(value,10.0),D.scalar(3.0))),3.0))),global,point,params,model,programs,low,high)

@unsafe
def select_lava(+level: D.Number,status: A.Status,+point: Q.Point,+params: A.Params,model: Q.Model,programs: Q.Table<Q.Program>,low: U32,high: U32) -> A.Status:
  match status params:
    case A.Status{_,+kind} A.Params{sea,water,+lava,cutoff,_}: lava_enabled(Bool.and(Bool.not(D.less(D.scalar(F32.neg(10.0)),level)),Bool.and(Bool.or(D.less(level,D.scalar(F32.neg(32512.0))),D.less(D.scalar(F32.neg(32512.0)),level)),U32.is_ne(kind,lava))),level,kind,lava,point,model,programs,low,high)

@unsafe
def lava_enabled(enabled: Bool,+level: D.Number,+kind: U32,lava: U32,point: Q.Point,model: Q.Model,programs: Q.Table<Q.Program>,low: U32,high: U32) -> A.Status:
  match enabled point:
    case False{} _: A.Status{level,kind}
    case True{} Q.Point{x,y,z}: A.Status{level,N.word(F32.is_gt(F32.abs(D.value(A.first(Q.run_index(model,programs,2,Q.Point{floor_cell(x,64),floor_cell(y,40),floor_cell(z,64)},low,high)))),0.3),lava,kind)}

@unsafe
def center(+cell: Q.Point,+params: A.Params,model: Q.Model,programs: Q.Table<Q.Program>,surfaces: A.Volume,+low: U32,+high: U32) -> A.Center:
  match cell:
    case Q.Point{+x,+y,+z}:
      +h = N.hash(U32.xor(U32.xor(U32.xor(U32.mul(word(x),2654435769),U32.mul(word(y),2246822507)),U32.mul(word(z),3266489909)),U32.xor(low,U32.add(N.hash(high),91871))))
      +point : Q.Point = Q.Point{D.add(D.scale(x,16.0),D.unsigned(U32.mod(h,10))),D.add(D.scale(y,12.0),D.unsigned(U32.mod(N.hash(U32.inc(h)),9))),D.add(D.scale(z,16.0),D.unsigned(U32.mod(N.hash(U32.add(h,2)),10)))}
      A.Center{point,status(point,params,model,programs,surfaces,low,high)}

@unsafe
def center_at(+layout: A.Layout,index: U32,params: A.Params,model: Q.Model,programs: Q.Table<Q.Program>,surfaces: A.Volume) -> A.Center:
  match layout:
    case A.Layout{_,_,_,_,_,_,_,low,high}: center(point(layout,index),params,model,programs,surfaces,low,high)

@unsafe
def centers(+limit: U32,+n: U32,+base: U32,+layout: A.Layout,+params: A.Params,+model: Q.Model,+programs: Q.Table<Q.Program>,+surfaces: A.Volume) -> Q.Table<A.Center>:
  match n:
    case 1: Q.Leaf{center_at(layout,base,params,model,programs,surfaces)}
    case _: center_split(U32.is_le(n,limit),limit,n,base,layout,params,model,programs,surfaces)

@unsafe
def center_split(serial: Bool,+limit: U32,+n: U32,+base: U32,+layout: A.Layout,+params: A.Params,+model: Q.Model,+programs: Q.Table<Q.Program>,+surfaces: A.Volume) -> Q.Table<A.Center>:
  match serial:
    case True{}: Q.Branch{n,centers(limit,U32.div(n,2),base,layout,params,model,programs,surfaces),centers(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),layout,params,model,programs,surfaces)}
    case False{}:
      +half = U32.div(n,2)
      a b = centers(limit,half,base,layout,params,model,programs,surfaces) centers(limit,U32.sub(n,half),U32.add(base,half),layout,params,model,programs,surfaces)
      Q.Branch{n,a,b}

@unsafe
def barrier(+layout: A.Layout,index: U32,model: Q.Model,programs: Q.Table<Q.Program>) -> D.Number:
  match layout:
    case A.Layout{_,_,_,_,_,_,_,low,high}: A.first(Q.run_index(model,programs,3,point(layout,index),low,high))

@unsafe
def barriers(+limit: U32,+n: U32,+base: U32,+layout: A.Layout,+model: Q.Model,+programs: Q.Table<Q.Program>) -> Q.Table<D.Number>:
  match n:
    case 1: Q.Leaf{barrier(layout,base,model,programs)}
    case _: barrier_split(U32.is_le(n,limit),limit,n,base,layout,model,programs)

@unsafe
def barrier_split(serial: Bool,+limit: U32,+n: U32,+base: U32,+layout: A.Layout,+model: Q.Model,+programs: Q.Table<Q.Program>) -> Q.Table<D.Number>:
  match serial:
    case True{}: Q.Branch{n,barriers(limit,U32.div(n,2),base,layout,model,programs),barriers(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),layout,model,programs)}
    case False{}:
      +half = U32.div(n,2)
      a b = barriers(limit,half,base,layout,model,programs) barriers(limit,U32.sub(n,half),U32.add(base,half),layout,model,programs)
      Q.Branch{n,a,b}

@unsafe
def limit(n: U32) -> U32:
  U32.max(1,U32.div(U32.add(n,16383),16384))

@unsafe
def execute(+prepared: A.Prepared,+model: Q.Model,+tile: S.Tile,+minimum: U32,+height: U32,+params: A.Params) -> Computed:
  match prepared:
    case A.Prepared{+config,+programs,_}:
      +layout = layout(tile,minimum,height)
      +sl = surface_layout(layout)
      +bl = barrier_layout(tile,minimum,height)
      surfaces : Q.Table<D.Number> = surfaces!(limit(size(sl)),size(sl),0,config,model,programs,sl)
      centers : Q.Table<A.Center> = centers!(limit(size(layout)),size(layout),0,layout,params,model,programs,A.Volume{sl,surfaces})
      barriers : Q.Table<D.Number> = barriers!(limit(size(bl)),size(bl),0,bl,model,programs)
      Computed{A.cached(prepared,A.Cache{layout,centers,A.Volume{bl,barriers},params})}
