import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./climate.bend as C
import ./compression.bend as Z

# Typed climate projection from the validated resident tape. Resolve dictionary
# keys once, walk list spans once, and retain the owned document throughout.
type Keys is Data:
  Keys{low: U32,high: U32,weirdness: U32,depth: U32,offset: U32,biome: U32,flags: U32}

type Vector is Data:
  Vector{a: F32,b: F32,c: F32,d: F32}

type Prepared is Data:
  Prepared{model: C.Model,targets: U32,biomes: U32}

def key_id(value: Maybe<&2,U32>) -> U32:
  match value:
    case None{}: 4294967295
    case Some{id}: id

def keys(-A: Type,doc: P.Document,next: P.Document -> Keys -> A) -> A:
  V.key(A,doc,"min",doc => low => V.key(A,doc,"max",doc => high =>
  V.key(A,doc,"weirdness",doc => weirdness => V.key(A,doc,"depth",doc => depth =>
  V.key(A,doc,"offset",doc => offset => V.key(A,doc,"biome",doc => biome =>
  V.key(A,doc,"flags",doc => flags => next(doc,Keys{key_id(low),key_id(high),key_id(weirdness),
    key_id(depth),key_id(offset),key_id(biome),key_id(flags)}))))))))

def integer(value: Maybe<&2,P.Value>) -> Result<&1,&1,U32,U32>:
  match value:
    case Some{P.Value{3,0,lo,_}}: Done{lo}
    case _: Fail{713}

def vector_values(values: List<&2,F32>) -> Result<&1,&1,U32,Vector>:
  match values:
    case a <> b <> c <> d <> Nil{}: Done{Vector{a,b,c,d}}
    case a <> b <> Nil{}: Done{Vector{a,b,0.0,0.0}}
    case _: Fail{713}

@unsafe
def vector_read(-A: Type,n: Nat,doc: P.Document,+value: P.Value,+i: U32,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,Vector> -> A) -> A:
  match n:
    case 0n: next(doc,vector_values(List.reverse(&2,F32,acc)))
    case 1n+p: P.item(A,doc,value,i,doc => found =>
      vector_number(A,V.number(found),p,doc,value,U32.inc(i),acc,next))

@unsafe
def vector_number(-A: Type,result: Result<&1,&1,U32,F32>,n: Nat,doc: P.Document,+value: P.Value,+i: U32,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,Vector> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{713})
    case Done{x}: vector_read(A,n,doc,value,i,x <> acc,next)

def vector_shape(-A: Type,valid: Bool,doc: P.Document,+value: P.Value,+count: U32,next: P.Document -> Result<&1,&1,U32,Vector> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{713})
    case True{}: vector_read(A,U32.to_nat(count),doc,value,0,[],next)

def vector(-A: Type,doc: P.Document,found: Maybe<&2,P.Value>,+count: U32,optional: Bool,next: P.Document -> Result<&1,&1,U32,Vector> -> A) -> A:
  match found optional:
    case None{} True{}: next(doc,Done{Vector{0.0,0.0,0.0,0.0}})
    case Some{P.Value{+tag,+n,b,at}} _:
      vector_shape(A,Bool.and(U32.is_eq(n,count),Bool.or(U32.is_eq(tag,6),
        Bool.and(U32.is_ge(tag,8),U32.is_le(tag,10)))),doc,P.Value{tag,n,b,at},count,next)
    case _ _: next(doc,Fail{713})

def axes(+low: Vector,+weirdness: F32,+depth: F32) -> C.Axes:
  match low:
    case Vector{a,b,c,d}: C.Axes{a,b,c,d,weirdness,depth}

def make_target(+low: Vector,+high: Vector,+weirdness: Vector,+depth: Vector,+offset: F32,+biome: U32,+flags: U32,+order: U32) -> C.Target:
  match weirdness depth:
    case Vector{a,b,_,_} Vector{c,d,_,_}: C.Target{axes(low,a,c),axes(high,b,d),offset,biome,flags,order}

def target_valid(-A: Type,valid: Bool,+value: C.Target,doc: P.Document,flags: Array<U32>,next: P.Document -> Array<U32> -> Result<&1,&1,U32,C.Target> -> A) -> A:
  match valid:
    case False{}: next(doc,flags,Fail{713})
    case True{}: next(doc,flags,Done{value})

def target_checked(-A: Type,+value: C.Target,doc: P.Document,flags: Array<U32>,next: P.Document -> Array<U32> -> Result<&1,&1,U32,C.Target> -> A) -> A:
  target_valid(A,C.valid(value),value,doc,flags,next)

