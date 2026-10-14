import Base
import ./structure_registry.bend as M
import ./structure_values.bend as V
import ./registry.bend as Registry
import ./registry_values.bend as RV
import ./registry_density.bend as D
import ./density.bend as Q
import ./structure_random.bend as R
import ./structure_candidates.bend as C
import ./structure_geometry.bend as G
import ./placement_data.bend as P
import ./word64.bend as W
import ./numbers.bend as N

# CPU execution for irregular ordered template rules. No Java/Rust callbacks.
type Environment is Data:
  Environment{model: M.Model,names: Q.Table<U32>}
type Processor is Data:
  Noop{}
  Chain{items: List<&2,Processor>}
  Rot{ids: Maybe<&2,M.Node>,probability: Registry.Value}
  Protected{ids: M.Node}
  Capped{id: U32,limit: U32,delegate: Processor}
  Rules{items: List<&2,Rule>}
type Program is Data:
  Program{processor: Processor,counters: U32}
type Compiled is Data:
  Compiled{processor: Processor,next: U32}
type Predicate is Data:
  Predicate{kind: U32,value: M.Node,probability: Maybe<&2,Registry.Value>}
type Position is Data:
  Always{}
  Never{}
  Linear{axis: U32,min: F32,max: F32,low: F32,high: F32}
type Rule is Data:
  Rule{input: Predicate,location: Predicate,position: Position,output: M.Node,entity: M.Node,modifier: U32,append: M.Node,loot: U32}
type Count is Data:
  Count{id: U32,value: U32}
type State is Data:
  State{material: U32,tag: M.Node,random: W.U64,counts: List<&2,Count>,keep: Bool}
type Context is Data:
  Context{position: P.Position,origin: P.Position,world: U32,rotation: U32}
type Decision is Data:
  Decision{state: State,matches: Bool}

@unsafe
def keys(-A: Type,doc: Registry.Document,next: Registry.Document -> D.Keys -> A) -> A:
  D.resolved(A,["processor_type", "processors", "block_rot", "protected_blocks", "capped", "rule", "retina_rottable", "integrity", "retina_protected", "limit", "value", "delegate", "rules", "input_predicate", "location_predicate", "position_predicate", "output_state", "retina_rotations", "retina_entity", "block_entity_modifier", "type", "clear", "append_static", "retina_nbt", "loot_table", "predicate_type", "always_true", "block_match", "random_block_match", "blockstate_match", "random_blockstate_match", "tag_match", "block", "block_state", "retina_matching", "probability", "linear_pos", "axis_aligned_linear_pos", "axis", "x", "y", "z", "min_dist", "max_dist", "min_chance", "max_chance", "id", "materials", "LootTable", "minecraft:processor_type", "minecraft:processors", "minecraft:block_rot", "minecraft:protected_blocks", "minecraft:capped", "minecraft:rule", "minecraft:retina_rottable", "minecraft:integrity", "minecraft:retina_protected", "minecraft:limit", "minecraft:value", "minecraft:delegate", "minecraft:rules", "minecraft:input_predicate", "minecraft:location_predicate", "minecraft:position_predicate", "minecraft:output_state", "minecraft:retina_rotations", "minecraft:retina_entity", "minecraft:block_entity_modifier", "minecraft:type", "minecraft:clear", "minecraft:append_static", "minecraft:retina_nbt", "minecraft:loot_table", "minecraft:predicate_type", "minecraft:always_true", "minecraft:block_match", "minecraft:random_block_match", "minecraft:blockstate_match", "minecraft:random_blockstate_match", "minecraft:tag_match", "minecraft:block", "minecraft:block_state", "minecraft:retina_matching", "minecraft:probability", "minecraft:linear_pos", "minecraft:axis_aligned_linear_pos", "minecraft:axis", "minecraft:x", "minecraft:y", "minecraft:z", "minecraft:min_dist", "minecraft:max_dist", "minecraft:min_chance", "minecraft:max_chance", "minecraft:id", "minecraft:materials", "minecraft:LootTable"],doc,[],next)

@unsafe
def model(+env: Environment) -> M.Model:
  match env:
    case Environment{model,_}: model

@unsafe
def field(+env: Environment,+node: M.Node,+key: Nat) -> M.Node:
  M.get(model(env),node,key)

@unsafe
def kind(+env: Environment,+node: M.Node,+key: Nat) -> Bool:
  Bool.or(M.named(model(env),node,key),M.named(model(env),node,Nat.add(key,49n)))

