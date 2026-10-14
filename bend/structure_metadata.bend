import Base
import ./structure_registry.bend as M
import ./structure_values.bend as V
import ./structure_nbt.bend as N
import ./structure_blocks.bend as Bk
import ./structure_plan.bend as Plan
import ./structure_geometry.bend as G
import ./placement_data.bend as P
import ./registry.bend as R
import ./registry_density.bend as D
import ./word64.bend as W
import ./binary.bend as B
import ./compression.bend as Z

# Ordered starts are shared by block/entity replay and chunk metadata. Seed and
# terrain stay attached to the start, independent of the requesting chunk.
type Start is Data:
  Start{definition: U32,x: U32,z: U32,seed: W.U64,pieces: List<&2,Plan.Piece>,terrain: Maybe<&2,Plan.Heights>}
type Context is Data:
  Context{model: M.Model,keys: D.Keys,x: U32,z: U32,bounds: G.Box}
type Records is Data:
  Records{starts: M.Node,references: M.Node}

@unsafe
def keys(-A: Type,doc: R.Document,next: R.Document -> D.Keys -> A) -> A:
  D.resolved(A,["id","ChunkX","ChunkZ","references","Children","BB","O","GD","template","starts","References","structures","entities","block_entities","retina:template"],doc,[],next)

@unsafe
def key(+ctx: Context,+i: Nat) -> U32:
  match ctx:
    case Context{_,keys,_,_,_}: Bk.key_or(D.key(keys,i),U32.add(4294967232,U32.from_nat(i)))

@unsafe
def int(value: U32) -> M.Node:
  V.array([V.integer(3),V.signed_integer(value)])

@unsafe
def text(value: U32) -> M.Node:
  V.array([V.integer(8),V.string(value)])

@unsafe
def list(xs: List<&2,M.Node>) -> M.Node:
  V.array([V.integer(9),V.array([V.integer(10),V.array(xs)])])

@unsafe
def box(b: G.Box) -> M.Node:
  match b:
    case G.Box{P.Position{x,y,z},P.Position{a,b,c}}: V.array([V.integer(11),V.array([V.signed_integer(x),V.signed_integer(y),V.signed_integer(z),V.signed_integer(a),V.signed_integer(b),V.signed_integer(c)])])

@unsafe
def children(xs: List<&2,Plan.Piece>,+ctx: Context) -> List<&2,M.Node>:
  match xs ctx:
    case Nil{} _: []
    case Plan.Piece{element,_,_,bounds,_,_,depth,_} <> rest Context{model,_,_,_,_}:
      V.array([V.integer(10),V.object([M.Field{key(ctx,0n),text(key(ctx,14n))},M.Field{key(ctx,5n),box(bounds)},M.Field{key(ctx,6n),int(4294967295)},M.Field{key(ctx,7n),int(depth)},M.Field{key(ctx,8n),text(M.text(M.get(model,Plan.primary(model,element),5n)))}])]) <> children(rest,ctx)

@unsafe
def start_tag(+start: Start,+ctx: Context,+id: U32) -> M.Node:
  match start:
    case Start{_,x,z,_,pieces,_}: V.array([V.integer(10),V.object([M.Field{key(ctx,0n),text(id)},M.Field{key(ctx,1n),int(x)},M.Field{key(ctx,2n),int(z)},M.Field{key(ctx,3n),int(0)},M.Field{key(ctx,4n),list(children(pieces,ctx))}])])

@unsafe
def relevant(xs: List<&2,Plan.Piece>,+bounds: G.Box) -> Bool:
  match xs:
    case Nil{}: False{}
    case Plan.Piece{_,_,_,b,_,_,_,_} <> rest: Bool.or(G.intersects(b,bounds),relevant(rest,bounds))

@unsafe
def items(+node: M.Node,+i: U32,n: U32) -> List<&2,M.Node>:
  match n:
    case 0: []
    case _: M.item(node,i) <> items(node,U32.inc(i),U32.sub(n,1))

@unsafe
def reference(+refs: M.Node,+id: U32,x: U32,z: U32) -> M.Node:
  +previous = M.item(M.field(M.item(refs,1),id),1)
  V.put(refs,id,V.array([V.integer(12),V.array(List.append(&2,M.Node,items(previous,0,M.count(previous)),[M.Scalar{R.Value{3,z,x,0}}]))]))

@unsafe
def owned(owner: Bool,+start: Start,+ctx: Context,+id: U32,starts: M.Node) -> M.Node:
  match owner:
    case True{}: V.put(starts,id,start_tag(start,ctx,id))
    case False{}: starts

@unsafe
def referenced(nearby: Bool,refs: M.Node,id: U32,x: U32,z: U32) -> M.Node:
  match nearby:
    case True{}: reference(refs,id,x,z)
    case False{}: refs

