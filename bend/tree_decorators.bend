import Base
import ./tree_data.bend as T
import ./tree_geometry.bend as G
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_checks.bend as C
import ./placement_random.bend as R
import ./density.bend as Q
import ./word64.bend as W
import ./noise.bend as N

type Decorated is Data:
  Decorated{occupied: E.Patch,writes: List<&2,T.Write>}

@unsafe
def ordered(at: P.Position) -> P.Position:
  match at:
    case P.Position{x,y,z}: P.Position{y,z,x}

@unsafe
def unordered(at: P.Position) -> P.Position:
  match at:
    case P.Position{y,z,x}: P.Position{x,y,z}

@unsafe
def trunks(xs: List<&2,G.Wood>,+root: P.Position,acc: E.Patch) -> E.Patch:
  match xs:
    case Nil{}: acc
    case G.Wood{at,_} <> rest: trunk_position(P.shift(root,at),rest,root,acc)

@unsafe
def trunk_position(at: Maybe<&2,P.Position>,rest: List<&2,G.Wood>,+root: P.Position,acc: E.Patch) -> E.Patch:
  match at:
    case None{}: trunks(rest,root,acc)
    case Some{at}: trunks(rest,root,E.insert(acc,ordered(at),1))

@unsafe
def leaves(xs: List<&2,T.Write>,acc: E.Patch) -> E.Patch:
  match xs:
    case Nil{}: acc
    case T.Write{at,_,_,_} <> rest: leaves(rest,E.insert(acc,ordered(at),1))

@unsafe
def base(xs: List<&2,G.Wood>,+value: U32) -> U32:
  match xs:
    case Nil{}: value
    case G.Wood{P.Position{_,y,_},_} <> rest: base(rest,G.min(value,y))

@unsafe
def decorate(xs: List<&2,T.Decorator>,+wood: List<&2,G.Wood>,+root: P.Position,leaf: List<&2,T.Write>,occupied: E.Patch,+seed: W.U64,+world: E.World) -> List<&2,T.Write>:
  match root:
    case P.Position{_,y,_}: finish(decorators(xs,0,trunks(wood,root,E.Empty{}),leaves(leaf,E.Empty{}),U32.add(y,base(wood,2147483647)),seed,world,Decorated{occupied,[]}))

@unsafe
def finish(result: Decorated) -> List<&2,T.Write>:
  match result:
    case Decorated{_,writes}: List.reverse(&2,T.Write,writes)

@unsafe
def decorators(xs: List<&2,T.Decorator>,+i: U32,+wood: E.Patch,+leaf: E.Patch,+base: U32,+seed: W.U64,+world: E.World,result: Decorated) -> Decorated:
  match xs:
    case Nil{}: result
    case +decorator <> rest: decorator_start(decorator,rest,i,wood,leaf,base,seed,world,result,W.xor(seed,R.mixed(W.U64{0,i})))

@unsafe
def decorator_start(+decorator: T.Decorator,rest: List<&2,T.Decorator>,+i: U32,+wood: E.Patch,+leaf: E.Patch,+base: U32,+seed: W.U64,+world: E.World,result: Decorated,+local: W.U64) -> Decorated:
  match decorator:
    case T.Decorator{2,probability,_}: cocoa_start(R.next(local),probability,decorator,rest,i,wood,leaf,base,seed,world,result,local)
    case _: decorator_ready(True{},decorator,rest,i,wood,leaf,base,seed,world,result,local)

@unsafe
def cocoa_start(draw: R.Draw,+probability: F32,+decorator: T.Decorator,rest: List<&2,T.Decorator>,+i: U32,+wood: E.Patch,+leaf: E.Patch,+base: U32,+seed: W.U64,+world: E.World,result: Decorated,+local: W.U64) -> Decorated:
  match draw:
    case R.Draw{_,value}: decorator_ready(F32.is_lt(R.unit(value),probability),decorator,rest,i,wood,leaf,base,seed,world,result,local)

