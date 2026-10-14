import Base
import ./registry.bend as Registry
import ./structure_registry.bend as M
import ./structure_geometry.bend as G
import ./structure_random.bend as R
import ./placement_shuffle.bend as Shuffle
import ./placement_data.bend as P
import ./word64.bend as W
import ./density.bend as Q
import ./noise.bend as N

# Planning is CPU Bend over immutable registry geometry. Height tiles are
# supplied by the terrain backend, so no CPU approximation of GPU noise occurs.
type Heights is Data:
  Heights{x: U32,z: U32,width: U32,values: Q.Table<U32>}
type Piece is Data:
  Piece{element: M.Node,pos: P.Position,rotation: U32,box: G.Box,palette: U32,context: U32,depth: U32,priority: U32}
type Layout is Data:
  Layout{state: W.U64,pieces: List<&2,Piece>,queue: List<&2,U32>}
type Alias is Data:
  Alias{source: U32,target: U32}
type Aliases is Data:
  Aliases{state: W.U64,values: List<&2,Alias>}
type Selected is Data:
  Selected{state: W.U64,node: M.Node}
type Attempt is Data:
  Attempt{state: W.U64,piece: Maybe<&2,Piece>}
type Context is Data:
  Context{model: M.Model,heights: Maybe<&2,Heights>,limit: G.Box,max_depth: U32,expansion: Bool,aliases: List<&2,Alias>}

@unsafe
def indices(+i: U32,+n: U32) -> List<&2,U32>:
  match n:
    case 0: []
    case _: i <> indices(U32.inc(i),U32.sub(n,1))

@unsafe
def list_item(+xs: List<&2,Piece>,+i: U32) -> Piece:
  match xs i:
    case p <> _ 0: p
    case _ <> rest _: list_item(rest,U32.sub(i,1))
    case Nil{} _: Piece{M.empty(),P.Position{0,0,0},0,G.Box{P.Position{0,0,0},P.Position{0,0,0}},0,0,0,0}

@unsafe
def array(+m: M.Model,+i: Nat) -> M.Node:
  M.get(m,M.root(m),i)

@unsafe
def pool(+m: M.Model,+name: U32) -> M.Node:
  M.field(array(m,3n),name)

@unsafe
def primary(+m: M.Model,+element: M.Node) -> M.Node:
  G.template(m,M.item(M.get(m,element,24n),0))

@unsafe
def height(+h: Heights,+x: U32,+z: U32) -> U32:
  match h:
    case Heights{ox,oz,+width,values}: Q.get(U32,values,U32.add(G.min(G.max(U32.sub(x,ox),0),U32.sub(width,1)),
      U32.mul(G.min(G.max(U32.sub(z,oz),0),U32.sub(width,1)),width)),0)

@unsafe
def weight(+m: M.Model,+node: M.Node) -> U32:
  M.int(M.get(m,node,28n),1)

@unsafe
def total(+m: M.Model,+xs: M.Node,+i: U32,+n: U32,+acc: U32) -> U32:
  match n:
    case 0: acc
    case _: total(m,xs,U32.inc(i),U32.sub(n,1),U32.add(acc,weight(m,M.item(xs,i))))

@unsafe
def choose(+m: M.Model,+xs: M.Node,+state: W.U64) -> Selected:
  choose_total(total(m,xs,0,M.count(xs),0),m,xs,state)

@unsafe
def choose_total(+sum: U32,+m: M.Model,+xs: M.Node,+state: W.U64) -> Selected:
  match sum:
    case 0: Selected{state,M.empty()}
    case _: choose_draw(R.integer(state,sum),m,xs)

@unsafe
def choose_draw(+draw: R.Draw,+m: M.Model,+xs: M.Node) -> Selected:
  match draw:
    case R.Draw{state,pick}: Selected{state,chosen(m,xs,0,pick)}

@unsafe
def chosen(+m: M.Model,+xs: M.Node,+i: U32,+pick: U32) -> M.Node:
  chosen_here(U32.is_lt(pick,weight(m,M.item(xs,i))),m,xs,i,pick)

@unsafe
def chosen_here(+here: Bool,+m: M.Model,+xs: M.Node,+i: U32,+pick: U32) -> M.Node:
  match here:
    case True{}: M.item(xs,i)
    case False{}: chosen(m,xs,U32.inc(i),U32.sub(pick,weight(m,M.item(xs,i))))

@unsafe
def repeated(+n: U32,+id: U32,+acc: List<&2,U32>) -> List<&2,U32>:
  match n:
    case 0: acc
    case _: repeated(U32.sub(n,1),id,id <> acc)

@unsafe
def slots(+m: M.Model,+xs: M.Node,+i: U32,+n: U32,+acc: List<&2,U32>) -> List<&2,U32>:
  match n:
    case 0: List.reverse(&2,U32,acc)
    case _: slots(m,xs,U32.inc(i),U32.sub(n,1),repeated(weight(m,M.item(xs,i)),i,acc))

@unsafe
def alias_name(+xs: List<&2,Alias>,+name: U32) -> U32:
  match xs:
    case Nil{}: name
    case Alias{from,to} <> rest: alias_equal(U32.is_eq(from,name),to,rest,name)

