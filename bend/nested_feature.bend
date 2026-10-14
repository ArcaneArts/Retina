import Base
import ./vegetation_patch.bend as Patch
import ./provider_noise.bend as N
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_checks.bend as Check
import ./placement_height.bend as H
import ./placement_random.bend as R
import ./int_provider.bend as I
import ./decoration_counts.bend as Count
import ./material_columns_data.bend as MD
import ./surface_plants.bend as S
import ./density.bend as Q
import ./word64.bend as W

type Placed<-A: Data> is Data:
  Placed{program: Q.Table<P.Modifier>,feature: A}
type Feature<-A: Data> is Data:
  Leaf{feature: A}
  Composite{sequence: Bool,children: List<&2,Placed<Feature<A>>>}
  Random{options: List<&2,Option<A>>,fallback: Placed<Feature<A>>}
  Simple{children: List<&2,Placed<Feature<A>>>}
  Boolean{yes: Placed<Feature<A>>,no: Placed<Feature<A>>}
  Weighted{entries: List<&2,Entry<A>>,total: W.U64}
  Patch{recipe: Patch.Recipe<Placed<Feature<A>>>}
type Option<-A: Data> is Data:
  Option{chance: F32,placed: Placed<Feature<A>>}
type Entry<-A: Data> is Data:
  Entry{weight: U32,placed: Placed<Feature<A>>}

@unsafe
def weight_sum(-A: Data,+xs: List<&2,Entry<A>>,+total: W.U64) -> W.U64:
  match xs:
    case Nil{}: total
    case Entry{w,_} <> rest: weight_sum(A,rest,W.add(total,W.U64{0,w}))

@unsafe
def modifier_valid(+m: P.Modifier,+palette: U32) -> Bool:
  match m:
    case P.Count{Count.Provider{+p}}: Bool.and(I.valid(p),I.within(p,0,4096))
    case P.Count{_}: False{}
    case P.Biome{}: False{}
    case P.Select{_,_}: False{}
    case _: P.modifier_valid(m,palette)

@unsafe
def program_valid(+xs: Q.Table<P.Modifier>,+palette: U32) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{m}: modifier_valid(m,palette)
    case Q.Branch{_,a,b}: Bool.and(program_valid(a,palette),program_valid(b,palette))

@unsafe
def placed_valid(-A: Data,+row: Placed<Feature<A>>,+palette: U32,+noises: U32,+leaf: A -> U32 -> U32 -> Bool) -> Bool:
  match row:
    case Placed{program,feature}: Bool.and(program_valid(program,palette),valid(A,feature,palette,noises,leaf))

@unsafe
def children_valid(-A: Data,+xs: List<&2,Placed<Feature<A>>>,+palette: U32,+noises: U32,+leaf: A -> U32 -> U32 -> Bool) -> Bool:
  match xs:
    case Nil{}: True{}
    case row <> rest: Bool.and(placed_valid(A,row,palette,noises,leaf),children_valid(A,rest,palette,noises,leaf))

@unsafe
def options_valid(-A: Data,+xs: List<&2,Option<A>>,+palette: U32,+noises: U32,+leaf: A -> U32 -> U32 -> Bool) -> Bool:
  match xs:
    case Nil{}: True{}
    case Option{p,row} <> rest: Bool.and(Patch.probability(p),Bool.and(placed_valid(A,row,palette,noises,leaf),options_valid(A,rest,palette,noises,leaf)))

@unsafe
def entries_valid(-A: Data,+xs: List<&2,Entry<A>>,+palette: U32,+noises: U32,+leaf: A -> U32 -> U32 -> Bool) -> Bool:
  match xs:
    case Nil{}: True{}
    case Entry{_,row} <> rest: Bool.and(placed_valid(A,row,palette,noises,leaf),entries_valid(A,rest,palette,noises,leaf))

@unsafe
def nonempty(-A: Data,+xs: List<&2,A>) -> Bool:
  match xs:
    case Nil{}: False{}
    case _ <> _: True{}

