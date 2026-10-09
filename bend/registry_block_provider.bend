import Base
import ./registry.bend as R
import ./registry_density.bend as D
import ./registry_cave_features.bend as A
import ./registry_material.bend as M
import ./registry_int_provider.bend as RI
import ./registry_placement_predicate.bend as RP
import ./density.bend as Q
import ./block_provider.bend as B
import ./placement_data.bend as P
import ./word64.bend as W
import ./int_provider.bend as I

type Context is Data:
  Context{keys: D.Keys,integers: D.Keys,predicates: D.Keys}

@unsafe
def context(-T: Type,doc: R.Document,next: R.Document -> Context -> T) -> T:
  D.resolved(T,["type","material","entries","weight","provider","source","values","variants","rules","fallback","predicate","direction","states","program","fast","slow","variety","threshold","high_chance","default_state","low_states","high_states","minimum","passthrough","replacements",
    "state","weighted","randomized_int","rule_based","rotated","copy_properties","random_block","noise","dual_noise","noise_threshold"],doc,[],doc => keys =>
    RI.keys(T,doc,doc => ints => RP.keys(T,doc,doc => predicates => next(doc,Context{keys,ints,predicates}))))

@unsafe
def name(+keys: D.Keys,+id: U32,+i: U32) -> U32:
  name_equal(U32.is_eq(id,D.key(keys,U32.to_nat(U32.add(i,25)))),keys,id,i)

@unsafe
def name_equal(equal: Bool,+keys: D.Keys,+id: U32,+i: U32) -> U32:
  match equal:
    case True{}: i
    case False{}: name_more(U32.is_lt(i,9),keys,id,U32.inc(i))

@unsafe
def name_more(more: Bool,+keys: D.Keys,+id: U32,+i: U32) -> U32:
  match more:
    case False{}: 4294967295
    case True{}: name(keys,id,i)

@unsafe
def fields(-T: Type,indices: List<&2,U32>,doc: R.Document,+value: R.Value,+context: Context,acc: List<&2,Maybe<&2,R.Value>>,next: R.Document -> List<&2,Maybe<&2,R.Value>> -> T) -> T:
  match indices context:
    case Nil{} _: next(doc,List.reverse(&2,Maybe<&2,R.Value>,acc))
    case i <> rest Context{keys,_,_}: D.named(T,doc,value,keys,U32.to_nat(i),doc => found => fields(T,rest,doc,value,context,found <> acc,next))

@unsafe
def array(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Q.Table<U32>> -> T) -> T:
  match found:
    case None{}: next(doc,Fail{764})
    case Some{x}: A.array(T,Some{x},doc,0,next)

def minimum(found: Maybe<&2,R.Value>) -> Result<&1,&1,U32,U32>:
  match found:
    case None{}: Done{0}
    case Some{x}: D.signed(Some{x})

def passthrough(found: Maybe<&2,R.Value>) -> Result<&1,&1,U32,Bool>:
  match found:
    case None{}: Done{False{}}
    case Some{x}: M.boolean(Some{x})

def direction_value(result: Result<&1,&1,U32,U32>) -> Result<&1,&1,U32,Maybe<&2,U32>>:
  match result:
    case Done{x}: Done{Some{x}}
    case _: Fail{764}

def direction(found: Maybe<&2,R.Value>) -> Result<&1,&1,U32,Maybe<&2,U32>>:
  match found:
    case None{}: Done{None{}}
    case Some{x}: direction_value(D.integer(Some{x}))

def state(result: Result<&1,&1,U32,U32>) -> Result<&1,&1,U32,B.Provider>:
  match result:
    case Done{x}: Done{B.State{x}}
    case _: Fail{764}

@unsafe
def replacement_pair(-T: Type,result: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Replacement> -> T) -> T:
  match result:
    case Done{+pair}: replacement_shape(T,U32.is_eq(Q.table_size(U32,pair),2),pair,doc,next)
    case _: next(doc,Fail{764})

@unsafe
def replacement_shape(-T: Type,ok: Bool,+pair: Q.Table<U32>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Replacement> -> T) -> T:
  match ok:
    case False{}: next(doc,Fail{764})
    case True{}: next(doc,Done{B.Replacement{Q.get(U32,pair,0,0),Q.get(U32,pair,1,0)}})

@unsafe
def replacement(-T: Type,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Replacement> -> T) -> T:
  array(T,Some{value},doc,doc => result => replacement_pair(T,result,doc,next))

