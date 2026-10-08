import Base
import ../binary.bend as B
import ../word64.bend as W
import ../compression.bend as Z
import ../nbt.bend as N
import ../packed.bend as P
import ../region.bend as R
import ./compress-file.bend as H
import ./binary-fixtures.bend as T

@unsafe
def packed_word(pair: Array<W.U64> & W.U64, w: B.Writer, +i: U32, +size: U32) -> B.Writer:
  (a,value) = pair
  packed_words(U32.is_lt(U32.inc(i),size),a,B.long(w,value),U32.inc(i),size)

@unsafe
def packed_words(more: Bool, a: Array<W.U64>, w: B.Writer, +i: U32, +size: U32) -> B.Writer:
  match more:
    case False{}: w
    case True{}: packed_word(Array.get(W.U64,a,i),w,i,size)

def packed_result(result: Result<&1, &1, U32, P.Packed>, w: B.Writer) -> B.Writer:
  match result:
    case Fail{code}: B.fail(w,code)
    case Done{P.Packed{a,+size,bits,entries}}:
      packed_words(U32.is_gt(size,0),a,B.int(B.int(B.int(w,bits),entries),size),0,size)

def indices(+n: Nat, a: Array<U32>, +bits: U32) -> Array<U32>:
  match n:
    case 0n: a
    case 1n+p:
      +i = U32.from_nat(p)
      v = U32.and(U32.xor(U32.mul(i,2654435761),U32.shrn(i,3n)),U32.sub(U32.shln(1,U32.to_nat(bits)),1))
      indices(p,Array.set(U32,a,i,v),bits)

def pack_counts(counts: List<U32>, w: B.Writer, +bits: U32) -> B.Writer:
  match counts:
    case Nil{}: w
    case +count <> rest:
      pack_counts(rest,packed_result(P.pack(indices(U32.to_nat(count),[0 : U32*4096n],bits),count,bits),w),bits)

def pack_cases(+n: Nat, w: B.Writer) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p:
      pack_cases(p,pack_counts([0,1,63,64,65,255,256,4095,4096],w,U32.inc(U32.from_nat(p))))

def rejected_pack(result: Result<&1, &1, U32, P.Packed>, code: U32) -> IO(Unit):
  match result:
    case Done{_}: T.expected(False{})
    case Fail{actual}: T.expected(U32.is_eq(actual,code))

def random_bytes(+n: Nat, a: Array<U32>) -> Array<U32>:
  match n:
    case 0n: a
    case 1n+p:
      +i = U32.from_nat(p)
      random_bytes(p,Array.set(U32,a,i,U32.and(T.hash(i),255)))

def compressed(result: Result<&1, &1, U32, Z.Bytes>) -> Z.Bytes:
  match result:
    case Done{bytes}: Z.compress(bytes)
    # An encoding failure becomes an invalid record and makes MCA planning
    # fail; it must never turn into a successful empty chunk fixture.
    case Fail{_}: Z.Bytes{[0 : U32*1n],0}

def chunk(+slot: U32) -> Z.Bytes:
  # This is a container fixture, deliberately not a complete generated chunk.
  # DataVersion=1 is test metadata, not a claim about the current game version.
  compressed(N.encode("",N.CompoundTag{[
    N.Field{"xPos",N.IntTag{U32.add(4294967232,U32.and(slot,31))}},
    N.Field{"zPos",N.IntTag{U32.add(96,U32.shrn(slot,5n))}},
    N.Field{"DataVersion",N.IntTag{1}},N.Field{"Status",N.StringTag{"minecraft:empty"}}
  ]}))

def large() -> Z.Bytes:
  compressed(N.encode("",N.CompoundTag{[
    N.Field{"fixture",N.ByteArrayTag{Z.Bytes{random_bytes(12000n,[0 : U32*16384n]),12000}}}
  ]}))

def record(+slot: U32) -> R.Record:
  R.Record{slot,U32.add(1700000000,slot),chunk(slot)}

def records(+n: Nat, acc: List<R.Record>) -> List<R.Record>:
  match n:
    case 0n: acc
    case 1n+p:
      slot = U32.and(U32.mul(U32.from_nat(p),73),1023)
      records(p,record(slot) <> acc)

@unsafe
def segment_written(pair: File & Result<&1, &1, U32 & String, Unit>, rest: List<Z.Bytes>) -> IO(Unit):
  (file,status) = pair
  do IO<Unit>:
    _ : Unit <- H.unwrap(Unit,status)
    write_segments(rest,file)

@unsafe
def write_segments(xs: List<Z.Bytes>, file: File) -> IO(Unit):
  match xs:
    case Nil{}: File.close(file)
    case Z.Bytes{a,size} <> rest:
      do IO<Unit>:
        pair : File & Result<&1, &1, U32 & String, Unit> <- File.write_bytes(file,H.to_list(U32.to_nat(size),a,[]))
        segment_written(pair,rest)

def save_plan(result: Result<&1, &1, U32, R.Plan>, path: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"MCA planning failed")
    case Done{R.Plan{segments,_,_}}:
      do IO<Unit>:
        file : File <- IO.try(File,File.open(path,"w"))
        write_segments(segments,file)

def rejected_region(result: Result<&1, &1, U32, R.Plan>, code: U32) -> IO(Unit):
  match result:
    case Done{_}: T.expected(False{})
    case Fail{actual}: T.expected(U32.is_eq(actual,code))

def invalid() -> IO(Unit):
  do IO<Unit>:
    _ : Unit <- rejected_pack(P.pack([0 : U32*1n],1,0),1)
    _ : Unit <- rejected_pack(P.pack([0 : U32*1n],1,17),1)
    _ : Unit <- rejected_pack(P.pack([0 : U32*1n],4097,4),1)
    _ : Unit <- rejected_pack(P.pack([0 : U32*1n],2,4),2)
    _ : Unit <- rejected_pack(P.pack([16 : U32*1n],1,4),3)
    _ : Unit <- rejected_region(R.plan([record(1024)]),101)
    _ : Unit <- rejected_region(R.plan([record(0),record(0)]),102)
    _ : Unit <- rejected_region(R.plan([R.Record{0,0,Z.Bytes{[0 : U32*1n],0}}]),101)
    rejected_region(R.plan([R.Record{0,0,Z.Bytes{[0 : U32*1n],1044476}}]),101)

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> +output <> Nil{}:
      do IO<Unit>:
        _ : Unit <- T.unwrapped(B.done(pack_cases(16n,B.new())),output ++ ".packed")
        _ : Unit <- save_plan(R.plan(records(1024n,[])),output ++ ".mca")
        _ : Unit <- save_plan(R.plan([record(1023),R.Record{0,1234,large()},record(32)]),output ++ ".sparse.mca")
        _ : Unit <- save_plan(R.plan([]),output ++ ".empty.mca")
        _ : Unit <- invalid()
        IO.print("PASS: packed and region fixtures")
    case _: IO.die(Unit,2,"usage: format-fixtures output-prefix")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
