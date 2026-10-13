import Base
import ../vegetation.bend as G
import ../registry_vegetation.bend as RG
import ../registry.bend as R
import ../registry_values.bend as V
import ../registry_cave_features.bend as A
import ../registry_chunk_catalog.bend as RC
import ../chunk_catalog.bend as CC
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../placement_cursor.bend as K
import ../placement_merge.bend as Merge
import ../material_columns_data.bend as MD
import ../surface_feature.bend as F
import ../density.bend as Q
import ../word64.bend as W
import ../noise.bend as N
import ../binary.bend as B
import ./tree-world.bend as World
import ./placement-world-fixtures.bend as Small
import ./surface-features.bend as Output
import ./binary-fixtures.bend as Binary
import ./tree-mca.bend as Mca

type Case is Data:
  Case{status: U32,missing: U32,slices: U32,anchor: G.Anchor,membership: List<&2,U32>}

@unsafe
def ids(+i: U32,+n: U32,+xs: Q.Table<U32>,acc: List<&2,U32>) -> List<&2,U32>:
  match n:
    case 0: List.reverse(&2,U32,acc)
    case _: ids(U32.inc(i),U32.sub(n,1),xs,Q.get(U32,xs,i,0) <> acc)

@unsafe
def drive(+anchor: G.Anchor,+model: G.Model,+fuel: U32,+limit: U32,+slices: U32,+members: List<&2,U32>) -> Case:
  match limit:
    case 0: Case{2,4294967295,slices,anchor,members}
    case _: driven(G.resume(anchor,model,fuel),model,fuel,U32.sub(limit,1),U32.inc(slices),members)

@unsafe
def driven(result: G.Outcome,+model: G.Model,+fuel: U32,+limit: U32,+slices: U32,+members: List<&2,U32>) -> Case:
  match result:
    case G.Finished{anchor}: Case{0,4294967295,slices,anchor,members}
    case G.Unavailable{anchor,recipe,_,_}: Case{1,recipe,slices,anchor,members}
    case G.Paused{anchor}: drive(anchor,model,fuel,limit,slices,members)

@unsafe
def started(+origin: W.U64,+context: U32,+fuel: U32,limit: U32,ids: List<&2,U32>,+model: G.Model) -> Case:
  match origin model:
    case W.U64{x,z} G.Model{P.Model{_,_,+policy},_,_}:
      start_world(World.world(policy,context),P.Position{x,4294967232,z},fuel,limit,ids,model)

@unsafe
def start_world(+world: E.World,+at: P.Position,+fuel: U32,limit: U32,ids: List<&2,U32>,+model: G.Model) -> Case:
  match world:
    case E.World{source,_,_}: drive(G.start(model,source,ids,at,W.U64{2147483648,123456789}),model,fuel,limit,0,G.all_ids(world,at))

@unsafe
def run(+xs: Q.Table<U32>,model: G.Model) -> Case:
  +context = Q.get(U32,xs,0,12)
  started(World.origin(context),context,Q.get(U32,xs,1,1),Q.get(U32,xs,2,1),ids(3,U32.sub(Q.table_size(U32,xs),3),xs,[]),model)

