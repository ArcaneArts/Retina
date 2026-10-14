import Base
import ../registry.bend as R
import ../registry_values.bend as V
import ../registry_cave_features.bend as A
import ../registry_placement.bend as RP
import ../registry_chunk_catalog.bend as RC
import ../chunk_catalog.bend as CC
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../placement_replay.bend as Replay
import ../placement_merge.bend as Merge
import ../material_columns_data.bend as M
import ../density.bend as Q
import ../binary.bend as B
import ../word64.bend as W
import ./binary-fixtures.bend as Binary
import ./placement-merge.bend as Output
import ./surface-feature-world.bend as World
import ./placement-world-fixtures.bend as Old
import ./tree-mca.bend as Mca
import ../tree.bend as Tree
import ../tree_data.bend as T
import ../surface_plants.bend as Plants

@unsafe
def commands(+xs: Q.Table<U32>,+i: U32,+n: U32,acc: List<&2,E.Command>) -> List<&2,E.Command>:
  match n:
    case 0: List.reverse(&2,E.Command,acc)
    case _: commands(xs,U32.add(i,6),U32.sub(n,6),E.Command{P.Position{Q.get(U32,xs,i,0),Q.get(U32,xs,U32.inc(i),0),Q.get(U32,xs,U32.add(i,2),0)},Q.get(U32,xs,U32.add(i,3),0),Q.get(U32,xs,U32.add(i,4),0),Q.get(U32,xs,U32.add(i,5),0)} <> acc)

@unsafe
def anchors(+n: U32,+at: U32,doc: R.Document,acc: List<&2,Replay.Anchor>,next: R.Document -> Q.Table<Replay.Anchor> -> IO(Unit)) -> IO(Unit):
  match n:
    case 0: next(doc,Q.prepared_table(Replay.Anchor,List.reverse(&2,Replay.Anchor,acc)))
    case _: R.value(IO(Unit),doc,at,doc => value => R.get(IO(Unit),doc,U32.inc(at),doc => span => A.array(IO(Unit),Some{value},doc,2,doc => result => anchor_result(result,U32.sub(n,1),U32.add(at,span),doc,acc,next))))

@unsafe
def anchor_result(result: Result<&1,&1,U32,Q.Table<U32>>,+n: U32,+at: U32,doc: R.Document,acc: List<&2,Replay.Anchor>,next: R.Document -> Q.Table<Replay.Anchor> -> IO(Unit)) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid anchor fixture")
    case Done{+xs}: anchor_shape(Bool.and(U32.is_ge(Q.table_size(U32,xs),3),U32.is_zero(U32.mod(U32.sub(Q.table_size(U32,xs),3),6))),xs,n,at,doc,acc,next)

@unsafe
def anchor_shape(ok: Bool,+xs: Q.Table<U32>,n: U32,at: U32,doc: R.Document,acc: List<&2,Replay.Anchor>,next: R.Document -> Q.Table<Replay.Anchor> -> IO(Unit)) -> IO(Unit):
  match ok:
    case False{}: IO.die(Unit,2,"invalid command shape")
    case True{}: anchors(n,at,doc,Replay.Anchor{P.Position{Q.get(U32,xs,0,0),Q.get(U32,xs,1,0),Q.get(U32,xs,2,0)},commands(xs,3,U32.sub(Q.table_size(U32,xs),3),[])} <> acc,next)

@unsafe
def source(policy: P.Policy) -> M.Grid:
  Output.source_of(World.world(policy,0))

