import Base
import ../registry.bend as R
import ../registry_values.bend as V
import ../registry_cave_features.bend as A
import ../registry_snow.bend as RS
import ../snow.bend as Snow
import ../registry_chunk_catalog.bend as RC
import ../chunk_catalog.bend as CC
import ../material_columns_data.bend as M
import ../material_columns.bend as MC
import ../terrain_surface.bend as S
import ../density.bend as Q
import ../binary.bend as B
import ../lake_terrain.bend as Lake
import ../lake_data.bend as LD
import ../lake_lattice.bend as LL
import ../lake_candidates.bend as LC
import ../precise.bend as D
import ./binary-fixtures.bend as Binary
import ./placement-merge.bend as Output
import ../generated_region.bend as RegionFile
import ../noise.bend as N

@unsafe
def word(+v: Q.Table<U32>,i: U32) -> U32:
  Q.get(U32,v,i,0)

@unsafe
def source_column(+v: Q.Table<U32>,+model: Snow.Model,+i: U32) -> M.Column:
  match model:
    case Snow.Model{sea,water,_,_,_,+palette,_,_,+biomes}:
      +top = word(v,6)
      +mode = word(v,7)
      +biome = N.word(U32.is_eq(mode,4),U32.mod(U32.add(word(v,5),i),Q.table_size(Snow.Biome,biomes)),word(v,5))
      +m = N.word(U32.is_eq(mode,1),word(v,9),U32.mod(U32.add(word(v,8),i),palette))
      +runs = Lake.replace(Lake.replace([M.Run{0,word(v,3),0}],0,U32.inc(top),word(v,9),[]),top,U32.inc(top),m,[])
      source_water(U32.is_eq(mode,1),v,i,biome,runs,U32.sub(sea,word(v,2)),water,top)

@unsafe
def source_water(wet: Bool,+v: Q.Table<U32>,+i: U32,biome: U32,runs: List<&2,M.Run>,+sea: U32,water: U32,+top: U32) -> M.Column:
  match wet:
    case True{}: source_cover(v,i,biome,Lake.replace(runs,U32.inc(top),sea,water,[]),sea)
    case False{}: source_cover(v,i,biome,runs,U32.inc(top))

@unsafe
def source_cover(+v: Q.Table<U32>,+i: U32,biome: U32,runs: List<&2,M.Run>,+level: U32) -> M.Column:
  source_finished(word(v,4),biome,Bool.not(Bool.and(U32.is_eq(word(v,7),2),U32.is_zero(U32.mod(i,2)))),
    covered(U32.is_zero(U32.mod(i,8)),runs,level,word(v,10),word(v,3)))

@unsafe
def covered(ok: Bool,runs: List<&2,M.Run>,+level: U32,cover: U32,+height: U32) -> List<&2,M.Run>:
  match ok:
    case False{}: runs
    case True{}: Lake.replace(runs,U32.min(level,height),U32.min(U32.inc(level),height),cover,[])

@unsafe
def source_finished(terrain: U32,biome: U32,valid: Bool,+runs: List<&2,M.Run>) -> M.Column:
  M.Column{terrain,biome,1.0,2.0,3.0,4.0,5.0,runs,U32.from_nat(List.length(&2,M.Run,runs)),valid}

@unsafe
def columns(+n: U32,+i: U32,+v: Q.Table<U32>,+model: Snow.Model) -> Q.Table<M.Column>:
  split(U32.is_gt(n,1),n,i,v,model)

@unsafe
def split(many: Bool,+n: U32,+i: U32,+v: Q.Table<U32>,+model: Snow.Model) -> Q.Table<M.Column>:
  match many:
    case False{}: Q.Leaf{source_column(v,model,i)}
    case True{}:
      +half = U32.div(n,2)
      a b = columns(half,i,v,model) columns(U32.sub(n,half),U32.add(i,half),v,model)
      Q.Branch{n,a,b}

