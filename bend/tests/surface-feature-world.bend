import Base
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../material_columns_data.bend as MD
import ../terrain_surface.bend as S
import ../density.bend as Q
import ../word64.bend as W
import ../noise.bend as N
import ./placement-world-fixtures.bend as Old

@unsafe
def columns(+n: U32,+base: U32,+policy: P.Policy,+context: U32) -> Q.Table<MD.Column>:
  split(U32.is_gt(n,1),n,base,policy,context)

@unsafe
def split(more: Bool,+n: U32,+base: U32,+policy: P.Policy,+context: U32) -> Q.Table<MD.Column>:
  match more policy:
    case False{} P.Policy{+masks,_,+biomes,water,_,bedrock,_}:
      Q.Leaf{MD.Column{0,U32.mod(base,U32.max(1,Q.table_size(Q.Table<U32>,biomes))),0.0,0.0,0.0,0.0,0.0,
        [MD.Run{0,1,bedrock},MD.Run{1,55,1},MD.Run{55,63,N.word(U32.is_gt(Q.table_size(U32,masks),8),10,1)},
         MD.Run{63,64,N.word(U32.is_gt(Q.table_size(U32,masks),8),9,1)},
         MD.Run{64,66,N.word(U32.is_eq(U32.mod(context,4),1),water,0)},
         MD.Run{66,67,N.word(U32.is_eq(U32.mod(context,4),2),1,N.word(U32.is_eq(U32.mod(context,4),1),water,0))},
         MD.Run{67,68,N.word(U32.is_eq(U32.mod(context,4),1),water,0)},MD.Run{68,128,0}],6,U32.is_ne(base,1023)}}
    case True{} _:
      +half = U32.div(n,2)
      a b = columns(half,base,policy,context) columns(U32.sub(n,half),U32.add(base,half),policy,context)
      Q.Branch{n,a,b}

@unsafe
def world(+policy: P.Policy,+context: U32) -> E.World:
  located(Old.origin(context),policy,context)

@unsafe
def located(at: W.U64,+policy: P.Policy,+context: U32) -> E.World:
  match at:
    case W.U64{x,z}: E.World{MD.Grid{S.Tile{x,z,32,32,123456789,2147483648},4294967232,128,columns(1024,0,policy,context),None{}},policy,E.Empty{}}

@unsafe
def point(+context: U32) -> P.Position:
  positioned(Old.origin(context),U32.mod(context,8),U32.is_ge(context,8))

@unsafe
def positioned(at: W.U64,+index: U32,+high: Bool) -> P.Position:
  match at:
    case W.U64{x,z}: P.Position{U32.add(x,Q.get(U32,Q.prepared_table(U32,[8,8,8,8,0,31,32,8]),index,0)),
      Q.get(U32,Q.prepared_table(U32,[N.word(high,1,0),0,0,63,4294967232,0,0,N.word(high,64,4294967231)]),index,0),
      U32.add(z,N.word(U32.is_eq(index,5),31,8))}