@unsafe
def valid(-A: Data,+feature: Feature<A>,+palette: U32,+noises: U32,+leaf: A -> U32 -> U32 -> Bool) -> Bool:
  match feature:
    case Leaf{value}: leaf(value,palette,noises)
    case Composite{_,xs}: Bool.and(nonempty(Placed<Feature<A>>,xs),children_valid(A,xs,palette,noises,leaf))
    case Simple{xs}: Bool.and(nonempty(Placed<Feature<A>>,xs),children_valid(A,xs,palette,noises,leaf))
    case Random{options,fallback}: Bool.and(options_valid(A,options,palette,noises,leaf),placed_valid(A,fallback,palette,noises,leaf))
    case Boolean{yes,no}: Bool.and(placed_valid(A,yes,palette,noises,leaf),placed_valid(A,no,palette,noises,leaf))
    case Weighted{entries,total}: Bool.and(Bool.not(W.equal(total,W.U64{0,0})),Bool.and(W.equal(weight_sum(A,entries,W.U64{0,0}),total),entries_valid(A,entries,palette,noises,leaf)))
    case Patch{recipe}: Patch.valid(Placed<Feature<A>>,recipe,palette,noises,row => placed_valid(A,row,palette,noises,leaf))

@unsafe
def failure(+state: W.U64,+world: E.World) -> S.Placement:
  S.Placement{state,world,False{}}

@unsafe
def place(-A: Data,-C: Data,+feature: Feature<A>,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,
    +runtime: Runtime<A,C>) -> S.Placement:
  match feature:
    case Leaf{value}: evaluate(A,C,runtime,value,state,world,at)
    case Composite{sequence,children}: composite(A,C,children,sequence,False{},state,noises,world,at,runtime)
    case Random{options,fallback}: random(A,C,options,fallback,state,noises,world,at,runtime)
    case Simple{children}: simple_draw(A,C,selection(state,U32.from_nat(List.length(&2,Placed<Feature<A>>,children))),children,noises,world,at,runtime)
    case Boolean{yes,no}: boolean_draw(A,C,R.next(state),yes,no,noises,world,at,runtime)
    case Weighted{entries,total}: weighted_draw(A,C,R.next(state),entries,total,noises,world,at,runtime)
    case Patch{recipe}: Patch.place(Placed<Feature<A>>,PatchRuntime<A,C>,recipe,state,noises,world,at,PatchRuntime{runtime,noises},patch_generate(A,C))

