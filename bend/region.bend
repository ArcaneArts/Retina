import Base
import ./binary.bend as B
import ./compression.bend as Z

# Compressed records are independent zlib streams, not encoded by this module.
# A plan is an ordered header + sector-aligned record stream; callers can write
# it once without concatenating an entire MCA into a giant byte-list.
type Record is Type:
  Record{slot: U32, timestamp: U32, compressed: Z.Bytes}

type Plan is Type:
  Plan{segments: List<Z.Bytes>, sectors: U32, chunks: U32}

def set_int(a: Array<U32>, +offset: U32, +v: U32) -> Array<U32>:
  a = Array.set(U32,a,offset,U32.shrn(v,24n))
  a = Array.set(U32,a,U32.inc(offset),U32.and(U32.shrn(v,16n),255))
  a = Array.set(U32,a,U32.add(offset,2),U32.and(U32.shrn(v,8n),255))
  Array.set(U32,a,U32.add(offset,3),U32.and(v,255))

def reverse(xs: List<Z.Bytes>, acc: List<Z.Bytes>) -> List<Z.Bytes>:
  match xs:
    case Nil{}: acc
    case head <> rest: reverse(rest,head <> acc)

def padding(n: Nat, w: B.Writer) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: padding(p,B.byte(w,0))

def padded(w: B.Writer) -> Result<&1, &1, U32, Z.Bytes>:
  match w:
    case B.Writer{a,+size,capacity,error}:
      n = U32.mod(U32.sub(4096,U32.mod(size,4096)),4096)
      B.done(padding(U32.to_nat(n),B.Writer{a,size,capacity,error}))

@unsafe
def payload(more: Bool, a: Array<U32>, w: B.Writer, +i: U32, +size: U32) -> Result<&1, &1, U32, Z.Bytes>:
  match more:
    case False{}: padded(w)
    case True{}:
      Z.read(Result<&1, &1, U32, Z.Bytes>,a,i,a => value =>
        payload(U32.is_lt(U32.inc(i),size),a,B.byte(w,value),U32.inc(i),size))

def packet(input: Z.Bytes) -> Result<&1, &1, U32, Z.Bytes>:
  match input:
    case Z.Bytes{a,+size}: payload(U32.is_gt(size,0),a,B.byte(B.int(B.new(),U32.inc(size)),2),0,size)

@unsafe
def appended(result: Result<&1, &1, U32, Z.Bytes>, rest: List<Record>,
    header: Array<U32>, seen: Array<U32>, sector: U32, segments: List<Z.Bytes>, chunks: U32) -> Result<&1, &1, U32, Plan>:
  match result:
    case Fail{code}: Fail{code}
    case Done{bytes}: assemble(rest,header,seen,sector,bytes <> segments,U32.inc(chunks))

@unsafe
def unique(unused: Bool, rest: List<Record>, header: Array<U32>, seen: Array<U32>,
    +sector: U32, segments: List<Z.Bytes>, chunks: U32, +slot: U32, timestamp: U32,
    data: Z.Bytes, +sectors: U32) -> Result<&1, &1, U32, Plan>:
  match unused:
    case False{}: Fail{102}
    case True{}:
      header = set_int(header,U32.mul(slot,4),U32.or(U32.shln(sector,8n),sectors))
      header = set_int(header,U32.add(4096,U32.mul(slot,4)),timestamp)
      appended(packet(data),rest,header,Array.set(U32,seen,slot,1),U32.add(sector,sectors),segments,chunks)

@unsafe
def record_valid(valid: Bool, rest: List<Record>, header: Array<U32>, seen: Array<U32>,
    sector: U32, segments: List<Z.Bytes>, chunks: U32, +slot: U32, timestamp: U32,
    data: Z.Bytes, sectors: U32) -> Result<&1, &1, U32, Plan>:
  match valid:
    case False{}: Fail{101}
    case True{}:
      Z.read(Result<&1, &1, U32, Plan>,seen,slot,seen => used =>
        unique(U32.is_zero(used),rest,header,seen,sector,segments,chunks,slot,timestamp,data,sectors))

@unsafe
def assemble(records: List<Record>, header: Array<U32>, seen: Array<U32>,
    +sector: U32, segments: List<Z.Bytes>, chunks: U32) -> Result<&1, &1, U32, Plan>:
  match records:
    case Nil{}: Done{Plan{Z.Bytes{header,8192} <> reverse(segments,[]),sector,chunks}}
    case Record{+slot,timestamp,Z.Bytes{data,+size}} <> rest:
      +sectors = U32.div(U32.add(size,4100),4096)
      record_valid(Bool.and(U32.is_lt(slot,1024),Bool.and(U32.is_gt(size,0),
        Bool.and(U32.is_le(sectors,255),U32.is_le(U32.add(sector,sectors),16777215)))),
        rest,header,seen,sector,segments,chunks,slot,timestamp,Z.Bytes{data,size},sectors)

# New-file planning only. Existing-record preservation/cache promotion are
# implemented by a later lifecycle layer; never replace an existing save with
# this empty-header plan. Inline record limits match the current Rust writer.
def plan(records: List<Record>) -> Result<&1, &1, U32, Plan>:
  assemble(records,[0 : U32*8192n],[0 : U32*1024n],2,[],0)
