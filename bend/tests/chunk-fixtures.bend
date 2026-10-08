import Base
import ../binary.bend as B
import ../word64.bend as W
import ../compression.bend as Z
import ../nbt.bend as N
import ../chunk.bend as C
import ../material.bend as M
import ../palette.bend as A
import ../region.bend as R
import ./compress-file.bend as F
import ./binary-fixtures.bend as T
import ./format-fixtures.bend as G

def materials() -> C.Profile:
  a = Array.new(M.Material,3n,M.Material{M.State{"minecraft:air",[]},0})
  a = Array.set(M.Material,a,1,M.Material{M.State{"minecraft:stone",[]},63})
  a = Array.set(M.Material,a,2,M.Material{M.State{"minecraft:dirt",[]},63})
  a = Array.set(M.Material,a,3,M.Material{M.State{"minecraft:grass_block",[M.Property{"snowy","false"}]},63})
  a = Array.set(M.Material,a,4,M.Material{M.State{"minecraft:water",[M.Property{"level","0"}]},51})
  a = Array.set(M.Material,a,5,M.Material{M.State{"minecraft:oak_leaves",[
    M.Property{"distance","1"},M.Property{"persistent","false"},M.Property{"waterlogged","false"}]},31})
  a = Array.set(M.Material,a,6,M.Material{M.State{"minecraft:oak_log",[M.Property{"axis","x"}]},63})
  a = Array.set(M.Material,a,7,M.Material{M.State{"minecraft:chest",[
    M.Property{"facing","east"},M.Property{"type","single"},M.Property{"waterlogged","false"}]},63})
  b = Array.set(String,Array.set(String,Array.new(String,2n,"minecraft:plains"),1,"minecraft:forest"),2,"minecraft:lush_caves")
  C.Profile{a,8,b,3}

def block(+layer: U32, +column: U32) -> U32:
  +h = U32.add(20,U32.mod(column,11))
  Z.word(Bool.and(U32.is_eq(layer,383),U32.is_eq(column,255)),6,
    Z.word(Bool.and(U32.is_eq(layer,30),U32.is_eq(column,209)),7,
    Z.word(Bool.and(U32.is_eq(layer,U32.add(h,5)),U32.is_zero(U32.mod(column,7))),5,
    Z.word(Bool.and(U32.is_eq(layer,U32.add(h,3)),U32.is_zero(U32.mod(column,13))),6,
    Z.word(U32.is_lt(layer,U32.sub(h,3)),1,
    Z.word(U32.is_lt(layer,h),2,Z.word(U32.is_eq(layer,h),3,Z.word(U32.is_lt(layer,27),4,0))))))))

def blocks(+n: Nat, a: Array<U32>, +section: U32) -> Array<U32>:
  match n:
    case 0n: a
    case 1n+p:
      +i = U32.from_nat(p)
      blocks(p,Array.set(U32,a,i,block(U32.add(U32.mul(section,16),U32.div(i,256)),U32.mod(i,256))),section)

def biomes(+n: Nat, a: Array<U32>, +section: U32) -> Array<U32>:
  match n:
    case 0n: a
    case 1n+p:
      +i = U32.from_nat(p)
      biomes(p,Array.set(U32,a,i,Z.word(U32.is_zero(U32.mod(section,3)),0,U32.mod(U32.add(i,section),3))),section)

def sections(+n: Nat, acc: List<C.Section>) -> List<C.Section>:
  match n:
    case 0n: acc
    case 1n+p:
      +s = U32.from_nat(p)
      sections(p,C.Section{blocks(4096n,[0 : U32*4096n],s),biomes(64n,[0 : U32*64n],s),None{}} <> acc)

def empty_metadata() -> C.Metadata:
  C.Metadata{[],[],[],[]}

def metadata() -> C.Metadata:
  C.Metadata{[],[N.Field{"minecraft:village_plains",N.LongArrayTag{Array.new(W.U64,0n,W.U64{4294967295,4294967263}),1}}],[],[
    N.CompoundTag{[N.Field{"id",N.StringTag{"minecraft:chest"}},N.Field{"x",N.IntTag{4294966769}},
      N.Field{"y",N.IntTag{4294967262}},N.Field{"z",N.IntTag{1021}},
      N.Field{"LootTable",N.StringTag{"minecraft:chests/village/village_plains_house"}},
      N.Field{"LootTableSeed",N.LongTag{W.U64{2147483648,1}}}]}
  ]}

