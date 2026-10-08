import Base
import ./compression.bend as Z
import ./binary.bend as B
import ./reader.bend as R
import ./transport.bend as T

# Lossless structural registry wire, v1. Numbers retain exact i64/f64 bits;
# homogeneous arrays occupy contiguous raw words rather than one node per item.
# Strings retain UTF-16 code units (including NUL and unpaired surrogates).
# No world generation or native/Java codec is involved in this decoder.
type Document is Type:
  Document{words: Array<U32>,count: U32,strings: U32,tape: U32,offsets: Array<U32>,hash: U32}

def get(-A: Type,doc: Document,index: U32,next: Document -> U32 -> A) -> A:
  match doc:
    case Document{a,count,strings,tape,offsets,hash}:
      Z.read(A,a,index,a => word => next(Document{a,count,strings,tape,offsets,hash},word))

def info(-A: Type,doc: Document,next: Document -> U32 -> U32 -> U32 -> U32 -> A) -> A:
  match doc:
    case Document{a,+count,+strings,+tape,offsets,+hash}:
      next(Document{a,count,strings,tape,offsets,hash},count,strings,tape,hash)

@unsafe
def capacity(+count: U32,+size: U32,depth: Nat) -> Nat:
  capacity_more(U32.is_gt(count,size),count,size,depth)

@unsafe
def capacity_more(more: Bool,count: U32,size: U32,depth: Nat) -> Nat:
  match more:
    case False{}: depth
    case True{}: capacity(count,U32.mul(size,2),Nat.add(depth,1n))

# Dictionary offsets allow O(log n) owned-array access; no string DOM is built.
@unsafe
def dictionary(-A: Type,doc: Document,+i: U32,+position: U32,next: Document -> Bool -> A) -> A:
  info(A,doc,doc => count => +strings => +tape => hash =>
    dictionary_more(A,U32.is_lt(i,strings),doc,i,position,tape,strings,next))

@unsafe
def dictionary_more(-A: Type,more: Bool,doc: Document,+i: U32,+position: U32,+end: U32,strings: U32,next: Document -> Bool -> A) -> A:
  match more:
    case False{}: next(doc,U32.is_eq(position,end))
    case True{}: dictionary_room(A,U32.is_lt(position,end),doc,i,position,end,strings,next)

@unsafe
def dictionary_room(-A: Type,room: Bool,doc: Document,+i: U32,+position: U32,+end: U32,strings: U32,next: Document -> Bool -> A) -> A:
  match room:
    case False{}: next(doc,False{})
    case True{}:
      get(A,doc,position,doc => +length => dictionary_length(A,
        Bool.and(U32.is_le(length,67108864),U32.is_le(U32.add(1,U32.div(U32.inc(length),2)),U32.sub(end,position))),
        doc,i,position,end,strings,length,next))

@unsafe
def dictionary_length(-A: Type,valid: Bool,doc: Document,+i: U32,+position: U32,end: U32,strings: U32,+length: U32,next: Document -> Bool -> A) -> A:
  match valid:
    case False{}: next(doc,False{})
    case True{}:
      +after = U32.add(position,U32.add(1,U32.div(U32.inc(length),2)))
      dictionary_padding(A,U32.is_eq(U32.and(length,1),1),doc,i,position,after,end,strings,next)

@unsafe
def dictionary_padding(-A: Type,odd: Bool,doc: Document,i: U32,position: U32,+after: U32,end: U32,strings: U32,next: Document -> Bool -> A) -> A:
  match odd:
    case False{}: dictionary_saved(A,True{},doc,i,position,after,end,strings,next)
    case True{}: get(A,doc,U32.sub(after,1),doc => last =>
      dictionary_saved(A,U32.is_zero(U32.and(last,65535)),doc,i,position,after,end,strings,next))

