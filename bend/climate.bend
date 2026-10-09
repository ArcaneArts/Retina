import Base

# Registered interval fitness and a balanced spatial index. All computation is
# Bend; callers prepare once, then share immutable Data trees across GPU queries.
# Validate targets before preparation. Preparation forks on the CPU; finish that
# evaluation with an IO effect before a subsequent GPU bang (Bend 2.0.36).
type Axes is Data:
  Axes{temperature: F32,humidity: F32,continentalness: F32,erosion: F32,weirdness: F32,depth: F32}

type Target is Data:
  Target{low: Axes,high: Axes,offset: F32,biome: U32,flags: U32,order: U32}

type Box is Data:
  Box{low: Axes,high: Axes,offset2: F32}

type Tree is Data:
  Empty{}
  Leaf{box: Box,targets: List<&2,Target>}
  Branch{box: Box,left: Tree,right: Tree}

type Model is Data:
  Model{surface: Tree,underground: Tree,shores: Tree}

type Query is Data:
  Query{point: Axes,weighted: Bool,underground: Bool,ocean_penalty: Bool,exclude_shores: Bool}

type Choice is Data:
  Missing{}
  Choice{fitness: F32,biome: U32,order: U32,continentalness: F32}

def finite(x: F32) -> Bool:
  U32.is_ne(U32.and(F32.bits(x),2139095040),2139095040)

def finite_axes(value: Axes) -> Bool:
  match value:
    case Axes{a,b,c,d,e,f}: Bool.and(Bool.and(finite(a),finite(b)),
      Bool.and(Bool.and(finite(c),finite(d)),Bool.and(finite(e),finite(f))))

def ordered(lo: Axes,hi: Axes) -> Bool:
  match lo hi:
    case Axes{a,b,c,d,e,f} Axes{g,h,i,j,k,l}: Bool.and(Bool.and(F32.is_le(a,g),F32.is_le(b,h)),
      Bool.and(Bool.and(F32.is_le(c,i),F32.is_le(d,j)),Bool.and(F32.is_le(e,k),F32.is_le(f,l))))

def valid(target: Target) -> Bool:
  match target:
    case Target{+lo,+hi,offset,biome,_,_}: Bool.and(Bool.and(finite_axes(lo),finite_axes(hi)),
      Bool.and(Bool.and(finite(offset),ordered(lo,hi)),U32.is_le(biome,65535)))

def valid_targets(xs: List<&2,Target>) -> Bool:
  match xs:
    case Nil{}: True{}
    case x <> rest: Bool.and(valid(x),valid_targets(rest))

def axis(value: Axes,index: U32) -> F32:
  match value index:
    case Axes{a,_,_,_,_,_} 0: a
    case Axes{_,b,_,_,_,_} 1: b
    case Axes{_,_,c,_,_,_} 2: c
    case Axes{_,_,_,d,_,_} 3: d
    case Axes{_,_,_,_,e,_} 4: e
    case Axes{_,_,_,_,_,f} _: f

def minimum(a: Axes,b: Axes) -> Axes:
  match a b:
    case Axes{a,b,c,d,e,f} Axes{g,h,i,j,k,l}:
      Axes{F32.min(a,g),F32.min(b,h),F32.min(c,i),F32.min(d,j),F32.min(e,k),F32.min(f,l)}

def maximum(a: Axes,b: Axes) -> Axes:
  match a b:
    case Axes{a,b,c,d,e,f} Axes{g,h,i,j,k,l}:
      Axes{F32.max(a,g),F32.max(b,h),F32.max(c,i),F32.max(d,j),F32.max(e,k),F32.max(f,l)}

def midpoint(a: Axes,b: Axes) -> Axes:
  match a b:
    case Axes{a,b,c,d,e,f} Axes{g,h,i,j,k,l}:
      Axes{F32.add(F32.mul(a,0.5),F32.mul(g,0.5)),F32.add(F32.mul(b,0.5),F32.mul(h,0.5)),
        F32.add(F32.mul(c,0.5),F32.mul(i,0.5)),F32.add(F32.mul(d,0.5),F32.mul(j,0.5)),
        F32.add(F32.mul(e,0.5),F32.mul(k,0.5)),F32.add(F32.mul(f,0.5),F32.mul(l,0.5))}

def bound(target: Target) -> Box:
  match target:
    case Target{lo,hi,+offset,_,_,_}: Box{lo,hi,F32.mul(offset,offset)}

def union(a: Box,b: Box) -> Box:
  match a b:
    case Box{lo,hi,offset} Box{lo2,hi2,offset2}: Box{minimum(lo,lo2),maximum(hi,hi2),F32.min(offset,offset2)}

def bounds(xs: List<&2,Target>,acc: Box) -> Box:
  match xs:
    case Nil{}: acc
    case x <> rest: bounds(rest,union(acc,bound(x)))

def center(target: Target) -> Axes:
  match target:
    case Target{lo,hi,_,_,_,_}: midpoint(lo,hi)

def centers(xs: List<&2,Target>,lo: Axes,hi: Axes) -> Axes & Axes:
  match xs:
    case Nil{}: (lo,hi)
    case +x <> rest: centers(rest,minimum(lo,center(x)),maximum(hi,center(x)))

