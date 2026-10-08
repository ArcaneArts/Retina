import Base
# Same hash, gradients, skew and four-octave fBm as native/src/simplex.wgsl.
# Negative lattice coordinates are explicitly converted to wrapping U32.
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

def fbm(+x: F32, +z: F32) -> F32:
  a = simplex(x, z, 123456789, 987654321)
  b = F32.mul(simplex(F32.mul(x, 2.0), F32.mul(z, 2.0), 123457802, 987654321), 0.5)
  c = F32.mul(simplex(F32.mul(x, 4.0), F32.mul(z, 4.0), 123458815, 987654321), 0.25)
  d = F32.mul(simplex(F32.mul(x, 8.0), F32.mul(z, 8.0), 123459828, 987654321), 0.125)
  F32.div(F32.add(F32.add(F32.add(a, b), c), d), 1.875)

def sample(+i: U32) -> F32:
  x = F32.mul(F32.sub(U32.to_f32(U32.and(i, 511)), 4096.0), 0.0035)
  z = F32.mul(F32.add(U32.to_f32(U32.shrn(i, 9n)), 8192.0), 0.0035)
  fbm(x, z)

def quantize(v: F32) -> U32:
  F32.to_u32(F32.mul(F32.add(v, 2.0), 65536.0))
