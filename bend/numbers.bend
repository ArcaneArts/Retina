import Base
import ./word64.bend as W
import ./reader.bend as R

# Registry JSON retains exact i64/f64 words. Convert only where a typed GPU
# parameter needs F32. These operations round once, to nearest/ties-to-even;
# adding converted high/low halves would double-round some integers.
def flag(b: Bool) -> U32:
  match b:
    case False{}: 0
    case True{}: 1

def greater(a: W.U64,b: W.U64) -> Bool:
  match a b:
    case W.U64{+ah,al} W.U64{+bh,bl}:
      Bool.or(U32.is_gt(ah,bh),Bool.and(U32.is_eq(ah,bh),U32.is_gt(al,bl)))

def low(a: W.U64) -> U32:
  match a:
    case W.U64{_,lo}: lo

# n is 1..53 here, so the word-pair masked-shift contract is unambiguous.
def rounded(+mantissa: W.U64,+n: U32) -> U32:
  +kept = low(W.shr(mantissa,n))
  +half = W.shl(W.U64{0,1},U32.sub(n,1))
  +remainder = W.and(mantissa,W.sub(W.shl(W.U64{0,1},n),W.U64{0,1}))
  U32.add(kept,flag(Bool.or(greater(remainder,half),
    Bool.and(W.equal(remainder,half),U32.is_ne(U32.and(kept,1),0)))))

def special(nan: Bool,+hi: U32,lo: U32) -> U32:
  match nan:
    case False{}: 2139095040
    case True{}: U32.or(2143289344,U32.or(U32.shln(U32.and(hi,1048575),3n),U32.shrn(lo,29n)))

def finite_large(overflow: Bool,+exponent: U32,+hi: U32,+lo: U32) -> U32:
  match overflow:
    case True{}: 2139095040
    case False{}:
      +fraction = U32.or(U32.shln(U32.and(hi,1048575),3n),U32.shrn(lo,29n))
      +tail = U32.and(lo,536870911)
      U32.add(U32.add(U32.shln(U32.sub(exponent,896),23n),fraction),
        flag(Bool.or(U32.is_gt(tail,268435456),
          Bool.and(U32.is_eq(tail,268435456),U32.is_ne(U32.and(fraction,1),0)))))

def finite_small(underflow: Bool,exponent: U32,hi: U32,lo: U32) -> U32:
  match underflow:
    case True{}: 0
    case False{}: rounded(W.U64{U32.or(U32.and(hi,1048575),1048576),lo},U32.sub(926,exponent))

def finite(normal: Bool,+exponent: U32,hi: U32,lo: U32) -> U32:
  match normal:
    case True{}: finite_large(U32.is_gt(exponent,1150),exponent,hi,lo)
    case False{}: finite_small(U32.is_lt(exponent,873),exponent,hi,lo)

def double_magnitude(exceptional: Bool,+exponent: U32,+hi: U32,+lo: U32) -> U32:
  match exceptional:
    case True{}: special(Bool.or(U32.is_ne(U32.and(hi,1048575),0),U32.is_ne(lo,0)),hi,lo)
    case False{}: finite(U32.is_ge(exponent,897),exponent,hi,lo)

def double_bits(+hi: U32,+lo: U32) -> U32:
  +exponent = U32.and(U32.shrn(hi,20n),2047)
  U32.or(U32.and(hi,2147483648),double_magnitude(U32.is_eq(exponent,2047),exponent,hi,lo))

def double(hi: U32,lo: U32) -> F32:
  R.float(double_bits(hi,lo))

@unsafe
def highest(+value: U32,acc: U32) -> U32:
  highest_more(U32.is_gt(value,1),value,acc)

@unsafe
def highest_more(more: Bool,value: U32,acc: U32) -> U32:
  match more:
    case False{}: acc
    case True{}: highest(U32.shrn(value,1n),U32.inc(acc))

def integer_magnitude(small: Bool,+value: W.U64,+bit: U32) -> U32:
  match small:
    case True{}: F32.bits(U32.to_f32(low(value)))
    case False{}: U32.add(U32.shln(U32.add(bit,126),23n),rounded(value,U32.sub(bit,23)))

def integer_high(zero: Bool,+hi: U32,+lo: U32) -> U32:
  match zero:
    case True{}: integer_magnitude(U32.is_le(highest(lo,0),23),W.U64{hi,lo},highest(lo,0))
    case False{}: integer_magnitude(False{},W.U64{hi,lo},highest(hi,32))

def integer_negative(value: W.U64) -> U32:
  match value:
    case W.U64{+hi,lo}: U32.or(2147483648,integer_high(U32.is_zero(hi),hi,lo))

def integer_sign(negative: Bool,+value: W.U64) -> U32:
  match negative value:
    case False{} W.U64{+hi,lo}: integer_high(U32.is_zero(hi),hi,lo)
    case True{} _: integer_negative(W.sub(W.U64{0,0},value))

def integer_bits(+hi: U32,lo: U32) -> U32:
  integer_sign(U32.is_ne(U32.and(hi,2147483648),0),W.U64{hi,lo})

def integer(hi: U32,lo: U32) -> F32:
  R.float(integer_bits(hi,lo))