@unsafe
def alias_equal(+equal: Bool,+to: U32,+rest: List<&2,Alias>,+name: U32) -> U32:
  match equal:
    case True{}: to
    case False{}: alias_name(rest,name)

@unsafe
def aliases(+m: M.Model,+node: M.Node,+state: W.U64,+acc: List<&2,Alias>) -> Aliases:
  alias_kind(M.tag(node),m,node,state,acc)

@unsafe
def alias_kind(+tag: U32,+m: M.Model,+node: M.Node,+state: W.U64,+acc: List<&2,Alias>) -> Aliases:
  match tag:
    case 6: alias_array(m,node,0,M.count(node),state,acc)
    case 7: alias_group(Bool.or(M.named(m,M.get(m,node,66n),67n),M.named(m,M.get(m,node,66n),68n)),m,node,state,acc)
    case _: Aliases{state,acc}

@unsafe
def alias_array(+m: M.Model,+xs: M.Node,+i: U32,+n: U32,+state: W.U64,+acc: List<&2,Alias>) -> Aliases:
  match n:
    case 0: Aliases{state,acc}
    case _: alias_continue(aliases(m,M.item(xs,i),state,acc),m,xs,U32.inc(i),U32.sub(n,1))

@unsafe
def alias_continue(+result: Aliases,+m: M.Model,+xs: M.Node,+i: U32,+n: U32) -> Aliases:
  match result:
    case Aliases{state,acc}: alias_array(m,xs,i,n,state,acc)

@unsafe
def alias_group(+group: Bool,+m: M.Model,+node: M.Node,+state: W.U64,+acc: List<&2,Alias>) -> Aliases:
  match group:
    case True{}: alias_chosen(choose(m,M.get(m,node,69n),state),m,acc)
    case False{}: alias_random(Bool.or(M.named(m,M.get(m,node,66n),72n),M.named(m,M.get(m,node,66n),73n)),m,node,state,acc)

@unsafe
def alias_chosen(+chosen: Selected,+m: M.Model,+acc: List<&2,Alias>) -> Aliases:
  match chosen:
    case Selected{state,node}: aliases(m,M.get(m,node,70n),state,acc)

@unsafe
def alias_random(+random: Bool,+m: M.Model,+node: M.Node,+state: W.U64,+acc: List<&2,Alias>) -> Aliases:
  match random:
    case False{}: alias_added(M.text(M.get(m,node,71n)),M.text(M.get(m,node,18n)),state,acc)
    case True{}: alias_target(choose(m,M.get(m,node,74n),state),m,M.text(M.get(m,node,71n)),acc)

@unsafe
def alias_target(+result: Selected,+m: M.Model,+name: U32,+acc: List<&2,Alias>) -> Aliases:
  match result:
    case Selected{state,node}: alias_added(name,M.text(M.get(m,node,70n)),state,acc)

@unsafe
def alias_added(+name: U32,+target: U32,+state: W.U64,+acc: List<&2,Alias>) -> Aliases:
  alias_valid(Bool.and(U32.is_ne(name,4294967295),U32.is_ne(target,4294967295)),name,target,state,acc)

@unsafe
def alias_valid(+valid: Bool,+name: U32,+target: U32,+state: W.U64,+acc: List<&2,Alias>) -> Aliases:
  match valid:
    case False{}: Aliases{state,acc}
    case True{}: Aliases{state,Alias{name,target} <> acc}

@unsafe
def anchor(+m: M.Model,+v: M.Node,+min_y: U32,+height: U32) -> U32:
  anchor_tag(M.tag(v),m,v,min_y,height)

@unsafe
def anchor_tag(+tag: U32,+m: M.Model,+v: M.Node,+min_y: U32,+height: U32) -> U32:
  match tag:
    case 3: M.int(v,0)
    case _: anchor_absolute(U32.is_ne(M.tag(M.get(m,v,52n)),0),m,v,min_y,height)

@unsafe
def anchor_absolute(+absolute: Bool,+m: M.Model,+v: M.Node,+min_y: U32,+height: U32) -> U32:
  match absolute:
    case True{}: M.int(M.get(m,v,52n),0)
    case False{}: anchor_bottom(U32.is_ne(M.tag(M.get(m,v,53n)),0),m,v,min_y,height)

@unsafe
def anchor_bottom(+bottom: Bool,+m: M.Model,+v: M.Node,+min_y: U32,+height: U32) -> U32:
  match bottom:
    case True{}: U32.add(min_y,M.int(M.get(m,v,53n),0))
    case False{}: U32.sub(U32.sub(U32.add(min_y,height),1),M.int(M.get(m,v,54n),0))

@unsafe
def sample_height(+m: M.Model,+v: M.Node,+state: W.U64,+min_y: U32,+height: U32) -> R.Draw:
  height_kind(M.tag(v),m,v,state,min_y,height)

@unsafe
def height_kind(+tag: U32,+m: M.Model,+v: M.Node,+state: W.U64,+min_y: U32,+height: U32) -> R.Draw:
  match tag:
    case 0: R.Draw{state,0}
    case 3: R.Draw{state,anchor(m,v,min_y,height)}
    case _: height_absolute(U32.is_ne(M.tag(M.get(m,v,52n)),0),m,v,state,min_y,height)