@unsafe
def name(+env: Environment,+id: U32) -> U32:
  match env:
    case Environment{_,+names}: name_valid(U32.is_lt(id,Q.table_size(U32,names)),names,id)

@unsafe
def name_valid(valid: Bool,+names: Q.Table<U32>,+id: U32) -> U32:
  match valid:
    case True{}: Q.get(U32,names,id,4294967295)
    case False{}: 4294967295

@unsafe
def number(+node: M.Node,+default: F32) -> F32:
  numeric(M.value(node),default)

@unsafe
def numeric(value: Registry.Value,default: F32) -> F32:
  match value:
    case Registry.Value{3,hi,lo,_}: N.integer(hi,lo)
    case Registry.Value{4,hi,lo,_}: N.double(hi,lo)
    case _: default

@unsafe
def probability(+node: M.Node) -> Registry.Value:
  present_probability(U32.is_eq(M.tag(node),0),M.value(node))

@unsafe
def present_probability(missing: Bool,value: Registry.Value) -> Registry.Value:
  match missing:
    case True{}: Registry.Value{3,0,1,0}
    case False{}: value

@unsafe
def optional(+node: M.Node) -> Maybe<&2,M.Node>:
  optional_present(U32.is_ne(M.tag(node),0),node)

@unsafe
def optional_present(present: Bool,node: M.Node) -> Maybe<&2,M.Node>:
  match present:
    case False{}: None{}
    case True{}: Some{node}

@unsafe
def compile(+env: Environment,+node: M.Node) -> Program:
  compiled(prepare(env,node,0))

@unsafe
def compiled(value: Compiled) -> Program:
  match value:
    case Compiled{p,n}: Program{p,n}

@unsafe
def prepare(+env: Environment,+node: M.Node,+id: U32) -> Compiled:
  match node:
    case M.Array{_,_}: compile_list(env,node,0,M.count(node),id,[])
    case _: prepare_object(env,node,id,field(env,node,1n))

@unsafe
def prepare_object(+env: Environment,+node: M.Node,+id: U32,+list: M.Node) -> Compiled:
  prepare_nested(U32.is_ne(M.tag(list),0),env,node,id,list)

@unsafe
def prepare_nested(nested: Bool,+env: Environment,+node: M.Node,+id: U32,list: M.Node) -> Compiled:
  match nested:
    case True{}: prepare(env,list,id)
    case False{}: prepare_kind(env,node,id,field(env,node,0n))

@unsafe
def prepare_kind(+env: Environment,+node: M.Node,+id: U32,+ty: M.Node) -> Compiled:
  prepare_rot(kind(env,ty,2n),env,node,id,ty)

@unsafe
def prepare_rot(yes: Bool,+env: Environment,+node: M.Node,+id: U32,+ty: M.Node) -> Compiled:
  match yes:
    case True{}: Compiled{Rot{optional(field(env,node,6n)),probability(field(env,node,7n))},id}
    case False{}: prepare_protected(kind(env,ty,3n),env,node,id,ty)

@unsafe
def prepare_protected(yes: Bool,+env: Environment,+node: M.Node,+id: U32,+ty: M.Node) -> Compiled:
  match yes:
    case True{}: Compiled{Protected{field(env,node,8n)},id}
    case False{}: prepare_capped(kind(env,ty,4n),env,node,id,ty)

@unsafe
def prepare_capped(yes: Bool,+env: Environment,+node: M.Node,+id: U32,+ty: M.Node) -> Compiled:
  match yes:
    case True{}: cap(prepare(env,field(env,node,11n),U32.inc(id)),id,M.int(field(env,node,9n),M.int(field(env,field(env,node,9n),10n),1)))
    case False{}: prepare_rules(kind(env,ty,5n),env,node,id)

@unsafe
def cap(value: Compiled,id: U32,limit: U32) -> Compiled:
  match value:
    case Compiled{p,n}: Compiled{Capped{id,limit,p},n}

@unsafe
def prepare_rules(yes: Bool,+env: Environment,+node: M.Node,id: U32) -> Compiled:
  match yes:
    case True{}: Compiled{Rules{rules(env,field(env,node,12n),0,M.count(field(env,node,12n)))},id}
    case False{}: Compiled{Noop{},id}

@unsafe
def compile_list(+env: Environment,+node: M.Node,+i: U32,n: U32,+id: U32,+acc: List<&2,Processor>) -> Compiled:
  match n:
    case 0: Compiled{Chain{List.reverse(&2,Processor,acc)},id}
    case _: compile_next(prepare(env,M.item(node,i),id),env,node,U32.inc(i),U32.sub(n,1),acc)

