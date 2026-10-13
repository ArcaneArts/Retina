import Base
import ./block_provider.bend as B
import ./int_provider.bend as I
import ./placement_data.bend as P
import ./density.bend as Q
import ./word64.bend as W

type Mapped is Data:
  Mapped{source: U32,states: Q.Table<U32>}
type Mushroom is Data:
  Mushroom{red: Bool,radius: U32,cap: B.Provider,stem: B.Provider,faces: List<&2,Mapped>,support: P.Predicate,clearance: P.Predicate,replaceable: P.Predicate}
type Decorator is Data:
  Attached{probability: F32,provider: B.Provider,directions: Q.Table<P.Position>}
  Vine{states: Q.Table<U32>}
  Shelf{probability: F32,states: Q.Table<Q.Table<U32>>}
type Fallen is Data:
  Fallen{trunk: B.Provider,length: I.Provider,axes: List<&2,Mapped>,stump: List<&2,Decorator>,logs: List<&2,Decorator>,
    clearance: P.Predicate,sturdy: P.Predicate,air: P.Predicate,replaceable: P.Predicate,water: P.Predicate,shelf: P.Predicate}

@unsafe
def mapped(rows: List<&2,Mapped>,+source: U32,+index: U32) -> U32:
  match rows:
    case Nil{}: source
    case Mapped{+id,states} <> rest: mapped_equal(U32.is_eq(id,source),states,rest,source,index)

@unsafe
def mapped_equal(equal: Bool,states: Q.Table<U32>,rest: List<&2,Mapped>,source: U32,index: U32) -> U32:
  match equal:
    case True{}: Q.get(U32,states,index,source)
    case False{}: mapped(rest,source,index)

@unsafe
def contains(rows: List<&2,Mapped>,+source: U32) -> Bool:
  match rows:
    case Nil{}: False{}
    case Mapped{id,_} <> rest: contains_equal(U32.is_eq(id,source),rest,source)

@unsafe
def contains_equal(equal: Bool,rest: List<&2,Mapped>,source: U32) -> Bool:
  match equal:
    case True{}: True{}
    case False{}: contains(rest,source)

@unsafe
def covered(outputs: List<&2,U32>,+rows: List<&2,Mapped>) -> Bool:
  match outputs:
    case Nil{}: True{}
    case id <> rest: Bool.and(contains(rows,id),covered(rest,rows))

@unsafe
def rows_valid(rows: List<&2,Mapped>,+width: U32,+palette: U32) -> Bool:
  match rows:
    case Nil{}: True{}
    case Mapped{source,+states} <> rest: Bool.and(U32.is_le(source,65535),Bool.and(U32.is_eq(Q.table_size(U32,states),width),
      Bool.and(B.table_states(states,palette),rows_valid(rest,width,palette))))

@unsafe
def transformed(+provider: B.Provider,+rows: List<&2,Mapped>,+width: U32,+palette: U32,+noises: U32) -> Bool:
  Bool.and(B.valid(provider,palette,noises),Bool.and(rows_valid(rows,width,palette),Bool.or(B.reads_current(provider),covered(B.outputs(provider),rows))))

@unsafe
def mushroom_valid(recipe: Mushroom,+palette: U32,+noises: U32) -> Bool:
  match recipe:
    case Mushroom{_,radius,cap,stem,faces,support,clearance,replaceable}:
      Bool.and(U32.is_le(radius,15),Bool.and(transformed(cap,faces,32,palette,noises),Bool.and(B.valid(stem,palette,noises),
        Bool.and(P.predicate_valid(support,palette),Bool.and(P.predicate_valid(clearance,palette),P.predicate_valid(replaceable,palette))))))

@unsafe
def probability(+p: F32) -> Bool:
  Bool.and(I.finite(p),Bool.and(F32.is_ge(p,0.0),F32.is_le(p,1.0)))

@unsafe
def unit_axis(+x: U32) -> Bool:
  Bool.or(U32.is_zero(x),Bool.or(U32.is_eq(x,1),U32.is_eq(x,4294967295)))

@unsafe
def unit(at: P.Position) -> Bool:
  match at:
    case P.Position{+x,+y,+z}: Bool.and(unit_axis(x),Bool.and(unit_axis(y),Bool.and(unit_axis(z),
      U32.is_eq(U32.add(W.flag(U32.is_ne(x,0)),U32.add(W.flag(U32.is_ne(y,0)),W.flag(U32.is_ne(z,0)))),1))))

@unsafe
def directions(xs: Q.Table<P.Position>) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{p}: unit(p)
    case Q.Branch{_,a,b}: Bool.and(directions(a),directions(b))

@unsafe
def shelves(xs: Q.Table<Q.Table<U32>>,+palette: U32) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{+row}: Bool.and(U32.is_eq(Q.table_size(U32,row),2),B.table_states(row,palette))
    case Q.Branch{_,a,b}: Bool.and(shelves(a,palette),shelves(b,palette))

@unsafe
def decorators(xs: List<&2,Decorator>,+palette: U32,+noises: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case d <> rest: Bool.and(decorator_valid(d,palette,noises),decorators(rest,palette,noises))

@unsafe
def decorator_valid(decorator: Decorator,+palette: U32,+noises: U32) -> Bool:
  match decorator:
    case Attached{p,provider,+ds}: Bool.and(probability(p),Bool.and(B.valid(provider,palette,noises),Bool.and(U32.is_gt(Q.table_size(P.Position,ds),0),directions(ds))))
    case Vine{+states}: Bool.and(U32.is_eq(Q.table_size(U32,states),4),B.table_states(states,palette))
    case Shelf{p,+states}: Bool.and(probability(p),Bool.and(U32.is_eq(Q.table_size(Q.Table<U32>,states),4),shelves(states,palette)))

@unsafe
def fallen_valid(recipe: Fallen,+palette: U32,+noises: U32) -> Bool:
  match recipe:
    case Fallen{trunk,+length,axes,stump,logs,clearance,sturdy,air,replaceable,water,shelf}:
      Bool.and(transformed(trunk,axes,2,palette,noises),Bool.and(I.valid(length),Bool.and(I.within(length,0,15),
        Bool.and(decorators(stump,palette,noises),Bool.and(decorators(logs,palette,noises),
          Bool.and(P.predicate_valid(clearance,palette),Bool.and(P.predicate_valid(sturdy,palette),Bool.and(P.predicate_valid(air,palette),
            Bool.and(P.predicate_valid(replaceable,palette),Bool.and(P.predicate_valid(water,palette),P.predicate_valid(shelf,palette)))))))))))
