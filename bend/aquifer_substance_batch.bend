import Base
import ./aquifer.bend as A
import ./aquifer_substance.bend as S
import ./cave_lattice.bend as CV
import ./density.bend as Q
import ./material_columns.bend as MC
import ./binary.bend as B

type Row is Data:
  Row{present: U32,substance: U32}
type Computed is Type:
  Computed{rows: Array<Row>}

@unsafe
def row(+cache: A.Cache,caves: CV.Grid,query: MC.Query) -> Row:
  match query:
    case MC.Query{+x,+y,+z,+low,+high}: selected(S.covered(cache,Q.world(x,y,z),low,high),cache,CV.lookup(caves,Q.world(x,y,z),low,high),Q.world(x,y,z))

@unsafe
def selected(covered: Bool,cache: A.Cache,value: Maybe<&2,Q.Vec4>,point: Q.Point) -> Row:
  match covered value:
    case True{} Some{Q.Vec4{density,_,_,_}}: Row{1,S.substance(point,cache,density)}
    case _ _: Row{0,0}

@unsafe
def calculate(+cache: A.Cache,+caves: CV.Grid,queries: Array<MC.Query>) -> Array<Row>:
  match queries:
    case ALeaf{query}: ALeaf{row(cache,caves,query)}
    case ANode{+a,+b}:
      a b = calculate(cache,caves,a) calculate(cache,caves,b)
      ANode{a,b}

def execute(cache: A.Cache,caves: CV.Grid,queries: Array<MC.Query>) -> Computed:
  rows : Array<Row> = calculate!(cache,caves,queries)
  Computed{rows}

@unsafe
def encode(n: Nat,rows: Array<Row>,writer: B.Writer,+index: U32) -> B.Writer:
  match n:
    case 0n: writer
    case 1n+p: encoded(p,Array.get(Row,rows,index),writer,index)

@unsafe
def encoded(n: Nat,pair: Array<Row> & Row,writer: B.Writer,index: U32) -> B.Writer:
  (rows,row) = pair
  encode_row(n,rows,row,writer,index)

@unsafe
def encode_row(n: Nat,rows: Array<Row>,row: Row,writer: B.Writer,index: U32) -> B.Writer:
  match row:
    case Row{present,substance}: encode(n,rows,B.int(B.int(writer,present),substance),U32.inc(index))
