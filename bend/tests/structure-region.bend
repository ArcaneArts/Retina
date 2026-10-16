import Base
import ../structure_region.bend as Region
import ../structure_terrain.bend as Terrain
import ../structure_candidates.bend as Candidates
import ../structure_registry.bend as M
import ../structure_plan.bend as Plan
import ../structure_metadata.bend as Meta
import ../registry.bend as R
import ../registry_density.bend as RD
import ../registry_climate.bend as RC
import ../registry_surface.bend as RS
import ../density.bend as Q
import ../density_lattice.bend as L
import ../climate.bend as C
import ../terrain_surface.bend as S
import ../binary.bend as B
import ../word64.bend as W
import ./structure-plan.bend as Fixture
import ./binary-fixtures.bend as Output
import ./structure-region-cache.bend as CacheChecks

@unsafe
def uints(xs: Q.Table<U32>,w: B.Writer) -> B.Writer:
  match xs:
    case Q.Empty{}: w
    case Q.Leaf{x}: B.int(w,x)
    case Q.Branch{_,a,b}: uints(b,uints(a,w))

@unsafe
def heights(h: Maybe<&2,Plan.Heights>,w: B.Writer) -> B.Writer:
  match h:
    case None{}: B.int(w,0)
    case Some{Plan.Heights{x,z,width,+values}}: uints(values,B.int(B.int(B.int(B.int(B.int(w,1),x),z),width),Q.table_size(U32,values)))

@unsafe
def starts(xs: List<&2,Meta.Start>,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case Meta.Start{d,x,z,seed,+pieces,h} <> rest:
      starts(rest,Fixture.pieces(pieces,heights(h,B.int(B.long(B.int(B.int(B.int(w,d),x),z),seed),U32.from_nat(List.length(&2,Plan.Piece,pieces))))))

@unsafe
def probes(xs: List<&2,Candidates.Start>,+m: M.Model,+env: Terrain.Environment) -> List<&2,Terrain.Probe>:
  match xs:
    case Nil{}: []
    case x <> rest: Terrain.probe(m,x,env) <> probes(rest,m,env)

@unsafe
def samples(xs: List<&2,Candidates.Start>,+values: Q.Table<Q.Table<U32>>,+i: U32,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case Candidates.Start{set,x,z} <> rest:
      samples(rest,values,U32.inc(i),uints(Q.get(Q.Table<U32>,values,i,Q.Empty{}),B.int(B.int(B.int(w,set),x),z)))

def context(i: U32) -> U32:
  match i:
    case 3: 1
    case 4: 2
    case _: 0

def x(i: U32) -> U32:
  match i:
    case 2: 1
    case _: Fixture.coordinate(context(i))

def environment(base: Terrain.Environment,c: U32) -> Terrain.Environment:
  match base:
    case Terrain.Environment{config,grid,model,programs,climate,_}:
      Terrain.Environment{config,grid,model,programs,climate,Fixture.seed(c)}

@unsafe
def completed(result: Result<&1,&1,U32,Region.Complete>,+m: M.Model,env: Terrain.Environment,i: U32,w: B.Writer,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"structure region failed")
    case Done{Region.Complete{+cache,+xs}}: done_cache(cache,xs,m,env,i,w,output)

@unsafe
def done_cache(+cache: Region.Cache,+xs: List<&2,Meta.Start>,m: M.Model,env: Terrain.Environment,i: U32,w: B.Writer,output: String) -> IO(Unit):
  match cache:
    case Region.Cache{entries,hits,misses}:
      cases(m,env,U32.inc(i),cache,starts(xs,B.int(B.int(B.int(B.int(w,U32.from_nat(List.length(&2,Region.Entry,entries))),hits),misses),U32.from_nat(List.length(&2,Meta.Start,xs)))),output)

@unsafe
def run(+m: M.Model,+env: Terrain.Environment,+i: U32,cache: Region.Cache,w: B.Writer,output: String) -> IO(Unit):
  +xs = Candidates.region(m,Region.seed(env),x(i),U32.sub(0,Fixture.coordinate(context(i))),32)
  +biomes = Terrain.execute(Q.prepared_table(Terrain.Probe,probes(xs,m,env)),env)
  completed(Region.candidates(xs,m,env,cache),m,env,i,samples(xs,biomes,0,B.int(w,U32.from_nat(List.length(&2,Candidates.Start,xs)))),output)

@unsafe
def cases(+m: M.Model,+env: Terrain.Environment,+i: U32,cache: Region.Cache,w: B.Writer,output: String) -> IO(Unit):
  match i:
    case 6: Output.unwrapped(B.done(w),output)
    case _:
      do IO<Unit>:
        _ : Nat <- IO.now()
        run(m,environment(env,context(i)),i,cache,w,output)

@unsafe
def ready(density: Result<&1,&1,U32,RD.Prepared>,climate: Result<&1,&1,U32,RC.Prepared>,surface: Result<&1,&1,U32,S.Config>,m: M.Model,output: String) -> IO(Unit):
  match density climate surface:
    case Done{RD.Prepared{model,programs,_,_,_,_,_,_}} Done{RC.Prepared{climate,_,_}} Done{config}:
      cases(m,Terrain.Environment{config,L.Grid{L.Layout{Q.world(0,0,0),2,2,2,1,1,0,0,0},Q.Empty{}},model,programs,climate,W.U64{0,0}},0,Region.empty(),B.int(B.new(),6),output)
    case _ _ _: IO.die(Unit,880,"invalid terrain input")

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"profile failed")
    case Done{doc}: M.prepare(IO(Unit),doc,doc => m => RD.prepare(IO(Unit),doc,doc => density => RC.prepare(IO(Unit),doc,doc => climate => RS.prepare(IO(Unit),doc,doc => surface => ready(density,climate,surface,m,output)))))

def arguments(args: List<String>,cache_ok: Bool) -> IO(Unit):
  match args cache_ok:
    case _ <> input <> output <> Nil{} True{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _ False{}: IO.die(Unit,881,"start cache identity or bound failed")
    case _ _: IO.die(Unit,2,"usage: structure-region profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args,CacheChecks.run())
