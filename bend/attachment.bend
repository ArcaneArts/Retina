import Base
import ./attachment_data.bend as D
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_random.bend as R
import ./placement_shuffle.bend as Shuffle
import ./surface_plants.bend as S
import ./density.bend as Q
import ./word64.bend as W

type Work is Data:
  Work{state: W.U64,recipe: D.Recipe,world: E.World}
type Growth is Data:
  Growth{work: Work,placed: Bool}
type Attempt is Data:
  Missing{work: Work}
  Selected{work: Work,written: Bool}

def direction(i: U32) -> P.Position:
  match i:
    case 0: P.Position{0,4294967295,0}
    case 1: P.Position{0,1,0}
    case 2: P.Position{0,0,4294967295}
    case 3: P.Position{0,0,1}
    case 4: P.Position{4294967295,0,0}
    case _: P.Position{1,0,0}

@unsafe
def relative(at: P.Position,d: U32) -> Maybe<&2,P.Position>:
  P.shift(at,direction(d))

@unsafe
def test(predicate: P.Predicate,point: Maybe<&2,P.Position>,world: E.World) -> Bool:
  match point:
    case None{}: False{}
    case Some{at}: S.test(predicate,world,at)

@unsafe
def write(at: P.Position,material: U32,work: Work) -> Work:
  match work:
    case Work{state,recipe,world}: Work{state,recipe,E.put(world,at,material)}

def finish(result: Growth) -> S.Placement:
  match result:
    case Growth{Work{state,_,world},ok}: S.Placement{state,world,ok}

@unsafe
def inside(world: E.World,at: P.Position) -> Bool:
  match world at:
    case E.World{source,_,_,_} P.Position{_,y,_}: E.inside_y(source,y)

@unsafe
def state_at(states: Q.Table<D.State>,world: E.World,at: P.Position) -> Maybe<&2,D.State>:
  state_value(E.material(world,at),states)

def state_value(value: Maybe<&2,U32>,states: Q.Table<D.State>) -> Maybe<&2,D.State>:
  match value:
    case None{}: None{}
    case Some{id}: D.find(states,id)

@unsafe
def placed_state(+recipe: D.Recipe,+world: E.World,+at: P.Position,+face: U32) -> Maybe<&2,U32>:
  match recipe:
    case D.Recipe{D.Settings{_,_,_,mask,_,_,_,_,_},_,D.Surfaces{_,_,_,_,_,_,support,_}}:
      face_allowed(Bool.and(D.bit(mask,face),test(Q.get(P.Predicate,support,face,P.Not{P.Always{}}),Some{at},world)),recipe,world,at,face)

@unsafe
def face_allowed(ok: Bool,+recipe: D.Recipe,+world: E.World,+at: P.Position,face: U32) -> Maybe<&2,U32>:
  match ok:
    case False{}: None{}
    case True{}: old_material(E.material(world,at),recipe,world,at,face)

@unsafe
def old_material(value: Maybe<&2,U32>,+recipe: D.Recipe,+world: E.World,+at: P.Position,face: U32) -> Maybe<&2,U32>:
  match value recipe:
    case None{} _: None{}
    case Some{id} D.Recipe{_,states,_}: existing(D.find(states,id),recipe,world,at,face)

@unsafe
def existing(row: Maybe<&2,D.State>,+recipe: D.Recipe,+world: E.World,+at: P.Position,+face: U32) -> Maybe<&2,U32>:
  match row recipe:
    case Some{D.State{_,mask,added}} _: state_added(Bool.not(D.bit(mask,face)),added,face)
    case None{} D.Recipe{D.Settings{_,initial,wet,_,_,_,_,_,_},states,D.Surfaces{_,_,water,_,_,_,_,_}}:
      initial_state(test(water,Some{at},world),initial,wet,states,face)

@unsafe
def state_added(ok: Bool,added: Q.Table<U32>,face: U32) -> Maybe<&2,U32>:
  match ok:
    case False{}: None{}
    case True{}: Some{Q.get(U32,added,face,0)}

@unsafe
def initial_state(water: Bool,initial: U32,wet: U32,states: Q.Table<D.State>,face: U32) -> Maybe<&2,U32>:
  match water:
    case False{}: added(D.find(states,initial),face)
    case True{}: added(D.find(states,wet),face)

