import Base
import ./registry.bend as P
import ./registry_density.bend as RD
import ./registry_values.bend as V
import ./int_provider.bend as I
import ./word64.bend as W

# Resolve dictionary IDs once. The production projection and recursive provider
# decisions are Bend; test code only transports the unmodified registry values.
@unsafe
def keys(-A: Type,doc: P.Document,next: P.Document -> RD.Keys -> A) -> A:
  RD.resolved(A,["type","value","min_inclusive","max_inclusive","min","max","plateau","source","mean","deviation",
    "distribution","data","weight","constant","uniform","biased_to_bottom","trapezoid","clamped","clamped_normal","weighted_list"],doc,[],next)

@unsafe
def name(+keys: RD.Keys,+id: U32,+i: U32) -> U32:
  name_match(U32.is_eq(id,RD.key(keys,U32.to_nat(U32.add(13,i)))),keys,id,i)

@unsafe
def name_match(+equal: Bool,+keys: RD.Keys,+id: U32,+i: U32) -> U32:
  match equal:
    case True{}: i
    case False{}: name_more(U32.is_lt(i,6),keys,id,U32.inc(i))

@unsafe
def name_more(+more: Bool,+keys: RD.Keys,+id: U32,+i: U32) -> U32:
  match more:
    case False{}: 4294967295
    case True{}: name(keys,id,i)

@unsafe
def checked(-A: Type,result: Result<&1,&1,U32,I.Provider>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{758})
    case Done{+provider}: accepted(A,I.valid(provider),provider,doc,next)

@unsafe
def accepted(-A: Type,+valid: Bool,+provider: I.Provider,doc: P.Document,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{758})
    case True{}: next(doc,Done{provider})

@unsafe
def alias(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,value: P.Value,+keys: RD.Keys,+index: Nat,next: P.Document -> Result<&1,&1,U32,U32> -> A) -> A:
  match found:
    case None{}: RD.named(A,doc,value,keys,index,doc => found => next(doc,RD.signed(found)))
    case Some{x}: next(doc,RD.signed(Some{x}))

@unsafe
def bounds(-A: Type,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,U32> -> Result<&1,&1,U32,U32> -> A) -> A:
  RD.named(A,doc,value,keys,2n,doc => found => alias(A,found,doc,value,keys,4n,doc => a =>
    RD.named(A,doc,value,keys,3n,doc => found => alias(A,found,doc,value,keys,5n,doc => b => next(doc,a,b)))))

@unsafe
def ranged(-A: Type,a: Result<&1,&1,U32,U32>,b: Result<&1,&1,U32,U32>,+kind: U32,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match a b:
    case Done{a} Done{b}: range_kind(A,kind,a,b,doc,value,keys,next)
    case _ _: next(doc,Fail{758})

@unsafe
def range_kind(-A: Type,+kind: U32,+a: U32,+b: U32,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match kind:
    case 1: next(doc,Done{I.Uniform{a,b}})
    case 2: next(doc,Done{I.Biased{a,b}})
    case 3: RD.named(A,doc,value,keys,6n,doc => found => trapezoid(A,RD.signed(found),a,b,doc,next))
    case 4: RD.named(A,doc,value,keys,7n,doc => found => parse(A,found,doc,keys,doc => source => clamped(A,source,a,b,doc,next)))
    case 5: RD.named(A,doc,value,keys,8n,doc => mean => RD.named(A,doc,value,keys,9n,doc => deviation =>
      normal(A,RD.number(mean),RD.number(deviation),a,b,doc,next)))
    case _: next(doc,Fail{758})

@unsafe
def trapezoid(-A: Type,p: Result<&1,&1,U32,U32>,+a: U32,+b: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match p:
    case Done{p}: next(doc,Done{I.Trapezoid{a,b,p}})
    case _: next(doc,Fail{758})

@unsafe
def clamped(-A: Type,p: Result<&1,&1,U32,I.Provider>,+a: U32,+b: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match p:
    case Done{p}: next(doc,Done{I.Clamped{p,a,b}})
    case _: next(doc,Fail{758})

@unsafe
def normal(-A: Type,mean: Result<&1,&1,U32,F32>,deviation: Result<&1,&1,U32,F32>,+a: U32,+b: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match mean deviation:
    case Done{mean} Done{deviation}: next(doc,Done{I.Normal{mean,deviation,a,b}})
    case _ _: next(doc,Fail{758})

@unsafe
def constant(-A: Type,result: Result<&1,&1,U32,U32>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match result:
    case Done{x}: next(doc,Done{I.Constant{x}})
    case _: next(doc,Fail{758})

@unsafe
def entry(-A: Type,result: Result<&1,&1,U32,I.Provider>,weight: Result<&1,&1,U32,U32>,+n: U32,+at: U32,doc: P.Document,+keys: RD.Keys,acc: List<&2,I.Entry>,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match result weight:
    case Done{p} Done{w}: entries(A,n,at,doc,keys,I.Entry{p,w} <> acc,next)
    case _ _: next(doc,Fail{758})

@unsafe
def entries(-A: Type,+n: U32,+at: U32,doc: P.Document,+keys: RD.Keys,acc: List<&2,I.Entry>,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match n:
    case 0:
      +entries = List.reverse(&2,I.Entry,acc)
      next(doc,Done{I.Weighted{entries,I.weight_sum(entries,W.U64{0,0})}})
    case _: P.value(A,doc,at,doc => +value => P.get(A,doc,U32.inc(at),doc => span =>
      RD.named(A,doc,value,keys,12n,doc => weight => RD.named(A,doc,value,keys,11n,doc => data =>
        parse(A,data,doc,keys,doc => result => entry(A,result,RD.integer(weight),U32.sub(n,1),U32.add(at,span),doc,keys,acc,next))))))

@unsafe
def distribution(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match found:
    case Some{P.Value{6,n,_,at}}: entries(A,n,U32.add(at,3),doc,keys,[],next)
    case _: next(doc,Fail{758})

@unsafe
def kind(-A: Type,+id: U32,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match id:
    case 0: RD.named(A,doc,value,keys,1n,doc => found => constant(A,RD.signed(found),doc,next))
    case 6: RD.named(A,doc,value,keys,10n,doc => found => distribution(A,found,doc,keys,next))
    case 4294967295: next(doc,Fail{758})
    case _: bounds(A,doc,value,keys,doc => a => b => ranged(A,a,b,id,doc,value,keys,next))

@unsafe
def typed(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match found:
    case Some{P.Value{5,id,_,_}}: kind(A,name(keys,id,0),doc,value,keys,next)
    case _: next(doc,Fail{758})

@unsafe
def parse(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  match found:
    case Some{P.Value{3,hi,lo,at}}: constant(A,RD.signed(Some{P.Value{3,hi,lo,at}}),doc,next)
    case Some{P.Value{7,n,b,at}}:
      +value : P.Value = P.Value{7,n,b,at}
      RD.named(A,doc,value,keys,0n,doc => found => typed(A,found,doc,value,keys,next))
    case _: next(doc,Fail{758})

@unsafe
def prepare(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,I.Provider> -> A) -> A:
  parse(A,found,doc,keys,doc => result => checked(A,result,doc,next))
