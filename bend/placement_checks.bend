import Base
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_height.bend as H
import ./word64.bend as W
import ./placement_random.bend as R
import ./density.bend as Q

@unsafe
def shifted_material(+at: Maybe<&2,P.Position>,+world: E.World,+allowed: Q.Table<U32>) -> Maybe<&2,Bool>:
  match at:
    case None{}: None{}
    case Some{p}: allowed_material(E.material(world,p),allowed)

@unsafe
def allowed_material(+value: Maybe<&2,U32>,+allowed: Q.Table<U32>) -> Maybe<&2,Bool>:
  match value:
    case None{}: None{}
    case Some{m}: Some{P.member(allowed,m)}

def negated(+value: Maybe<&2,Bool>) -> Maybe<&2,Bool>:
  match value:
    case None{}: None{}
    case Some{b}: Some{Bool.not(b)}

def combined(+unknown: Bool,+all: Bool) -> Maybe<&2,Bool>:
  match unknown:
    case True{}: None{}
    case False{}: Some{all}

@unsafe
def combine_value(+value: Maybe<&2,Bool>,rest: List<&2,P.Predicate>,+all: Bool,+unknown: Bool,+world: E.World,+at: P.Position) -> Maybe<&2,Bool>:
  match value:
    case None{}: combine(rest,all,True{},world,at)
    case Some{+value}: combine_decisive(Bool.or(Bool.and(value,all),Bool.and(Bool.not(value),Bool.not(all))),value,rest,all,unknown,world,at)

@unsafe
def combine_decisive(+same: Bool,+value: Bool,rest: List<&2,P.Predicate>,+all: Bool,+unknown: Bool,+world: E.World,+at: P.Position) -> Maybe<&2,Bool>:
  match same:
    case False{}: Some{value}
    case True{}: combine(rest,all,unknown,world,at)

@unsafe
def combine(xs: List<&2,P.Predicate>,+all: Bool,+unknown: Bool,+world: E.World,+at: P.Position) -> Maybe<&2,Bool>:
  match xs:
    case Nil{}: combined(unknown,all)
    case p <> rest: combine_value(predicate(p,world,at),rest,all,unknown,world,at)

@unsafe
def predicate(p: P.Predicate,+world: E.World,+at: P.Position) -> Maybe<&2,Bool>:
  match p:
    case P.Material{offset,allowed}: shifted_material(P.shift(at,offset),world,allowed)
    case P.All{xs}: combine(xs,True{},False{},world,at)
    case P.Any{xs}: combine(xs,False{},False{},world,at)
    case P.Not{p}: negated(predicate(p,world,at))
    case P.Always{}: Some{True{}}

def true_value(+value: Maybe<&2,Bool>) -> Bool:
  match value:
    case Some{True{}}: True{}
    case _: False{}

@unsafe
def set_y(+height: Maybe<&2,U32>,+at: P.Position) -> Maybe<&2,P.Position>:
  match height at:
    case None{} _: None{}
    case Some{y} P.Position{x,_,z}: Some{P.Position{x,y,z}}

@unsafe
def retain(+ok: Bool,+at: P.Position) -> Maybe<&2,P.Position>:
  match ok:
    case False{}: None{}
    case True{}: Some{at}

@unsafe
def signed(+value: U32) -> W.U64:
  W.U64{U32.mul(W.flag(U32.is_ge(value,2147483648)),4294967295),value}

@unsafe
def signed_less(+a: W.U64,+b: W.U64) -> Bool:
  R.less(W.xor(a,W.U64{2147483648,0}),W.xor(b,W.U64{2147483648,0}))

@unsafe
def relative(+height: Maybe<&2,U32>,+at: P.Position,+minimum: U32,+maximum: U32) -> Maybe<&2,P.Position>:
  match height at:
    case None{} _: None{}
    case Some{h} P.Position{_,y,_}:
      retain(Bool.and(Bool.not(signed_less(signed(y),W.add(signed(h),signed(minimum)))),Bool.not(signed_less(W.add(signed(h),signed(maximum)),signed(y)))),at)

