import Base
import ./word64.bend as W
import ./numbers.bend as Numbers
import ./density.bend as Q
import ./placement_data.bend as P
import ./placement_random.bend as R
import ./noise.bend as N
import ./reader.bend as Reader

# Minecraft initializes these permutations/offsets in the shared exporter.
# Coordinate phases are Q40 modulo 256, retaining distant signed coordinates
# without a backend f64 requirement or a large-coordinate F32 subtraction.
type Layer is Data:
  Layer{frequency: W.U64,coefficient: W.U64,amplitude: F32,x: W.U64,y: W.U64,z: W.U64,permutation: Q.Table<U32>}
type Program is Data:
  Program{layers: List<&2,Layer>,scale: F32}
type Wide is Data:
  Wide{low: U32,middle: U32,high: U32}

def mask(+value: W.U64) -> W.U64:
  W.and(value,W.U64{65535,4294967295})

def negative(+value: W.U64) -> Bool:
  match value:
    case W.U64{hi,_}: U32.is_ne(U32.and(hi,2147483648),0)

def exponent(+value: W.U64) -> U32:
  match value:
    case W.U64{hi,_}: U32.and(U32.shrn(hi,20n),2047)

def finite(+value: W.U64) -> Bool:
  U32.is_ne(exponent(value),2047)

def mantissa(+value: W.U64) -> W.U64:
  match value:
    case W.U64{+hi,lo}: W.U64{U32.or(U32.and(hi,1048575),N.word(U32.is_zero(exponent(value)),0,1048576)),lo}

def sign(+neg: Bool,+value: W.U64) -> W.U64:
  match neg:
    case False{}: mask(value)
    case True{}: mask(W.sub(W.U64{0,0},value))

def left(+fits: Bool,+value: W.U64,+shift: U32) -> W.U64:
  match fits:
    case False{}: W.U64{0,0}
    case True{}: mask(W.shl(value,shift))

def rounded(+fits: Bool,+value: W.U64,+shift: U32,+neg: Bool) -> W.U64:
  match fits:
    case False{}: W.U64{0,0}
    case True{}:
      +half = W.shl(W.U64{0,1},U32.sub(shift,1))
      +tail = W.and(value,W.sub(W.shl(W.U64{0,1},shift),W.U64{0,1}))
      +up = Bool.or(R.less(half,tail),Bool.and(Bool.not(neg),W.equal(tail,half)))
      sign(neg,W.add(W.shr(value,shift),W.U64{0,W.flag(up)}))

def fixed_kind(+large: Bool,+value: W.U64,+exp: U32,+neg: Bool) -> W.U64:
  match large:
    case True{}: sign(neg,left(U32.is_lt(U32.sub(exp,1035),48),mantissa(value),U32.sub(exp,1035)))
    case False{}: rounded(U32.is_lt(U32.sub(1035,exp),64),mantissa(value),U32.sub(1035,exp),neg)

# Nearest Q40 phase, ties toward positive infinity after Euclidean reduction,
# matching round(rem_euclid(256) * 2^40) for the registered f64 values.
def fixed(+value: W.U64) -> W.U64:
  +exp = U32.max(exponent(value),1)
  fixed_kind(U32.is_ge(exp,1035),value,exp,negative(value))

def phase(+point: U32,+coefficient: W.U64,+offset: W.U64) -> W.U64:
  mask(W.add(W.mul(W.U64{N.word(U32.is_ge(point,2147483648),4294967295,0),point},coefficient),offset))

def product(+point: U32,+frequency: W.U64) -> Wide:
  match frequency:
    case W.U64{hi,lo}:
      +a = W.high_product(point,lo)
      +b = U32.mul(point,hi)
      +mid = U32.add(a,b)
      Wide{U32.mul(point,lo),mid,U32.add(W.high_product(point,hi),W.flag(U32.is_lt(mid,a)))}

def wide_right_end(+fits: Bool,+shift: U32,+hi: U32) -> W.U64:
  match fits:
    case True{}: W.U64{0,U32.shrn(hi,U32.to_nat(U32.sub(shift,64)))}
    case False{}: W.U64{0,0}

def wide_right_long(+medium: Bool,+shift: U32,+mid: U32,+hi: U32) -> W.U64:
  match medium:
    case True{}: W.shr(W.U64{hi,mid},U32.sub(shift,32))
    case False{}: wide_right_end(U32.is_lt(shift,96),shift,hi)

def wide_right_short(+short: Bool,+shift: U32,+lo: U32,+mid: U32,+hi: U32) -> W.U64:
  match short:
    case True{}: W.U64{U32.or(U32.shrn(mid,U32.to_nat(shift)),U32.shln(hi,U32.to_nat(U32.sub(32,shift)))),
      U32.or(U32.shrn(lo,U32.to_nat(shift)),U32.shln(mid,U32.to_nat(U32.sub(32,shift))))}
    case False{}: wide_right_long(U32.is_lt(shift,64),shift,mid,hi)

def wide_right_kind(+shift: U32,+value: Wide) -> W.U64:
  match value:
    case Wide{+lo,+mid,+hi}:
      wide_right_short(U32.is_lt(shift,32),shift,lo,mid,hi)

def wide_shift(+leftward: Bool,+shift: U32,+value: Wide) -> W.U64:
  match leftward value:
    case True{} Wide{lo,mid,_}: left(U32.is_lt(shift,48),W.U64{mid,lo},shift)
    case False{} _: wide_right_kind(shift,value)

