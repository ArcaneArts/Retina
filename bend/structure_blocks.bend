import Base
import ./structure_template.bend as T
import ./structure_processors.bend as S
import ./structure_registry.bend as M
import ./structure_values.bend as V
import ./structure_geometry.bend as G
import ./structure_plan.bend as Plan
import ./structure_random.bend as R
import ./registry.bend as Registry
import ./registry_density.bend as D
import ./placement_world.bend as E
import ./placement_data.bend as P
import ./placement_random.bend as Mix
import ./ore_random.bend as O
import ./word64.bend as W

type Metadata is Data:
  Metadata{keys: D.Keys}
type TagWrite is Data:
  TagWrite{position: P.Position,tag: M.Node}
type Placement is Data:
  Placement{world: E.World,tags: List<&2,TagWrite>,counts: List<&2,S.Count>,valid: Bool}
# Bounds identify one resident 16x16 chunk. Capped processors visit the full
# template, including outside blocks, before cropping writes into that chunk.
type Context is Data:
  Context{environment: S.Environment,metadata: Metadata,program: S.Program,origin: P.Position,rotation: U32,
    bounds: G.Box,terrain: Maybe<&2,Plan.Heights>,matching: Bool,ignore_air: Bool,seed: W.U64,stone: U32}

@unsafe
def metadata(-A: Type,doc: Registry.Document,next: Registry.Document -> Metadata -> A) -> A:
  D.resolved(A,["LootTable","x","y","z","keepPacked","LootTableSeed"],doc,[],doc => keys => next(doc,Metadata{keys}))

@unsafe
def key(+m: Metadata,+i: Nat) -> U32:
  match m:
    case Metadata{keys}: key_or(D.key(keys,i),U32.add(4294967280,U32.from_nat(i)))

@unsafe
def key_or(value: U32,default: U32) -> U32:
  match value:
    case 4294967295: default
    case _: value

@unsafe
def seed(+seed: W.U64,+pos: P.Position) -> W.U64:
  match pos:
    case P.Position{x,y,z}: W.xor(seed,W.xor(Mix.mixed(O.signed(x)),W.xor(rotate(Mix.mixed(O.signed(y)),21),rotate(Mix.mixed(O.signed(z)),42))))

@unsafe
def rotate(+v: W.U64,+n: U32) -> W.U64:
  W.xor(W.shl(v,n),W.shr(v,U32.sub(64,n)))

@unsafe
def place(+template: T.Template,+ctx: Context,+world: E.World) -> Placement:
  match template:
    case T.Template{blocks,_,True{}}: rows(blocks,ctx,Placement{world,[],[],True{}})
    case _: Placement{world,[],[],False{}}

@unsafe
def rows(+blocks: List<&2,T.Block>,+ctx: Context,+out: Placement) -> Placement:
  match blocks:
    case Nil{}: out
    case block <> rest: rows(rest,ctx,place_block(block,ctx,out))

@unsafe
def place_block(+block: T.Block,+ctx: Context,+out: Placement) -> Placement:
  match block ctx:
    case T.Block{local,_,original,_,skip,_} Context{_,_,_,origin,_,_,_,_,ignore_air,_,_}:
      eligible(Bool.not(Bool.or(skip,Bool.and(ignore_air,U32.is_zero(original)))),block,ctx,out,G.add(origin,local))

@unsafe
def eligible(ok: Bool,+block: T.Block,+ctx: Context,+out: Placement,pos: P.Position) -> Placement:
  match ok:
    case False{}: out
    case True{}: at_position(block,ctx,out,projected(block,ctx,pos))

@unsafe
def projected(+block: T.Block,+ctx: Context,+pos: P.Position) -> P.Position:
  match block ctx pos:
    case T.Block{P.Position{_,dy,_},_,_,_,_,_} Context{_,_,_,_,_,_,Some{h},True{},_,_,_} P.Position{x,_,z}: P.Position{x,U32.add(U32.sub(Plan.height(h,x,z),1),dy),z}
    case _ _ _: pos

@unsafe
def at_position(+block: T.Block,+ctx: Context,+out: Placement,+pos: P.Position) -> Placement:
  match ctx:
    case Context{_,_,S.Program{_,n},_,_,bounds,_,_,_,_,_}: candidate(Bool.or(G.inside(bounds,pos),U32.is_gt(n,0)),block,ctx,out,pos)

