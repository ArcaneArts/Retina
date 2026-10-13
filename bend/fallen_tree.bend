import Base
import ./forest_data.bend as D
import ./block_provider.bend as B
import ./provider_noise.bend as N
import ./int_provider.bend as I
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_random.bend as R
import ./surface_plants.bend as S
import ./density.bend as Q
import ./word64.bend as W

type Context is Data:
  Context{recipe: D.Fallen,noises: Q.Table<N.Program>,world: E.World}
type Work is Data:
  Work{state: W.U64,context: Context}
type Sampled is Data:
  Sampled{work: Work,material: U32}
type Shuffled is Data:
  Shuffled{state: W.U64,positions: List<&2,P.Position>}

def direction(i: U32) -> P.Position:
  match i:
    case 0: P.Position{0,0,4294967295}
    case 1: P.Position{1,0,0}
    case 2: P.Position{0,0,1}
    case _: P.Position{4294967295,0,0}

@unsafe
def shift(at: P.Position,direction: P.Position,+n: U32) -> Maybe<&2,P.Position>:
  match direction:
    case P.Position{x,y,z}: P.shift(at,P.Position{U32.mul(x,n),U32.mul(y,n),U32.mul(z,n)})

@unsafe
def test(predicate: P.Predicate,point: Maybe<&2,P.Position>,context: Context) -> Bool:
  match point context:
    case None{} _: False{}
    case Some{at} Context{_,_,world}: S.test(predicate,world,at)

@unsafe
def put(point: P.Position,material: U32,work: Work) -> Work:
  match work:
    case Work{state,Context{recipe,noises,world}}: Work{state,Context{recipe,noises,E.put(world,point,material)}}

@unsafe
def sample(provider: B.Provider,+at: P.Position,work: Work) -> Sampled:
  match work:
    case Work{state,Context{+recipe,+noises,+world}}: sampled(B.sample(provider,state,at,world,noises),Context{recipe,noises,world})

def sampled(result: B.Sample,context: Context) -> Sampled:
  match result:
    case B.Sample{state,material}: Sampled{Work{state,context},B.value(material)}

def finish(work: Work) -> S.Placement:
  match work:
    case Work{state,Context{_,_,world}}: S.Placement{state,world,True{}}

@unsafe
def below(state: W.U64,+limit: U32) -> R.Draw:
  below_many(U32.is_gt(limit,1),state,limit)

def below_many(many: Bool,state: W.U64,limit: U32) -> R.Draw:
  match many:
    case False{}: R.Draw{state,W.U64{0,0}}
    case True{}: R.integer(state,0,U32.sub(limit,1))

@unsafe
def place(+recipe: D.Fallen,state: W.U64,noises: Q.Table<N.Program>,world: E.World,+at: P.Position) -> S.Placement:
  match recipe:
    case D.Fallen{trunk,_,_,_,_,_,_,_,_,_,_}: stump(sample(trunk,at,Work{state,Context{recipe,noises,world}}),at)

@unsafe
def stump(result: Sampled,+at: P.Position) -> S.Placement:
  match result:
    case Sampled{+work,material}: stump_decorated(put(at,material,work),at)

@unsafe
def stump_decorated(+work: Work,+at: P.Position) -> S.Placement:
  match work:
    case Work{_,Context{D.Fallen{_,_,_,decorators,_,_,_,_,_,_,_},_,_}}: horizontal(decorate(decorators,[at],work),at)

@unsafe
def horizontal(+work: Work,+at: P.Position) -> S.Placement:
  match work:
    case Work{state,context}: chosen_direction(below(state,4),context,at)

@unsafe
def chosen_direction(draw: R.Draw,+context: Context,+at: P.Position) -> S.Placement:
  match draw context:
    case R.Draw{state,value} Context{D.Fallen{_,length,_,_,_,_,_,_,_,_,_},_,_}: chosen_length(I.sample(length,state),direction(R.low(value)),context,at)

