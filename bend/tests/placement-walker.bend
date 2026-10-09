import Base
import ../registry.bend as R
import ../registry_placement.bend as RP
import ../placement_walk.bend as Walk
import ../placement_cursor.bend as K
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../density.bend as Q
import ../word64.bend as W
import ../noise.bend as N
import ../binary.bend as B
import ./binary-fixtures.bend as T
import ./placement-world-fixtures.bend as Old
import ./placement-walker-world.bend as World

type Record is Data:
  Record{candidate: K.Candidate,before: U32,after: U32,queue: U32,steps: U32}
type Case is Data:
  Case{context: U32,count: U32,status: U32,steps: U32,queue: U32,cache: U32,records: List<&2,Record>,probes: List<&2,U32>}

@unsafe
def ids(+n: U32,+i: U32,+reverse: Bool) -> List<&2,U32>:
  ids_acc(n,i,reverse,[])

@unsafe
def ids_acc(+n: U32,+i: U32,+reverse: Bool,acc: List<&2,U32>) -> List<&2,U32>:
  match n:
    case 0: List.reverse(&2,U32,acc)
    case _: ids_acc(U32.sub(n,1),U32.inc(i),reverse,N.word(reverse,U32.sub(n,1),i) <> acc)

@unsafe
def selection(+context: U32,+n: U32,+reverse: Bool) -> List<&2,U32>:
  match context:
    case 0: List.append(&2,U32,ids(n,0,reverse),[0,0,4294967295])
    case 32: List.append(&2,U32,ids(n,0,reverse),[0,0,4294967295])
    case _: [U32.sub(context,1),U32.sub(context,1),4294967295]

@unsafe
def cache_size(+cache: K.Cache) -> U32:
  match cache:
    case K.Missing{}: 0
    case K.Stored{_,_,_,a,b}: U32.inc(U32.add(cache_size(a),cache_size(b)))

@unsafe
def probes(+world: E.World,+origin: P.Position,+n: U32) -> List<&2,U32>:
  match origin n:
    case _ 6: []
    case P.Position{x,_,z} _: Old.optional(E.height(world,x,z,n)) <> probes(world,origin,U32.inc(n))

@unsafe
def finish(+cursor: K.Cursor,+context: U32,+status: U32,+count: U32,+records: List<&2,Record>,+world: E.World) -> Case:
  match cursor:
    case K.Cursor{queue,cache,steps}: Case{context,count,N.word(U32.is_zero(K.size(queue)),0,status),steps,K.size(queue),cache_size(cache),List.reverse(&2,Record,records),probes(world,World.origin(context),0)}

# Test-only limits report exhaustion, a 64-candidate prefix, or an 8192-task
# prefix explicitly. Walk.next has no candidate cap and retains its cursor.
@unsafe
def drive(+cursor: K.Cursor,+model: P.Model,+world: E.World,+context: U32,+count: U32,+records: List<&2,Record>,+fuel: U32) -> Case:
  match cursor:
    case K.Cursor{_,_,+steps}: drive_limit(Bool.or(U32.is_ge(count,64),U32.is_ge(steps,8192)),cursor,model,world,context,count,records,fuel)

@unsafe
def drive_limit(+stop: Bool,+cursor: K.Cursor,+model: P.Model,+world: E.World,+context: U32,+count: U32,+records: List<&2,Record>,+fuel: U32) -> Case:
  match stop cursor:
    case True{} _: finish(cursor,context,N.word(U32.is_ge(count,64),1,2),count,records,world)
    case False{} K.Cursor{_,_,steps}: outcome(Walk.next(cursor,model,world,U32.min(fuel,U32.sub(8192,steps))),model,world,context,count,records,fuel)

@unsafe
def outcome(result: K.Outcome,+model: P.Model,+world: E.World,+context: U32,+count: U32,+records: List<&2,Record>,+fuel: U32) -> Case:
  match result:
    case K.Exhausted{cursor}: finish(cursor,context,0,count,records,world)
    case K.Paused{cursor}: drive(cursor,model,world,context,count,records,fuel)
    case K.Yield{+cursor,+candidate}: yielded(cursor,candidate,model,world,World.edit(world,candidate_at(candidate),context),context,count,records,fuel)

def candidate_at(+candidate: K.Candidate) -> P.Position:
  match candidate:
    case K.Candidate{_,_,at,_,_}: at

@unsafe
def queried(+world: E.World,+at: P.Position) -> U32:
  match at:
    case P.Position{x,_,z}: Old.optional(E.height(world,x,z,4))

@unsafe
def yielded(+cursor: K.Cursor,+candidate: K.Candidate,+model: P.Model,+before: E.World,+after: E.World,+context: U32,+count: U32,+records: List<&2,Record>,+fuel: U32) -> Case:
  match cursor:
    case K.Cursor{queue,_,steps}: drive(cursor,model,after,context,U32.inc(count),Record{candidate,queried(before,candidate_at(candidate)),queried(after,candidate_at(candidate)),K.size(queue),steps} <> records,fuel)

