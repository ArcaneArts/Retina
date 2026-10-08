import Base
import ./binary.bend as B
import ./compression.bend as Z
import ./word64.bend as W

# Long and floating tags carry exact bits. Java registry extraction can export
# these words without any generation or serialization decisions in the bridge.
type Tag is Type:
  ByteTag{value: U32}
  ShortTag{value: U32}
  IntTag{value: U32}
  LongTag{value: W.U64}
  FloatTag{bits: U32}
  DoubleTag{bits: W.U64}
  ByteArrayTag{values: Z.Bytes}
  StringTag{value: String}
  ListTag{kind: U32, size: U32, values: List<Tag>}
  CompoundTag{fields: List<Field>}
  IntArrayTag{values: Array<U32>, size: U32}
  LongArrayTag{values: Array<W.U64>, size: U32}

type Field is Type:
  Field{name: String, tag: Tag}

def char_size(+cp: U32) -> U32:
  Z.word(Bool.and(U32.is_gt(cp, 0), U32.is_lt(cp, 128)), 1,
    Z.word(U32.is_lt(cp, 2048), 2, Z.word(U32.is_lt(cp, 65536), 3, 6)))

def text_size(s: String) -> U32:
  match s:
    case SNil{}: 0
    case SCon{c, rest}: U32.add(char_size(Char.to_u32(c)), text_size(rest))

def unit_long(two: Bool, w: B.Writer, +unit: U32) -> B.Writer:
  match two:
    case True{}:
      B.byte(B.byte(w, U32.or(192, U32.shrn(unit, 6n))), U32.or(128, U32.and(unit, 63)))
    case False{}:
      B.byte(B.byte(B.byte(w, U32.or(224, U32.shrn(unit, 12n))),
        U32.or(128, U32.and(U32.shrn(unit, 6n), 63))), U32.or(128, U32.and(unit, 63)))

def unit_ascii(ascii: Bool, w: B.Writer, +unit: U32) -> B.Writer:
  match ascii:
    case True{}: B.byte(w, unit)
    case False{}: unit_long(U32.is_lt(unit, 2048), w, unit)

def utf16(w: B.Writer, +unit: U32) -> B.Writer:
  unit_ascii(Bool.and(U32.is_gt(unit, 0), U32.is_lt(unit, 128)), w, unit)

def character(bmp: Bool, w: B.Writer, +cp: U32) -> B.Writer:
  match bmp:
    case True{}: utf16(w, cp)
    case False{}:
      utf16(utf16(w, U32.add(55296, U32.shrn(U32.sub(cp, 65536), 10n))),
        U32.add(56320, U32.and(U32.sub(cp, 65536), 1023)))

def text_bytes(s: String, w: B.Writer) -> B.Writer:
  match s:
    case SNil{}: w
    case SCon{c, rest}:
      +cp = Char.to_u32(c)
      text_bytes(rest, character(U32.is_lt(cp, 65536), w, cp))

def text_valid(valid: Bool, s: String, w: B.Writer, size: U32) -> B.Writer:
  match valid:
    case False{}: B.fail(w, 2)
    case True{}: text_bytes(s, B.short(w, size))

def text(w: B.Writer, +s: String) -> B.Writer:
  +size = text_size(s)
  text_valid(U32.is_le(size, 65535), s, w, size)

def kind_valid(valid: Bool, w: B.Writer) -> B.Writer:
  match valid:
    case True{}: w
    case False{}: B.fail(w, 3)

def header(name: Maybe<&2, String>, +kind: U32, expected: U32, w: B.Writer) -> B.Writer:
  match name:
    case Some{s}: text(B.byte(w, kind), s)
    case None{}: kind_valid(U32.is_eq(kind, expected), w)

@unsafe
def ints(more: Bool, a: Array<U32>, w: B.Writer, +i: U32, +end: U32, bytes: Bool) -> B.Writer:
  match more:
    case False{}: w
    case True{}:
      Z.read(B.Writer, a, i, a => value =>
        int_value(bytes, a, w, i, end, value))

@unsafe
def int_value(bytes: Bool, a: Array<U32>, w: B.Writer, +i: U32, +end: U32, value: U32) -> B.Writer:
  match bytes:
    case True{}: ints(U32.is_lt(U32.inc(i), end), a, B.byte(w, value), U32.inc(i), end, True{})
    case False{}: ints(U32.is_lt(U32.inc(i), end), a, B.int(w, value), U32.inc(i), end, False{})

