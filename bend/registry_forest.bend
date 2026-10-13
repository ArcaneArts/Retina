import Base
import ./registry.bend as R
import ./registry_density.bend as D
import ./registry_cave_features.bend as A
import ./registry_material.bend as M
import ./registry_block_provider.bend as RB
import ./registry_int_provider.bend as RI
import ./registry_placement_predicate.bend as RP
import ./forest_data.bend as F
import ./block_provider.bend as B
import ./int_provider.bend as I
import ./placement_data.bend as P
import ./density.bend as Q

type Context is Data:
  Context{keys: D.Keys,providers: RB.Context}

@unsafe
def context(-T: Type,doc: R.Document,providers: RB.Context,next: R.Document -> Context -> T) -> T:
  D.resolved(T,["red","foliage_radius","cap","stem","faces","source","states","support","clearance","replaceable",
    "trunk","log_length","axes","stump_decorators","log_decorators","sturdy","air","water","shelf","type","probability","provider","directions",
    "attached_to_logs","trunk_vine","shelf_mushroom"],doc,[],doc => keys => next(doc,Context{keys,providers}))

@unsafe
def fields(-T: Type,indices: List<&2,U32>,doc: R.Document,+value: R.Value,+context: Context,acc: List<&2,Maybe<&2,R.Value>>,next: R.Document -> List<&2,Maybe<&2,R.Value>> -> T) -> T:
  match indices context:
    case Nil{} _: next(doc,List.reverse(&2,Maybe<&2,R.Value>,acc))
    case i <> rest Context{keys,_}: D.named(T,doc,value,keys,U32.to_nat(i),doc => found => fields(T,rest,doc,value,context,found <> acc,next))

def providers(context: Context) -> RB.Context:
  match context:
    case Context{_,providers}: providers

@unsafe
def predicates(-T: Type,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,acc: List<&2,P.Predicate>,next: R.Document -> Result<&1,&1,U32,List<&2,P.Predicate>> -> T) -> T:
  match values context:
    case Nil{} _: next(doc,Done{List.reverse(&2,P.Predicate,acc)})
    case value <> rest Context{_,RB.Context{_,_,keys}}: RP.parse(T,value,doc,keys,doc => result => predicate_result(T,result,rest,doc,context,acc,next))

@unsafe
def predicate_result(-T: Type,result: Result<&1,&1,U32,P.Predicate>,rest: List<&2,Maybe<&2,R.Value>>,doc: R.Document,context: Context,acc: List<&2,P.Predicate>,next: R.Document -> Result<&1,&1,U32,List<&2,P.Predicate>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{768})
    case Done{p}: predicates(T,rest,doc,context,p <> acc,next)

@unsafe
def mapped(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,List<&2,F.Mapped>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: mapped_items(T,n,U32.add(at,3),doc,context,[],next)
    case _: next(doc,Fail{768})

@unsafe
def mapped_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,F.Mapped>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Mapped>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,F.Mapped,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span => fields(T,[5,6],doc,value,context,[],doc => values => mapped_fields(T,values,U32.sub(n,1),U32.add(at,span),doc,context,acc,next))))

@unsafe
def mapped_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,F.Mapped>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Mapped>> -> T) -> T:
  match values:
    case source <> states <> Nil{}: A.array(T,states,doc,0,doc => states => mapped_result(T,D.integer(source),states,n,at,doc,context,acc,next))
    case _: next(doc,Fail{768})

@unsafe
def mapped_result(-T: Type,source: Result<&1,&1,U32,U32>,states: Result<&1,&1,U32,Q.Table<U32>>,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,F.Mapped>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Mapped>> -> T) -> T:
  match source states:
    case Done{id} Done{states}: mapped_items(T,n,at,doc,context,F.Mapped{id,states} <> acc,next)
    case _ _: next(doc,Fail{768})

@unsafe
def mushroom(-T: Type,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Mushroom> -> T) -> T:
  fields(T,[0,1,2,3,4,7,8,9],doc,value,context,[],doc => values => mushroom_fields(T,values,doc,context,next))

@unsafe
def mushroom_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Mushroom> -> T) -> T:
  match values:
    case red <> radius <> cap <> stem <> faces <> support <> clearance <> replaceable <> Nil{}:
      RB.parse(T,cap,doc,providers(context),doc => cap => RB.parse(T,stem,doc,providers(context),doc => stem =>
        mapped(T,faces,doc,context,doc => faces => predicates(T,[support,clearance,replaceable],doc,context,[],doc => ps => mushroom_result(T,M.boolean(red),D.integer(radius),cap,stem,faces,ps,doc,next)))))
    case _: next(doc,Fail{768})

