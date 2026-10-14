import Base
import ./structure_registry.bend as M
import ./registry.bend as R
import ./registry_density.bend as D
import ./density.bend as Q

# Fully project only a selected template/processor payload, retaining exact
# numeric bits and dictionary IDs. The planner still keeps large payloads opaque.
@unsafe
def project(-A: Type,doc: R.Document,value: R.Value,next: R.Document -> M.Node -> A) -> A:
  M.project(A,doc,D.Keys{[]},value,next)

@unsafe
def integer(value: U32) -> M.Node:
  M.Scalar{R.Value{3,0,value,0}}

@unsafe
def string(id: U32) -> M.Node:
  M.Scalar{R.Value{5,id,0,0}}

@unsafe
def array(+xs: List<&2,M.Node>) -> M.Node:
  M.Array{R.Value{6,U32.from_nat(List.length(&2,M.Node,xs)),0,0},Q.prepared_table(M.Node,xs)}

@unsafe
def object(+xs: List<&2,M.Field>) -> M.Node:
  M.Object{R.Value{7,U32.from_nat(List.length(&2,M.Field,xs)),0,0},xs}

@unsafe
def compound() -> M.Node:
  array([integer(10),object([])])

@unsafe
def put_fields(+xs: List<&2,M.Field>,+key: U32,+value: M.Node) -> List<&2,M.Field>:
  match xs:
    case Nil{}: [M.Field{key,value}]
    case M.Field{+id,v} <> rest: put_equal(U32.is_eq(id,key),id,v,rest,key,value)

@unsafe
def put_equal(equal: Bool,+id: U32,v: M.Node,+rest: List<&2,M.Field>,+key: U32,+value: M.Node) -> List<&2,M.Field>:
  match equal:
    case True{}: M.Field{key,value} <> rest
    case False{}: M.Field{id,v} <> put_fields(rest,key,value)

@unsafe
def put_object(+node: M.Node,+key: U32,+value: M.Node) -> M.Node:
  match node:
    case M.Object{_,fields}: object(put_fields(fields,key,value))
    case _: object([M.Field{key,value}])

@unsafe
def put(+tag: M.Node,+key: U32,+value: M.Node) -> M.Node:
  array([integer(10),put_object(M.item(tag,1),key,value)])

@unsafe
def append_fields(+xs: List<&2,M.Field>,+tag: M.Node) -> M.Node:
  match xs:
    case Nil{}: tag
    case M.Field{key,value} <> rest: append_fields(rest,put(tag,key,value))

@unsafe
def append_object(+node: M.Node,+tag: M.Node) -> M.Node:
  match node:
    case M.Object{_,xs}: append_fields(xs,tag)
    case _: tag

@unsafe
def append(+tag: M.Node,+value: M.Node) -> M.Node:
  append_object(M.item(value,1),tag_or_compound(tag))

# Equality ignores tape addresses and object order. Capped quotas count actual
# changes, not allocation or identical metadata produced from another template.
@unsafe
def equal(+a: M.Node,+b: M.Node) -> Bool:
  match a b:
    case M.Array{R.Value{_,+n,_,_},_} M.Array{R.Value{_,size,_,_},_}: Bool.and(U32.is_eq(n,size),equal_items(a,b,0,n))
    case M.Object{_,+xs} M.Object{_,+ys}: Bool.and(Nat.is_eq(List.length(&2,M.Field,xs),List.length(&2,M.Field,ys)),equal_fields(xs,b))
    case M.Scalar{av} M.Scalar{bv}: scalar_equal(av,bv)
    case _ _: False{}

@unsafe
def equal_items(+a: M.Node,+b: M.Node,+i: U32,n: U32) -> Bool:
  match n:
    case 0: True{}
    case _: Bool.and(equal(M.item(a,i),M.item(b,i)),equal_items(a,b,U32.inc(i),U32.sub(n,1)))

@unsafe
def equal_fields(+xs: List<&2,M.Field>,+b: M.Node) -> Bool:
  match xs:
    case Nil{}: True{}
    case M.Field{key,value} <> rest: Bool.and(Bool.and(has_field(b,key),equal(value,M.field(b,key))),equal_fields(rest,b))

@unsafe
def signed_integer(+value: U32) -> M.Node:
  M.Scalar{R.Value{3,U32.mul(4294967295,U32.shrn(value,31n)),value,0}}

@unsafe
def tag_or_compound(+tag: M.Node) -> M.Node:
  tag_present(U32.is_ne(M.tag(tag),0),tag)

@unsafe
def tag_present(present: Bool,tag: M.Node) -> M.Node:
  match present:
    case True{}: tag
    case False{}: compound()

@unsafe
def has_field(+node: M.Node,+id: U32) -> Bool:
  match node:
    case M.Object{_,xs}: has_field_list(xs,id)
    case _: False{}

@unsafe
def has_field_list(xs: List<&2,M.Field>,+id: U32) -> Bool:
  match xs:
    case Nil{}: False{}
    case M.Field{key,_} <> rest: Bool.or(U32.is_eq(key,id),has_field_list(rest,id))

@unsafe
def scalar_equal(+a: R.Value,+b: R.Value) -> Bool:
  match a b:
    case R.Value{4,x,0,_} R.Value{4,p,0,_}: Bool.or(U32.is_eq(x,p),Bool.and(U32.is_zero(U32.and(x,2147483647)),U32.is_zero(U32.and(p,2147483647))))
    case R.Value{t,x,y,_} R.Value{u,p,q,_}: Bool.and(U32.is_eq(t,u),Bool.and(U32.is_eq(x,p),U32.is_eq(y,q)))
