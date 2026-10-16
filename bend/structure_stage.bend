import Base
import ./structure_region.bend as Region
import ./structure_terrain.bend as Terrain
import ./structure_replay.bend as Replay
import ./structure_registry.bend as M
import ./structure_metadata.bend as Meta
import ./structure_cache.bend as Cache
import ./structure_geometry.bend as Geometry
import ./registry.bend as R
import ./registry_values.bend as V
import ./registry_density.bend as D
import ./registry_placement_policy.bend as Policy
import ./registry_cave_features.bend as Arrays
import ./placement_data.bend as P
import ./placement_merge.bend as Merge
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./terrain_surface.bend as S
import ./density.bend as Q
import ./chunk_metadata.bend as Tags
import ./cave_lattice.bend as CV

# Session caches belong to one registry/configuration. Final grids contain the
# replayed structures before weather, lighting, queries and MCA serialization.
type Session is Data:
  Session{context: Q.Table<Replay.Context>,policy: P.Policy,repair: Merge.Repair,layouts: Region.Cache,templates: Cache.Cache}
type Cell is Data:
  Cell{columns: Q.Table<MD.Column>,metadata: Q.Table<U32>,pieces: U32}
type Batch is Type:
  Leaf{result: Result<&1,&1,U32,Replay.Chunk>}
  Node{left: Batch,right: Batch}
type Computed is Data:
  Computed{session: Session,grid: MD.Grid,metadata: Tags.Store,starts: U32,pieces: U32}

@unsafe
def count(found: Maybe<&2,R.Value>) -> U32:
  match found:
    case Some{R.Value{6,n,_,_}}: n
    case _: 0

@unsafe
def prepare(-A: Type,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Session> -> A) -> A:
  M.prepare(A,doc,doc => model => Replay.context(A,doc,model,doc => ctx =>
    V.root(A,doc,doc => +root => V.field(A,doc,root,"materials",doc => palette =>
      V.field(A,doc,root,"decorations",doc => recipes => Policy.prepare(A,doc,root,count(palette),count(recipes),doc => policy =>
        V.field(A,doc,root,"plant_halves",doc => found => Arrays.array(A,found,doc,2,doc => halves =>
          V.field(A,doc,root,"plant_floor_masks",doc => found => Arrays.array(A,found,doc,0,doc => floors =>
            prepared(A,policy,halves,floors,doc,ctx,next)))))))))))

@unsafe
def prepared(-A: Type,policy: Result<&1,&1,U32,P.Policy>,halves: Result<&1,&1,U32,Q.Table<U32>>,floors: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,ctx: Replay.Context,next: R.Document -> Result<&1,&1,U32,Session> -> A) -> A:
  match policy halves floors:
    case Done{+policy} Done{halves} Done{floors}: repaired(A,policy,halves,floors,doc,ctx,next)
    case _ _ _: next(doc,Fail{881})

@unsafe
def repaired(-A: Type,+policy: P.Policy,halves: Q.Table<U32>,floors: Q.Table<U32>,doc: R.Document,ctx: Replay.Context,next: R.Document -> Result<&1,&1,U32,Session> -> A) -> A:
  match policy:
    case P.Policy{_,flags,_,_,_,_,_}: next(doc,Done{Session{Q.Leaf{ctx},policy,Merge.Repair{flags,halves,floors},Region.empty(),Cache.empty()}})

@unsafe
def compatible(source: MD.Grid) -> Bool:
  match source:
    case MD.Grid{S.Tile{x,z,+width,+depth,_,_},_,_,_,_}:
      Bool.and(Bool.and(U32.is_zero(U32.and(x,15)),U32.is_zero(U32.and(z,15))),Bool.and(
        Bool.and(U32.is_eq(width,depth),Bool.and(U32.is_gt(width,0),U32.is_le(width,512))),U32.is_zero(U32.and(width,15))))

