import Base
import ../density.bend as Q
import ../density_batch.bend as DB
import ../precise.bend as D
import ../registry.bend as P
import ../reader.bend as R
import ../binary.bend as B
import ../compression.bend as Z
import ../transport.bend as T
import ./compress-file.bend as F

type Input is Type:
  Input{cursor: R.Cursor,model: Q.Model,programs: Q.Table<Q.Program>,queries: Array<DB.Request>,count: U32,valid: Bool}

def vector(-A: Type,cursor: R.Cursor,next: R.Cursor -> Q.Vec4 -> A) -> A:
  R.word(A,cursor,cursor => a => R.word(A,cursor,cursor => b => R.word(A,cursor,cursor => c => R.word(A,cursor,cursor => d =>
    next(cursor,Q.Vec4{R.float(a),R.float(b),R.float(c),R.float(d)})))))

def floats(-A: Type,n: Nat,cursor: R.Cursor,acc: List<&2,F32>,next: R.Cursor -> List<&2,F32> -> A) -> A:
  match n:
    case 0n: next(cursor,List.reverse(&2,F32,acc))
    case 1n+p: R.word(A,cursor,cursor => x => floats(A,p,cursor,R.float(x) <> acc,next))

@unsafe
def instructions(-A: Type,n: Nat,cursor: R.Cursor,acc: List<&2,Q.Instruction>,next: R.Cursor -> List<&2,Q.Instruction> -> A) -> A:
  match n:
    case 0n: next(cursor,List.reverse(&2,Q.Instruction,acc))
    case 1n+p: R.word(A,cursor,cursor => op => R.word(A,cursor,cursor => a => R.word(A,cursor,cursor => b => R.word(A,cursor,cursor => c =>
      vector(A,cursor,cursor => point => instructions(A,p,cursor,Q.Instruction{op,a,b,c,point} <> acc,next))))))

def roots(-A: Type,n: Nat,cursor: R.Cursor,acc: List<&2,U32>,next: R.Cursor -> List<&2,U32> -> A) -> A:
  match n:
    case 0n: next(cursor,List.reverse(&2,U32,acc))
    case 1n+p: R.word(A,cursor,cursor => x => roots(A,p,cursor,x <> acc,next))

@unsafe
def program(-A: Type,cursor: R.Cursor,next: R.Cursor -> Q.Program -> A) -> A:
  R.word(A,cursor,cursor => +n => R.word(A,cursor,cursor => +r => program_room(A,Bool.and(U32.is_le(n,2048),U32.is_le(r,8)),cursor,n,r,next)))

@unsafe
def program_room(-A: Type,room: Bool,cursor: R.Cursor,+n: U32,r: U32,next: R.Cursor -> Q.Program -> A) -> A:
  match room:
    case False{}: next(cursor,Q.Program{[],[],0})
    case True{}: roots(A,U32.to_nat(r),cursor,[],cursor => roots => instructions(A,U32.to_nat(n),cursor,[],cursor => nodes => next(cursor,Q.Program{nodes,roots,n})))

@unsafe
def noises(n: Nat,cursor: R.Cursor,acc: List<&2,Q.Noise>,+point_count: U32,+field_count: U32,+program_count: U32,+query_count: U32) -> Input:
  match n:
    case 0n: points(U32.to_nat(point_count),cursor,Q.prepared_table(Q.Noise,List.reverse(&2,Q.Noise,acc)),[],field_count,program_count,query_count)
    case 1n+p: R.word(Input,cursor,cursor => horizontal => R.word(Input,cursor,cursor => freq => R.word(Input,cursor,cursor => amp =>
      R.word(Input,cursor,cursor => salt => R.word(Input,cursor,cursor => +count => noise_room(U32.is_le(count,33),p,cursor,acc,
        R.float(horizontal),R.float(freq),R.float(amp),salt,count,point_count,field_count,program_count,query_count))))))