@unsafe
def height_absolute(+absolute: Bool,+m: M.Model,+v: M.Node,+state: W.U64,+min_y: U32,+height: U32) -> R.Draw:
  match absolute:
    case True{}: R.Draw{state,anchor(m,v,min_y,height)}
    case False{}: height_value(U32.is_ne(M.tag(M.get(m,v,55n)),0),m,v,state,min_y,height)

@unsafe
def height_value(+has: Bool,+m: M.Model,+v: M.Node,+state: W.U64,+min_y: U32,+height: U32) -> R.Draw:
  match has:
    case True{}: R.Draw{state,anchor(m,M.get(m,v,55n),min_y,height)}
    case False{}: height_range(m,v,state,min_y,height,height_low(m,M.get(m,v,56n),min_y,height))

@unsafe
def height_low(+m: M.Model,+v: M.Node,+min_y: U32,+height: U32) -> U32:
  height_low_present(U32.is_ne(M.tag(v),0),m,v,min_y,height)

@unsafe
def height_low_present(+present: Bool,+m: M.Model,+v: M.Node,+min_y: U32,+height: U32) -> U32:
  match present:
    case False{}: 0
    case True{}: anchor(m,v,min_y,height)

@unsafe
def height_range(+m: M.Model,+v: M.Node,+state: W.U64,+min_y: U32,+height: U32,+low: U32) -> R.Draw:
  height_high(U32.is_ne(M.tag(M.get(m,v,57n)),0),m,M.get(m,v,57n),state,min_y,height,low)

@unsafe
def height_high(+present: Bool,+m: M.Model,+v: M.Node,+state: W.U64,+min_y: U32,+height: U32,+low: U32) -> R.Draw:
  match present:
    case False{}: height_draw(R.integer(state,1),low)
    case True{}: height_draw(R.integer(state,G.max(1,U32.inc(U32.sub(anchor(m,v,min_y,height),low)))),low)

@unsafe
def height_draw(+draw: R.Draw,+low: U32) -> R.Draw:
  match draw:
    case R.Draw{state,v}: R.Draw{state,U32.add(low,v)}

@unsafe
def joint_priority(+m: M.Model,+joints: M.Node,+id: U32) -> U32:
  M.int(M.get(m,M.item(joints,id),21n),0)

@unsafe
def sorted_joints(+m: M.Model,+joints: M.Node,+xs: List<&2,U32>,+acc: List<&2,U32>) -> List<&2,U32>:
  match xs:
    case Nil{}: acc
    case id <> rest: sorted_joints(m,joints,rest,insert_joint(m,joints,id,acc))

@unsafe
def insert_joint(+m: M.Model,+joints: M.Node,+id: U32,+xs: List<&2,U32>) -> List<&2,U32>:
  match xs:
    case Nil{}: [id]
    case +x <> rest: insert_joint_before(G.lt(joint_priority(m,joints,x),joint_priority(m,joints,id)),m,joints,id,x,rest)

@unsafe
def insert_joint_before(+before: Bool,+m: M.Model,+joints: M.Node,+id: U32,+x: U32,+rest: List<&2,U32>) -> List<&2,U32>:
  match before:
    case True{}: id <> x <> rest
    case False{}: x <> insert_joint(m,joints,id,rest)

@unsafe
def priority(+piece: Piece) -> U32:
  match piece:
    case Piece{_,_,_,_,_,_,_,priority}: priority

@unsafe
def queue_insert(+pieces: List<&2,Piece>,+id: U32,+queue: List<&2,U32>) -> List<&2,U32>:
  match queue:
    case Nil{}: [id]
    case +x <> rest: queue_before(G.lt(priority(list_item(pieces,x)),priority(list_item(pieces,id))),pieces,id,x,rest)

@unsafe
def queue_before(+before: Bool,+pieces: List<&2,Piece>,+id: U32,+x: U32,+rest: List<&2,U32>) -> List<&2,U32>:
  match before:
    case True{}: id <> x <> rest
    case False{}: x <> queue_insert(pieces,id,rest)

@unsafe
def box(+piece: Piece) -> G.Box:
  match piece:
    case Piece{_,_,_,b,_,_,_,_}: b

@unsafe
def collides(+xs: List<&2,Piece>,+context: U32,+b: G.Box) -> Bool:
  match xs:
    case Nil{}: False{}
    case Piece{_,_,_,other,_,c,_,_} <> rest: collision(Bool.and(U32.is_eq(context,c),G.intersects(b,other)),rest,context,b)

@unsafe
def collision(+hit: Bool,+rest: List<&2,Piece>,+context: U32,+b: G.Box) -> Bool:
  match hit:
    case True{}: True{}
    case False{}: collides(rest,context,b)

@unsafe
def pool_height(+m: M.Model,+entries: M.Node,+i: U32,+n: U32,+acc: U32) -> U32:
  match n:
    case 0: acc
    case _: pool_height(m,entries,U32.inc(i),U32.sub(n,1),G.max(acc,element_height(m,M.item(entries,i))))

@unsafe
def element_height(+m: M.Model,+e: M.Node) -> U32:
  element_nonempty(U32.is_gt(M.count(M.get(m,e,24n)),0),m,e)

@unsafe
def element_nonempty(+nonempty: Bool,+m: M.Model,+e: M.Node) -> U32:
  match nonempty:
    case False{}: 0
    case True{}: box_height(G.bounds(m,e,P.Position{0,0,0},0))

