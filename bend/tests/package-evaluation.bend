import Base
import ../binary.bend as B
import ../compression.bend as Z
# Published bend-kit-bytes@0.3.2.0 and bend-kit-zlib@0.2.0.1, pinned by content.
import 0x185ae03c75e3e75be1171471f68b43cb/bytes.bend as Bytes
import 0xcc180113489c489d3f5cdc852e6806f9/zlib.bend as Zlib

# Benchmark only. No native compression effects or generated-kernel changes.
# Inputs are real, already generated chunk NBT. Byte replay measures the buffer
# primitive and growth, not tag traversal, palette construction or heightmaps.
type Operation is Data:
  LocalCompression{}
  PackageCompression{}
  LocalWriter{}
  PackedWriter{}
  PackedAdapted{}
  LocalPresized{}
  PackedGrowing{}
  PackedGrowingAdapted{}

def depth(+size: U32) -> Nat:
  Nat.add(1n, U32.log2(U32.max(1, U32.sub(U32.max(1, size), 1))))

def unwrap(-T: Type, status: Result<&1, &1, U32 & String, T>) -> IO(T):
  IO.try(T, IO.pure(Result<&1, &1, U32 & String, T>, status))

def fill(xs: List<&2, U32>, a: Array<U32>, +i: U32) -> Z.Bytes:
  match xs:
    case Nil{}: Z.Bytes{a, i}
    case value <> rest: fill(rest, Array.set(U32, a, i, value), U32.inc(i))

def fill_string(s: String, a: Array<U32>, +i: U32) -> Z.Bytes:
  match s:
    case SNil{}: Z.Bytes{a, i}
    case SCon{Chr{value}, rest}:
      fill_string(rest, Array.set(U32, a, i, value), U32.inc(i))

def string_bytes(s: String, +size: U32) -> Z.Bytes:
  fill_string(s, Array.new(U32, depth(size), 0), 0)

def to_string(+n: Nat, a: Array<U32>, acc: String) -> String:
  match n:
    case 0n: acc
    case 1n+p:
      Z.read(String, a, U32.from_nat(p), a => value =>
        to_string(p, a, SCon{Chr{value}, acc}))

def to_list(+n: Nat, a: Array<U32>, acc: List<&2, U32>) -> List<&2, U32>:
  match n:
    case 0n: acc
    case 1n+p:
      Z.read(List<&2, U32>, a, U32.from_nat(p), a => value =>
        to_list(p, a, value <> acc))

def string_length(s: String, +size: U32) -> U32:
  match s:
    case SNil{}: size
    case SCon{_, rest}: string_length(rest, U32.inc(size))

def package_output(+s: String) -> Z.Bytes:
  string_bytes(s, string_length(s, 0))

def package_compress(input: Z.Bytes) -> Z.Bytes:
  Z.Bytes{a, size} = input
  package_output(Zlib.zlib(to_string(U32.to_nat(size), a, "")))

def finish(r: Result<&1, &1, U32, Z.Bytes>) -> Z.Bytes:
  match r:
    case Done{value}: value
    case Fail{_}: Z.Bytes{[0 : U32*1n], 0}

@unsafe
def replay(more: Bool, a: Array<U32>, w: B.Writer, +i: U32, +size: U32) -> Z.Bytes:
  match more:
    case False{}: finish(B.done(w))
    case True{}:
      Z.read(Z.Bytes, a, i, a => value =>
        replay(U32.is_lt(U32.inc(i), size), a, B.byte(w, value), U32.inc(i), size))

@unsafe
def replay_packed(more: Bool, a: Array<U32>, b: Bytes.Bytes,
    +i: U32, +size: U32) -> Bytes.Bytes:
  match more:
    case False{}: b
    case True{}:
      Z.read(Bytes.Bytes, a, i, a => value =>
        replay_packed(U32.is_lt(U32.inc(i), size), a,
          Bytes.set(b, i, value), U32.inc(i), size))

def packed(input: Z.Bytes) -> Bytes.Bytes:
  Z.Bytes{a, +size} = input
  replay_packed(U32.is_gt(size, 0), a, Bytes.new(size), 0, size)

# Public append API grows by powers of two and copies whole words. The small
# appended buffer is canonical: exactly one byte-valued U32 in one array word.
@unsafe
def replay_growing(more: Bool, a: Array<U32>, b: Bytes.Bytes,
    +i: U32, +size: U32) -> Bytes.Bytes:
  match more:
    case False{}: b
    case True{}:
      Z.read(Bytes.Bytes, a, i, a => value =>
        replay_growing(U32.is_lt(U32.inc(i), size), a,
          Bytes.append(b, Bytes.Bytes{1, [value : U32*1n]}), U32.inc(i), size))

def packed_growing(input: Z.Bytes) -> Bytes.Bytes:
  Z.Bytes{a, +size} = input
  replay_growing(U32.is_gt(size, 0), a, Bytes.new(0), 0, size)