def light() -> C.Light:
  C.Light{Z.Bytes{[0 : U32*2048n],2048},Z.Bytes{[255 : U32*2048n],2048}}

def lit_sections(+n: Nat, acc: List<C.Section>) -> List<C.Section>:
  match n:
    case 0n: acc
    case 1n+p: lit_sections(p,C.Section{[0 : U32*4096n],[0 : U32*64n],Some{light()}} <> acc)

def fixture(version: U32) -> Result<&1, &1, U32, Z.Bytes>:
  C.encode(C.Chunk{version,4294967263,63,4294967292,24,sections(24n,[]),None{},metadata()},materials())

def lit_fixture(version: U32) -> Result<&1, &1, U32, Z.Bytes>:
  C.encode(C.Chunk{version,4294967262,63,4294967292,24,lit_sections(24n,[]),
    Some{C.Boundary{light(),light()}},empty_metadata()},materials())

def palette_values(+n: Nat, a: Array<U32>, +distinct: U32) -> Array<U32>:
  match n:
    case 0n: a
    case 1n+p:
      +i = U32.from_nat(p)
      palette_values(p,Array.set(U32,a,i,U32.sub(65535,U32.mul(U32.mod(U32.mul(i,13),distinct),16))),distinct)

def palette_result(result: Result<&1, &1, U32, A.Palette>, w: B.Writer, distinct: U32) -> B.Writer:
  match result:
    case Fail{code}: B.fail(w,code)
    case Done{A.Palette{globals,values,+size,+entries}}:
      w = B.int(B.int(B.int(B.int(w,distinct),entries),size),A.width(size))
      w = N.ints(U32.is_gt(size,0),globals,w,0,size,False{})
      N.ints(U32.is_gt(entries,0),values,w,0,entries,False{})

def palette_cases(xs: List<U32>, w: B.Writer, +count: U32) -> B.Writer:
  match xs:
    case Nil{}: w
    case +distinct <> rest:
      palette_cases(rest,palette_result(A.build(palette_values(U32.to_nat(count),[0 : U32*4096n],distinct),count,65536),w,distinct),count)

def rejected(result: Result<&1, &1, U32, Z.Bytes>, code: U32) -> IO(Unit):
  match result:
    case Done{_}: T.expected(False{})
    case Fail{actual}: T.expected(U32.is_eq(actual,code))

def rejected_palette(result: Result<&1, &1, U32, A.Palette>, code: U32) -> IO(Unit):
  match result:
    case Done{_}: T.expected(False{})
    case Fail{actual}: T.expected(U32.is_eq(actual,code))

def one(value: C.Section, +min: U32, total: U32, boundary: Maybe<&1, C.Boundary>, profile: C.Profile)
    -> Result<&1, &1, U32, Z.Bytes>:
  C.encode(C.Chunk{1,0,0,min,total,[value],boundary,empty_metadata()},profile)

def empty_section() -> C.Section:
  C.Section{[0 : U32*4096n],[0 : U32*64n],None{}}

