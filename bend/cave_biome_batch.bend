import Base
import ./cave_lattice.bend as CV
import ./material_columns.bend as MC
import ./density.bend as Q
import ./binary.bend as B
import ./reader.bend as R
import ./compression.bend as Z

type Computed is Type:
  Computed{grid: CV.Grid,rows: Array<Maybe<&2,U32>>}

@unsafe
def row(grid: CV.Grid,query: MC.Query) -> Maybe<&2,U32>:
  match query:
    case MC.Query{x,y,z,low,high}: CV.biome(grid,Q.world(x,y,z),low,high)

@unsafe
def calculate(+grid: CV.Grid,queries: Array<MC.Query>) -> Array<Maybe<&2,U32>>:
  match queries:
    case ALeaf{query}: ALeaf{row(grid,query)}
    case ANode{a,b}:
      a b = calculate(grid,a) calculate(grid,b)
      ANode{a,b}

def execute(+grid: CV.Grid,queries: Array<MC.Query>) -> Computed:
  rows : Array<Maybe<&2,U32>> = calculate!(grid,queries)
  Computed{grid,rows}

@unsafe
def encode(n: Nat,rows: Array<Maybe<&2,U32>>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: encoded(p,Array.get(Maybe<&2,U32>,rows,i),w,i)

@unsafe
def encoded(n: Nat,pair: Array<Maybe<&2,U32>> & Maybe<&2,U32>,w: B.Writer,+i: U32) -> B.Writer:
  (rows,value) = pair
  encode(n,rows,output(value,w),U32.inc(i))

# A present MAX ID means inherit this column's finalized surface biome.
@unsafe
def output(value: Maybe<&2,U32>,w: B.Writer) -> B.Writer:
  match value:
    case None{}: B.int(B.int(w,0),4294967295)
    case Some{id}: B.int(B.int(w,1),id)