@unsafe
def added(row: Maybe<&2,D.State>,face: U32) -> Maybe<&2,U32>:
  match row:
    case None{}: None{}
    case Some{D.State{_,_,xs}}: Some{Q.get(U32,xs,face,0)}

@unsafe
def place(+recipe: D.Recipe,state: W.U64,+world: E.World,+at: P.Position) -> S.Placement:
  match recipe:
    case D.Recipe{D.Settings{vines,_,_,_,ds,_,_,_,_},_,D.Surfaces{air,water,_,_,_,_,_,_}}:
      finish(begin(vines,test(air,Some{at},world),test(water,Some{at},world),ds,at,Work{state,recipe,world}))

@unsafe
def begin(vines: Bool,air: Bool,water: Bool,ds: List<&2,U32>,at: P.Position,work: Work) -> Growth:
  match vines air water work:
    case True{} True{} _ _: vines_at(ds,at,work)
    case True{} False{} _ _: Growth{work,False{}}
    case False{} False{} False{} _: Growth{work,False{}}
    case _ _ _ Work{state,recipe,world}: shuffled(Shuffle.shuffle(ds,state),at,recipe,world)

@unsafe
def vines_at(ds: List<&2,U32>,+at: P.Position,+work: Work) -> Growth:
  match ds work:
    case Nil{} _: Growth{work,False{}}
    case +d <> rest Work{_,D.Recipe{_,_,D.Surfaces{_,_,_,_,_,_,support,_}},world}:
      vine_support(test(Q.get(P.Predicate,support,d,P.Not{P.Always{}}),Some{at},world),d,rest,at,work)

@unsafe
def vine_support(ok: Bool,d: U32,rest: List<&2,U32>,+at: P.Position,+work: Work) -> Growth:
  match ok work:
    case False{} _: vines_at(rest,at,work)
    case True{} Work{_,D.Recipe{D.Settings{_,initial,_,_,_,_,_,_,_},states,_},_}: vine_state(added(D.find(states,initial),d),at,work)

@unsafe
def vine_state(value: Maybe<&2,U32>,at: P.Position,work: Work) -> Growth:
  match value:
    case None{}: Growth{work,False{}}
    case Some{material}: Growth{write(at,material,work),True{}}

@unsafe
def shuffled(result: Shuffle.Shuffled,+at: P.Position,+recipe: D.Recipe,+world: E.World) -> Growth:
  match result:
    case Shuffle.Shuffled{state,+ds}: first(growth(ds,at,Work{state,recipe,world}),ds,at)

@unsafe
def first(result: Growth,ds: List<&2,U32>,at: P.Position) -> Growth:
  match result:
    case Growth{_,True{}}: result
    case Growth{work,False{}}: search(ds,at,work)

@unsafe
def growth(ds: List<&2,U32>,+at: P.Position,+work: Work) -> Growth:
  match ds work:
    case Nil{} _: Growth{work,False{}}
    case +d <> rest Work{_,D.Recipe{_,_,D.Surfaces{_,_,_,on,_,_,_,_}},world}:
      neighbor_allowed(test(on,relative(at,d),world),d,rest,at,work)

# The first allowed neighbor ends an attempt, even if its face cannot attach.
@unsafe
def neighbor_allowed(ok: Bool,+d: U32,rest: List<&2,U32>,+at: P.Position,+work: Work) -> Growth:
  match ok work:
    case False{} _: growth(rest,at,work)
    case True{} Work{_,recipe,world}: growth_state(placed_state(recipe,world,at,d),d,at,work)

@unsafe
def growth_state(value: Maybe<&2,U32>,d: U32,+at: P.Position,work: Work) -> Growth:
  match value:
    case None{}: Growth{work,False{}}
    case Some{+material}: growth_written(write(at,material,work),at,d,material)

@unsafe
def growth_written(work: Work,+at: P.Position,+d: U32,+source: U32) -> Growth:
  match work:
    case Work{state,recipe,world}: spread_chance(R.next(state),at,d,source,recipe,world)

@unsafe
def spread_chance(draw: R.Draw,+at: P.Position,+d: U32,+source: U32,+recipe: D.Recipe,world: E.World) -> Growth:
  match draw recipe:
    case R.Draw{state,value} D.Recipe{D.Settings{_,_,_,_,_,_,p,_,_},_,_}:
      spread_selected(F32.is_lt(R.unit(value),p),at,d,source,Work{state,recipe,world})

