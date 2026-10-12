import Base
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_checks.bend as C
import ./placement_random.bend as R
import ./material_columns_data.bend as MD
import ./density.bend as Q
import ./word64.bend as W
import ./int_provider.bend as I

# Registered weighted surface plants and aquatic states share selection, but
# retain different replacement rules. All arithmetic belongs to Bend's stream.
type State is Data:
  State{lower: U32,upper: U32,weight: F32,band: U32,dry: Bool,survival: P.Predicate,upper_allowed: P.Predicate}
type Bamboo is Data:
  Bamboo{probability: F32,trunk: U32,crown: Q.Table<U32>,podzol: U32,survival: P.Predicate,podzol_allowed: P.Predicate}
type Placement is Data:
  Placement{state: W.U64,world: E.World,placed: Bool}

@unsafe
def test(+predicate: P.Predicate,+world: E.World,+at: P.Position) -> Bool:
  C.true_value(C.predicate(predicate,world,at))

@unsafe
def valid(states: List<&2,State>,+palette: U32) -> Bool:
  match states:
    case Nil{}: False{}
    case _: states_valid(states,palette)

@unsafe
def states_valid(states: List<&2,State>,+palette: U32) -> Bool:
  match states:
    case Nil{}: True{}
    case State{lower,upper,+weight,band,_,survival,allowed} <> rest:
      Bool.and(U32.is_lt(lower,palette),Bool.and(U32.is_lt(upper,palette),Bool.and(I.finite(weight),
        Bool.and(F32.is_gt(weight,0.0),Bool.and(U32.is_le(band,2),Bool.and(P.predicate_valid(survival,palette),
          Bool.and(P.predicate_valid(allowed,palette),states_valid(rest,palette))))))))

@unsafe
def crown_valid(xs: Q.Table<U32>,+palette: U32) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{x}: U32.is_lt(x,palette)
    case Q.Branch{_,a,b}: Bool.and(crown_valid(a,palette),crown_valid(b,palette))

@unsafe
def bamboo_valid(recipe: Bamboo,+palette: U32) -> Bool:
  match recipe:
    case Bamboo{+p,trunk,+crown,podzol,survival,allowed}:
      Bool.and(I.finite(p),Bool.and(F32.is_ge(p,0.0),Bool.and(F32.is_le(p,1.0),Bool.and(U32.is_lt(trunk,palette),
        Bool.and(U32.is_lt(podzol,palette),Bool.and(U32.is_eq(Q.table_size(U32,crown),3),
          Bool.and(crown_valid(crown,palette),Bool.and(P.predicate_valid(survival,palette),P.predicate_valid(allowed,palette)))))))))

@unsafe
def column_band(column: Maybe<&2,MD.Column>) -> U32:
  match column:
    case None{}: 4294967295
    case Some{MD.Column{_,_,_,_,_,_,_,_,packed,_}}: band_flag(U32.is_ne(U32.and(packed,134217728),0))

def band_flag(low: Bool) -> U32:
  match low:
    case True{}: 1
    case False{}: 2

@unsafe
def band(+world: E.World,at: P.Position) -> U32:
  match world at:
    case E.World{source,_,_} P.Position{x,_,z}: column_band(E.column(source,x,z))

@unsafe
def eligible(+entry: U32,+band: U32) -> Bool:
  Bool.and(U32.is_le(band,2),Bool.or(U32.is_zero(entry),U32.is_eq(entry,band)))

# Scale by the largest eligible weight before summing. Large finite registry
# weights cannot overflow the selection total. The final eligible entry absorbs
# F32 summation roundoff at the upper end of the half-open random interval.
@unsafe
def maximum(states: List<&2,State>,+band: U32,+acc: F32) -> F32:
  match states:
    case Nil{}: acc
    case State{_,_,weight,entry,_,_,_} <> rest: maximum(rest,band,F32.max(acc,scaled_weight(eligible(entry,band),weight,1.0)))