@unsafe
def spread(n: Nat,+lo: Axes,+hi: Axes,+i: U32,+best: U32,+width: F32) -> U32:
  match n:
    case 0n: best
    case 1n+p: wider(F32.is_gt(F32.sub(axis(hi,i),axis(lo,i)),width),p,lo,hi,i,best,width)

@unsafe
def wider(wide: Bool,n: Nat,+lo: Axes,+hi: Axes,+i: U32,best: U32,width: F32) -> U32:
  match wide:
    case False{}: spread(n,lo,hi,U32.inc(i),best,width)
    case True{}: spread(n,lo,hi,U32.inc(i),i,F32.sub(axis(hi,i),axis(lo,i)))

def dimension_result(pair: Axes & Axes,n: Nat) -> U32:
  (lo,hi) = pair
  spread(n,lo,hi,0,0,F32.neg(1.0))

def dimension(xs: List<&2,Target>,+first: Target,n: Nat) -> U32:
  dimension_result(centers(xs,center(first),center(first)),n)

def ordinal(target: Target) -> U32:
  match target:
    case Target{_,_,_,_,_,order}: order

def compare(+dimension: U32,+a: Target,+b: Target) -> Bool:
  +ac = axis(center(a),dimension)
  +bc = axis(center(b),dimension)
  Bool.or(F32.is_lt(ac,bc),Bool.and(F32.is_eq(ac,bc),U32.is_le(ordinal(a),ordinal(b))))

def sort(dimension: U32,xs: List<&2,Target>) -> List<&2,Target>:
  match dimension:
    case 0: List.sort(Target,a => b => compare(0,a,b),xs)
    case 1: List.sort(Target,a => b => compare(1,a,b),xs)
    case 2: List.sort(Target,a => b => compare(2,a,b),xs)
    case 3: List.sort(Target,a => b => compare(3,a,b),xs)
    case 4: List.sort(Target,a => b => compare(4,a,b),xs)
    case _: List.sort(Target,a => b => compare(5,a,b),xs)

@unsafe
def tree(xs: List<&2,Target>,+count: U32,+dimensions: Nat) -> Tree:
  match xs:
    case Nil{}: Empty{}
    case +first <> rest: small(U32.is_le(count,8),first <> rest,count,dimensions,first)

@unsafe
def small(leaf: Bool,+xs: List<&2,Target>,+count: U32,+dimensions: Nat,+first: Target) -> Tree:
  match leaf:
    case True{}: Leaf{bounds(xs,bound(first)),xs}
    case False{}:
      +chosen = dimension(xs,first,dimensions)
      +sorted = sort(chosen,xs)
      +middle = U32.div(count,2)
      a b = tree(List.take(&2,Target,sorted,U32.to_nat(middle)),middle,dimensions)
        tree(List.drop(&2,Target,sorted,U32.to_nat(middle)),U32.sub(count,middle),dimensions)
      Branch{bounds(xs,bound(first)),a,b}

def coastal(target: Target) -> Target:
  match target:
    case Target{Axes{a,b,+c,d,e,_},Axes{f,g,+h,i,j,_},offset,biome,flags,order}:
      Target{Axes{a,b,0.0,d,e,c},Axes{f,g,0.0,i,j,h},offset,biome,flags,order}

def include(yes: Bool,x: Target,rest: List<&2,Target>) -> List<&2,Target>:
  match yes:
    case False{}: rest
    case True{}: x <> rest

def surface(xs: List<&2,Target>) -> List<&2,Target>:
  match xs:
    case Nil{}: []
    case Target{lo,hi,offset,biome,+flags,order} <> rest:
      include(U32.is_zero(U32.and(flags,16)),Target{lo,hi,offset,biome,flags,order},surface(rest))

def shores(xs: List<&2,Target>) -> List<&2,Target>:
  match xs:
    case Nil{}: []
    case Target{lo,hi,offset,biome,+flags,order} <> rest:
      include(Bool.and(U32.is_zero(U32.and(flags,16)),U32.is_ne(U32.and(flags,64),0)),
        coastal(Target{lo,hi,offset,biome,flags,order}),shores(rest))

def prepare(+targets: List<&2,Target>) -> Model:
  +surface_targets = surface(targets)
  +coastal_targets = shores(targets)
  Model{tree(surface_targets,U32.from_nat(List.length(&2,Target,surface_targets)),5n),
    tree(targets,U32.from_nat(List.length(&2,Target,targets)),6n),
    tree(coastal_targets,U32.from_nat(List.length(&2,Target,coastal_targets)),5n)}

def delta(+x: F32,lo: F32,hi: F32) -> F32:
  +d = F32.sub(x,F32.clamp(x,lo,hi))
  F32.mul(d,d)

def depth(enabled: Bool,point: Axes,lo: Axes,hi: Axes) -> F32:
  match enabled:
    case False{}: 0.0
    case True{}: delta(axis(point,5),axis(lo,5),axis(hi,5))

