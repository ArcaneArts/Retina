import Base
import ../registry.bend as R
import ../registry_values.bend as V
import ../registry_density.bend as D
import ../registry_provider_noise.bend as RN
import ../registry_block_provider.bend as RB
import ../registry_placement.bend as RP
import ../block_provider.bend as B
import ../provider_noise.bend as N
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../placement_random.bend as Random
import ../density.bend as Q
import ../word64.bend as W
import ../noise.bend as Noise
import ../binary.bend as Binary
import ./binary-fixtures.bend as Output
import ./placement-walker-world.bend as World

type Samples is Data:
  Samples{rows: List<&2,B.Sample>}

# Traverse the original decorations subtree, including nested providers, in
# document order. The fixture does not normalize or replace registry recipes.
@unsafe
def visit(-T: Type,doc: R.Document,+value: R.Value,+context: RB.Context,+palette: U32,+noises: U32,acc: List<&2,B.Provider>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Provider>> -> T) -> T:
  match value context:
    case R.Value{7,_,_,_} RB.Context{keys,_,_}: D.named(T,doc,value,keys,0n,doc => found => identified(T,found,doc,value,context,palette,noises,acc,next))
    case _ _: descend(T,doc,value,context,palette,noises,acc,next)

@unsafe
def identified(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+value: R.Value,+context: RB.Context,+palette: U32,+noises: U32,acc: List<&2,B.Provider>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Provider>> -> T) -> T:
  match found context:
    case Some{R.Value{5,id,_,_}} RB.Context{keys,_,_}: known(T,U32.is_ne(RB.name(keys,id,0),4294967295),doc,value,context,palette,noises,acc,next)
    case _ _: descend(T,doc,value,context,palette,noises,acc,next)

@unsafe
def known(-T: Type,known: Bool,doc: R.Document,+value: R.Value,+context: RB.Context,+palette: U32,+noises: U32,acc: List<&2,B.Provider>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Provider>> -> T) -> T:
  match known:
    case False{}: descend(T,doc,value,context,palette,noises,acc,next)
    case True{}: RB.prepare(T,Some{value},doc,context,palette,noises,doc => result => collected(T,result,doc,value,context,palette,noises,acc,next))

@unsafe
def collected(-T: Type,result: Result<&1,&1,U32,B.Provider>,doc: R.Document,+value: R.Value,+context: RB.Context,+palette: U32,+noises: U32,acc: List<&2,B.Provider>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Provider>> -> T) -> T:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{provider}: descend(T,doc,value,context,palette,noises,provider <> acc,next)

@unsafe
def descend(-T: Type,doc: R.Document,value: R.Value,+context: RB.Context,+palette: U32,+noises: U32,acc: List<&2,B.Provider>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Provider>> -> T) -> T:
  match value:
    case R.Value{6,n,_,at}: children(T,n,U32.add(at,3),0,doc,context,palette,noises,acc,next)
    case R.Value{7,n,_,at}: children(T,n,U32.add(at,3),1,doc,context,palette,noises,acc,next)
    case _: next(doc,Done{acc})

@unsafe
def children(-T: Type,+n: U32,+at: U32,+skip: U32,doc: R.Document,+context: RB.Context,+palette: U32,+noises: U32,acc: List<&2,B.Provider>,next: R.Document -> Result<&1,&1,U32,List<&2,B.Provider>> -> T) -> T:
  match n:
    case 0: next(doc,Done{acc})
    case _: R.value(T,doc,U32.add(at,skip),doc => value => R.get(T,doc,U32.inc(U32.add(at,skip)),doc => span =>
      visit(T,doc,value,context,palette,noises,acc,doc => result => child(T,result,U32.sub(n,1),U32.add(U32.add(at,skip),span),skip,doc,context,palette,noises,next))))

