import Base
import ./structure_terrain.bend as T
import ./structure_candidates.bend as C
import ./structure_plan.bend as Plan
import ./structure_metadata.bend as Meta
import ./structure_registry.bend as M
import ./structure_geometry.bend as G
import ./terrain_surface.bend as S
import ./density.bend as Q
import ./word64.bend as W

# One cache belongs to one loaded registry/configuration. Both seed words and
# the structure-set/start coordinates are part of each entry's identity.
# Rejected starts are cached too; a repeat does not resample their GPU biomes.
# The caller drops this cache on profile/configuration replacement.
type Entry is Data:
  Entry{seed: W.U64,candidate: C.Start,start: Maybe<&2,Meta.Start>}
type Cache is Data:
  Cache{entries: List<&2,Entry>,hits: U32,misses: U32}
type Work is Data:
  Known{start: Maybe<&2,Meta.Start>}
  Pending{candidate: C.Start,index: U32}
type Batch is Data:
  Batch{work: List<&2,Work>,probes: List<&2,T.Probe>,size: U32,hits: U32}
type Complete is Data:
  Complete{cache: Cache,starts: List<&2,Meta.Start>}

def empty() -> Cache:
  Cache{[],0,0}

def same(a: C.Start,b: C.Start) -> Bool:
  match a b:
    case C.Start{s,x,z} C.Start{t,u,v}: Bool.and(U32.is_eq(s,t),Bool.and(U32.is_eq(x,u),U32.is_eq(z,v)))

@unsafe
def find(xs: List<&2,Entry>,+seed: W.U64,+candidate: C.Start) -> Maybe<&2,Maybe<&2,Meta.Start>>:
  match xs:
    case Nil{}: None{}
    case Entry{s,c,start} <> rest: found(Bool.and(W.equal(s,seed),same(c,candidate)),start,rest,seed,candidate)

@unsafe
def found(equal: Bool,start: Maybe<&2,Meta.Start>,rest: List<&2,Entry>,seed: W.U64,candidate: C.Start) -> Maybe<&2,Maybe<&2,Meta.Start>>:
  match equal:
    case True{}: Some{start}
    case False{}: find(rest,seed,candidate)

@unsafe
def take(n: U32,xs: List<&2,Entry>) -> List<&2,Entry>:
  match n xs:
    case 0 _: []
    case _ Nil{}: []
    case _ x <> rest: x <> take(U32.sub(n,1),rest)

def stored(cache: Cache,entry: Entry) -> Cache:
  match cache:
    case Cache{xs,hits,misses}: Cache{entry <> take(63,xs),hits,U32.inc(misses)}

def seed(env: T.Environment) -> W.U64:
  match env:
    case T.Environment{_,_,_,_,_,seed}: seed

# Freeze cache hits before any miss is inserted: eviction during this request
# cannot turn a previously cached candidate into an unindexed GPU lookup.
@unsafe
def batch(xs: List<&2,C.Start>,+m: M.Model,+env: T.Environment,+cache: Cache,b: Batch) -> Batch:
  match xs cache:
    case Nil{} _: reverse(b)
    case +c <> rest Cache{entries,_,_}: batch_found(find(entries,seed(env),c),c,rest,m,env,cache,b)

@unsafe
def batch_found(result: Maybe<&2,Maybe<&2,Meta.Start>>,+c: C.Start,rest: List<&2,C.Start>,+m: M.Model,+env: T.Environment,cache: Cache,b: Batch) -> Batch:
  match result b:
    case Some{start} Batch{work,probes,n,hits}: batch(rest,m,env,cache,Batch{Known{start} <> work,probes,n,U32.inc(hits)})
    case None{} Batch{work,probes,+n,hits}: batch(rest,m,env,cache,Batch{Pending{c,n} <> work,T.probe(m,c,env) <> probes,U32.inc(n),hits})

def reverse(b: Batch) -> Batch:
  match b:
    case Batch{work,probes,n,hits}: Batch{List.reverse(&2,Work,work),List.reverse(&2,T.Probe,probes),n,hits}

@unsafe
def before(a: Meta.Start,b: Meta.Start) -> Bool:
  match a b:
    case Meta.Start{+d,+x,+z,_,_,_} Meta.Start{+e,+u,+v,_,_,_}:
      Bool.or(G.lt(z,v),Bool.and(U32.is_eq(z,v),Bool.or(G.lt(x,u),Bool.and(U32.is_eq(x,u),U32.is_lt(d,e)))))

@unsafe
def insert(+start: Meta.Start,xs: List<&2,Meta.Start>) -> List<&2,Meta.Start>:
  match xs:
    case Nil{}: [start]
    case +x <> rest: insert_before(before(start,x),start,x,rest)

@unsafe
def insert_before(first: Bool,+start: Meta.Start,x: Meta.Start,rest: List<&2,Meta.Start>) -> List<&2,Meta.Start>:
  match first:
    case True{}: start <> x <> rest
    case False{}: x <> insert(start,rest)

