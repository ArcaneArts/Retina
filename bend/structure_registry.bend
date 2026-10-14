import Base
import ./registry.bend as R
import ./registry_values.bend as V
import ./registry_density.bend as D
import ./density.bend as Q

# Immutable planning view of the original registry. Large block/palette/entity
# payloads remain referenced in the owned tape; no Java-side planning or lossy
# JSON rewrite. String identity is the registry dictionary's exact UTF-16 ID.
type Node is Data:
  Scalar{value: R.Value}
  Array{value: R.Value,items: Q.Table<Node>}
  Object{value: R.Value,fields: List<&2,Field>}
type Field is Data:
  Field{key: U32,value: Node}
type Model is Data:
  Model{root: Node,keys: D.Keys}

@unsafe
def empty() -> Node:
  Scalar{R.Value{0,0,0,0}}

@unsafe
def value(+node: Node) -> R.Value:
  match node:
    case Scalar{v}: v
    case Array{v,_}: v
    case Object{v,_}: v

@unsafe
def tag(+node: Node) -> U32:
  tag_value(value(node))

@unsafe
def tag_value(+v: R.Value) -> U32:
  match v:
    case R.Value{tag,_,_,_}: tag

@unsafe
def at(+node: Node) -> U32:
  at_value(value(node))

@unsafe
def at_value(+v: R.Value) -> U32:
  match v:
    case R.Value{_,_,_,at}: at

@unsafe
def count(+node: Node) -> U32:
  count_value(value(node))

@unsafe
def count_value(+v: R.Value) -> U32:
  match v:
    case R.Value{_,count,_,_}: count

@unsafe
def lookup(+fields: List<&2,Field>,+id: U32) -> Node:
  match fields:
    case Nil{}: empty()
    case Field{key,v} <> rest:
      lookup_equal(U32.is_eq(key,id),v,rest,id)

@unsafe
def field(+node: Node,+id: U32) -> Node:
  match node:
    case Object{_,fields}: lookup(fields,id)
    case _: empty()

@unsafe
def key(+model: Model,+i: Nat) -> U32:
  match model:
    case Model{_,keys}: D.key(keys,i)

@unsafe
def get(+model: Model,+node: Node,+i: Nat) -> Node:
  field(node,key(model,i))

@unsafe
def root(+model: Model) -> Node:
  match model:
    case Model{root,_}: root

@unsafe
def item(+node: Node,+i: U32) -> Node:
  match node:
    case Array{R.Value{_,n,_,_},xs}:
      item_valid(U32.is_lt(i,n),xs,i)
    case _: empty()

@unsafe
def int(+node: Node,+default: U32) -> U32:
  int_value(value(node),default)

@unsafe
def int_value(+v: R.Value,+default: U32) -> U32:
  match v:
    case R.Value{3,_,lo,_}: lo
    case _: default

@unsafe
def text(+node: Node) -> U32:
  text_value(value(node))

@unsafe
def text_value(+v: R.Value) -> U32:
  match v:
    case R.Value{5,id,_,_}: id
    case _: 4294967295

@unsafe
def named(+model: Model,+node: Node,+i: Nat) -> Bool:
  Bool.and(U32.is_eq(tag(node),5),Bool.and(U32.is_ne(key(model,i),4294967295),U32.is_eq(text(node),key(model,i))))

@unsafe
def flag(+node: Node) -> Bool:
  U32.is_eq(tag(node),2)

@unsafe
def keys(-A: Type,doc: R.Document,next: R.Document -> D.Keys -> A) -> A:
  D.resolved(A,["structures","templates","definitions","pools","sets","id","size","ground","palettes","blocks","tags","entities","processors",
    "joints","pos","front","top","name","target","pool","joint","selection_priority","placement_priority","final","parts","template","ignore_air","terrain_matching","weight","fallback","entries","kind","biomes","config","placement","definition",
    "spacing","separation","salt","spread_type","triangular","frequency","frequency_reduction_method","legacy_type_1","legacy_type_2","legacy_type_3","exclusion_zone","other_set","chunk_count",
    "jigsaw","aligned","start_height","absolute","above_bottom","below_top","value","min_inclusive","max_inclusive","project_start_to_heightmap","WORLD_SURFACE_WG","WORLD_SURFACE","start_jigsaw_name","max_distance_from_center","horizontal","use_expansion_hack","pool_aliases","type","random_group","minecraft:random_group","groups","data","alias","random","minecraft:random","targets"],doc,[],next)

@unsafe
def skip(+keys: D.Keys,+id: U32) -> Bool:
  Bool.or(Bool.or(U32.is_eq(id,D.key(keys,8n)),U32.is_eq(id,D.key(keys,9n))),
    Bool.or(Bool.or(U32.is_eq(id,D.key(keys,10n)),U32.is_eq(id,D.key(keys,11n))),U32.is_eq(id,D.key(keys,12n))))

