import Base

# RFC 1950/1951 encoder. Every byte decision, hash probe, match, Huffman code
# and checksum is Bend code. File transport belongs to the caller.
type Bytes is Type:
  Bytes{array: Array<U32>, size: U32}

type Bits is Type:
  Bits{array: Array<U32>, size: U32, pending: U32, count: Nat}

type Code is Data:
  Code{symbol: U32, extra: U32, width: Nat}

type Batch is Type:
  Leaf{bytes: Bytes}
  Node{left: Batch, right: Batch}

def result(-R: Type, pair: Array<U32> & U32, next: Array<U32> -> U32 -> R) -> R:
  (a, v) = pair
  next(a, v)

def read(-R: Type, a: Array<U32>, i: U32, next: Array<U32> -> U32 -> R) -> R:
  result(R, Array.get(U32, a, i), next)

def encoded(-R: Type, pair: Array<U32> & Bits, next: Array<U32> -> Bits -> R) -> R:
  (a, b) = pair
  next(a, b)

def word(b: Bool, yes: U32, no: U32) -> U32:
  match b:
    case True{}: yes
    case False{}: no

def natural(b: Bool, yes: Nat, no: Nat) -> Nat:
  match b:
    case True{}: yes
    case False{}: no

def reverse(n: Nat, +v: U32, acc: U32) -> U32:
  match n:
    case 0n: acc
    case 1n+p:
      reverse(p, U32.shrn(v, 1n), U32.or(U32.shln(acc, 1n), U32.and(v, 1)))

@unsafe
def flush(full: Bool, a: Array<U32>, +size: U32, +pending: U32, +count: Nat) -> Bits:
  match full:
    case False{}: Bits{a, size, pending, count}
    case True{}:
      flush(Nat.is_ge(Nat.sub(count, 8n), 8n),
        Array.set(U32, a, size, U32.and(pending, 255)), U32.inc(size),
        U32.shrn(pending, 8n), Nat.sub(count, 8n))

def put(b: Bits, value: U32, +width: Nat) -> Bits:
  match b:
    case Bits{a, size, pending, +count}:
      flush(Nat.is_ge(Nat.add(count, width), 8n), a, size,
        U32.or(pending, U32.shln(value, count)), Nat.add(count, width))

def align_nonempty(nonempty: Bool, a: Array<U32>, +size: U32, pending: U32) -> Bits:
  match nonempty:
    case False{}: Bits{a, size, 0, 0n}
    case True{}: Bits{Array.set(U32, a, size, pending), U32.inc(size), 0, 0n}

def align(b: Bits) -> Bits:
  match b:
    case Bits{a, size, pending, count}:
      align_nonempty(Nat.is_gt(count, 0n), a, size, pending)

def literal(b: Bits, +symbol: U32) -> Bits:
  +width = natural(U32.is_lt(symbol, 144), 8n,
    natural(U32.is_lt(symbol, 256), 9n, natural(U32.is_lt(symbol, 280), 7n, 8n)))
  code = word(U32.is_lt(symbol, 144), U32.add(symbol, 48),
    word(U32.is_lt(symbol, 256), U32.add(symbol, 256),
      word(U32.is_lt(symbol, 280), U32.sub(symbol, 256), U32.sub(symbol, 88))))
  put(b, reverse(width, code, 0), width)

# RFC 1951 3.2.5: four length codes share each extra-bit width after length
# ten. Compute the group directly instead of allocating and walking two lists
# for every LZ77 match. Valid input is 3..257; 258 has its own terminal code.
def length_group(small: Bool, +value: U32) -> Code:
  match small:
    case True{}: Code{U32.add(value, 254), 0, 0n}
    case False{}:
      +offset = U32.sub(value, 3)
      +width = Nat.sub(U32.log2(offset), 2n)
      Code{U32.add(257, U32.add(U32.mul(4, U32.from_nat(width)), U32.shrn(offset, width))),
        U32.and(offset, U32.sub(U32.shln(1, width), 1)), width}

def length_code(maximum: Bool, +value: U32) -> Code:
  match maximum:
    case True{}: Code{285, 0, 0n}
    case False{}: length_group(U32.is_le(value, 10), value)

# After distances 1..4, each power-of-two interval has two equally sized codes.
def distance_group(small: Bool, +value: U32) -> Code:
  match small:
    case True{}: Code{U32.sub(value, 1), 0, 0n}
    case False{}:
      +offset = U32.sub(value, 1)
      +order = U32.log2(offset)
      +width = Nat.sub(order, 1n)
      Code{U32.add(U32.mul(2, U32.from_nat(order)), U32.and(U32.shrn(offset, width), 1)),
        U32.and(offset, U32.sub(U32.shln(1, width), 1)), width}

