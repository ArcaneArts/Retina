import Base
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../material_columns_data.bend as MD
import ../terrain_surface.bend as S
import ../density.bend as Q
import ../word64.bend as W
import ./placement-world-fixtures.bend as Old

@unsafe
def columns(+n: U32,+base: U32,+policy: P.Policy) -> Q.Table<MD.Column>:
  split(U32.is_gt(n,1),n,base,policy)

@unsafe
def split(+more: Bool,+n: U32,+base: U32,+policy: P.Policy) -> Q.Table<MD.Column>:
  match more policy:
    case False{} P.Policy{_,_,+biomes,water,_,bedrock,_}:
      Q.Leaf{MD.Column{4294967256,U32.mod(U32.sub(1023,base),U32.max(1,Q.table_size(Q.Table<U32>,biomes))),0.0,0.0,0.0,0.0,0.0,
        [MD.Run{0,1,bedrock},MD.Run{1,10,1},MD.Run{10,13,0},MD.Run{13,20,1},MD.Run{20,24,water},MD.Run{24,128,0}],6,U32.is_ne(base,1023)}}
    case True{} _:
      +half = U32.div(n,2)
      a b = columns(half,base,policy) columns(U32.sub(n,half),U32.add(base,half),policy)
      Q.Branch{n,a,b}

@unsafe
def world(+policy: P.Policy,+context: U32) -> E.World:
  located(Old.origin(context),policy)

@unsafe
def located(at: W.U64,+policy: P.Policy) -> E.World:
  match at:
    case W.U64{x,z}: E.World{MD.Grid{S.Tile{x,z,32,32,123456789,2147483648},4294967232,128,columns(1024,0,policy),None{}},policy,E.Empty{}}

@unsafe
def origin(+context: U32) -> P.Position:
  positioned(Old.origin(context))

def positioned(at: W.U64) -> P.Position:
  match at:
    case W.U64{x,z}: P.Position{x,4294967256,z}

@unsafe
def edit(+world: E.World,+at: P.Position,+context: U32) -> E.World:
  mode(U32.mod(context,3),world,at)

@unsafe
def mode(+kind: U32,+world: E.World,+at: P.Position) -> E.World:
  match kind at:
    case 0 _: world
    case 1 _: E.put(world,at,1)
    case _ P.Position{x,y,z}: E.put(E.put(world,P.Position{x,U32.sub(y,1),z},0),at,1)
