import Base
import ./word64.bend as W
import ./numbers.bend as N

# Software IEEE-754 binary64 addition for structure entity transforms. Keep
# fractional positions at distant world coordinates; converting through F32
# would move hanging entities and lose sub-block positions. Rounds once using
# nearest/ties-to-even, including subnormals and signed zero.
type Parts is Data:
  Parts{sign: U32,exponent: U32,mantissa: W.U64}

@unsafe
def magnitude(value: W.U64) -> W.U64:
  match value:
    case W.U64{hi,lo}: W.U64{U32.and(hi,2147483647),lo}

@unsafe
def negate(value: W.U64) -> W.U64:
  W.xor(value,W.U64{2147483648,0})

@unsafe
def exponent(value: W.U64) -> U32:
  match value:
    case W.U64{hi,_}: U32.and(U32.shrn(hi,20n),2047)

@unsafe
def finite(+value: W.U64) -> Bool:
  U32.is_ne(exponent(value),2047)

@unsafe
def unpack(value: W.U64) -> Parts:
  match value:
    case W.U64{+hi,lo}: unpack_exp(U32.and(U32.shrn(hi,20n),2047),U32.and(hi,2147483648),W.U64{U32.and(hi,1048575),lo})

@unsafe
def unpack_exp(+exp: U32,sign: U32,mantissa: W.U64) -> Parts:
  match exp:
    case 0: Parts{sign,1,W.shl(mantissa,3)}
    case _: Parts{sign,exp,W.shl(W.add(mantissa,W.U64{1048576,0}),3)}

@unsafe
def jam(+value: W.U64,+shift: U32) -> W.U64:
  jam_large(U32.is_ge(shift,64),value,shift)

@unsafe
def jam_large(large: Bool,+value: W.U64,+shift: U32) -> W.U64:
  match large:
    case True{}: W.U64{0,W.flag(Bool.not(W.equal(value,W.U64{0,0})))}
    case False{}: jam_low(W.shr(value,shift),Bool.not(W.equal(W.and(value,W.sub(W.shl(W.U64{0,1},shift),W.U64{0,1})),W.U64{0,0})))

@unsafe
def jam_low(value: W.U64,sticky: Bool) -> W.U64:
  match value:
    case W.U64{hi,lo}: W.U64{hi,U32.or(lo,W.flag(sticky))}

@unsafe
def add(+a: W.U64,+b: W.U64) -> W.U64:
  add_finite(Bool.and(finite(a),finite(b)),a,b)

@unsafe
def sub(a: W.U64,b: W.U64) -> W.U64:
  add(a,negate(b))

@unsafe
def add_finite(ok: Bool,+a: W.U64,+b: W.U64) -> W.U64:
  match ok:
    case True{}: ordered(N.greater(magnitude(b),magnitude(a)),a,b)
    case False{}: exceptional(a,b)

@unsafe
def exceptional(+a: W.U64,+b: W.U64) -> W.U64:
  exceptional_nan(Bool.or(N.greater(magnitude(a),W.U64{2146435072,0}),Bool.or(N.greater(magnitude(b),W.U64{2146435072,0}),Bool.and(W.equal(magnitude(a),W.U64{2146435072,0}),W.equal(a,negate(b))))),a,b)

@unsafe
def exceptional_nan(nan: Bool,+a: W.U64,b: W.U64) -> W.U64:
  match nan:
    case True{}: W.U64{2146959360,0}
    case False{}: infinite_a(U32.is_eq(exponent(a),2047),a,b)

@unsafe
def infinite_a(infinite: Bool,a: W.U64,b: W.U64) -> W.U64:
  match infinite:
    case True{}: a
    case False{}: b

@unsafe
def ordered(reverse: Bool,a: W.U64,b: W.U64) -> W.U64:
  match reverse:
    case True{}: aligned(unpack(b),unpack(a))
    case False{}: aligned(unpack(a),unpack(b))

@unsafe
def aligned(a: Parts,b: Parts) -> W.U64:
  match a b:
    case Parts{+sign,+exp,m} Parts{other,e,n}: combined(U32.is_eq(sign,other),sign,exp,m,jam(n,U32.sub(exp,e)))

@unsafe
def combined(same: Bool,sign: U32,exp: U32,+m: W.U64,+n: W.U64) -> W.U64:
  match same:
    case True{}: normalize(sign,exp,W.add(m,n))
    case False{}: difference(W.equal(m,n),sign,exp,W.sub(m,n))

@unsafe
def difference(zero: Bool,sign: U32,exp: U32,m: W.U64) -> W.U64:
  match zero:
    case True{}: W.U64{0,0}
    case False{}: normalize(sign,exp,m)

@unsafe
def normalize(+sign: U32,+exp: U32,+m: W.U64) -> W.U64:
  normalize_zero(W.equal(m,W.U64{0,0}),sign,exp,m)

