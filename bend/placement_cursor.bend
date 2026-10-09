import Base
import ./placement_data.bend as P
import ./word64.bend as W
import ./noise.bend as N

# A lazy family holds one continuation, not one allocation per future child.
type Entry is Data:
  Entry{recipe: U32,group: U32,index: U32,at: P.Position,state: W.U64,path: List<&2,U32>}
type TaskKind is Data:
  Step{}
  Count{remaining: U32,ordinal: U32}
  Cuboid{width: U32,height: U32,length: U32,cell: U32,ordinal: U32,edges: Bool,interior: Bool}
  Layer{layer: U32,attempt: U32,found: Bool}
type Task is Data:
  Task{entry: Entry,kind: TaskKind}
type Heap is Data:
  Empty{}
  Node{rank: U32,size: U32,task: Task,left: Heap,right: Heap}
type CacheKey is Data:
  CacheKey{group: U32,index: U32,path: List<&2,U32>}
type Cache is Data:
  Missing{}
  Stored{key: CacheKey,value: Maybe<&2,P.Position>,priority: U32,left: Cache,right: Cache}
type Cursor is Data:
  Cursor{queue: Heap,cache: Cache,steps: U32}
type Candidate is Data:
  Candidate{recipe: U32,group: U32,at: P.Position,state: W.U64,path: List<&2,U32>}
type Outcome is Data:
  Yield{cursor: Cursor,candidate: Candidate}
  Paused{cursor: Cursor}
  Exhausted{cursor: Cursor}
type Action is Data:
  Work{cursor: Cursor}
  CandidateReady{cursor: Cursor,candidate: Candidate}

@unsafe
def append(xs: List<&2,U32>,+value: U32) -> List<&2,U32>:
  match xs:
    case Nil{}: [value]
    case x <> rest: x <> append(rest,value)

@unsafe
def path_equal(xs: List<&2,U32>,ys: List<&2,U32>) -> Bool:
  match xs ys:
    case Nil{} Nil{}: True{}
    case x <> a y <> b: Bool.and(U32.is_eq(x,y),path_equal(a,b))
    case _ _: False{}

@unsafe
def path_less(xs: List<&2,U32>,ys: List<&2,U32>) -> Bool:
  match xs ys:
    case Nil{} _ <> _: True{}
    case +x <> a +y <> b: Bool.or(U32.is_lt(x,y),Bool.and(U32.is_eq(x,y),path_less(a,b)))
    case _ _: False{}

@unsafe
def path(+task: Task) -> List<&2,U32>:
  match task:
    case Task{Entry{_,_,_,_,_,p},Step{}}: p
    case Task{Entry{_,_,_,_,_,p},Count{_,i}}: append(p,i)
    case Task{Entry{_,_,_,_,_,p},Cuboid{_,_,_,_,i,_,_}}: append(p,i)
    case Task{Entry{_,_,_,_,_,p},Layer{l,i,_}}: append(append(p,l),i)

def priority(+kind: TaskKind) -> U32:
  match kind:
    case Layer{_,_,_}: 0
    case Step{}: 2
    case _: 1

@unsafe
def less(+a: Task,+b: Task) -> Bool:
  match a b:
    case Task{Entry{ar,+ag,_,_,_,_},+ak} Task{Entry{br,+bg,_,_,_,_},+bk}:
      Bool.or(U32.is_lt(ag,bg),Bool.and(U32.is_eq(ag,bg),Bool.or(path_less(path(a),path(b)),
        Bool.and(path_equal(path(a),path(b)),Bool.or(U32.is_lt(priority(ak),priority(bk)),
          Bool.and(U32.is_eq(priority(ak),priority(bk)),U32.is_lt(ar,br)))))))

def rank(+heap: Heap) -> U32:
  match heap:
    case Empty{}: 0
    case Node{rank,_,_,_,_}: rank

def size(+heap: Heap) -> U32:
  match heap:
    case Empty{}: 0
    case Node{_,size,_,_,_}: size

@unsafe
def balanced(+swapped: Bool,+task: Task,+left: Heap,+right: Heap) -> Heap:
  match swapped:
    case False{}: Node{U32.inc(rank(right)),U32.inc(U32.add(size(left),size(right))),task,left,right}
    case True{}: Node{U32.inc(rank(left)),U32.inc(U32.add(size(left),size(right))),task,right,left}

@unsafe
def node(+task: Task,+left: Heap,+right: Heap) -> Heap:
  balanced(U32.is_lt(rank(left),rank(right)),task,left,right)

@unsafe
def merge_order(+first: Bool,+a: Task,+al: Heap,+ar: Heap,+b: Task,+bl: Heap,+br: Heap) -> Heap:
  match first:
    case True{}: node(a,al,merge(ar,node(b,bl,br)))
    case False{}: node(b,bl,merge(node(a,al,ar),br))

@unsafe
def merge(+a: Heap,+b: Heap) -> Heap:
  match a b:
    case Empty{} _: b
    case _ Empty{}: a
    case Node{_,_,+at,al,ar} Node{_,_,+bt,bl,br}: merge_order(Bool.not(less(bt,at)),at,al,ar,bt,bl,br)

