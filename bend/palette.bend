import Base
import ./compression.bend as Z

# First-occurrence local palettes for block sections and quart biomes. Global
# IDs are profile-local U16s, not Minecraft runtime IDs. No 64K clearing pass.
type Palette is Type:
  Palette{globals: Array<U32>, indices: Array<U32>, size: U32, entries: U32}

def width_nonuniform(multiple: Bool, size: U32) -> U32:
  match multiple:
    case False{}: 0
    case True{}: U32.inc(U32.from_nat(U32.log2(U32.sub(size,1))))

def width(+size: U32) -> U32:
  width_nonuniform(U32.is_gt(size,1),size)

@unsafe
def found(empty: Bool, values: Array<U32>, globals: Array<U32>, indices: Array<U32>, table: Array<U32>,
    +count: U32, +domain: U32, +mask: U32, +i: U32, +size: U32, +id: U32, +slot: U32, +entry: U32)
    -> Result<&1, &1, U32, Palette>:
  match empty:
    case True{}:
      more(U32.is_lt(U32.inc(i),count),values,Array.set(U32,globals,size,id),
        Array.set(U32,indices,i,size),
        Array.set(U32,table,slot,U32.or(U32.shln(id,13n),U32.inc(size))),
        count,domain,mask,U32.inc(i),U32.inc(size))
    case False{}:
      same(U32.is_eq(U32.shrn(entry,13n),id),values,globals,indices,table,
        count,domain,mask,i,size,id,slot,entry)

@unsafe
def same(equal: Bool, values: Array<U32>, globals: Array<U32>, indices: Array<U32>, table: Array<U32>,
    +count: U32, +domain: U32, +mask: U32, +i: U32, +size: U32, +id: U32, slot: U32, entry: U32)
    -> Result<&1, &1, U32, Palette>:
  match equal:
    case True{}:
      more(U32.is_lt(U32.inc(i),count),values,globals,
        Array.set(U32,indices,i,U32.sub(U32.and(entry,8191),1)),table,
        count,domain,mask,U32.inc(i),size)
    case False{}:
      probe(values,globals,indices,table,count,domain,mask,i,size,id,U32.and(U32.inc(slot),mask))

@unsafe
def probe(values: Array<U32>, globals: Array<U32>, indices: Array<U32>, table: Array<U32>,
    +count: U32, +domain: U32, +mask: U32, +i: U32, +size: U32, +id: U32, +slot: U32)
    -> Result<&1, &1, U32, Palette>:
  Z.read(Result<&1, &1, U32, Palette>,table,slot,table => +entry =>
    found(U32.is_zero(entry),values,globals,indices,table,count,domain,mask,i,size,id,slot,entry))

@unsafe
def valid_id(valid: Bool, values: Array<U32>, globals: Array<U32>, indices: Array<U32>, table: Array<U32>,
    +count: U32, +domain: U32, +mask: U32, +i: U32, +size: U32, +id: U32)
    -> Result<&1, &1, U32, Palette>:
  match valid:
    case False{}: Fail{203}
    case True{}:
      probe(values,globals,indices,table,count,domain,mask,i,size,id,U32.and(U32.mul(id,2654435761),mask))

@unsafe
def more(again: Bool, values: Array<U32>, globals: Array<U32>, indices: Array<U32>, table: Array<U32>,
    +count: U32, +domain: U32, +mask: U32, +i: U32, +size: U32) -> Result<&1, &1, U32, Palette>:
  match again:
    case False{}: Done{Palette{globals,indices,size,count}}
    case True{}:
      Z.read(Result<&1, &1, U32, Palette>,values,i,values => +id =>
        valid_id(U32.is_lt(id,domain),values,globals,indices,table,count,domain,mask,i,size,id))

def capacity_valid(valid: Bool, values: Array<U32>, +count: U32, +domain: U32) -> Result<&1, &1, U32, Palette>:
  match valid:
    case False{}: Fail{202}
    case True{}:
      +depth = U32.to_nat(width(count))
      +table_depth = Nat.add(depth,1n)
      more(True{},values,Array.new(U32,depth,0),Array.new(U32,depth,0),Array.new(U32,table_depth,0),
        count,domain,U32.sub(U32.shln(1,table_depth),1),0,0)

def parameters_valid(valid: Bool, values: Array<U32>, +count: U32, domain: U32) -> Result<&1, &1, U32, Palette>:
  match valid:
    case False{}: Fail{201}
    case True{}:
      Z.result(Result<&1, &1, U32, Palette>,Array.size(U32,values),values => capacity =>
        capacity_valid(U32.is_le(count,capacity),values,count,domain))

def build(values: Array<U32>, +count: U32, +domain: U32) -> Result<&1, &1, U32, Palette>:
  parameters_valid(Bool.and(Bool.and(U32.is_gt(count,0),U32.is_le(count,4096)),
    Bool.and(U32.is_gt(domain,0),U32.is_le(domain,65536))),values,count,domain)