@unsafe
def spread_selected(ok: Bool,+at: P.Position,+face: U32,+source: U32,+work: Work) -> Growth:
  match ok work:
    case False{} _: Growth{work,True{}}
    case True{} Work{state,recipe,world}: spread_shuffled(Shuffle.shuffle([0,1,2,3,4,5],state),at,face,source,recipe,world)

@unsafe
def spread_shuffled(result: Shuffle.Shuffled,+at: P.Position,+face: U32,+source: U32,+recipe: D.Recipe,world: E.World) -> Growth:
  match result recipe:
    case Shuffle.Shuffled{state,ds} D.Recipe{_,states,_}: source_faces(D.find(states,source),ds,at,face,Work{state,recipe,world})

@unsafe
def source_faces(row: Maybe<&2,D.State>,ds: List<&2,U32>,at: P.Position,face: U32,work: Work) -> Growth:
  match row:
    case None{}: Growth{work,True{}}
    case Some{D.State{_,mask,_}}: Growth{spread(ds,at,face,mask,work),True{}}

@unsafe
def spread(ds: List<&2,U32>,+at: P.Position,+face: U32,+mask: U32,+work: Work) -> Work:
  match ds:
    case Nil{}: work
    case +d <> rest: spread_direction(Bool.and(U32.is_ne(U32.div(d,2),U32.div(face,2)),Bool.not(D.bit(mask,d))),d,rest,at,face,mask,work)

@unsafe
def spread_direction(ok: Bool,+d: U32,rest: List<&2,U32>,+at: P.Position,+face: U32,mask: U32,+work: Work) -> Work:
  match ok work:
    case False{} _: spread(rest,at,face,mask,work)
    case True{} Work{_,D.Recipe{D.Settings{_,_,_,_,_,_,_,os,_},_,_},_}:
      spread_attempt(types(os,at,face,d,work),rest,at,face,mask)

@unsafe
def spread_attempt(result: Attempt,rest: List<&2,U32>,at: P.Position,face: U32,mask: U32) -> Work:
  match result:
    case Selected{work,True{}}: work
    case Selected{work,False{}}: spread(rest,at,face,mask,work)
    case Missing{work}: spread(rest,at,face,mask,work)

@unsafe
def types(os: List<&2,U32>,+at: P.Position,+face: U32,+d: U32,+work: Work) -> Attempt:
  match os:
    case Nil{}: Missing{work}
    case +order <> rest: type_position(order,rest,at,face,d,work)

@unsafe
def type_position(+order: U32,rest: List<&2,U32>,+at: P.Position,+face: U32,+d: U32,work: Work) -> Attempt:
  match order:
    case 0: type_test(Some{at},d,order,rest,at,face,d,work)
    case 1: type_test(relative(at,d),face,order,rest,at,face,d,work)
    case _: type_corner(relative(at,d),order,rest,at,face,d,work)

@unsafe
def type_corner(point: Maybe<&2,P.Position>,order: U32,rest: List<&2,U32>,+at: P.Position,+face: U32,+d: U32,work: Work) -> Attempt:
  match point:
    case None{}: types(rest,at,face,d,work)
    case Some{p}: type_test(relative(p,face),U32.xor(d,1),order,rest,at,face,d,work)

@unsafe
def replace(+recipe: D.Recipe,+world: E.World,+at: P.Position) -> Bool:
  match recipe:
    case D.Recipe{_,states,D.Surfaces{air,water,source,_,_,_,_,_}}:
      Bool.or(test(air,Some{at},world),Bool.or(D.present(state_at(states,world,at)),Bool.and(test(water,Some{at},world),test(source,Some{at},world))))

@unsafe
def spread_allowed(+recipe: D.Recipe,+world: E.World,+pos: P.Position,+new_face: U32,+order: U32,+at: P.Position) -> Bool:
  match recipe:
    case D.Recipe{D.Settings{_,_,_,_,_,_,_,_,False{}},_,_}: replace(recipe,world,pos)
    case D.Recipe{D.Settings{_,_,_,_,_,_,_,_,True{}},_,D.Surfaces{_,_,_,_,replaceable,support,_,sturdy}}:
      Bool.and(test(support,relative(pos,new_face),world),Bool.and(Bool.or(replace(recipe,world,pos),test(replaceable,Some{pos},world)),
        Bool.not(Bool.and(U32.is_eq(order,2),test(Q.get(P.Predicate,sturdy,new_face,P.Not{P.Always{}}),relative(at,U32.xor(new_face,1)),world)))))

