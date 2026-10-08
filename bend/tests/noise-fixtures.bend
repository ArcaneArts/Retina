import Base
import ../noise.bend as N
import ../binary.bend as B
import ./binary-fixtures.bend as T

type Row is Data:
  Row{cell: U32,fraction: U32,simplex: U32,perlin: U32,registry: U32,point: U32,interpolation: U32,hash: U32,neighbor: U32}

type Rows is Type:
  Leaf{rows: Array<Row>}
  Node{left: Rows,right: Rows}

def quantize(x: F32) -> U32:
  F32.to_u32(F32.mul(F32.add(x,2.0),1048576.0))

def frequency(+i: U32) -> F32:
  N.pick(U32.is_zero(U32.mod(i,7)),0.0,
    N.pick(U32.is_eq(U32.mod(i,7),1),0.0035,
    N.pick(U32.is_eq(U32.mod(i,7),2),0.015625,
    N.pick(U32.is_eq(U32.mod(i,7),3),0.125,
    N.pick(U32.is_eq(U32.mod(i,7),4),1.0,
    N.pick(U32.is_eq(U32.mod(i,7),5),3.25,0.000244140625))))))

def coordinate(+i: U32) -> U32:
  +h = N.hash(i)
  N.word(U32.is_zero(U32.mod(i,6)),U32.sub(U32.mod(h,8192),4096),
    N.word(U32.is_eq(U32.mod(i,6),1),U32.add(29999000,U32.mod(h,1000)),
    N.word(U32.is_eq(U32.mod(i,6),2),U32.sub(U32.mod(h,1000),30000000),
    N.word(U32.is_eq(U32.mod(i,6),3),U32.add(2147482648,U32.mod(h,1000)),
    N.word(U32.is_eq(U32.mod(i,6),4),U32.add(2147483648,U32.mod(h,1000)),h)))))

def registry_value(result: Result<&1,&1,U32,N.Stack>,x: U32,z: U32) -> F32:
  match result:
    case Fail{_}: 99.0
    case Done{stack}: N.sample(stack,x,z)

def row_axis(axis: N.Axis,+i: U32,+x: U32,+y: U32,+z: U32,+low: U32,+high: U32,+f: F32,+fx: F32,+fy: F32,+fz: F32) -> Row:
  match axis:
    case N.Axis{cell,fraction}:
      Row{cell,F32.to_u32(F32.mul(fraction,16777216.0)),
        quantize(N.world_simplex(x,z,f,low,high)),quantize(N.world_perlin(x,y,z,f,low,high)),
        quantize(registry_value(N.prepare(f,0.8,2,low,high,[1.0,0.0,0.25,1.0,F32.neg(1.0),0.5]),x,z)),
        quantize(N.simplex(F32.sub(U32.to_f32(U32.mod(i,1000)),500.0),F32.mul(F32.sub(U32.to_f32(U32.mod(N.hash(i),1000)),500.0),0.125),low,high)),
        quantize(N.trilinear(0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,fx,fy,fz)),N.hash(U32.xor(low,N.hash(high))),quantize(N.world_simplex(U32.inc(x),z,f,low,high))}

def row(+i: U32) -> Row:
  +x = coordinate(i)
  +y = U32.sub(U32.mod(N.hash(U32.add(i,31)),512),128)
  +z = coordinate(U32.add(i,19))
  +low = N.hash(U32.add(U32.div(i,16),117))
  +high = N.hash(U32.add(U32.div(i,32),437))
  +f = frequency(i)
  +fx = F32.mul(U32.to_f32(U32.and(i,255)),0.00390625)
  +fy = F32.mul(U32.to_f32(U32.and(N.hash(i),255)),0.00390625)
  +fz = F32.mul(U32.to_f32(U32.and(N.hash(U32.inc(i)),255)),0.00390625)
  row_axis(N.axis(x,f),i,x,y,z,low,high,f,fx,fy,fz)

def fill(+n: Nat,+base: U32,a: Array<Row>) -> Array<Row>:
  match n:
    case 0n: a
    case 1n+p:
      +i = U32.from_nat(p)
      fill(p,base,Array.set(Row,a,i,row(U32.add(base,i))))