def distance_code(+value: U32) -> Code:
  distance_group(U32.is_le(value, 4), value)

def emit_length(c: Code, b: Bits) -> Bits:
  match c:
    case Code{symbol, extra, width}: put(literal(b, symbol), extra, width)

def emit_distance(c: Code, b: Bits) -> Bits:
  match c:
    case Code{symbol, extra, width}: put(put(b, reverse(5n, symbol, 0), 5n), extra, width)

# Public for format-boundary tests as well as the LZ77 parser. Preconditions:
# length in 3..258, distance in 1..32768, with sufficient preceding input.
def backref(b: Bits, +length: U32, distance: U32) -> Bits:
  emit_distance(distance_code(distance),
    emit_length(length_code(U32.is_eq(length, 258), length), b))

def reduce(+v: U32) -> U32:
  U32.sub(v, word(U32.is_ge(v, 65521), 65521, 0))

@unsafe
def checksum_more(more: Bool, a: Array<U32>, +size: U32, +i: U32,
    +s1: U32, s2: U32) -> Array<U32> & U32:
  match more:
    case False{}: (a, U32.or(U32.shln(s2, 16n), s1))
    case True{}:
      read(Array<U32> & U32, a, i, a => +value =>
        checksum_more(U32.is_lt(U32.inc(i), size), a, size, U32.inc(i),
          reduce(U32.add(s1, value)), reduce(U32.add(s2, reduce(U32.add(s1, value))))))

def checksum(a: Array<U32>, +size: U32) -> Array<U32> & U32:
  checksum_more(U32.is_gt(size, 0), a, size, 0, 1, 0)

def hash_at(a: Array<U32>, +i: U32) -> Array<U32> & U32:
  read(Array<U32> & U32, a, i, a => x =>
    read(Array<U32> & U32, a, U32.inc(i), a => y =>
      read(Array<U32> & U32, a, U32.add(i, 2), a => z =>
        (a, U32.shrn(U32.mul(U32.or(x, U32.or(U32.shln(y, 8n), U32.shln(z, 16n))), 2654435761), 18n)))))

@unsafe
def match_equal(equal: Bool, a: Array<U32>, +current: U32, +previous: U32,
    +limit: U32, +length: U32) -> Array<U32> & U32:
  match equal:
    case False{}: (a, length)
    case True{}: match_more(U32.is_lt(U32.inc(length), limit), a, current, previous, limit, U32.inc(length))

@unsafe
def match_more(more: Bool, a: Array<U32>, +current: U32, +previous: U32,
    +limit: U32, +length: U32) -> Array<U32> & U32:
  match more:
    case False{}: (a, length)
    case True{}:
      read(Array<U32> & U32, a, U32.add(current, length), a => x =>
        read(Array<U32> & U32, a, U32.add(previous, length), a => y =>
          match_equal(U32.is_eq(x, y), a, current, previous, limit, length)))

def match_valid(valid: Bool, a: Array<U32>, current: U32, previous: U32, limit: U32) -> Array<U32> & U32:
  match valid:
    case False{}: (a, 0)
    case True{}: match_more(True{}, a, current, previous, limit, 0)

@unsafe
def token(matched: Bool, a: Array<U32>, table: Array<U32>, b: Bits,
    +size: U32, +i: U32, +length: U32, distance: U32) -> Array<U32> & Bits:
  match matched:
    case True{}:
      encode_more(U32.is_lt(U32.add(i, length), size), a, table, backref(b, length, distance), size, U32.add(i, length))
    case False{}:
      read(Array<U32> & Bits, a, i, a => value =>
        encode_more(U32.is_lt(U32.inc(i), size), a, table, literal(b, value), size, U32.inc(i)))

@unsafe
def probe_entry(pair: Array<U32> & U32, a: Array<U32>, b: Bits,
    +size: U32, +i: U32, hash: U32) -> Array<U32> & Bits:
  (table, +entry) = pair
  table = Array.set(U32, table, hash, U32.inc(i))
  +distance = U32.sub(U32.inc(i), entry)
  result(Array<U32> & Bits,
    match_valid(Bool.and(U32.is_gt(entry, 0), U32.is_le(distance, 32768)),
      a, i, U32.sub(entry, 1), U32.min(258, U32.sub(size, i))),
    a => +length => token(U32.is_ge(length, 3), a, table, b, size, i, length, distance))

