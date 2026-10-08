import Base
import ./compression.bend as Z

type Cursor is Type:
  Cursor{array: Array<U32>,size: U32,position: U32,error: U32}

def start(bytes: Z.Bytes) -> Cursor:
  match bytes:
    case Z.Bytes{a,size}: Cursor{a,size,0,0}

def word_valid(-T: Type,ok: Bool,a: Array<U32>,+size: U32,+position: U32,+error: U32,next: Cursor -> U32 -> T) -> T:
  match ok:
    case False{}: next(Cursor{a,size,position,Z.word(U32.is_zero(error),601,error)},0)
    case True{}:
      Z.read(T,a,position,a => b0 => Z.read(T,a,U32.inc(position),a => b1 =>
        Z.read(T,a,U32.add(position,2),a => b2 => Z.read(T,a,U32.add(position,3),a => b3 =>
          next(Cursor{a,size,U32.add(position,4),error},
            U32.or(U32.or(U32.shln(b0,24n),U32.shln(b1,16n)),U32.or(U32.shln(b2,8n),b3)))))))

# Caller supplies a bounded byte-valued transport buffer. Bounds are checked
# before Array.get, whose indices otherwise wrap in Bend.
def word(-T: Type,cursor: Cursor,next: Cursor -> U32 -> T) -> T:
  match cursor:
    case Cursor{a,+size,+position,+error}:
      word_valid(T,Bool.and(U32.is_zero(error),Bool.and(U32.is_le(position,size),
        U32.is_ge(U32.sub(size,position),4))),a,size,position,error,next)

def end(cursor: Cursor) -> Bool:
  match cursor:
    case Cursor{_,size,position,error}: Bool.and(U32.is_zero(error),U32.is_eq(size,position))

def float(bits: U32) -> F32:
  match bits:
    case U32{word}: F32{word}
