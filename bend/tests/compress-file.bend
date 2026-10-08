import Base
import ../compression.bend as Z

def unwrap(-T: Type, status: Result<&1, &1, U32 & String, T>) -> IO(T):
  IO.try(T, IO.pure(Result<&1, &1, U32 & String, T>, status))

type Mode is Data:
  Normal{}
  Reference{length: U32, distance: U32}
  Parallel{}

@unsafe
def prefix(more: Bool, a: Array<U32>, b: Z.Bits, +i: U32, +end: U32) -> Array<U32> & Z.Bits:
  match more:
    case False{}: (a, b)
    case True{}:
      Z.read(Array<U32> & Z.Bits, a, i, a => value =>
        prefix(U32.is_lt(U32.inc(i), end), a, Z.literal(b, value), U32.inc(i), end))

def reference(input: Z.Bytes, +length: U32, distance: U32) -> Z.Bytes:
  match input:
    case Z.Bytes{a, +size}:
      Z.result(Z.Bytes, Z.checksum(a, size), a => sum =>
        Z.encoded(Z.Bytes, prefix(U32.is_gt(U32.sub(size, length), 0), a,
          Z.put(Z.start(size), 3, 3n), 0, U32.sub(size, length)),
          a => b => Z.finish(Z.align(Z.literal(Z.backref(b, length, distance), 256)), sum)))

def halves(left: Array<U32> & Array<U32>, right: Array<U32> & Array<U32>, +size: U32) -> Z.Batch:
  (a, b) = left
  (c, d) = right
  Z.Node{Z.Node{Z.Leaf{Z.Bytes{a, size}}, Z.Leaf{Z.Bytes{b, size}}},
    Z.Node{Z.Leaf{Z.Bytes{c, size}}, Z.Leaf{Z.Bytes{d, size}}}}

def pair4(pair: Array<U32> & Array<U32>, +size: U32) -> Z.Batch:
  (a, b) = pair
  halves(Array.clone(U32, a), Array.clone(U32, b), size)

def batch4(data: Z.Bytes) -> Z.Batch:
  match data:
    case Z.Bytes{a, +size}:
      # Test the real parallel API on independent owned chunk arrays. Cloning
      # is outside compression and only constructs identical test fixtures.
      pair4(Array.clone(U32, a), size)

def fill(xs: List<&2, U32>, a: Array<U32>, +i: U32) -> Z.Bytes:
  match xs:
    case Nil{}: Z.Bytes{a, i}
    case value <> rest: fill(rest, Array.set(U32, a, i, value), U32.inc(i))

def to_list(+n: Nat, a: Array<U32>, acc: List<&2, U32>) -> List<&2, U32>:
  match n:
    case 0n: acc
    case 1n+p:
      Z.read(List<&2, U32>, a, U32.from_nat(p), a => value => to_list(p, a, value <> acc))

def written(pair: File & Result<&1, &1, U32 & String, Unit>) -> IO(Unit):
  (file, status) = pair
  do IO<Unit>:
    _ : Unit <- unwrap(Unit, status)
    File.close(file)

def save(data: Z.Bytes, path: String) -> IO(Unit):
  match data:
    case Z.Bytes{a, size}:
      do IO<Unit>:
        file : File <- IO.try(File, File.open(path, "w"))
        pair : File & Result<&1, &1, U32 & String, Unit> <- File.write_bytes(file, to_list(U32.to_nat(size), a, Nil{}))
        written(pair)

def save_batch(batch: Z.Batch, +path: String) -> IO(Unit):
  match batch:
    case Z.Leaf{bytes}: save(bytes, path)
    case Z.Node{left, right}:
      do IO<Unit>:
        _ : Unit <- save_batch(left, path ++ ".a")
        save_batch(right, path ++ ".b")

def reference_valid(ok: Bool, input: Z.Bytes, length: U32, distance: U32, output: String) -> IO(Unit):
  match ok:
    case True{}: save(reference(input, length, distance), output)
    case False{}: IO.die(Unit, 2, "reference input does not contain the required prefix and suffix")

