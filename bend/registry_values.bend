import Base
import ./registry.bend as P
import ./numbers.bend as N

# Typed projection helpers retain the owned registry tape. Known schema keys
# are ASCII; dictionary values still retain unrestricted lossless UTF-16.
# Resolve keys during preparation, never in the per-coordinate GPU kernel.
def character(-A: Type,doc: P.Document,+at: U32,next: P.Document -> U32 -> A) -> A:
  P.get(A,doc,U32.div(at,2),doc => bits =>
    next(doc,U32.and(U32.shrn(bits,U32.to_nat(U32.mul(U32.sub(1,U32.mod(at,2)),16))),65535)))

@unsafe
def text(-A: Type,doc: P.Document,+at: U32,+expected: String,next: P.Document -> Bool -> A) -> A:
  match expected:
    case SNil{}: next(doc,True{})
    case SCon{Chr{+code},rest}: character(A,doc,at,doc => found =>
      text_equal(A,Bool.and(U32.is_le(code,127),U32.is_eq(code,found)),doc,U32.inc(at),rest,next))

@unsafe
def text_equal(-A: Type,equal: Bool,doc: P.Document,+at: U32,+expected: String,next: P.Document -> Bool -> A) -> A:
  match equal:
    case False{}: next(doc,False{})
    case True{}: text(A,doc,at,expected,next)

@unsafe
def key_next(-A: Type,more: Bool,doc: P.Document,+id: U32,+count: U32,+expected: String,next: P.Document -> Maybe<&2,U32> -> A) -> A:
  match more:
    case False{}: next(doc,None{})
    case True{}: P.string(A,doc,id,doc => reference => key_ref(A,reference,doc,id,count,expected,next))

@unsafe
def key_ref(-A: Type,reference: Maybe<&2,P.StringRef>,doc: P.Document,id: U32,count: U32,+expected: String,next: P.Document -> Maybe<&2,U32> -> A) -> A:
  match reference:
    case None{}: next(doc,None{})
    case Some{P.StringRef{at,length}}: key_length(A,U32.is_eq(length,U32.from_nat(String.length(expected))),doc,id,count,at,expected,next)

@unsafe
def key_length(-A: Type,equal: Bool,doc: P.Document,id: U32,count: U32,at: U32,+expected: String,next: P.Document -> Maybe<&2,U32> -> A) -> A:
  match equal:
    case False{}: key_found(A,False{},doc,id,count,expected,next)
    case True{}: text(A,doc,U32.mul(at,2),expected,doc => equal => key_found(A,equal,doc,id,count,expected,next))

@unsafe
def key_found(-A: Type,found: Bool,doc: P.Document,+id: U32,+count: U32,expected: String,next: P.Document -> Maybe<&2,U32> -> A) -> A:
  match found:
    case True{}: next(doc,Some{id})
    case False{}: key_next(A,U32.is_lt(U32.inc(id),count),doc,U32.inc(id),count,expected,next)

def key(-A: Type,doc: P.Document,expected: String,next: P.Document -> Maybe<&2,U32> -> A) -> A:
  P.info(A,doc,doc => size => +count => tape => hash => key_next(A,U32.is_gt(count,0),doc,0,count,expected,next))

def named(-A: Type,key: Maybe<&2,U32>,doc: P.Document,value: P.Value,next: P.Document -> Maybe<&2,P.Value> -> A) -> A:
  match key:
    case None{}: next(doc,None{})
    case Some{id}: P.field(A,doc,value,id,next)

def field(-A: Type,doc: P.Document,value: P.Value,name: String,next: P.Document -> Maybe<&2,P.Value> -> A) -> A:
  key(A,doc,name,doc => key => named(A,key,doc,value,next))

def root(-A: Type,doc: P.Document,next: P.Document -> P.Value -> A) -> A:
  P.info(A,doc,doc => size => count => tape => hash => P.value(A,doc,tape,next))

def number(value: Maybe<&2,P.Value>) -> Result<&1,&1,U32,F32>:
  match value:
    case Some{P.Value{3,hi,lo,_}}: Done{N.integer(hi,lo)}
    case Some{P.Value{4,hi,lo,_}}: Done{N.double(hi,lo)}
    case _: Fail{710}
