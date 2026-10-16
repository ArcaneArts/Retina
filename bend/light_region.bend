import Base
import ./light_volume.bend as L
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./terrain_surface.bend as S
import ./density.bend as Q
import ./precise.bend as D
import ./compression.bend as Z
import ./chunk.bend as C

# Light only the interior, using the existing outer chunk ring as a halo.
# Each bounded GPU tile covers at most 96x96 core columns plus 16 on every side.
# Keep packed, uniform-compressed light words; discard optical/material bricks.
type Tile is Data:
  Tile{width: U32,depth: U32,words: Q.Table<L.Words>}
type Store is Data:
  Store{area: S.Tile,minimum: U32,height: U32,tiles: Q.Table<Tile>}
type View is Data:
  View{x: U32,z: U32,width: U32,depth: U32,height: U32,words: Q.Table<L.Words>}

def column(value: MD.Column,model: L.Model) -> L.Column:
  match value:
    case MD.Column{_,_,_,_,_,_,_,runs,_,_}: L.column(runs,model)

@unsafe
def columns(+n: U32,+base: U32,+dx: U32,+dz: U32,+width: U32,+stride: U32,+values: Q.Table<MD.Column>,+model: L.Model) -> Q.Table<L.Column>:
  match n:
    case 1: Q.Leaf{column(Q.get(MD.Column,values,U32.add(U32.add(dx,U32.mod(base,width)),U32.mul(stride,U32.add(dz,U32.div(base,width)))),MC.empty()),model)}
    case _:
      a b = columns(U32.div(n,2),base,dx,dz,width,stride,values,model) columns(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),dx,dz,width,stride,values,model)
      Q.Branch{n,a,b}

def source(grid: MD.Grid,x: U32,z: U32,+width: U32,+depth: U32,model: L.Model) -> L.Source:
  match grid:
    case MD.Grid{S.Tile{ox,oz,stride,_,_,_},_,height,values,_}: L.Source{width,U32.add(height,32),depth,
      columns(U32.mul(width,depth),0,U32.sub(x,ox),U32.sub(z,oz),width,stride,values,model)}

def light(brick: L.Brick) -> L.Words:
  match brick:
    case L.Brick{_,words,_}: words

@unsafe
def crop(+n: U32,+base: U32,+sx: U32,+sz: U32,+grid: L.Grid) -> Q.Table<L.Words>:
  match n:
    case 1: Q.Leaf{light(L.brick(grid,U32.add(2,U32.mod(base,sx)),U32.div(base,U32.mul(sx,sz)),U32.add(2,U32.mod(U32.div(base,sx),sz))))}
    case _:
      a b = crop(U32.div(n,2),base,sx,sz,grid) crop(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),sx,sz,grid)
      Q.Branch{n,a,b}

@unsafe
def tile(grid: L.Grid,+width: U32,+depth: U32,+height: U32) -> Tile:
  Tile{width,depth,crop(U32.div(U32.mul(U32.mul(width,depth),U32.add(height,32)),512),0,U32.div(width,8),U32.div(depth,8),grid)}

@unsafe
def generate(+grid: MD.Grid,+model: L.Model,x: U32,z: U32,+width: U32,+depth: U32,+height: U32) -> IO(Tile):
  do IO<Tile>:
    input : L.Source = source(grid,U32.sub(x,16),U32.sub(z,16),U32.add(width,32),U32.add(depth,32),model)
    lit : L.Grid <- L.run(input,model)
    IO.pure(Tile,tile(lit,width,depth,height))

@unsafe
def collect(+n: U32,+i: U32,+nx: U32,+area: S.Tile,+grid: MD.Grid,+model: L.Model,+height: U32,acc: List<&2,Tile>) -> IO(Store):
  match n area:
    case 0 _:
      finish(area,grid,height,Q.prepared_table(Tile,List.reverse(&2,Tile,acc)))
    case _ S.Tile{+x,+z,+width,+depth,_,_}:
      do IO<Store>:
        result : Tile <- generate(grid,model,U32.add(x,U32.add(16,U32.mul(U32.mod(i,nx),96))),U32.add(z,U32.add(16,U32.mul(U32.div(i,nx),96))),
          U32.min(96,U32.sub(U32.sub(width,32),U32.mul(U32.mod(i,nx),96))),
          U32.min(96,U32.sub(U32.sub(depth,32),U32.mul(U32.div(i,nx),96))),height)
        collect(U32.sub(n,1),U32.inc(i),nx,area,grid,model,height,result <> acc)

