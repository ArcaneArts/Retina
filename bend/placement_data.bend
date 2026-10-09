import Base
import ./density.bend as Q
import ./decoration_counts.bend as C
import ./int_provider.bend as I
import ./placement_height.bend as H
import ./word64.bend as W

type Position is Data:
  Position{x: U32,y: U32,z: U32}
type Predicate is Data:
  Material{offset: Position,allowed: Q.Table<U32>}
  All{predicates: List<&2,Predicate>}
  Any{predicates: List<&2,Predicate>}
  Not{predicate: Predicate}
  Always{}
type Modifier is Data:
  Count{rule: C.Rule}
  Layer{provider: I.Provider}
  Square{}
  Cuboid{xz: I.Provider,y: I.Provider,edges: Bool,interior: Bool}
  Heightmap{map: U32}
  HeightRange{provider: H.Provider}
  Scan{direction: U32,target: Predicate,allowed: Predicate,steps: U32}
  Offset{x: I.Provider,y: I.Provider,z: I.Provider}
  Filter{predicate: Predicate}
  WaterDepth{maximum: U32}
  Relative{map: U32,minimum: U32,maximum: U32}
  Biome{}
  Select{minimum: F32,maximum: F32}
  Rarity{chance: U32}
  Chance{chance: F32}
type Recipe is Data:
  Recipe{group: U32,salt: W.U64,active: Bool,program: Q.Table<Modifier>}
type Policy is Data:
  Policy{masks: Q.Table<U32>,flags: Q.Table<U32>,biomes: Q.Table<Q.Table<U32>>,water: U32,lava: U32,bedrock: U32,volume: Bool}
type Model is Data:
  Model{recipes: Q.Table<Recipe>,permutation: Q.Table<U32>,policy: Policy}

@unsafe
def equal(+a: Position,+b: Position) -> Bool:
  match a b:
    case Position{x,y,z} Position{xx,yy,zz}: Bool.and(U32.is_eq(x,xx),Bool.and(U32.is_eq(y,yy),U32.is_eq(z,zz)))

@unsafe
def less(+a: Position,+b: Position) -> Bool:
  match a b:
    case Position{+x,y,+z} Position{+xx,yy,+zz}:
      Bool.or(H.signed_less(x,xx),Bool.and(U32.is_eq(x,xx),Bool.or(H.signed_less(z,zz),Bool.and(U32.is_eq(z,zz),H.signed_less(y,yy)))))

@unsafe
def shifted(+ok: Bool,+a: Position,+b: Position) -> Maybe<&2,Position>:
  match ok a b:
    case False{} _ _: None{}
    case True{} Position{x,y,z} Position{xx,yy,zz}: Some{Position{U32.add(x,xx),U32.add(y,yy),U32.add(z,zz)}}

# Reject unrepresentable probes rather than wrapping them across the world.
@unsafe
def shift(+a: Position,+b: Position) -> Maybe<&2,Position>:
  match a b:
    case Position{+x,+y,+z} Position{+xx,+yy,+zz}:
      shifted(Bool.and(U32.is_eq(U32.add(x,xx),H.add(x,xx)),Bool.and(U32.is_eq(U32.add(y,yy),H.add(y,yy)),U32.is_eq(U32.add(z,zz),H.add(z,zz)))),a,b)

@unsafe
def member(+values: Q.Table<U32>,+value: U32) -> Bool:
  match values:
    case Q.Empty{}: False{}
    case Q.Leaf{x}: U32.is_eq(x,value)
    case Q.Branch{_,a,b}: Bool.or(member(a,value),member(b,value))

@unsafe
def allowed_valid(+values: Q.Table<U32>,+palette: U32,+i: U32,+n: U32) -> Bool:
  match n:
    case 0: True{}
    case _: Bool.and(U32.is_lt(Q.get(U32,values,i,4294967295),palette),Bool.and(
      Bool.or(U32.is_zero(i),U32.is_lt(Q.get(U32,values,U32.sub(i,1),0),Q.get(U32,values,i,0))),
      allowed_valid(values,palette,U32.inc(i),U32.sub(n,1))))

@unsafe
def predicates_valid(xs: List<&2,Predicate>,+palette: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case p <> rest: Bool.and(predicate_valid(p,palette),predicates_valid(rest,palette))

@unsafe
def predicate_valid(p: Predicate,+palette: U32) -> Bool:
  match p:
    case Material{Position{x,y,z},+allowed}:
      Bool.and(U32.is_le(U32.add(x,17),34),Bool.and(U32.is_le(U32.add(y,17),34),Bool.and(U32.is_le(U32.add(z,17),34),allowed_valid(allowed,palette,0,Q.table_size(U32,allowed)))))
    case All{xs}: predicates_valid(xs,palette)
    case Any{xs}: predicates_valid(xs,palette)
    case Not{x}: predicate_valid(x,palette)
    case Always{}: True{}

@unsafe
def modifier_valid(m: Modifier,+palette: U32) -> Bool:
  match m:
    case Count{rule}: C.valid(rule)
    case Layer{+p}: Bool.and(I.valid(p),I.within(p,0,256))
    case Cuboid{+xz,+y,_,_}: Bool.and(Bool.and(I.valid(xz),I.valid(y)),Bool.and(I.within(xz,1,16),I.within(y,1,16)))
    case Heightmap{map}: U32.is_lt(map,6)
    case HeightRange{p}: H.valid(p)
    case Scan{+direction,target,allowed,+steps}:
      Bool.and(Bool.or(U32.is_eq(direction,1),U32.is_eq(direction,4294967295)),Bool.and(Bool.and(U32.is_ge(steps,1),U32.is_le(steps,32)),Bool.and(predicate_valid(target,palette),predicate_valid(allowed,palette))))
    case Offset{x,y,z}: Bool.and(I.valid(x),Bool.and(I.valid(y),I.valid(z)))
    case Filter{p}: predicate_valid(p,palette)
    case Relative{map,_,_}: U32.is_lt(map,6)
    case Select{+a,+b}: Bool.and(I.finite(a),Bool.and(I.finite(b),Bool.and(F32.is_ge(a,0.0),Bool.and(F32.is_le(b,1.00000001),F32.is_le(a,b)))))
    case Rarity{chance}: U32.is_ne(chance,0)
    case Chance{+chance}: Bool.and(I.finite(chance),Bool.and(F32.is_ge(chance,0.0),F32.is_le(chance,1.0)))
    case _: True{}

def empty_recipe() -> Recipe:
  Recipe{0,W.U64{0,0},False{},Q.Empty{}}
