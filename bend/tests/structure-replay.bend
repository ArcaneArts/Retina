import Base
import ../structure_replay.bend as Replay
import ../structure_chunk.bend as SC
import ../structure_template.bend as Template
import ../structure_processors.bend as Processors
import ../structure_geometry.bend as Geometry
import ../region.bend as Region
import ../structure_cache.bend as Cache
import ../structure_plan.bend as Plan
import ../structure_random.bend as Random
import ../structure_registry.bend as M
import ../structure_values.bend as V
import ../structure_metadata.bend as Meta
import ../structure_supports.bend as Supports
import ../structure_nbt.bend as SN
import ../registry.bend as R
import ../registry_values.bend as RV
import ../registry_cave_features.bend as Arrays
import ../registry_chunk_catalog.bend as Catalog
import ../chunk_catalog.bend as CC
import ../chunk.bend as C
import ../generated_chunk.bend as GC
import ../material_columns_data.bend as MD
import ../terrain_surface.bend as Surface
import ../placement_merge.bend as Merge
import ../placement_data.bend as P
import ../density.bend as Q
import ../word64.bend as W
import ../binary.bend as B
import ../compression.bend as Z
import ./structure-plan.bend as Fixture
import ./structure-metadata.bend as Packet
import ./binary-fixtures.bend as Output

@unsafe
def starts(+model: M.Model,+ids: M.Node,+i: U32,n: U32,+context: U32,+h: Plan.Heights) -> List<&2,Meta.Start>:
  match n:
    case 0: []
    case _: start(model,M.int(M.item(ids,i),0),context,h) <> starts(model,ids,U32.inc(i),U32.sub(n,1),context,h)

@unsafe
def start(+model: M.Model,+id: U32,+context: U32,+h: Plan.Heights) -> Meta.Start:
  +d = M.item(Plan.array(model,2n),id)
  start_layout(Plan.begin(model,d,Random.new(W.add(Fixture.seed(context),W.U64{0,id})),Fixture.coordinate(context),U32.sub(0,Fixture.coordinate(context)),Fixture.height_field(model,d,h),4294967232,384),id,context,Fixture.height_field(model,d,h))

@unsafe
def start_layout(layout: Plan.Layout,+id: U32,+context: U32,h: Maybe<&2,Plan.Heights>) -> Meta.Start:
  match layout:
    case Plan.Layout{_,pieces,_}: Meta.Start{id,Fixture.coordinate(context),U32.sub(0,Fixture.coordinate(context)),W.add(Fixture.seed(context),W.U64{0,id}),pieces,h}

@unsafe
def field(+context: U32) -> Plan.Heights:
  Plan.Heights{U32.sub(U32.mul(Fixture.coordinate(context),16),256),U32.sub(U32.mul(U32.sub(0,Fixture.coordinate(context)),16),256),512,Q.prepared_table(U32,Fixture.heights(context,0,262144))}

@unsafe
def column(+h: U32,+ground: M.Node) -> MD.Column:
  MD.Column{h,0,0.0,0.0,0.0,0.0,0.0,[MD.Run{0,U32.add(h,60),M.int(M.item(ground,0),0)},MD.Run{U32.add(h,60),U32.add(h,63),M.int(M.item(ground,1),0)},MD.Run{U32.add(h,63),U32.add(h,64),M.int(M.item(ground,2),0)},MD.Run{U32.add(h,64),384,0}],4,True{}}

@unsafe
def columns(+i: U32,n: U32,+x: U32,+z: U32,+h: Plan.Heights,+ground: M.Node) -> List<&2,MD.Column>:
  match n:
    case 0: []
    case _: column(Plan.height(h,U32.add(x,U32.mod(i,32)),U32.add(z,U32.div(i,32))),ground) <> columns(U32.inc(i),U32.sub(n,1),x,z,h,ground)

@unsafe
def source(+x: U32,+z: U32,+h: Plan.Heights,+ground: M.Node) -> MD.Grid:
  MD.Grid{Surface.Tile{U32.sub(U32.mul(x,16),16),U32.sub(U32.mul(z,16),16),32,32,123,0},4294967232,384,Q.prepared_table(MD.Column,columns(0,1024,U32.sub(U32.mul(x,16),16),U32.sub(U32.mul(z,16),16),h,ground)),None{}}

def region_parts(xs: List<Z.Bytes>,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case Z.Bytes{a,n} <> rest: region_parts(rest,Packet.copied(a,0,n,w))

def region_plan(result: Result<&1,&1,U32,Region.Plan>,w: B.Writer) -> B.Writer:
  match result:
    case Fail{code}: B.fail(w,code)
    case Done{Region.Plan{parts,sectors,_}}: region_parts(parts,B.int(w,U32.mul(sectors,4096)))

def region(result: Result<&1,&1,U32,Z.Bytes>,+x: U32,+z: U32,w: B.Writer) -> B.Writer:
  match result:
    case Fail{code}: B.fail(w,code)
    case Done{bytes}: region_plan(Region.plan([Region.Record{U32.add(U32.and(x,31),U32.mul(U32.and(z,31),32)),1700000000,bytes}]),w)

@unsafe
def encode(result: Result<&1,&1,U32,Replay.Chunk>,doc: R.Document,+catalog: CC.Catalog,+x: U32,+z: U32,w: B.Writer,next: R.Document -> B.Writer -> IO(Unit)) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"replay failed")
    case Done{+chunk}:
      chunk_encoded(chunk,doc,catalog,x,z,w,next)