@unsafe
def chunk(+i: U32,+source: MD.Grid,+ctx: Q.Table<Replay.Context>,ready: List<&2,Replay.Start>,policy: P.Policy,repair: Merge.Repair) -> Result<&1,&1,U32,Replay.Chunk>:
  match source ctx:
    case MD.Grid{S.Tile{x,z,width,_,_,_},_,_,_,_} Q.Leaf{ctx}:
      Replay.chunk(ctx,ready,source,policy,repair,U32.add(Geometry.floor_div(x,16),U32.mod(i,U32.div(width,16))),U32.add(Geometry.floor_div(z,16),U32.div(i,U32.div(width,16))))
    case _ _: Fail{881}

@unsafe
def batch(+n: U32,+i: U32,+source: MD.Grid,+ctx: Q.Table<Replay.Context>,+ready: List<&2,Replay.Start>,+policy: P.Policy,+repair: Merge.Repair) -> Batch:
  match n:
    case 1: Leaf{chunk(i,source,ctx,ready,policy,repair)}
    case _:
      +half = U32.div(n,2)
      a b = batch(half,i,source,ctx,ready,policy,repair) batch(U32.sub(n,half),U32.add(i,half),source,ctx,ready,policy,repair)
      Node{a,b}

@unsafe
def collected(-A: Type,tree: Batch,doc: R.Document,acc: List<&2,Cell>,next: R.Document -> Result<&1,&1,U32,List<&2,Cell>> -> A) -> A:
  match tree:
    case Leaf{result}: cell(A,result,doc,acc,next)
    case Node{a,b}: collected(A,a,doc,acc,doc => result => collected_more(A,result,b,doc,next))