@unsafe
def box_height(+b: G.Box) -> U32:
  match b:
    case G.Box{P.Position{_,lo,_},P.Position{_,hi,_}}: U32.inc(U32.sub(hi,lo))

@unsafe
def expansion(+c: Context,+e: M.Node,+r: U32) -> U32:
  match c:
    case Context{m,_,_,_,enabled,aliases}: expansion_enabled(Bool.and(enabled,U32.is_le(box_height(G.bounds(m,e,P.Position{0,0,0},r)),16)),m,e,r,aliases)

@unsafe
def expansion_enabled(+enabled: Bool,+m: M.Model,+e: M.Node,+r: U32,+aliases: List<&2,Alias>) -> U32:
  match enabled:
    case False{}: 0
    case True{}: expansion_joints(m,M.get(m,primary(m,e),13n),r,G.bounds(m,e,P.Position{0,0,0},r),aliases,0,M.count(M.get(m,primary(m,e),13n)),0)

@unsafe
def expansion_joints(+m: M.Model,+joints: M.Node,+r: U32,+b: G.Box,+aliases: List<&2,Alias>,+i: U32,+n: U32,+acc: U32) -> U32:
  match n:
    case 0: acc
    case _: expansion_joints(m,joints,r,b,aliases,U32.inc(i),U32.sub(n,1),G.max(acc,expansion_joint(m,M.item(joints,i),r,b,aliases)))

@unsafe
def expansion_joint(+m: M.Model,+j: M.Node,+r: U32,+b: G.Box,+aliases: List<&2,Alias>) -> U32:
  expansion_internal(G.inside(b,G.add(G.rotate(G.position(M.get(m,j,14n)),r),G.direction(M.int(M.get(m,j,15n),0),r))),m,pool(m,alias_name(aliases,M.text(M.get(m,j,19n)))))

@unsafe
def expansion_internal(+internal: Bool,+m: M.Model,+p: M.Node) -> U32:
  match internal:
    case False{}: 0
    case True{}: G.max(max_pool(m,p),max_pool(m,pool(m,M.text(M.get(m,p,29n)))))

@unsafe
def max_pool(+m: M.Model,+p: M.Node) -> U32:
  pool_height(m,M.get(m,p,30n),0,M.count(M.get(m,p,30n)),0)

@unsafe
def expanded(+b: G.Box,+expand: U32) -> G.Box:
  match b:
    case G.Box{+lo,P.Position{x,hi,z}}: expanded_low(lo,x,hi,z,expand)

@unsafe
def expanded_low(+lo: P.Position,+x: U32,+hi: U32,+z: U32,+expand: U32) -> G.Box:
  match lo:
    case P.Position{_,y,_}: G.Box{lo,P.Position{x,N.word(U32.is_gt(expand,0),G.max(hi,U32.add(U32.add(y,expand),1)),hi),z}}

@unsafe
def projected(+h: Maybe<&2,Heights>,+matching: Bool,+pos: P.Position,+jp: P.Position,+other: P.Position) -> P.Position:
  match h matching pos jp other:
    case Some{h} True{} P.Position{x,_,z} P.Position{a,_,b} P.Position{_,y,_}: P.Position{x,U32.sub(height(h,a,b),y),z}
    case _ _ _ _ _: pos

@unsafe
def compatible(+m: M.Model,+joint: M.Node,+other: M.Node,+sr: U32,+r: U32) -> Bool:
  Bool.and(G.equal(G.direction(M.int(M.get(m,other,15n),0),r),G.sub(P.Position{0,0,0},G.direction(M.int(M.get(m,joint,15n),0),sr))),
    Bool.and(U32.is_eq(M.text(M.get(m,joint,18n)),M.text(M.get(m,other,17n))),
      Bool.or(Bool.not(M.named(m,M.get(m,joint,20n),50n)),G.equal(G.direction(M.int(M.get(m,joint,16n),0),sr),G.direction(M.int(M.get(m,other,16n),0),r)))))

@unsafe
def place(+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+e: M.Node,+r: U32,+other: M.Node,+state: W.U64) -> Attempt:
  match c source:
    case Context{m,h,limit,_,_,_} Piece{se,sp,sr,sb,_,sc,depth,_}:
      +jp = G.add(sp,G.rotate(G.position(M.get(m,joint,14n)),sr))
      +target = G.add(jp,G.direction(M.int(M.get(m,joint,15n),0),sr))
      +context = N.word(G.inside(sb,target),U32.inc(source_index),sc)
      +pos = projected(h,Bool.or(M.flag(M.get(m,se,27n)),M.flag(M.get(m,e,27n))),G.sub(target,G.rotate(G.position(M.get(m,other,14n)),r)),jp,G.position(M.get(m,other,14n)))
      +b = expanded(G.bounds(m,e,pos,r),expansion(c,e,r))
      placed(Bool.and(G.contains(limit,b),Bool.and(context_fits(context,pieces,b),Bool.not(collides(pieces,context,b)))),
        m,e,pos,r,b,context,U32.inc(depth),M.int(M.get(m,joint,22n),0),state)

@unsafe
def context_fits(+context: U32,+pieces: List<&2,Piece>,+b: G.Box) -> Bool:
  match context:
    case 0: True{}
    case _: G.contains(box(list_item(pieces,U32.sub(context,1))),b)

