import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./registry_placement_height.bend as RH
import ./placement_height.bend as H
import ./density.bend as Q

# Retain addresses in the original ordered programs. Projection and sampling
# do not flatten selectors or replace height providers with averaged heights.
@unsafe
def appended(-A: Type,result: Result<&1,&1,U32,H.Provider>,+n: U32,+at: U32,+i: U32,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,H.Row>,+recipe: U32,next: P.Document -> Result<&1,&1,U32,List<&2,H.Row>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{760})
    case Done{provider}: modifiers(A,n,at,i,doc,keys,providers,H.Row{recipe,U32.sub(i,1),provider} <> acc,recipe,next)

@unsafe
def modifier_type(-A: Type,found: Maybe<&2,P.Value>,+value: P.Value,+n: U32,+at: U32,+i: U32,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,H.Row>,+recipe: U32,next: P.Document -> Result<&1,&1,U32,List<&2,H.Row>> -> A) -> A:
  match found:
    case Some{P.Value{5,+id,_,_}}:
      height_modifier(A,U32.is_eq(id,RD.key(keys,4n)),value,n,at,i,doc,keys,providers,acc,recipe,next)
    case _: next(doc,Fail{760})

@unsafe
def height_modifier(-A: Type,+height: Bool,+value: P.Value,+n: U32,+at: U32,+i: U32,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,H.Row>,+recipe: U32,next: P.Document -> Result<&1,&1,U32,List<&2,H.Row>> -> A) -> A:
  match height:
    case False{}: modifiers(A,n,at,i,doc,keys,providers,acc,recipe,next)
    case True{}: RD.named(A,doc,value,keys,3n,doc => found => RH.prepare(A,found,doc,providers,doc => result =>
      appended(A,result,n,at,i,doc,keys,providers,acc,recipe,next)))

@unsafe
def modifiers(-A: Type,+n: U32,+at: U32,+i: U32,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,H.Row>,+recipe: U32,next: P.Document -> Result<&1,&1,U32,List<&2,H.Row>> -> A) -> A:
  match n:
    case 0: next(doc,Done{acc})
    case _: P.value(A,doc,at,doc => +value => P.get(A,doc,U32.inc(at),doc => span => RD.named(A,doc,value,keys,2n,doc => found =>
      modifier_type(A,found,value,U32.sub(n,1),U32.add(at,span),U32.inc(i),doc,keys,providers,acc,recipe,next))))

@unsafe
def placement(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,H.Row>,+recipe: U32,next: P.Document -> Result<&1,&1,U32,List<&2,H.Row>> -> A) -> A:
  match found:
    case None{}: next(doc,Done{acc})
    case Some{P.Value{0,_,_,_}}: next(doc,Done{acc})
    case Some{P.Value{6,n,_,at}}: modifiers(A,n,U32.add(at,3),0,doc,keys,providers,acc,recipe,next)
    case _: next(doc,Fail{760})

@unsafe
def recipes(-A: Type,+n: U32,+at: U32,+i: U32,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,H.Row>,next: P.Document -> Result<&1,&1,U32,Q.Table<H.Row>> -> A) -> A:
  match n:
    case 0: next(doc,Done{Q.prepared_table(H.Row,List.reverse(&2,H.Row,acc))})
    case _: P.value(A,doc,at,doc => +value => P.get(A,doc,U32.inc(at),doc => span => RD.named(A,doc,value,keys,1n,doc => found =>
      placement(A,found,doc,keys,providers,acc,i,doc => result =>
        recipes_more(A,result,U32.sub(n,1),U32.add(at,span),U32.inc(i),doc,keys,providers,next)))))

@unsafe
def recipes_more(-A: Type,result: Result<&1,&1,U32,List<&2,H.Row>>,+n: U32,+at: U32,+i: U32,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,next: P.Document -> Result<&1,&1,U32,Q.Table<H.Row>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{760})
    case Done{acc}: recipes(A,n,at,i,doc,keys,providers,acc,next)

@unsafe
def decorations(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,next: P.Document -> Result<&1,&1,U32,Q.Table<H.Row>> -> A) -> A:
  match found:
    case Some{P.Value{6,n,_,at}}: recipes(A,n,U32.add(at,3),0,doc,keys,providers,[],next)
    case _: next(doc,Fail{760})

@unsafe
def prepare(-A: Type,doc: P.Document,next: P.Document -> Result<&1,&1,U32,Q.Table<H.Row>> -> A) -> A:
  RH.keys(A,doc,doc => +providers => RD.resolved(A,["decorations","placement","type","height","height_range"],doc,[],doc => +keys =>
    V.root(A,doc,doc => +root => RD.named(A,doc,root,keys,0n,doc => found => decorations(A,found,doc,keys,providers,next)))))
