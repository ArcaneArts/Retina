import Base
import ./placement_world.bend as E
import ./placement_data.bend as P
import ./placement_merge.bend as Merge
import ./material_columns_data.bend as M
import ./density.bend as Q
import ./tree.bend as Tree
import ./tree_decorators.bend as Decorator

# Anchors plan independently against the same resident terrain. Publication uses
# signed X/Z/Y anchor order, never worker completion order. Every accepted command
# is retained, including transient air and same-state changes of role.
type Anchor is Data:
  Anchor{origin: P.Position,commands: List<&2,E.Command>}
type Split is Data:
  Split{left: List<&2,Anchor>,right: List<&2,Anchor>}

@unsafe
def capture(origin: P.Position,world: E.World) -> Anchor:
  Anchor{origin,E.journal(world)}

@unsafe
def point(anchor: Anchor) -> P.Position:
  match anchor:
    case Anchor{at,_}: at

@unsafe
def flatten(xs: Q.Table<Anchor>,acc: List<&2,Anchor>) -> List<&2,Anchor>:
  match xs:
    case Q.Empty{}: acc
    case Q.Leaf{a}: a <> acc
    case Q.Branch{_,a,b}: flatten(a,flatten(b,acc))

@unsafe
def split(+n: U32,xs: List<&2,Anchor>,acc: List<&2,Anchor>) -> Split:
  match n xs:
    case 0 _: Split{List.reverse(&2,Anchor,acc),xs}
    case _ Nil{}: Split{List.reverse(&2,Anchor,acc),[]}
    case _ a <> rest: split(U32.sub(n,1),rest,a <> acc)

@unsafe
def reverse_append(xs: List<&2,Anchor>,acc: List<&2,Anchor>) -> List<&2,Anchor>:
  match xs:
    case Nil{}: acc
    case a <> rest: reverse_append(rest,a <> acc)

@unsafe
def merge(a: List<&2,Anchor>,b: List<&2,Anchor>,acc: List<&2,Anchor>) -> List<&2,Anchor>:
  match a b:
    case Nil{} _: reverse_append(acc,b)
    case _ Nil{}: reverse_append(acc,a)
    case +x <> ar +y <> br: merge_first(P.less(point(x),point(y)),x,ar,y,br,acc)

@unsafe
def merge_first(left: Bool,+x: Anchor,ar: List<&2,Anchor>,+y: Anchor,br: List<&2,Anchor>,acc: List<&2,Anchor>) -> List<&2,Anchor>:
  match left:
    case True{}: merge(ar,y <> br,x <> acc)
    case False{}: merge(x <> ar,br,y <> acc)

@unsafe
def sort(+n: U32,xs: List<&2,Anchor>) -> List<&2,Anchor>:
  sort_more(U32.is_gt(n,1),n,xs)

@unsafe
def sort_more(more: Bool,+n: U32,xs: List<&2,Anchor>) -> List<&2,Anchor>:
  match more:
    case False{}: xs
    case True{}: sorted(split(U32.div(n,2),xs,[]),n)

@unsafe
def sorted(parts: Split,+n: U32) -> List<&2,Anchor>:
  match parts:
    case Split{a,b}: merge(sort(U32.div(n,2),a),sort(U32.sub(n,U32.div(n,2)),b),[])

@unsafe
def valid_command(command: E.Command,+palette: U32) -> Bool:
  match command:
    case E.Command{_,m,+role,+extra}:
      Bool.and(U32.is_lt(m,palette),Bool.and(U32.is_le(role,6),extra_valid(role,extra,palette)))

def extra_valid(role: U32,extra: U32,palette: U32) -> Bool:
  match role:
    case 0: U32.is_lt(extra,palette)
    case 3: U32.is_lt(extra,4)
    case 4: U32.is_lt(extra,4)
    case 5: U32.is_lt(extra,palette)
    case _: True{}

