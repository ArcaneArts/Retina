import Base
import ../structure_template.bend as T
import ../structure_blocks.bend as Bk
import ../structure_nbt.bend as Nbt
import ../structure_processors.bend as S
import ../structure_registry.bend as M
import ../structure_values.bend as V
import ../structure_geometry.bend as G
import ../structure_plan.bend as Plan
import ../registry.bend as R
import ../registry_values.bend as RV
import ../binary.bend as B
import ../word64.bend as W
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../material_columns_data.bend as MD
import ../terrain_surface.bend as Surface
import ../density.bend as Q
import ../noise.bend as N
import ./structure-processors.bend as F
import ./structure-plan.bend as Geometry
import ./binary-fixtures.bend as Output

@unsafe
def list(+n: U32) -> List<&2,U32>:
  match n:
    case 0: []
    case _: 1 <> list(U32.sub(n,1))

@unsafe
def columns(+i: U32,n: U32) -> List<&2,MD.Column>:
  match n:
    case 0: []
    case _: MD.Column{64,0,0.0,0.0,0.0,0.0,0.0,[MD.Run{0,128,1},MD.Run{128,132,N.word(U32.is_zero(U32.mod(i,7)),2,0)},MD.Run{132,384,0}],3,True{}} <> columns(U32.inc(i),U32.sub(n,1))

@unsafe
def world(+env: S.Environment,+cx: U32,+cz: U32) -> E.World:
  match env:
    case S.Environment{_,names}: E.World{MD.Grid{Surface.Tile{U32.mul(cx,16),U32.mul(cz,16),16,16,123,0},4294967232,384,Q.prepared_table(MD.Column,columns(0,256)),None{}},P.Policy{Q.prepared_table(U32,list(Q.table_size(U32,names))),Q.Empty{},Q.Empty{},2,2,1,False{}},E.Empty{},[]}

@unsafe
def heights(+n: U32,+i: U32) -> List<&2,U32>:
  match n:
    case 0: []
    case _: U32.add(64,U32.mod(U32.add(U32.mod(i,64),U32.div(i,64)),5)) <> heights(U32.sub(n,1),U32.inc(i))

@unsafe
def point(+row: M.Node) -> P.Position:
  P.Position{F.int(row,6),F.int(row,7),F.int(row,8)}

@unsafe
def bounds(+row: M.Node) -> G.Box:
  G.Box{P.Position{U32.mul(F.int(row,9),16),4294967232,U32.mul(F.int(row,10),16)},P.Position{U32.add(U32.mul(F.int(row,9),16),15),319,U32.add(U32.mul(F.int(row,10),16),15)}}

@unsafe
def context(+row: M.Node,+env: S.Environment,+metadata: Bk.Metadata,+program: S.Program) -> Bk.Context:
  Bk.Context{env,metadata,program,point(row),F.int(row,3),bounds(row),Some{Plan.Heights{U32.sub(F.int(row,6),32),U32.sub(F.int(row,8),32),64,Q.prepared_table(U32,heights(4096,0))}},M.flag(M.item(row,4)),M.flag(M.item(row,5)),W.U64{F.int(row,11),F.int(row,12)},1}

