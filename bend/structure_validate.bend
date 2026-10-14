import Base
import ./structure_registry.bend as M
import ./structure_plan.bend as P
import ./registry.bend as R
import ./structure_geometry.bend as G

# Validate the geometry/selection schema actually consumed by this planner.
# Opaque block, entity and processor payloads stay in the original registry;
# their placement-specific validation belongs to the later replay preparation.
@unsafe
def integer(+node: M.Node) -> Bool:
  integer_value(M.value(node))

@unsafe
def integer_value(+v: R.Value) -> Bool:
  match v:
    case R.Value{3,+hi,+lo,_}: Bool.or(Bool.and(U32.is_zero(hi),U32.is_le(lo,2147483647)),Bool.and(U32.is_eq(hi,4294967295),U32.is_ge(lo,2147483648)))
    case _: False{}

@unsafe
def unsigned(+node: M.Node,+limit: U32) -> Bool:
  Bool.and(integer(node),U32.is_le(M.int(node,4294967295),limit))

@unsafe
def array(+node: M.Node) -> Bool:
  Bool.or(U32.is_eq(M.tag(node),6),Bool.and(U32.is_ge(M.tag(node),8),U32.is_le(M.tag(node),11)))

@unsafe
def vector(+node: M.Node) -> Bool:
  Bool.and(Bool.and(array(node),U32.is_eq(M.count(node),3)),Bool.and(integer(M.item(node,0)),Bool.and(integer(M.item(node,1)),integer(M.item(node,2)))))

@unsafe
def list(+m: M.Model,+xs: M.Node,+kind: U32,+i: U32,+n: U32) -> Bool:
  match n:
    case 0: True{}
    case _: next(valid(m,M.item(xs,i),kind),m,xs,kind,U32.inc(i),U32.sub(n,1))

@unsafe
def next(+ok: Bool,+m: M.Model,+xs: M.Node,+kind: U32,+i: U32,+n: U32) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: list(m,xs,kind,i,n)

@unsafe
def all(+m: M.Model,+xs: M.Node,+kind: U32) -> Bool:
  all_array(array(xs),m,xs,kind)

@unsafe
def all_array(+ok: Bool,+m: M.Model,+xs: M.Node,+kind: U32) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: list(m,xs,kind,0,M.count(xs))

@unsafe
def valid(+m: M.Model,+v: M.Node,+kind: U32) -> Bool:
  match kind:
    case 0: template(m,v)
    case 1: joint(m,v)
    case 2: element(m,v)
    case 3: part(m,v)
    case 4: definition(m,v)
    case 5: set(m,v)
    case 6: entry(m,v)
    case 7: unsigned(v,65535)
    case _: False{}

@unsafe
def positive(+v: M.Node,+limit: U32) -> Bool:
  Bool.and(unsigned(v,limit),U32.is_gt(M.int(v,0),0))

@unsafe
def size(+v: M.Node) -> Bool:
  Bool.and(vector(v),Bool.and(positive(M.item(v,0),512),Bool.and(positive(M.item(v,1),512),positive(M.item(v,2),512))))

@unsafe
def template(+m: M.Model,+v: M.Node) -> Bool:
  Bool.and(Bool.and(size(M.get(m,v,6n)),integer(M.get(m,v,7n))),Bool.and(U32.is_gt(M.count(M.get(m,v,8n)),0),all(m,M.get(m,v,13n),1)))

@unsafe
def joint(+m: M.Model,+v: M.Node) -> Bool:
  Bool.and(vector(M.get(m,v,14n)),Bool.and(Bool.and(unsigned(M.get(m,v,15n),5),unsigned(M.get(m,v,16n),5)),
    Bool.and(integer(M.get(m,v,21n)),integer(M.get(m,v,22n)))))

@unsafe
def part(+m: M.Model,+v: M.Node) -> Bool:
  Bool.and(unsigned(M.get(m,v,25n),2147483647),U32.is_lt(M.int(M.get(m,v,25n),4294967295),M.count(P.array(m,1n))))

