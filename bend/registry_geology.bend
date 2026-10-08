import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./registry_material.bend as RM
import ./density.bend as Q
import ./precise.bend as D
import ./geology.bend as G

# Resolve schema names once, then visit arrays sequentially. Projection happens
# in Bend on the host; immutable numeric records are usable on either backend.
@unsafe
def keys(-A: Type,doc: P.Document,next: P.Document -> RD.Keys -> A) -> A:
  RD.resolved(A,["cave_noises","biomes","carveable","lava","lava_level","geology_min_y","geology_height",
    "frequency","amplitude","modifiers","carvers","kind","probability","height","min","max","triangle","plateau",
    "count","thickness","horizontal","vertical","floor","room","width_smoothness","vertical_default","vertical_center",
    "thickness_range","horizontal_range","distance_range","vertical_range","rotation_range","id"],doc,[],next)

@unsafe
def valid(-A: Type,-T: Data,ok: Bool,value: T,doc: P.Document,next: P.Document -> Result<&1,&1,U32,T> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{736})
    case True{}: next(doc,Done{value})

@unsafe
def scalar(found: Maybe<&2,P.Value>,optional: Bool) -> Result<&1,&1,U32,F32>:
  match optional:
    case True{}: RD.default_number(found,0.0)
    case False{}: RD.number(found)