@unsafe
def dictionary_saved(-A: Type,valid: Bool,doc: Document,+i: U32,position: U32,after: U32,end: U32,strings: U32,next: Document -> Bool -> A) -> A:
  match valid doc:
    case False{} _: next(doc,False{})
    case True{} Document{a,count,n,tape,offsets,hash}:
      dictionary(A,Document{a,count,n,tape,Array.set(U32,offsets,i,position),hash},U32.inc(i),after,next)

@unsafe
def walk(-A: Type,doc: Document,+at: U32,+limit: U32,+depth: U32,next: Document -> U32 -> Bool -> A) -> A:
  walk_room(A,Bool.and(U32.is_le(depth,128),Bool.and(U32.is_le(at,limit),U32.is_ge(U32.sub(limit,at),2))),doc,at,limit,depth,next)

@unsafe
def walk_room(-A: Type,room: Bool,doc: Document,+at: U32,+limit: U32,depth: U32,next: Document -> U32 -> Bool -> A) -> A:
  match room:
    case False{}: next(doc,at,False{})
    case True{}: get(A,doc,at,doc => tag => get(A,doc,U32.inc(at),doc => +span =>
      node_valid(A,Bool.and(U32.is_ge(span,2),U32.is_le(span,U32.sub(limit,at))),doc,at,span,tag,depth,next)))

@unsafe
def node_valid(-A: Type,valid: Bool,doc: Document,+at: U32,+span: U32,tag: U32,depth: U32,next: Document -> U32 -> Bool -> A) -> A:
  match valid:
    case False{}: next(doc,at,False{})
    case True{}: node(A,doc,at,U32.add(at,span),span,tag,depth,next)

@unsafe
def node(-A: Type,doc: Document,+at: U32,+end: U32,+span: U32,tag: U32,depth: U32,next: Document -> U32 -> Bool -> A) -> A:
  match tag:
    case 0: next(doc,end,U32.is_eq(span,2))
    case 1: next(doc,end,U32.is_eq(span,2))
    case 2: next(doc,end,U32.is_eq(span,2))
    case 3: next(doc,end,U32.is_eq(span,4))
    case 4: real_scalar(A,U32.is_eq(span,4),doc,at,end,next)
    case 5: string_scalar(A,U32.is_eq(span,3),doc,at,end,next)
    case 6: container(A,U32.is_ge(span,3),doc,at,end,False{},depth,next)
    case 7: container(A,U32.is_ge(span,3),doc,at,end,True{},depth,next)
    case 8: packed(A,U32.is_ge(span,3),doc,at,end,span,8,next)
    case 9: packed(A,U32.is_ge(span,3),doc,at,end,span,9,next)
    case 10: packed(A,U32.is_ge(span,3),doc,at,end,span,10,next)
    case 11: packed(A,U32.is_ge(span,3),doc,at,end,span,11,next)
    case _: next(doc,end,False{})

def finite(high: U32) -> Bool:
  Bool.not(U32.is_eq(U32.and(high,2146435072),2146435072))

def real_scalar(-A: Type,valid: Bool,doc: Document,+at: U32,end: U32,next: Document -> U32 -> Bool -> A) -> A:
  match valid:
    case False{}: next(doc,end,False{})
    case True{}: get(A,doc,U32.add(at,2),doc => high => next(doc,end,finite(high)))

def string_scalar(-A: Type,valid: Bool,doc: Document,+at: U32,end: U32,next: Document -> U32 -> Bool -> A) -> A:
  match valid:
    case False{}: next(doc,end,False{})
    case True{}: info(A,doc,doc => count => strings => tape => hash =>
      get(A,doc,U32.add(at,2),doc => id => next(doc,end,U32.is_lt(id,strings))))

@unsafe
def container(-A: Type,valid: Bool,doc: Document,+at: U32,end: U32,object: Bool,depth: U32,next: Document -> U32 -> Bool -> A) -> A:
  match valid:
    case False{}: next(doc,end,False{})
    case True{}: get(A,doc,U32.add(at,2),doc => count => children(A,doc,U32.add(at,3),end,count,object,depth,next))

