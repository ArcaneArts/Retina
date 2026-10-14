import Base
import ./registry.bend as R
import ./registry_values.bend as V
import ./registry_density.bend as D
import ./registry_placement.bend as RP
import ./registry_surface_feature.bend as RF
import ./registry_provider_noise.bend as RN
import ./placement_data.bend as P
import ./surface_feature.bend as F
import ./provider_noise.bend as N
import ./density.bend as Q
import ./vegetation.bend as G

# Preserve top-level recipe indices and placement salt groups. Unsupported
# feature families are explicit None entries, never aliases to another feature.
# The coordinator reports Unavailable if one is actually reached.
@unsafe
def items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: RF.Context,+palette: U32,+noises: U32,acc: List<&2,Maybe<&2,F.Feature>>,next: R.Document -> Result<&1,&1,U32,Q.Table<Maybe<&2,F.Feature>>> -> T) -> T:
  match n context:
    case 0 _: next(doc,Done{Q.prepared_table(Maybe<&2,F.Feature>,List.reverse(&2,Maybe<&2,F.Feature>,acc))})
    case _ RF.Context{keys,_,_}: R.value(T,doc,at,doc => +value => R.get(T,doc,U32.inc(at),doc => span =>
      D.named(T,doc,value,keys,0n,doc => found => kind(T,found,value,U32.sub(n,1),U32.add(at,span),doc,context,palette,noises,acc,next))))

@unsafe
def kind(-T: Type,found: Maybe<&2,R.Value>,+value: R.Value,+n: U32,+at: U32,doc: R.Document,+context: RF.Context,+palette: U32,+noises: U32,acc: List<&2,Maybe<&2,F.Feature>>,next: R.Document -> Result<&1,&1,U32,Q.Table<Maybe<&2,F.Feature>>> -> T) -> T:
  match found context:
    case Some{R.Value{5,id,_,_}} RF.Context{keys,_,_}:
      known(T,U32.is_le(RF.name(keys,id,0),16),value,n,at,doc,context,palette,noises,acc,next)
    case _ _: next(doc,Fail{766})

@unsafe
def known(-T: Type,known: Bool,value: R.Value,+n: U32,+at: U32,doc: R.Document,+context: RF.Context,+palette: U32,+noises: U32,acc: List<&2,Maybe<&2,F.Feature>>,next: R.Document -> Result<&1,&1,U32,Q.Table<Maybe<&2,F.Feature>>> -> T) -> T:
  match known:
    case False{}: items(T,n,at,doc,context,palette,noises,None{} <> acc,next)
    case True{}: RF.prepare(T,Some{value},doc,context,palette,noises,doc => result => appended(T,result,n,at,doc,context,palette,noises,acc,next))

@unsafe
def appended(-T: Type,result: Result<&1,&1,U32,F.Feature>,+n: U32,+at: U32,doc: R.Document,+context: RF.Context,+palette: U32,+noises: U32,acc: List<&2,Maybe<&2,F.Feature>>,next: R.Document -> Result<&1,&1,U32,Q.Table<Maybe<&2,F.Feature>>> -> T) -> T:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{feature}: items(T,n,at,doc,context,palette,noises,Some{feature} <> acc,next)

@unsafe
def projected(-T: Type,result: Result<&1,&1,U32,Q.Table<Maybe<&2,F.Feature>>>,+placement: P.Model,writers: F.Model,doc: R.Document,next: R.Document -> Result<&1,&1,U32,G.Model> -> T) -> T:
  match result placement:
    case Done{+features} P.Model{recipes,_,_}: aligned(T,U32.is_eq(Q.table_size(Maybe<&2,F.Feature>,features),Q.table_size(P.Recipe,recipes)),placement,features,writers,doc,next)
    case _ _: next(doc,Fail{766})

@unsafe
def aligned(-T: Type,valid: Bool,placement: P.Model,features: Q.Table<Maybe<&2,F.Feature>>,writers: F.Model,doc: R.Document,next: R.Document -> Result<&1,&1,U32,G.Model> -> T) -> T:
  match valid:
    case False{}: next(doc,Fail{766})
    case True{}: next(doc,Done{G.Model{placement,features,writers}})

@unsafe
def decorations(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: RF.Context,+placement: P.Model,+noises: Q.Table<N.Program>,current: Q.Table<Maybe<&2,F.PlantState>>,next: R.Document -> Result<&1,&1,U32,G.Model> -> T) -> T:
  match found placement:
    case Some{R.Value{6,n,_,at}} P.Model{_,_,P.Policy{masks,_,_,_,_,_,_}}:
      items(T,n,U32.add(at,3),doc,context,Q.table_size(U32,masks),Q.table_size(N.Program,noises),[],doc => result => projected(T,result,placement,F.Model{noises,current},doc,next))
    case _ _: next(doc,Fail{766})

@unsafe
def current(-T: Type,result: Result<&1,&1,U32,Q.Table<Maybe<&2,F.PlantState>>>,doc: R.Document,+context: RF.Context,+placement: P.Model,+noises: Q.Table<N.Program>,next: R.Document -> Result<&1,&1,U32,G.Model> -> T) -> T:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{current}: V.root(T,doc,doc => root => V.field(T,doc,root,"decorations",doc => found => decorations(T,found,doc,context,placement,noises,current,next)))

@unsafe
def noises(-T: Type,result: Result<&1,&1,U32,Q.Table<N.Program>>,doc: R.Document,+placement: P.Model,next: R.Document -> Result<&1,&1,U32,G.Model> -> T) -> T:
  match result placement:
    case Fail{code} _: next(doc,Fail{code})
    case Done{+noises} P.Model{_,_,P.Policy{masks,_,_,_,_,_,_}}: RF.context(T,doc,doc => +context =>
      RF.current_states(T,doc,context,Q.table_size(U32,masks),doc => result => current(T,result,doc,context,placement,noises,next)))

@unsafe
def placement(-T: Type,result: Result<&1,&1,U32,P.Model>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,G.Model> -> T) -> T:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{placement}: RN.prepare(T,doc,doc => result => noises(T,result,doc,placement,next))

@unsafe
def prepare(-T: Type,doc: R.Document,next: R.Document -> Result<&1,&1,U32,G.Model> -> T) -> T:
  RP.prepare(T,doc,doc => result => placement(T,result,doc,next))
