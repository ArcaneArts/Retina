import Base
import ./binary.bend as B
import ./compression.bend as Z
import ./nbt.bend as N
import ./packed.bend as P
import ./palette.bend as A
import ./heightmaps.bend as H
import ./material.bend as M
import ./word64.bend as W

type Profile is Type:
  Profile{materials: Array<M.Material>, material_count: U32, biomes: Array<String>, biome_count: U32}
type Light is Type:
  Light{block: Z.Bytes, sky: Z.Bytes}
type Boundary is Type:
  Boundary{below: Light, above: Light}
type Section is Type:
  Section{blocks: Array<U32>, biomes: Array<U32>, light: Maybe<&1, Light>}
type Metadata is Type:
  Metadata{starts: List<N.Field>, references: List<N.Field>, entities: List<N.Tag>, block_entities: List<N.Tag>}
type Chunk is Type:
  Chunk{version: U32, x: U32, z: U32, min_section: U32, section_count: U32,
    sections: List<Section>, boundary: Maybe<&1, Boundary>, metadata: Metadata}
type NamedBlocks is Type:
  NamedBlocks{materials: Array<M.Material>, tags: List<N.Tag>}
type NamedBiomes is Type:
  NamedBiomes{biomes: Array<String>, tags: List<N.Tag>}
type BlockEncoded is Type:
  BlockEncoded{materials: Array<M.Material>, writer: B.Writer}
type BiomeEncoded is Type:
  BiomeEncoded{biomes: Array<String>, writer: B.Writer}
type Step is Type:
  Step{profile: Profile, maps: Array<U32>, writer: B.Writer}

def properties(xs: List<&2, M.Property>) -> List<N.Field>:
  match xs:
    case Nil{}: []
    case M.Property{name,value} <> rest: N.Field{name,N.StringTag{value}} <> properties(rest)

def state_properties(xs: List<&2, M.Property>, id: String) -> N.Tag:
  match xs:
    case Nil{}: N.CompoundTag{[N.Field{"id",N.StringTag{id}}]}
    case p <> rest: N.CompoundTag{[N.Field{"id",N.StringTag{id}},
      N.Field{"properties",N.CompoundTag{properties(p <> rest)}}]}

def state(value: M.State) -> N.Tag:
  match value:
    case M.State{id,props}: state_properties(props,id)

@unsafe
def block_names(+n: Nat, globals: Array<U32>, materials: Array<M.Material>, acc: List<N.Tag>) -> NamedBlocks:
  match n:
    case 0n: NamedBlocks{materials,acc}
    case 1n+p:
      Z.read(NamedBlocks,globals,U32.from_nat(p),globals => id =>
        block_name(Array.get(M.Material,materials,id),p,globals,acc))

@unsafe
def block_material(material: M.Material, n: Nat, globals: Array<U32>, materials: Array<M.Material>, acc: List<N.Tag>) -> NamedBlocks:
  match material:
    case M.Material{value,_}: block_names(n,globals,materials,state(value) <> acc)

@unsafe
def block_name(pair: Array<M.Material> & M.Material, n: Nat, globals: Array<U32>, acc: List<N.Tag>) -> NamedBlocks:
  (materials, material) = pair
  block_material(material,n,globals,materials,acc)

@unsafe
def biome_names(+n: Nat, globals: Array<U32>, biomes: Array<String>, acc: List<N.Tag>) -> NamedBiomes:
  match n:
    case 0n: NamedBiomes{biomes,acc}
    case 1n+p:
      Z.read(NamedBiomes,globals,U32.from_nat(p),globals => id =>
        biome_name(Array.get(String,biomes,id),p,globals,acc))

@unsafe
def biome_name(pair: Array<String> & String, n: Nat, globals: Array<U32>, acc: List<N.Tag>) -> NamedBiomes:
  (biomes, name) = pair
  biome_names(n,globals,biomes,N.StringTag{name} <> acc)

def packed_data(result: Result<&1, &1, U32, P.Packed>, w: B.Writer) -> B.Writer:
  H.packed(result,w,"data")

