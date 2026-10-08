import Base
import ./noise.bend as N
import ./binary.bend as B
import ./compression.bend as Z
import ./reader.bend as R
import ./transport.bend as T

# Persistent binary worker. Protocol v1 is a component transport, not yet a
# world generator: PING=0, PREPARE_NOISE=1, SAMPLE_GRID=2, COMPRESS=3, STOP=4.
# Requests: RBND magic, payload length, request ID, opcode (all BE u32).
# Responses add a status u32 after opcode. stdout contains frames only.
type Grid is Data:
  Grid{x: U32,z: U32,width: U32,count: U32,step: U32}

type Samples is Type:
  Leaf{base: U32,values: Array<U32>}
  Node{left: Samples,right: Samples}

def point(active: Bool,+grid: Grid,+stack: N.Stack,+i: U32) -> U32:
  match active grid:
    case False{} _: 0
    case True{} Grid{x,z,+width,_,+step}:
      F32.bits(N.sample(stack,U32.add(x,U32.mul(U32.mod(i,width),step)),U32.add(z,U32.mul(U32.div(i,width),step))))

def fill(+n: Nat,+base: U32,+grid: Grid,+stack: N.Stack,a: Array<U32>,+count: U32) -> Array<U32>:
  match n:
    case 0n: a
    case 1n+p:
      +i = U32.from_nat(p)
      fill(p,base,grid,stack,Array.set(U32,a,i,point(U32.is_lt(U32.add(base,i),count),grid,stack,U32.add(base,i))),count)

def sample(+depth: Nat,+base: U32,+grid: Grid,+stack: N.Stack,+count: U32) -> Samples:
  match depth:
    case 0n: Leaf{base,fill(256n,base,grid,stack,[0 : U32*256n],count)}
    case 1n+p:
      a b = sample(p,base,grid,stack,count) sample(p,U32.add(base,U32.shln(256,p)),grid,stack,count)
      Node{a,b}

@unsafe
def depth(+count: U32,+capacity: U32,n: Nat) -> Nat:
  depth_more(U32.is_gt(count,capacity),count,capacity,n)

@unsafe
def depth_more(more: Bool,count: U32,capacity: U32,n: Nat) -> Nat:
  match more:
    case False{}: n
    case True{}: depth(count,U32.mul(capacity,2),Nat.add(n,1n))

def encode_array(n: Nat,a: Array<U32>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: Z.read(B.Writer,a,i,a => value => encode_array(p,a,B.int(w,value),U32.inc(i)))

def encode(samples: Samples,w: B.Writer,+count: U32) -> B.Writer:
  match samples:
    case Leaf{+base,a}: encode_array(U32.to_nat(U32.min(256,Z.word(U32.is_lt(base,count),U32.sub(count,base),0))),a,w,0)
    case Node{a,b}: encode(b,encode(a,w,count),count)

def bytes(result: Result<&1,&1,U32,Z.Bytes>) -> IO(Z.Bytes):
  match result:
    case Done{value}: IO.pure(Z.Bytes,value)
    case Fail{code}: IO.die(Z.Bytes,code,"Bend response encoding failed")

def payload(w: B.Writer) -> IO(Z.Bytes):
  bytes(B.done(w))

def reply(output: File,+id: U32,+opcode: U32,+status: U32,body: Z.Bytes) -> IO(File):
  match body:
    case Z.Bytes{a,+size}:
      do IO<File>:
        header : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.int(B.new(),1380077124),size),id),opcode),status))
        output : File <- T.write(output,header)
        T.write(output,Z.Bytes{a,size})

@unsafe
def finish(input: File,output: File,+stack: Maybe<&2,N.Stack>,+backend: U32,id: U32,opcode: U32,status: U32,body: Z.Bytes,keep: Bool) -> IO(Unit):
  do IO<Unit>:
    output : File <- reply(output,id,opcode,status,body)
    continued(keep,input,output,stack,backend)

