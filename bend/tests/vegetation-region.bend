import Base
import ../registry.bend as R
import ../registry_values.bend as V
import ../registry_cave_features.bend as A
import ../registry_vegetation.bend as RG
import ../registry_chunk_catalog.bend as RC
import ../chunk_catalog.bend as CC
import ../vegetation.bend as G
import ../vegetation_region.bend as Region
import ../placement_data.bend as P
import ../placement_merge.bend as Merge
import ../material_columns_data.bend as M
import ../terrain_surface.bend as S
import ../density.bend as Q
import ../word64.bend as W
import ../binary.bend as B
import ./binary-fixtures.bend as Binary
import ./placement-merge.bend as Output
import ../generated_region.bend as RegionFile
import ../noise.bend as N

@unsafe
def columns(+n: U32,+biome: U32,+stone: U32,+bedrock: U32,+top: U32,+filler: U32) -> Q.Table<M.Column>:
  split(U32.is_gt(n,1),n,biome,stone,bedrock,top,filler)

@unsafe
def split(many: Bool,+n: U32,+biome: U32,+stone: U32,+bedrock: U32,+top: U32,+filler: U32) -> Q.Table<M.Column>:
  match many n:
    case _ 0: Q.Empty{}
    case False{} _: Q.Leaf{M.Column{80,biome,0.0,0.0,0.0,0.0,0.0,[M.Run{0,1,bedrock},M.Run{1,142,stone},M.Run{142,143,filler},M.Run{143,144,top},M.Run{144,384,0}],5,True{}}}
    case True{} _:
      +half = U32.div(n,2)
      a b = columns(half,biome,stone,bedrock,top,filler) columns(U32.sub(n,half),biome,stone,bedrock,top,filler)
      Q.Branch{n,a,b}

@unsafe
def case_ready(result: Result<&1,&1,U32,Q.Table<U32>>,+i: U32,+n: U32,+at: U32,doc: R.Document,+model: G.Model,+repair: Merge.Repair,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid region fixture")
    case Done{+v}:
      +core : S.Tile = S.Tile{Q.get(U32,v,4,0),Q.get(U32,v,5,0),Q.get(U32,v,6,0),Q.get(U32,v,7,0),123456789,2147483648}
      +source : M.Grid = M.Grid{S.Tile{Q.get(U32,v,0,0),Q.get(U32,v,1,0),Q.get(U32,v,2,0),Q.get(U32,v,3,0),123456789,2147483648},4294967232,384,
        columns(U32.mul(Q.get(U32,v,2,0),Q.get(U32,v,3,0)),Q.get(U32,v,8,0),Q.get(U32,v,9,1),Q.get(U32,v,10,1),Q.get(U32,v,11,9),Q.get(U32,v,12,10)),None{}}
      do IO<Unit>:
        +result : Result<&1,&1,U32,Region.Computed> = Region.execute(core,source,model,repair)
        _ : Unit <- saved(result,catalog,output ++ "." ++ U32.show(i))
        cases(n,at,U32.inc(i),doc,model,repair,catalog,output)

@unsafe
def saved(result: Result<&1,&1,U32,Region.Computed>,catalog: CC.Catalog,+output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"region vegetation failed")
    case Done{Region.Computed{+grid,anchors,candidates,placed}}:
      do IO<Unit>:
        _ : Unit <- Binary.unwrapped(B.done(Output.write(grid,B.int(B.int(B.int(B.new(),anchors),candidates),placed))),output)
        region_mca(grid,catalog,output ++ ".mca")

def region_axis(+value: U32) -> U32:
  U32.or(U32.shrn(value,9n),N.word(U32.is_ge(value,2147483648),4286578688,0))

@unsafe
def region_mca(+grid: M.Grid,catalog: CC.Catalog,output: String) -> IO(Unit):
  match grid:
    case M.Grid{S.Tile{x,z,_,_,low,high},_,_,_,_}:
      do IO<Unit>:
        result : Result<&1,&1,U32,U32 & U32> <- RegionFile.run_partial(RegionFile.Input{RegionFile.Request{region_axis(x),region_axis(z),low,high,5023,1700000000},output,True{}},grid,catalog)
        region_saved(result)

def region_saved(result: Result<&1,&1,U32,U32 & U32>) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"composed MCA failed")
    case Done{_}: IO.pure(Unit,Unit{})

@unsafe
def cases(+n: U32,+at: U32,+i: U32,doc: R.Document,+model: G.Model,+repair: Merge.Repair,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match n:
    case 0: IO.print("PASS: composed vegetation cores")
    case _: R.value(IO(Unit),doc,at,doc => value => R.get(IO(Unit),doc,U32.inc(at),doc => span => A.array(IO(Unit),Some{value},doc,2,doc => result => case_ready(result,i,U32.sub(n,1),U32.add(at,span),doc,model,repair,catalog,output))))

@unsafe
def ready(result: Result<&1,&1,U32,CC.Catalog>,doc: R.Document,+model: G.Model,+repair: Merge.Repair,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid chunk catalog")
    case Done{catalog}: V.root(IO(Unit),doc,doc => root => V.field(IO(Unit),doc,root,"vegetation_region_cases",doc => found => found_cases(found,doc,model,repair,catalog,output)))

@unsafe
def found_cases(found: Maybe<&2,R.Value>,doc: R.Document,model: G.Model,repair: Merge.Repair,catalog: CC.Catalog,output: String) -> IO(Unit):
  match found:
    case Some{R.Value{6,n,_,at}}: cases(n,U32.add(at,3),0,doc,model,repair,catalog,output)
    case _: IO.die(Unit,2,"missing vegetation region cases")

@unsafe
def repairs(halves: Result<&1,&1,U32,Q.Table<U32>>,floors: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,+model: G.Model,output: String) -> IO(Unit):
  match halves floors model:
    case Done{halves} Done{floors} G.Model{P.Model{_,_,P.Policy{_,+flags,_,_,_,_,_}},_,_}: RC.prepare(IO(Unit),doc,doc => result => ready(result,doc,model,Merge.Repair{flags,halves,floors},output))
    case _ _ _: IO.die(Unit,2,"invalid repair tables")

@unsafe
def prepared(result: Result<&1,&1,U32,G.Model>,doc: R.Document,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid vegetation profile")
    case Done{+model}: V.root(IO(Unit),doc,doc => +root => V.field(IO(Unit),doc,root,"plant_halves",doc => found => A.array(IO(Unit),found,doc,2,doc => halves => V.field(IO(Unit),doc,root,"plant_floor_masks",doc => found => A.array(IO(Unit),found,doc,0,doc => floors => repairs(halves,floors,doc,model,output))))))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid registry transport")
    case Done{doc}: RG.prepare(IO(Unit),doc,doc => result => prepared(result,doc,output))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: vegetation-region profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
