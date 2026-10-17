import Base
import ../frame.bend as F
import ../binary.bend as B
import ../compression.bend as Z
import ../transport.bend as T

def bytes(result: Result<&1,&1,U32,Z.Bytes>) -> IO(Z.Bytes):
  match result:
    case Done{value}: IO.pure(Z.Bytes,value)
    case Fail{code}: IO.die(Z.Bytes,code,"frame fixture encoding failed")

@unsafe
def received(read: F.Read,output: File) -> IO(Unit):
  match read:
    case F.End{input}:
      do IO<Unit>:
        _ : Unit <- File.close(input)
        File.close(output)
    case F.Packet{input,id,opcode,Z.Bytes{a,+size}}:
      do IO<Unit>:
        header : Z.Bytes <- bytes(B.done(B.int(B.int(B.int(B.int(B.new(),1380077124),size),id),opcode)))
        output : File <- T.write(output,header)
        output : File <- T.write(output,Z.Bytes{a,size})
        loop(input,output)

@unsafe
def loop(input: File,output: File) -> IO(Unit):
  do IO<Unit>:
    read : F.Read <- F.read(input)
    received(read,output)

def main() -> IO(Unit):
  do IO<Unit>:
    input : File <- IO.try(File,File.open("/dev/stdin","r"))
    output : File <- IO.try(File,File.open("/dev/stdout","w"))
    loop(input,output)
