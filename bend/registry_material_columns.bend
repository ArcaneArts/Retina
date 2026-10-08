import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./registry_material.bend as RM
import ./density.bend as Q
import ./material_columns_data.bend as MD

# Optional projection for generating blocks; the material-DAG query API remains
# usable without a terrain palette. All IDs/noise references come from the tape.
@unsafe
def ready(+ok: Bool,config: MD.Config) -> Result<&1,&1,U32,MD.Config>:
  match ok:
    case False{}: Fail{726}
    case True{}: Done{config}

@unsafe
def complete(count: Result<&1,&1,U32,U32>,stone: Result<&1,&1,U32,U32>,water: Result<&1,&1,U32,U32>,
    noises: Result<&1,&1,U32,List<&2,U32>>,+noise_count: U32,+air: Bool) -> Result<&1,&1,U32,MD.Config>:
  match count stone water noises:
    case Done{+count} Done{+stone} Done{+water} Done{+a <> +b <> +c <> Nil{}}:
      ready(Bool.and(air,Bool.and(Bool.and(U32.is_gt(stone,0),U32.is_lt(stone,count)),
        Bool.and(Bool.and(U32.is_gt(water,0),U32.is_lt(water,count)),
          Bool.and(U32.is_lt(a,noise_count),Bool.and(U32.is_lt(b,noise_count),U32.is_lt(c,noise_count)))))),MD.Config{stone,water,count,a,b,c})
    case _ _ _ _: Fail{726}

@unsafe
def program(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,count: Result<&1,&1,U32,U32>,stone: Result<&1,&1,U32,U32>,water: Result<&1,&1,U32,U32>,+noise_count: U32,+air: Bool,next: P.Document -> Result<&1,&1,U32,MD.Config> -> A) -> A:
  match found:
    case Some{value}: V.field(A,doc,value,"surface_noises",doc => found =>
      RD.ints(A,doc,found,3,3,doc => result => next(doc,complete(count,stone,water,result,noise_count,air))))
    case _: next(doc,Fail{726})

@unsafe
def metadata(-A: Type,doc: P.Document,+root: P.Value,count: Result<&1,&1,U32,U32>,+noise_count: U32,+air: Bool,next: P.Document -> Result<&1,&1,U32,MD.Config> -> A) -> A:
  V.field(A,doc,root,"stone",doc => stone => V.field(A,doc,root,"water",doc => water =>
    V.field(A,doc,root,"registry_program",doc => found => program(A,found,doc,count,RD.integer(stone),RD.integer(water),noise_count,air,next))))

@unsafe
def first_ref(-A: Type,ref: Maybe<&2,P.StringRef>,doc: P.Document,root: P.Value,count: Result<&1,&1,U32,U32>,noise_count: U32,next: P.Document -> Result<&1,&1,U32,MD.Config> -> A) -> A:
  match ref:
    case Some{P.StringRef{at,13}}: V.text(A,doc,U32.mul(at,2),"minecraft:air",doc => air => metadata(A,doc,root,count,noise_count,air,next))
    case _: next(doc,Fail{726})

@unsafe
def first(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,root: P.Value,count: Result<&1,&1,U32,U32>,noise_count: U32,next: P.Document -> Result<&1,&1,U32,MD.Config> -> A) -> A:
  match found:
    case Some{P.Value{5,id,_,_}}: P.string(A,doc,id,doc => ref => first_ref(A,ref,doc,root,count,noise_count,next))
    case _: next(doc,Fail{726})

@unsafe
def palette(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,root: P.Value,noise_count: U32,next: P.Document -> Result<&1,&1,U32,MD.Config> -> A) -> A:
  match found:
    case Some{+value}: P.item(A,doc,value,0,doc => found => first(A,found,doc,root,RM.count(Some{value},65535),noise_count,next))
    case _: next(doc,Fail{726})

@unsafe
def prepare(-A: Type,doc: P.Document,+model: Q.Model,next: P.Document -> Result<&1,&1,U32,MD.Config> -> A) -> A:
  match model:
    case Q.Model{noises,_,_}: V.root(A,doc,doc => +root => V.field(A,doc,root,"materials",doc => found =>
      palette(A,found,doc,root,Q.table_size(Q.Noise,noises),next)))