@unsafe
def placed(+ok: Bool,+m: M.Model,+e: M.Node,+pos: P.Position,+r: U32,+b: G.Box,+context: U32,+depth: U32,+priority: U32,+state: W.U64) -> Attempt:
  match ok:
    case False{}: Attempt{state,None{}}
    case True{}: palette(R.integer(state,M.count(M.get(m,primary(m,e),8n))),e,pos,r,b,context,depth,priority)

@unsafe
def palette(+draw: R.Draw,+e: M.Node,+pos: P.Position,+r: U32,+b: G.Box,+context: U32,+depth: U32,+priority: U32) -> Attempt:
  match draw:
    case R.Draw{state,n}: Attempt{state,Some{Piece{e,pos,r,b,n,context,depth,priority}}}

@unsafe
def try_targets(+ids: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+e: M.Node,+r: U32,+joints: M.Node,+state: W.U64) -> Attempt:
  match ids:
    case Nil{}: Attempt{state,None{}}
    case id <> rest: try_target(rest,c,source,source_index,pieces,joint,e,r,joints,M.item(joints,id),state)

@unsafe
def try_target(+rest: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+e: M.Node,+r: U32,+joints: M.Node,+other: M.Node,+state: W.U64) -> Attempt:
  match c source:
    case Context{m,_,_,_,_,_} Piece{_,_,sr,_,_,_,_,_}: target_match(compatible(m,joint,other,sr,r),rest,c,source,source_index,pieces,joint,e,r,joints,other,state)

@unsafe
def target_match(+matches: Bool,+rest: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+e: M.Node,+r: U32,+joints: M.Node,+other: M.Node,+state: W.U64) -> Attempt:
  match matches:
    case False{}: try_targets(rest,c,source,source_index,pieces,joint,e,r,joints,state)
    case True{}: target_result(place(c,source,source_index,pieces,joint,e,r,other,state),rest,c,source,source_index,pieces,joint,e,r,joints)

@unsafe
def target_result(+result: Attempt,+rest: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+e: M.Node,+r: U32,+joints: M.Node) -> Attempt:
  match result:
    case Attempt{state,None{}}: try_targets(rest,c,source,source_index,pieces,joint,e,r,joints,state)
    case _: result

@unsafe
def rotations(+ids: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+e: M.Node,+state: W.U64) -> Attempt:
  match ids c:
    case Nil{} _: Attempt{state,None{}}
    case r <> rest Context{m,_,_,_,_,_}: rotation_targets(R.shuffle(indices(0,M.count(M.get(m,primary(m,e),13n))),state),rest,c,source,source_index,pieces,joint,e,r,M.get(m,primary(m,e),13n))

@unsafe
def rotation_targets(+shuffled: Shuffle.Shuffled,+rest: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+e: M.Node,+r: U32,+joints: M.Node) -> Attempt:
  match shuffled c:
    case Shuffle.Shuffled{state,ids} Context{m,_,_,_,_,_}: rotation_result(try_targets(sorted_joints(m,joints,ids,[]),c,source,source_index,pieces,joint,e,r,joints,state),rest,c,source,source_index,pieces,joint,e)

@unsafe
def rotation_result(+result: Attempt,+rest: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+e: M.Node) -> Attempt:
  match result:
    case Attempt{state,None{}}: rotations(rest,c,source,source_index,pieces,joint,e,state)
    case _: result

@unsafe
def elements(+ids: List<&2,U32>,+entries: M.Node,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+state: W.U64) -> Attempt:
  match ids c:
    case Nil{} _: Attempt{state,None{}}
    case id <> rest Context{m,_,_,_,_,_}: element_nonempty_attempt(U32.is_gt(M.count(M.get(m,M.item(entries,id),24n)),0),rest,entries,c,source,source_index,pieces,joint,M.item(entries,id),state)

@unsafe
def element_nonempty_attempt(+nonempty: Bool,+rest: List<&2,U32>,+entries: M.Node,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+e: M.Node,+state: W.U64) -> Attempt:
  match nonempty:
    case False{}: Attempt{state,None{}}
    case True{}: element_rotations(R.shuffle([0,1,2,3],state),rest,entries,c,source,source_index,pieces,joint,e)

@unsafe
def element_rotations(+shuffled: Shuffle.Shuffled,+rest: List<&2,U32>,+entries: M.Node,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+e: M.Node) -> Attempt:
  match shuffled:
    case Shuffle.Shuffled{state,ids}: element_result(rotations(ids,c,source,source_index,pieces,joint,e,state),rest,entries,c,source,source_index,pieces,joint)

@unsafe
def element_result(+result: Attempt,+rest: List<&2,U32>,+entries: M.Node,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node) -> Attempt:
  match result:
    case Attempt{state,None{}}: elements(rest,entries,c,source,source_index,pieces,joint,state)
    case _: result

@unsafe
def node_list(+xs: M.Node,+i: U32,+n: U32) -> List<&2,M.Node>:
  match n:
    case 0: []
    case _: M.item(xs,i) <> node_list(xs,U32.inc(i),U32.sub(n,1))

@unsafe
def shifted(+xs: List<&2,U32>,+offset: U32) -> List<&2,U32>:
  match xs:
    case Nil{}: []
    case x <> rest: U32.add(x,offset) <> shifted(rest,offset)

