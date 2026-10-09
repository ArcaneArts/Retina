import Base
import ./placement_cursor.bend as K
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_checks.bend as Check
import ./placement_random.bend as R
import ./placement_height.bend as H
import ./int_provider.bend as I
import ./decoration_counts.bend as C
import ./material_columns_data.bend as MD
import ./density.bend as Q
import ./noise.bend as N
import ./word64.bend as W

@unsafe
def chunk(+axis: U32) -> U32:
  U32.or(U32.shrn(axis,4n),N.word(U32.is_ge(axis,2147483648),4026531840,0))

@unsafe
def initial_seed(+origin: P.Position,+seed: W.U64,+salt: W.U64) -> W.U64:
  match origin:
    case P.Position{x,_,z}: W.xor(W.xor(seed,salt),W.xor(R.mixed(W.U64{0,chunk(x)}),R.mixed(W.U64{chunk(z),0})))

@unsafe
def contains(xs: List<&2,U32>,+id: U32) -> Bool:
  match xs:
    case Nil{}: False{}
    case x <> rest: contains_equal(U32.is_eq(x,id),rest,id)

@unsafe
def contains_equal(+same: Bool,rest: List<&2,U32>,+id: U32) -> Bool:
  match same:
    case True{}: True{}
    case False{}: contains(rest,id)

@unsafe
def initialize(ids: List<&2,U32>,+seen: List<&2,U32>,+model: P.Model,+origin: P.Position,+seed: W.U64,+cursor: K.Cursor) -> K.Cursor:
  match ids model:
    case Nil{} _: cursor
    case +id <> rest P.Model{+recipes,_,_}: initialize_recipe(Bool.and(U32.is_lt(id,Q.table_size(P.Recipe,recipes)),Bool.not(contains(seen,id))),Q.get(P.Recipe,recipes,id,P.empty_recipe()),rest,id,seen,model,origin,seed,cursor)

@unsafe
def initialize_recipe(+include: Bool,+recipe: P.Recipe,rest: List<&2,U32>,+id: U32,+seen: List<&2,U32>,+model: P.Model,+origin: P.Position,+seed: W.U64,+cursor: K.Cursor) -> K.Cursor:
  match include recipe:
    case True{} P.Recipe{group,salt,True{},_}: initialize(rest,id <> seen,model,origin,seed,K.queued(cursor,K.Task{K.Entry{id,group,0,origin,initial_seed(origin,seed,salt),[]},K.Step{}}))
    case _ _: initialize(rest,id <> seen,model,origin,seed,cursor)

# Recipe input order does not select evaluation order; the heap does. Duplicate
# IDs do not double a placement budget. Every anchor owns its cursor/cache.
@unsafe
def new(+model: P.Model,ids: List<&2,U32>,+origin: P.Position,+seed: W.U64) -> K.Cursor:
  initialize(ids,[],model,origin,seed,K.Cursor{K.Empty{},K.Missing{},0})

@unsafe
def entry_next(+entry: K.Entry,+at: P.Position,+state: W.U64,+path: List<&2,U32>) -> K.Entry:
  match entry:
    case K.Entry{r,g,i,_,_,_}: K.Entry{r,g,U32.inc(i),at,state,path}

@unsafe
def with_state(+entry: K.Entry,+state: W.U64) -> K.Entry:
  match entry:
    case K.Entry{r,g,i,at,_,p}: K.Entry{r,g,i,at,state,p}

