import Base
import ./word64.bend as W
import ./placement_random.bend as R
import ./placement_shuffle.bend as S
import ./ore_random.bend as O

# Java's 48-bit structure stream, including rejection sampling and signed
# nextLong halves. No F32 conversion participates in a seed or coordinate.
type Draw is Data:
  Draw{state: W.U64,value: U32}
type Long is Data:
  Long{state: W.U64,value: W.U64}

@unsafe
def new(+seed: W.U64) -> W.U64:
  W.and(W.xor(seed,W.U64{5,3740067437}),W.U64{65535,4294967295})

@unsafe
def bits(+state: W.U64,+n: U32) -> Draw:
  +state = W.and(W.add(W.mul(state,W.U64{5,3740067437}),W.U64{0,11}),W.U64{65535,4294967295})
  Draw{state,R.low(W.shr(state,U32.sub(48,n)))}

@unsafe
def bounded(+draw: Draw,+bound: U32) -> Draw:
  match draw:
    case Draw{state,+v}: accepted(U32.is_le(U32.add(U32.sub(v,U32.mod(v,bound)),U32.sub(bound,1)),2147483647),state,bound,U32.mod(v,bound))

@unsafe
def accepted(+ok: Bool,+state: W.U64,+bound: U32,+value: U32) -> Draw:
  match ok:
    case True{}: Draw{state,value}
    case False{}: integer_width(U32.is_gt(bound,2147483647),state,bound)

@unsafe
def power_draw(+draw: Draw,+bound: U32) -> Draw:
  match draw:
    case Draw{state,v}: Draw{state,R.low(W.shr(W.mul(W.U64{0,v},W.U64{0,bound}),31))}

@unsafe
def integer(+state: W.U64,+bound: U32) -> Draw:
  # Validated callers supply a nonzero bound. Pool weights fit i32; the
  # legacy frequency reciprocal can saturate to U32::MAX, as in Rust.
  integer_power(U32.is_zero(U32.and(bound,U32.sub(bound,1))),state,bound)

@unsafe
def integer_power(+power: Bool,+state: W.U64,+bound: U32) -> Draw:
  match power:
    case True{}: power_draw(bits(state,31),bound)
    case False{}: integer_width(U32.is_gt(bound,2147483647),state,bound)

@unsafe
def long(+state: W.U64) -> Long:
  long_high(bits(state,32))

@unsafe
def long_high(+draw: Draw) -> Long:
  match draw:
    case Draw{state,high}: long_low(bits(state,32),high)

@unsafe
def long_low(+draw: Draw,+high: U32) -> Long:
  match draw:
    case Draw{state,low}: Long{state,W.add(W.U64{high,0},O.signed(low))}

@unsafe
def shuffle_count(+n: U32,+xs: List<&2,U32>,+state: W.U64) -> S.Shuffled:
  shuffle_more(U32.is_gt(n,1),n,xs,state)

@unsafe
def shuffle_more(+more: Bool,+n: U32,+xs: List<&2,U32>,+state: W.U64) -> S.Shuffled:
  match more:
    case False{}: S.Shuffled{state,xs}
    case True{}: shuffle_draw(integer(state,n),n,xs)

@unsafe
def shuffle_draw(+draw: Draw,+n: U32,+xs: List<&2,U32>) -> S.Shuffled:
  match draw:
    case Draw{state,+j}: shuffle_count(U32.sub(n,1),S.swapped(xs,0,U32.sub(n,1),j,S.get(xs,U32.sub(n,1)),S.get(xs,j),[]),state)

@unsafe
def shuffle(+xs: List<&2,U32>,+state: W.U64) -> S.Shuffled:
  shuffle_count(U32.from_nat(List.length(&2,U32,xs)),xs,state)

@unsafe
def salted(+seed: W.U64,+x: U32,+z: U32,+salt: W.U64) -> W.U64:
  W.add(W.add(W.add(seed,W.mul(O.signed(x),W.U64{79,2570712328})),W.mul(O.signed(z),W.U64{30,4048968661})),salt)

# nextInt's signed bound contract covers pools. A very small legacy frequency
# can saturate its reciprocal above i32::MAX; using the 31-bit rejection loop
# there would never terminate. Extend that exceptional range with 32-bit draws.
@unsafe
def integer_width(wide: Bool,state: W.U64,bound: U32) -> Draw:
  match wide:
    case False{}: bounded(bits(state,31),bound)
    case True{}: wide_draw(bits(state,32),bound)

@unsafe
def wide_draw(draw: Draw,+bound: U32) -> Draw:
  match draw:
    case Draw{state,+v}: wide_accepted(U32.is_le(U32.sub(v,U32.mod(v,bound)),U32.sub(4294967295,U32.sub(bound,1))),state,bound,U32.mod(v,bound))

@unsafe
def wide_accepted(ok: Bool,state: W.U64,bound: U32,value: U32) -> Draw:
  match ok:
    case True{}: Draw{state,value}
    case False{}: wide_draw(bits(state,32),bound)
