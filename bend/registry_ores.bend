import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./registry_material.bend as RM
import ./precise.bend as D
import ./ore_data.bend as O

# Projection runs in Bend over the shared raw registry tape. No host language
# chooses ore recipes, height distributions, membership or replacement maps.
@unsafe
def valid(-A: Type,-T: Data,ok: Bool,value: T,doc: P.Document,next: P.Document -> Result<&1,&1,U32,T> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{753})
    case True{}: next(doc,Done{value})

# Array walkers retain only Data parameters across elements. A linear parser
# closure cannot be duplicated with +parse in the stock Bend runtime.
@unsafe
def recipes(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,keys: RD.Keys,+palette: U32,+low: U32,+high: U32,next: P.Document -> Result<&1,&1,U32,List<&2,O.Recipe>> -> A) -> A:
  match found:
    case None{}: recipes_room(A,U32.is_zero(low),0,doc,0,keys,palette,next)
    case Some{P.Value{6,+n,_,at}}: recipes_room(A,Bool.and(U32.is_ge(n,low),U32.is_le(n,high)),n,doc,U32.add(at,3),keys,palette,next)
    case _: next(doc,Fail{753})

@unsafe
def recipes_room(-A: Type,ok: Bool,n: U32,doc: P.Document,at: U32,keys: RD.Keys,palette: U32,next: P.Document -> Result<&1,&1,U32,List<&2,O.Recipe>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{753})
    case True{}: recipes_read(A,n,doc,at,keys,palette,[],next)

@unsafe
def recipes_read(-A: Type,n: U32,doc: P.Document,+at: U32,+keys: RD.Keys,+palette: U32,acc: List<&2,O.Recipe>,next: P.Document -> Result<&1,&1,U32,List<&2,O.Recipe>> -> A) -> A:
  match n:
    case 0: next(doc,Done{List.reverse(&2,O.Recipe,acc)})
    case _: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      recipe(A,doc,value,keys,palette,doc => result => recipes_result(A,result,U32.sub(n,1),doc,U32.add(at,span),keys,palette,acc,next))))

@unsafe
def recipes_result(-A: Type,result: Result<&1,&1,U32,O.Recipe>,n: U32,doc: P.Document,at: U32,keys: RD.Keys,palette: U32,acc: List<&2,O.Recipe>,next: P.Document -> Result<&1,&1,U32,List<&2,O.Recipe>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{753})
    case Done{value}: recipes_read(A,n,doc,at,keys,palette,value <> acc,next)

@unsafe
def bands(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,keys: RD.Keys,+palette: U32,+low: U32,+high: U32,next: P.Document -> Result<&1,&1,U32,List<&2,O.Band>> -> A) -> A:
  match found:
    case None{}: bands_room(A,U32.is_zero(low),0,doc,0,keys,palette,next)
    case Some{P.Value{6,+n,_,at}}: bands_room(A,Bool.and(U32.is_ge(n,low),U32.is_le(n,high)),n,doc,U32.add(at,3),keys,palette,next)
    case _: next(doc,Fail{753})

@unsafe
def bands_room(-A: Type,ok: Bool,n: U32,doc: P.Document,at: U32,keys: RD.Keys,palette: U32,next: P.Document -> Result<&1,&1,U32,List<&2,O.Band>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{753})
    case True{}: bands_read(A,n,doc,at,keys,palette,[],next)

@unsafe
def bands_read(-A: Type,n: U32,doc: P.Document,+at: U32,+keys: RD.Keys,+palette: U32,acc: List<&2,O.Band>,next: P.Document -> Result<&1,&1,U32,List<&2,O.Band>> -> A) -> A:
  match n:
    case 0: next(doc,Done{List.reverse(&2,O.Band,acc)})
    case _: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      band(A,doc,value,keys,palette,doc => result => bands_result(A,result,U32.sub(n,1),doc,U32.add(at,span),keys,palette,acc,next))))

@unsafe
def bands_result(-A: Type,result: Result<&1,&1,U32,O.Band>,n: U32,doc: P.Document,at: U32,keys: RD.Keys,palette: U32,acc: List<&2,O.Band>,next: P.Document -> Result<&1,&1,U32,List<&2,O.Band>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{753})
    case Done{value}: bands_read(A,n,doc,at,keys,palette,value <> acc,next)

@unsafe
def memberships(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,keys: RD.Keys,+count: U32,+low: U32,+high: U32,next: P.Document -> Result<&1,&1,U32,List<&2,List<&2,U32>>> -> A) -> A:
  match found:
    case None{}: memberships_room(A,U32.is_zero(low),0,doc,0,keys,count,next)
    case Some{P.Value{6,+n,_,at}}: memberships_room(A,Bool.and(U32.is_ge(n,low),U32.is_le(n,high)),n,doc,U32.add(at,3),keys,count,next)
    case _: next(doc,Fail{753})

