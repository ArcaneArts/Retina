import Base
import ./aquifer.bend as A
import ./aquifer_lattice.bend as L
import ./density.bend as Q
import ./precise.bend as D
import ./noise.bend as N

# Nearest three of twelve globally hashed fluid centers. Density is the cave
# field's negative carver proxy; this stage does not reevaluate terrain graphs.
type Nearest is Data:
  Nearest{a: A.Status,da: F32,b: A.Status,db: F32,c: A.Status,dc: F32}

@unsafe
def in_axis(+value: D.Number,n: U32) -> Bool:
  Bool.and(Bool.not(D.less(value,D.scalar(0.0))),Bool.not(D.less(D.unsigned(U32.sub(n,1)),value)))

@unsafe
def contains(point: Q.Point,+layout: A.Layout,low: U32,high: U32) -> Bool:
  contains_relative(L.relative(point,layout),layout,low,high)

@unsafe
def contains_relative(relative: Q.Point,layout: A.Layout,low: U32,high: U32) -> Bool:
  match relative layout:
    case Q.Point{x,y,z} A.Layout{_,nx,ny,nz,_,_,_,lo,hi}: Bool.and(Bool.and(U32.is_eq(low,lo),U32.is_eq(high,hi)),Bool.and(in_axis(x,nx),Bool.and(in_axis(y,ny),in_axis(z,nz))))

@unsafe
def covered(cache: A.Cache,point: Q.Point,low: U32,high: U32) -> Bool:
  match cache:
    case A.Cache{_,_,A.Volume{layout,_},_}: contains(point,layout,low,high)

@unsafe
def cell(+relative: D.Number,n: U32) -> U32:
  U32.min(L.word(D.floor(relative)),U32.sub(n,2))

@unsafe
def corner(+values: Q.Table<D.Number>,+index: U32,+nx: U32,+ny: U32,+tx: F32,+ty: F32,tz: F32) -> F32:
  N.mix(N.mix(N.mix(D.value(Q.get(D.Number,values,index,D.scalar(0.0))),D.value(Q.get(D.Number,values,U32.inc(index),D.scalar(0.0))),tx),
      N.mix(D.value(Q.get(D.Number,values,U32.add(index,nx),D.scalar(0.0))),D.value(Q.get(D.Number,values,U32.inc(U32.add(index,nx)),D.scalar(0.0))),tx),ty),
    N.mix(N.mix(D.value(Q.get(D.Number,values,U32.add(index,U32.mul(nx,ny)),D.scalar(0.0))),D.value(Q.get(D.Number,values,U32.inc(U32.add(index,U32.mul(nx,ny))),D.scalar(0.0))),tx),
      N.mix(D.value(Q.get(D.Number,values,U32.add(index,U32.add(U32.mul(nx,ny),nx)),D.scalar(0.0))),D.value(Q.get(D.Number,values,U32.inc(U32.add(index,U32.add(U32.mul(nx,ny),nx))),D.scalar(0.0))),tx),ty),tz)

@unsafe
def barrier(volume: A.Volume,point: Q.Point) -> F32:
  match volume:
    case A.Volume{+layout,values}: interpolated(L.relative(point,layout),layout,values)

@unsafe
def interpolated(relative: Q.Point,layout: A.Layout,values: Q.Table<D.Number>) -> F32:
  match relative layout:
    case Q.Point{+x,+y,+z} A.Layout{_,+nx,+ny,nz,_,_,_,_,_}:
      +ix = cell(x,nx)
      +iy = cell(y,ny)
      +iz = cell(z,nz)
      corner(values,U32.add(ix,U32.mul(nx,U32.add(iy,U32.mul(ny,iz)))),nx,ny,D.value(D.sub(x,D.unsigned(ix))),D.value(D.sub(y,D.unsigned(iy))),D.value(D.sub(z,D.unsigned(iz))))

@unsafe
def distance(a: Q.Point,b: Q.Point) -> F32:
  match a b:
    case Q.Point{x,y,z} Q.Point{bx,by,bz}:
      +dx = D.value(D.sub(x,bx))
      +dy = D.value(D.sub(y,by))
      +dz = D.value(D.sub(z,bz))
      F32.add(F32.add(F32.mul(dx,dx),F32.mul(dy,dy)),F32.mul(dz,dz))

