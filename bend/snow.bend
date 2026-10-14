import Base
import ./density.bend as Q
import ./material_columns_data.bend as M
import ./material_columns.bend as MC
import ./material_program.bend as Material
import ./terrain_surface.bend as S
import ./lake_terrain.bend as Lake
import ./lake_data.bend as LD
import ./precise.bend as D

# Registered snow/freezing is column-local after vegetation and structures.
# Sparse run scans avoid expanding a world-height voxel array on either device.
type Biome is Data:
  Biome{temperature: F32,flags: U32,surface: U32}
type Model is Data:
  Model{sea: U32,water: U32,ice: U32,lava: U32,layer: U32,palette: U32,
    masks: Q.Table<U32>,supports: Q.Table<U32>,biomes: Q.Table<Biome>}
type Computed is Data:
  Computed{grid: M.Grid}

def empty() -> Biome:
  Biome{0.0,0,0}

@unsafe
def valid(+model: Model) -> Bool:
  match model:
    case Model{_,+water,ice,lava,layer,+palette,masks,+supports,+biomes}:
      Bool.and(Bool.and(U32.is_gt(palette,0),U32.is_le(palette,65535)),
        Bool.and(Bool.and(U32.is_gt(water,0),U32.is_lt(water,palette)),
          Bool.and(Bool.and(U32.is_lt(ice,palette),U32.is_lt(lava,palette)),
            Bool.and(U32.is_lt(layer,palette),Bool.and(U32.is_eq(Q.table_size(U32,masks),palette),
              Bool.and(Bool.or(U32.is_zero(Q.table_size(U32,supports)),U32.is_eq(Q.table_size(U32,supports),palette)),
                Bool.and(U32.is_gt(Q.table_size(Biome,biomes),0),U32.is_le(Q.table_size(Biome,biomes),65535))))))))

# Topmost motion-blocking voxel below the top build cell, as in the native
# approximation. A covered/unsupported top surface never falls through to soil.
@unsafe
def highest(todo: List<&2,M.Run>,+masks: Q.Table<U32>,+height: U32,best: Maybe<&2,U32>) -> Maybe<&2,U32>:
  match todo:
    case Nil{}: best
    case M.Run{+a,+b,m} <> rest:
      highest_next(Bool.and(U32.is_lt(a,U32.sub(height,1)),U32.is_ne(U32.and(Q.get(U32,masks,m,0),16),0)),rest,masks,height,b,best)

@unsafe
def highest_next(ok: Bool,rest: List<&2,M.Run>,masks: Q.Table<U32>,+height: U32,b: U32,best: Maybe<&2,U32>) -> Maybe<&2,U32>:
  match ok:
    case False{}: highest(rest,masks,height,best)
    case True{}: highest(rest,masks,height,Some{U32.sub(U32.min(b,U32.sub(height,1)),1)})

@unsafe
def support(+model: Model,+material: U32) -> Bool:
  match model:
    case Model{_,_,ice,_,_,_,_,+supports,_}: support_known(U32.is_zero(Q.table_size(U32,supports)),supports,material,ice)

def support_known(legacy: Bool,supports: Q.Table<U32>,material: U32,ice: U32) -> Bool:
  match legacy:
    case True{}: U32.is_ne(material,ice)
    case False{}: U32.is_ne(Q.get(U32,supports,material,0),0)

@unsafe
def snow_at(found: Maybe<&2,U32>,+runs: List<&2,M.Run>,+model: Model) -> List<&2,M.Run>:
  match found:
    case None{}: runs
    case Some{+y}: snow_material(MC.material_at(runs,y),y,runs,model)

@unsafe
def snow_material(+m: U32,+y: U32,+runs: List<&2,M.Run>,+model: Model) -> List<&2,M.Run>:
  match model:
    case Model{_,water,_,lava,layer,_,_,_,_}:
      snowy(Bool.and(U32.is_ne(layer,0),Bool.and(support(model,m),Bool.and(U32.is_ne(m,water),
        Bool.and(U32.is_ne(m,lava),U32.is_zero(MC.material_at(runs,U32.inc(y))))))),runs,y,layer)

def snowy(ok: Bool,runs: List<&2,M.Run>,+y: U32,layer: U32) -> List<&2,M.Run>:
  match ok:
    case False{}: runs
    case True{}: Lake.replace(runs,U32.inc(y),U32.add(y,2),layer,[])

