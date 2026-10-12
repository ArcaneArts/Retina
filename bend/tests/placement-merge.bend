import Base
import ../registry.bend as R
import ../registry_values.bend as V
import ../registry_cave_features.bend as A
import ../registry_placement.bend as RP
import ../registry_chunk_catalog.bend as RC
import ../chunk_catalog.bend as CC
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../placement_merge.bend as Merge
import ../material_columns_data.bend as M
import ../terrain_surface.bend as S
import ../density.bend as Q
import ../binary.bend as B
import ../word64.bend as W
import ../noise.bend as N
import ../compression.bend as Z
import ../generated_chunk.bend as GC
import ../generated_region.bend as GR
import ../region.bend as Region
import ../surface_feature.bend as F
import ../block_provider.bend as BP
import ../int_provider.bend as I
import ./binary-fixtures.bend as Output
import ./placement-world-fixtures.bend as Old
import ./surface-feature-world.bend as Wide

@unsafe
def patch(+xs: Q.Table<U32>,+i: U32,+n: U32,acc: E.Patch) -> E.Patch:
  match n:
    case 0: acc
    case _: patch(xs,U32.add(i,4),U32.sub(n,4),E.insert(acc,P.Position{Q.get(U32,xs,i,0),Q.get(U32,xs,U32.inc(i),0),Q.get(U32,xs,U32.add(i,2),0)},Q.get(U32,xs,U32.add(i,3),0)))

@unsafe
def patches(+n: U32,+at: U32,doc: R.Document,acc: List<&2,E.Patch>,next: R.Document -> Q.Table<E.Patch> -> IO(Unit)) -> IO(Unit):
  match n:
    case 0: next(doc,Q.prepared_table(E.Patch,List.reverse(&2,E.Patch,acc)))
    case _: R.value(IO(Unit),doc,at,doc => value => R.get(IO(Unit),doc,U32.inc(at),doc => span => A.array(IO(Unit),Some{value},doc,2,doc => result => patch_result(result,U32.sub(n,1),U32.add(at,span),doc,acc,next))))

@unsafe
def patch_result(result: Result<&1,&1,U32,Q.Table<U32>>,+n: U32,+at: U32,doc: R.Document,acc: List<&2,E.Patch>,next: R.Document -> Q.Table<E.Patch> -> IO(Unit)) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid merge fixture patch")
    case Done{+xs}: patches(n,at,doc,patch(xs,0,Q.table_size(U32,xs),E.Empty{}) <> acc,next)

@unsafe
def runs_count(xs: List<&2,M.Run>) -> U32:
  U32.from_nat(List.length(&2,M.Run,xs))

@unsafe
def runs(xs: List<&2,M.Run>,writer: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: writer
    case M.Run{a,b,m} <> rest: runs(rest,B.int(B.int(B.int(writer,a),b),m))

@unsafe
def columns(+i: U32,+n: U32,+xs: Q.Table<M.Column>,writer: B.Writer) -> B.Writer:
  match n:
    case 0: writer
    case _: one_column(Q.get(M.Column,xs,i,M.Column{0,0,0.0,0.0,0.0,0.0,0.0,[],0,False{}}),i,n,xs,writer)

@unsafe
def one_column(c: M.Column,+i: U32,+n: U32,+xs: Q.Table<M.Column>,writer: B.Writer) -> B.Writer:
  match c:
    case M.Column{h,b,_,_,_,_,_,+rs,p,v}: columns(U32.inc(i),U32.sub(n,1),xs,runs(rs,B.int(B.int(B.int(B.int(B.int(writer,h),b),p),W.flag(v)),runs_count(rs))))

@unsafe
def write(grid: M.Grid,writer: B.Writer) -> B.Writer:
  match grid:
    case M.Grid{S.Tile{x,z,w,d,lo,hi},minimum,height,+cols,_}: columns(0,Q.table_size(M.Column,cols),cols,
      B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(writer,x),z),w),d),lo),hi),minimum),height))

@unsafe
def optional(v: Maybe<&2,U32>) -> U32:
  match v:
    case None{}: 4294967295
    case Some{x}: x

