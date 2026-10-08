import Base

# Exact bit representation for Minecraft seeds, NBT longs/doubles and palettes.
# Arithmetic is modulo 2^64; no F32 conversion loses low seed bits.
type U64 is Data:
  U64{high: U32, low: U32}

def flag(b: Bool) -> U32:
  match b:
    case True{}: 1
    case False{}: 0

def add(a: U64, b: U64) -> U64:
  match a b:
    case U64{ah, +al} U64{bh, +bl}:
      +low = U32.add(al, bl)
      U64{U32.add(U32.add(ah, bh), flag(U32.is_lt(low, al))), low}

def sub(a: U64, b: U64) -> U64:
  match a b:
    case U64{ah, +al} U64{bh, +bl}:
      U64{U32.sub(U32.sub(ah, bh), flag(U32.is_lt(al, bl))), U32.sub(al, bl)}

def xor(a: U64, b: U64) -> U64:
  match a b:
    case U64{ah, al} U64{bh, bl}: U64{U32.xor(ah, bh), U32.xor(al, bl)}

def and(a: U64, b: U64) -> U64:
  match a b:
    case U64{ah, al} U64{bh, bl}: U64{U32.and(ah, bh), U32.and(al, bl)}

def high_product(+a: U32, +b: U32) -> U32:
  +a0 = U32.and(a, 65535)
  +a1 = U32.shrn(a, 16n)
  +b0 = U32.and(b, 65535)
  +b1 = U32.shrn(b, 16n)
  +t = U32.add(U32.mul(a1, b0), U32.shrn(U32.mul(a0, b0), 16n))
  w = U32.add(U32.mul(a0, b1), U32.and(t, 65535))
  U32.add(U32.mul(a1, b1), U32.add(U32.shrn(t, 16n), U32.shrn(w, 16n)))

def mul(a: U64, b: U64) -> U64:
  match a b:
    case U64{ah, +al} U64{bh, +bl}:
      U64{U32.add(high_product(al, bl), U32.add(U32.mul(ah, bl), U32.mul(al, bh))), U32.mul(al, bl)}

def shl_nonzero(small: Bool, a: U64, +n: Nat) -> U64:
  match small a:
    case True{} U64{high, +low}:
      U64{U32.or(U32.shln(high, n), U32.shrn(low, Nat.sub(32n, n))), U32.shln(low, n)}
    case False{} U64{_, low}: U64{U32.shln(low, Nat.sub(n, 32n)), 0}

def shr_nonzero(small: Bool, a: U64, +n: Nat) -> U64:
  match small a:
    case True{} U64{+high, low}:
      U64{U32.shrn(high, n), U32.or(U32.shrn(low, n), U32.shln(high, Nat.sub(32n, n)))}
    case False{} U64{high, _}: U64{0, U32.shrn(high, Nat.sub(n, 32n))}

def shl_zero(zero: Bool, a: U64, +n: Nat) -> U64:
  match zero:
    case True{}: a
    case False{}: shl_nonzero(Nat.is_lt(n, 32n), a, n)

def shr_zero(zero: Bool, a: U64, +n: Nat) -> U64:
  match zero:
    case True{}: a
    case False{}: shr_nonzero(Nat.is_lt(n, 32n), a, n)

# Shift counts follow the Java long contract (masked to 0..63).
def shl(a: U64, count: U32) -> U64:
  +n = U32.to_nat(U32.and(count, 63))
  shl_zero(Nat.is_eq(n, 0n), a, n)

def shr(a: U64, count: U32) -> U64:
  +n = U32.to_nat(U32.and(count, 63))
  shr_zero(Nat.is_eq(n, 0n), a, n)

def equal(a: U64, b: U64) -> Bool:
  match a b:
    case U64{ah, al} U64{bh, bl}: Bool.and(U32.is_eq(ah, bh), U32.is_eq(al, bl))