@unsafe
def children(-A: Type,doc: Document,+at: U32,+end: U32,+count: U32,+object: Bool,+depth: U32,next: Document -> U32 -> Bool -> A) -> A:
  children_more(A,U32.is_gt(count,0),doc,at,end,count,object,depth,next)

@unsafe
def children_more(-A: Type,more: Bool,doc: Document,+at: U32,+end: U32,count: U32,+object: Bool,depth: U32,next: Document -> U32 -> Bool -> A) -> A:
  match more:
    case False{}: next(doc,end,U32.is_eq(at,end))
    case True{}: child_room(A,U32.is_lt(at,end),doc,at,end,count,object,depth,next)

@unsafe
def child_room(-A: Type,room: Bool,doc: Document,+at: U32,end: U32,count: U32,object: Bool,depth: U32,next: Document -> U32 -> Bool -> A) -> A:
  match room object:
    case False{} _: next(doc,end,False{})
    case True{} False{}: child(A,True{},doc,at,end,count,False{},depth,next)
    case True{} True{}:
      info(A,doc,doc => size => strings => tape => hash => get(A,doc,at,doc => key =>
        child(A,U32.is_lt(key,strings),doc,U32.inc(at),end,count,True{},depth,next)))

@unsafe
def child(-A: Type,valid: Bool,doc: Document,at: U32,+end: U32,+count: U32,+object: Bool,+depth: U32,next: Document -> U32 -> Bool -> A) -> A:
  match valid:
    case False{}: next(doc,end,False{})
    case True{}: walk(A,doc,at,end,U32.inc(depth),doc => after => valid =>
      child_done(A,valid,doc,after,end,U32.sub(count,1),object,depth,next))

@unsafe
def child_done(-A: Type,valid: Bool,doc: Document,at: U32,end: U32,count: U32,object: Bool,depth: U32,next: Document -> U32 -> Bool -> A) -> A:
  match valid:
    case False{}: next(doc,end,False{})
    case True{}: children(A,doc,at,end,count,object,depth,next)

@unsafe
def packed(-A: Type,valid: Bool,doc: Document,+at: U32,end: U32,span: U32,tag: U32,next: Document -> U32 -> Bool -> A) -> A:
  match valid:
    case False{}: next(doc,end,False{})
    case True{}: get(A,doc,U32.add(at,2),doc => count => packed_count(A,doc,at,end,span,tag,count,next))

@unsafe
def packed_count(-A: Type,doc: Document,+at: U32,+end: U32,+span: U32,tag: U32,+count: U32,next: Document -> U32 -> Bool -> A) -> A:
  match tag:
    case 8: next(doc,end,U32.is_eq(count,U32.sub(span,3)))
    case 9: next(doc,end,Bool.and(U32.is_le(count,33554432),U32.is_eq(U32.mul(count,2),U32.sub(span,3))))
    case 10: packed_real(A,Bool.and(U32.is_le(count,33554432),U32.is_eq(U32.mul(count,2),U32.sub(span,3))),doc,U32.add(at,3),end,count,next)
    case _: packed_bool(A,Bool.and(U32.is_le(count,1073741824),U32.is_eq(U32.div(U32.add(count,31),32),U32.sub(span,3))),doc,end,count,next)

@unsafe
def packed_real(-A: Type,valid: Bool,doc: Document,+at: U32,+end: U32,+count: U32,next: Document -> U32 -> Bool -> A) -> A:
  match valid:
    case False{}: next(doc,end,False{})
    case True{}: packed_real_more(A,U32.is_gt(count,0),doc,at,end,count,next)

@unsafe
def packed_real_more(-A: Type,more: Bool,doc: Document,+at: U32,end: U32,+count: U32,next: Document -> U32 -> Bool -> A) -> A:
  match more:
    case False{}: next(doc,end,True{})
    case True{}: get(A,doc,at,doc => high => packed_real(A,finite(high),doc,U32.add(at,2),end,U32.sub(count,1),next))