@unsafe
def combine(+a: M.Node,+b: M.Node) -> M.Node:
  M.Array{Registry.Value{6,U32.add(M.count(a),M.count(b)),0,0},Q.prepared_table(M.Node,List.append(&2,M.Node,node_list(a,0,M.count(a)),node_list(b,0,M.count(b))))}

@unsafe
def connection(+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+state: W.U64) -> Attempt:
  match c source:
    case Context{m,_,_,max_depth,_,aliases} Piece{_,_,_,_,_,_,depth,_}:
      connection_pool(c,source,source_index,pieces,joint,state,pool(m,alias_name(aliases,M.text(M.get(m,joint,19n)))),U32.is_lt(depth,max_depth))

@unsafe
def connection_pool(+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+state: W.U64,+p: M.Node,+primary_enabled: Bool) -> Attempt:
  match c:
    case Context{m,_,_,_,_,_}: connection_entries(c,source,source_index,pieces,joint,state,M.get(m,p,30n),M.get(m,pool(m,M.text(M.get(m,p,29n))),30n),primary_enabled)

@unsafe
def connection_entries(+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+state: W.U64,+a: M.Node,+b: M.Node,+enabled: Bool) -> Attempt:
  match c enabled:
    case Context{m,_,_,_,_,_} True{}: connection_primary(R.shuffle(slots(m,a,0,M.count(a),[]),state),c,source,source_index,pieces,joint,a,b)
    case _ False{}: connection_primary(Shuffle.Shuffled{state,[]},c,source,source_index,pieces,joint,a,b)

@unsafe
def connection_primary(+first: Shuffle.Shuffled,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+a: M.Node,+b: M.Node) -> Attempt:
  match first c:
    case Shuffle.Shuffled{state,ids} Context{m,_,_,_,_,_}: connection_fallback(R.shuffle(slots(m,b,0,M.count(b),[]),state),ids,c,source,source_index,pieces,joint,a,b)

@unsafe
def connection_fallback(+second: Shuffle.Shuffled,+ids: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+pieces: List<&2,Piece>,+joint: M.Node,+a: M.Node,+b: M.Node) -> Attempt:
  match second:
    case Shuffle.Shuffled{state,fallback}: elements(List.append(&2,U32,ids,shifted(fallback,M.count(a))),combine(a,b),c,source,source_index,pieces,joint,state)

@unsafe
def walk(+c: Context,+layout: Layout) -> Layout:
  match layout:
    case Layout{state,pieces,Nil{}}: Layout{state,pieces,[]}
    case Layout{state,+pieces,index <> rest}: source_joints(c,list_item(pieces,index),index,pieces,rest,state)

@unsafe
def source_joints(+c: Context,+source: Piece,+index: U32,+pieces: List<&2,Piece>,+queue: List<&2,U32>,+state: W.U64) -> Layout:
  match c source:
    case Context{m,_,_,_,_,_} Piece{e,_,_,_,_,_,_,_}: source_shuffled(R.shuffle(indices(0,M.count(M.get(m,primary(m,e),13n))),state),c,source,index,pieces,queue,M.get(m,primary(m,e),13n))

@unsafe
def source_shuffled(+shuffled: Shuffle.Shuffled,+c: Context,+source: Piece,+index: U32,+pieces: List<&2,Piece>,+queue: List<&2,U32>,+joints: M.Node) -> Layout:
  match shuffled c:
    case Shuffle.Shuffled{state,ids} Context{m,_,_,_,_,_}: connectors(sorted_joints(m,joints,ids,[]),c,source,index,joints,Layout{state,pieces,queue})

@unsafe
def connectors(+ids: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+joints: M.Node,+layout: Layout) -> Layout:
  match ids layout:
    case Nil{} _: walk(c,layout)
    case id <> rest Layout{state,+pieces,queue}: connected(connection(c,source,source_index,pieces,M.item(joints,id),state),rest,c,source,source_index,joints,pieces,queue)

@unsafe
def connected(+attempt: Attempt,+rest: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+joints: M.Node,+pieces: List<&2,Piece>,+queue: List<&2,U32>) -> Layout:
  match attempt c source:
    case Attempt{state,None{}} _ _: connectors(rest,c,source,source_index,joints,Layout{state,pieces,queue})
    case Attempt{state,Some{piece}} Context{_,_,_,max_depth,_,_} Piece{_,_,_,_,_,_,depth,_}: enqueued(rest,c,source,source_index,joints,state,List.append(&2,Piece,pieces,[piece]),queue,U32.is_lt(depth,max_depth))

@unsafe
def enqueued(+rest: List<&2,U32>,+c: Context,+source: Piece,+source_index: U32,+joints: M.Node,+state: W.U64,+pieces: List<&2,Piece>,+queue: List<&2,U32>,+enqueue: Bool) -> Layout:
  match enqueue:
    case False{}: connectors(rest,c,source,source_index,joints,Layout{state,pieces,queue})
    case True{}: connectors(rest,c,source,source_index,joints,Layout{state,pieces,queue_insert(pieces,U32.sub(U32.from_nat(List.length(&2,Piece,pieces)),1),queue)})

@unsafe
def scalar(+n: U32) -> M.Node:
  M.Scalar{Registry.Value{3,0,n,0}}

