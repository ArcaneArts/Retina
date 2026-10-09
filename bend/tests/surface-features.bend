import Base
import ../registry.bend as R
import ../registry_values.bend as V
import ../registry_density.bend as D
import ../registry_provider_noise.bend as RN
import ../registry_surface_feature.bend as RS
import ../registry_placement.bend as RP
import ../surface_feature.bend as F
import ../block_provider.bend as B
import ../provider_noise.bend as N
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../density.bend as Q
import ../word64.bend as W
import ../noise.bend as Noise
import ../binary.bend as Binary
import ./binary-fixtures.bend as Output
import ./surface-feature-world.bend as World

type Snapshot is Data:
  Snapshot{state: W.U64,placed: Bool,patch: E.Patch}
type Samples is Data:
  Samples{rows: List<&2,Snapshot>}

# Traverse the original decorations subtree, including nested features, in
# document order. The fixture does not normalize or replace registry recipes.
@unsafe
def visit(-T: Type,doc: R.Document,+value: R.Value,+context: RS.Context,+palette: U32,+noises: U32,acc: List<&2,F.Feature>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Feature>> -> T) -> T:
  match value context:
    case R.Value{7,_,_,_} RS.Context{keys,_}: D.named(T,doc,value,keys,0n,doc => found => identified(T,found,doc,value,context,palette,noises,acc,next))
    case _ _: descend(T,doc,value,context,palette,noises,acc,next)

@unsafe
def identified(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+value: R.Value,+context: RS.Context,+palette: U32,+noises: U32,acc: List<&2,F.Feature>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Feature>> -> T) -> T:
  match found context:
    case Some{R.Value{5,id,_,_}} RS.Context{keys,_}: known(T,U32.is_ne(RS.name(keys,id,0),4294967295),doc,value,context,palette,noises,acc,next)
    case _ _: descend(T,doc,value,context,palette,noises,acc,next)

@unsafe
def known(-T: Type,known: Bool,doc: R.Document,+value: R.Value,+context: RS.Context,+palette: U32,+noises: U32,acc: List<&2,F.Feature>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Feature>> -> T) -> T:
  match known:
    case False{}: descend(T,doc,value,context,palette,noises,acc,next)
    case True{}: RS.prepare(T,Some{value},doc,context,palette,noises,doc => result => collected(T,result,doc,value,context,palette,noises,acc,next))

@unsafe
def collected(-T: Type,result: Result<&1,&1,U32,F.Feature>,doc: R.Document,+value: R.Value,+context: RS.Context,+palette: U32,+noises: U32,acc: List<&2,F.Feature>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Feature>> -> T) -> T:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{provider}: descend(T,doc,value,context,palette,noises,provider <> acc,next)

@unsafe
def descend(-T: Type,doc: R.Document,value: R.Value,+context: RS.Context,+palette: U32,+noises: U32,acc: List<&2,F.Feature>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Feature>> -> T) -> T:
  match value:
    case R.Value{6,n,_,at}: children(T,n,U32.add(at,3),0,doc,context,palette,noises,acc,next)
    case R.Value{7,n,_,at}: children(T,n,U32.add(at,3),1,doc,context,palette,noises,acc,next)
    case _: next(doc,Done{acc})

@unsafe
def children(-T: Type,+n: U32,+at: U32,+skip: U32,doc: R.Document,+context: RS.Context,+palette: U32,+noises: U32,acc: List<&2,F.Feature>,next: R.Document -> Result<&1,&1,U32,List<&2,F.Feature>> -> T) -> T:
  match n:
    case 0: next(doc,Done{acc})
    case _: R.value(T,doc,U32.add(at,skip),doc => value => R.get(T,doc,U32.inc(U32.add(at,skip)),doc => span =>
      visit(T,doc,value,context,palette,noises,acc,doc => result => child(T,result,U32.sub(n,1),U32.add(U32.add(at,skip),span),skip,doc,context,palette,noises,next))))

@unsafe
def child(-T: Type,result: Result<&1,&1,U32,List<&2,F.Feature>>,+n: U32,+at: U32,+skip: U32,doc: R.Document,+context: RS.Context,+palette: U32,+noises: U32,next: R.Document -> Result<&1,&1,U32,List<&2,F.Feature>> -> T) -> T:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{acc}: children(T,n,at,skip,doc,context,palette,noises,acc,next)

@unsafe
def seeded(+id: U32,+context: U32) -> W.U64:
  W.U64{U32.xor(2147483648,U32.mul(id,7919)),Noise.hash(U32.add(U32.mul(context,811),id))}

@unsafe
def snapshot(result: F.Placement) -> Snapshot:
  match result:
    case F.Placement{state,E.World{_,_,patch},placed}: Snapshot{state,placed,patch}

@unsafe
def repeated(+first: F.Placement,+feature: F.Feature,+model: F.Model,+at: P.Position) -> List<&2,Snapshot>:
  match first:
    case F.Placement{state,world,_}: [snapshot(first),snapshot(F.place(feature,state,model,world,at))]