@unsafe
def memberships_room(-A: Type,ok: Bool,n: U32,doc: P.Document,at: U32,keys: RD.Keys,count: U32,next: P.Document -> Result<&1,&1,U32,List<&2,List<&2,U32>>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{753})
    case True{}: memberships_read(A,n,doc,at,keys,count,[],next)

@unsafe
def memberships_read(-A: Type,n: U32,doc: P.Document,+at: U32,+keys: RD.Keys,+count: U32,acc: List<&2,List<&2,U32>>,next: P.Document -> Result<&1,&1,U32,List<&2,List<&2,U32>>> -> A) -> A:
  match n:
    case 0: next(doc,Done{List.reverse(&2,List<&2,U32>,acc)})
    case _: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      members(A,doc,value,keys,count,doc => result => memberships_result(A,result,U32.sub(n,1),doc,U32.add(at,span),keys,count,acc,next))))

@unsafe
def memberships_result(-A: Type,result: Result<&1,&1,U32,List<&2,U32>>,n: U32,doc: P.Document,at: U32,keys: RD.Keys,count: U32,acc: List<&2,List<&2,U32>>,next: P.Document -> Result<&1,&1,U32,List<&2,List<&2,U32>>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{753})
    case Done{value}: memberships_read(A,n,doc,at,keys,count,value <> acc,next)

@unsafe
def id(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,U32> -> A) -> A:
  match found:
    case Some{P.Value{5,+i,_,_}}: P.string(A,doc,i,doc => ref => id_ref(A,ref,i,doc,next))
    case _: next(doc,Fail{753})

@unsafe
def id_ref(-A: Type,ref: Maybe<&2,P.StringRef>,i: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,U32> -> A) -> A:
  match ref:
    case Some{P.StringRef{_,n}}: valid(A,U32,U32.is_gt(n,0),i,doc,next)
    case _: next(doc,Fail{753})

