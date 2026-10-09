import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./registry_int_provider.bend as RI
import ./int_provider.bend as I
import ./decoration_counts.bend as C
import ./density.bend as Q

# Project the count/offset instructions of the original ordered programs. Row
# addresses retain recipe/modifier/slot, including CountOnEveryLayer, for the
# future live-overlay walker. This projection alone never places a feature.
@unsafe
def contains(xs: List<&2,U32>,+value: U32) -> Bool:
  match xs:
    case Nil{}: False{}
    case x <> rest: Bool.or(U32.is_eq(x,value),contains(rest,value))

@unsafe
def permutation_valid(xs: List<&2,U32>,+seen: List<&2,U32>) -> Bool:
  match xs:
    case Nil{}: True{}
    case +x <> rest: Bool.and(U32.is_lt(x,256),Bool.and(Bool.not(contains(seen,x)),permutation_valid(rest,x <> seen)))

@unsafe
def row_valid(+slot: U32,+rule: C.Rule) -> Bool:
  Bool.and(C.valid(rule),row_range(slot,rule))

@unsafe
def row_range(+slot: U32,+rule: C.Rule) -> Bool:
  match rule:
    case C.Provider{p}: provider_range(slot,p)
    case _: True{}

@unsafe
def provider_range(+slot: U32,+p: I.Provider) -> Bool:
  match slot:
    case 0: I.within(p,0,4096)
    case 4: I.within(p,0,256)
    case _: True{}

@unsafe
def append(-A: Type,result: Result<&1,&1,U32,C.Rule>,+slot: U32,doc: P.Document,acc: List<&2,C.Row>,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{759})
    case Done{+rule}: append_valid(A,row_valid(slot,rule),rule,slot,doc,acc,recipe,modifier,next)

@unsafe
def append_valid(-A: Type,+valid: Bool,+rule: C.Rule,+slot: U32,doc: P.Document,acc: List<&2,C.Row>,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{759})
    case True{}: next(doc,Done{C.Row{recipe,modifier,slot,rule} <> acc})

@unsafe
def provider(-A: Type,result: Result<&1,&1,U32,I.Provider>,+slot: U32,doc: P.Document,acc: List<&2,C.Row>,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{759})
    case Done{p}: append(A,Done{C.Provider{p}},slot,doc,acc,recipe,modifier,next)