@unsafe
def candidate(ok: Bool,+block: T.Block,+ctx: Context,+out: Placement,+pos: P.Position) -> Placement:
  match ok block ctx out:
    case False{} _ _ _: out
    case True{} T.Block{_,_,_,material,_,tag} Context{env,_,program,origin,rotation,bounds,_,_,_,start,stone} Placement{world,_,counts,_}:
      applied(S.apply(program,env,S.Context{pos,origin,world_material(G.inside(bounds,pos),world,pos,stone),rotation},
        S.State{material,tag,R.new(seed(start,pos)),counts,True{}}),ctx,out,pos)

@unsafe
def world_material(inside: Bool,+world: E.World,+pos: P.Position,stone: U32) -> U32:
  match inside:
    case False{}: stone
    case True{}: known_material(E.material(world,pos),stone)

@unsafe
def known_material(value: Maybe<&2,U32>,stone: U32) -> U32:
  match value:
    case None{}: stone
    case Some{value}: value

@unsafe
def applied(+state: S.State,+ctx: Context,+out: Placement,+pos: P.Position) -> Placement:
  match state ctx out:
    case S.State{_,_,_,counts,keep} Context{_,_,_,_,_,bounds,_,_,_,_,_} Placement{world,tags,_,valid}:
      applied_inside(Bool.and(keep,G.inside(bounds,pos)),state,ctx,Placement{world,tags,counts,valid},pos)

@unsafe
def applied_inside(inside: Bool,+state: S.State,+ctx: Context,+out: Placement,+pos: P.Position) -> Placement:
  match inside state out:
    case False{} _ _: out
    case True{} S.State{material,_,_,_,_} Placement{world,_,_,_}: write_known(Bool.and(known(E.material(world,pos)),valid_material(ctx,material)),state,ctx,out,pos)

@unsafe
def final_tag(material: U32,+tag: M.Node,+ctx: Context,+pos: P.Position) -> M.Node:
  final_present(Bool.and(U32.is_ne(material,0),U32.is_ne(M.tag(tag),0)),tag,ctx,pos)

@unsafe
def final_present(present: Bool,+tag: M.Node,+ctx: Context,+pos: P.Position) -> M.Node:
  match present ctx pos:
    case False{} _ _: M.empty()
    case True{} Context{_,m,_,_,_,bounds,_,_,_,seed,_} P.Position{x,y,z}: loot_seed(Bool.or(U32.is_ne(M.tag(M.field(M.item(tag,1),key(m,0n))),0),U32.is_ne(M.tag(M.field(M.item(tag,1),4294967280)),0)),
      V.put(V.put(V.put(V.put(tag,key(m,1n),V.array([V.integer(3),V.signed_integer(x)])),key(m,2n),V.array([V.integer(3),V.signed_integer(y)])),key(m,3n),V.array([V.integer(3),V.signed_integer(z)])),key(m,4n),V.array([V.integer(1),V.integer(1)])),m,Mix.mixed(W.xor(seed,W.U64{0,index(bounds,pos)})))

@unsafe
def index(+bounds: G.Box,+pos: P.Position) -> U32:
  match bounds pos:
    case G.Box{P.Position{cx,min,cz},_} P.Position{x,y,z}: U32.add(U32.mul(U32.sub(y,min),256),U32.add(U32.mul(U32.sub(z,cz),16),U32.sub(x,cx)))

@unsafe
def loot_seed(present: Bool,tag: M.Node,+m: Metadata,value: W.U64) -> M.Node:
  match present value:
    case False{} _: tag
    case True{} W.U64{hi,lo}: V.put(tag,key(m,5n),V.array([V.integer(4),M.Scalar{Registry.Value{3,hi,lo,0}}]))

@unsafe
def known(value: Maybe<&2,U32>) -> Bool:
  match value:
    case None{}: False{}
    case Some{_}: True{}

@unsafe
def valid_material(+ctx: Context,material: U32) -> Bool:
  match ctx:
    case Context{env,_,_,_,_,_,_,_,_,_,_}: U32.is_ne(S.name(env,material),4294967295)

@unsafe
def write_known(valid: Bool,+state: S.State,+ctx: Context,+out: Placement,+pos: P.Position) -> Placement:
  match valid state out:
    case False{} _ Placement{world,tags,counts,_}: Placement{world,tags,counts,False{}}
    case True{} S.State{material,tag,_,_,_} Placement{world,tags,counts,valid}: Placement{E.put(world,pos,material),TagWrite{pos,final_tag(material,tag,ctx,pos)} <> tags,counts,valid}