def reference_input(input: Z.Bytes, +length: U32, +distance: U32, output: String) -> IO(Unit):
  match input:
    case Z.Bytes{a, +size}:
      reference_valid(U32.is_ge(size, U32.add(length, distance)), Z.Bytes{a, size}, length, distance, output)

def generate(mode: Mode, input: Z.Bytes, output: String) -> IO(Unit):
  match mode:
    case Normal{}: save(Z.compress(input), output)
    case Reference{length, distance}: reference_input(input, length, distance, output)
    case Parallel{}: save_batch(Z.compress_batch(batch4(input)), output)

def loaded(pair: File & Result<&1, &1, U32 & String, List<&2, U32>>,
    +size: U32, output: String, mode: Mode) -> IO(Unit):
  (file, status) = pair
  do IO<Unit>:
    bytes : List<&2, U32> <- unwrap(List<&2, U32>, status)
    _ : Unit <- File.close(file)
    generate(mode, fill(bytes, Array.new(U32, Nat.add(1n, U32.log2(U32.max(1, size))), 0), 0), output)

def read_nonempty(nonempty: Bool, file: File, +size: U32, output: String, mode: Mode) -> IO(Unit):
  match nonempty:
    case False{}:
      do IO<Unit>:
        _ : Unit <- File.close(file)
        generate(mode, Z.Bytes{[0 : U32*1n], 0}, output)
    case True{}:
      do IO<Unit>:
        pair : File & Result<&1, &1, U32 & String, List<&2, U32>> <- File.read_bytes(file, size)
        loaded(pair, size, output, mode)

def bounded(ok: Bool, file: File, +size: U32, output: String, mode: Mode) -> IO(Unit):
  match ok:
    case False{}:
      do IO<Unit>:
        _ : Unit <- File.close(file)
        IO.die(Unit, 2, "one chunk payload must be <= 16 MiB")
    case True{}: read_nonempty(U32.is_gt(size, 0), file, size, output, mode)

def sized(pair: File & Result<&1, &1, U32 & String, U32>, output: String, mode: Mode) -> IO(Unit):
  (file, status) = pair
  do IO<Unit>:
    +size : U32 <- unwrap(U32, status)
    bounded(U32.is_le(size, 16777216), file, size, output, mode)

def run(input: String, output: String, mode: Mode) -> IO(Unit):
  do IO<Unit>:
    file : File <- IO.try(File, File.open(input, "r"))
    pair : File & Result<&1, &1, U32 & String, U32> <- File.size(file)
    sized(pair, output, mode)

def number(value: Maybe<&2, U32>) -> IO(U32):
  match value:
    case Some{n}: IO.pure(U32, n)
    case None{}: IO.die(U32, 2, "invalid integer")

def reference_args(ok: Bool, input: String, output: String, length: U32, distance: U32) -> IO(Unit):
  match ok:
    case True{}: run(input, output, Reference{length, distance})
    case False{}: IO.die(Unit, 2, "length must be 3..258 and distance 1..32768")

def parallel_args(ok: Bool, input: String, output: String) -> IO(Unit):
  match ok:
    case True{}: run(input, output, Parallel{})
    case False{}: IO.die(Unit, 2, "expected parallel mode")

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      run(input, output, Normal{})
    case _ <> input <> output <> length <> distance <> Nil{}:
      do IO<Unit>:
        +n : U32 <- number(U32.read(length))
        +d : U32 <- number(U32.read(distance))
        reference_args(Bool.and(Bool.and(U32.is_ge(n, 3), U32.is_le(n, 258)),
          Bool.and(U32.is_ge(d, 1), U32.is_le(d, 32768))), input, output, n, d)
    case _ <> input <> output <> mode <> Nil{}:
      parallel_args(String.eq(mode, "parallel"), input, output)
    case _:
      IO.die(Unit, 2, "usage: compress-file input output [--threads N]")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
