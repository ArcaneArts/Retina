import Base
import ./structure_registry.bend as M
import ./structure_values.bend as V
import ./structure_nbt.bend as N
import ./structure_blocks.bend as B
import ./structure_geometry.bend as G
import ./structure_template.bend as T
import ./registry.bend as R
import ./registry_density.bend as D
import ./placement_data.bend as P
import ./placement_random.bend as Mix
import ./ore_random.bend as O
import ./word64.bend as W
import ./float64.bend as F

type Context is Data:
  Context{keys: D.Keys,origin: P.Position,rotation: U32,bounds: G.Box,seed: W.U64,template: U32}
type Position is Data:
  Position{x: W.U64,y: W.U64,z: W.U64}

@unsafe
def keys(-A: Type,doc: R.Document,next: R.Document -> D.Keys -> A) -> A:
  D.resolved(A,["nbt","pos","blockPos","block_pos","id","minecraft:item_frame","minecraft:glow_item_frame","minecraft:painting","Facing","facing","Pos","UUID","Rotation"],doc,[],next)

@unsafe
def key(+ctx: Context,+i: Nat) -> U32:
  match ctx:
    case Context{keys,_,_,_,_,_}: D.key(keys,i)

@unsafe
def output_key(+ctx: Context,+i: Nat) -> U32:
  B.key_or(key(ctx,i),U32.add(4294967276,U32.from_nat(i)))

@unsafe
def field(+ctx: Context,+tag: M.Node,i: Nat) -> M.Node:
  M.field(M.item(tag,1),key(ctx,i))

@unsafe
def has(+ctx: Context,+tag: M.Node,i: Nat) -> Bool:
  V.has_field(M.item(tag,1),key(ctx,i))

@unsafe
def values(+tag: M.Node) -> M.Node:
  list_values(U32.is_eq(M.int(M.item(tag,0),0),9),M.item(M.item(tag,1),1))

@unsafe
def list_values(list: Bool,value: M.Node) -> M.Node:
  match list:
    case True{}: value
    case False{}: M.empty()

@unsafe
def number(+tag: M.Node) -> W.U64:
  N.double_bits(M.value(M.item(tag,1)))

@unsafe
def position(+values: M.Node) -> Position:
  Position{number(M.item(values,0)),number(M.item(values,1)),number(M.item(values,2))}

@unsafe
def rotate(+pos: Position,rotation: U32) -> Position:
  match pos rotation:
    case Position{x,y,z} 1: Position{F.sub(W.U64{1072693248,0},z),y,x}
    case Position{x,y,z} 2: Position{F.sub(W.U64{1072693248,0},x),y,F.sub(W.U64{1072693248,0},z)}
    case Position{x,y,z} 3: Position{z,y,F.sub(W.U64{1072693248,0},x)}
    case _ _: pos

@unsafe
def translate(pos: Position,origin: P.Position) -> Position:
  match pos origin:
    case Position{x,y,z} P.Position{a,b,c}: Position{F.add(x,N.double_integer(O.signed(a))),F.add(y,N.double_integer(O.signed(b))),F.add(z,N.double_integer(O.signed(c)))}

@unsafe
def transformed(+raw: M.Node,+ctx: Context) -> Position:
  match ctx:
    case Context{_,origin,rotation,_,_,_}: translate(rotate(position(raw),rotation),origin)

@unsafe
def anchor(+wrapper: M.Node,+ctx: Context,+pos: Position) -> P.Position:
  +v = values(field(ctx,wrapper,2n))
  anchor_present(U32.is_eq(M.count(v),3),v,ctx,pos)

@unsafe
def anchor_present(present: Bool,+v: M.Node,+ctx: Context,pos: Position) -> P.Position:
  match present ctx pos:
    case True{} Context{_,origin,rotation,_,_,_} _: G.add(origin,G.rotate(P.Position{F.integer(number(M.item(v,0)),False{}),F.integer(number(M.item(v,1)),False{}),F.integer(number(M.item(v,2)),False{})},rotation))
    case False{} _ Position{x,y,z}: P.Position{F.integer(x,True{}),F.integer(y,True{}),F.integer(z,True{})}