@unsafe
def transform_fields(-T: Type,fields: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Transform> -> T) -> T:
  match fields:
    case source <> lo <> states <> pass <> replacements <> Nil{}:
      array(T,states,doc,doc => states => replacement_list(T,replacements,doc,context,doc => replacements =>
        transform_results(T,D.integer(source),minimum(lo),states,passthrough(pass),replacements,doc,next)))
    case _: next(doc,Fail{764})

@unsafe
def transform_results(-T: Type,source: Result<&1,&1,U32,U32>,lo: Result<&1,&1,U32,U32>,states: Result<&1,&1,U32,Q.Table<U32>>,pass: Result<&1,&1,U32,Bool>,replacements: Result<&1,&1,U32,List<&2,B.Replacement>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Transform> -> T) -> T:
  match source lo states pass replacements:
    case Done{source} Done{lo} Done{states} Done{pass} Done{replacements}: next(doc,Done{B.Transform{source,lo,states,pass,replacements}})
    case _ _ _ _ _: next(doc,Fail{764})

@unsafe
def transform(-T: Type,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Transform> -> T) -> T:
  fields(T,[5,22,12,23,24],doc,value,context,[],doc => fields => transform_fields(T,fields,doc,context,next))

@unsafe
def entry_fields(-T: Type,fields: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Entry> -> T) -> T:
  match fields:
    case weight <> provider <> Nil{}: parse(T,provider,doc,context,doc => provider => entry_results(T,D.integer(weight),provider,doc,next))
    case _: next(doc,Fail{764})

@unsafe
def entry_results(-T: Type,weight: Result<&1,&1,U32,U32>,provider: Result<&1,&1,U32,B.Provider>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Entry> -> T) -> T:
  match weight provider:
    case Done{w} Done{p}: next(doc,Done{B.Entry{w,p}})
    case _ _: next(doc,Fail{764})

@unsafe
def entry(-T: Type,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Entry> -> T) -> T:
  fields(T,[3,4],doc,value,context,[],doc => fields => entry_fields(T,fields,doc,context,next))

@unsafe
def rule_fields(-T: Type,fields: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Rule> -> T) -> T:
  match fields context:
    case predicate <> provider <> Nil{} Context{_,_,predicates}:
      RP.parse(T,predicate,doc,predicates,doc => predicate => parse(T,provider,doc,context,doc => provider => rule_results(T,predicate,provider,doc,next)))
    case _ _: next(doc,Fail{764})

@unsafe
def rule_results(-T: Type,predicate: Result<&1,&1,U32,P.Predicate>,provider: Result<&1,&1,U32,B.Provider>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Rule> -> T) -> T:
  match predicate provider:
    case Done{p} Done{b}: next(doc,Done{B.Rule{p,b}})
    case _ _: next(doc,Fail{764})

@unsafe
def rule(-T: Type,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Rule> -> T) -> T:
  fields(T,[10,4],doc,value,context,[],doc => fields => rule_fields(T,fields,doc,context,next))

@unsafe
def replacement_list(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,List<&2,B.Replacement>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: replacement_items(T,n,U32.add(at,3),doc,context,[],next)
    case None{}: next(doc,Done{[]})
    case _: next(doc,Fail{764})

@unsafe
def replacement_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,B.Replacement>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Replacement>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,B.Replacement,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span =>
      replacement(T,doc,value,context,doc => result => replacement_item(T,result,U32.sub(n,1),U32.add(at,span),doc,context,acc,next))))

@unsafe
def replacement_item(-T: Type,result: Result<&1,&1,U32,B.Replacement>,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,B.Replacement>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Replacement>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{764})
    case Done{x}: replacement_items(T,n,at,doc,context,x <> acc,next)

@unsafe
def transform_list(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,List<&2,B.Transform>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: transform_items(T,n,U32.add(at,3),doc,context,[],next)
    case None{}: next(doc,Fail{764})
    case _: next(doc,Fail{764})

@unsafe
def transform_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,B.Transform>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Transform>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,B.Transform,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span =>
      transform(T,doc,value,context,doc => result => transform_item(T,result,U32.sub(n,1),U32.add(at,span),doc,context,acc,next))))

@unsafe
def transform_item(-T: Type,result: Result<&1,&1,U32,B.Transform>,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,B.Transform>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Transform>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{764})
    case Done{x}: transform_items(T,n,at,doc,context,x <> acc,next)

@unsafe
def entry_list(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,List<&2,B.Entry>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: entry_items(T,n,U32.add(at,3),doc,context,[],next)
    case None{}: next(doc,Fail{764})
    case _: next(doc,Fail{764})

@unsafe
def entry_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,B.Entry>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Entry>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,B.Entry,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span =>
      entry(T,doc,value,context,doc => result => entry_item(T,result,U32.sub(n,1),U32.add(at,span),doc,context,acc,next))))

