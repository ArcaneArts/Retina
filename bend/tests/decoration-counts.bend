import Base
import ../registry.bend as P
import ../registry_decoration_counts.bend as RC
import ../decoration_counts.bend as C
import ../int_provider.bend as I
import ../density.bend as Q
import ../placement_random.bend as R
import ../word64.bend as W
import ../noise.bend as N
import ../binary.bend as B
import ./binary-fixtures.bend as T

type Samples is Type:
  Samples{values: Array<W.U64>,states: Array<W.U64>,noise: Array<F32>}
type Rows is Type:
  Leaf{id: U32,recipe: U32,modifier: U32,slot: U32,samples: Samples}
  Node{left: Rows,right: Rows}

@unsafe
def position(+id: U32,+i: U32) -> W.U64:
  coordinates(U32.mod(i,4),U32.add(U32.mul(id,19),U32.mul(i,7)))

@unsafe
def coordinates(+kind: U32,+n: U32) -> W.U64:
  match kind:
    case 0: W.U64{U32.add(713,n),U32.sub(0,U32.add(1929,n))}
    case 1: W.U64{U32.add(29990000,n),U32.add(U32.sub(0,30000000),n)}
    case 2: W.U64{U32.sub(2147483647,n),U32.add(2147483648,n)}
    case _: W.U64{U32.add(2147483648,n),U32.sub(2147483647,n)}

@unsafe
def seed(+id: U32,+i: U32) -> W.U64:
  W.U64{U32.xor(2147483648,U32.mul(id,7919)),N.hash(U32.add(U32.mul(id,811),i))}

@unsafe
def sample_values(+draw: R.Draw,+n: Nat,+i: U32,+id: U32,+rule: C.Rule,+table: Q.Table<U32>,samples: Samples,+x: U32,+z: U32) -> Samples:
  match draw samples:
    case R.Draw{state,value} Samples{values,states,noise}:
      fill(n,id,rule,table,Samples{Array.set(W.U64,values,i,value),Array.set(W.U64,states,i,state),Array.set(F32,noise,i,C.noise(table,x,z,200.0))})

@unsafe
def sample_at(+at: W.U64,+n: Nat,+i: U32,+id: U32,+rule: C.Rule,+table: Q.Table<U32>,samples: Samples) -> Samples:
  match at:
    case W.U64{+x,+z}: sample_values(C.sample(rule,table,x,z,seed(id,i)),n,i,id,rule,table,samples,x,z)

@unsafe
def fill(+n: Nat,+id: U32,+rule: C.Rule,+table: Q.Table<U32>,samples: Samples) -> Samples:
  match n:
    case 0n: samples
    case 1n+p:
      +i = U32.from_nat(p)
      sample_at(position(id,i),p,i,id,rule,table,samples)

@unsafe
def leaf(row: C.Row,+id: U32,+table: Q.Table<U32>) -> Rows:
  match row:
    case C.Row{recipe,modifier,slot,rule}: Leaf{id,recipe,modifier,slot,
      fill(32n,id,rule,table,Samples{Array.new(W.U64,5n,W.U64{0,0}),Array.new(W.U64,5n,W.U64{0,0}),Array.new(F32,5n,0.0)})}

@unsafe
def rows(+n: U32,+base: U32,+count: U32,+reverse: Bool,+model: C.Model) -> Rows:
  rows_split(U32.is_gt(n,1),n,base,count,reverse,model)

@unsafe
def rows_split(+split: Bool,+n: U32,+base: U32,+count: U32,+reverse: Bool,+model: C.Model) -> Rows:
  match split model:
    case False{} C.Model{table,+rules}:
      +id = N.word(reverse,U32.sub(U32.sub(count,1),base),base)
      leaf(Q.get(C.Row,rules,id,C.Row{0,0,0,C.Provider{I.Constant{0}}}),id,table)
    case True{} _:
      +half = U32.div(n,2)
      a b = rows(half,base,count,reverse,model) rows(U32.sub(n,half),U32.add(base,half),count,reverse,model)
      Node{a,b}

@unsafe
def write_sample(value: Array<W.U64> & W.U64,+n: Nat,+i: U32,states: Array<W.U64>,noise: Array<F32>,w: B.Writer) -> B.Writer:
  (values,value) = value
  write_state(Array.get(W.U64,states,i),n,i,values,noise,B.long(w,value))

@unsafe
def write_state(state: Array<W.U64> & W.U64,+n: Nat,+i: U32,values: Array<W.U64>,noise: Array<F32>,w: B.Writer) -> B.Writer:
  (states,state) = state
  write_noise(Array.get(F32,noise,i),n,i,values,states,B.long(w,state))

@unsafe
def write_noise(noise: Array<F32> & F32,+n: Nat,+i: U32,values: Array<W.U64>,states: Array<W.U64>,w: B.Writer) -> B.Writer:
  (noise,F32{bits}) = noise
  write_samples(n,U32.inc(i),Samples{values,states,noise},B.int(w,U32{bits}))

@unsafe
def write_samples(+n: Nat,+i: U32,samples: Samples,w: B.Writer) -> B.Writer:
  match n samples:
    case 0n _: w
    case 1n+p Samples{values,states,noise}: write_sample(Array.get(W.U64,values,i),p,i,states,noise,w)

@unsafe
def write(rows: Rows,w: B.Writer) -> B.Writer:
  match rows:
    case Leaf{id,recipe,modifier,slot,samples}:
      write_samples(32n,0,samples,B.int(B.int(B.int(B.int(w,id),recipe),modifier),slot))
    case Node{a,b}: write(b,write(a,w))

@unsafe
def generated(+nonempty: Bool,+model: C.Model,output: String,+reverse: Bool,+count: U32) -> IO(Unit):
  match nonempty:
    case False{}: T.unwrapped(B.done(B.int(B.new(),0)),output)
    case True{}:
      do IO<Unit>:
        # Complete host registry preparation before selecting the compute pool.
        _ : Nat <- IO.now()
        values : Rows = rows!(count,0,count,reverse,model)
        T.unwrapped(B.done(write(values,B.int(B.new(),count))),output)

@unsafe
def prepared(result: Result<&1,&1,U32,C.Model>,output: String,+reverse: Bool) -> IO(Unit):
  match result:
    case Fail{code}: T.unwrapped(B.done(B.int(B.int(B.new(),4294967295),code)),output)
    case Done{+model}: counted(model,output,reverse)

@unsafe
def counted(+model: C.Model,output: String,+reverse: Bool) -> IO(Unit):
  match model:
    case C.Model{_,rules}:
      +count = Q.table_size(C.Row,rules)
      generated(U32.is_gt(count,0),model,output,reverse,count)

@unsafe
def loaded(result: Result<&1,&1,U32,P.Document>,output: String,+reverse: Bool) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid registry transport")
    case Done{doc}: RC.prepare(IO(Unit),doc,doc => result => prepared(result,output,reverse))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,P.Document> <- P.load(input)
        loaded(result,output,False{})
    case _ <> input <> output <> _ <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,P.Document> <- P.load(input)
        loaded(result,output,True{})
    case _: IO.die(Unit,2,"usage: decoration-counts profile.rbp output [reverse]")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