@unsafe
def probe(enough: Bool, a: Array<U32>, table: Array<U32>, b: Bits, +size: U32, +i: U32) -> Array<U32> & Bits:
  match enough:
    case False{}: token(False{}, a, table, b, size, i, 0, 0)
    case True{}:
      result(Array<U32> & Bits, hash_at(a, i), a => +hash =>
        probe_entry(Array.get(U32, table, hash), a, b, size, i, hash))

@unsafe
def encode_more(more: Bool, a: Array<U32>, table: Array<U32>, b: Bits, +size: U32, +i: U32) -> Array<U32> & Bits:
  match more:
    case False{}: (a, align(literal(b, 256)))
    case True{}: probe(U32.is_ge(U32.sub(size, i), 3), a, table, b, size, i)

def finish(b: Bits, +sum: U32) -> Bytes:
  match b:
    case Bits{a, +size, _, _}:
      a = Array.set(U32, a, size, U32.shrn(sum, 24n))
      a = Array.set(U32, a, U32.inc(size), U32.and(U32.shrn(sum, 16n), 255))
      a = Array.set(U32, a, U32.add(size, 2), U32.and(U32.shrn(sum, 8n), 255))
      Bytes{Array.set(U32, a, U32.add(size, 3), U32.and(sum, 255)), U32.add(size, 4)}

def start(+size: U32) -> Bits:
  # More than 2*size+64 bytes: sufficient for all fixed-Huffman literal and
  # match encodings, zlib framing, and stored-block fallback. Array.new takes
  # a power-of-two depth, not an element count.
  a = Array.new(U32, Nat.add(1n, U32.log2(U32.add(U32.mul(size, 2), 64))), 0)
  a = Array.set(U32, a, 0, 120)
  Bits{Array.set(U32, a, 1, 1), 2, 0, 0n}

@unsafe
def copy_more(more: Bool, a: Array<U32>, b: Bits, +i: U32, +end: U32) -> Array<U32> & Bits:
  match more:
    case False{}: (a, b)
    case True{}:
      read(Array<U32> & Bits, a, i, a => value =>
        copy_more(U32.is_lt(U32.inc(i), end), a, put(b, value, 8n), U32.inc(i), end))

@unsafe
def stored_next(more: Bool, a: Array<U32>, b: Bits, +size: U32, +i: U32) -> Bytes:
  match more:
    case False{}:
      result(Bytes, checksum(a, size), a => sum => finish(b, sum))
    case True{}: stored(a, b, size, i)

@unsafe
def stored(a: Array<U32>, b: Bits, +size: U32, +i: U32) -> Bytes:
  +length = U32.min(U32.sub(size, i), 65535)
  +end = U32.add(i, length)
  +inverse = U32.xor(length, 65535)
  b = align(put(b, word(U32.is_eq(end, size), 1, 0), 3n))
  b = put(put(b, U32.and(length, 255), 8n), U32.shrn(length, 8n), 8n)
  b = put(put(b, U32.and(inverse, 255), 8n), U32.shrn(inverse, 8n), 8n)
  encoded(Bytes, copy_more(U32.is_lt(i, end), a, b, i, end),
    a => b => stored_next(U32.is_lt(end, size), a, b, size, end))

def choose_stream(smaller: Bool, a: Array<U32>, b: Bits, +size: U32, sum: U32) -> Bytes:
  match smaller:
    case True{}: finish(b, sum)
    case False{}: stored(a, start(size), size, 0)

def select_stream(a: Array<U32>, b: Bits, +size: U32, sum: U32) -> Bytes:
  match b:
    case Bits{out, +used, pending, count}:
      overhead = U32.add(6, U32.mul(5, U32.max(1, U32.div(U32.add(size, 65534), 65535))))
      choose_stream(U32.is_le(U32.add(used, 4), U32.add(size, overhead)),
        a, Bits{out, used, pending, count}, size, sum)

# Input contains byte-valued U32s, size <= 16 MiB, with sufficient array
# capacity. These invariants are checked by the transport before this call.
def compress(input: Bytes) -> Bytes:
  match input:
    case Bytes{a, +size}:
      result(Bytes, checksum(a, size), a => sum =>
        encoded(Bytes, encode_more(U32.is_gt(size, 0), a, [0 : U32^14n], put(start(size), 3, 3n), size, 0),
          a => b => select_stream(a, b, size, sum)))

# Each leaf is a separate MCA chunk stream with its own 32 KiB dictionary.
# Caller balances the tree by expected payload work, not just leaf count.
def compress_batch(batch: Batch) -> Batch:
  match batch:
    case Leaf{bytes}: Leaf{compress(bytes)}
    case Node{left, right}:
      a b = compress_batch(left) compress_batch(right)
      Node{a, b}