@unsafe
def element(+m: M.Model,+v: M.Node) -> Bool:
  Bool.and(positive(M.get(m,v,28n),2147483647),all(m,M.get(m,v,24n),3))

@unsafe
def definition(+m: M.Model,+v: M.Node) -> Bool:
  Bool.and(Bool.and(all(m,M.get(m,v,32n),7),U32.is_eq(M.tag(M.get(m,v,33n)),7)),definition_kind(M.named(m,M.get(m,v,31n),49n),m,v))

@unsafe
def definition_kind(+jigsaw: Bool,+m: M.Model,+v: M.Node) -> Bool:
  match jigsaw:
    case False{}: part(m,v)
    case True{}: Bool.and(U32.is_eq(M.tag(M.get(m,v,19n)),5),Bool.and(Bool.not(G.lt(P.distance(m,v),0)),optional_unsigned(M.get(m,M.get(m,v,33n),6n),2147483647)))

@unsafe
def sum(+m: M.Model,+xs: M.Node,+i: U32,+n: U32,+acc: U32) -> Bool:
  match n:
    case 0: True{}
    case _: sum_room(U32.is_le(P.weight(m,M.item(xs,i)),U32.sub(2147483647,acc)),m,xs,i,n,acc)

@unsafe
def sum_room(+room: Bool,+m: M.Model,+xs: M.Node,+i: U32,+n: U32,+acc: U32) -> Bool:
  match room:
    case False{}: False{}
    case True{}: sum(m,xs,U32.inc(i),U32.sub(n,1),U32.add(acc,P.weight(m,M.item(xs,i))))

@unsafe
def pool(+m: M.Model,+v: M.Node) -> Bool:
  Bool.and(all(m,M.get(m,v,30n),2),sum(m,M.get(m,v,30n),0,M.count(M.get(m,v,30n)),0))

@unsafe
def pools(+m: M.Model,+node: M.Node) -> Bool:
  match node:
    case M.Object{_,fields}: pool_fields(m,fields)
    case _: False{}

@unsafe
def pool_fields(+m: M.Model,+fields: List<&2,M.Field>) -> Bool:
  match fields:
    case Nil{}: True{}
    case M.Field{_,v} <> rest: pool_next(pool(m,v),m,rest)

@unsafe
def pool_next(+ok: Bool,+m: M.Model,+rest: List<&2,M.Field>) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: pool_fields(m,rest)

@unsafe
def entry(+m: M.Model,+v: M.Node) -> Bool:
  Bool.and(positive(M.get(m,v,28n),2147483647),Bool.and(unsigned(M.get(m,v,35n),2147483647),U32.is_lt(M.int(M.get(m,v,35n),4294967295),M.count(P.array(m,2n)))))

@unsafe
def set(+m: M.Model,+v: M.Node) -> Bool:
  +p = M.get(m,v,34n)
  Bool.and(Bool.and(positive(M.get(m,p,36n),4096),unsigned(M.get(m,p,37n),4095)),
    Bool.and(U32.is_gt(M.int(M.get(m,p,36n),0),M.int(M.get(m,p,37n),4294967295)),
      Bool.and(U32.is_gt(M.count(M.get(m,v,30n)),0),Bool.and(all(m,M.get(m,v,30n),6),sum(m,M.get(m,v,30n),0,M.count(M.get(m,v,30n)),0)))))

@unsafe
def model(+m: M.Model) -> Bool:
  Bool.and(all(m,P.array(m,1n),0),Bool.and(pools(m,P.array(m,3n)),Bool.and(all(m,P.array(m,2n),4),all(m,P.array(m,4n),5))))

@unsafe
def optional_unsigned(+v: M.Node,limit: U32) -> Bool:
  optional_missing(U32.is_zero(M.tag(v)),v,limit)

@unsafe
def optional_missing(missing: Bool,v: M.Node,limit: U32) -> Bool:
  match missing:
    case True{}: True{}
    case False{}: unsigned(v,limit)
