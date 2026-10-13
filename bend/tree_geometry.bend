import Base
import ./placement_data.bend as P
import ./placement_random.bend as R
import ./word64.bend as W
import ./noise.bend as N
import ./placement_height.bend as H

# All geometry is relative to the root. World coordinates are added with checked
# integer arithmetic by the writer, never converted to F32 for branch geometry.
type Wood is Data:
  Wood{at: P.Position,axis: U32}
type Crown is Data:
  Crown{at: P.Position,radius_offset: U32,width: U32}
type Geometry is Data:
  Geometry{state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>}
type Attachment is Data:
  Attachment{at: P.Position,base: U32}

@unsafe
def abs(+x: U32) -> U32:
  N.word(U32.is_ge(x,2147483648),U32.sub(0,x),x)

@unsafe
def signed_less(a: U32,b: U32) -> Bool:
  H.signed_less(a,b)

@unsafe
def min(+x: U32,+y: U32) -> U32:
  N.word(H.signed_less(x,y),x,y)

@unsafe
def max(+x: U32,+y: U32) -> U32:
  N.word(H.signed_less(x,y),y,x)

@unsafe
def clamp(+x: U32,+low: U32,+high: U32) -> U32:
  min(max(x,low),high)

@unsafe
def float(+x: U32) -> F32:
  N.pick(U32.is_ge(x,2147483648),F32.neg(U32.to_f32(abs(x))),U32.to_f32(x))

@unsafe
def half(+x: U32) -> U32:
  N.word(U32.is_ge(x,2147483648),U32.sub(0,U32.div(abs(x),2)),U32.div(x,2))

@unsafe
def plus(a: P.Position,b: P.Position) -> P.Position:
  match a b:
    case P.Position{x,y,z} P.Position{dx,dy,dz}: P.Position{U32.add(x,dx),U32.add(y,dy),U32.add(z,dz)}

@unsafe
def direction(+side: U32) -> P.Position:
  match side:
    case 0: P.Position{1,0,0}
    case 1: P.Position{4294967295,0,0}
    case 2: P.Position{0,0,1}
    case _: P.Position{0,0,4294967295}

@unsafe
def below(+state: W.U64,+limit: U32) -> R.Draw:
  below_needed(U32.is_gt(limit,1),state,limit)

@unsafe
def below_needed(needed: Bool,+state: W.U64,+limit: U32) -> R.Draw:
  match needed:
    case False{}: R.Draw{state,W.U64{0,0}}
    case True{}: R.integer(state,0,U32.sub(limit,1))

@unsafe
def range(+state: W.U64,+low: U32,+high: U32) -> R.Draw:
  ranged(below(state,U32.inc(U32.sub(high,low))),low)