@unsafe
def normalize_zero(zero: Bool,+sign: U32,+exp: U32,+m: W.U64) -> W.U64:
  match zero:
    case True{}: W.U64{sign,0}
    case False{}: normalize_high(N.greater(m,W.U64{16777215,4294967295}),sign,exp,m)

@unsafe
def normalize_high(high: Bool,+sign: U32,+exp: U32,+m: W.U64) -> W.U64:
  match high:
    case True{}: round(sign,U32.inc(exp),jam(m,1))
    case False{}: normalize_low(Bool.and(U32.is_gt(exp,1),Bool.not(N.greater(m,W.U64{8388607,4294967295}))),sign,exp,m)

@unsafe
def normalize_low(low: Bool,+sign: U32,+exp: U32,+m: W.U64) -> W.U64:
  match low:
    case True{}: normalize(sign,U32.sub(exp,1),W.shl(m,1))
    case False{}: round(sign,exp,m)

@unsafe
def round(sign: U32,exp: U32,+m: W.U64) -> W.U64:
  +kept = W.shr(m,3)
  +tail = U32.and(N.low(m),7)
  rounded(sign,exp,W.add(kept,W.U64{0,W.flag(Bool.or(U32.is_gt(tail,4),Bool.and(U32.is_eq(tail,4),U32.is_ne(U32.and(N.low(kept),1),0))))}))

@unsafe
def rounded(+sign: U32,+exp: U32,+m: W.U64) -> W.U64:
  rounded_carry(N.greater(m,W.U64{2097151,4294967295}),sign,exp,m)

@unsafe
def rounded_carry(carry: Bool,sign: U32,exp: U32,m: W.U64) -> W.U64:
  match carry:
    case True{}: pack(sign,U32.inc(exp),W.shr(m,1))
    case False{}: pack(sign,exp,m)

@unsafe
def pack(+sign: U32,+exp: U32,+m: W.U64) -> W.U64:
  pack_overflow(U32.is_ge(exp,2047),sign,exp,m)

@unsafe
def pack_overflow(overflow: Bool,sign: U32,+exp: U32,+m: W.U64) -> W.U64:
  match overflow:
    case True{}: W.U64{U32.or(sign,2146435072),0}
    case False{}: pack_subnormal(Bool.and(U32.is_eq(exp,1),Bool.not(N.greater(m,W.U64{1048575,4294967295}))),sign,exp,m)

@unsafe
def pack_subnormal(subnormal: Bool,sign: U32,exp: U32,m: W.U64) -> W.U64:
  match subnormal m:
    case True{} W.U64{hi,lo}: W.U64{U32.or(sign,hi),lo}
    case False{} W.U64{hi,lo}: W.U64{U32.or(sign,U32.or(U32.shln(exp,20n),U32.and(hi,1048575))),lo}

# Saturating i32 conversion, with optional floor for missing blockPos anchors.
@unsafe
def integer(+value: W.U64,+floor: Bool) -> U32:
  integer_nan(N.greater(magnitude(value),W.U64{2146435072,0}),value,floor)

@unsafe
def integer_nan(nan: Bool,+value: W.U64,+floor: Bool) -> U32:
  match nan value:
    case True{} _: 0
    case False{} W.U64{+hi,_}: integer_range(exponent(value),U32.is_ne(U32.and(hi,2147483648),0),value,floor)

@unsafe
def integer_range(+exp: U32,+negative: Bool,+value: W.U64,+floor: Bool) -> U32:
  integer_big(U32.is_ge(exp,1054),exp,negative,value,floor)

@unsafe
def integer_big(big: Bool,+exp: U32,+negative: Bool,value: W.U64,floor: Bool) -> U32:
  match big:
    case True{}: U32.add(2147483647,W.flag(negative))
    case False{}: integer_small(U32.is_lt(exp,1023),exp,negative,value,floor)

@unsafe
def integer_small(small: Bool,exp: U32,+negative: Bool,+value: W.U64,+floor: Bool) -> U32:
  match small value:
    case True{} _: U32.sub(0,W.flag(Bool.and(negative,Bool.and(floor,Bool.not(W.equal(magnitude(value),W.U64{0,0}))))))
    case False{} W.U64{hi,lo}: integer_shift(W.U64{U32.or(U32.and(hi,1048575),1048576),lo},U32.sub(1075,exp),negative,floor)

@unsafe
def integer_shift(+m: W.U64,+shift: U32,+negative: Bool,+floor: Bool) -> U32:
  integer_sign(negative,N.low(W.shr(m,shift)),Bool.and(floor,Bool.not(W.equal(W.and(m,W.sub(W.shl(W.U64{0,1},shift),W.U64{0,1})),W.U64{0,0}))))

@unsafe
def integer_sign(negative: Bool,value: U32,extra: Bool) -> U32:
  match negative:
    case True{}: U32.sub(0,U32.add(value,W.flag(extra)))
    case False{}: value