@unsafe
def compile_next(value: Compiled,+env: Environment,+node: M.Node,+i: U32,n: U32,+acc: List<&2,Processor>) -> Compiled:
  match value:
    case Compiled{p,id}: compile_list(env,node,i,n,id,p <> acc)

@unsafe
def predicate(+env: Environment,+node: M.Node) -> Predicate:
  predicate_kind(env,node,field(env,node,25n))

@unsafe
def predicate_kind(+env: Environment,+node: M.Node,+ty: M.Node) -> Predicate:
  predicate_always(Bool.or(U32.is_eq(M.tag(ty),0),kind(env,ty,26n)),env,node,ty)

@unsafe
def predicate_always(yes: Bool,+env: Environment,+node: M.Node,+ty: M.Node) -> Predicate:
  match yes:
    case True{}: Predicate{0,M.empty(),None{}}
    case False{}: predicate_block(Bool.or(kind(env,ty,27n),kind(env,ty,28n)),env,node,ty)

@unsafe
def random_probability(random: Bool,value: Registry.Value) -> Maybe<&2,Registry.Value>:
  match random:
    case False{}: None{}
    case True{}: Some{value}

@unsafe
def predicate_block(yes: Bool,+env: Environment,+node: M.Node,+ty: M.Node) -> Predicate:
  match yes:
    case True{}: Predicate{1,field(env,node,32n),random_probability(kind(env,ty,28n),probability(field(env,node,35n)))}
    case False{}: predicate_state(Bool.or(kind(env,ty,29n),kind(env,ty,30n)),env,node,ty)

@unsafe
def predicate_state(yes: Bool,+env: Environment,+node: M.Node,+ty: M.Node) -> Predicate:
  match yes:
    case True{}: Predicate{2,field(env,field(env,node,33n),17n),random_probability(kind(env,ty,30n),probability(field(env,node,35n)))}
    case False{}: predicate_tag(kind(env,ty,31n),env,node)

@unsafe
def predicate_tag(yes: Bool,+env: Environment,+node: M.Node) -> Predicate:
  match yes:
    case True{}: Predicate{3,field(env,node,34n),None{}}
    case False{}: Predicate{4,M.empty(),None{}}

@unsafe
def position(+env: Environment,+node: M.Node) -> Position:
  position_always(Bool.or(U32.is_eq(M.tag(field(env,node,25n)),0),kind(env,field(env,node,25n),26n)),env,node)

@unsafe
def position_always(yes: Bool,+env: Environment,+node: M.Node) -> Position:
  match yes:
    case True{}: Always{}
    case False{}: position_linear(Bool.or(kind(env,field(env,node,25n),36n),kind(env,field(env,node,25n),37n)),env,node)

@unsafe
def position_linear(yes: Bool,+env: Environment,+node: M.Node) -> Position:
  match yes:
    case False{}: Never{}
    case True{}: Linear{axis(kind(env,field(env,node,25n),37n),env,field(env,node,38n)),number(field(env,node,42n),0.0),number(field(env,node,43n),100.0),number(field(env,node,44n),0.0),number(field(env,node,45n),0.0)}

@unsafe
def axis(aligned: Bool,+env: Environment,+node: M.Node) -> U32:
  match aligned:
    case False{}: 3
    case True{}: U32.add(W.flag(Bool.not(M.named(model(env),node,39n))),W.flag(M.named(model(env),node,41n)))

@unsafe
def modifier(+env: Environment,+node: M.Node) -> U32:
  U32.add(W.flag(kind(env,node,21n)),U32.mul(2,W.flag(kind(env,node,22n))))

@unsafe
def rules(+env: Environment,+node: M.Node,+i: U32,n: U32) -> List<&2,Rule>:
  match n:
    case 0: []
    case _: rule(env,M.item(node,i)) <> rules(env,node,U32.inc(i),U32.sub(n,1))

@unsafe
def rule(+env: Environment,+node: M.Node) -> Rule:
  Rule{predicate(env,field(env,node,13n)),predicate(env,field(env,node,14n)),position(env,field(env,node,15n)),
    field(env,field(env,node,16n),17n),field(env,field(env,node,16n),18n),modifier(env,field(env,field(env,node,19n),20n)),
    field(env,field(env,node,19n),23n),M.text(field(env,field(env,node,19n),24n))}

