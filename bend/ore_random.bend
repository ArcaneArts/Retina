import Base
import ./word64.bend as W
import ./ore_data.bend as O

# Exact SplitMix64 state; no seed or signed coordinate is converted to F32.
type Draw is Data:
  Draw{state: W.U64,value: U32}

@unsafe
def signed(+value: U32) -> W.U64:
  W.U64{U32.sub(0,U32.shrn(value,31n)),value}

@unsafe
def seed(world: W.U64,x: U32,z: U32,id: U32) -> W.U64:
  W.xor(W.xor(world,W.mul(signed(x),W.U64{1663821211,3652509721})),
    W.xor(W.mul(signed(z),W.U64{2654435769,2135587861}),W.mul(W.U64{0,id},W.U64{2496678331,321982955})))

@unsafe
def mixed(+value: W.U64) -> U32:
  +a = W.mul(W.xor(value,W.shr(value,30)),W.U64{3210233709,484763065})
  +b = W.mul(W.xor(a,W.shr(a,27)),W.U64{2496678331,321982955})
  low(W.xor(b,W.shr(b,31)))

@unsafe
def low(value: W.U64) -> U32:
  match value:
    case W.U64{_,low}: low

@unsafe
def next(state: W.U64) -> Draw:
  +state = W.add(state,W.U64{2654435769,2135587861})
  Draw{state,mixed(state)}

# Inclusive intervals include the complete signed-i32 range. A full 2^32 span
# consumes the raw draw instead of wrapping its modulus to zero.
@unsafe
def interval(full: Bool,value: U32,minimum: U32,range: U32) -> U32:
  match full:
    case True{}: U32.add(minimum,value)
    case False{}: U32.add(minimum,U32.mod(value,U32.inc(range)))

@unsafe
def integer(state: W.U64,+minimum: U32,maximum: U32) -> Draw:
  integer_draw(next(state),minimum,U32.sub(maximum,minimum))

@unsafe
def integer_draw(draw: Draw,minimum: U32,+range: U32) -> Draw:
  match draw:
    case Draw{state,value}:
      Draw{state,interval(U32.is_eq(range,4294967295),value,minimum,range)}

@unsafe
def unit(value: U32) -> F32:
  F32.div(U32.to_f32(U32.shrn(value,8n)),16777216.0)

@unsafe
def triangular(active: Bool,state: W.U64,+minimum: U32,+range: U32,plateau: U32) -> Draw:
  match active:
    case False{}: integer(state,minimum,U32.add(minimum,range))
    case True{}:
      +a = U32.div(U32.sub(range,plateau),2)
      triangle_a(integer(state,0,a),minimum,U32.sub(range,a))

@unsafe
def triangle_a(draw: Draw,minimum: U32,b: U32) -> Draw:
  match draw:
    case Draw{state,x}: triangle_b(integer(state,0,b),minimum,x)

@unsafe
def triangle_b(draw: Draw,minimum: U32,x: U32) -> Draw:
  match draw:
    case Draw{state,y}: Draw{state,U32.add(minimum,U32.add(x,y))}

@unsafe
def height(+store: O.Store,id: U32,state: W.U64) -> Draw:
  +at = O.recipe_at(id)
  +minimum = O.word(store,U32.add(at,7))
  +range = U32.sub(O.word(store,U32.add(at,8)),minimum)
  +plateau = O.word(store,U32.add(at,10))
  triangular(Bool.and(U32.is_eq(O.word(store,U32.add(at,9)),1),U32.is_lt(plateau,range)),state,minimum,range,plateau)

@unsafe
def local(seed: W.U64,index: U32) -> W.U64:
  W.xor(seed,W.mul(W.U64{0,U32.inc(index)},W.U64{3605593784,1717173651}))

@unsafe
def advance(state: W.U64,n: U32) -> W.U64:
  W.add(state,W.mul(W.U64{0,n},W.U64{2654435769,2135587861}))

# Every attempt in a recipe consumes two X/Z draws plus one uniform or two
# triangular height draws. Jumping the additive state permits parallel leaves.
@unsafe
def attempt_draws(+store: O.Store,id: U32) -> U32:
  +at = O.recipe_at(id)
  +range = U32.sub(O.word(store,U32.add(at,8)),O.word(store,U32.add(at,7)))
  U32.add(3,W.flag(Bool.and(U32.is_eq(O.word(store,U32.add(at,9)),1),U32.is_lt(O.word(store,U32.add(at,10)),range))))
