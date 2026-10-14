import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./registry_material.bend as RM
import ./registry_cave_features.bend as Arrays
import ./density.bend as Q
import ./snow.bend as Snow
import ./precise.bend as D

# Loaded snow feature flags, precipitation, temperatures and support predicates.
# Resolve schema keys on the CPU; GPU kernels only receive typed immutable data.
@unsafe
def finish(-A: Type,ok: Bool,doc: P.Document,model: Snow.Model,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{777})
    case True{}: next(doc,Done{model})

@unsafe
def biome_result(-A: Type,t: Result<&1,&1,U32,F32>,flags: Result<&1,&1,U32,U32>,surface: Result<&1,&1,U32,U32>,
    n: U32,at: U32,doc: P.Document,keys: RD.Keys,acc: List<&2,Snow.Biome>,model: Snow.Model,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  match t flags surface:
    case Done{+t} Done{flags} Done{surface}: biome_valid(A,D.finite(t),Snow.Biome{t,flags,surface},n,at,doc,keys,acc,model,next)
    case _ _ _: next(doc,Fail{777})

@unsafe
def biome_valid(-A: Type,ok: Bool,biome: Snow.Biome,n: U32,at: U32,doc: P.Document,keys: RD.Keys,acc: List<&2,Snow.Biome>,model: Snow.Model,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{777})
    case True{}: biomes(A,n,at,doc,keys,biome <> acc,model,next)

@unsafe
def biome(-A: Type,+value: P.Value,n: U32,at: U32,doc: P.Document,+keys: RD.Keys,acc: List<&2,Snow.Biome>,model: Snow.Model,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  match value:
    case P.Value{7,_,_,_}:
      RD.named(A,doc,value,keys,0n,doc => t => RD.named(A,doc,value,keys,1n,doc => flags => RD.named(A,doc,value,keys,2n,doc => surface =>
        biome_result(A,RD.default_number(t,0.0),Arrays.uint(flags),Arrays.boolean(surface),n,at,doc,keys,acc,model,next))))
    case _: next(doc,Fail{777})

@unsafe
def biomes(-A: Type,n: U32,+at: U32,doc: P.Document,+keys: RD.Keys,acc: List<&2,Snow.Biome>,model: Snow.Model,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  match n model:
    case 0 Snow.Model{sea,water,ice,lava,layer,palette,masks,supports,_}:
      +result : Snow.Model = Snow.Model{sea,water,ice,lava,layer,palette,masks,supports,Q.prepared_table(Snow.Biome,List.reverse(&2,Snow.Biome,acc))}
      finish(A,Snow.valid(result),doc,result,next)
    case _ _:
      P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span => biome(A,value,U32.sub(n,1),U32.add(at,span),doc,keys,acc,model,next)))

@unsafe
def found_biomes(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,keys: RD.Keys,model: Snow.Model,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  match found:
    case Some{P.Value{6,n,_,at}}: biomes(A,n,U32.add(at,3),doc,keys,[],model,next)
    case _: next(doc,Fail{777})

@unsafe
def arrays_ready(-A: Type,masks: Result<&1,&1,U32,Q.Table<U32>>,supports: Result<&1,&1,U32,Q.Table<U32>>,doc: P.Document,root: P.Value,+keys: RD.Keys,model: Snow.Model,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  match masks supports model:
    case Done{masks} Done{supports} Snow.Model{sea,water,ice,lava,layer,palette,_,_,_}:
      RD.named(A,doc,root,keys,12n,doc => found => found_biomes(A,found,doc,keys,Snow.Model{sea,water,ice,lava,layer,palette,masks,supports,Q.Empty{}},next))
    case _ _ _: next(doc,Fail{777})

@unsafe
def arrays(-A: Type,doc: P.Document,+root: P.Value,+keys: RD.Keys,model: Snow.Model,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  RD.named(A,doc,root,keys,9n,doc => masks => Arrays.array(A,masks,doc,0,doc => masks =>
    RD.named(A,doc,root,keys,10n,doc => supports => Arrays.array(A,supports,doc,1,doc => supports =>
      arrays_ready(A,masks,supports,doc,root,keys,model,next)))))

@unsafe
def configured(-A: Type,sea: Result<&1,&1,U32,U32>,ids: Result<&1,&1,U32,List<&2,U32>>,layer: Result<&1,&1,U32,U32>,palette: Result<&1,&1,U32,U32>,doc: P.Document,root: P.Value,keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  match sea ids layer palette:
    case Done{sea} Done{water <> ice <> lava <> Nil{}} Done{layer} Done{palette}:
      arrays(A,doc,root,keys,Snow.Model{sea,water,ice,lava,layer,palette,Q.Empty{},Q.Empty{},Q.Empty{}},next)
    case _ _ _ _: next(doc,Fail{777})

@unsafe
def feature(-A: Type,found: Maybe<&2,P.Value>,sea: Result<&1,&1,U32,U32>,ids: Result<&1,&1,U32,List<&2,U32>>,palette: Result<&1,&1,U32,U32>,doc: P.Document,root: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  match found:
    case None{}: configured(A,sea,ids,Done{0},palette,doc,root,keys,next)
    case Some{P.Value{7,n,b,at}}: RD.named(A,doc,P.Value{7,n,b,at},keys,8n,doc => layer => configured(A,sea,ids,Arrays.uint(layer),palette,doc,root,keys,next))
    case _: next(doc,Fail{777})

@unsafe
def metadata(-A: Type,doc: P.Document,+root: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  RD.named(A,doc,root,keys,11n,doc => materials => RD.named(A,doc,root,keys,3n,doc => sea =>
    Arrays.words(A,[4,5,6],doc,root,keys,[],doc => ids => RD.named(A,doc,root,keys,7n,doc => found =>
      feature(A,found,RD.signed(sea),ids,RM.count(materials,65535),doc,root,keys,next)))))

@unsafe
def prepare(-A: Type,doc: P.Document,next: P.Document -> Result<&1,&1,U32,Snow.Model> -> A) -> A:
  RD.resolved(A,["temperature","flags","snow_surface","sea_level","water","ice","lava","terrain_features","snow_layer","heightmap_masks","snow_support","materials","biomes"],doc,[],doc => keys =>
    V.root(A,doc,doc => root => metadata(A,doc,root,keys,next)))
