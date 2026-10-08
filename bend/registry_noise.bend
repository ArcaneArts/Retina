import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./noise.bend as N

# Read the loaded projection in Bend, then reuse a small Data octave stack on
# the GPU. Java does not choose parameters, convert numbers or evaluate noise.
# Channels 0..3 are the four climate stacks; 4 is the registered ridge stack.
@unsafe
def modifiers(-A: Type,n: Nat,doc: P.Document,+value: P.Value,+i: U32,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match n:
    case 0n: next(doc,Done{List.reverse(&2,F32,acc)})
    case 1n+p: P.item(A,doc,value,i,doc => item => modifier(A,V.number(item),p,doc,value,U32.inc(i),acc,next))

@unsafe
def modifier(-A: Type,result: Result<&1,&1,U32,F32>,n: Nat,doc: P.Document,value: P.Value,i: U32,acc: List<&2,F32>,next: P.Document -> Result<&1,&1,U32,List<&2,F32>> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{x}: modifiers(A,n,doc,value,i,x <> acc,next)

def prepared(-A: Type,result: Result<&1,&1,U32,List<&2,F32>>,doc: P.Document,freq: F32,amp: F32,channel: U32,low: U32,high: U32,next: P.Document -> Result<&1,&1,U32,N.Stack> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{values}: next(doc,N.prepare(freq,amp,channel,low,high,values))

def modifier_count(-A: Type,valid: Bool,doc: P.Document,value: P.Value,count: U32,freq: F32,amp: F32,channel: U32,low: U32,high: U32,next: P.Document -> Result<&1,&1,U32,N.Stack> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{710})
    case True{}: modifiers(A,U32.to_nat(count),doc,value,0,[],doc => result => prepared(A,result,doc,freq,amp,channel,low,high,next))

def modifier_shape(-A: Type,+value: P.Value,doc: P.Document,freq: F32,amp: F32,channel: U32,low: U32,high: U32,next: P.Document -> Result<&1,&1,U32,N.Stack> -> A) -> A:
  match value:
    case P.Value{+tag,+count,_,_}: modifier_count(A,Bool.and(Bool.or(U32.is_eq(tag,6),Bool.and(U32.is_ge(tag,8),U32.is_le(tag,10))),
      Bool.and(U32.is_gt(count,0),U32.is_le(count,32))),doc,value,count,freq,amp,channel,low,high,next)

def modifier_value(-A: Type,value: Maybe<&2,P.Value>,doc: P.Document,freq: F32,amp: F32,channel: U32,low: U32,high: U32,next: P.Document -> Result<&1,&1,U32,N.Stack> -> A) -> A:
  match value:
    case Some{+value}: modifier_shape(A,value,doc,freq,amp,channel,low,high,next)
    case _: next(doc,Fail{710})

def read_amplitude(-A: Type,result: Result<&1,&1,U32,F32>,doc: P.Document,value: P.Value,freq: F32,channel: U32,low: U32,high: U32,next: P.Document -> Result<&1,&1,U32,N.Stack> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{amp}: V.field(A,doc,value,"modifiers",doc => modifiers => modifier_value(A,modifiers,doc,freq,amp,channel,low,high,next))

def frequency(-A: Type,result: Result<&1,&1,U32,F32>,doc: P.Document,+value: P.Value,channel: U32,low: U32,high: U32,next: P.Document -> Result<&1,&1,U32,N.Stack> -> A) -> A:
  match result:
    case Fail{code}: next(doc,Fail{code})
    case Done{freq}: V.field(A,doc,value,"amplitude",doc => amp_value => read_amplitude(A,V.number(amp_value),doc,value,freq,channel,low,high,next))

def noise(-A: Type,value: Maybe<&2,P.Value>,doc: P.Document,channel: U32,low: U32,high: U32,next: P.Document -> Result<&1,&1,U32,N.Stack> -> A) -> A:
  match value:
    case Some{P.Value{7,+count,b,at}}:
      +value : P.Value = P.Value{7,count,b,at}
      V.field(A,doc,value,"frequency",doc => freq => frequency(A,V.number(freq),doc,value,channel,low,high,next))
    case _: next(doc,Fail{710})

def noise_channel(-A: Type,value: Maybe<&2,P.Value>,doc: P.Document,+channel: U32,low: U32,high: U32,next: P.Document -> Result<&1,&1,U32,N.Stack> -> A) -> A:
  match value:
    case Some{P.Value{6,4,b,at}}: P.item(A,doc,P.Value{6,4,b,at},channel,doc => value => noise(A,value,doc,channel,low,high,next))
    case _: next(doc,Fail{710})

def selected(-A: Type,ridge: Bool,doc: P.Document,root: P.Value,channel: U32,low: U32,high: U32,next: P.Document -> Result<&1,&1,U32,N.Stack> -> A) -> A:
  match ridge:
    case True{}: V.field(A,doc,root,"weirdness_noise",doc => value => noise(A,value,doc,channel,low,high,next))
    case False{}: V.field(A,doc,root,"noises",doc => value => noise_channel(A,value,doc,channel,low,high,next))

def prepare(-A: Type,doc: P.Document,+channel: U32,low: U32,high: U32,next: P.Document -> Result<&1,&1,U32,N.Stack> -> A) -> A:
  V.root(A,doc,doc => root => selected(A,U32.is_eq(channel,4),doc,root,channel,low,high,next))