@unsafe
def contains(+ids: M.Node,+material: U32,+i: U32,n: U32) -> Bool:
  match n:
    case 0: False{}
    case _: Bool.or(U32.is_eq(M.int(M.item(ids,i),4294967295),material),contains(ids,material,U32.inc(i),U32.sub(n,1)))

@unsafe
def matches(+predicate: Predicate,+env: Environment,+material: U32,+rotation: U32,+state: State) -> Decision:
  match predicate:
    case Predicate{kind,value,p}: matched(matches_kind(kind,value,env,material,rotation),p,state)

@unsafe
def matches_kind(kind: U32,+value: M.Node,+env: Environment,+material: U32,+rotation: U32) -> Bool:
  match kind:
    case 0: True{}
    case 1: Bool.and(U32.is_ne(M.text(value),4294967295),U32.is_eq(name(env,material),M.text(value)))
    case 2: U32.is_eq(material,M.int(M.item(value,rotation),4294967295))
    case 3: contains(value,material,0,M.count(value))
    case _: False{}

@unsafe
def matched(yes: Bool,p: Maybe<&2,Registry.Value>,+state: State) -> Decision:
  match yes p:
    case False{} _: Decision{state,False{}}
    case True{} None{}: Decision{state,True{}}
    case True{} Some{value}: chance(state,C.threshold(value))

@unsafe
def random(+state: State) -> W.U64:
  match state:
    case State{_,_,r,_,_}: r

@unsafe
def chance(+state: State,+threshold: W.U64) -> Decision:
  chance_draw(R.bits(random(state),24),state,threshold)

@unsafe
def chance_draw(draw: R.Draw,state: State,threshold: W.U64) -> Decision:
  match draw state:
    case R.Draw{r,v} State{m,tag,_,counts,keep}: Decision{State{m,tag,r,counts,keep},N.greater(threshold,W.shl(W.U64{0,v},29))}

# Rot's comparison is <= integrity; predicates use < probability. Advance a
# positive F64 by one ULP before ceil so an exactly equal 24-bit draw survives.
@unsafe
def inclusive(+v: Registry.Value) -> W.U64:
  match v:
    case Registry.Value{4,+hi,+lo,_}: inclusive_float(Bool.and(U32.is_zero(U32.and(hi,2147483647)),U32.is_zero(lo)),hi,lo)
    case Registry.Value{3,0,0,_}: W.U64{0,1}
    case _: C.threshold(v)

@unsafe
def inclusive_float(zero: Bool,hi: U32,lo: U32) -> W.U64:
  match zero:
    case True{}: W.U64{0,1}
    case False{}: inclusive_bits(W.add(W.U64{hi,lo},W.U64{0,1}))

@unsafe
def inclusive_bits(value: W.U64) -> W.U64:
  match value:
    case W.U64{hi,lo}: C.threshold(Registry.Value{4,hi,lo,0})

@unsafe
def count(+counts: List<&2,Count>,+id: U32) -> U32:
  match counts:
    case Nil{}: 0
    case Count{n,value} <> rest: count_found(U32.is_eq(n,id),value,rest,id)

@unsafe
def count_found(found: Bool,value: U32,rest: List<&2,Count>,id: U32) -> U32:
  match found:
    case True{}: value
    case False{}: count(rest,id)

@unsafe
def increment(+counts: List<&2,Count>,+id: U32) -> List<&2,Count>:
  match counts:
    case Nil{}: [Count{id,1}]
    case Count{+n,+value} <> rest: increment_found(U32.is_eq(n,id),n,value,rest,id)

@unsafe
def increment_found(found: Bool,n: U32,value: U32,+rest: List<&2,Count>,id: U32) -> List<&2,Count>:
  match found:
    case True{}: Count{n,U32.inc(value)} <> rest
    case False{}: Count{n,value} <> increment(rest,id)

@unsafe
def apply(+program: Program,+env: Environment,+ctx: Context,+state: State) -> State:
  match program:
    case Program{p,_}: process(p,env,ctx,state)

@unsafe
def process(+p: Processor,+env: Environment,+ctx: Context,+state: State) -> State:
  match p ctx state:
    case Noop{} _ _: state
    case Chain{items} _ _: chain(items,env,ctx,state)
    case Rot{ids,probability} _ State{material,_,_,_,_}: rot(rottable(ids,material),probability,state)
    case Protected{ids} Context{_,_,world,_} _: kept(Bool.not(contains(ids,world,0,M.count(ids))),state)
    case Capped{id,limit,delegate} _ State{_,_,_,counts,_}: capped(U32.is_lt(count(counts,id),limit),id,delegate,env,ctx,state)
    case Rules{items} _ _: apply_rules(items,env,ctx,state)

