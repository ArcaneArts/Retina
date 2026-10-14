import Base
import ./region.bend as R
import ./compression.bend as Z
import ./binary.bend as B
import ./transport.bend as T

# One private MCA body, with each compressed chunk written once. The small
# header is returned to the bridge for an opaque offset-zero write: stock
# Bend 2.0.36 File.open supports r/w/a, but no nontruncating positional write.
# All sector layout, record framing, validation and header bytes belong to Bend.
type State is Type:
  State{file: File,header: Array<U32>,seen: Array<U32>,sectors: U32,chunks: U32}
type Complete is Type:
  Complete{header: Z.Bytes,sectors: U32,chunks: U32}

def begin(path: String) -> IO(State):
  do IO<State>:
    opened : Result<&1,&1,U32 & String,File> <- File.open(path,"w")
    file : File <- T.unwrap(File,opened)
    file : File <- T.write(file,Z.Bytes{[0:U32*8192n],8192})
    IO.pure(State,State{file,[0:U32*8192n],[0:U32*1024n],2,0})

def failed(file: File,code: U32) -> IO(Result<&1,&1,U32,State>):
  do IO<Result<&1,&1,U32,State>>:
    _ : Unit <- File.close(file)
    IO.pure(Result<&1,&1,U32,State>,Fail{code})

def packet(result: Result<&1,&1,U32,Z.Bytes>,file: File,header: Array<U32>,seen: Array<U32>,sectors: U32,chunks: U32) -> IO(Result<&1,&1,U32,State>):
  match result:
    case Fail{code}: failed(file,code)
    case Done{bytes}:
      do IO<Result<&1,&1,U32,State>>:
        file : File <- T.write(file,bytes)
        IO.pure(Result<&1,&1,U32,State>,Done{State{file,header,seen,sectors,chunks}})

@unsafe
def unique(ok: Bool,file: File,header: Array<U32>,seen: Array<U32>,+sector: U32,chunks: U32,+slot: U32,timestamp: U32,data: Z.Bytes,+sectors: U32) -> IO(Result<&1,&1,U32,State>):
  match ok:
    case False{}: failed(file,102)
    case True{}:
      header = R.set_int(header,U32.mul(slot,4),U32.or(U32.shln(sector,8n),sectors))
      header = R.set_int(header,U32.add(4096,U32.mul(slot,4)),timestamp)
      packet(R.packet(data),file,header,Array.set(U32,seen,slot,1),U32.add(sector,sectors),U32.inc(chunks))

@unsafe
def valid(ok: Bool,file: File,header: Array<U32>,seen: Array<U32>,sector: U32,chunks: U32,+slot: U32,timestamp: U32,data: Z.Bytes,sectors: U32) -> IO(Result<&1,&1,U32,State>):
  match ok:
    case False{}: failed(file,101)
    case True{}: Z.read(IO(Result<&1,&1,U32,State>),seen,slot,seen => used =>
      unique(U32.is_zero(used),file,header,seen,sector,chunks,slot,timestamp,data,sectors))

@unsafe
def append(record: R.Record,state: State) -> IO(Result<&1,&1,U32,State>):
  match record state:
    case R.Record{+slot,timestamp,Z.Bytes{a,+size}} State{file,header,seen,+sector,chunks}:
      +sectors = U32.div(U32.add(size,4100),4096)
      valid(Bool.and(U32.is_lt(slot,1024),Bool.and(Bool.and(U32.is_gt(size,0),U32.is_le(size,1044475)),
        Bool.and(Bool.and(U32.is_ge(sector,2),U32.is_le(sector,16777215)),Bool.and(U32.is_le(sectors,255),
          U32.is_le(U32.add(sector,sectors),16777215))))),file,header,seen,sector,chunks,slot,timestamp,Z.Bytes{a,size},sectors)

@unsafe
def appended(result: Result<&1,&1,U32,State>,rest: List<R.Record>) -> IO(Result<&1,&1,U32,State>):
  match result:
    case Fail{code}: IO.pure(Result<&1,&1,U32,State>,Fail{code})
    case Done{state}: records(rest,state)

@unsafe
def records(rows: List<R.Record>,state: State) -> IO(Result<&1,&1,U32,State>):
  match rows:
    case Nil{}: IO.pure(Result<&1,&1,U32,State>,Done{state})
    case row <> rest:
      do IO<Result<&1,&1,U32,State>>:
        result : Result<&1,&1,U32,State> <- append(row,state)
        appended(result,rest)

def complete(state: State) -> IO(Result<&1,&1,U32,Complete>):
  match state:
    case State{file,header,_,sectors,chunks}:
      do IO<Result<&1,&1,U32,Complete>>:
        _ : Unit <- File.close(file)
        IO.pure(Result<&1,&1,U32,Complete>,Done{Complete{Z.Bytes{header,8192},sectors,chunks}})

@unsafe
def response(+n: U32,+i: U32,a: Array<U32>,w: B.Writer) -> Result<&1,&1,U32,Z.Bytes>:
  match n:
    case 0: B.done(w)
    case _: Z.read(Result<&1,&1,U32,Z.Bytes>,a,i,a => byte => response(U32.sub(n,1),U32.inc(i),a,B.byte(w,byte)))

def encoded(done: Complete) -> Result<&1,&1,U32,Z.Bytes>:
  match done:
    case Complete{Z.Bytes{a,size},sectors,chunks}: response(size,0,a,B.int(B.int(B.new(),chunks),sectors))