@unsafe
def offset(-A: Type,+slot: U32,doc: P.Document,+value: P.Value,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,C.Row>,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match slot:
    case 4: next(doc,Done{acc})
    case _: RD.named(A,doc,value,keys,U32.to_nat(U32.add(slot,3)),doc => found =>
      offset_value(A,found,slot,doc,value,keys,providers,acc,recipe,modifier,next))

@unsafe
def offset_value(-A: Type,found: Maybe<&2,P.Value>,+slot: U32,doc: P.Document,+value: P.Value,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,C.Row>,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match found:
    case None{}: offset_result(A,Done{I.Constant{0}},slot,doc,value,keys,providers,acc,recipe,modifier,next)
    case Some{x}: RI.prepare(A,Some{x},doc,providers,doc => result => offset_result(A,result,slot,doc,value,keys,providers,acc,recipe,modifier,next))

@unsafe
def offset_result(-A: Type,result: Result<&1,&1,U32,I.Provider>,+slot: U32,doc: P.Document,+value: P.Value,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,C.Row>,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  provider(A,result,slot,doc,acc,recipe,modifier,doc => result => offset_more(A,result,U32.inc(slot),doc,value,keys,providers,recipe,modifier,next))

@unsafe
def offset_more(-A: Type,result: Result<&1,&1,U32,List<&2,C.Row>>,+slot: U32,doc: P.Document,value: P.Value,+keys: RD.Keys,+providers: RD.Keys,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{759})
    case Done{acc}: offset(A,slot,doc,value,keys,providers,acc,recipe,modifier,next)

@unsafe
def threshold(-A: Type,level: Result<&1,&1,U32,F32>,below: Result<&1,&1,U32,U32>,above: Result<&1,&1,U32,U32>,doc: P.Document,acc: List<&2,C.Row>,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match level below above:
    case Done{level} Done{below} Done{above}: append(A,Done{C.Threshold{level,below,above}},0,doc,acc,recipe,modifier,next)
    case _ _ _: next(doc,Fail{759})

@unsafe
def based(-A: Type,ratio: Result<&1,&1,U32,U32>,factor: Result<&1,&1,U32,F32>,offset: Result<&1,&1,U32,F32>,doc: P.Document,acc: List<&2,C.Row>,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match ratio factor offset:
    case Done{ratio} Done{factor} Done{offset}: append(A,Done{C.Based{ratio,factor,offset}},0,doc,acc,recipe,modifier,next)
    case _ _ _: next(doc,Fail{759})

@unsafe
def kind(-A: Type,+kind: U32,doc: P.Document,+value: P.Value,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,C.Row>,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match kind:
    case 15: RD.named(A,doc,value,keys,3n,doc => found => RI.prepare(A,found,doc,providers,doc => result => provider(A,result,0,doc,acc,recipe,modifier,next)))
    case 16: RD.named(A,doc,value,keys,3n,doc => found => RI.prepare(A,found,doc,providers,doc => result => provider(A,result,4,doc,acc,recipe,modifier,next)))
    case 17: offset(A,1,doc,value,keys,providers,acc,recipe,modifier,next)
    case 18: RD.named(A,doc,value,keys,7n,doc => level => RD.named(A,doc,value,keys,8n,doc => below => RD.named(A,doc,value,keys,9n,doc => above =>
      threshold(A,RD.number(level),RD.signed(below),RD.signed(above),doc,acc,recipe,modifier,next))))
    case 19: RD.named(A,doc,value,keys,10n,doc => ratio => RD.named(A,doc,value,keys,11n,doc => factor => RD.named(A,doc,value,keys,12n,doc => noise_offset =>
      based(A,RD.signed(ratio),RD.number(factor),RD.default_number(noise_offset,0.0),doc,acc,recipe,modifier,next))))
    case _: next(doc,Done{acc})

@unsafe
def identify(+keys: RD.Keys,+id: U32,+i: U32) -> U32:
  identify_match(U32.is_eq(id,RD.key(keys,U32.to_nat(i))),keys,id,i)

@unsafe
def identify_match(+equal: Bool,+keys: RD.Keys,+id: U32,+i: U32) -> U32:
  match equal:
    case True{}: i
    case False{}: identify_more(U32.is_lt(i,19),keys,id,U32.inc(i))

@unsafe
def identify_more(+more: Bool,+keys: RD.Keys,+id: U32,+i: U32) -> U32:
  match more:
    case False{}: 0
    case True{}: identify(keys,id,i)

@unsafe
def modifier_type(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+value: P.Value,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,C.Row>,+recipe: U32,+modifier: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match found:
    case Some{P.Value{5,id,_,_}}: kind(A,identify(keys,id,15),doc,value,keys,providers,acc,recipe,modifier,next)
    case _: next(doc,Fail{759})

@unsafe
def modifiers(-A: Type,+n: U32,+at: U32,+i: U32,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,C.Row>,+recipe: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match n:
    case 0: next(doc,Done{acc})
    case _: P.value(A,doc,at,doc => +value => P.get(A,doc,U32.inc(at),doc => span => RD.named(A,doc,value,keys,2n,doc => found =>
      modifier_type(A,found,doc,value,keys,providers,acc,recipe,i,doc => result =>
        modifiers_more(A,result,U32.sub(n,1),U32.add(at,span),U32.inc(i),doc,keys,providers,recipe,next)))))

@unsafe
def modifiers_more(-A: Type,result: Result<&1,&1,U32,List<&2,C.Row>>,+n: U32,+at: U32,+i: U32,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,+recipe: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{759})
    case Done{acc}: modifiers(A,n,at,i,doc,keys,providers,acc,recipe,next)

@unsafe
def placement(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,C.Row>,+recipe: U32,next: P.Document -> Result<&1,&1,U32,List<&2,C.Row>> -> A) -> A:
  match found:
    case None{}: next(doc,Done{acc})
    case Some{P.Value{0,_,_,_}}: next(doc,Done{acc})
    case Some{P.Value{6,n,_,at}}: modifiers(A,n,U32.add(at,3),0,doc,keys,providers,acc,recipe,next)
    case _: next(doc,Fail{759})

@unsafe
def recipes(-A: Type,+n: U32,+at: U32,+i: U32,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,acc: List<&2,C.Row>,next: P.Document -> Result<&1,&1,U32,Q.Table<C.Row>> -> A) -> A:
  match n:
    case 0: next(doc,Done{Q.prepared_table(C.Row,List.reverse(&2,C.Row,acc))})
    case _: P.value(A,doc,at,doc => +value => P.get(A,doc,U32.inc(at),doc => span => RD.named(A,doc,value,keys,1n,doc => found =>
      placement(A,found,doc,keys,providers,acc,i,doc => result =>
        recipes_more(A,result,U32.sub(n,1),U32.add(at,span),U32.inc(i),doc,keys,providers,next)))))

@unsafe
def recipes_more(-A: Type,result: Result<&1,&1,U32,List<&2,C.Row>>,+n: U32,+at: U32,+i: U32,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,next: P.Document -> Result<&1,&1,U32,Q.Table<C.Row>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{759})
    case Done{acc}: recipes(A,n,at,i,doc,keys,providers,acc,next)

@unsafe
def decorations(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,+providers: RD.Keys,next: P.Document -> Result<&1,&1,U32,Q.Table<C.Row>> -> A) -> A:
  match found:
    case Some{P.Value{6,n,_,at}}: recipes(A,n,U32.add(at,3),0,doc,keys,providers,[],next)
    case _: next(doc,Fail{759})

@unsafe
def model(-A: Type,permutation: Result<&1,&1,U32,List<&2,U32>>,rules: Result<&1,&1,U32,Q.Table<C.Row>>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,C.Model> -> A) -> A:
  match permutation rules:
    case Done{+permutation} Done{rules}: model_valid(A,permutation_valid(permutation,[]),permutation,rules,doc,next)
    case _ _: next(doc,Fail{759})

@unsafe
def model_valid(-A: Type,+ok: Bool,permutation: List<&2,U32>,+rules: Q.Table<C.Row>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,C.Model> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{759})
    case True{}: next(doc,Done{C.Model{Q.prepared_table(U32,permutation),rules}})

@unsafe
def noise(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match found:
    case Some{value}: RD.named(A,doc,value,keys,14n,doc => found => RD.ints(A,doc,found,256,256,next))
    case _: next(doc,Fail{759})

@unsafe
def prepare(-A: Type,doc: P.Document,next: P.Document -> Result<&1,&1,U32,C.Model> -> A) -> A:
  RI.keys(A,doc,doc => +providers => RD.resolved(A,["decorations","placement","type","count","x","y","z","noise_level",
    "below_noise","above_noise","noise_to_count_ratio","noise_factor","noise_offset","decoration_noise","permutation",
    "count","count_on_every_layer","offset","noise_threshold_count","noise_based_count"],doc,[],doc => +keys =>
      V.root(A,doc,doc => +root => RD.named(A,doc,root,keys,13n,doc => found => noise(A,found,doc,keys,doc => permutation =>
        RD.named(A,doc,root,keys,0n,doc => found => decorations(A,found,doc,keys,providers,doc => rules => model(A,permutation,rules,doc,next))))))))
