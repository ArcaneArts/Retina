import Base
import ./registry.bend as R
import ./registry_density.bend as D
import ./registry_material.bend as M
import ./registry_cave_features.bend as Array
import ./registry_block_provider.bend as RB
import ./registry_int_provider.bend as RI
import ./registry_placement_predicate.bend as RP
import ./registry_attachment.bend as RA
import ./registry_forest.bend as RF
import ./registry_nested_placement.bend as NP
import ./nested_feature.bend as F
import ./vegetation_patch.bend as V
import ./block_provider.bend as B
import ./int_provider.bend as I
import ./placement_data.bend as P
import ./density.bend as Q
import ./word64.bend as W

type Context is Data:
  Context{keys: D.Keys,providers: RB.Context,programs: NP.Context}
type Choice<-A: Data> is Data:
  Choice{chance: F32,weight: U32,placed: F.Placed<F.Feature<A>>}

@unsafe
def context(-T: Type,doc: R.Document,+providers: RB.Context,next: R.Document -> Context -> T) -> T:
  D.resolved(T,["kind","placement","feature","features","options","default","when_true","when_false","entries","chance","weight","placed",
    "ground","same_blocks","replaceable","air","sturdy","direction","depth","extra_bottom_block_chance","vertical_range","vegetation_chance","xz_radius","extra_edge_column_chance",
    "vegetation","water_pool","enclosed","waterlogged_states","source","predicate","vegetation_patch","sequence","overlay","random_selector","simple_selector","boolean_selector","weighted_selector"],doc,[],doc => keys =>
      NP.context(T,doc,doc => programs => next(doc,Context{keys,providers,programs})))

@unsafe
def fields(-T: Type,indices: List<&2,U32>,doc: R.Document,+value: R.Value,+ctx: Context,acc: List<&2,Maybe<&2,R.Value>>,next: R.Document -> List<&2,Maybe<&2,R.Value>> -> T) -> T:
  match indices ctx:
    case Nil{} _: next(doc,List.reverse(&2,Maybe<&2,R.Value>,acc))
    case i <> rest Context{keys,_,_}: D.named(T,doc,value,keys,U32.to_nat(i),doc => found => fields(T,rest,doc,value,ctx,found <> acc,next))

@unsafe
def name(+keys: D.Keys,+id: U32,+i: U32) -> U32:
  name_equal(U32.is_eq(id,D.key(keys,U32.to_nat(U32.add(i,30)))),keys,id,i)

@unsafe
def name_equal(equal: Bool,+keys: D.Keys,+id: U32,+i: U32) -> U32:
  match equal:
    case True{}: i
    case False{}: name_more(U32.is_lt(i,6),keys,id,U32.inc(i))

@unsafe
def name_more(more: Bool,keys: D.Keys,id: U32,i: U32) -> U32:
  match more:
    case False{}: 4294967295
    case True{}: name(keys,id,i)

@unsafe
def providers(ctx: Context) -> RB.Context:
  match ctx:
    case Context{_,providers,_}: providers

@unsafe
def integers(ctx: Context) -> D.Keys:
  match ctx:
    case Context{_,RB.Context{_,integers,_},_}: integers

@unsafe
def predicates(ctx: Context) -> D.Keys:
  match ctx:
    case Context{_,RB.Context{_,_,predicates},_}: predicates

@unsafe
def predicate_context(ctx: Context) -> RA.Context:
  match ctx:
    case Context{keys,RB.Context{_,_,predicates},_}: RA.Context{keys,predicates}

@unsafe
def forest_context(ctx: Context) -> RF.Context:
  match ctx:
    case Context{keys,providers,_}: RF.Context{keys,providers}

@unsafe
def flatten(-A: Data,xs: Q.Table<A>,acc: List<&2,A>) -> List<&2,A>:
  match xs:
    case Q.Empty{}: acc
    case Q.Leaf{v}: v <> acc
    case Q.Branch{_,a,b}: flatten(A,a,flatten(A,b,acc))