def indices(multiple: Bool, values: Array<U32>, w: B.Writer, count: U32, bits: U32) -> B.Writer:
  match multiple:
    case False{}: B.byte(w,0)
    case True{}: B.byte(packed_data(P.pack(values,count,bits),w),0)

def named_blocks(named: NamedBlocks, indices_array: Array<U32>, w: B.Writer, +size: U32) -> BlockEncoded:
  match named:
    case NamedBlocks{materials,tags}:
      BlockEncoded{materials,indices(U32.is_gt(size,1),indices_array,
        N.write(N.ListTag{10,size,tags},N.header(Some{"block_states"},10,0,w),Some{"palette"},0,0),
        4096,U32.max(4,A.width(size)))}

def block_palette(result: Result<&1, &1, U32, A.Palette>, materials: Array<M.Material>, w: B.Writer)
    -> Result<&1, &1, U32, BlockEncoded>:
  match result:
    case Fail{code}: Fail{code}
    case Done{A.Palette{globals,values,+size,_}}:
      Done{named_blocks(block_names(U32.to_nat(size),globals,materials,[]),values,w,size)}

def named_biomes(named: NamedBiomes, indices_array: Array<U32>, w: B.Writer, +size: U32) -> BiomeEncoded:
  match named:
    case NamedBiomes{biomes,tags}:
      BiomeEncoded{biomes,indices(U32.is_gt(size,1),indices_array,
        N.write(N.ListTag{8,size,tags},N.header(Some{"biomes"},10,0,w),Some{"palette"},0,0),64,A.width(size))}

def biome_palette(result: Result<&1, &1, U32, A.Palette>, biomes: Array<String>, w: B.Writer)
    -> Result<&1, &1, U32, BiomeEncoded>:
  match result:
    case Fail{code}: Fail{code}
    case Done{A.Palette{globals,values,+size,_}}:
      Done{named_biomes(biome_names(U32.to_nat(size),globals,biomes,[]),values,w,size)}

# Lighting is supplied only by a producer that has established correctness. The
# serializer validates size/byte values and never decides that a chunk is lit.
@unsafe
def light_value(valid: Bool, n: Nat, a: Array<U32>, w: B.Writer, value: U32) -> B.Writer:
  match valid:
    case False{}: B.fail(w,405)
    case True{}: light_bytes(n,a,B.byte(w,value))

@unsafe
def light_bytes(+n: Nat, a: Array<U32>, w: B.Writer) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p:
      +i = U32.sub(2047,U32.from_nat(p))
      Z.read(B.Writer,a,i,a => +value =>
        light_value(U32.is_le(value,255),p,a,w,value))

def light_capacity(valid: Bool, a: Array<U32>, w: B.Writer, name: String) -> B.Writer:
  match valid:
    case False{}: B.fail(w,405)
    case True{}: light_bytes(2048n,a,B.int(N.header(Some{name},7,0,w),2048))

def light_size(valid: Bool, a: Array<U32>, w: B.Writer, name: String) -> B.Writer:
  match valid:
    case False{}: B.fail(w,405)
    case True{}:
      Z.result(B.Writer,Array.size(U32,a),a => capacity =>
        light_capacity(U32.is_ge(capacity,2048),a,w,name))

def light_array(bytes: Z.Bytes, w: B.Writer, name: String) -> B.Writer:
  match bytes:
    case Z.Bytes{a,size}: light_size(U32.is_eq(size,2048),a,w,name)

def light(value: Light, w: B.Writer) -> B.Writer:
  match value:
    case Light{block,sky}: light_array(sky,light_array(block,w,"BlockLight"),"SkyLight")

def section_light(value: Maybe<&1, Light>, lit: Bool, w: B.Writer) -> B.Writer:
  match value lit:
    case None{} False{}: B.byte(w,0)
    case Some{l} True{}: B.byte(light(l,w),0)
    case _ _: B.fail(w,404)

