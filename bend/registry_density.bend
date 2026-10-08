import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./density.bend as Q
import ./density_lattice.bend as L

# Typed numeric density projection from the resident registry tape. No Java
# choice of opcodes/noise parameters and no per-query registry traversal.
type Keys is Data:
  Keys{ids: List<&2,U32>}
type Prepared is Data:
  Prepared{model: Q.Model,programs: Q.Table<Q.Program>,noises: U32,points: U32,fields: U32,lattice: Maybe<&2,L.Grid>}

@unsafe
def key_id(value: Maybe<&2,U32>) -> U32:
  match value:
    case None{}: 4294967295
    case Some{id}: id

@unsafe
def resolved(-A: Type,names: List<String>,doc: P.Document,acc: List<&2,U32>,next: P.Document -> Keys -> A) -> A:
  match names:
    case Nil{}: next(doc,Keys{List.reverse(&2,U32,acc)})
    case name <> rest: V.key(A,doc,name,doc => id => resolved(A,rest,doc,key_id(id) <> acc,next))

@unsafe
def keys(-A: Type,doc: P.Document,next: P.Document -> Keys -> A) -> A:
  resolved(A,["op","a","b","c","p","nodes","roots","programs","interpolations","noises","points",
    "frequency","amplitude","horizontal_scale","salt","coefficients","input","cell"],doc,[],next)

@unsafe
def key(keys: Keys,i: Nat) -> U32:
  match keys:
    case Keys{ids}: key_id(List.get(&2,U32,ids,i))

@unsafe
def named(-A: Type,doc: P.Document,value: P.Value,keys: Keys,i: Nat,next: P.Document -> Maybe<&2,P.Value> -> A) -> A:
  P.field(A,doc,value,key(keys,i),next)

@unsafe
def integer(value: Maybe<&2,P.Value>) -> Result<&1,&1,U32,U32>:
  match value:
    case Some{P.Value{3,0,lo,_}}: Done{lo}
    case _: Fail{715}

@unsafe
def signed_valid(ok: Bool,lo: U32) -> Result<&1,&1,U32,U32>:
  match ok:
    case False{}: Fail{715}
    case True{}: Done{lo}

@unsafe
def signed(value: Maybe<&2,P.Value>) -> Result<&1,&1,U32,U32>:
  match value:
    case Some{P.Value{3,+hi,+lo,_}}: signed_valid(Bool.or(Bool.and(U32.is_zero(hi),U32.is_le(lo,2147483647)),
      Bool.and(U32.is_eq(hi,4294967295),U32.is_ge(lo,2147483648))),lo)
    case _: Fail{715}



@unsafe
def number(value: Maybe<&2,P.Value>) -> Result<&1,&1,U32,F32>:
  match value:
    case Some{P.Value{3,hi,lo,_}}: V.number(Some{P.Value{3,hi,lo,0}})
    case Some{P.Value{4,hi,lo,_}}: V.number(Some{P.Value{4,hi,lo,0}})
    case _: Fail{715}

@unsafe
def default_number(value: Maybe<&2,P.Value>,default: F32) -> Result<&1,&1,U32,F32>:
  match value:
    case None{}: Done{default}
    case Some{x}: number(Some{x})

