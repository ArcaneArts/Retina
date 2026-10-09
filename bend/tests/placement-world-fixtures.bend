import Base
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../placement_checks.bend as Check
import ../material_columns_data.bend as MD
import ../terrain_surface.bend as S
import ../density.bend as Q
import ../binary.bend as B
import ../noise.bend as N
import ../word64.bend as W
import ../cave_lattice.bend as CV
import ../precise.bend as D

@unsafe
def origin(+i: U32) -> W.U64:
  origin_kind(U32.mod(i,4))

def origin_kind(i: U32) -> W.U64:
  match i:
    case 0: W.U64{29990000,4264967296}
    case 1: W.U64{4294967168,713}
    case 2: W.U64{2147483632,2147483648}
    case _: W.U64{2147483648,2147483632}

@unsafe
def columns(+n: U32,+policy: P.Policy) -> List<&2,MD.Column>:
  match n policy:
    case 0 _: []
    case _ P.Policy{_,_,+biomes,water,_,bedrock,_}:
      MD.Column{4294967256,U32.mod(U32.sub(n,1),U32.max(1,Q.table_size(Q.Table<U32>,biomes))),0.0,0.0,0.0,0.0,0.0,
        [MD.Run{0,1,bedrock},MD.Run{1,10,1},MD.Run{10,13,0},MD.Run{13,20,1},MD.Run{20,24,water},MD.Run{24,128,0}],6,U32.is_ne(n,1)} <> columns(U32.sub(n,1),policy)

@unsafe
def source(+policy: P.Policy,+i: U32) -> MD.Grid:
  source_at(origin(i),policy,i)

@unsafe
def source_at(at: W.U64,+policy: P.Policy,+i: U32) -> MD.Grid:
  match at:
    case W.U64{+x,+z}: MD.Grid{S.Tile{x,z,4,4,123456789,2147483648},4294967232,128,Q.prepared_table(MD.Column,columns(16,policy)),volume(U32.is_zero(U32.mod(i,2)),policy,x,z)}

@unsafe
def volume(+present: Bool,+policy: P.Policy,+x: U32,+z: U32) -> Maybe<&2,CV.Grid>:
  match present policy:
    case False{} _: None{}
    case True{} P.Policy{_,_,biomes,_,_,_,_}: Some{CV.Grid{CV.Layout{Q.Point{D.integer(x),D.integer(4294967232),D.integer(z)},2,33,2,123456789,2147483648},Q.prepared_table(CV.Sample,samples(132,0,Q.table_size(Q.Table<U32>,biomes)))}}

@unsafe
def samples(+n: U32,+i: U32,+biomes: U32) -> List<&2,CV.Sample>:
  match n:
    case 0: []
    case _: CV.Sample{Q.Vec4{0.0,0.0,0.0,0.0},U32.mod(i,U32.max(biomes,1))} <> samples(U32.sub(n,1),U32.inc(i),biomes)

@unsafe
def edit(+world: E.World,+n: U32,+i: U32,+origin: W.U64) -> E.World:
  match world n origin:
    case _ 0 _: world
    case E.World{_,P.Policy{_,_,_,water,_,_,_},_} _ W.U64{x,z}:
      edit(E.put(world,P.Position{U32.add(x,U32.mod(i,4)),U32.add(4294967236,U32.mod(U32.mul(U32.mod(i,8),17),40)),U32.add(z,U32.mod(U32.div(i,4),2))},
        N.word(U32.is_zero(U32.mod(i,3)),0,N.word(U32.is_eq(U32.mod(i,3),1),1,water))),U32.sub(n,1),U32.inc(i),origin)

@unsafe
def world(+policy: P.Policy,+i: U32) -> E.World:
  edit(E.World{source(policy,i),policy,E.Empty{}},i,0,origin(i))

def optional(value: Maybe<&2,U32>) -> U32:
  match value:
    case None{}: 4294967295
    case Some{v}: v

def boolean(value: Maybe<&2,Bool>) -> U32:
  match value:
    case None{}: 2
    case Some{v}: W.flag(v)

def y(value: Maybe<&2,P.Position>) -> U32:
  match value:
    case None{}: 4294967295
    case Some{P.Position{_,y,_}}: y

@unsafe
def match_material(+offset: P.Position,+id: U32) -> P.Predicate:
  P.Material{offset,Q.Leaf{id}}

