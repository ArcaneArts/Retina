import Base
import ../registry.bend as R
import ../registry_values.bend as V
import ../registry_surface_feature.bend as RF
import ../registry_placement.bend as RP
import ../registry_chunk_catalog.bend as RC
import ../chunk_catalog.bend as CC
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../placement_merge.bend as Merge
import ../material_columns_data.bend as MD
import ../density.bend as Q
import ../surface_feature.bend as F
import ../tree_data.bend as T
import ../generated_region.bend as GR
import ../word64.bend as W
import ../noise.bend as N
import ./trees.bend as Fixture
import ./tree-world.bend as World

@unsafe
def select(xs: List<&2,F.Feature>,+trunk: U32) -> Maybe<&2,F.Feature>:
  match xs:
    case Nil{}: None{}
    case F.Tree{+recipe} <> rest: select_tree(recipe,rest,trunk)
    case _ <> rest: select(rest,trunk)

@unsafe
def select_tree(+recipe: T.Recipe,rest: List<&2,F.Feature>,+trunk: U32) -> Maybe<&2,F.Feature>:
  match recipe:
    case T.Recipe{+kind,_,_,_,_,_,_,_,_,_,_,_,_,_}: selected(U32.is_eq(kind,trunk),recipe,rest,trunk)

@unsafe
def selected(ok: Bool,recipe: T.Recipe,rest: List<&2,F.Feature>,+trunk: U32) -> Maybe<&2,F.Feature>:
  match ok:
    case True{}: Some{F.Tree{recipe}}
    case False{}: select(rest,trunk)

@unsafe
def region_axis(+axis: U32) -> U32:
  U32.or(U32.shrn(axis,9n),N.word(U32.is_ge(axis,2147483648),4286578688,0))

@unsafe
def save(+origin: W.U64,grid: MD.Grid,catalog: CC.Catalog,path: String) -> IO(Unit):
  match origin:
    case W.U64{x,z}:
      do IO<Unit>:
        result : Result<&1,&1,U32,U32 & U32> <- GR.run_partial(GR.Input{GR.Request{region_axis(x),region_axis(z),123456789,2147483648,5023,1700000000},path,True{}},grid,catalog)
        saved(result)

def saved(result: Result<&1,&1,U32,U32 & U32>) -> IO(Unit):
  match result:
    case Done{(4,_)}: IO.pure(Unit,Unit{})
    case _: IO.die(Unit,2,"tree MCA did not contain four chunks")

@unsafe
def cases(+i: U32,+features: List<&2,F.Feature>,+policy: P.Policy,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match i:
    case 4: IO.print("PASS: registered trees through resident merge and MCA")
    case _: case_selected(select(features,i),i,features,policy,catalog,output)

@unsafe
def case_selected(feature: Maybe<&2,F.Feature>,+i: U32,+features: List<&2,F.Feature>,+policy: P.Policy,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match feature:
    case None{}: IO.die(Unit,2,"missing requested test tree")
    case Some{feature}:
      do IO<Unit>:
        # Registry preparation can fork on the host. Finish that work at a real
        # IO boundary before dispatching the complete tree+merge computation.
        _ : Unit <- IO.print("Tree MCA fixture " ++ U32.show(i))
        grid : MD.Grid = generate!(feature,policy)
        _ : Unit <- save(World.origin(0),grid,catalog,output ++ "." ++ U32.show(i) ++ ".mca")
        cases(U32.inc(i),features,policy,catalog,output)

@unsafe
def generate(feature: F.Feature,+policy: P.Policy) -> MD.Grid:
  merged(F.place(feature,W.U64{0,123},F.Model{Q.Empty{},Q.Empty{}},World.world(policy,12),World.point(12)))

@unsafe
def merged(result: F.Placement) -> MD.Grid:
  match result:
    case F.Placement{_,E.World{source,P.Policy{_,flags,_,_,_,_,_},patch},_}:
      Merge.apply(source,Q.Leaf{patch},Merge.Repair{flags,Q.Empty{},Q.Empty{}})

@unsafe
def catalog_ready(result: Result<&1,&1,U32,CC.Catalog>,+features: List<&2,F.Feature>,+policy: P.Policy,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid tree chunk catalog")
    case Done{catalog}: cases(1,features,policy,catalog,output)

@unsafe
def features_ready(result: Result<&1,&1,U32,List<&2,F.Feature>>,doc: R.Document,+policy: P.Policy,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid surface feature")
    case Done{xs}: RC.prepare(IO(Unit),doc,doc => result => catalog_ready(result,List.reverse(&2,F.Feature,xs),policy,output))

@unsafe
def policy_ready(result: Result<&1,&1,U32,P.Model>,doc: R.Document,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid placement profile")
    case Done{P.Model{_,_,+policy}}: RF.context(IO(Unit),doc,doc => +context => V.root(IO(Unit),doc,doc => root => V.field(IO(Unit),doc,root,"decorations",doc => found =>
      Fixture.decorations(IO(Unit),found,doc,context,palette(policy),0,doc => result => features_ready(result,doc,policy,output)))))

@unsafe
def palette(policy: P.Policy) -> U32:
  match policy:
    case P.Policy{masks,_,_,_,_,_,_}: Q.table_size(U32,masks)

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid tree profile")
    case Done{doc}: RP.prepare(IO(Unit),doc,doc => result => policy_ready(result,doc,output))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: tree-mca profile output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
