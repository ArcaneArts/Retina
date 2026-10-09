import Base
import ./placement_data.bend as P
import ./placement_height.bend as H
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./terrain_surface.bend as S
import ./density.bend as Q
import ./cave_lattice.bend as CV
import ./precise.bend as D
import ./noise.bend as N

# Each anchor owns a persistent overlay. Earlier feature writes are immediately
# visible to later placement checks; no writes escape to another anchor's plan.
type Patch is Data:
  Empty{}
  Node{at: P.Position,material: U32,priority: U32,left: Patch,right: Patch}
type World is Data:
  World{source: MD.Grid,policy: P.Policy,patch: Patch}

@unsafe
def priority(+at: P.Position) -> U32:
  match at:
    case P.Position{x,y,z}: N.hash(U32.xor(U32.mul(x,2654435769),U32.xor(U32.mul(y,2246822507),U32.mul(z,3266489909))))

@unsafe
def root(+patch: Patch) -> U32:
  match patch:
    case Empty{}: 4294967295
    case Node{_,_,priority,_,_}: priority

@unsafe
def rotate_left(+rotate: Bool,+at: P.Position,+material: U32,+priority: U32,+left: Patch,+right: Patch) -> Patch:
  match rotate right:
    case True{} Node{r,m,p,a,b}: Node{r,m,p,Node{at,material,priority,left,a},b}
    case _ _: Node{at,material,priority,left,right}

@unsafe
def rotate_right(+rotate: Bool,+at: P.Position,+material: U32,+priority: U32,+left: Patch,+right: Patch) -> Patch:
  match rotate left:
    case True{} Node{r,m,p,a,b}: Node{r,m,p,a,Node{at,material,priority,b,right}}
    case _ _: Node{at,material,priority,left,right}

@unsafe
def inserted(+leftward: Bool,+at: P.Position,+material: U32,+priority: U32,+left: Patch,+right: Patch,+target: P.Position,+value: U32) -> Patch:
  match leftward:
    case True{}:
      +updated = insert(left,target,value)
      rotate_right(U32.is_lt(root(updated),priority),at,material,priority,updated,right)
    case False{}:
      +updated = insert(right,target,value)
      rotate_left(U32.is_lt(root(updated),priority),at,material,priority,left,updated)

@unsafe
def insert_equal(+equal: Bool,+at: P.Position,+material: U32,+priority: U32,+left: Patch,+right: Patch,+target: P.Position,+value: U32) -> Patch:
  match equal:
    case True{}: Node{at,value,priority,left,right}
    case False{}: inserted(P.less(target,at),at,material,priority,left,right,target,value)

@unsafe
def insert(+patch: Patch,+target: P.Position,+value: U32) -> Patch:
  match patch:
    case Empty{}: Node{target,value,priority(target),Empty{},Empty{}}
    case Node{+at,material,priority,left,right}: insert_equal(P.equal(target,at),at,material,priority,left,right,target,value)

@unsafe
def found(+equal: Bool,+at: P.Position,+material: U32,+left: Patch,+right: Patch,+target: P.Position) -> Maybe<&2,U32>:
  match equal:
    case True{}: Some{material}
    case False{}: find_child(P.less(target,at),left,right,target)

@unsafe
def find_child(+leftward: Bool,+left: Patch,+right: Patch,+target: P.Position) -> Maybe<&2,U32>:
  match leftward:
    case True{}: find(left,target)
    case False{}: find(right,target)

@unsafe
def find(+patch: Patch,+target: P.Position) -> Maybe<&2,U32>:
  match patch:
    case Empty{}: None{}
    case Node{+at,material,_,left,right}: found(P.equal(at,target),at,material,left,right,target)

@unsafe
def column(+grid: MD.Grid,+x: U32,+z: U32) -> Maybe<&2,MD.Column>:
  match grid:
    case MD.Grid{S.Tile{+ox,+oz,+width,+depth,_,_},_,_,columns,_}:
      column_known(Bool.and(U32.is_lt(U32.sub(x,ox),width),U32.is_lt(U32.sub(z,oz),depth)),columns,U32.add(U32.mul(U32.sub(z,oz),width),U32.sub(x,ox)))