@unsafe
def noise_room(room: Bool,n: Nat,cursor: R.Cursor,acc: List<&2,Q.Noise>,horizontal: F32,freq: F32,amp: F32,salt: U32,count: U32,point_count: U32,field_count: U32,program_count: U32,+query_count: U32) -> Input:
  match room:
    case False{}: bad(cursor)
    case True{}: floats(Input,U32.to_nat(count),cursor,[],cursor => coefficients =>
      noises(n,cursor,Q.Noise{horizontal,freq,amp,salt,coefficients} <> acc,point_count,field_count,program_count,query_count))

@unsafe
def points(n: Nat,cursor: R.Cursor,noises: Q.Table<Q.Noise>,acc: List<&2,Q.Vec4>,field_count: U32,program_count: U32,+query_count: U32) -> Input:
  match n:
    case 0n: fields(U32.to_nat(field_count),cursor,noises,Q.prepared_table(Q.Vec4,List.reverse(&2,Q.Vec4,acc)),[],program_count,query_count)
    case 1n+p: vector(Input,cursor,cursor => value => points(p,cursor,noises,value <> acc,field_count,program_count,query_count))

@unsafe
def fields(n: Nat,cursor: R.Cursor,noises: Q.Table<Q.Noise>,points: Q.Table<Q.Vec4>,acc: List<&2,Q.Field>,program_count: U32,+query_count: U32) -> Input:
  match n:
    case 0n: fields_done(cursor,noises,points,List.reverse(&2,Q.Field,acc),program_count,query_count)
    case 1n+p: R.word(Input,cursor,cursor => horizontal => R.word(Input,cursor,cursor => vertical =>
      program(Input,cursor,cursor => program => fields(p,cursor,noises,points,Q.Field{program,horizontal,vertical} <> acc,program_count,query_count))))

@unsafe
def fields_done(cursor: R.Cursor,noises: Q.Table<Q.Noise>,points: Q.Table<Q.Vec4>,+fields: List<&2,Q.Field>,program_count: U32,+query_count: U32) -> Input:
  +model : Q.Model = Q.Model{noises,points,Q.prepared_table(Q.Field,fields)}
  programs(U32.to_nat(program_count),cursor,model,[],query_count,Q.valid_model(model))

@unsafe
def programs(n: Nat,cursor: R.Cursor,+model: Q.Model,acc: List<&2,Q.Program>,+query_count: U32,+valid: Bool) -> Input:
  match n model:
    case 0n _: queries(U32.to_nat(query_count),cursor,model,Q.prepared_table(Q.Program,List.reverse(&2,Q.Program,acc)),
      Array.new(DB.Request,P.capacity(query_count,1,0n),DB.Request{4294967295,0,0,0,0,0}),0,query_count,valid)
    case 1n+p Q.Model{_,_,+fields}: program(Input,cursor,cursor => +value => programs(p,cursor,model,value <> acc,query_count,
      Bool.and(valid,Q.valid_program(model,value,Q.table_size(Q.Field,fields)))))

@unsafe
def queries(n: Nat,cursor: R.Cursor,+model: Q.Model,+programs: Q.Table<Q.Program>,a: Array<DB.Request>,+i: U32,+count: U32,+valid: Bool) -> Input:
  match n:
    case 0n: Input{cursor,model,programs,a,count,valid}
    case 1n+p: R.word(Input,cursor,cursor => +program => R.word(Input,cursor,cursor => x => R.word(Input,cursor,cursor => y =>
      R.word(Input,cursor,cursor => z => R.word(Input,cursor,cursor => low => R.word(Input,cursor,cursor => high =>
      queries(p,cursor,model,programs,Array.set(DB.Request,a,i,DB.Request{program,x,y,z,low,high}),U32.inc(i),count,
        Bool.and(valid,U32.is_lt(program,Q.table_size(Q.Program,programs))))))))))

def bad(cursor: R.Cursor) -> Input:
  Input{cursor,Q.Model{Q.Empty{},Q.Empty{},Q.Empty{}},Q.Empty{},[DB.Request{4294967295,0,0,0,0,0}:DB.Request*1n],0,False{}}