@unsafe
def floats(-A: Type,indices: List<&2,U32>,doc: P.Document,+value: P.Value,+keys: RD.Keys,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match indices:
    case Nil{}: next(doc,Done{List.reverse(&2,F32,acc)})
    case +i <> rest: RD.named(A,doc,value,keys,U32.to_nat(i),doc => found =>
      float_result(A,scalar(found,U32.is_ge(i,24)),rest,doc,value,keys,acc,next))

@unsafe
def float_result(-A: Type,result: Result<&1,&1,U32,F32>,indices: List<&2,U32>,doc: P.Document,value: P.Value,keys: RD.Keys,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{736})
    case Done{x}: floats(A,indices,doc,value,keys,x <> acc,next)

@unsafe
def noise_values(-A: Type,result: Result<&1,&1,U32,List<&2,F32>>,parameters: Result<&1,&1,U32,List<&2,F32>>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,G.Noise> -> A) -> A:
  match result parameters:
    case Done{+modifiers} Done{+frequency <> +amplitude <> Nil{}}:
      valid(A,G.Noise,G.noise_valid(G.Noise{frequency,amplitude,modifiers}),G.Noise{frequency,amplitude,modifiers},doc,next)
    case _ _: next(doc,Fail{736})

@unsafe
def noise(-A: Type,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,G.Noise> -> A) -> A:
  floats(A,[7,8],doc,value,keys,[],doc => parameters => RD.named(A,doc,value,keys,9n,doc => found =>
    RD.floats(A,doc,found,1,32,doc => result => noise_values(A,result,parameters,doc,next))))

@unsafe
def height_values(-A: Type,a: Result<&1,&1,U32,U32>,b: Result<&1,&1,U32,U32>,c: Result<&1,&1,U32,Bool>,d: Result<&1,&1,U32,U32>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,G.Height> -> A) -> A:
  match a b c d:
    case Done{+a} Done{+b} Done{+c} Done{+d}: valid(A,G.Height,G.height_valid(G.Height{a,b,c,d}),G.Height{a,b,c,d},doc,next)
    case _ _ _ _: next(doc,Fail{736})

@unsafe
def height(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,G.Height> -> A) -> A:
  match found:
    case Some{+value}: RD.named(A,doc,value,keys,14n,doc => a => RD.named(A,doc,value,keys,15n,doc => b =>
      RD.named(A,doc,value,keys,16n,doc => c => RD.named(A,doc,value,keys,17n,doc => d =>
        height_values(A,RD.signed(a),RD.signed(b),RM.boolean(c),RD.integer(d),doc,next)))))
    case _: next(doc,Fail{736})

@unsafe
def range(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match found:
    case None{}: next(doc,Done{[0.0,0.0]})
    case Some{value}: RD.floats(A,doc,Some{value},2,2,next)

@unsafe
def ranges(-A: Type,indices: List<&2,U32>,doc: P.Document,+value: P.Value,+keys: RD.Keys,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match indices:
    case Nil{}: next(doc,Done{List.reverse(&2,F32,acc)})
    case i <> rest: RD.named(A,doc,value,keys,U32.to_nat(i),doc => found => range(A,found,doc,
      doc => result => range_result(A,result,rest,doc,value,keys,acc,next)))

@unsafe
def range_result(-A: Type,result: Result<&1,&1,U32,List<&2,F32>>,indices: List<&2,U32>,doc: P.Document,value: P.Value,keys: RD.Keys,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match result:
    case Done{a <> b <> Nil{}}: ranges(A,indices,doc,value,keys,b <> a <> acc,next)
    case _: next(doc,Fail{736})

@unsafe
def id_ref(-A: Type,ref: Maybe<&2,P.StringRef>,id: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,U32> -> A) -> A:
  match ref:
    case Some{P.StringRef{_,length}}: valid(A,U32,U32.is_gt(length,0),id,doc,next)
    case _: next(doc,Fail{736})

@unsafe
def id(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,U32> -> A) -> A:
  match found:
    case Some{P.Value{5,+id,_,_}}: P.string(A,doc,id,doc => ref => id_ref(A,ref,id,doc,next))
    case _: next(doc,Fail{736})

@unsafe
def carver_values(-A: Type,id: Result<&1,&1,U32,U32>,kind: Result<&1,&1,U32,U32>,height: Result<&1,&1,U32,G.Height>,scalars: Result<&1,&1,U32,List<&2,F32>>,ranges: Result<&1,&1,U32,List<&2,F32>>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,G.Carver> -> A) -> A:
  match id kind height scalars ranges:
    case Done{id} Done{kind} Done{height}
        Done{probability <> thickness <> horizontal <> vertical <> count <> floor <> room <> smooth <> vdefault <> center <> Nil{}}
        Done{t0 <> t1 <> h0 <> h1 <> d0 <> d1 <> v0 <> v1 <> r0 <> r1 <> Nil{}}:
      +carver : G.Carver = G.Carver{id,kind,height,probability,Q.Vec4{thickness,horizontal,vertical,count},Q.Vec4{floor,room,smooth,vdefault},
        Q.Vec4{t0,t1,h0,h1},Q.Vec4{d0,d1,center,0.0},Q.Vec4{v0,v1,r0,r1}}
      valid(A,G.Carver,G.carver_valid(carver),carver,doc,next)
    case _ _ _ _ _: next(doc,Fail{736})

@unsafe
def carver(-A: Type,doc: P.Document,+value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,G.Carver> -> A) -> A:
  RD.named(A,doc,value,keys,32n,doc => found => id(A,found,doc,doc => id =>
    RD.named(A,doc,value,keys,11n,doc => kind => RD.named(A,doc,value,keys,13n,doc => found =>
      height(A,found,doc,keys,doc => height => floats(A,[12,19,20,21,18,22,23,24,25,26],doc,value,keys,[],doc => scalars =>
        ranges(A,[27,28,29,30,31],doc,value,keys,[],doc => ranges => carver_values(A,id,RD.integer(kind),height,scalars,ranges,doc,next))))))))

@unsafe
def noises(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,+low: U32,+high: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Noise>> -> A) -> A:
  match found:
    case Some{P.Value{6,+count,_,at}}: noises_room(A,Bool.and(U32.is_ge(count,low),U32.is_le(count,high)),count,doc,U32.add(at,3),keys,next)
    case _: next(doc,Fail{736})

@unsafe
def noises_room(-A: Type,ok: Bool,count: U32,doc: P.Document,at: U32,keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Noise>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{736})
    case True{}: noises_read(A,count,doc,at,keys,[],next)

@unsafe
def noises_read(-A: Type,+count: U32,doc: P.Document,+at: U32,+keys: RD.Keys,acc: List<&2,G.Noise>,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Noise>> -> A) -> A:
  match count:
    case 0: next(doc,Done{Q.prepared_table(G.Noise,List.reverse(&2,G.Noise,acc))})
    case _: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      noise(A,doc,value,keys,doc => result => noises_result(A,result,U32.sub(count,1),doc,U32.add(at,span),keys,acc,next))))

@unsafe
def noises_result(-A: Type,result: Result<&1,&1,U32,G.Noise>,count: U32,doc: P.Document,at: U32,keys: RD.Keys,acc: List<&2,G.Noise>,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Noise>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{736})
    case Done{value}: noises_read(A,count,doc,at,keys,value <> acc,next)

@unsafe
def carvers(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,+low: U32,+high: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Carver>> -> A) -> A:
  match found:
    case Some{P.Value{6,+count,_,at}}: carvers_room(A,Bool.and(U32.is_ge(count,low),U32.is_le(count,high)),count,doc,U32.add(at,3),keys,next)
    case _: next(doc,Fail{736})

@unsafe
def carvers_room(-A: Type,ok: Bool,count: U32,doc: P.Document,at: U32,keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Carver>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{736})
    case True{}: carvers_read(A,count,doc,at,keys,[],next)

@unsafe
def carvers_read(-A: Type,+count: U32,doc: P.Document,+at: U32,+keys: RD.Keys,acc: List<&2,G.Carver>,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Carver>> -> A) -> A:
  match count:
    case 0: next(doc,Done{Q.prepared_table(G.Carver,List.reverse(&2,G.Carver,acc))})
    case _: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      carver(A,doc,value,keys,doc => result => carvers_result(A,result,U32.sub(count,1),doc,U32.add(at,span),keys,acc,next))))