@unsafe
def valid_commands(xs: List<&2,E.Command>,+palette: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case command <> rest: Bool.and(valid_command(command,palette),valid_commands(rest,palette))

@unsafe
def valid(xs: List<&2,Anchor>,previous: Maybe<&2,P.Position>,+palette: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case Anchor{+origin,commands} <> rest:
      Bool.and(after(previous,origin),Bool.and(valid_commands(commands,palette),valid(rest,Some{origin},palette)))

def after(previous: Maybe<&2,P.Position>,origin: P.Position) -> Bool:
  match previous:
    case None{}: True{}
    case Some{p}: P.less(p,origin)

# The final replay needs only its live overlay. Drop each ephemeral journal node
# rather than building a second retained copy of the full command stream.
@unsafe
def unrecorded(world: E.World) -> E.World:
  match world:
    case E.World{source,policy,patch,_}: E.World{source,policy,patch,[]}

@unsafe
def command(+row: E.Command,+world: E.World) -> E.World:
  match row:
    case E.Command{at,_,_,_}: previous(E.material(world,at),row,world)

@unsafe
def previous(value: Maybe<&2,U32>,+row: E.Command,+world: E.World) -> E.World:
  match value row:
    case None{} _: world
    case Some{m} E.Command{_,_,+role,extra}: accepted(replaces(role,m,extra,world),row,world)

@unsafe
def replaces(role: U32,previous: U32,extra: U32,world: E.World) -> Bool:
  match role:
    case 6: True{}
    case _: Tree.replaces(role,previous,extra,world)

@unsafe
def accepted(ok: Bool,+row: E.Command,+world: E.World) -> E.World:
  match ok row:
    case False{} _: world
    case True{} E.Command{at,m,3,side}: attached(Decorator.supported(world,at,m,side,True{}),row,world)
    case True{} E.Command{at,m,4,side}: attached(Decorator.supported(world,at,m,side,False{}),row,world)
    case True{} E.Command{at,m,5,0}: unrecorded(E.put(world,at,m))
    case True{} E.Command{at,_,5,_}: paired(P.shift(at,P.Position{0,1,0}),row,world)
    case True{} E.Command{at,m,_,_}: unrecorded(E.put(world,at,m))

@unsafe
def attached(ok: Bool,row: E.Command,world: E.World) -> E.World:
  match ok row:
    case False{} _: world
    case True{} E.Command{at,m,_,_}: unrecorded(E.put(world,at,m))

@unsafe
def paired(point: Maybe<&2,P.Position>,row: E.Command,+world: E.World) -> E.World:
  match point:
    case None{}: world
    case Some{at}: pair_clear(E.material(world,at),row,world)

@unsafe
def pair_clear(value: Maybe<&2,U32>,row: E.Command,world: E.World) -> E.World:
  match value row:
    case Some{0} E.Command{at,m,_,upper}: unrecorded(E.put_pair(world,at,m,upper))
    case _ _: world

@unsafe
def commands(xs: List<&2,E.Command>,world: E.World) -> E.World:
  match xs:
    case Nil{}: world
    case row <> rest: commands(rest,command(row,world))

@unsafe
def anchors(xs: List<&2,Anchor>,world: E.World) -> E.World:
  match xs:
    case Nil{}: world
    case Anchor{_,rows} <> rest: anchors(rest,commands(rows,world))

@unsafe
def prepare(+source: M.Grid,+policy: P.Policy,+xs: Q.Table<Anchor>) -> Result<&1,&1,U32,E.World>:
  match policy:
    case P.Policy{masks,_,_,_,_,_,_}:
      +ordered = sort(Q.table_size(Anchor,xs),flatten(xs,[]))
      prepared(valid(ordered,None{},Q.table_size(U32,masks)),ordered,source,policy)

@unsafe
def prepared(ok: Bool,xs: List<&2,Anchor>,source: M.Grid,policy: P.Policy) -> Result<&1,&1,U32,E.World>:
  match ok:
    case False{}: Fail{773}
    case True{}: Done{anchors(xs,E.World{source,policy,E.Empty{},[]})}

@unsafe
def finish(result: Result<&1,&1,U32,E.World>,repair: Merge.Repair) -> Result<&1,&1,U32,M.Grid>:
  match result:
    case Fail{code}: Fail{code}
    case Done{E.World{source,_,patch,_}}: Done{Merge.apply(source,Q.Leaf{patch},repair)}

@unsafe
def apply(source: M.Grid,policy: P.Policy,rows: Q.Table<Anchor>,repair: Merge.Repair) -> Result<&1,&1,U32,M.Grid>:
  finish(prepare(source,policy,rows),repair)