@unsafe
def column_known(+known: Bool,+columns: Q.Table<MD.Column>,+index: U32) -> Maybe<&2,MD.Column>:
  match known:
    case False{}: None{}
    case True{}: valid_column(Q.get(MD.Column,columns,index,MD.Column{0,0,0.0,0.0,0.0,0.0,0.0,[],0,False{}}))

@unsafe
def valid_column(+column: MD.Column) -> Maybe<&2,MD.Column>:
  match column:
    case MD.Column{_,_,_,_,_,_,_,_,_,True{}}: Some{column}
    case _: None{}

@unsafe
def base(+column: Maybe<&2,MD.Column>,+offset: U32) -> Maybe<&2,U32>:
  match column:
    case None{}: None{}
    case Some{MD.Column{_,_,_,_,_,_,_,runs,_,_}}: material_known(MC.material_at(runs,offset))

def material_known(+value: U32) -> Maybe<&2,U32>:
  match value:
    case 4294967295: None{}
    case _: Some{value}

@unsafe
def material_patch(+previous: Maybe<&2,U32>,+source: MD.Grid,+at: P.Position) -> Maybe<&2,U32>:
  match previous source at:
    case Some{m} _ _: Some{m}
    case None{} MD.Grid{_,minimum,_,_,_} P.Position{x,y,z}: base(column(source,x,z),U32.sub(y,minimum))

@unsafe
def material_inside(+inside: Bool,+world: World,+at: P.Position) -> Maybe<&2,U32>:
  match inside world:
    case False{} _: Some{0}
    case True{} World{source,_,patch}: material_patch(find(patch,at),source,at)

@unsafe
def material(+world: World,+at: P.Position) -> Maybe<&2,U32>:
  match world at:
    case World{MD.Grid{_,minimum,height,_,_},_,_} P.Position{_,y,_}: material_inside(U32.is_lt(U32.sub(y,minimum),height),world,at)

@unsafe
def put_valid(+ok: Bool,+world: World,+at: P.Position,+material: U32) -> World:
  match ok world:
    case False{} _: world
    case True{} World{source,policy,patch}: World{source,policy,insert(patch,at,material)}

@unsafe
def put_column(+known: Maybe<&2,MD.Column>,+world: World,+at: P.Position,+material: U32) -> World:
  match known:
    case None{}: world
    case Some{_}: put_valid(True{},world,at,material)

# Feature writers still decide their own replacement/support policy. This
# primitive only enforces the actual resident domain and palette bounds.
@unsafe
def put(+world: World,+at: P.Position,+value: U32) -> World:
  match world at:
    case World{+source,P.Policy{masks,_,_,_,_,_,_},_} P.Position{x,y,z}:
      put_checked(Bool.and(U32.is_lt(value,Q.table_size(U32,masks)),inside_y(source,y)),source,world,at,value,x,z)

@unsafe
def put_checked(+ok: Bool,+source: MD.Grid,+world: World,+at: P.Position,+value: U32,+x: U32,+z: U32) -> World:
  match ok:
    case False{}: world
    case True{}: put_column(column(source,x,z),world,at,value)

@unsafe
def inside_y(+source: MD.Grid,+y: U32) -> Bool:
  match source:
    case MD.Grid{_,minimum,height,_,_}: U32.is_lt(U32.sub(y,minimum),height)

@unsafe
def masked(+policy: P.Policy,+material: U32,+map: U32) -> Bool:
  match policy:
    case P.Policy{masks,_,_,_,_,_,_}: U32.is_ne(U32.and(Q.get(U32,masks,material,0),U32.shln(1,U32.to_nat(map))),0)

