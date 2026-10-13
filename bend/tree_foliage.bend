import Base
import ./tree_geometry.bend as G
import ./placement_data.bend as P
import ./placement_random.bend as R
import ./word64.bend as W
import ./noise.bend as N

type Shape is Data:
  Shape{kind: U32,radius: U32,offset: U32,height: U32}
type Leaves is Data:
  Leaves{state: W.U64,positions: List<&2,P.Position>}

@unsafe
def crowns(xs: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match xs shape:
    case Nil{} _: Leaves{state,List.reverse(&2,P.Position,acc)}
    case +crown <> rest Shape{8,_,_,_}: dark_start(crown,rest,shape,state,acc)
    case +crown <> rest Shape{+kind,_,_,+height}: layers(Bool.and(U32.is_eq(kind,7),single(crown)),crown,rest,shape,height,state,acc)

@unsafe
def single(crown: G.Crown) -> Bool:
  match crown:
    case G.Crown{_,_,width}: U32.is_eq(width,1)

@unsafe
def layers(random: Bool,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+height: U32,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match random shape:
    case True{} _: jungle_layers(G.below(state,2),crown,rest,shape,acc)
    case False{} Shape{kind,_,_,_}: rows(0,N.word(U32.is_eq(kind,6),2,height),crown,rest,shape,state,acc)

@unsafe
def jungle_layers(draw: R.Draw,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,acc: List<&2,P.Position>) -> Leaves:
  match draw:
    case R.Draw{state,value}: rows(0,U32.inc(R.low(value)),crown,rest,shape,state,acc)

@unsafe
def conifer(+kind: U32) -> Bool:
  Bool.and(U32.is_ge(kind,3),U32.is_le(kind,5))

@unsafe
def radius(+dy: U32,+layers: U32,+crown: G.Crown,+shape: Shape) -> U32:
  match crown shape:
    case G.Crown{_,ro,_} Shape{kind,+r,+o,_}: G.clamp(row_radius(kind,dy,layers,r,o,ro),0,6)

@unsafe
def row_radius(kind: U32,+dy: U32,+layers: U32,+r: U32,+o: U32,+ro: U32) -> U32:
  match kind:
    case 7: U32.add(U32.sub(U32.inc(U32.add(r,ro)),o),dy)
    case 1: U32.add(U32.add(r,ro),W.flag(Bool.and(U32.is_ne(dy,0),U32.is_ne(dy,layers))))
    case 2: U32.add(U32.sub(U32.sub(U32.add(r,ro),1),o),dy)
    case 0: U32.sub(U32.sub(U32.add(r,ro),1),G.half(U32.sub(o,dy)))
    case 6: U32.sub(U32.inc(r),dy)
    case _: N.word(conifer(kind),U32.min(U32.inc(U32.div(dy,2)),U32.inc(r)),G.min(U32.add(U32.sub(r,1),U32.div(dy,2)),5))

@unsafe
def rows(+dy: U32,+layers: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  row_more(U32.is_le(dy,layers),dy,layers,crown,rest,shape,state,acc)

@unsafe
def row_more(more: Bool,+dy: U32,+layers: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match more:
    case False{}: crowns(rest,shape,state,acc)
    case True{}: cells(0,0,radius(dy,layers,crown,shape),dy,layers,crown,rest,shape,state,acc)

@unsafe
def cells(+ix: U32,+iz: U32,+r: U32,+dy: U32,+layers: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match crown shape:
    case G.Crown{at,_,+width} Shape{kind,_,o,_}:
      +x = U32.sub(ix,r)
      +z = U32.sub(iz,r)
      cell_more(U32.is_lt(ix,U32.add(U32.mul(r,2),width)),kind,G.min(G.abs(x),G.abs(U32.inc(U32.sub(x,width)))),G.min(G.abs(z),G.abs(U32.inc(U32.sub(z,width)))),
        plus_offset(at,P.Position{x,U32.sub(o,dy),z}),ix,iz,r,dy,layers,crown,rest,shape,state,acc)

@unsafe
def plus_offset(a: P.Position,b: P.Position) -> P.Position:
  G.plus(a,b)

@unsafe
def cell_more(more: Bool,+kind: U32,+ax: U32,+az: U32,+at: P.Position,+ix: U32,+iz: U32,+r: U32,+dy: U32,+layers: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match more kind:
    case False{} _: rows(U32.inc(dy),layers,crown,rest,shape,state,acc)
    case True{} 7: cell_result(Bool.or(U32.is_ge(U32.add(ax,az),7),U32.is_gt(U32.add(U32.mul(ax,ax),U32.mul(az,az)),U32.mul(r,r))),at,ix,iz,r,dy,layers,crown,rest,shape,state,acc)
    case True{} 1: cell_result(U32.is_gt(U32.add(U32.mul(U32.inc(U32.mul(ax,2)),U32.inc(U32.mul(ax,2))),U32.mul(U32.inc(U32.mul(az,2)),U32.inc(U32.mul(az,2)))),U32.mul(U32.mul(r,r),4)),at,ix,iz,r,dy,layers,crown,rest,shape,state,acc)
    case True{} _: corner(Bool.and(U32.is_eq(ax,r),U32.is_eq(az,r)),Bool.or(conifer(kind),Bool.and(U32.is_ne(kind,2),U32.is_zero(dy))),at,ix,iz,r,dy,layers,crown,rest,shape,state,acc)

@unsafe
def corner(corner: Bool,skip: Bool,+at: P.Position,+ix: U32,+iz: U32,+r: U32,+dy: U32,+layers: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match corner skip:
    case True{} False{}: corner_draw(G.below(state,2),at,ix,iz,r,dy,layers,crown,rest,shape,acc)
    case True{} True{}: cell_result(True{},at,ix,iz,r,dy,layers,crown,rest,shape,state,acc)
    case False{} _: cell_result(False{},at,ix,iz,r,dy,layers,crown,rest,shape,state,acc)

@unsafe
def corner_draw(draw: R.Draw,+at: P.Position,+ix: U32,+iz: U32,+r: U32,+dy: U32,+layers: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,acc: List<&2,P.Position>) -> Leaves:
  match draw:
    case R.Draw{state,value}: cell_result(U32.is_zero(R.low(value)),at,ix,iz,r,dy,layers,crown,rest,shape,state,acc)

@unsafe
def cell_result(skip: Bool,+at: P.Position,+ix: U32,+iz: U32,+r: U32,+dy: U32,+layers: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match skip:
    case True{}: cell_next(ix,iz,r,dy,layers,crown,rest,shape,state,acc)
    case False{}: cell_next(ix,iz,r,dy,layers,crown,rest,shape,state,at <> acc)

@unsafe
def cell_next(+ix: U32,+iz: U32,+r: U32,+dy: U32,+layers: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match crown:
    case G.Crown{_,_,width}:
      +end = U32.add(U32.mul(r,2),width)
      cells(N.word(U32.is_eq(U32.inc(iz),end),U32.inc(ix),ix),N.word(U32.is_eq(U32.inc(iz),end),0,U32.inc(iz)),r,dy,layers,crown,rest,shape,state,acc)

@unsafe
def dark_start(+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match crown:
    case G.Crown{_,_,2}: dark_chance(R.next(state),crown,rest,shape,acc)
    case _: dark_rows(0,2,crown,rest,shape,state,acc)

@unsafe
def dark_chance(draw: R.Draw,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,acc: List<&2,P.Position>) -> Leaves:
  match draw:
    case R.Draw{state,value}: dark_rows(0,N.word(F32.is_lt(R.unit(value),0.5),4,3),crown,rest,shape,state,acc)

@unsafe
def dark_rows(+i: U32,+count: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match crown shape:
    case G.Crown{_,_,+width} Shape{_,+r,_,_}: dark_row(U32.is_lt(i,count),i,count,U32.add(r,N.word(U32.is_zero(i),2,N.word(U32.is_eq(i,1),N.word(U32.is_eq(width,2),3,1),N.word(U32.is_eq(i,2),2,0)))),crown,rest,shape,state,acc)

@unsafe
def dark_row(more: Bool,+i: U32,+count: U32,+r: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match more:
    case False{}: crowns(rest,shape,state,acc)
    case True{}: dark_cells(0,0,i,count,r,crown,rest,shape,state,acc)

@unsafe
def dark_cells(+ix: U32,+iz: U32,+i: U32,+count: U32,+r: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match crown shape:
    case G.Crown{at,_,+width} Shape{_,_,offset,_}:
      +ax = U32.min(G.abs(U32.sub(ix,r)),G.abs(U32.inc(U32.sub(U32.sub(ix,r),width))))
      +az = U32.min(G.abs(U32.sub(iz,r)),G.abs(U32.inc(U32.sub(U32.sub(iz,r),width))))
      dark_cell(U32.is_lt(ix,U32.add(U32.mul(r,2),width)),
        Bool.or(Bool.and(Bool.and(U32.is_eq(i,1),U32.is_eq(width,2)),Bool.and(Bool.or(U32.is_zero(ix),U32.is_ge(ix,U32.mul(r,2))),Bool.or(U32.is_zero(iz),U32.is_ge(iz,U32.mul(r,2))))),
          Bool.or(Bool.and(Bool.and(U32.is_zero(i),U32.is_ne(width,2)),Bool.and(U32.is_eq(ax,r),U32.is_eq(az,r))),
            Bool.and(U32.is_eq(i,2),signed_less(U32.sub(U32.mul(r,2),2),U32.add(ax,az))))),
        plus_offset(at,P.Position{U32.sub(ix,r),U32.add(offset,U32.sub(i,1)),U32.sub(iz,r)}),ix,iz,i,count,r,crown,rest,shape,state,acc)

@unsafe
def signed_less(a: U32,b: U32) -> Bool:
  U32.is_lt(U32.xor(a,2147483648),U32.xor(b,2147483648))

@unsafe
def dark_cell(more: Bool,skip: Bool,+at: P.Position,+ix: U32,+iz: U32,+i: U32,+count: U32,+r: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match more skip:
    case False{} _: dark_rows(U32.inc(i),count,crown,rest,shape,state,acc)
    case True{} True{}: dark_next(ix,iz,i,count,r,crown,rest,shape,state,acc)
    case True{} False{}: dark_next(ix,iz,i,count,r,crown,rest,shape,state,at <> acc)

@unsafe
def dark_next(+ix: U32,+iz: U32,+i: U32,+count: U32,+r: U32,+crown: G.Crown,rest: List<&2,G.Crown>,+shape: Shape,+state: W.U64,acc: List<&2,P.Position>) -> Leaves:
  match crown:
    case G.Crown{_,_,width}:
      +end = U32.add(U32.mul(r,2),width)
      dark_cells(N.word(U32.is_eq(U32.inc(iz),end),U32.inc(ix),ix),N.word(U32.is_eq(U32.inc(iz),end),0,U32.inc(iz)),i,count,r,crown,rest,shape,state,acc)