def bool_padding(-A: Type,partial: Bool,doc: Document,+end: U32,bits: U32,next: Document -> U32 -> Bool -> A) -> A:
  match partial:
    case False{}: next(doc,end,True{})
    case True{}: get(A,doc,U32.sub(end,1),doc => last => next(doc,end,U32.is_zero(U32.shrn(last,U32.to_nat(bits)))))

def packed_bool(-A: Type,valid: Bool,doc: Document,+end: U32,+count: U32,next: Document -> U32 -> Bool -> A) -> A:
  match valid:
    case False{}: next(doc,end,False{})
    case True{}: bool_padding(A,U32.is_gt(U32.mod(count,32),0),doc,end,U32.mod(count,32),next)

def validated(valid: Bool,doc: Document) -> Result<&1,&1,U32,Document>:
  match valid:
    case False{}: Fail{701}
    case True{}: Done{doc}

def validate_tape(valid: Bool,doc: Document) -> Result<&1,&1,U32,Document>:
  match valid:
    case False{}: Fail{701}
    case True{}: info(Result<&1,&1,U32,Document>,doc,doc => +count => strings => tape => hash =>
      walk(Result<&1,&1,U32,Document>,doc,tape,count,0,doc => end => valid => validated(Bool.and(valid,U32.is_eq(end,count)),doc)))

def validate(doc: Document) -> Result<&1,&1,U32,Document>:
  dictionary(Result<&1,&1,U32,Document>,doc,0,5,doc => valid => validate_tape(valid,doc))

# A profile file is untrusted input. Unlike a broken IPC frame, a failed file
# read rejects just this upload and retains the previous resident profile.
type Piece is Type:
  Piece{file: File,status: Result<&1,&1,U32,Z.Bytes>}

@unsafe
def file_exact(file: File,+remaining: U32,w: B.Writer) -> IO(Piece):
  file_more(U32.is_gt(remaining,0),file,remaining,w)

@unsafe
def file_more(more: Bool,file: File,+remaining: U32,w: B.Writer) -> IO(Piece):
  match more:
    case False{}: IO.pure(Piece,Piece{file,B.done(w)})
    case True{}:
      do IO<Piece>:
        pair : File & Result<&1,&1,U32 & String,List<&2,U32>> <- File.read_bytes(file,U32.min(remaining,65536))
        file_received(pair,remaining,w)

@unsafe
def file_received(pair: File & Result<&1,&1,U32 & String,List<&2,U32>>,remaining: U32,w: B.Writer) -> IO(Piece):
  (file,status) = pair
  file_status(status,file,remaining,w)

@unsafe
def file_status(status: Result<&1,&1,U32 & String,List<&2,U32>>,file: File,remaining: U32,w: B.Writer) -> IO(Piece):
  match status:
    case Fail{_}: IO.pure(Piece,Piece{file,Fail{704}})
    case Done{xs}: file_bytes(xs,file,remaining,w)

@unsafe
def file_bytes(xs: List<&2,U32>,file: File,remaining: U32,w: B.Writer) -> IO(Piece):
  match xs:
    case Nil{}: IO.pure(Piece,Piece{file,Fail{703}})
    case byte <> rest: file_appended(T.append(byte <> rest,w,0),file,remaining)

@unsafe
def file_appended(pair: B.Writer & U32,file: File,+remaining: U32) -> IO(Piece):
  (w,count) = pair
  file_exact(file,U32.sub(remaining,count),w)

# Load large profiles in 64 KiB pieces. The protocol's per-command 16 MiB limit
# stays intact; a staged registry file may be up to 128 MiB of compact words.
type Loaded is Type:
  Loaded{file: File,words: Array<U32>,hash: U32}
  LoadFail{file: File,code: U32}

def append(n: Nat,cursor: R.Cursor,a: Array<U32>,+position: U32,+hash: U32,next: Array<U32> -> U32 -> Loaded) -> Loaded:
  match n:
    case 0n: next(a,hash)
    case 1n+p: R.word(Loaded,cursor,cursor => +word => append(p,cursor,Array.set(U32,a,position,word),
      U32.inc(position),U32.mul(U32.xor(hash,word),16777619),next))