@unsafe
def decorator_ready(ok: Bool,+decorator: T.Decorator,rest: List<&2,T.Decorator>,+i: U32,+wood: E.Patch,+leaf: E.Patch,+base: U32,+seed: W.U64,+world: E.World,result: Decorated,+local: W.U64) -> Decorated:
  match ok decorator:
    case False{} _: decorators(rest,U32.inc(i),wood,leaf,base,seed,world,result)
    case True{} T.Decorator{1,_,_}: decorators(rest,U32.inc(i),wood,leaf,base,seed,world,walk(leaf,decorator,base,local,world,result))
    case True{} _: decorators(rest,U32.inc(i),wood,leaf,base,seed,world,walk(wood,decorator,base,local,world,result))

@unsafe
def walk(positions: E.Patch,+decorator: T.Decorator,+base: U32,+seed: W.U64,+world: E.World,result: Decorated) -> Decorated:
  match positions:
    case E.Empty{}: result
    case E.Node{at,_,_,left,right}: walk(right,decorator,base,seed,world,position(unordered(at),decorator,base,seed,world,walk(left,decorator,base,seed,world,result)))

@unsafe
def position(+at: P.Position,+decorator: T.Decorator,+base: U32,+seed: W.U64,+world: E.World,result: Decorated) -> Decorated:
  match at decorator:
    case P.Position{_,y,_} T.Decorator{kind,_,_}: position_allowed(Bool.or(U32.is_ne(kind,2),Bool.not(G.signed_less(2,U32.sub(y,base)))),at,decorator,seed,world,result)

@unsafe
def position_allowed(ok: Bool,+at: P.Position,+decorator: T.Decorator,+seed: W.U64,+world: E.World,result: Decorated) -> Decorated:
  match ok at:
    case False{} _: result
    case True{} P.Position{x,y,z}: sides(0,at,decorator,W.xor(seed,W.xor(R.mixed(W.U64{0,x}),W.xor(R.mixed(W.U64{z,0}),R.mixed(C.signed(y))))),world,result)

@unsafe
def sides(+side: U32,+at: P.Position,+decorator: T.Decorator,+state: W.U64,+world: E.World,result: Decorated) -> Decorated:
  match side:
    case 4: result
    case _: side_draw(R.next(state),side,at,decorator,world,result)

@unsafe
def side_draw(draw: R.Draw,+side: U32,+at: P.Position,+decorator: T.Decorator,+world: E.World,result: Decorated) -> Decorated:
  match draw decorator:
    case R.Draw{state,value} T.Decorator{+kind,probability,_}: side_chance(F32.is_lt(R.unit(value),N.pick(U32.is_eq(kind,2),0.25,probability)),side,at,decorator,state,world,result)

@unsafe
def side_chance(ok: Bool,+side: U32,+at: P.Position,+decorator: T.Decorator,+state: W.U64,+world: E.World,result: Decorated) -> Decorated:
  match ok decorator:
    case False{} _: sides(U32.inc(side),at,decorator,state,world,result)
    case True{} T.Decorator{2,_,states}: cocoa_age(G.below(state,3),side,at,decorator,states,world,result)
    case True{} T.Decorator{+kind,_,states}: sides(U32.inc(side),at,decorator,state,world,
      drops(0,N.word(U32.is_eq(kind,1),5,1),at,side,Q.get(U32,states,side,0),4,world,result))

@unsafe
def cocoa_age(draw: R.Draw,+side: U32,+at: P.Position,+decorator: T.Decorator,+states: Q.Table<U32>,+world: E.World,result: Decorated) -> Decorated:
  match draw:
    case R.Draw{state,value}: sides(U32.inc(side),at,decorator,state,world,drops(0,1,at,side,Q.get(U32,states,U32.add(U32.mul(side,3),R.low(value)),0),3,world,result))

@unsafe
def drops(+i: U32,+length: U32,+at: P.Position,+side: U32,+material: U32,+role: U32,+world: E.World,result: Decorated) -> Decorated:
  match i:
    case _: drop_more(U32.is_lt(i,length),i,length,at,side,material,role,world,result)