@unsafe
def scaled_weight(ok: Bool,+weight: F32,+scale: F32) -> F32:
  match ok:
    case False{}: 0.0
    case True{}: F32.div(weight,scale)

@unsafe
def total(states: List<&2,State>,+band: U32,+scale: F32,+acc: F32) -> F32:
  match states:
    case Nil{}: acc
    case State{_,_,weight,entry,_,_,_} <> rest: total(rest,band,scale,F32.add(acc,scaled_weight(eligible(entry,band),weight,scale)))

@unsafe
def choose(states: List<&2,State>,+band: U32,+scale: F32,+value: F32,fallback: Maybe<&2,State>) -> Maybe<&2,State>:
  match states:
    case Nil{}: fallback
    case +row <> rest: choose_row(row,rest,band,scale,value,fallback)

@unsafe
def choose_row(+row: State,rest: List<&2,State>,+band: U32,+scale: F32,+value: F32,fallback: Maybe<&2,State>) -> Maybe<&2,State>:
  match row:
    case State{_,_,weight,entry,_,_,_}: choose_eligible(eligible(entry,band),row,rest,band,scale,value,weight,fallback)

@unsafe
def choose_eligible(ok: Bool,+row: State,rest: List<&2,State>,+band: U32,+scale: F32,+value: F32,+weight: F32,fallback: Maybe<&2,State>) -> Maybe<&2,State>:
  match ok:
    case False{}: choose(rest,band,scale,value,fallback)
    case True{}: choose_selected(F32.is_lt(value,F32.div(weight,scale)),row,rest,band,scale,F32.sub(value,F32.div(weight,scale)))

@unsafe
def choose_selected(ok: Bool,+row: State,rest: List<&2,State>,+band: U32,+scale: F32,+value: F32) -> Maybe<&2,State>:
  match ok:
    case True{}: Some{row}
    case False{}: choose(rest,band,scale,value,Some{row})

@unsafe
def is_air(material: Maybe<&2,U32>) -> Bool:
  match material:
    case Some{0}: True{}
    case _: False{}

@unsafe
def soil(material: Maybe<&2,U32>,+world: E.World,+dry: Bool) -> Bool:
  match material world:
    case Some{m} E.World{_,P.Policy{_,flags,_,_,_,_,_},_}: soil_flag(dry,Q.get(U32,flags,m,0))
    case _ _: False{}

@unsafe
def soil_flag(dry: Bool,+flags: U32) -> Bool:
  match dry:
    case True{}: U32.is_ne(U32.and(flags,2),0)
    case False{}: U32.is_ne(U32.and(flags,1),0)

@unsafe
def ground(at: Maybe<&2,P.Position>,+world: E.World,+dry: Bool) -> Bool:
  match at:
    case None{}: False{}
    case Some{p}: soil(E.material(world,p),world,dry)

@unsafe
def inside(+world: E.World,at: P.Position) -> Bool:
  match world at:
    case E.World{source,_,_} P.Position{_,y,_}: E.inside_y(source,y)

@unsafe
def support(aquatic: Bool,+row: State,+world: E.World,+at: P.Position) -> Bool:
  match aquatic row:
    case True{} State{_,_,_,_,_,survival,_}: test(survival,world,at)
    case False{} State{_,_,_,_,dry,_,_}: Bool.and(inside(world,at),Bool.and(is_air(E.material(world,at)),ground(P.shift(at,P.Position{0,4294967295,0}),world,dry)))

@unsafe
def top_allowed(aquatic: Bool,+row: State,+world: E.World,+above: P.Position) -> Bool:
  match aquatic row:
    case True{} State{_,_,_,_,_,_,allowed}: test(allowed,world,above)
    case False{} _: Bool.and(inside(world,above),is_air(E.material(world,above)))

@unsafe
def selected(row: Maybe<&2,State>,+aquatic: Bool,+state: W.U64,+world: E.World,+at: P.Position) -> Placement:
  match row:
    case None{}: Placement{state,world,False{}}
    case Some{+row}: supported(support(aquatic,row,world,at),row,aquatic,state,world,at)