def mushroom_result(-T: Type,red: Result<&1,&1,U32,Bool>,radius: Result<&1,&1,U32,U32>,cap: Result<&1,&1,U32,B.Provider>,stem: Result<&1,&1,U32,B.Provider>,faces: Result<&1,&1,U32,List<&2,F.Mapped>>,ps: Result<&1,&1,U32,List<&2,P.Predicate>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Mushroom> -> T) -> T:
  match red radius cap stem faces ps:
    case Done{r} Done{radius} Done{cap} Done{stem} Done{faces} Done{support <> clearance <> replaceable <> Nil{}}: next(doc,Done{F.Mushroom{r,radius,cap,stem,faces,support,clearance,replaceable}})
    case _ _ _ _ _ _: next(doc,Fail{768})

@unsafe
def directions(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Position>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: direction_items(T,n,U32.add(at,3),doc,[],next)
    case _: next(doc,Fail{768})

@unsafe
def direction_items(-T: Type,+n: U32,+at: U32,doc: R.Document,acc: List<&2,P.Position>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Position>> -> T) -> T:
  match n:
    case 0: next(doc,Done{Q.prepared_table(P.Position,List.reverse(&2,P.Position,acc))})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span => A.array(T,Some{value},doc,2,doc => result => direction_result(T,result,U32.sub(n,1),U32.add(at,span),doc,acc,next))))

@unsafe
def direction_result(-T: Type,result: Result<&1,&1,U32,Q.Table<U32>>,+n: U32,+at: U32,doc: R.Document,acc: List<&2,P.Position>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Position>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{768})
    case Done{+xs}: direction_size(T,U32.is_eq(Q.table_size(U32,xs),3),xs,n,at,doc,acc,next)

@unsafe
def direction_size(-T: Type,valid: Bool,+xs: Q.Table<U32>,n: U32,at: U32,doc: R.Document,acc: List<&2,P.Position>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Position>> -> T) -> T:
  match valid:
    case False{}: next(doc,Fail{768})
    case True{}: direction_items(T,n,at,doc,P.Position{Q.get(U32,xs,0,0),Q.get(U32,xs,1,0),Q.get(U32,xs,2,0)} <> acc,next)

@unsafe
def shelves(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Q.Table<Q.Table<U32>>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: shelf_items(T,n,U32.add(at,3),doc,[],next)
    case _: next(doc,Fail{768})

@unsafe
def shelf_items(-T: Type,+n: U32,+at: U32,doc: R.Document,acc: List<&2,Q.Table<U32>>,next: R.Document -> Result<&1,&1,U32,Q.Table<Q.Table<U32>>> -> T) -> T:
  match n:
    case 0: next(doc,Done{Q.prepared_table(Q.Table<U32>,List.reverse(&2,Q.Table<U32>,acc))})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span => A.array(T,Some{value},doc,0,doc => result => shelf_result(T,result,U32.sub(n,1),U32.add(at,span),doc,acc,next))))

@unsafe
def shelf_result(-T: Type,result: Result<&1,&1,U32,Q.Table<U32>>,n: U32,at: U32,doc: R.Document,acc: List<&2,Q.Table<U32>>,next: R.Document -> Result<&1,&1,U32,Q.Table<Q.Table<U32>>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{768})
    case Done{row}: shelf_items(T,n,at,doc,row <> acc,next)

@unsafe
def decorators(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,List<&2,F.Decorator>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: decorator_items(T,n,U32.add(at,3),doc,context,[],next)
    case _: next(doc,Fail{768})

@unsafe
def decorator_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,F.Decorator>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Decorator>> -> T) -> T:
  match n context:
    case 0 _: next(doc,Done{List.reverse(&2,F.Decorator,acc)})
    case _ Context{keys,_}: R.value(T,doc,at,doc => +value => R.get(T,doc,U32.inc(at),doc => span => D.named(T,doc,value,keys,19n,doc => found =>
      decorator_type(T,found,value,doc,context,doc => result => decorator_result(T,result,U32.sub(n,1),U32.add(at,span),doc,context,acc,next)))))

@unsafe
def decorator_type(-T: Type,found: Maybe<&2,R.Value>,+value: R.Value,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Decorator> -> T) -> T:
  match found context:
    case Some{R.Value{5,+id,_,_}} Context{+keys,_}: decorator_known(T,U32.is_eq(id,D.key(keys,23n)),U32.is_eq(id,D.key(keys,24n)),U32.is_eq(id,D.key(keys,25n)),value,doc,context,next)
    case _ _: next(doc,Fail{768})

