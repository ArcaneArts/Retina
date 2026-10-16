import Base
import ./density.bend as Q
import ./terrain_surface.bend as S
import ./compression.bend as Z
import ./binary.bend as B
import ./chunk.bend as C

# Small encoded metadata stays immutable alongside the final resident grid.
# This lets independent chunk encoders share it without owning the registry tape.
type Store is Data:
  Store{tile: S.Tile,fields: Q.Table<Q.Table<U32>>}

@unsafe
def freeze(a: Array<U32>,+i: U32,n: U32,acc: List<&2,U32>) -> Q.Table<U32>:
  match n:
    case 0: Q.prepared_table(U32,List.reverse(&2,U32,acc))
    case _: Z.read(Q.Table<U32>,a,i,a => byte => freeze(a,U32.inc(i),U32.sub(n,1),byte <> acc))

@unsafe
def encoded(result: Result<&1,&1,U32,Z.Bytes>) -> Result<&1,&1,U32,Q.Table<U32>>:
  match result:
    case Fail{code}: Fail{code}
    case Done{Z.Bytes{a,n}}: Done{freeze(a,0,n,[])}

@unsafe
def write(xs: Q.Table<U32>,w: B.Writer) -> B.Writer:
  match xs:
    case Q.Empty{}: w
    case Q.Leaf{byte}: B.byte(w,byte)
    case Q.Branch{_,a,b}: write(b,write(a,w))

@unsafe
def selected(ok: Bool,fields: Q.Table<Q.Table<U32>>,index: U32) -> C.Metadata:
  match ok:
    case False{}: C.EncodedMetadata{Fail{880}}
    case True{}: C.EncodedMetadata{B.done(write(Q.get(Q.Table<U32>,fields,index,Q.Empty{}),B.new()))}

@unsafe
def get(store: Maybe<&2,Store>,+x: U32,+z: U32,low: U32,high: U32) -> C.Metadata:
  match store:
    case None{}: C.Metadata{[],[],[],[]}
    case Some{Store{S.Tile{ox,oz,+width,+depth,lo,hi},fields}}:
      +dx = U32.sub(U32.mul(x,16),ox)
      +dz = U32.sub(U32.mul(z,16),oz)
      selected(Bool.and(Bool.and(U32.is_eq(low,lo),U32.is_eq(high,hi)),Bool.and(
        Bool.and(U32.is_lt(dx,width),U32.is_lt(dz,depth)),Bool.and(U32.is_zero(U32.and(dx,15)),U32.is_zero(U32.and(dz,15))))),
        fields,U32.add(U32.div(dx,16),U32.mul(U32.div(dz,16),U32.div(width,16))))