@unsafe
def room(valid: Bool,cursor: R.Cursor,noise_count: U32,points: U32,fields: U32,programs: U32,queries: U32) -> Input:
  match valid:
    case False{}: bad(cursor)
    case True{}: noises(U32.to_nat(noise_count),cursor,[],points,fields,programs,queries)

def decode(bytes: Z.Bytes) -> Input:
  R.word(Input,R.start(bytes),cursor => +n => R.word(Input,cursor,cursor => +p => R.word(Input,cursor,cursor => +f =>
  R.word(Input,cursor,cursor => +g => R.word(Input,cursor,cursor => +q =>
    room(Bool.and(Bool.and(U32.is_le(n,8192),U32.is_le(p,65536)),Bool.and(U32.is_le(f,128),Bool.and(U32.is_le(g,256),U32.is_le(q,4096)))),cursor,n,p,f,g,q))))))

@unsafe
def encode(n: Nat,a: Array<Q.Outputs>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: encoded(p,Array.get(Q.Outputs,a,i),w,i)

@unsafe
def encoded(n: Nat,pair: Array<Q.Outputs> & Q.Outputs,w: B.Writer,i: U32) -> B.Writer:
  (a,output) = pair
  encode_row(n,a,output,w,i)

@unsafe
def encode_row(n: Nat,a: Array<Q.Outputs>,output: Q.Outputs,w: B.Writer,i: U32) -> B.Writer:
  match output:
    case Q.Outputs{v0,v1,v2,v3,v4,v5}: encode(n,a,B.int(B.int(B.int(B.int(B.int(B.int(w,
      F32.bits(D.value(v0))),F32.bits(D.value(v1))),F32.bits(D.value(v2))),F32.bits(D.value(v3))),F32.bits(D.value(v4))),F32.bits(D.value(v5))),U32.inc(i))

def save(result: Result<&1,&1,U32,Z.Bytes>,path: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"density fixture output overflow")
    case Done{bytes}: F.save(bytes,path)

def generated(computed: DB.Computed,count: U32,path: String) -> IO(Unit):
  match computed:
    case DB.Computed{_,_,rows}: save(B.done(encode(U32.to_nat(count),rows,B.new(),0)),path)

def generate(valid: Bool,model: Q.Model,programs: Q.Table<Q.Program>,queries: Array<DB.Request>,count: U32,path: String) -> IO(Unit):
  match valid:
    case False{}: IO.die(Unit,715,"invalid density fixture")
    case True{}:
      do IO<Unit>:
        _ : Nat <- IO.now()
        generated(DB.execute(model,programs,queries),count,path)

def decoded(input: Input,path: String) -> IO(Unit):
  match input:
    case Input{cursor,model,programs,queries,count,valid}: generate(Bool.and(valid,R.end(cursor)),model,programs,queries,count,path)

def data(read: T.Read,path: String) -> IO(Unit):
  match read:
    case T.End{_}: IO.die(Unit,715,"missing density fixture")
    case T.Complete{file,bytes}:
      do IO<Unit>:
        _ : Unit <- File.close(file)
        decoded(decode(bytes),path)

def size(status: Result<&1,&1,U32 & String,U32>,file: File,path: String) -> IO(Unit):
  match status:
    case Fail{_}: IO.die(Unit,715,"unreadable density fixture")
    case Done{count}:
      do IO<Unit>:
        read : T.Read <- T.exact(file,count,B.new(),False{})
        data(read,path)

def sized(pair: File & Result<&1,&1,U32 & String,U32>,path: String) -> IO(Unit):
  (file,status) = pair
  size(status,file,path)

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        file : File <- IO.try(File,File.open(input,"r"))
        pair : File & Result<&1,&1,U32 & String,U32> <- File.size(file)
        sized(pair,output)
    case _: IO.die(Unit,2,"usage: density-fixtures input output [--gpu on|off] [--threads N]")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
