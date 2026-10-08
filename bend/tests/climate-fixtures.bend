import Base
import ../climate.bend as C
import ../reader.bend as R
import ../binary.bend as B
import ../compression.bend as Z
import ../transport.bend as T
import ./compress-file.bend as F

# Raw numeric fixture transport only. The Python harness copies registered
# intervals/flags and coordinates; index construction and lookup are pure Bend.
# Production registry-tape projection is deliberately separate pending work.
type Input is Type:
  Input{cursor: R.Cursor,targets: List<&2,C.Target>,queries: Array<Request>,count: U32,valid: Bool}

type Request is Data:
  Request{point: C.Axes,mode: U32}

type Row is Data:
  Row{present: U32,biome: U32,order: U32,fitness: U32,continentalness: U32}

def axes(-A: Type,cursor: R.Cursor,next: R.Cursor -> C.Axes -> A) -> A:
  R.word(A,cursor,cursor => a => R.word(A,cursor,cursor => b => R.word(A,cursor,cursor => c =>
  R.word(A,cursor,cursor => d => R.word(A,cursor,cursor => e => R.word(A,cursor,cursor => f =>
    next(cursor,C.Axes{R.float(a),R.float(b),R.float(c),R.float(d),R.float(e),R.float(f)})))))))

@unsafe
def targets(n: Nat,cursor: R.Cursor,acc: List<&2,C.Target>,+valid: Bool,+queries: U32) -> Input:
  match n:
    case 0n: requests(U32.to_nat(queries),cursor,List.reverse(&2,C.Target,acc),
      Array.new(Request,Nat.add(U32.log2(U32.max(queries,1)),1n),Request{C.Axes{0.0,0.0,0.0,0.0,0.0,0.0},32}),0,queries,valid)
    case 1n+p: axes(Input,cursor,cursor => +lo => axes(Input,cursor,cursor => +hi =>
      R.word(Input,cursor,cursor => +offset => R.word(Input,cursor,cursor => +biome =>
      R.word(Input,cursor,cursor => +flags => R.word(Input,cursor,cursor => +order =>
        target(p,cursor,C.Target{lo,hi,R.float(offset),biome,flags,order},acc,valid,queries)))))))

@unsafe
def target(n: Nat,cursor: R.Cursor,+value: C.Target,acc: List<&2,C.Target>,valid: Bool,queries: U32) -> Input:
  targets(n,cursor,value <> acc,Bool.and(valid,C.valid(value)),queries)

@unsafe
def requests(n: Nat,cursor: R.Cursor,targets: List<&2,C.Target>,a: Array<Request>,+i: U32,count: U32,+valid: Bool) -> Input:
  match n:
    case 0n: Input{cursor,targets,a,count,valid}
    case 1n+p: axes(Input,cursor,cursor => +point => R.word(Input,cursor,cursor => +mode =>
      requests(p,cursor,targets,Array.set(Request,a,i,Request{point,mode}),U32.inc(i),count,
        Bool.and(valid,Bool.and(C.finite_axes(point),U32.is_le(mode,31))))))

def checked_size(valid: Bool,cursor: R.Cursor,count: U32,queries: U32) -> Input:
  match valid:
    case False{}: Input{cursor,[],[Request{C.Axes{0.0,0.0,0.0,0.0,0.0,0.0},32} : Request*1n],0,False{}}
    case True{}: targets(U32.to_nat(count),cursor,[],True{},queries)

def decode(bytes: Z.Bytes) -> Input:
  R.word(Input,R.start(bytes),cursor => +count => R.word(Input,cursor,cursor => +queries =>
    checked_size(Bool.and(U32.is_le(count,65536),U32.is_le(queries,4096)),cursor,count,queries)))

def choice(value: C.Choice) -> Row:
  match value:
    case C.Missing{}: Row{0,4294967295,4294967295,0,0}
    case C.Choice{fitness,biome,order,continentalness}: Row{1,biome,order,F32.bits(fitness),F32.bits(continentalness)}

def query(coast: Bool,model: C.Model,point: C.Axes,+mode: U32) -> Row:
  match coast:
    case True{}: choice(C.coast(model,point))
    case False{}: choice(C.lookup(model,C.Query{point,U32.is_ne(U32.and(mode,1),0),U32.is_ne(U32.and(mode,2),0),
      U32.is_ne(U32.and(mode,4),0),U32.is_ne(U32.and(mode,8),0)}))

def active(yes: Bool,model: C.Model,point: C.Axes,+mode: U32) -> Row:
  match yes:
    case False{}: Row{0,4294967295,4294967295,0,0}
    case True{}: query(U32.is_ne(U32.and(mode,16),0),model,point,mode)

def row(+model: C.Model,request: Request) -> Row:
  match request:
    case Request{point,+mode}: active(U32.is_le(mode,31),model,point,mode)

def calculate(+model: C.Model,requests: Array<Request>) -> Array<Row>:
  match requests:
    case ALeaf{request}: ALeaf{row(model,request)}
    case ANode{a,b}:
      a b = calculate(model,a) calculate(model,b)
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
    case Row{present,biome,order,fitness,continentalness}: encode(n,a,
      B.int(B.int(B.int(B.int(B.int(w,present),biome),order),fitness),continentalness),U32.inc(i))

def output(result: Result<&1,&1,U32,Z.Bytes>,path: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"climate fixture output overflow")
    case Done{bytes}: F.save(bytes,path)

def generate(valid: Bool,targets: List<&2,C.Target>,queries: Array<Request>,count: U32,path: String) -> IO(Unit):
  match valid:
    case False{}: IO.die(Unit,712,"invalid climate fixture")
    case True{}:
      do IO<Unit>:
        model : C.Model = C.prepare(targets)
        # Stock Bend 2.0.36 keeps later bangs on the CPU pool if this
        # evaluation already forked. Finish index preparation before dispatch.
        _ : Nat <- IO.now()
        rows : Array<Row> = calculate!(model,queries)
        output(B.done(encode(U32.to_nat(count),rows,B.new(),0)),path)

def decoded(input: Input,path: String) -> IO(Unit):
  match input:
    case Input{cursor,targets,queries,count,valid}: generate(Bool.and(valid,R.end(cursor)),targets,queries,count,path)

def data(read: T.Read,path: String) -> IO(Unit):
  match read:
    case T.End{_}: IO.die(Unit,712,"missing climate fixture")
    case T.Complete{file,bytes}:
      do IO<Unit>:
        _ : Unit <- File.close(file)
        decoded(decode(bytes),path)

def size(status: Result<&1,&1,U32 & String,U32>,file: File,path: String) -> IO(Unit):
  match status:
    case Fail{_}: IO.die(Unit,712,"unreadable climate fixture")
    case Done{count}:
      do IO<Unit>:
        read : T.Read <- T.exact(file,count,B.new(),False{})
        data(read,path)

def sized(pair: File & Result<&1,&1,U32 & String,U32>,path: String) -> IO(Unit):
  (file,status) = pair
  size(status,file,path)

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        file : File <- IO.try(File,File.open(input,"r"))
        pair : File & Result<&1,&1,U32 & String,U32> <- File.size(file)
        sized(pair,output)
    case _: IO.die(Unit,2,"usage: climate-fixtures input output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
