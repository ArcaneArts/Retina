import Base
import ./placement_world.bend as E
import ./placement_data.bend as P
import ./material_columns_data.bend as M
import ./material_columns.bend as MC
import ./terrain_surface.bend as S
import ./density.bend as Q
import ./lake_terrain.bend as L

# Anchor overlays are independent snapshots. Their table order is the global
# anchor order, never completion order. These patches contain already accepted
# FEATURE writes (unconditional replacement), including explicit air writes.
# Other placement roles must resolve their replacement policy before calling.
type Repair is Data:
  Repair{flags: Q.Table<U32>,halves: Q.Table<U32>,floors: Q.Table<U32>}

@unsafe
def selected(+same: Bool,+leftward: Bool,+left: E.Patch,+right: E.Patch,+x: U32,+z: U32,+minimum: U32,+height: U32,+y: U32,+material: U32,runs: List<&2,M.Run>) -> List<&2,M.Run>:
  match same leftward:
    case True{} _:
      column_patch(right,x,z,minimum,height,point(U32.is_lt(U32.sub(y,minimum),height),U32.sub(y,minimum),material,column_patch(left,x,z,minimum,height,runs)))
    case False{} True{}: column_patch(left,x,z,minimum,height,runs)
    case False{} False{}: column_patch(right,x,z,minimum,height,runs)

@unsafe
def point(inside: Bool,+offset: U32,material: U32,runs: List<&2,M.Run>) -> List<&2,M.Run>:
  match inside:
    case False{}: runs
    case True{}: L.replace(runs,offset,U32.inc(offset),material,[])

# The treap is ordered x, z, y. Prune both coordinate dimensions; do not scan
# every feature block for every terrain column or expand whole voxel volumes.
@unsafe
def column_patch(patch: E.Patch,+x: U32,+z: U32,+minimum: U32,+height: U32,runs: List<&2,M.Run>) -> List<&2,M.Run>:
  match patch:
    case E.Empty{}: runs
    case E.Node{P.Position{+px,y,+pz},material,_,left,right}:
      selected(Bool.and(U32.is_eq(x,px),U32.is_eq(z,pz)),P.less(P.Position{x,0,z},P.Position{px,0,pz}),left,right,x,z,minimum,height,y,material,runs)

@unsafe
def ordered(patches: Q.Table<E.Patch>,+x: U32,+z: U32,+minimum: U32,+height: U32,runs: List<&2,M.Run>) -> List<&2,M.Run>:
  match patches:
    case Q.Empty{}: runs
    case Q.Leaf{patch}: column_patch(patch,x,z,minimum,height,runs)
    case Q.Branch{_,a,b}: ordered(b,x,z,minimum,height,ordered(a,x,z,minimum,height,runs))

@unsafe
def flag(+repair: Repair,+material: U32) -> U32:
  match repair:
    case Repair{+flags,_,_}: bounded(flags,material)

@unsafe
def bounded(+values: Q.Table<U32>,+id: U32) -> U32:
  bounded_get(U32.is_lt(id,Q.table_size(U32,values)),values,id)

def bounded_get(ok: Bool,values: Q.Table<U32>,id: U32) -> U32:
  match ok:
    case False{}: 0
    case True{}: Q.get(U32,values,id,0)

@unsafe
def supported(+offset: U32,+mask: U32,+repair: Repair,+runs: List<&2,M.Run>) -> Bool:
  Bool.or(U32.is_zero(mask),Bool.and(U32.is_ne(offset,0),U32.is_ne(U32.and(flag(repair,MC.material_at(runs,U32.sub(offset,1))),mask),0)))

@unsafe
def paired(+offset: U32,+half: U32,+height: U32,+repair: Repair,+runs: List<&2,M.Run>) -> Bool:
  paired_at(offset,half,height,repair,runs,partner_delta(U32.is_lt(half,2147483648)))

def partner_delta(positive: Bool) -> U32:
  match positive:
    case True{}: 1
    case False{}: 4294967295