@unsafe
def entry_item(-T: Type,result: Result<&1,&1,U32,B.Entry>,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,B.Entry>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Entry>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{764})
    case Done{x}: entry_items(T,n,at,doc,context,x <> acc,next)

@unsafe
def rule_list(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,List<&2,B.Rule>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: rule_items(T,n,U32.add(at,3),doc,context,[],next)
    case None{}: next(doc,Fail{764})
    case _: next(doc,Fail{764})

@unsafe
def rule_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,B.Rule>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Rule>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,B.Rule,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span =>
      rule(T,doc,value,context,doc => result => rule_item(T,result,U32.sub(n,1),U32.add(at,span),doc,context,acc,next))))

@unsafe
def rule_item(-T: Type,result: Result<&1,&1,U32,B.Rule>,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,B.Rule>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Rule>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{764})
    case Done{x}: rule_items(T,n,at,doc,context,x <> acc,next)

@unsafe
def optional(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,Maybe<&2,B.Provider>> -> T) -> T:
  match found:
    case None{}: next(doc,Done{None{}})
    case Some{x}: parse(T,Some{x},doc,context,doc => result => optional_result(T,result,doc,next))

@unsafe
def optional_result(-T: Type,result: Result<&1,&1,U32,B.Provider>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Maybe<&2,B.Provider>> -> T) -> T:
  match result:
    case Done{x}: next(doc,Done{Some{x}})
    case _: next(doc,Fail{764})