@unsafe
def advance(+at: Maybe<&2,P.Position>,+state: W.U64,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match at entry:
    case None{} _: K.Work{cursor}
    case Some{at} K.Entry{_,_,_,_,_,path}: K.Work{K.queued(cursor,K.Task{entry_next(entry,at,state,path),K.Step{}})}

@unsafe
def child(+at: Maybe<&2,P.Position>,+state: W.U64,+path: List<&2,U32>,+entry: K.Entry,+cursor: K.Cursor) -> K.Cursor:
  match at:
    case None{}: cursor
    case Some{at}: K.queued(cursor,K.Task{entry_next(entry,at,state,path),K.Step{}})

@unsafe
def expanding(+modifier: P.Modifier) -> Bool:
  match modifier:
    case P.Count{_}: True{}
    case P.Cuboid{_,_,_,_}: True{}
    case P.Layer{_}: True{}
    case _: False{}

@unsafe
def future(+program: Q.Table<P.Modifier>,+index: U32,+count: U32) -> Bool:
  match count:
    case 0: False{}
    case _: Bool.or(expanding(Q.get(P.Modifier,program,index,P.Square{})),future(program,U32.inc(index),U32.sub(count,1)))

@unsafe
def terrain(+modifier: P.Modifier,+program: Q.Table<P.Modifier>,+entry: K.Entry,+cursor: K.Cursor,+world: E.World) -> K.Action:
  match entry:
    case K.Entry{_,_,index,_,_,_}: terrain_cached(future(program,U32.inc(index),U32.sub(Q.table_size(P.Modifier,program),U32.inc(index))),modifier,entry,cursor,world)

@unsafe
def terrain_cached(+remember: Bool,+modifier: P.Modifier,+entry: K.Entry,+cursor: K.Cursor,+world: E.World) -> K.Action:
  match remember entry cursor:
    case False{} K.Entry{r,_,_,at,state,_} _: advance(Check.evaluate(modifier,world,at,r),state,entry,cursor)
    case True{} K.Entry{r,g,i,at,_,p} K.Cursor{_,cache,_}: terrain_lookup(K.lookup(cache,K.CacheKey{g,i,p}),modifier,entry,cursor,world,K.CacheKey{g,i,p})

@unsafe
def terrain_lookup(+found: Maybe<&2,Maybe<&2,P.Position>>,+modifier: P.Modifier,+entry: K.Entry,+cursor: K.Cursor,+world: E.World,+key: K.CacheKey) -> K.Action:
  match found entry:
    case Some{value} K.Entry{_,_,_,_,state,_}: advance(value,state,entry,cursor)
    case None{} K.Entry{r,_,_,at,_,_}: terrain_store(Check.evaluate(modifier,world,at,r),entry,cursor,key)

@unsafe
def terrain_store(+value: Maybe<&2,P.Position>,+entry: K.Entry,+cursor: K.Cursor,+key: K.CacheKey) -> K.Action:
  match entry:
    case K.Entry{_,_,_,_,state,_}: advance(value,state,entry,K.cached(cursor,key,value))

@unsafe
def counted(+draw: R.Draw,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw:
    case R.Draw{state,value}: K.Work{K.queued(cursor,K.Task{with_state(entry,state),K.Count{C.nonnegative(R.low(value)),0}})}

@unsafe
def count_child(+remaining: U32,+ordinal: U32,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match remaining entry:
    case 0 _: K.Work{cursor}
    case _ K.Entry{_,_,_,_,state,_}: count_draw(R.next(state),remaining,ordinal,entry,cursor)

@unsafe
def count_draw(+draw: R.Draw,+remaining: U32,+ordinal: U32,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw entry:
    case R.Draw{state,seed} K.Entry{_,_,_,at,_,path}: K.Work{child(Some{at},seed,K.append(path,ordinal),entry,
      K.queued(cursor,K.Task{with_state(entry,state),K.Count{U32.sub(remaining,1),U32.inc(ordinal)}}))}

@unsafe
def square_x(+draw: R.Draw,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw:
    case R.Draw{state,x}: square_z(R.integer(state,0,15),R.low(x),entry,cursor)

@unsafe
def square_z(+draw: R.Draw,+x: U32,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw entry:
    case R.Draw{state,z} K.Entry{_,_,_,at,_,_}: advance(P.shift(at,P.Position{x,0,R.low(z)}),state,entry,cursor)

@unsafe
def offset_x(+draw: R.Draw,+y: I.Provider,+z: I.Provider,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw:
    case R.Draw{state,x}: offset_y(I.sample(y,state),R.low(x),z,entry,cursor)

@unsafe
def offset_y(+draw: R.Draw,+x: U32,+z: I.Provider,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw:
    case R.Draw{state,y}: offset_z(I.sample(z,state),x,R.low(y),entry,cursor)

@unsafe
def offset_z(+draw: R.Draw,+x: U32,+y: U32,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw entry:
    case R.Draw{state,z} K.Entry{_,_,_,at,_,_}: advance(P.shift(at,P.Position{x,y,R.low(z)}),state,entry,cursor)

@unsafe
def height_draw(+draw: R.Draw,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw entry:
    case R.Draw{state,y} K.Entry{_,_,_,P.Position{x,_,z},_,_}: advance(Some{P.Position{x,R.low(y),z}},state,entry,cursor)

@unsafe
def selected(+draw: R.Draw,+minimum: F32,+maximum: F32,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw entry:
    case R.Draw{state,+value} K.Entry{_,_,_,at,_,_}: advance(Check.retain(Bool.and(F32.is_ge(R.unit(value),minimum),F32.is_lt(R.unit(value),maximum)),at),state,entry,cursor)

@unsafe
def cuboid_height(+draw: R.Draw,+xz: I.Provider,+edges: Bool,+interior: Bool,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw:
    case R.Draw{state,y}: cuboid_width(I.sample(xz,state),R.low(y),xz,edges,interior,entry,cursor)

@unsafe
def cuboid_width(+draw: R.Draw,+height: U32,+xz: I.Provider,+edges: Bool,+interior: Bool,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw:
    case R.Draw{state,x}: cuboid_length(I.sample(xz,state),R.low(x),height,edges,interior,entry,cursor)

@unsafe
def cuboid_length(+draw: R.Draw,+width: U32,+height: U32,+edges: Bool,+interior: Bool,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw:
    case R.Draw{state,z}: K.Work{K.queued(cursor,K.Task{with_state(entry,state),K.Cuboid{width,height,R.low(z),0,0,edges,interior}})}

@unsafe
def boundary(+value: U32,+maximum: U32) -> U32:
  W.flag(Bool.or(U32.is_zero(value),U32.is_eq(value,maximum)))

@unsafe
def emit(+planes: U32,+edges: Bool,+interior: Bool) -> Bool:
  match planes:
    case 0: interior
    case 1: True{}
    case _: edges

@unsafe
def cuboid_child(+kind: K.TaskKind,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match kind:
    case K.Cuboid{+w,+h,+l,+cell,_,_,_}: cuboid_more(U32.is_lt(cell,U32.mul(U32.inc(w),U32.mul(U32.inc(h),U32.inc(l)))),kind,entry,cursor)
    case _: K.Work{cursor}

@unsafe
def cuboid_more(+more: Bool,+kind: K.TaskKind,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match more kind:
    case False{} _: K.Work{cursor}
    case True{} K.Cuboid{w,h,+l,+cell,_,_,_}: cuboid_at(P.Position{U32.div(cell,U32.mul(U32.inc(h),U32.inc(l))),U32.mod(U32.div(cell,U32.inc(l)),U32.inc(h)),U32.mod(cell,U32.inc(l))},kind,entry,cursor)
    case _ _: K.Work{cursor}

@unsafe
def cuboid_at(+offset: P.Position,+kind: K.TaskKind,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match offset kind:
    case P.Position{x,y,z} K.Cuboid{w,h,l,_,_,e,i}: cuboid_emit(emit(U32.add(boundary(x,w),U32.add(boundary(y,h),boundary(z,l))),e,i),offset,kind,entry,cursor)
    case _ _: K.Work{cursor}

@unsafe
def cuboid_emit(+emitted: Bool,+offset: P.Position,+kind: K.TaskKind,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match emitted kind entry:
    case False{} K.Cuboid{w,h,l,cell,i,e,inside} _: K.Work{K.queued(cursor,K.Task{entry,K.Cuboid{w,h,l,U32.inc(cell),i,e,inside}})}
    case True{} _ K.Entry{_,_,_,_,state,_}: cuboid_draw(R.next(state),offset,kind,entry,cursor)
    case _ _ _: K.Work{cursor}

@unsafe
def cuboid_draw(+draw: R.Draw,+offset: P.Position,+kind: K.TaskKind,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match draw kind entry:
    case R.Draw{state,seed} K.Cuboid{w,h,l,cell,i,e,inside} K.Entry{_,_,_,at,_,path}: K.Work{child(P.shift(at,offset),seed,K.append(path,i),entry,
      K.queued(cursor,K.Task{with_state(entry,state),K.Cuboid{w,h,l,U32.inc(cell),U32.inc(i),e,inside}}))}
    case _ _ _: K.Work{cursor}

@unsafe
def layer_test(+draw: R.Draw,+layer: U32,+attempt: U32,+found: Bool,+entry: K.Entry,+cursor: K.Cursor,+world: E.World) -> K.Action:
  match draw:
    case R.Draw{state,count}: layer_more(U32.is_lt(attempt,C.nonnegative(R.low(count))),layer,attempt,found,with_state(entry,state),cursor,world)

@unsafe
def layer_more(+more: Bool,+layer: U32,+attempt: U32,+found: Bool,+entry: K.Entry,+cursor: K.Cursor,+world: E.World) -> K.Action:
  match more found entry:
    case False{} True{} _: K.Work{K.queued(cursor,K.Task{entry,K.Layer{U32.inc(layer),0,False{}}})}
    case False{} False{} _: K.Work{cursor}
    case True{} _ K.Entry{_,_,_,_,state,_}: layer_x(R.integer(state,0,15),layer,attempt,found,entry,cursor,world)

@unsafe
def layer_x(+draw: R.Draw,+layer: U32,+attempt: U32,+found: Bool,+entry: K.Entry,+cursor: K.Cursor,+world: E.World) -> K.Action:
  match draw:
    case R.Draw{state,x}: layer_z(R.integer(state,0,15),R.low(x),layer,attempt,found,entry,cursor,world)

@unsafe
def layer_z(+draw: R.Draw,+x: U32,+layer: U32,+attempt: U32,+found: Bool,+entry: K.Entry,+cursor: K.Cursor,+world: E.World) -> K.Action:
  match draw entry cursor:
    case R.Draw{state,z} K.Entry{_,g,i,_,_,path} K.Cursor{_,cache,_}:
      +key = {K.CacheKey{g,i,K.append(K.append(path,layer),attempt)} : K.CacheKey}
      layer_lookup(K.lookup(cache,key),P.Position{x,0,R.low(z)},layer,attempt,found,with_state(entry,state),cursor,world,key)

@unsafe
def layer_lookup(+cached: Maybe<&2,Maybe<&2,P.Position>>,+offset: P.Position,+layer: U32,+attempt: U32,+found: Bool,+entry: K.Entry,+cursor: K.Cursor,+world: E.World,+key: K.CacheKey) -> K.Action:
  match cached entry:
    case Some{at} _: layer_children(at,layer,attempt,found,entry,cursor)
    case None{} K.Entry{_,_,_,at,_,_}: layer_ground(P.shift(at,offset),layer,attempt,found,entry,cursor,world,key)

@unsafe
def layer_ground(+at: Maybe<&2,P.Position>,+layer: U32,+attempt: U32,+found: Bool,+entry: K.Entry,+cursor: K.Cursor,+world: E.World,+key: K.CacheKey) -> K.Action:
  match at:
    case None{}: layer_stored(None{},layer,attempt,found,entry,cursor,key)
    case Some{P.Position{+x,_,+z}}: layer_stored(Check.set_y(E.ground(world,x,z,layer),P.Position{x,0,z}),layer,attempt,found,entry,cursor,key)

@unsafe
def layer_stored(+at: Maybe<&2,P.Position>,+layer: U32,+attempt: U32,+found: Bool,+entry: K.Entry,+cursor: K.Cursor,+key: K.CacheKey) -> K.Action:
  layer_children(at,layer,attempt,found,entry,K.cached(cursor,key,at))

def present(+at: Maybe<&2,P.Position>) -> Bool:
  match at:
    case None{}: False{}
    case Some{_}: True{}

@unsafe
def layer_children(+at: Maybe<&2,P.Position>,+layer: U32,+attempt: U32,+found: Bool,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match entry:
    case K.Entry{_,_,_,_,state,path}: K.Work{child(at,state,K.append(K.append(path,layer),attempt),entry,
      K.queued(cursor,K.Task{entry,K.Layer{layer,U32.inc(attempt),Bool.or(found,present(at))}}))}

@unsafe
def finished(+entry: K.Entry,+cursor: K.Cursor,+world: E.World) -> K.Action:
  match entry world:
    case K.Entry{r,g,_,P.Position{_,y,_},state,path} E.World{source,_,_}: finished_inside(E.inside_y(source,y),entry,cursor)

@unsafe
def finished_inside(+inside: Bool,+entry: K.Entry,+cursor: K.Cursor) -> K.Action:
  match inside entry:
    case False{} _: K.Work{cursor}
    case True{} K.Entry{r,g,_,at,state,path}: K.CandidateReady{cursor,K.Candidate{r,g,at,state,path}}

@unsafe
def step(+done: Bool,+entry: K.Entry,+cursor: K.Cursor,+world: E.World,+model: P.Model,+program: Q.Table<P.Modifier>) -> K.Action:
  match done entry:
    case True{} _: finished(entry,cursor,world)
    case False{} K.Entry{_,_,index,_,_,_}: modifier(Q.get(P.Modifier,program,index,P.Square{}),entry,cursor,world,model,program)

@unsafe
def modifier(+m: P.Modifier,+entry: K.Entry,+cursor: K.Cursor,+world: E.World,+model: P.Model,+program: Q.Table<P.Modifier>) -> K.Action:
  match m entry world model:
    case P.Count{rule} K.Entry{_,_,_,P.Position{x,_,z},state,_} _ P.Model{_,permutation,_}: counted(C.sample(rule,permutation,x,z,state),entry,cursor)
    case P.Layer{_} _ _ _: K.Work{K.queued(cursor,K.Task{entry,K.Layer{0,0,False{}}})}
    case P.Cuboid{xz,y,e,i} K.Entry{_,_,_,_,state,_} _ _: cuboid_height(I.sample(y,state),xz,e,i,entry,cursor)
    case P.Square{} K.Entry{_,_,_,_,state,_} _ _: square_x(R.integer(state,0,15),entry,cursor)
    case P.Offset{x,y,z} K.Entry{_,_,_,_,state,_} _ _: offset_x(I.sample(x,state),y,z,entry,cursor)
    case P.HeightRange{p} K.Entry{_,_,_,_,state,_} E.World{MD.Grid{_,minimum,height,_,_},_,_} _: height_draw(H.sample(p,state,minimum,height),entry,cursor)
    case P.Select{a,b} K.Entry{_,_,_,_,state,_} _ _: selected(R.next(state),a,b,entry,cursor)
    case P.Rarity{chance} K.Entry{_,_,_,_,state,_} _ _: selected(R.next(state),0.0,F32.div(1.0,U32.to_f32(chance)),entry,cursor)
    case P.Chance{chance} K.Entry{_,_,_,_,state,_} _ _: selected(R.next(state),0.0,chance,entry,cursor)
    case _ _ _ _: terrain(m,program,entry,cursor,world)

@unsafe
def evaluate_task(+task: K.Task,+cursor: K.Cursor,+world: E.World,+model: P.Model) -> K.Action:
  match task model:
    case K.Task{+entry,K.Step{}} P.Model{recipes,_,_}: step_recipe(Q.get(P.Recipe,recipes,recipe_id(entry),P.empty_recipe()),entry,cursor,world,model)
    case K.Task{entry,K.Count{remaining,ordinal}} _: count_child(remaining,ordinal,entry,cursor)
    case K.Task{entry,K.Cuboid{w,h,l,c,i,e,inr}} _: cuboid_child(K.Cuboid{w,h,l,c,i,e,inr},entry,cursor)
    case K.Task{+entry,K.Layer{l,i,f}} P.Model{recipes,_,_}: layer_recipe(Q.get(P.Recipe,recipes,recipe_id(entry),P.empty_recipe()),l,i,f,entry,cursor,world)

def recipe_id(+entry: K.Entry) -> U32:
  match entry:
    case K.Entry{r,_,_,_,_,_}: r

@unsafe
def step_recipe(+recipe: P.Recipe,+entry: K.Entry,+cursor: K.Cursor,+world: E.World,+model: P.Model) -> K.Action:
  match recipe entry:
    case P.Recipe{_,_,_,+program} K.Entry{_,_,index,_,_,_}: step(U32.is_ge(index,Q.table_size(P.Modifier,program)),entry,cursor,world,model,program)

@unsafe
def layer_recipe(+recipe: P.Recipe,+layer: U32,+attempt: U32,+found: Bool,+entry: K.Entry,+cursor: K.Cursor,+world: E.World) -> K.Action:
  match recipe entry:
    case P.Recipe{_,_,_,program} K.Entry{_,_,index,_,_,_}: layer_provider(Q.get(P.Modifier,program,index,P.Square{}),layer,attempt,found,entry,cursor,world)

@unsafe
def layer_provider(+modifier: P.Modifier,+layer: U32,+attempt: U32,+found: Bool,+entry: K.Entry,+cursor: K.Cursor,+world: E.World) -> K.Action:
  match modifier entry:
    case P.Layer{provider} K.Entry{_,_,_,_,state,_}: layer_test(I.sample(provider,state),layer,attempt,found,entry,cursor,world)
    case _ _: K.Work{cursor}

# A fuel exhaustion pauses the same cursor. It never silently truncates a
# candidate stream. The caller can update the live world between Yield calls.
@unsafe
def next(+cursor: K.Cursor,+model: P.Model,+world: E.World,+fuel: U32) -> K.Outcome:
  match cursor fuel:
    case K.Cursor{K.Empty{},_,_} _: K.Exhausted{cursor}
    case _ 0: K.Paused{cursor}
    case K.Cursor{K.Node{_,_,pending,left,right},cache,steps} _: resumed(evaluate_task(pending,K.Cursor{K.merge(left,right),cache,U32.inc(steps)},world,model),model,world,U32.sub(fuel,1))

@unsafe
def resumed(+action: K.Action,+model: P.Model,+world: E.World,+fuel: U32) -> K.Outcome:
  match action:
    case K.CandidateReady{cursor,candidate}: K.Yield{cursor,candidate}
    case K.Work{cursor}: next(cursor,model,world,fuel)