def biome_encoded(result: Result<&1, &1, U32, BiomeEncoded>, materials: Array<M.Material>, maps: Array<U32>,
    material_count: U32, biome_count: U32, lighting: Maybe<&1, Light>, lit: Bool) -> Result<&1, &1, U32, Step>:
  match result:
    case Fail{code}: Fail{code}
    case Done{BiomeEncoded{biomes,w}}:
      Done{Step{Profile{materials,material_count,biomes,biome_count},maps,section_light(lighting,lit,w)}}

def block_encoded(result: Result<&1, &1, U32, BlockEncoded>, biomes: Array<String>, values: Array<U32>, maps: Array<U32>,
    material_count: U32, +biome_count: U32, lighting: Maybe<&1, Light>, lit: Bool) -> Result<&1, &1, U32, Step>:
  match result:
    case Fail{code}: Fail{code}
    case Done{BlockEncoded{materials,w}}:
      biome_encoded(biome_palette(A.build(values,64,biome_count),biomes,w),materials,maps,
        material_count,biome_count,lighting,lit)

def heights_updated(result: Result<&1, &1, U32, H.Updated>, biomes: Array<String>, values: Array<U32>, w: B.Writer,
    +material_count: U32, biome_count: U32, lighting: Maybe<&1, Light>, lit: Bool) -> Result<&1, &1, U32, Step>:
  match result:
    case Fail{code}: Fail{code}
    case Done{H.Updated{blocks,materials,maps}}:
      block_encoded(block_palette(A.build(blocks,4096,material_count),materials,w),biomes,values,maps,
        material_count,biome_count,lighting,lit)

def section_capacity(valid: Bool, blocks: Array<U32>, biomes_values: Array<U32>, profile: Profile, maps: Array<U32>,
    w: B.Writer, index: U32, lighting: Maybe<&1, Light>, lit: Bool) -> Result<&1, &1, U32, Step>:
  match valid profile:
    case False{} _: Fail{403}
    case True{} Profile{materials,+material_count,biomes,biome_count}:
      heights_updated(H.update(blocks,materials,maps,index,material_count),biomes,biomes_values,w,
        material_count,biome_count,lighting,lit)

def block_capacity(pair: Array<U32> & U32, values: Array<U32>, profile: Profile, maps: Array<U32>,
    w: B.Writer, index: U32, lighting: Maybe<&1, Light>, lit: Bool) -> Result<&1, &1, U32, Step>:
  (blocks,+capacity) = pair
  Z.result(Result<&1, &1, U32, Step>,Array.size(U32,values),values => size =>
    section_capacity(Bool.and(U32.is_eq(capacity,4096),U32.is_eq(size,64)),blocks,values,profile,maps,w,index,lighting,lit))

def section(value: Section, profile: Profile, maps: Array<U32>, w: B.Writer, +index: U32, min_section: U32, lit: Bool)
    -> Result<&1, &1, U32, Step>:
  match value:
    case Section{blocks,biomes,lighting}:
      block_capacity(Array.size(U32,blocks),biomes,profile,maps,
        N.write(N.ByteTag{U32.add(min_section,index)},w,Some{"Y"},0,0),index,lighting,lit)

def counted_blocks(pair: List<N.Tag> & U32, w: B.Writer) -> B.Writer:
  (values,size) = pair
  N.write(N.ListTag{10,size,values},w,Some{"block_entities"},0,0)

@unsafe
def counted_tail(pair: List<N.Tag> & U32, head: N.Tag) -> List<N.Tag> & U32:
  (rest,size) = pair
  (head <> rest,U32.inc(size))

@unsafe
def count(xs: List<N.Tag>) -> List<N.Tag> & U32:
  match xs:
    case Nil{}: ([],0)
    case head <> rest: counted_tail(count(rest),head)

def counted_entities(pair: List<N.Tag> & U32, block_entities: List<N.Tag>, w: B.Writer) -> B.Writer:
  (entities,size) = pair
  counted_blocks(count(block_entities),N.write(N.ListTag{10,size,entities},w,Some{"entities"},0,0))

def metadata_entities(entities: List<N.Tag>, block_entities: List<N.Tag>, w: B.Writer) -> B.Writer:
  # List<Tag> is affine: use a structural counter that returns the same list.
  counted_entities(count(entities),block_entities,w)

