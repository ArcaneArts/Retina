import Base
# Reusable seeded simplex and lattice noise. All arithmetic is Bend.
# Integer-world entry points retain fractions before any F32 coordinate conversion.
import ./word64.bend as W
def pick(c: Bool, a: F32, b: F32) -> F32:
  match c:
    case True{}: a
    case False{}: b

def word(c: Bool, a: U32, b: U32) -> U32:
  match c:
    case True{}: a
    case False{}: b

def signed(+x: F32) -> U32:
  word(F32.is_lt(x, 0.0), U32.sub(0, F32.to_u32(F32.abs(x))), F32.to_u32(F32.abs(x)))

def hash(+v: U32) -> U32:
  +a = U32.mul(U32.xor(v, U32.shrn(v, 16n)), 2146121005)
  +b = U32.mul(U32.xor(a, U32.shrn(a, 15n)), 2221713035)
  U32.xor(b, U32.shrn(b, 16n))

def sign(b: U32) -> F32:
  pick(U32.is_zero(b), 1.0, F32.neg(1.0))

def gradient(+cx: U32, +cz: U32, low: U32, high: U32, dx: F32, dz: F32) -> F32:
  +h = U32.and(hash(U32.xor(U32.xor(U32.mul(cx, 2654435769), U32.mul(cz, 2246822507)), U32.xor(low, hash(high)))), 7)
  +diag = U32.is_zero(U32.and(h, 4))
  gx = pick(diag, sign(U32.and(h, 1)), pick(U32.is_zero(U32.and(h, 2)), sign(U32.and(h, 1)), 0.0))
  gz = pick(diag, sign(U32.and(h, 2)), pick(U32.is_zero(U32.and(h, 2)), 0.0, sign(U32.and(h, 1))))
  F32.add(F32.mul(gx, dx), F32.mul(gz, dz))

def contribution(cx: U32, cz: U32, +dx: F32, +dz: F32, low: U32, high: U32) -> F32:
  +t = F32.max(F32.sub(0.5, F32.add(F32.mul(dx, dx), F32.mul(dz, dz))), 0.0)
  +t2 = F32.mul(t, t)
  F32.mul(F32.mul(t2, t2), gradient(cx, cz, low, high, dx, dz))

def simplex(+x: F32, +z: F32, +low: U32, +high: U32) -> F32:
  +skew = F32.mul(F32.add(x, z), 0.3660254037844386)
  +ix = F32.floor(F32.add(x, skew))
  +iz = F32.floor(F32.add(z, skew))
  +unskew = F32.mul(F32.add(ix, iz), 0.2113248654051871)
  +dx = F32.sub(x, F32.sub(ix, unskew))
  +dz = F32.sub(z, F32.sub(iz, unskew))
  +sx = pick(F32.is_gt(dx, dz), 1.0, 0.0)
  +sz = F32.sub(1.0, sx)
  +cx = signed(ix)
  +cz = signed(iz)
  a = contribution(cx, cz, dx, dz, low, high)
  b = contribution(U32.add(cx, F32.to_u32(sx)), U32.add(cz, F32.to_u32(sz)), F32.add(F32.sub(dx, sx), 0.2113248654051871), F32.add(F32.sub(dz, sz), 0.2113248654051871), low, high)
  c = contribution(U32.inc(cx), U32.inc(cz), F32.add(F32.sub(dx, 1.0), 0.4226497308103742), F32.add(F32.sub(dz, 1.0), 0.4226497308103742), low, high)
  F32.mul(70.0, F32.add(F32.add(a, b), c))

type Axis is Data:
  Axis{cell: U32, fraction: F32}

def normalize(+cell: U32, +fraction: F32) -> Axis:
  +carry = F32.floor(fraction)
  Axis{U32.add(cell,F32.to_u32(carry)),F32.sub(fraction,carry)}

def axis_sign(negative: Bool, +cell: U32, +fraction: U32) -> Axis:
  match negative:
    case False{}: normalize(cell,F32.mul(U32.to_f32(fraction),0.00000000023283064365386962890625))
    case True{}:
      normalize(U32.sub(U32.sub(0,cell),word(U32.is_zero(fraction),0,1)),
        F32.mul(U32.to_f32(U32.sub(0,fraction)),0.00000000023283064365386962890625))