@unsafe
def supported(ok: Bool,+row: State,+aquatic: Bool,+state: W.U64,+world: E.World,+at: P.Position) -> Placement:
  match ok row:
    case False{} _: Placement{state,world,False{}}
    case True{} State{lower,0,_,_,_,_,_}: Placement{state,E.put(world,at,lower),True{}}
    case True{} State{lower,upper,_,_,_,_,_}: above(P.shift(at,P.Position{0,1,0}),row,aquatic,state,world,at,lower,upper)

@unsafe
def above(above: Maybe<&2,P.Position>,+row: State,+aquatic: Bool,+state: W.U64,+world: E.World,+at: P.Position,+lower: U32,+upper: U32) -> Placement:
  match above:
    case None{}: Placement{state,world,False{}}
    case Some{+p}: paired(top_allowed(aquatic,row,world,p),state,world,at,p,lower,upper)

@unsafe
def paired(ok: Bool,+state: W.U64,+world: E.World,+at: P.Position,+above: P.Position,+lower: U32,+upper: U32) -> Placement:
  match ok:
    case False{}: Placement{state,world,False{}}
    case True{}: Placement{state,E.put(E.put(world,above,upper),at,lower),True{}}

@unsafe
def drawn(draw: R.Draw,+states: List<&2,State>,+aquatic: Bool,+world: E.World,+at: P.Position,+band: U32,+scale: F32) -> Placement:
  match draw:
    case R.Draw{state,word}: selected(choose(states,band,F32.max(scale,0.0000000000000000000000000000000000000117549435),
      F32.mul(R.unit(word),total(states,band,F32.max(scale,0.0000000000000000000000000000000000000117549435),0.0)),None{}),aquatic,state,world,at)

@unsafe
def place(+states: List<&2,State>,+aquatic: Bool,+state: W.U64,+world: E.World,+at: P.Position) -> Placement:
  drawn(R.next(state),states,aquatic,world,at,band(world,at),maximum(states,band(world,at),0.0))

# Bamboo queues its writes while checking the pre-feature world. Podzol must
# not change height/survival probes midway through this same feature.
@unsafe
def bamboo(recipe: Bamboo,+state: W.U64,+world: E.World,+at: P.Position) -> Placement:
  match recipe:
    case Bamboo{p,trunk,crown,podzol,+survival,allowed}:
      bamboo_supported(Bool.and(is_air(E.material(world,at)),test(survival,world,at)),p,trunk,crown,podzol,allowed,state,world,at)

@unsafe
def bamboo_supported(ok: Bool,+probability: F32,+trunk: U32,+crown: Q.Table<U32>,+podzol: U32,+allowed: P.Predicate,+state: W.U64,+world: E.World,+at: P.Position) -> Placement:
  match ok:
    case False{}: Placement{state,world,False{}}
    case True{}: bamboo_height(R.integer(state,5,16),probability,trunk,crown,podzol,allowed,world,at)

@unsafe
def bamboo_height(draw: R.Draw,+probability: F32,+trunk: U32,+crown: Q.Table<U32>,+podzol: U32,+allowed: P.Predicate,+world: E.World,+at: P.Position) -> Placement:
  match draw:
    case R.Draw{state,value}: bamboo_chance(R.next(state),probability,R.low(value),trunk,crown,podzol,allowed,world,at)

@unsafe
def bamboo_chance(draw: R.Draw,+probability: F32,+height: U32,+trunk: U32,+crown: Q.Table<U32>,+podzol: U32,+allowed: P.Predicate,+world: E.World,+at: P.Position) -> Placement:
  match draw:
    case R.Draw{state,word}: bamboo_patch(F32.is_lt(R.unit(word),probability),state,height,trunk,crown,podzol,allowed,world,at)