def target_flags(-A: Type,valid: Bool,doc: P.Document,flags: Array<U32>,+biome: U32,+low: Vector,+high: Vector,+weirdness: Vector,+depth: Vector,+offset: F32,+order: U32,next: P.Document -> Array<U32> -> Result<&1,&1,U32,C.Target> -> A) -> A:
  match valid:
    case False{}: next(doc,flags,Fail{713})
    case True{}: Z.read(A,flags,biome,flags => flag =>
      target_checked(A,make_target(low,high,weirdness,depth,offset,biome,flag,order),doc,flags,next))

def target_biome(-A: Type,result: Result<&1,&1,U32,U32>,doc: P.Document,flags: Array<U32>,+count: U32,+low: Vector,+high: Vector,+weirdness: Vector,+depth: Vector,+offset: F32,+order: U32,next: P.Document -> Array<U32> -> Result<&1,&1,U32,C.Target> -> A) -> A:
  match result:
    case Fail{_}: next(doc,flags,Fail{713})
    case Done{+biome}: target_flags(A,U32.is_lt(biome,count),doc,flags,biome,low,high,weirdness,depth,offset,order,next)

def target_offset(-A: Type,result: Result<&1,&1,U32,F32>,doc: P.Document,flags: Array<U32>,+count: U32,+value: P.Value,+biome_key: U32,+low: Vector,+high: Vector,+weirdness: Vector,+depth: Vector,+order: U32,next: P.Document -> Array<U32> -> Result<&1,&1,U32,C.Target> -> A) -> A:
  match result:
    case Fail{_}: next(doc,flags,Fail{713})
    case Done{offset}: P.field(A,doc,value,biome_key,doc => biome =>
      target_biome(A,integer(biome),doc,flags,count,low,high,weirdness,depth,offset,order,next))

def target_depth(-A: Type,result: Result<&1,&1,U32,Vector>,doc: P.Document,flags: Array<U32>,+count: U32,+value: P.Value,+keys: Keys,+low: Vector,+high: Vector,+weirdness: Vector,+order: U32,next: P.Document -> Array<U32> -> Result<&1,&1,U32,C.Target> -> A) -> A:
  match result keys:
    case Fail{_} _: next(doc,flags,Fail{713})
    case Done{depth} Keys{_,_,_,_,offset_key,biome_key,_}: P.field(A,doc,value,offset_key,doc => offset =>
      target_offset(A,V.number(offset),doc,flags,count,value,biome_key,low,high,weirdness,depth,order,next))

def target_weirdness(-A: Type,result: Result<&1,&1,U32,Vector>,doc: P.Document,flags: Array<U32>,+count: U32,+value: P.Value,+keys: Keys,+low: Vector,+high: Vector,+order: U32,next: P.Document -> Array<U32> -> Result<&1,&1,U32,C.Target> -> A) -> A:
  match result keys:
    case Fail{_} _: next(doc,flags,Fail{713})
    case Done{weirdness} Keys{_,_,_,depth_key,_,_,_}: P.field(A,doc,value,depth_key,doc => depth =>
      vector(A,doc,depth,2,True{},doc => result => target_depth(A,result,doc,flags,count,value,keys,low,high,weirdness,order,next)))

def target_high(-A: Type,result: Result<&1,&1,U32,Vector>,doc: P.Document,flags: Array<U32>,+count: U32,+value: P.Value,+keys: Keys,+low: Vector,+order: U32,next: P.Document -> Array<U32> -> Result<&1,&1,U32,C.Target> -> A) -> A:
  match result keys:
    case Fail{_} _: next(doc,flags,Fail{713})
    case Done{high} Keys{_,_,weird_key,_,_,_,_}: P.field(A,doc,value,weird_key,doc => weird =>
      vector(A,doc,weird,2,False{},doc => result => target_weirdness(A,result,doc,flags,count,value,keys,low,high,order,next)))

def target_low(-A: Type,result: Result<&1,&1,U32,Vector>,doc: P.Document,flags: Array<U32>,+count: U32,+value: P.Value,+keys: Keys,+order: U32,next: P.Document -> Array<U32> -> Result<&1,&1,U32,C.Target> -> A) -> A:
  match result keys:
    case Fail{_} _: next(doc,flags,Fail{713})
    case Done{low} Keys{_,high_key,_,_,_,_,_}: P.field(A,doc,value,high_key,doc => high =>
      vector(A,doc,high,4,False{},doc => result => target_high(A,result,doc,flags,count,value,keys,low,order,next)))