@unsafe
def float_items(-A: Type,n: Nat,doc: P.Document,+value: P.Value,+i: U32,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match n:
    case 0n: next(doc,Done{List.reverse(&2,F32,acc)})
    case 1n+p: P.item(A,doc,value,i,doc => item => float_item(A,number(item),p,doc,value,U32.inc(i),acc,next))

@unsafe
def float_item(-A: Type,result: Result<&1,&1,U32,F32>,n: Nat,doc: P.Document,value: P.Value,i: U32,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{x}: float_items(A,n,doc,value,i,x <> acc,next)

@unsafe
def int_items(-A: Type,n: Nat,doc: P.Document,+value: P.Value,+i: U32,acc: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match n:
    case 0n: next(doc,Done{List.reverse(&2,U32,acc)})
    case 1n+p: P.item(A,doc,value,i,doc => item => int_item(A,integer(item),p,doc,value,U32.inc(i),acc,next))

@unsafe
def int_item(-A: Type,result: Result<&1,&1,U32,U32>,n: Nat,doc: P.Document,value: P.Value,i: U32,acc: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{x}: int_items(A,n,doc,value,i,x <> acc,next)

@unsafe
def floats_room(-A: Type,ok: Bool,doc: P.Document,value: P.Value,count: U32,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{715})
    case True{}: float_items(A,U32.to_nat(count),doc,value,0,[],next)

@unsafe
def floats(-A: Type,doc: P.Document,value: Maybe<&2,P.Value>,+low: U32,+high: U32,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match value:
    case Some{P.Value{+tag,+count,b,at}}: floats_room(A,Bool.and(Bool.or(U32.is_eq(tag,6),Bool.and(U32.is_ge(tag,8),U32.is_le(tag,10))),
      Bool.and(U32.is_ge(count,low),U32.is_le(count,high))),doc,P.Value{tag,count,b,at},count,next)
    case _: next(doc,Fail{715})

@unsafe
def ints_room(-A: Type,ok: Bool,doc: P.Document,value: P.Value,count: U32,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{715})
    case True{}: int_items(A,U32.to_nat(count),doc,value,0,[],next)

@unsafe
def ints(-A: Type,doc: P.Document,value: Maybe<&2,P.Value>,+low: U32,+high: U32,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match value:
    case Some{P.Value{+tag,+count,b,at}}: ints_room(A,Bool.and(Bool.or(U32.is_eq(tag,6),Bool.or(U32.is_eq(tag,8),U32.is_eq(tag,9))),
      Bool.and(U32.is_ge(count,low),U32.is_le(count,high))),doc,P.Value{tag,count,b,at},count,next)
    case _: next(doc,Fail{715})

@unsafe
def int_fields(-A: Type,indices: List<&2,U32>,doc: P.Document,+value: P.Value,+keys: Keys,acc: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match indices:
    case Nil{}: next(doc,Done{List.reverse(&2,U32,acc)})
    case i <> rest: named(A,doc,value,keys,U32.to_nat(i),doc => found => int_field(A,integer(found),rest,doc,value,keys,acc,next))

@unsafe
def int_field(-A: Type,result: Result<&1,&1,U32,U32>,indices: List<&2,U32>,doc: P.Document,value: P.Value,keys: Keys,acc: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{x}: int_fields(A,indices,doc,value,keys,x <> acc,next)

@unsafe
def vector(values: Result<&1,&1,U32,List<&2,F32>>) -> Result<&1,&1,U32,Q.Vec4>:
  match values:
    case Done{a <> b <> c <> d <> Nil{}}: Done{Q.Vec4{a,b,c,d}}
    case _: Fail{715}

@unsafe
def instruction_vector(-A: Type,result: Result<&1,&1,U32,Q.Vec4>,doc: P.Document,op: U32,a: U32,b: U32,c: U32,next: P.Document -> Result<&1,&1,U32,Q.Instruction> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{p}: next(doc,Done{Q.Instruction{op,a,b,c,p}})

@unsafe
def instruction_parameters(-A: Type,result: Result<&1,&1,U32,List<&2,U32>>,doc: P.Document,+value: P.Value,+keys: Keys,next: P.Document -> Result<&1,&1,U32,Q.Instruction> -> A) -> A:
  match result:
    case Done{op <> a <> b <> c <> Nil{}}: named(A,doc,value,keys,4n,doc => found =>
      floats(A,doc,found,4,4,doc => values => instruction_vector(A,vector(values),doc,op,a,b,c,next)))
    case _: next(doc,Fail{715})



@unsafe
def instruction(-A: Type,doc: P.Document,+value: P.Value,+keys: Keys,next: P.Document -> Result<&1,&1,U32,Q.Instruction> -> A) -> A:
  int_fields(A,[0,1,2,3],doc,value,keys,[],doc => result => instruction_parameters(A,result,doc,value,keys,next))

@unsafe
def nodes(-A: Type,doc: P.Document,value: Maybe<&2,P.Value>,+keys: Keys,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Instruction>> -> A) -> A:
  match value:
    case Some{P.Value{6,+count,_,at}}: nodes_room(A,Bool.and(U32.is_ge(count,1),U32.is_le(count,1024)),doc,U32.add(at,3),count,keys,next)
    case _: next(doc,Fail{715})

@unsafe
def nodes_room(-A: Type,ok: Bool,doc: P.Document,at: U32,count: U32,keys: Keys,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Instruction>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{715})
    case True{}: nodes_read(A,U32.to_nat(count),doc,at,keys,[],next)

@unsafe
def nodes_read(-A: Type,n: Nat,doc: P.Document,+at: U32,+keys: Keys,acc: List<&2,Q.Instruction>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Instruction>> -> A) -> A:
  match n:
    case 0n: next(doc,Done{List.reverse(&2,Q.Instruction,acc)})
    case 1n+p: P.get(A,doc,U32.inc(at),doc => span => P.value(A,doc,at,doc => value =>
      instruction(A,doc,value,keys,doc => result => nodes_result(A,result,p,doc,U32.add(at,span),keys,acc,next))))

@unsafe
def nodes_result(-A: Type,result: Result<&1,&1,U32,Q.Instruction>,n: Nat,doc: P.Document,at: U32,keys: Keys,acc: List<&2,Q.Instruction>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Instruction>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{value}: nodes_read(A,n,doc,at,keys,value <> acc,next)

@unsafe
def points(-A: Type,doc: P.Document,value: Maybe<&2,P.Value>,+keys: Keys,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Vec4>> -> A) -> A:
  match value:
    case Some{P.Value{6,+count,_,at}}: points_room(A,Bool.and(U32.is_ge(count,0),U32.is_le(count,65536)),doc,U32.add(at,3),count,keys,next)
    case _: next(doc,Fail{715})

@unsafe
def points_room(-A: Type,ok: Bool,doc: P.Document,at: U32,count: U32,keys: Keys,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Vec4>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{715})
    case True{}: points_read(A,U32.to_nat(count),doc,at,keys,[],next)

@unsafe
def points_read(-A: Type,n: Nat,doc: P.Document,+at: U32,+keys: Keys,acc: List<&2,Q.Vec4>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Vec4>> -> A) -> A:
  match n:
    case 0n: next(doc,Done{List.reverse(&2,Q.Vec4,acc)})
    case 1n+p: P.get(A,doc,U32.inc(at),doc => span => P.value(A,doc,at,doc => value =>
      point(A,doc,value,keys,doc => result => points_result(A,result,p,doc,U32.add(at,span),keys,acc,next))))

@unsafe
def points_result(-A: Type,result: Result<&1,&1,U32,Q.Vec4>,n: Nat,doc: P.Document,at: U32,keys: Keys,acc: List<&2,Q.Vec4>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Vec4>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{value}: points_read(A,n,doc,at,keys,value <> acc,next)

@unsafe
def noises(-A: Type,doc: P.Document,value: Maybe<&2,P.Value>,+keys: Keys,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Noise>> -> A) -> A:
  match value:
    case Some{P.Value{6,+count,_,at}}: noises_room(A,Bool.and(U32.is_ge(count,0),U32.is_le(count,8192)),doc,U32.add(at,3),count,keys,next)
    case _: next(doc,Fail{715})

@unsafe
def noises_room(-A: Type,ok: Bool,doc: P.Document,at: U32,count: U32,keys: Keys,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Noise>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{715})
    case True{}: noises_read(A,U32.to_nat(count),doc,at,keys,[],next)

@unsafe
def noises_read(-A: Type,n: Nat,doc: P.Document,+at: U32,+keys: Keys,acc: List<&2,Q.Noise>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Noise>> -> A) -> A:
  match n:
    case 0n: next(doc,Done{List.reverse(&2,Q.Noise,acc)})
    case 1n+p: P.get(A,doc,U32.inc(at),doc => span => P.value(A,doc,at,doc => value =>
      noise(A,doc,value,keys,doc => result => noises_result(A,result,p,doc,U32.add(at,span),keys,acc,next))))

@unsafe
def noises_result(-A: Type,result: Result<&1,&1,U32,Q.Noise>,n: Nat,doc: P.Document,at: U32,keys: Keys,acc: List<&2,Q.Noise>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Noise>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{value}: noises_read(A,n,doc,at,keys,value <> acc,next)

@unsafe
def fields(-A: Type,doc: P.Document,value: Maybe<&2,P.Value>,+keys: Keys,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Field>> -> A) -> A:
  match value:
    case Some{P.Value{6,+count,_,at}}: fields_room(A,Bool.and(U32.is_ge(count,0),U32.is_le(count,128)),doc,U32.add(at,3),count,keys,next)
    case None{}: next(doc,Done{[]})
    case _: next(doc,Fail{715})

@unsafe
def fields_room(-A: Type,ok: Bool,doc: P.Document,at: U32,count: U32,keys: Keys,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Field>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{715})
    case True{}: fields_read(A,U32.to_nat(count),doc,at,keys,[],next)

@unsafe
def fields_read(-A: Type,n: Nat,doc: P.Document,+at: U32,+keys: Keys,acc: List<&2,Q.Field>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Field>> -> A) -> A:
  match n:
    case 0n: next(doc,Done{List.reverse(&2,Q.Field,acc)})
    case 1n+p: P.get(A,doc,U32.inc(at),doc => span => P.value(A,doc,at,doc => value =>
      field(A,doc,value,keys,doc => result => fields_result(A,result,p,doc,U32.add(at,span),keys,acc,next))))

@unsafe
def fields_result(-A: Type,result: Result<&1,&1,U32,Q.Field>,n: Nat,doc: P.Document,at: U32,keys: Keys,acc: List<&2,Q.Field>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Field>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{value}: fields_read(A,n,doc,at,keys,value <> acc,next)

@unsafe
def programs(-A: Type,doc: P.Document,value: Maybe<&2,P.Value>,+keys: Keys,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Program>> -> A) -> A:
  match value:
    case Some{P.Value{6,+count,_,at}}: programs_room(A,Bool.and(U32.is_ge(count,3),U32.is_le(count,65543)),doc,U32.add(at,3),3,keys,next)
    case _: next(doc,Fail{715})

@unsafe
def programs_room(-A: Type,ok: Bool,doc: P.Document,at: U32,count: U32,keys: Keys,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Program>> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{715})
    case True{}: programs_read(A,U32.to_nat(count),doc,at,keys,[],next)

@unsafe
def programs_read(-A: Type,n: Nat,doc: P.Document,+at: U32,+keys: Keys,acc: List<&2,Q.Program>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Program>> -> A) -> A:
  match n:
    case 0n: next(doc,Done{List.reverse(&2,Q.Program,acc)})
    case 1n+p: P.get(A,doc,U32.inc(at),doc => span => P.value(A,doc,at,doc => value =>
      program(A,doc,value,keys,doc => result => programs_result(A,result,p,doc,U32.add(at,span),keys,acc,next))))

@unsafe
def programs_result(-A: Type,result: Result<&1,&1,U32,Q.Program>,n: Nat,doc: P.Document,at: U32,keys: Keys,acc: List<&2,Q.Program>,next: P.Document -> Result<&1,&1,U32,List<&2,Q.Program>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{value}: programs_read(A,n,doc,at,keys,value <> acc,next)

@unsafe
def point(-A: Type,doc: P.Document,value: P.Value,keys: Keys,next: P.Document -> Result<&1,&1,U32,Q.Vec4> -> A) -> A:
  floats(A,doc,Some{value},4,4,doc => result => next(doc,vector(result)))

@unsafe
def program_roots(-A: Type,result: Result<&1,&1,U32,List<&2,U32>>,doc: P.Document,nodes: List<&2,Q.Instruction>,next: P.Document -> Result<&1,&1,U32,Q.Program> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{roots}: program_complete(A,doc,nodes,roots,next)

@unsafe
def program_complete(-A: Type,doc: P.Document,+nodes: List<&2,Q.Instruction>,roots: List<&2,U32>,next: P.Document -> Result<&1,&1,U32,Q.Program> -> A) -> A:
  next(doc,Done{Q.Program{nodes,roots,U32.from_nat(List.length(&2,Q.Instruction,nodes))}})

@unsafe
def program_nodes(-A: Type,result: Result<&1,&1,U32,List<&2,Q.Instruction>>,doc: P.Document,value: P.Value,keys: Keys,next: P.Document -> Result<&1,&1,U32,Q.Program> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{nodes}: named(A,doc,value,keys,6n,doc => found =>
      ints(A,doc,found,0,6,doc => result => program_roots(A,result,doc,nodes,next)))

@unsafe
def program(-A: Type,doc: P.Document,+value: P.Value,+keys: Keys,next: P.Document -> Result<&1,&1,U32,Q.Program> -> A) -> A:
  named(A,doc,value,keys,5n,doc => found => nodes(A,doc,found,keys,doc => result => program_nodes(A,result,doc,value,keys,next)))

@unsafe
def field_cell(-A: Type,result: Result<&1,&1,U32,List<&2,U32>>,doc: P.Document,program: Q.Program,next: P.Document -> Result<&1,&1,U32,Q.Field> -> A) -> A:
  match result:
    case Done{horizontal <> vertical <> Nil{}}: next(doc,Done{Q.Field{program,horizontal,vertical}})
    case _: next(doc,Fail{715})

@unsafe
def field_program(-A: Type,result: Result<&1,&1,U32,Q.Program>,doc: P.Document,value: P.Value,keys: Keys,next: P.Document -> Result<&1,&1,U32,Q.Field> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{program}: named(A,doc,value,keys,17n,doc => found =>
      ints(A,doc,found,2,2,doc => result => field_cell(A,result,doc,program,next)))

@unsafe
def field_input(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,+value: P.Value,+keys: Keys,next: P.Document -> Result<&1,&1,U32,Q.Field> -> A) -> A:
  match found:
    case None{}: next(doc,Fail{715})
    case Some{input}: program(A,doc,input,keys,doc => result => field_program(A,result,doc,value,keys,next))

@unsafe
def field(-A: Type,doc: P.Document,+value: P.Value,+keys: Keys,next: P.Document -> Result<&1,&1,U32,Q.Field> -> A) -> A:
  named(A,doc,value,keys,16n,doc => found => field_input(A,found,doc,value,keys,next))

@unsafe
def float_fields(-A: Type,indices: List<&2,U32>,doc: P.Document,+value: P.Value,+keys: Keys,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match indices:
    case Nil{}: next(doc,Done{List.reverse(&2,F32,acc)})
    case +i <> rest: named(A,doc,value,keys,U32.to_nat(i),doc => found =>
      float_field(A,optional_number(U32.is_eq(i,13),found),rest,doc,value,keys,acc,next))

@unsafe
def optional_number(optional: Bool,found: Maybe<&2,P.Value>) -> Result<&1,&1,U32,F32>:
  match optional:
    case False{}: number(found)
    case True{}: default_number(found,1.0)

@unsafe
def float_field(-A: Type,result: Result<&1,&1,U32,F32>,indices: List<&2,U32>,doc: P.Document,value: P.Value,keys: Keys,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{x}: float_fields(A,indices,doc,value,keys,x <> acc,next)

@unsafe
def noise_coefficients(-A: Type,result: Result<&1,&1,U32,List<&2,F32>>,doc: P.Document,horizontal: F32,frequency: F32,amplitude: F32,salt: U32,next: P.Document -> Result<&1,&1,U32,Q.Noise> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{coefficients}: next(doc,Done{Q.Noise{horizontal,frequency,amplitude,salt,coefficients}})

@unsafe
def noise_salt(-A: Type,result: Result<&1,&1,U32,U32>,doc: P.Document,value: P.Value,keys: Keys,horizontal: F32,frequency: F32,amplitude: F32,next: P.Document -> Result<&1,&1,U32,Q.Noise> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{salt}: named(A,doc,value,keys,15n,doc => found =>
      floats(A,doc,found,1,32,doc => result => noise_coefficients(A,result,doc,horizontal,frequency,amplitude,salt,next)))

@unsafe
def noise_numbers(-A: Type,result: Result<&1,&1,U32,List<&2,F32>>,doc: P.Document,+value: P.Value,+keys: Keys,next: P.Document -> Result<&1,&1,U32,Q.Noise> -> A) -> A:
  match result:
    case Done{horizontal <> frequency <> amplitude <> Nil{}}: named(A,doc,value,keys,14n,doc => found =>
      noise_salt(A,signed(found),doc,value,keys,horizontal,frequency,amplitude,next))
    case _: next(doc,Fail{715})

@unsafe
def noise(-A: Type,doc: P.Document,+value: P.Value,+keys: Keys,next: P.Document -> Result<&1,&1,U32,Q.Noise> -> A) -> A:
  float_fields(A,[13,11,12],doc,value,keys,[],doc => result => noise_numbers(A,result,doc,value,keys,next))

@unsafe
def valid_programs(+model: Q.Model,programs: List<&2,Q.Program>,+limit: U32) -> Bool:
  match programs:
    case Nil{}: True{}
    case program <> rest: Bool.and(Q.valid_program(model,program,limit),valid_programs(model,rest,limit))

@unsafe
def complete(-A: Type,ok: Bool,doc: P.Document,model: Q.Model,programs: List<&2,Q.Program>,noise_count: U32,point_count: U32,field_count: U32,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match ok:
    case False{}: next(doc,Fail{715})
    case True{}: next(doc,Done{Prepared{model,Q.prepared_table(Q.Program,programs),noise_count,point_count,field_count,None{}}})

@unsafe
def projected(-A: Type,result: Result<&1,&1,U32,List<&2,Q.Program>>,doc: P.Document,+noises: List<&2,Q.Noise>,+points: List<&2,Q.Vec4>,+fields: List<&2,Q.Field>,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{+programs}:
      +model : Q.Model = Q.Model{Q.prepared_table(Q.Noise,noises),Q.prepared_table(Q.Vec4,points),Q.prepared_table(Q.Field,fields)}
      +count = U32.from_nat(List.length(&2,Q.Field,fields))
      complete(A,Bool.and(Q.valid_model(model),valid_programs(model,programs,count)),doc,model,programs,
        U32.from_nat(List.length(&2,Q.Noise,noises)),U32.from_nat(List.length(&2,Q.Vec4,points)),count,next)

@unsafe
def projected_fields(-A: Type,result: Result<&1,&1,U32,List<&2,Q.Field>>,doc: P.Document,+value: P.Value,+keys: Keys,noises: List<&2,Q.Noise>,points: List<&2,Q.Vec4>,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{fields}: named(A,doc,value,keys,7n,doc => found => programs(A,doc,found,keys,doc => result => projected(A,result,doc,noises,points,fields,next)))

@unsafe
def projected_points(-A: Type,result: Result<&1,&1,U32,List<&2,Q.Vec4>>,doc: P.Document,+value: P.Value,+keys: Keys,noises: List<&2,Q.Noise>,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{points}: named(A,doc,value,keys,8n,doc => found => fields(A,doc,found,keys,doc => result => projected_fields(A,result,doc,value,keys,noises,points,next)))

@unsafe
def projected_noises(-A: Type,result: Result<&1,&1,U32,List<&2,Q.Noise>>,doc: P.Document,+value: P.Value,+keys: Keys,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{715})
    case Done{noises}: named(A,doc,value,keys,10n,doc => found => points(A,doc,found,keys,doc => result => projected_points(A,result,doc,value,keys,noises,next)))

@unsafe
def projection(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  match found:
    case Some{P.Value{7,count,b,at}}:
      +value : P.Value = P.Value{7,count,b,at}
      keys(A,doc,doc => +keys => named(A,doc,value,keys,9n,doc => found => noises(A,doc,found,keys,doc => result => projected_noises(A,result,doc,value,keys,next))))
    case _: next(doc,Fail{715})

@unsafe
def prepare(-A: Type,doc: P.Document,next: P.Document -> Result<&1,&1,U32,Prepared> -> A) -> A:
  V.root(A,doc,doc => root => V.field(A,doc,root,"registry_program",doc => found => projection(A,found,doc,next)))
