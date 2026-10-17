import Base
import ../registry.bend as P
import ../registry_climate.bend as RC
import ../climate.bend as C
import ../climate_batch.bend as CB
import ../binary.bend as B
import ../compression.bend as Z
import ../transport.bend as T

# CPU-only correctness driver. Reuse the production projection, query decoder,
# parallel lookup and result encoder without importing the terrain engine or
# dispatching GPU work. Each argument triple is profile/query/output filenames.
def save(result: Result<&1,&1,U32,Z.Bytes>,path: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"climate test output overflow")
    case Done{bytes}:
      do IO<Unit>:
        file : File <- IO.try(File,File.open(path,"w"))
        file : File <- T.write(file,bytes)
        File.close(file)

def failure(code: U32,path: String) -> IO(Unit):
  save(B.done(B.int(B.int(B.int(B.new(),code),0),0)),path)

def calculated(rows: Array<CB.Row>,count: U32,targets: U32,biomes: U32,path: String) -> IO(Unit):
  save(B.done(CB.encode(U32.to_nat(count),rows,B.int(B.int(B.int(B.new(),0),targets),biomes),0)),path)

def input_valid(valid: Bool,model: C.Model,queries: Array<CB.Request>,count: U32,targets: U32,biomes: U32,path: String) -> IO(Unit):
  match valid:
    case False{}: failure(603,path)
    case True{}: calculated(CB.calculate(model,queries),count,targets,biomes,path)

def input(value: CB.Input,prepared: RC.Prepared,path: String) -> IO(Unit):
  match value prepared:
    case CB.Input{queries,+count,valid} RC.Prepared{model,targets,biomes}:
      input_valid(Bool.and(valid,U32.is_le(count,512)),model,queries,count,targets,biomes,path)

def read_queries(read: T.Read,prepared: RC.Prepared,path: String) -> IO(Unit):
  match read:
    case T.End{file}:
      do IO<Unit>:
        _ : Unit <- File.close(file)
        failure(603,path)
    case T.Complete{file,bytes}:
      do IO<Unit>:
        _ : Unit <- File.close(file)
        input(CB.decode(bytes),prepared,path)

def bounded(valid: Bool,file: File,size: U32,prepared: RC.Prepared,path: String) -> IO(Unit):
  match valid:
    case False{}:
      do IO<Unit>:
        _ : Unit <- File.close(file)
        failure(603,path)
    case True{}:
      do IO<Unit>:
        read : T.Read <- T.exact(file,size,B.new(),False{})
        read_queries(read,prepared,path)

def sized(pair: File & Result<&1,&1,U32 & String,U32>,prepared: RC.Prepared,path: String) -> IO(Unit):
  (file,status) = pair
  do IO<Unit>:
    +size : U32 <- T.unwrap(U32,status)
    bounded(U32.is_le(size,14340),file,size,prepared,path)

def projected(result: Result<&1,&1,U32,RC.Prepared>,query: String,path: String) -> IO(Unit):
  match result:
    case Fail{code}: failure(code,path)
    case Done{prepared}:
      do IO<Unit>:
        file : File <- IO.try(File,File.open(query,"r"))
        pair : File & Result<&1,&1,U32 & String,U32> <- File.size(file)
        sized(pair,prepared,path)

def loaded(result: Result<&1,&1,U32,P.Document>,query: String,path: String) -> IO(Unit):
  match result:
    case Fail{code}: failure(code,path)
    case Done{doc}: RC.prepare(IO(Unit),doc,doc => result => projected(result,query,path))

def run(profile: String,query: String,path: String) -> IO(Unit):
  do IO<Unit>:
    result : Result<&1,&1,U32,P.Document> <- P.load(profile)
    loaded(result,query,path)

@unsafe
def cases(args: List<String>) -> IO(Unit):
  match args:
    case Nil{}: IO.pure(Unit,Unit{})
    case profile <> query <> path <> rest:
      do IO<Unit>:
        _ : Unit <- run(profile,query,path)
        cases(rest)
    case _: IO.die(Unit,2,"usage: climate-coverage [profile query output]...")

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> rest: cases(rest)
    case _: IO.die(Unit,2,"missing executable argument")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
