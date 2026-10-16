import Base
import ../registry.bend as P
import ../registry_lighting.bend as RL
import ../light_volume.bend as L
import ../density.bend as Q
import ../binary.bend as B
import ../compression.bend as Z
import ./binary-fixtures.bend as T

@unsafe
def rows(n: U32,+i: U32,+states: Q.Table<L.Optics>,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: row(Q.get(L.Optics,states,i,L.Optics{0,0,0,0,0,0,0}),U32.sub(n,1),U32.inc(i),states,w)

def row(value: L.Optics,n: U32,i: U32,states: Q.Table<L.Optics>,w: B.Writer) -> B.Writer:
  match value:
    case L.Optics{t,a,b,c,d,e,f}: rows(n,i,states,B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(w,t),a),b),c),d),e),f),0))

@unsafe
def bits(n: U32,+i: U32,+values: Q.Table<U32>,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: bits(U32.sub(n,1),U32.inc(i),values,B.int(w,Q.get(U32,values,i,0)))

def projected(result: Result<&1,&1,U32,L.Model>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid registered lighting")
    case Done{L.Model{sky,stride,+states,+coverage}}:
      T.unwrapped(B.done(bits(Q.table_size(U32,coverage),0,coverage,
        rows(Q.table_size(L.Optics,states),0,states,
          B.int(B.int(B.int(B.int(B.new(),Z.word(sky,1,0)),stride),Q.table_size(L.Optics,states)),Q.table_size(U32,coverage))))),output)

def loaded(result: Result<&1,&1,U32,P.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid profile transport")
    case Done{doc}: RL.prepare(IO(Unit),doc,doc => model => projected(model,output))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,P.Document> <- P.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: registry-lighting input.rbp output.bin")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