@unsafe
def query(+world: E.World,+kind: U32,+at: P.Position) -> U32:
  match world kind at:
    case E.World{+grid,+policy,_} 0 _: optional(E.material(world,at))
    case _ 1 P.Position{x,_,z}: optional(E.height(world,x,z,0))
    case _ 2 P.Position{x,_,z}: optional(E.height(world,x,z,1))
    case _ 3 P.Position{x,_,z}: optional(E.height(world,x,z,2))
    case _ 4 P.Position{x,_,z}: optional(E.height(world,x,z,3))
    case _ 5 P.Position{x,_,z}: optional(E.height(world,x,z,4))
    case _ 6 P.Position{x,_,z}: optional(E.height(world,x,z,5))
    case _ 7 P.Position{x,_,z}: optional(E.ground(world,x,z,0))
    case _ 8 P.Position{x,_,z}: optional(E.ground(world,x,z,1))
    case _ 9 P.Position{x,_,z}: optional(E.ground(world,x,z,2))
    case _ 10 P.Position{x,_,z}: optional(E.ground(world,x,z,3))
    case _ 11 _: optional(E.biome(world,at))
    case _ 12 P.Position{x,y,z}: optional(E.material(world,P.Position{U32.add(x,16),y,z}))
    case _ 13 P.Position{x,_,z}: optional(E.height(world,U32.add(x,16),z,4))
    case _ 14 P.Position{x,y,z}: optional(E.material(E.put(world,P.Position{U32.add(x,16),y,z},1),at))
    case _ 15 _: boolean(Check.predicate(match_material(P.Position{0,0,0},1),world,at))
    case _ 16 _: boolean(Check.predicate(P.Not{match_material(P.Position{16,0,0},1)},world,at))
    case _ 17 _: boolean(Check.predicate(P.All{[match_material(P.Position{16,0,0},1),P.Not{P.Always{}}]},world,at))
    case _ 18 _: boolean(Check.predicate(P.Any{[match_material(P.Position{16,0,0},1),P.Always{}]},world,at))
    case _ 19 _: boolean(Check.predicate(P.All{[match_material(P.Position{16,0,0},1),P.Always{}]},world,at))
    case _ 20 _: boolean(Check.predicate(P.Any{[match_material(P.Position{16,0,0},1),P.Not{P.Always{}}]},world,at))
    case _ 21 P.Position{x,_,z}: y(Check.evaluate(P.Scan{4294967295,match_material(P.Position{0,0,0},1),P.Not{match_material(P.Position{0,0,0},1)},32},world,P.Position{x,4294967256,z},0))
    case _ 22 P.Position{x,_,z}: y(Check.evaluate(P.Scan{4294967295,P.Always{},P.Not{P.Always{}},32},world,P.Position{x,4294967256,z},0))
    case _ 23 P.Position{x,_,z}: y(Check.evaluate(P.Scan{4294967295,match_material(P.Position{0,0,0},1),P.Always{},3},world,P.Position{x,4294967256,z},0))
    case _ 24 _: y(Check.evaluate(P.Relative{4,2147483648,2147483647},world,at,0))
    case _ 25 _: y(Check.evaluate(P.WaterDepth{4},world,at,0))
    case _ 26 _: y(Check.evaluate(P.Biome{},world,at,0))
    case _ 27 _: y(Check.evaluate(P.Heightmap{4},world,at,0))
    case E.World{_,P.Policy{masks,_,_,_,_,_,_},_} 28 _: optional(E.material(E.put(world,at,Q.table_size(U32,masks)),at))
    case _ 29 _: y(P.shift(P.Position{2147483647,0,0},P.Position{1,0,0}))
    case E.World{grid,policy,_} 30 P.Position{x,_,z}: optional(E.height(E.World{grid,policy,E.Empty{}},x,z,4))
    case _ _ P.Position{x,_,z}: optional(E.material(world,P.Position{x,64,z}))

@unsafe
def value(+model: P.Model,+id: U32) -> U32:
  match model:
    case P.Model{_,_,+policy}: located(world(policy,U32.div(id,32)),origin(U32.div(id,32)),U32.div(id,32),U32.mod(id,32))

@unsafe
def located(+world: E.World,+origin: W.U64,+context: U32,+kind: U32) -> U32:
  match origin:
    case W.U64{x,z}: query(world,kind,P.Position{U32.add(x,U32.mod(context,4)),U32.add(4294967232,U32.mod(U32.mul(context,7),128)),U32.add(z,U32.mod(U32.div(context,4),4))})

@unsafe
def tree(+n: U32,+base: U32,+model: P.Model,+reverse: Bool) -> Q.Table<U32>:
  split(U32.is_gt(n,1),n,base,model,reverse)

@unsafe
def split(+more: Bool,+n: U32,+base: U32,+model: P.Model,+reverse: Bool) -> Q.Table<U32>:
  match more:
    case False{}: Q.Leaf{value(model,N.word(reverse,U32.sub(1023,base),base))}
    case True{}:
      +half = U32.div(n,2)
      a b = tree(half,base,model,reverse) tree(U32.sub(n,half),U32.add(base,half),model,reverse)
      Q.Branch{n,a,b}

@unsafe
def fill(+n: Nat,+i: U32,+table: Q.Table<U32>,+reverse: Bool,array: Array<U32>) -> Array<U32>:
  match n:
    case 0n: array
    case 1n+p: fill(p,U32.inc(i),table,reverse,Array.set(U32,array,i,Q.get(U32,table,N.word(reverse,U32.sub(1023,i),i),0)))

@unsafe
def values(+model: P.Model,+reverse: Bool) -> Array<U32>:
  fill(1024n,0,tree(1024,0,model,reverse),reverse,Array.new(U32,10n,0))

@unsafe
def write(+n: Nat,+i: U32,array: Array<U32>,writer: B.Writer) -> B.Writer:
  match n:
    case 0n: writer
    case 1n+p: written(Array.get(U32,array,i),p,U32.inc(i),writer)

@unsafe
def written(pair: Array<U32> & U32,+n: Nat,+i: U32,writer: B.Writer) -> B.Writer:
  (array,value) = pair
  write(n,i,array,B.int(writer,value))
