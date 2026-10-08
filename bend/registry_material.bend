import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./density.bend as Q
import ./material_program.bend as M

# Material programs immediately follow climate/surface/final-density programs.
# Resolve and validate once on CPU; queries only borrow typed immutable tables.
@unsafe
def count(found: Maybe<&2,P.Value>,+maximum: U32) -> Result<&1,&1,U32,U32>:
  match found:
    case Some{P.Value{6,+n,_,_}}: count_valid(Bool.and(U32.is_gt(n,0),U32.is_le(n,maximum)),n)
    case _: Fail{724}

@unsafe
def count_valid(+ok: Bool,+n: U32) -> Result<&1,&1,U32,U32>:
  match ok:
    case False{}: Fail{724}
    case True{}: Done{n}

@unsafe
def boolean(found: Maybe<&2,P.Value>) -> Result<&1,&1,U32,Bool>:
  match found:
    case Some{P.Value{1,_,_,_}}: Done{False{}}
    case Some{P.Value{2,_,_,_}}: Done{True{}}
    case _: Fail{724}

@unsafe
def valid_bands(xs: List<&2,U32>,+materials: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case band <> rest: Bool.and(U32.is_lt(band,materials),valid_bands(rest,materials))

@unsafe
def complete(-A: Type,result: Result<&1,&1,U32,List<&2,Q.Program>>,doc: P.Document,+model: Q.Model,+bands: List<&2,U32>,+sea: U32,+layered: Bool,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  match result model:
    case Done{+programs} Q.Model{+noises,+points,+fields}: finish(A,M.valid_programs(Q.Model{noises,points,fields},programs,Q.table_size(Q.Field,fields)),doc,programs,bands,sea,layered,next)
    case _ _: next(doc,Fail{724})

@unsafe
def finish(-A: Type,+ok: Bool,doc: P.Document,programs: List<&2,Q.Program>,bands: List<&2,U32>,+sea: U32,+layered: Bool,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{724})
    case True{}: next(doc,Done{M.Model{Q.prepared_table(Q.Program,programs),Q.prepared_table(U32,bands),sea,layered,None{}}})

@unsafe
def skip(-A: Type,n: Nat,doc: P.Document,+at: U32,+count: U32,keys: RD.Keys,+model: Q.Model,bands: List<&2,U32>,+sea: U32,+layered: Bool,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  match n:
    case 0n: RD.programs_read(A,U32.to_nat(count),doc,at,keys,[],doc => result => complete(A,result,doc,model,bands,sea,layered,next))
    case 1n+p: P.get(A,doc,U32.inc(at),doc => span => skip(A,p,doc,U32.add(at,span),count,keys,model,bands,sea,layered,next))

@unsafe
def programs(-A: Type,+ok: Bool,doc: P.Document,value: P.Value,+count: U32,+model: Q.Model,bands: List<&2,U32>,+sea: U32,+layered: Bool,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  match ok value:
    case True{} P.Value{6,_,_,+at}: RD.keys(A,doc,doc => keys => skip(A,3n,doc,U32.add(at,3),count,keys,model,bands,sea,layered,next))
    case _ _: next(doc,Fail{724})

@unsafe
def selected(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+count: U32,+model: Q.Model,bands: List<&2,U32>,+sea: U32,+layered: Bool,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  match found:
    case Some{+value}: programs(A,program_count(value,count),doc,value,count,model,bands,sea,layered,next)
    case _: next(doc,Fail{724})

@unsafe
def program_count(value: P.Value,+count: U32) -> Bool:
  match value:
    case P.Value{6,+n,_,_}: Bool.and(U32.is_ge(n,U32.add(count,3)),U32.is_le(n,65543))
    case _: False{}

@unsafe
def bands_ready(-A: Type,result: Result<&1,&1,U32,List<&2,U32>>,doc: P.Document,+program: P.Value,+count: U32,+materials: U32,+model: Q.Model,+sea: U32,+layered: Bool,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  match result:
    case Done{+bands}: bands_valid(A,valid_bands(bands,materials),doc,program,count,model,bands,sea,layered,next)
    case _: next(doc,Fail{724})

@unsafe
def bands_valid(-A: Type,+ok: Bool,doc: P.Document,program: P.Value,+count: U32,+model: Q.Model,bands: List<&2,U32>,+sea: U32,+layered: Bool,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{724})
    case True{}: V.field(A,doc,program,"programs",doc => found => selected(A,found,doc,count,model,bands,sea,layered,next))

@unsafe
def features(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,program: P.Value,+count: U32,+materials: U32,+model: Q.Model,+sea: U32,+layered: Bool,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  match found:
    case Some{+value}: V.field(A,doc,value,"bands",doc => found => RD.ints(A,doc,found,0,4096,doc => bands => bands_ready(A,bands,doc,program,count,materials,model,sea,layered,next)))
    case _: next(doc,Fail{724})

@unsafe
def metadata(-A: Type,biomes: Result<&1,&1,U32,U32>,materials: Result<&1,&1,U32,U32>,sea: Result<&1,&1,U32,U32>,layered: Result<&1,&1,U32,Bool>,doc: P.Document,root: P.Value,program: P.Value,+model: Q.Model,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  match biomes materials sea layered:
    case Done{+count} Done{+materials} Done{+sea} Done{+layered}:
      V.field(A,doc,root,"terrain_features",doc => found => features(A,found,doc,program,count,materials,model,sea,layered,next))
    case _ _ _ _: next(doc,Fail{724})

@unsafe
def program(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+root: P.Value,biomes: Result<&1,&1,U32,U32>,materials: Result<&1,&1,U32,U32>,sea: Result<&1,&1,U32,U32>,+model: Q.Model,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  match found:
    case Some{+value}: V.field(A,doc,value,"material_layers",doc => found => metadata(A,biomes,materials,sea,boolean(found),doc,root,value,model,next))
    case _: next(doc,Fail{724})

@unsafe
def prepare(-A: Type,doc: P.Document,+model: Q.Model,next: P.Document -> Result<&1,&1,U32,M.Model> -> A) -> A:
  V.root(A,doc,doc => +root => V.field(A,doc,root,"biomes",doc => biomes => V.field(A,doc,root,"materials",doc => materials =>
    V.field(A,doc,root,"sea_level",doc => sea => V.field(A,doc,root,"registry_program",doc => found =>
      program(A,found,doc,root,count(biomes,65535),count(materials,65535),RD.signed(sea),model,next))))))
