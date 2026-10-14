import Base
import ./forest_data.bend as D
import ./block_provider.bend as B
import ./provider_noise.bend as N
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_height.bend as H
import ./placement_random.bend as R
import ./surface_plants.bend as S
import ./material_columns_data.bend as MD
import ./density.bend as Q
import ./word64.bend as W

@unsafe
def place(+recipe: D.Mushroom,state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position) -> S.Placement:
  height_first(R.integer(state,0,2),recipe,noises,world,at)

@unsafe
def height_first(draw: R.Draw,+recipe: D.Mushroom,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position) -> S.Placement:
  match draw:
    case R.Draw{state,value}: height_second(R.integer(state,0,11),U32.add(R.low(value),4),recipe,noises,world,at)

@unsafe
def height_second(draw: R.Draw,+height: U32,+recipe: D.Mushroom,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position) -> S.Placement:
  match draw:
    case R.Draw{state,+value}: bounded(P.shift(at,P.Position{0,U32.add(U32.mul(height,U32.add(1,W.flag(U32.is_zero(R.low(value))))),1),0}),
      U32.mul(height,U32.add(1,W.flag(U32.is_zero(R.low(value))))),recipe,state,noises,world,at)

@unsafe
def bounded(top: Maybe<&2,P.Position>,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position) -> S.Placement:
  match top world at:
    case Some{P.Position{_,top,_}} E.World{+source,_,_,_} P.Position{_,y,_}:
      bounds(Bool.and(E.inside_y(source,top),base_inside(source,y)),height,recipe,state,noises,world,at)
    case _ _ _: S.Placement{state,world,False{}}

@unsafe
def base_inside(+source: MD.Grid,+y: U32) -> Bool:
  match source:
    case MD.Grid{_,minimum,_,_,_}: Bool.and(E.inside_y(source,y),U32.is_ne(y,minimum))

@unsafe
def bounds(ok: Bool,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position) -> S.Placement:
  match ok recipe:
    case False{} _: S.Placement{state,world,False{}}
    case True{} D.Mushroom{_,_,_,_,_,support,_,_}: supported(test(P.shift(at,P.Position{0,4294967295,0}),support,world),height,recipe,state,noises,world,at)

@unsafe
def test(point: Maybe<&2,P.Position>,predicate: P.Predicate,world: E.World) -> Bool:
  match point:
    case None{}: False{}
    case Some{at}: S.test(predicate,world,at)

@unsafe
def supported(ok: Bool,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position) -> S.Placement:
  match ok:
    case False{}: S.Placement{state,world,False{}}
    case True{}: cleared(clear_y(0,height,recipe,world,at),height,recipe,state,noises,world,at)

@unsafe
def clear_y(+y: U32,+height: U32,+recipe: D.Mushroom,+world: E.World,+at: P.Position) -> Bool:
  clear_more(U32.is_le(y,height),y,height,recipe,world,at)

@unsafe
def clear_more(more: Bool,+y: U32,+height: U32,+recipe: D.Mushroom,+world: E.World,+at: P.Position) -> Bool:
  match more recipe:
    case False{} _: True{}
    case True{} D.Mushroom{red,radius,_,_,_,_,clearance,_}: clear_cells(0,y,height,U32.mul(radius,W.flag(Bool.and(Bool.not(red),U32.is_gt(y,3)))),clearance,recipe,world,at)

@unsafe
def clear_cells(+i: U32,+y: U32,+height: U32,+radius: U32,+predicate: P.Predicate,+recipe: D.Mushroom,+world: E.World,+at: P.Position) -> Bool:
  +side = U32.inc(U32.mul(radius,2))
  clear_cell(U32.is_lt(i,U32.mul(side,side)),i,y,height,radius,predicate,recipe,world,at,side)

@unsafe
def clear_cell(more: Bool,+i: U32,+y: U32,+height: U32,+radius: U32,+predicate: P.Predicate,+recipe: D.Mushroom,+world: E.World,+at: P.Position,+side: U32) -> Bool:
  match more:
    case False{}: clear_y(U32.inc(y),height,recipe,world,at)
    case True{}: clear_allowed(test(P.shift(at,P.Position{U32.sub(U32.div(i,side),radius),y,U32.sub(U32.mod(i,side),radius)}),predicate,world),i,y,height,radius,predicate,recipe,world,at)