@unsafe
def read(file: File,a: Array<U32>,+position: U32,+remaining: U32,hash: U32) -> IO(Loaded):
  read_more(U32.is_gt(remaining,0),file,a,position,remaining,hash)

@unsafe
def read_more(more: Bool,file: File,a: Array<U32>,+position: U32,+remaining: U32,hash: U32) -> IO(Loaded):
  match more:
    case False{}: IO.pure(Loaded,Loaded{file,a,hash})
    case True{}:
      do IO<Loaded>:
        chunk : Piece <- file_exact(file,U32.mul(U32.min(remaining,16384),4),B.new())
        read_chunk(chunk,a,position,remaining,hash)

@unsafe
def read_chunk(chunk: Piece,a: Array<U32>,+position: U32,+remaining: U32,hash: U32) -> IO(Loaded):
  match chunk:
    case Piece{file,Fail{code}}: IO.pure(Loaded,LoadFail{file,code})
    case Piece{file,Done{bytes}}:
      read_appended(append(U32.to_nat(U32.min(remaining,16384)),R.start(bytes),a,position,hash,
        a => hash => Loaded{file,a,hash}),U32.add(position,U32.min(remaining,16384)),U32.sub(remaining,U32.min(remaining,16384)))

@unsafe
def read_appended(loaded: Loaded,position: U32,remaining: U32) -> IO(Loaded):
  match loaded:
    case LoadFail{file,code}: IO.pure(Loaded,LoadFail{file,code})
    case Loaded{file,a,hash}: read(file,a,position,remaining,hash)

def eof_bytes(bytes: List<&2,U32>,a: Array<U32>,count: U32,+strings: U32,tape: U32,hash: U32) -> IO(Result<&1,&1,U32,Document>):
  match bytes:
    case Nil{}: IO.pure(Result<&1,&1,U32,Document>,validate(Document{a,count,strings,tape,Array.new(U32,capacity(strings,1,0n),0),hash}))
    case _ <> _: IO.pure(Result<&1,&1,U32,Document>,Fail{701})

def eof_status(status: Result<&1,&1,U32 & String,List<&2,U32>>,a: Array<U32>,count: U32,strings: U32,tape: U32,hash: U32) -> IO(Result<&1,&1,U32,Document>):
  match status:
    case Fail{_}: IO.pure(Result<&1,&1,U32,Document>,Fail{704})
    case Done{bytes}: eof_bytes(bytes,a,count,strings,tape,hash)

def eof(pair: File & Result<&1,&1,U32 & String,List<&2,U32>>,a: Array<U32>,count: U32,strings: U32,tape: U32,hash: U32) -> IO(Result<&1,&1,U32,Document>):
  (file,status) = pair
  do IO<Result<&1,&1,U32,Document>>:
    _ : Unit <- File.close(file)
    eof_status(status,a,count,strings,tape,hash)

def read_done(loaded: Loaded,count: U32,strings: U32,tape: U32) -> IO(Result<&1,&1,U32,Document>):
  match loaded:
    case LoadFail{file,code}:
      do IO<Result<&1,&1,U32,Document>>:
        _ : Unit <- File.close(file)
        IO.pure(Result<&1,&1,U32,Document>,Fail{code})
    case Loaded{file,a,hash}:
      do IO<Result<&1,&1,U32,Document>>:
        pair : File & Result<&1,&1,U32 & String,List<&2,U32>> <- File.read_bytes(file,1)
        eof(pair,a,count,strings,tape,hash)

