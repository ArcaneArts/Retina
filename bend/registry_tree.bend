import Base
import ./registry.bend as R
import ./registry_values.bend as V
import ./registry_density.bend as D
import ./registry_cave_features.bend as A
import ./density.bend as Q
import ./tree_data.bend as B
import ./noise.bend as N

type Context is Data:
  Context{keys: D.Keys,snow: U32}

@unsafe
def context(-T: Type,doc: R.Document,next: R.Document -> Context -> T) -> T:
  D.resolved(T,["trunk_shape","foliage_shape","height","radius","offset","foliage_height","trunk_height","logs","leaves","soil","root","root_offset","decorators","axes","distances","weight","kind","probability","states","snow","tree",
    "fancy_trunk_placer","dark_oak_trunk_placer","mega_jungle_trunk_placer","forking_trunk_placer","upwards_branching_trunk_placer","giant_trunk_placer",
    "blob_foliage_placer","fancy_foliage_placer","bush_foliage_placer","spruce_foliage_placer","pine_foliage_placer","mega_pine_foliage_placer","acacia_foliage_placer","jungle_foliage_placer","dark_oak_foliage_placer","trunk_vine","leaf_vine","cocoa"],doc,[],doc => +keys =>
    V.root(T,doc,doc => root => D.named(T,doc,root,keys,19n,doc => found => next(doc,Context{keys,snow(D.integer(found))}))))

@unsafe
def snow(result: Result<&1,&1,U32,U32>) -> U32:
  match result:
    case Done{x}: x
    case Fail{_}: 4294967295

@unsafe
def name(+keys: D.Keys,+id: U32,+i: U32,+end: U32,+fallback: U32) -> U32:
  named(U32.is_eq(id,D.key(keys,U32.to_nat(i))),keys,id,i,end,fallback)

@unsafe
def named(equal: Bool,+keys: D.Keys,+id: U32,+i: U32,+end: U32,+fallback: U32) -> U32:
  match equal:
    case True{}: i
    case False{}: name_more(U32.is_lt(i,end),keys,id,U32.inc(i),end,fallback)

@unsafe
def name_more(more: Bool,+keys: D.Keys,+id: U32,+i: U32,+end: U32,+fallback: U32) -> U32:
  match more:
    case False{}: fallback
    case True{}: name(keys,id,i,end,fallback)

@unsafe
def shape(found: Maybe<&2,R.Value>,+keys: D.Keys,+foliage: Bool) -> Result<&1,&1,U32,U32>:
  match found:
    case Some{R.Value{5,id,_,_}}: Done{shape_id(foliage,name(keys,id,N.word(foliage,27,21),N.word(foliage,35,26),4294967295))}
    case _: Fail{767}

@unsafe
def shape_id(foliage: Bool,id: U32) -> U32:
  match foliage id:
    case True{} 4294967295: 9
    case True{} _: U32.sub(id,27)
    case False{} 4294967295: 0
    case False{} _: U32.sub(id,20)

@unsafe
def arrays(-T: Type,indices: List<&2,U32>,doc: R.Document,+value: R.Value,+keys: D.Keys,acc: List<&2,Q.Table<U32>>,next: R.Document -> Result<&1,&1,U32,List<&2,Q.Table<U32>>> -> T) -> T:
  match indices:
    case Nil{}: next(doc,Done{List.reverse(&2,Q.Table<U32>,acc)})
    case +i <> rest: D.named(T,doc,value,keys,U32.to_nat(i),doc => found => A.array(T,found,doc,2,doc => result => array_result(T,result,rest,doc,value,keys,acc,next)))

@unsafe
def array_result(-T: Type,result: Result<&1,&1,U32,Q.Table<U32>>,rest: List<&2,U32>,doc: R.Document,value: R.Value,keys: D.Keys,acc: List<&2,Q.Table<U32>>,next: R.Document -> Result<&1,&1,U32,List<&2,Q.Table<U32>>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{767})
    case Done{x}: arrays(T,rest,doc,value,keys,x <> acc,next)

@unsafe
def states(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+keys: D.Keys,+leaf: Bool,next: R.Document -> Result<&1,&1,U32,List<&2,B.State>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: state_items(T,n,U32.add(at,3),doc,keys,leaf,[],next)
    case _: next(doc,Fail{767})

@unsafe
def state_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,+leaf: Bool,acc: List<&2,B.State>,next: R.Document -> Result<&1,&1,U32,List<&2,B.State>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,B.State,acc)})
    case _: R.value(T,doc,at,doc => +value => R.get(T,doc,U32.inc(at),doc => span =>
      D.named(T,doc,value,keys,U32.to_nat(N.word(leaf,14,13)),doc => ids => A.array(T,ids,doc,0,doc => ids =>
        D.named(T,doc,value,keys,15n,doc => weight => state_result(T,ids,D.number(weight),U32.sub(n,1),U32.add(at,span),doc,keys,leaf,acc,next))))))

