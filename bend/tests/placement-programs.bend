import Base
import ../registry.bend as R
import ../registry_placement.bend as RP
import ../placement_data.bend as P
import ../placement_height.bend as H
import ../placement_random.bend as Random
import ../int_provider.bend as I
import ../decoration_counts.bend as C
import ../density.bend as Q
import ../word64.bend as W
import ../noise.bend as N
import ../binary.bend as B
import ../compression.bend as Z
import ./binary-fixtures.bend as T
import ./placement-world-fixtures.bend as World

type Rows is Type:
  Leaf{bytes: Z.Bytes}
  Node{left: Rows,right: Rows}
type Output is Type:
  Output{rows: Rows,world: Array<U32>}

@unsafe
def words(+xs: Q.Table<U32>,+i: U32,+n: U32,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: words(xs,U32.inc(i),U32.sub(n,1),B.int(w,Q.get(U32,xs,i,0)))

@unsafe
def table(+xs: Q.Table<U32>,w: B.Writer) -> B.Writer:
  words(xs,0,Q.table_size(U32,xs),B.int(w,Q.table_size(U32,xs)))

def position(at: P.Position,w: B.Writer) -> B.Writer:
  match at:
    case P.Position{x,y,z}: B.int(B.int(B.int(w,x),y),z)

@unsafe
def predicates(xs: List<&2,P.Predicate>,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case p <> rest: predicates(rest,predicate(p,w))

@unsafe
def predicate(p: P.Predicate,w: B.Writer) -> B.Writer:
  match p:
    case P.Material{offset,allowed}: table(allowed,position(offset,B.int(w,0)))
    case P.All{+xs}: predicates(xs,B.int(B.int(w,1),U32.from_nat(List.length(&2,P.Predicate,xs))))
    case P.Any{+xs}: predicates(xs,B.int(B.int(w,2),U32.from_nat(List.length(&2,P.Predicate,xs))))
    case P.Not{p}: predicate(p,B.int(w,3))
    case P.Always{}: B.int(w,4)

def float(value: F32,w: B.Writer) -> B.Writer:
  match value:
    case F32{bits}: B.int(w,U32{bits})

@unsafe
def seed(+r: U32,+m: U32,+i: U32) -> W.U64:
  W.U64{U32.xor(2147483648,U32.mul(r,7919)),N.hash(U32.add(U32.mul(m,811),i))}

def draw(value: Random.Draw,w: B.Writer) -> B.Writer:
  match value:
    case Random.Draw{state,value}: B.long(B.int(w,Random.low(value)),state)

@unsafe
def integer(+p: I.Provider,+r: U32,+m: U32,+i: U32,w: B.Writer) -> B.Writer:
  match i:
    case 4: w
    case _: integer(p,r,m,U32.inc(i),draw(I.sample(p,seed(r,m,i)),w))

@unsafe
def height(+p: H.Provider,+r: U32,+m: U32,+i: U32,w: B.Writer) -> B.Writer:
  match i:
    case 4: w
    case _: height(p,r,m,U32.inc(i),draw(H.sample(p,seed(r,m,i),4294967232,384),w))

@unsafe
def count(+p: C.Rule,+permutation: Q.Table<U32>,+r: U32,+m: U32,+i: U32,w: B.Writer) -> B.Writer:
  match i:
    case 4: w
    case _: count(p,permutation,r,m,U32.inc(i),draw(C.sample(p,permutation,U32.add(29990000,U32.mul(i,37)),U32.add(4264967296,U32.mul(i,19)),seed(r,m,i)),w))

@unsafe
def modifier(p: P.Modifier,+permutation: Q.Table<U32>,+r: U32,+m: U32,w: B.Writer) -> B.Writer:
  match p:
    case P.Count{p}: count(p,permutation,r,m,0,B.int(w,0))
    case P.Layer{p}: integer(p,r,m,0,B.int(w,1))
    case P.Square{}: B.int(w,2)
    case P.Cuboid{xz,y,e,i}: integer(y,r,m,0,integer(xz,r,m,0,B.int(B.int(B.int(w,3),W.flag(e)),W.flag(i))))
    case P.Heightmap{map}: B.int(B.int(w,4),map)
    case P.HeightRange{p}: height(p,r,m,0,B.int(w,5))
    case P.Scan{direction,target,allowed,steps}: predicate(allowed,predicate(target,B.int(B.int(B.int(w,6),direction),steps)))
    case P.Offset{x,y,z}: integer(z,r,m,0,integer(y,r,m,0,integer(x,r,m,0,B.int(w,7))))
    case P.Filter{p}: predicate(p,B.int(w,8))
    case P.WaterDepth{maximum}: B.int(B.int(w,9),maximum)
    case P.Relative{map,minimum,maximum}: B.int(B.int(B.int(B.int(w,10),map),minimum),maximum)
    case P.Biome{}: B.int(w,11)
    case P.Select{a,b}: float(b,float(a,B.int(w,12)))
    case P.Rarity{chance}: B.int(B.int(w,13),chance)
    case P.Chance{chance}: float(chance,B.int(w,14))

@unsafe
def modifiers(+p: Q.Table<P.Modifier>,+permutation: Q.Table<U32>,+r: U32,+m: U32,+n: U32,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: modifiers(p,permutation,r,U32.inc(m),U32.sub(n,1),modifier(Q.get(P.Modifier,p,m,P.Square{}),permutation,r,m,w))

def leaf(result: Result<&1,&1,U32,Z.Bytes>) -> Rows:
  match result:
    case Done{bytes}: Leaf{bytes}
    case Fail{_}: Leaf{Z.Bytes{[0 : U32*1n],0}}

@unsafe
def recipe(p: P.Recipe,+permutation: Q.Table<U32>,+id: U32) -> Rows:
  match p:
    case P.Recipe{group,salt,active,+program}: leaf(B.done(modifiers(program,permutation,id,0,Q.table_size(P.Modifier,program),
      B.int(B.int(B.long(B.int(B.int(B.new(),id),group),salt),W.flag(active)),Q.table_size(P.Modifier,program)))))

@unsafe
def rows(+n: U32,+base: U32,+total: U32,+reverse: Bool,+model: P.Model) -> Rows:
  match n:
    case 0: Leaf{Z.Bytes{[0 : U32*1n],0}}
    case _: split(U32.is_gt(n,1),n,base,total,reverse,model)

@unsafe
def split(+more: Bool,+n: U32,+base: U32,+total: U32,+reverse: Bool,+model: P.Model) -> Rows:
  match more model:
    case False{} P.Model{recipes,permutation,_}:
      +id = N.word(reverse,U32.sub(U32.sub(total,1),base),base)
      recipe(Q.get(P.Recipe,recipes,id,P.empty_recipe()),permutation,id)
    case True{} _:
      +half = U32.div(n,2)
      a b = rows(half,base,total,reverse,model) rows(U32.sub(n,half),U32.add(base,half),total,reverse,model)
      Node{a,b}

@unsafe
def calculate(+model: P.Model,+n: U32,+reverse: Bool) -> Output:
  a b = rows(n,0,n,reverse,model) World.values(model,reverse)
  Output{a,b}

@unsafe
def write_bytes(+n: U32,+i: U32,bytes: Array<U32>,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: copied(Array.get(U32,bytes,i),U32.sub(n,1),U32.inc(i),w)

@unsafe
def copied(pair: Array<U32> & U32,+n: U32,+i: U32,w: B.Writer) -> B.Writer:
  (bytes,v) = pair
  write_bytes(n,i,bytes,B.byte(w,v))

@unsafe
def write(rows: Rows,w: B.Writer) -> B.Writer:
  match rows:
    case Leaf{Z.Bytes{bytes,size}}: write_bytes(size,0,bytes,w)
    case Node{a,b}: write(b,write(a,w))

@unsafe
def wrote(value: Output,+model: P.Model,+n: U32,output: String) -> IO(Unit):
  match value:
    case Output{rows,world}: T.unwrapped(B.done(World.write(1024n,0,world,write(rows,policy(model,B.int(B.new(),n))))),output)

@unsafe
def memberships(+xs: Q.Table<Q.Table<U32>>,+i: U32,+n: U32,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: memberships(xs,U32.inc(i),U32.sub(n,1),table(Q.get(Q.Table<U32>,xs,i,Q.Empty{}),w))

@unsafe
def policy(+model: P.Model,w: B.Writer) -> B.Writer:
  match model:
    case P.Model{_,_,P.Policy{masks,flags,+biomes,water,lava,bedrock,volume}}:
      memberships(biomes,0,Q.table_size(Q.Table<U32>,biomes),B.int(table(flags,table(masks,B.int(B.int(B.int(B.int(w,water),lava),bedrock),W.flag(volume)))),Q.table_size(Q.Table<U32>,biomes)))

@unsafe
def prepared(result: Result<&1,&1,U32,P.Model>,output: String,+reverse: Bool) -> IO(Unit):
  match result:
    case Fail{code}: T.unwrapped(B.done(B.int(B.int(B.new(),4294967295),code)),output)
    case Done{+model}: sized(model,output,reverse)

@unsafe
def sized(+model: P.Model,output: String,+reverse: Bool) -> IO(Unit):
  match model:
    case P.Model{recipes,_,_}:
      do IO<Unit>:
        _ : Nat <- IO.now()
        result : Output = calculate!(model,Q.table_size(P.Recipe,recipes),reverse)
        wrote(result,model,Q.table_size(P.Recipe,recipes),output)

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String,+reverse: Bool) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid registry transport")
    case Done{doc}: RP.prepare(IO(Unit),doc,doc => result => prepared(result,output,reverse))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output,False{})
    case _ <> input <> output <> _ <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output,True{})
    case _: IO.die(Unit,2,"usage: placement-programs profile.rbp output [reverse]")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
