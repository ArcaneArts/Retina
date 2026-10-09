import Base
import ./word64.bend as W
import ./placement_random.bend as R
import ./noise.bend as N

# Typed forms of the shared registry's decoration count/offset providers.
# Values use signed-i32 bits. Validation matches the supported [-4096,4096]
# placement range; sampling never substitutes an expected/average count.
type Provider is Data:
  Constant{value: U32}
  Uniform{minimum: U32,maximum: U32}
  Biased{minimum: U32,maximum: U32}
  Trapezoid{minimum: U32,maximum: U32,plateau: U32}
  Clamped{source: Provider,minimum: U32,maximum: U32}
  Normal{mean: F32,deviation: F32,minimum: U32,maximum: U32}
  Weighted{entries: List<&2,Entry>,total: W.U64}
type Entry is Data:
  Entry{provider: Provider,weight: U32}

@unsafe
def bounded(+minimum: U32,+maximum: U32) -> Bool:
  Bool.and(U32.is_le(U32.add(minimum,4096),8192),Bool.and(U32.is_le(U32.add(maximum,4096),8192),
    U32.is_le(U32.xor(minimum,2147483648),U32.xor(maximum,2147483648))))

@unsafe
def finite(+value: F32) -> Bool:
  match value:
    case F32{bits}: U32.is_ne(U32.and(U32{bits},2139095040),2139095040)

@unsafe
def weight_sum(entries: List<&2,Entry>,+total: W.U64) -> W.U64:
  match entries:
    case Nil{}: total
    case Entry{_,weight} <> rest: weight_sum(rest,W.add(total,W.U64{0,weight}))

@unsafe
def entries_valid(entries: List<&2,Entry>) -> Bool:
  match entries:
    case Nil{}: True{}
    case Entry{provider,+weight} <> rest:
      Bool.and(Bool.or(U32.is_zero(weight),valid(provider)),entries_valid(rest))

@unsafe
def valid(provider: Provider) -> Bool:
  match provider:
    case Constant{+value}: bounded(value,value)
    case Uniform{a,b}: bounded(a,b)
    case Biased{a,b}: bounded(a,b)
    case Trapezoid{+a,+b,p}: Bool.and(bounded(a,b),U32.is_le(p,2147483647))
    case Clamped{source,a,b}: Bool.and(valid(source),bounded(a,b))
    case Normal{+mean,+deviation,a,b}:
      Bool.and(bounded(a,b),Bool.and(finite(mean),Bool.and(finite(deviation),F32.is_ge(deviation,0.0))))
    case Weighted{+entries,+total}:
      Bool.and(Bool.not(W.equal(total,W.U64{0,0})),Bool.and(W.equal(weight_sum(entries,W.U64{0,0}),total),entries_valid(entries)))

@unsafe
def inside(+a: U32,+b: U32,+minimum: U32,+maximum: U32) -> Bool:
  Bool.and(U32.is_ge(U32.xor(a,2147483648),U32.xor(minimum,2147483648)),
    U32.is_le(U32.xor(b,2147483648),U32.xor(maximum,2147483648)))

@unsafe
def entries_within(entries: List<&2,Entry>,+a: U32,+b: U32) -> Bool:
  match entries:
    case Nil{}: True{}
    case Entry{p,+w} <> rest: Bool.and(Bool.or(U32.is_zero(w),within(p,a,b)),entries_within(rest,a,b))

@unsafe
def within(provider: Provider,+minimum: U32,+maximum: U32) -> Bool:
  match provider:
    case Constant{+x}: inside(x,x,minimum,maximum)
    case Uniform{a,b}: inside(a,b,minimum,maximum)
    case Biased{a,b}: inside(a,b,minimum,maximum)
    case Trapezoid{a,b,_}: inside(a,b,minimum,maximum)
    case Clamped{_,a,b}: inside(a,b,minimum,maximum)
    case Normal{_,_,a,b}: inside(a,b,minimum,maximum)
    case Weighted{entries,_}: entries_within(entries,minimum,maximum)