type Measure is Type:
  Measure{output: Maybe<&1, Z.Bytes>, size: U32, sum: U32}

def measured_pair(pair: Array<U32> & U32, +size: U32) -> Measure:
  (a, sum) = pair
  Measure{Some{Z.Bytes{a, size}}, size, sum}

# Both compression lanes start with owned byte-valued U32 arrays and end with
# exactly the same representation consumed by Retina's MCA writer. Conversion
# overhead is deliberately included. Packed-only keeps its compact result.

def measured(input: Z.Bytes) -> Measure:
  Z.Bytes{a, +size} = input
  measured_pair(Z.checksum(a, size), size)

type PackedSum is Type:
  PackedSum{bytes: Bytes.Bytes, s1: U32, s2: U32}

def packed_done(pair: Bytes.Bytes & U32, sum: U32) -> Measure:
  (b, size) = pair
  Measure{None{}, size, sum}

def packed_sum_value(value: Maybe<&2, U32>, b: Bytes.Bytes,
    +s1: U32, +s2: U32) -> PackedSum:
  match value:
    case None{}: PackedSum{b, 0, 0}
    case Some{v}:
      +s = Z.reduce(U32.add(s1, v))
      PackedSum{b, s, Z.reduce(U32.add(s2, s))}

def packed_got(pair: Bytes.Bytes & Maybe<&2, U32>, s1: U32, s2: U32) -> PackedSum:
  (b, value) = pair
  packed_sum_value(value, b, s1, s2)

def packed_sum(n: Nat, state: PackedSum, +i: U32) -> Measure:
  match n state:
    case 0n PackedSum{b, +s1, +s2}:
      packed_done(Bytes.length(b), U32.or(U32.shln(s2, 16n), s1))
    case 1n+p PackedSum{b, +s1, +s2}:
      packed_sum(p, packed_got(Bytes.get(b, i), s1, s2), U32.inc(i))

def packed_length(pair: Bytes.Bytes & U32) -> Measure:
  (b, size) = pair
  packed_sum(U32.to_nat(size), PackedSum{b, 1, 0}, 0)

def packed_measure(input: Bytes.Bytes) -> Measure:
  # The sum traverses the packed buffer directly; no unpacked result is kept.
  packed_length(Bytes.length(input))

def evaluate(operation: Operation, input: Z.Bytes) -> Measure:
  match operation:
    case LocalCompression{}: measured(Z.compress(input))
    case PackageCompression{}: measured(package_compress(input))
    case LocalWriter{}:
      Z.Bytes{a, +size} = input
      measured(replay(U32.is_gt(size, 0), a, B.new(), 0, size))
    case PackedWriter{}: packed_measure(packed(input))
    case PackedAdapted{}: measured(package_output(Bytes.to_string(packed(input))))
    case LocalPresized{}:
      Z.Bytes{a, +size} = input
      +capacity = U32.shln(1, depth(size))
      measured(replay(U32.is_gt(size, 0), a,
        B.Writer{Array.new(U32, depth(size), 0), 0, capacity, 0}, 0, size))
    case PackedGrowing{}: packed_measure(packed_growing(input))
    case PackedGrowingAdapted{}:
      measured(package_output(Bytes.to_string(packed_growing(input))))

def written(pair: File & Result<&1, &1, U32 & String, Unit>) -> IO(Unit):
  (file, status) = pair
  do IO<Unit>:
    _ : Unit <- unwrap(Unit, status)
    File.close(file)

def save(data: Z.Bytes, path: String) -> IO(Unit):
  Z.Bytes{a, size} = data
  do IO<Unit>:
    file : File <- IO.try(File, File.open(path, "w"))
    pair : File & Result<&1, &1, U32 & String, Unit> <- File.write_bytes(file,
      to_list(U32.to_nat(size), a, Nil{}))
    written(pair)

def cloned(pair: Array<U32> & Array<U32>, +size: U32) -> Z.Bytes & Z.Bytes:
  (a, b) = pair
  (Z.Bytes{a, size}, Z.Bytes{b, size})

def clone(input: Z.Bytes) -> Z.Bytes & Z.Bytes:
  Z.Bytes{a, +size} = input
  cloned(Array.clone(U32, a), size)

def store_output(output: Maybe<&1, Z.Bytes>, input: Z.Bytes, out: String) -> IO(Z.Bytes):
  match output:
    case None{}: IO.pure(Z.Bytes, input)
    case Some{bytes}:
      do IO<Z.Bytes>:
        _ : Unit <- save(bytes, out)
        IO.pure(Z.Bytes, input)

def store_first(first: Bool, output: Maybe<&1, Z.Bytes>, input: Z.Bytes,
    out: String) -> IO(Z.Bytes):
  match first:
    case False{}: IO.pure(Z.Bytes, input)
    case True{}: store_output(output, input, out)

