import Base
import ./structure_registry.bend as M
import ./structure_values.bend as V
import ./structure_processors.bend as S
import ./structure_geometry.bend as G
import ./registry.bend as R
import ./registry_values.bend as RV
import ./registry_density.bend as D
import ./placement_data.bend as P
import ./density.bend as Q

# Rotate a selected template/palette once on the CPU. The block and state
# rotations are both registered data; jigsaw markers become their final states.
type Block is Data:
  Block{pos: P.Position,index: U32,original: U32,material: U32,skip: Bool,tag: M.Node}
type Template is Data:
  Template{blocks: List<&2,Block>,entities: M.Node,valid: Bool}
type Context is Data:
  Context{keys: D.Keys,environment: S.Environment}

@unsafe
def keys(-A: Type,doc: R.Document,+env: S.Environment,next: R.Document -> Context -> A) -> A:
  D.resolved(A,["palettes","blocks","tags","entities","joints","pos","final","size","ground","minecraft:structure_void","minecraft:structure_block","minecraft:jigsaw"],doc,[],doc => keys => next(doc,Context{keys,env}))

@unsafe
def key(+ctx: Context,+i: Nat) -> U32:
  match ctx:
    case Context{keys,_}: D.key(keys,i)

@unsafe
def field(+ctx: Context,+node: M.Node,+i: Nat) -> M.Node:
  M.field(node,key(ctx,i))

@unsafe
def marker(+ctx: Context,+material: U32,+i: Nat) -> Bool:
  match ctx:
    case Context{_,env}: Bool.and(U32.is_ne(key(ctx,i),4294967295),U32.is_eq(S.name(env,material),key(ctx,i)))

@unsafe
def prepare(-A: Type,doc: R.Document,+ctx: Context,value: R.Value,+palette: U32,+rotation: U32,next: R.Document -> Template -> A) -> A:
  V.project(A,doc,value,doc => +node => tag_fields(A,doc,field(ctx,node,2n),[],True{},doc => tags => valid => next(doc,prepared(ctx,node,tags,valid,palette,rotation))))

@unsafe
def tag_fields(-A: Type,doc: R.Document,value: M.Node,+acc: List<&2,M.Field>,valid: Bool,next: R.Document -> M.Node -> Bool -> A) -> A:
  match value:
    case M.Object{_,xs}: tag_rows(A,doc,xs,acc,valid,next)
    case _: next(doc,V.object(acc),False{})

@unsafe
def tag_rows(-A: Type,doc: R.Document,rows: List<&2,M.Field>,+acc: List<&2,M.Field>,+valid: Bool,next: R.Document -> M.Node -> Bool -> A) -> A:
  match rows:
    case Nil{}: next(doc,V.object(List.reverse(&2,M.Field,acc)),valid)
    case M.Field{id,value} <> rest: R.string(A,doc,id,doc => ref => tag_ref(A,doc,ref,doc => index => ok =>
      tag_rows(A,doc,rest,M.Field{index,value} <> acc,Bool.and(valid,ok),next)))

@unsafe
def tag_ref(-A: Type,doc: R.Document,ref: Maybe<&2,R.StringRef>,next: R.Document -> U32 -> Bool -> A) -> A:
  match ref:
    case None{}: next(doc,0,False{})
    case Some{R.StringRef{at,+length}}: digits(A,doc,U32.mul(at,2),length,0,U32.is_gt(length,0),next)

@unsafe
def digits(-A: Type,doc: R.Document,+at: U32,n: U32,+number: U32,+valid: Bool,next: R.Document -> U32 -> Bool -> A) -> A:
  match n:
    case 0: next(doc,number,valid)
    case _: RV.character(A,doc,at,doc => +digit => digits(A,doc,U32.inc(at),U32.sub(n,1),U32.add(U32.mul(number,10),U32.sub(digit,48)),
      Bool.and(valid,Bool.and(Bool.and(U32.is_ge(digit,48),U32.is_le(digit,57)),Bool.or(U32.is_lt(number,429496729),Bool.and(U32.is_eq(number,429496729),U32.is_le(digit,53))))),next))

@unsafe
def prepared(+ctx: Context,+node: M.Node,+tags: M.Node,+valid: Bool,+palette: U32,+rotation: U32) -> Template:
  has_palette(Bool.and(valid,Bool.and(U32.is_gt(M.count(field(ctx,node,0n)),0),U32.is_lt(rotation,4))),ctx,node,tags,palette,rotation)

@unsafe
def has_palette(valid: Bool,+ctx: Context,+node: M.Node,+tags: M.Node,+palette: U32,+rotation: U32) -> Template:
  match valid:
    case False{}: Template{[],M.empty(),False{}}
    case True{}: assembled(ctx,node,tags,M.item(field(ctx,node,0n),U32.mod(palette,M.count(field(ctx,node,0n)))),rotation)

