import Base
import ./registry.bend as P
import ./registry_density.bend as RD
import ./registry_material.bend as RM
import ./registry_values.bend as V
import ./density.bend as Q
import ./cave_features_data.bend as F

# Host preparation reads the shared lossless registry tape in Bend. All loaded
# choices and support flags are immutable Data consumed unchanged on CPU/GPU.
@unsafe
def uint(+found: Maybe<&2,P.Value>) -> Result<&1,&1,U32,U32>:
  match found:
    case None{}: Done{0}
    case Some{value}: RD.integer(Some{value})

@unsafe
def boolean(+found: Maybe<&2,P.Value>) -> Result<&1,&1,U32,U32>:
  match found:
    case None{}: Done{0}
    case Some{value}: bool_value(RM.boolean(Some{value}))

def bool_value(result: Result<&1,&1,U32,Bool>) -> Result<&1,&1,U32,U32>:
  match result:
    case Fail{_}: Fail{757}
    case Done{False{}}: Done{0}
    case Done{True{}}: Done{1}

@unsafe
def item(mode: U32,found: Maybe<&2,P.Value>) -> Result<&1,&1,U32,U32>:
  match mode:
    case 1: bool_value(RM.boolean(found))
    case 2: RD.signed(found)
    case _: RD.integer(found)

@unsafe
def array(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,mode: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<U32>> -> A) -> A:
  match found:
    case None{}: next(doc,Done{Q.Empty{}})
    case Some{+value}: array_tag(A,value,doc,mode,next)

@unsafe
def array_tag(-A: Type,+value: P.Value,doc: P.Document,mode: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<U32>> -> A) -> A:
  match value:
    case P.Value{6,n,_,at}: array_stream(A,n,U32.add(at,3),doc,mode,[],next)
    case P.Value{+tag,n,_,_}: array_room(A,Bool.or(U32.is_eq(tag,6),Bool.and(U32.is_ge(tag,8),U32.is_le(tag,11))),n,value,doc,mode,next)

# Generic arrays have node spans on the validated tape. Walk them once rather
# than restarting P.item from the first element for every palette entry.
@unsafe
def array_stream(-A: Type,n: U32,+at: U32,doc: P.Document,+mode: U32,acc: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,Q.Table<U32>> -> A) -> A:
  match n:
    case 0: next(doc,Done{Q.prepared_table(U32,List.reverse(&2,U32,acc))})
    case _: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      array_stream_result(A,item(mode,Some{value}),U32.sub(n,1),U32.add(at,span),doc,mode,acc,next)))

@unsafe
def array_stream_result(-A: Type,result: Result<&1,&1,U32,U32>,n: U32,at: U32,doc: P.Document,mode: U32,acc: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,Q.Table<U32>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{757})
    case Done{x}: array_stream(A,n,at,doc,mode,x <> acc,next)

@unsafe
def array_room(-A: Type,ok: Bool,n: U32,value: P.Value,doc: P.Document,mode: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<U32>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{757})
    case True{}: array_read(A,n,0,value,doc,mode,[],next)

@unsafe
def array_read(-A: Type,n: U32,+i: U32,+value: P.Value,doc: P.Document,+mode: U32,acc: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,Q.Table<U32>> -> A) -> A:
  match n:
    case 0: next(doc,Done{Q.prepared_table(U32,List.reverse(&2,U32,acc))})
    case _: P.item(A,doc,value,i,doc => found => array_result(A,item(mode,found),U32.sub(n,1),U32.inc(i),value,doc,mode,acc,next))

@unsafe
def array_result(-A: Type,result: Result<&1,&1,U32,U32>,n: U32,i: U32,value: P.Value,doc: P.Document,mode: U32,acc: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,Q.Table<U32>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{757})
    case Done{x}: array_read(A,n,i,value,doc,mode,x <> acc,next)

