import Base
import ./registry.bend as R
import ./registry_values.bend as V
import ./registry_density.bend as D
import ./registry_cave_features.bend as A
import ./registry_material.bend as M
import ./registry_block_provider.bend as RB
import ./registry_int_provider.bend as RI
import ./registry_placement_predicate.bend as RP
import ./surface_feature.bend as F
import ./placement_data.bend as P
import ./block_provider.bend as B
import ./int_provider.bend as I
import ./density.bend as Q

type Context is Data:
  Context{keys: D.Keys,providers: RB.Context}

@unsafe
def context(-T: Type,doc: R.Document,next: R.Document -> Context -> T) -> T:
  D.resolved(T,["kind","provider","states","water","current_survival","source","lower","upper","survival","upper_allowed","layers","direction","allowed","prioritize_tip","height","target","radius","half_height","simple_current_states","simple_block","block_column","disk"],doc,[],doc => keys => RB.context(T,doc,doc => providers => next(doc,Context{keys,providers})))

@unsafe
def name(+keys: D.Keys,+id: U32,+i: U32) -> U32:
  named(U32.is_eq(id,D.key(keys,U32.to_nat(U32.add(i,19)))),keys,id,i)

@unsafe
def named(equal: Bool,+keys: D.Keys,+id: U32,+i: U32) -> U32:
  match equal:
    case True{}: i
    case False{}: name_more(U32.is_lt(i,2),keys,id,U32.inc(i))

@unsafe
def name_more(more: Bool,+keys: D.Keys,+id: U32,+i: U32) -> U32:
  match more:
    case False{}: 4294967295
    case True{}: name(keys,id,i)

@unsafe
def fields(-T: Type,indices: List<&2,U32>,doc: R.Document,+value: R.Value,+context: Context,acc: List<&2,Maybe<&2,R.Value>>,next: R.Document -> List<&2,Maybe<&2,R.Value>> -> T) -> T:
  match indices context:
    case Nil{} _: next(doc,List.reverse(&2,Maybe<&2,R.Value>,acc))
    case i <> rest Context{keys,_}: D.named(T,doc,value,keys,U32.to_nat(i),doc => found => fields(T,rest,doc,value,context,found <> acc,next))

@unsafe
def vector(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Position> -> T) -> T:
  A.array(T,found,doc,2,doc => result => vector_result(T,result,doc,next))

@unsafe
def vector_result(-T: Type,result: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Position> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{765})
    case Done{+xs}: vector_shape(T,U32.is_eq(Q.table_size(U32,xs),3),xs,doc,next)

@unsafe
def vector_shape(-T: Type,ok: Bool,+xs: Q.Table<U32>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Position> -> T) -> T:
  match ok:
    case False{}: next(doc,Fail{765})
    case True{}: next(doc,Done{P.Position{Q.get(U32,xs,0,0),Q.get(U32,xs,1,0),Q.get(U32,xs,2,0)}})

@unsafe
def plant_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,F.PlantState> -> T) -> T:
  match values context:
    case source <> lower <> upper <> survival <> allowed <> Nil{} Context{_,RB.Context{_,_,predicates}}:
      RB.array(T,lower,doc,doc => lower => RB.array(T,upper,doc,doc => upper =>
        RP.parse(T,survival,doc,predicates,doc => survival => RP.parse(T,allowed,doc,predicates,doc => allowed =>
        plant_results(T,D.integer(source),lower,upper,survival,allowed,doc,next)))))
    case _ _: next(doc,Fail{765})

@unsafe
def plant_results(-T: Type,source: Result<&1,&1,U32,U32>,lower: Result<&1,&1,U32,Q.Table<U32>>,upper: Result<&1,&1,U32,Q.Table<U32>>,survival: Result<&1,&1,U32,P.Predicate>,allowed: Result<&1,&1,U32,P.Predicate>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.PlantState> -> T) -> T:
  match source lower upper survival allowed:
    case Done{s} Done{l} Done{u} Done{p} Done{a}: next(doc,Done{F.PlantState{s,l,u,p,a}})
    case _ _ _ _ _: next(doc,Fail{765})

@unsafe
def plant(-T: Type,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,F.PlantState> -> T) -> T:
  fields(T,[5,6,7,8,9],doc,value,context,[],doc => values => plant_fields(T,values,doc,context,next))

@unsafe
def layer_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Layer> -> T) -> T:
  match values context:
    case height <> provider <> Nil{} Context{_,RB.Context{_,integers,_}}:
      RI.prepare(T,height,doc,integers,doc => height => RB.parse(T,provider,doc,providers(context),doc => provider => layer_results(T,height,provider,doc,next)))
    case _ _: next(doc,Fail{765})

@unsafe
def providers(context: Context) -> RB.Context:
  match context:
    case Context{_,p}: p

@unsafe
def layer_results(-T: Type,height: Result<&1,&1,U32,I.Provider>,provider: Result<&1,&1,U32,B.Provider>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Layer> -> T) -> T:
  match height provider:
    case Done{h} Done{p}: next(doc,Done{F.Layer{h,p}})
    case _ _: next(doc,Fail{765})