@unsafe
def freeze(+runs: List<&2,M.Run>,+model: Model,+minimum: U32,+height: U32) -> List<&2,M.Run>:
  match model:
    case Model{sea,water,ice,_,_,_,_,_,_}:
      +at = U32.sub(sea,minimum)
      frozen(Bool.and(U32.is_gt(at,0),Bool.and(U32.is_lt(at,height),Bool.and(U32.is_ne(ice,0),
        Bool.and(U32.is_eq(MC.material_at(runs,U32.sub(at,1)),water),U32.is_zero(MC.material_at(runs,at)))))),runs,at,ice)

def frozen(ok: Bool,runs: List<&2,M.Run>,+at: U32,ice: U32) -> List<&2,M.Run>:
  match ok:
    case False{}: runs
    case True{}: Lake.replace(runs,U32.sub(at,1),at,ice,[])

def lake_active(shape: LD.Shape) -> Bool:
  match shape:
    case LD.Shape{active,_,_,_,_,_,_}: active

@unsafe
def column(+column: M.Column,+model: Model,+point: Q.Point,+minimum: U32,+height: U32,+config: S.Config,+lakes: Lake.Input,+low: U32,+high: U32) -> M.Column:
  match column model:
    case M.Column{+terrain,+biome,_,_,_,_,_,_,_,True{}} Model{+sea,_,_,_,_,_,_,_,+biomes}:
      selected(Q.get(Biome,biomes,biome,empty()),column,model,point,minimum,height,
        lake_active(Lake.shape(lakes,config,point,low,high,terrain)),sea,terrain)
    case _ _: column

@unsafe
def selected(biome: Biome,+column: M.Column,+model: Model,point: Q.Point,minimum: U32,height: U32,lake: Bool,sea: U32,terrain: U32) -> M.Column:
  match biome point:
    case Biome{temperature,flags,surface} Q.Point{x,_,z}:
      enabled(Bool.and(Bool.not(lake),Bool.and(U32.is_ne(U32.and(flags,32),0),
        F32.is_gt(D.value(Material.cold(sea,Q.Vec4{temperature,0.0,0.0,0.0},Q.Point{x,D.integer(terrain),z})),0.0))),surface,column,model,minimum,height)

@unsafe
def enabled(ok: Bool,surface: U32,column: M.Column,+model: Model,minimum: U32,+height: U32) -> M.Column:
  match ok column:
    case False{} _: column
    case True{} M.Column{h,b,a,c,d,e,f,runs,_,valid}:
      rebuilt(h,b,a,c,d,e,f,completed(surface,freeze(runs,model,minimum,height),model,height),valid)

@unsafe
def completed(surface: U32,+runs: List<&2,M.Run>,+model: Model,+height: U32) -> List<&2,M.Run>:
  match surface model:
    case 0 _: runs
    case _ Model{_,_,_,_,_,_,masks,_,_}: snow_at(highest(runs,masks,height,None{}),runs,model)

@unsafe
def rebuilt(h: U32,b: U32,a: F32,c: F32,d: F32,e: F32,f: F32,+runs: List<&2,M.Run>,valid: Bool) -> M.Column:
  M.Column{h,b,a,c,d,e,f,runs,U32.from_nat(List.length(&2,M.Run,runs)),valid}

@unsafe
def columns(+n: U32,+i: U32,+source: M.Grid,+model: Model,+config: S.Config,+lakes: Lake.Input) -> Q.Table<M.Column>:
  split(U32.is_gt(n,1),n,i,source,model,config,lakes)

@unsafe
def split(many: Bool,+n: U32,+i: U32,+source: M.Grid,+model: Model,+config: S.Config,+lakes: Lake.Input) -> Q.Table<M.Column>:
  match many n source:
    case _ 0 _: Q.Empty{}
    case False{} _ M.Grid{+tile,minimum,height,values,_}:
      leaf(Q.get(M.Column,values,i,MC.empty()),tile,i,minimum,height,model,config,lakes)
    case True{} _ _:
      +half = U32.div(n,2)
      a b = columns(half,i,source,model,config,lakes) columns(U32.sub(n,half),U32.add(i,half),source,model,config,lakes)
      Q.Branch{n,a,b}

@unsafe
def leaf(value: M.Column,+tile: S.Tile,i: U32,minimum: U32,height: U32,model: Model,config: S.Config,lakes: Lake.Input) -> Q.Table<M.Column>:
  match tile:
    case S.Tile{_,_,_,_,low,high}: Q.Leaf{column(value,model,S.point(tile,i),minimum,height,config,lakes,low,high)}

@unsafe
def execute(+source: M.Grid,model: Model,config: S.Config,lakes: Lake.Input) -> Computed:
  match source:
    case M.Grid{+tile,minimum,height,_,biomes}:
      values : Q.Table<M.Column> = columns!(S.total(tile),0,source,model,config,lakes)
      Computed{M.Grid{tile,minimum,height,values,biomes}}