@unsafe
def continued(keep: Bool,input: File,output: File,stack: Maybe<&2,N.Stack>,backend: U32) -> IO(Unit):
  match keep:
    case True{}: loop(input,output,stack,backend)
    case False{}:
      do IO<Unit>:
        _ : Unit <- File.close(input)
        File.close(output)

@unsafe
def error(input: File,output: File,stack: Maybe<&2,N.Stack>,backend: U32,id: U32,opcode: U32,code: U32) -> IO(Unit):
  do IO<Unit>:
    body : Z.Bytes <- payload(B.int(B.new(),code))
    finish(input,output,stack,backend,id,opcode,1,body,True{})

def modifiers(-A: Type,n: Nat,cursor: R.Cursor,acc: List<&2,F32>,next: R.Cursor -> List<&2,F32> -> A) -> A:
  match n:
    case 0n: next(cursor,List.reverse(&2,F32,acc))
    case 1n+p: R.word(A,cursor,cursor => bits => modifiers(A,p,cursor,R.float(bits) <> acc,next))

@unsafe
def set_valid(valid: Bool,input: File,output: File,old: Maybe<&2,N.Stack>,backend: U32,id: U32,opcode: U32,
    cursor: R.Cursor,frequency: F32,amplitude: F32,channel: U32,low: U32,high: U32,count: U32) -> IO(Unit):
  match valid:
    case False{}: error(input,output,old,backend,id,opcode,603)
    case True{}:
      modifiers(IO(Unit),U32.to_nat(count),cursor,[],cursor => values =>
        set_ended(R.end(cursor),input,output,old,backend,id,opcode,frequency,amplitude,channel,low,high,values))

@unsafe
def set_ended(valid: Bool,input: File,output: File,old: Maybe<&2,N.Stack>,backend: U32,id: U32,opcode: U32,
    frequency: F32,amplitude: F32,channel: U32,low: U32,high: U32,values: List<&2,F32>) -> IO(Unit):
  match valid:
    case False{}: error(input,output,old,backend,id,opcode,603)
    case True{}: set_result(N.prepare(frequency,amplitude,channel,low,high,values),input,output,old,backend,id,opcode)

@unsafe
def set_result(result: Result<&1,&1,U32,N.Stack>,input: File,output: File,old: Maybe<&2,N.Stack>,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result:
    case Fail{code}: error(input,output,old,backend,id,opcode,code)
    case Done{stack}: finish(input,output,Some{stack},backend,id,opcode,0,Z.Bytes{[0 : U32*1n],0},True{})

@unsafe
def set(input: File,output: File,old: Maybe<&2,N.Stack>,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  R.word(IO(Unit),R.start(body),cursor => low => R.word(IO(Unit),cursor,cursor => high =>
    R.word(IO(Unit),cursor,cursor => frequency => R.word(IO(Unit),cursor,cursor => amplitude =>
    R.word(IO(Unit),cursor,cursor => channel => R.word(IO(Unit),cursor,cursor => +count =>
      set_valid(Bool.and(U32.is_gt(count,0),U32.is_le(count,32)),input,output,old,backend,id,opcode,
        cursor,R.float(frequency),R.float(amplitude),channel,low,high,count)))))))

@unsafe
def grid_valid(valid: Bool,input: File,output: File,+old: Maybe<&2,N.Stack>,backend: U32,id: U32,opcode: U32,
    x: U32,z: U32,+width: U32,+height: U32,step: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} None{}: error(input,output,old,backend,id,opcode,604)
    case True{} Some{stack}:
      do IO<Unit>:
        samples : Samples = sample!(depth(U32.mul(width,height),256,0n),0,Grid{x,z,width,U32.mul(width,height),step},stack,U32.mul(width,height))
        body : Z.Bytes <- payload(encode(samples,B.new(),U32.mul(width,height)))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def grid(input: File,output: File,old: Maybe<&2,N.Stack>,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  R.word(IO(Unit),R.start(body),cursor => x => R.word(IO(Unit),cursor,cursor => z =>
    R.word(IO(Unit),cursor,cursor => +width => R.word(IO(Unit),cursor,cursor => +height =>
    R.word(IO(Unit),cursor,cursor => +step =>
      grid_valid(Bool.and(R.end(cursor),Bool.and(Bool.and(U32.is_gt(width,0),U32.is_le(width,512)),
        Bool.and(Bool.and(U32.is_gt(height,0),U32.is_le(height,512)),Bool.and(U32.is_gt(step,0),U32.is_le(step,1024))))),
        input,output,old,backend,id,opcode,x,z,width,height,step))))))

@unsafe
def empty_command(valid: Bool,input: File,output: File,stack: Maybe<&2,N.Stack>,+backend: U32,id: U32,+opcode: U32) -> IO(Unit):
  match valid:
    case False{}: error(input,output,stack,backend,id,opcode,603)
    case True{}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.int(B.new(),1),2),0),36),backend))
        finish(input,output,stack,backend,id,opcode,0,body,U32.is_zero(opcode))

