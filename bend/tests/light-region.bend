import Base
import ../registry.bend as P
import ../registry_values.bend as V
import ../registry_cave_features.bend as F
import ../registry_lighting.bend as RL
import ../registry_chunk_catalog.bend as RC
import ../chunk_catalog.bend as CC
import ../light_region.bend as Region
import ../light_volume.bend as L
import ../material_columns_data.bend as MD
import ../terrain_surface.bend as S
import ../density.bend as Q
import ../generated_region.bend as MCA
import ../noise.bend as N
import ./light-volume.bend as Fixture

def word(values: Q.Table<U32>,i: U32) -> U32:
  Q.get(U32,values,i,0)

@unsafe
def columns(+n: U32,+base: U32,+height: U32,+plane: U32,+blocks: Q.Table<U32>) -> Q.Table<MD.Column>:
  match n:
    case 1: Q.Leaf{MD.Column{0,0,0.0,0.0,0.0,0.0,0.0,Fixture.runs(height,base,plane,blocks,[]),height,True{}}}
    case _:
      a b = columns(U32.div(n,2),base,height,plane,blocks) columns(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),height,plane,blocks)
      Q.Branch{n,a,b}

def region_axis(+value: U32) -> U32:
  U32.or(U32.shrn(value,9n),N.word(U32.is_ge(value,2147483648),4286578688,0))

def saved(result: Result<&1,&1,U32,U32 & U32>) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"lighting MCA failed")
    case Done{_}: IO.print("PASS: lit MCA")

@unsafe
def lit(result: Result<&1,&1,U32,Region.Store>,+grid: MD.Grid,catalog: CC.Catalog,output: String) -> IO(Unit):
  match result grid:
    case Fail{code} _: IO.die(Unit,code,"lighting failed")
    case Done{store} MD.Grid{S.Tile{x,z,_,_,low,high},_,_,_,_}:
      do IO<Unit>:
        result : Result<&1,&1,U32,U32 & U32> <- MCA.run_partial_context(MCA.Input{MCA.Request{region_axis(x),region_axis(z),low,high,5023,1700000000},output,True{}},grid,MCA.Context{catalog,None{},Some{store}})
        saved(result)

def execute(+grid: MD.Grid,catalog: CC.Catalog,model: L.Model,output: String) -> IO(Unit):
  match grid:
    case MD.Grid{area,_,_,_,_}:
      do IO<Unit>:
        result : Result<&1,&1,U32,Region.Store> <- Region.run(area,grid,model)
        lit(result,grid,catalog,output)

@unsafe
def parsed(params: Result<&1,&1,U32,Q.Table<U32>>,blocks: Result<&1,&1,U32,Q.Table<U32>>,catalog: CC.Catalog,model: L.Model,output: String) -> IO(Unit):
  match params blocks:
    case Done{+p} Done{blocks}: execute(MD.Grid{S.Tile{word(p,3),word(p,4),word(p,0),word(p,2),word(p,5),word(p,6)},word(p,7),word(p,1),
      columns(U32.mul(word(p,0),word(p,2)),0,word(p,1),U32.mul(word(p,0),word(p,2)),blocks),None{}},catalog,model,output)
    case _ _: IO.die(Unit,2,"invalid region fixture")

def catalog_ready(result: Result<&1,&1,U32,CC.Catalog>,doc: P.Document,model: L.Model,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid catalog")
    case Done{catalog}: V.root(IO(Unit),doc,doc => +root =>
      V.field(IO(Unit),doc,root,"params",doc => found => F.array(IO(Unit),found,doc,2,doc => params =>
      V.field(IO(Unit),doc,root,"blocks",doc => found => F.array(IO(Unit),found,doc,0,doc => blocks =>
        parsed(params,blocks,catalog,model,output))))))

def ready(result: Result<&1,&1,U32,L.Model>,doc: P.Document,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid optics")
    case Done{model}: RC.prepare(IO(Unit),doc,doc => result => catalog_ready(result,doc,model,output))

def loaded(result: Result<&1,&1,U32,P.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid transport")
    case Done{doc}: RL.prepare(IO(Unit),doc,doc => result => ready(result,doc,output))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,P.Document> <- P.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: light-region profile.rbp output.mca")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
