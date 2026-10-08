import Base
import ./word64.bend as W
import ./compression.bend as Z

# Modern Minecraft packed arrays never split an entry across long boundaries.
# Palette remapping is a separate step; this accepts already local indices.
type Packed is Type:
  Packed{words: Array<W.U64>, size: U32, bits: U32, entries: U32}

@unsafe
def word_value(pair: Array<W.U64> & W.U64, values: Array<U32>,
    +count: U32, +bits: U32, +per: U32, +i: U32, value: U32) -> Result<&1, &1, U32, Packed>:
  (words, old) = pair
  +index = U32.div(i, per)
  words = Array.set(W.U64, words, index,
    W.xor(old, W.shl(W.U64{0,value}, U32.mul(U32.mod(i,per),bits))))
  pack_more(U32.is_lt(U32.inc(i),count), values, words, count,bits,per,U32.inc(i))

@unsafe
def index_valid(valid: Bool, values: Array<U32>, words: Array<W.U64>,
    +count: U32, +bits: U32, +per: U32, +i: U32, value: U32) -> Result<&1, &1, U32, Packed>:
  match valid:
    case False{}: Fail{3}
    case True{}: word_value(Array.get(W.U64,words,U32.div(i,per)),values,count,bits,per,i,value)

@unsafe
def pack_more(more: Bool, values: Array<U32>, words: Array<W.U64>,
    +count: U32, +bits: U32, +per: U32, +i: U32) -> Result<&1, &1, U32, Packed>:
  match more:
    case False{}: Done{Packed{words,U32.div(U32.add(count,U32.sub(per,1)),per),bits,count}}
    case True{}:
      Z.read(Result<&1, &1, U32, Packed>,values,i,values => +value =>
        index_valid(U32.is_lt(value,U32.shln(1,U32.to_nat(bits))),values,words,count,bits,per,i,value))

def capacity_valid(valid: Bool, values: Array<U32>, +count: U32, +bits: U32) -> Result<&1, &1, U32, Packed>:
  match valid:
    case False{}: Fail{2}
    case True{}:
      +per = U32.div(64,bits)
      +size = U32.div(U32.add(count,U32.sub(per,1)),per)
      words = Array.new(W.U64,Nat.add(1n,U32.log2(U32.max(1,size))),W.U64{0,0})
      pack_more(U32.is_gt(count,0),values,words,count,bits,per,0)

def parameters_valid(valid: Bool, values: Array<U32>, +count: U32, bits: U32) -> Result<&1, &1, U32, Packed>:
  match valid:
    case False{}: Fail{1}
    case True{}:
      Z.result(Result<&1, &1, U32, Packed>,Array.size(U32,values),values => capacity =>
        capacity_valid(U32.is_le(count,capacity),values,count,bits))

# Covers section palette indices (up to 4096 entries) and heightmaps.
# Single-entry palettes omit their data tag instead of calling this with bits=0.
def pack(values: Array<U32>, +count: U32, +bits: U32) -> Result<&1, &1, U32, Packed>:
  parameters_valid(Bool.and(U32.is_le(count,4096),Bool.and(U32.is_ge(bits,1),U32.is_le(bits,16))),
    values,count,bits)