@unsafe
def rottable(ids: Maybe<&2,M.Node>,material: U32) -> Bool:
  match ids:
    case None{}: True{}
    case Some{+node}: contains(node,material,0,M.count(node))

@unsafe
def kept(keep: Bool,state: State) -> State:
  match state:
    case State{m,tag,r,counts,_}: State{m,tag,r,counts,keep}

@unsafe
def rot(applies: Bool,probability: Registry.Value,state: State) -> State:
  match applies:
    case False{}: state
    case True{}: rot_result(chance(state,inclusive(probability)))

@unsafe
def rot_result(result: Decision) -> State:
  match result:
    case Decision{state,yes}: kept(yes,state)

@unsafe
def capped(active: Bool,+id: U32,+delegate: Processor,+env: Environment,+ctx: Context,+state: State) -> State:
  match active:
    case False{}: state
    case True{}: capped_result(state,process(delegate,env,ctx,state),id)

@unsafe
def capped_result(+before: State,+after: State,+id: U32) -> State:
  match before after:
    case State{a,tag,_,_,_} State{b,other,_,_,keep}: counted(Bool.or(Bool.not(keep),Bool.or(U32.is_ne(a,b),Bool.not(V.equal(tag,other)))),after,id)

@unsafe
def counted(changed: Bool,state: State,id: U32) -> State:
  match changed state:
    case True{} State{m,tag,r,counts,keep}: State{m,tag,r,increment(counts,id),keep}
    case False{} _: state

@unsafe
def chain(+xs: List<&2,Processor>,+env: Environment,+ctx: Context,+state: State) -> State:
  match xs state:
    case Nil{} _: state
    case _ State{_,_,_,_,False{}}: state
    case p <> rest _: chain(rest,env,ctx,process(p,env,ctx,state))

@unsafe
def apply_rules(+xs: List<&2,Rule>,+env: Environment,+ctx: Context,+state: State) -> State:
  match xs ctx state:
    case Nil{} _ _: state
    case +rule <> rest Context{_,_,_,rotation} State{material,_,_,_,_}: input_result(rule,rest,env,ctx,matches(input(rule),env,material,rotation,state))

@unsafe
def input(+rule: Rule) -> Predicate:
  match rule:
    case Rule{p,_,_,_,_,_,_,_}: p

@unsafe
def input_result(+rule: Rule,+rest: List<&2,Rule>,+env: Environment,+ctx: Context,result: Decision) -> State:
  match rule ctx result:
    case _ _ Decision{state,False{}}: apply_rules(rest,env,ctx,state)
    case Rule{_,p,_,_,_,_,_,_} Context{_,_,world,rotation} Decision{state,True{}}: location_result(rule,rest,env,ctx,matches(p,env,world,rotation,state))

@unsafe
def location_result(+rule: Rule,+rest: List<&2,Rule>,+env: Environment,+ctx: Context,result: Decision) -> State:
  match rule result:
    case _ Decision{state,False{}}: apply_rules(rest,env,ctx,state)
    case Rule{_,_,p,_,_,_,_,_} Decision{state,True{}}: position_result(rule,rest,env,ctx,position_matches(p,ctx,state))

@unsafe
def abs(+x: U32) -> U32:
  G.max(x,U32.sub(0,x))

@unsafe
def distance(+delta: P.Position,+axis: U32) -> F32:
  match delta axis:
    case P.Position{x,_,_} 0: U32.to_f32(abs(x))
    case P.Position{_,y,_} 1: U32.to_f32(abs(y))
    case P.Position{_,_,z} 2: U32.to_f32(abs(z))
    case P.Position{x,y,z} _: U32.to_f32(U32.add(abs(x),U32.add(abs(y),abs(z))))

@unsafe
def position_matches(+p: Position,+ctx: Context,+state: State) -> Decision:
  match p ctx:
    case Always{} _: Decision{state,True{}}
    case Never{} _: Decision{state,False{}}
    case Linear{axis,min,max,low,high} Context{at,origin,_,_}: linear(state,
      F32.max(0.0,F32.min(1.0,F32.div(F32.sub(distance(G.sub(at,origin),axis),min),F32.max(1.0,F32.sub(max,min))))),low,high)