@unsafe
def chosen_length(draw: R.Draw,+direction: P.Position,+context: Context,+at: P.Position) -> S.Placement:
  match draw:
    case R.Draw{state,value}: chosen_distance(below(state,2),U32.sub(U32.max(2,R.low(value)),2),direction,context,at)

@unsafe
def chosen_distance(draw: R.Draw,+length: U32,+direction: P.Position,context: Context,+at: P.Position) -> S.Placement:
  match draw:
    case R.Draw{state,value}: raised(shift(at,direction,U32.add(2,R.low(value))),length,direction,Work{state,context})

@unsafe
def raised(point: Maybe<&2,P.Position>,length: U32,direction: P.Position,work: Work) -> S.Placement:
  match point:
    case None{}: finish(work)
    case Some{point}: settled_start(P.shift(point,P.Position{0,1,0}),length,direction,work)

@unsafe
def settled_start(point: Maybe<&2,P.Position>,+length: U32,+direction: P.Position,+work: Work) -> S.Placement:
  match point:
    case None{}: finish(work)
    case Some{point}: settled(settle(6,point,work),length,direction,work)

@unsafe
def settle(+remaining: U32,+at: P.Position,+work: Work) -> Maybe<&2,P.Position>:
  match remaining work:
    case 0 _: Some{at}
    case _ Work{_,+context}: settled_test(Bool.and(test(clearance(context),Some{at},context),test(sturdy(context),Some{at},context)),remaining,at,work)

def clearance(context: Context) -> P.Predicate:
  match context:
    case Context{D.Fallen{_,_,_,_,_,predicate,_,_,_,_,_},_,_}: predicate

def sturdy(context: Context) -> P.Predicate:
  match context:
    case Context{D.Fallen{_,_,_,_,_,_,predicate,_,_,_,_},_,_}: predicate

@unsafe
def settled_test(ok: Bool,+remaining: U32,+at: P.Position,+work: Work) -> Maybe<&2,P.Position>:
  match ok:
    case True{}: Some{at}
    case False{}: settle_lower(P.shift(at,P.Position{0,4294967295,0}),U32.sub(remaining,1),work)

@unsafe
def settle_lower(point: Maybe<&2,P.Position>,remaining: U32,work: Work) -> Maybe<&2,P.Position>:
  match point:
    case None{}: None{}
    case Some{point}: settle(remaining,point,work)

@unsafe
def settled(point: Maybe<&2,P.Position>,+length: U32,+direction: P.Position,+work: Work) -> S.Placement:
  match point:
    case None{}: finish(work)
    case Some{+point}: room(valid_logs(0,length,0,point,direction,work),point,length,direction,work)

@unsafe
def valid_logs(+i: U32,+length: U32,+gap: U32,+at: P.Position,+direction: P.Position,+work: Work) -> Bool:
  valid_more(U32.is_lt(i,length),i,length,gap,at,direction,work)

@unsafe
def valid_more(more: Bool,+i: U32,+length: U32,+gap: U32,+at: P.Position,+direction: P.Position,+work: Work) -> Bool:
  match more work:
    case False{} _: True{}
    case True{} Work{_,+context}: valid_clear(test(clearance(context),shift(at,direction,i),context),i,length,gap,at,direction,work)

@unsafe
def valid_clear(ok: Bool,+i: U32,+length: U32,+gap: U32,+at: P.Position,+direction: P.Position,+work: Work) -> Bool:
  match ok work:
    case False{} _: False{}
    case True{} Work{_,+context}: valid_gap(test(sturdy(context),shift(at,direction,i),context),i,length,gap,at,direction,work)

@unsafe
def valid_gap(supported: Bool,+i: U32,+length: U32,+gap: U32,+at: P.Position,+direction: P.Position,+work: Work) -> Bool:
  match supported:
    case True{}: valid_logs(U32.inc(i),length,0,at,direction,work)
    case False{}: gap_limit(U32.is_lt(gap,2),i,length,gap,at,direction,work)