def metadata(value: Metadata, w: B.Writer) -> B.Writer:
  match value:
    case Metadata{starts,refs,entities,block_entities}:
      # Lengths are counted from the owned lists instead of trusting a caller.
      metadata_entities(entities,block_entities,
        N.write(N.CompoundTag{[N.Field{"starts",N.CompoundTag{starts}},
          N.Field{"References",N.CompoundTag{refs}}]},w,Some{"structures"},0,0))

def finish(w: B.Writer, maps: Array<U32>, height_bits: U32, meta: Metadata) -> Result<&1, &1, U32, Z.Bytes>:
  w = metadata(meta,H.write(maps,w,height_bits))
  w = N.write(N.ListTag{10,0,[]},w,Some{"block_ticks"},0,0)
  w = N.write(N.ListTag{10,0,[]},w,Some{"fluid_ticks"},0,0)
  B.done(B.byte(N.write(N.ListTag{9,0,[]},w,Some{"PostProcessing"},0,0),0))

def top_light(boundary: Maybe<&1, Light>, w: B.Writer, y: U32) -> B.Writer:
  match boundary:
    case None{}: w
    case Some{l}: B.byte(light(l,N.write(N.ByteTag{y},w,Some{"Y"},0,0)),0)

def sections_done(valid: Bool, w: B.Writer, maps: Array<U32>, +total: U32, min_section: U32,
    above: Maybe<&1, Light>, meta: Metadata) -> Result<&1, &1, U32, Z.Bytes>:
  match valid:
    case False{}: Fail{402}
    case True{}: finish(top_light(above,w,U32.add(min_section,total)),maps,A.width(U32.inc(U32.mul(total,16))),meta)

@unsafe
def section_available(valid: Bool, value: Section, rest: List<Section>, profile: Profile, maps: Array<U32>, w: B.Writer,
    +i: U32, +total: U32, +min_section: U32, +lit: Bool, above: Maybe<&1, Light>, meta: Metadata)
    -> Result<&1, &1, U32, Z.Bytes>:
  match valid:
    case False{}: Fail{402}
    case True{}: next(section(value,profile,maps,w,i,min_section,lit),rest,i,total,min_section,lit,above,meta)

@unsafe
def sections(xs: List<Section>, profile: Profile, maps: Array<U32>, w: B.Writer, +i: U32,
    +total: U32, +min_section: U32, +lit: Bool, above: Maybe<&1, Light>, meta: Metadata)
    -> Result<&1, &1, U32, Z.Bytes>:
  match xs:
    case Nil{}: sections_done(U32.is_eq(i,total),w,maps,total,min_section,above,meta)
    case value <> rest: section_available(U32.is_lt(i,total),value,rest,profile,maps,w,i,total,min_section,lit,above,meta)

@unsafe
def next(result: Result<&1, &1, U32, Step>, rest: List<Section>, +i: U32, +total: U32, +min_section: U32,
    +lit: Bool, above: Maybe<&1, Light>, meta: Metadata) -> Result<&1, &1, U32, Z.Bytes>:
  match result:
    case Fail{code}: Fail{code}
    case Done{Step{profile,maps,w}}:
      sections(rest,profile,maps,w,U32.inc(i),total,min_section,lit,above,meta)

def begin_lighting(boundary: Maybe<&1, Boundary>, values: List<Section>, profile: Profile, w: B.Writer,
    +total: U32, +min_section: U32, meta: Metadata) -> Result<&1, &1, U32, Z.Bytes>:
  match boundary:
    case None{}:
      w = N.write(N.StringTag{"minecraft:features"},w,Some{"Status"},0,0)
      w = N.write(N.ByteTag{0},w,Some{"isLightOn"},0,0)
      sections(values,profile,[0 : U32*2048n],B.int(B.byte(N.header(Some{"sections"},9,0,w),10),total),
        0,total,min_section,False{},None{},meta)
    case Some{Boundary{below,above}}:
      w = N.write(N.StringTag{"minecraft:light"},w,Some{"Status"},0,0)
      w = N.write(N.ByteTag{1},w,Some{"isLightOn"},0,0)
      w = B.int(B.byte(N.header(Some{"sections"},9,0,w),10),U32.add(total,2))
      w = B.byte(light(below,N.write(N.ByteTag{U32.sub(min_section,1)},w,Some{"Y"},0,0)),0)
      sections(values,profile,[0 : U32*2048n],w,0,total,min_section,True{},Some{above},meta)