@unsafe
def child(-T: Type,result: Result<&1,&1,U32,List<&2,B.Provider>>,+n: U32,+at: U32,+skip: U32,doc: R.Document,+context: RB.Context,+palette: U32,+noises: U32,next: R.Document -> Result<&1,&1,U32,List<&2,B.Provider>> -> T) -> T:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{acc}: children(T,n,at,skip,doc,context,palette,noises,acc,next)

@unsafe
def point(+context: U32) -> P.Position:
  located(World.origin(context),context)

@unsafe
def located(+origin: P.Position,+context: U32) -> P.Position:
  match origin:
    case P.Position{x,_,z}: P.Position{U32.add(x,Noise.word(U32.is_eq(context,7),32,U32.mod(U32.mul(context,3),32))),U32.sub(U32.mul(context,17),64),U32.add(z,U32.mod(U32.mul(context,5),32))}

@unsafe
def seeded(+id: U32,+context: U32) -> W.U64:
  W.U64{U32.xor(2147483648,U32.mul(id,7919)),Noise.hash(U32.add(U32.mul(context,811),id))}

@unsafe
def control(+provider: B.Provider,+noises: Q.Table<N.Program>,+policy: P.Policy,+id: U32,+context: U32) -> List<&2,B.Sample>:
  match policy:
    case P.Policy{masks,_,_,_,_,_,_}:
      +world = World.world(policy,context)
      +at = point(context)
      +changed = E.put(world,at,U32.mod(U32.add(id,3),Q.table_size(U32,masks)))
      [B.optional(provider,seeded(id,context),at,world,noises),B.sample(provider,seeded(id,context),at,world,noises),
        B.optional(provider,seeded(id,context),at,changed,noises),B.sample(provider,seeded(id,context),at,changed,noises)]

@unsafe
def contexts(+provider: B.Provider,+noises: Q.Table<N.Program>,+policy: P.Policy,+id: U32,+context: U32,acc: List<&2,B.Sample>) -> Samples:
  match context:
    case 8: Samples{List.reverse(&2,B.Sample,acc)}
    case _: contexts(provider,noises,policy,id,U32.inc(context),List.append(&2,B.Sample,List.reverse(&2,B.Sample,control(provider,noises,policy,id,context)),acc))

@unsafe
def samples(+count: U32,+base: U32,+providers: Q.Table<B.Provider>,+noises: Q.Table<N.Program>,+policy: P.Policy) -> Q.Table<Samples>:
  samples_split(U32.is_gt(count,1),count,base,providers,noises,policy)

@unsafe
def samples_split(more: Bool,+count: U32,+base: U32,+providers: Q.Table<B.Provider>,+noises: Q.Table<N.Program>,+policy: P.Policy) -> Q.Table<Samples>:
  match more count:
    case _ 0: Q.Empty{}
    case False{} _: Q.Leaf{contexts(Q.get(B.Provider,providers,base,B.State{0}),noises,policy,base,0,[])}
    case True{} _:
      +half = U32.div(count,2)
      a b = samples(half,base,providers,noises,policy) samples(U32.sub(count,half),U32.add(base,half),providers,noises,policy)
      Q.Branch{count,a,b}

@unsafe
def noise_point(+i: U32) -> P.Position:
  P.Position{Noise.hash(U32.mul(i,2654435769)),U32.sub(U32.mul(i,29),512),Noise.hash(U32.mul(i,2246822507))}

@unsafe
def noise_samples(+count: U32,+base: U32,+noises: Q.Table<N.Program>) -> Q.Table<F32>:
  noise_split(U32.is_gt(count,1),count,base,noises)

@unsafe
def noise_split(more: Bool,+count: U32,+base: U32,+noises: Q.Table<N.Program>) -> Q.Table<F32>:
  match more count:
    case _ 0: Q.Empty{}
    case False{} _: Q.Leaf{N.get(noises,U32.div(base,64),noise_point(U32.mod(base,64)))}
    case True{} _:
      +half = U32.div(count,2)
      a b = noise_samples(half,base,noises) noise_samples(U32.sub(count,half),U32.add(base,half),noises)
      Q.Branch{count,a,b}

