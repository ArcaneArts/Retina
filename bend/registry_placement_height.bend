import Base
import ./registry.bend as P
import ./registry_density.bend as RD
import ./placement_height.bend as H
import ./word64.bend as W

# Height providers have a different schema and draw contract from ordinary
# integer providers. In particular, their endpoints can be build-relative.
@unsafe
def keys(-A: Type,doc: P.Document,next: P.Document -> RD.Keys -> A) -> A:
  RD.resolved(A,["type","value","min_inclusive","max_inclusive","plateau","inner","distribution","data","weight",
    "absolute","above_bottom","below_top","constant","uniform","biased_to_bottom","very_biased_to_bottom","trapezoid","weighted_list"],doc,[],next)

@unsafe
def name(+keys: RD.Keys,+id: U32,+i: U32) -> U32:
  name_match(U32.is_eq(id,RD.key(keys,U32.to_nat(U32.add(12,i)))),keys,id,i)

@unsafe
def name_match(+equal: Bool,+keys: RD.Keys,+id: U32,+i: U32) -> U32:
  match equal:
    case True{}: i
    case False{}: name_more(U32.is_lt(i,5),keys,id,U32.inc(i))

@unsafe
def name_more(+more: Bool,+keys: RD.Keys,+id: U32,+i: U32) -> U32:
  match more:
    case False{}: 4294967295
    case True{}: name(keys,id,i)

@unsafe
def anchor_value(-A: Type,result: Result<&1,&1,U32,U32>,+kind: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,H.Anchor> -> A) -> A:
  match result kind:
    case Done{x} 0: next(doc,Done{H.Absolute{x}})
    case Done{x} 1: next(doc,Done{H.AboveBottom{x}})
    case Done{x} 2: next(doc,Done{H.BelowTop{x}})
    case _ _: next(doc,Fail{760})

@unsafe
def anchor_find(-A: Type,found: Maybe<&2,P.Value>,+kind: U32,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,H.Anchor> -> A) -> A:
  match found kind:
    case Some{x} _: anchor_value(A,RD.signed(Some{x}),kind,doc,next)
    case None{} 2: next(doc,Fail{760})
    case None{} _: RD.named(A,doc,value,keys,U32.to_nat(U32.add(10,kind)),doc => found => anchor_find(A,found,U32.inc(kind),doc,value,keys,next))

@unsafe
def anchor(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,H.Anchor> -> A) -> A:
  match found:
    case Some{P.Value{7,1,b,at}}:
      +value : P.Value = P.Value{7,1,b,at}
      RD.named(A,doc,value,keys,9n,doc => found => anchor_find(A,found,0,doc,value,keys,next))
    case _: next(doc,Fail{760})

@unsafe
def checked(-A: Type,result: Result<&1,&1,U32,H.Provider>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{760})
    case Done{+provider}: accepted(A,H.valid(provider),provider,doc,next)

@unsafe
def accepted(-A: Type,+valid: Bool,+provider: H.Provider,doc: P.Document,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{760})
    case True{}: next(doc,Done{provider})

@unsafe
def constant(-A: Type,result: Result<&1,&1,U32,H.Anchor>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match result:
    case Done{x}: next(doc,Done{H.Constant{x}})
    case _: next(doc,Fail{760})

@unsafe
def parameter(found: Maybe<&2,P.Value>,fallback: U32) -> Result<&1,&1,U32,U32>:
  match found:
    case None{}: Done{fallback}
    case Some{x}: RD.signed(Some{x})

@unsafe
def ranged_parameter(-A: Type,p: Result<&1,&1,U32,U32>,a: H.Anchor,b: H.Anchor,+kind: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match p kind:
    case Done{p} 2: next(doc,Done{H.Biased{a,b,p}})
    case Done{p} 3: next(doc,Done{H.VeryBiased{a,b,p}})
    case Done{p} 4: next(doc,Done{H.Trapezoid{a,b,p}})
    case _ _: next(doc,Fail{760})

@unsafe
def ranged(-A: Type,a: Result<&1,&1,U32,H.Anchor>,b: Result<&1,&1,U32,H.Anchor>,+kind: U32,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match a b kind:
    case Done{a} Done{b} 1: next(doc,Done{H.Uniform{a,b}})
    case Done{a} Done{b} 4: RD.named(A,doc,value,keys,4n,doc => found => ranged_parameter(A,parameter(found,0),a,b,kind,doc,next))
    case Done{a} Done{b} _: RD.named(A,doc,value,keys,5n,doc => found => ranged_parameter(A,parameter(found,1),a,b,kind,doc,next))
    case _ _ _: next(doc,Fail{760})

@unsafe
def entry(-A: Type,result: Result<&1,&1,U32,H.Provider>,weight: Result<&1,&1,U32,U32>,+n: U32,+at: U32,doc: P.Document,+keys: RD.Keys,acc: List<&2,H.Entry>,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match result weight:
    case Done{p} Done{w}: entries(A,n,at,doc,keys,H.Entry{p,w} <> acc,next)
    case _ _: next(doc,Fail{760})

@unsafe
def entries(-A: Type,+n: U32,+at: U32,doc: P.Document,+keys: RD.Keys,acc: List<&2,H.Entry>,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match n:
    case 0:
      +entries = List.reverse(&2,H.Entry,acc)
      next(doc,Done{H.Weighted{entries,H.weight_sum(entries,W.U64{0,0})}})
    case _: P.value(A,doc,at,doc => +value => P.get(A,doc,U32.inc(at),doc => span =>
      RD.named(A,doc,value,keys,8n,doc => weight => RD.named(A,doc,value,keys,7n,doc => data =>
        parse(A,data,doc,keys,doc => result => entry(A,result,RD.integer(weight),U32.sub(n,1),U32.add(at,span),doc,keys,acc,next))))))

@unsafe
def distribution(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match found:
    case Some{P.Value{6,n,_,at}}: entries(A,n,U32.add(at,3),doc,keys,[],next)
    case _: next(doc,Fail{760})

@unsafe
def kind(-A: Type,+id: U32,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match id:
    case 0: RD.named(A,doc,value,keys,1n,doc => found => anchor(A,found,doc,keys,doc => result => constant(A,result,doc,next)))
    case 5: RD.named(A,doc,value,keys,6n,doc => found => distribution(A,found,doc,keys,next))
    case 4294967295: next(doc,Fail{760})
    case _: RD.named(A,doc,value,keys,2n,doc => found => anchor(A,found,doc,keys,doc => a =>
      RD.named(A,doc,value,keys,3n,doc => found => anchor(A,found,doc,keys,doc => b => ranged(A,a,b,id,doc,value,keys,next)))))

@unsafe
def typed(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match found:
    case None{}: anchor(A,Some{value},doc,keys,doc => result => constant(A,result,doc,next))
    case Some{P.Value{5,id,_,_}}: kind(A,name(keys,id,0),doc,value,keys,next)
    case _: next(doc,Fail{760})

@unsafe
def parse(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  match found:
    case Some{P.Value{7,n,b,at}}:
      +value : P.Value = P.Value{7,n,b,at}
      RD.named(A,doc,value,keys,0n,doc => found => typed(A,found,doc,value,keys,next))
    case _: next(doc,Fail{760})

@unsafe
def prepare(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,H.Provider> -> A) -> A:
  parse(A,found,doc,keys,doc => result => checked(A,result,doc,next))
