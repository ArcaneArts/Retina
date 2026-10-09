import Base
import ./word64.bend as W
import ./placement_random.bend as R
import ./noise.bend as N

# Height-provider values retain signed-i32 bits. Resolve anchors for the actual
# build interval at sampling time; a profile can be reused for another height.
type Anchor is Data:
  Absolute{value: U32}
  AboveBottom{value: U32}
  BelowTop{value: U32}
type Provider is Data:
  Constant{value: Anchor}
  Uniform{minimum: Anchor,maximum: Anchor}
  Biased{minimum: Anchor,maximum: Anchor,inner: U32}
  VeryBiased{minimum: Anchor,maximum: Anchor,inner: U32}
  Trapezoid{minimum: Anchor,maximum: Anchor,plateau: U32}
  Weighted{entries: List<&2,Entry>,total: W.U64}
type Entry is Data:
  Entry{provider: Provider,weight: U32}
type Row is Data:
  Row{recipe: U32,modifier: U32,provider: Provider}

@unsafe
def context_valid(+minimum: U32,+height: U32) -> Bool:
  Bool.and(U32.is_ne(height,0),U32.is_le(U32.sub(height,1),U32.sub(2147483647,minimum)))

@unsafe
def signed_less(+a: U32,+b: U32) -> Bool:
  U32.is_lt(U32.xor(a,2147483648),U32.xor(b,2147483648))

@unsafe
def saturated(+overflow: Bool,+a: U32,+result: U32) -> U32:
  N.word(overflow,N.word(U32.is_ge(a,2147483648),2147483648,2147483647),result)

@unsafe
def add(+a: U32,+b: U32) -> U32:
  +sum = U32.add(a,b)
  saturated(U32.is_ne(U32.and(U32.and(U32.xor(a,sum),U32.xor(b,sum)),2147483648),0),a,sum)

@unsafe
def subtract(+a: U32,+b: U32) -> U32:
  +difference = U32.sub(a,b)
  saturated(U32.is_ne(U32.and(U32.and(U32.xor(a,b),U32.xor(a,difference)),2147483648),0),a,difference)

@unsafe
def resolve(anchor: Anchor,+minimum: U32,+height: U32) -> U32:
  match anchor:
    case Absolute{x}: x
    case AboveBottom{x}: add(minimum,x)
    case BelowTop{x}: subtract(U32.add(minimum,U32.sub(height,1)),x)

@unsafe
def weight_sum(entries: List<&2,Entry>,+total: W.U64) -> W.U64:
  match entries:
    case Nil{}: total
    case Entry{_,weight} <> rest: weight_sum(rest,W.add(total,W.U64{0,weight}))

@unsafe
def entries_valid(entries: List<&2,Entry>) -> Bool:
  match entries:
    case Nil{}: True{}
    case Entry{provider,_} <> rest: Bool.and(valid(provider),entries_valid(rest))

@unsafe
def valid(provider: Provider) -> Bool:
  match provider:
    case Biased{_,_,+inner}: Bool.and(U32.is_gt(inner,0),U32.is_le(inner,2147483647))
    case VeryBiased{_,_,+inner}: Bool.and(U32.is_gt(inner,0),U32.is_le(inner,2147483647))
    case Trapezoid{_,_,plateau}: U32.is_le(plateau,2147483647)
    case Weighted{+entries,+total}:
      Bool.and(Bool.not(W.equal(total,W.U64{0,0})),Bool.and(W.equal(total,weight_sum(entries,W.U64{0,0})),entries_valid(entries)))
    case _: True{}