@unsafe
def carvers_result(-A: Type,result: Result<&1,&1,U32,G.Carver>,count: U32,doc: P.Document,at: U32,keys: RD.Keys,acc: List<&2,G.Carver>,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Carver>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{736})
    case Done{value}: carvers_read(A,count,doc,at,keys,value <> acc,next)

@unsafe
def biomes(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,+low: U32,+high: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Biome>> -> A) -> A:
  match found:
    case Some{P.Value{6,+count,_,at}}: biomes_room(A,Bool.and(U32.is_ge(count,low),U32.is_le(count,high)),count,doc,U32.add(at,3),keys,next)
    case _: next(doc,Fail{736})

@unsafe
def biomes_room(-A: Type,ok: Bool,count: U32,doc: P.Document,at: U32,keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Biome>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{736})
    case True{}: biomes_read(A,count,doc,at,keys,[],next)

@unsafe
def biomes_read(-A: Type,+count: U32,doc: P.Document,+at: U32,+keys: RD.Keys,acc: List<&2,G.Biome>,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Biome>> -> A) -> A:
  match count:
    case 0: next(doc,Done{Q.prepared_table(G.Biome,List.reverse(&2,G.Biome,acc))})
    case _: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      biome(A,doc,value,keys,doc => result => biomes_result(A,result,U32.sub(count,1),doc,U32.add(at,span),keys,acc,next))))

@unsafe
def biomes_result(-A: Type,result: Result<&1,&1,U32,G.Biome>,count: U32,doc: P.Document,at: U32,keys: RD.Keys,acc: List<&2,G.Biome>,next: P.Document -> Result<&1,&1,U32,Q.Table<G.Biome>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{736})
    case Done{value}: biomes_read(A,count,doc,at,keys,value <> acc,next)

@unsafe
def flags(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+keys: RD.Keys,+low: U32,+high: U32,next: P.Document -> Result<&1,&1,U32,Q.Table<Bool>> -> A) -> A:
  match found:
    case Some{P.Value{6,+count,_,at}}: flags_room(A,Bool.and(U32.is_ge(count,low),U32.is_le(count,high)),count,doc,U32.add(at,3),keys,next)
    case Some{P.Value{11,+count,b,at}}: flags_packed_room(A,Bool.and(U32.is_ge(count,low),U32.is_le(count,high)),count,doc,P.Value{11,count,b,at},next)
    case _: next(doc,Fail{736})

@unsafe
def flags_packed_room(-A: Type,ok: Bool,count: U32,doc: P.Document,value: P.Value,next: P.Document -> Result<&1,&1,U32,Q.Table<Bool>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{736})
    case True{}: flags_packed(A,count,doc,value,0,[],next)

@unsafe
def flags_packed(-A: Type,+count: U32,doc: P.Document,+value: P.Value,+i: U32,acc: List<&2,Bool>,next: P.Document -> Result<&1,&1,U32,Q.Table<Bool>> -> A) -> A:
  match count:
    case 0: next(doc,Done{Q.prepared_table(Bool,List.reverse(&2,Bool,acc))})
    case _: P.item(A,doc,value,i,doc => found => flags_packed_result(A,RM.boolean(found),U32.sub(count,1),doc,value,U32.inc(i),acc,next))

@unsafe
def flags_packed_result(-A: Type,result: Result<&1,&1,U32,Bool>,count: U32,doc: P.Document,value: P.Value,i: U32,acc: List<&2,Bool>,next: P.Document -> Result<&1,&1,U32,Q.Table<Bool>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{736})
    case Done{x}: flags_packed(A,count,doc,value,i,x <> acc,next)

