import Base
import ./structure_replay.bend as Replay
import ./structure_metadata.bend as Meta
import ./registry.bend as R
import ./generated_chunk.bend as G
import ./chunk_catalog.bend as CC
import ./chunk.bend as C
import ./compression.bend as Z

# Serialize with the resident registry dictionary before releasing its tape.
# Invalid replay never yields publishable chunk bytes, even after partial writes.
@unsafe
def encode(-A: Type,result: Result<&1,&1,U32,Replay.Chunk>,doc: R.Document,catalog: CC.Catalog,request: G.Request,next: R.Document -> Result<&1,&1,U32,Z.Bytes> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{Replay.Chunk{_,_,_,False{}}}: next(doc,Fail{871})
    case Done{Replay.Chunk{grid,metadata,_,True{}}}:
      Meta.encode(A,doc,metadata,doc => fields => next(doc,G.encode_metadata(grid,catalog,request,C.EncodedMetadata{fields})))
