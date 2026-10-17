import Base
import ./reader.bend as R
import ./compression.bend as Z
import ./binary.bend as B
import ./transport.bend as T

# Decode the fixed transport header independently of the resident world state.
# Returning a small value keeps parser continuations out of the engine's IO
# dispatch graph. Payload contents and opcode validation remain with dispatch.
type Header is Data:
  Header{valid: Bool,length: U32,id: U32,opcode: U32}

type Read is Type:
  Packet{input: File,id: U32,opcode: U32,body: Z.Bytes}
  End{input: File}

def decode(bytes: Z.Bytes) -> Header:
  R.word(Header,R.start(bytes),cursor => +magic => R.word(Header,cursor,cursor => +length =>
    R.word(Header,cursor,cursor => id => R.word(Header,cursor,cursor => opcode =>
      Header{Bool.and(R.end(cursor),Bool.and(U32.is_eq(magic,1380077124),U32.is_le(length,16777216))),
        length,id,opcode}))))

def body_read(read: T.Read,id: U32,opcode: U32) -> IO(Read):
  match read:
    case T.End{_}: IO.die(Read,602,"Bend transport ended before request body")
    case T.Complete{input,body}: IO.pure(Read,Packet{input,id,opcode,body})

def header_valid(header: Header,input: File) -> IO(Read):
  match header:
    case Header{False{},_,_,_}: IO.die(Read,606,"Invalid Bend frame magic or payload size")
    case Header{True{},length,id,opcode}:
      do IO<Read>:
        read : T.Read <- T.exact(input,length,B.new(),False{})
        body_read(read,id,opcode)

def header_read(read: T.Read) -> IO(Read):
  match read:
    case T.End{input}: IO.pure(Read,End{input})
    case T.Complete{input,header}: header_valid(decode(header),input)

def read(input: File) -> IO(Read):
  do IO<Read>:
    header : T.Read <- T.exact(input,16,B.new(),True{})
    header_read(header)
