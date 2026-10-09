import Base
import ./aquifer.bend as A
import ./density.bend as Q
import ./material_columns.bend as MC

type Computed is Type:
  Computed{rows: Array<Q.Outputs>}

def row(config: A.Config,model: Q.Model,programs: Q.Table<Q.Program>,query: MC.Query) -> Q.Outputs:
  match query:
    case MC.Query{x,y,z,low,high}: A.fields(config,model,programs,Q.world(x,y,z),low,high)

@unsafe
def calculate(+config: A.Config,+model: Q.Model,+programs: Q.Table<Q.Program>,queries: Array<MC.Query>) -> Array<Q.Outputs>:
  match queries:
    case ALeaf{query}: ALeaf{row(config,model,programs,query)}
    case ANode{a,b}:
      a b = calculate(config,model,programs,a) calculate(config,model,programs,b)
      ANode{a,b}

def execute(prepared: A.Prepared,model: Q.Model,queries: Array<MC.Query>) -> Computed:
  match prepared:
    case A.Prepared{config,programs}:
      rows : Array<Q.Outputs> = calculate!(config,model,programs,queries)
      Computed{rows}
