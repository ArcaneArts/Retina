import Base
import ./vegetation.bend as G
import ./placement_replay.bend as Replay
import ./placement_merge.bend as Merge
import ./placement_world.bend as E
import ./placement_data.bend as P
import ./material_columns_data.bend as M
import ./terrain_surface.bend as S
import ./density.bend as Q
import ./word64.bend as W
import ./precise.bend as D

# Independent chunk anchors share a resident terrain snapshot. One neighboring
# anchor ring contributes to the core; a second terrain ring contains complete
# registered feature footprints before the merged result is cropped.
type Plans is Type:
  Empty{}
  Leaf{result: Result<&1,&1,U32,Planned>}
  Node{count: U32,left: Plans,right: Plans}
type Planned is Data:
  Planned{anchor: Replay.Anchor,candidates: U32,placed: U32}
type Computed is Data:
  Computed{grid: M.Grid,anchors: U32,candidates: U32,placed: U32}
type Collected is Data:
  Collected{anchors: List<&2,Replay.Anchor>,candidates: U32,placed: U32}

@unsafe
def finish(+outcome: G.Outcome,+model: G.Model,+origin: P.Position) -> Result<&1,&1,U32,Planned>:
  match outcome:
    case G.Finished{G.Anchor{_,world,candidates,placed,_}}: Done{Planned{Replay.capture(origin,world),candidates,placed}}
    case G.Unavailable{_,_,_,_}: Fail{774}
    case G.Paused{anchor}: finish(G.resume(anchor,model,4096),model,origin)

@unsafe
def anchor(+source: M.Grid,+model: G.Model,+origin: P.Position,+seed: W.U64) -> Result<&1,&1,U32,Planned>:
  match model:
    case G.Model{P.Model{_,_,policy},_,_}:
      finish(G.resume(G.start(model,source,G.all_ids(E.World{source,policy,E.Empty{},[]},origin),origin,seed),model,4096),model,origin)

@unsafe
def plans(+n: U32,+i: U32,+core: S.Tile,+source: M.Grid,+model: G.Model) -> Plans:
  split(U32.is_gt(n,1),n,i,core,source,model)

@unsafe
def split(many: Bool,+n: U32,+i: U32,+core: S.Tile,+source: M.Grid,+model: G.Model) -> Plans:
  match many n core source:
    case _ 0 _ _: Empty{}
    case False{} _ S.Tile{x,z,width,_,low,high} M.Grid{_,minimum,_,_,_}:
      Leaf{anchor(source,model,P.Position{U32.add(U32.sub(x,16),U32.mul(U32.div(i,U32.add(U32.div(core_depth(core),16),2)),16)),minimum,
        U32.add(U32.sub(z,16),U32.mul(U32.mod(i,U32.add(U32.div(core_depth(core),16),2)),16))},W.U64{high,low})}
    case True{} _ _ _:
      +half = U32.div(n,2)
      a b = plans(half,i,core,source,model) plans(U32.sub(n,half),U32.add(i,half),core,source,model)
      Node{n,a,b}

def core_depth(core: S.Tile) -> U32:
  match core:
    case S.Tile{_,_,_,depth,_,_}: depth

@unsafe
def flatten(tree: Plans,acc: Collected) -> Result<&1,&1,U32,Collected>:
  match tree acc:
    case Empty{} _: Done{acc}
    case Leaf{Fail{code}} _: Fail{code}
    case Leaf{Done{Planned{anchor,c,p}}} Collected{anchors,candidates,placed}: Done{Collected{anchor <> anchors,U32.add(candidates,c),U32.add(placed,p)}}
    case Node{_,a,b} _: flattened(flatten(b,acc),a)

@unsafe
def flattened(result: Result<&1,&1,U32,Collected>,tree: Plans) -> Result<&1,&1,U32,Collected>:
  match result:
    case Fail{code}: Fail{code}
    case Done{acc}: flatten(tree,acc)

@unsafe
def merged(result: Result<&1,&1,U32,M.Grid>,core: S.Tile,count: U32,candidates: U32,placed: U32) -> Result<&1,&1,U32,Computed>:
  match result core:
    case Fail{code} _: Fail{code}
    case Done{grid} S.Tile{x,z,width,depth,_,_}: cropped(Merge.crop(grid,x,z,width,depth),count,candidates,placed)

@unsafe
def cropped(result: Result<&1,&1,U32,M.Grid>,count: U32,candidates: U32,placed: U32) -> Result<&1,&1,U32,Computed>:
  match result:
    case Fail{code}: Fail{code}
    case Done{grid}: Done{Computed{grid,count,candidates,placed}}

@unsafe
def collected(result: Result<&1,&1,U32,Collected>,+core: S.Tile,+source: M.Grid,model: G.Model,repair: Merge.Repair,count: U32) -> Result<&1,&1,U32,Computed>:
  match result model:
    case Fail{code} _: Fail{code}
    case Done{Collected{anchors,candidates,placed}} G.Model{P.Model{_,_,policy},_,_}:
      merged(Replay.apply(source,policy,Q.prepared_table(Replay.Anchor,anchors),repair),core,count,candidates,placed)

@unsafe
def execute(+core: S.Tile,+source: M.Grid,+model: G.Model,repair: Merge.Repair) -> Result<&1,&1,U32,Computed>:
  match core:
    case S.Tile{_,_,width,depth,_,_}:
      +count = U32.mul(U32.add(U32.div(width,16),2),U32.add(U32.div(depth,16),2))
      collected(flatten(plans(count,0,core,source,model),Collected{[],0,0}),core,source,model,repair,count)


# Validate the actual resident geometry used by every anchor, rather than
# pretending an absent border is known air. Seed identity is part of the tile.
@unsafe
def compatible(+core: S.Tile,+source: M.Grid) -> Bool:
  match core source:
    case S.Tile{+x,+z,+w,+d,lo,hi} M.Grid{S.Tile{ox,oz,ow,od,slo,shi},_,_,_,_}:
      Bool.and(Bool.and(U32.is_zero(U32.and(x,15)),U32.is_zero(U32.and(z,15))),
        Bool.and(Bool.and(U32.is_gt(w,0),U32.is_gt(d,0)),Bool.and(Bool.and(U32.is_zero(U32.and(w,15)),U32.is_zero(U32.and(d,15))),
          Bool.and(Bool.and(U32.is_le(w,512),U32.is_le(d,512)),Bool.and(Bool.and(U32.is_eq(lo,slo),U32.is_eq(hi,shi)),
            Bool.and(Bool.and(Bool.not(D.less(D.sub(D.integer(x),D.scalar(32.0)),D.integer(ox))),Bool.not(D.less(D.sub(D.integer(z),D.scalar(32.0)),D.integer(oz)))),
              Bool.and(Bool.not(D.less(D.add(D.integer(ox),D.unsigned(ow)),D.add(D.integer(x),D.unsigned(U32.add(w,32))))),
                Bool.not(D.less(D.add(D.integer(oz),D.unsigned(od)),D.add(D.integer(z),D.unsigned(U32.add(d,32))))))))))))