@unsafe
def decorator_known(-T: Type,attached: Bool,vine: Bool,shelf: Bool,value: R.Value,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Decorator> -> T) -> T:
  match attached vine shelf:
    case True{} _ _: fields(T,[20,21,22],doc,value,context,[],doc => values => attached_fields(T,values,doc,context,next))
    case False{} True{} _: fields(T,[6],doc,value,context,[],doc => values => vine_fields(T,values,doc,next))
    case False{} False{} True{}: fields(T,[20,6],doc,value,context,[],doc => values => shelf_fields(T,values,doc,next))
    case _ _ _: next(doc,Fail{768})

@unsafe
def attached_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,context: Context,next: R.Document -> Result<&1,&1,U32,F.Decorator> -> T) -> T:
  match values:
    case probability <> provider <> ds <> Nil{}: RB.parse(T,provider,doc,providers(context),doc => provider => directions(T,ds,doc,doc => ds => attached_result(T,D.number(probability),provider,ds,doc,next)))
    case _: next(doc,Fail{768})

def attached_result(-T: Type,p: Result<&1,&1,U32,F32>,provider: Result<&1,&1,U32,B.Provider>,ds: Result<&1,&1,U32,Q.Table<P.Position>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Decorator> -> T) -> T:
  match p provider ds:
    case Done{p} Done{provider} Done{ds}: next(doc,Done{F.Attached{p,provider,ds}})
    case _ _ _: next(doc,Fail{768})

@unsafe
def vine_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Decorator> -> T) -> T:
  match values:
    case states <> Nil{}: A.array(T,states,doc,0,doc => result => vine_result(T,result,doc,next))
    case _: next(doc,Fail{768})

def vine_result(-T: Type,result: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Decorator> -> T) -> T:
  match result:
    case Done{states}: next(doc,Done{F.Vine{states}})
    case Fail{_}: next(doc,Fail{768})

@unsafe
def shelf_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Decorator> -> T) -> T:
  match values:
    case probability <> states <> Nil{}: shelves(T,states,doc,doc => result => shelf_decorator(T,D.number(probability),result,doc,next))
    case _: next(doc,Fail{768})

def shelf_decorator(-T: Type,p: Result<&1,&1,U32,F32>,states: Result<&1,&1,U32,Q.Table<Q.Table<U32>>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Decorator> -> T) -> T:
  match p states:
    case Done{p} Done{states}: next(doc,Done{F.Shelf{p,states}})
    case _ _: next(doc,Fail{768})

@unsafe
def decorator_result(-T: Type,result: Result<&1,&1,U32,F.Decorator>,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,F.Decorator>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Decorator>> -> T) -> T:
  match result:
    case Done{d}: decorator_items(T,n,at,doc,context,d <> acc,next)
    case Fail{_}: next(doc,Fail{768})

@unsafe
def fallen(-T: Type,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Fallen> -> T) -> T:
  fields(T,[10,11,12,13,14,8,15,16,9,17,18],doc,value,context,[],doc => values => fallen_fields(T,values,doc,context,next))

@unsafe
def fallen_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Fallen> -> T) -> T:
  match values context:
    case trunk <> length <> axes <> stump <> logs <> clearance <> sturdy <> air <> replaceable <> water <> shelf <> Nil{} Context{_,RB.Context{_,integers,_}}:
      RB.parse(T,trunk,doc,providers(context),doc => trunk => RI.prepare(T,length,doc,integers,doc => length => mapped(T,axes,doc,context,doc => axes =>
        decorators(T,stump,doc,context,doc => stump => decorators(T,logs,doc,context,doc => logs =>
          predicates(T,[clearance,sturdy,air,replaceable,water,shelf],doc,context,[],doc => ps => fallen_result(T,trunk,length,axes,stump,logs,ps,doc,next)))))))
    case _ _: next(doc,Fail{768})

def fallen_result(-T: Type,trunk: Result<&1,&1,U32,B.Provider>,length: Result<&1,&1,U32,I.Provider>,axes: Result<&1,&1,U32,List<&2,F.Mapped>>,stump: Result<&1,&1,U32,List<&2,F.Decorator>>,logs: Result<&1,&1,U32,List<&2,F.Decorator>>,ps: Result<&1,&1,U32,List<&2,P.Predicate>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Fallen> -> T) -> T:
  match trunk length axes stump logs ps:
    case Done{trunk} Done{length} Done{axes} Done{stump} Done{logs} Done{clearance <> sturdy <> air <> replaceable <> water <> shelf <> Nil{}}:
      next(doc,Done{F.Fallen{trunk,length,axes,stump,logs,clearance,sturdy,air,replaceable,water,shelf}})
    case _ _ _ _ _ _: next(doc,Fail{768})