def append(start: Maybe<&2,Meta.Start>,xs: List<&2,Meta.Start>) -> List<&2,Meta.Start>:
  match start:
    case None{}: xs
    case Some{start}: insert(start,xs)

@unsafe
def planned(layout: Plan.Layout,id: U32,c: C.Start,seed: W.U64,heights: Maybe<&2,Plan.Heights>) -> Maybe<&2,Meta.Start>:
  match layout c:
    case Plan.Layout{_,Nil{},_} _: None{}
    case Plan.Layout{_,pieces,_} C.Start{_,x,z}: Some{Meta.Start{id,x,z,seed,pieces,heights}}

@unsafe
def layout(+m: M.Model,+id: U32,+c: C.Start,state: W.U64,env: T.Environment,+heights: Maybe<&2,Plan.Heights>) -> Maybe<&2,Meta.Start>:
  match c env:
    case C.Start{set,+x,+z} T.Environment{S.Config{min,h,_,_,_,_},_,_,_,_,seed}:
      planned(Plan.begin(m,M.item(Plan.array(m,2n),id),state,x,z,heights,min,h),id,c,C.start_seed(m,M.get(m,C.set(m,set),34n),seed,x,z),heights)

@unsafe
def terrain(result: Result<&1,&1,U32,Plan.Heights>,m: M.Model,id: U32,c: C.Start,state: W.U64,env: T.Environment) -> Result<&1,&1,U32,Maybe<&2,Meta.Start>>:
  match result:
    case Fail{code}: Fail{code}
    case Done{h}: Done{layout(m,id,c,state,env,Some{h})}

@unsafe
def projected(yes: Bool,+m: M.Model,+id: U32,+c: C.Start,state: W.U64,+env: T.Environment) -> Result<&1,&1,U32,Maybe<&2,Meta.Start>>:
  match yes c:
    case False{} _: Done{layout(m,id,c,state,env,None{})}
    case True{} C.Start{_,x,z}:
      +d = M.item(Plan.array(m,2n),id)
      terrain(T.terrain(T.tile(m,d,x,z,seed(env)),env,T.water(m,d)),m,id,c,state,env)

@unsafe
def chosen(choice: C.Choice,+m: M.Model,c: C.Start,env: T.Environment) -> Result<&1,&1,U32,Maybe<&2,Meta.Start>>:
  match choice:
    case C.Choice{_,None{}}: Done{None{}}
    case C.Choice{state,Some{+id}}: projected(T.projected(m,M.item(Plan.array(m,2n),id)),m,id,c,state,env)

@unsafe
def build(+m: M.Model,+c: C.Start,+env: T.Environment,biomes: Q.Table<U32>) -> Result<&1,&1,U32,Maybe<&2,Meta.Start>>:
  match c:
    case C.Start{set,x,z}: chosen(C.choose_definition(m,set,seed(env),x,z,biomes),m,c,env)

@unsafe
def resolved(result: Result<&1,&1,U32,Maybe<&2,Meta.Start>>,c: C.Start,rest: List<&2,Work>,m: M.Model,+env: T.Environment,biomes: Q.Table<Q.Table<U32>>,cache: Cache,starts: List<&2,Meta.Start>) -> Result<&1,&1,U32,Complete>:
  match result:
    case Fail{code}: Fail{code}
    case Done{+start}: collect(rest,m,env,biomes,stored(cache,Entry{seed(env),c,start}),append(start,starts))

@unsafe
def collect(xs: List<&2,Work>,+m: M.Model,+env: T.Environment,+biomes: Q.Table<Q.Table<U32>>,cache: Cache,starts: List<&2,Meta.Start>) -> Result<&1,&1,U32,Complete>:
  match xs:
    case Nil{}: Done{Complete{cache,starts}}
    case Known{start} <> rest: collect(rest,m,env,biomes,cache,append(start,starts))
    case Pending{+c,i} <> rest: resolved(build(m,c,env,Q.get(Q.Table<U32>,biomes,i,Q.Empty{})),c,rest,m,env,biomes,cache,starts)

@unsafe
def prepared(b: Batch,m: M.Model,+env: T.Environment,cache: Cache) -> Result<&1,&1,U32,Complete>:
  match b cache:
    case Batch{work,ps,_,hits} Cache{entries,h,misses}:
      collect(work,m,env,T.execute(Q.prepared_table(T.Probe,ps),env),Cache{entries,U32.add(h,hits),misses},[])

@unsafe
def candidates(+xs: List<&2,C.Start>,+m: M.Model,+env: T.Environment,+cache: Cache) -> Result<&1,&1,U32,Complete>:
  prepared(batch(xs,m,env,cache,Batch{[],[],0,0}),m,env,cache)

@unsafe
def region(+m: M.Model,+env: T.Environment,x: U32,z: U32,side: U32,cache: Cache) -> Result<&1,&1,U32,Complete>:
  candidates(C.region(m,seed(env),x,z,side),m,env,cache)
