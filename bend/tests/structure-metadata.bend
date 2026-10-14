import Base
import ../structure_metadata.bend as S
import ../structure_plan.bend as Plan
import ../structure_registry.bend as M
import ../structure_values.bend as V
import ../structure_blocks.bend as Bk
import ../structure_geometry.bend as G
import ../registry.bend as R
import ../registry_values.bend as RV
import ../registry_density.bend as D
import ../binary.bend as B
import ../compression.bend as Z
import ../chunk.bend as C
import ../material.bend as Material
import ../generated_chunk.bend as GC
import ../material_columns_data.bend as MD
import ../chunk_catalog.bend as CC
import ../density.bend as Q
import ../terrain_surface.bend as Surface
import ../placement_data.bend as P
import ../word64.bend as W
import ./binary-fixtures.bend as Output

@unsafe
def box(+row: M.Node) -> G.Box:
  G.Box{P.Position{M.int(M.item(row,0),0),M.int(M.item(row,1),0),M.int(M.item(row,2),0)},P.Position{M.int(M.item(row,3),0),M.int(M.item(row,4),0),M.int(M.item(row,5),0)}}

@unsafe
def pieces(+model: M.Model,+xs: M.Node,+i: U32,n: U32) -> List<&2,Plan.Piece>:
  match n:
    case 0: []
    case _: piece(model,M.item(xs,i)) <> pieces(model,xs,U32.inc(i),U32.sub(n,1))

@unsafe
def piece(+model: M.Model,+row: M.Node) -> Plan.Piece:
  Plan.Piece{V.object([M.Field{M.key(model,24n),V.array([V.object([M.Field{M.key(model,25n),M.item(row,0)}])])}]),P.Position{0,0,0},0,box(M.item(row,1)),0,0,M.int(M.item(row,2),0),0}

@unsafe
def starts(+model: M.Model,+xs: M.Node,+i: U32,n: U32) -> List<&2,S.Start>:
  match n:
    case 0: []
    case _: start(model,M.item(xs,i)) <> starts(model,xs,U32.inc(i),U32.sub(n,1))

@unsafe
def start(+model: M.Model,+row: M.Node) -> S.Start:
  S.Start{M.int(M.item(row,0),0),M.int(M.item(row,1),0),M.int(M.item(row,2),0),W.U64{0,0},pieces(model,M.item(row,3),0,M.count(M.item(row,3))),None{}}

@unsafe
def writes(+xs: M.Node,+i: U32,n: U32) -> List<&2,Bk.TagWrite>:
  match n:
    case 0: []
    case _: Bk.TagWrite{G.position(M.item(M.item(xs,i),0)),M.item(M.item(xs,i),1)} <> writes(xs,U32.inc(i),U32.sub(n,1))

@unsafe
def context(+row: M.Node,model: M.Model,keys: D.Keys) -> S.Context:
  S.Context{model,keys,M.int(M.item(row,0),0),M.int(M.item(row,1),0),G.Box{P.Position{U32.mul(M.int(M.item(row,0),0),16),4294967232,U32.mul(M.int(M.item(row,1),0),16)},P.Position{U32.add(U32.mul(M.int(M.item(row,0),0),16),15),319,U32.add(U32.mul(M.int(M.item(row,1),0),16),15)}}}

@unsafe
def root(+row: M.Node,+model: M.Model,keys: D.Keys) -> M.Node:
  +ctx = context(row,model,keys)
  S.root(ctx,S.collect(starts(model,M.item(row,2),0,M.count(M.item(row,2))),ctx,S.Records{V.compound(),V.compound()}),S.items(M.item(row,4),0,M.count(M.item(row,4))),S.updates(writes(M.item(row,3),0,M.count(M.item(row,3))),[]))

@unsafe
def columns(n: U32) -> List<&2,MD.Column>:
  match n:
    case 0: []
    case _: MD.Column{16,0,0.0,0.0,0.0,0.0,0.0,[MD.Run{0,16,1}],1,True{}} <> columns(U32.sub(n,1))

@unsafe
def chunk(+row: M.Node,meta: Result<&1,&1,U32,Z.Bytes>) -> Result<&1,&1,U32,Z.Bytes>:
  GC.encode_metadata(MD.Grid{Surface.Tile{U32.mul(M.int(M.item(row,0),0),16),U32.mul(M.int(M.item(row,1),0),16),16,16,0,0},0,16,Q.prepared_table(MD.Column,columns(256)),None{}},
    CC.Catalog{Q.prepared_table(Material.Material,[Material.Material{Material.State{"minecraft:air",[]},0},Material.Material{Material.State{"minecraft:stone",[]},63}]),Q.Leaf{"minecraft:plains"}},
    GC.Request{M.int(M.item(row,0),0),M.int(M.item(row,1),0),0,0,5023},C.EncodedMetadata{meta})

@unsafe
def copied(a: Array<U32>,+i: U32,n: U32,w: B.Writer) -> B.Writer:
  match n:
    case 0: w
    case _: Z.read(B.Writer,a,i,a => value => copied(a,U32.inc(i),U32.sub(n,1),B.byte(w,value)))

def packet(value: Result<&1,&1,U32,Z.Bytes>,w: B.Writer) -> B.Writer:
  match value:
    case Fail{code}: B.fail(w,code)
    case Done{Z.Bytes{a,+size}}: copied(a,0,size,B.int(w,size))

@unsafe
def rows(doc: R.Document,+model: M.Model,+keys: D.Keys,+xs: M.Node,+i: U32,n: U32,w: B.Writer,output: String) -> IO(Unit):
  match n:
    case 0: Output.unwrapped(B.done(w),output)
    case _: S.encode(IO(Unit),doc,root(M.item(xs,i),model,keys),doc => encoded => rows(doc,model,keys,xs,U32.inc(i),U32.sub(n,1),packet(chunk(M.item(xs,i),encoded),w),output))

@unsafe
def selected(found: Maybe<&2,R.Value>,doc: R.Document,+model: M.Model,+keys: D.Keys,output: String) -> IO(Unit):
  match found:
    case None{}: IO.die(Unit,852,"missing metadata cases")
    case Some{value}: V.project(IO(Unit),doc,value,doc => +xs => rows(doc,model,keys,xs,0,M.count(xs),B.int(B.new(),M.count(xs)),output))

@unsafe
def loaded(result: Result<&1,&1,U32,R.Document>,output: String) -> IO(Unit):
  match result:
    case Fail{code}: IO.die(Unit,code,"registry load failed")
    case Done{doc}: checked(doc,output)

@unsafe
def rejected(value: Result<&1,&1,U32,Z.Bytes>,expected: U32) -> IO(Unit):
  match value:
    case Done{_}: Output.expected(False{})
    case Fail{code}: Output.expected(U32.is_eq(code,expected))

@unsafe
def checked(doc: R.Document,output: String) -> IO(Unit):
  do IO<Unit>:
    _ : Unit <- rejected(chunk(V.array([V.integer(0),V.integer(0)]),Fail{864}),864)
    _ : Unit <- rejected(chunk(V.array([V.integer(0),V.integer(0)]),Done{Z.Bytes{[0:U32*1n],2}}),407)
    M.prepare(IO(Unit),doc,doc => model => S.keys(IO(Unit),doc,doc => keys => RV.root(IO(Unit),doc,doc => root => RV.field(IO(Unit),doc,root,"metadata_cases",doc => found => selected(found,doc,model,keys,output)))))

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,R.Document> <- R.load(input)
        loaded(result,output)
    case _: IO.die(Unit,2,"usage: structure-metadata profile.rbp output")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
