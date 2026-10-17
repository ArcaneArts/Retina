import Base
import ../compression.bend as Z
import ../binary.bend as B
import ./compress-file.bend as F

# Emit the actual production codes for independent comparison with RFC tables.
def append(w: B.Writer, code: Z.Code) -> B.Writer:
  match code:
    case Z.Code{symbol, extra, width}: B.int(B.int(B.int(w, symbol), extra), U32.from_nat(width))

@unsafe
def lengths(more: Bool, +value: U32, w: B.Writer) -> B.Writer:
  match more:
    case False{}: w
    case True{}:
      lengths(U32.is_lt(value, 258), U32.inc(value), append(w, Z.length_code(U32.is_eq(value, 258), value)))

@unsafe
def distances(more: Bool, +value: U32, w: B.Writer) -> B.Writer:
  match more:
    case False{}: w
    case True{}:
      distances(U32.is_lt(value, 32768), U32.inc(value), append(w, Z.distance_code(value)))

def save(result: Result<&1, &1, U32, Z.Bytes>, path: String) -> IO(Unit):
  match result:
    case Done{bytes}: F.save(bytes, path)
    case Fail{_}: IO.die(Unit, 1, "code fixture exceeded its buffer")

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> path <> Nil{}:
      save(B.done(distances(True{}, 1, lengths(True{}, 3, B.new()))), path)
    case _: IO.die(Unit, 2, "usage: compression-codes output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
