import Base
import ./density.bend as Q
import ./noise.bend as N
import ./int_provider.bend as I
import ./placement_random.bend as R
import ./word64.bend as W

type Rule is Data:
  Provider{value: I.Provider}
  Threshold{level: F32,below: U32,above: U32}
  Based{ratio: U32,factor: F32,offset: F32}
type Row is Data:
  Row{recipe: U32,modifier: U32,slot: U32,rule: Rule}
type Model is Data:
  Model{permutation: Q.Table<U32>,rules: Q.Table<Row>}

@unsafe
def valid(rule: Rule) -> Bool:
  match rule:
    case Provider{p}: I.valid(p)
    case Threshold{level,below,above}: Bool.and(I.finite(level),Bool.and(U32.is_le(below,2147483647),U32.is_le(above,2147483647)))
    case Based{_,+factor,+offset}:
      Bool.and(I.finite(factor),Bool.and(I.finite(offset),Bool.and(F32.is_ne(factor,0.0),
        F32.is_le(F32.abs(F32.div(1.0,factor)),1073741824.0))))

@unsafe
def perm(+table: Q.Table<U32>,+x: U32,+z: U32) -> U32:
  U32.mod(Q.get(U32,table,U32.and(U32.add(x,Q.get(U32,table,U32.and(z,255),0)),255),0),12)

@unsafe
def gx(+h: U32) -> F32:
  match h:
    case 0: 1.0
    case 1: F32.neg(1.0)
    case 2: 1.0
    case 3: F32.neg(1.0)
    case 4: 1.0
    case 5: F32.neg(1.0)
    case 6: 1.0
    case 7: F32.neg(1.0)
    case _: 0.0

@unsafe
def gz(+h: U32) -> F32:
  match h:
    case 0: 1.0
    case 1: 1.0
    case 2: F32.neg(1.0)
    case 3: F32.neg(1.0)
    case 8: 1.0
    case 9: F32.neg(1.0)
    case 10: 1.0
    case 11: F32.neg(1.0)
    case _: 0.0

@unsafe
def corner(+table: Q.Table<U32>,+x: U32,+z: U32,+dx: F32,+dz: F32) -> F32:
  +h = perm(table,x,z)
  +t = F32.max(0.0,F32.sub(0.5,F32.add(F32.mul(dx,dx),F32.mul(dz,dz))))
  +square = F32.mul(t,t)
  F32.mul(F32.mul(square,square),F32.add(F32.mul(gx(h),dx),F32.mul(gz(h),dz)))

@unsafe
def axes(+table: Q.Table<U32>,x: N.Axis,z: N.Axis) -> F32:
  match x z:
    case N.Axis{+cx,+fx} N.Axis{+cz,+fz}:
      +unskew = F32.mul(F32.add(fx,fz),0.21132486540518713)
      +dx = F32.sub(fx,unskew)
      +dz = F32.sub(fz,unskew)
      +sx = N.word(F32.is_gt(dx,dz),1,0)
      +sz = U32.sub(1,sx)
      F32.mul(70.0,F32.add(F32.add(corner(table,cx,cz,dx,dz),
        corner(table,U32.add(cx,sx),U32.add(cz,sz),F32.add(F32.sub(dx,U32.to_f32(sx)),0.21132486540518713),F32.add(F32.sub(dz,U32.to_f32(sz)),0.21132486540518713))),
        corner(table,U32.inc(cx),U32.inc(cz),F32.sub(dx,0.5773502691896257),F32.sub(dz,0.5773502691896257))))

@unsafe
def reverse(+negative: Bool,value: N.Axis) -> N.Axis:
  match negative value:
    case False{} _: value
    case True{} N.Axis{cell,fraction}:
      N.normalize(U32.sub(U32.sub(0,cell),1),F32.sub(1.0,fraction))

# The registered BIOME_INFO_NOISE permutation and 12 gradients are distinct
# from Retina's seeded terrain simplex. Keep integer/fraction coordinates to
# avoid the F32 cancellation that destroys nearby samples far from the origin.
@unsafe
def noise(+table: Q.Table<U32>,+x: U32,+z: U32,+factor: F32) -> F32:
  +frequency = F32.abs(F32.div(1.0,factor))
  +skew = F32.mul(frequency,0.3660254037844386)
  +shift = N.add_axes(N.axis(x,skew),N.axis(z,skew))
  axes(table,reverse(F32.is_lt(factor,0.0),N.add_axes(N.axis(x,frequency),shift)),
    reverse(F32.is_lt(factor,0.0),N.add_axes(N.axis(z,frequency),shift)))

@unsafe
def nonnegative(+value: U32) -> U32:
  N.word(U32.is_ge(value,2147483648),0,value)

@unsafe
def based_count(+value: F32) -> U32:
  saturated(F32.is_ge(value,2147483648.0),value)

@unsafe
def saturated(large: Bool,value: F32) -> U32:
  match large:
    case True{}: 2147483647
    case False{}: F32.to_u32(F32.max(0.0,value))

@unsafe
def sample(rule: Rule,+table: Q.Table<U32>,+x: U32,+z: U32,+state: W.U64) -> R.Draw:
  match rule:
    case Provider{p}: I.sample(p,state)
    case Threshold{level,below,above}: R.Draw{state,W.U64{0,N.word(F32.is_lt(noise(table,x,z,200.0),level),below,above)}}
    case Based{ratio,factor,offset}:
      R.Draw{state,W.U64{0,based_count(F32.ceil(F32.mul(F32.add(noise(table,x,z,factor),offset),I.signed_float(ratio))))}}