def dimensions(valid: Bool, chunk: Chunk, profile: Profile) -> Result<&1, &1, U32, Z.Bytes>:
  match valid chunk:
    case False{} _: Fail{401}
    case True{} Chunk{version,x,z,+min_section,+total,values,boundary,meta}:
      w = N.header(Some{""},10,0,B.new())
      w = N.write(N.IntTag{version},w,Some{"DataVersion"},0,0)
      w = N.write(N.IntTag{x},w,Some{"xPos"},0,0)
      w = N.write(N.IntTag{z},w,Some{"zPos"},0,0)
      w = N.write(N.IntTag{min_section},w,Some{"yPos"},0,0)
      w = N.write(N.LongTag{W.U64{0,0}},w,Some{"LastUpdate"},0,0)
      w = N.write(N.LongTag{W.U64{0,0}},w,Some{"InhabitedTime"},0,0)
      begin_lighting(boundary,values,profile,w,total,min_section,meta)

def bounds_light(boundary: Maybe<&1, Boundary>, version: U32, x: U32, z: U32,
    +min_section: U32, +total: U32, values: List<Section>, meta: Metadata, profile: Profile)
    -> Result<&1, &1, U32, Z.Bytes>:
  match boundary:
    case None{}:
      dimensions(Bool.and(U32.is_gt(total,0),Bool.and(U32.is_le(total,256),
        Bool.and(U32.is_lt(U32.add(min_section,128),256),U32.is_le(U32.add(U32.add(min_section,128),total),256)))),
        Chunk{version,x,z,min_section,total,values,None{},meta},profile)
    case Some{b}:
      dimensions(Bool.and(U32.is_gt(total,0),Bool.and(U32.is_le(total,254),
        Bool.and(Bool.and(U32.is_gt(U32.add(min_section,128),0),U32.is_lt(U32.add(min_section,128),256)),
          U32.is_lt(U32.add(U32.add(min_section,128),total),256)))),
        Chunk{version,x,z,min_section,total,values,Some{b},meta},profile)

def bounds(chunk: Chunk, profile: Profile) -> Result<&1, &1, U32, Z.Bytes>:
  match chunk:
    case Chunk{version,x,z,+min_section,+total,values,boundary,meta}:
      bounds_light(boundary,version,x,z,min_section,total,values,meta,profile)

def profile_valid(valid: Bool, profile: Profile, chunk: Chunk) -> Result<&1, &1, U32, Z.Bytes>:
  match valid:
    case False{}: Fail{406}
    case True{}: bounds(chunk,profile)

def profile_biomes(pair: Array<String> & U32, materials: Array<M.Material>, +mc: U32, +bc: U32,
    +material_capacity: U32, chunk: Chunk) -> Result<&1, &1, U32, Z.Bytes>:
  (biomes,+capacity) = pair
  profile_valid(Bool.and(Bool.and(U32.is_gt(mc,0),U32.is_le(mc,65536)),
    Bool.and(Bool.and(U32.is_gt(bc,0),U32.is_le(bc,65536)),Bool.and(U32.is_le(mc,material_capacity),U32.is_le(bc,capacity)))),
    Profile{materials,mc,biomes,bc},chunk)

def profile_materials(pair: Array<M.Material> & U32, +mc: U32, biomes: Array<String>, +bc: U32, chunk: Chunk)
    -> Result<&1, &1, U32, Z.Bytes>:
  (materials,capacity) = pair
  profile_biomes(Array.size(String,biomes),materials,mc,bc,capacity,chunk)

def encode(chunk: Chunk, profile: Profile) -> Result<&1, &1, U32, Z.Bytes>:
  match profile:
    case Profile{materials,mc,biomes,bc}: profile_materials(Array.size(M.Material,materials),mc,biomes,bc,chunk)
