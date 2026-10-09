import Base
import ./registry.bend as R
import ./registry_density.bend as D
import ./registry_cave_features.bend as A
import ./placement_data.bend as P
import ./density.bend as Q

@unsafe
def keys(-T: Type,doc: R.Document,next: R.Document -> D.Keys -> T) -> T:
  D.resolved(T,["type","offset","allowed","predicates","predicate","material","all_of","any_of","not","true"],doc,[],next)

@unsafe
def name(+keys: D.Keys,+id: U32,+i: U32) -> U32:
  named(U32.is_eq(id,D.key(keys,U32.to_nat(U32.add(i,5)))),keys,id,i)

@unsafe
def named(+same: Bool,+keys: D.Keys,+id: U32,+i: U32) -> U32:
  match same:
    case True{}: i
    case False{}: name_more(U32.is_lt(i,4),keys,id,U32.inc(i))

@unsafe
def name_more(+more: Bool,+keys: D.Keys,+id: U32,+i: U32) -> U32:
  match more:
    case False{}: 4294967295
    case True{}: name(keys,id,i)

@unsafe
def material(-T: Type,offset: Result<&1,&1,U32,Q.Table<U32>>,allowed: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match offset allowed:
    case Done{+offset} Done{allowed}: material_size(T,U32.is_eq(Q.table_size(U32,offset),3),offset,allowed,doc,next)
    case _ _: next(doc,Fail{761})

@unsafe
def allowed_array(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Q.Table<U32>> -> T) -> T:
  match found:
    case None{}: next(doc,Fail{761})
    case Some{x}: A.array(T,Some{x},doc,0,next)

@unsafe
def material_size(-T: Type,+valid: Bool,+offset: Q.Table<U32>,+allowed: Q.Table<U32>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match valid:
    case False{}: next(doc,Fail{761})
    case True{}: next(doc,Done{P.Material{P.Position{Q.get(U32,offset,0,0),Q.get(U32,offset,1,0),Q.get(U32,offset,2,0)},allowed}})

@unsafe
def negated(-T: Type,result: Result<&1,&1,U32,P.Predicate>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match result:
    case Done{p}: next(doc,Done{P.Not{p}})
    case Fail{_}: next(doc,Fail{761})

@unsafe
def items(-T: Type,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,+all: Bool,acc: List<&2,P.Predicate>,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match n:
    case 0: collected(T,all,List.reverse(&2,P.Predicate,acc),doc,next)
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span =>
      parse(T,Some{value},doc,keys,doc => result => item(T,result,U32.sub(n,1),U32.add(at,span),doc,keys,all,acc,next))))

@unsafe
def collected(-T: Type,+all: Bool,acc: List<&2,P.Predicate>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match all:
    case True{}: next(doc,Done{P.All{acc}})
    case False{}: next(doc,Done{P.Any{acc}})

@unsafe
def item(-T: Type,result: Result<&1,&1,U32,P.Predicate>,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,+all: Bool,acc: List<&2,P.Predicate>,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{761})
    case Done{p}: items(T,n,at,doc,keys,all,p <> acc,next)

@unsafe
def list(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+keys: D.Keys,+all: Bool,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: items(T,n,U32.add(at,3),doc,keys,all,[],next)
    case _: next(doc,Fail{761})

@unsafe
def kind(-T: Type,+kind: U32,doc: R.Document,+value: R.Value,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match kind:
    case 0: D.named(T,doc,value,keys,1n,doc => offset => A.array(T,offset,doc,2,doc => offset =>
      D.named(T,doc,value,keys,2n,doc => allowed => allowed_array(T,allowed,doc,doc => allowed => material(T,offset,allowed,doc,next)))))
    case 1: D.named(T,doc,value,keys,3n,doc => found => list(T,found,doc,keys,True{},next))
    case 2: D.named(T,doc,value,keys,3n,doc => found => list(T,found,doc,keys,False{},next))
    case 3: D.named(T,doc,value,keys,4n,doc => found => parse(T,found,doc,keys,doc => result => negated(T,result,doc,next)))
    case 4: next(doc,Done{P.Always{}})
    case _: next(doc,Fail{761})

@unsafe
def typed(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+value: R.Value,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match found:
    case Some{R.Value{5,id,_,_}}: kind(T,name(keys,id,0),doc,value,keys,next)
    case _: next(doc,Fail{761})

@unsafe
def parse(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match found:
    case Some{+value}: D.named(T,doc,value,keys,0n,doc => found => typed(T,found,doc,value,keys,next))
    case _: next(doc,Fail{761})