@unsafe
def words(xs: List<&2,U32>,writer: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: writer
    case x <> rest: words(rest,B.int(writer,x))

@unsafe
def write(row: Case,writer: B.Writer) -> B.Writer:
  match row:
    case Case{status,missing,slices,G.Anchor{K.Cursor{queue,_,steps},E.World{_,_,+patch},n,placed,state},+members}:
      w = B.int(B.int(B.int(B.int(B.int(B.int(B.int(writer,status),missing),slices),steps),K.size(queue)),n),placed)
      w = B.int(B.long(w,state),U32.from_nat(List.length(&2,U32,members)))
      Output.write_patch(patch,B.int(words(members,w),Output.patch_size(patch)))

@unsafe
def merged(row: Case,repair: Merge.Repair) -> MD.Grid:
  match row:
    case Case{_,_,_,G.Anchor{_,E.World{source,_,patch},_,_,_},_}: Merge.apply(source,Q.Leaf{patch},repair)

@unsafe
def save_first(first: Bool,row: Case,repair: Merge.Repair,catalog: CC.Catalog,output: String) -> IO(Unit):
  match first:
    case False{}: IO.pure(Unit,Unit{})
    case True{}:
      do IO<Unit>:
        grid : MD.Grid = merged!(row,repair)
        Mca.save(World.origin(12),grid,catalog,output ++ ".mca")

@unsafe
def case_ready(result: Result<&1,&1,U32,Q.Table<U32>>,+n: U32,+at: U32,+i: U32,doc: R.Document,+model: G.Model,+repair: Merge.Repair,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid vegetation case")
    case Done{xs}:
      do IO<Unit>:
        _ : Unit <- IO.print("Vegetation anchor " ++ U32.show(i))
        +row : Case = run!(xs,model)
        _ : Unit <- Binary.unwrapped(B.done(write(row,B.new())),output ++ "." ++ U32.show(i))
        _ : Unit <- save_first(U32.is_zero(i),row,repair,catalog,output)
        cases(n,at,U32.inc(i),doc,model,repair,catalog,output)

@unsafe
def cases(+n: U32,+at: U32,+i: U32,doc: R.Document,+model: G.Model,+repair: Merge.Repair,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match n:
    case 0: IO.print("PASS: coordinated registered vegetation")
    case _: R.value(IO(Unit),doc,at,doc => value => R.get(IO(Unit),doc,U32.inc(at),doc => span =>
      A.array(IO(Unit),Some{value},doc,0,doc => result => case_ready(result,U32.sub(n,1),U32.add(at,span),i,doc,model,repair,catalog,output))))

@unsafe
def membership(source: E.World,output: String) -> IO(Unit):
  do IO<Unit>:
    +ids : List<&2,U32> = G.all_ids!(source,P.Position{29990000,4294967232,4264967296})
    Binary.unwrapped(B.done(words(ids,B.int(B.new(),U32.from_nat(List.length(&2,U32,ids))))),output)

@unsafe
def ready(result: Result<&1,&1,U32,CC.Catalog>,doc: R.Document,+model: G.Model,+repair: Merge.Repair,+output: String) -> IO(Unit):
  match result model:
    case Fail{code} _: IO.die(Unit,code,"invalid vegetation chunk catalog")
    case Done{catalog} G.Model{P.Model{_,_,+policy},_,_}:
      do IO<Unit>:
        _ : Unit <- IO.print("Vegetation membership")
        _ : Unit <- membership(E.World{Small.source(policy,0),policy,E.Empty{}},output ++ ".members")
        V.root(IO(Unit),doc,doc => root => V.field(IO(Unit),doc,root,"vegetation_cases",doc => found => found_cases(found,doc,model,repair,catalog,output)))

@unsafe
def found_cases(found: Maybe<&2,R.Value>,doc: R.Document,model: G.Model,repair: Merge.Repair,catalog: CC.Catalog,output: String) -> IO(Unit):
  match found:
    case Some{R.Value{6,n,_,at}}: cases(n,U32.add(at,3),0,doc,model,repair,catalog,output)
    case _: IO.die(Unit,2,"missing vegetation cases")

@unsafe
def repairs(halves: Result<&1,&1,U32,Q.Table<U32>>,floors: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,+model: G.Model,output: String) -> IO(Unit):
  match halves floors model:
    case Done{halves} Done{floors} G.Model{P.Model{_,_,P.Policy{_,flags,_,_,_,_,_}},_,_}: RC.prepare(IO(Unit),doc,doc => result => ready(result,doc,model,Merge.Repair{flags,halves,floors},output))
    case _ _ _: IO.die(Unit,2,"invalid vegetation repair tables")

@unsafe
def prepared(result: Result<&1,&1,U32,G.Model>,doc: R.Document,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid vegetation profile")
    case Done{model}: V.root(IO(Unit),doc,doc => +root => V.field(IO(Unit),doc,root,"plant_halves",doc => found => A.array(IO(Unit),found,doc,2,doc => halves =>
      V.field(IO(Unit),doc,root,"plant_floor_masks",doc => found => A.array(IO(Unit),found,doc,0,doc => floors => repairs(halves,floors,doc,model,output))))))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid vegetation transport")
    case Done{doc}: RG.prepare(IO(Unit),doc,doc => result => prepared(result,doc,output))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: vegetation profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