def weighted_distance(weighted: Bool,a: F32,b: F32,c: F32,d: F32) -> F32:
  match weighted:
    case False{}: F32.add(F32.add(a,b),F32.add(c,d))
    case True{}: F32.add(F32.add(F32.mul(a,2.5),F32.mul(b,1.5)),F32.add(F32.mul(c,2.0),F32.mul(d,0.5)))

def fitness(box: Box,q: Query) -> F32:
  match box q:
    case Box{+lo,+hi,offset2} Query{+p,weighted,underground,_,_}:
      +a = delta(axis(p,0),axis(lo,0),axis(hi,0))
      +b = delta(axis(p,1),axis(lo,1),axis(hi,1))
      +c = delta(axis(p,2),axis(lo,2),axis(hi,2))
      +d = delta(axis(p,3),axis(lo,3),axis(hi,3))
      F32.add(F32.add(weighted_distance(weighted,a,b,c,d),delta(axis(p,4),axis(lo,4),axis(hi,4))),
        F32.add(depth(underground,p,lo,hi),offset2))

def ocean(mismatch: Bool) -> F32:
  match mismatch:
    case False{}: 0.0
    case True{}: 8.0

def leaf_fitness(+target: Target,q: Query) -> F32:
  match target q:
    case Target{_,_,_,_,flags,_} Query{+p,+weighted,+underground,+penalty,exclude}:
      F32.add(fitness(bound(target),Query{p,weighted,underground,penalty,exclude}),ocean(Bool.and(penalty,
        Bool.xor(U32.is_ne(U32.and(flags,4),0),F32.is_lt(axis(p,2),F32.neg(0.25))))))

def replace(better: Bool,candidate: Choice,current: Choice) -> Choice:
  match better:
    case True{}: candidate
    case False{}: current

def choose(+candidate: Choice,+current: Choice) -> Choice:
  match candidate current:
    case Missing{} _: current
    case _ Missing{}: candidate
    case Choice{+a,_,+ao,_} Choice{+b,_,+bo,_}:
      replace(Bool.or(F32.is_lt(a,b),Bool.and(F32.is_eq(a,b),U32.is_lt(ao,bo))),candidate,current)

def allowed(target: Target,q: Query) -> Bool:
  match target q:
    case Target{_,_,_,_,flags,_} Query{_,_,_,_,exclude}:
      Bool.not(Bool.and(exclude,U32.is_ne(U32.and(flags,64),0)))

def evaluate(allowed: Bool,+target: Target,q: Query,current: Choice) -> Choice:
  match allowed target:
    case False{} _: current
    case True{} Target{+lo,+hi,_,biome,_,order}:
      choose(Choice{leaf_fitness(target,q),biome,order,axis(midpoint(lo,hi),5)},current)

def leaves(xs: List<&2,Target>,+q: Query,current: Choice) -> Choice:
  match xs:
    case Nil{}: current
    case +x <> rest: leaves(rest,q,evaluate(allowed(x,q),x,q,current))

def visit(box: Box,q: Query,current: Choice) -> Bool:
  match current:
    case Missing{}: True{}
    case Choice{best,_,_,_}: F32.is_le(fitness(box,q),best)

@unsafe
def search(tree: Tree,+q: Query,+current: Choice) -> Choice:
  match tree:
    case Empty{}: current
    case Leaf{box,targets}: visit_leaf(visit(box,q,current),targets,q,current)
    case Branch{box,left,right}: visit_branch(visit(box,q,current),left,right,q,current)

@unsafe
def visit_leaf(yes: Bool,targets: List<&2,Target>,q: Query,current: Choice) -> Choice:
  match yes:
    case False{}: current
    case True{}: leaves(targets,q,current)

@unsafe
def visit_branch(yes: Bool,left: Tree,right: Tree,+q: Query,current: Choice) -> Choice:
  match yes:
    case False{}: current
    case True{}: search(right,q,search(left,q,current))

def lookup(model: Model,+q: Query) -> Choice:
  match model q:
    case Model{surface,_,_} Query{_,_,False{},_,_}: search(surface,q,Missing{})
    case Model{_,underground,_} _: search(underground,q,Missing{})

def coast(model: Model,point: Axes) -> Choice:
  match model point:
    case Model{_,_,shores} Axes{a,b,_,c,d,e}: search(shores,Query{Axes{a,b,0.0,c,d,e},False{},False{},False{},False{}},Missing{})

# Only enable depth-dependent selection when registered cave targets exist.
# Otherwise retain the surface biome, as the Rust path does.
def underground_targets(xs: List<&2,Target>) -> Bool:
  match xs:
    case Nil{}: False{}
    case Target{_,_,_,_,flags,_} <> rest: Bool.or(U32.is_ne(U32.and(flags,16),0),underground_targets(rest))

def underground_tree(tree: Tree) -> Bool:
  match tree:
    case Empty{}: False{}
    case Leaf{_,targets}: underground_targets(targets)
    case Branch{_,a,b}: Bool.or(underground_tree(a),underground_tree(b))

def has_underground(model: Model) -> Bool:
  match model:
    case Model{_,tree,_}: underground_tree(tree)
