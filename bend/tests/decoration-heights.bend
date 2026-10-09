import Base
import ../registry.bend as P
import ../registry_decoration_heights.bend as RH
import ../placement_height.bend as H
import ../placement_random.bend as R
import ../density.bend as Q
import ../word64.bend as W
import ../noise.bend as N
import ../binary.bend as B
import ./binary-fixtures.bend as T

type Rows is Type:
  Leaf{id: U32,recipe: U32,modifier: U32,draws: Array<R.Draw>}
  Node{left: Rows,right: Rows}

@unsafe
def context(+id: U32) -> W.U64:
  context_kind(U32.mod(id,8))

@unsafe
def context_kind(+id: U32) -> W.U64:
  match id:
    case 0: W.U64{4294967232,384}
    case 1: W.U64{4294965264,4064}
    case 2: W.U64{0,256}
    case 3: W.U64{4294967168,512}
    case 4: W.U64{2147483648,4294967295}
    case 5: W.U64{2147483520,128}
    case 6: W.U64{2147483648,128}
    case _: W.U64{4294967295,1}

@unsafe
def seed(+id: U32,+i: U32) -> W.U64:
  W.U64{N.hash(U32.add(U32.mul(id,7919),i)),N.hash(U32.add(U32.mul(id,811),U32.mul(i,131)))}

@unsafe
def chain(+n: Nat,+provider: H.Provider,+state: W.U64,+minimum: U32,+height: U32) -> R.Draw:
  match n:
    case 0n: R.Draw{state,W.U64{0,minimum}}
    case 1n+p: advanced(p,provider,H.sample(provider,state,minimum,height),minimum,height)

@unsafe
def advanced(+n: Nat,+provider: H.Provider,+draw: R.Draw,+minimum: U32,+height: U32) -> R.Draw:
  match n draw:
    case 0n _: draw
    case _ R.Draw{state,_}: chain(n,provider,state,minimum,height)

@unsafe
def at(+world: W.U64,+provider: H.Provider,+id: U32,+i: U32) -> R.Draw:
  match world:
    case W.U64{minimum,height}: chain(U32.to_nat(U32.inc(U32.mod(U32.div(i,8),4))),provider,seed(id,U32.div(i,32)),minimum,height)

@unsafe
def fill(+n: Nat,+provider: H.Provider,+id: U32,draws: Array<R.Draw>) -> Array<R.Draw>:
  match n:
    case 0n: draws
    case 1n+p:
      +i = U32.from_nat(p)
      fill(p,provider,id,Array.set(R.Draw,draws,i,at(context(i),provider,id,i)))

@unsafe
def leaf(row: H.Row,+id: U32) -> Rows:
  match row:
    case H.Row{recipe,modifier,+provider}: Leaf{id,recipe,modifier,fill(256n,provider,id,Array.new(R.Draw,8n,R.Draw{W.U64{0,0},W.U64{0,0}}))}

@unsafe
def rows(+n: U32,+base: U32,+count: U32,+reverse: Bool,+model: Q.Table<H.Row>) -> Rows:
  split(U32.is_gt(n,1),n,base,count,reverse,model)

@unsafe
def split(+more: Bool,+n: U32,+base: U32,+count: U32,+reverse: Bool,+model: Q.Table<H.Row>) -> Rows:
  match more:
    case False{}:
      +id = N.word(reverse,U32.sub(U32.sub(count,1),base),base)
      leaf(Q.get(H.Row,model,id,H.Row{0,0,H.Constant{H.Absolute{0}}}),id)
    case True{}:
      +half = U32.div(n,2)
      a b = rows(half,base,count,reverse,model) rows(U32.sub(n,half),U32.add(base,half),count,reverse,model)
      Node{a,b}

@unsafe
def write_draw(pair: Array<R.Draw> & R.Draw,+n: Nat,+i: U32,w: B.Writer) -> B.Writer:
  (draws,R.Draw{state,value}) = pair
  write_draws(n,U32.inc(i),draws,B.long(B.int(w,R.low(value)),state))

@unsafe
def write_draws(+n: Nat,+i: U32,draws: Array<R.Draw>,w: B.Writer) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: write_draw(Array.get(R.Draw,draws,i),p,i,w)

@unsafe
def write(rows: Rows,w: B.Writer) -> B.Writer:
  match rows:
    case Leaf{id,recipe,modifier,draws}: write_draws(256n,0,draws,B.int(B.int(B.int(w,id),recipe),modifier))
    case Node{a,b}: write(b,write(a,w))

@unsafe
def context_bits() -> U32:
  U32.or(U32.or(U32.or(W.flag(H.context_valid(4294967232,384)),U32.mul(W.flag(H.context_valid(2147483648,4294967295)),2)),
    U32.mul(W.flag(H.context_valid(2147483647,1)),4)),
    U32.or(U32.or(U32.mul(W.flag(H.context_valid(0,0)),8),U32.mul(W.flag(H.context_valid(2147483647,2)),16)),U32.mul(W.flag(H.context_valid(0,4294967295)),32)))

@unsafe
def generated(+nonempty: Bool,+model: Q.Table<H.Row>,output: String,+reverse: Bool,+count: U32) -> IO(Unit):
  match nonempty:
    case False{}: T.unwrapped(B.done(B.int(B.int(B.new(),0),context_bits())),output)
    case True{}:
      do IO<Unit>:
        _ : Nat <- IO.now()
        values : Rows = rows!(count,0,count,reverse,model)
        T.unwrapped(B.done(write(values,B.int(B.int(B.new(),count),context_bits()))),output)

@unsafe
def prepared(result: Result<&1,&1,U32,Q.Table<H.Row>>,output: String,+reverse: Bool) -> IO(Unit):
  match result:
    case Fail{code}: T.unwrapped(B.done(B.int(B.int(B.new(),4294967295),code)),output)
    case Done{+model}: generated(U32.is_ne(Q.table_size(H.Row,model),0),model,output,reverse,Q.table_size(H.Row,model))

@unsafe
def loaded(result: Result<&1,&1,U32,P.Document>,output: String,+reverse: Bool) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid registry transport")
    case Done{doc}: RH.prepare(IO(Unit),doc,doc => result => prepared(result,output,reverse))

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
    case _: IO.die(Unit,2,"usage: decoration-heights profile.rbp output [reverse]")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