@unsafe
def bamboo_patch(patch: Bool,+state: W.U64,+height: U32,+trunk: U32,+crown: Q.Table<U32>,+podzol: U32,+allowed: P.Predicate,+world: E.World,+at: P.Position) -> Placement:
  match patch:
    case False{}: bamboo_stem(0,height,trunk,crown,state,world,world,at)
    case True{}: bamboo_radius(R.integer(state,1,4),height,trunk,crown,podzol,allowed,world,at)

@unsafe
def bamboo_radius(draw: R.Draw,+height: U32,+trunk: U32,+crown: Q.Table<U32>,+podzol: U32,+allowed: P.Predicate,+world: E.World,+at: P.Position) -> Placement:
  match draw:
    case R.Draw{state,value}: bamboo_stem(0,height,trunk,crown,state,world,podzol_columns(0,0,R.low(value),podzol,allowed,world,world,at),at)

@unsafe
def podzol_columns(+x: U32,+z: U32,+radius: U32,+podzol: U32,+allowed: P.Predicate,+base: E.World,+world: E.World,+at: P.Position) -> E.World:
  podzol_more(U32.is_le(x,U32.mul(radius,2)),x,z,radius,podzol,allowed,base,world,at)

@unsafe
def podzol_more(more: Bool,+x: U32,+z: U32,+radius: U32,+podzol: U32,+allowed: P.Predicate,+base: E.World,+world: E.World,+at: P.Position) -> E.World:
  match more:
    case False{}: world
    case True{}:
      +dx = U32.sub(x,radius)
      +dz = U32.sub(z,radius)
      podzol_circle(U32.is_le(U32.add(U32.mul(dx,dx),U32.mul(dz,dz)),U32.mul(radius,radius)),x,z,radius,podzol,allowed,base,world,at)

@unsafe
def podzol_circle(ok: Bool,+x: U32,+z: U32,+radius: U32,+podzol: U32,+allowed: P.Predicate,+base: E.World,+world: E.World,+at: P.Position) -> E.World:
  match ok:
    case False{}: podzol_advance(x,z,radius,podzol,allowed,base,world,at)
    case True{}: podzol_position(P.shift(at,P.Position{U32.sub(x,radius),0,U32.sub(z,radius)}),x,z,radius,podzol,allowed,base,world,at)

@unsafe
def podzol_position(pos: Maybe<&2,P.Position>,+x: U32,+z: U32,+radius: U32,+podzol: U32,+allowed: P.Predicate,+base: E.World,+world: E.World,+at: P.Position) -> E.World:
  match pos:
    case None{}: podzol_advance(x,z,radius,podzol,allowed,base,world,at)
    case Some{P.Position{+px,_,+pz}}: podzol_height(E.height(base,px,pz,1),px,pz,x,z,radius,podzol,allowed,base,world,at)

@unsafe
def podzol_height(height: Maybe<&2,U32>,+px: U32,+pz: U32,+x: U32,+z: U32,+radius: U32,+podzol: U32,+allowed: P.Predicate,+base: E.World,+world: E.World,+at: P.Position) -> E.World:
  match height:
    case None{}: podzol_advance(x,z,radius,podzol,allowed,base,world,at)
    case Some{y}: podzol_write(P.shift(P.Position{px,y,pz},P.Position{0,4294967295,0}),x,z,radius,podzol,allowed,base,world,at)

@unsafe
def podzol_write(pos: Maybe<&2,P.Position>,+x: U32,+z: U32,+radius: U32,+podzol: U32,+allowed: P.Predicate,+base: E.World,+world: E.World,+at: P.Position) -> E.World:
  match pos:
    case None{}: podzol_advance(x,z,radius,podzol,allowed,base,world,at)
    case Some{+p}: podzol_allowed(test(allowed,base,p),p,x,z,radius,podzol,allowed,base,world,at)