@unsafe
def layer(-T: Type,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Layer> -> T) -> T:
  fields(T,[14,1],doc,value,context,[],doc => values => layer_fields(T,values,doc,context,next))

@unsafe
def optional_plant(-T: Type,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,Maybe<&2,F.PlantState>> -> T) -> T:
  match value:
    case R.Value{0,_,_,_}: next(doc,Done{None{}})
    case _: plant(T,doc,value,context,doc => result => optional_plant_result(T,result,doc,next))

@unsafe
def optional_plant_result(-T: Type,result: Result<&1,&1,U32,F.PlantState>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Maybe<&2,F.PlantState>> -> T) -> T:
  match result:
    case Done{p}: next(doc,Done{Some{p}})
    case Fail{_}: next(doc,Fail{765})

@unsafe
def plants(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,List<&2,F.PlantState>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: plants_items(T,n,U32.add(at,3),doc,context,[],next)
    case _: next(doc,Fail{765})

@unsafe
def plants_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,F.PlantState>,next: R.Document -> Result<&1,&1,U32,List<&2,F.PlantState>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,F.PlantState,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span =>
      plant(T,doc,value,context,doc => result => plants_item(T,result,U32.sub(n,1),U32.add(at,span),doc,context,acc,next))))

@unsafe
def plants_item(-T: Type,result: Result<&1,&1,U32,F.PlantState>,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,F.PlantState>,next: R.Document -> Result<&1,&1,U32,List<&2,F.PlantState>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{765})
    case Done{x}: plants_items(T,n,at,doc,context,x <> acc,next)

@unsafe
def layers(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,List<&2,F.Layer>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: layers_items(T,n,U32.add(at,3),doc,context,[],next)
    case _: next(doc,Fail{765})

@unsafe
def layers_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,F.Layer>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Layer>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,F.Layer,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span =>
      layer(T,doc,value,context,doc => result => layers_item(T,result,U32.sub(n,1),U32.add(at,span),doc,context,acc,next))))

@unsafe
def layers_item(-T: Type,result: Result<&1,&1,U32,F.Layer>,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,F.Layer>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Layer>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{765})
    case Done{x}: layers_items(T,n,at,doc,context,x <> acc,next)

@unsafe
def current(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,List<&2,Maybe<&2,F.PlantState>>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: current_items(T,n,U32.add(at,3),doc,context,[],next)
    case _: next(doc,Fail{765})

@unsafe
def current_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,Maybe<&2,F.PlantState>>,next: R.Document -> Result<&1,&1,U32,List<&2,Maybe<&2,F.PlantState>>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,Maybe<&2,F.PlantState>,acc)})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span =>
      optional_plant(T,doc,value,context,doc => result => current_item(T,result,U32.sub(n,1),U32.add(at,span),doc,context,acc,next))))

@unsafe
def current_item(-T: Type,result: Result<&1,&1,U32,Maybe<&2,F.PlantState>>,+n: U32,+at: U32,doc: R.Document,+context: Context,acc: List<&2,Maybe<&2,F.PlantState>>,next: R.Document -> Result<&1,&1,U32,List<&2,Maybe<&2,F.PlantState>>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{765})
    case Done{x}: current_items(T,n,at,doc,context,x <> acc,next)

@unsafe
def simple_results(-T: Type,provider: Result<&1,&1,U32,B.Provider>,states: Result<&1,&1,U32,List<&2,F.PlantState>>,water: Result<&1,&1,U32,P.Predicate>,current: Result<&1,&1,U32,Bool>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature> -> T) -> T:
  match provider states water current:
    case Done{p} Done{s} Done{w} Done{c}: next(doc,Done{F.Simple{p,s,w,c}})
    case _ _ _ _: next(doc,Fail{765})

@unsafe
def column_results(-T: Type,layers: Result<&1,&1,U32,List<&2,F.Layer>>,direction: Result<&1,&1,U32,P.Position>,allowed: Result<&1,&1,U32,P.Predicate>,tip: Result<&1,&1,U32,Bool>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature> -> T) -> T:
  match layers direction allowed tip:
    case Done{l} Done{d} Done{a} Done{t}: next(doc,Done{F.Column{l,d,a,t}})
    case _ _ _ _: next(doc,Fail{765})

@unsafe
def disk_results(-T: Type,provider: Result<&1,&1,U32,B.Provider>,target: Result<&1,&1,U32,P.Predicate>,radius: Result<&1,&1,U32,I.Provider>,half: Result<&1,&1,U32,U32>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature> -> T) -> T:
  match provider target radius half:
    case Done{p} Done{t} Done{r} Done{h}: next(doc,Done{F.Disk{p,t,r,h}})
    case _ _ _ _: next(doc,Fail{765})

