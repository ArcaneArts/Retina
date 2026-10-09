import Base
import ./word64.bend as W

# Placement keeps the complete seed and random word. Weighted registry lists
# may sum to more than 2^32 even though each individual weight is a U32.
type Draw is Data:
  Draw{state: W.U64,value: W.U64}

@unsafe
def mixed(+value: W.U64) -> W.U64:
  +a = W.mul(W.xor(value,W.shr(value,30)),W.U64{3210233709,484763065})
  +b = W.mul(W.xor(a,W.shr(a,27)),W.U64{2496678331,321982955})
  W.xor(b,W.shr(b,31))

@unsafe
def next(+state: W.U64) -> Draw:
  +state = W.add(state,W.U64{2654435769,2135587861})
  Draw{state,mixed(state)}

@unsafe
def less(+a: W.U64,+b: W.U64) -> Bool:
  match a b:
    case W.U64{+ah,al} W.U64{+bh,bl}:
      Bool.or(U32.is_lt(ah,bh),Bool.and(U32.is_eq(ah,bh),U32.is_lt(al,bl)))

@unsafe
def reduction(+subtract: Bool,+value: W.U64,+divisor: W.U64) -> W.U64:
  match subtract:
    case False{}: value
    case True{}: W.sub(value,divisor)

@unsafe
def highest(+value: W.U64) -> U32:
  match value:
    case W.U64{high,_}: U32.shrn(high,31n)

@unsafe
def remainder_bits(+n: Nat,+value: W.U64,+divisor: W.U64,+remainder: W.U64) -> W.U64:
  match n:
    case 0n: remainder
    case 1n+p:
      +shifted = W.add(W.shl(remainder,1),W.U64{0,highest(value)})
      remainder_bits(p,W.shl(value,1),divisor,
        reduction(Bool.or(U32.is_ne(highest(remainder),0),Bool.not(less(shifted,divisor))),shifted,divisor))

# Caller validates a nonzero divisor. Long division also handles divisors with
# bit 63 set: the overflow bit participates in the subtraction decision.
@unsafe
def remainder(+value: W.U64,+divisor: W.U64) -> W.U64:
  remainder_bits(64n,value,divisor,W.U64{0,0})

@unsafe
def low(+value: W.U64) -> U32:
  match value:
    case W.U64{_,low}: low

@unsafe
def unit(+value: W.U64) -> F32:
  F32.div(U32.to_f32(U32.shrn(low(value),8n)),16777216.0)

@unsafe
def integer(+state: W.U64,+minimum: U32,+maximum: U32) -> Draw:
  integer_draw(next(state),minimum,U32.inc(U32.sub(maximum,minimum)))

@unsafe
def integer_draw(draw: Draw,+minimum: U32,+span: U32) -> Draw:
  match draw:
    case Draw{state,value}: Draw{state,W.U64{0,U32.add(minimum,U32.mod(low(value),span))}}
