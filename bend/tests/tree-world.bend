import Base
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../material_columns_data.bend as MD
import ../terrain_surface.bend as S
import ../density.bend as Q
import ../word64.bend as W
import ../noise.bend as N

@unsafe
def origin(+context: U32) -> W.U64:
  origin_kind(U32.mod(context,4))

@unsafe
def origin_kind(kind: U32) -> W.U64:
  match kind:
    case 0: W.U64{29990000,4264967296}
    case 1: W.U64{4294967168,713}
    case 2: W.U64{2147483616,2147483648}
    case _: W.U64{2147483648,2147483616}

@unsafe
def ground(+context: U32) -> U32:
  N.word(U32.is_eq(context,3),315,N.word(U32.is_eq(context,8),4294967264,0))

@unsafe
def columns(+n: U32,+base: U32,+policy: P.Policy,+context: U32) -> Q.Table<MD.Column>:
  split(U32.is_gt(n,1),n,base,policy,context)

@unsafe
def split(more: Bool,+n: U32,+base: U32,+policy: P.Policy,+context: U32) -> Q.Table<MD.Column>:
  match more policy:
    case False{} P.Policy{_,_,+biomes,water,_,bedrock,_}:
      +h = U32.add(U32.add(ground(context),64),N.word(Bool.and(U32.is_eq(context,9),U32.is_ge(U32.mod(base,32),17)),5,0))
      Q.Leaf{MD.Column{U32.sub(h,64),U32.mod(base,U32.max(1,Q.table_size(Q.Table<U32>,biomes))),0.0,0.0,0.0,0.0,0.0,
        [MD.Run{0,1,bedrock},MD.Run{1,U32.sub(h,2),1},MD.Run{U32.sub(h,2),U32.sub(h,1),10},
          MD.Run{U32.sub(h,1),h,N.word(U32.is_eq(context,7),1,N.word(U32.is_eq(context,10),5,9))},
          MD.Run{h,U32.max(h,68),N.word(U32.is_eq(context,1),water,0)},
          MD.Run{U32.max(h,68),U32.max(h,72),0},MD.Run{U32.max(h,72),U32.max(h,73),N.word(U32.is_eq(context,2),1,0)},
          MD.Run{U32.max(h,73),384,0}],8,Bool.or(U32.is_ge(context,12),U32.is_ne(base,1023))}}
    case True{} _:
      +half = U32.div(n,2)
      a b = columns(half,base,policy,context) columns(U32.sub(n,half),U32.add(base,half),policy,context)
      Q.Branch{n,a,b}

@unsafe
def world(+policy: P.Policy,+context: U32) -> E.World:
  located(origin(context),policy,context)

@unsafe
def located(at: W.U64,+policy: P.Policy,+context: U32) -> E.World:
  match at:
    case W.U64{x,z}: edited(E.World{MD.Grid{S.Tile{x,z,32,32,123456789,2147483648},4294967232,384,columns(1024,0,policy,context),None{}},policy,E.Empty{}},context)

@unsafe
def edited(+world: E.World,+context: U32) -> E.World:
  match context:
    case 11: edit_at(point(context),world)
    case _: world

@unsafe
def edit_at(at: P.Position,world: E.World) -> E.World:
  match at:
    case P.Position{x,y,z}: E.put(world,P.Position{x,U32.add(y,3),z},1)

@unsafe
def point(+context: U32) -> P.Position:
  positioned(origin(context),context)

@unsafe
def positioned(at: W.U64,+context: U32) -> P.Position:
  match at:
    case W.U64{x,z}: P.Position{U32.add(x,N.word(U32.is_eq(context,4),0,N.word(U32.is_eq(context,5),31,N.word(U32.is_eq(context,6),32,16)))),ground(context),U32.add(z,N.word(U32.is_eq(context,5),31,16))}
