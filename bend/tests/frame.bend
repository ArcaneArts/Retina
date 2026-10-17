import Base
import ../frame.bend as F
import ../binary.bend as B
import ../compression.bend as Z

def check(decoded: F.Header,valid: Bool,length: U32,id: U32,opcode: U32) -> U32:
  match decoded:
    case F.Header{actual,got_length,got_id,got_opcode}:
      Z.word(Bool.and(U32.is_eq(Z.word(actual,1,0),Z.word(valid,1,0)),Bool.and(U32.is_eq(got_length,length),
        Bool.and(U32.is_eq(got_id,id),U32.is_eq(got_opcode,opcode)))),0,1)

def encoded(result: Result<&1,&1,U32,Z.Bytes>,valid: Bool,length: U32,id: U32,opcode: U32) -> U32:
  match result:
    case Fail{_}: 1
    case Done{bytes}: check(F.decode(bytes),valid,length,id,opcode)

def header(magic: U32,+length: U32,+id: U32,+opcode: U32,valid: Bool) -> U32:
  encoded(B.done(B.int(B.int(B.int(B.int(B.new(),magic),length),id),opcode)),valid,length,id,opcode)

def rejected(header: F.Header) -> U32:
  match header:
    case F.Header{False{},_,_,_}: 0
    case _: 1

def invalid_size(result: Result<&1,&1,U32,Z.Bytes>,size: U32) -> U32:
  match result:
    case Done{Z.Bytes{a,_}}: rejected(F.decode(Z.Bytes{a,size}))
    case Fail{_}: 1

def canonical() -> Result<&1,&1,U32,Z.Bytes>:
  B.done(B.byte(B.int(B.int(B.int(B.int(B.new(),1380077124),0),0),0),0))

def truncations(+n: Nat) -> U32:
  match n:
    case 0n: 0
    case 1n+p: U32.add(invalid_size(canonical(),U32.from_nat(p)),truncations(p))

def result(failures: U32) -> IO(Unit):
  match failures:
    case 0: IO.print("PASS: 24 frame header cases and 13 buffer capacity cases")
    case _: IO.die(Unit,1,"frame header regression")

type Capacity is Data:
  Capacity{length: U32,expected: U32}

def capacities(cases: List<&2,Capacity>) -> U32:
  match cases:
    case Nil{}: 0
    case pair <> rest:
      Capacity{length,expected} = pair
      U32.add(Z.word(U32.is_eq(F.body_capacity(length),expected),0,1),capacities(rest))

def main() -> IO(Unit):
  result(U32.add(capacities([Capacity{0,256},Capacity{1,256},Capacity{255,256},Capacity{256,256},Capacity{257,512},
    Capacity{511,512},Capacity{512,512},Capacity{513,1024},Capacity{65535,65536},Capacity{65536,65536},Capacity{65537,131072},
    Capacity{16777215,16777216},Capacity{16777216,16777216}]),
    U32.add(U32.add(truncations(16n),invalid_size(canonical(),17)),
    U32.add(header(1380077124,0,0,0,True{}),
    U32.add(header(1380077124,16777216,4294967295,4294967295,True{}),
    U32.add(header(1380077124,16777217,123,4,False{}),
    U32.add(header(1380077124,4294967295,456,999,False{}),
    U32.add(header(0,0,123,0,False{}),
    U32.add(header(1380077125,1,456,55,False{}),
      header(1380077124,1,2147483648,999,True{}))))))))))