@unsafe
def clamp(+value: U32,+minimum: U32,+maximum: U32) -> U32:
  U32.xor(U32.min(U32.max(U32.xor(value,2147483648),U32.xor(minimum,2147483648)),U32.xor(maximum,2147483648)),2147483648)

@unsafe
def clamped(+draw: R.Draw,+minimum: U32,+maximum: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: R.Draw{state,W.U64{0,clamp(R.low(value),minimum,maximum)}}

@unsafe
def biased_a(+draw: R.Draw,+minimum: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: R.integer(state,minimum,U32.add(minimum,R.low(value)))

@unsafe
def triangle_b(+draw: R.Draw,+minimum: U32,+a: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: R.Draw{state,W.U64{0,U32.add(minimum,U32.add(a,R.low(value)))}}

@unsafe
def triangle_a(+draw: R.Draw,+minimum: U32,+b: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: triangle_b(R.integer(state,0,b),minimum,R.low(value))

@unsafe
def triangle(+flat: Bool,+state: W.U64,+a: U32,+b: U32,+plateau: U32) -> R.Draw:
  match flat:
    case True{}: R.integer(state,a,b)
    case False{}:
      +low = U32.div(U32.sub(U32.sub(b,a),plateau),2)
      triangle_a(R.integer(state,0,low),a,U32.sub(U32.sub(b,a),low))

@unsafe
def signed_float(+value: U32) -> F32:
  N.pick(U32.is_ge(value,2147483648),F32.neg(U32.to_f32(U32.sub(0,value))),U32.to_f32(value))

@unsafe
def normal_b(+draw: R.Draw,+u: F32,+mean: F32,+deviation: F32,+a: U32,+b: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}:
      +gaussian = F32.mul(F32.sqrt(F32.mul(F32.neg(2.0),F32.log(F32.max(u,0.0000000000000000000000000000000000000117549435)))),
        F32.cos(F32.mul(6.283185307179586,R.unit(value))))
      R.Draw{state,W.U64{0,N.signed(F32.trunc(F32.min(signed_float(b),F32.max(signed_float(a),F32.add(mean,F32.mul(gaussian,deviation))))))}}

@unsafe
def normal_a(+draw: R.Draw,+mean: F32,+deviation: F32,+a: U32,+b: U32) -> R.Draw:
  match draw:
    case R.Draw{state,value}: normal_b(R.next(state),R.unit(value),mean,deviation,a,b)

@unsafe
def weighted_pick(+selected: Bool,provider: Provider,rest: List<&2,Entry>,+n: W.U64,+weight: U32,+state: W.U64) -> R.Draw:
  match selected:
    case True{}: sample(provider,state)
    case False{}: weighted(rest,W.sub(n,W.U64{0,weight}),state)

@unsafe
def weighted(entries: List<&2,Entry>,+n: W.U64,+state: W.U64) -> R.Draw:
  match entries:
    case Nil{}: R.Draw{state,W.U64{0,0}}
    case Entry{provider,+weight} <> rest: weighted_pick(R.less(n,W.U64{0,weight}),provider,rest,n,weight,state)

@unsafe
def weighted_draw(+draw: R.Draw,entries: List<&2,Entry>,+total: W.U64) -> R.Draw:
  match draw:
    case R.Draw{state,value}: weighted(entries,R.remainder(value,total),state)

# Sampling consumes the same number of draws regardless of scheduling. The
# returned state lets a placement walker split count children from one shared
# parent budget instead of independently multiplying each selector's density.
@unsafe
def sample(provider: Provider,+state: W.U64) -> R.Draw:
  match provider:
    case Constant{value}: R.Draw{state,W.U64{0,value}}
    case Uniform{a,b}: R.integer(state,a,b)
    case Biased{+a,b}: biased_a(R.integer(state,0,U32.sub(b,a)),a)
    case Trapezoid{+a,+b,+p}: triangle(U32.is_ge(p,U32.sub(b,a)),state,a,b,p)
    case Clamped{source,a,b}: clamped(sample(source,state),a,b)
    case Normal{mean,deviation,a,b}: normal_a(R.next(state),mean,deviation,a,b)
    case Weighted{entries,total}: weighted_draw(R.next(state),entries,total)