@unsafe
def double(value: W.U64) -> M.Node:
  match value:
    case W.U64{hi,lo}: V.array([V.integer(6),M.Scalar{R.Value{4,hi,lo,0}}])

@unsafe
def position_tag(pos: Position) -> M.Node:
  match pos:
    case Position{x,y,z}: V.array([V.integer(9),V.array([V.integer(6),V.array([double(x),double(y),double(z)])])])

@unsafe
def anchor_tag(pos: P.Position) -> M.Node:
  match pos:
    case P.Position{x,y,z}: V.array([V.integer(11),V.array([V.signed_integer(x),V.signed_integer(y),V.signed_integer(z)])])

@unsafe
def uuid(+ctx: Context,index: U32) -> M.Node:
  match ctx:
    case Context{_,P.Position{x,y,+z},r,_,seed,template}: uuid_pair(Mix.mixed(W.xor(seed,W.xor(W.U64{0,template},W.xor(B.rotate(W.U64{0,index},17),W.xor(O.signed(x),W.xor(B.rotate(O.signed(y),37),B.rotate(W.U64{0,r},11))))))),z)

@unsafe
def uuid_pair(+a: W.U64,z: U32) -> M.Node:
  uuid_values(a,Mix.mixed(W.xor(a,O.signed(z))))

@unsafe
def uuid_values(a: W.U64,b: W.U64) -> M.Node:
  match a b:
    case W.U64{ah,al} W.U64{bh,bl}: V.array([V.integer(11),V.array([V.signed_integer(al),V.signed_integer(ah),V.signed_integer(bl),V.signed_integer(bh)])])

@unsafe
def facing3(value: U32,n: U32) -> U32:
  match n:
    case 0: value
    case _: facing3(quarter(value),U32.sub(n,1))

@unsafe
def quarter(value: U32) -> U32:
  match value:
    case 2: 5
    case 3: 4
    case 4: 2
    case 5: 3
    case _: value

@unsafe
def attachment(+tag: M.Node,+ctx: Context,+anchor: P.Position) -> M.Node:
  attachment_present(has(ctx,tag,3n),tag,ctx,anchor)

@unsafe
def attachment_present(present: Bool,+tag: M.Node,+ctx: Context,anchor: P.Position) -> M.Node:
  match present:
    case False{}: tag
    case True{}: direction(V.put(tag,key(ctx,3n),anchor_tag(anchor)),ctx,M.text(M.item(field(ctx,tag,4n),1)))

@unsafe
def direction(+tag: M.Node,+ctx: Context,+id: U32) -> M.Node:
  direction_kind(Bool.and(U32.is_ne(id,4294967295),Bool.or(U32.is_eq(id,key(ctx,5n)),U32.is_eq(id,key(ctx,6n)))),Bool.and(U32.is_ne(id,4294967295),U32.is_eq(id,key(ctx,7n))),tag,ctx)

@unsafe
def direction_kind(frame: Bool,painting: Bool,tag: M.Node,+ctx: Context) -> M.Node:
  match frame painting:
    case True{} _: direction_field(tag,ctx,8n)
    case False{} True{}: direction_field(tag,ctx,9n)
    case _ _: tag

@unsafe
def direction_field(+tag: M.Node,+ctx: Context,+i: Nat) -> M.Node:
  direction_present(has(ctx,tag,i),tag,ctx,i,field(ctx,tag,i))

@unsafe
def direction_present(present: Bool,+tag: M.Node,+ctx: Context,+i: Nat,+facing: M.Node) -> M.Node:
  match present ctx:
    case False{} _: tag
    case True{} Context{_,_,r,_,_,_}: direction_value(tag,ctx,i,facing,F.integer(number(facing),False{}),r)

@unsafe
def direction_value(+tag: M.Node,+ctx: Context,+i: Nat,+facing: M.Node,+value: U32,+rotation: U32) -> M.Node:
  direction_changed(Bool.and(Nat.is_eq(i,9n),U32.is_lt(value,4)),Bool.and(Nat.is_eq(i,8n),Bool.and(U32.is_ge(value,2),U32.is_lt(value,6))),tag,ctx,i,facing,value,rotation)