@unsafe
def ranged(draw: R.Draw,low: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: R.Draw{state,W.U64{0,U32.add(low,R.low(value))}}

@unsafe
def trunk(+shape: U32,+height: U32,+width: U32,+state: W.U64) -> Geometry:
  match shape:
    case 1: fancy(height,state)
    case 2: dark(height,state)
    case _: directed(below(state,4),shape,height,width)

@unsafe
def directed(draw: R.Draw,+shape: U32,+height: U32,+width: U32) -> Geometry:
  match draw:
    case R.Draw{state,value}: straight(0,height,width,shape,direction(R.low(value)),state,[])

@unsafe
def straight(+i: U32,+height: U32,+width: U32,+shape: U32,+dir: P.Position,+state: W.U64,wood: List<&2,Wood>) -> Geometry:
  match dir:
    case P.Position{+dx,_,+dz}:
      +bend = N.word(Bool.and(U32.is_eq(shape,4),Bool.not(H.signed_less(i,U32.sub(height,3)))),U32.inc(U32.sub(i,U32.sub(height,3))),0)
      straight_more(U32.is_lt(i,height),i,height,width,shape,dir,state,wood,P.Position{U32.mul(dx,bend),i,U32.mul(dz,bend)})

@unsafe
def straight_more(more: Bool,+i: U32,+height: U32,+width: U32,+shape: U32,+dir: P.Position,+state: W.U64,wood: List<&2,Wood>,at: P.Position) -> Geometry:
  match more:
    case True{}: straight(U32.inc(i),height,width,shape,dir,state,square(0,0,width,Bool.and(U32.is_eq(width,2),U32.is_eq(i,U32.sub(height,1))),at,wood))
    case False{}: branched(shape,height,width,dir,state,wood)

@unsafe
def square(+x: U32,+z: U32,+width: U32,+single: Bool,+at: P.Position,wood: List<&2,Wood>) -> List<&2,Wood>:
  square_more(U32.is_lt(z,width),x,z,width,single,at,wood)

@unsafe
def square_more(more: Bool,+x: U32,+z: U32,+width: U32,+single: Bool,+at: P.Position,wood: List<&2,Wood>) -> List<&2,Wood>:
  match more:
    case False{}: wood
    case True{}: square_added(Bool.or(Bool.not(single),Bool.and(U32.is_zero(x),U32.is_zero(z))),x,z,width,single,at,wood)

@unsafe
def square_added(add: Bool,+x: U32,+z: U32,+width: U32,+single: Bool,+at: P.Position,wood: List<&2,Wood>) -> List<&2,Wood>:
  match add:
    case True{}: square_next(x,z,width,single,at,Wood{plus(at,P.Position{x,0,z}),0} <> wood)
    case False{}: square_next(x,z,width,single,at,wood)

@unsafe
def square_next(+x: U32,+z: U32,+width: U32,+single: Bool,+at: P.Position,wood: List<&2,Wood>) -> List<&2,Wood>:
  square(N.word(U32.is_eq(U32.inc(x),width),0,U32.inc(x)),N.word(U32.is_eq(U32.inc(x),width),U32.inc(z),z),width,single,at,wood)

@unsafe
def branched(+shape: U32,+height: U32,+width: U32,+dir: P.Position,+state: W.U64,wood: List<&2,Wood>) -> Geometry:
  match shape dir:
    case 3 _: jungle_begin(below(state,4),height,width,wood)
    case _ P.Position{+dx,_,+dz}:
      branch_needed(Bool.or(U32.is_eq(shape,4),U32.is_eq(shape,5)),shape,height,width,dir,state,wood,
        Crown{P.Position{N.word(U32.is_eq(shape,4),U32.mul(dx,3),0),height,N.word(U32.is_eq(shape,4),U32.mul(dz,3),0)},0,width})

@unsafe
def branch_needed(needed: Bool,+shape: U32,+height: U32,+width: U32,dir: P.Position,+state: W.U64,wood: List<&2,Wood>,crown: Crown) -> Geometry:
  match needed dir:
    case False{} _: Geometry{state,List.reverse(&2,Wood,wood),[crown]}
    case True{} P.Position{+dx,_,+dz}: Geometry{state,List.reverse(&2,Wood,
      Wood{P.Position{U32.mul(dx,3),U32.sub(height,1),U32.mul(dz,3)},N.word(U32.is_ne(dx,0),1,2)} <>
      Wood{P.Position{U32.mul(dx,2),U32.sub(height,1),U32.mul(dz,2)},N.word(U32.is_ne(dx,0),1,2)} <>
      Wood{P.Position{dx,U32.sub(height,2),dz},N.word(U32.is_ne(dx,0),1,2)} <> wood),
      [crown,Crown{P.Position{U32.mul(dx,3),height,U32.mul(dz,3)},0,1}]}

@unsafe
def jungle_begin(draw: R.Draw,+height: U32,+width: U32,wood: List<&2,Wood>) -> Geometry:
  match draw:
    case R.Draw{state,value}: jungle(U32.sub(U32.sub(height,2),R.low(value)),height,state,wood,[Crown{P.Position{0,height,0},0,width}])

@unsafe
def jungle(+level: U32,+height: U32,+state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  jungle_more(H.signed_less(U32.div(height,2),level),level,height,state,wood,crowns)

@unsafe
def jungle_more(more: Bool,+level: U32,+height: U32,+state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  match more:
    case False{}: Geometry{state,List.reverse(&2,Wood,wood),List.reverse(&2,Crown,crowns)}
    case True{}: jungle_angle(R.next(state),level,height,wood,crowns)

@unsafe
def jungle_angle(draw: R.Draw,+level: U32,+height: U32,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  match draw:
    case R.Draw{state,value}: jungle_limb(0,F32.mul(R.unit(value),6.283185307179586),level,height,state,wood,crowns,P.Position{0,0,0})

@unsafe
def jungle_limb(+i: U32,+angle: F32,+level: U32,+height: U32,+state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>,end: P.Position) -> Geometry:
  match i end:
    case 5 P.Position{x,_,z}: jungle_step(below(state,4),level,height,wood,Crown{P.Position{x,level,z},4294967294,1} <> crowns)
    case _ _: jungle_added(i,angle,level,height,state,wood,crowns,
      P.Position{N.signed(F32.add(1.5,F32.mul(F32.cos(angle),U32.to_f32(i)))),U32.add(U32.sub(level,3),U32.div(i,2)),N.signed(F32.add(1.5,F32.mul(F32.sin(angle),U32.to_f32(i))))})

@unsafe
def jungle_added(+i: U32,+angle: F32,+level: U32,+height: U32,+state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>,+end: P.Position) -> Geometry:
  jungle_limb(U32.inc(i),angle,level,height,state,Wood{end,0} <> wood,crowns,end)

@unsafe
def jungle_step(draw: R.Draw,+level: U32,+height: U32,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  match draw:
    case R.Draw{state,value}: jungle(U32.sub(U32.sub(level,2),R.low(value)),height,state,wood,crowns)

@unsafe
def dark(+height: U32,+state: W.U64) -> Geometry:
  dark_direction(below(state,4),height)

@unsafe
def dark_direction(draw: R.Draw,+height: U32) -> Geometry:
  match draw:
    case R.Draw{state,value}: dark_lean(below(state,4),height,direction(R.low(value)))

@unsafe
def dark_lean(draw: R.Draw,+height: U32,+dir: P.Position) -> Geometry:
  match draw:
    case R.Draw{state,value}: dark_steps(below(state,3),height,dir,U32.sub(height,R.low(value)))

@unsafe
def dark_steps(draw: R.Draw,+height: U32,+dir: P.Position,+lean: U32) -> Geometry:
  match draw:
    case R.Draw{state,value}: dark_rows(0,height,dir,lean,U32.sub(2,R.low(value)),P.Position{0,0,0},state,[])

@unsafe
def dark_rows(+i: U32,+height: U32,+dir: P.Position,+lean: U32,+steps: U32,+at: P.Position,+state: W.U64,wood: List<&2,Wood>) -> Geometry:
  dark_row(U32.is_lt(i,height),Bool.and(Bool.not(H.signed_less(i,lean)),U32.is_gt(steps,0)),i,height,dir,lean,steps,at,state,wood)

@unsafe
def dark_row(more: Bool,bend: Bool,+i: U32,+height: U32,+dir: P.Position,+lean: U32,+steps: U32,+at: P.Position,+state: W.U64,wood: List<&2,Wood>) -> Geometry:
  match more bend at:
    case False{} _ P.Position{x,_,z}: dark_branches(0,height,state,wood,[Crown{P.Position{x,U32.sub(height,1),z},0,2}])
    case True{} True{} _: dark_row(True{},False{},i,height,dir,lean,U32.sub(steps,1),plus(at,dir),state,wood)
    case True{} False{} P.Position{x,_,z}: dark_rows(U32.inc(i),height,dir,lean,steps,at,state,
      Wood{P.Position{U32.inc(x),i,U32.inc(z)},0} <> Wood{P.Position{U32.inc(x),i,z},0} <>
      Wood{P.Position{x,i,U32.inc(z)},0} <> Wood{P.Position{x,i,z},0} <> wood)

@unsafe
def dark_branches(+i: U32,+height: U32,+state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  match i:
    case 16: Geometry{state,List.reverse(&2,Wood,wood),List.reverse(&2,Crown,crowns)}
    case _: dark_candidate(Bool.or(Bool.or(U32.is_zero(U32.div(i,4)),U32.is_eq(U32.div(i,4),3)),Bool.or(U32.is_zero(U32.mod(i,4)),U32.is_eq(U32.mod(i,4),3))),i,height,state,wood,crowns)

@unsafe
def dark_candidate(outside: Bool,+i: U32,+height: U32,+state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  match outside:
    case False{}: dark_branches(U32.inc(i),height,state,wood,crowns)
    case True{}: dark_chance(below(state,3),i,height,wood,crowns)

@unsafe
def dark_chance(draw: R.Draw,+i: U32,+height: U32,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  match draw:
    case R.Draw{state,W.U64{_,0}}: dark_length(below(state,3),i,height,wood,crowns)
    case R.Draw{state,_}: dark_branches(U32.inc(i),height,state,wood,crowns)

@unsafe
def dark_length(draw: R.Draw,+i: U32,+height: U32,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  match draw:
    case R.Draw{state,value}: dark_limb(0,U32.add(2,R.low(value)),i,height,state,wood,crowns)

@unsafe
def dark_limb(+j: U32,+length: U32,+i: U32,+height: U32,+state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  dark_limb_more(U32.is_lt(j,length),j,length,i,height,state,wood,crowns,U32.sub(U32.div(i,4),1),U32.sub(U32.mod(i,4),1))

@unsafe
def dark_limb_more(more: Bool,+j: U32,+length: U32,+i: U32,+height: U32,+state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>,+x: U32,+z: U32) -> Geometry:
  match more:
    case False{}: dark_branches(U32.inc(i),height,state,wood,Crown{P.Position{x,U32.sub(height,1),z},0,1} <> crowns)
    case True{}: dark_limb(U32.inc(j),length,i,height,state,Wood{P.Position{x,U32.sub(U32.sub(height,2),j),z},0} <> wood,crowns)

@unsafe
def limb(+from: P.Position,+to: P.Position,wood: List<&2,Wood>) -> List<&2,Wood>:
  match from to:
    case P.Position{+x,+y,+z} P.Position{tx,ty,tz}:
      +delta: P.Position = P.Position{U32.sub(tx,x),U32.sub(ty,y),U32.sub(tz,z)}
      limb_delta(from,delta,wood)

@unsafe
def limb_delta(+from: P.Position,+delta: P.Position,wood: List<&2,Wood>) -> List<&2,Wood>:
  match delta:
    case P.Position{x,y,z}: limb_points(0,U32.max(1,U32.max(abs(x),U32.max(abs(y),abs(z)))),from,delta,wood)

@unsafe
def limb_points(+i: U32,+steps: U32,+from: P.Position,+delta: P.Position,wood: List<&2,Wood>) -> List<&2,Wood>:
  limb_point(U32.is_le(i,steps),i,steps,from,delta,wood)

@unsafe
def limb_point(more: Bool,+i: U32,+steps: U32,+from: P.Position,+delta: P.Position,wood: List<&2,Wood>) -> List<&2,Wood>:
  match more delta:
    case False{} _: wood
    case True{} P.Position{x,y,z}: limb_offset(i,steps,from,delta,wood,
      P.Position{N.signed(F32.floor(F32.add(0.5,F32.div(F32.mul(U32.to_f32(i),float(x)),U32.to_f32(steps))))),
        N.signed(F32.floor(F32.add(0.5,F32.div(F32.mul(U32.to_f32(i),float(y)),U32.to_f32(steps))))),
        N.signed(F32.floor(F32.add(0.5,F32.div(F32.mul(U32.to_f32(i),float(z)),U32.to_f32(steps)))))})

@unsafe
def limb_offset(+i: U32,+steps: U32,+from: P.Position,+delta: P.Position,wood: List<&2,Wood>,+at: P.Position) -> List<&2,Wood>:
  match at:
    case P.Position{x,_,z}: limb_points(U32.inc(i),steps,from,delta,Wood{plus(from,at),N.word(U32.is_zero(U32.max(abs(x),abs(z))),0,N.word(U32.is_ge(abs(x),abs(z)),1,2))} <> wood)

@unsafe
def fancy(+height: U32,+state: W.U64) -> Geometry:
  +h = U32.add(height,2)
  +trunk = F32.to_u32(F32.floor(F32.mul(U32.to_f32(h),0.618)))
  fancy_layers(U32.sub(h,5),h,trunk,state,[Attachment{P.Position{0,U32.sub(h,5),0},trunk}],limb(P.Position{0,0,0},P.Position{0,trunk,0},[]))

@unsafe
def fancy_layers(+layer: U32,+height: U32,+trunk: U32,+state: W.U64,attachments: List<&2,Attachment>,wood: List<&2,Wood>) -> Geometry:
  fancy_layer(F32.is_ge(float(layer),F32.mul(U32.to_f32(height),0.3)),layer,height,trunk,state,attachments,wood)

@unsafe
def fancy_layer(more: Bool,+layer: U32,+height: U32,+trunk: U32,+state: W.U64,attachments: List<&2,Attachment>,wood: List<&2,Wood>) -> Geometry:
  match more:
    case False{}: fancy_attach(List.reverse(&2,Attachment,attachments),height,state,wood,[])
    case True{}: fancy_radius(R.next(state),layer,height,trunk,attachments,wood)

@unsafe
def fancy_radius(draw: R.Draw,+layer: U32,+height: U32,+trunk: U32,attachments: List<&2,Attachment>,wood: List<&2,Wood>) -> Geometry:
  match draw:
    case R.Draw{state,value}:
      +radius = F32.div(U32.to_f32(height),2.0)
      +adjacent = F32.sub(radius,U32.to_f32(layer))
      fancy_angle(R.next(state),F32.mul(F32.mul(F32.sqrt(F32.max(0.0,F32.sub(F32.mul(radius,radius),F32.mul(adjacent,adjacent)))),0.5),F32.add(R.unit(value),0.328)),layer,height,trunk,attachments,wood)

@unsafe
def fancy_angle(draw: R.Draw,+radius: F32,+layer: U32,+height: U32,+trunk: U32,attachments: List<&2,Attachment>,wood: List<&2,Wood>) -> Geometry:
  match draw:
    case R.Draw{state,value}:
      +angle = F32.mul(R.unit(value),6.283185307179586)
      fancy_position(P.Position{clamp(N.signed(F32.floor(F32.add(F32.mul(radius,F32.sin(angle)),0.5))),4294967289,7),
        U32.sub(layer,1),clamp(N.signed(F32.floor(F32.add(F32.mul(radius,F32.cos(angle)),0.5))),4294967289,7)},layer,height,trunk,state,attachments,wood)

@unsafe
def fancy_position(+at: P.Position,+layer: U32,+height: U32,+trunk: U32,+state: W.U64,attachments: List<&2,Attachment>,wood: List<&2,Wood>) -> Geometry:
  match at:
    case P.Position{+x,+y,+z}: fancy_layers(U32.sub(layer,1),height,trunk,state,
      Attachment{at,min(N.signed(F32.floor(F32.sub(float(y),F32.mul(F32.sqrt(U32.to_f32(U32.add(U32.mul(x,x),U32.mul(z,z)))),0.381)))),trunk)} <> attachments,wood)

@unsafe
def fancy_attach(attachments: List<&2,Attachment>,+height: U32,+state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  match attachments:
    case Nil{}: Geometry{state,List.reverse(&2,Wood,wood),List.reverse(&2,Crown,crowns)}
    case Attachment{at,+base} <> rest: fancy_attachment(F32.is_ge(float(base),F32.mul(U32.to_f32(height),0.2)),at,base,rest,height,state,wood,crowns)

@unsafe
def fancy_attachment(add: Bool,+at: P.Position,+base: U32,rest: List<&2,Attachment>,+height: U32,+state: W.U64,wood: List<&2,Wood>,crowns: List<&2,Crown>) -> Geometry:
  match add:
    case False{}: fancy_attach(rest,height,state,wood,crowns)
    case True{}: fancy_attach(rest,height,state,limb(P.Position{0,base,0},at,wood),Crown{at,0,1} <> crowns)