def array_valid(valid: Bool, a: Array<U32>, w: B.Writer, +size: U32, bytes: Bool) -> B.Writer:
  match valid:
    case False{}: B.fail(w, 4)
    case True{}: ints(U32.is_gt(size, 0), a, B.int(w, size), 0, size, bytes)

def array(a: Array<U32>, w: B.Writer, +size: U32, bytes: Bool) -> B.Writer:
  Z.result(B.Writer, Array.size(U32, a), a => capacity =>
    array_valid(U32.is_le(size, capacity), a, w, size, bytes))

@unsafe
def longs_next(pair: Array<W.U64> & W.U64, w: B.Writer, +i: U32, +end: U32) -> B.Writer:
  (a, value) = pair
  longs(U32.is_lt(U32.inc(i), end), a, B.long(w, value), U32.inc(i), end)

@unsafe
def longs(more: Bool, a: Array<W.U64>, w: B.Writer, +i: U32, +end: U32) -> B.Writer:
  match more:
    case False{}: w
    case True{}: longs_next(Array.get(W.U64, a, i), w, i, end)

def long_array_valid(valid: Bool, a: Array<W.U64>, w: B.Writer, +size: U32) -> B.Writer:
  match valid:
    case False{}: B.fail(w, 4)
    case True{}: longs(U32.is_gt(size, 0), a, B.int(w, size), 0, size)

def long_array_size(pair: Array<W.U64> & U32, w: B.Writer, +size: U32) -> B.Writer:
  (a, capacity) = pair
  long_array_valid(U32.is_le(size, capacity), a, w, size)

@unsafe
def fields(xs: List<Field>, w: B.Writer, +depth: U32) -> B.Writer:
  match xs:
    case Nil{}: B.byte(w, 0)
    case Field{name, tag} <> rest:
      fields(rest, write(tag, w, Some{name}, 0, depth), depth)

@unsafe
def items_nonempty(valid: Bool, head: Tag, rest: List<Tag>, w: B.Writer,
    +kind: U32, size: U32, +depth: U32) -> B.Writer:
  match valid:
    case False{}: B.fail(w, 5)
    case True{}: items(rest, write(head, w, None{}, kind, depth), kind, U32.sub(size, 1), depth)

@unsafe
def items(xs: List<Tag>, w: B.Writer, +kind: U32, +size: U32, +depth: U32) -> B.Writer:
  match xs:
    case Nil{}: kind_valid(U32.is_zero(size), w)
    case head <> rest: items_nonempty(U32.is_gt(size, 0), head, rest, w, kind, size, depth)

@unsafe
def write_depth(valid: Bool, tag: Tag, w: B.Writer, name: Maybe<&2, String>, expected: U32, +depth: U32) -> B.Writer:
  match valid tag:
    case False{} _: B.fail(w, 6)
    case True{} ByteTag{value}: B.byte(header(name, 1, expected, w), value)
    case True{} ShortTag{value}: B.short(header(name, 2, expected, w), value)
    case True{} IntTag{value}: B.int(header(name, 3, expected, w), value)
    case True{} LongTag{value}: B.long(header(name, 4, expected, w), value)
    case True{} FloatTag{bits}: B.int(header(name, 5, expected, w), bits)
    case True{} DoubleTag{bits}: B.long(header(name, 6, expected, w), bits)
    case True{} ByteArrayTag{Z.Bytes{a, size}}: array(a, header(name, 7, expected, w), size, True{})
    case True{} StringTag{value}: text(header(name, 8, expected, w), value)
    case True{} ListTag{+kind, +size, values}:
      items(values, B.int(B.byte(kind_valid(U32.is_le(kind, 12), header(name, 9, expected, w)), kind), size), kind, size, U32.inc(depth))
    case True{} CompoundTag{values}: fields(values, header(name, 10, expected, w), U32.inc(depth))
    case True{} IntArrayTag{a, size}: array(a, header(name, 11, expected, w), size, False{})
    case True{} LongArrayTag{a, size}: long_array_size(Array.size(W.U64, a), header(name, 12, expected, w), size)

@unsafe
def write(tag: Tag, w: B.Writer, name: Maybe<&2, String>, expected: U32, +depth: U32) -> B.Writer:
  write_depth(U32.is_le(depth, 128), tag, w, name, expected, depth)

def encode(name: String, tag: Tag) -> Result<&1, &1, U32, Z.Bytes>:
  B.done(write(tag, B.new(), Some{name}, 0, 0))
