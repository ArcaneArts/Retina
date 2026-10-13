import Base
import ./placement_random.bend as R
import ./word64.bend as W

# Small registered direction lists. Native Fisher-Yates consumes no draw for
# zero/one element and keeps equal values/positions in the supplied order.
type Shuffled is Data:
  Shuffled{state: W.U64,values: List<&2,U32>}

@unsafe
def get(xs: List<&2,U32>,+i: U32) -> U32:
  match xs i:
    case Nil{} _: 0
    case value <> _ 0: value
    case _ <> rest _: get(rest,U32.sub(i,1))

@unsafe
def swapped(xs: List<&2,U32>,+i: U32,+a: U32,+b: U32,+av: U32,+bv: U32,acc: List<&2,U32>) -> List<&2,U32>:
  match xs:
    case Nil{}: List.reverse(&2,U32,acc)
    case v <> rest: swap_cell(U32.is_eq(i,a),U32.is_eq(i,b),v,rest,i,a,b,av,bv,acc)

@unsafe
def swap_cell(a_here: Bool,b_here: Bool,v: U32,rest: List<&2,U32>,i: U32,a: U32,b: U32,+av: U32,+bv: U32,acc: List<&2,U32>) -> List<&2,U32>:
  match a_here b_here:
    case True{} _: swapped(rest,U32.inc(i),a,b,av,bv,bv <> acc)
    case False{} True{}: swapped(rest,U32.inc(i),a,b,av,bv,av <> acc)
    case False{} False{}: swapped(rest,U32.inc(i),a,b,av,bv,v <> acc)

@unsafe
def count(+n: U32,+xs: List<&2,U32>,state: W.U64) -> Shuffled:
  many(U32.is_gt(n,1),n,xs,state)

@unsafe
def many(more: Bool,+n: U32,+xs: List<&2,U32>,state: W.U64) -> Shuffled:
  match more:
    case False{}: Shuffled{state,xs}
    case True{}: drawn(R.integer(state,0,U32.sub(n,1)),n,xs)

@unsafe
def drawn(draw: R.Draw,+n: U32,+xs: List<&2,U32>) -> Shuffled:
  match draw:
    case R.Draw{state,+value}: count(U32.sub(n,1),swapped(xs,0,U32.sub(n,1),R.low(value),get(xs,U32.sub(n,1)),get(xs,R.low(value)),[]),state)

@unsafe
def shuffle(+xs: List<&2,U32>,state: W.U64) -> Shuffled:
  count(U32.from_nat(List.length(&2,U32,xs)),xs,state)
