import Base
import ./registry.bend as R
import ./registry_density.bend as D
import ./registry_material.bend as M
import ./registry_cave_features.bend as A
import ./registry_placement_predicate.bend as RP
import ./attachment_data.bend as F
import ./placement_data.bend as P
import ./density.bend as Q

type Context is Data:
  Context{keys: D.Keys,predicates: D.Keys}

@unsafe
def context(-T: Type,doc: R.Document,next: R.Document -> Context -> T) -> T:
  D.resolved(T,["vines","initial","wet_initial","face_mask","states","directions","search_range","spread_chance","spread_order","sculk",
    "air","water","source_water","placed_on","spread_replaceable","spread_support","support","sturdy","source","faces","added"],doc,[],doc => keys =>
      RP.keys(T,doc,doc => predicates => next(doc,Context{keys,predicates})))

@unsafe
def fields(-T: Type,indices: List<&2,U32>,doc: R.Document,+value: R.Value,+context: Context,acc: List<&2,Maybe<&2,R.Value>>,next: R.Document -> List<&2,Maybe<&2,R.Value>> -> T) -> T:
  match indices context:
    case Nil{} _: next(doc,List.reverse(&2,Maybe<&2,R.Value>,acc))
    case i <> rest Context{keys,_}: D.named(T,doc,value,keys,U32.to_nat(i),doc => found => fields(T,rest,doc,value,context,found <> acc,next))

@unsafe
def array(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Q.Table<U32>> -> T) -> T:
  match found:
    case None{}: next(doc,Fail{769})
    case Some{value}: A.array(T,Some{value},doc,0,next)

@unsafe
def flatten(xs: Q.Table<U32>,acc: List<&2,U32>) -> List<&2,U32>:
  match xs:
    case Q.Empty{}: acc
    case Q.Leaf{v}: v <> acc
    case Q.Branch{_,a,b}: flatten(a,flatten(b,acc))

@unsafe
def config(vines: Result<&1,&1,U32,Bool>,initial: Result<&1,&1,U32,U32>,wet: Result<&1,&1,U32,U32>,mask: Result<&1,&1,U32,U32>,ds: Result<&1,&1,U32,Q.Table<U32>>,range: Result<&1,&1,U32,U32>,chance: Result<&1,&1,U32,F32>,os: Result<&1,&1,U32,Q.Table<U32>>,sculk: Result<&1,&1,U32,Bool>) -> Result<&1,&1,U32,F.Settings>:
  match vines initial wet mask ds range chance os sculk:
    case Done{v} Done{i} Done{w} Done{m} Done{ds} Done{r} Done{p} Done{os} Done{s}: Done{F.Settings{v,i,w,m,flatten(ds,[]),r,p,flatten(os,[]),s}}
    case _ _ _ _ _ _ _ _ _: Fail{769}

@unsafe
def predicates(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,context: Context,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Predicate>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: predicate_items(T,n,U32.add(at,3),doc,context,[],next)
    case _: next(doc,Fail{769})

@unsafe
def predicate_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,P.Predicate>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Predicate>> -> T) -> T:
  match n context:
    case 0 _: next(doc,Done{Q.prepared_table(P.Predicate,List.reverse(&2,P.Predicate,acc))})
    case _ Context{_,keys}: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span => RP.parse(T,Some{value},doc,keys,doc => result =>
      predicate_result(T,result,U32.sub(n,1),U32.add(at,span),doc,context,acc,next))))

@unsafe
def predicate_result(-T: Type,result: Result<&1,&1,U32,P.Predicate>,n: U32,at: U32,doc: R.Document,context: Context,acc: List<&2,P.Predicate>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Predicate>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{769})
    case Done{p}: predicate_items(T,n,at,doc,context,p <> acc,next)

@unsafe
def tests(-T: Type,xs: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,acc: List<&2,P.Predicate>,next: R.Document -> Result<&1,&1,U32,List<&2,P.Predicate>> -> T) -> T:
  match xs context:
    case Nil{} _: next(doc,Done{List.reverse(&2,P.Predicate,acc)})
    case value <> rest Context{_,keys}: RP.parse(T,value,doc,keys,doc => result => test_result(T,result,rest,doc,context,acc,next))