@unsafe
def clear_allowed(ok: Bool,i: U32,y: U32,height: U32,radius: U32,predicate: P.Predicate,recipe: D.Mushroom,world: E.World,at: P.Position) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: clear_cells(U32.inc(i),y,height,radius,predicate,recipe,world,at)

@unsafe
def cleared(ok: Bool,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position) -> S.Placement:
  match ok recipe:
    case False{} _: S.Placement{state,world,False{}}
    case True{} D.Mushroom{red,_,_,_,_,_,_,_}: cap_layer(U32.sub(height,U32.mul(W.flag(red),3)),height,recipe,state,noises,world,world,at)

@unsafe
def cap_layer(+y: U32,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+snapshot: E.World,+world: E.World,+at: P.Position) -> S.Placement:
  match recipe:
    case D.Mushroom{+red,+radius,_,_,_,_,_,_}: cap_more(U32.is_le(y,height),Bool.and(red,U32.is_eq(y,height)),radius,y,height,recipe,state,noises,snapshot,world,at)

@unsafe
def cap_more(more: Bool,+narrow: Bool,+radius: U32,+y: U32,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+snapshot: E.World,+world: E.World,+at: P.Position) -> S.Placement:
  match more:
    case False{}: stem(0,height,recipe,state,noises,snapshot,world,at)
    case True{}: cap_radius(Bool.and(narrow,U32.is_zero(radius)),U32.sub(radius,W.flag(narrow)),y,height,recipe,state,noises,snapshot,world,at)

@unsafe
def cap_radius(empty: Bool,radius: U32,+y: U32,height: U32,recipe: D.Mushroom,state: W.U64,noises: Q.Table<N.Program>,snapshot: E.World,world: E.World,at: P.Position) -> S.Placement:
  match empty:
    case True{}: cap_layer(U32.inc(y),height,recipe,state,noises,snapshot,world,at)
    case False{}: cap_cells(0,radius,y,height,recipe,state,noises,snapshot,world,at)

@unsafe
def cap_cells(+i: U32,+radius: U32,+y: U32,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+snapshot: E.World,+world: E.World,+at: P.Position) -> S.Placement:
  +side = U32.inc(U32.mul(radius,2))
  cap_cell(U32.is_lt(i,U32.mul(side,side)),i,radius,y,height,recipe,state,noises,snapshot,world,at,side)

@unsafe
def cap_cell(more: Bool,+i: U32,+radius: U32,+y: U32,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+snapshot: E.World,+world: E.World,+at: P.Position,+side: U32) -> S.Placement:
  match more recipe:
    case False{} _: cap_layer(U32.inc(y),height,recipe,state,noises,snapshot,world,at)
    case True{} D.Mushroom{+red,_,_,_,_,_,_,_}:
      +x = U32.div(i,side)
      +z = U32.mod(i,side)
      +ex = Bool.or(U32.is_zero(x),U32.is_eq(x,U32.sub(side,1)))
      +ez = Bool.or(U32.is_zero(z),U32.is_eq(z,U32.sub(side,1)))
      cap_kept(Bool.not(Bool.or(Bool.and(Bool.and(red,U32.is_lt(y,height)),U32.is_eq(W.flag(ex),W.flag(ez))),Bool.and(Bool.not(red),Bool.and(ex,ez)))),i,radius,y,height,recipe,state,noises,snapshot,world,at,x,z,ex,ez)

