import Base
import ./ore_data.bend as O
import ./ore_geometry.bend as G
import ./density.bend as Q
import ./precise.bend as D
import ./noise.bend as N
import ./reader.bend as R
import ./binary.bend as B

# Local Y/Z/X union masks. Each parallel leaf owns one U32 output word;
# overlapping spheres never race and never produce duplicate voxel records.
type Layout is Data:
  Layout{x: U32,y: U32,z: U32,nx: U32,ny: U32,nz: U32}
type Bounds is Data:
  Bounds{active: Bool,x0: F32,y0: F32,z0: F32,x1: F32,y1: F32,z1: F32}
type Sphere is Data:
  Sphere{value: Q.Vec4,inverse: F32}
type Row is Data:
  Mask{layout: Layout,words: Q.Table<U32>}
  Scatter{layout: Layout,points: List<&2,G.Piece>}
type Computed is Type:
  Computed{rows: Array<Row>}

def empty_bounds() -> Bounds:
  Bounds{False{},0.0,0.0,0.0,0.0,0.0,0.0}

@unsafe
def expanded(box: Bounds,x0: F32,y0: F32,z0: F32,x1: F32,y1: F32,z1: F32) -> Bounds:
  match box:
    case Bounds{False{},_,_,_,_,_,_}: Bounds{True{},x0,y0,z0,x1,y1,z1}
    case Bounds{True{},a,b,c,d,e,f}:
      Bounds{True{},F32.min(a,x0),F32.min(b,y0),F32.min(c,z0),F32.max(d,x1),F32.max(e,y1),F32.max(f,z1)}

@unsafe
def live(xs: List<&2,G.Piece>) -> List<&2,Sphere>:
  match xs:
    case Nil{}: []
    case G.Piece{x,y,z,+radius} <> rest:
      live_piece(F32.is_gt(R.float(radius),0.0),x,y,z,R.float(radius),rest)

@unsafe
def live_piece(active: Bool,x: U32,y: U32,z: U32,+radius: F32,rest: List<&2,G.Piece>) -> List<&2,Sphere>:
  match active:
    case False{}: live(rest)
    case True{}: Sphere{Q.Vec4{R.float(x),R.float(y),R.float(z),radius},F32.div(1.0,radius)} <> live(rest)

@unsafe
def sphere_bounds(xs: List<&2,Sphere>,box: Bounds) -> Bounds:
  match xs:
    case Nil{}: box
    case Sphere{Q.Vec4{+x,+y,+z,+r},_} <> rest:
      sphere_bounds(rest,expanded(box,F32.sub(x,r),F32.sub(y,r),F32.sub(z,r),F32.add(x,r),F32.add(y,r),F32.add(z,r)))

@unsafe
def scatter_bounds(xs: List<&2,G.Piece>,box: Bounds) -> Bounds:
  match xs:
    case Nil{}: box
    case G.Piece{x,y,z,_} <> rest:
      scatter_bound(rest,box,D.value(D.integer(x)),D.value(D.integer(y)),D.value(D.integer(z)))

@unsafe
def scatter_bound(rest: List<&2,G.Piece>,box: Bounds,+x: F32,+y: F32,+z: F32) -> Bounds:
  scatter_bounds(rest,expanded(box,x,y,z,x,y,z))

@unsafe
def layout(box: Bounds) -> Layout:
  match box:
    case Bounds{False{},_,_,_,_,_,_}: Layout{0,0,0,0,0,0}
    case Bounds{True{},x0,y0,z0,x1,y1,z1}:
      layout_floored(F32.floor(x0),F32.floor(y0),F32.floor(z0),F32.floor(x1),F32.floor(y1),F32.floor(z1))

@unsafe
def layout_floored(+x0: F32,+y0: F32,+z0: F32,x1: F32,y1: F32,z1: F32) -> Layout:
  Layout{N.signed(x0),N.signed(y0),N.signed(z0),F32.to_u32(F32.add(F32.sub(x1,x0),1.0)),
    F32.to_u32(F32.add(F32.sub(y1,y0),1.0)),F32.to_u32(F32.add(F32.sub(z1,z0),1.0))}

def volume(layout: Layout) -> U32:
  match layout:
    case Layout{_,_,_,nx,ny,nz}: U32.mul(U32.mul(nx,ny),nz)

@unsafe
def point(+index: U32,layout: Layout) -> Q.Vec4:
  match layout:
    case Layout{x,y,z,+nx,_,+nz}:
      Q.Vec4{F32.add(D.value(D.integer(U32.add(x,U32.mod(index,nx)))),0.5),
        F32.add(D.value(D.integer(U32.add(y,U32.div(index,U32.mul(nx,nz))))),0.5),
        F32.add(D.value(D.integer(U32.add(z,U32.mod(U32.div(index,nx),nz)))),0.5),0.0}

