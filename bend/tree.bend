import Base
import ./tree_data.bend as T
import ./tree_geometry.bend as G
import ./tree_foliage.bend as L
import ./tree_decorators.bend as V
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_checks.bend as C
import ./placement_random.bend as R
import ./material_columns_data.bend as MD
import ./density.bend as Q
import ./word64.bend as W
import ./noise.bend as N
import ./surface_plants.bend as S

type Visit is Data:
  Visit{at: P.Position,distance: U32}
type Connected is Data:
  Connected{leaves: List<&2,T.Write>,positions: E.Patch}

@unsafe
def flags(+world: E.World,+material: U32) -> U32:
  match world:
    case E.World{_,P.Policy{_,values,_,_,_,_,_},_}: Q.get(U32,values,material,0)

@unsafe
def ground(+material: Maybe<&2,U32>,+world: E.World,+at: P.Position,+snow: U32) -> Bool:
  match material:
    case None{}: False{}
    case Some{+m}: Bool.or(U32.is_ne(U32.and(flags(world,m),1),0),
      Bool.and(U32.is_eq(m,snow),below_soil(P.shift(at,P.Position{0,4294967294,0}),world)))

@unsafe
def below_soil(at: Maybe<&2,P.Position>,+world: E.World) -> Bool:
  match at:
    case None{}: False{}
    case Some{at}: soil_value(E.material(world,at),world)

@unsafe
def soil_value(material: Maybe<&2,U32>,+world: E.World) -> Bool:
  match material:
    case None{}: False{}
    case Some{m}: U32.is_ne(U32.and(flags(world,m),1),0)

@unsafe
def plan(+recipe: T.Recipe,+state: W.U64,+world: E.World,+at: P.Position) -> T.Plan:
  match recipe:
    case T.Recipe{_,_,_,_,_,_,_,_,_,_,_,_,_,snow}: ground_position(P.shift(at,P.Position{0,4294967295,0}),recipe,state,world,at,snow)

@unsafe
def ground_position(below: Maybe<&2,P.Position>,+recipe: T.Recipe,+state: W.U64,+world: E.World,+at: P.Position,+snow: U32) -> T.Plan:
  match below:
    case None{}: T.Plan{state,[],False{}}
    case Some{p}: grounded(ground(E.material(world,p),world,at,snow),recipe,state,world,at)

@unsafe
def grounded(ok: Bool,+recipe: T.Recipe,+state: W.U64,+world: E.World,+at: P.Position) -> T.Plan:
  match ok recipe:
    case False{} _: T.Plan{state,[],False{}}
    case True{} T.Recipe{_,_,+height,_,_,_,_,_,_,_,_,_,_,_}:
      height_first(G.below(state,U32.inc(Q.get(U32,height,1,0))),Q.get(U32,height,0,0),Q.get(U32,height,2,0),recipe,world,at)

