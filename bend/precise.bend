import Base
import ./noise.bend as N

# Compensated two-F32 arithmetic for transformed world coordinates. Converting
# an i32 to one F32 before applying the density DAG loses neighboring blocks
# beyond 2^24. Keep the residual through arithmetic and lattice conversion.
# This is not an IEEE F64 implementation; transcendental density operators use
# F32. No handwritten native or shader arithmetic is involved.
type Number is Data:
  Number{high: F32,low: F32}

def scalar(value: F32) -> Number:
  Number{value,0.0}

def value(a: Number) -> F32:
  match a:
    case Number{a,b}: F32.add(a,b)

def finite(+x: F32) -> Bool:
  Bool.and(F32.is_ge(x,F32.neg(340282346638528859811704183484516925440.0)),
    F32.is_le(x,340282346638528859811704183484516925440.0))

def normalized(ok: Bool,+a: F32,+b: F32) -> Number:
  match ok:
    case False{}: Number{a,0.0}
    case True{}:
      +s = F32.add(a,b)
      +v = F32.sub(s,a)
      Number{s,F32.add(F32.sub(a,F32.sub(s,v)),F32.sub(b,v))}

def normalize(+a: F32,b: F32) -> Number:
  normalized(finite(a),a,b)

def unsigned(+a: U32) -> Number:
  normalize(F32.mul(U32.to_f32(U32.shrn(a,16n)),65536.0),U32.to_f32(U32.and(a,65535)))

def negate(a: Number) -> Number:
  match a:
    case Number{a,b}: Number{F32.neg(a),F32.neg(b)}

def integer_sign(negative: Bool,a: U32) -> Number:
  match negative:
    case False{}: unsigned(a)
    case True{}: negate(unsigned(U32.sub(0,a)))

def integer(+a: U32) -> Number:
  integer_sign(U32.is_ne(U32.and(a,2147483648),0),a)

def add(a: Number,b: Number) -> Number:
  match a b:
    case Number{+a,al} Number{+b,bl}:
      +s = F32.add(a,b)
      +v = F32.sub(s,a)
      normalize(s,F32.add(F32.add(F32.sub(a,F32.sub(s,v)),F32.sub(b,v)),F32.add(al,bl)))

def sub(a: Number,b: Number) -> Number:
  add(a,negate(b))

def product_valid(ok: Bool,+a: F32,+b: F32,+p: F32) -> Number:
  match ok:
    case False{}: Number{p,0.0}
    case True{}:
      +ac = F32.mul(a,4097.0)
      +bc = F32.mul(b,4097.0)
      +ah = F32.sub(ac,F32.sub(ac,a))
      +bh = F32.sub(bc,F32.sub(bc,b))
      +al = F32.sub(a,ah)
      +bl = F32.sub(b,bh)
      Number{p,F32.add(F32.add(F32.add(F32.sub(F32.mul(ah,bh),p),F32.mul(ah,bl)),F32.mul(al,bh)),F32.mul(al,bl))}

def product(+a: F32,+b: F32) -> Number:
  product_valid(Bool.and(Bool.and(F32.is_le(F32.abs(a),1.0e30),F32.is_le(F32.abs(b),1.0e30)),
    finite(F32.mul(a,b))),a,b,F32.mul(a,b))

def multiply(a: Number,b: Number) -> Number:
  match a b:
    case Number{+a,+al} Number{+b,+bl}:
      add(product(a,b),normalize(F32.add(F32.mul(a,bl),F32.mul(al,b)),F32.mul(al,bl)))

def quotient_finite(ok: Bool,a: Number,+b: Number,+q: F32) -> Number:
  match ok:
    case False{}: scalar(q)
    case True{}: add(scalar(q),scalar(F32.div(value(sub(a,multiply(scalar(q),b))),value(b))))

def quotient(a: Number,+b: Number,+q: F32) -> Number:
  quotient_finite(finite(q),a,b,q)

def divide(+a: Number,+b: Number) -> Number:
  quotient(a,b,F32.div(value(a),value(b)))

def scale(a: Number,b: F32) -> Number:
  multiply(a,scalar(b))

def choose(ok: Bool,a: Number,b: Number) -> Number:
  match ok:
    case True{}: a
    case False{}: b

def less(a: Number,b: Number) -> Bool:
  match a b:
    case Number{+a,al} Number{+b,bl}: Bool.or(F32.is_lt(a,b),Bool.and(F32.is_eq(a,b),F32.is_lt(al,bl)))

def minimum(+a: Number,+b: Number) -> Number:
  choose(less(a,b),a,b)

def maximum(+a: Number,+b: Number) -> Number:
  choose(less(a,b),b,a)

def absolute(+a: Number) -> Number:
  choose(less(a,scalar(0.0)),negate(a),a)

def mix(+a: Number,b: Number,t: Number) -> Number:
  add(a,multiply(sub(b,a),t))

# floor(high) stays exact as an F32 integer. The small remainder is carried
# separately, including a low word that crosses an integer boundary.
def floor(a: Number) -> Number:
  match a:
    case Number{+a,b}:
      +base = F32.floor(a)
      add(scalar(base),scalar(F32.floor(F32.add(F32.sub(a,base),b))))

def fraction(+a: Number) -> F32:
  value(sub(a,floor(a)))

def axis(a: Number) -> N.Axis:
  match a:
    case Number{+a,b}:
      +base = F32.floor(a)
      # Keep the hash cell modulo 2^32 before converting to U32. Negative
      # floor values use an explicit sign rather than an unsigned C cast.
      +magnitude = F32.abs(base)
      +cell = F32.sub(magnitude,F32.mul(F32.floor(F32.div(magnitude,4294967296.0)),4294967296.0))
      +part = F32.add(F32.sub(a,base),b)
      +carry = F32.floor(part)
      N.Axis{U32.add(N.signed(N.pick(F32.is_lt(base,0.0),F32.neg(cell),cell)),N.signed(carry)),F32.sub(part,carry)}