@unsafe
def insert(+status: A.Status,+d: F32,nearest: Nearest) -> Nearest:
  match nearest:
    case Nearest{+a,+da,+b,+db,+c,+dc}: insert_at(F32.is_le(d,da),F32.is_le(d,db),F32.is_le(d,dc),status,d,a,da,b,db,c,dc)

@unsafe
def insert_at(first: Bool,second: Bool,third: Bool,+status: A.Status,+d: F32,+a: A.Status,+da: F32,+b: A.Status,+db: F32,c: A.Status,dc: F32) -> Nearest:
  match first second third:
    case True{} _ _: Nearest{status,d,a,da,b,db}
    case False{} True{} _: Nearest{a,da,status,d,b,db}
    case False{} False{} True{}: Nearest{a,da,b,db,status,d}
    case _ _ _: Nearest{a,da,b,db,c,dc}

@unsafe
def nearest(+point: Q.Point,cache: A.Cache) -> Nearest:
  match point cache:
    case Q.Point{+x,+y,+z} A.Cache{layout,centers,_,_}: candidates(0,point,
      Q.Point{L.floor_cell(D.sub(x,D.scalar(5.0)),16),L.floor_cell(D.add(y,D.scalar(1.0)),12),L.floor_cell(D.sub(z,D.scalar(5.0)),16)},
      layout,centers,Nearest{A.Status{D.scalar(F32.neg(32512.0)),0},2147483647.0,A.Status{D.scalar(F32.neg(32512.0)),0},2147483647.0,A.Status{D.scalar(F32.neg(32512.0)),0},2147483647.0})

@unsafe
def candidates(+i: U32,+point: Q.Point,+anchor: Q.Point,+layout: A.Layout,+centers: Q.Table<A.Center>,+nearest: Nearest) -> Nearest:
  match i:
    case 12: nearest
    case _: candidate(Q.get(A.Center,centers,L.index(Q.shifted(anchor,D.unsigned(U32.div(i,6)),D.sub(D.unsigned(U32.mod(U32.div(i,2),3)),D.scalar(1.0)),D.unsigned(U32.mod(i,2))),layout),
      A.Center{Q.world(0,0,0),A.Status{D.scalar(F32.neg(32512.0)),0}}),i,point,anchor,layout,centers,nearest)

@unsafe
def candidate(center: A.Center,+i: U32,+point: Q.Point,anchor: Q.Point,layout: A.Layout,centers: Q.Table<A.Center>,nearest: Nearest) -> Nearest:
  match center:
    case A.Center{position,status}: candidates(U32.inc(i),point,anchor,layout,centers,insert(status,distance(position,point),nearest))

@unsafe
def pressure(+y: D.Number,noise: F32,+a: A.Status,+b: A.Status,+water: U32,+lava: U32,water_barriers: Bool) -> F32:
  pressure_types(Bool.and(water_barriers,Bool.or(Bool.and(U32.is_eq(L.material(a,y),water),U32.is_eq(L.material(b,y),lava)),
    Bool.and(U32.is_eq(L.material(b,y),water),U32.is_eq(L.material(a,y),lava)))),y,noise,a,b)

@unsafe
def pressure_types(mixed: Bool,y: D.Number,noise: F32,+a: A.Status,+b: A.Status) -> F32:
  match mixed a b:
    case True{} _ _: 2.0
    case False{} A.Status{+a,_} A.Status{+b,_}: pressure_difference(D.value(D.absolute(D.sub(a,b))),D.value(D.sub(D.add(y,D.scalar(0.5)),D.scale(D.add(a,b),0.5))),noise)

@unsafe
def pressure_difference(+difference: F32,+distance: F32,noise: F32) -> F32:
  pressure_edge(F32.is_eq(difference,0.0),F32.is_gt(distance,0.0),F32.sub(F32.mul(difference,0.5),F32.abs(distance)),noise)