@unsafe
def write_samples(xs: List<&2,B.Sample>,writer: Binary.Writer) -> Binary.Writer:
  match xs:
    case Nil{}: writer
    case B.Sample{state,material} <> rest: write_samples(rest,Binary.long(Binary.int(writer,optional(material)),state))

def optional(value: Maybe<&2,U32>) -> U32:
  match value:
    case None{}: 4294967295
    case Some{x}: x

@unsafe
def write(+rows: Q.Table<Samples>,+i: U32,+count: U32,writer: Binary.Writer) -> Binary.Writer:
  match count:
    case 0: writer
    case _: write_row(Q.get(Samples,rows,i,Samples{[]}),rows,U32.inc(i),U32.sub(count,1),writer)

@unsafe
def write_row(row: Samples,+rows: Q.Table<Samples>,+i: U32,+count: U32,writer: Binary.Writer) -> Binary.Writer:
  match row:
    case Samples{samples}: write(rows,i,count,write_samples(samples,writer))

@unsafe
def write_noises(+rows: Q.Table<F32>,+i: U32,+count: U32,writer: Binary.Writer) -> Binary.Writer:
  match count:
    case 0: writer
    case _: write_noises(rows,U32.inc(i),U32.sub(count,1),Binary.int(writer,F32.bits(Q.get(F32,rows,i,0.0))))

@unsafe
def calculate(+providers: Q.Table<B.Provider>,+noises: Q.Table<N.Program>,+policy: P.Policy,output: String) -> IO(Unit):
  do IO<Unit>:
    +count : U32 = Q.table_size(B.Provider,providers)
    +noise_count : U32 = U32.mul(Q.table_size(N.Program,noises),64)
    results : Q.Table<Samples> = samples!(count,0,providers,noises,policy)
    values : Q.Table<F32> = noise_samples!(noise_count,0,noises)
    Output.unwrapped(Binary.done(write_noises(values,0,noise_count,write(results,0,count,Binary.int(Binary.int(Binary.new(),count),noise_count)))),output)

@unsafe
def providers(result: Result<&1,&1,U32,List<&2,B.Provider>>,+noises: Q.Table<N.Program>,+policy: P.Policy,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid block provider")
    case Done{xs}: calculate(Q.prepared_table(B.Provider,List.reverse(&2,B.Provider,xs)),noises,policy,output)

@unsafe
def decorations(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: RB.Context,+palette: U32,+noises: U32,next: R.Document -> Result<&1,&1,U32,List<&2,B.Provider>> -> T) -> T:
  match found:
    case None{}: next(doc,Done{[]})
    case Some{x}: visit(T,doc,x,context,palette,noises,[],next)

@unsafe
def noise_ready(result: Result<&1,&1,U32,Q.Table<N.Program>>,doc: R.Document,+policy: P.Policy,output: String) -> IO(Unit):
  match result policy:
    case Fail{code} _: IO.die(Unit,code,"invalid provider noise")
    case Done{+noises} P.Policy{masks,_,_,_,_,_,_}: RB.context(IO(Unit),doc,doc => context => V.root(IO(Unit),doc,doc => root =>
      V.field(IO(Unit),doc,root,"decorations",doc => value => decorations(IO(Unit),value,doc,context,Q.table_size(U32,masks),Q.table_size(N.Program,noises),doc => result => providers(result,noises,policy,output)))))

@unsafe
def policy_ready(result: Result<&1,&1,U32,P.Model>,doc: R.Document,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid placement profile")
    case Done{P.Model{_,_,policy}}: RN.prepare(IO(Unit),doc,doc => result => noise_ready(result,doc,policy,output))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid transport")
    case Done{doc}: RP.prepare(IO(Unit),doc,doc => result => policy_ready(result,doc,output))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: block-providers profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