def target(-A: Type,doc: P.Document,flags: Array<U32>,+count: U32,+value: P.Value,+keys: Keys,+order: U32,next: P.Document -> Array<U32> -> Result<&1,&1,U32,C.Target> -> A) -> A:
  match keys:
    case Keys{low_key,_,_,_,_,_,_}: P.field(A,doc,value,low_key,doc => low =>
      vector(A,doc,low,4,False{},doc => result => target_low(A,result,doc,flags,count,value,keys,order,next)))

@unsafe
def targets(-A: Type,n: Nat,doc: P.Document,+at: U32,flags: Array<U32>,+biomes: U32,+keys: Keys,+order: U32,+count: U32,acc: List<&2,C.Target>,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match n:
    case 0n: next(doc,Done{Prepared{C.prepare(List.reverse(&2,C.Target,acc)),count,biomes}})
    case 1n+p: P.get(A,doc,U32.inc(at),doc => span => P.value(A,doc,at,doc => value =>
      target(A,doc,flags,biomes,value,keys,order,doc => flags => result =>
        target_result(A,result,p,doc,U32.add(at,span),flags,biomes,keys,U32.inc(order),count,acc,next))))

@unsafe
def target_result(-A: Type,result: Result<&1,&1,U32,C.Target>,n: Nat,doc: P.Document,+at: U32,flags: Array<U32>,+biomes: U32,+keys: Keys,+order: U32,+count: U32,acc: List<&2,C.Target>,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{value}: targets(A,n,doc,at,flags,biomes,keys,order,count,value <> acc,next)

def targets_valid(-A: Type,valid: Bool,doc: P.Document,+at: U32,+count: U32,flags: Array<U32>,+biomes: U32,+keys: Keys,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{713})
    case True{}: targets(A,U32.to_nat(count),doc,at,flags,biomes,keys,0,count,[],next)

def targets_value(-A: Type,value: Maybe<&2,P.Value>,doc: P.Document,flags: Array<U32>,+biomes: U32,+keys: Keys,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match value:
    case None{}: next(doc,Done{Prepared{C.prepare([]),0,biomes}})
    case Some{P.Value{6,+count,_,at}}: targets_valid(A,U32.is_le(count,65536),doc,U32.add(at,3),count,flags,biomes,keys,next)
    case _: next(doc,Fail{713})

@unsafe
def flags(-A: Type,n: Nat,doc: P.Document,+at: U32,a: Array<U32>,+i: U32,+count: U32,+keys: Keys,+root: P.Value,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match n keys:
    case 0n _: V.field(A,doc,root,"climate_targets",doc => value => targets_value(A,value,doc,a,count,keys,next))
    case 1n+p Keys{_,_,_,_,_,_,flag_key}: P.get(A,doc,U32.inc(at),doc => span => P.value(A,doc,at,doc => value =>
      P.field(A,doc,value,flag_key,doc => found => flag_result(A,integer(found),p,doc,U32.add(at,span),a,i,count,keys,root,next))))

@unsafe
def flag_result(-A: Type,result: Result<&1,&1,U32,U32>,n: Nat,doc: P.Document,+at: U32,a: Array<U32>,+i: U32,+count: U32,+keys: Keys,+root: P.Value,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{value}: flags(A,n,doc,at,Array.set(U32,a,i,value),U32.inc(i),count,keys,root,next)

def biomes_valid(-A: Type,valid: Bool,doc: P.Document,+at: U32,+count: U32,+keys: Keys,+root: P.Value,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{713})
    case True{}: flags(A,U32.to_nat(count),doc,at,Array.new(U32,P.capacity(count,1,0n),0),0,count,keys,root,next)

def biomes(-A: Type,value: Maybe<&2,P.Value>,doc: P.Document,+keys: Keys,+root: P.Value,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match value:
    case Some{P.Value{6,+count,_,at}}: biomes_valid(A,Bool.and(U32.is_gt(count,0),U32.is_le(count,65535)),doc,U32.add(at,3),count,keys,root,next)
    case _: next(doc,Fail{713})

def prepare(-A: Type,doc: P.Document,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  keys(A,doc,doc => +keys => V.root(A,doc,doc => +root =>
    V.field(A,doc,root,"biomes",doc => value => biomes(A,value,doc,keys,root,next))))