@unsafe
def biome_probes(+n: U32,+source: E.World,+merged: E.World,+origin: W.U64,writer: B.Writer) -> B.Writer:
  match n origin:
    case 0 _: writer
    case _ W.U64{x,z}:
      +i = U32.sub(n,1)
      +at : P.Position = P.Position{U32.add(x,U32.mod(i,4)),U32.add(4294967232,U32.mul(U32.div(i,4),16)),U32.add(z,U32.mod(U32.div(i,2),4))}
      biome_probes(i,source,merged,origin,B.int(B.int(writer,optional(E.biome(source,at))),optional(E.biome(merged,at))))

@unsafe
def crop_result(result: Result<&1,&1,U32,M.Grid>,writer: B.Writer) -> B.Writer:
  match result:
    case Fail{code}: B.fail(writer,code)
    case Done{grid}: write(grid,writer)

@unsafe
def cropped(+source: M.Grid,+merged: M.Grid,+policy: P.Policy,+context: U32,output: String) -> IO(Unit):
  cropped_at(Old.origin(context),source,merged,policy,output)

@unsafe
def cropped_at(+origin: W.U64,+source: M.Grid,+merged: M.Grid,+policy: P.Policy,output: String) -> IO(Unit):
  match origin:
    case W.U64{+x,+z}:
      do IO<Unit>:
        _ : Unit <- Output.expected(rejected(Merge.crop(merged,U32.sub(x,1),z,2,2)))
        _ : Unit <- Output.expected(rejected(Merge.crop(merged,x,z,0,2)))
        _ : Unit <- Output.expected(rejected(Merge.crop(merged,x,z,5,1)))
        _ : Unit <- Output.expected(rejected(Merge.crop(merged,U32.add(x,3),z,2,2)))
        Output.unwrapped(B.done(crop_result(Merge.crop(merged,U32.inc(x),U32.inc(z),2,2),biome_probes(32,E.World{source,policy,E.Empty{}},E.World{merged,policy,E.Empty{}},origin,write(merged,B.new())))),output)

@unsafe
def rejected(r: Result<&1,&1,U32,M.Grid>) -> Bool:
  match r:
    case Fail{766}: True{}
    case _: False{}

@unsafe
def wide_source(+policy: P.Policy) -> M.Grid:
  source_of(Wide.world(policy,0))

@unsafe
def source_of(world: E.World) -> M.Grid:
  match world:
    case E.World{source,_,_}: source

# Exercise an actual feature writer, then merge its accepted overlay together
# with independent anchor patches before the existing NBT/zlib/MCA path.
@unsafe
def writer_patch(+source: M.Grid,+policy: P.Policy) -> E.Patch:
  writer_at(Old.origin(0),source,policy)

@unsafe
def writer_at(origin: W.U64,+source: M.Grid,+policy: P.Policy) -> E.Patch:
  match origin:
    case W.U64{x,z}: writer_done(F.place(F.Column{[F.Layer{I.Constant{4},BP.State{1}}],P.Position{0,1,0},P.Always{},False{}},W.U64{0,123},F.Model{Q.Empty{},Q.Empty{}},E.World{source,policy,E.Empty{}},P.Position{U32.add(x,8),0,U32.add(z,8)}))

@unsafe
def writer_done(result: F.Placement) -> E.Patch:
  match result:
    case F.Placement{_,E.World{_,_,patch},_}: patch

@unsafe
def wide_merge(+patches: Q.Table<E.Patch>,+repair: Merge.Repair,+policy: P.Policy) -> M.Grid:
  +source = wide_source(policy)
  Merge.apply(source,Q.Branch{U32.inc(Q.table_size(E.Patch,patches)),patches,Q.Leaf{writer_patch(source,policy)}},repair)

@unsafe
def mca(patches: Q.Table<E.Patch>,repair: Merge.Repair,policy: P.Policy,catalog: CC.Catalog,output: String) -> IO(Unit):
  do IO<Unit>:
    merged : M.Grid = wide_merge!(patches,repair,policy)
    mca_at(Old.origin(0),merged,catalog,output)

@unsafe
def mca_at(origin: W.U64,grid: M.Grid,catalog: CC.Catalog,output: String) -> IO(Unit):
  match origin:
    case W.U64{+x,+z}: encoded(GC.encode(grid,catalog,GC.Request{U32.div(x,16),4293092296,123456789,2147483648,5023}),
      U32.add(U32.div(U32.mod(x,512),16),U32.mul(U32.div(U32.mod(z,512),16),32)),output)