@unsafe
def composite(-A: Data,-C: Data,+children: List<&2,Placed<Feature<A>>>,+sequence: Bool,+any: Bool,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match children:
    case Nil{}: S.Placement{state,world,Bool.or(sequence,any)}
    case row <> rest: composite_child(A,C,placed(A,C,row,state,noises,world,at,runtime),rest,sequence,any,noises,at,runtime)

@unsafe
def composite_child(-A: Data,-C: Data,+result: S.Placement,+rest: List<&2,Placed<Feature<A>>>,+sequence: Bool,+any: Bool,+noises: Q.Table<N.Program>,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match result sequence:
    case S.Placement{state,world,False{}} True{}: failure(state,world)
    case S.Placement{state,world,False{}} False{}: composite(A,C,rest,False{},any,state,noises,world,at,runtime)
    case S.Placement{state,world,True{}} _: composite(A,C,rest,sequence,True{},state,noises,world,at,runtime)

@unsafe
def random(-A: Data,-C: Data,+options: List<&2,Option<A>>,+fallback: Placed<Feature<A>>,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match options:
    case Nil{}: placed(A,C,fallback,state,noises,world,at,runtime)
    case Option{p,row} <> rest: random_draw(A,C,R.next(state),p,row,rest,fallback,noises,world,at,runtime)

@unsafe
def random_draw(-A: Data,-C: Data,+draw: R.Draw,+p: F32,+row: Placed<Feature<A>>,+rest: List<&2,Option<A>>,+fallback: Placed<Feature<A>>,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: random_chosen(A,C,F32.is_lt(R.unit(value),p),row,rest,fallback,state,noises,world,at,runtime)

@unsafe
def random_chosen(-A: Data,-C: Data,+chosen: Bool,+row: Placed<Feature<A>>,+rest: List<&2,Option<A>>,+fallback: Placed<Feature<A>>,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match chosen:
    case True{}: placed(A,C,row,state,noises,world,at,runtime)
    case False{}: random(A,C,rest,fallback,state,noises,world,at,runtime)

@unsafe
def selection(+state: W.U64,+n: U32) -> R.Draw:
  selection_many(U32.is_gt(n,1),state,n)

def selection_many(+many: Bool,+state: W.U64,+n: U32) -> R.Draw:
  match many:
    case False{}: R.Draw{state,W.U64{0,0}}
    case True{}: R.integer(state,0,U32.sub(n,1))

@unsafe
def child(-A: Data,+xs: List<&2,Placed<Feature<A>>>,+i: U32) -> Maybe<&2,Placed<Feature<A>>>:
  match xs i:
    case Nil{} _: None{}
    case row <> _ 0: Some{row}
    case _ <> rest _: child(A,rest,U32.sub(i,1))

@unsafe
def simple_draw(-A: Data,-C: Data,+draw: R.Draw,+xs: List<&2,Placed<Feature<A>>>,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: selected_child(A,C,child(A,xs,R.low(value)),state,noises,world,at,runtime)

@unsafe
def selected_child(-A: Data,-C: Data,+row: Maybe<&2,Placed<Feature<A>>>,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match row:
    case None{}: failure(state,world)
    case Some{row}: placed(A,C,row,state,noises,world,at,runtime)

@unsafe
def boolean_draw(-A: Data,-C: Data,+draw: R.Draw,+yes: Placed<Feature<A>>,+no: Placed<Feature<A>>,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: boolean_chosen(A,C,F32.is_lt(R.unit(value),0.5),yes,no,state,noises,world,at,runtime)

@unsafe
def boolean_chosen(-A: Data,-C: Data,+yes: Bool,+a: Placed<Feature<A>>,+b: Placed<Feature<A>>,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match yes:
    case True{}: placed(A,C,a,state,noises,world,at,runtime)
    case False{}: placed(A,C,b,state,noises,world,at,runtime)

@unsafe
def weighted_draw(-A: Data,-C: Data,+draw: R.Draw,+entries: List<&2,Entry<A>>,+total: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: weighted(A,C,entries,R.remainder(value,total),state,noises,world,at,runtime)

@unsafe
def weighted(-A: Data,-C: Data,+entries: List<&2,Entry<A>>,+n: W.U64,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match entries:
    case Nil{}: failure(state,world)
    case Entry{w,row} <> rest: weighted_chosen(A,C,R.less(n,W.U64{0,w}),w,row,rest,n,state,noises,world,at,runtime)

@unsafe
def weighted_chosen(-A: Data,-C: Data,+chosen: Bool,+w: U32,+row: Placed<Feature<A>>,+rest: List<&2,Entry<A>>,+n: W.U64,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match chosen:
    case True{}: placed(A,C,row,state,noises,world,at,runtime)
    case False{}: weighted(A,C,rest,W.sub(n,W.U64{0,w}),state,noises,world,at,runtime)

@unsafe
def placed(-A: Data,-C: Data,+row: Placed<Feature<A>>,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  run(A,C,row,0,state,noises,world,at,runtime)

@unsafe
def run(-A: Data,-C: Data,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match row:
    case Placed{program,_}: run_more(A,C,U32.is_lt(index,Q.table_size(P.Modifier,program)),row,index,state,noises,world,at,runtime)

@unsafe
def run_more(-A: Data,-C: Data,+more: Bool,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match more row:
    case False{} Placed{_,feature}: place(A,C,feature,state,noises,world,at,runtime)
    case True{} Placed{program,_}: op(A,C,Q.get(P.Modifier,program,index,P.Biome{}),row,U32.inc(index),state,noises,world,at,runtime)

@unsafe
def jump(-A: Data,-C: Data,+point: Maybe<&2,P.Position>,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+runtime: Runtime<A,C>) -> S.Placement:
  match point:
    case None{}: failure(state,world)
    case Some{at}: run(A,C,row,index,state,noises,world,at,runtime)

@unsafe
def chance_draw(-A: Data,-C: Data,+draw: R.Draw,+chance: F32,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: chance_kept(A,C,F32.is_lt(R.unit(value),chance),row,index,state,noises,world,at,runtime)

@unsafe
def chance_kept(-A: Data,-C: Data,+ok: Bool,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match ok:
    case False{}: failure(state,world)
    case True{}: run(A,C,row,index,state,noises,world,at,runtime)

@unsafe
def offset_x(-A: Data,-C: Data,+draw: R.Draw,+y: I.Provider,+z: I.Provider,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: offset_y(A,C,I.sample(y,state),R.low(value),z,row,index,noises,world,at,runtime)

@unsafe
def offset_y(-A: Data,-C: Data,+draw: R.Draw,+x: U32,+z: I.Provider,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: offset_z(A,C,I.sample(z,state),x,R.low(value),row,index,noises,world,at,runtime)

@unsafe
def offset_z(-A: Data,-C: Data,+draw: R.Draw,+x: U32,+y: U32,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: jump(A,C,P.shift(at,P.Position{x,y,R.low(value)}),row,index,state,noises,world,runtime)

@unsafe
def square_x(-A: Data,-C: Data,+draw: R.Draw,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: square_z(A,C,R.integer(state,0,15),R.low(value),row,index,noises,world,at,runtime)

@unsafe
def square_z(-A: Data,-C: Data,+draw: R.Draw,+x: U32,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: jump(A,C,P.shift(at,P.Position{x,0,R.low(value)}),row,index,state,noises,world,runtime)

@unsafe
def height_draw(-A: Data,-C: Data,+draw: R.Draw,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: run(A,C,row,index,state,noises,world,with_y(at,R.low(value)),runtime)

def with_y(+at: P.Position,+y: U32) -> P.Position:
  match at:
    case P.Position{x,_,z}: P.Position{x,y,z}

@unsafe
def mapped_height(+value: Maybe<&2,U32>,+minimum: U32,+at: P.Position) -> Maybe<&2,P.Position>:
  match value:
    case None{}: None{}
    case Some{y}: Check.retain(H.signed_less(minimum,y),with_y(at,y))

@unsafe
def op(-A: Data,-C: Data,+m: P.Modifier,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match m world at:
    case P.Count{Count.Provider{p}} _ _: count_draw(A,C,I.sample(p,state),row,index,noises,world,at,runtime)
    case P.Layer{p} _ _: layer(A,C,p,0,0,False{},False{},row,index,state,noises,world,at,runtime)
    case P.Cuboid{xz,y,edges,inside} _ _: cube_height(A,C,I.sample(y,state),xz,edges,inside,row,index,noises,world,at,runtime)
    case P.Chance{p} _ _: chance_draw(A,C,R.next(state),p,row,index,noises,world,at,runtime)
    case P.Rarity{n} _ _: chance_draw(A,C,R.next(state),F32.div(1.0,U32.to_f32(n)),row,index,noises,world,at,runtime)
    case P.Square{} _ _: square_x(A,C,R.integer(state,0,15),row,index,noises,world,at,runtime)
    case P.Offset{x,y,z} _ _: offset_x(A,C,I.sample(x,state),y,z,row,index,noises,world,at,runtime)
    case P.HeightRange{p} E.World{MD.Grid{_,minimum,height,_,_},_,_,_} _: height_draw(A,C,H.sample(p,state,minimum,height),row,index,noises,world,at,runtime)
    case P.Heightmap{map} E.World{MD.Grid{_,minimum,_,_,_},_,_,_} P.Position{x,_,z}: jump(A,C,mapped_height(E.height(world,x,z,map),minimum,at),row,index,state,noises,world,runtime)
    case _ _ _: jump(A,C,Check.evaluate(m,world,at,0),row,index,state,noises,world,runtime)

@unsafe
def count_draw(-A: Data,-C: Data,+draw: R.Draw,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: repeated(A,C,Count.nonnegative(R.low(value)),False{},row,index,state,noises,world,at,runtime)

@unsafe
def repeated(-A: Data,-C: Data,+n: U32,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match n:
    case 0: S.Placement{state,world,any}
    case _: repeated_child(A,C,run(A,C,row,index,state,noises,world,at,runtime),U32.sub(n,1),any,row,index,noises,at,runtime)

@unsafe
def repeated_child(-A: Data,-C: Data,+result: S.Placement,+n: U32,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match result:
    case S.Placement{state,world,True{}}: repeated(A,C,n,True{},row,index,state,noises,world,at,runtime)
    case S.Placement{state,world,False{}}: repeated(A,C,n,any,row,index,state,noises,world,at,runtime)

@unsafe
def boundary(+value: U32,+maximum: U32) -> U32:
  W.flag(Bool.or(U32.is_zero(value),U32.is_eq(value,maximum)))

@unsafe
def emit(+planes: U32,+edges: Bool,+inside: Bool) -> Bool:
  match planes:
    case 0: inside
    case 1: True{}
    case _: edges

@unsafe
def cube_height(-A: Data,-C: Data,+draw: R.Draw,+xz: I.Provider,+edges: Bool,+inside: Bool,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: cube_width(A,C,I.sample(xz,state),R.low(value),xz,edges,inside,row,index,noises,world,at,runtime)

@unsafe
def cube_width(-A: Data,-C: Data,+draw: R.Draw,+height: U32,+xz: I.Provider,+edges: Bool,+inside: Bool,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: cube_length(A,C,I.sample(xz,state),R.low(value),height,edges,inside,row,index,noises,world,at,runtime)

@unsafe
def cube_length(-A: Data,-C: Data,+draw: R.Draw,+w: U32,+h: U32,+edges: Bool,+inside: Bool,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: cube(A,C,0,w,h,R.low(value),edges,inside,False{},row,index,state,noises,world,at,runtime)

@unsafe
def cube(-A: Data,-C: Data,+cell: U32,+w: U32,+h: U32,+l: U32,+edges: Bool,+inside: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  cube_more(A,C,U32.is_lt(cell,U32.mul(U32.inc(w),U32.mul(U32.inc(h),U32.inc(l)))),cell,w,h,l,edges,inside,any,row,index,state,noises,world,at,runtime)

@unsafe
def cube_more(-A: Data,-C: Data,+more: Bool,+cell: U32,+w: U32,+h: U32,+l: U32,+edges: Bool,+inside: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match more:
    case False{}: S.Placement{state,world,any}
    case True{}:
      +x = U32.div(cell,U32.mul(U32.inc(h),U32.inc(l)))
      +y = U32.mod(U32.div(cell,U32.inc(l)),U32.inc(h))
      +z = U32.mod(cell,U32.inc(l))
      cube_emitted(A,C,emit(U32.add(boundary(x,w),U32.add(boundary(y,h),boundary(z,l))),edges,inside),P.Position{x,y,z},cell,w,h,l,edges,inside,any,row,index,state,noises,world,at,runtime)

@unsafe
def cube_emitted(-A: Data,-C: Data,+ok: Bool,+offset: P.Position,+cell: U32,+w: U32,+h: U32,+l: U32,+edges: Bool,+inside: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match ok:
    case False{}: cube(A,C,U32.inc(cell),w,h,l,edges,inside,any,row,index,state,noises,world,at,runtime)
    case True{}: cube_child(A,C,jump(A,C,P.shift(at,offset),row,index,state,noises,world,runtime),cell,w,h,l,edges,inside,any,row,index,noises,at,runtime)

@unsafe
def cube_child(-A: Data,-C: Data,+result: S.Placement,+cell: U32,+w: U32,+h: U32,+l: U32,+edges: Bool,+inside: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match result:
    case S.Placement{state,world,True{}}: cube(A,C,U32.inc(cell),w,h,l,edges,inside,True{},row,index,state,noises,world,at,runtime)
    case S.Placement{state,world,False{}}: cube(A,C,U32.inc(cell),w,h,l,edges,inside,any,row,index,state,noises,world,at,runtime)

@unsafe
def layer(-A: Data,-C: Data,+p: I.Provider,+level: U32,+attempt: U32,+found: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  layer_count(A,C,I.sample(p,state),p,level,attempt,found,any,row,index,noises,world,at,runtime)

@unsafe
def layer_count(-A: Data,-C: Data,+draw: R.Draw,+p: I.Provider,+level: U32,+attempt: U32,+found: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: layer_more(A,C,U32.is_lt(attempt,Count.nonnegative(R.low(value))),p,level,attempt,found,any,row,index,state,noises,world,at,runtime)

@unsafe
def layer_more(-A: Data,-C: Data,+more: Bool,+p: I.Provider,+level: U32,+attempt: U32,+found: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match more found:
    case False{} False{}: S.Placement{state,world,any}
    case False{} True{}: layer(A,C,p,U32.inc(level),0,False{},any,row,index,state,noises,world,at,runtime)
    case True{} _: layer_x(A,C,R.integer(state,0,15),p,level,attempt,found,any,row,index,noises,world,at,runtime)

@unsafe
def layer_x(-A: Data,-C: Data,+draw: R.Draw,+p: I.Provider,+level: U32,+attempt: U32,+found: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: layer_z(A,C,R.integer(state,0,15),R.low(value),p,level,attempt,found,any,row,index,noises,world,at,runtime)

@unsafe
def layer_z(-A: Data,-C: Data,+draw: R.Draw,+x: U32,+p: I.Provider,+level: U32,+attempt: U32,+found: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match draw:
    case R.Draw{state,value}: layer_position(A,C,P.shift(at,P.Position{x,0,R.low(value)}),p,level,attempt,found,any,row,index,state,noises,world,at,runtime)

@unsafe
def layer_position(-A: Data,-C: Data,+point: Maybe<&2,P.Position>,+p: I.Provider,+level: U32,+attempt: U32,+found: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match point:
    case None{}: layer(A,C,p,level,U32.inc(attempt),found,any,row,index,state,noises,world,at,runtime)
    case Some{P.Position{x,_,z}}: layer_ground(A,C,Check.set_y(E.ground(world,x,z,level),P.Position{x,0,z}),p,level,attempt,found,any,row,index,state,noises,world,at,runtime)

@unsafe
def layer_ground(-A: Data,-C: Data,+point: Maybe<&2,P.Position>,+p: I.Provider,+level: U32,+attempt: U32,+found: Bool,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match point:
    case None{}: layer(A,C,p,level,U32.inc(attempt),found,any,row,index,state,noises,world,at,runtime)
    case Some{pos}: layer_child(A,C,run(A,C,row,index,state,noises,world,pos,runtime),p,level,attempt,any,row,index,noises,at,runtime)

@unsafe
def layer_child(-A: Data,-C: Data,+result: S.Placement,+p: I.Provider,+level: U32,+attempt: U32,+any: Bool,+row: Placed<Feature<A>>,+index: U32,+noises: Q.Table<N.Program>,+at: P.Position,+runtime: Runtime<A,C>) -> S.Placement:
  match result:
    case S.Placement{state,world,True{}}: layer(A,C,p,level,U32.inc(attempt),True{},True{},row,index,state,noises,world,at,runtime)
    case S.Placement{state,world,False{}}: layer(A,C,p,level,U32.inc(attempt),True{},any,row,index,state,noises,world,at,runtime)


# Function references here are noncapturing. Captured model data lives in the
# context field, so sharing this record never reference-counts a closure.
type Runtime<-A: Data,-C: Data> is Type:
  Runtime{context: C,leaf: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement}
type PatchRuntime<-A: Data,-C: Data> is Type:
  PatchRuntime{runtime: Runtime<A,C>,noises: Q.Table<N.Program>}

@unsafe
def evaluate(-A: Data,-C: Data,runtime: Runtime<A,C>,value: A,state: W.U64,world: E.World,at: P.Position) -> S.Placement:
  match runtime:
    case Runtime{context,leaf}: leaf(context,value,state,world,at)

@unsafe
def patch_generate(-A: Data,-C: Data,context: PatchRuntime<A,C>,row: Placed<Feature<A>>,state: W.U64,world: E.World,at: P.Position) -> S.Placement:
  match context:
    case PatchRuntime{runtime,noises}: placed(A,C,row,state,noises,world,at,runtime)