@unsafe
def squared(a: F32,b: F32,inverse: F32) -> F32:
  +delta = F32.mul(F32.sub(a,b),inverse)
  F32.mul(delta,delta)

@unsafe
def hit(sphere: Sphere,p: Q.Vec4) -> Bool:
  match sphere p:
    case Sphere{Q.Vec4{x,y,z,_},+inverse} Q.Vec4{a,b,c,_}:
      F32.is_lt(F32.add(F32.add(squared(b,y,inverse),squared(c,z,inverse)),squared(a,x,inverse)),1.0)

@unsafe
def inside(xs: List<&2,Sphere>,+p: Q.Vec4) -> Bool:
  match xs:
    case Nil{}: False{}
    case sphere <> rest: found(hit(sphere,p),rest,p)

@unsafe
def found(ok: Bool,xs: List<&2,Sphere>,p: Q.Vec4) -> Bool:
  match ok:
    case True{}: True{}
    case False{}: inside(xs,p)

@unsafe
def bits(n: U32,+index: U32,word: U32,+layout: Layout,+size: U32,xs: List<&2,Sphere>) -> U32:
  match n:
    case 0: word
    case _: within(U32.is_lt(index,size),n,index,word,layout,size,xs)

@unsafe
def within(ok: Bool,n: U32,+index: U32,word: U32,+layout: Layout,size: U32,+xs: List<&2,Sphere>) -> U32:
  match ok:
    case False{}: word
    case True{}: bits(U32.sub(n,1),U32.inc(index),U32.or(word,
      U32.shln(N.word(inside(xs,point(index,layout)),1,0),U32.to_nat(U32.mod(index,32)))),layout,size,xs)

@unsafe
def words(+n: U32,+index: U32,+layout: Layout,+size: U32,+xs: List<&2,Sphere>) -> Q.Table<U32>:
  match n:
    case 0: Q.Empty{}
    case 1: Q.Leaf{bits(32,U32.mul(index,32),0,layout,size,xs)}
    case _:
      +half = U32.div(n,2)
      a b = words(half,index,layout,size,xs) words(U32.sub(n,half),U32.add(index,half),layout,size,xs)
      Q.Branch{n,a,b}

@unsafe
def regular(+xs: List<&2,Sphere>) -> Row:
  regular_layout(layout(sphere_bounds(xs,empty_bounds())),xs)

@unsafe
def regular_layout(+layout: Layout,xs: List<&2,Sphere>) -> Row:
  +size = volume(layout)
  Mask{layout,words(U32.div(U32.add(size,31),32),0,layout,size,xs)}

@unsafe
def geometry(row: G.Row) -> Row:
  match row:
    case G.Row{0,xs}: regular(live(xs))
    case G.Row{_,+xs}: Scatter{layout(scatter_bounds(xs,empty_bounds())),xs}

def row(query: G.Query,store: O.Store) -> Row:
  geometry(G.row(query,store))

@unsafe
def calculate(+store: O.Store,queries: Array<G.Query>) -> Array<Row>:
  match queries:
    case ALeaf{query}: ALeaf{row(query,store)}
    case ANode{a,b}:
      a b = calculate(store,a) calculate(store,b)
      ANode{a,b}

@unsafe
def execute(store: O.Store,queries: Array<G.Query>) -> Computed:
  rows : Array<Row> = calculate!(store,queries)
  Computed{rows}

@unsafe
def header(layout: Layout,kind: U32,count: U32,w: B.Writer) -> B.Writer:
  match layout:
    case Layout{x,y,z,nx,ny,nz}:
      B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(w,kind),x),y),z),nx),ny),nz),count)

@unsafe
def output(n: U32,+xs: Q.Table<U32>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0: w
    case _: output(U32.sub(n,1),xs,B.int(w,Q.get(U32,xs,i,0)),U32.inc(i))

@unsafe
def output_row(row: Row,w: B.Writer) -> B.Writer:
  match row:
    case Mask{layout,+xs}: output(Q.table_size(U32,xs),xs,header(layout,0,Q.table_size(U32,xs),w),0)
    case Scatter{layout,+xs}: G.output(xs,header(layout,1,U32.from_nat(List.length(&2,G.Piece,xs)),w))

@unsafe
def encode(n: Nat,rows: Array<Row>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: encoded(p,Array.get(Row,rows,i),w,i)

@unsafe
def encoded(n: Nat,pair: Array<Row> & Row,w: B.Writer,+i: U32) -> B.Writer:
  (rows,row) = pair
  encode(n,rows,output_row(row,w),U32.inc(i))