@unsafe
def run(+context: U32,+model: P.Model,+reverse: Bool,+fuel: U32) -> Case:
  match model:
    case P.Model{recipes,_,+policy}:
      +world = World.world(policy,context)
      +cursor = Walk.new(model,selection(context,Q.table_size(P.Recipe,recipes),reverse),World.origin(context),W.U64{U32.xor(2147483648,U32.mul(context,7919)),N.hash(U32.mul(context,811))})
      outcome(Walk.next(cursor,model,world,0),model,world,context,0,[],fuel)

@unsafe
def rows(+n: U32,+base: U32,+total: U32,+model: P.Model,+reverse: Bool,+fuel: U32) -> Q.Table<Case>:
  split(U32.is_gt(n,1),n,base,total,model,reverse,fuel)

@unsafe
def split(+more: Bool,+n: U32,+base: U32,+total: U32,+model: P.Model,+reverse: Bool,+fuel: U32) -> Q.Table<Case>:
  match more:
    case False{}: Q.Leaf{run(N.word(reverse,U32.sub(U32.sub(total,1),base),base),model,reverse,fuel)}
    case True{}:
      +half = U32.div(n,2)
      a b = rows(half,base,total,model,reverse,fuel) rows(U32.sub(n,half),U32.add(base,half),total,model,reverse,fuel)
      Q.Branch{n,a,b}

@unsafe
def words(xs: List<&2,U32>,writer: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: writer
    case x <> rest: words(rest,B.int(writer,x))

@unsafe
def record(+record: Record,writer: B.Writer) -> B.Writer:
  match record:
    case Record{K.Candidate{r,g,P.Position{x,y,z},state,+path},before,after,queue,steps}:
      B.int(B.int(B.int(B.int(words(path,B.int(B.long(B.int(B.int(B.int(B.int(B.int(writer,r),g),x),y),z),state),U32.from_nat(List.length(&2,U32,path)))),before),after),queue),steps)

@unsafe
def records(xs: List<&2,Record>,writer: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: writer
    case row <> rest: records(rest,record(row,writer))

@unsafe
def write_case(row: Case,writer: B.Writer) -> B.Writer:
  match row:
    case Case{context,count,status,steps,queue,cache,rs,ps}: words(ps,records(rs,B.int(B.int(B.int(B.int(B.int(B.int(writer,context),count),status),steps),queue),cache)))

@unsafe
def write(+n: U32,+i: U32,+total: U32,+reverse: Bool,+rows: Q.Table<Case>,writer: B.Writer) -> B.Writer:
  match n:
    case 0: writer
    case _: write(U32.sub(n,1),U32.inc(i),total,reverse,rows,write_case(Q.get(Case,rows,N.word(reverse,U32.sub(U32.sub(total,1),i),i),Case{0,0,0,0,0,0,[],[]}),writer))

@unsafe
def calculate(+model: P.Model,+reverse: Bool,+fuel: U32,output: String) -> IO(Unit):
  match model:
    case P.Model{recipes,_,_}:
      do IO<Unit>:
        +total : U32 = 33
        _ : Nat <- IO.now()
        result : Q.Table<Case> = rows!(total,0,total,model,reverse,fuel)
        T.unwrapped(B.done(write(total,0,total,reverse,result,B.int(B.new(),total))),output)

@unsafe
def prepared(result: Result<&1,&1,U32,P.Model>,output: String,+reverse: Bool,+fuel: U32) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid placement profile")
    case Done{model}: calculate(model,reverse,fuel,output)

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String,+reverse: Bool,+fuel: U32) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid registry transport")
    case Done{doc}: RP.prepare(IO(Unit),doc,doc => result => prepared(result,output,reverse,fuel))

@unsafe
def read_fuel(value: Maybe<&2,U32>,input: String,output: String,+reverse: Bool) -> IO(Unit):
  match value:
    case None{}: IO.die(Unit,2,"invalid fuel")
    case Some{+fuel}: start(Bool.and(U32.is_ge(fuel,1),U32.is_le(fuel,256)),fuel,input,output,reverse)

@unsafe
def start(+ok: Bool,+fuel: U32,input: String,output: String,+reverse: Bool) -> IO(Unit):
  match ok:
    case False{}: IO.die(Unit,2,"fuel must be 1..256")
    case True{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output,reverse,fuel)

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> fuel <> Nil{}: read_fuel(U32.read(fuel),input,output,False{})
    case _ <> input <> output <> fuel <> _ <> Nil{}: read_fuel(U32.read(fuel),input,output,True{})
    case _: IO.die(Unit,2,"usage: placement-walker profile.rbp output fuel [reverse]")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
