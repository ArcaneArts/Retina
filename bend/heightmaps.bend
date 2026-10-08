import Base
import ./compression.bend as Z
import ./material.bend as M
import ./binary.bend as B
import ./nbt.bend as N
import ./packed.bend as P

type Updated is Type:
  Updated{blocks: Array<U32>, materials: Array<M.Material>, maps: Array<U32>}

@unsafe
def hits(+n: Nat, maps: Array<U32>, +column: U32, +height: U32, +mask: U32) -> Array<U32>:
  match n:
    case 0n: maps
    case 1n+p:
      +kind = U32.from_nat(p)
      hit(U32.is_gt(U32.and(mask,U32.shln(1,p)),0),p,maps,column,height,mask,kind)

@unsafe
def hit(yes: Bool, n: Nat, maps: Array<U32>, +column: U32, +height: U32, mask: U32, kind: U32) -> Array<U32>:
  match yes:
    case False{}: hits(n,maps,column,height,mask)
    case True{}: hits(n,Array.set(U32,maps,U32.add(U32.mul(kind,256),column),height),column,height,mask)

@unsafe
def material_value(pair: Array<M.Material> & M.Material, blocks: Array<U32>, maps: Array<U32>,
    +i: U32, +section: U32, +domain: U32) -> Result<&1, &1, U32, Updated>:
  (materials, M.Material{_,+mask}) = pair
  valid_mask(U32.is_le(mask,63),blocks,materials,maps,i,section,domain,mask)

@unsafe
def valid_mask(valid: Bool, blocks: Array<U32>, materials: Array<M.Material>, maps: Array<U32>,
    +i: U32, +section: U32, +domain: U32, mask: U32) -> Result<&1, &1, U32, Updated>:
  match valid:
    case False{}: Fail{303}
    case True{}:
      scan(U32.is_lt(U32.inc(i),4096),blocks,materials,
        hits(6n,maps,U32.mod(i,256),U32.inc(U32.add(U32.mul(section,16),U32.div(i,256))),mask),
        U32.inc(i),section,domain)

@unsafe
def valid_id(valid: Bool, blocks: Array<U32>, materials: Array<M.Material>, maps: Array<U32>,
    i: U32, section: U32, domain: U32, id: U32) -> Result<&1, &1, U32, Updated>:
  match valid:
    case False{}: Fail{302}
    case True{}: material_value(Array.get(M.Material,materials,id),blocks,maps,i,section,domain)

@unsafe
def scan(more: Bool, blocks: Array<U32>, materials: Array<M.Material>, maps: Array<U32>,
    +i: U32, +section: U32, +domain: U32) -> Result<&1, &1, U32, Updated>:
  match more:
    case False{}: Done{Updated{blocks,materials,maps}}
    case True{}:
      Z.read(Result<&1, &1, U32, Updated>,blocks,i,blocks => +id =>
        valid_id(U32.is_lt(id,domain),blocks,materials,maps,i,section,domain,id))

# Caller maintains exact 4096-block sections, domain <= material capacity,
# maps capacity >=1536, and ascending section order. chunk.bend validates these.
def update(blocks: Array<U32>, materials: Array<M.Material>, maps: Array<U32>,
    section: U32, domain: U32) -> Result<&1, &1, U32, Updated>:
  scan(True{},blocks,materials,maps,0,section,domain)

@unsafe
def extract(+n: Nat, maps: Array<U32>, values: Array<U32>, +kind: U32) -> Array<U32> & Array<U32>:
  match n:
    case 0n: (maps,values)
    case 1n+p:
      +i = U32.from_nat(p)
      Z.read(Array<U32> & Array<U32>,maps,U32.add(U32.mul(kind,256),i),maps => value =>
        extract(p,maps,Array.set(U32,values,i,value),kind))

def packed(result: Result<&1, &1, U32, P.Packed>, w: B.Writer, name: String) -> B.Writer:
  match result:
    case Fail{code}: B.fail(w,code)
    case Done{P.Packed{words,size,_,_}}:
      N.write(N.LongArrayTag{words,size},w,Some{name},0,0)

@unsafe
def extracted(pair: Array<U32> & Array<U32>, w: B.Writer, names: List<String>, +kind: U32, +bits: U32, name: String) -> B.Writer:
  (maps,values) = pair
  write_more(names,maps,packed(P.pack(values,256,bits),w,name),U32.inc(kind),bits)

@unsafe
def write_more(names: List<String>, maps: Array<U32>, w: B.Writer, +kind: U32, +bits: U32) -> B.Writer:
  match names:
    case Nil{}: B.byte(w,0)
    case name <> rest: extracted(extract(256n,maps,[0 : U32*256n],kind),w,rest,kind,bits,name)

def write(maps: Array<U32>, w: B.Writer, bits: U32) -> B.Writer:
  write_more(["WORLD_SURFACE_WG","WORLD_SURFACE","OCEAN_FLOOR_WG","OCEAN_FLOOR","MOTION_BLOCKING","MOTION_BLOCKING_NO_LEAVES"],
    maps,N.header(Some{"Heightmaps"},10,0,w),0,bits)