@unsafe
def linear(+state: State,+blend: F32,low: F32,high: F32) -> Decision:
  linear_draw(R.bits(random(state),24),state,F32.add(F32.mul(low,F32.sub(1.0,blend)),F32.mul(high,blend)))

@unsafe
def linear_draw(draw: R.Draw,state: State,p: F32) -> Decision:
  match draw state:
    case R.Draw{r,v} State{m,tag,_,counts,keep}: Decision{State{m,tag,r,counts,keep},F32.is_lt(F32.mul(U32.to_f32(v),0.000000059604644775390625),p)}

@unsafe
def position_result(+rule: Rule,+rest: List<&2,Rule>,+env: Environment,+ctx: Context,result: Decision) -> State:
  match ctx result:
    case _ Decision{state,False{}}: apply_rules(rest,env,ctx,state)
    case Context{_,_,_,rotation} Decision{state,True{}}: replace(rule,env,rotation,state)

@unsafe
def replace(+rule: Rule,+env: Environment,+rotation: U32,+state: State) -> State:
  match rule:
    case Rule{_,_,_,output,entity,modifier,append,loot}: tag_modified(modifier,append,loot,env,replaced(M.item(output,rotation),entity,env,state))

@unsafe
def replaced(+output: M.Node,+entity: M.Node,+env: Environment,+state: State) -> State:
  replace_present(U32.is_eq(M.tag(output),3),M.int(output,0),entity,env,state)

@unsafe
def replace_present(present: Bool,+output: U32,+entity: M.Node,+env: Environment,+state: State) -> State:
  match present state:
    case False{} _: state
    case True{} State{material,tag,r,counts,keep}: State{output,replacement_tag(U32.is_eq(name(env,material),name(env,output)),tag,entity),r,counts,keep}

@unsafe
def replacement_tag(same: Bool,tag: M.Node,entity: M.Node) -> M.Node:
  match same:
    case True{}: tag
    case False{}: entity

@unsafe
def tag_modified(modifier: U32,append: M.Node,loot: U32,+env: Environment,state: State) -> State:
  match state:
    case State{m,tag,r,counts,keep}: State{m,loot_tag(loot,env,modified(modifier,append,tag)),r,counts,keep}

@unsafe
def modified(modifier: U32,+append: M.Node,tag: M.Node) -> M.Node:
  match modifier:
    case 1: M.empty()
    case 2: append_present(U32.is_ne(M.tag(append),0),append,tag)
    case _: tag

@unsafe
def append_present(present: Bool,append: M.Node,tag: M.Node) -> M.Node:
  match present:
    case True{}: V.append(tag,append)
    case False{}: tag

@unsafe
def loot_tag(+loot: U32,+env: Environment,tag: M.Node) -> M.Node:
  match loot:
    case 4294967295: tag
    case _: V.put(tag,loot_key(M.key(model(env),48n)),V.array([V.integer(8),V.string(loot)]))

# Reserved generated metadata key when a pack has no pre-existing LootTable tag.
@unsafe
def loot_key(id: U32) -> U32:
  match id:
    case 4294967295: 4294967280
    case _: id

@unsafe
def names(+m: M.Model,+materials: M.Node,+i: U32,n: U32) -> List<&2,U32>:
  match n:
    case 0: []
    case _: material_name(m,M.item(materials,i)) <> names(m,materials,U32.inc(i),U32.sub(n,1))

@unsafe
def material_name(+m: M.Model,+node: M.Node) -> U32:
  match node:
    case M.Scalar{Registry.Value{5,id,_,_}}: id
    case _: M.text(M.get(m,node,46n))

@unsafe
def prepare_environment(-A: Type,doc: Registry.Document,next: Registry.Document -> Environment -> A) -> A:
  keys(A,doc,doc => +keys => RV.root(A,doc,doc => root => Registry.field(A,doc,root,D.key(keys,47n),doc => found =>
    materials_found(A,doc,keys,found,next))))

@unsafe
def materials_found(-A: Type,doc: Registry.Document,+keys: D.Keys,found: Maybe<&2,Registry.Value>,next: Registry.Document -> Environment -> A) -> A:
  match found:
    case None{}: next(doc,Environment{M.Model{M.empty(),keys},Q.Empty{}})
    case Some{value}: V.project(A,doc,value,doc => +node => next(doc,Environment{M.Model{M.empty(),keys},Q.prepared_table(U32,names(M.Model{M.empty(),keys},node,0,M.count(node)))}))