# Sixteen operations per clock interval avoid millisecond clock quantization.
# Every output checksum contributes to the returned sum, so no repetition is
# dead pure work. Fresh input cloning and checksum consumption are included.
def combine(measure: Measure, input: Z.Bytes, previous: U32) -> Z.Bytes & Measure:
  Measure{output, size, sum} = measure
  (input, Measure{output, size, U32.add(previous, sum)})

def cycle(pair: Z.Bytes & Z.Bytes, operation: Operation, previous: U32) -> Z.Bytes & Measure:
  (input, copy) = pair
  combine(evaluate(operation, copy), input, previous)

def batch(n: Nat, pair: Z.Bytes & Measure, +operation: Operation) -> Z.Bytes & Measure:
  match n pair:
    case 0n result: result
    case 1n+p result:
      (input, Measure{_, _, sum}) = result
      batch(p, cycle(clone(input), operation, sum), operation)

def timed(measure: Measure, input: Z.Bytes, out: String, +index: U32,
    start: Nat) -> IO(Z.Bytes):
  # Constructing Measure consumes the whole checksum before the clock effect.
  Measure{output, +size, +sum} = measure
  do IO<Z.Bytes>:
    end : Nat <- IO.now()
    IO.print(U32.show(index) ++ " " ++ Nat.show(Nat.sub(end, start)) ++ " " ++
      U32.show(size) ++ " " ++ U32.show(sum))
    store_first(U32.is_zero(index), output, input, out)

def iteration_done(pair: Z.Bytes & Measure, out: String, index: U32,
    start: Nat) -> IO(Z.Bytes):
  (input, measure) = pair
  timed(measure, input, out, index, start)

def iterations(n: Nat, input: Z.Bytes, +operation: Operation, +out: String,
    +index: U32) -> IO(Unit):
  match n:
    case 0n: IO.print("complete")
    case 1n+p:
      do IO<Unit>:
        start : Nat <- IO.now()
        next : Z.Bytes <- iteration_done(batch(16n,
          (input, Measure{None{}, 0, 0}), operation), out, index, start)
        iterations(p, next, operation, out, U32.inc(index))

def mode(ok: Maybe<&2, U32>, input: Z.Bytes, +out: String, +count: Nat) -> IO(Unit):
  match ok:
    case Some{0}: iterations(count, input, LocalCompression{}, out, 0)
    case Some{1}: iterations(count, input, PackageCompression{}, out, 0)
    case Some{2}: iterations(count, input, LocalWriter{}, out, 0)
    case Some{3}: iterations(count, input, PackedWriter{}, out, 0)
    case Some{4}: iterations(count, input, PackedAdapted{}, out, 0)
    case Some{5}: iterations(count, input, LocalPresized{}, out, 0)
    case Some{6}: iterations(count, input, PackedGrowing{}, out, 0)
    case Some{7}: iterations(count, input, PackedGrowingAdapted{}, out, 0)
    case _: IO.die(Unit, 2, "mode must be 0..7")

def loaded(pair: File & Result<&1, &1, U32 & String, List<&2, U32>>,
    size: U32, out: String, m: String, count: Nat) -> IO(Unit):
  (file, status) = pair
  do IO<Unit>:
    bytes : List<&2, U32> <- unwrap(List<&2, U32>, status)
    _ : Unit <- File.close(file)
    mode(U32.read(m), fill(bytes, Array.new(U32, depth(size), 0), 0), out, count)

def read_file(nonempty: Bool, file: File, +size: U32, out: String, m: String,
    count: Nat) -> IO(Unit):
  match nonempty:
    case False{}:
      do IO<Unit>:
        _ : Unit <- File.close(file)
        mode(U32.read(m), Z.Bytes{[0 : U32*1n], 0}, out, count)
    case True{}:
      do IO<Unit>:
        pair : File & Result<&1, &1, U32 & String, List<&2, U32>> <- File.read_bytes(file, size)
        loaded(pair, size, out, m, count)

def sized(pair: File & Result<&1, &1, U32 & String, U32>, out: String, m: String,
    count: Nat) -> IO(Unit):
  (file, status) = pair
  do IO<Unit>:
    +size : U32 <- unwrap(U32, status)
    read_file(U32.is_gt(size, 0), file, size, out, m, count)

def number(value: Maybe<&2, U32>) -> Result<&1, &1, U32 & String, U32>:
  match value:
    case Some{n}: Done{n}
    case None{}: Fail{(2, "invalid repetitions")}

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> path <> out <> m <> repetitions <> Nil{}:
      do IO<Unit>:
        count : U32 <- IO.try(U32, IO.pure(Result<&1, &1, U32 & String, U32>,
          number(U32.read(repetitions))))
        file : File <- IO.try(File, File.open(path, "r"))
        pair : File & Result<&1, &1, U32 & String, U32> <- File.size(file)
        sized(pair, out, m, U32.to_nat(count))
    case _: IO.die(Unit, 2, "usage: package-evaluation input output mode repetitions")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