def invalid(+output: String) -> IO(Unit):
  do IO<Unit>:
    _ : Unit <- rejected_palette(A.build([0 : U32*1n],0,1),201)
    _ : Unit <- rejected_palette(A.build([0 : U32*4096n],4097,1),201)
    _ : Unit <- rejected_palette(A.build([0 : U32*1n],1,0),201)
    _ : Unit <- rejected_palette(A.build([0 : U32*1n],1,65537),201)
    _ : Unit <- rejected_palette(A.build([0 : U32*1n],2,1),202)
    _ : Unit <- rejected_palette(A.build([1 : U32*1n],1,1),203)
    _ : Unit <- rejected_palette(A.build([65536 : U32*1n],1,65536),203)
    _ : Unit <- rejected(one(empty_section(),4294967167,1,None{},materials()),401)
    _ : Unit <- rejected(one(empty_section(),128,1,None{},materials()),401)
    _ : Unit <- rejected(one(empty_section(),127,2,None{},materials()),401)
    _ : Unit <- rejected(one(empty_section(),0,0,None{},materials()),401)
    _ : Unit <- rejected(one(empty_section(),0,2,None{},materials()),402)
    _ : Unit <- rejected(C.encode(C.Chunk{1,0,0,0,1,[],None{},empty_metadata()},materials()),402)
    _ : Unit <- rejected(one(C.Section{[0 : U32*2048n],[0 : U32*64n],None{}},0,1,None{},materials()),403)
    _ : Unit <- rejected(one(C.Section{[0 : U32*4096n],[0 : U32*32n],None{}},0,1,None{},materials()),403)
    _ : Unit <- rejected(one(C.Section{Array.set(U32,[0 : U32*4096n],4095,8),[0 : U32*64n],None{}},0,1,None{},materials()),302)
    _ : Unit <- rejected(one(C.Section{[0 : U32*4096n],Array.set(U32,[0 : U32*64n],63,3),None{}},0,1,None{},materials()),203)
    _ : Unit <- rejected(one(C.Section{[0 : U32*4096n],[0 : U32*64n],Some{light()}},0,1,None{},materials()),404)
    _ : Unit <- rejected(one(empty_section(),0,1,Some{C.Boundary{light(),light()}},materials()),404)
    _ : Unit <- rejected(one(empty_section(),4294967168,1,Some{C.Boundary{light(),light()}},materials()),401)
    _ : Unit <- rejected(one(empty_section(),127,1,Some{C.Boundary{light(),light()}},materials()),401)
    _ : Unit <- rejected(one(C.Section{[0 : U32*4096n],[0 : U32*64n],Some{C.Light{
      Z.Bytes{[0 : U32*2048n],2047},Z.Bytes{[0 : U32*2048n],2048}}}},0,1,Some{C.Boundary{light(),light()}},materials()),405)
    _ : Unit <- rejected(one(C.Section{[0 : U32*4096n],[0 : U32*64n],Some{C.Light{
      Z.Bytes{[0 : U32*1n],2048},Z.Bytes{[0 : U32*2048n],2048}}}},0,1,Some{C.Boundary{light(),light()}},materials()),405)
    _ : Unit <- rejected(one(C.Section{[0 : U32*4096n],[0 : U32*64n],Some{C.Light{
      Z.Bytes{Array.set(U32,[0 : U32*2048n],2047,256),2048},Z.Bytes{[0 : U32*2048n],2048}}}},
      0,1,Some{C.Boundary{light(),light()}},materials()),405)
    _ : Unit <- rejected(one(empty_section(),0,1,None{},C.Profile{
      Array.new(M.Material,0n,M.Material{M.State{"minecraft:air",[]},0}),2,["minecraft:plains" : String*1n],1}),406)
    _ : Unit <- rejected(one(empty_section(),0,1,None{},C.Profile{
      Array.new(M.Material,0n,M.Material{M.State{"minecraft:air",[]},64}),1,["minecraft:plains" : String*1n],1}),303)
    _ : Unit <- rejected(C.encode(C.Chunk{1,0,0,0,1,[empty_section()],None{},
      C.Metadata{[],[],[N.IntTag{1}],[]}},materials()),3)
    # Extreme valid section coordinates must remain signed byte/int values.
    _ : Unit <- T.unwrapped(one(empty_section(),4294967168,1,None{},materials()),output ++ ".min.nbt")
    T.unwrapped(one(empty_section(),127,1,None{},materials()),output ++ ".max.nbt")

def run(+output: String, +version: U32) -> IO(Unit):
  do IO<Unit>:
    _ : Unit <- T.unwrapped(fixture(version),output ++ ".nbt")
    _ : Unit <- G.save_plan(R.plan([
      R.Record{1023,1700000000,G.compressed(fixture(version))},
      R.Record{1022,1700000001,G.compressed(lit_fixture(version))}]),output ++ ".mca")
    _ : Unit <- T.unwrapped(B.done(palette_cases([1,2,3,16,17,32,33,64],
      palette_cases([1,2,3,16,17,32,33,64,127,256,257,4096],B.new(),4096),64)),output ++ ".palettes")
    _ : Unit <- invalid(output)
    IO.print("PASS: chunk fixtures")

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> output <> version <> Nil{}:
      do IO<Unit>:
        n : U32 <- F.number(U32.read(version))
        run(output,n)
    case _: IO.die(Unit,2,"usage: chunk-fixtures output-prefix data-version")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