@unsafe
def drop_more(more: Bool,+i: U32,+length: U32,+at: P.Position,+side: U32,+material: U32,+role: U32,+world: E.World,result: Decorated) -> Decorated:
  match more:
    case False{}: result
    case True{}: drop_direction(G.direction(side),i,length,at,side,material,role,world,result)

@unsafe
def drop_direction(dir: P.Position,+i: U32,+length: U32,+at: P.Position,+side: U32,+material: U32,+role: U32,+world: E.World,result: Decorated) -> Decorated:
  match dir:
    case P.Position{x,_,z}: drop_position(P.shift(at,P.Position{x,U32.sub(0,i),z}),i,length,at,side,material,role,world,result)

@unsafe
def drop_position(next: Maybe<&2,P.Position>,+i: U32,+length: U32,+at: P.Position,+side: U32,+material: U32,+role: U32,+world: E.World,result: Decorated) -> Decorated:
  match next result:
    case None{} _: result
    case Some{+next} Decorated{+occupied,writes}: drop_above(E.find(occupied,next),next,i,length,at,side,material,role,world,occupied,writes)

@unsafe
def drop_above(found: Maybe<&2,U32>,+next: P.Position,+i: U32,+length: U32,+at: P.Position,+side: U32,+material: U32,+role: U32,+world: E.World,occupied: E.Patch,writes: List<&2,T.Write>) -> Decorated:
  match found next world:
    case Some{1} _ _: Decorated{occupied,writes}
    case _ P.Position{x,y,z} E.World{source,policy,_}: drop_allowed(height_allows(E.height(E.World{source,policy,E.Empty{}},x,z,1),y),next,i,length,at,side,material,role,world,occupied,writes)

@unsafe
def height_allows(height: Maybe<&2,U32>,+y: U32) -> Bool:
  match height:
    case None{}: False{}
    case Some{h}: Bool.not(G.signed_less(y,h))

@unsafe
def drop_allowed(ok: Bool,+next: P.Position,+i: U32,+length: U32,+at: P.Position,+side: U32,+material: U32,+role: U32,+world: E.World,occupied: E.Patch,writes: List<&2,T.Write>) -> Decorated:
  match ok:
    case False{}: Decorated{occupied,writes}
    case True{}: drops(U32.inc(i),length,at,side,material,role,world,Decorated{E.insert(occupied,next,1),T.Write{next,material,role,side} <> writes})

@unsafe
def supported(+world: E.World,+at: P.Position,+material: U32,+side: U32,+cocoa: Bool) -> Bool:
  match at:
    case P.Position{_,_,_}: supported_direction(G.direction(side),world,at,material,cocoa)

@unsafe
def supported_direction(dir: P.Position,+world: E.World,+at: P.Position,+material: U32,+cocoa: Bool) -> Bool:
  match dir:
    case P.Position{x,_,z}: Bool.or(support_position(P.shift(at,P.Position{U32.sub(0,x),0,U32.sub(0,z)}),world,cocoa),
      Bool.and(Bool.not(cocoa),hanging(P.shift(at,P.Position{0,1,0}),world,material)))

@unsafe
def hanging(at: Maybe<&2,P.Position>,+world: E.World,+material: U32) -> Bool:
  match at:
    case None{}: False{}
    case Some{at}: same(E.material(world,at),material)

@unsafe
def same(value: Maybe<&2,U32>,+material: U32) -> Bool:
  match value:
    case Some{m}: U32.is_eq(m,material)
    case None{}: False{}

@unsafe
def support_position(at: Maybe<&2,P.Position>,+world: E.World,+cocoa: Bool) -> Bool:
  match at:
    case None{}: False{}
    case Some{at}: support_value(E.material(world,at),world,cocoa)

@unsafe
def support_value(value: Maybe<&2,U32>,+world: E.World,+cocoa: Bool) -> Bool:
  match value world:
    case Some{m} E.World{_,P.Policy{_,flags,_,_,_,_,_},_}: U32.is_ne(U32.and(Q.get(U32,flags,m,0),N.word(cocoa,32,12)),0)
    case _ _: False{}