# coordinate is signed i32 bits. frequency must be finite in [0, 2^30].
# Q32 frequency quantization is at most 2^-32 per block. Integer cells wrap as
# hashes do; the fractional coordinate never subtracts two large F32 values.
def axis(+coordinate: U32, +frequency: F32) -> Axis:
  +negative = U32.is_ne(U32.and(coordinate,2147483648),0)
  +magnitude = word(negative,U32.sub(0,coordinate),coordinate)
  +whole = F32.floor(frequency)
  +part = F32.to_u32(F32.mul(F32.sub(frequency,whole),4294967296.0))
  axis_sign(negative,U32.add(U32.mul(magnitude,F32.to_u32(whole)),W.high_product(magnitude,part)),U32.mul(magnitude,part))

def add_axes(a: Axis, b: Axis) -> Axis:
  match a b:
    case Axis{ac,af} Axis{bc,bf}: normalize(U32.add(ac,bc),F32.add(af,bf))

def simplex_axes(a: Axis, b: Axis, +low: U32, +high: U32) -> F32:
  match a b:
    case Axis{+cx,+fx} Axis{+cz,+fz}:
      +unskew = F32.mul(F32.add(fx,fz),0.2113248654051871)
      +dx = F32.sub(fx,unskew)
      +dz = F32.sub(fz,unskew)
      +sx = word(F32.is_gt(dx,dz),1,0)
      +sz = U32.sub(1,sx)
      a = contribution(cx,cz,dx,dz,low,high)
      b = contribution(U32.add(cx,sx),U32.add(cz,sz),
        F32.add(F32.sub(dx,U32.to_f32(sx)),0.2113248654051871),
        F32.add(F32.sub(dz,U32.to_f32(sz)),0.2113248654051871),low,high)
      c = contribution(U32.inc(cx),U32.inc(cz),F32.sub(dx,0.5773502691896258),F32.sub(dz,0.5773502691896258),low,high)
      F32.mul(70.0,F32.add(F32.add(a,b),c))

def world_simplex(+x: U32, +z: U32, +frequency: F32, low: U32, high: U32) -> F32:
  +skew_frequency = F32.mul(frequency,0.3660254037844386)
  +skew = add_axes(axis(x,skew_frequency),axis(z,skew_frequency))
  simplex_axes(add_axes(axis(x,frequency),skew),add_axes(axis(z,frequency),skew),low,high)

def mix(+a: F32, b: F32, +t: F32) -> F32:
  F32.add(a,F32.mul(F32.sub(b,a),t))

def fade(+x: F32) -> F32:
  F32.mul(F32.mul(F32.mul(x,x),x),F32.add(F32.mul(x,F32.sub(F32.mul(x,6.0),15.0)),10.0))

def corner(+x: U32, +y: U32, z: U32, +dx: F32, +dy: F32, +dz: F32, seed: U32) -> F32:
  +h = U32.and(hash(U32.xor(U32.xor(U32.mul(x,2654435769),U32.mul(y,2246822507)),
    U32.xor(U32.mul(z,3266489909),seed))),15)
  a = pick(U32.is_lt(h,8),dx,dy)
  b = pick(U32.is_lt(h,4),dy,pick(Bool.or(U32.is_eq(h,12),U32.is_eq(h,14)),dx,dz))
  F32.add(F32.mul(a,sign(U32.and(h,1))),F32.mul(b,sign(U32.and(h,2))))

def trilinear(a: F32,b: F32,c: F32,d: F32,e: F32,f: F32,g: F32,h: F32,+x: F32,+y: F32,z: F32) -> F32:
  mix(mix(mix(a,b,x),mix(c,d,x),y),mix(mix(e,f,x),mix(g,h,x),y),z)

def perlin_axes(x: Axis,y: Axis,z: Axis,+low: U32,high: U32) -> F32:
  match x y z:
    case Axis{+cx,+dx} Axis{+cy,+dy} Axis{+cz,+dz}:
      +seed = U32.xor(low,hash(high))
      +xm = F32.sub(dx,1.0)
      +ym = F32.sub(dy,1.0)
      +zm = F32.sub(dz,1.0)
      F32.mul(1.6,trilinear(
        corner(cx,cy,cz,dx,dy,dz,seed),corner(U32.inc(cx),cy,cz,xm,dy,dz,seed),
        corner(cx,U32.inc(cy),cz,dx,ym,dz,seed),corner(U32.inc(cx),U32.inc(cy),cz,xm,ym,dz,seed),
        corner(cx,cy,U32.inc(cz),dx,dy,zm,seed),corner(U32.inc(cx),cy,U32.inc(cz),xm,dy,zm,seed),
        corner(cx,U32.inc(cy),U32.inc(cz),dx,ym,zm,seed),corner(U32.inc(cx),U32.inc(cy),U32.inc(cz),xm,ym,zm,seed),
        fade(dx),fade(dy),fade(dz)))

