import Base
import ../structure_registry.bend as M
import ../structure_values.bend as V
import ../structure_nbt.bend as N
import ../registry.bend as R
import ../registry_values.bend as RV
import ../binary.bend as B
import ./binary-fixtures.bend as T

@unsafe
def rows(doc: R.Document,+xs: M.Node,+i: U32,n: U32,w: B.Writer,output: String) -> IO(Unit):
  match n:
    case 0: T.unwrapped(B.done(w),output)
    case _: N.root(IO(Unit),doc,M.item(xs,i),w,doc => w => rows(doc,xs,U32.inc(i),U32.sub(n,1),w,output))

@unsafe
def selected(found: Maybe<&2,R.Value>,doc: R.Document,output: String) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,852,"missing nbt cases")
    case Some{value}: V.project(IO(Unit),doc,value,doc => +xs => rows(doc,xs,0,M.count(xs),B.int(B.new(),M.count(xs)),output))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"registry load failed")
    case Done{doc}: RV.root(IO(Unit),doc,doc => root => RV.field(IO(Unit),doc,root,"nbt_cases",doc => found => selected(found,doc,output)))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: structure-nbt profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