@unsafe
def commands(xs: List<&2,E.Command>,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case E.Command{at,material,role,extra} <> rest: commands(rest,B.int(Geometry.position(at,w),material))

@unsafe
def tags(xs: List<&2,Bk.TagWrite>,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case Bk.TagWrite{at,tag} <> rest: tags(rest,F.node(tag,Geometry.position(at,w)))

@unsafe
def written(+out: Bk.Placement,+program: S.Program,w: B.Writer) -> B.Writer:
  match out program:
    case Bk.Placement{world,+values,counts,valid} S.Program{_,n}: tags(List.reverse(&2,Bk.TagWrite,values),B.int(commands(E.journal(world),B.int(F.counts(counts,0,n,B.int(B.int(w,W.flag(valid)),n)),U32.from_nat(List.length(&2,E.Command,E.journal(world))))),U32.from_nat(List.length(&2,Bk.TagWrite,values))))

@unsafe
def prepared(+template: T.Template,+row: M.Node,+env: S.Environment,+metadata: Bk.Metadata) -> Bk.Placement:
  Bk.place(template,context(row,env,metadata,S.compile(env,M.item(row,1))),world(env,F.int(row,9),F.int(row,10)))

@unsafe
def encoded(-A: Type,doc: R.Document,+placement: Bk.Placement,+program: S.Program,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match placement:
    case Bk.Placement{_,tags,_,_}: encoded_tags(A,doc,List.reverse(&2,Bk.TagWrite,tags),written(placement,program,w),next)

@unsafe
def encoded_tags(-A: Type,doc: R.Document,tags: List<&2,Bk.TagWrite>,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match tags:
    case Nil{}: next(doc,w)
    case Bk.TagWrite{_,+tag} <> rest: encoded_tag(A,U32.is_ne(M.tag(tag),0),doc,tag,w,doc => w => encoded_tags(A,doc,rest,w,next))

@unsafe
def encoded_tag(-A: Type,present: Bool,doc: R.Document,tag: M.Node,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match present:
    case False{}: next(doc,B.byte(w,0))
    case True{}: Nbt.root(A,doc,tag,B.byte(w,1),next)

@unsafe
def rows(doc: R.Document,+templates: R.Value,+xs: M.Node,+i: U32,n: U32,+env: S.Environment,+metadata: Bk.Metadata,+ctx: T.Context,w: B.Writer,output: String) -> IO(Unit):
  match n:
    case 0: Output.unwrapped(B.done(w),output)
    case _: R.item(IO(Unit),doc,templates,F.int(M.item(xs,i),0),doc => found => selected_template(found,doc,templates,xs,i,n,env,metadata,ctx,w,output))

@unsafe
def selected_template(found: Maybe<&2,R.Value>,doc: R.Document,+templates: R.Value,+xs: M.Node,+i: U32,n: U32,+env: S.Environment,+metadata: Bk.Metadata,+ctx: T.Context,w: B.Writer,output: String) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,853,"missing template")
    case Some{value}: T.prepare(IO(Unit),doc,ctx,value,F.int(M.item(xs,i),2),F.int(M.item(xs,i),3),doc => template =>
      encoded(IO(Unit),doc,prepared(template,M.item(xs,i),env,metadata),S.compile(env,M.item(M.item(xs,i),1)),w,doc => w => rows(doc,templates,xs,U32.inc(i),U32.sub(n,1),env,metadata,ctx,w,output)))

@unsafe
def selected(found: Maybe<&2,R.Value>,doc: R.Document,+env: S.Environment,+metadata: Bk.Metadata,+ctx: T.Context,+root: R.Value,output: String) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,852,"missing block cases")
    case Some{value}: V.project(IO(Unit),doc,value,doc => +xs => RV.field(IO(Unit),doc,root,"structures",doc => found => structure(found,doc,xs,env,metadata,ctx,output)))

@unsafe
def structure(found: Maybe<&2,R.Value>,doc: R.Document,+xs: M.Node,+env: S.Environment,+metadata: Bk.Metadata,+ctx: T.Context,output: String) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,852,"missing structures")
    case Some{value}: RV.field(IO(Unit),doc,value,"templates",doc => found => templates(found,doc,xs,env,metadata,ctx,output))

@unsafe
def templates(found: Maybe<&2,R.Value>,doc: R.Document,+xs: M.Node,+env: S.Environment,+metadata: Bk.Metadata,+ctx: T.Context,output: String) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,852,"missing templates")
    case Some{value}: rows(doc,value,xs,0,M.count(xs),env,metadata,ctx,B.int(B.new(),M.count(xs)),output)

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"registry load failed")
    case Done{doc}: S.prepare_environment(IO(Unit),doc,doc => +env => Bk.metadata(IO(Unit),doc,doc => +metadata => T.keys(IO(Unit),doc,env,doc => +ctx => RV.root(IO(Unit),doc,doc => +root => RV.field(IO(Unit),doc,root,"block_cases",doc => found => selected(found,doc,env,metadata,ctx,root,output))))))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: structure-blocks profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