@unsafe
def words(-A: Type,indices: List<&2,U32>,doc: P.Document,+value: P.Value,+keys: RD.Keys,acc: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match indices:
    case Nil{}: next(doc,Done{List.reverse(&2,U32,acc)})
    case +i <> rest: RD.named(A,doc,value,keys,U32.to_nat(i),doc => found => word_result(A,uint(found),rest,doc,value,keys,acc,next))

@unsafe
def word_result(-A: Type,result: Result<&1,&1,U32,U32>,rest: List<&2,U32>,doc: P.Document,value: P.Value,keys: RD.Keys,acc: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{757})
    case Done{x}: words(A,rest,doc,value,keys,x <> acc,next)

@unsafe
def arrays(-A: Type,indices: List<&2,U32>,doc: P.Document,+value: P.Value,+keys: RD.Keys,acc: List<&2,Q.Table<U32>>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Table<U32>>> -> A) -> A:
  match indices:
    case Nil{}: next(doc,Done{List.reverse(&2,Q.Table<U32>,acc)})
    case +i <> rest: RD.named(A,doc,value,keys,U32.to_nat(i),doc => found => array(A,found,doc,mode(i),doc => result => arrays_result(A,result,rest,doc,value,keys,acc,next)))

def mode(i: U32) -> U32:
  match i:
    case 6: 1
    case 21: 2
    case _: 0

@unsafe
def arrays_result(-A: Type,result: Result<&1,&1,U32,Q.Table<U32>>,rest: List<&2,U32>,doc: P.Document,value: P.Value,keys: RD.Keys,acc: List<&2,Q.Table<U32>>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Table<U32>>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{757})
    case Done{x}: arrays(A,rest,doc,value,keys,x <> acc,next)

@unsafe
def finish(-A: Type,+config: F.Config,+palette: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,F.Config> -> A) -> A:
  accepted(A,F.valid(config,palette),config,doc,next)

@unsafe
def accepted(-A: Type,ok: Bool,config: F.Config,doc: P.Document,next: P.Document -> Result<&1,&1,U32,F.Config> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{757})
    case True{}: next(doc,Done{config})

@unsafe
def flags(-A: Type,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,U32> -> A) -> A:
  RD.named(A,doc,value,keys,3n,doc => a => RD.named(A,doc,value,keys,4n,doc => b => RD.named(A,doc,value,keys,5n,doc => c =>
    flag_values(A,boolean(a),boolean(b),boolean(c),doc,next))))

def flag_values(-A: Type,a: Result<&1,&1,U32,U32>,b: Result<&1,&1,U32,U32>,c: Result<&1,&1,U32,U32>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,U32> -> A) -> A:
  match a b c:
    case Done{a} Done{b} Done{c}: next(doc,Done{U32.add(a,U32.add(U32.mul(b,2),U32.mul(c,4)))})
    case _ _ _: next(doc,Fail{757})

@unsafe
def values(-A: Type,kind: Result<&1,&1,U32,U32>,flags: Result<&1,&1,U32,U32>,words: Result<&1,&1,U32,List<&2,U32>>,density: Result<&1,&1,U32,F32>,plants: Result<&1,&1,U32,F32>,arrays: Result<&1,&1,U32,List<&2,Q.Table<U32>>>,policy: Q.Table<F.Policy>,palette: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,F.Config> -> A) -> A:
  match kind flags words density plants arrays:
    case Done{kind} Done{flags} Done{floor <> clay <> blossom <> minimum <> maximum <> vines <> Nil{}} Done{density} Done{plants}
      Done{rs <> ps <> bs <> ts <> up <> down <> Nil{}}:
      finish(A,F.Config{kind,flags,F.Shape{floor,clay,blossom,minimum,maximum,vines,density,plants},F.Arrays{rs,ps,bs,ts,up,down},policy},palette,doc,next)
    case _ _ _ _ _ _: next(doc,Fail{757})

@unsafe
def feature(-A: Type,found: Maybe<&2,P.Value>,kind: Result<&1,&1,U32,U32>,doc: P.Document,+keys: RD.Keys,+policy: Q.Table<F.Policy>,+palette: U32,next: P.Document -> Result<&1,&1,U32,F.Config> -> A) -> A:
  match found:
    case None{}: values(A,kind,Done{0},Done{[0,0,0,0,0,0]},Done{0.0},Done{0.0},Done{[Q.Empty{},Q.Empty{},Q.Empty{},Q.Empty{},Q.Empty{},Q.Empty{}]},policy,palette,doc,next)
    case Some{P.Value{7,n,b,at}}:
      +value : P.Value = P.Value{7,n,b,at}
      flags(A,doc,value,keys,doc => flags => words(A,[7,8,9,15,16,19],doc,value,keys,[],doc => words =>
      RD.named(A,doc,value,keys,17n,doc => density => RD.named(A,doc,value,keys,18n,doc => plants =>
        arrays(A,[6,10,11,12,13,14],doc,value,keys,[],doc => arrays => values(A,kind,flags,words,RD.default_number(density,0.0),RD.default_number(plants,0.0),arrays,policy,palette,doc,next))))))
    case _: next(doc,Fail{757})

@unsafe
def biomes(-A: Type,+n: U32,+i: U32,+value: P.Value,doc: P.Document,+keys: RD.Keys,+policy: Q.Table<F.Policy>,+palette: U32,acc: List<&2,F.Config>,next: P.Document -> Result<&1,&1,U32,Q.Table<F.Config>> -> A) -> A:
  match n:
    case 0: next(doc,Done{Q.prepared_table(F.Config,List.reverse(&2,F.Config,acc))})
    case _: P.value(A,doc,i,doc => found => P.get(A,doc,U32.inc(i),doc => span =>
      biome_value(A,Some{found},U32.sub(n,1),U32.add(i,span),value,doc,keys,policy,palette,acc,next)))

@unsafe
def biome_value(-A: Type,found: Maybe<&2,P.Value>,n: U32,i: U32,value: P.Value,doc: P.Document,+keys: RD.Keys,+policy: Q.Table<F.Policy>,+palette: U32,acc: List<&2,F.Config>,next: P.Document -> Result<&1,&1,U32,Q.Table<F.Config>> -> A) -> A:
  match found:
    case None{}: next(doc,Fail{757})
    case Some{P.Value{7,nfields,bits,at}}:
      +b : P.Value = P.Value{7,nfields,bits,at}
      RD.named(A,doc,b,keys,1n,doc => kind => RD.named(A,doc,b,keys,2n,doc => found =>
      feature(A,found,uint(kind),doc,keys,policy,palette,doc => result => biome_result(A,result,n,i,value,doc,keys,policy,palette,acc,next))))
    case _: next(doc,Fail{757})

@unsafe
def biome_result(-A: Type,result: Result<&1,&1,U32,F.Config>,n: U32,i: U32,value: P.Value,doc: P.Document,keys: RD.Keys,policy: Q.Table<F.Policy>,palette: U32,acc: List<&2,F.Config>,next: P.Document -> Result<&1,&1,U32,Q.Table<F.Config>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{757})
    case Done{x}: biomes(A,n,i,value,doc,keys,policy,palette,x <> acc,next)

@unsafe
def sized(+xs: Q.Table<U32>,palette: U32) -> Bool:
  Bool.or(U32.is_zero(Q.table_size(U32,xs)),U32.is_eq(Q.table_size(U32,xs),palette))

@unsafe
def policy_ready(-A: Type,result: Result<&1,&1,U32,List<&2,Q.Table<U32>>>,doc: P.Document,root: P.Value,+keys: RD.Keys,+palette: U32,count: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<F.Config>> -> A) -> A:
  match result:
    case Done{+fs <> +hs <> +ms <> Nil{}}:
      +policy = F.policies(palette,0,fs,hs,ms)
      policy_valid(A,Bool.and(sized(fs,palette),Bool.and(sized(hs,palette),Bool.and(sized(ms,palette),F.policies_valid(policy)))),doc,root,keys,policy,palette,count,next)
    case _: next(doc,Fail{757})

@unsafe
def policy_valid(-A: Type,ok: Bool,doc: P.Document,root: P.Value,+keys: RD.Keys,policy: Q.Table<F.Policy>,palette: U32,count: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<F.Config>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{757})
    case True{}: RD.named(A,doc,root,keys,0n,doc => found => biome_array(A,found,doc,keys,policy,palette,count,next))

@unsafe
def biome_array(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,keys: RD.Keys,policy: Q.Table<F.Policy>,palette: U32,count: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<F.Config>> -> A) -> A:
  match found:
    case Some{+value}: biome_count(A,value,doc,keys,policy,palette,count,next)
    case _: next(doc,Fail{757})

@unsafe
def biome_count(-A: Type,+value: P.Value,doc: P.Document,keys: RD.Keys,policy: Q.Table<F.Policy>,palette: U32,count: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<F.Config>> -> A) -> A:
  match value:
    case P.Value{6,+n,_,_}: biome_room(A,U32.is_eq(n,count),value,doc,keys,policy,palette,n,next)
    case _: next(doc,Fail{757})

@unsafe
def biome_room(-A: Type,ok: Bool,value: P.Value,doc: P.Document,keys: RD.Keys,policy: Q.Table<F.Policy>,palette: U32,count: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<F.Config>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{757})
    case True{}: biomes_start(A,count,value,doc,keys,policy,palette,next)

@unsafe
def biomes_start(-A: Type,count: U32,+value: P.Value,doc: P.Document,keys: RD.Keys,policy: Q.Table<F.Policy>,palette: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<F.Config>> -> A) -> A:
  match value:
    case P.Value{_,_,_,at}: biomes(A,count,U32.add(at,3),value,doc,keys,policy,palette,[],next)

@unsafe
def prepare(-A: Type,doc: P.Document,+palette: U32,+count: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<F.Config>> -> A) -> A:
  RD.resolved(A,["biomes","cave_kind","cave_features","registered_vines","registered_floor_patch","registered_ceiling_patch",
    "replaceable","floor","clay","blossom","plants","vine_bodies","vine_tips","drip_up","drip_down","drip_min","drip_max",
    "density","plant_chance","vine_max","material_flags","plant_halves","plant_floor_masks"],doc,[],doc => +keys =>
      V.root(A,doc,doc => +root => arrays(A,[20,21,22],doc,root,keys,[],doc => result => policy_ready(A,result,doc,root,keys,palette,count,next))))
