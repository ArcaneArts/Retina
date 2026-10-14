import Base
import ./structure_registry.bend as M
import ./structure_values.bend as V
import ./registry.bend as R
import ./registry_values.bend as RV
import ./nbt.bend as Nbt
import ./numbers.bend as N
import ./binary.bend as B
import ./word64.bend as W

# Serialize the shared typed-NBT registry form directly. Exact long/double bits
# and UTF-16 dictionary units survive processor changes; no JSON/text roundtrip.
@unsafe
def integer64(+node: M.Node) -> W.U64:
  raw_integer(M.value(node))

@unsafe
def raw_integer(value: R.Value) -> W.U64:
  match value:
    case R.Value{3,hi,lo,_}: W.U64{hi,lo}
    case _: W.U64{0,0}

@unsafe
def double_integer(+value: W.U64) -> W.U64:
  double_signed(N.greater(value,W.U64{2147483647,4294967295}),value)

@unsafe
def double_signed(negative: Bool,+value: W.U64) -> W.U64:
  match negative:
    case False{}: double_magnitude(value)
    case True{}: W.add(double_magnitude(W.sub(W.U64{0,0},value)),W.U64{2147483648,0})

@unsafe
def double_magnitude(+value: W.U64) -> W.U64:
  match value:
    case W.U64{0,0}: value
    case W.U64{+hi,+lo}: double_bit(value,N.highest(hi,32),hi,lo)

@unsafe
def double_bit(+value: W.U64,high: U32,hi: U32,lo: U32) -> W.U64:
  double_high(U32.is_zero(hi),value,high,lo)

@unsafe
def double_high(zero: Bool,+value: W.U64,high: U32,lo: U32) -> W.U64:
  match zero:
    case True{}: double_shift(value,N.highest(lo,0))
    case False{}: double_shift(value,high)

@unsafe
def double_shift(+value: W.U64,+bit: U32) -> W.U64:
  double_small(U32.is_le(bit,52),value,bit)

@unsafe
def double_small(small: Bool,+value: W.U64,+bit: U32) -> W.U64:
  match small:
    case True{}: W.add(W.U64{U32.shln(U32.add(bit,1022),20n),0},W.shl(value,U32.sub(52,bit)))
    case False{}: double_round(value,bit,U32.sub(bit,52))

@unsafe
def double_round(+value: W.U64,+bit: U32,+shift: U32) -> W.U64:
  +kept = W.shr(value,shift)
  +tail = W.and(value,W.sub(W.shl(W.U64{0,1},shift),W.U64{0,1}))
  +half = W.shl(W.U64{0,1},U32.sub(shift,1))
  W.add(W.U64{U32.shln(U32.add(bit,1022),20n),0},W.add(kept,W.U64{0,W.flag(Bool.or(N.greater(tail,half),Bool.and(W.equal(tail,half),Bool.not(W.equal(W.and(kept,W.U64{0,1}),W.U64{0,0})))))}))

@unsafe
def double_bits(value: R.Value) -> W.U64:
  match value:
    case R.Value{4,hi,lo,_}: W.U64{hi,lo}
    case R.Value{3,hi,lo,_}: double_integer(W.U64{hi,lo})
    case _: W.U64{0,0}

@unsafe
def float_bits(value: R.Value) -> U32:
  match value:
    case R.Value{4,hi,lo,_}: N.double_bits(hi,lo)
    case R.Value{3,hi,lo,_}: F32.bits(N.integer(hi,lo))
    case _: 0