@unsafe
def gap_limit(ok: Bool,i: U32,length: U32,gap: U32,at: P.Position,direction: P.Position,work: Work) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: valid_logs(U32.inc(i),length,U32.inc(gap),at,direction,work)

@unsafe
def room(ok: Bool,at: P.Position,length: U32,direction: P.Position,work: Work) -> S.Placement:
  match ok:
    case False{}: finish(work)
    case True{}: logs(0,length,at,direction,work,[])

@unsafe
def logs(+i: U32,+length: U32,+at: P.Position,+direction: P.Position,+work: Work,acc: List<&2,P.Position>) -> S.Placement:
  logs_more(U32.is_lt(i,length),i,length,at,direction,work,acc)

@unsafe
def logs_more(more: Bool,+i: U32,+length: U32,+at: P.Position,+direction: P.Position,+work: Work,acc: List<&2,P.Position>) -> S.Placement:
  match more work:
    case False{} Work{_,Context{D.Fallen{_,_,_,_,ds,_,_,_,_,_,_},_,_}}:
      finish(decorate(ds,ordered(List.reverse(&2,P.Position,acc),length),work))
    case True{} _: log_position(shift(at,direction,i),i,length,at,direction,work,acc)

@unsafe
def log_position(point: Maybe<&2,P.Position>,+i: U32,+length: U32,+at: P.Position,+direction: P.Position,+work: Work,acc: List<&2,P.Position>) -> S.Placement:
  match point work:
    case None{} _: finish(work)
    case Some{+p} Work{_,Context{D.Fallen{trunk,_,_,_,_,_,_,_,_,_,_},_,_}}: log_sample(sample(trunk,p,work),p,i,length,at,direction,acc)

@unsafe
def log_sample(result: Sampled,+point: P.Position,+i: U32,+length: U32,+at: P.Position,+direction: P.Position,acc: List<&2,P.Position>) -> S.Placement:
  match result direction:
    case Sampled{+work,material} P.Position{_,_,z}: logs(U32.inc(i),length,at,direction,put(point,axis(work,material,W.flag(U32.is_ne(z,0))),work),point <> acc)

@unsafe
def axis(work: Work,material: U32,index: U32) -> U32:
  match work:
    case Work{_,Context{D.Fallen{_,_,axes,_,_,_,_,_,_,_,_},_,_}}: D.mapped(axes,material,index)

# Native decorator contexts retain stable Java HashSet bucket order for equal-Y
# logs. There are at most thirteen horizontal logs; collisions keep insertion order.
@unsafe
def bucket(at: P.Position,+mask: U32) -> U32:
  match at:
    case P.Position{x,y,z}:
      +h = U32.add(U32.mul(U32.add(y,U32.mul(z,31)),31),x)
      U32.and(U32.xor(h,U32.shrn(h,16n)),mask)

@unsafe
def ordered(+positions: List<&2,P.Position>,length: U32) -> List<&2,P.Position>:
  buckets(0,U32.add(15,U32.mul(W.flag(U32.is_gt(length,12)),16)),positions,[])

@unsafe
def buckets(+i: U32,+mask: U32,+positions: List<&2,P.Position>,acc: List<&2,P.Position>) -> List<&2,P.Position>:
  buckets_more(U32.is_le(i,mask),i,mask,positions,acc)

@unsafe
def buckets_more(more: Bool,+i: U32,+mask: U32,+positions: List<&2,P.Position>,acc: List<&2,P.Position>) -> List<&2,P.Position>:
  match more:
    case False{}: List.reverse(&2,P.Position,acc)
    case True{}: buckets(U32.inc(i),mask,positions,in_bucket(positions,i,mask,acc))

@unsafe
def in_bucket(positions: List<&2,P.Position>,+id: U32,+mask: U32,acc: List<&2,P.Position>) -> List<&2,P.Position>:
  match positions:
    case Nil{}: acc
    case +p <> rest: bucket_keep(U32.is_eq(bucket(p,mask),id),p,rest,id,mask,acc)

