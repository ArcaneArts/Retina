import Base
import ./registry.bend as R
import ./registry_density.bend as D
import ./registry_placement.bend as RP
import ./registry_int_provider.bend as RI
import ./registry_placement_height.bend as RH
import ./registry_placement_predicate.bend as Pred
import ./placement_data.bend as P
import ./placement_height.bend as H
import ./int_provider.bend as I
import ./decoration_counts.bend as C
import ./density.bend as Q

type Context is Data:
  Context{base: RP.Context,extras: D.Keys,heights: D.Keys}

@unsafe
def context(-T: Type,doc: R.Document,next: R.Document -> Context -> T) -> T:
  RP.keys(T,doc,doc => keys => Pred.keys(T,doc,doc => predicates => RI.keys(T,doc,doc => integers =>
    RH.keys(T,doc,doc => heights => D.resolved(T,["count","x","y","z","height"],doc,[],doc => extras =>
      next(doc,Context{RP.Context{keys,predicates,integers,Q.Empty{},Q.Empty{},0},extras,heights}))))))

@unsafe
def counted(-T: Type,result: Result<&1,&1,U32,I.Provider>,layer: Bool,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Modifier> -> T) -> T:
  match result layer:
    case Done{p} False{}: next(doc,Done{P.Count{C.Provider{p}}})
    case Done{p} True{}: next(doc,Done{P.Layer{p}})
    case _ _: next(doc,Fail{770})

@unsafe
def height_result(-T: Type,result: Result<&1,&1,U32,H.Provider>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Modifier> -> T) -> T:
  match result:
    case Done{p}: next(doc,Done{P.HeightRange{p}})
    case Fail{_}: next(doc,Fail{770})

@unsafe
def offset_result(-T: Type,a: Result<&1,&1,U32,I.Provider>,b: Result<&1,&1,U32,I.Provider>,c: Result<&1,&1,U32,I.Provider>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Modifier> -> T) -> T:
  match a b c:
    case Done{x} Done{y} Done{z}: next(doc,Done{P.Offset{x,y,z}})
    case _ _ _: next(doc,Fail{770})

@unsafe
def offset_fields(-T: Type,fields: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,P.Modifier> -> T) -> T:
  match fields:
    case x <> y <> z <> Nil{}: offset_value(T,x,doc,keys,doc => x => offset_value(T,y,doc,keys,doc => y => offset_value(T,z,doc,keys,doc => z => offset_result(T,x,y,z,doc,next))))
    case _: next(doc,Fail{770})

@unsafe
def kind(-T: Type,+id: U32,doc: R.Document,+value: R.Value,ctx: Context,next: R.Document -> Result<&1,&1,U32,P.Modifier> -> T) -> T:
  match id ctx:
    case 28 Context{RP.Context{_,_,keys,_,_,_},extras,_}: D.named(T,doc,value,extras,0n,doc => found => RI.prepare(T,found,doc,keys,doc => result => counted(T,result,False{},doc,next)))
    case 29 Context{RP.Context{_,_,keys,_,_,_},extras,_}: D.named(T,doc,value,extras,0n,doc => found => RI.prepare(T,found,doc,keys,doc => result => counted(T,result,True{},doc,next)))
    case 35 Context{_,extras,heights}: D.named(T,doc,value,extras,4n,doc => found => RH.prepare(T,found,doc,heights,doc => result => height_result(T,result,doc,next)))
    case 37 Context{RP.Context{_,_,keys,_,_,_},extras,_}: RP.fields(T,[1,2,3],doc,value,extras,[],doc => values => offset_fields(T,values,doc,keys,next))
    case 30 _: next(doc,Fail{770})
    case 31 _: next(doc,Fail{770})
    case 41 _: next(doc,Fail{770})
    case 42 _: next(doc,Fail{770})
    case _ Context{base,_,_}: RP.kind(T,id,doc,value,base,0,0,next)

@unsafe
def typed(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+value: R.Value,+ctx: Context,next: R.Document -> Result<&1,&1,U32,P.Modifier> -> T) -> T:
  match found ctx:
    case Some{R.Value{5,id,_,_}} Context{RP.Context{keys,_,_,_,_,_},_,_}: kind(T,RP.name(keys,id,28),doc,value,ctx,next)
    case _ _: next(doc,Fail{770})

@unsafe
def items(-T: Type,+n: U32,+at: U32,doc: R.Document,+ctx: Context,acc: List<&2,P.Modifier>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Modifier>> -> T) -> T:
  match n ctx:
    case 0 _: next(doc,Done{Q.prepared_table(P.Modifier,List.reverse(&2,P.Modifier,acc))})
    case _ Context{RP.Context{keys,_,_,_,_,_},_,_}: R.value(T,doc,at,doc => +value => R.get(T,doc,U32.inc(at),doc => span => D.named(T,doc,value,keys,2n,doc => found =>
      typed(T,found,doc,value,ctx,doc => result => appended(T,result,U32.sub(n,1),U32.add(at,span),doc,ctx,acc,next)))))

@unsafe
def appended(-T: Type,result: Result<&1,&1,U32,P.Modifier>,n: U32,at: U32,doc: R.Document,ctx: Context,acc: List<&2,P.Modifier>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Modifier>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{770})
    case Done{m}: items(T,n,at,doc,ctx,m <> acc,next)

@unsafe
def prepare(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,ctx: Context,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Modifier>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: items(T,n,U32.add(at,3),doc,ctx,[],next)
    case _: next(doc,Fail{770})

@unsafe
def offset_value(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,keys: D.Keys,next: R.Document -> Result<&1,&1,U32,I.Provider> -> T) -> T:
  match found:
    case None{}: next(doc,Done{I.Constant{0}})
    case Some{value}: RI.prepare(T,Some{value},doc,keys,next)