@unsafe
def case_ready(+rows: Q.Table<Replay.Anchor>,+i: U32,+n: U32,+at: U32,doc: R.Document,+policy: P.Policy,+repair: Merge.Repair,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  do IO<Unit>:
    result : Result<&1,&1,U32,M.Grid> = Replay.apply!(source(policy),policy,rows,repair)
    _ : Unit <- case_output(result,i,catalog,output ++ "." ++ U32.show(i))
    cases(n,at,U32.inc(i),doc,policy,repair,catalog,output)

@unsafe
def case_output(result: Result<&1,&1,U32,M.Grid>,+i: U32,catalog: CC.Catalog,+output: String) -> IO(Unit):
  match result:
    case Fail{code}: Binary.unwrapped(B.done(B.int(B.new(),code)),output)
    case Done{+grid}:
      do IO<Unit>:
        _ : Unit <- Binary.unwrapped(B.done(Output.write(grid,B.int(B.new(),0))),output)
        first_mca(U32.is_zero(i),grid,catalog,output ++ ".mca")

@unsafe
def first_mca(first: Bool,grid: M.Grid,catalog: CC.Catalog,output: String) -> IO(Unit):
  match first:
    case False{}: IO.pure(Unit,Unit{})
    case True{}: Mca.save(Old.origin(0),grid,catalog,output)

@unsafe
def cases(+n: U32,+at: U32,+i: U32,doc: R.Document,+policy: P.Policy,+repair: Merge.Repair,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match n:
    case 0: IO.print("PASS: spatially ordered anchor command replay")
    case _: R.value(IO(Unit),doc,at,doc => value => R.get(IO(Unit),doc,U32.inc(at),doc => span => case_shape(value,U32.sub(n,1),U32.add(at,span),i,doc,policy,repair,catalog,output)))

@unsafe
def case_shape(value: R.Value,+n: U32,+at: U32,+i: U32,doc: R.Document,+policy: P.Policy,+repair: Merge.Repair,+catalog: CC.Catalog,+output: String) -> IO(Unit):
  match value:
    case R.Value{6,count,_,start}: anchors(count,U32.add(start,3),doc,[],doc => rows => case_ready(rows,i,n,at,doc,policy,repair,catalog,output))
    case _: IO.die(Unit,2,"invalid anchor case")

@unsafe
def ready(result: Result<&1,&1,U32,CC.Catalog>,doc: R.Document,+policy: P.Policy,+repair: Merge.Repair,+output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid chunk catalog")
    case Done{catalog}:
      do IO<Unit>:
        _ : Unit <- probe(policy,output ++ ".journal")
        V.root(IO(Unit),doc,doc => root => V.field(IO(Unit),doc,root,"anchor_replay_cases",doc => found => found_cases(found,doc,policy,repair,catalog,output)))

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
    case _: IO.die(Unit,2,"usage: anchor-replay profile.rbp output-prefix")

@unsafe
def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)

@unsafe
def first_flag(+flags: Q.Table<U32>,+i: U32) -> U32:
  first_found(U32.is_ne(U32.and(Q.get(U32,flags,i,0),32),0),flags,i)

@unsafe
def first_found(found: Bool,flags: Q.Table<U32>,+i: U32) -> U32:
  match found:
    case True{}: i
    case False{}: first_flag(flags,U32.inc(i))

@unsafe
def probe(+policy: P.Policy,output: String) -> IO(Unit):
  match policy:
    case P.Policy{_,flags,_,_,_,_,_}: probe_at(Old.origin(0),first_flag(flags,0),World.world(policy,0),output)

@unsafe
def probe_at(origin: W.U64,+jungle: U32,+world: E.World,output: String) -> IO(Unit):
  match origin:
    case W.U64{+x,+z}:
      +a : P.Position = P.Position{U32.add(x,8),2,U32.add(z,8)}
      +b : P.Position = P.Position{U32.add(x,9),2,U32.add(z,8)}
      +w = E.put_pair(E.put(E.put_role(world,a,1,1,0),a,1),b,1,1)
      probe_plants(x,z,Tree.replay([
        T.Write{P.Position{U32.add(x,10),2,U32.add(z,8)},1,1,0},
        T.Write{P.Position{U32.add(x,11),2,U32.add(z,8)},1,2,0},
        T.Write{P.Position{U32.add(x,15),2,U32.add(z,8)},1,0,0},
        T.Write{P.Position{U32.add(x,16),2,U32.add(z,8)},jungle,1,0},
        T.Write{P.Position{U32.add(x,16),3,U32.add(z,8)},jungle,1,0},
        T.Write{P.Position{U32.add(x,17),2,U32.add(z,8)},1,3,0},
        T.Write{P.Position{U32.add(x,17),3,U32.add(z,8)},1,4,0}],0,0,w),output)

@unsafe
def probe_plants(+x: U32,+z: U32,+world: E.World,output: String) -> IO(Unit):
  +w = Plants.write_single(False{},world,P.Position{U32.add(x,12),2,U32.add(z,8)},1)
  +w = Plants.write_pair(False{},w,P.Position{U32.add(x,13),2,U32.add(z,8)},P.Position{U32.add(x,13),3,U32.add(z,8)},1,1)
  +w = Plants.write_pair(True{},w,P.Position{U32.add(x,14),2,U32.add(z,8)},P.Position{U32.add(x,14),3,U32.add(z,8)},1,1)
  +w = E.put(E.put(w,P.Position{U32.sub(x,1),2,z},1),P.Position{x,2,z},4294967295)
  +rows = E.journal(w)
  Binary.unwrapped(B.done(probe_words(rows,B.int(B.new(),U32.from_nat(List.length(&2,E.Command,rows))))),output)

@unsafe
def probe_words(xs: List<&2,E.Command>,writer: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: writer
    case E.Command{P.Position{x,y,z},m,r,e} <> rest: probe_words(rest,B.int(B.int(B.int(B.int(B.int(B.int(writer,x),y),z),m),r),e))