def finish(area: S.Tile,grid: MD.Grid,height: U32,tiles: Q.Table<Tile>) -> IO(Store):
  match grid:
    case MD.Grid{_,minimum,_,_,_}: IO.pure(Store,Store{area,minimum,height,tiles})

# Every source column must be present, including the halo; bounds are checked
# against actual resident terrain, not an external flag or cached assumption.
@unsafe
def covered(+area: S.Tile,+grid: MD.Grid) -> Bool:
  match area grid:
    case S.Tile{+x,+z,+width,+depth,low,high} MD.Grid{S.Tile{+ox,+oz,+w,+d,lo,hi},_,_,_,_}:
      Bool.and(Bool.and(U32.is_eq(low,lo),U32.is_eq(high,hi)),
        Bool.and(Bool.and(Bool.not(D.less(D.integer(x),D.integer(ox))),Bool.not(D.less(D.integer(z),D.integer(oz)))),
          Bool.and(Bool.not(D.less(D.add(D.integer(ox),D.unsigned(w)),D.add(D.integer(x),D.unsigned(width)))),
            Bool.not(D.less(D.add(D.integer(oz),D.unsigned(d)),D.add(D.integer(z),D.unsigned(depth)))))))

@unsafe
def valid(+area: S.Tile,+grid: MD.Grid) -> Bool:
  match area grid:
    case S.Tile{x,z,+width,+depth,_,_} MD.Grid{_,+minimum,+height,_,_}:
      Bool.and(covered(area,grid),Bool.and(Bool.and(U32.is_ge(width,48),U32.is_le(width,512)),
        Bool.and(Bool.and(U32.is_ge(depth,48),U32.is_le(depth,512)),
          Bool.and(U32.is_zero(U32.and(U32.or(U32.or(x,z),U32.or(U32.or(width,depth),U32.or(minimum,height))),15)),
            Bool.and(U32.is_gt(height,0),U32.is_le(height,4064))))))

@unsafe
def run(+area: S.Tile,+grid: MD.Grid,model: L.Model) -> IO(Result<&1,&1,U32,Store>):
  accepted(valid(area,grid),area,grid,model)

def accepted(ok: Bool,+area: S.Tile,+grid: MD.Grid,model: L.Model) -> IO(Result<&1,&1,U32,Store>):
  match ok area grid:
    case False{} _ _: IO.pure(Result<&1,&1,U32,Store>,Fail{885})
    case True{} S.Tile{_,_,+width,depth,_,_} MD.Grid{_,_,+height,_,_}:
      do IO<Result<&1,&1,U32,Store>>:
        result : Store <- collect(U32.mul(U32.div(U32.add(width,63),96),U32.div(U32.add(depth,63),96)),0,U32.div(U32.add(width,63),96),area,grid,model,height,[])
        IO.pure(Result<&1,&1,U32,Store>,Done{result})

@unsafe
def view(store: Maybe<&2,Store>,+x: U32,+z: U32,low: U32,high: U32,minimum: U32,height: U32) -> Maybe<&2,View>:
  match store:
    case None{}: None{}
    case Some{Store{S.Tile{ox,oz,+width,+depth,lo,hi},base,+span,tiles}}:
      +dx = U32.sub(U32.mul(x,16),ox)
      +dz = U32.sub(U32.mul(z,16),oz)
      selected(Bool.and(Bool.and(U32.is_eq(low,lo),U32.is_eq(high,hi)),Bool.and(Bool.and(U32.is_eq(minimum,base),U32.is_eq(height,span)),
        Bool.and(Bool.and(U32.is_ge(dx,16),U32.is_lt(dx,U32.sub(width,16))),Bool.and(U32.is_ge(dz,16),U32.is_lt(dz,U32.sub(depth,16)))))),
        U32.sub(dx,16),U32.sub(dz,16),U32.div(U32.add(width,63),96),span,tiles)