@unsafe
def assembled(+ctx: Context,+node: M.Node,+tags: M.Node,+palette: M.Node,+rotation: U32) -> Template:
  Template{blocks(ctx,node,tags,palette,rotation,0,U32.div(M.count(field(ctx,node,1n)),4)),field(ctx,node,3n),
    Bool.and(Bool.and(dimensions(ctx,node),U32.is_zero(U32.mod(M.count(field(ctx,node,1n)),4))),Bool.and(tags_valid(tags,U32.div(M.count(field(ctx,node,1n)),4)),blocks_valid(ctx,node,palette,rotation,0,U32.div(M.count(field(ctx,node,1n)),4))))}

@unsafe
def tags_valid(+tags: M.Node,n: U32) -> Bool:
  match tags:
    case M.Object{_,xs}: indices_valid(xs,n)
    case _: False{}

@unsafe
def indices_valid(xs: List<&2,M.Field>,+n: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case M.Field{i,_} <> rest: Bool.and(U32.is_lt(i,n),indices_valid(rest,n))

@unsafe
def raw(+ctx: Context,+node: M.Node,+i: U32,+axis: U32) -> U32:
  M.int(M.item(field(ctx,node,1n),U32.add(U32.mul(i,4),axis)),4294967295)

@unsafe
def blocks_valid(+ctx: Context,+node: M.Node,+palette: M.Node,+rotation: U32,+i: U32,n: U32) -> Bool:
  match ctx n:
    case _ 0: True{}
    case Context{_,S.Environment{_,names}} _: Bool.and(Bool.and(
      Bool.and(U32.is_lt(raw(ctx,node,i,0),M.int(M.item(field(ctx,node,7n),0),0)),U32.is_lt(raw(ctx,node,i,1),M.int(M.item(field(ctx,node,7n),1),0))),
      Bool.and(U32.is_lt(raw(ctx,node,i,2),M.int(M.item(field(ctx,node,7n),2),0)),Bool.and(U32.is_lt(M.int(M.item(M.item(palette,raw(ctx,node,i,3)),rotation),4294967295),Q.table_size(U32,names)),U32.is_lt(final_material(marker(ctx,M.int(M.item(M.item(palette,raw(ctx,node,i,3)),rotation),0),11n),ctx,field(ctx,node,4n),P.Position{raw(ctx,node,i,0),raw(ctx,node,i,1),raw(ctx,node,i,2)},rotation,M.int(M.item(M.item(palette,raw(ctx,node,i,3)),rotation),0)),Q.table_size(U32,names))))),blocks_valid(ctx,node,palette,rotation,U32.inc(i),U32.sub(n,1)))

@unsafe
def blocks(+ctx: Context,+node: M.Node,+tags: M.Node,+palette: M.Node,+rotation: U32,+i: U32,n: U32) -> List<&2,Block>:
  match n:
    case 0: []
    case _: block(ctx,node,tags,rotation,i,P.Position{raw(ctx,node,i,0),raw(ctx,node,i,1),raw(ctx,node,i,2)},M.int(M.item(M.item(palette,raw(ctx,node,i,3)),rotation),0)) <>
      blocks(ctx,node,tags,palette,rotation,U32.inc(i),U32.sub(n,1))

@unsafe
def block(+ctx: Context,+node: M.Node,+tags: M.Node,+rotation: U32,+i: U32,+pos: P.Position,+original: U32) -> Block:
  Block{G.rotate(pos,rotation),i,original,final_material(marker(ctx,original,11n),ctx,field(ctx,node,4n),pos,rotation,original),
    Bool.or(marker(ctx,original,9n),marker(ctx,original,10n)),M.field(tags,i)}

@unsafe
def final_material(jigsaw: Bool,+ctx: Context,+joints: M.Node,+pos: P.Position,+rotation: U32,original: U32) -> U32:
  match jigsaw:
    case False{}: original
    case True{}: joint(ctx,joints,pos,rotation,0,M.count(joints))

@unsafe
def joint(+ctx: Context,+joints: M.Node,+pos: P.Position,+rotation: U32,+i: U32,n: U32) -> U32:
  match n:
    case 0: 0
    case _: joint_match(G.equal(G.position(field(ctx,M.item(joints,i),5n)),pos),ctx,joints,pos,rotation,i,n)

@unsafe
def joint_match(equal: Bool,+ctx: Context,+joints: M.Node,+pos: P.Position,+rotation: U32,+i: U32,n: U32) -> U32:
  match equal:
    case True{}: M.int(M.item(field(ctx,M.item(joints,i),6n),rotation),0)
    case False{}: joint(ctx,joints,pos,rotation,U32.inc(i),U32.sub(n,1))

@unsafe
def dimensions(+ctx: Context,+node: M.Node) -> Bool:
  Bool.and(U32.is_eq(M.count(field(ctx,node,7n)),3),Bool.and(dimension(M.int(M.item(field(ctx,node,7n),0),0)),Bool.and(dimension(M.int(M.item(field(ctx,node,7n),1),0)),dimension(M.int(M.item(field(ctx,node,7n),2),0)))))

@unsafe
def dimension(+n: U32) -> Bool:
  Bool.and(U32.is_gt(n,0),U32.is_le(n,512))
