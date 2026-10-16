import Base
import ./structure_stage.bend as Stage
import ./structure_replay.bend as Replay
import ./structure_region.bend as Region
import ./structure_terrain.bend as Terrain
import ./structure_candidates.bend as C
import ./structure_registry.bend as M
import ./structure_plan.bend as Plan
import ./structure_metadata.bend as Meta
import ./density.bend as Q
import ./word64.bend as W
import ./reader.bend as R
import ./compression.bend as Z
import ./binary.bend as B
import ./structure_values.bend as V
import ./structure_geometry.bend as Geometry
import ./placement_data.bend as Placement
import ./terrain_surface.bend as Surface

# Both commands are read-only with respect to terrain and serialized metadata.
# Candidate cells and actual layout acceptance run in Bend; the bridge may scan
# saved starts before asking about unknown candidates. Batches are bounded at 64.
type Query is Data:
  Query{seed: W.U64,points: List<&2,C.Start>}
type Computed is Type:
  Computed{session: Stage.Session,body: B.Writer}
  Metadata{session: Stage.Session,root: M.Node}

@unsafe
def decode(bytes: Z.Bytes) -> Result<&1,&1,U32,Query>:
  R.word(Result<&1,&1,U32,Query>,R.start(bytes),cursor => lo =>
    R.word(Result<&1,&1,U32,Query>,cursor,cursor => hi =>
      R.word(Result<&1,&1,U32,Query>,cursor,cursor => +n =>
        bounded(U32.is_le(n,64),n,cursor,W.U64{hi,lo}))))

@unsafe
def bounded(ok: Bool,n: U32,cursor: R.Cursor,seed: W.U64) -> Result<&1,&1,U32,Query>:
  match ok:
    case False{}: Fail{883}
    case True{}: points(n,cursor,seed,[])

@unsafe
def points(n: U32,cursor: R.Cursor,seed: W.U64,acc: List<&2,C.Start>) -> Result<&1,&1,U32,Query>:
  match n:
    case 0: ended(R.end(cursor),seed,acc)
    case _: R.word(Result<&1,&1,U32,Query>,cursor,cursor => x =>
      R.word(Result<&1,&1,U32,Query>,cursor,cursor => z =>
        R.word(Result<&1,&1,U32,Query>,cursor,cursor => set => points(U32.sub(n,1),cursor,seed,C.Start{set,x,z} <> acc))))

def ended(ok: Bool,seed: W.U64,acc: List<&2,C.Start>) -> Result<&1,&1,U32,Query>:
  match ok:
    case False{}: Fail{603}
    case True{}: Done{Query{seed,List.reverse(&2,C.Start,acc)}}

