import Base
import ./density.bend as Q
import ./word64.bend as W
import ./placement_random.bend as R
import ./placement_data.bend as P
import ./int_provider.bend as I
import ./tree_geometry.bend as G

type State is Data:
  State{materials: Q.Table<U32>,weight: F32}
type Decorator is Data:
  Decorator{kind: U32,probability: F32,states: Q.Table<U32>}
type Recipe is Data:
  Recipe{trunk: U32,foliage: U32,height: Q.Table<U32>,radius: Q.Table<U32>,offset: Q.Table<U32>,foliage_height: Q.Table<U32>,trunk_height: Q.Table<U32>,
    logs: List<&2,State>,leaves: List<&2,State>,soil: U32,root: U32,root_offset: Q.Table<U32>,decorators: List<&2,Decorator>,snow: U32}
# Roles are retained until ordered replay; a leaf must never replace a log or
# neighboring terrain just because both were planned on the same GPU dispatch.
type Write is Data:
  Write{at: P.Position,material: U32,role: U32,side: U32}
type Plan is Data:
  Plan{state: W.U64,writes: List<&2,Write>,placed: Bool}
type Chosen is Data:
  Chosen{state: W.U64,materials: Q.Table<U32>}

@unsafe
def materials(xs: Q.Table<U32>,+palette: U32) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{value}: U32.is_lt(value,palette)
    case Q.Branch{_,a,b}: Bool.and(materials(a,palette),materials(b,palette))

@unsafe
def bounds(xs: Q.Table<U32>,+low: U32,+high: U32) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{+x}: Bool.and(Bool.not(G.signed_less(x,low)),Bool.not(G.signed_less(high,x)))
    case Q.Branch{_,a,b}: Bool.and(bounds(a,low,high),bounds(b,low,high))

@unsafe
def pair(+xs: Q.Table<U32>,+low: U32,+high: U32) -> Bool:
  Bool.and(U32.is_eq(Q.table_size(U32,xs),2),Bool.and(bounds(xs,low,high),Bool.not(G.signed_less(Q.get(U32,xs,1,0),Q.get(U32,xs,0,0)))))

@unsafe
def states(xs: List<&2,State>,+palette: U32,+width: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case State{+ids,+weight} <> rest: Bool.and(U32.is_eq(Q.table_size(U32,ids),width),Bool.and(materials(ids,palette),
      Bool.and(Bool.and(I.finite(weight),F32.is_gt(weight,0.0)),states(rest,palette,width))))

@unsafe
def nonempty(xs: List<&2,State>) -> Bool:
  match xs:
    case Nil{}: False{}
    case _: True{}

@unsafe
def decorators(xs: List<&2,Decorator>,+palette: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case Decorator{+kind,+probability,+ids} <> rest: Bool.and(U32.is_le(kind,2),Bool.and(U32.is_eq(Q.table_size(U32,ids),word(U32.is_eq(kind,2),12,4)),
      Bool.and(materials(ids,palette),Bool.and(I.finite(probability),Bool.and(Bool.and(F32.is_ge(probability,0.0),F32.is_le(probability,1.0)),decorators(rest,palette))))))

@unsafe
def word(value: Bool,a: U32,b: U32) -> U32:
  match value:
    case True{}: a
    case False{}: b

@unsafe
def valid(recipe: Recipe,+palette: U32) -> Bool:
  match recipe:
    case Recipe{trunk,foliage,+height,radius,offset,fh,th,+logs,+leaves,soil,root,ro,ds,snow}:
      Bool.and(U32.is_le(trunk,6),Bool.and(U32.is_le(foliage,9),Bool.and(U32.is_eq(Q.table_size(U32,height),3),Bool.and(bounds(height,0,80),
        Bool.and(pair(radius,0,256),Bool.and(pair(offset,4294967040,256),Bool.and(pair(fh,0,256),Bool.and(pair(th,0,256),Bool.and(pair(ro,4294967040,256),
          Bool.and(nonempty(logs),Bool.and(nonempty(leaves),Bool.and(states(logs,palette,3),Bool.and(states(leaves,palette,6),
            Bool.and(U32.is_lt(soil,palette),Bool.and(U32.is_lt(root,palette),Bool.and(U32.is_lt(snow,palette),decorators(ds,palette)))))))))))))))))

@unsafe
def scale(xs: List<&2,State>,+value: F32) -> F32:
  match xs:
    case Nil{}: value
    case State{_,weight} <> rest: scale(rest,F32.max(value,weight))

@unsafe
def total(xs: List<&2,State>,+scale: F32,+value: F32) -> F32:
  match xs:
    case Nil{}: value
    case State{_,weight} <> rest: total(rest,scale,F32.add(value,F32.div(weight,scale)))

@unsafe
def choose(+xs: List<&2,State>,+state: W.U64) -> Chosen:
  weighted(R.next(state),xs,scale(xs,1.17549435e-38))

@unsafe
def weighted(draw: R.Draw,+xs: List<&2,State>,+scale: F32) -> Chosen:
  match draw:
    case R.Draw{state,value}: selected(xs,F32.mul(R.unit(value),total(xs,scale,0.0)),scale,state,Q.Empty{})

@unsafe
def selected(xs: List<&2,State>,+value: F32,+scale: F32,+state: W.U64,last: Q.Table<U32>) -> Chosen:
  match xs:
    case Nil{}: Chosen{state,last}
    case State{ids,+weight} <> rest: selection(F32.is_lt(value,F32.div(weight,scale)),ids,rest,F32.sub(value,F32.div(weight,scale)),scale,state)

@unsafe
def selection(done: Bool,ids: Q.Table<U32>,rest: List<&2,State>,+value: F32,+scale: F32,+state: W.U64) -> Chosen:
  match done:
    case True{}: Chosen{state,ids}
    case False{}: selected(rest,value,scale,state,ids)

@unsafe
def range(+xs: Q.Table<U32>,+state: W.U64) -> R.Draw:
  G.range(state,Q.get(U32,xs,0,0),Q.get(U32,xs,1,0))