@unsafe
def bucket_keep(keep: Bool,p: P.Position,rest: List<&2,P.Position>,id: U32,mask: U32,acc: List<&2,P.Position>) -> List<&2,P.Position>:
  match keep:
    case False{}: in_bucket(rest,id,mask,acc)
    case True{}: in_bucket(rest,id,mask,p <> acc)

@unsafe
def get(xs: List<&2,P.Position>,+i: U32) -> P.Position:
  match xs i:
    case Nil{} _: P.Position{0,0,0}
    case p <> _ 0: p
    case _ <> rest _: get(rest,U32.sub(i,1))

@unsafe
def swap(xs: List<&2,P.Position>,+i: U32,+a: U32,+b: U32,+pa: P.Position,+pb: P.Position,acc: List<&2,P.Position>) -> List<&2,P.Position>:
  match xs:
    case Nil{}: List.reverse(&2,P.Position,acc)
    case p <> rest: swapped(U32.is_eq(i,a),U32.is_eq(i,b),p,rest,i,a,b,pa,pb,acc)

@unsafe
def swapped(a_here: Bool,b_here: Bool,p: P.Position,rest: List<&2,P.Position>,i: U32,a: U32,b: U32,+pa: P.Position,+pb: P.Position,acc: List<&2,P.Position>) -> List<&2,P.Position>:
  match a_here b_here:
    case True{} _: swap(rest,U32.inc(i),a,b,pa,pb,pb <> acc)
    case False{} True{}: swap(rest,U32.inc(i),a,b,pa,pb,pa <> acc)
    case False{} False{}: swap(rest,U32.inc(i),a,b,pa,pb,p <> acc)

@unsafe
def shuffle(+n: U32,+positions: List<&2,P.Position>,state: W.U64) -> Shuffled:
  shuffle_more(U32.is_gt(n,1),n,positions,state)

@unsafe
def shuffle_more(more: Bool,+n: U32,+positions: List<&2,P.Position>,state: W.U64) -> Shuffled:
  match more:
    case False{}: Shuffled{state,positions}
    case True{}: shuffle_draw(below(state,n),n,positions)

@unsafe
def shuffle_draw(draw: R.Draw,+n: U32,+positions: List<&2,P.Position>) -> Shuffled:
  match draw:
    case R.Draw{state,+value}: shuffle(U32.sub(n,1),swap(positions,0,U32.sub(n,1),R.low(value),get(positions,U32.sub(n,1)),get(positions,R.low(value)),[]),state)

@unsafe
def decorate(decorators: List<&2,D.Decorator>,+positions: List<&2,P.Position>,work: Work) -> Work:
  match decorators:
    case Nil{}: work
    case decorator <> rest: decorate(rest,positions,apply_decorator(decorator,positions,work))

@unsafe
def apply_decorator(decorator: D.Decorator,+positions: List<&2,P.Position>,+work: Work) -> Work:
  match decorator work:
    case D.Attached{p,provider,directions} Work{state,context}: attached_shuffled(shuffle(U32.from_nat(List.length(&2,P.Position,positions)),positions,state),p,provider,directions,context)
    case D.Vine{states} _: vines(positions,states,work)
    case D.Shelf{p,states} Work{state,context}: shelf_chance(R.next(state),p,states,positions,context)

@unsafe
def attached_shuffled(result: Shuffled,p: F32,provider: B.Provider,directions: Q.Table<P.Position>,context: Context) -> Work:
  match result:
    case Shuffled{state,positions}: attached(positions,p,provider,directions,Work{state,context})

@unsafe
def attached(positions: List<&2,P.Position>,+p: F32,+provider: B.Provider,+directions: Q.Table<P.Position>,work: Work) -> Work:
  match positions work:
    case Nil{} _: work
    case at <> rest Work{state,context}: attached_direction(below(state,Q.table_size(P.Position,directions)),at,rest,p,provider,directions,context)