def float_phase(+point: F32,+frequency: W.U64,+offset: W.U64) -> W.U64:
  +bits = F32.bits(point)
  +pe = U32.and(U32.shrn(bits,23n),255)
  +pm = U32.or(U32.and(bits,8388607),N.word(U32.is_zero(pe),0,8388608))
  +exp = U32.add(U32.max(pe,1),U32.max(exponent(frequency),1))
  +shift = N.word(U32.is_ge(exp,1185),U32.sub(exp,1185),U32.sub(1185,exp))
  +value = wide_shift(U32.is_ge(exp,1185),shift,product(pm,mantissa(frequency)))
  mask(W.add(sign(Bool.xor(U32.is_ge(bits,2147483648),negative(frequency)),value),offset))

def coordinate(+point: U32) -> F32:
  Numbers.integer(N.word(U32.is_ge(point,2147483648),4294967295,0),point)

def scaled_phase(+scaled: Bool,+point: U32,+scale: F32,+frequency: W.U64,+coefficient: W.U64,+offset: W.U64) -> W.U64:
  match scaled:
    case False{}: phase(point,coefficient,offset)
    case True{}: float_phase(F32.mul(coordinate(point),scale),frequency,offset)

def cell(+value: W.U64) -> U32:
  match value:
    case W.U64{hi,_}: U32.shrn(hi,8n)

def fraction(+value: W.U64) -> F32:
  match value:
    case W.U64{hi,lo}: F32.add(F32.div(U32.to_f32(U32.and(hi,255)),256.0),F32.div(U32.to_f32(lo),1099511627776.0))

@unsafe
def permute(+permutation: Q.Table<U32>,+index: U32) -> U32:
  Q.get(U32,permutation,U32.and(index,255),0)

# The last four gradients differ from the common hash-based improved-noise
# shortcut. Preserve the initialized Minecraft GradientNoise table exactly.
def gradient(+index: U32,+x: F32,+y: F32,+z: F32) -> F32:
  match index:
    case 0: F32.add(x,y)
    case 1: F32.sub(y,x)
    case 2: F32.sub(x,y)
    case 3: F32.neg(F32.add(x,y))
    case 4: F32.add(x,z)
    case 5: F32.sub(z,x)
    case 6: F32.sub(x,z)
    case 7: F32.neg(F32.add(x,z))
    case 8: F32.add(y,z)
    case 9: F32.sub(z,y)
    case 10: F32.sub(y,z)
    case 11: F32.neg(F32.add(y,z))
    case 12: F32.add(x,y)
    case 13: F32.sub(z,y)
    case 14: F32.sub(y,x)
    case _: F32.neg(F32.add(y,z))

@unsafe
def corner(+permutation: Q.Table<U32>,+xy: U32,+cz: U32,+x: F32,+y: F32,+z: F32) -> F32:
  gradient(U32.and(permute(permutation,U32.add(xy,cz)),15),x,y,z)

@unsafe
def interpolated(+permutation: Q.Table<U32>,+px: W.U64,+py: W.U64,+pz: W.U64) -> F32:
  +cx = cell(px)
  +cy = cell(py)
  +cz = cell(pz)
  +x = fraction(px)
  +y = fraction(py)
  +z = fraction(pz)
  +xm = F32.sub(x,1.0)
  +ym = F32.sub(y,1.0)
  +zm = F32.sub(z,1.0)
  +x0 = permute(permutation,cx)
  +x1 = permute(permutation,U32.inc(cx))
  +xy00 = permute(permutation,U32.add(x0,cy))
  +xy01 = permute(permutation,U32.inc(U32.add(x0,cy)))
  +xy10 = permute(permutation,U32.add(x1,cy))
  +xy11 = permute(permutation,U32.inc(U32.add(x1,cy)))
  N.trilinear(corner(permutation,xy00,cz,x,y,z),corner(permutation,xy10,cz,xm,y,z),
    corner(permutation,xy01,cz,x,ym,z),corner(permutation,xy11,cz,xm,ym,z),
    corner(permutation,xy00,U32.inc(cz),x,y,zm),corner(permutation,xy10,U32.inc(cz),xm,y,zm),
    corner(permutation,xy01,U32.inc(cz),x,ym,zm),corner(permutation,xy11,U32.inc(cz),xm,ym,zm),N.fade(x),N.fade(y),N.fade(z))

@unsafe
def layer(+layer: Layer,+at: P.Position,+scale: F32) -> F32:
  match layer at:
    case Layer{frequency,coefficient,amplitude,ox,oy,oz,permutation} P.Position{x,y,z}:
      F32.mul(amplitude,interpolated(permutation,
        scaled_phase(F32.is_gt(scale,0.0),x,scale,frequency,coefficient,ox),
        scaled_phase(F32.is_gt(scale,0.0),y,scale,frequency,coefficient,oy),
        scaled_phase(F32.is_gt(scale,0.0),z,scale,frequency,coefficient,oz)))

@unsafe
def layers(rows: List<&2,Layer>,+at: P.Position,+scale: F32,+acc: F32) -> F32:
  match rows:
    case Nil{}: acc
    case row <> rest: layers(rest,at,scale,F32.add(acc,layer(row,at,scale)))

@unsafe
def sample(+program: Program,+at: P.Position) -> F32:
  match program:
    case Program{rows,scale}: layers(rows,at,scale,0.0)

@unsafe
def get(+programs: Q.Table<Program>,+id: U32,+at: P.Position) -> F32:
  sample(Q.get(Program,programs,id,Program{[],0.0}),at)
