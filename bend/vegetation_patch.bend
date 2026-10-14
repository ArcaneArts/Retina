import Base
import ./block_provider.bend as B
import ./provider_noise.bend as N
import ./int_provider.bend as I
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_random.bend as R
import ./placement_set_order.bend as Order
import ./surface_plants.bend as S
import ./density.bend as Q
import ./word64.bend as W

type Same is Data:
  Same{source: U32,predicate: P.Predicate}
type Wet is Data:
  Wet{source: U32,target: U32}
type Settings is Data:
  Settings{direction: U32,depth: I.Provider,bottom: F32,range: U32,chance: F32,radius: I.Provider,edge: F32,pool: Bool}
type Recipe<-A: Data> is Data:
  Recipe{ground: B.Provider,same: List<&2,Same>,replaceable: P.Predicate,air: P.Predicate,sturdy: P.Predicate,settings: Settings,
    vegetation: A,enclosed: List<&2,P.Predicate>,waterlogged: List<&2,Wet>}
type Work is Data:
  Work{state: W.U64,world: E.World,surface: List<&2,P.Position>}
type Filled is Data:
  Filled{work: Work,placed: Bool}

@unsafe
def probability(+p: F32) -> Bool:
  Bool.and(I.finite(p),Bool.and(F32.is_ge(p,0.0),F32.is_le(p,1.0)))