@unsafe
def attached_direction(draw: R.Draw,at: P.Position,rest: List<&2,P.Position>,p: F32,provider: B.Provider,+directions: Q.Table<P.Position>,context: Context) -> Work:
  match draw:
    case R.Draw{state,value}: attached_chance(R.next(state),P.shift(at,Q.get(P.Position,directions,R.low(value),P.Position{0,0,0})),rest,p,provider,directions,context)

@unsafe
def attached_chance(draw: R.Draw,+point: Maybe<&2,P.Position>,rest: List<&2,P.Position>,+p: F32,provider: B.Provider,directions: Q.Table<P.Position>,+context: Context) -> Work:
  match draw context:
    case R.Draw{state,value} Context{D.Fallen{_,_,_,_,_,_,_,air,_,_,_},_,_}:
      attached_allowed(Bool.and(F32.is_le(R.unit(value),p),test(air,point,context)),point,rest,p,provider,directions,Work{state,context})

@unsafe
def attached_allowed(ok: Bool,point: Maybe<&2,P.Position>,rest: List<&2,P.Position>,p: F32,+provider: B.Provider,directions: Q.Table<P.Position>,work: Work) -> Work:
  match ok point:
    case True{} Some{+at}: attached_sample(sample(provider,at,work),at,rest,p,provider,directions)
    case _ _: attached(rest,p,provider,directions,work)

@unsafe
def attached_sample(result: Sampled,at: P.Position,rest: List<&2,P.Position>,p: F32,provider: B.Provider,directions: Q.Table<P.Position>) -> Work:
  match result:
    case Sampled{work,material}: attached(rest,p,provider,directions,put(at,material,work))

@unsafe
def vines(positions: List<&2,P.Position>,+states: Q.Table<U32>,work: Work) -> Work:
  match positions:
    case Nil{}: work
    case at <> rest: vines(rest,states,vine_sides(0,at,states,work))

@unsafe
def vine_sides(+i: U32,+at: P.Position,+states: Q.Table<U32>,work: Work) -> Work:
  match i work:
    case 4 _: work
    case _ Work{state,context}: vine_draw(below(state,3),i,at,states,context)

def vine_direction(i: U32) -> P.Position:
  match i:
    case 0: direction(3)
    case 1: direction(1)
    case 2: direction(0)
    case _: direction(2)

@unsafe
def vine_draw(draw: R.Draw,+i: U32,+at: P.Position,+states: Q.Table<U32>,+context: Context) -> Work:
  match draw:
    case R.Draw{state,value}: vine_chance(U32.is_gt(R.low(value),0),P.shift(at,vine_direction(i)),i,at,states,Work{state,context})

@unsafe
def vine_chance(chance: Bool,+point: Maybe<&2,P.Position>,+i: U32,+at: P.Position,+states: Q.Table<U32>,+work: Work) -> Work:
  match work:
    case Work{_,+context}: vine_allowed(Bool.and(chance,test(air(context),point,context)),point,i,at,states,work)

def air(context: Context) -> P.Predicate:
  match context:
    case Context{D.Fallen{_,_,_,_,_,_,_,predicate,_,_,_},_,_}: predicate

@unsafe
def vine_allowed(ok: Bool,point: Maybe<&2,P.Position>,+i: U32,at: P.Position,+states: Q.Table<U32>,work: Work) -> Work:
  match ok point:
    case True{} Some{p}: vine_sides(U32.inc(i),at,states,put(p,Q.get(U32,states,i,0),work))
    case _ _: vine_sides(U32.inc(i),at,states,work)

@unsafe
def shelf_chance(draw: R.Draw,p: F32,states: Q.Table<Q.Table<U32>>,+positions: List<&2,P.Position>,context: Context) -> Work:
  match draw:
    case R.Draw{state,value}: shelf_active(F32.is_lt(R.unit(value),p),states,positions,Work{state,context})

@unsafe
def shelf_active(ok: Bool,+states: Q.Table<Q.Table<U32>>,+positions: List<&2,P.Position>,work: Work) -> Work:
  match ok positions:
    case False{} _: work
    case True{} Nil{}: work
    case True{} _: shelf_logs(positions,shelf_orientation(positions),states,work)

