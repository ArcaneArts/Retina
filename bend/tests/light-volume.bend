import Base
import ../light_volume.bend as L
import ../density.bend as Q
import ../material_columns_data.bend as MD
import ../registry.bend as P
import ../registry_values.bend as V
import ../registry_cave_features.bend as F
import ../binary.bend as B
import ./binary-fixtures.bend as T

@unsafe
def states(n: U32,+at: U32,+data: Q.Table<U32>,acc: List<&2,L.Optics>) -> Q.Table<L.Optics>:
  match n:
    case 0: Q.prepared_table(L.Optics,List.reverse(&2,L.Optics,acc))
    case _: states(U32.sub(n,1),U32.add(at,8),data,L.Optics{Q.get(U32,data,at,0),Q.get(U32,data,U32.add(at,1),0),Q.get(U32,data,U32.add(at,2),0),
      Q.get(U32,data,U32.add(at,3),0),Q.get(U32,data,U32.add(at,4),0),Q.get(U32,data,U32.add(at,5),0),Q.get(U32,data,U32.add(at,6),0)} <> acc)

@unsafe
def runs(+n: U32,+column: U32,+plane: U32,+blocks: Q.Table<U32>,acc: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match n:
    case 0: acc
    case _: runs(U32.sub(n,1),column,plane,blocks,MD.Run{U32.sub(n,1),n,Q.get(U32,blocks,U32.add(column,U32.mul(U32.sub(n,1),plane)),0)} <> acc)

@unsafe
def columns(+n: U32,+base: U32,+height: U32,+plane: U32,+blocks: Q.Table<U32>,+model: L.Model) -> Q.Table<L.Column>:
  match n:
    case 1: Q.Leaf{L.column(runs(height,base,plane,blocks,[]),model)}
    case _:
      a b = columns(U32.div(n,2),base,height,plane,blocks,model) columns(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),height,plane,blocks,model)
      Q.Branch{n,a,b}

@unsafe
def encode(n: U32,+i: U32,+width: U32,+depth: U32,+grid: L.Grid,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: encode(U32.sub(n,1),U32.inc(i),width,depth,grid,B.byte(w,L.at(grid,U32.mod(i,width),U32.div(i,U32.mul(width,depth)),U32.mod(U32.div(i,width),depth))))

def execute(+width: U32,+height: U32,+depth: U32,blocks: Q.Table<U32>,+model: L.Model,output: String) -> IO(Unit):
  do IO<Unit>:
    source : L.Source = L.Source{width,U32.add(height,32),depth,columns(U32.mul(width,depth),0,height,U32.mul(width,depth),blocks,model)}
    lit : L.Grid <- L.run(source,model)
    T.unwrapped(B.done(encode(U32.mul(U32.mul(width,depth),U32.add(height,32)),0,width,depth,lit,B.new())),output)

def parsed(params: Result<&1,&1,U32,Q.Table<U32>>,ss: Result<&1,&1,U32,Q.Table<U32>>,blocked: Result<&1,&1,U32,Q.Table<U32>>,blocks: Result<&1,&1,U32,Q.Table<U32>>,output: String) -> IO(Unit):
  match params ss blocked blocks:
    case Done{+p} Done{+ss} Done{blocked} Done{blocks}:
      execute(Q.get(U32,p,0,0),Q.get(U32,p,1,0),Q.get(U32,p,2,0),blocks,
        L.Model{U32.is_ne(Q.get(U32,p,3,0),0),Q.get(U32,p,4,0),states(U32.div(Q.table_size(U32,ss),8),0,ss,[]),blocked},output)
    case _ _ _ _: IO.die(Unit,2,"invalid lighting fixture")

def loaded(result: Result<&1,&1,U32,P.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"invalid lighting fixture transport")
    case Done{doc}: V.root(IO(Unit),doc,doc => +root =>
      V.field(IO(Unit),doc,root,"params",doc => found => F.array(IO(Unit),found,doc,0,doc => params =>
      V.field(IO(Unit),doc,root,"states",doc => found => F.array(IO(Unit),found,doc,0,doc => states =>
      V.field(IO(Unit),doc,root,"blocked",doc => found => F.array(IO(Unit),found,doc,0,doc => blocked =>
      V.field(IO(Unit),doc,root,"blocks",doc => found => F.array(IO(Unit),found,doc,0,doc => blocks =>
        parsed(params,states,blocked,blocks,output))))))))))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,P.Document> <- P.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: light-volume input.rbp output.bin")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
