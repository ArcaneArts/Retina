import Base
import ../structure_processors.bend as S
import ../structure_values.bend as V
import ../structure_registry.bend as M
import ../registry.bend as R
import ../registry_values.bend as RV
import ../binary.bend as B
import ../word64.bend as W
import ../placement_data.bend as P
import ./binary-fixtures.bend as T

# Canonical exact-value transcript; the independent reader reconstructs metadata
# and checks every material, RNG state and capped counter after each placement.
@unsafe
def node(+v: M.Node,w: B.Writer) -> B.Writer:
  match v:
    case M.Scalar{R.Value{t,a,b,_}}: B.int(B.int(B.int(w,t),a),b)
    case M.Array{_,_}: items(v,0,M.count(v),B.int(B.int(w,6),M.count(v)))
    case M.Object{_,+xs}: fields(xs,B.int(B.int(w,7),U32.from_nat(List.length(&2,M.Field,xs))))

@unsafe
def items(+v: M.Node,+i: U32,n: U32,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: items(v,U32.inc(i),U32.sub(n,1),node(M.item(v,i),w))

@unsafe
def fields(xs: List<&2,M.Field>,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case M.Field{key,value} <> rest: fields(rest,node(value,B.int(w,key)))

@unsafe
def counts(+xs: List<&2,S.Count>,+i: U32,n: U32,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: counts(xs,U32.inc(i),U32.sub(n,1),B.int(w,S.count(xs,i)))

@unsafe
def result(+state: S.State,+n: U32,w: B.Writer) -> B.Writer:
  match state:
    case S.State{m,tag,r,xs,keep}: node(tag,counts(xs,0,n,B.long(B.int(B.int(w,W.flag(keep)),m),r)))

@unsafe
def int(+v: M.Node,i: U32) -> U32:
  M.int(M.item(v,i),0)

@unsafe
def position(+v: M.Node,+i: U32) -> P.Position:
  P.Position{int(v,i),int(v,U32.inc(i)),int(v,U32.add(i,2))}

@unsafe
def probe(+program: S.Program,+env: S.Environment,+row: M.Node,+previous: S.State) -> S.State:
  match previous:
    case S.State{_,_,_,xs,_}: S.apply(program,env,S.Context{position(row,5),position(row,8),int(row,1),int(row,2)},
      S.State{int(row,0),M.item(row,11),W.U64{int(row,3),int(row,4)},xs,True{}})

@unsafe
def probes(+program: S.Program,+env: S.Environment,+rows: M.Node,+i: U32,n: U32,+previous: S.State,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: next_probe(probe(program,env,M.item(rows,i),previous),program,env,rows,U32.inc(i),U32.sub(n,1),w)

@unsafe
def next_probe(+state: S.State,+program: S.Program,+env: S.Environment,+rows: M.Node,i: U32,n: U32,w: B.Writer) -> B.Writer:
  match program:
    case S.Program{_,size}: probes(program,env,rows,i,n,state,result(state,size,w))

@unsafe
def compiled(+program: S.Program,+env: S.Environment,+rows: M.Node,w: B.Writer) -> B.Writer:
  match program:
    case S.Program{_,size}: probes(program,env,rows,0,M.count(rows),S.State{0,M.empty(),W.U64{0,0},[],True{}},B.int(B.int(w,size),M.count(rows)))

@unsafe
def cases(+env: S.Environment,+xs: M.Node,+i: U32,n: U32,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: cases(env,xs,U32.inc(i),U32.sub(n,1),compiled(S.compile(env,M.item(M.item(xs,i),0)),env,M.item(M.item(xs,i),1),w))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"registry load failed")
    case Done{doc}: S.prepare_environment(IO(Unit),doc,doc => +env => RV.root(IO(Unit),doc,doc => root => RV.field(IO(Unit),doc,root,"processor_cases",doc => found => selected(found,doc,env,output))))

@unsafe
def selected(found: Maybe<&2,R.Value>,doc: R.Document,+env: S.Environment,output: String) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,851,"missing fixture cases")
    case Some{value}: V.project(IO(Unit),doc,value,doc => +xs => T.unwrapped(B.done(cases(env,xs,0,M.count(xs),B.int(B.new(),M.count(xs)))),output))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: structure-processors profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