@unsafe
def shelf_orientation(+positions: List<&2,P.Position>) -> U32:
  orientation(get(positions,0),get(positions,U32.sub(U32.from_nat(List.length(&2,P.Position,positions)),1)))

def orientation(a: P.Position,b: P.Position) -> U32:
  match a b:
    case P.Position{x,_,_} P.Position{y,_,_}: W.flag(U32.is_eq(x,y))

@unsafe
def shelf_logs(positions: List<&2,P.Position>,+orientation: U32,+states: Q.Table<Q.Table<U32>>,work: Work) -> Work:
  match positions:
    case Nil{}: work
    case at <> rest: shelf_logs(rest,orientation,states,shelf_sides(0,orientation,at,states,work))

@unsafe
def shelf_sides(+i: U32,+orientation: U32,+at: P.Position,+states: Q.Table<Q.Table<U32>>,work: Work) -> Work:
  match i work:
    case 2 _: work
    case _ Work{state,context}: shelf_draw(R.next(state),i,orientation,at,states,context)

@unsafe
def shelf_draw(draw: R.Draw,+i: U32,+orientation: U32,+at: P.Position,+states: Q.Table<Q.Table<U32>>,context: Context) -> Work:
  match draw:
    case R.Draw{state,value}: shelf_try(F32.is_le(R.unit(value),0.25),P.shift(at,direction(U32.add(orientation,U32.mul(i,2)))),i,orientation,at,states,Work{state,context})

@unsafe
def any_neighbor(+i: U32,+predicate: P.Predicate,+at: P.Position,+context: Context) -> Bool:
  match i:
    case 4: False{}
    case _: neighbor_found(test(predicate,P.shift(at,direction(i)),context),i,predicate,at,context)

@unsafe
def neighbor_found(found: Bool,i: U32,predicate: P.Predicate,at: P.Position,context: Context) -> Bool:
  match found:
    case True{}: True{}
    case False{}: any_neighbor(U32.inc(i),predicate,at,context)

@unsafe
def shelf_clear(+p: P.Position,+at: P.Position,+context: Context) -> Bool:
  match context:
    case Context{D.Fallen{_,_,_,_,_,_,_,_,replaceable,+water,+shelf},_,_}:
      Bool.and(test(replaceable,Some{p},context),Bool.not(Bool.or(test(water,Some{p},context),
        Bool.or(any_neighbor(0,water,p,context),Bool.or(any_neighbor(0,shelf,p,context),any_neighbor(0,shelf,at,context))))))

@unsafe
def shelf_try(chance: Bool,+point: Maybe<&2,P.Position>,+i: U32,+orientation: U32,+at: P.Position,+states: Q.Table<Q.Table<U32>>,+work: Work) -> Work:
  match chance point work:
    case True{} Some{+p} Work{_,context}: shelf_allowed(shelf_clear(p,at,context),point,i,orientation,at,states,work)
    case _ _ _: shelf_sides(U32.inc(i),orientation,at,states,work)

@unsafe
def shelf_allowed(ok: Bool,point: Maybe<&2,P.Position>,+i: U32,+orientation: U32,+at: P.Position,+states: Q.Table<Q.Table<U32>>,work: Work) -> Work:
  match ok point work:
    case True{} Some{p} Work{state,context}: shelf_material(below(state,2),p,i,orientation,at,states,context)
    case _ _ _: shelf_sides(U32.inc(i),orientation,at,states,work)

@unsafe
def shelf_material(draw: R.Draw,point: P.Position,+i: U32,+orientation: U32,+at: P.Position,+states: Q.Table<Q.Table<U32>>,context: Context) -> Work:
  match draw:
    case R.Draw{state,value}: shelf_sides(U32.inc(i),orientation,at,states,put(point,Q.get(U32,Q.get(Q.Table<U32>,states,U32.add(orientation,U32.mul(i,2)),Q.Empty{}),R.low(value),0),Work{state,context}))
