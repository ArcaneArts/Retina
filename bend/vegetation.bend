import Base
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_walk.bend as Walk
import ./placement_cursor.bend as K
import ./surface_feature.bend as F
import ./material_columns_data.bend as MD
import ./density.bend as Q
import ./word64.bend as W

# One anchor owns the live world used by both placement modifiers and feature
# writers. Independent anchors may fork; their final role-aware replay is a
# separate operation. Never use completion order as the world write order.
type Model is Data:
  Model{placement: P.Model,features: Q.Table<Maybe<&2,F.Feature>>,writers: F.Model}
type Anchor is Data:
  Anchor{cursor: K.Cursor,world: E.World,candidates: U32,placed: U32,last: W.U64}
type Outcome is Data:
  Paused{anchor: Anchor}
  Finished{anchor: Anchor}
  Unavailable{anchor: Anchor,recipe: U32,at: P.Position,state: W.U64}

@unsafe
def start(+model: Model,source: MD.Grid,ids: List<&2,U32>,origin: P.Position,seed: W.U64) -> Anchor:
  match model:
    case Model{+placement,_,_}: started(placement,source,ids,origin,seed)

@unsafe
def started(+placement: P.Model,source: MD.Grid,ids: List<&2,U32>,origin: P.Position,+seed: W.U64) -> Anchor:
  match placement:
    case P.Model{_,_,policy}: Anchor{Walk.new(placement,ids,origin,seed),E.World{source,policy,E.Empty{}},0,0,seed}

def steps(cursor: K.Cursor) -> U32:
  match cursor:
    case K.Cursor{_,_,n}: n

# Fuel bounds placement work without truncating future candidates. A yielded
# feature is atomic; the next slice sees all of its accepted writes. The feature
# RNG is distinct from the cursor's forked placement streams.
@unsafe
def resume(+anchor: Anchor,+model: Model,+fuel: U32) -> Outcome:
  match anchor model:
    case Anchor{+cursor,+world,_,_,_} Model{placement,_,_}:
      outcome(Walk.next(cursor,placement,world,fuel),anchor,model,fuel,steps(cursor))

@unsafe
def outcome(result: K.Outcome,+before: Anchor,+model: Model,+fuel: U32,+previous: U32) -> Outcome:
  match result before:
    case K.Exhausted{cursor} Anchor{_,world,n,placed,last}: Finished{Anchor{cursor,world,n,placed,last}}
    case K.Paused{cursor} Anchor{_,world,n,placed,last}: Paused{Anchor{cursor,world,n,placed,last}}
    case K.Yield{+cursor,candidate} Anchor{_,world,n,placed,last}:
      dispatch(candidate,Anchor{cursor,world,n,placed,last},model,U32.sub(fuel,U32.sub(steps(cursor),previous)))

@unsafe
def dispatch(candidate: K.Candidate,+anchor: Anchor,+model: Model,+fuel: U32) -> Outcome:
  match candidate model:
    case K.Candidate{+id,_,+at,+state,_} Model{_,features,+writers}:
      selected(Q.get(Maybe<&2,F.Feature>,features,id,None{}),id,at,state,anchor,model,writers,fuel)

@unsafe
def selected(feature: Maybe<&2,F.Feature>,+id: U32,+at: P.Position,+state: W.U64,+anchor: Anchor,+model: Model,writers: F.Model,+fuel: U32) -> Outcome:
  match feature anchor:
    case None{} _: Unavailable{anchor,id,at,state}
    case Some{feature} Anchor{_,world,_,_,_}: placed(F.place(feature,state,writers,world,at),anchor,model,fuel)

@unsafe
def placed(result: F.Placement,anchor: Anchor,model: Model,fuel: U32) -> Outcome:
  match result anchor:
    case F.Placement{state,world,ok} Anchor{cursor,_,n,placed,_}:
      resume(Anchor{cursor,world,U32.inc(n),U32.add(placed,W.flag(ok)),state},model,fuel)

# Registry recipe membership is sampled at the same global four-block lattice
# as the Rust anchor planner, including cave biomes when the profile requests it.
# The treap is only a set of recipe IDs; its sorted traversal fixes output order.
@unsafe
def membership(xs: Q.Table<U32>,acc: E.Patch) -> E.Patch:
  match xs:
    case Q.Empty{}: acc
    case Q.Leaf{id}: E.insert(acc,P.Position{id,0,0},1)
    case Q.Branch{_,a,b}: membership(b,membership(a,acc))

@unsafe
def biome(found: Maybe<&2,U32>,+world: E.World,acc: E.Patch) -> E.Patch:
  match found world:
    case None{} _: acc
    case Some{id} E.World{_,P.Policy{_,_,biomes,_,_,_,_},_}: membership(Q.get(Q.Table<U32>,biomes,id,Q.Empty{}),acc)

@unsafe
def surface(column: Maybe<&2,MD.Column>,world: E.World,acc: E.Patch) -> E.Patch:
  match column:
    case None{}: acc
    case Some{MD.Column{_,id,_,_,_,_,_,_,_,_}}: biome(Some{id},world,acc)

@unsafe
def layer(+i: U32,+at: P.Position,+world: E.World,+volume: Bool,acc: E.Patch) -> E.Patch:
  match i at:
    case 16 _: acc
    case _ P.Position{x,y,z}: sampled(P.shift(at,P.Position{U32.mul(U32.mod(i,4),4),0,U32.mul(U32.div(i,4),4)}),i,at,world,volume,acc)

@unsafe
def sampled(point: Maybe<&2,P.Position>,+i: U32,+at: P.Position,+world: E.World,+volume: Bool,acc: E.Patch) -> E.Patch:
  match point world volume:
    case None{} _ _: layer(U32.inc(i),at,world,volume,acc)
    case Some{+p} _ True{}: layer(U32.inc(i),at,world,volume,biome(E.biome(world,p),world,acc))
    case Some{P.Position{x,_,z}} E.World{source,_,_} False{}:
      layer(U32.inc(i),at,world,volume,surface(E.column(source,x,z),world,acc))

@unsafe
def volume(+n: U32,+at: P.Position,+world: E.World,acc: E.Patch) -> E.Patch:
  match n:
    case 0: acc
    case _: volume_next(P.shift(at,P.Position{0,4,0}),U32.sub(n,1),world,layer(0,at,world,True{},acc))

@unsafe
def volume_next(at: Maybe<&2,P.Position>,n: U32,world: E.World,acc: E.Patch) -> E.Patch:
  match at:
    case None{}: acc
    case Some{at}: volume(n,at,world,acc)

@unsafe
def all_ids(+world: E.World,+origin: P.Position) -> List<&2,U32>:
  volume_ids(world,origin,layer(0,origin,world,False{},E.Empty{}))

@unsafe
def volume_ids(+world: E.World,origin: P.Position,acc: E.Patch) -> List<&2,U32>:
  match world origin:
    case E.World{MD.Grid{_,+minimum,height,_,_},P.Policy{_,_,_,_,_,_,True{}},_} P.Position{x,_,z}:
      ids(volume(U32.div(U32.add(U32.add(height,U32.and(minimum,3)),3),4),P.Position{x,U32.and(minimum,4294967292),z},world,acc),[])
    case _ _: ids(acc,[])

@unsafe
def ids(patch: E.Patch,acc: List<&2,U32>) -> List<&2,U32>:
  match patch:
    case E.Empty{}: acc
    case E.Node{P.Position{id,_,_},_,_,left,right}: ids(left,id <> ids(right,acc))
