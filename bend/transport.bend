import Base
import ./binary.bend as B
import ./compression.bend as Z

type Read is Type:
  Complete{file: File,bytes: Z.Bytes}
  End{file: File}

def unwrap(-T: Type,status: Result<&1,&1,U32 & String,T>) -> IO(T):
  IO.try(T,IO.pure(Result<&1,&1,U32 & String,T>,status))

def append(xs: List<&2,U32>,w: B.Writer,+count: U32) -> B.Writer & U32:
  match xs:
    case Nil{}: (w,count)
    case byte <> rest: append(rest,B.byte(w,byte),U32.inc(count))

def completed(file: File,result: Result<&1,&1,U32,Z.Bytes>) -> IO(Read):
  match result:
    case Done{bytes}: IO.pure(Read,Complete{file,bytes})
    case Fail{code}: IO.die(Read,code,"Bend transport frame exceeds buffer bounds")

@unsafe
def exact(file: File,+remaining: U32,w: B.Writer,allow_eof: Bool) -> IO(Read):
  exact_more(U32.is_gt(remaining,0),file,remaining,w,allow_eof)

@unsafe
def exact_more(more: Bool,file: File,+remaining: U32,w: B.Writer,allow_eof: Bool) -> IO(Read):
  match more:
    case False{}: completed(file,B.done(w))
    case True{}:
      do IO<Read>:
        result : File & Result<&1,&1,U32 & String,List<&2,U32>> <- File.read_bytes(file,U32.min(remaining,65536))
        received(result,remaining,w,allow_eof)

@unsafe
def received(pair: File & Result<&1,&1,U32 & String,List<&2,U32>>,remaining: U32,w: B.Writer,allow_eof: Bool) -> IO(Read):
  (file,status) = pair
  do IO<Read>:
    xs : List<&2,U32> <- unwrap(List<&2,U32>,status)
    received_list(xs,file,remaining,w,allow_eof)

@unsafe
def received_list(xs: List<&2,U32>,file: File,remaining: U32,w: B.Writer,allow_eof: Bool) -> IO(Read):
  match xs:
    case Nil{}: at_eof(allow_eof,file)
    case byte <> rest: appended(append(byte <> rest,w,0),file,remaining)

@unsafe
def appended(pair: B.Writer & U32,file: File,+remaining: U32) -> IO(Read):
  (w,count) = pair
  exact(file,U32.sub(remaining,count),w,False{})

def at_eof(clean: Bool,file: File) -> IO(Read):
  match clean:
    case True{}: IO.pure(Read,End{file})
    case False{}: IO.die(Read,602,"Bend transport ended within a frame")

def list(+n: Nat,a: Array<U32>,acc: List<&2,U32>) -> List<&2,U32>:
  match n:
    case 0n: acc
    case 1n+p: Z.read(List<&2,U32>,a,U32.from_nat(p),a => value => list(p,a,value <> acc))

def written(pair: File & Result<&1,&1,U32 & String,Unit>) -> IO(File):
  (file,status) = pair
  do IO<File>:
    _ : Unit <- unwrap(Unit,status)
    IO.pure(File,file)

def write(file: File,bytes: Z.Bytes) -> IO(File):
  match bytes:
    case Z.Bytes{a,size}:
      do IO<File>:
        pair : File & Result<&1,&1,U32 & String,Unit> <- File.write_bytes(file,list(U32.to_nat(size),a,[]))
        written(pair)
