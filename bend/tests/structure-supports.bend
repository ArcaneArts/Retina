import Base
import ../structure_supports.bend as Support
import ../structure_blocks.bend as Bk
import ../structure_processors.bend as S
import ../structure_template.bend as T
import ../structure_registry.bend as M
import ../structure_values.bend as V
import ../structure_geometry.bend as G
import ../structure_plan.bend as Plan
import ../registry.bend as R
import ../registry_values.bend as RV
import ../material_columns_data.bend as MD
import ../terrain_surface.bend as Surface
import ../density.bend as Q
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../binary.bend as B
import ../word64.bend as W
import ./structure-blocks.bend as F
import ./binary-fixtures.bend as Output

@unsafe
def val(+row: M.Node,i: U32) -> U32:
  M.int(M.item(row,i),0)

@unsafe
def columns(n: U32,+row: M.Node) -> List<&2,MD.Column>:
  match n:
    case 0: []
    case _: MD.Column{64,val(row,1),0.0,0.0,0.0,0.0,0.0,[MD.Run{0,124,val(row,7)},MD.Run{124,127,val(row,8)},MD.Run{127,128,val(row,9)},MD.Run{128,133,val(row,10)},MD.Run{133,384,0}],5,True{}} <> columns(U32.sub(n,1),row)

@unsafe
def world(+row: M.Node,env: S.Environment) -> E.World:
  match env:
    case S.Environment{_,names}: E.World{MD.Grid{Surface.Tile{val(row,5),val(row,6),16,16,123,0},4294967232,384,Q.prepared_table(MD.Column,columns(256,row)),None{}},P.Policy{Q.prepared_table(U32,F.list(Q.table_size(U32,names))),Q.Empty{},Q.Empty{},0,0,0,False{}},E.Empty{},[]}

@unsafe
def context(+row: M.Node,env: S.Environment,metadata: Bk.Metadata) -> Bk.Context:
  Bk.Context{env,metadata,S.Program{S.Noop{},0},P.Position{U32.add(val(row,5),8),val(row,3),U32.add(val(row,6),8)},val(row,2),G.Box{P.Position{val(row,5),4294967232,val(row,6)},P.Position{U32.add(val(row,5),15),319,U32.add(val(row,6),15)}},Some{Plan.Heights{val(row,5),val(row,6),1,Q.Leaf{64}}},M.flag(M.item(row,11)),False{},W.U64{0,123},val(row,7)}

@unsafe
def run(+template: T.Template,+row: M.Node,+env: S.Environment,metadata: Bk.Metadata,model: Support.Model) -> Bk.Placement:
  +ctx = context(row,env,metadata)
  Bk.place_after(template,ctx,Support.apply(M.flag(M.item(row,4)),template,ctx,model,world(row,env)))

@unsafe
def rows(doc: R.Document,+xs: M.Node,+i: U32,n: U32,+tc: T.Context,+env: S.Environment,+metadata: Bk.Metadata,+model: Support.Model,w: B.Writer,output: String) -> IO(Unit):
  match n:
    case 0: Output.unwrapped(B.done(w),output)
    case _: selected(doc,M.item(xs,i),tc,env,metadata,model,w,doc => w => rows(doc,xs,U32.inc(i),U32.sub(n,1),tc,env,metadata,model,w,output))

@unsafe
def selected(doc: R.Document,+row: M.Node,+tc: T.Context,+env: S.Environment,+metadata: Bk.Metadata,+model: Support.Model,w: B.Writer,next: R.Document -> B.Writer -> IO(Unit)) -> IO(Unit):
  RV.root(IO(Unit),doc,doc => root => RV.field(IO(Unit),doc,root,"structures",doc => found => structure(found,doc,row,tc,env,metadata,model,w,next)))

@unsafe
def structure(found: Maybe<&2,R.Value>,doc: R.Document,+row: M.Node,+tc: T.Context,+env: S.Environment,+metadata: Bk.Metadata,+model: Support.Model,w: B.Writer,next: R.Document -> B.Writer -> IO(Unit)) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,852,"missing structures")
    case Some{value}: RV.field(IO(Unit),doc,value,"templates",doc => found => templates(found,doc,row,tc,env,metadata,model,w,next))

@unsafe
def templates(found: Maybe<&2,R.Value>,doc: R.Document,+row: M.Node,+tc: T.Context,+env: S.Environment,+metadata: Bk.Metadata,+model: Support.Model,w: B.Writer,next: R.Document -> B.Writer -> IO(Unit)) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,852,"missing templates")
    case Some{array}: R.item(IO(Unit),doc,array,val(row,0),doc => found => chosen(found,doc,row,tc,env,metadata,model,w,next))

@unsafe
def chosen(found: Maybe<&2,R.Value>,doc: R.Document,+row: M.Node,+tc: T.Context,+env: S.Environment,+metadata: Bk.Metadata,+model: Support.Model,w: B.Writer,next: R.Document -> B.Writer -> IO(Unit)) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,852,"missing template")
    case Some{value}: T.prepare(IO(Unit),doc,tc,value,0,val(row,2),doc => template => F.encoded(IO(Unit),doc,run(template,row,env,metadata,model),S.Program{S.Noop{},0},w,next))

@unsafe
def cases(found: Maybe<&2,R.Value>,doc: R.Document,+tc: T.Context,+env: S.Environment,+metadata: Bk.Metadata,+model: Support.Model,output: String) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,852,"missing support cases")
    case Some{value}: V.project(IO(Unit),doc,value,doc => +xs => rows(doc,xs,0,M.count(xs),tc,env,metadata,model,B.int(B.new(),M.count(xs)),output))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"registry load failed")
    case Done{doc}: S.prepare_environment(IO(Unit),doc,doc => +env => T.keys(IO(Unit),doc,env,doc => tc => Bk.metadata(IO(Unit),doc,doc => metadata => Support.prepare(IO(Unit),doc,doc => model => RV.root(IO(Unit),doc,doc => root => RV.field(IO(Unit),doc,root,"support_cases",doc => found => cases(found,doc,tc,env,metadata,model,output)))))))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: structure-supports profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