def batch(+depth: Nat,+base: U32) -> Rows:
  match depth:
    case 0n: Leaf{fill(256n,base,Array.new(Row,8n,Row{0,0,0,0,0,0,0,0,0}))}
    case 1n+p:
      a b = batch(p,base) batch(p,U32.add(base,U32.shln(256,p)))
      Node{a,b}

def encode_row(r: Row,w: B.Writer) -> B.Writer:
  match r:
    case Row{a,b,c,d,e,f,g,h,i}: B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(w,a),b),c),d),e),f),g),h),i)

def encode_array(n: Nat,r: Array<Row> & Row,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n:
      (a,row) = r
      w
    case 1n+p:
      (a,row) = r
      encode_array(p,Array.get(Row,a,U32.inc(i)),encode_row(row,w),U32.inc(i))

def encode(rows: Rows,w: B.Writer) -> B.Writer:
  match rows:
    case Leaf{a}: encode_array(256n,Array.get(Row,a,0),w,0)
    case Node{a,b}: encode(b,encode(a,w))

def rejected(result: Result<&1,&1,U32,N.Stack>) -> IO(Unit):
  match result:
    case Fail{code}: T.expected(U32.is_eq(code,501))
    case Done{_}: T.expected(False{})

def ones(n: Nat) -> List<&2,F32>:
  match n:
    case 0n: []
    case 1n+p: 1.0 <> ones(p)

def zero_stack(result: Result<&1,&1,U32,N.Stack>) -> IO(Unit):
  match result:
    case Fail{_}: T.expected(False{})
    case Done{stack}: T.expected(F32.is_eq(N.sample(stack,117,4294967062),0.0))

def boundaries() -> IO(Unit):
  do IO<Unit>:
    _ : Unit <- T.expected(F32.is_ne(N.world_simplex(117,4294967062,0.0035,0,0),N.world_simplex(117,4294967062,0.0035,1,0)))
    _ : Unit <- T.expected(F32.is_ne(N.world_simplex(117,4294967062,0.0035,0,0),N.world_simplex(117,4294967062,0.0035,0,2147483648)))
    _ : Unit <- T.expected(F32.is_ne(N.world_perlin(117,23,4294967062,0.0035,4294967295,4294967295),
      N.world_perlin(117,23,4294967062,0.0035,4294967295,2147483648)))
    _ : Unit <- zero_stack(N.prepare(0.01,0.0,0,0,0,[1.0,1.0]))
    _ : Unit <- zero_stack(N.prepare(0.01,1.0,0,0,0,[0.0,F32.neg(1.0)]))
    zero_stack(N.prepare(0.0,1.0,0,0,0,ones(32n)))

def invalid() -> IO(Unit):
  do IO<Unit>:
    _ : Unit <- rejected(N.prepare(F32.neg(0.1),1.0,0,0,0,[1.0]))
    _ : Unit <- rejected(N.prepare(0.1,F32.neg(1.0),0,0,0,[1.0]))
    _ : Unit <- rejected(N.prepare(0.1,1.0,0,0,0,[]))
    _ : Unit <- rejected(N.prepare(0.1,1.0,0,0,0,ones(33n)))
    _ : Unit <- rejected(N.prepare(1073741824.0,1.0,0,0,0,[1.0,1.0]))
    _ : Unit <- rejected(N.prepare(0.1,1.0,0,0,0,[F32.div(0.0,0.0)]))
    _ : Unit <- rejected(N.prepare(F32.div(1.0,0.0),1.0,0,0,0,[1.0]))
    rejected(N.prepare(0.1,F32.div(1.0,0.0),0,0,0,[1.0]))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> output <> Nil{}:
      do IO<Unit>:
        _ : Unit <- invalid()
        _ : Unit <- boundaries()
        rows : Rows = batch!(5n,0)
        _ : Unit <- T.unwrapped(B.done(encode(rows,B.new())),output)
        IO.print("PASS: noise fixtures")
    case _: IO.die(Unit,2,"usage: noise-fixtures output [--gpu on|off] [--threads N]")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
