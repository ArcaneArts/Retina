import Base
import ./structure_cache.bend as Cache
import ./structure_registry.bend as M
import ./structure_values.bend as V
import ./structure_plan.bend as Plan
import ./structure_template.bend as T
import ./structure_processors.bend as S
import ./structure_blocks.bend as Bk
import ./structure_entities.bend as Entities
import ./structure_metadata.bend as Meta
import ./structure_supports.bend as Supports
import ./structure_geometry.bend as G
import ./registry.bend as R
import ./registry_values.bend as RV
import ./registry_density.bend as D
import ./placement_world.bend as E
import ./placement_data.bend as P
import ./placement_merge.bend as Merge
import ./material_columns_data.bend as MD
import ./density.bend as Q
import ./word64.bend as W

# Prepare once per ordered start set, then replay independent cropped chunks.
# Part quotas restart for each chunk, while the full capped template is visited.
type Context is Data:
  Context{model: M.Model,environment: S.Environment,template: T.Context,block_keys: Bk.Metadata,
    entity_keys: D.Keys,record_keys: D.Keys,supports: Supports.Model,keys: D.Keys,stone: U32}
type Part is Data:
  Part{id: U32,template: T.Template,program: S.Program,ignore_air: Bool}
type Piece is Data:
  Piece{plan: Plan.Piece,parts: List<&2,Part>}
type Start is Data:
  Start{plan: Meta.Start,pieces: List<&2,Piece>,support: Bool}
type Output is Data:
  Output{world: E.World,entities: List<&2,M.Node>,tags: List<&2,Bk.TagWrite>,pieces: U32,valid: Bool}
type Chunk is Data:
  Chunk{grid: MD.Grid,metadata: M.Node,pieces: U32,valid: Bool}

@unsafe
def context(-A: Type,doc: R.Document,+model: M.Model,next: R.Document -> Context -> A) -> A:
  S.prepare_environment(A,doc,doc => +env => T.keys(A,doc,env,doc => tc => Bk.metadata(A,doc,doc => bk =>
    Entities.keys(A,doc,doc => ek => Meta.keys(A,doc,doc => mk => Supports.prepare(A,doc,doc => support =>
      D.resolved(A,["terrain_adaptation","none","stone"],doc,[],doc => +keys => RV.root(A,doc,doc => root =>
        R.field(A,doc,root,D.key(keys,2n),doc => stone => next(doc,Context{model,env,tc,bk,ek,mk,support,keys,Supports.uint(stone)}))))))))))

@unsafe
def supports(+ctx: Context,definition: U32) -> Bool:
  match ctx:
    case Context{model,_,_,_,_,_,_,keys,_}:
      +d = M.item(Plan.array(model,2n),definition)
      Bool.not(Bool.and(M.named(model,M.get(model,d,31n),49n),Bool.and(U32.is_ne(D.key(keys,1n),4294967295),U32.is_eq(M.text(M.field(M.get(model,d,33n),D.key(keys,0n))),D.key(keys,1n)))))

@unsafe
def prepare(-A: Type,doc: R.Document,+ctx: Context,xs: List<&2,Meta.Start>,cache: Cache.Cache,next: R.Document -> Cache.Cache -> List<&2,Start> -> A) -> A:
  match xs:
    case Nil{}: next(doc,cache,[])
    case +start <> rest: prepared_start(A,doc,ctx,start,cache,doc => cache => ready => prepare(A,doc,ctx,rest,cache,doc => cache => tail => next(doc,cache,ready <> tail)))

@unsafe
def prepared_start(-A: Type,doc: R.Document,+ctx: Context,+start: Meta.Start,cache: Cache.Cache,next: R.Document -> Cache.Cache -> Start -> A) -> A:
  match start:
    case Meta.Start{d,_,_,_,pieces,_}: prepare_pieces(A,doc,ctx,pieces,cache,doc => cache => ready => next(doc,cache,Start{start,ready,supports(ctx,d)}))