@unsafe
def same_valid(+xs: List<&2,Same>,+palette: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case Same{id,p} <> rest: Bool.and(U32.is_lt(id,palette),Bool.and(P.predicate_valid(p,palette),same_valid(rest,palette)))

@unsafe
def find(+xs: List<&2,Same>,+id: U32) -> Maybe<&2,P.Predicate>:
  match xs:
    case Nil{}: None{}
    case Same{source,p} <> rest: same_equal(U32.is_eq(id,source),p,rest,id)

@unsafe
def same_equal(+equal: Bool,+p: P.Predicate,+rest: List<&2,Same>,+id: U32) -> Maybe<&2,P.Predicate>:
  match equal:
    case True{}: Some{p}
    case False{}: find(rest,id)

@unsafe
def covered(+ids: List<&2,U32>,+rows: List<&2,Same>) -> Bool:
  match ids:
    case Nil{}: True{}
    case id <> rest: Bool.and(known(find(rows,id)),covered(rest,rows))

def known(+p: Maybe<&2,P.Predicate>) -> Bool:
  match p:
    case None{}: False{}
    case Some{_}: True{}

@unsafe
def wet_valid(+xs: List<&2,Wet>,+palette: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case Wet{a,b} <> rest: Bool.and(U32.is_lt(a,palette),Bool.and(U32.is_lt(b,palette),wet_valid(rest,palette)))

@unsafe
def settings_valid(+settings: Settings) -> Bool:
  match settings:
    case Settings{+d,+depth,bottom,+range,chance,+radius,edge,_}:
      Bool.and(Bool.or(U32.is_eq(d,1),U32.is_eq(d,4294967295)),Bool.and(I.valid(depth),Bool.and(I.within(depth,1,128),
        Bool.and(I.valid(radius),Bool.and(I.within(radius,0,14),Bool.and(U32.is_ge(range,1),Bool.and(U32.is_le(range,256),
          Bool.and(probability(bottom),Bool.and(probability(chance),probability(edge))))))))))

@unsafe
def valid(-A: Data,+recipe: Recipe<A>,+palette: U32,+noises: U32,+leaf: A -> Bool) -> Bool:
  match recipe:
    case Recipe{+ground,+same,replace,air,sturdy,+settings,vegetation,+enclosed,wet}:
      Bool.and(B.valid(ground,palette,noises),Bool.and(same_valid(same,palette),Bool.and(covered(B.outputs(ground),same),
        Bool.and(P.predicate_valid(replace,palette),Bool.and(P.predicate_valid(air,palette),Bool.and(P.predicate_valid(sturdy,palette),
          Bool.and(settings_valid(settings),Bool.and(leaf(vegetation),Bool.and(enclosed_valid(settings,enclosed),
            Bool.and(P.predicates_valid(enclosed,palette),wet_valid(wet,palette)))))))))))

@unsafe
def enclosed_valid(+settings: Settings,+enclosed: List<&2,P.Predicate>) -> Bool:
  match settings:
    case Settings{_,_,_,_,_,_,_,pool}: Bool.or(Bool.not(pool),U32.is_eq(U32.from_nat(List.length(&2,P.Predicate,enclosed)),5))

@unsafe
def test(+p: P.Predicate,+point: Maybe<&2,P.Position>,+world: E.World) -> Bool:
  match point:
    case None{}: False{}
    case Some{at}: S.test(p,world,at)

@unsafe
def write(+at: P.Position,+material: U32,+work: Work) -> Work:
  match work:
    case Work{state,world,surface}: Work{state,E.put(world,at,material),surface}

@unsafe
def place(-A: Data,-C: Type,+recipe: Recipe<A>,+state: W.U64,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,
    +context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match recipe:
    case Recipe{_,_,_,_,_,Settings{_,_,_,_,_,radius,_,_},_,_,_}: radius_x(A,C,I.sample(radius,state),recipe,noises,world,at,context,generate)

@unsafe
def radius_x(-A: Data,-C: Type,+draw: R.Draw,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match draw recipe:
    case R.Draw{state,value} Recipe{_,_,_,_,_,Settings{_,_,_,_,_,radius,_,_},_,_,_}: radius_z(A,C,I.sample(radius,state),U32.inc(R.low(value)),recipe,noises,world,at,context,generate)

@unsafe
def radius_z(-A: Data,-C: Type,+draw: R.Draw,+rx: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+world: E.World,+at: P.Position,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match draw:
    case R.Draw{state,value}: finish_ground(A,C,columns(A,0,rx,U32.inc(R.low(value)),recipe,noises,at,Work{state,world,[]}),recipe,context,generate)

@unsafe
def columns(-A: Data,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  columns_more(A,U32.is_lt(i,U32.mul(U32.inc(U32.mul(rx,2)),U32.inc(U32.mul(rz,2)))),i,rx,rz,recipe,noises,at,work)

@unsafe
def columns_more(-A: Data,+more: Bool,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  match more recipe:
    case False{} _: work
    case True{} Recipe{_,_,_,_,_,Settings{_,_,_,_,_,_,edge,_},_,_,_}:
      +x = U32.div(i,U32.inc(U32.mul(rz,2)))
      +z = U32.mod(i,U32.inc(U32.mul(rz,2)))
      +ex = Bool.or(U32.is_zero(x),U32.is_eq(x,U32.mul(rx,2)))
      +ez = Bool.or(U32.is_zero(z),U32.is_eq(z,U32.mul(rz,2)))
      edge_column(A,Bool.and(ex,ez),Bool.or(ex,ez),edge,P.Position{U32.sub(x,rx),0,U32.sub(z,rz)},i,rx,rz,recipe,noises,at,work)

@unsafe
def edge_column(-A: Data,+corner: Bool,+edge: Bool,+chance: F32,+offset: P.Position,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  match corner edge work:
    case True{} _ _: columns(A,U32.inc(i),rx,rz,recipe,noises,at,work)
    case False{} False{} _: probe_column(A,P.shift(at,offset),i,rx,rz,recipe,noises,at,work)
    case False{} True{} Work{state,world,surface}: edge_enabled(A,F32.is_gt(chance,0.0),chance,offset,i,rx,rz,recipe,noises,at,Work{state,world,surface})

@unsafe
def edge_enabled(-A: Data,+enabled: Bool,+chance: F32,+offset: P.Position,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  match enabled work:
    case False{} _: columns(A,U32.inc(i),rx,rz,recipe,noises,at,work)
    case True{} Work{state,world,surface}: edge_draw(A,R.next(state),chance,offset,i,rx,rz,recipe,noises,at,world,surface)

@unsafe
def edge_draw(-A: Data,+draw: R.Draw,+chance: F32,+offset: P.Position,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+world: E.World,+surface: List<&2,P.Position>) -> Work:
  match draw:
    case R.Draw{state,value}: edge_kept(A,F32.is_le(R.unit(value),chance),offset,i,rx,rz,recipe,noises,at,Work{state,world,surface})

@unsafe
def edge_kept(-A: Data,+ok: Bool,+offset: P.Position,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  match ok:
    case False{}: columns(A,U32.inc(i),rx,rz,recipe,noises,at,work)
    case True{}: probe_column(A,P.shift(at,offset),i,rx,rz,recipe,noises,at,work)

@unsafe
def scan(+point: Maybe<&2,P.Position>,+remaining: U32,+direction: U32,+air: P.Predicate,+world: E.World,+want_air: Bool) -> Maybe<&2,P.Position>:
  match point:
    case None{}: None{}
    case Some{+at}: scan_more(Bool.and(U32.is_gt(remaining,0),Bool.not(U32.is_eq(W.flag(S.test(air,world,at)),W.flag(want_air)))),at,remaining,direction,air,world,want_air)

@unsafe
def scan_more(+more: Bool,+at: P.Position,+remaining: U32,+direction: U32,+air: P.Predicate,+world: E.World,+want_air: Bool) -> Maybe<&2,P.Position>:
  match more:
    case False{}: Some{at}
    case True{}: scan(P.shift(at,P.Position{0,direction,0}),U32.sub(remaining,1),direction,air,world,want_air)

@unsafe
def probe_column(-A: Data,+point: Maybe<&2,P.Position>,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  match recipe work:
    case Recipe{_,_,_,air,_,Settings{+d,_,_,range,_,_,_,_},_,_,_} Work{_,world,_}:
      scanned(A,scan(point,range,d,air,world,False{}),i,rx,rz,recipe,noises,at,work)

@unsafe
def scanned(-A: Data,+point: Maybe<&2,P.Position>,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  match recipe work:
    case Recipe{_,_,_,air,_,Settings{d,_,_,range,_,_,_,_},_,_,_} Work{_,world,_}:
      surface_point(A,scan(point,range,U32.sub(0,d),air,world,True{}),i,rx,rz,recipe,noises,at,work)

@unsafe
def surface_point(-A: Data,+point: Maybe<&2,P.Position>,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  match point recipe work:
    case None{} _ _: columns(A,U32.inc(i),rx,rz,recipe,noises,at,work)
    case Some{+p} Recipe{_,_,_,air,+sturdy,Settings{d,_,_,_,_,_,_,_},_,_,_} Work{_,world,_}:
      ground_point(A,P.shift(p,P.Position{0,d,0}),S.test(air,world,p),i,rx,rz,recipe,noises,at,work)

@unsafe
def ground_point(-A: Data,+point: Maybe<&2,P.Position>,+air: Bool,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  match recipe work:
    case Recipe{_,_,_,_,sturdy,_,_,_,_} Work{_,world,_}: supported(A,Bool.and(air,test(sturdy,point,world)),point,i,rx,rz,recipe,noises,at,work)

@unsafe
def supported(-A: Data,+ok: Bool,+point: Maybe<&2,P.Position>,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  match ok point recipe work:
    case True{} Some{p} Recipe{_,_,_,_,_,Settings{_,depth,_,_,_,_,_,_},_,_,_} Work{state,world,surface}:
      depth_draw(A,I.sample(depth,state),p,i,rx,rz,recipe,noises,at,world,surface)
    case _ _ _ _: columns(A,U32.inc(i),rx,rz,recipe,noises,at,work)

@unsafe
def depth_draw(-A: Data,+draw: R.Draw,+ground: P.Position,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+world: E.World,+surface: List<&2,P.Position>) -> Work:
  match draw recipe:
    case R.Draw{state,value} Recipe{_,_,_,_,_,Settings{_,_,chance,_,_,_,_,_},_,_,_}:
      bottom_enabled(A,F32.is_gt(chance,0.0),chance,R.low(value),ground,i,rx,rz,recipe,noises,at,Work{state,world,surface})

@unsafe
def bottom_enabled(-A: Data,+enabled: Bool,+chance: F32,+n: U32,+ground: P.Position,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+work: Work) -> Work:
  match enabled work:
    case False{} _: filled(A,fill(A,0,n,ground,recipe,noises,work),ground,i,rx,rz,recipe,noises,at)
    case True{} Work{state,world,surface}: bottom_draw(A,R.next(state),chance,n,ground,i,rx,rz,recipe,noises,at,world,surface)

@unsafe
def bottom_draw(-A: Data,+draw: R.Draw,+chance: F32,+n: U32,+ground: P.Position,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position,+world: E.World,+surface: List<&2,P.Position>) -> Work:
  match draw:
    case R.Draw{state,value}: filled(A,fill(A,0,U32.add(n,W.flag(F32.is_lt(R.unit(value),chance))),ground,recipe,noises,Work{state,world,surface}),ground,i,rx,rz,recipe,noises,at)

@unsafe
def fill(-A: Data,+i: U32,+n: U32,+pos: P.Position,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+work: Work) -> Filled:
  fill_more(A,U32.is_lt(i,n),i,n,pos,recipe,noises,work)

@unsafe
def fill_more(-A: Data,+more: Bool,+i: U32,+n: U32,+pos: P.Position,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+work: Work) -> Filled:
  match more recipe work:
    case False{} _ _: Filled{work,True{}}
    case True{} Recipe{ground,_,_,_,_,_,_,_,_} Work{state,+world,surface}:
      fill_sample(A,B.optional(ground,state,pos,world,noises),i,n,pos,recipe,noises,world,surface)

@unsafe
def same_material(+value: Maybe<&2,U32>,+id: U32) -> Bool:
  match value:
    case None{}: False{}
    case Some{m}: U32.is_eq(m,id)

@unsafe
def fill_sample(-A: Data,+sample: B.Sample,+i: U32,+n: U32,+pos: P.Position,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+world: E.World,+surface: List<&2,P.Position>) -> Filled:
  match sample:
    case B.Sample{state,None{}}: fill(A,U32.inc(i),n,pos,recipe,noises,Work{state,world,surface})
    case B.Sample{state,Some{+m}}: fill_identity(A,same_material(E.material(world,pos),m),m,i,n,pos,recipe,noises,Work{state,world,surface})

@unsafe
def fill_identity(-A: Data,+same: Bool,+m: U32,+i: U32,+n: U32,+pos: P.Position,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+work: Work) -> Filled:
  match same recipe:
    case True{} _: fill(A,U32.inc(i),n,pos,recipe,noises,work)
    case False{} Recipe{_,rows,_,_,_,_,_,_,_}: fill_predicate(A,find(rows,m),m,i,n,pos,recipe,noises,work)

@unsafe
def fill_predicate(-A: Data,+predicate: Maybe<&2,P.Predicate>,+m: U32,+i: U32,+n: U32,+pos: P.Position,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+work: Work) -> Filled:
  match predicate work:
    case None{} _: Filled{work,False{}}
    case Some{p} Work{_,world,_}: fill_same(A,S.test(p,world,pos),m,i,n,pos,recipe,noises,work)

@unsafe
def fill_same(-A: Data,+same: Bool,+m: U32,+i: U32,+n: U32,+pos: P.Position,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+work: Work) -> Filled:
  match same recipe work:
    case True{} _ _: fill(A,U32.inc(i),n,pos,recipe,noises,work)
    case False{} Recipe{_,_,replace,_,_,_,_,_,_} Work{_,world,_}: fill_replace(A,S.test(replace,world,pos),m,i,n,pos,recipe,noises,work)

@unsafe
def fill_replace(-A: Data,+ok: Bool,+m: U32,+i: U32,+n: U32,+pos: P.Position,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+work: Work) -> Filled:
  match ok recipe:
    case False{} _: Filled{work,U32.is_ne(i,0)}
    case True{} Recipe{_,_,_,_,_,Settings{d,_,_,_,_,_,_,_},_,_,_}: fill_next(A,P.shift(pos,P.Position{0,d,0}),U32.inc(i),n,recipe,noises,write(pos,m,work))

@unsafe
def fill_next(-A: Data,+point: Maybe<&2,P.Position>,+i: U32,+n: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+work: Work) -> Filled:
  match point:
    case None{}: Filled{work,True{}}
    case Some{p}: fill(A,i,n,p,recipe,noises,work)

@unsafe
def filled(-A: Data,+result: Filled,+ground: P.Position,+i: U32,+rx: U32,+rz: U32,+recipe: Recipe<A>,+noises: Q.Table<N.Program>,+at: P.Position) -> Work:
  match result:
    case Filled{work,False{}}: columns(A,U32.inc(i),rx,rz,recipe,noises,at,work)
    case Filled{Work{state,world,surface},True{}}: columns(A,U32.inc(i),rx,rz,recipe,noises,at,Work{state,world,ground <> surface})

@unsafe
def all_tests(+xs: List<&2,P.Predicate>,+world: E.World,+at: P.Position) -> Bool:
  match xs:
    case Nil{}: True{}
    case p <> rest: test_rest(S.test(p,world,at),rest,world,at)

@unsafe
def test_rest(+ok: Bool,+rest: List<&2,P.Predicate>,+world: E.World,+at: P.Position) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: all_tests(rest,world,at)

@unsafe
def enclosed(+xs: List<&2,P.Position>,+predicates: List<&2,P.Predicate>,+world: E.World,+acc: List<&2,P.Position>) -> List<&2,P.Position>:
  match xs:
    case Nil{}: List.reverse(&2,P.Position,acc)
    case +p <> rest: enclosed_keep(all_tests(predicates,world,p),p,rest,predicates,world,acc)

@unsafe
def enclosed_keep(+keep: Bool,+p: P.Position,+rest: List<&2,P.Position>,+predicates: List<&2,P.Predicate>,+world: E.World,+acc: List<&2,P.Position>) -> List<&2,P.Position>:
  match keep:
    case False{}: enclosed(rest,predicates,world,acc)
    case True{}: enclosed(rest,predicates,world,p <> acc)

@unsafe
def flood(+xs: List<&2,P.Position>,+work: Work) -> Work:
  match xs work:
    case Nil{} _: work
    case p <> rest Work{_,E.World{_,P.Policy{_,_,_,water,_,_,_},_,_},_}: flood(rest,write(p,water,work))

@unsafe
def finish_ground(-A: Data,-C: Type,+work: Work,+recipe: Recipe<A>,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match work recipe:
    case Work{state,+world,surface} Recipe{_,_,_,_,_,Settings{_,_,_,_,_,_,_,pool},_,predicates,_}:
      pool_ground(A,C,pool,Order.ordered(List.reverse(&2,P.Position,surface)),predicates,state,world,recipe,context,generate)

@unsafe
def pool_ground(-A: Data,-C: Type,+pool: Bool,+surface: List<&2,P.Position>,+predicates: List<&2,P.Predicate>,+state: W.U64,+world: E.World,+recipe: Recipe<A>,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match pool:
    case False{}: decorate(A,C,surface,recipe,Work{state,world,surface},context,generate)
    case True{}: pooled(A,C,Order.ordered(enclosed(surface,predicates,world,[])),recipe,state,world,context,generate)

@unsafe
def pooled(-A: Data,-C: Type,+surface: List<&2,P.Position>,+recipe: Recipe<A>,+state: W.U64,+world: E.World,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  decorate(A,C,surface,recipe,flood(surface,Work{state,world,surface}),context,generate)

@unsafe
def completed(+work: Work) -> S.Placement:
  match work:
    case Work{state,world,Nil{}}: S.Placement{state,world,False{}}
    case Work{state,world,_}: S.Placement{state,world,True{}}

@unsafe
def decorate(-A: Data,-C: Type,+surface: List<&2,P.Position>,+recipe: Recipe<A>,+work: Work,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match surface recipe work:
    case Nil{} _ _: completed(work)
    case ground <> rest Recipe{_,_,_,_,_,Settings{_,_,_,_,chance,_,_,_},_,_,_} Work{state,world,surface}:
      vegetation_enabled(A,C,F32.is_gt(chance,0.0),chance,ground,rest,recipe,Work{state,world,surface},context,generate)

@unsafe
def vegetation_enabled(-A: Data,-C: Type,+enabled: Bool,+chance: F32,+ground: P.Position,+rest: List<&2,P.Position>,+recipe: Recipe<A>,+work: Work,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match enabled work:
    case False{} _: decorate(A,C,rest,recipe,work,context,generate)
    case True{} Work{state,world,surface}: vegetation_draw(A,C,R.next(state),chance,ground,rest,recipe,world,surface,context,generate)

@unsafe
def vegetation_draw(-A: Data,-C: Type,+draw: R.Draw,+chance: F32,+ground: P.Position,+rest: List<&2,P.Position>,+recipe: Recipe<A>,+world: E.World,+surface: List<&2,P.Position>,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match draw:
    case R.Draw{state,value}: vegetation_chance(A,C,F32.is_lt(R.unit(value),chance),ground,rest,recipe,Work{state,world,surface},context,generate)

@unsafe
def vegetation_chance(-A: Data,-C: Type,+ok: Bool,+ground: P.Position,+rest: List<&2,P.Position>,+recipe: Recipe<A>,+work: Work,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match ok recipe:
    case False{} _: decorate(A,C,rest,recipe,work,context,generate)
    case True{} Recipe{_,_,_,_,_,Settings{d,_,_,_,_,_,_,pool},_,_,_}:
      vegetation_point(A,C,P.shift(ground,P.Position{0,U32.mul(U32.sub(0,d),W.flag(Bool.not(pool))),0}),ground,rest,recipe,work,context,generate)

@unsafe
def vegetation_point(-A: Data,-C: Type,+point: Maybe<&2,P.Position>,+ground: P.Position,+rest: List<&2,P.Position>,+recipe: Recipe<A>,+work: Work,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match point recipe work:
    case None{} _ _: decorate(A,C,rest,recipe,work,context,generate)
    case Some{at} Recipe{_,_,_,_,_,_,vegetation,_,_} Work{state,world,surface}:
      vegetation_result(A,C,generate(context,vegetation,state,world,at),ground,rest,recipe,surface,context,generate)

@unsafe
def wet(+xs: List<&2,Wet>,+id: U32) -> Maybe<&2,U32>:
  match xs:
    case Nil{}: None{}
    case Wet{a,b} <> rest: wet_equal(U32.is_eq(a,id),b,rest,id)

@unsafe
def wet_equal(+equal: Bool,+target: U32,+rest: List<&2,Wet>,+id: U32) -> Maybe<&2,U32>:
  match equal:
    case True{}: Some{target}
    case False{}: wet(rest,id)

@unsafe
def vegetation_result(-A: Data,-C: Type,+result: S.Placement,+ground: P.Position,+rest: List<&2,P.Position>,+recipe: Recipe<A>,+surface: List<&2,P.Position>,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match result recipe:
    case S.Placement{state,+world,True{}} Recipe{_,_,_,_,_,Settings{_,_,_,_,_,_,_,True{}},_,_,pairs}:
      waterlog(A,C,E.material(world,ground),pairs,ground,rest,recipe,Work{state,world,surface},context,generate)
    case S.Placement{state,world,_} _: decorate(A,C,rest,recipe,Work{state,world,surface},context,generate)

@unsafe
def waterlog(-A: Data,-C: Type,+value: Maybe<&2,U32>,+pairs: List<&2,Wet>,+ground: P.Position,+rest: List<&2,P.Position>,+recipe: Recipe<A>,+work: Work,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match value:
    case None{}: decorate(A,C,rest,recipe,work,context,generate)
    case Some{id}: waterlogged(A,C,wet(pairs,id),ground,rest,recipe,work,context,generate)

@unsafe
def waterlogged(-A: Data,-C: Type,+value: Maybe<&2,U32>,+ground: P.Position,+rest: List<&2,P.Position>,+recipe: Recipe<A>,+work: Work,+context: C,+generate: C -> A -> W.U64 -> E.World -> P.Position -> S.Placement) -> S.Placement:
  match value:
    case None{}: decorate(A,C,rest,recipe,work,context,generate)
    case Some{id}: decorate(A,C,rest,recipe,write(ground,id,work),context,generate)
