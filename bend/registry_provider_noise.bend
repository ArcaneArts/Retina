import Base
import ./registry.bend as R
import ./registry_density.bend as D
import ./registry_cave_features.bend as A
import ./registry_values.bend as V
import ./density.bend as Q
import ./provider_noise.bend as N
import ./word64.bend as W
import ./int_provider.bend as I

@unsafe
def keys(-T: Type,doc: R.Document,next: R.Document -> D.Keys -> T) -> T:
  D.resolved(T,["frequency","amplitude","offsets","permutation","layers","coordinate_scale","decoration_provider_noises"],doc,[],next)

# Raw doubles preserve the low frequency bits used by the rounded-float dual
# provider path. The shared exporter emits these fields as f64, including zero.
@unsafe
def raw(found: Maybe<&2,R.Value>) -> Result<&1,&1,U32,W.U64>:
  match found:
    case Some{R.Value{4,+hi,+lo,_}}: raw_valid(N.finite(W.U64{hi,lo}),W.U64{hi,lo})
    case _: Fail{763}

def raw_valid(ok: Bool,value: W.U64) -> Result<&1,&1,U32,W.U64>:
  match ok:
    case False{}: Fail{763}
    case True{}: Done{value}

@unsafe
def member(xs: List<&2,U32>,+value: U32) -> Bool:
  match xs:
    case Nil{}: False{}
    case x <> rest: Bool.or(U32.is_eq(x,value),member(rest,value))

@unsafe
def permutation(+values: Q.Table<U32>,+i: U32,+seen: List<&2,U32>) -> Bool:
  match i:
    case 256: True{}
    case _: permutation_value(Q.get(U32,values,i,256),values,i,seen)

@unsafe
def permutation_value(+value: U32,+values: Q.Table<U32>,+i: U32,+seen: List<&2,U32>) -> Bool:
  permutation_range(U32.is_lt(value,256),value,values,i,seen)

@unsafe
def permutation_range(ok: Bool,+value: U32,+values: Q.Table<U32>,+i: U32,+seen: List<&2,U32>) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: permutation_unique(Bool.not(member(seen,value)),value,values,i,seen)

@unsafe
def permutation_unique(ok: Bool,+value: U32,+values: Q.Table<U32>,+i: U32,+seen: List<&2,U32>) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: permutation(values,U32.inc(i),value <> seen)

@unsafe
def offsets(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,List<&2,W.U64>> -> T) -> T:
  match found:
    case Some{+value}: offset_shape(T,value,doc,next)
    case _: next(doc,Fail{763})

@unsafe
def offset_shape(-T: Type,+value: R.Value,doc: R.Document,next: R.Document -> Result<&1,&1,U32,List<&2,W.U64>> -> T) -> T:
  match value:
    case R.Value{6,3,_,_}: offset_items(T,0,doc,value,[],next)
    case R.Value{10,3,_,_}: offset_items(T,0,doc,value,[],next)
    case _: next(doc,Fail{763})

@unsafe
def offset_items(-T: Type,+i: U32,doc: R.Document,+value: R.Value,acc: List<&2,W.U64>,next: R.Document -> Result<&1,&1,U32,List<&2,W.U64>> -> T) -> T:
  match i:
    case 3: next(doc,Done{List.reverse(&2,W.U64,acc)})
    case _: R.item(T,doc,value,i,doc => found => offset_item(T,raw(found),i,doc,value,acc,next))

@unsafe
def offset_item(-T: Type,result: Result<&1,&1,U32,W.U64>,+i: U32,doc: R.Document,+value: R.Value,acc: List<&2,W.U64>,next: R.Document -> Result<&1,&1,U32,List<&2,W.U64>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{763})
    case Done{x}: offset_items(T,U32.inc(i),doc,value,x <> acc,next)

@unsafe
def layer_valid(-T: Type,+valid: Bool,+frequency: W.U64,+amplitude: F32,+x: W.U64,+y: W.U64,+z: W.U64,+permutation: Q.Table<U32>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,N.Layer> -> T) -> T:
  match valid:
    case False{}: next(doc,Fail{763})
    case True{}: next(doc,Done{N.Layer{frequency,N.fixed(frequency),amplitude,N.fixed(x),N.fixed(y),N.fixed(z),permutation}})

@unsafe
def layer_fields(-T: Type,frequency: Result<&1,&1,U32,W.U64>,amplitude: Result<&1,&1,U32,F32>,offsets: Result<&1,&1,U32,List<&2,W.U64>>,perm_result: Result<&1,&1,U32,Q.Table<U32>>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,N.Layer> -> T) -> T:
  match frequency amplitude offsets perm_result:
    case Done{frequency} Done{+amplitude} Done{x <> y <> z <> Nil{}} Done{+perm}:
      layer_valid(T,Bool.and(I.finite(amplitude),Bool.and(U32.is_eq(Q.table_size(U32,perm),256),permutation(perm,0,[]))),frequency,amplitude,x,y,z,perm,doc,next)
    case _ _ _ _: next(doc,Fail{763})