@unsafe
def state_result(-T: Type,ids: Result<&1,&1,U32,Q.Table<U32>>,weight: Result<&1,&1,U32,F32>,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,+leaf: Bool,acc: List<&2,B.State>,next: R.Document -> Result<&1,&1,U32,List<&2,B.State>> -> T) -> T:
  match ids weight:
    case Done{ids} Done{weight}: state_items(T,n,at,doc,keys,leaf,B.State{ids,weight} <> acc,next)
    case _ _: next(doc,Fail{767})

@unsafe
def decorators(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,List<&2,B.Decorator>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: decorator_items(T,n,U32.add(at,3),doc,keys,[],next)
    case _: next(doc,Fail{767})

@unsafe
def decorator_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,acc: List<&2,B.Decorator>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Decorator>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,B.Decorator,acc)})
    case _: R.value(T,doc,at,doc => +value => R.get(T,doc,U32.inc(at),doc => span =>
      D.named(T,doc,value,keys,16n,doc => kind => D.named(T,doc,value,keys,17n,doc => probability =>
        D.named(T,doc,value,keys,18n,doc => ids => A.array(T,ids,doc,0,doc => ids =>
          decorator_result(T,decorator_kind(kind,keys),D.number(probability),ids,U32.sub(n,1),U32.add(at,span),doc,keys,acc,next)))))))

@unsafe
def decorator_kind(value: Maybe<&2,R.Value>,+keys: D.Keys) -> Result<&1,&1,U32,U32>:
  match value:
    case Some{R.Value{5,id,_,_}}: Done{U32.sub(name(keys,id,36,38,35),36)}
    case _: Fail{767}

@unsafe
def decorator_result(-T: Type,kind: Result<&1,&1,U32,U32>,probability: Result<&1,&1,U32,F32>,ids: Result<&1,&1,U32,Q.Table<U32>>,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,acc: List<&2,B.Decorator>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Decorator>> -> T) -> T:
  match kind probability ids:
    case Done{k} Done{p} Done{ids}: decorator_items(T,n,at,doc,keys,B.Decorator{k,p,ids} <> acc,next)
    case _ _ _: next(doc,Fail{767})

@unsafe
def parse(-T: Type,doc: R.Document,+value: R.Value,+context: Context,next: R.Document -> Result<&1,&1,U32,B.Recipe> -> T) -> T:
  match context:
    case Context{+keys,snow}: D.named(T,doc,value,keys,0n,doc => trunk => D.named(T,doc,value,keys,1n,doc => foliage =>
      arrays(T,[2,3,4,5,6,11],doc,value,keys,[],doc => arrays =>
        D.named(T,doc,value,keys,7n,doc => logs => states(T,logs,doc,keys,False{},doc => logs =>
          D.named(T,doc,value,keys,8n,doc => leaves => states(T,leaves,doc,keys,True{},doc => leaves =>
            D.named(T,doc,value,keys,9n,doc => soil => D.named(T,doc,value,keys,10n,doc => root =>
              D.named(T,doc,value,keys,12n,doc => ds => decorators(T,ds,doc,keys,doc => ds =>
                parsed(T,shape(trunk,keys,False{}),shape(foliage,keys,True{}),arrays,logs,leaves,D.integer(soil),D.integer(root),ds,snow,doc,next))))))))))))

@unsafe
def parsed(-T: Type,trunk: Result<&1,&1,U32,U32>,foliage: Result<&1,&1,U32,U32>,arrays: Result<&1,&1,U32,List<&2,Q.Table<U32>>>,logs: Result<&1,&1,U32,List<&2,B.State>>,leaves: Result<&1,&1,U32,List<&2,B.State>>,soil: Result<&1,&1,U32,U32>,root: Result<&1,&1,U32,U32>,ds: Result<&1,&1,U32,List<&2,B.Decorator>>,+snow: U32,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Recipe> -> T) -> T:
  match trunk foliage arrays logs leaves soil root ds:
    case Done{t} Done{f} Done{height <> radius <> offset <> fh <> th <> ro <> Nil{}} Done{logs} Done{leaves} Done{soil} Done{root} Done{ds}:
      next(doc,Done{B.Recipe{t,f,height,radius,offset,fh,th,logs,leaves,soil,root,ro,ds,snow}})
    case _ _ _ _ _ _ _ _: next(doc,Fail{767})

@unsafe
def prepare(-T: Type,doc: R.Document,value: R.Value,+context: Context,+palette: U32,next: R.Document -> Result<&1,&1,U32,B.Recipe> -> T) -> T:
  parse(T,doc,value,context,doc => result => checked(T,result,palette,doc,next))

@unsafe
def checked(-T: Type,result: Result<&1,&1,U32,B.Recipe>,+palette: U32,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Recipe> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{767})
    case Done{+recipe}: accepted(T,B.valid(recipe,palette),recipe,doc,next)

@unsafe
def accepted(-T: Type,ok: Bool,recipe: B.Recipe,doc: R.Document,next: R.Document -> Result<&1,&1,U32,B.Recipe> -> T) -> T:
  match ok:
    case False{}: next(doc,Fail{767})
    case True{}: next(doc,Done{recipe})