@unsafe
def chunk_encoded(+chunk: Replay.Chunk,doc: R.Document,catalog: CC.Catalog,+x: U32,+z: U32,w: B.Writer,next: R.Document -> B.Writer -> IO(Unit)) -> IO(Unit):
  match chunk:
    case Replay.Chunk{_,_,pieces,valid}:
      SC.encode(IO(Unit),Done{chunk},doc,catalog,GC.Request{x,z,123,0,5023},doc => result => next(doc,region(GC.compress_result(True{},result),x,z,B.int(B.int(w,W.flag(valid)),pieces))))

@unsafe
def queries(doc: R.Document,+ctx: Replay.Context,+ready: List<&2,Replay.Start>,+catalog: CC.Catalog,+policy: P.Policy,+repair: Merge.Repair,+h: Plan.Heights,+ground: M.Node,+xs: M.Node,+i: U32,n: U32,w: B.Writer,next: R.Document -> B.Writer -> IO(Unit)) -> IO(Unit):
  match n:
    case 0: next(doc,w)
    case _: query(doc,ctx,ready,catalog,policy,repair,h,ground,M.item(xs,i),w,doc => w => queries(doc,ctx,ready,catalog,policy,repair,h,ground,xs,U32.inc(i),U32.sub(n,1),w,next))

@unsafe
def query(doc: R.Document,+ctx: Replay.Context,+ready: List<&2,Replay.Start>,+catalog: CC.Catalog,+policy: P.Policy,+repair: Merge.Repair,+h: Plan.Heights,+ground: M.Node,+row: M.Node,w: B.Writer,next: R.Document -> B.Writer -> IO(Unit)) -> IO(Unit):
  +x = M.int(M.item(row,0),0)
  +z = M.int(M.item(row,1),0)
  encode(Replay.chunk(ctx,ready,source(x,z,h,ground),policy,repair,x,z),doc,catalog,x,z,w,next)

@unsafe
def cache_info(cache: Cache.Cache,w: B.Writer) -> B.Writer:
  match cache:
    case Cache.Cache{ts,ps,th,ph}: B.int(B.int(B.int(B.int(w,U32.from_nat(List.length(&2,Cache.Rotated,ts))),U32.from_nat(List.length(&2,Cache.Program,ps))),th),ph)

@unsafe
def groups(doc: R.Document,+ctx: Replay.Context,+catalog: CC.Catalog,+policy: P.Policy,+repair: Merge.Repair,+ground: M.Node,+xs: M.Node,+i: U32,n: U32,cache: Cache.Cache,w: B.Writer,output: String) -> IO(Unit):
  match n:
    case 0: Output.unwrapped(B.done(cache_info(cache,w)),output)
    case _: group(doc,ctx,catalog,policy,repair,ground,M.item(xs,i),cache,w,doc => cache => w => groups(doc,ctx,catalog,policy,repair,ground,xs,U32.inc(i),U32.sub(n,1),cache,w,output))

@unsafe
def group(doc: R.Document,+ctx: Replay.Context,+catalog: CC.Catalog,+policy: P.Policy,+repair: Merge.Repair,+ground: M.Node,+row: M.Node,cache: Cache.Cache,w: B.Writer,next: R.Document -> Cache.Cache -> B.Writer -> IO(Unit)) -> IO(Unit):
  +h = field(M.int(M.item(row,0),0))
  group_heights(doc,ctx,catalog,policy,repair,ground,row,cache,w,h,next)

@unsafe
def group_heights(doc: R.Document,+ctx: Replay.Context,+catalog: CC.Catalog,+policy: P.Policy,+repair: Merge.Repair,+ground: M.Node,+row: M.Node,cache: Cache.Cache,w: B.Writer,+h: Plan.Heights,next: R.Document -> Cache.Cache -> B.Writer -> IO(Unit)) -> IO(Unit):
  match ctx:
    case Replay.Context{model,_,_,_,_,_,_,_,_}:
      Replay.prepare(IO(Unit),doc,ctx,starts(model,M.item(row,1),0,M.count(M.item(row,1)),M.int(M.item(row,0),0),h),cache,doc => cache => ready =>
        queries(doc,ctx,ready,catalog,policy,repair,h,ground,M.item(row,2),0,M.count(M.item(row,2)),B.int(w,M.count(M.item(row,2))),doc => w => next(doc,cache,w)))

