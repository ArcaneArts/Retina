import Base
import ./structure_registry.bend as M
import ./placement_data.bend as P
import ./noise.bend as N

type Box is Data:
  Box{low: P.Position,high: P.Position}

@unsafe
def lt(+a: U32,+b: U32) -> Bool:
  U32.is_lt(U32.xor(a,2147483648),U32.xor(b,2147483648))

@unsafe
def min(+a: U32,+b: U32) -> U32:
  N.word(lt(a,b),a,b)

@unsafe
def max(+a: U32,+b: U32) -> U32:
  N.word(lt(a,b),b,a)

@unsafe
def div(+a: U32,+b: U32) -> U32:
  divided(lt(a,0),a,b)

@unsafe
def divided(+negative: Bool,+a: U32,+b: U32) -> U32:
  match negative:
    case False{}: U32.div(a,b)
    case True{}: U32.sub(0,U32.div(U32.sub(0,a),b))

@unsafe
def floor_div(+a: U32,+b: U32) -> U32:
  U32.sub(div(a,b),N.word(Bool.and(lt(a,0),U32.is_ne(U32.mod(U32.sub(0,a),b),0)),1,0))

@unsafe
def position(+node: M.Node) -> P.Position:
  P.Position{M.int(M.item(node,0),0),M.int(M.item(node,1),0),M.int(M.item(node,2),0)}

@unsafe
def rotate(+p: P.Position,+r: U32) -> P.Position:
  match p r:
    case P.Position{x,y,z} 1: P.Position{U32.sub(0,z),y,x}
    case P.Position{x,y,z} 2: P.Position{U32.sub(0,x),y,U32.sub(0,z)}
    case P.Position{x,y,z} 3: P.Position{z,y,U32.sub(0,x)}
    case _ _: p

@unsafe
def add(+a: P.Position,+b: P.Position) -> P.Position:
  match a b:
    case P.Position{x,y,z} P.Position{a,b,c}: P.Position{U32.add(x,a),U32.add(y,b),U32.add(z,c)}

@unsafe
def sub(+a: P.Position,+b: P.Position) -> P.Position:
  match a b:
    case P.Position{x,y,z} P.Position{a,b,c}: P.Position{U32.sub(x,a),U32.sub(y,b),U32.sub(z,c)}

@unsafe
def direction(+d: U32,+r: U32) -> P.Position:
  rotate(axis(d),r)

@unsafe
def axis(+d: U32) -> P.Position:
  match d:
    case 0: P.Position{0,4294967295,0}
    case 1: P.Position{0,1,0}
    case 2: P.Position{0,0,4294967295}
    case 3: P.Position{0,0,1}
    case 4: P.Position{4294967295,0,0}
    case _: P.Position{1,0,0}

@unsafe
def equal(+a: P.Position,+b: P.Position) -> Bool:
  match a b:
    case P.Position{x,y,z} P.Position{a,b,c}: Bool.and(U32.is_eq(x,a),Bool.and(U32.is_eq(y,b),U32.is_eq(z,c)))

@unsafe
def inside(+b: Box,+p: P.Position) -> Bool:
  match b p:
    case Box{P.Position{x,y,z},P.Position{a,b,c}} P.Position{i,j,k}:
      Bool.not(Bool.or(Bool.or(Bool.or(lt(i,x),lt(j,y)),Bool.or(lt(k,z),lt(a,i))),Bool.or(lt(b,j),lt(c,k))))

@unsafe
def contains(+a: Box,+b: Box) -> Bool:
  match b:
    case Box{lo,hi}: Bool.and(inside(a,lo),inside(a,hi))

@unsafe
def intersects(+a: Box,+b: Box) -> Bool:
  match a b:
    case Box{P.Position{x,y,z},P.Position{a,b,c}} Box{P.Position{i,j,k},P.Position{d,e,f}}:
      Bool.not(Bool.or(Bool.or(Bool.or(lt(d,x),lt(e,y)),Bool.or(lt(f,z),lt(a,i))),Bool.or(lt(b,j),lt(c,k))))

@unsafe
def include(+b: Box,+p: P.Position) -> Box:
  match b p:
    case Box{P.Position{x,y,z},P.Position{a,b,c}} P.Position{i,j,k}:
      Box{P.Position{min(x,i),min(y,j),min(z,k)},P.Position{max(a,i),max(b,j),max(c,k)}}

@unsafe
def template(+model: M.Model,+part: M.Node) -> M.Node:
  M.item(M.get(model,M.root(model),1n),M.int(M.get(model,part,25n),0))

@unsafe
def bounds(+model: M.Model,+element: M.Node,+pos: P.Position,+r: U32) -> Box:
  parts(model,M.get(model,element,24n),pos,r,0,M.count(M.get(model,element,24n)),
    Box{P.Position{2147483647,2147483647,2147483647},P.Position{2147483648,2147483648,2147483648}})

@unsafe
def parts(+model: M.Model,+xs: M.Node,+pos: P.Position,+r: U32,+i: U32,+n: U32,+b: Box) -> Box:
  match n:
    case 0: b
    case _: parts(model,xs,pos,r,U32.inc(i),U32.sub(n,1),include(include(b,pos),
      add(pos,rotate(sub(position(M.get(model,template(model,M.item(xs,i)),6n)),P.Position{1,1,1}),r))))
