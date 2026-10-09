import Base
import ./block_provider.bend as B
import ./provider_noise.bend as N
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_checks.bend as C
import ./placement_random.bend as R
import ./density.bend as Q
import ./word64.bend as W
import ./int_provider.bend as I

# Pure writers share the live placement world. The caller retains the returned
# RNG state and world before asking its placement cursor for the next candidate.
type PlantState is Data:
  PlantState{source: U32,lower: Q.Table<U32>,upper: Q.Table<U32>,survival: P.Predicate,upper_allowed: P.Predicate}
type Layer is Data:
  Layer{height: I.Provider,provider: B.Provider}
type Feature is Data:
  Simple{provider: B.Provider,states: List<&2,PlantState>,water: P.Predicate,current_survival: Bool}
  Column{layers: List<&2,Layer>,direction: P.Position,allowed: P.Predicate,prioritize_tip: Bool}
  Disk{provider: B.Provider,target: P.Predicate,radius: I.Provider,half_height: U32}
type Model is Data:
  Model{noises: Q.Table<N.Program>,current_states: Q.Table<Maybe<&2,PlantState>>}
type Placement is Data:
  Placement{state: W.U64,world: E.World,placed: Bool}
type MeasuredLayer is Data:
  MeasuredLayer{height: U32,provider: B.Provider}
type Heights is Data:
  Heights{state: W.U64,layers: List<&2,MeasuredLayer>,total: U32}

@unsafe
def test(+predicate: P.Predicate,+world: E.World,+at: P.Position) -> Bool:
  C.true_value(C.predicate(predicate,world,at))

@unsafe
def materials_valid(xs: Q.Table<U32>,+palette: U32) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{x}: U32.is_lt(x,palette)
    case Q.Branch{_,a,b}: Bool.and(materials_valid(a,palette),materials_valid(b,palette))

@unsafe
def plant_valid(+plant: PlantState,+palette: U32) -> Bool:
  match plant:
    case PlantState{source,+lower,+upper,survival,allowed}:
      Bool.and(U32.is_lt(source,palette),Bool.and(U32.is_eq(Q.table_size(U32,lower),2),
        Bool.and(U32.is_eq(Q.table_size(U32,upper),2),Bool.and(materials_valid(lower,palette),
        Bool.and(materials_valid(upper,palette),Bool.and(P.predicate_valid(survival,palette),P.predicate_valid(allowed,palette)))))))

