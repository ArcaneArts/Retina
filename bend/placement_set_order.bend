import Base
import ./placement_data.bend as P

# Stable Java HashSet bucket iteration, including insertion order inside bins.
# Merge sorting avoids scanning every bucket for every patch surface position.
@unsafe
def capacity(+n: U32,+value: U32) -> U32:
  room(U32.is_le(n,U32.div(U32.mul(value,3),4)),n,value)

@unsafe
def room(ok: Bool,n: U32,+value: U32) -> U32:
  match ok:
    case True{}: value
    case False{}: capacity(n,U32.mul(value,2))

@unsafe
def bucket(at: P.Position,+mask: U32) -> U32:
  match at:
    case P.Position{x,y,z}:
      +h = U32.add(U32.mul(U32.add(y,U32.mul(z,31)),31),x)
      U32.and(U32.xor(h,U32.shrn(h,16n)),mask)

@unsafe
def reverse_append(xs: List<&2,P.Position>,acc: List<&2,P.Position>) -> List<&2,P.Position>:
  match xs:
    case Nil{}: acc
    case p <> rest: reverse_append(rest,p <> acc)

type Split is Data:
  Split{a: List<&2,P.Position>,b: List<&2,P.Position>}

@unsafe
def split(+n: U32,xs: List<&2,P.Position>,acc: List<&2,P.Position>) -> Split:
  match n xs:
    case 0 _: Split{List.reverse(&2,P.Position,acc),xs}
    case _ Nil{}: Split{List.reverse(&2,P.Position,acc),[]}
    case _ p <> rest: split(U32.sub(n,1),rest,p <> acc)

@unsafe
def merge(a: List<&2,P.Position>,b: List<&2,P.Position>,+mask: U32,acc: List<&2,P.Position>) -> List<&2,P.Position>:
  match a b:
    case Nil{} _: reverse_append(acc,b)
    case _ Nil{}: reverse_append(acc,a)
    case +x <> ar +y <> br: merge_first(U32.is_le(bucket(x,mask),bucket(y,mask)),x,ar,y,br,mask,acc)

@unsafe
def merge_first(left: Bool,+x: P.Position,ar: List<&2,P.Position>,+y: P.Position,br: List<&2,P.Position>,mask: U32,acc: List<&2,P.Position>) -> List<&2,P.Position>:
  match left:
    case True{}: merge(ar,y <> br,mask,x <> acc)
    case False{}: merge(x <> ar,br,mask,y <> acc)

@unsafe
def sort(+n: U32,xs: List<&2,P.Position>,mask: U32) -> List<&2,P.Position>:
  sort_more(U32.is_gt(n,1),n,xs,mask)

@unsafe
def sort_more(more: Bool,+n: U32,xs: List<&2,P.Position>,mask: U32) -> List<&2,P.Position>:
  match more:
    case False{}: xs
    case True{}: sorted(split(U32.div(n,2),xs,[]),n,mask)

@unsafe
def sorted(parts: Split,+n: U32,+mask: U32) -> List<&2,P.Position>:
  match parts:
    case Split{a,b}: merge(sort(U32.div(n,2),a,mask),sort(U32.sub(n,U32.div(n,2)),b,mask),mask,[])

@unsafe
def ordered(+xs: List<&2,P.Position>) -> List<&2,P.Position>:
  +n = U32.from_nat(List.length(&2,P.Position,xs))
  sort(n,xs,U32.sub(capacity(n,16),1))