@unsafe
def collected_more(-A: Type,result: Result<&1,&1,U32,List<&2,Cell>>,rest: Batch,doc: R.Document,next: R.Document -> Result<&1,&1,U32,List<&2,Cell>> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{acc}: collected(A,rest,doc,acc,next)

@unsafe
def cell(-A: Type,result: Result<&1,&1,U32,Replay.Chunk>,doc: R.Document,acc: List<&2,Cell>,next: R.Document -> Result<&1,&1,U32,List<&2,Cell>> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{Replay.Chunk{_,_,_,False{}}}: next(doc,Fail{871})
    case Done{Replay.Chunk{MD.Grid{_,_,_,columns,_},metadata,pieces,True{}}}:
      Meta.encode(A,doc,metadata,doc => bytes => cell_encoded(A,Tags.encoded(bytes),columns,pieces,doc,acc,next))

@unsafe
def cell_encoded(-A: Type,result: Result<&1,&1,U32,Q.Table<U32>>,columns: Q.Table<MD.Column>,pieces: U32,doc: R.Document,acc: List<&2,Cell>,next: R.Document -> Result<&1,&1,U32,List<&2,Cell>> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{metadata}: next(doc,Done{Cell{columns,metadata,pieces} <> acc})

@unsafe
def batches(-A: Type,+base: U32,+count: U32,+source: MD.Grid,+session: Session,+ready: List<&2,Replay.Start>,doc: R.Document,acc: List<&2,Cell>,next: R.Document -> Result<&1,&1,U32,List<&2,Cell>> -> A) -> A:
  match count session:
    case 0 _: next(doc,Done{List.reverse(&2,Cell,acc)})
    case _ Session{ctx,policy,repair,_,_}:
      +n = U32.min(8,count)
      collected(A,batch(n,base,source,ctx,ready,policy,repair),doc,acc,doc => result => batches_more(A,result,U32.add(base,n),U32.sub(count,n),source,session,ready,doc,next))

@unsafe
def batches_more(-A: Type,result: Result<&1,&1,U32,List<&2,Cell>>,base: U32,count: U32,source: MD.Grid,session: Session,ready: List<&2,Replay.Start>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,List<&2,Cell>> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{acc}: batches(A,base,count,source,session,ready,doc,acc,next)

@unsafe
def column(+i: U32,+width: U32,cells: Q.Table<Cell>) -> MD.Column:
  from_cell(Q.get(Cell,cells,U32.add(U32.div(U32.mod(i,width),16),U32.mul(U32.div(U32.div(i,width),16),U32.div(width,16))),Cell{Q.Empty{},Q.Empty{},0}),U32.add(U32.mod(i,16),U32.mul(U32.mod(U32.div(i,width),16),16)))

@unsafe
def from_cell(cell: Cell,i: U32) -> MD.Column:
  match cell:
    case Cell{columns,_,_}: Q.get(MD.Column,columns,i,MC.empty())

@unsafe
def columns(+n: U32,+base: U32,+width: U32,+cells: Q.Table<Cell>) -> Q.Table<MD.Column>:
  match n:
    case 0: Q.Empty{}
    case 1: Q.Leaf{column(base,width,cells)}
    case _:
      +half = U32.div(n,2)
      a b = columns(half,base,width,cells) columns(U32.sub(n,half),U32.add(base,half),width,cells)
      Q.Branch{n,a,b}

@unsafe
def tags(cells: List<&2,Cell>) -> List<&2,Q.Table<U32>>:
  match cells:
    case Nil{}: []
    case Cell{_,metadata,_} <> rest: metadata <> tags(rest)

@unsafe
def pieces(cells: List<&2,Cell>) -> U32:
  match cells:
    case Nil{}: 0
    case Cell{_,_,n} <> rest: U32.add(n,pieces(rest))

@unsafe
def assembled(result: Result<&1,&1,U32,List<&2,Cell>>,session: Session,source: MD.Grid,starts: U32) -> Result<&1,&1,U32,Computed>:
  match result source:
    case Fail{code} _: Fail{code}
    case Done{+cells} MD.Grid{+tile,minimum,height,_,biomes}: assembled_tile(session,tile,minimum,height,biomes,cells,starts)

@unsafe
def assembled_tile(session: Session,+tile: S.Tile,minimum: U32,height: U32,biomes: Maybe<&2,CV.Grid>,+cells: List<&2,Cell>,starts: U32) -> Result<&1,&1,U32,Computed>:
  match tile:
    case S.Tile{_,_,+width,depth,_,_}: Done{Computed{session,MD.Grid{tile,minimum,height,columns(U32.mul(width,depth),0,width,Q.prepared_table(Cell,cells)),biomes},Tags.Store{tile,Q.prepared_table(Q.Table<U32>,tags(cells))},starts,pieces(cells)}}

@unsafe
def planned(-A: Type,result: Result<&1,&1,U32,Region.Complete>,doc: R.Document,session: Session,+source: MD.Grid,next: R.Document -> Result<&1,&1,U32,Computed> -> A) -> A:
  match result session:
    case Fail{code} _: next(doc,Fail{code})
    case Done{Region.Complete{layouts,+starts}} Session{Q.Leaf{+ctx},policy,repair,_,cache}:
      Replay.prepare(A,doc,ctx,starts,cache,doc => cache => ready =>
        replay(A,doc,Session{Q.Leaf{ctx},policy,repair,layouts,cache},source,ready,U32.from_nat(List.length(&2,Meta.Start,starts)),next))
    case _ _: next(doc,Fail{881})

@unsafe
def replay(-A: Type,doc: R.Document,+session: Session,+source: MD.Grid,ready: List<&2,Replay.Start>,starts: U32,next: R.Document -> Result<&1,&1,U32,Computed> -> A) -> A:
  match source:
    case MD.Grid{S.Tile{_,_,width,depth,_,_},_,_,_,_}:
      batches(A,0,U32.div(U32.mul(width,depth),256),source,session,ready,doc,[],doc => result => next(doc,assembled(result,session,source,starts)))

@unsafe
def execute(-A: Type,doc: R.Document,+session: Session,env: Terrain.Environment,+source: MD.Grid,next: R.Document -> Result<&1,&1,U32,Computed> -> A) -> A:
  match session source:
    case Session{Q.Leaf{Replay.Context{model,_,_,_,_,_,_,_,_}},_,_,cache,_} MD.Grid{S.Tile{x,z,width,_,_,_},_,_,_,_}:
      planned(A,Region.region(model,env,Geometry.floor_div(x,16),Geometry.floor_div(z,16),U32.div(width,16),cache),doc,session,source,next)
    case _ _: next(doc,Fail{881})
