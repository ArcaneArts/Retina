import Base
import ./registry.bend as R
import ./registry_values.bend as V
import ./registry_density.bend as D
import ./registry_cave_features.bend as A
import ./registry_material.bend as RM
import ./placement_data.bend as P
import ./density.bend as Q

@unsafe
def values_valid(+values: Q.Table<U32>,+maximum: U32) -> Bool:
  match values:
    case Q.Empty{}: True{}
    case Q.Leaf{x}: U32.is_le(x,maximum)
    case Q.Branch{_,a,b}: Bool.and(values_valid(a,maximum),values_valid(b,maximum))

@unsafe
def memberships_valid(+biomes: Q.Table<Q.Table<U32>>,+recipes: U32) -> Bool:
  match biomes:
    case Q.Empty{}: True{}
    case Q.Leaf{+xs}: membership_values(U32.is_ne(recipes,0),xs,recipes)
    case Q.Branch{_,a,b}: Bool.and(memberships_valid(a,recipes),memberships_valid(b,recipes))

@unsafe
def membership_values(+nonempty: Bool,+xs: Q.Table<U32>,+recipes: U32) -> Bool:
  match nonempty:
    case True{}: values_valid(xs,U32.sub(recipes,1))
    case False{}: U32.is_zero(Q.table_size(U32,xs))

@unsafe
def assembled(-T: Type,+valid: Bool,+policy: P.Policy,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Policy> -> T) -> T:
  match valid:
    case False{}: next(doc,Fail{761})
    case True{}: next(doc,Done{policy})

@unsafe
def model(-T: Type,masks: Result<&1,&1,U32,Q.Table<U32>>,flags: Result<&1,&1,U32,Q.Table<U32>>,biomes: Result<&1,&1,U32,Q.Table<Q.Table<U32>>>,water: Result<&1,&1,U32,U32>,lava: Result<&1,&1,U32,U32>,bedrock: Result<&1,&1,U32,U32>,volume: Result<&1,&1,U32,Bool>,+palette: U32,+recipes: U32,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Policy> -> T) -> T:
  match masks flags biomes water lava bedrock volume:
    case Done{+m} Done{+f} Done{+b} Done{+w} Done{+l} Done{+k} Done{volume}:
      assembled(T,Bool.and(Bool.and(U32.is_eq(Q.table_size(U32,m),palette),U32.is_eq(Q.table_size(U32,f),palette)),Bool.and(
        Bool.and(values_valid(m,63),values_valid(f,255)),Bool.and(memberships_valid(b,recipes),Bool.and(U32.is_lt(w,palette),Bool.and(U32.is_lt(l,palette),U32.is_lt(k,palette)))))),P.Policy{m,f,b,w,l,k,volume},doc,next)
    case _ _ _ _ _ _ _: next(doc,Fail{761})

def volume(found: Maybe<&2,R.Value>) -> Result<&1,&1,U32,Bool>:
  match found:
    case None{}: Done{False{}}
    case Some{x}: RM.boolean(Some{x})

@unsafe
def biomes(-T: Type,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,acc: List<&2,Q.Table<U32>>,next: R.Document -> Result<&1,&1,U32,Q.Table<Q.Table<U32>>> -> T) -> T:
  match n:
    case 0: next(doc,Done{Q.prepared_table(Q.Table<U32>,List.reverse(&2,Q.Table<U32>,acc))})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span => D.named(T,doc,value,keys,3n,doc => found => A.array(T,found,doc,0,doc => result =>
      biome(T,result,U32.sub(n,1),U32.add(at,span),doc,keys,acc,next)))))

@unsafe
def biome(-T: Type,result: Result<&1,&1,U32,Q.Table<U32>>,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,acc: List<&2,Q.Table<U32>>,next: R.Document -> Result<&1,&1,U32,Q.Table<Q.Table<U32>>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{761})
    case Done{xs}: biomes(T,n,at,doc,keys,xs <> acc,next)

@unsafe
def biome_array(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,Q.Table<Q.Table<U32>>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: biomes(T,n,U32.add(at,3),doc,keys,[],next)
    case _: next(doc,Fail{761})

@unsafe
def prepare(-T: Type,doc: R.Document,+root: R.Value,+palette: U32,+recipes: U32,next: R.Document -> Result<&1,&1,U32,P.Policy> -> T) -> T:
  D.resolved(T,["heightmap_masks","material_flags","biomes","decorations","water","lava","bedrock","decoration_biome_3d"],doc,[],doc => +keys =>
    D.named(T,doc,root,keys,0n,doc => found => A.array(T,found,doc,0,doc => masks =>
    D.named(T,doc,root,keys,1n,doc => found => A.array(T,found,doc,0,doc => flags =>
    D.named(T,doc,root,keys,2n,doc => found => biome_array(T,found,doc,keys,doc => biomes =>
    D.named(T,doc,root,keys,4n,doc => water => D.named(T,doc,root,keys,5n,doc => lava => D.named(T,doc,root,keys,6n,doc => bedrock =>
    D.named(T,doc,root,keys,7n,doc => v => model(T,masks,flags,biomes,D.integer(water),D.integer(lava),D.integer(bedrock),volume(v),palette,recipes,doc,next))))))))))))