def world_perlin(+x: U32,+y: U32,+z: U32,+frequency: F32,low: U32,high: U32) -> F32:
  perlin_axes(axis(x,frequency),axis(y,frequency),axis(z,frequency),low,high)

type Octave is Data:
  Octave{frequency: F32,weight: F32,low: U32,high: U32}

type Stack is Data:
  Stack{layers: List<&2,Octave>,gain: F32}

def reverse_layers(xs: List<&2,Octave>,acc: List<&2,Octave>) -> List<&2,Octave>:
  match xs:
    case Nil{}: acc
    case x <> rest: reverse_layers(rest,x <> acc)

def prepend_layer(active: Bool,layer: Octave,acc: List<&2,Octave>) -> List<&2,Octave>:
  match active:
    case True{}: layer <> acc
    case False{}: acc

@unsafe
def prepare_layers(xs: List<&2,F32>,+frequency: F32,+amplitude: F32,+persistence: F32,+weight_sum: F32,
    +low: U32,+high: U32,+octave: U32,acc: List<&2,Octave>) -> Result<&1,&1,U32,Stack>:
  match xs:
    case Nil{}: Done{Stack{reverse_layers(acc,[]),F32.div(F32.mul(amplitude,1.6),F32.max(0.0001,weight_sum))}}
    case +modifier <> rest:
      prepare_modifier(Bool.and(Bool.and(F32.is_ge(modifier,F32.neg(1000000.0)),F32.is_le(modifier,1000000.0)),
        Bool.and(U32.is_lt(octave,32),F32.is_le(frequency,1073741824.0))),rest,frequency,amplitude,persistence,
        weight_sum,low,high,octave,acc,modifier)

@unsafe
def prepare_modifier(ok: Bool,rest: List<&2,F32>,+frequency: F32,amplitude: F32,+persistence: F32,+weight_sum: F32,
    +low: U32,+high: U32,+octave: U32,acc: List<&2,Octave>,+modifier: F32) -> Result<&1,&1,U32,Stack>:
  match ok:
    case False{}: Fail{501}
    case True{}:
      +weight = F32.mul(F32.max(modifier,0.0),persistence)
      prepare_layers(rest,F32.mul(frequency,2.0),amplitude,F32.mul(persistence,0.5),F32.add(weight_sum,weight),
        low,high,U32.inc(octave),prepend_layer(F32.is_gt(weight,0.0),
          Octave{frequency,weight,U32.add(low,U32.mul(octave,1013)),high},acc))

def prepare_valid(ok: Bool,frequency: F32,amplitude: F32,channel: U32,low: U32,high: U32,modifiers: List<&2,F32>)
    -> Result<&1,&1,U32,Stack>:
  match ok:
    case False{}: Fail{501}
    case True{}: prepare_layers(modifiers,frequency,amplitude,1.0,0.0,U32.add(low,U32.mul(channel,7919)),high,0,[])

def prepare(+frequency: F32,+amplitude: F32,channel: U32,low: U32,high: U32,+modifiers: List<&2,F32>)
    -> Result<&1,&1,U32,Stack>:
  prepare_valid(Bool.and(Bool.and(F32.is_ge(frequency,0.0),F32.is_le(frequency,1073741824.0)),
    Bool.and(Bool.and(F32.is_ge(amplitude,0.0),F32.is_le(amplitude,1000000.0)),
      Bool.and(Nat.is_gt(List.length(&2,F32,modifiers),0n),Nat.is_le(List.length(&2,F32,modifiers),32n)))),
    frequency,amplitude,channel,low,high,modifiers)

def sample_layers(xs: List<&2,Octave>,+x: U32,+z: U32,acc: F32) -> F32:
  match xs:
    case Nil{}: acc
    case Octave{frequency,weight,low,high} <> rest:
      sample_layers(rest,x,z,F32.add(acc,F32.mul(world_simplex(x,z,frequency,low,high),weight)))

def sample(stack: Stack,x: U32,z: U32) -> F32:
  match stack:
    case Stack{layers,gain}: F32.min(F32.max(F32.mul(sample_layers(layers,x,z,0.0),gain),F32.neg(1.0)),1.0)