@unsafe
def contexts(+feature: F.Feature,+model: F.Model,+policy: P.Policy,+id: U32,+context: U32,acc: List<&2,Snapshot>) -> Samples:
  match context:
    case 16: Samples{List.reverse(&2,Snapshot,acc)}
    case _:
      +at = World.point(context)
      +world = World.world(policy,context)
      contexts(feature,model,policy,id,U32.inc(context),List.append(&2,Snapshot,List.reverse(&2,Snapshot,repeated(F.place(feature,seeded(id,context),model,world,at),feature,model,at)),acc))

@unsafe
def samples(+count: U32,+base: U32,+features: Q.Table<F.Feature>,+model: F.Model,+policy: P.Policy) -> Q.Table<Samples>:
  split(U32.is_gt(count,1),count,base,features,model,policy)

@unsafe
def split(more: Bool,+count: U32,+base: U32,+features: Q.Table<F.Feature>,+model: F.Model,+policy: P.Policy) -> Q.Table<Samples>:
  match more count:
    case _ 0: Q.Empty{}
    case False{} _: Q.Leaf{contexts(Q.get(F.Feature,features,base,F.Simple{B.State{0},[],P.Always{},False{}}),model,policy,base,0,[])}
    case True{} _:
      +half = U32.div(count,2)
      a b = samples(half,base,features,model,policy) samples(U32.sub(count,half),U32.add(base,half),features,model,policy)
      Q.Branch{count,a,b}

@unsafe
def patch_size(+patch: E.Patch) -> U32:
  match patch:
    case E.Empty{}: 0
    case E.Node{_,_,_,left,right}: U32.inc(U32.add(patch_size(left),patch_size(right)))

@unsafe
def write_patch(patch: E.Patch,writer: Binary.Writer) -> Binary.Writer:
  match patch:
    case E.Empty{}: writer
    case E.Node{P.Position{x,y,z},m,_,left,right}: write_patch(right,Binary.int(Binary.int(Binary.int(Binary.int(write_patch(left,writer),x),y),z),m))

@unsafe
def write_rows(rows: List<&2,Snapshot>,writer: Binary.Writer) -> Binary.Writer:
  match rows:
    case Nil{}: writer
    case Snapshot{state,placed,+patch} <> rest: write_rows(rest,write_patch(patch,Binary.int(Binary.int(Binary.long(writer,state),W.flag(placed)),patch_size(patch))))

@unsafe
def write(+rows: Q.Table<Samples>,+i: U32,+count: U32,writer: Binary.Writer) -> Binary.Writer:
  match count:
    case 0: writer
    case _: write_row(Q.get(Samples,rows,i,Samples{[]}),rows,U32.inc(i),U32.sub(count,1),writer)

@unsafe
def write_row(row: Samples,+rows: Q.Table<Samples>,+i: U32,+count: U32,writer: Binary.Writer) -> Binary.Writer:
  match row:
    case Samples{xs}: write(rows,i,count,write_rows(xs,writer))

@unsafe
def calculate(+features: Q.Table<F.Feature>,+model: F.Model,+policy: P.Policy,output: String) -> IO(Unit):
  do IO<Unit>:
    +count : U32 = Q.table_size(F.Feature,features)
    rows : Q.Table<Samples> = samples!(count,0,features,model,policy)
    Output.unwrapped(Binary.done(write(rows,0,count,Binary.int(Binary.new(),count))),output)

@unsafe
def features_ready(result: Result<&1,&1,U32,List<&2,F.Feature>>,+model: F.Model,+policy: P.Policy,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid surface feature")
    case Done{xs}: calculate(Q.prepared_table(F.Feature,List.reverse(&2,F.Feature,xs)),model,policy,output)

@unsafe
def current_ready(result: Result<&1,&1,U32,Q.Table<Maybe<&2,F.PlantState>>>,doc: R.Document,+context: RS.Context,+noises: Q.Table<N.Program>,+policy: P.Policy,output: String) -> IO(Unit):
  match result policy:
    case Fail{code} _: IO.die(Unit,code,"invalid surface feature")
    case Done{current} P.Policy{masks,_,_,_,_,_,_}:
      V.root(IO(Unit),doc,doc => root => V.field(IO(Unit),doc,root,"decorations",doc => found =>
        decorations(IO(Unit),found,doc,context,Q.table_size(U32,masks),Q.table_size(N.Program,noises),doc => result => features_ready(result,F.Model{noises,current},policy,output))))

@unsafe
def decorations(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+context: RS.Context,+palette: U32,+noises: U32,next: R.Document -> Result<&1,&1,U32,List<&2,F.Feature>> -> T) -> T:
  match found:
    case None{}: next(doc,Done{[]})
    case Some{x}: visit(T,doc,x,context,palette,noises,[],next)

@unsafe
def noise_ready(result: Result<&1,&1,U32,Q.Table<N.Program>>,doc: R.Document,+policy: P.Policy,output: String) -> IO(Unit):
  match result policy:
    case Fail{code} _: IO.die(Unit,code,"invalid provider noise")
    case Done{noises} P.Policy{masks,_,_,_,_,_,_}: RS.context(IO(Unit),doc,doc => +context =>
      RS.current_states(IO(Unit),doc,context,Q.table_size(U32,masks),doc => result => current_ready(result,doc,context,noises,policy,output)))

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
    case _: IO.die(Unit,2,"usage: surface-features profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