@unsafe
def string(-A: Type,doc: R.Document,id: U32,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match id:
    case 4294967232: next(doc,Nbt.text(w,"id"))
    case 4294967233: next(doc,Nbt.text(w,"ChunkX"))
    case 4294967234: next(doc,Nbt.text(w,"ChunkZ"))
    case 4294967235: next(doc,Nbt.text(w,"references"))
    case 4294967236: next(doc,Nbt.text(w,"Children"))
    case 4294967237: next(doc,Nbt.text(w,"BB"))
    case 4294967238: next(doc,Nbt.text(w,"O"))
    case 4294967239: next(doc,Nbt.text(w,"GD"))
    case 4294967240: next(doc,Nbt.text(w,"template"))
    case 4294967241: next(doc,Nbt.text(w,"starts"))
    case 4294967242: next(doc,Nbt.text(w,"References"))
    case 4294967243: next(doc,Nbt.text(w,"structures"))
    case 4294967244: next(doc,Nbt.text(w,"entities"))
    case 4294967245: next(doc,Nbt.text(w,"block_entities"))
    case 4294967246: next(doc,Nbt.text(w,"retina:template"))
    case 4294967280: next(doc,Nbt.text(w,"LootTable"))
    case 4294967281: next(doc,Nbt.text(w,"x"))
    case 4294967282: next(doc,Nbt.text(w,"y"))
    case 4294967283: next(doc,Nbt.text(w,"z"))
    case 4294967284: next(doc,Nbt.text(w,"keepPacked"))
    case 4294967285: next(doc,Nbt.text(w,"LootTableSeed"))
    case 4294967286: next(doc,Nbt.text(w,"Pos"))
    case 4294967287: next(doc,Nbt.text(w,"UUID"))
    case 4294967288: next(doc,Nbt.text(w,"Rotation"))
    case _: R.string(A,doc,id,doc => reference => string_ref(A,doc,reference,w,next))

@unsafe
def string_ref(-A: Type,doc: R.Document,reference: Maybe<&2,R.StringRef>,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match reference:
    case None{}: next(doc,B.fail(w,860))
    case Some{R.StringRef{at,n}}: string_size(A,doc,U32.mul(at,2),n,0,0,w,next)

@unsafe
def string_size(-A: Type,doc: R.Document,+at: U32,+n: U32,+i: U32,+size: U32,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  string_more(A,U32.is_lt(i,n),doc,at,n,i,size,w,next)

@unsafe
def string_more(-A: Type,more: Bool,doc: R.Document,+at: U32,+n: U32,+i: U32,+size: U32,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match more:
    case False{}: string_valid(A,U32.is_le(size,65535),doc,at,n,size,w,next)
    case True{}: RV.character(A,doc,U32.add(at,i),doc => value => string_size(A,doc,at,n,U32.inc(i),U32.add(size,Nbt.char_size(value)),w,next))

@unsafe
def string_valid(-A: Type,valid: Bool,doc: R.Document,at: U32,n: U32,size: U32,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match valid:
    case False{}: next(doc,B.fail(w,861))
    case True{}: string_bytes(A,doc,at,n,B.short(w,size),next)

@unsafe
def string_bytes(-A: Type,doc: R.Document,+at: U32,n: U32,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match n:
    case 0: next(doc,w)
    case _: RV.character(A,doc,at,doc => value => string_bytes(A,doc,U32.inc(at),U32.sub(n,1),Nbt.utf16(w,value),next))

@unsafe
def root(-A: Type,doc: R.Document,+tag: M.Node,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  write(A,doc,tag,B.short(B.byte(w,M.int(M.item(tag,0),0)),0),0,next)

@unsafe
def write(-A: Type,doc: R.Document,+tag: M.Node,w: B.Writer,+depth: U32,next: R.Document -> B.Writer -> A) -> A:
  write_valid(A,Bool.and(U32.is_le(depth,128),U32.is_eq(M.count(tag),2)),doc,tag,w,depth,next)

@unsafe
def write_valid(-A: Type,valid: Bool,doc: R.Document,+tag: M.Node,w: B.Writer,+depth: U32,next: R.Document -> B.Writer -> A) -> A:
  match valid:
    case False{}: next(doc,B.fail(w,862))
    case True{}: payload(A,doc,M.int(M.item(tag,0),0),M.item(tag,1),w,depth,next)

@unsafe
def scalar(valid: Bool,w: B.Writer) -> B.Writer:
  match valid:
    case True{}: w
    case False{}: B.fail(w,863)

@unsafe
def payload(-A: Type,doc: R.Document,kind: U32,+value: M.Node,w: B.Writer,+depth: U32,next: R.Document -> B.Writer -> A) -> A:
  match kind:
    case 1: next(doc,B.byte(scalar(U32.is_eq(M.tag(value),3),w),M.int(value,0)))
    case 2: next(doc,B.short(scalar(U32.is_eq(M.tag(value),3),w),M.int(value,0)))
    case 3: next(doc,B.int(scalar(U32.is_eq(M.tag(value),3),w),M.int(value,0)))
    case 4: next(doc,B.long(scalar(U32.is_eq(M.tag(value),3),w),integer64(value)))
    case 5: next(doc,B.int(scalar(Bool.or(U32.is_eq(M.tag(value),3),U32.is_eq(M.tag(value),4)),w),float_bits(M.value(value))))
    case 6: next(doc,B.long(scalar(Bool.or(U32.is_eq(M.tag(value),3),U32.is_eq(M.tag(value),4)),w),double_bits(M.value(value))))
    case 7: numeric_array(A,doc,value,0,M.count(value),7,B.int(w,M.count(value)),next)
    case 8: string(A,doc,M.text(value),w,next)
    case 9: list(A,doc,value,w,depth,next)
    case 10: compound(A,doc,value,w,depth,next)
    case 11: numeric_array(A,doc,value,0,M.count(value),11,B.int(w,M.count(value)),next)
    case 12: numeric_array(A,doc,value,0,M.count(value),12,B.int(w,M.count(value)),next)
    case _: next(doc,B.fail(w,864))

@unsafe
def numeric_array(-A: Type,doc: R.Document,+value: M.Node,+i: U32,n: U32,+kind: U32,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match value n:
    case M.Array{_,_} 0: next(doc,w)
    case M.Array{_,_} _: numeric_array(A,doc,value,U32.inc(i),U32.sub(n,1),kind,numeric_item(kind,M.item(value,i),w),next)
    case _ _: next(doc,B.fail(w,865))

@unsafe
def numeric_item(kind: U32,+node: M.Node,w: B.Writer) -> B.Writer:
  match kind:
    case 7: B.byte(scalar(U32.is_eq(M.tag(node),3),w),M.int(node,0))
    case 11: B.int(scalar(U32.is_eq(M.tag(node),3),w),M.int(node,0))
    case _: B.long(scalar(U32.is_eq(M.tag(node),3),w),integer64(node))

@unsafe
def compound(-A: Type,doc: R.Document,+value: M.Node,w: B.Writer,+depth: U32,next: R.Document -> B.Writer -> A) -> A:
  match value:
    case M.Object{_,fields}: fields_write(A,doc,fields,w,depth,next)
    case _: next(doc,B.fail(w,866))

@unsafe
def fields_write(-A: Type,doc: R.Document,fields: List<&2,M.Field>,w: B.Writer,+depth: U32,next: R.Document -> B.Writer -> A) -> A:
  match fields:
    case Nil{}: next(doc,B.byte(w,0))
    case M.Field{id,+tag} <> rest: string(A,doc,id,B.byte(w,M.int(M.item(tag,0),0)),doc => w =>
      write(A,doc,tag,w,U32.inc(depth),doc => w => fields_write(A,doc,rest,w,depth,next)))

@unsafe
def list(-A: Type,doc: R.Document,+value: M.Node,w: B.Writer,+depth: U32,next: R.Document -> B.Writer -> A) -> A:
  list_valid(A,Bool.and(U32.is_eq(M.count(value),2),U32.is_le(M.int(M.item(value,0),4294967295),12)),doc,M.int(M.item(value,0),0),M.item(value,1),w,depth,next)

@unsafe
def list_valid(-A: Type,valid: Bool,doc: R.Document,+kind: U32,+xs: M.Node,w: B.Writer,+depth: U32,next: R.Document -> B.Writer -> A) -> A:
  match valid xs:
    case True{} M.Array{_,_}: list_items(A,doc,xs,0,M.count(xs),kind,B.int(B.byte(w,kind),M.count(xs)),depth,next)
    case _ _: next(doc,B.fail(w,867))

@unsafe
def list_items(-A: Type,doc: R.Document,+xs: M.Node,+i: U32,n: U32,+kind: U32,w: B.Writer,+depth: U32,next: R.Document -> B.Writer -> A) -> A:
  match n:
    case 0: next(doc,w)
    case _: list_item(A,doc,M.item(xs,i),kind,w,depth,doc => w => list_items(A,doc,xs,U32.inc(i),U32.sub(n,1),kind,w,depth,next))

@unsafe
def list_item(-A: Type,doc: R.Document,+item: M.Node,+kind: U32,w: B.Writer,+depth: U32,next: R.Document -> B.Writer -> A) -> A:
  list_same(A,U32.is_eq(kind,M.int(M.item(item,0),4294967295)),doc,item,kind,w,depth,next)

@unsafe
def list_same(-A: Type,same: Bool,doc: R.Document,+item: M.Node,kind: U32,w: B.Writer,+depth: U32,next: R.Document -> B.Writer -> A) -> A:
  match same kind:
    case True{} _: write(A,doc,item,w,U32.inc(depth),next)
    case False{} 10: write(A,doc,item,B.short(B.byte(w,M.int(M.item(item,0),0)),0),U32.inc(depth),doc => w => next(doc,B.byte(w,0)))
    case False{} _: next(doc,B.fail(w,868))