@unsafe
def layer(-T: Type,doc: R.Document,+value: R.Value,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,N.Layer> -> T) -> T:
  D.named(T,doc,value,keys,0n,doc => f => D.named(T,doc,value,keys,1n,doc => a =>
    D.named(T,doc,value,keys,2n,doc => o => offsets(T,o,doc,doc => o =>
      D.named(T,doc,value,keys,3n,doc => p => A.array(T,p,doc,0,doc => p => layer_fields(T,raw(f),D.number(a),o,p,doc,next)))))))

@unsafe
def layer_items(-T: Type,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,acc: List<&2,N.Layer>,next: R.Document -> Result<&1,&1,U32,List<&2,N.Layer>> -> T) -> T:
  match n:
    case 0: next(doc,Done{List.reverse(&2,N.Layer,acc)})
    case _: R.value(T,doc,at,doc => v => R.get(T,doc,U32.inc(at),doc => span =>
      layer(T,doc,v,keys,doc => result => layer_item(T,result,U32.sub(n,1),U32.add(at,span),doc,keys,acc,next))))

@unsafe
def layer_item(-T: Type,result: Result<&1,&1,U32,N.Layer>,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,acc: List<&2,N.Layer>,next: R.Document -> Result<&1,&1,U32,List<&2,N.Layer>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{763})
    case Done{x}: layer_items(T,n,at,doc,keys,x <> acc,next)

@unsafe
def layers(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,List<&2,N.Layer>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: layer_items(T,n,U32.add(at,3),doc,keys,[],next)
    case _: next(doc,Fail{763})

@unsafe
def program_fields(-T: Type,rows: Result<&1,&1,U32,List<&2,N.Layer>>,scale: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,N.Program> -> T) -> T:
  match rows scale:
    case Done{rows} None{}: next(doc,Done{N.Program{rows,0.0}})
    case Done{rows} Some{x}: program_scaled(T,D.number(Some{x}),rows,doc,next)
    case _ _: next(doc,Fail{763})

@unsafe
def program_scaled(-T: Type,result: Result<&1,&1,U32,F32>,rows: List<&2,N.Layer>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,N.Program> -> T) -> T:
  match result:
    case Done{+scale}: program_valid(T,Bool.and(I.finite(scale),F32.is_gt(scale,0.0)),scale,rows,doc,next)
    case _: next(doc,Fail{763})

@unsafe
def program_valid(-T: Type,ok: Bool,scale: F32,rows: List<&2,N.Layer>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,N.Program> -> T) -> T:
  match ok:
    case False{}: next(doc,Fail{763})
    case True{}: next(doc,Done{N.Program{rows,scale}})

@unsafe
def program(-T: Type,doc: R.Document,+value: R.Value,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,N.Program> -> T) -> T:
  D.named(T,doc,value,keys,4n,doc => found => layers(T,found,doc,keys,doc => rows =>
    D.named(T,doc,value,keys,5n,doc => scale => program_fields(T,rows,scale,doc,next))))

@unsafe
def programs(-T: Type,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,acc: List<&2,N.Program>,next: R.Document -> Result<&1,&1,U32,Q.Table<N.Program>> -> T) -> T:
  match n:
    case 0: next(doc,Done{Q.prepared_table(N.Program,List.reverse(&2,N.Program,acc))})
    case _: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span =>
      program(T,doc,value,keys,doc => result => program_item(T,result,U32.sub(n,1),U32.add(at,span),doc,keys,acc,next))))

@unsafe
def program_item(-T: Type,result: Result<&1,&1,U32,N.Program>,+n: U32,+at: U32,doc: R.Document,+keys: D.Keys,acc: List<&2,N.Program>,next: R.Document -> Result<&1,&1,U32,Q.Table<N.Program>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{763})
    case Done{x}: programs(T,n,at,doc,keys,x <> acc,next)

@unsafe
def found(-T: Type,value: Maybe<&2,R.Value>,doc: R.Document,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,Q.Table<N.Program>> -> T) -> T:
  match value:
    case None{}: next(doc,Done{Q.Empty{}})
    case Some{R.Value{6,n,_,at}}: programs(T,n,U32.add(at,3),doc,keys,[],next)
    case _: next(doc,Fail{763})

@unsafe
def prepare(-T: Type,doc: R.Document,next: R.Document -> Result<&1,&1,U32,Q.Table<N.Program>> -> T) -> T:
  keys(T,doc,doc => +keys => V.root(T,doc,doc => root => D.named(T,doc,root,keys,6n,doc => value => found(T,value,doc,keys,next))))
