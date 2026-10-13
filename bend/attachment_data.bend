import Base
import ./placement_data.bend as P
import ./density.bend as Q
import ./int_provider.bend as I

type State is Data:
  State{source: U32,faces: U32,added: Q.Table<U32>}
type Settings is Data:
  Settings{vines: Bool,initial: U32,wet_initial: U32,face_mask: U32,directions: List<&2,U32>,search_range: U32,
    spread_chance: F32,spread_order: List<&2,U32>,sculk: Bool}
type Surfaces is Data:
  Surfaces{air: P.Predicate,water: P.Predicate,source_water: P.Predicate,placed_on: P.Predicate,
    spread_replaceable: P.Predicate,spread_support: P.Predicate,support: Q.Table<P.Predicate>,sturdy: Q.Table<P.Predicate>}
type Recipe is Data:
  Recipe{settings: Settings,states: Q.Table<State>,surfaces: Surfaces}

@unsafe
def find(+states: Q.Table<State>,+source: U32) -> Maybe<&2,State>:
  search(states,source,0,Q.table_size(State,states))

@unsafe
def search(+states: Q.Table<State>,+source: U32,+lo: U32,+hi: U32) -> Maybe<&2,State>:
  search_more(U32.is_lt(lo,hi),states,source,lo,hi)

@unsafe
def search_more(more: Bool,+states: Q.Table<State>,+source: U32,+lo: U32,+hi: U32) -> Maybe<&2,State>:
  match more:
    case False{}: None{}
    case True{}: compared(Q.get(State,states,U32.div(U32.add(lo,hi),2),State{0,0,Q.Empty{}}),states,source,lo,hi,U32.div(U32.add(lo,hi),2))

@unsafe
def compared(+row: State,+states: Q.Table<State>,+source: U32,lo: U32,hi: U32,+mid: U32) -> Maybe<&2,State>:
  match row:
    case State{+id,_,_}: compare(U32.is_eq(id,source),U32.is_lt(id,source),row,states,source,lo,hi,mid)

@unsafe
def compare(equal: Bool,less: Bool,row: State,states: Q.Table<State>,source: U32,lo: U32,hi: U32,+mid: U32) -> Maybe<&2,State>:
  match equal less:
    case True{} _: Some{row}
    case False{} True{}: search(states,source,U32.inc(mid),hi)
    case False{} False{}: search(states,source,lo,mid)

def present(row: Maybe<&2,State>) -> Bool:
  match row:
    case None{}: False{}
    case Some{_}: True{}

@unsafe
def bit(+mask: U32,face: U32) -> Bool:
  U32.is_ne(U32.and(mask,U32.shln(1,U32.to_nat(U32.and(face,31)))),0)

@unsafe
def linked(xs: Q.Table<U32>,+states: Q.Table<State>) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{id}: present(find(states,id))
    case Q.Branch{_,a,b}: Bool.and(linked(a,states),linked(b,states))

@unsafe
def rows(+i: U32,+states: Q.Table<State>,+palette: U32,+mask: U32,+previous: U32) -> Bool:
  rows_more(U32.is_lt(i,Q.table_size(State,states)),i,states,palette,mask,previous)

@unsafe
def rows_more(more: Bool,+i: U32,+states: Q.Table<State>,+palette: U32,+mask: U32,+previous: U32) -> Bool:
  match more:
    case False{}: True{}
    case True{}: row_valid(Q.get(State,states,i,State{0,0,Q.Empty{}}),i,states,palette,mask,previous)

@unsafe
def row_valid(row: State,+i: U32,+states: Q.Table<State>,+palette: U32,+mask: U32,previous: U32) -> Bool:
  match row:
    case State{+source,faces,+added}: Bool.and(U32.is_lt(source,palette),Bool.and(Bool.or(U32.is_zero(i),U32.is_gt(source,previous)),
      Bool.and(U32.is_zero(U32.and(faces,U32.xor(mask,4294967295))),Bool.and(U32.is_eq(Q.table_size(U32,added),6),
        Bool.and(linked(added,states),rows(U32.inc(i),states,palette,mask,source))))))

@unsafe
def directions(xs: List<&2,U32>,+seen: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case +d <> rest: Bool.and(U32.is_lt(d,6),Bool.and(Bool.not(bit(seen,d)),directions(rest,U32.or(seen,U32.shln(1,U32.to_nat(U32.and(d,31)))))))

@unsafe
def orders(xs: List<&2,U32>) -> Bool:
  match xs:
    case Nil{}: True{}
    case id <> rest: Bool.and(U32.is_lt(id,3),orders(rest))

@unsafe
def predicates(xs: Q.Table<P.Predicate>,+palette: U32) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{p}: P.predicate_valid(p,palette)
    case Q.Branch{_,a,b}: Bool.and(predicates(a,palette),predicates(b,palette))

@unsafe
def surfaces_valid(surfaces: Surfaces,+palette: U32) -> Bool:
  match surfaces:
    case Surfaces{air,water,source,on,replace,support,+faces,+sturdy}: Bool.and(P.predicate_valid(air,palette),Bool.and(P.predicate_valid(water,palette),
      Bool.and(P.predicate_valid(source,palette),Bool.and(P.predicate_valid(on,palette),Bool.and(P.predicate_valid(replace,palette),
        Bool.and(P.predicate_valid(support,palette),Bool.and(U32.is_eq(Q.table_size(P.Predicate,faces),6),
          Bool.and(U32.is_eq(Q.table_size(P.Predicate,sturdy),6),Bool.and(predicates(faces,palette),predicates(sturdy,palette))))))))))

@unsafe
def range(vines: Bool,+value: U32) -> Bool:
  match vines:
    case True{}: U32.is_zero(value)
    case False{}: Bool.and(U32.is_ge(value,1),U32.is_le(value,64))

@unsafe
def valid(recipe: Recipe,+palette: U32) -> Bool:
  match recipe:
    case Recipe{Settings{vines,initial,wet,+mask,ds,r,+p,os,_},+states,surfaces}: Bool.and(U32.is_lt(mask,64),Bool.and(directions(ds,0),
      Bool.and(present(find(states,initial)),Bool.and(present(find(states,wet)),Bool.and(rows(0,states,palette,mask,0),
        Bool.and(range(vines,r),Bool.and(I.finite(p),Bool.and(F32.is_ge(p,0.0),Bool.and(F32.is_le(p,1.0),Bool.and(orders(os),surfaces_valid(surfaces,palette)))))))))))