@unsafe
def collected(-T: Type,kind: U32,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Feature> -> T) -> T:
  match kind values context:
    case 0 provider <> states <> water <> current <> Nil{} Context{_,RB.Context{_,_,predicates}}:
      RB.parse(T,provider,doc,providers(context),doc => provider => plants(T,states,doc,context,doc => states =>
        RP.parse(T,water,doc,predicates,doc => water => simple_results(T,provider,states,water,RB.passthrough(current),doc,next))))
    case 1 ls <> direction <> allowed <> tip <> Nil{} Context{_,RB.Context{_,_,predicates}}:
      layers(T,ls,doc,context,doc => ls => vector(T,direction,doc,doc => direction =>
        RP.parse(T,allowed,doc,predicates,doc => allowed => column_results(T,ls,direction,allowed,M.boolean(tip),doc,next))))
    case 2 provider <> target <> radius <> half <> Nil{} Context{_,RB.Context{_,integers,predicates}}:
      RB.parse(T,provider,doc,providers(context),doc => provider => RP.parse(T,target,doc,predicates,doc => target =>
        RI.prepare(T,radius,doc,integers,doc => radius => disk_results(T,provider,target,radius,D.integer(half),doc,next))))
    case _ _ _: next(doc,Fail{765})

@unsafe
def kind(-T: Type,+kind: U32,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Feature> -> T) -> T:
  match kind:
    case 0: fields(T,[1,2,3,4],doc,value,context,[],doc => values => collected(T,kind,values,doc,context,next))
    case 1: fields(T,[10,11,12,13],doc,value,context,[],doc => values => collected(T,kind,values,doc,context,next))
    case 2: fields(T,[1,15,16,17],doc,value,context,[],doc => values => collected(T,kind,values,doc,context,next))
    case _: next(doc,Fail{765})

@unsafe
def typed(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Feature> -> T) -> T:
  match found context:
    case Some{R.Value{5,id,_,_}} Context{keys,_}: kind(T,name(keys,id,0),doc,value,context,next)
    case _ _: next(doc,Fail{765})

@unsafe
def parse(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,next: R.Document -> Result<&1,&1,U32,F.Feature> -> T) -> T:
  match found context:
    case Some{+value} Context{keys,_}: D.named(T,doc,value,keys,0n,doc => found => typed(T,found,doc,value,context,next))
    case _ _: next(doc,Fail{765})

@unsafe
def accepted(-T: Type,ok: Bool,feature: F.Feature,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature> -> T) -> T:
  match ok:
    case False{}: next(doc,Fail{765})
    case True{}: next(doc,Done{feature})

@unsafe
def checked(-T: Type,result: Result<&1,&1,U32,F.Feature>,+palette: U32,+noises: U32,doc: R.Document,next: R.Document -> Result<&1,&1,U32,F.Feature> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{765})
    case Done{+feature}: accepted(T,F.valid(feature,palette,noises),feature,doc,next)

@unsafe
def prepare(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,+palette: U32,+noises: U32,next: R.Document -> Result<&1,&1,U32,F.Feature> -> T) -> T:
  parse(T,found,doc,context,doc => result => checked(T,result,palette,noises,doc,next))

@unsafe
def current_valid(rows: List<&2,Maybe<&2,F.PlantState>>,+index: U32,+palette: U32) -> Bool:
  match rows:
    case Nil{}: U32.is_eq(index,palette)
    case None{} <> rest: current_valid(rest,U32.inc(index),palette)
    case Some{+p} <> rest: Bool.and(F.plant_valid(p,palette),Bool.and(current_source(p,index),current_valid(rest,U32.inc(index),palette)))

@unsafe
def current_source(plant: F.PlantState,+index: U32) -> Bool:
  match plant:
    case F.PlantState{source,_,_,_,_}: U32.is_eq(source,index)

@unsafe
def current_checked(-T: Type,result: Result<&1,&1,U32,List<&2,Maybe<&2,F.PlantState>>>,+palette: U32,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Q.Table<Maybe<&2,F.PlantState>>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{765})
    case Done{+xs}: current_accepted(T,current_valid(xs,0,palette),xs,doc,next)

@unsafe
def current_accepted(-T: Type,ok: Bool,xs: List<&2,Maybe<&2,F.PlantState>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Q.Table<Maybe<&2,F.PlantState>>> -> T) -> T:
  match ok:
    case False{}: next(doc,Fail{765})
    case True{}: next(doc,Done{Q.prepared_table(Maybe<&2,F.PlantState>,xs)})

@unsafe
def current_found(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: Context,+palette: U32,next: R.Document -> Result<&1,&1,U32,Q.Table<Maybe<&2,F.PlantState>>> -> T) -> T:
  match found:
    case None{}: next(doc,Done{Q.Empty{}})
    case Some{R.Value{6,0,_,_}}: next(doc,Done{Q.Empty{}})
    case Some{x}: current(T,Some{x},doc,context,doc => result => current_checked(T,result,palette,doc,next))

@unsafe
def current_states(-T: Type,doc: R.Document,+context: Context,+palette: U32,next: R.Document -> Result<&1,&1,U32,Q.Table<Maybe<&2,F.PlantState>>> -> T) -> T:
  V.root(T,doc,doc => root => V.field(T,doc,root,"simple_current_states",doc => found => current_found(T,found,doc,context,palette,next)))