@unsafe
def push(+heap: Heap,+task: Task) -> Heap:
  merge(heap,Node{1,1,task,Empty{},Empty{}})

@unsafe
def key_equal(+a: CacheKey,+b: CacheKey) -> Bool:
  match a b:
    case CacheKey{ag,ai,ap} CacheKey{bg,bi,bp}: Bool.and(U32.is_eq(ag,bg),Bool.and(U32.is_eq(ai,bi),path_equal(ap,bp)))

@unsafe
def key_less(+a: CacheKey,+b: CacheKey) -> Bool:
  match a b:
    case CacheKey{+ag,+ai,ap} CacheKey{+bg,+bi,bp}: Bool.or(U32.is_lt(ag,bg),Bool.and(U32.is_eq(ag,bg),Bool.or(U32.is_lt(ai,bi),Bool.and(U32.is_eq(ai,bi),path_less(ap,bp)))))

@unsafe
def lookup_equal(+same: Bool,+key: CacheKey,+value: Maybe<&2,P.Position>,+left: Cache,+right: Cache,+wanted: CacheKey) -> Maybe<&2,Maybe<&2,P.Position>>:
  match same:
    case True{}: Some{value}
    case False{}: lookup_child(key_less(wanted,key),left,right,wanted)

@unsafe
def lookup_child(+leftward: Bool,+left: Cache,+right: Cache,+wanted: CacheKey) -> Maybe<&2,Maybe<&2,P.Position>>:
  match leftward:
    case True{}: lookup(left,wanted)
    case False{}: lookup(right,wanted)

@unsafe
def lookup(+cache: Cache,+key: CacheKey) -> Maybe<&2,Maybe<&2,P.Position>>:
  match cache:
    case Missing{}: None{}
    case Stored{+stored,value,_,left,right}: lookup_equal(key_equal(key,stored),stored,value,left,right,key)

@unsafe
def path_hash(xs: List<&2,U32>,+state: U32) -> U32:
  match xs:
    case Nil{}: state
    case x <> rest: path_hash(rest,N.hash(U32.xor(state,x)))

@unsafe
def key_hash(+key: CacheKey) -> U32:
  match key:
    case CacheKey{g,i,p}: path_hash(p,N.hash(U32.add(U32.mul(g,7919),U32.mul(i,104729))))

def cache_priority(+cache: Cache) -> U32:
  match cache:
    case Missing{}: 4294967295
    case Stored{_,_,p,_,_}: p

@unsafe
def rotate_left(+rotate: Bool,+key: CacheKey,+value: Maybe<&2,P.Position>,+priority: U32,+left: Cache,+right: Cache) -> Cache:
  match rotate right:
    case True{} Stored{k,v,p,a,b}: Stored{k,v,p,Stored{key,value,priority,left,a},b}
    case _ _: Stored{key,value,priority,left,right}

@unsafe
def rotate_right(+rotate: Bool,+key: CacheKey,+value: Maybe<&2,P.Position>,+priority: U32,+left: Cache,+right: Cache) -> Cache:
  match rotate left:
    case True{} Stored{k,v,p,a,b}: Stored{k,v,p,a,Stored{key,value,priority,b,right}}
    case _ _: Stored{key,value,priority,left,right}

@unsafe
def insert_child(+leftward: Bool,+key: CacheKey,+value: Maybe<&2,P.Position>,+priority: U32,+left: Cache,+right: Cache,+wanted: CacheKey,+new: Maybe<&2,P.Position>) -> Cache:
  match leftward:
    case True{}:
      +updated = insert(left,wanted,new)
      rotate_right(U32.is_lt(cache_priority(updated),priority),key,value,priority,updated,right)
    case False{}:
      +updated = insert(right,wanted,new)
      rotate_left(U32.is_lt(cache_priority(updated),priority),key,value,priority,left,updated)

@unsafe
def insert_equal(+same: Bool,+key: CacheKey,+value: Maybe<&2,P.Position>,+priority: U32,+left: Cache,+right: Cache,+wanted: CacheKey,+new: Maybe<&2,P.Position>) -> Cache:
  match same:
    case True{}: Stored{key,new,priority,left,right}
    case False{}: insert_child(key_less(wanted,key),key,value,priority,left,right,wanted,new)

@unsafe
def insert(+cache: Cache,+key: CacheKey,+value: Maybe<&2,P.Position>) -> Cache:
  match cache:
    case Missing{}: Stored{key,value,key_hash(key),Missing{},Missing{}}
    case Stored{+old,v,p,a,b}: insert_equal(key_equal(old,key),old,v,p,a,b,key,value)

@unsafe
def queued(+cursor: Cursor,+task: Task) -> Cursor:
  match cursor:
    case Cursor{queue,cache,steps}: Cursor{push(queue,task),cache,steps}

@unsafe
def cached(+cursor: Cursor,+key: CacheKey,+value: Maybe<&2,P.Position>) -> Cursor:
  match cursor:
    case Cursor{queue,cache,steps}: Cursor{queue,insert(cache,key,value),steps}

def work(+cursor: Cursor) -> Action:
  Work{cursor}