@unsafe
def standalone(+m: M.Model,+d: M.Node) -> M.Node:
  M.Object{Registry.Value{7,1,0,0},[M.Field{M.key(m,24n),M.Array{Registry.Value{6,1,0,0},Q.Leaf{
    M.Object{Registry.Value{7,1,0,0},[M.Field{M.key(m,25n),M.get(m,d,25n)}]}}}}]}

@unsafe
def begin(+m: M.Model,+d: M.Node,+state: W.U64,+x: U32,+z: U32,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  begin_jigsaw(M.named(m,M.get(m,d,31n),49n),m,d,state,x,z,h,min_y,height)

@unsafe
def begin_jigsaw(+jigsaw: Bool,+m: M.Model,+d: M.Node,+state: W.U64,+x: U32,+z: U32,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  match jigsaw:
    case True{}: root_selected(choose(m,M.get(m,pool(m,M.text(M.get(m,d,19n))),30n),state),m,d,x,z,h,min_y,height)
    case False{}: root_selected(Selected{state,standalone(m,d)},m,d,x,z,h,min_y,height)

@unsafe
def root_selected(+selected: Selected,+m: M.Model,+d: M.Node,+x: U32,+z: U32,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  match selected:
    case Selected{state,+e}: root_nonempty(U32.is_gt(M.count(M.get(m,e,24n)),0),m,d,e,state,x,z,h,min_y,height)

@unsafe
def root_nonempty(+nonempty: Bool,+m: M.Model,+d: M.Node,+e: M.Node,+state: W.U64,+x: U32,+z: U32,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  match nonempty:
    case False{}: Layout{state,[],[]}
    case True{}: root_rotated(R.integer(state,4),m,d,e,x,z,h,min_y,height)

@unsafe
def root_rotated(+draw: R.Draw,+m: M.Model,+d: M.Node,+e: M.Node,+x: U32,+z: U32,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  match draw:
    case R.Draw{state,r}: root_height(sample_height(m,M.get(m,M.get(m,d,33n),51n),state,min_y,height),m,d,e,r,x,z,h,min_y,height)

@unsafe
def root_height(+draw: R.Draw,+m: M.Model,+d: M.Node,+e: M.Node,+r: U32,+x: U32,+z: U32,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  match draw:
    case R.Draw{state,y}: root_position(m,d,e,r,state,root_project(m,e,r,P.Position{U32.mul(x,16),y,U32.mul(z,16)},h),h,min_y,height)

@unsafe
def root_project(+m: M.Model,+e: M.Node,+r: U32,+pos: P.Position,+h: Maybe<&2,Heights>) -> P.Position:
  match h:
    case None{}: pos
    case Some{h}: root_center(h,pos,G.bounds(m,e,pos,r),M.int(M.get(m,primary(m,e),7n),0))

@unsafe
def root_center(+h: Heights,+pos: P.Position,+b: G.Box,+ground: U32) -> P.Position:
  match pos b:
    case P.Position{x,y,z} G.Box{P.Position{a,_,b},P.Position{c,_,d}}: P.Position{x,U32.sub(U32.add(y,height(h,G.div(U32.add(a,c),2),G.div(U32.add(b,d),2))),ground),z}

@unsafe
def root_position(+m: M.Model,+d: M.Node,+e: M.Node,+r: U32,+state: W.U64,+pos: P.Position,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  root_named(M.text(M.get(m,M.get(m,d,33n),61n)),m,d,e,r,state,pos,h,min_y,height)

@unsafe
def root_named(+name: U32,+m: M.Model,+d: M.Node,+e: M.Node,+r: U32,+state: W.U64,+pos: P.Position,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  match name:
    case 4294967295: root_box(m,d,e,r,state,pos,h,min_y,height)
    case _: root_find(m,d,e,r,state,pos,h,min_y,height,name,M.get(m,primary(m,e),13n),0,M.count(M.get(m,primary(m,e),13n)))

@unsafe
def root_find(+m: M.Model,+d: M.Node,+e: M.Node,+r: U32,+state: W.U64,+pos: P.Position,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32,+name: U32,+joints: M.Node,+i: U32,+n: U32) -> Layout:
  match n:
    case 0: Layout{state,[],[]}
    case _: root_found(U32.is_eq(M.text(M.get(m,M.item(joints,i),17n)),name),m,d,e,r,state,pos,h,min_y,height,name,joints,i,n)

@unsafe
def root_found(+found: Bool,+m: M.Model,+d: M.Node,+e: M.Node,+r: U32,+state: W.U64,+pos: P.Position,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32,+name: U32,+joints: M.Node,+i: U32,+n: U32) -> Layout:
  match found:
    case False{}: root_find(m,d,e,r,state,pos,h,min_y,height,name,joints,U32.inc(i),U32.sub(n,1))
    case True{}: root_box(m,d,e,r,state,G.sub(pos,G.rotate(G.position(M.get(m,M.item(joints,i),14n)),r)),h,min_y,height)

@unsafe
def root_box(+m: M.Model,+d: M.Node,+e: M.Node,+r: U32,+state: W.U64,+pos: P.Position,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  root_in_world(m,d,e,r,state,pos,h,min_y,height,G.bounds(m,e,pos,r))

@unsafe
def root_in_world(+m: M.Model,+d: M.Node,+e: M.Node,+r: U32,+state: W.U64,+pos: P.Position,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32,+b: G.Box) -> Layout:
  match b:
    case G.Box{P.Position{_,lo,_},P.Position{_,hi,_}}: root_valid(Bool.and(Bool.not(G.lt(lo,min_y)),G.lt(hi,U32.add(min_y,height))),m,d,e,r,state,pos,h,min_y,height,b)

@unsafe
def root_valid(+valid: Bool,+m: M.Model,+d: M.Node,+e: M.Node,+r: U32,+state: W.U64,+pos: P.Position,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32,+b: G.Box) -> Layout:
  match valid:
    case False{}: Layout{state,[],[]}
    case True{}: root_palette(R.integer(state,M.count(M.get(m,primary(m,e),8n))),m,d,Piece{e,pos,r,b,0,0,0,0},h,min_y,height)

@unsafe
def root_palette(+draw: R.Draw,+m: M.Model,+d: M.Node,+p: Piece,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  match draw p:
    case R.Draw{state,palette} Piece{e,pos,r,b,_,c,depth,priority}: root_type(M.named(m,M.get(m,d,31n),49n),m,d,Piece{e,pos,r,b,palette,c,depth,priority},state,h,min_y,height)

@unsafe
def root_type(+jigsaw: Bool,+m: M.Model,+d: M.Node,+p: Piece,+state: W.U64,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  match jigsaw:
    case False{}: Layout{state,[p],[]}
    case True{}: root_aliases(aliases(m,M.get(m,M.get(m,d,33n),65n),state,[]),m,d,p,h,min_y,height)

@unsafe
def distance(+m: M.Model,+d: M.Node) -> U32:
  +v = M.get(m,M.get(m,d,33n),62n)
  M.int(v,M.int(M.get(m,v,63n),80))

@unsafe
def limit(+b: G.Box,+distance: U32,+min_y: U32,+height: U32) -> G.Box:
  match b:
    case G.Box{P.Position{a,_,b},P.Position{c,_,d}}:
      +x = G.div(U32.add(a,c),2)
      +z = G.div(U32.add(b,d),2)
      G.Box{P.Position{U32.sub(x,distance),min_y,U32.sub(z,distance)},P.Position{U32.add(x,distance),U32.sub(U32.add(min_y,height),1),U32.add(z,distance)}}

@unsafe
def root_aliases(+result: Aliases,+m: M.Model,+d: M.Node,+p: Piece,+h: Maybe<&2,Heights>,+min_y: U32,+height: U32) -> Layout:
  match result:
    case Aliases{state,aliases}: adjusted(walk(Context{m,h,limit(box(p),distance(m,d),min_y,height),M.int(M.get(m,M.get(m,d,33n),6n),0),M.flag(M.get(m,M.get(m,d,33n),64n)),aliases},Layout{state,[p],[0]}),m,h)

@unsafe
def adjusted(+layout: Layout,+m: M.Model,+h: Maybe<&2,Heights>) -> Layout:
  match layout h:
    case Layout{state,pieces,queue} Some{h}: Layout{state,adjust_pieces(pieces,m,h),queue}
    case _ None{}: layout

@unsafe
def adjust_pieces(+xs: List<&2,Piece>,+m: M.Model,+h: Heights) -> List<&2,Piece>:
  match xs:
    case Nil{}: []
    case p <> rest: adjust_piece(p,m,h) <> adjust_pieces(rest,m,h)

@unsafe
def adjust_piece(+p: Piece,+m: M.Model,+h: Heights) -> Piece:
  match p:
    case Piece{e,_,_,_,_,_,_,_}: adjust_matching(M.flag(M.get(m,e,27n)),p,h)

@unsafe
def adjust_matching(+matching: Bool,+p: Piece,+h: Heights) -> Piece:
  match matching p:
    case False{} _: p
    case True{} Piece{e,pos,r,+b,palette,context,depth,priority}: Piece{e,pos,r,terrain_box(b,h),palette,context,depth,priority}

@unsafe
def terrain_box(+b: G.Box,+h: Heights) -> G.Box:
  match b:
    case G.Box{P.Position{x,lo,z},P.Position{a,hi,c}}: terrain_range(h,x,z,a,c,x,z,2147483647,2147483648,U32.sub(hi,lo))

@unsafe
def terrain_range(+h: Heights,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+x: U32,+z: U32,+lo: U32,+hi: U32,+size: U32) -> G.Box:
  terrain_next(U32.is_eq(x,x1),U32.is_eq(z,z1),h,x0,z0,x1,z1,x,z,G.min(lo,U32.sub(height(h,x,z),1)),G.max(hi,U32.sub(height(h,x,z),1)),size)

@unsafe
def terrain_next(+end_x: Bool,+end_z: Bool,+h: Heights,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+x: U32,+z: U32,+lo: U32,+hi: U32,+size: U32) -> G.Box:
  match end_x end_z:
    case True{} True{}: G.Box{P.Position{x0,lo,z0},P.Position{x1,U32.add(hi,size),z1}}
    case True{} False{}: terrain_range(h,x0,z0,x1,z1,x0,U32.inc(z),lo,hi,size)
    case False{} _: terrain_range(h,x0,z0,x1,z1,U32.inc(x),z,lo,hi,size)