@unsafe
def selected(ok: Bool,+dx: U32,+dz: U32,nx: U32,height: U32,tiles: Q.Table<Tile>) -> Maybe<&2,View>:
  match ok:
    case False{}: None{}
    case True{}: selected_tile(Q.get(Tile,tiles,U32.add(U32.div(dx,96),U32.mul(nx,U32.div(dz,96))),Tile{0,0,Q.Empty{}}),U32.mod(dx,96),U32.mod(dz,96),height)

def selected_tile(tile: Tile,x: U32,z: U32,height: U32) -> Maybe<&2,View>:
  match tile:
    case Tile{width,depth,words}: Some{View{x,z,width,depth,height,words}}

@unsafe
def byte(words: Q.Table<L.Words>,+sx: U32,+sz: U32,+x: U32,+y: U32,+z: U32) -> U32:
  value_at(words,U32.add(U32.div(x,8),U32.mul(sx,U32.add(U32.div(z,8),U32.mul(sz,U32.div(y,8))))),
    U32.add(U32.mod(x,8),U32.mul(8,U32.add(U32.mod(z,8),U32.mul(8,U32.mod(y,8))))))

@unsafe
def value_at(values: Q.Table<L.Words>,+i: U32,+voxel: U32) -> U32:
  match values:
    case Q.Empty{}: 0
    case Q.Leaf{words}: U32.and(U32.shrn(L.get(words,U32.div(voxel,4),64),U32.to_nat(U32.mul(U32.mod(voxel,4),8))),255)
    case Q.Branch{_,+a,b}: value_side(U32.is_lt(i,Q.table_size(L.Words,a)),a,b,i,voxel)

@unsafe
def value_side(left: Bool,+a: Q.Table<L.Words>,b: Q.Table<L.Words>,i: U32,voxel: U32) -> U32:
  match left:
    case True{}: value_at(a,i,voxel)
    case False{}: value_at(b,U32.sub(i,Q.table_size(L.Words,a)),voxel)

@unsafe
def packed(n: U32,+i: U32,+view: View,+offset: U32,block: Array<U32>,sky: Array<U32>) -> C.Light:
  match n view:
    case 0 _: C.Light{Z.Bytes{block,2048},Z.Bytes{sky,2048}}
    case _ View{+x,+z,+width,+depth,_,+words}:
      +px = U32.add(x,U32.mod(U32.mul(i,2),16))
      +py = U32.add(offset,U32.div(i,128))
      +pz = U32.add(z,U32.mod(U32.div(i,8),16))
      pair(byte(words,U32.div(width,8),U32.div(depth,8),px,py,pz),byte(words,U32.div(width,8),U32.div(depth,8),U32.inc(px),py,pz),n,i,view,offset,block,sky)

@unsafe
def pair(+a: U32,+b: U32,n: U32,+i: U32,view: View,offset: U32,block: Array<U32>,sky: Array<U32>) -> C.Light:
  packed(U32.sub(n,1),U32.inc(i),view,offset,Array.set(U32,block,i,U32.or(U32.and(a,15),U32.shln(U32.and(b,15),4n))),
    Array.set(U32,sky,i,U32.or(U32.shrn(a,4n),U32.and(b,240))))

def section(view: Maybe<&2,View>,index: U32) -> Maybe<&1,C.Light>:
  match view:
    case None{}: None{}
    case Some{view}: Some{packed(2048,0,view,U32.mul(U32.inc(index),16),[0:U32*2048n],[0:U32*2048n])}

@unsafe
def boundary(view: Maybe<&2,View>) -> Maybe<&1,C.Boundary>:
  match view:
    case None{}: None{}
    case Some{+view}: ends(view)

def ends(+view: View) -> Maybe<&1,C.Boundary>:
  match view:
    case View{_,_,_,_,height,_}: Some{C.Boundary{packed(2048,0,view,0,[0:U32*2048n],[0:U32*2048n]),
      packed(2048,0,view,U32.add(height,16),[0:U32*2048n],[0:U32*2048n])}}