@unsafe
def base_height(runs: List<&2,MD.Run>,+policy: P.Policy,+map: U32,+maximum: U32) -> U32:
  match runs:
    case Nil{}: maximum
    case MD.Run{_,end,material} <> rest: base_height(rest,policy,map,U32.max(maximum,N.word(masked(policy,material,map),end,0)))

@unsafe
def patch_height(+patch: Patch,+x: U32,+z: U32,+minimum: U32,+maximum: U32) -> U32:
  match patch:
    case Empty{}: maximum
    case Node{P.Position{+px,py,+pz},_,_,left,right}:
      patch_column(Bool.and(U32.is_eq(x,px),U32.is_eq(z,pz)),P.less(P.Position{x,0,z},P.Position{px,0,pz}),left,right,x,z,minimum,maximum,py)

@unsafe
def patch_column(+same: Bool,+leftward: Bool,+left: Patch,+right: Patch,+x: U32,+z: U32,+minimum: U32,+maximum: U32,+y: U32) -> U32:
  match same leftward:
    case True{} _: patch_height(right,x,z,minimum,patch_height(left,x,z,minimum,U32.max(maximum,U32.inc(U32.sub(y,minimum)))))
    case False{} True{}: patch_height(left,x,z,minimum,maximum)
    case False{} False{}: patch_height(right,x,z,minimum,maximum)

@unsafe
def scan_height(+offset: U32,+world: World,+x: U32,+z: U32,+map: U32,+minimum: U32) -> Maybe<&2,U32>:
  match offset:
    case 0: Some{minimum}
    case _: scan_material(material(world,P.Position{x,U32.add(minimum,U32.sub(offset,1)),z}),offset,world,x,z,map,minimum)

@unsafe
def scan_material(+value: Maybe<&2,U32>,+offset: U32,+world: World,+x: U32,+z: U32,+map: U32,+minimum: U32) -> Maybe<&2,U32>:
  match value world:
    case None{} _: None{}
    case Some{value} World{_,policy,_}: scan_present(masked(policy,value,map),offset,world,x,z,map,minimum)

@unsafe
def scan_present(+present: Bool,+offset: U32,+world: World,+x: U32,+z: U32,+map: U32,+minimum: U32) -> Maybe<&2,U32>:
  match present:
    case True{}: height_representable(H.context_valid(minimum,U32.inc(offset)),minimum,offset)
    case False{}: scan_height(U32.sub(offset,1),world,x,z,map,minimum)

@unsafe
def height_representable(+ok: Bool,+minimum: U32,+offset: U32) -> Maybe<&2,U32>:
  match ok:
    case False{}: None{}
    case True{}: Some{U32.add(minimum,offset)}

@unsafe
def height_column(+column: Maybe<&2,MD.Column>,+world: World,+x: U32,+z: U32,+map: U32) -> Maybe<&2,U32>:
  match column world:
    case None{} _: None{}
    case Some{MD.Column{_,_,_,_,_,_,_,runs,_,_}} World{MD.Grid{_,minimum,_,_,_},policy,patch}:
      scan_height(patch_height(patch,x,z,minimum,base_height(runs,policy,map,0)),world,x,z,map,minimum)

@unsafe
def height(+world: World,+x: U32,+z: U32,+map: U32) -> Maybe<&2,U32>:
  match world:
    case World{source,_,_}: height_column(column(source,x,z),world,x,z,map)

@unsafe
def biome_column(+column: Maybe<&2,MD.Column>) -> Maybe<&2,U32>:
  match column:
    case None{}: None{}
    case Some{MD.Column{_,biome,_,_,_,_,_,_,_,_}}: Some{biome}

@unsafe
def biome(+world: World,+at: P.Position) -> Maybe<&2,U32>:
  match world at:
    case World{source,_,_} P.Position{x,_,z}: biome_surface(column(source,x,z),world,at)