@unsafe
def test_result(-T: Type,result: Result<&1,&1,U32,P.Predicate>,rest: List<&2,Maybe<&2,R.Value>>,doc: R.Document,context: Context,acc: List<&2,P.Predicate>,next: R.Document -> Result<&1,&1,U32,List<&2,P.Predicate>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{769})
    case Done{p}: tests(T,rest,doc,context,p <> acc,next)

@unsafe
def states(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,context: Context,next: R.Document -> Result<&1,&1,U32,Q.Table<F.State>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: state_items(T,n,U32.add(at,3),doc,context,[],next)
    case _: next(doc,Fail{769})

@unsafe
def state_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,F.State>,next: R.Document -> Result<&1,&1,U32,Q.Table<F.State>> -> T) -> T:
  match n:
    case 0: next(doc,Done{Q.prepared_table(F.State,List.reverse(&2,F.State,acc))})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span => fields(T,[18,19,20],doc,value,context,[],doc => values =>
      state_fields(T,values,U32.sub(n,1),U32.add(at,span),doc,context,acc,next))))

@unsafe
def state_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,n: U32,at: U32,doc: R.Document,context: Context,acc: List<&2,F.State>,next: R.Document -> Result<&1,&1,U32,Q.Table<F.State>> -> T) -> T:
  match values:
    case source <> faces <> added <> Nil{}: array(T,added,doc,doc => added => state_result(T,D.integer(source),D.integer(faces),added,n,at,doc,context,acc,next))
    case _: next(doc,Fail{769})

@unsafe
def state_result(-T: Type,source: Result<&1,&1,U32,U32>,faces: Result<&1,&1,U32,U32>,added: Result<&1,&1,U32,Q.Table<U32>>,n: U32,at: U32,doc: R.Document,context: Context,acc: List<&2,F.State>,next: R.Document -> Result<&1,&1,U32,Q.Table<F.State>> -> T) -> T:
  match source faces added:
    case Done{s} Done{f} Done{a}: state_items(T,n,at,doc,context,F.State{s,f,a} <> acc,next)
    case _ _ _: next(doc,Fail{769})

@unsafe
def parse(-T: Type,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Recipe> -> T) -> T:
  fields(T,[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17],doc,value,context,[],doc => values => collected(T,values,doc,context,next))

@unsafe
def collected(-T: Type,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Recipe> -> T) -> T:
  match values:
    case vines <> initial <> wet <> mask <> rows <> ds <> range <> chance <> os <> sculk <> air <> water <> source <> on <> replace <> support <> faces <> sturdy <> Nil{}:
      array(T,ds,doc,doc => ds => array(T,os,doc,doc => os => states(T,rows,doc,context,doc => rows =>
        tests(T,[air,water,source,on,replace,support],doc,context,[],doc => tests => predicates(T,faces,doc,context,doc => faces =>
          predicates(T,sturdy,doc,context,doc => sturdy => finished(T,config(M.boolean(vines),D.integer(initial),D.integer(wet),D.integer(mask),ds,D.integer(range),D.number(chance),os,M.boolean(sculk)),rows,tests,faces,sturdy,doc,next)))))))
    case _: next(doc,Fail{769})

def finished(-T: Type,settings: Result<&1,&1,U32,F.Settings>,states: Result<&1,&1,U32,Q.Table<F.State>>,tests: Result<&1,&1,U32,List<&2,P.Predicate>>,faces: Result<&1,&1,U32,Q.Table<P.Predicate>>,sturdy: Result<&1,&1,U32,Q.Table<P.Predicate>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Recipe> -> T) -> T:
  match settings states tests faces sturdy:
    case Done{settings} Done{states} Done{air <> water <> source <> on <> replace <> support <> Nil{}} Done{faces} Done{sturdy}: next(doc,Done{F.Recipe{settings,states,F.Surfaces{air,water,source,on,replace,support,faces,sturdy}}})
    case _ _ _ _ _: next(doc,Fail{769})