@unsafe
def pressure_edge(equal: Bool,above: Bool,+edge: F32,noise: F32) -> F32:
  match equal:
    case True{}: 0.0
    case False{}: pressure_gradient(N.pick(above,F32.div(edge,N.pick(F32.is_gt(edge,0.0),1.5,2.5)),F32.div(F32.add(edge,3.0),N.pick(F32.is_gt(F32.add(edge,3.0),0.0),3.0,10.0))),noise)

@unsafe
def pressure_gradient(+gradient: F32,noise: F32) -> F32:
  F32.mul(2.0,F32.add(gradient,N.pick(Bool.and(F32.is_ge(gradient,F32.neg(2.0)),F32.is_le(gradient,2.0)),noise,0.0)))

@unsafe
def similar(a: F32,b: F32) -> F32:
  F32.sub(1.0,F32.div(F32.sub(b,a),25.0))

@unsafe
def result(+material: U32,lava: U32) -> U32:
  N.word(U32.is_eq(material,lava),2,N.word(U32.is_ne(material,0),1,0))

@unsafe
def substance(+point: Q.Point,+cache: A.Cache,density: F32) -> U32:
  match point cache:
    case Q.Point{_,+y,_} A.Cache{layout,centers,barriers,+params}: below_lava(U32.is_eq(L.material(L.global_status(params,y),y),lava(params)),point,A.Cache{layout,centers,barriers,params},density)

def lava(params: A.Params) -> U32:
  match params:
    case A.Params{_,_,lava,_,_}: lava

@unsafe
def below_lava(is_lava: Bool,+point: Q.Point,+cache: A.Cache,density: F32) -> U32:
  match is_lava:
    case True{}: 2
    case False{}: decide(nearest(point,cache),point,cache,density)

@unsafe
def decide(near: Nearest,point: Q.Point,cache: A.Cache,density: F32) -> U32:
  match near point cache:
    case Nearest{+a,+da,+b,+db,+c,+dc} Q.Point{+x,+y,+z} A.Cache{layout,centers,+barriers,+params}:
      selected(F32.is_le(similar(da,db),0.0),a,b,c,da,db,dc,Q.Point{x,y,z},barriers,params,density,result(L.material(a,y),lava(params)))

@unsafe
def selected(separate: Bool,+a: A.Status,+b: A.Status,+c: A.Status,+da: F32,+db: F32,+dc: F32,+point: Q.Point,barriers: A.Volume,+params: A.Params,density: F32,+result: U32) -> U32:
  match separate point params:
    case True{} _ _: result
    case False{} Q.Point{_,+y,_} A.Params{_,+water,+lava,_,+water_barriers}: pressure_selected(Bool.and(water_barriers,Bool.and(U32.is_eq(L.material(a,y),water),U32.is_eq(L.material(L.global_status(params,D.sub(y,D.scalar(1.0))),D.sub(y,D.scalar(1.0))),lava))),
      a,b,c,similar(da,db),similar(da,dc),similar(db,dc),y,barrier(barriers,point),water,lava,water_barriers,density,result)

@unsafe
def pressure_selected(skip: Bool,+a: A.Status,+b: A.Status,+c: A.Status,+s12: F32,+s13: F32,+s23: F32,+y: D.Number,+noise: F32,+water: U32,+lava: U32,+water_barriers: Bool,+density: F32,+result: U32) -> U32:
  match skip:
    case True{}: result
    case False{}: N.word(Bool.or(F32.is_gt(F32.add(F32.min(F32.neg(0.02),density),F32.mul(s12,pressure(y,noise,a,b,water,lava,water_barriers))),0.0),
      Bool.or(Bool.and(F32.is_gt(s13,0.0),F32.is_gt(F32.add(F32.min(F32.neg(0.02),density),F32.mul(F32.mul(s12,s13),pressure(y,noise,a,c,water,lava,water_barriers))),0.0)),
        Bool.and(F32.is_gt(s23,0.0),F32.is_gt(F32.add(F32.min(F32.neg(0.02),density),F32.mul(F32.mul(s12,s23),pressure(y,noise,b,c,water,lava,water_barriers))),0.0)))),3,result)