@unsafe
def plants_valid(xs: List<&2,PlantState>,+palette: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case p <> rest: Bool.and(plant_valid(p,palette),plants_valid(rest,palette))

@unsafe
def find(xs: List<&2,PlantState>,+source: U32) -> Maybe<&2,PlantState>:
  match xs:
    case Nil{}: None{}
    case PlantState{+id,lower,upper,survival,allowed} <> rest: find_equal(U32.is_eq(id,source),PlantState{id,lower,upper,survival,allowed},rest,source)

@unsafe
def find_equal(equal: Bool,plant: PlantState,rest: List<&2,PlantState>,+source: U32) -> Maybe<&2,PlantState>:
  match equal:
    case True{}: Some{plant}
    case False{}: find(rest,source)

@unsafe
def covered(outputs: List<&2,U32>,+states: List<&2,PlantState>) -> Bool:
  match outputs:
    case Nil{}: True{}
    case id <> rest: Bool.and(present(find(states,id)),covered(rest,states))

def present(value: Maybe<&2,PlantState>) -> Bool:
  match value:
    case None{}: False{}
    case Some{_}: True{}

@unsafe
def maximum(provider: I.Provider) -> U32:
  match provider:
    case I.Constant{x}: x
    case I.Uniform{_,b}: b
    case I.Biased{_,b}: b
    case I.Trapezoid{_,b,_}: b
    case I.Clamped{_,_,b}: b
    case I.Normal{_,_,_,b}: b
    case I.Weighted{xs,_}: maximum_entries(xs,0)

@unsafe
def maximum_entries(xs: List<&2,I.Entry>,+value: U32) -> U32:
  match xs:
    case Nil{}: value
    case I.Entry{provider,weight} <> rest: maximum_weight(U32.is_zero(weight),provider,rest,value)

@unsafe
def maximum_weight(zero: Bool,provider: I.Provider,rest: List<&2,I.Entry>,+value: U32) -> U32:
  match zero:
    case True{}: maximum_entries(rest,value)
    case False{}: maximum_entries(rest,U32.max(value,maximum(provider)))

@unsafe
def layers_valid(xs: List<&2,Layer>,+palette: U32,+noises: U32,+count: U32,+total: U32,+limit: U32) -> Bool:
  match xs:
    case Nil{}: Bool.and(U32.is_gt(count,0),Bool.and(U32.is_le(count,128),U32.is_le(total,limit)))
    case Layer{+height,provider} <> rest:
      Bool.and(B.valid(provider,palette,noises),Bool.and(I.valid(height),Bool.and(I.within(height,0,4096),
        layers_valid(rest,palette,noises,U32.inc(count),U32.add(total,maximum(height)),limit))))

@unsafe
def unit_component(+value: U32) -> Bool:
  Bool.or(U32.is_zero(value),Bool.or(U32.is_eq(value,1),U32.is_eq(value,4294967295)))

@unsafe
def unit(+direction: P.Position) -> Bool:
  match direction:
    case P.Position{+x,+y,+z}: Bool.and(unit_component(x),Bool.and(unit_component(y),Bool.and(unit_component(z),
      U32.is_eq(U32.add(W.flag(U32.is_ne(x,0)),U32.add(W.flag(U32.is_ne(y,0)),W.flag(U32.is_ne(z,0)))),1))))

@unsafe
def vertical_limit(direction: P.Position) -> U32:
  match direction:
    case P.Position{_,0,_}: 15
    case _: 4096

@unsafe
def valid(feature: Feature,+palette: U32,+noises: U32) -> Bool:
  match feature:
    case Simple{+provider,+states,water,current}:
      Bool.and(B.valid(provider,palette,noises),Bool.and(plants_valid(states,palette),Bool.and(P.predicate_valid(water,palette),
        Bool.or(current,covered(B.outputs(provider),states)))))
    case Column{layers,+direction,allowed,_}: Bool.and(unit(direction),Bool.and(P.predicate_valid(allowed,palette),layers_valid(layers,palette,noises,0,0,vertical_limit(direction))))
    case Disk{provider,target,+radius,half}: Bool.and(B.valid(provider,palette,noises),Bool.and(P.predicate_valid(target,palette),Bool.and(I.valid(radius),Bool.and(I.within(radius,0,8),U32.is_le(half,4)))))

@unsafe
def current_at(+available: Bool,+states: Q.Table<Maybe<&2,PlantState>>,+id: U32) -> Maybe<&2,PlantState>:
  match available:
    case False{}: None{}
    case True{}: Q.get(Maybe<&2,PlantState>,states,id,None{})

@unsafe
def choose_state(found: Maybe<&2,PlantState>,+model: Model,+current: Bool,+id: U32) -> Maybe<&2,PlantState>:
  match found model:
    case Some{p} _: Some{p}
    case None{} Model{_,+states}: current_at(Bool.and(current,U32.is_lt(id,Q.table_size(Maybe<&2,PlantState>,states))),states,id)

@unsafe
def simple_sample(sample: B.Sample,+states: List<&2,PlantState>,+water: P.Predicate,+current: Bool,+model: Model,+world: E.World,+at: P.Position) -> Placement:
  match sample:
    case B.Sample{state,None{}}: Placement{state,world,False{}}
    case B.Sample{state,Some{+id}}: simple_state(choose_state(find(states,id),model,current,id),water,state,world,at)

@unsafe
def simple_state(plant: Maybe<&2,PlantState>,+water: P.Predicate,+state: W.U64,+world: E.World,+at: P.Position) -> Placement:
  match plant:
    case None{}: Placement{state,world,False{}}
    case Some{PlantState{_,lower,+upper,survival,allowed}}:
      simple_survives(test(survival,world,at),lower,upper,water,allowed,state,world,at)

@unsafe
def simple_survives(ok: Bool,+lower: Q.Table<U32>,+upper: Q.Table<U32>,+water: P.Predicate,+allowed: P.Predicate,+state: W.U64,+world: E.World,+at: P.Position) -> Placement:
  match ok:
    case False{}: Placement{state,world,False{}}
    case True{}: simple_above(P.shift(at,P.Position{0,1,0}),U32.is_ne(Q.get(U32,upper,0,0),0),lower,upper,water,allowed,state,world,at)

@unsafe
def simple_above(above: Maybe<&2,P.Position>,+paired: Bool,+lower: Q.Table<U32>,+upper: Q.Table<U32>,+water: P.Predicate,+allowed: P.Predicate,+state: W.U64,+world: E.World,+at: P.Position) -> Placement:
  match above paired:
    case None{} True{}: Placement{state,world,False{}}
    case Some{+above} _: simple_allowed(Bool.or(Bool.not(paired),test(allowed,world,above)),paired,lower,upper,water,state,world,at,above)
    case None{} False{}: Placement{state,E.put(world,at,Q.get(U32,lower,W.flag(test(water,world,at)),0)),True{}}

@unsafe
def simple_allowed(ok: Bool,+paired: Bool,+lower: Q.Table<U32>,+upper: Q.Table<U32>,+water: P.Predicate,+state: W.U64,+world: E.World,+at: P.Position,+above: P.Position) -> Placement:
  match ok:
    case False{}: Placement{state,world,False{}}
    case True{}:
      +bottom = Q.get(U32,lower,W.flag(test(water,world,at)),0)
      +top = Q.get(U32,upper,W.flag(test(water,world,above)),0)
      simple_written(paired,state,E.put(world,at,bottom),above,top)

@unsafe
def simple_written(paired: Bool,+state: W.U64,world: E.World,+above: P.Position,+material: U32) -> Placement:
  match paired:
    case False{}: Placement{state,world,True{}}
    case True{}: Placement{state,E.put(world,above,material),True{}}

@unsafe
def heights(xs: List<&2,Layer>,+state: W.U64,acc: List<&2,MeasuredLayer>,+total: U32) -> Heights:
  match xs:
    case Nil{}: Heights{state,List.reverse(&2,MeasuredLayer,acc),total}
    case Layer{height,provider} <> rest: height_draw(I.sample(height,state),provider,rest,acc,total)

@unsafe
def height_draw(draw: R.Draw,provider: B.Provider,rest: List<&2,Layer>,acc: List<&2,MeasuredLayer>,+total: U32) -> Heights:
  match draw:
    case R.Draw{state,+value}: heights(rest,state,MeasuredLayer{R.low(value),provider} <> acc,U32.add(total,R.low(value)))

@unsafe
def scaled(+direction: P.Position,+n: U32) -> P.Position:
  match direction:
    case P.Position{x,y,z}: P.Position{U32.mul(x,n),U32.mul(y,n),U32.mul(z,n)}

@unsafe
def capacity(+n: U32,+total: U32,+at: P.Position,+direction: P.Position,+allowed: P.Predicate,+world: E.World) -> U32:
  capacity_more(U32.is_lt(n,total),n,total,at,direction,allowed,world)

@unsafe
def capacity_more(more: Bool,+n: U32,+total: U32,+at: P.Position,+direction: P.Position,+allowed: P.Predicate,+world: E.World) -> U32:
  match more:
    case False{}: n
    case True{}: capacity_next(P.shift(at,scaled(direction,U32.inc(n))),n,total,at,direction,allowed,world)

@unsafe
def capacity_next(next: Maybe<&2,P.Position>,+n: U32,+total: U32,+at: P.Position,+direction: P.Position,+allowed: P.Predicate,+world: E.World) -> U32:
  match next:
    case None{}: n
    case Some{p}: capacity_allowed(test(allowed,world,p),n,total,at,direction,allowed,world)

@unsafe
def capacity_allowed(ok: Bool,+n: U32,+total: U32,+at: P.Position,+direction: P.Position,+allowed: P.Predicate,+world: E.World) -> U32:
  match ok:
    case False{}: n
    case True{}: capacity(U32.inc(n),total,at,direction,allowed,world)

@unsafe
def trim(xs: List<&2,MeasuredLayer>,+remove: U32) -> List<&2,MeasuredLayer>:
  match xs:
    case Nil{}: []
    case MeasuredLayer{+height,provider} <> rest:
      +n = U32.min(height,remove)
      MeasuredLayer{U32.sub(height,n),provider} <> trim(rest,U32.sub(remove,n))

@unsafe
def truncated(tip: Bool,xs: List<&2,MeasuredLayer>,+remove: U32) -> List<&2,MeasuredLayer>:
  match tip:
    case True{}: trim(xs,remove)
    case False{}: List.reverse(&2,MeasuredLayer,trim(List.reverse(&2,MeasuredLayer,xs),remove))

@unsafe
def column_heights(measured: Heights,+direction: P.Position,+allowed: P.Predicate,+tip: Bool,+model: Model,+world: E.World,+at: P.Position) -> Placement:
  match measured:
    case Heights{state,layers,+total}:
      column_layers(truncated(tip,layers,U32.sub(total,capacity(0,total,at,direction,allowed,world))),state,model,world,at,direction,U32.is_ne(total,0))

@unsafe
def column_layers(layers: List<&2,MeasuredLayer>,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+direction: P.Position,+placed: Bool) -> Placement:
  match layers:
    case Nil{}: Placement{state,world,placed}
    case MeasuredLayer{height,provider} <> rest: column_blocks(height,provider,rest,state,model,world,at,direction,placed)

@unsafe
def column_blocks(+n: U32,+provider: B.Provider,rest: List<&2,MeasuredLayer>,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+direction: P.Position,+placed: Bool) -> Placement:
  match n model:
    case 0 _: column_layers(rest,state,model,world,at,direction,placed)
    case _ Model{noises,_}: column_draw(B.sample(provider,state,at,world,noises),U32.sub(n,1),provider,rest,model,world,at,direction,placed)

@unsafe
def column_draw(draw: B.Sample,+n: U32,+provider: B.Provider,rest: List<&2,MeasuredLayer>,+model: Model,+world: E.World,+at: P.Position,+direction: P.Position,+placed: Bool) -> Placement:
  match draw:
    case B.Sample{state,material}: column_next(P.shift(at,direction),n,provider,rest,state,model,E.put(world,at,B.value(material)),direction,placed)

@unsafe
def column_next(next: Maybe<&2,P.Position>,+n: U32,+provider: B.Provider,rest: List<&2,MeasuredLayer>,+state: W.U64,+model: Model,+world: E.World,+direction: P.Position,+placed: Bool) -> Placement:
  match next:
    case None{}: Placement{state,world,placed}
    case Some{at}: column_blocks(n,provider,rest,state,model,world,at,direction,placed)

@unsafe
def disk_radius(draw: R.Draw,+provider: B.Provider,+target: P.Predicate,+half: U32,+model: Model,+world: E.World,+at: P.Position) -> Placement:
  match draw:
    case R.Draw{state,value}: disk_columns(0,0,R.low(value),provider,target,half,state,model,world,at,False{})

# Z-major, X-next, then descending Y, matching the registered disk replay.
# A provider may inspect an earlier block in this same disk, so this inner loop
# remains ordered; independent anchors can be scheduled in parallel by callers.
@unsafe
def disk_columns(+x: U32,+z: U32,+radius: U32,+provider: B.Provider,+target: P.Predicate,+half: U32,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+placed: Bool) -> Placement:
  disk_more(U32.is_le(z,U32.mul(radius,2)),x,z,radius,provider,target,half,state,model,world,at,placed)

@unsafe
def disk_more(more: Bool,+x: U32,+z: U32,+radius: U32,+provider: B.Provider,+target: P.Predicate,+half: U32,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+placed: Bool) -> Placement:
  match more:
    case False{}: Placement{state,world,placed}
    case True{}:
      +dx = U32.sub(x,radius)
      +dz = U32.sub(z,radius)
      disk_circle(U32.is_le(U32.add(U32.mul(dx,dx),U32.mul(dz,dz)),U32.mul(radius,radius)),x,z,radius,provider,target,half,state,model,world,at,placed)

@unsafe
def disk_circle(inside: Bool,+x: U32,+z: U32,+radius: U32,+provider: B.Provider,+target: P.Predicate,+half: U32,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+placed: Bool) -> Placement:
  match inside:
    case False{}: disk_advance(x,z,radius,provider,target,half,state,model,world,at,placed)
    case True{}: disk_blocks(0,x,z,radius,provider,target,half,state,model,world,at,placed)

@unsafe
def disk_advance(+x: U32,+z: U32,+radius: U32,+provider: B.Provider,+target: P.Predicate,+half: U32,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+placed: Bool) -> Placement:
  disk_row(U32.is_eq(x,U32.mul(radius,2)),x,z,radius,provider,target,half,state,model,world,at,placed)

@unsafe
def disk_row(end: Bool,+x: U32,+z: U32,+radius: U32,+provider: B.Provider,+target: P.Predicate,+half: U32,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+placed: Bool) -> Placement:
  match end:
    case True{}: disk_columns(0,U32.inc(z),radius,provider,target,half,state,model,world,at,placed)
    case False{}: disk_columns(U32.inc(x),z,radius,provider,target,half,state,model,world,at,placed)

@unsafe
def disk_blocks(+y: U32,+x: U32,+z: U32,+radius: U32,+provider: B.Provider,+target: P.Predicate,+half: U32,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+placed: Bool) -> Placement:
  disk_block_more(U32.is_le(y,U32.mul(half,2)),y,x,z,radius,provider,target,half,state,model,world,at,placed)

@unsafe
def disk_block_more(more: Bool,+y: U32,+x: U32,+z: U32,+radius: U32,+provider: B.Provider,+target: P.Predicate,+half: U32,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+placed: Bool) -> Placement:
  match more:
    case False{}: disk_advance(x,z,radius,provider,target,half,state,model,world,at,placed)
    case True{}: disk_position(P.shift(at,P.Position{U32.sub(x,radius),U32.sub(half,y),U32.sub(z,radius)}),y,x,z,radius,provider,target,half,state,model,world,at,placed)

@unsafe
def disk_position(pos: Maybe<&2,P.Position>,+y: U32,+x: U32,+z: U32,+radius: U32,+provider: B.Provider,+target: P.Predicate,+half: U32,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+placed: Bool) -> Placement:
  match pos:
    case None{}: disk_blocks(U32.inc(y),x,z,radius,provider,target,half,state,model,world,at,placed)
    case Some{+p}: disk_target(test(target,world,p),p,y,x,z,radius,provider,target,half,state,model,world,at,placed)

@unsafe
def disk_target(ok: Bool,+p: P.Position,+y: U32,+x: U32,+z: U32,+radius: U32,+provider: B.Provider,+target: P.Predicate,+half: U32,+state: W.U64,+model: Model,+world: E.World,+at: P.Position,+placed: Bool) -> Placement:
  match ok model:
    case False{} _: disk_blocks(U32.inc(y),x,z,radius,provider,target,half,state,model,world,at,placed)
    case True{} Model{noises,_}: disk_draw(B.optional(provider,state,p,world,noises),p,y,x,z,radius,provider,target,half,model,world,at,placed)

@unsafe
def disk_draw(draw: B.Sample,+p: P.Position,+y: U32,+x: U32,+z: U32,+radius: U32,+provider: B.Provider,+target: P.Predicate,+half: U32,+model: Model,+world: E.World,+at: P.Position,+placed: Bool) -> Placement:
  match draw:
    case B.Sample{state,None{}}: disk_blocks(U32.inc(y),x,z,radius,provider,target,half,state,model,world,at,placed)
    case B.Sample{state,Some{m}}: disk_blocks(U32.inc(y),x,z,radius,provider,target,half,state,model,E.put(world,p,m),at,True{})

@unsafe
def place(feature: Feature,+state: W.U64,+model: Model,+world: E.World,+at: P.Position) -> Placement:
  match feature model:
    case Simple{provider,states,water,current} Model{noises,_}: simple_sample(B.optional(provider,state,at,world,noises),states,water,current,model,world,at)
    case Column{layers,direction,allowed,tip} _: column_heights(heights(layers,state,[],0),direction,allowed,tip,model,world,at)
    case Disk{provider,target,radius,half} _: disk_radius(I.sample(radius,state),provider,target,half,model,world,at)