def rejected(result: Result<&1,&1,U32,Z.Bytes>,code: U32) -> Bool:
  match result:
    case Fail{actual}: U32.is_eq(actual,code)
    case Done{_}: False{}

@unsafe
def bad_start(+model: M.Model) -> List<&2,Replay.Start>:
  +piece: Plan.Piece = Plan.Piece{V.object([M.Field{M.key(model,24n),V.array([V.object([M.Field{M.key(model,25n),V.integer(0)}])])}]),P.Position{0,64,0},0,Geometry.Box{P.Position{0,64,0},P.Position{15,65,15}},0,0,0,0}
  [Replay.Start{Meta.Start{0,0,0,W.U64{0,0},[piece],None{}},[Replay.Piece{piece,[Replay.Part{0,Template.Template{[],M.empty(),False{}},Processors.Program{Processors.Noop{},0},False{}}]}],False{}}]

@unsafe
def failures(doc: R.Document,+ctx: Replay.Context,+catalog: CC.Catalog,+policy: P.Policy,+repair: Merge.Repair,+root: M.Node,output: String) -> IO(Unit):
  match ctx:
    case Replay.Context{model,_,_,_,_,_,_,_,_}:
      +grid = source(0,0,field(0),M.item(root,0))
      SC.encode(IO(Unit),Replay.chunk(ctx,bad_start(model),grid,policy,repair,0,0),doc,catalog,GC.Request{0,0,123,0,5023},doc => result =>
        failure_checked(rejected(result,871),doc,ctx,catalog,policy,repair,root,output))

@unsafe
def failure_checked(ok: Bool,doc: R.Document,+ctx: Replay.Context,+catalog: CC.Catalog,+policy: P.Policy,+repair: Merge.Repair,+root: M.Node,output: String) -> IO(Unit):
  match ok:
    case False{}: IO.die(Unit,872,"invalid template yielded chunk bytes")
    case True{}: SC.encode(IO(Unit),Replay.chunk(ctx,[],source(0,0,field(0),M.item(root,0)),policy,repair,2,2),doc,catalog,GC.Request{2,2,123,0,5023},doc => result =>
      failures_done(rejected(result,766),doc,ctx,catalog,policy,repair,root,output))

@unsafe
def failures_done(ok: Bool,doc: R.Document,+ctx: Replay.Context,+catalog: CC.Catalog,+policy: P.Policy,+repair: Merge.Repair,+root: M.Node,output: String) -> IO(Unit):
  match ok:
    case False{}: IO.die(Unit,872,"missing terrain yielded chunk bytes")
    case True{}: groups(doc,ctx,catalog,policy,repair,M.item(root,0),M.item(root,1),0,M.count(M.item(root,1)),Cache.empty(),B.int(B.new(),M.count(M.item(root,1))),output)

@unsafe
def cases(found: Maybe<&2,R.Value>,doc: R.Document,+ctx: Replay.Context,catalog: CC.Catalog,policy: P.Policy,repair: Merge.Repair,output: String) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,872,"missing cases")
    case Some{value}: V.project(IO(Unit),doc,value,doc => root => failures(doc,ctx,catalog,policy,repair,root,output))

@unsafe
def arrays(masks: Result<&1,&1,U32,Q.Table<U32>>,halves: Result<&1,&1,U32,Q.Table<U32>>,floors: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,+ctx: Replay.Context,catalog: CC.Catalog,output: String) -> IO(Unit):
  match masks halves floors ctx:
    case Done{masks} Done{halves} Done{floors} Replay.Context{_,_,_,_,_,_,Supports.Model{+flags,_,water,lava,_},_,stone}:
      RV.root(IO(Unit),doc,doc => root => RV.field(IO(Unit),doc,root,"replay_cases",doc => found => cases(found,doc,ctx,catalog,P.Policy{masks,flags,Q.Empty{},water,lava,stone,False{}},Merge.Repair{flags,halves,floors},output)))
    case _ _ _ _: IO.die(Unit,872,"invalid repair arrays")

@unsafe
def catalog(result: Result<&1,&1,U32,CC.Catalog>,doc: R.Document,+ctx: Replay.Context,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid catalog")
    case Done{catalog}: RV.root(IO(Unit),doc,doc => +root => RV.field(IO(Unit),doc,root,"heightmap_masks",doc => found => Arrays.array(IO(Unit),found,doc,0,doc => masks =>
      RV.field(IO(Unit),doc,root,"plant_halves",doc => found => Arrays.array(IO(Unit),found,doc,2,doc => halves =>
        RV.field(IO(Unit),doc,root,"plant_floor_masks",doc => found => Arrays.array(IO(Unit),found,doc,0,doc => floors => arrays(masks,halves,floors,doc,ctx,catalog,output))))))))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"registry load failed")
    case Done{doc}: M.prepare(IO(Unit),doc,doc => +model => Replay.context(IO(Unit),doc,model,doc => ctx => Catalog.prepare(IO(Unit),doc,doc => result => catalog(result,doc,ctx,output))))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: structure-replay profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
