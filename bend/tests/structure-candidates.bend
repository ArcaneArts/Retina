import Base
import ../structure_plan.bend as S
import ../structure_registry.bend as M
import ../structure_candidates.bend as C
import ../structure_validate.bend as Valid
import ../registry.bend as R
import ../structure_random.bend as Random
import ../word64.bend as W
import ../binary.bend as B
import ../density.bend as Q
import ./binary-fixtures.bend as T
import ./structure-plan.bend as Fixture

@unsafe
def starts(xs: List<&2,C.Start>,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case C.Start{set,x,z} <> rest: starts(rest,B.int(B.int(B.int(w,set),x),z))

@unsafe
def region(+xs: List<&2,C.Start>,w: B.Writer) -> B.Writer:
  starts(xs,B.int(w,U32.from_nat(List.length(&2,C.Start,xs))))

@unsafe
def biome_list(+m: M.Model,+context: U32,+i: U32,n: U32) -> List<&2,U32>:
  match n:
    case 0: []
    case _: biome(U32.is_eq(U32.mod(i,3),context),m,i) <> biome_list(m,context,U32.inc(i),U32.sub(n,1))

@unsafe
def biome(accepted: Bool,+m: M.Model,i: U32) -> U32:
  match accepted:
    case False{}: 4294967295
    case True{}: M.int(M.item(M.get(m,M.item(S.array(m,2n),i),32n),0),4294967295)

@unsafe
def choice(value: C.Choice,w: B.Writer) -> B.Writer:
  match value:
    case C.Choice{state,None{}}: B.int(B.long(w,state),4294967295)
    case C.Choice{state,Some{id}}: B.int(B.long(w,state),id)

@unsafe
def candidate(at: C.Candidate,+m: M.Model,+set: U32,+seed: W.U64,+biomes: Q.Table<U32>,w: B.Writer) -> B.Writer:
  match at:
    case C.Candidate{+x,+z}: choice(C.choose_definition(m,set,seed,x,z,biomes),B.int(B.int(B.int(w,x),z),W.flag(C.permitted(m,set,seed,x,z,[]))))

@unsafe
def points(+m: M.Model,+set: U32,+context: U32,+i: U32,+biomes: Q.Table<U32>,w: B.Writer) -> B.Writer:
  match i:
    case 64: w
    case _: points(m,set,context,U32.inc(i),biomes,candidate(C.candidate(Fixture.seed(context),
      U32.add(Fixture.coordinate(context),U32.sub(U32.mul(U32.mod(i,8),37),148)),
      U32.add(U32.sub(0,Fixture.coordinate(context)),U32.sub(U32.mul(U32.div(i,8),53),212)),m,M.get(m,C.set(m,set),34n)),m,set,Fixture.seed(context),biomes,w))

@unsafe
def sets(+m: M.Model,+context: U32,+i: U32,n: U32,+biomes: Q.Table<U32>,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: sets(m,context,U32.inc(i),U32.sub(n,1),biomes,points(m,i,context,0,biomes,w))

@unsafe
def contexts(+m: M.Model,+context: U32,w: B.Writer) -> B.Writer:
  match context:
    case 3: random_rows(0,w)
    case _: contexts(m,U32.inc(context),region(C.region(m,Fixture.seed(context),Fixture.coordinate(context),U32.sub(0,Fixture.coordinate(context)),32),
      sets(m,context,0,M.count(S.array(m,4n)),Q.prepared_table(U32,biome_list(m,context,0,M.count(S.array(m,2n)))),w)))

@unsafe
def valid(ok: Bool,+m: M.Model,output: String) -> IO(Unit):
  match ok:
    case False{}: IO.die(Unit,850,"invalid structure planning schema")
    case True{}: T.unwrapped(B.done(contexts(m,0,B.int(B.new(),M.count(S.array(m,4n))))),output)

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"registry load failed")
    case Done{doc}: M.prepare(IO(Unit),doc,doc => +model => valid(Valid.model(model),model,output))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: structure-candidates profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)

@unsafe
def bounds(i: U32) -> U32:
  match i:
    case 0: 1
    case 1: 2
    case 2: 3
    case 3: 1073741825
    case 4: 2147483647
    case 5: 2147483648
    case 6: 4294967295
    case _: 37

@unsafe
def random_rows(+i: U32,w: B.Writer) -> B.Writer:
  match i:
    case 256: w
    case _: random_draw(Random.integer(Random.new(W.U64{U32.mul(i,2654435769),U32.mul(i,2135587861)}),bounds(U32.mod(i,8))),i,w)

@unsafe
def random_draw(draw: Random.Draw,+i: U32,w: B.Writer) -> B.Writer:
  match draw:
    case Random.Draw{state,v}: random_rows(U32.inc(i),B.int(B.long(w,state),v))