@unsafe
def valid(xs: List<&2,C.Start>,+count: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case C.Start{id,_,_} <> rest: Bool.and(U32.is_lt(id,count),valid(rest,count))

def coordinate(c: C.Candidate,w: B.Writer) -> B.Writer:
  match c:
    case C.Candidate{x,z}: B.int(B.int(w,x),z)

@unsafe
def candidates(xs: List<&2,C.Start>,+m: M.Model,+seed: W.U64,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case C.Start{id,x,z} <> rest: candidates(rest,m,seed,coordinate(C.candidate(seed,x,z,m,M.get(m,C.set(m,id),34n)),w))

@unsafe
def allowed(xs: List<&2,C.Start>,+m: M.Model,+seed: W.U64) -> List<&2,C.Start>:
  match xs:
    case Nil{}: []
    case +c <> rest: allowed_one(c,m,seed,allowed(rest,m,seed))

@unsafe
def allowed_one(+c: C.Start,m: M.Model,seed: W.U64,rest: List<&2,C.Start>) -> List<&2,C.Start>:
  match c:
    case C.Start{id,x,z}: included(C.permitted(m,id,seed,x,z,[]),c,rest)

def included(ok: Bool,c: C.Start,rest: List<&2,C.Start>) -> List<&2,C.Start>:
  match ok:
    case True{}: c <> rest
    case False{}: rest

def selected(start: Maybe<&2,Meta.Start>) -> U32:
  match start:
    case Some{Meta.Start{id,_,_,_,_,_}}: id
    case None{}: 4294967295

@unsafe
def cached(found: Maybe<&2,Maybe<&2,Meta.Start>>,old: Region.Cache,seed: W.U64,c: C.Start) -> U32:
  match found old:
    case Some{start} _: selected(start)
    case None{} Region.Cache{entries,_,_}: previous(Region.find(entries,seed,c))

def previous(found: Maybe<&2,Maybe<&2,Meta.Start>>) -> U32:
  match found:
    case None{}: 4294967295
    case Some{start}: selected(start)

# Freeze old entries as well as final entries: a hit from the start of this
# batch can be evicted by a later miss, but must still appear in the response.
@unsafe
def selections(xs: List<&2,C.Start>,+seed: W.U64,+old: Region.Cache,+fresh: Region.Cache,w: B.Writer) -> B.Writer:
  match xs fresh:
    case Nil{} _: w
    case +c <> rest Region.Cache{entries,_,_}: selections(rest,seed,old,fresh,B.int(w,cached(Region.find(entries,seed,c),old,seed,c)))

@unsafe
def planned(result: Result<&1,&1,U32,Region.Complete>,session: Stage.Session,seed: W.U64,points: List<&2,C.Start>) -> Result<&1,&1,U32,Computed>:
  match result session:
    case Fail{code} _: Fail{code}
    case Done{Region.Complete{+fresh,_}} Stage.Session{ctx,policy,repair,+old,templates}:
      Done{Computed{Stage.Session{ctx,policy,repair,fresh,templates},selections(points,seed,old,fresh,B.new())}}

@unsafe
def execute(ok: Bool,opcode: U32,+m: M.Model,+session: Stage.Session,env: Terrain.Environment,+seed: W.U64,+points: List<&2,C.Start>) -> Result<&1,&1,U32,Computed>:
  match ok opcode session:
    case False{} _ _: Fail{883}
    case True{} 57 _: Done{Computed{session,candidates(points,m,seed,B.new())}}
    case True{} _ Stage.Session{_,_,_,cache,_}:
      planned(Region.candidates(allowed(points,m,seed),m,env,cache),session,seed,points)

@unsafe
def selection_query(opcode: U32,+session: Stage.Session,env: Terrain.Environment,query: Query) -> Result<&1,&1,U32,Computed>:
  match session query:
    case Stage.Session{Q.Leaf{Replay.Context{+m,_,_,_,_,_,_,_,_}},_,_,_,_} Query{+seed,+points}:
      execute(valid(points,M.count(Plan.array(m,4n))),opcode,m,session,env,seed,points)
    case _ _: Fail{881}

@unsafe
def at_chunk(+x: U32,+z: U32,+i: U32,n: U32) -> List<&2,C.Start>:
  match n:
    case 0: []
    case _: C.Start{i,x,z} <> at_chunk(x,z,U32.inc(i),U32.sub(n,1))

@unsafe
def records(data: Meta.Records,ctx: Meta.Context) -> M.Node:
  match data:
    case Meta.Records{starts,_}: Meta.root(ctx,Meta.Records{starts,V.compound()},[],[])

@unsafe
def metadata_planned(result: Result<&1,&1,U32,Region.Complete>,session: Stage.Session,config: Surface.Config,+x: U32,+z: U32) -> Result<&1,&1,U32,Computed>:
  match result session config:
    case Fail{code} _ _: Fail{code}
    case Done{Region.Complete{cache,starts}} Stage.Session{Q.Leaf{+context},policy,repair,_,templates} Surface.Config{+minimum,height,_,_,_,_}:
      metadata_context(context,Stage.Session{Q.Leaf{context},policy,repair,cache,templates},starts,x,z,Geometry.Box{Placement.Position{U32.mul(x,16),minimum,U32.mul(z,16)},Placement.Position{U32.add(U32.mul(x,16),15),U32.sub(U32.add(minimum,height),1),U32.add(U32.mul(z,16),15)}})
    case _ _ _: Fail{881}

@unsafe
def metadata_context(context: Replay.Context,session: Stage.Session,starts: List<&2,Meta.Start>,x: U32,z: U32,bounds: Geometry.Box) -> Result<&1,&1,U32,Computed>:
  match context:
    case Replay.Context{model,_,_,_,_,keys,_,_,_}:
      +ctx: Meta.Context = Meta.Context{model,keys,x,z,bounds}
      Done{Metadata{session,records(Meta.collect(starts,ctx,Meta.Records{V.compound(),V.compound()}),ctx)}}

@unsafe
def metadata_query(+session: Stage.Session,+env: Terrain.Environment,query: Query) -> Result<&1,&1,U32,Computed>:
  match session env query:
    case Stage.Session{Q.Leaf{Replay.Context{+m,_,_,_,_,_,_,_,_}},_,_,cache,_} Terrain.Environment{config,_,_,_,_,_} Query{+seed,C.Start{0,+x,+z} <> Nil{}}:
      metadata_planned(Region.candidates(allowed(at_chunk(x,z,0,M.count(Plan.array(m,4n))),m,seed),m,env,cache),session,config,x,z)
    case _ _ _: Fail{883}

def run(opcode: U32,session: Stage.Session,env: Terrain.Environment,query: Query) -> Result<&1,&1,U32,Computed>:
  match opcode:
    case 59: metadata_query(session,env,query)
    case _: selection_query(opcode,session,env,query)
