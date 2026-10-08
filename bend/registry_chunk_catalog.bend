import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./density.bend as Q
import ./material.bend as M
import ./chunk_catalog.bend as C

# Projection uses sequential tape visitors rather than repeatedly walking the
# beginning of large material/biome arrays. Strings retain UTF-16 code units;
# the existing Bend NBT writer emits their exact Java modified-UTF8 bytes.
@unsafe
def units(-A: Type,+n: U32,doc: P.Document,+at: U32,acc: String,next: P.Document -> Result<&1,&1,U32,String> -> A) -> A:
  match n:
    case 0: next(doc,Done{acc})
    case _: V.character(A,doc,U32.add(at,U32.sub(n,1)),doc => unit => units(A,U32.sub(n,1),doc,at,SCon{Chr{unit},acc},next))

@unsafe
def text_ref(-A: Type,ref: Maybe<&2,P.StringRef>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,String> -> A) -> A:
  match ref:
    case Some{P.StringRef{at,+length}}: text_length(A,U32.is_le(length,65535),doc,at,length,next)
    case _: next(doc,Fail{730})

@unsafe
def text_length(-A: Type,ok: Bool,doc: P.Document,at: U32,length: U32,next: P.Document -> Result<&1,&1,U32,String> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{730})
    case True{}: units(A,length,doc,U32.mul(at,2),"",next)

@unsafe
def text(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,String> -> A) -> A:
  match found:
    case Some{P.Value{5,id,_,_}}: P.string(A,doc,id,doc => ref => text_ref(A,ref,doc,next))
    case _: next(doc,Fail{730})

@unsafe
def named_text(-A: Type,doc: P.Document,value: P.Value,keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,String> -> A) -> A:
  RD.named(A,doc,value,keys,3n,doc => found => text(A,found,doc,next))

@unsafe
def property_value(-A: Type,result: Result<&1,&1,U32,String>,name: String,n: U32,doc: P.Document,at: U32,acc: List<&2,M.Property>,next: P.Document -> Result<&1,&1,U32,List<&2,M.Property>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{730})
    case Done{value}: property_fields(A,n,doc,at,M.Property{name,value} <> acc,next)

@unsafe
def property_name(-A: Type,result: Result<&1,&1,U32,String>,found: P.Value,n: U32,doc: P.Document,at: U32,acc: List<&2,M.Property>,next: P.Document -> Result<&1,&1,U32,List<&2,M.Property>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{730})
    case Done{name}: text(A,Some{found},doc,doc => result => property_value(A,result,name,n,doc,at,acc,next))

@unsafe
def property_fields(-A: Type,+n: U32,doc: P.Document,+at: U32,acc: List<&2,M.Property>,next: P.Document -> Result<&1,&1,U32,List<&2,M.Property>> -> A) -> A:
  match n:
    case 0: next(doc,Done{List.reverse(&2,M.Property,acc)})
    case _: P.get(A,doc,at,doc => key => P.value(A,doc,U32.inc(at),doc => found =>
      P.get(A,doc,U32.add(at,2),doc => span => P.string(A,doc,key,doc => ref => text_ref(A,ref,doc,
        doc => result => property_name(A,result,found,U32.sub(n,1),doc,U32.add(U32.inc(at),span),acc,next))))))

@unsafe
def properties(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,List<&2,M.Property>> -> A) -> A:
  match found:
    case None{}: next(doc,Done{[]})
    case Some{P.Value{7,count,_,at}}: property_fields(A,count,doc,U32.add(at,3),[],next)
    case _: next(doc,Fail{730})

@unsafe
def state_properties(-A: Type,result: Result<&1,&1,U32,List<&2,M.Property>>,id: String,doc: P.Document,next: P.Document -> Result<&1,&1,U32,M.State> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{730})
    case Done{properties}: next(doc,Done{M.State{id,properties}})

@unsafe
def state_name(-A: Type,result: Result<&1,&1,U32,String>,doc: P.Document,value: P.Value,keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,M.State> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{730})
    case Done{id}: RD.named(A,doc,value,keys,4n,doc => found => properties(A,found,doc,
      doc => result => state_properties(A,result,id,doc,next)))

@unsafe
def plain_state(-A: Type,result: Result<&1,&1,U32,String>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,M.State> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{730})
    case Done{id}: next(doc,Done{M.State{id,[]}})

@unsafe
def state(-A: Type,+value: P.Value,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,M.State> -> A) -> A:
  match value:
    case P.Value{5,_,_,_}: text(A,Some{value},doc,doc => result => plain_state(A,result,doc,next))
    case P.Value{7,_,_,_}: named_text(A,doc,value,keys,doc => result => state_name(A,result,doc,value,keys,next))
    case _: next(doc,Fail{730})

@unsafe
def material_result(-A: Type,result: Result<&1,&1,U32,M.State>,+mask: U32,n: U32,doc: P.Document,at: U32,keys: RD.Keys,masks: List<&2,U32>,acc: List<&2,M.Material>,next: P.Document -> Result<&1,&1,U32,Q.Table<M.Material>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{730})
    case Done{value}: material_mask(A,U32.is_le(mask,63),value,mask,n,doc,at,keys,masks,acc,next)

