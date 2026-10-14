import Base
import ../structure_plan.bend as S
import ../structure_registry.bend as M
import ../registry.bend as R
import ../structure_validate.bend as Valid
import ../registry_values.bend as V
import ../structure_random.bend as Random
import ../word64.bend as W
import ../binary.bend as B
import ../density.bend as Q
import ../placement_data.bend as P
import ../structure_geometry.bend as G
import ./binary-fixtures.bend as T

@unsafe
def position(+p: P.Position,w: B.Writer) -> B.Writer:
  match p:
    case P.Position{x,y,z}: B.int(B.int(B.int(w,x),y),z)

@unsafe
def box(+b: G.Box,w: B.Writer) -> B.Writer:
  match b:
    case G.Box{a,b}: position(b,position(a,w))

@unsafe
def pieces(+xs: List<&2,S.Piece>,w: B.Writer) -> B.Writer:
  match xs:
    case Nil{}: w
    case S.Piece{element,pos,r,b,palette,context,depth,priority} <> rest: pieces(rest,
      B.int(B.int(B.int(B.int(box(b,B.int(position(pos,B.int(w,M.at(element))),r)),palette),context),depth),priority))

@unsafe
def finished(+layout: S.Layout,w: B.Writer) -> B.Writer:
  match layout:
    case S.Layout{state,+xs,_}: pieces(xs,B.int(B.long(w,state),U32.from_nat(List.length(&2,S.Piece,xs))))

@unsafe
def heights(+kind: U32,+i: U32,+n: U32) -> List<&2,U32>:
  match n:
    case 0: []
    case _: ground(kind,U32.mod(i,512),U32.div(i,512)) <> heights(kind,U32.inc(i),U32.sub(n,1))

@unsafe
def ground(+kind: U32,+x: U32,+z: U32) -> U32:
  match kind:
    case 0: 64
    case 1: U32.add(40,U32.add(U32.div(x,16),U32.div(z,24)))
    case _: U32.add(50,U32.mod(U32.add(U32.div(x,8),U32.div(z,8)),11))

@unsafe
def seed(+context: U32) -> W.U64:
  match context:
    case 0: W.U64{0,123456789}
    case 1: W.U64{4294967295,4294967295}
    case _: W.U64{2147483648,4294967295}

@unsafe
def coordinate(+context: U32) -> U32:
  match context:
    case 0: 0
    case 1: 4294967281
    case _: 1874000

@unsafe
def height_field(+m: M.Model,+d: M.Node,+h: S.Heights) -> Maybe<&2,S.Heights>:
  projected(Bool.or(Bool.not(M.named(m,M.get(m,d,31n),49n)),U32.is_ne(M.tag(M.get(m,M.get(m,d,33n),58n)),0)),h)

@unsafe
def projected(+enabled: Bool,+h: S.Heights) -> Maybe<&2,S.Heights>:
  match enabled:
    case True{}: Some{h}
    case False{}: None{}

@unsafe
def rows(+m: M.Model,+n: U32,+i: U32,+context: U32,+h: S.Heights,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: rows(m,U32.sub(n,1),U32.inc(i),context,h,finished(S.begin(m,M.item(S.array(m,2n),i),Random.new(W.add(seed(context),W.U64{0,i})),
      coordinate(context),U32.sub(0,coordinate(context)),height_field(m,M.item(S.array(m,2n),i),h),4294967232,384),B.int(B.int(w,context),i)))

@unsafe
def contexts(+m: M.Model,+n: U32,w: B.Writer) -> B.Writer:
  match n:
    case 3: w
    case _: contexts(m,U32.inc(n),rows(m,M.count(S.array(m,2n)),0,n,S.Heights{U32.sub(U32.mul(coordinate(n),16),256),U32.sub(U32.mul(U32.sub(0,coordinate(n)),16),256),512,Q.prepared_table(U32,heights(n,0,262144))},w))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"registry load failed")
    case Done{doc}: M.prepare(IO(Unit),doc,doc => model => run(model,output))

@unsafe
def run(+model: M.Model,output: String) -> IO(Unit):
  validated(Valid.model(model),model,output)

@unsafe
def validated(ok: Bool,+model: M.Model,output: String) -> IO(Unit):
  match ok:
    case False{}: IO.die(Unit,850,"invalid structure planning schema")
    case True{}: T.unwrapped(B.done(contexts(model,0,B.int(B.new(),U32.mul(3,M.count(S.array(model,2n)))))),output)

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: structure-plan profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
