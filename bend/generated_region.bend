import Base
import ./generated_chunk.bend as G
import ./chunk_catalog.bend as C
import ./material_columns_data.bend as M
import ./compression.bend as Z
import ./region.bend as R
import ./reader.bend as B
import ./precise.bend as D
import ./transport.bend as T

# New-file component writer. The caller owns a private staging path and only
# publishes it after acknowledgement. No existing world file may be passed.
# Keep eight independent chunk tasks in a fork tree; only compressed records
# survive each batch. Never retain a region of expanded block/NBT arrays.
type Request is Data:
  Request{x: U32,z: U32,low: U32,high: U32,version: U32,timestamp: U32}
type Input is Type:
  Input{request: Request,path: String,valid: Bool}
type Batch is Type:
  Leaf{result: Result<&1,&1,U32,R.Record>}
  Node{left: Batch,right: Batch}

@unsafe
def path(n: Nat,cursor: B.Cursor,acc: String,+valid: Bool,request: Request) -> Input:
  match n:
    case 0n: Input{request,String.reverse(acc),Bool.and(valid,B.end(cursor))}
    case 1n+p: B.word(Input,cursor,cursor => +code => path(p,cursor,SCon{Char.from_u32(code),acc},
      Bool.and(valid,Bool.and(U32.is_gt(code,0),Bool.and(U32.is_le(code,1114111),
        Bool.or(U32.is_lt(code,55296),U32.is_gt(code,57343))))),request))

def length(valid: Bool,+count: U32,cursor: B.Cursor,request: Request) -> Input:
  match valid:
    case False{}: Input{request,"",False{}}
    case True{}: path(U32.to_nat(count),cursor,"",True{},request)

def decode(bytes: Z.Bytes) -> Input:
  B.word(Input,B.start(bytes),cursor => x => B.word(Input,cursor,cursor => z =>
    B.word(Input,cursor,cursor => low => B.word(Input,cursor,cursor => high =>
      B.word(Input,cursor,cursor => version => B.word(Input,cursor,cursor => timestamp =>
        B.word(Input,cursor,cursor => +count => length(Bool.and(U32.is_gt(count,0),U32.is_le(count,4096)),
          count,cursor,Request{x,z,low,high,version,timestamp}))))))))

def chunk(+request: Request,+slot: U32) -> G.Request:
  match request:
    case Request{+x,+z,low,high,version,_}: G.Request{U32.add(U32.mul(x,32),U32.mod(slot,32)),
      U32.add(U32.mul(z,32),U32.div(slot,32)),low,high,version}

def coordinate(+value: U32) -> Bool:
  +point = D.integer(value)
  Bool.and(Bool.not(D.less(point,D.integer(4290772992))),Bool.not(D.less(D.integer(4194303),point)))

def aligned(+grid: M.Grid) -> Bool:
  match grid:
    case M.Grid{_,+minimum,+height,_}: Bool.and(U32.is_zero(U32.mod(minimum,16)),U32.is_zero(U32.mod(height,16)))

def validation(+grid: M.Grid,+request: Request) -> U32:
  match request:
    case Request{+x,+z,_,_,_,_}:
      Z.word(Bool.not(Bool.and(coordinate(x),Bool.and(coordinate(z),aligned(grid)))),733,
        Z.word(Bool.and(G.covered(grid,chunk(request,0)),G.covered(grid,chunk(request,1023))),0,732))

def record(result: Result<&1,&1,U32,Z.Bytes>,slot: U32,request: Request) -> Result<&1,&1,U32,R.Record>:
  match result request:
    case Fail{code} _: Fail{code}
    case Done{bytes} Request{_,_,_,_,_,timestamp}: Done{R.Record{slot,timestamp,bytes}}

@unsafe
def batch(+n: Nat,+base: U32,+grid: M.Grid,+catalog: C.Catalog,+request: Request) -> Batch:
  match n:
    case 0n: Leaf{record(G.compress_result(True{},G.encode(grid,catalog,chunk(request,base))),base,request)}
    case 1n+p:
      left right = batch(p,base,grid,catalog,request) batch(p,U32.add(base,U32.shln(1,p)),grid,catalog,request)
      Node{left,right}

@unsafe
def flatten(tree: Batch,acc: List<R.Record>) -> Result<&1,&1,U32,List<R.Record>>:
  match tree:
    case Leaf{Fail{code}}: Fail{code}
    case Leaf{Done{record}}: Done{record <> acc}
    case Node{left,right}: left_done(flatten(left,acc),right)

@unsafe
def left_done(result: Result<&1,&1,U32,List<R.Record>>,right: Batch) -> Result<&1,&1,U32,List<R.Record>>:
  match result:
    case Fail{code}: Fail{code}
    case Done{acc}: flatten(right,acc)

@unsafe
def collected(result: Result<&1,&1,U32,List<R.Record>>,base: U32,grid: M.Grid,catalog: C.Catalog,request: Request) -> Result<&1,&1,U32,R.Plan>:
  match result:
    case Fail{code}: Fail{code}
    case Done{acc}: collect(base,grid,catalog,request,acc)

@unsafe
def collect(+base: U32,+grid: M.Grid,+catalog: C.Catalog,+request: Request,acc: List<R.Record>) -> Result<&1,&1,U32,R.Plan>:
  match base:
    case 1024: R.plan(acc)
    case _: collected(flatten(batch(3n,base,grid,catalog,request),acc),U32.add(base,8),grid,catalog,request)

@unsafe
def segments(file: File,rest: List<Z.Bytes>,chunks: U32,sectors: U32) -> IO(Result<&1,&1,U32,U32 & U32>):
  match rest:
    case Nil{}:
      do IO<Result<&1,&1,U32,U32 & U32>>:
        _ : Unit <- File.close(file)
        IO.pure(Result<&1,&1,U32,U32 & U32>,Done{(chunks,sectors)})
    case bytes <> rest:
      do IO<Result<&1,&1,U32,U32 & U32>>:
        file : File <- T.write(file,bytes)
        segments(file,rest,chunks,sectors)

def save(result: Result<&1,&1,U32,R.Plan>,path: String) -> IO(Result<&1,&1,U32,U32 & U32>):
  match result:
    case Fail{code}: IO.pure(Result<&1,&1,U32,U32 & U32>,Fail{code})
    case Done{R.Plan{parts,sectors,chunks}}:
      do IO<Result<&1,&1,U32,U32 & U32>>:
        opened : Result<&1,&1,U32 & String,File> <- File.open(path,"w")
        file : File <- T.unwrap(File,opened)
        segments(file,parts,chunks,sectors)

def accepted(code: U32,grid: M.Grid,catalog: C.Catalog,request: Request,path: String) -> IO(Result<&1,&1,U32,U32 & U32>):
  match code:
    case 0: save(collect(0,grid,catalog,request,[]),path)
    case _: IO.pure(Result<&1,&1,U32,U32 & U32>,Fail{code})

def run(input: Input,+grid: M.Grid,catalog: C.Catalog) -> IO(Result<&1,&1,U32,U32 & U32>):
  match input:
    case Input{_,_,False{}}: IO.pure(Result<&1,&1,U32,U32 & U32>,Fail{603})
    case Input{+request,path,True{}}: accepted(validation(grid,request),grid,catalog,request,path)