@unsafe
def direction_changed(painting: Bool,frame: Bool,tag: M.Node,+ctx: Context,+i: Nat,+facing: M.Node,value: U32,rotation: U32) -> M.Node:
  match painting frame:
    case True{} _: V.put(tag,key(ctx,i),V.array([M.item(facing,0),V.integer(U32.mod(U32.add(value,rotation),4))]))
    case False{} True{}: V.put(tag,key(ctx,i),V.array([M.item(facing,0),V.integer(facing3(value,rotation))]))
    case _ _: tag

@unsafe
def orientation(+tag: M.Node,+ctx: Context) -> M.Node:
  +v = values(field(ctx,tag,12n))
  orientation_present(U32.is_eq(M.count(v),2),tag,ctx,v)

@unsafe
def orientation_present(present: Bool,tag: M.Node,+ctx: Context,+v: M.Node) -> M.Node:
  match present ctx:
    case False{} _: tag
    case True{} Context{_,_,r,_,_,_}: V.put(tag,output_key(ctx,12n),V.array([V.integer(9),V.array([V.integer(5),V.array([yaw(F.add(number(M.item(v,0)),N.double_integer(W.U64{0,U32.mul(r,90)}))),M.item(v,1)])])]))

@unsafe
def yaw(value: W.U64) -> M.Node:
  match value:
    case W.U64{hi,lo}: V.array([V.integer(5),M.Scalar{R.Value{4,hi,lo,0}}])

@unsafe
def place(+template: T.Template,+ctx: Context) -> List<&2,M.Node>:
  match template:
    case T.Template{_,xs,True{}}: rows(xs,ctx,0,M.count(xs))
    case _: []

@unsafe
def rows(+xs: M.Node,+ctx: Context,+i: U32,n: U32) -> List<&2,M.Node>:
  match n:
    case 0: []
    case _: result(one(M.item(xs,i),ctx,i),rows(xs,ctx,U32.inc(i),U32.sub(n,1)))

@unsafe
def result(value: Maybe<&2,M.Node>,rest: List<&2,M.Node>) -> List<&2,M.Node>:
  match value:
    case None{}: rest
    case Some{v}: v <> rest

@unsafe
def one(+wrapper: M.Node,+ctx: Context,index: U32) -> Maybe<&2,M.Node>:
  +tag = field(ctx,wrapper,0n)
  +v = values(field(ctx,wrapper,1n))
  one_valid(Bool.and(U32.is_eq(M.int(M.item(tag,0),0),10),U32.is_eq(M.count(v),3)),wrapper,tag,v,ctx,index)

@unsafe
def one_valid(valid: Bool,+wrapper: M.Node,tag: M.Node,v: M.Node,+ctx: Context,index: U32) -> Maybe<&2,M.Node>:
  match valid:
    case False{}: None{}
    case True{}: located(wrapper,tag,transformed(v,ctx),ctx,index)

@unsafe
def located(+wrapper: M.Node,tag: M.Node,+pos: Position,+ctx: Context,index: U32) -> Maybe<&2,M.Node>:
  +a = anchor(wrapper,ctx,pos)
  inside(tag,pos,a,ctx,index)

@unsafe
def inside(tag: M.Node,+pos: Position,+anchor: P.Position,+ctx: Context,index: U32) -> Maybe<&2,M.Node>:
  match pos ctx:
    case Position{x,y,z} Context{_,_,_,bounds,_,_}: included(Bool.and(G.inside(bounds,anchor),Bool.and(F.finite(x),Bool.and(F.finite(y),F.finite(z)))),tag,pos,anchor,ctx,index)

@unsafe
def included(include: Bool,tag: M.Node,pos: Position,anchor: P.Position,+ctx: Context,index: U32) -> Maybe<&2,M.Node>:
  match include:
    case False{}: None{}
    case True{}: Some{orientation(V.put(V.put(attachment(tag,ctx,anchor),output_key(ctx,10n),position_tag(pos)),output_key(ctx,11n),uuid(ctx,index)),ctx)}