@unsafe
def material_mask(-A: Type,ok: Bool,value: M.State,mask: U32,n: U32,doc: P.Document,at: U32,keys: RD.Keys,masks: List<&2,U32>,acc: List<&2,M.Material>,next: P.Document -> Result<&1,&1,U32,Q.Table<M.Material>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{730})
    case True{}: materials(A,n,doc,at,keys,masks,M.Material{value,mask} <> acc,next)

@unsafe
def materials(-A: Type,+n: U32,doc: P.Document,+at: U32,+keys: RD.Keys,masks: List<&2,U32>,acc: List<&2,M.Material>,next: P.Document -> Result<&1,&1,U32,Q.Table<M.Material>> -> A) -> A:
  match n masks:
    case 0 Nil{}: next(doc,Done{Q.prepared_table(M.Material,List.reverse(&2,M.Material,acc))})
    case _ mask <> rest: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      state(A,value,doc,keys,doc => result => material_result(A,result,mask,U32.sub(n,1),doc,U32.add(at,span),keys,rest,acc,next))))
    case _ _: next(doc,Fail{730})

@unsafe
def biome_result(-A: Type,result: Result<&1,&1,U32,String>,n: U32,doc: P.Document,at: U32,keys: RD.Keys,acc: List<&2,String>,next: P.Document -> Result<&1,&1,U32,Q.Table<String>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{730})
    case Done{id}: biomes(A,n,doc,at,keys,id <> acc,next)

@unsafe
def biomes(-A: Type,+n: U32,doc: P.Document,+at: U32,+keys: RD.Keys,acc: List<&2,String>,next: P.Document -> Result<&1,&1,U32,Q.Table<String>> -> A) -> A:
  match n:
    case 0: next(doc,Done{Q.prepared_table(String,List.reverse(&2,String,acc))})
    case _: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      named_text(A,doc,value,keys,doc => result => biome_result(A,result,U32.sub(n,1),doc,U32.add(at,span),keys,acc,next))))

@unsafe
def complete(-A: Type,result: Result<&1,&1,U32,Q.Table<String>>,materials: Q.Table<M.Material>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,C.Catalog> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{730})
    case Done{biomes}: next(doc,Done{C.Catalog{materials,biomes}})

@unsafe
def biome_count(-A: Type,ok: Bool,doc: P.Document,at: U32,count: U32,keys: RD.Keys,materials: Q.Table<M.Material>,next: P.Document -> Result<&1,&1,U32,C.Catalog> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{730})
    case True{}: biomes(A,count,doc,U32.add(at,3),keys,[],doc => result => complete(A,result,materials,doc,next))

@unsafe
def biome_array(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,keys: RD.Keys,materials: Q.Table<M.Material>,next: P.Document -> Result<&1,&1,U32,C.Catalog> -> A) -> A:
  match found:
    case Some{P.Value{6,+count,_,at}}: biome_count(A,Bool.and(U32.is_gt(count,0),U32.is_le(count,65535)),doc,at,count,keys,materials,next)
    case _: next(doc,Fail{730})

@unsafe
def material_table(-A: Type,result: Result<&1,&1,U32,Q.Table<M.Material>>,doc: P.Document,root: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,C.Catalog> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{730})
    case Done{materials}: RD.named(A,doc,root,keys,1n,doc => found => biome_array(A,found,doc,keys,materials,next))

@unsafe
def mask_list(-A: Type,result: Result<&1,&1,U32,List<&2,U32>>,doc: P.Document,root: P.Value,count: U32,at: U32,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,C.Catalog> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{730})
    case Done{masks}: materials(A,count,doc,U32.add(at,3),keys,masks,[],doc => result => material_table(A,result,doc,root,keys,next))

@unsafe
def material_count(-A: Type,ok: Bool,doc: P.Document,+root: P.Value,+count: U32,at: U32,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,C.Catalog> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{730})
    case True{}: RD.named(A,doc,root,keys,2n,doc => found => RD.ints(A,doc,found,count,count,
      doc => result => mask_list(A,result,doc,root,count,at,keys,next)))

@unsafe
def material_array(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,root: P.Value,keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,C.Catalog> -> A) -> A:
  match found:
    case Some{P.Value{6,+count,_,at}}: material_count(A,Bool.and(U32.is_gt(count,0),U32.is_le(count,65535)),doc,root,count,at,keys,next)
    case _: next(doc,Fail{730})

@unsafe
def prepare(-A: Type,doc: P.Document,next: P.Document -> Result<&1,&1,U32,C.Catalog> -> A) -> A:
  RD.resolved(A,["materials","biomes","heightmap_masks","id","properties"],doc,[],doc => +keys =>
    V.root(A,doc,doc => +root => RD.named(A,doc,root,keys,0n,doc => found => material_array(A,found,doc,root,keys,next))))