@unsafe
def paired_at(+offset: U32,+half: U32,+height: U32,+repair: Repair,+runs: List<&2,M.Run>,+delta: U32) -> Bool:
  match repair:
    case Repair{_,+halves,_}: Bool.or(U32.is_zero(half),Bool.and(U32.is_lt(U32.add(offset,delta),height),
      U32.is_eq(bounded(halves,MC.material_at(runs,U32.add(offset,delta))),U32.sub(0,half))))

# Support runs first, partners second. Later anchors may have replaced soil or
# just one half. Full columns also cover packs using plants as base materials.
@unsafe
def repair_voxels(+n: U32,+at: U32,+mode: U32,+height: U32,+repair: Repair,+runs: List<&2,M.Run>) -> List<&2,M.Run>:
  match n:
    case 0: runs
    case _: repair_value(MC.material_at(runs,at),n,at,mode,height,repair,runs)

@unsafe
def repair_value(+material: U32,+n: U32,+at: U32,+mode: U32,+height: U32,+repair: Repair,+runs: List<&2,M.Run>) -> List<&2,M.Run>:
  match mode repair:
    case 0 Repair{_,_,floors}: repair_next(supported(at,bounded(floors,material),repair,runs),n,at,mode,height,repair,runs)
    case _ Repair{_,halves,_}: repair_next(paired(at,bounded(halves,material),height,repair,runs),n,at,mode,height,repair,runs)

@unsafe
def repair_next(ok: Bool,+n: U32,+at: U32,+mode: U32,+height: U32,+repair: Repair,runs: List<&2,M.Run>) -> List<&2,M.Run>:
  match ok:
    case True{}: repair_voxels(U32.sub(n,1),U32.inc(at),mode,height,repair,runs)
    case False{}: repair_voxels(U32.sub(n,1),U32.inc(at),mode,height,repair,L.replace(runs,at,U32.inc(at),0,[]))

# Only runs containing checked plants need per-voxel repair. Unchecked stone,
# air, fluids and most terrain remain proportional to run count.
@unsafe
def repair_runs(todo: List<&2,M.Run>,+mode: U32,+height: U32,+repair: Repair,runs: List<&2,M.Run>) -> List<&2,M.Run>:
  match todo:
    case Nil{}: runs
    case M.Run{+a,+b,+material} <> rest: repair_run(repair_checked(mode,repair,material),rest,a,b,mode,height,repair,runs)

@unsafe
def repair_checked(mode: U32,repair: Repair,material: U32) -> Bool:
  match mode repair:
    case 0 Repair{_,_,floors}: U32.is_ne(bounded(floors,material),0)
    case _ Repair{_,halves,_}: U32.is_ne(bounded(halves,material),0)

@unsafe
def repair_run(checked: Bool,todo: List<&2,M.Run>,+a: U32,+b: U32,+mode: U32,+height: U32,+repair: Repair,+runs: List<&2,M.Run>) -> List<&2,M.Run>:
  match checked:
    case False{}: repair_runs(todo,mode,height,repair,runs)
    case True{}: repair_runs(todo,mode,height,repair,repair_voxels(U32.sub(b,a),a,mode,height,repair,runs))

@unsafe
def partners(+runs: List<&2,M.Run>,height: U32,repair: Repair) -> List<&2,M.Run>:
  repair_runs(runs,1,height,repair,runs)

@unsafe
def clean(+runs: List<&2,M.Run>,+height: U32,+repair: Repair) -> List<&2,M.Run>:
  partners(repair_runs(runs,0,height,repair,runs),height,repair)

@unsafe
def column(column: M.Column,patches: Q.Table<E.Patch>,x: U32,z: U32,minimum: U32,+height: U32,+repair: Repair) -> M.Column:
  match column:
    case M.Column{h,b,a,c,d,e,f,runs,p,True{}}: M.Column{h,b,a,c,d,e,f,clean(ordered(patches,x,z,minimum,height,runs),height,repair),p,True{}}
    case _: column