@unsafe
def type_test(point: Maybe<&2,P.Position>,+new_face: U32,+order: U32,rest: List<&2,U32>,+at: P.Position,+face: U32,+d: U32,+work: Work) -> Attempt:
  match point work:
    case None{} _: types(rest,at,face,d,work)
    case Some{+pos} Work{_,recipe,world}: type_allowed(spread_allowed(recipe,world,pos,new_face,order,at),pos,new_face,rest,at,face,d,work)

@unsafe
def type_allowed(ok: Bool,+pos: P.Position,new_face: U32,rest: List<&2,U32>,+at: P.Position,+face: U32,+d: U32,+work: Work) -> Attempt:
  match ok work:
    case False{} _: types(rest,at,face,d,work)
    case True{} Work{_,recipe,world}: type_state(placed_state(recipe,world,pos,new_face),pos,rest,at,face,d,work)

@unsafe
def type_state(value: Maybe<&2,U32>,+pos: P.Position,rest: List<&2,U32>,at: P.Position,face: U32,d: U32,+work: Work) -> Attempt:
  match value work:
    case None{} _: types(rest,at,face,d,work)
    case Some{material} Work{_,_,world}: Selected{write(pos,material,work),inside(world,pos)}

@unsafe
def excluding(ds: List<&2,U32>,+excluded: U32,acc: List<&2,U32>) -> List<&2,U32>:
  match ds:
    case Nil{}: List.reverse(&2,U32,acc)
    case +d <> rest: excluded_direction(U32.is_eq(d,excluded),d,rest,excluded,acc)

@unsafe
def excluded_direction(skip: Bool,d: U32,rest: List<&2,U32>,excluded: U32,acc: List<&2,U32>) -> List<&2,U32>:
  match skip:
    case True{}: excluding(rest,excluded,acc)
    case False{}: excluding(rest,excluded,d <> acc)

# The registered game's search repeats the same origin-relative probe. Failed
# growth neither writes nor draws; retain each direction shuffle and probe once.
@unsafe
def search(ds: List<&2,U32>,+at: P.Position,+work: Work) -> Growth:
  match ds work:
    case Nil{} _: Growth{work,False{}}
    case +d <> rest Work{state,D.Recipe{D.Settings{_,_,_,_,original,_,_,_,_},_,_},_}:
      search_shuffled(Shuffle.shuffle(excluding(original,U32.xor(d,1),[]),state),relative(at,d),rest,at,work)

@unsafe
def search_shuffled(result: Shuffle.Shuffled,point: Maybe<&2,P.Position>,rest: List<&2,U32>,at: P.Position,work: Work) -> Growth:
  match result work:
    case Shuffle.Shuffled{state,ds} Work{_,recipe,world}: search_point(point,ds,rest,at,Work{state,recipe,world})

@unsafe
def search_point(point: Maybe<&2,P.Position>,ds: List<&2,U32>,rest: List<&2,U32>,at: P.Position,+work: Work) -> Growth:
  match point work:
    case None{} _: search(rest,at,work)
    case Some{+pos} Work{_,D.Recipe{_,+states,D.Surfaces{air,water,_,_,_,_,_,_}},+world}:
      search_available(Bool.or(test(air,Some{pos},world),Bool.or(test(water,Some{pos},world),D.present(state_at(states,world,pos)))),pos,ds,rest,at,work)

@unsafe
def search_available(ok: Bool,pos: P.Position,ds: List<&2,U32>,rest: List<&2,U32>,at: P.Position,work: Work) -> Growth:
  match ok:
    case False{}: search(rest,at,work)
    case True{}: search_result(growth(ds,pos,work),rest,at)

@unsafe
def search_result(result: Growth,rest: List<&2,U32>,at: P.Position) -> Growth:
  match result:
    case Growth{_,True{}}: result
    case Growth{work,False{}}: search(rest,at,work)