@unsafe
def prepare_pieces(-A: Type,doc: R.Document,+ctx: Context,xs: List<&2,Plan.Piece>,cache: Cache.Cache,next: R.Document -> Cache.Cache -> List<&2,Piece> -> A) -> A:
  match xs:
    case Nil{}: next(doc,cache,[])
    case +piece <> rest: prepare_piece(A,doc,ctx,piece,cache,doc => cache => p => prepare_pieces(A,doc,ctx,rest,cache,doc => cache => tail => next(doc,cache,p <> tail)))

@unsafe
def prepare_piece(-A: Type,doc: R.Document,+ctx: Context,+piece: Plan.Piece,cache: Cache.Cache,next: R.Document -> Cache.Cache -> Piece -> A) -> A:
  match ctx piece:
    case Context{model,_,_,_,_,_,_,_,_} Plan.Piece{element,_,rotation,_,palette,_,_,_}:
      +parts = M.get(model,element,24n)
      prepare_parts(A,doc,ctx,parts,0,M.count(parts),palette,rotation,cache,doc => cache => ps => next(doc,cache,Piece{piece,ps}))

@unsafe
def prepare_parts(-A: Type,doc: R.Document,+ctx: Context,+xs: M.Node,+i: U32,n: U32,+palette: U32,+rotation: U32,cache: Cache.Cache,next: R.Document -> Cache.Cache -> List<&2,Part> -> A) -> A:
  match n:
    case 0: next(doc,cache,[])
    case _: prepare_part(A,doc,ctx,M.item(xs,i),palette,rotation,cache,doc => cache => p => prepare_parts(A,doc,ctx,xs,U32.inc(i),U32.sub(n,1),palette,rotation,cache,doc => cache => tail => next(doc,cache,p <> tail)))

@unsafe
def prepare_part(-A: Type,doc: R.Document,+ctx: Context,+part: M.Node,palette: U32,rotation: U32,cache: Cache.Cache,next: R.Document -> Cache.Cache -> Part -> A) -> A:
  match ctx:
    case Context{+model,+env,tc,_,_,_,_,_,_}:
      +id = M.int(M.get(model,part,25n),4294967295)
      Cache.template(A,doc,model,tc,cache,id,palette,rotation,doc => cache => t =>
        Cache.program(A,doc,env,cache,M.value(M.get(model,part,12n)),doc => cache => program => next(doc,cache,Part{id,t,program,M.flag(M.get(model,part,26n))})))

@unsafe
def bounds(source: MD.Grid,+x: U32,+z: U32) -> G.Box:
  match source:
    case MD.Grid{_,+minimum,height,_,_}: G.Box{P.Position{U32.mul(x,16),minimum,U32.mul(z,16)},P.Position{U32.add(U32.mul(x,16),15),U32.sub(U32.add(minimum,height),1),U32.add(U32.mul(z,16),15)}}

@unsafe
def replay(xs: List<&2,Start>,+ctx: Context,+bounds: G.Box,out: Output) -> Output:
  match xs:
    case Nil{}: out
    case +start <> rest: replay(rest,ctx,bounds,replay_start(start,ctx,bounds,out))

@unsafe
def replay_start(+start: Start,+ctx: Context,+bounds: G.Box,out: Output) -> Output:
  match start:
    case Start{_,pieces,_}: replay_pieces(pieces,start,ctx,bounds,out)

@unsafe
def replay_pieces(xs: List<&2,Piece>,+start: Start,+ctx: Context,+bounds: G.Box,out: Output) -> Output:
  match xs:
    case Nil{}: out
    case +p <> rest: replay_pieces(rest,start,ctx,bounds,replay_piece(p,start,ctx,bounds,out))

@unsafe
def replay_piece(+piece: Piece,+start: Start,+ctx: Context,+bounds: G.Box,out: Output) -> Output:
  match piece:
    case Piece{Plan.Piece{_,_,_,box,_,_,_,_},_}: piece_intersects(G.intersects(box,bounds),piece,start,ctx,bounds,out)

