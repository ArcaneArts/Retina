import Base
import ../word64.bend as W
import ../numbers.bend as N
import ../reader.bend as R
import ../binary.bend as B
import ../compression.bend as Z
import ../transport.bend as T
import ./compress-file.bend as C

type Row is Data:
  Row{double: U32,integer: U32}

def decode(n: Nat,cursor: R.Cursor,a: Array<W.U64>,+i: U32) -> Array<W.U64>:
  match n:
    case 0n: a
    case 1n+p: R.word(Array<W.U64>,cursor,cursor => high => R.word(Array<W.U64>,cursor,cursor => low =>
      decode(p,cursor,Array.set(W.U64,a,i,W.U64{high,low}),U32.inc(i))))

def row(value: W.U64) -> Row:
  match value:
    case W.U64{+hi,+lo}: Row{N.double_bits(hi,lo),N.integer_bits(hi,lo)}

def calculate(a: Array<W.U64>) -> Array<Row>:
  match a:
    case ALeaf{value}: ALeaf{row(value)}
    case ANode{a,b}:
      a b = calculate(a) calculate(b)
      ANode{a,b}

@unsafe
def encode(n: Nat,a: Array<Row>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: encoded(p,Array.get(Row,a,i),w,i)

@unsafe
def encoded(n: Nat,pair: Array<Row> & Row,w: B.Writer,i: U32) -> B.Writer:
  (a,value) = pair
  encode_row(n,a,value,w,i)

@unsafe
def encode_row(n: Nat,a: Array<Row>,value: Row,w: B.Writer,i: U32) -> B.Writer:
  match value:
    case Row{d,l}: encode(n,a,B.int(B.int(w,d),l),U32.inc(i))

def output_bytes(result: Result<&1,&1,U32,Z.Bytes>) -> IO(Z.Bytes):
  match result:
    case Fail{code}: IO.die(Z.Bytes,code,"numeric output overflow")
    case Done{bytes}: IO.pure(Z.Bytes,bytes)

def generate(input: T.Read,+count: U32,path: String) -> IO(Unit):
  match input:
    case T.End{file}: IO.die(Unit,2,"missing numeric fixture")
    case T.Complete{file,bytes}:
      do IO<Unit>:
        _ : Unit <- File.close(file)
        values : Array<Row> = calculate!(decode(U32.to_nat(count),R.start(bytes),
          Array.new(W.U64,U32.log2(count),W.U64{0,0}),0))
        output : Z.Bytes <- output_bytes(B.done(encode(U32.to_nat(count),values,B.new(),0)))
        C.save(output,path)

def sized(input: T.Read,path: String) -> IO(Unit):
  match input:
    case T.End{_}: IO.die(Unit,2,"missing numeric fixture count")
    case T.Complete{file,bytes}: R.word(IO(Unit),R.start(bytes),cursor => +count =>
      do IO<Unit>:
        data : T.Read <- T.exact(file,U32.mul(count,8),B.new(),False{})
        generate(data,count,path))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        file : File <- IO.try(File,File.open(input,"r"))
        header : T.Read <- T.exact(file,4,B.new(),False{})
        sized(header,output)
    case _: IO.die(Unit,2,"usage: numeric-fixtures input output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