# Columns fork independently. Metadata and the cave-biome lattice are carried
# through unchanged; final NBT heightmaps are derived from the resulting runs.
@unsafe
def columns(+n: U32,+base: U32,+source: M.Grid,+patches: Q.Table<E.Patch>,+repair: Repair) -> Q.Table<M.Column>:
  split(U32.is_gt(n,1),n,base,source,patches,repair)

@unsafe
def split(more: Bool,+n: U32,+base: U32,+source: M.Grid,+patches: Q.Table<E.Patch>,+repair: Repair) -> Q.Table<M.Column>:
  match more n source:
    case _ 0 _: Q.Empty{}
    case False{} _ M.Grid{S.Tile{x,z,width,_,_,_},minimum,height,cols,_}:
      Q.Leaf{column(Q.get(M.Column,cols,base,MC.empty()),patches,U32.add(x,U32.mod(base,width)),U32.add(z,U32.div(base,width)),minimum,height,repair)}
    case True{} _ _:
      +half = U32.div(n,2)
      a b = columns(half,base,source,patches,repair) columns(U32.sub(n,half),U32.add(base,half),source,patches,repair)
      Q.Branch{n,a,b}

@unsafe
def apply(+source: M.Grid,patches: Q.Table<E.Patch>,repair: Repair) -> M.Grid:
  match source:
    case M.Grid{+tile,minimum,height,cols,caves}: M.Grid{tile,minimum,height,columns(Q.table_size(M.Column,cols),0,source,patches,repair),caves}

# Cropping happens after all halo writes and repairs. A caller can serialize
# only its core while retaining the original shared cave lattice for queries.
@unsafe
def crop_columns(+n: U32,+base: U32,+width: U32,+dx: U32,+dz: U32,+old_width: U32,+cols: Q.Table<M.Column>) -> Q.Table<M.Column>:
  crop_split(U32.is_gt(n,1),n,base,width,dx,dz,old_width,cols)

@unsafe
def crop_split(more: Bool,+n: U32,+base: U32,+width: U32,+dx: U32,+dz: U32,+old_width: U32,+cols: Q.Table<M.Column>) -> Q.Table<M.Column>:
  match more n:
    case _ 0: Q.Empty{}
    case False{} _: Q.Leaf{Q.get(M.Column,cols,U32.add(U32.add(dx,U32.mod(base,width)),U32.mul(old_width,U32.add(dz,U32.div(base,width)))),MC.empty())}
    case True{} _:
      +half = U32.div(n,2)
      a b = crop_columns(half,base,width,dx,dz,old_width,cols) crop_columns(U32.sub(n,half),U32.add(base,half),width,dx,dz,old_width,cols)
      Q.Branch{n,a,b}

@unsafe
def crop_valid(ok: Bool,source: M.Grid,+x: U32,+z: U32,+width: U32,+depth: U32) -> Result<&1,&1,U32,M.Grid>:
  match ok source:
    case False{} _: Fail{766}
    case True{} M.Grid{S.Tile{ox,oz,old_width,_,lo,hi},minimum,height,cols,caves}:
      Done{M.Grid{S.Tile{x,z,width,depth,lo,hi},minimum,height,crop_columns(U32.mul(width,depth),0,width,U32.sub(x,ox),U32.sub(z,oz),old_width,cols),caves}}

@unsafe
def crop(+source: M.Grid,+x: U32,+z: U32,+width: U32,+depth: U32) -> Result<&1,&1,U32,M.Grid>:
  match source:
    case M.Grid{S.Tile{+ox,+oz,+ow,+od,_,_},_,_,_,_}:
      crop_valid(Bool.and(Bool.and(U32.is_gt(width,0),U32.is_gt(depth,0)),Bool.and(
        Bool.and(U32.is_le(width,ow),U32.is_le(depth,od)),Bool.and(U32.is_le(U32.sub(x,ox),U32.sub(ow,width)),U32.is_le(U32.sub(z,oz),U32.sub(od,depth))))),source,x,z,width,depth)