@unsafe
def project(-A: Type,doc: R.Document,+keys: D.Keys,+v: R.Value,next: R.Document -> Node -> A) -> A:
  match v:
    case R.Value{6,n,_,at}: array(A,doc,keys,v,n,0,U32.add(at,3),[],next)
    case R.Value{7,n,_,at}: object(A,doc,keys,v,n,U32.add(at,3),[],next)
    case R.Value{8,n,_,_}: array(A,doc,keys,v,n,0,0,[],next)
    case R.Value{9,n,_,_}: array(A,doc,keys,v,n,0,0,[],next)
    case R.Value{10,n,_,_}: array(A,doc,keys,v,n,0,0,[],next)
    case R.Value{11,n,_,_}: array(A,doc,keys,v,n,0,0,[],next)
    case _: next(doc,Scalar{v})

@unsafe
def array(-A: Type,doc: R.Document,+keys: D.Keys,+v: R.Value,+n: U32,+i: U32,+cursor: U32,+acc: List<&2,Node>,next: R.Document -> Node -> A) -> A:
  match n:
    case 0: next(doc,Array{v,Q.prepared_table(Node,List.reverse(&2,Node,acc))})
    case _: array_nonempty(A,doc,keys,v,n,i,cursor,acc,next)

@unsafe
def array_nonempty(-A: Type,doc: R.Document,+keys: D.Keys,+v: R.Value,+n: U32,+i: U32,+cursor: U32,+acc: List<&2,Node>,next: R.Document -> Node -> A) -> A:
  match v:
    case R.Value{6,_,_,_}: R.get(A,doc,U32.inc(cursor),doc => span => R.value(A,doc,cursor,doc => child => project(A,doc,keys,child,doc => node => array(A,doc,keys,v,U32.sub(n,1),U32.inc(i),U32.add(cursor,span),node <> acc,next))))
    case _: R.item(A,doc,v,i,doc => child => packed(A,doc,keys,v,n,i,child,acc,next))

@unsafe
def packed(-A: Type,doc: R.Document,+keys: D.Keys,+v: R.Value,+n: U32,+i: U32,+child: Maybe<&2,R.Value>,+acc: List<&2,Node>,next: R.Document -> Node -> A) -> A:
  match child:
    case None{}: next(doc,empty())
    case Some{child}: array(A,doc,keys,v,U32.sub(n,1),U32.inc(i),0,Scalar{child} <> acc,next)

@unsafe
def object(-A: Type,doc: R.Document,+keys: D.Keys,+v: R.Value,+n: U32,+cursor: U32,+acc: List<&2,Field>,next: R.Document -> Node -> A) -> A:
  match n:
    case 0: next(doc,Object{v,List.reverse(&2,Field,acc)})
    case _: R.get(A,doc,cursor,doc => +id => R.get(A,doc,U32.add(cursor,2),doc => span => R.value(A,doc,U32.inc(cursor),doc => cv =>
      child(A,skip(keys,id),doc,keys,cv,doc => node => object(A,doc,keys,v,U32.sub(n,1),U32.add(U32.inc(cursor),span),Field{id,node} <> acc,next)))))

@unsafe
def child(-A: Type,+skip: Bool,doc: R.Document,+keys: D.Keys,+v: R.Value,next: R.Document -> Node -> A) -> A:
  match skip:
    case True{}: next(doc,Scalar{v})
    case False{}: project(A,doc,keys,v,next)

@unsafe
def prepare(-A: Type,doc: R.Document,next: R.Document -> Model -> A) -> A:
  keys(A,doc,doc => +keys => V.root(A,doc,doc => root => R.field(A,doc,root,D.key(keys,0n),doc => result => found(A,doc,keys,result,next))))

@unsafe
def found(-A: Type,doc: R.Document,+keys: D.Keys,+found: Maybe<&2,R.Value>,next: R.Document -> Model -> A) -> A:
  match found:
    case None{}: next(doc,Model{empty(),keys})
    case Some{v}: project(A,doc,keys,v,doc => node => next(doc,Model{node,keys}))

@unsafe
def lookup_equal(+equal: Bool,+v: Node,+rest: List<&2,Field>,+id: U32) -> Node:
  match equal:
    case True{}: v
    case False{}: lookup(rest,id)

@unsafe
def item_valid(+valid: Bool,+xs: Q.Table<Node>,+i: U32) -> Node:
  match valid:
    case True{}: Q.get(Node,xs,i,empty())
    case False{}: empty()