def header_valid(valid: Bool,file: File,+strings: U32,+dictionary: U32,+tape: U32) -> IO(Result<&1,&1,U32,Document>):
  match valid:
    case False{}:
      do IO<Result<&1,&1,U32,Document>>:
        _ : Unit <- File.close(file)
        IO.pure(Result<&1,&1,U32,Document>,Fail{700})
    case True{}:
      do IO<Result<&1,&1,U32,Document>>:
        loaded : Loaded <- read(file,Array.new(U32,capacity(U32.add(5,U32.add(dictionary,tape)),1,0n),0),5,U32.add(dictionary,tape),2166136261)
        read_done(loaded,U32.add(5,U32.add(dictionary,tape)),strings,U32.add(5,dictionary))

def header_checks(magic: U32,version: U32,+strings: U32,+dictionary: U32,+tape: U32) -> Bool:
  Bool.and(Bool.and(U32.is_eq(magic,1380077617),U32.is_eq(version,1)),
    Bool.and(U32.is_le(strings,1048576),Bool.and(U32.is_le(dictionary,33554425),
      Bool.and(U32.is_ge(tape,2),U32.is_le(tape,U32.sub(33554427,dictionary))))))

def header(read: Piece) -> IO(Result<&1,&1,U32,Document>):
  match read:
    case Piece{file,Fail{code}}:
      do IO<Result<&1,&1,U32,Document>>:
        _ : Unit <- File.close(file)
        IO.pure(Result<&1,&1,U32,Document>,Fail{code})
    case Piece{file,Done{bytes}}:
      R.word(IO(Result<&1,&1,U32,Document>),R.start(bytes),cursor => magic =>
      R.word(IO(Result<&1,&1,U32,Document>),cursor,cursor => version =>
      R.word(IO(Result<&1,&1,U32,Document>),cursor,cursor => +strings =>
      R.word(IO(Result<&1,&1,U32,Document>),cursor,cursor => +dictionary =>
      R.word(IO(Result<&1,&1,U32,Document>),cursor,cursor => +tape =>
        header_valid(Bool.and(R.end(cursor),header_checks(magic,version,strings,dictionary,tape)),file,strings,dictionary,tape))))))

def opened(status: Result<&1,&1,U32 & String,File>) -> IO(Result<&1,&1,U32,Document>):
  match status:
    case Fail{_}: IO.pure(Result<&1,&1,U32,Document>,Fail{702})
    case Done{file}:
      do IO<Result<&1,&1,U32,Document>>:
        read : Piece <- file_exact(file,20,B.new())
        header(read)

def load(path: String) -> IO(Result<&1,&1,U32,Document>):
  do IO<Result<&1,&1,U32,Document>>:
    status : Result<&1,&1,U32 & String,File> <- File.open(path,"r")
    opened(status)

# Accessors operate on validated documents and retain owned storage. A Value is
# cheap Data: scalar bits or a container's count and tape position. Packed arrays
# are indexed directly without expanding their elements into nodes.
type Value is Data:
  Value{tag: U32,a: U32,b: U32,at: U32}

def value_kind(-A: Type,tag: U32,doc: Document,+at: U32,next: Document -> Value -> A) -> A:
  match tag:
    case 0: next(doc,Value{0,0,0,at})
    case 1: next(doc,Value{1,0,0,at})
    case 2: next(doc,Value{2,0,0,at})
    case 3: get(A,doc,U32.add(at,2),doc => high => get(A,doc,U32.add(at,3),doc => low => next(doc,Value{3,high,low,at})))
    case 4: get(A,doc,U32.add(at,2),doc => high => get(A,doc,U32.add(at,3),doc => low => next(doc,Value{4,high,low,at})))
    case _: get(A,doc,U32.add(at,2),doc => count => next(doc,Value{tag,count,0,at}))

def value(-A: Type,doc: Document,+at: U32,next: Document -> Value -> A) -> A:
  get(A,doc,at,doc => tag => value_kind(A,tag,doc,at,next))

@unsafe
def field_next(-A: Type,more: Bool,doc: Document,+at: U32,+count: U32,+key: U32,next: Document -> Maybe<&2,Value> -> A) -> A:
  match more:
    case False{}: next(doc,None{})
    case True{}: get(A,doc,at,doc => found => field_found(A,U32.is_eq(found,key),doc,U32.inc(at),count,key,next))