@unsafe
def biome_surface(+column: Maybe<&2,MD.Column>,+world: World,+at: P.Position) -> Maybe<&2,U32>:
  match column world at:
    case None{} _ _: None{}
    case Some{MD.Column{height,surface,_,_,_,_,_,_,_,_}} World{_,P.Policy{_,_,_,_,_,_,volume},_} P.Position{_,y,_}:
      biome_deep(Bool.and(volume,H.signed_less(y,H.subtract(height,12))),world,at,surface)

@unsafe
def biome_deep(+deep: Bool,+world: World,+at: P.Position,+surface: U32) -> Maybe<&2,U32>:
  match deep world at:
    case True{} World{MD.Grid{S.Tile{_,_,_,_,low,high},_,_,_,Some{grid}},_,_} P.Position{x,y,z}:
      biome_fallback(CV.biome(grid,Q.Point{D.integer(x),D.integer(y),D.integer(z)},low,high),surface)
    case _ _ _: Some{surface}

def biome_fallback(+id: Maybe<&2,U32>,+surface: U32) -> Maybe<&2,U32>:
  match id:
    case None{}: Some{surface}
    case Some{4294967295}: Some{surface}
    case Some{id}: Some{id}

@unsafe
def empty_material(+policy: P.Policy,+material: U32) -> Bool:
  match policy:
    case P.Policy{_,flags,_,water,lava,_,_}: Bool.or(U32.is_zero(material),Bool.or(U32.is_ne(U32.and(Q.get(U32,flags,material,0),64),0),Bool.or(U32.is_eq(material,water),U32.is_eq(material,lava))))

@unsafe
def bedrock(+policy: P.Policy,+material: U32) -> Bool:
  match policy:
    case P.Policy{_,flags,_,_,_,bedrock,_}: Bool.or(U32.is_eq(material,bedrock),U32.is_ne(U32.and(Q.get(U32,flags,material,0),128),0))

@unsafe
def ground(+world: World,+x: U32,+z: U32,+layer: U32) -> Maybe<&2,U32>:
  ground_start(height(world,x,z,4),world,x,z,layer)

@unsafe
def ground_start(+start: Maybe<&2,U32>,+world: World,+x: U32,+z: U32,+layer: U32) -> Maybe<&2,U32>:
  match start:
    case None{}: None{}
    case Some{y}: ground_scan(world,x,z,y,layer,material(world,P.Position{x,y,z}))

@unsafe
def ground_scan(+world: World,+x: U32,+z: U32,+y: U32,+layer: U32,+current: Maybe<&2,U32>) -> Maybe<&2,U32>:
  match world:
    case World{MD.Grid{_,minimum,_,_,_},_,_}: ground_more(H.signed_less(minimum,y),world,x,z,y,layer,current)

@unsafe
def ground_more(+more: Bool,+world: World,+x: U32,+z: U32,+y: U32,+layer: U32,+current: Maybe<&2,U32>) -> Maybe<&2,U32>:
  match more:
    case False{}: None{}
    case True{}: ground_below(material(world,P.Position{x,U32.sub(y,1),z}),world,x,z,y,layer,current)

@unsafe
def ground_below(+below: Maybe<&2,U32>,+world: World,+x: U32,+z: U32,+y: U32,+layer: U32,+current: Maybe<&2,U32>) -> Maybe<&2,U32>:
  match below world current:
    case Some{+b} World{_,policy,_} Some{c}: ground_found(Bool.and(empty_material(policy,c),Bool.and(Bool.not(empty_material(policy,b)),Bool.not(bedrock(policy,b)))),world,x,z,y,layer,below)
    case _ _ _: None{}

@unsafe
def ground_found(+found: Bool,+world: World,+x: U32,+z: U32,+y: U32,+layer: U32,+below: Maybe<&2,U32>) -> Maybe<&2,U32>:
  match found layer:
    case True{} 0: Some{y}
    case True{} _: ground_scan(world,x,z,U32.sub(y,1),U32.sub(layer,1),below)
    case False{} _: ground_scan(world,x,z,U32.sub(y,1),layer,below)
