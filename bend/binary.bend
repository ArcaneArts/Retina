import Base
import ./compression.bend as Z
import ./word64.bend as W

# Growable aligned chunk buffer. MCA file publication can stream these buffers;
# individual uncompressed chunk payloads remain bounded to 16 MiB.
type Writer is Type:
  Writer{array: Array<U32>, size: U32, capacity: U32, error: U32}

def new() -> Writer:
  Writer{[0 : U32*256n], 0, 256, 0}

def fail(w: Writer, error: U32) -> Writer:
  match w:
    case Writer{a, size, capacity, _}: Writer{a, size, capacity, error}

def copied(-R: Type, pair: Array<U32> & Array<U32>, next: Array<U32> -> Array<U32> -> R) -> R:
  (a, b) = pair
  next(a, b)

@unsafe
def copy(more: Bool, source: Array<U32>, target: Array<U32>, +i: U32, +end: U32) -> Array<U32> & Array<U32>:
  match more:
    case False{}: (source, target)
    case True{}:
      Z.read(Array<U32> & Array<U32>, source, i, source => value =>
        copy(U32.is_lt(U32.inc(i), end), source, Array.set(U32, target, i, value), U32.inc(i), end))

def append_room(room: Bool, a: Array<U32>, +size: U32, +capacity: U32, value: U32) -> Writer:
  match room:
    case True{}: Writer{Array.set(U32, a, size, value), U32.inc(size), capacity, 0}
    case False{}:
      copied(Writer, copy(U32.is_gt(size, 0), a, Array.new(U32, Nat.add(U32.log2(capacity), 1n), 0), 0, size),
        old => target => Writer{Array.set(U32, target, size, value), U32.inc(size), U32.mul(capacity, 2), 0})

def append_valid(valid: Bool, a: Array<U32>, +size: U32, +capacity: U32, +error: U32, value: U32) -> Writer:
  match valid:
    case False{}: Writer{a, size, capacity, Z.word(U32.is_zero(error), 1, error)}
    case True{}: append_room(U32.is_lt(size, capacity), a, size, capacity, value)

def byte(w: Writer, value: U32) -> Writer:
  match w:
    case Writer{a, +size, +capacity, +error}:
      append_valid(Bool.and(U32.is_zero(error), U32.is_lt(size, 16777216)),
        a, size, capacity, error, U32.and(value, 255))

def short(w: Writer, +value: U32) -> Writer:
  byte(byte(w, U32.shrn(value, 8n)), value)

def int(w: Writer, +value: U32) -> Writer:
  short(short(w, U32.shrn(value, 16n)), value)

def long(w: Writer, value: W.U64) -> Writer:
  match value:
    case W.U64{high, low}: int(int(w, high), low)

def finish(ok: Bool, a: Array<U32>, size: U32, error: U32) -> Result<&1, &1, U32, Z.Bytes>:
  match ok:
    case True{}: Done{Z.Bytes{a, size}}
    case False{}: Fail{error}

def done(w: Writer) -> Result<&1, &1, U32, Z.Bytes>:
  match w:
    case Writer{a, size, _, +error}: finish(U32.is_zero(error), a, size, error)
