import Base
import ../binary.bend as B
import ../word64.bend as W
import ../compression.bend as Z
import ../nbt.bend as N
import ./compress-file.bend as H

def unwrapped(result: Result<&1, &1, U32, Z.Bytes>, output: String) -> IO(Unit):
  match result:
    case Done{bytes}: H.save(bytes, output)
    case Fail{code}: IO.die(Unit, code, "binary encoding failed")

def byte_pattern(+n: Nat, a: Array<U32>) -> Array<U32>:
  match n:
    case 0n: a
    case 1n+p:
      +i = U32.from_nat(p)
      byte_pattern(p, Array.set(U32, a, i, U32.and(i, 255)))

def fixture() -> N.Tag:
  bytes = byte_pattern(8192n, [0 : U32*8192n])
  ints = Array.set(U32, Array.set(U32, Array.set(U32, [0 : U32*4n], 0, 4294967295), 1, 2147483648), 2, 2147483647)
  longs = Array.set(W.U64, Array.set(W.U64, Array.new(W.U64, 2n, W.U64{0,0}), 0,
    W.U64{2147483648,0}), 1, W.U64{4294967295,4294967295})
  N.CompoundTag{[
    N.Field{"byte", N.ByteTag{255}}, N.Field{"short", N.ShortTag{32768}},
    N.Field{"int", N.IntTag{4294967295}}, N.Field{"long", N.LongTag{W.U64{2147483648,0}}},
    N.Field{"float", N.FloatTag{1069547520}}, N.Field{"double", N.DoubleTag{W.U64{3227443200,0}}},
    N.Field{"nan_payload", N.DoubleTag{W.U64{2146959360,305419896}}},
    N.Field{"bytes", N.ByteArrayTag{Z.Bytes{bytes,8192}}},
    N.Field{"unicode", N.StringTag{"A" ++ SCon{Char.from_u32(0), "é😀"}}},
    N.Field{"surrogate", N.StringTag{SCon{Char.from_u32(55296), ""}}},
    N.Field{"max_ascii", N.StringTag{String.repeat("a",65535n)}},
    N.Field{"max_supplementary", N.StringTag{String.repeat("😀",10922n) ++ "aaa"}},
    N.Field{"ints", N.IntArrayTag{ints,4}}, N.Field{"longs", N.LongArrayTag{longs,4}},
    N.Field{"list", N.ListTag{10,2,[N.CompoundTag{[N.Field{"x",N.IntTag{1}}]},
      N.CompoundTag{[N.Field{"x",N.IntTag{4294967295}}]}]}},
    N.Field{"empty", N.ListTag{0,0,[]}}, N.Field{"compound", N.CompoundTag{[]}}
  ]}

def hash(+x: U32) -> U32:
  +a = U32.mul(U32.xor(x, U32.shrn(x, 16n)), 2146121005)
  +b = U32.mul(U32.xor(a, U32.shrn(a, 15n)), 2221713035)
  U32.xor(b, U32.shrn(b, 16n))

def row(w: B.Writer, +a: W.U64, +b: W.U64, +n: U32) -> B.Writer:
  w = B.int(B.long(B.long(w, a), b), n)
  w = B.long(B.long(B.long(w, W.add(a,b)), W.sub(a,b)), W.mul(a,b))
  w = B.long(B.long(w, W.xor(a,b)), W.and(a,b))
  B.long(B.long(w, W.shl(a,n)), W.shr(a,n))

def rows(+n: Nat, w: B.Writer) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p:
      +i = U32.from_nat(p)
      rows(p, row(w, W.U64{hash(U32.add(i,123456789)),hash(i)},
        W.U64{hash(U32.xor(i,4294967295)),hash(U32.mul(i,987654321))}, i))

def edge_rows(xs: List<&2, W.U64>, w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case +a <> rest:
      edge_rows(rest, row(row(row(w,a,W.U64{0,0},0),a,W.U64{0,1},32),
        a,W.U64{4294967295,4294967295},63))

def words() -> Result<&1, &1, U32, Z.Bytes>:
  B.done(edge_rows([W.U64{0,0},W.U64{0,1},W.U64{0,4294967295},
    W.U64{2147483648,0},W.U64{4294967295,4294967295}],rows(512n,B.new())))

def expected(ok: Bool) -> IO(Unit):
  match ok:
    case True{}: IO.pure(Unit, Unit{})
    case False{}: IO.die(Unit, 2, "invalid NBT was accepted or returned the wrong error")

def rejected(result: Result<&1, &1, U32, Z.Bytes>, code: U32) -> IO(Unit):
  match result:
    case Done{_}: expected(False{})
    case Fail{actual}: expected(U32.is_eq(actual,code))

def nested(n: Nat) -> N.Tag:
  match n:
    case 0n: N.IntTag{0}
    case 1n+p: N.CompoundTag{[N.Field{"n",nested(p)}]}

def invalid() -> IO(Unit):
  do IO<Unit>:
    _ : Unit <- rejected(N.encode("",N.ListTag{3,1,[N.StringTag{"wrong"}]}),3)
    _ : Unit <- rejected(N.encode("",N.ListTag{3,2,[N.IntTag{1}]}),3)
    _ : Unit <- rejected(N.encode("",N.ListTag{3,0,[N.IntTag{1}]}),5)
    _ : Unit <- rejected(N.encode("",N.ListTag{13,0,[]}),3)
    _ : Unit <- rejected(N.encode("",N.IntArrayTag{[0 : U32*4n],5}),4)
    _ : Unit <- rejected(N.encode("",N.LongArrayTag{Array.new(W.U64,0n,W.U64{0,0}),2}),4)
    _ : Unit <- rejected(N.encode("",N.StringTag{String.repeat("a",65536n)}),2)
    _ : Unit <- rejected(N.encode("",nested(130n)),6)
    rejected(B.done(B.byte(B.Writer{[0 : U32*1n],16777216,16777216,0},1)),1)

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> +output <> Nil{}:
      do IO<Unit>:
        _ : Unit <- unwrapped(N.encode("root",fixture()),output ++ ".nbt")
        _ : Unit <- unwrapped(words(),output ++ ".words")
        _ : Unit <- invalid()
        IO.print("PASS: binary fixtures and invalid NBT")
    case _: IO.die(Unit,2,"usage: binary-fixtures output-prefix")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