@unsafe
def dispatch(input: File,output: File,stack: Maybe<&2,N.Stack>,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match opcode:
    case 0: empty_command(R.end(R.start(body)),input,output,stack,backend,id,0)
    case 1: set(input,output,stack,backend,id,1,body)
    case 2: grid(input,output,stack,backend,id,2,body)
    case 3: finish(input,output,stack,backend,id,3,0,Z.compress(body),True{})
    case 4: empty_command(R.end(R.start(body)),input,output,stack,backend,id,4)
    case _: error(input,output,stack,backend,id,opcode,605)

@unsafe
def body_read(read: T.Read,output: File,stack: Maybe<&2,N.Stack>,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match read:
    case T.End{_}: IO.die(Unit,602,"Bend transport ended before request body")
    case T.Complete{input,body}: dispatch(input,output,stack,backend,id,opcode,body)

@unsafe
def header_valid(valid: Bool,input: File,output: File,stack: Maybe<&2,N.Stack>,backend: U32,+length: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid:
    case False{}: IO.die(Unit,606,"Invalid Bend frame magic or payload size")
    case True{}:
      do IO<Unit>:
        read : T.Read <- T.exact(input,length,B.new(),False{})
        body_read(read,output,stack,backend,id,opcode)

@unsafe
def header_read(read: T.Read,output: File,stack: Maybe<&2,N.Stack>,backend: U32) -> IO(Unit):
  match read:
    case T.End{input}: continued(False{},input,output,stack,backend)
    case T.Complete{input,header}:
      R.word(IO(Unit),R.start(header),cursor => +magic => R.word(IO(Unit),cursor,cursor => +length =>
        R.word(IO(Unit),cursor,cursor => id => R.word(IO(Unit),cursor,cursor => opcode =>
          header_valid(Bool.and(R.end(cursor),Bool.and(U32.is_eq(magic,1380077124),U32.is_le(length,16777216))),
            input,output,stack,backend,length,id,opcode)))))

@unsafe
def loop(input: File,output: File,stack: Maybe<&2,N.Stack>,backend: U32) -> IO(Unit):
  do IO<Unit>:
    read : T.Read <- T.exact(input,16,B.new(),True{})
    header_read(read,output,stack,backend)

def launch(backend: U32) -> IO(Unit):
  do IO<Unit>:
    input : File <- IO.try(File,File.open("/dev/stdin","r"))
    output : File <- IO.try(File,File.open("/dev/stdout","w"))
    loop(input,output,None{},backend)

def modes(cpu: Bool,gpu: Bool) -> IO(Unit):
  match cpu gpu:
    case True{} _: launch(0)
    case _ True{}: launch(1)
    case _ _: IO.die(Unit,2,"expected cpu or gpu execution mode")

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> +mode <> Nil{}:
      modes(String.eq(mode,"cpu"),String.eq(mode,"gpu"))
    case _: IO.die(Unit,2,"usage: engine cpu|gpu --threads N --gpu off|on")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
