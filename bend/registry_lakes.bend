import Base
import ./registry.bend as P
import ./registry_density.bend as RD
import ./lake_data.bend as L

# Projection shares the geology key dictionary. Effects/tape traversal remain
# on the host; the immutable result can be consumed by either Bend backend.
@unsafe
def optional(found: Maybe<&2,P.Value>) -> Result<&1,&1,U32,U32>:
  match found:
    case None{}: Done{0}
    case Some{value}: RD.integer(Some{value})

@unsafe
def chances(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match found:
    case None{}: next(doc,Done{[0.0,0.0]})
    case Some{value}: RD.floats(A,doc,Some{value},2,2,next)

@unsafe
def complete(-A: Type,chances: Result<&1,&1,U32,List<&2,F32>>,barrier: Result<&1,&1,U32,U32>,water: Result<&1,&1,U32,U32>,flags: Result<&1,&1,U32,U32>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,L.Params> -> A) -> A:
  match chances barrier water flags:
    case Done{total <> lava <> Nil{}} Done{barrier} Done{water} Done{flags}: next(doc,Done{L.Params{total,lava,barrier,water,flags}})
    case _ _ _ _: next(doc,Fail{736})

@unsafe
def biome(-A: Type,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,L.Params> -> A) -> A:
  RD.named(A,doc,value,keys,33n,doc => found => chances(A,found,doc,doc => chances =>
    RD.named(A,doc,value,keys,34n,doc => barrier => RD.named(A,doc,value,keys,35n,doc => water =>
      RD.named(A,doc,value,keys,36n,doc => flags => complete(A,chances,optional(barrier),optional(water),optional(flags),doc,next))))))