@unsafe
def mask(red: Bool,+x: U32,+z: U32,+radius: U32,+y: U32,+height: U32,+foliage: U32,+ex: Bool,+ez: Bool) -> U32:
  match red:
    case True{}:
      +center = U32.sub(foliage,2)
      face_mask(H.signed_less(U32.sub(x,radius),U32.sub(0,center)),H.signed_less(center,U32.sub(x,radius)),
        H.signed_less(U32.sub(z,radius),U32.sub(0,center)),H.signed_less(center,U32.sub(z,radius)),U32.is_ge(y,U32.sub(height,1)))
    case False{}: face_mask(Bool.or(U32.is_zero(x),Bool.and(ez,U32.is_eq(x,1))),Bool.or(U32.is_eq(x,U32.mul(radius,2)),Bool.and(ez,U32.is_eq(x,U32.sub(U32.mul(radius,2),1)))),
      Bool.or(U32.is_zero(z),Bool.and(ex,U32.is_eq(z,1))),Bool.or(U32.is_eq(z,U32.mul(radius,2)),Bool.and(ex,U32.is_eq(z,U32.sub(U32.mul(radius,2),1)))),False{})

def face_mask(a: Bool,b: Bool,c: Bool,d: Bool,e: Bool) -> U32:
  U32.add(W.flag(a),U32.add(U32.mul(W.flag(b),2),U32.add(U32.mul(W.flag(c),4),U32.add(U32.mul(W.flag(d),8),U32.mul(W.flag(e),16)))))

@unsafe
def cap_kept(kept: Bool,+i: U32,+radius: U32,+y: U32,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+snapshot: E.World,+world: E.World,+at: P.Position,+x: U32,+z: U32,ex: Bool,ez: Bool) -> S.Placement:
  match kept recipe:
    case False{} _: cap_cells(U32.inc(i),radius,y,height,recipe,state,noises,snapshot,world,at)
    case True{} D.Mushroom{red,foliage,cap,_,_,_,_,_}: cap_sample(B.sample(cap,state,at,world,noises),mask(red,x,z,radius,y,height,foliage,ex,ez),
      P.shift(at,P.Position{U32.sub(x,radius),y,U32.sub(z,radius)}),i,radius,y,height,recipe,noises,snapshot,world,at)

@unsafe
def cap_sample(sample: B.Sample,mask: U32,+point: Maybe<&2,P.Position>,+i: U32,+radius: U32,+y: U32,+height: U32,+recipe: D.Mushroom,+noises: Q.Table<N.Program>,+snapshot: E.World,+world: E.World,+at: P.Position) -> S.Placement:
  match sample recipe:
    case B.Sample{state,source} D.Mushroom{_,_,_,_,faces,_,_,replaceable}: cap_cells(U32.inc(i),radius,y,height,recipe,state,noises,snapshot,
      write(test(point,replaceable,snapshot),point,D.mapped(faces,B.value(source),mask),world),at)

@unsafe
def write(ok: Bool,point: Maybe<&2,P.Position>,material: U32,world: E.World) -> E.World:
  match ok point:
    case True{} Some{at}: E.put(world,at,material)
    case _ _: world

@unsafe
def stem(+y: U32,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+snapshot: E.World,+world: E.World,+at: P.Position) -> S.Placement:
  stem_more(U32.is_lt(y,height),y,height,recipe,state,noises,snapshot,world,at)

@unsafe
def stem_more(more: Bool,+y: U32,+height: U32,+recipe: D.Mushroom,+state: W.U64,+noises: Q.Table<N.Program>,+snapshot: E.World,+world: E.World,+at: P.Position) -> S.Placement:
  match more recipe:
    case False{} _: S.Placement{state,world,True{}}
    case True{} D.Mushroom{_,_,_,provider,_,_,_,_}: stem_sample(B.sample(provider,state,at,world,noises),P.shift(at,P.Position{0,y,0}),y,height,recipe,noises,snapshot,world,at)

@unsafe
def stem_sample(sample: B.Sample,+point: Maybe<&2,P.Position>,+y: U32,+height: U32,+recipe: D.Mushroom,+noises: Q.Table<N.Program>,+snapshot: E.World,+world: E.World,+at: P.Position) -> S.Placement:
  match sample recipe:
    case B.Sample{state,material} D.Mushroom{_,_,_,_,_,_,_,replaceable}: stem(U32.inc(y),height,recipe,state,noises,snapshot,
      write(test(point,replaceable,snapshot),point,B.value(material),world),at)