@unsafe
def water_depth(+surface: Maybe<&2,U32>,+floor: Maybe<&2,U32>,+maximum: U32,+at: P.Position) -> Maybe<&2,P.Position>:
  match surface floor:
    case Some{a} Some{b}: retain(Bool.not(signed_less(signed(maximum),W.sub(signed(a),signed(b)))),at)
    case _ _: None{}

@unsafe
def biome(+id: Maybe<&2,U32>,+world: E.World,+at: P.Position,+recipe: U32) -> Maybe<&2,P.Position>:
  match id world:
    case Some{id} E.World{_,P.Policy{_,_,biomes,_,_,_,_},_}: retain(P.member(Q.get(Q.Table<U32>,biomes,id,Q.Empty{}),recipe),at)
    case None{} _: None{}

@unsafe
def scan_target(+targeted: Bool,+n: U32,+world: E.World,+at: P.Position,+direction: U32,+target: P.Predicate,+allowed: P.Predicate) -> Maybe<&2,P.Position>:
  match targeted n:
    case True{} _: Some{at}
    case False{} 0: None{}
    case False{} _: scan_shift(P.shift(at,P.Position{0,direction,0}),U32.sub(n,1),world,direction,target,allowed)

@unsafe
def scan_shift(+at: Maybe<&2,P.Position>,+n: U32,+world: E.World,+direction: U32,+target: P.Predicate,+allowed: P.Predicate) -> Maybe<&2,P.Position>:
  match at world:
    case None{} _: None{}
    case Some{+at} E.World{source,_,_}: scan_inside(E.inside_y(source,position_y(at)),n,world,at,direction,target,allowed)

def position_y(+at: P.Position) -> U32:
  match at:
    case P.Position{_,y,_}: y

@unsafe
def scan_inside(+inside: Bool,+n: U32,+world: E.World,+at: P.Position,+direction: U32,+target: P.Predicate,+allowed: P.Predicate) -> Maybe<&2,P.Position>:
  match inside:
    case False{}: None{}
    case True{}: scan_allowed(true_value(predicate(allowed,world,at)),n,world,at,direction,target,allowed)

@unsafe
def scan_allowed(+allowed_now: Bool,+n: U32,+world: E.World,+at: P.Position,+direction: U32,+target: P.Predicate,+allowed: P.Predicate) -> Maybe<&2,P.Position>:
  match allowed_now:
    case False{}: retain(true_value(predicate(target,world,at)),at)
    case True{}: scan_target(true_value(predicate(target,world,at)),n,world,at,direction,target,allowed)

@unsafe
def scan_start(+allowed_now: Bool,+n: U32,+world: E.World,+at: P.Position,+direction: U32,+target: P.Predicate,+allowed: P.Predicate) -> Maybe<&2,P.Position>:
  match allowed_now:
    case False{}: None{}
    case True{}: scan_target(true_value(predicate(target,world,at)),n,world,at,direction,target,allowed)

@unsafe
def evaluate(m: P.Modifier,+world: E.World,+at: P.Position,+recipe: U32) -> Maybe<&2,P.Position>:
  match m at:
    case P.Heightmap{map} P.Position{x,_,z}: set_y(E.height(world,x,z,map),at)
    case P.Filter{p} _: retain(true_value(predicate(p,world,at)),at)
    case P.WaterDepth{maximum} P.Position{x,_,z}: water_depth(E.height(world,x,z,1),E.height(world,x,z,3),maximum,at)
    case P.Relative{map,minimum,maximum} P.Position{x,_,z}: relative(E.height(world,x,z,map),at,minimum,maximum)
    case P.Biome{} _: biome(E.biome(world,at),world,at,recipe)
    case P.Scan{direction,+target,+allowed,steps} _: scan_start(true_value(predicate(allowed,world,at)),steps,world,at,direction,target,allowed)
    case _ _: None{}