@unsafe
def flags_room(-A: Type,ok: Bool,count: U32,doc: P.Document,at: U32,keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,Q.Table<Bool>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{736})
    case True{}: flags_read(A,count,doc,at,keys,[],next)

@unsafe
def flags_read(-A: Type,+count: U32,doc: P.Document,+at: U32,+keys: RD.Keys,acc: List<&2,Bool>,next: P.Document -> Result<&1,&1,U32,Q.Table<Bool>> -> A) -> A:
  match count:
    case 0: next(doc,Done{Q.prepared_table(Bool,List.reverse(&2,Bool,acc))})
    case _: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      flag(A,doc,value,keys,doc => result => flags_result(A,result,U32.sub(count,1),doc,U32.add(at,span),keys,acc,next))))

@unsafe
def flags_result(-A: Type,result: Result<&1,&1,U32,Bool>,count: U32,doc: P.Document,at: U32,keys: RD.Keys,acc: List<&2,Bool>,next: P.Document -> Result<&1,&1,U32,Q.Table<Bool>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{736})
    case Done{value}: flags_read(A,count,doc,at,keys,value <> acc,next)

@unsafe
def biome_result(-A: Type,result: Result<&1,&1,U32,Q.Table<G.Carver>>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,G.Biome> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{736})
    case Done{carvers}: next(doc,Done{G.Biome{carvers}})

@unsafe
def biome(-A: Type,doc: P.Document,value: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,G.Biome> -> A) -> A:
  RD.named(A,doc,value,keys,10n,doc => found => carvers(A,found,doc,keys,0,4,doc => result => biome_result(A,result,doc,next)))

@unsafe
def flag(-A: Type,doc: P.Document,value: P.Value,keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,Bool> -> A) -> A:
  next(doc,RM.boolean(Some{value}))

@unsafe
def profile_values(-A: Type,noises: Result<&1,&1,U32,Q.Table<G.Noise>>,biomes: Result<&1,&1,U32,Q.Table<G.Biome>>,flags: Result<&1,&1,U32,Q.Table<Bool>>,
    lava: Result<&1,&1,U32,U32>,level: Result<&1,&1,U32,U32>,minimum: Result<&1,&1,U32,U32>,height: Result<&1,&1,U32,U32>,+palette: U32,doc: P.Document,next: P.Document -> Result<&1,&1,U32,G.Profile> -> A) -> A:
  match noises biomes flags lava level minimum height:
    case Done{noises} Done{biomes} Done{flags} Done{+lava} Done{+level} Done{+minimum} Done{+height}:
      valid(A,G.Profile,Bool.and(U32.is_lt(lava,palette),Bool.and(Bool.and(U32.is_gt(height,0),U32.is_le(height,4096)),
        Bool.and(Bool.not(D.less(D.integer(level),D.integer(minimum))),
          Bool.and(D.less(D.integer(level),D.add(D.integer(minimum),D.unsigned(height))),
            Bool.not(D.less(D.integer(2147483647),D.add(D.integer(minimum),D.unsigned(height)))))))),
        G.Profile{noises,biomes,flags,lava,level,minimum,height},doc,next)
    case _ _ _ _ _ _ _: next(doc,Fail{736})

@unsafe
def prepare(-A: Type,doc: P.Document,+palette: U32,+biome_count: U32,next: P.Document -> Result<&1,&1,U32,G.Profile> -> A) -> A:
  keys(A,doc,doc => +keys => V.root(A,doc,doc => +root => RD.named(A,doc,root,keys,0n,doc => found =>
    noises(A,found,doc,keys,6,6,doc => noises => RD.named(A,doc,root,keys,1n,doc => found =>
    biomes(A,found,doc,keys,biome_count,biome_count,doc => biomes => RD.named(A,doc,root,keys,2n,doc => found =>
    flags(A,found,doc,keys,palette,palette,doc => flags => RD.named(A,doc,root,keys,3n,doc => lava =>
    RD.named(A,doc,root,keys,4n,doc => level => RD.named(A,doc,root,keys,5n,doc => minimum =>
    RD.named(A,doc,root,keys,6n,doc => height => profile_values(A,noises,biomes,flags,RD.integer(lava),RD.signed(level),RD.signed(minimum),RD.integer(height),palette,doc,next)))))))))))))