@unsafe
def encoded(result: Result<&1,&1,U32,Z.Bytes>,slot: U32,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"merged chunk encoding failed")
    case Done{bytes}:
      do IO<Unit>:
        result : Result<&1,&1,U32,U32 & U32> <- GR.save(Region.plan([Region.Record{slot,1700000000,Z.compress(bytes)}]),output)
        saved(result)

@unsafe
def saved(result: Result<&1,&1,U32,U32 & U32>) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"merged MCA writing failed")
    case Done{_}: IO.pure(Unit,Unit{})

@unsafe
def case_ready(+patches: Q.Table<E.Patch>,+context: U32,+n: U32,+at: U32,doc: R.Document,+policy: P.Policy,+repair: Merge.Repair,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  +source = Old.source(policy,context)
  do IO<Unit>:
    merged : M.Grid = Merge.apply!(source,patches,repair)
    _ : Unit <- cropped(source,merged,policy,context,output ++ "." ++ U32.show(context))
    _ : Unit <- first_mca(U32.is_zero(context),patches,repair,policy,catalog,output)
    cases(n,at,U32.inc(context),doc,policy,repair,catalog,output)

@unsafe
def first_mca(first: Bool,patches: Q.Table<E.Patch>,repair: Merge.Repair,policy: P.Policy,catalog: CC.Catalog,output: String) -> IO(Unit):
  match first:
    case False{}: IO.pure(Unit,Unit{})
    case True{}: mca(patches,repair,policy,catalog,output ++ ".mca")

@unsafe
def cases(+n: U32,+at: U32,+context: U32,doc: R.Document,+policy: P.Policy,+repair: Merge.Repair,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match n:
    case 0: IO.print("PASS: ordered overlay merge, plant repair, crop and MCA")
    case _: R.value(IO(Unit),doc,at,doc => value => R.get(IO(Unit),doc,U32.inc(at),doc => span => case_shape(value,U32.sub(n,1),U32.add(at,span),context,doc,policy,repair,catalog,output)))

@unsafe
def case_shape(value: R.Value,+n: U32,+at: U32,+context: U32,doc: R.Document,+policy: P.Policy,+repair: Merge.Repair,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match value:
    case R.Value{6,count,_,start}: patches(count,U32.add(start,3),doc,[],doc => patches => case_ready(patches,context,n,at,doc,policy,repair,catalog,output))
    case _: IO.die(Unit,2,"invalid merge case")

@unsafe
def ready(result: Result<&1,&1,U32,CC.Catalog>,doc: R.Document,+policy: P.Policy,+repair: Merge.Repair,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid chunk catalog")
    case Done{catalog}: V.root(IO(Unit),doc,doc => root => V.field(IO(Unit),doc,root,"placement_merge_cases",doc => found => found_cases(found,doc,policy,repair,catalog,output)))

@unsafe
def found_cases(found: Maybe<&2,R.Value>,doc: R.Document,policy: P.Policy,repair: Merge.Repair,catalog: CC.Catalog,output: String) -> IO(Unit):
  match found:
    case Some{R.Value{6,n,_,at}}: cases(n,U32.add(at,3),0,doc,policy,repair,catalog,output)
    case _: IO.die(Unit,2,"missing merge cases")

@unsafe
def repairs(halves: Result<&1,&1,U32,Q.Table<U32>>,floors: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,+policy: P.Policy,output: String) -> IO(Unit):
  match halves floors policy:
    case Done{halves} Done{floors} P.Policy{_,flags,_,_,_,_,_}: RC.prepare(IO(Unit),doc,doc => result => ready(result,doc,policy,Merge.Repair{flags,halves,floors},output))
    case _ _ _: IO.die(Unit,2,"invalid repair tables")

@unsafe
def policy_ready(result: Result<&1,&1,U32,P.Model>,doc: R.Document,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid placement profile")
    case Done{P.Model{_,_,policy}}: V.root(IO(Unit),doc,doc => +root => V.field(IO(Unit),doc,root,"plant_halves",doc => found => A.array(IO(Unit),found,doc,2,doc => halves => V.field(IO(Unit),doc,root,"plant_floor_masks",doc => found => A.array(IO(Unit),found,doc,0,doc => floors => repairs(halves,floors,doc,policy,output))))))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid profile transport")
    case Done{doc}: RP.prepare(IO(Unit),doc,doc => result => policy_ready(result,doc,output))

@unsafe
def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: placement-merge profile.rbp output-prefix")

@unsafe
def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