@unsafe
def height_first(draw: R.Draw,+base: U32,+more: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match draw:
    case R.Draw{state,value}: height_second(G.below(state,U32.inc(more)),U32.add(base,R.low(value)),recipe,world,at)

@unsafe
def height_second(draw: R.Draw,+base: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match draw recipe:
    case R.Draw{state,value} T.Recipe{_,_,_,_,_,_,_,_,_,_,_,offset,_,_}:
      root_draw(T.range(offset,state),U32.max(1,U32.add(base,R.low(value))),recipe,world,at)

@unsafe
def root_draw(draw: R.Draw,+height: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match draw:
    case R.Draw{state,value}: root_position(P.shift(at,P.Position{0,R.low(value),0}),height,recipe,state,world,at)

@unsafe
def root_position(root: Maybe<&2,P.Position>,+height: U32,+recipe: T.Recipe,+state: W.U64,+world: E.World,+at: P.Position) -> T.Plan:
  match root:
    case None{}: T.Plan{state,[],False{}}
    case Some{+root}: room(P.shift(root,P.Position{0,U32.add(height,4),0}),root,height,recipe,state,world,at)

@unsafe
def room(top: Maybe<&2,P.Position>,+root: P.Position,+height: U32,+recipe: T.Recipe,+state: W.U64,+world: E.World,+at: P.Position) -> T.Plan:
  match top recipe world:
    case Some{P.Position{_,y,_}} T.Recipe{_,_,_,_,_,_,_,logs,_,_,_,_,_,_} E.World{source,_,_}:
      room_valid(E.inside_y(source,y),root,height,recipe,state,world,at,logs)
    case _ _ _: T.Plan{state,[],False{}}

@unsafe
def room_valid(ok: Bool,+root: P.Position,+height: U32,+recipe: T.Recipe,+state: W.U64,+world: E.World,+at: P.Position,logs: List<&2,T.State>) -> T.Plan:
  match ok:
    case False{}: T.Plan{state,[],False{}}
    case True{}: chosen_log(T.choose(logs,state),root,height,recipe,world,at)

@unsafe
def chosen_log(chosen: T.Chosen,+root: P.Position,+height: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match chosen recipe:
    case T.Chosen{state,log} T.Recipe{_,_,_,_,_,_,_,_,leaves,_,_,_,_,_}: chosen_leaf(T.choose(leaves,state),log,root,height,recipe,world,at)

@unsafe
def width(+shape: U32) -> U32:
  N.word(Bool.or(U32.is_eq(shape,2),Bool.or(U32.is_eq(shape,3),U32.is_eq(shape,6))),2,1)

@unsafe
def chosen_leaf(chosen: T.Chosen,+log: Q.Table<U32>,+root: P.Position,+height: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match chosen recipe:
    case T.Chosen{state,leaf} T.Recipe{+trunk,_,_,_,_,_,_,_,_,_,_,_,_,_}:
      geometry(G.trunk(trunk,height,width(trunk),state),log,leaf,root,height,recipe,world,at)

# Source heightmaps include water and lake levels. Earlier vegetation overlays
# are checked separately, so they cannot raise the terrain used for a new trunk.
@unsafe
def above_ground(+world: E.World,+at: P.Position) -> Bool:
  match world at:
    case E.World{source,policy,_} P.Position{x,y,z}: above_height(E.height(E.World{source,policy,E.Empty{}},x,z,1),y)

@unsafe
def above_height(height: Maybe<&2,U32>,+y: U32) -> Bool:
  match height:
    case None{}: False{}
    case Some{h}: Bool.not(G.signed_less(y,h))

@unsafe
def trunk_valid(xs: List<&2,G.Wood>,+world: E.World,+root: P.Position) -> Bool:
  match xs:
    case Nil{}: True{}
    case G.Wood{at,_} <> rest: trunk_position(P.shift(root,at),rest,world,root)

@unsafe
def trunk_position(at: Maybe<&2,P.Position>,rest: List<&2,G.Wood>,+world: E.World,+root: P.Position) -> Bool:
  match at:
    case None{}: False{}
    case Some{+at}: trunk_allowed(Bool.and(above_ground(world,at),replace_log(E.material(world,at),world)),rest,world,root)

# A tall 2x2 tree can have a thousand wood cells. Do not retain a pending Bool
# conjunction for every cell on the stock device's bounded evaluation stack.
@unsafe
def trunk_allowed(ok: Bool,rest: List<&2,G.Wood>,+world: E.World,+root: P.Position) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: trunk_valid(rest,world,root)

@unsafe
def replace_log(previous: Maybe<&2,U32>,+world: E.World) -> Bool:
  match previous:
    case None{}: False{}
    case Some{+m}: Bool.or(U32.is_zero(m),U32.is_ne(U32.and(flags(world,m),20),0))

@unsafe
def geometry(+geometry: G.Geometry,+log: Q.Table<U32>,+leaf: Q.Table<U32>,+root: P.Position,+height: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match geometry:
    case G.Geometry{state,+wood,_}: geometry_valid(trunk_valid(wood,world,root),geometry,log,leaf,root,height,recipe,world,at,state)

@unsafe
def geometry_valid(ok: Bool,geometry: G.Geometry,+log: Q.Table<U32>,+leaf: Q.Table<U32>,+root: P.Position,+height: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position,+state: W.U64) -> T.Plan:
  match ok geometry recipe:
    case False{} _ _: T.Plan{state,[],False{}}
    case True{} G.Geometry{state,wood,crowns} T.Recipe{_,_,_,radius,_,_,_,_,_,_,_,_,_,_}:
      radius_draw(T.range(radius,state),wood,crowns,log,leaf,root,height,recipe,world,at)

@unsafe
def radius_draw(draw: R.Draw,+wood: List<&2,G.Wood>,+crowns: List<&2,G.Crown>,+log: Q.Table<U32>,+leaf: Q.Table<U32>,+root: P.Position,+height: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match draw recipe:
    case R.Draw{state,value} T.Recipe{_,foliage,_,_,offset,_,_,_,_,_,_,_,_,_}:
      offset_draw(T.range(offset,state),G.clamp(R.low(value),N.word(U32.is_eq(foliage,8),0,1),4),wood,crowns,log,leaf,root,height,recipe,world,at)

@unsafe
def offset_draw(draw: R.Draw,+radius: U32,+wood: List<&2,G.Wood>,+crowns: List<&2,G.Crown>,+log: Q.Table<U32>,+leaf: Q.Table<U32>,+root: P.Position,+height: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match draw recipe:
    case R.Draw{state,+value} T.Recipe{_,+foliage,_,_,_,fh,th,_,_,_,_,_,_,_}:
      foliage_height(U32.is_eq(foliage,3),state,fh,th,R.low(value),radius,wood,crowns,log,leaf,root,height,recipe,world,at)

@unsafe
def foliage_height(spruce: Bool,+state: W.U64,fh: Q.Table<U32>,th: Q.Table<U32>,+offset: U32,+radius: U32,+wood: List<&2,G.Wood>,+crowns: List<&2,G.Crown>,+log: Q.Table<U32>,+leaf: Q.Table<U32>,+root: P.Position,+height: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match spruce:
    case True{}: foliage_draw(T.range(th,state),True{},offset,radius,wood,crowns,log,leaf,root,height,recipe,world,at)
    case False{}: foliage_draw(T.range(fh,state),False{},offset,radius,wood,crowns,log,leaf,root,height,recipe,world,at)

@unsafe
def foliage_draw(draw: R.Draw,+spruce: Bool,+offset: U32,+radius: U32,+wood: List<&2,G.Wood>,+crowns: List<&2,G.Crown>,+log: Q.Table<U32>,+leaf: Q.Table<U32>,+root: P.Position,+height: U32,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match draw recipe:
    case R.Draw{state,+value} T.Recipe{_,+foliage,_,_,_,_,_,_,_,_,_,_,_,_}:
      +fh = U32.min(U32.add(height,2),G.max(N.word(spruce,U32.sub(height,R.low(value)),R.low(value)),N.word(spruce,4,N.word(U32.is_eq(foliage,5),6,2))))
      foliated(L.crowns(crowns,L.Shape{foliage,radius,offset,fh},state,[]),wood,log,leaf,root,recipe,world,at)

@unsafe
def foliated(result: L.Leaves,+wood: List<&2,G.Wood>,+log: Q.Table<U32>,+leaf: Q.Table<U32>,+root: P.Position,+recipe: T.Recipe,+world: E.World,+at: P.Position) -> T.Plan:
  match result recipe:
    case L.Leaves{state,positions} T.Recipe{_,_,_,_,_,_,_,_,_,_,_,_,decorators,_}:
      +connected = connect(wood,root,leaf_positions(positions,root,world,E.Empty{}),leaf)
      connected_plan(connected,wood,log,root,recipe,world,at,state,decorators)

@unsafe
def leaf_positions(xs: List<&2,P.Position>,+root: P.Position,+world: E.World,acc: E.Patch) -> E.Patch:
  match xs:
    case Nil{}: acc
    case at <> rest: leaf_position(P.shift(root,at),rest,root,world,acc)

@unsafe
def leaf_position(at: Maybe<&2,P.Position>,rest: List<&2,P.Position>,+root: P.Position,+world: E.World,acc: E.Patch) -> E.Patch:
  match at:
    case None{}: leaf_positions(rest,root,world,acc)
    case Some{+at}: leaf_above(above_ground(world,at),at,rest,root,world,acc)

@unsafe
def leaf_above(ok: Bool,+at: P.Position,rest: List<&2,P.Position>,+root: P.Position,+world: E.World,acc: E.Patch) -> E.Patch:
  match ok:
    case False{}: leaf_positions(rest,root,world,acc)
    case True{}: leaf_positions(rest,root,world,E.insert(acc,at,1))

@unsafe
def connect(wood: List<&2,G.Wood>,+root: P.Position,remaining: E.Patch,+leaf: Q.Table<U32>) -> Connected:
  logs(wood,root,remaining,E.Empty{},[],leaf)

@unsafe
def logs(xs: List<&2,G.Wood>,+root: P.Position,remaining: E.Patch,+seen: E.Patch,queue: List<&2,Visit>,+leaf: Q.Table<U32>) -> Connected:
  match xs:
    case Nil{}: breadth(List.reverse(&2,Visit,queue),[],remaining,seen,[],leaf)
    case G.Wood{at,_} <> rest: log_position(P.shift(root,at),rest,root,remaining,seen,queue,leaf)

@unsafe
def log_position(at: Maybe<&2,P.Position>,rest: List<&2,G.Wood>,+root: P.Position,remaining: E.Patch,+seen: E.Patch,queue: List<&2,Visit>,+leaf: Q.Table<U32>) -> Connected:
  match at:
    case None{}: logs(rest,root,remaining,seen,queue,leaf)
    case Some{+at}: log_seen(member(seen,at),at,rest,root,remaining,seen,queue,leaf)

@unsafe
def member(+set: E.Patch,+at: P.Position) -> Bool:
  member_value(E.find(set,at))

@unsafe
def member_value(value: Maybe<&2,U32>) -> Bool:
  match value:
    case Some{1}: True{}
    case _: False{}

@unsafe
def log_seen(known: Bool,+at: P.Position,rest: List<&2,G.Wood>,+root: P.Position,remaining: E.Patch,seen: E.Patch,queue: List<&2,Visit>,+leaf: Q.Table<U32>) -> Connected:
  match known:
    case True{}: logs(rest,root,remaining,seen,queue,leaf)
    case False{}: logs(rest,root,E.insert(remaining,at,0),E.insert(seen,at,1),Visit{at,0} <> queue,leaf)

@unsafe
def breadth(queue: List<&2,Visit>,back: List<&2,Visit>,remaining: E.Patch,occupied: E.Patch,writes: List<&2,T.Write>,+leaf: Q.Table<U32>) -> Connected:
  match queue back:
    case Nil{} Nil{}: Connected{List.reverse(&2,T.Write,writes),occupied}
    case Nil{} _: breadth(List.reverse(&2,Visit,back),[],remaining,occupied,writes,leaf)
    case Visit{at,+distance} <> rest _: neighbors(N.word(U32.is_ge(distance,6),6,0),at,U32.inc(distance),rest,back,remaining,occupied,writes,leaf)

@unsafe
def neighbor(+side: U32) -> P.Position:
  match side:
    case 2: P.Position{0,1,0}
    case 3: P.Position{0,4294967295,0}
    case _: G.direction(N.word(U32.is_ge(side,4),U32.sub(side,2),side))

@unsafe
def neighbors(+side: U32,+at: P.Position,+distance: U32,queue: List<&2,Visit>,back: List<&2,Visit>,remaining: E.Patch,occupied: E.Patch,writes: List<&2,T.Write>,+leaf: Q.Table<U32>) -> Connected:
  match side:
    case 6: breadth(queue,back,remaining,occupied,writes,leaf)
    case _: neighbor_position(P.shift(at,neighbor(side)),side,at,distance,queue,back,remaining,occupied,writes,leaf)

@unsafe
def neighbor_position(next: Maybe<&2,P.Position>,+side: U32,+at: P.Position,+distance: U32,queue: List<&2,Visit>,back: List<&2,Visit>,+remaining: E.Patch,occupied: E.Patch,writes: List<&2,T.Write>,+leaf: Q.Table<U32>) -> Connected:
  match next:
    case None{}: neighbors(U32.inc(side),at,distance,queue,back,remaining,occupied,writes,leaf)
    case Some{+next}: neighbor_leaf(member(remaining,next),next,side,at,distance,queue,back,remaining,occupied,writes,leaf)

@unsafe
def neighbor_leaf(found: Bool,+next: P.Position,+side: U32,+at: P.Position,+distance: U32,queue: List<&2,Visit>,back: List<&2,Visit>,remaining: E.Patch,occupied: E.Patch,writes: List<&2,T.Write>,+leaf: Q.Table<U32>) -> Connected:
  match found:
    case False{}: neighbors(U32.inc(side),at,distance,queue,back,remaining,occupied,writes,leaf)
    case True{}: neighbors(U32.inc(side),at,distance,queue,Visit{next,distance} <> back,E.insert(remaining,next,0),E.insert(occupied,next,1),T.Write{next,Q.get(U32,leaf,U32.sub(distance,1),0),2,0} <> writes,leaf)

@unsafe
def wood_writes(xs: List<&2,G.Wood>,+root: P.Position,+log: Q.Table<U32>,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  match xs:
    case Nil{}: List.reverse(&2,T.Write,acc)
    case G.Wood{offset,axis} <> rest: wood_position(P.shift(root,offset),axis,rest,root,log,acc)

@unsafe
def wood_position(at: Maybe<&2,P.Position>,+axis: U32,rest: List<&2,G.Wood>,+root: P.Position,+log: Q.Table<U32>,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  match at:
    case None{}: wood_writes(rest,root,log,acc)
    case Some{at}: wood_writes(rest,root,log,T.Write{at,Q.get(U32,log,axis,0),1,0} <> acc)

@unsafe
def soil(+i: U32,+width: U32,+material: U32,+at: P.Position,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  soil_more(Bool.and(U32.is_ne(material,0),U32.is_lt(i,U32.mul(width,width))),i,width,material,at,acc)

@unsafe
def soil_more(more: Bool,+i: U32,+width: U32,+material: U32,+at: P.Position,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  match more:
    case False{}: List.reverse(&2,T.Write,acc)
    case True{}: soil_position(P.shift(at,P.Position{U32.mod(i,width),4294967295,U32.div(i,width)}),i,width,material,at,acc)

@unsafe
def soil_position(p: Maybe<&2,P.Position>,+i: U32,+width: U32,+material: U32,+at: P.Position,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  match p:
    case None{}: soil(U32.inc(i),width,material,at,acc)
    case Some{p}: soil(U32.inc(i),width,material,at,T.Write{p,material,0,0} <> acc)

@unsafe
def roots(+i: U32,+material: U32,+root: P.Position,+world: E.World,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  roots_more(Bool.and(U32.is_ne(material,0),U32.is_lt(i,16)),i,material,root,world,acc)

@unsafe
def roots_more(more: Bool,+i: U32,+material: U32,+root: P.Position,+world: E.World,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  match more:
    case False{}: List.reverse(&2,T.Write,acc)
    case True{}: roots_direction(G.direction(U32.div(i,4)),i,material,root,world,acc)

@unsafe
def roots_direction(dir: P.Position,+i: U32,+material: U32,+root: P.Position,+world: E.World,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  match dir:
    case P.Position{x,_,z}: roots_position(P.shift(root,P.Position{U32.mul(x,U32.mod(i,4)),U32.sub(0,U32.div(U32.mod(i,4),2)),U32.mul(z,U32.mod(i,4))}),i,material,root,world,acc)

@unsafe
def roots_position(p: Maybe<&2,P.Position>,+i: U32,+material: U32,+root: P.Position,+world: E.World,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  match p world:
    case None{} _: roots(U32.inc(i),material,root,world,acc)
    case Some{+p} E.World{source,_,_}: roots_column(column_at(source,p),p,i,material,root,world,acc)

@unsafe
def column_at(+source: MD.Grid,at: P.Position) -> Maybe<&2,MD.Column>:
  match at:
    case P.Position{x,_,z}: E.column(source,x,z)

@unsafe
def roots_column(column: Maybe<&2,MD.Column>,+p: P.Position,+i: U32,+material: U32,+root: P.Position,+world: E.World,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  match column p:
    case None{} _: roots(U32.inc(i),material,root,world,acc)
    case Some{MD.Column{y,_,_,_,_,_,_,_,_,_}} P.Position{x,top,z}: root_blocks(y,top,x,z,i,material,root,world,acc)

@unsafe
def root_blocks(+y: U32,+top: U32,+x: U32,+z: U32,+i: U32,+material: U32,+root: P.Position,+world: E.World,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  root_block(Bool.not(G.signed_less(top,y)),y,top,x,z,i,material,root,world,acc)

@unsafe
def root_block(more: Bool,+y: U32,+top: U32,+x: U32,+z: U32,+i: U32,+material: U32,+root: P.Position,+world: E.World,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  match more:
    case False{}: roots(U32.inc(i),material,root,world,acc)
    case True{}: root_blocks(U32.inc(y),top,x,z,i,material,root,world,T.Write{P.Position{x,y,z},material,1,0} <> acc)

@unsafe
def connected_plan(connected: Connected,+wood: List<&2,G.Wood>,+log: Q.Table<U32>,+root: P.Position,+recipe: T.Recipe,+world: E.World,+at: P.Position,+state: W.U64,decorators: List<&2,T.Decorator>) -> T.Plan:
  match connected recipe:
    case Connected{+leaves,occupied} T.Recipe{trunk,_,_,_,_,_,_,_,_,material,root_material,_,_,_}:
      +writes = append(soil(0,width(trunk),material,at,[]),append(wood_writes(wood,root,log,[]),append(roots(0,root_material,root,world,[]),leaves)))
      decorated(decorators,wood,root,leaves,occupied,state,world,writes)

@unsafe
def decorated(decorators: List<&2,T.Decorator>,+wood: List<&2,G.Wood>,+root: P.Position,+leaves: List<&2,T.Write>,+occupied: E.Patch,+state: W.U64,+world: E.World,writes: List<&2,T.Write>) -> T.Plan:
  match decorators:
    case Nil{}: T.Plan{state,writes,True{}}
    case _: decorated_seed(R.next(state),decorators,wood,root,leaves,occupied,world,writes)

@unsafe
def decorated_seed(draw: R.Draw,decorators: List<&2,T.Decorator>,+wood: List<&2,G.Wood>,+root: P.Position,+leaves: List<&2,T.Write>,+occupied: E.Patch,+world: E.World,writes: List<&2,T.Write>) -> T.Plan:
  match draw:
    case R.Draw{state,value}: T.Plan{state,append(writes,V.decorate(decorators,wood,root,leaves,occupied,value,world)),True{}}

@unsafe
def replay(+writes: List<&2,T.Write>,+phase: U32,+snow: U32,+world: E.World) -> E.World:
  match phase:
    case 5: world
    case _: replay(writes,U32.inc(phase),snow,replay_phase(writes,phase,snow,world))

@unsafe
def replay_phase(xs: List<&2,T.Write>,+phase: U32,+snow: U32,+world: E.World) -> E.World:
  match xs:
    case Nil{}: world
    case +write <> rest: replay_one(same_role(write,phase),write,rest,phase,snow,world)

@unsafe
def same_role(write: T.Write,+phase: U32) -> Bool:
  match write:
    case T.Write{_,_,role,_}: U32.is_eq(role,phase)

@unsafe
def replay_one(selected: Bool,+write: T.Write,rest: List<&2,T.Write>,+phase: U32,+snow: U32,+world: E.World) -> E.World:
  match selected write:
    case False{} _: replay_phase(rest,phase,snow,world)
    case True{} T.Write{at,_,_,_}: replay_value(E.material(world,at),write,rest,phase,snow,world)

@unsafe
def replay_value(previous: Maybe<&2,U32>,+write: T.Write,rest: List<&2,T.Write>,+phase: U32,+snow: U32,+world: E.World) -> E.World:
  match previous:
    case None{}: replay_phase(rest,phase,snow,world)
    case Some{+m}: replay_allowed(replaces(phase,m,snow,world),write,rest,phase,snow,world)

@unsafe
def replaces(role: U32,+previous: U32,+snow: U32,+world: E.World) -> Bool:
  match role:
    case 0: Bool.or(U32.is_eq(previous,snow),U32.is_ne(U32.and(flags(world,previous),1),0))
    case 1: replace_log(Some{previous},world)
    case _: U32.is_zero(previous)

@unsafe
def replay_allowed(ok: Bool,+write: T.Write,rest: List<&2,T.Write>,+phase: U32,+snow: U32,+world: E.World) -> E.World:
  match ok write:
    case False{} _: replay_phase(rest,phase,snow,world)
    case True{} T.Write{at,material,role,side}: replay_supported(Bool.or(U32.is_lt(role,3),V.supported(world,at,material,side,U32.is_eq(role,3))),write,rest,phase,snow,world)

@unsafe
def replay_supported(ok: Bool,write: T.Write,rest: List<&2,T.Write>,+phase: U32,+snow: U32,+world: E.World) -> E.World:
  match ok write:
    case False{} _: replay_phase(rest,phase,snow,world)
    case True{} T.Write{at,material,_,_}: replay_phase(rest,phase,snow,E.put(world,at,material))

@unsafe
def placed(result: T.Plan,+snow: U32,+world: E.World) -> S.Placement:
  match result:
    case T.Plan{state,writes,ok}: S.Placement{state,replay(writes,0,snow,world),ok}

@unsafe
def place(+recipe: T.Recipe,+state: W.U64,+world: E.World,+at: P.Position) -> S.Placement:
  match recipe:
    case T.Recipe{_,_,_,_,_,_,_,_,_,_,_,_,_,snow}: placed(plan(recipe,state,world,at),snow,world)

# Base List.append retains one device continuation per left-hand element.
# Crowns/roots can contain thousands of writes; reverse-and-prepend keeps the
# ordered concatenation linear with constant evaluation-stack depth.
@unsafe
def prepend(xs: List<&2,T.Write>,acc: List<&2,T.Write>) -> List<&2,T.Write>:
  match xs:
    case Nil{}: acc
    case x <> rest: prepend(rest,x <> acc)

@unsafe
def append(xs: List<&2,T.Write>,rest: List<&2,T.Write>) -> List<&2,T.Write>:
  prepend(List.reverse(&2,T.Write,xs),rest)
