import Base
import ../structure_entities.bend as E
import ../structure_template.bend as T
import ../structure_registry.bend as M
import ../structure_values.bend as V
import ../structure_geometry.bend as G
import ../structure_nbt.bend as N
import ../registry.bend as R
import ../registry_values.bend as RV
import ../registry_density.bend as D
import ../binary.bend as B
import ../word64.bend as W
import ./binary-fixtures.bend as Output

@unsafe
def context(+row: M.Node,keys: D.Keys) -> E.Context:
  E.Context{keys,G.position(M.item(row,1)),M.int(M.item(row,2),0),G.Box{G.position(M.item(row,3)),G.position(M.item(row,4))},W.U64{M.int(M.item(row,5),0),M.int(M.item(row,6),0)},M.int(M.item(row,7),0)}

@unsafe
def encoded(doc: R.Document,tags: List<&2,M.Node>,w: B.Writer,next: R.Document -> B.Writer -> IO(Unit)) -> IO(Unit):
  match tags:
    case Nil{}: next(doc,w)
    case tag <> rest: N.root(IO(Unit),doc,tag,w,doc => w => encoded(doc,rest,w,next))

@unsafe
def written(doc: R.Document,+tags: List<&2,M.Node>,w: B.Writer,next: R.Document -> B.Writer -> IO(Unit)) -> IO(Unit):
  encoded(doc,tags,B.int(w,U32.from_nat(List.length(&2,M.Node,tags))),next)

@unsafe
def rows(doc: R.Document,+templates: M.Node,+xs: M.Node,+keys: D.Keys,+i: U32,n: U32,w: B.Writer,output: String) -> IO(Unit):
  match n:
    case 0: Output.unwrapped(B.done(w),output)
    case _: written(doc,E.place(T.Template{[],M.item(templates,M.int(M.item(M.item(xs,i),0),0)),True{}},context(M.item(xs,i),keys)),w,
      doc => w => rows(doc,templates,xs,keys,U32.inc(i),U32.sub(n,1),w,output))

@unsafe
def selected(found: Maybe<&2,R.Value>,doc: R.Document,+keys: D.Keys,output: String) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,852,"missing entity cases")
    case Some{value}: V.project(IO(Unit),doc,value,doc => +root => rows(doc,M.item(root,0),M.item(root,1),keys,0,M.count(M.item(root,1)),B.int(B.new(),M.count(M.item(root,1))),output))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"registry load failed")
    case Done{doc}: E.keys(IO(Unit),doc,doc => keys => RV.root(IO(Unit),doc,doc => root => RV.field(IO(Unit),doc,root,"entity_cases",doc => found => selected(found,doc,keys,output))))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: structure-entities profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