@unsafe
def podzol_allowed(ok: Bool,+p: P.Position,+x: U32,+z: U32,+radius: U32,+podzol: U32,+allowed: P.Predicate,+base: E.World,+world: E.World,+at: P.Position) -> E.World:
  match ok:
    case False{}: podzol_advance(x,z,radius,podzol,allowed,base,world,at)
    case True{}: podzol_advance(x,z,radius,podzol,allowed,base,E.put(world,p,podzol),at)

@unsafe
def podzol_advance(+x: U32,+z: U32,+radius: U32,+podzol: U32,+allowed: P.Predicate,+base: E.World,+world: E.World,+at: P.Position) -> E.World:
  podzol_row(U32.is_eq(z,U32.mul(radius,2)),x,z,radius,podzol,allowed,base,world,at)

@unsafe
def podzol_row(end: Bool,+x: U32,+z: U32,+radius: U32,+podzol: U32,+allowed: P.Predicate,+base: E.World,+world: E.World,+at: P.Position) -> E.World:
  match end:
    case True{}: podzol_columns(U32.inc(x),0,radius,podzol,allowed,base,world,at)
    case False{}: podzol_columns(x,U32.inc(z),radius,podzol,allowed,base,world,at)

@unsafe
def bamboo_stem(+count: U32,+height: U32,+trunk: U32,+crown: Q.Table<U32>,+state: W.U64,+base: E.World,+world: E.World,+at: P.Position) -> Placement:
  bamboo_more(U32.is_lt(count,height),count,height,trunk,crown,state,base,world,at)

@unsafe
def bamboo_more(more: Bool,+count: U32,+height: U32,+trunk: U32,+crown: Q.Table<U32>,+state: W.U64,+base: E.World,+world: E.World,+at: P.Position) -> Placement:
  match more:
    case False{}: bamboo_crown(U32.is_ge(count,3),count,crown,state,world,at)
    case True{}: bamboo_position(P.shift(at,P.Position{0,count,0}),count,height,trunk,crown,state,base,world,at)

@unsafe
def bamboo_position(pos: Maybe<&2,P.Position>,+count: U32,+height: U32,+trunk: U32,+crown: Q.Table<U32>,+state: W.U64,+base: E.World,+world: E.World,+at: P.Position) -> Placement:
  match pos:
    case None{}: bamboo_crown(U32.is_ge(count,3),count,crown,state,world,at)
    case Some{+p}: bamboo_air(is_air(E.material(base,p)),p,count,height,trunk,crown,state,base,world,at)

@unsafe
def bamboo_air(ok: Bool,+p: P.Position,+count: U32,+height: U32,+trunk: U32,+crown: Q.Table<U32>,+state: W.U64,+base: E.World,+world: E.World,+at: P.Position) -> Placement:
  match ok:
    case False{}: bamboo_crown(U32.is_ge(count,3),count,crown,state,world,at)
    case True{}: bamboo_stem(U32.inc(count),height,trunk,crown,state,base,E.put(world,p,trunk),at)

@unsafe
def bamboo_crown(ok: Bool,+count: U32,+crown: Q.Table<U32>,+state: W.U64,+world: E.World,+at: P.Position) -> Placement:
  match ok:
    case False{}: Placement{state,world,True{}}
    case True{}: Placement{state,crown_blocks(0,count,crown,world,at),True{}}

@unsafe
def crown_blocks(+i: U32,+count: U32,+crown: Q.Table<U32>,+world: E.World,+at: P.Position) -> E.World:
  match i:
    case 3: world
    case _: crown_position(P.shift(at,P.Position{0,U32.sub(count,i),0}),i,count,crown,world,at)

@unsafe
def crown_position(pos: Maybe<&2,P.Position>,+i: U32,+count: U32,+crown: Q.Table<U32>,+world: E.World,+at: P.Position) -> E.World:
  match pos:
    case None{}: crown_blocks(U32.inc(i),count,crown,world,at)
    case Some{p}: crown_blocks(U32.inc(i),count,crown,E.put(world,p,Q.get(U32,crown,i,0)),at)