@unsafe
def one(+start: Start,+ctx: Context,data: Records) -> Records:
  match start ctx data:
    case Start{definition,+x,+z,_,pieces,_} Context{model,_,cx,cz,bounds} Records{starts,refs}:
      +id = M.text(M.get(model,M.item(Plan.array(model,2n),definition),5n))
      Records{owned(Bool.and(U32.is_eq(x,cx),U32.is_eq(z,cz)),start,ctx,id,starts),referenced(relevant(pieces,bounds),refs,id,x,z)}

@unsafe
def collect(xs: List<&2,Start>,+ctx: Context,data: Records) -> Records:
  match xs:
    case Nil{}: data
    case start <> rest: collect(rest,ctx,one(start,ctx,data))

# Last accepted write wins; air/replacements remove old block entities. Entries
# are sorted in signed x,y,z order, matching the region serializer contract.
@unsafe
def less(+a: P.Position,+b: P.Position) -> Bool:
  match a b:
    case P.Position{+x,+y,z} P.Position{+a,+b,c}: Bool.or(G.lt(x,a),Bool.and(U32.is_eq(x,a),Bool.or(G.lt(y,b),Bool.and(U32.is_eq(y,b),G.lt(z,c)))))

@unsafe
def nonempty(+write: Bk.TagWrite,tail: List<&2,Bk.TagWrite>) -> List<&2,Bk.TagWrite>:
  match write:
    case Bk.TagWrite{_,tag}: present(U32.is_ne(M.tag(tag),0),write,tail)

@unsafe
def present(yes: Bool,write: Bk.TagWrite,tail: List<&2,Bk.TagWrite>) -> List<&2,Bk.TagWrite>:
  match yes:
    case True{}: write <> tail
    case False{}: tail

@unsafe
def insert(+write: Bk.TagWrite,+xs: List<&2,Bk.TagWrite>) -> List<&2,Bk.TagWrite>:
  match write xs:
    case _ Nil{}: nonempty(write,[])
    case Bk.TagWrite{a,_} Bk.TagWrite{+b,tag} <> tail: insert_equal(P.equal(a,b),less(a,b),write,Bk.TagWrite{b,tag},tail)

@unsafe
def insert_equal(equal: Bool,before: Bool,+write: Bk.TagWrite,head: Bk.TagWrite,tail: List<&2,Bk.TagWrite>) -> List<&2,Bk.TagWrite>:
  match equal before:
    case True{} _: nonempty(write,tail)
    case False{} True{}: nonempty(write,head <> tail)
    case _ _: head <> insert(write,tail)

@unsafe
def updates(xs: List<&2,Bk.TagWrite>,acc: List<&2,Bk.TagWrite>) -> List<&2,Bk.TagWrite>:
  match xs:
    case Nil{}: acc
    case write <> rest: updates(rest,insert(write,acc))

@unsafe
def tags(xs: List<&2,Bk.TagWrite>) -> List<&2,M.Node>:
  match xs:
    case Nil{}: []
    case Bk.TagWrite{_,tag} <> rest: tag <> tags(rest)

@unsafe
def root(+ctx: Context,data: Records,entities: List<&2,M.Node>,blocks: List<&2,Bk.TagWrite>) -> M.Node:
  match data:
    case Records{starts,refs}: V.array([V.integer(10),V.object([M.Field{key(ctx,11n),V.array([V.integer(10),V.object([M.Field{key(ctx,9n),starts},M.Field{key(ctx,10n),refs}])])},M.Field{key(ctx,12n),list(entities)},M.Field{key(ctx,13n),list(tags(blocks))}])])

# Chunk.metadata consumes the encoded named fields, without a root TAG_End.
# No JSON conversion, host serialization, or loss of original numeric bits.
@unsafe
def encode(-A: Type,doc: R.Document,root: M.Node,next: R.Document -> Result<&1,&1,U32,Z.Bytes> -> A) -> A:
  payload(A,doc,M.item(root,1),B.new(),doc => w => next(doc,B.done(w)))

@unsafe
def payload(-A: Type,doc: R.Document,node: M.Node,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match node:
    case M.Object{_,xs}: fields(A,doc,xs,w,next)
    case _: next(doc,B.fail(w,869))

@unsafe
def fields(-A: Type,doc: R.Document,xs: List<&2,M.Field>,w: B.Writer,next: R.Document -> B.Writer -> A) -> A:
  match xs:
    case Nil{}: next(doc,w)
    case M.Field{id,+tag} <> rest: N.string(A,doc,id,B.byte(w,M.int(M.item(tag,0),0)),doc => w => N.write(A,doc,tag,w,1,doc => w => fields(A,doc,rest,w,next)))