@unsafe
def field_found(-A: Type,found: Bool,doc: Document,+at: U32,+count: U32,key: U32,next: Document -> Maybe<&2,Value> -> A) -> A:
  match found:
    case True{}: value(A,doc,at,doc => v => next(doc,Some{v}))
    case False{}: get(A,doc,U32.inc(at),doc => span => field_next(A,U32.is_gt(count,1),doc,U32.add(at,span),U32.sub(count,1),key,next))

def field(-A: Type,doc: Document,v: Value,key: U32,next: Document -> Maybe<&2,Value> -> A) -> A:
  match v:
    case Value{7,+count,_,at}: field_next(A,U32.is_gt(count,0),doc,U32.add(at,3),count,key,next)
    case _: next(doc,None{})

@unsafe
def generic_item(-A: Type,zero: Bool,doc: Document,+at: U32,+index: U32,next: Document -> Maybe<&2,Value> -> A) -> A:
  match zero:
    case True{}: value(A,doc,at,doc => v => next(doc,Some{v}))
    case False{}: get(A,doc,U32.inc(at),doc => span => generic_item(A,U32.is_eq(index,1),doc,U32.add(at,span),U32.sub(index,1),next))

def item_valid(-A: Type,valid: Bool,doc: Document,tag: U32,+at: U32,+index: U32,next: Document -> Maybe<&2,Value> -> A) -> A:
  match valid tag:
    case False{} _: next(doc,None{})
    case True{} 6: generic_item(A,U32.is_zero(index),doc,U32.add(at,3),index,next)
    case True{} 8: get(A,doc,U32.add(U32.add(at,3),index),doc => +bits => next(doc,Some{Value{3,Z.word(U32.is_ge(bits,2147483648),4294967295,0),bits,0}}))
    case True{} 9: get(A,doc,U32.add(U32.add(at,3),U32.mul(index,2)),doc => high =>
      get(A,doc,U32.add(U32.add(at,4),U32.mul(index,2)),doc => low => next(doc,Some{Value{3,high,low,0}})))
    case True{} 10: get(A,doc,U32.add(U32.add(at,3),U32.mul(index,2)),doc => high =>
      get(A,doc,U32.add(U32.add(at,4),U32.mul(index,2)),doc => low => next(doc,Some{Value{4,high,low,0}})))
    case True{} 11: get(A,doc,U32.add(U32.add(at,3),U32.div(index,32)),doc => bits =>
      next(doc,Some{Value{U32.add(1,U32.and(U32.shrn(bits,U32.to_nat(U32.mod(index,32))),1)),0,0,0}}))
    case True{} _: next(doc,None{})

def item(-A: Type,doc: Document,v: Value,+index: U32,next: Document -> Maybe<&2,Value> -> A) -> A:
  match v:
    case Value{tag,+count,_,at}: item_valid(A,U32.is_lt(index,count),doc,tag,at,index,next)

type StringRef is Data:
  StringRef{offset: U32,length: U32}

# Offsets and packed UTF-16 can be consumed without lossy text conversion.
def string_valid(-A: Type,valid: Bool,doc: Document,id: U32,next: Document -> Maybe<&2,StringRef> -> A) -> A:
  match valid doc:
    case False{} _: next(doc,None{})
    case True{} Document{a,count,strings,tape,offsets,hash}:
      Z.read(A,offsets,id,offsets => +offset => get(A,Document{a,count,strings,tape,offsets,hash},offset,
        doc => length => next(doc,Some{StringRef{U32.inc(offset),length}})))

def string(-A: Type,doc: Document,+id: U32,next: Document -> Maybe<&2,StringRef> -> A) -> A:
  match doc:
    case Document{a,count,+strings,tape,offsets,hash}:
      string_valid(A,U32.is_lt(id,strings),Document{a,count,strings,tape,offsets,hash},id,next)