@unsafe
def case_ready(result: Result<&1,&1,U32,Q.Table<U32>>,+i: U32,+n: U32,+at: U32,doc: R.Document,+model: Snow.Model,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid snow fixture")
    case Done{+v}:
      +grid : M.Grid = M.Grid{S.Tile{word(v,0),word(v,1),16,16,123456789,2147483648},word(v,2),word(v,3),columns(256,0,v,model),None{}}
      do IO<Unit>:
        +result : Snow.Computed = Snow.execute(grid,model,S.Config{word(v,2),word(v,3),model_sea(model),4,8,False{}},lake_input(v))
        _ : Unit <- saved(result,catalog,output ++ "." ++ U32.show(i),word(v,11))
        cases(n,at,U32.inc(i),doc,model,catalog,output)

def model_sea(model: Snow.Model) -> U32:
  match model:
    case Snow.Model{sea,_,_,_,_,_,_,_,_}: sea

# A typed resident basin supplies actual Lake.shape() context; no test-only
# boolean skips the production exclusion. Its center/radius cover this tile.
@unsafe
def lake_input(+v: Q.Table<U32>) -> Lake.Input:
  lake_mode(U32.is_eq(word(v,7),6),v)

@unsafe
def lake_mode(ok: Bool,+v: Q.Table<U32>) -> Lake.Input:
  match ok:
    case False{}: Lake.empty()
    case True{}:
      +tile : S.Tile = S.Tile{word(v,0),word(v,1),16,16,123456789,2147483648}
      +layout : LD.Layout = LD.Layout{LC.cell(word(v,0)),LC.cell(word(v,1)),1,1,123456789,2147483648}
      +basin : LD.Basin = LD.Basin{Q.world(U32.add(word(v,0),8),0,U32.add(word(v,1),8)),128.0,word(v,4),Q.Empty{},word(v,5),True{},False{},word(v,9),0}
      Lake.Input{Some{LD.Cache{LL.packed(282,0,layout,tile,Q.Leaf{basin},zeroes(256))}},0}

@unsafe
def zeroes(+n: U32) -> Q.Table<U32>:
  match n:
    case 1: Q.Leaf{0}
    case _: Q.Branch{n,zeroes(U32.div(n,2)),zeroes(U32.sub(n,U32.div(n,2)))}

@unsafe
def saved(result: Snow.Computed,catalog: CC.Catalog,+output: String,write: U32) -> IO(Unit):
  match result:
    case Snow.Computed{+grid}:
      do IO<Unit>:
        _ : Unit <- Binary.unwrapped(B.done(Output.write(grid,B.new())),output)
        save_mca(write,grid,catalog,output ++ ".mca")

@unsafe
def save_mca(write: U32,grid: M.Grid,catalog: CC.Catalog,output: String) -> IO(Unit):
  match write:
    case 0: IO.pure(Unit,Unit{})
    case _: region_mca(grid,catalog,output)

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
def cases(+n: U32,+at: U32,+i: U32,doc: R.Document,+model: Snow.Model,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match n:
    case 0: IO.print("PASS: snow columns")
    case _: R.value(IO(Unit),doc,at,doc => value => R.get(IO(Unit),doc,U32.inc(at),doc => span => A.array(IO(Unit),Some{value},doc,2,doc => result => case_ready(result,i,U32.sub(n,1),U32.add(at,span),doc,model,catalog,output))))

@unsafe
def ready(result: Result<&1,&1,U32,CC.Catalog>,doc: R.Document,+model: Snow.Model,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid catalog")
    case Done{catalog}: V.root(IO(Unit),doc,doc => root => V.field(IO(Unit),doc,root,"snow_test_specs",doc => found => found_cases(found,doc,model,catalog,output)))

@unsafe
def found_cases(found: Maybe<&2,R.Value>,doc: R.Document,model: Snow.Model,catalog: CC.Catalog,output: String) -> IO(Unit):
  match found:
    case Some{R.Value{6,n,_,at}}: cases(n,U32.add(at,3),0,doc,model,catalog,output)
    case _: IO.die(Unit,2,"missing snow cases")

@unsafe
def prepared(result: Result<&1,&1,U32,Snow.Model>,doc: R.Document,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid snow profile")
    case Done{model}: RC.prepare(IO(Unit),doc,doc => result => ready(result,doc,model,output))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid registry transport")
    case Done{doc}: RS.prepare(IO(Unit),doc,doc => result => prepared(result,doc,output))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: snow profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