@unsafe
def parse(-T: Type,-A: Data,-C: Data,doc: R.Document,+value: R.Value,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match ctx:
    case Context{keys,_,_}: D.named(T,doc,value,keys,0n,doc => found => identified(T,A,C,found,doc,value,ctx,leaf_context,leaf,next))

@unsafe
def identified(-T: Type,-A: Data,-C: Data,found: Maybe<&2,R.Value>,doc: R.Document,+value: R.Value,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match found ctx:
    case Some{R.Value{5,id,_,_}} Context{keys,_,_}: kind(T,A,C,name(keys,id,0),doc,value,ctx,leaf_context,leaf,next)
    case _ _: next(doc,Fail{771})

@unsafe
def leaf_result(-T: Type,-A: Data,result: Result<&1,&1,U32,A>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{771})
    case Done{value}: next(doc,Done{F.Leaf{value}})

@unsafe
def kind(-T: Type,-A: Data,-C: Data,id: U32,doc: R.Document,+value: R.Value,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match id:
    case 0: fields(T,[12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27],doc,value,ctx,[],doc => values => patch_fields(T,A,C,values,doc,ctx,leaf_context,leaf,next))
    case 1: fields(T,[3],doc,value,ctx,[],doc => values => children_fields(T,A,C,values,1,doc,ctx,leaf_context,leaf,next))
    case 2: fields(T,[3],doc,value,ctx,[],doc => values => children_fields(T,A,C,values,2,doc,ctx,leaf_context,leaf,next))
    case 3: fields(T,[4,5],doc,value,ctx,[],doc => values => random_fields(T,A,C,values,doc,ctx,leaf_context,leaf,next))
    case 4: fields(T,[3],doc,value,ctx,[],doc => values => children_fields(T,A,C,values,4,doc,ctx,leaf_context,leaf,next))
    case 5: fields(T,[6,7],doc,value,ctx,[],doc => values => boolean_fields(T,A,C,values,doc,ctx,leaf_context,leaf,next))
    case 6: fields(T,[8],doc,value,ctx,[],doc => values => weighted_fields(T,A,C,values,doc,ctx,leaf_context,leaf,next))
    case _: leaf(leaf_context,doc,value,doc => result => leaf_result(T,A,result,doc,next))

@unsafe
def placed(-T: Type,-A: Data,-C: Data,found: Maybe<&2,R.Value>,doc: R.Document,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,F.Placed<F.Feature<A>>> -> T) -> T:
  match found:
    case None{}: next(doc,Fail{771})
    case Some{value}: fields(T,[1,2],doc,value,ctx,[],doc => values => placed_fields(T,A,C,values,doc,ctx,leaf_context,leaf,next))

@unsafe
def placed_fields(-T: Type,-A: Data,-C: Data,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,F.Placed<F.Feature<A>>> -> T) -> T:
  match values ctx:
    case program <> Some{feature} <> Nil{} Context{_,_,programs}: NP.prepare(T,program,doc,programs,doc => program => parse(T,A,C,doc,feature,ctx,leaf_context,leaf,doc => feature => placed_result(T,A,program,feature,doc,next)))
    case _ _: next(doc,Fail{771})

@unsafe
def placed_result(-T: Type,-A: Data,program: Result<&1,&1,U32,Q.Table<P.Modifier>>,feature: Result<&1,&1,U32,F.Feature<A>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Placed<F.Feature<A>>> -> T) -> T:
  match program feature:
    case Done{p} Done{f}: next(doc,Done{F.Placed{p,f}})
    case _ _: next(doc,Fail{771})

@unsafe
def children(-T: Type,-A: Data,-C: Data,found: Maybe<&2,R.Value>,doc: R.Document,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,List<&2,F.Placed<F.Feature<A>>>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: child_items(T,A,C,n,U32.add(at,3),doc,ctx,leaf_context,leaf,[],next)
    case _: next(doc,Fail{771})

@unsafe
def child_items(-T: Type,-A: Data,-C: Data,+n: U32,+at: U32,doc: R.Document,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,acc: List<&2,F.Placed<F.Feature<A>>>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Placed<F.Feature<A>>>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,F.Placed<F.Feature<A>>,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span => placed(T,A,C,Some{value},doc,ctx,leaf_context,leaf,doc => result => child_result(T,A,C,result,U32.sub(n,1),U32.add(at,span),doc,ctx,leaf_context,leaf,acc,next))))

@unsafe
def child_result(-T: Type,-A: Data,-C: Data,result: Result<&1,&1,U32,F.Placed<F.Feature<A>>>,n: U32,at: U32,doc: R.Document,ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,acc: List<&2,F.Placed<F.Feature<A>>>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Placed<F.Feature<A>>>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{771})
    case Done{p}: child_items(T,A,C,n,at,doc,ctx,leaf_context,leaf,p <> acc,next)

@unsafe
def children_fields(-T: Type,-A: Data,-C: Data,values: List<&2,Maybe<&2,R.Value>>,id: U32,doc: R.Document,ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match values:
    case found <> Nil{}: children(T,A,C,found,doc,ctx,leaf_context,leaf,doc => result => children_result(T,A,result,id,doc,next))
    case _: next(doc,Fail{771})

@unsafe
def children_result(-T: Type,-A: Data,result: Result<&1,&1,U32,List<&2,F.Placed<F.Feature<A>>>>,id: U32,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match result id:
    case Done{xs} 4: next(doc,Done{F.Simple{xs}})
    case Done{xs} _: next(doc,Done{F.Composite{U32.is_eq(id,1),xs}})
    case _ _: next(doc,Fail{771})

@unsafe
def choice_items(-T: Type,-A: Data,-C: Data,+n: U32,+at: U32,+weighted: Bool,doc: R.Document,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,acc: List<&2,Choice<A>>,next: R.Document -> Result<&1,&1,U32,List<&2,Choice<A>>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,Choice<A>,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span => fields(T,[U32.add(9,W.flag(weighted)),11],doc,value,ctx,[],doc => values => choice_fields(T,A,C,values,U32.sub(n,1),U32.add(at,span),weighted,doc,ctx,leaf_context,leaf,acc,next))))

@unsafe
def choice_fields(-T: Type,-A: Data,-C: Data,values: List<&2,Maybe<&2,R.Value>>,+n: U32,+at: U32,+weighted: Bool,doc: R.Document,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,acc: List<&2,Choice<A>>,next: R.Document -> Result<&1,&1,U32,List<&2,Choice<A>>> -> T) -> T:
  match values:
    case parameter <> child <> Nil{}: placed(T,A,C,child,doc,ctx,leaf_context,leaf,doc => result => choice_result(T,A,C,result,parameter,n,at,weighted,doc,ctx,leaf_context,leaf,acc,next))
    case _: next(doc,Fail{771})

@unsafe
def choice_result(-T: Type,-A: Data,-C: Data,result: Result<&1,&1,U32,F.Placed<F.Feature<A>>>,parameter: Maybe<&2,R.Value>,n: U32,at: U32,weighted: Bool,doc: R.Document,ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,acc: List<&2,Choice<A>>,next: R.Document -> Result<&1,&1,U32,List<&2,Choice<A>>> -> T) -> T:
  match result weighted:
    case Done{row} False{}: chance_value(T,A,C,D.number(parameter),row,n,at,doc,ctx,leaf_context,leaf,acc,next)
    case Done{row} True{}: weight_value(T,A,C,D.integer(parameter),row,n,at,doc,ctx,leaf_context,leaf,acc,next)
    case _ _: next(doc,Fail{771})

@unsafe
def chance_value(-T: Type,-A: Data,-C: Data,result: Result<&1,&1,U32,F32>,row: F.Placed<F.Feature<A>>,n: U32,at: U32,doc: R.Document,ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,acc: List<&2,Choice<A>>,next: R.Document -> Result<&1,&1,U32,List<&2,Choice<A>>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{771})
    case Done{value}: choice_items(T,A,C,n,at,False{},doc,ctx,leaf_context,leaf,Choice{value,0,row} <> acc,next)

@unsafe
def weight_value(-T: Type,-A: Data,-C: Data,result: Result<&1,&1,U32,U32>,row: F.Placed<F.Feature<A>>,n: U32,at: U32,doc: R.Document,ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,acc: List<&2,Choice<A>>,next: R.Document -> Result<&1,&1,U32,List<&2,Choice<A>>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{771})
    case Done{value}: choice_items(T,A,C,n,at,True{},doc,ctx,leaf_context,leaf,Choice{0.0,value,row} <> acc,next)

@unsafe
def choices(-T: Type,-A: Data,-C: Data,found: Maybe<&2,R.Value>,weighted: Bool,doc: R.Document,ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,List<&2,Choice<A>>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: choice_items(T,A,C,n,U32.add(at,3),weighted,doc,ctx,leaf_context,leaf,[],next)
    case _: next(doc,Fail{771})

@unsafe
def options(-A: Data,xs: List<&2,Choice<A>>) -> List<&2,F.Option<A>>:
  match xs:
    case Nil{}: []
    case Choice{p,_,row} <> rest: F.Option{p,row} <> options(A,rest)

@unsafe
def entries(-A: Data,xs: List<&2,Choice<A>>) -> List<&2,F.Entry<A>>:
  match xs:
    case Nil{}: []
    case Choice{_,w,row} <> rest: F.Entry{w,row} <> entries(A,rest)

@unsafe
def random_fields(-T: Type,-A: Data,-C: Data,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match values:
    case list <> fallback <> Nil{}: choices(T,A,C,list,False{},doc,ctx,leaf_context,leaf,doc => list => placed(T,A,C,fallback,doc,ctx,leaf_context,leaf,doc => fallback => random_result(T,A,list,fallback,doc,next)))
    case _: next(doc,Fail{771})

@unsafe
def random_result(-T: Type,-A: Data,list: Result<&1,&1,U32,List<&2,Choice<A>>>,fallback: Result<&1,&1,U32,F.Placed<F.Feature<A>>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match list fallback:
    case Done{xs} Done{f}: next(doc,Done{F.Random{options(A,xs),f}})
    case _ _: next(doc,Fail{771})

@unsafe
def boolean_fields(-T: Type,-A: Data,-C: Data,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match values:
    case a <> b <> Nil{}: placed(T,A,C,a,doc,ctx,leaf_context,leaf,doc => a => placed(T,A,C,b,doc,ctx,leaf_context,leaf,doc => b => boolean_result(T,A,a,b,doc,next)))
    case _: next(doc,Fail{771})

@unsafe
def boolean_result(-T: Type,-A: Data,a: Result<&1,&1,U32,F.Placed<F.Feature<A>>>,b: Result<&1,&1,U32,F.Placed<F.Feature<A>>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match a b:
    case Done{a} Done{b}: next(doc,Done{F.Boolean{a,b}})
    case _ _: next(doc,Fail{771})

@unsafe
def weighted_fields(-T: Type,-A: Data,-C: Data,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match values:
    case found <> Nil{}: choices(T,A,C,found,True{},doc,ctx,leaf_context,leaf,doc => result => weighted_result(T,A,result,doc,next))
    case _: next(doc,Fail{771})

@unsafe
def weighted_result(-T: Type,-A: Data,result: Result<&1,&1,U32,List<&2,Choice<A>>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{771})
    case Done{xs}:
      +rows = entries(A,xs)
      next(doc,Done{F.Weighted{rows,F.weight_sum(A,rows,W.U64{0,0})}})

@unsafe
def same(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,ctx: Context,next: R.Document -> Result<&1,&1,U32,List<&2,V.Same>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: same_items(T,n,U32.add(at,3),doc,ctx,[],next)
    case _: next(doc,Fail{771})

@unsafe
def same_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+ctx: Context,acc: List<&2,V.Same>,next: R.Document -> Result<&1,&1,U32,List<&2,V.Same>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,V.Same,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span => fields(T,[28,29],doc,value,ctx,[],doc => values => same_fields(T,values,U32.sub(n,1),U32.add(at,span),doc,ctx,acc,next))))

@unsafe
def same_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,n: U32,at: U32,doc: R.Document,+ctx: Context,acc: List<&2,V.Same>,next: R.Document -> Result<&1,&1,U32,List<&2,V.Same>> -> T) -> T:
  match values:
    case source <> predicate <> Nil{}: RP.parse(T,predicate,doc,predicates(ctx),doc => p => same_result(T,D.integer(source),p,n,at,doc,ctx,acc,next))
    case _: next(doc,Fail{771})

@unsafe
def same_result(-T: Type,source: Result<&1,&1,U32,U32>,p: Result<&1,&1,U32,P.Predicate>,n: U32,at: U32,doc: R.Document,ctx: Context,acc: List<&2,V.Same>,next: R.Document -> Result<&1,&1,U32,List<&2,V.Same>> -> T) -> T:
  match source p:
    case Done{id} Done{p}: same_items(T,n,at,doc,ctx,V.Same{id,p} <> acc,next)
    case _ _: next(doc,Fail{771})

@unsafe
def wet(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,List<&2,V.Wet>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: wet_items(T,n,U32.add(at,3),doc,[],next)
    case _: next(doc,Fail{771})

@unsafe
def wet_items(-T: Type,+n: U32,+at: U32,doc: R.Document,acc: List<&2,V.Wet>,next: R.Document -> Result<&1,&1,U32,List<&2,V.Wet>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,V.Wet,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span => Array.array(T,Some{value},doc,0,doc => result => wet_result(T,result,U32.sub(n,1),U32.add(at,span),doc,acc,next))))

@unsafe
def wet_result(-T: Type,result: Result<&1,&1,U32,Q.Table<U32>>,n: U32,at: U32,doc: R.Document,acc: List<&2,V.Wet>,next: R.Document -> Result<&1,&1,U32,List<&2,V.Wet>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{771})
    case Done{+xs}: wet_size(T,U32.is_eq(Q.table_size(U32,xs),2),xs,n,at,doc,acc,next)

@unsafe
def wet_size(-T: Type,valid: Bool,+xs: Q.Table<U32>,n: U32,at: U32,doc: R.Document,acc: List<&2,V.Wet>,next: R.Document -> Result<&1,&1,U32,List<&2,V.Wet>> -> T) -> T:
  match valid:
    case False{}: next(doc,Fail{771})
    case True{}: wet_items(T,n,at,doc,V.Wet{Q.get(U32,xs,0,0),Q.get(U32,xs,1,0)} <> acc,next)

def settings(direction: Result<&1,&1,U32,U32>,depth: Result<&1,&1,U32,I.Provider>,bottom: Result<&1,&1,U32,F32>,range: Result<&1,&1,U32,U32>,chance: Result<&1,&1,U32,F32>,radius: Result<&1,&1,U32,I.Provider>,edge: Result<&1,&1,U32,F32>,pool: Result<&1,&1,U32,Bool>) -> Result<&1,&1,U32,V.Settings>:
  match direction depth bottom range chance radius edge pool:
    case Done{d} Done{depth} Done{b} Done{r} Done{c} Done{radius} Done{e} Done{p}: Done{V.Settings{d,depth,b,r,c,radius,e,p}}
    case _ _ _ _ _ _ _ _: Fail{771}

@unsafe
def patch_fields(-T: Type,-A: Data,-C: Data,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+ctx: Context,+leaf_context: C,+leaf: C -> R.Document -> R.Value -> (R.Document -> Result<&1,&1,U32,A> -> T) -> T,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match values:
    case ground <> same <> replace <> air <> sturdy <> direction <> depth <> bottom <> range <> chance <> radius <> edge <> vegetation <> pool <> enclosed <> wet <> Nil{}:
      RB.parse(T,ground,doc,providers(ctx),doc => ground => RI.prepare(T,depth,doc,integers(ctx),doc => depth => RI.prepare(T,radius,doc,integers(ctx),doc => radius =>
        placed(T,A,C,vegetation,doc,ctx,leaf_context,leaf,doc => vegetation => patch_materials(T,A,same,wet,[replace,air,sturdy],enclosed,ground,settings(D.signed(direction),depth,D.number(bottom),D.integer(range),D.number(chance),radius,D.number(edge),M.boolean(pool)),vegetation,doc,ctx,next)))))
    case _: next(doc,Fail{771})

@unsafe
def patch_materials(-T: Type,-A: Data,same_value: Maybe<&2,R.Value>,wet_value: Maybe<&2,R.Value>,ps: List<&2,Maybe<&2,R.Value>>,enclosed_value: Maybe<&2,R.Value>,ground: Result<&1,&1,U32,B.Provider>,settings: Result<&1,&1,U32,V.Settings>,vegetation: Result<&1,&1,U32,F.Placed<F.Feature<A>>>,doc: R.Document,+ctx: Context,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  same(T,same_value,doc,ctx,doc => same => wet(T,wet_value,doc,doc => wet => RF.predicates(T,ps,doc,forest_context(ctx),[],doc => ps =>
    RA.predicates(T,enclosed_value,doc,predicate_context(ctx),doc => enclosed => patch_result(T,A,ground,same,settings,vegetation,ps,enclosed,wet,doc,next)))))

@unsafe
def patch_result(-T: Type,-A: Data,ground: Result<&1,&1,U32,B.Provider>,same: Result<&1,&1,U32,List<&2,V.Same>>,settings: Result<&1,&1,U32,V.Settings>,vegetation: Result<&1,&1,U32,F.Placed<F.Feature<A>>>,ps: Result<&1,&1,U32,List<&2,P.Predicate>>,enclosed: Result<&1,&1,U32,Q.Table<P.Predicate>>,wet: Result<&1,&1,U32,List<&2,V.Wet>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature<A>> -> T) -> T:
  match ground same settings vegetation ps enclosed wet:
    case Done{g} Done{s} Done{c} Done{v} Done{replace <> air <> sturdy <> Nil{}} Done{e} Done{w}: next(doc,Done{F.Patch{V.Recipe{g,s,replace,air,sturdy,c,v,flatten(P.Predicate,e,[]),w}}})
    case _ _ _ _ _ _ _: next(doc,Fail{771})