@unsafe
def piece_intersects(inside: Bool,+piece: Piece,+start: Start,+ctx: Context,+bounds: G.Box,out: Output) -> Output:
  match inside piece out:
    case False{} _ _: out
    case True{} Piece{_,parts} Output{world,entities,tags,n,valid}: replay_parts(parts,piece,start,ctx,bounds,Output{world,entities,tags,U32.inc(n),valid})

@unsafe
def replay_parts(xs: List<&2,Part>,+piece: Piece,+start: Start,+ctx: Context,+bounds: G.Box,out: Output) -> Output:
  match xs:
    case Nil{}: out
    case +part <> rest: replay_parts(rest,piece,start,ctx,bounds,replay_part(part,piece,start,ctx,bounds,out))

@unsafe
def replay_part(+part: Part,+piece: Piece,+start: Start,+ctx: Context,+bounds: G.Box,+out: Output) -> Output:
  match part piece start ctx out:
    case Part{id,+template,program,ignore} Piece{Plan.Piece{element,+origin,+rotation,_,_,_,_,_},_} Start{Meta.Start{_,_,_,+seed,_,terrain},_,support} Context{model,env,bk,metadata,ek,_,supports,_,stone} Output{world,_,_,_,_}:
      +block: Bk.Context = Bk.Context{env,metadata,program,origin,rotation,bounds,terrain,M.flag(M.get(model,element,27n)),ignore,seed,stone}
      combined(Bk.place_after(template,block,Supports.apply(support,template,block,supports,world)),
        Entities.place(template,Entities.Context{ek,origin,rotation,bounds,seed,id}),out)

@unsafe
def combined(placed: Bk.Placement,entities: List<&2,M.Node>,out: Output) -> Output:
  match placed out:
    case Bk.Placement{world,tags,_,valid} Output{_,old_entities,old_tags,n,ok}:
      Output{world,List.append(&2,M.Node,List.reverse(&2,M.Node,entities),old_entities),
        Meta.updates(List.reverse(&2,Bk.TagWrite,tags),old_tags),n,Bool.and(valid,ok)}

@unsafe
def plans(xs: List<&2,Start>) -> List<&2,Meta.Start>:
  match xs:
    case Nil{}: []
    case Start{plan,_,_} <> rest: plan <> plans(rest)

@unsafe
def finalized(out: Output,+ctx: Context,xs: List<&2,Start>,+x: U32,+z: U32,repair: Merge.Repair) -> Chunk:
  match out ctx:
    case Output{E.World{+source,_,patch,_},entities,tags,n,valid} Context{model,_,_,_,_,keys,_,_,_}:
      +mc: Meta.Context = Meta.Context{model,keys,x,z,bounds(source,x,z)}
      Chunk{Merge.apply(source,Q.Leaf{patch},repair),Meta.root(mc,Meta.collect(plans(xs),mc,Meta.Records{V.compound(),V.compound()}),List.reverse(&2,M.Node,entities),tags),n,valid}

@unsafe
def cropped(result: Result<&1,&1,U32,MD.Grid>,+ctx: Context,+xs: List<&2,Start>,policy: P.Policy,repair: Merge.Repair,+x: U32,+z: U32) -> Result<&1,&1,U32,Chunk>:
  match result:
    case Fail{code}: Fail{code}
    case Done{+source}: Done{finalized(replay(xs,ctx,bounds(source,x,z),Output{E.World{source,policy,E.Empty{},[]},[],[],0,True{}}),ctx,xs,x,z,repair)}

@unsafe
def chunk(+ctx: Context,+xs: List<&2,Start>,source: MD.Grid,policy: P.Policy,repair: Merge.Repair,+x: U32,+z: U32) -> Result<&1,&1,U32,Chunk>:
  cropped(Merge.crop(source,U32.mul(x,16),U32.mul(z,16),16,16),ctx,xs,policy,repair,x,z)