@unsafe
def weighted(-T: Type,result: Result<&1,&1,U32,List<&2,B.Entry>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match result:
    case Done{+entries}: next(doc,Done{B.Weighted{entries,B.total(entries,W.U64{0,0})}})
    case _: next(doc,Fail{764})

@unsafe
def source_kind(-T: Type,+kind: U32,source: Result<&1,&1,U32,B.Provider>,extra: Maybe<&2,R.Value>,variants: Result<&1,&1,U32,List<&2,B.Transform>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match kind source variants context:
    case 2 Done{source} Done{variants} Context{_,integers,_}: RI.prepare(T,extra,doc,integers,doc => values => randomized(T,source,values,variants,doc,next))
    case 4 Done{source} Done{variants} _: rotated(T,source,direction(extra),variants,doc,next)
    case 5 Done{source} Done{variants} _: next(doc,Done{B.Copy{source,variants}})
    case _ _ _ _: next(doc,Fail{764})

@unsafe
def randomized(-T: Type,source: B.Provider,values: Result<&1,&1,U32,I.Provider>,variants: List<&2,B.Transform>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match values:
    case Done{values}: next(doc,Done{B.Randomized{source,values,variants}})
    case _: next(doc,Fail{764})

@unsafe
def rotated(-T: Type,source: B.Provider,direction: Result<&1,&1,U32,Maybe<&2,U32>>,variants: List<&2,B.Transform>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match direction:
    case Done{direction}: next(doc,Done{B.Rotated{source,direction,variants}})
    case _: next(doc,Fail{764})

@unsafe
def rules(-T: Type,rows: Result<&1,&1,U32,List<&2,B.Rule>>,fallback: Result<&1,&1,U32,Maybe<&2,B.Provider>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match rows fallback:
    case Done{rows} Done{fallback}: next(doc,Done{B.Rules{rows,fallback}})
    case _ _: next(doc,Fail{764})

@unsafe
def states(-T: Type,+kind: U32,program: Result<&1,&1,U32,U32>,states: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match kind program states:
    case 6 _ Done{states}: next(doc,Done{B.RandomBlocks{states}})
    case 7 Done{program} Done{states}: next(doc,Done{B.Noise{program,states}})
    case _ _ _: next(doc,Fail{764})

@unsafe
def dual(-T: Type,fast: Result<&1,&1,U32,U32>,slow: Result<&1,&1,U32,U32>,variety: Result<&1,&1,U32,Q.Table<U32>>,states: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match fast slow variety states:
    case Done{fast} Done{slow} Done{+variety} Done{states}: dual_shape(T,U32.is_eq(Q.table_size(U32,variety),2),fast,slow,variety,states,doc,next)
    case _ _ _ _: next(doc,Fail{764})

@unsafe
def dual_shape(-T: Type,ok: Bool,fast: U32,slow: U32,+variety: Q.Table<U32>,states: Q.Table<U32>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match ok:
    case True{}: next(doc,Done{B.Dual{fast,slow,Q.get(U32,variety,0,0),Q.get(U32,variety,1,0),states}})
    case False{}: next(doc,Fail{764})

@unsafe
def threshold(-T: Type,program: Result<&1,&1,U32,U32>,level: Result<&1,&1,U32,F32>,chance: Result<&1,&1,U32,F32>,default: Result<&1,&1,U32,U32>,low: Result<&1,&1,U32,Q.Table<U32>>,high: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match program level chance default low high:
    case Done{program} Done{level} Done{chance} Done{default} Done{low} Done{high}: next(doc,Done{B.Threshold{program,level,chance,default,low,high}})
    case _ _ _ _ _ _: next(doc,Fail{764})

@unsafe
def collected(-T: Type,+kind: U32,fields: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match kind fields:
    case 0 material <> Nil{}: next(doc,state(D.integer(material)))
    case 1 entries <> Nil{}: entry_list(T,entries,doc,context,doc => entries => weighted(T,entries,doc,next))
    case 3 rows <> fallback <> Nil{}: rule_list(T,rows,doc,context,doc => rows => optional(T,fallback,doc,context,doc => fallback => rules(T,rows,fallback,doc,next)))
    case 6 a <> b <> Nil{}: array(T,b,doc,doc => b => states(T,kind,D.integer(a),b,doc,next))
    case 7 a <> b <> Nil{}: array(T,b,doc,doc => b => states(T,kind,D.integer(a),b,doc,next))
    case 8 f <> s <> v <> states <> Nil{}: array(T,v,doc,doc => v => array(T,states,doc,doc => states => dual(T,D.integer(f),D.integer(s),v,states,doc,next)))
    case 9 p <> l <> c <> d <> low <> high <> Nil{}: array(T,low,doc,doc => low => array(T,high,doc,doc => high => threshold(T,D.integer(p),D.number(l),D.number(c),D.integer(d),low,high,doc,next)))
    case _ source <> extra <> variants <> Nil{}: parse(T,source,doc,context,doc => source => transform_list(T,variants,doc,context,doc => variants => source_kind(T,kind,source,extra,variants,doc,context,next)))
    case _ _: next(doc,Fail{764})

@unsafe
def kind(-T: Type,+kind: U32,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  kind_fields(T,kind,doc,value,context,next)

@unsafe
def kind_fields(-T: Type,+kind: U32,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match kind:
    case 0: fields(T,[1],doc,value,context,[],doc => xs => collected(T,kind,xs,doc,context,next))
    case 1: fields(T,[2],doc,value,context,[],doc => xs => collected(T,kind,xs,doc,context,next))
    case 2: fields(T,[5,6,7],doc,value,context,[],doc => xs => collected(T,kind,xs,doc,context,next))
    case 3: fields(T,[8,9],doc,value,context,[],doc => xs => collected(T,kind,xs,doc,context,next))
    case 4: fields(T,[5,11,7],doc,value,context,[],doc => xs => collected(T,kind,xs,doc,context,next))
    case 5: fields(T,[5,11,7],doc,value,context,[],doc => xs => collected(T,kind,xs,doc,context,next))
    case 6: fields(T,[13,12],doc,value,context,[],doc => xs => collected(T,kind,xs,doc,context,next))
    case 7: fields(T,[13,12],doc,value,context,[],doc => xs => collected(T,kind,xs,doc,context,next))
    case 8: fields(T,[14,15,16,12],doc,value,context,[],doc => xs => collected(T,kind,xs,doc,context,next))
    case 9: fields(T,[13,17,18,19,20,21],doc,value,context,[],doc => xs => collected(T,kind,xs,doc,context,next))
    case _: next(doc,Fail{764})

@unsafe
def typed(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match found context:
    case Some{R.Value{5,id,_,_}} Context{keys,_,_}: kind(T,name(keys,id,0),doc,value,context,next)
    case _ _: next(doc,Fail{764})

@unsafe
def parse(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match found context:
    case Some{+value} Context{keys,_,_}: D.named(T,doc,value,keys,0n,doc => found => typed(T,found,doc,value,context,next))
    case _ _: next(doc,Fail{764})

@unsafe
def checked(-T: Type,result: Result<&1,&1,U32,B.Provider>,+palette: U32,+noises: U32,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match result:
    case Done{+provider}: accepted(T,B.valid(provider,palette,noises),provider,doc,next)
    case _: next(doc,Fail{764})

@unsafe
def accepted(-T: Type,ok: Bool,provider: B.Provider,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  match ok:
    case False{}: next(doc,Fail{764})
    case True{}: next(doc,Done{provider})

@unsafe
def prepare(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,+palette: U32,+noises: U32,next: R.Document -> Result<&1,&1,U32,B.Provider> -> T) -> T:
  parse(T,found,doc,context,doc => result => checked(T,result,palette,noises,doc,next))