# Empty/equal height ranges keep the lower endpoint and consume no draw. A
# complete signed-i32 interval has 2^32 choices and needs no modulo operation.
@unsafe
def full_draw(+draw: R.Draw,+minimum: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: R.Draw{state,W.U64{0,U32.add(minimum,R.low(value))}}

@unsafe
def between_span(+full: Bool,+state: W.U64,+minimum: U32,+maximum: U32) -> R.Draw:
  match full:
    case True{}: full_draw(R.next(state),minimum)
    case False{}: R.integer(state,minimum,maximum)

@unsafe
def between_range(+nonempty: Bool,+state: W.U64,+minimum: U32,+maximum: U32) -> R.Draw:
  match nonempty:
    case False{}: R.Draw{state,W.U64{0,minimum}}
    case True{}: between_span(U32.is_eq(U32.sub(maximum,minimum),4294967295),state,minimum,maximum)

@unsafe
def between(+state: W.U64,+minimum: U32,+maximum: U32) -> R.Draw:
  between_range(signed_less(minimum,maximum),state,minimum,maximum)

@unsafe
def unsigned_between(+nonempty: Bool,+state: W.U64,+maximum: U32) -> R.Draw:
  match nonempty:
    case False{}: R.Draw{state,W.U64{0,0}}
    case True{}: between_span(U32.is_eq(maximum,4294967295),state,0,maximum)

@unsafe
def shifted(+draw: R.Draw,+minimum: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: R.Draw{state,W.U64{0,U32.add(minimum,R.low(value))}}

@unsafe
def biased_second(+draw: R.Draw,+minimum: U32,+inner: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: shifted(R.integer(state,0,U32.sub(U32.add(R.low(value),inner),1)),minimum)

@unsafe
def very_third(+draw: R.Draw,+minimum: U32,+inner: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: between(state,minimum,add(R.low(value),U32.sub(inner,1)))

@unsafe
def very_second(+draw: R.Draw,+minimum: U32,+inner: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: very_third(between(state,minimum,subtract(R.low(value),1)),minimum,inner)

@unsafe
def biased_range(+nonempty: Bool,+very: Bool,+state: W.U64,+minimum: U32,+maximum: U32,+inner: U32) -> R.Draw:
  match nonempty very:
    case False{} _: R.Draw{state,W.U64{0,minimum}}
    case True{} False{}: biased_second(R.integer(state,0,U32.sub(U32.sub(maximum,minimum),inner)),minimum,inner)
    case True{} True{}: very_second(between(state,add(minimum,inner),maximum),minimum,inner)

@unsafe
def biased(+very: Bool,+state: W.U64,+minimum: U32,+maximum: U32,+inner: U32) -> R.Draw:
  biased_range(Bool.and(Bool.not(signed_less(maximum,minimum)),U32.is_ge(U32.sub(maximum,minimum),inner)),very,state,minimum,maximum,inner)

@unsafe
def triangle_second(+draw: R.Draw,+minimum: U32,+first: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: R.Draw{state,W.U64{0,U32.add(minimum,U32.add(first,R.low(value)))}}

@unsafe
def triangle_first(+draw: R.Draw,+minimum: U32,+low: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: triangle_second(unsigned_between(U32.is_ne(low,0),state,low),minimum,R.low(value))

@unsafe
def trapezoid_flat(+flat: Bool,+state: W.U64,+minimum: U32,+maximum: U32,+plateau: U32) -> R.Draw:
  match flat:
    case True{}: between(state,minimum,maximum)
    case False{}:
      +span = U32.sub(maximum,minimum)
      +low = U32.div(U32.sub(span,plateau),2)
      +upper = U32.sub(span,low)
      triangle_first(unsigned_between(U32.is_ne(upper,0),state,upper),minimum,low)

@unsafe
def trapezoid(+empty: Bool,+state: W.U64,+minimum: U32,+maximum: U32,+plateau: U32) -> R.Draw:
  match empty:
    case True{}: R.Draw{state,W.U64{0,minimum}}
    case False{}: trapezoid_flat(U32.is_ge(plateau,U32.sub(maximum,minimum)),state,minimum,maximum,plateau)

@unsafe
def weighted_pick(+selected: Bool,provider: Provider,rest: List<&2,Entry>,+n: W.U64,+weight: U32,+state: W.U64,+minimum: U32,+height: U32) -> R.Draw:
  match selected:
    case True{}: sample(provider,state,minimum,height)
    case False{}: weighted(rest,W.sub(n,W.U64{0,weight}),state,minimum,height)

@unsafe
def weighted(entries: List<&2,Entry>,+n: W.U64,+state: W.U64,+minimum: U32,+height: U32) -> R.Draw:
  match entries:
    case Nil{}: R.Draw{state,W.U64{0,minimum}}
    case Entry{provider,+weight} <> rest: weighted_pick(R.less(n,W.U64{0,weight}),provider,rest,n,weight,state,minimum,height)

@unsafe
def weighted_draw(+draw: R.Draw,entries: List<&2,Entry>,+total: W.U64,+minimum: U32,+height: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: weighted(entries,R.remainder(value,total),state,minimum,height)

# Caller supplies a validated provider and context. Return the advanced state
# so later ordered modifiers continue the same stream. Heights are deliberately
# not clipped here: the walker applies build limits after the placement program.
@unsafe
def sample(provider: Provider,+state: W.U64,+minimum: U32,+height: U32) -> R.Draw:
  match provider:
    case Constant{anchor}: R.Draw{state,W.U64{0,resolve(anchor,minimum,height)}}
    case Uniform{a,b}: between(state,resolve(a,minimum,height),resolve(b,minimum,height))
    case Biased{a,b,inner}: biased(False{},state,resolve(a,minimum,height),resolve(b,minimum,height),inner)
    case VeryBiased{a,b,inner}: biased(True{},state,resolve(a,minimum,height),resolve(b,minimum,height),inner)
    case Trapezoid{a,b,p}:
      +lo = resolve(a,minimum,height)
      +hi = resolve(b,minimum,height)
      trapezoid(signed_less(hi,lo),state,lo,hi,p)
    case Weighted{entries,total}: weighted_draw(R.next(state),entries,total,minimum,height)
