import Base
import ./density.bend as Q
import ./density_lattice.bend as L
import ./terrain_surface.bend as S
import ./precise.bend as D

# Shared integer terrain geometry for materials and cave-biome roof retention.
# Cache each column's bilinear density layers once, then interpolate only Y.
# This avoids fetching eight six-root lattice outputs for every single block.
type Vertical is Data:
  Vertical{origin: D.Number,step: U32,values: Q.Table<D.Number>,explicit: Maybe<&2,D.Number>}
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


def height(+config: S.Config,density: L.Grid,point: Q.Point) -> U32:
  match config:
    case S.Config{minimum,span,_,_,_,_}: height_field(vertical(config,density,point),minimum,span)