@unsafe
def height_values(-A: Type,a: Result<&1,&1,U32,U32>,b: Result<&1,&1,U32,U32>,c: Result<&1,&1,U32,Bool>,d: Result<&1,&1,U32,U32>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match a b c d:
    case Done{+a} Done{+b} Done{c} Done{+d}: valid(A,List<&2,U32>,Bool.and(Bool.not(D.less(D.integer(b),D.integer(a))),U32.is_le(d,2147483647)),[a,b,bool(c),d],doc,next)
    case _ _ _ _: next(doc,Fail{753})

def bool(value: Bool) -> U32:
  match value:
    case False{}: 0
    case True{}: 1

@unsafe
def height(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match found:
    case Some{+v}: RD.named(A,doc,v,keys,10n,doc => a => RD.named(A,doc,v,keys,11n,doc => b =>
      RD.named(A,doc,v,keys,12n,doc => c => RD.named(A,doc,v,keys,13n,doc => d =>
        height_values(A,RD.signed(a),RD.signed(b),RM.boolean(c),RD.integer(d),doc,next)))))
    case _: next(doc,Fail{753})

@unsafe
def band_values(-A: Type,a: Result<&1,&1,U32,U32>,b: Result<&1,&1,U32,U32>,materials: Result<&1,&1,U32,List<&2,U32>>,+palette: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,O.Band> -> A) -> A:
  match a b materials:
    case Done{+a} Done{+b} Done{+xs}: valid(A,O.Band,Bool.and(Bool.not(D.less(D.integer(b),D.integer(a))),RM.valid_bands(xs,palette)),O.Band{a,b,xs},doc,next)
    case _ _ _: next(doc,Fail{753})

@unsafe
def band(-A: Type,doc: P.Document,+value: P.Value,+keys: RD.Keys,+palette: U32,next: P.Document -> Result<&1,&1,U32,O.Band> -> A) -> A:
  RD.named(A,doc,value,keys,10n,doc => a => RD.named(A,doc,value,keys,11n,doc => b =>
    RD.named(A,doc,value,keys,15n,doc => found => RD.ints(A,doc,found,palette,palette,doc => xs =>
      band_values(A,RD.signed(a),RD.signed(b),xs,palette,doc,next)))))

@unsafe
def recipe_values(-A: Type,id: Result<&1,&1,U32,U32>,fields: Result<&1,&1,U32,List<&2,U32>>,discard: Result<&1,&1,U32,F32>,scattered: Result<&1,&1,U32,Bool>,height: Result<&1,&1,U32,List<&2,U32>>,bands: Result<&1,&1,U32,List<&2,O.Band>>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,O.Recipe> -> A) -> A:
  match id fields discard scattered height bands:
    case Done{i} Done{+size <> +low <> +high <> +rarity <> Nil{}} Done{+discard} Done{scattered} Done{h} Done{bands}:
      valid(A,O.Recipe,Bool.and(Bool.and(U32.is_le(size,64),Bool.and(U32.is_le(low,high),U32.is_le(high,1024))),
        Bool.and(U32.is_gt(rarity,0),Bool.and(D.finite(discard),Bool.and(F32.is_ge(discard,0.0),F32.is_le(discard,1.0))))),
        O.Recipe{i <> size <> F32.bits(discard) <> bool(scattered) <> low <> high <> rarity <> h,bands},doc,next)
    case _ _ _ _ _ _: next(doc,Fail{753})

@unsafe
def recipe(-A: Type,doc: P.Document,+value: P.Value,+keys: RD.Keys,+palette: U32,next: P.Document -> Result<&1,&1,U32,O.Recipe> -> A) -> A:
  RD.named(A,doc,value,keys,3n,doc => found => id(A,found,doc,doc => id =>
    RD.int_fields(A,[4,7,8,9],doc,value,keys,[],doc => fields => RD.named(A,doc,value,keys,5n,doc => discard =>
      RD.named(A,doc,value,keys,6n,doc => scattered => RD.named(A,doc,value,keys,16n,doc => found => height(A,found,doc,keys,doc => h =>
        RD.named(A,doc,value,keys,14n,doc => found => bands(A,found,doc,keys,palette,1,4096,doc => bands =>
            recipe_values(A,id,fields,RD.number(discard),RM.boolean(scattered),h,bands,doc,next))))))))))

@unsafe
def members(-A: Type,doc: P.Document,value: P.Value,keys: RD.Keys,+count: U32,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  RD.named(A,doc,value,keys,0n,doc => found => members_found(A,found,doc,count,next))

@unsafe
def members_found(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,count: U32,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match found:
    case None{}: next(doc,Done{[]})
    case Some{v}: RD.ints(A,doc,Some{v},0,4194304,doc => ids => members_values(A,ids,doc,count,next))

@unsafe
def members_values(-A: Type,ids: Result<&1,&1,U32,List<&2,U32>>,doc: P.Document,count: U32,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match ids:
    case Fail{_}: next(doc,Fail{753})
    case Done{+ids}: valid(A,List<&2,U32>,RM.valid_bands(ids,count),ids,doc,next)

@unsafe
def complete(-A: Type,result: Result<&1,&1,U32,List<&2,List<&2,U32>>>,doc: P.Document,layout: U32,recipes: List<&2,O.Recipe>,palette: U32,next: P.Document -> Result<&1,&1,U32,O.Store> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{753})
    case Done{members}: next(doc,O.prepare(layout,recipes,members,palette))

@unsafe
def selected(-A: Type,result: Result<&1,&1,U32,List<&2,O.Recipe>>,doc: P.Document,+root: P.Value,+keys: RD.Keys,layout: U32,palette: U32,+biomes: U32,next: P.Document -> Result<&1,&1,U32,O.Store> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{753})
    case Done{+recipes}: RD.named(A,doc,root,keys,2n,doc => found => memberships(A,found,doc,keys,U32.from_nat(List.length(&2,O.Recipe,recipes)),biomes,biomes,doc => result => complete(A,result,doc,layout,recipes,palette,next)))

@unsafe
def layout_value(found: Maybe<&2,P.Value>) -> Result<&1,&1,U32,U32>:
  match found:
    case None{}: Done{2}
    case Some{v}: RD.integer(Some{v})

@unsafe
def layout_ready(-A: Type,result: Result<&1,&1,U32,U32>,doc: P.Document,+root: P.Value,+keys: RD.Keys,palette: U32,biomes: U32,next: P.Document -> Result<&1,&1,U32,O.Store> -> A) -> A:
  match result:
    case Done{+layout}: layout_valid(A,Bool.and(U32.is_ge(layout,1),U32.is_le(layout,2)),layout,doc,root,keys,palette,biomes,next)
    case _: next(doc,Fail{753})

@unsafe
def layout_valid(-A: Type,ok: Bool,layout: U32,doc: P.Document,+root: P.Value,+keys: RD.Keys,+palette: U32,biomes: U32,next: P.Document -> Result<&1,&1,U32,O.Store> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{753})
    case True{}: RD.named(A,doc,root,keys,0n,doc => found => recipes(A,found,doc,keys,palette,0,4096,doc => result => selected(A,result,doc,root,keys,layout,palette,biomes,next)))

@unsafe
def prepare(-A: Type,doc: P.Document,+palette: U32,+biomes: U32,next: P.Document -> Result<&1,&1,U32,O.Store> -> A) -> A:
  RD.resolved(A,["ores","ore_layout","biomes","id","size","discard","scattered","count_min","count_max","rarity",
    "min","max","triangle","plateau","replacement_bands","materials","height"],doc,[],doc => +keys =>
      V.root(A,doc,doc => +root => RD.named(A,doc,root,keys,1n,doc => found => layout_ready(A,layout_value(found),doc,root,keys,palette,biomes,next))))
