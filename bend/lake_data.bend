import Base
import ./precise.bend as D
import ./density.bend as Q

# These are the loaded surface-lake placement budgets and palette references,
# not rainfall guesses. A zero budget means that no lake recipe was exported.
type Params is Data:
  Params{total: F32,lava: F32,barrier: U32,water_barrier: U32,flags: U32}
type Layout is Data:
  Layout{x: U32,z: U32,nx: U32,nz: U32,low: U32,high: U32}
type Basin is Data:
  Basin{point: Q.Point,radius: F32,level: U32,banks: Q.Table<U32>,biome: U32,eligible: Bool,lava: Bool,barrier: U32,flags: U32}
type Cache is Data:
  Cache{words: Q.Table<U32>}
type Shape is Data:
  Shape{active: Bool,inner: Bool,original: U32,height: U32,level: U32,fluid: U32,barrier: U32}

def empty_basin() -> Basin:
  Basin{Q.world(0,0,0),0.0,0,Q.Empty{},4294967295,False{},False{},0,0}

def no_shape(+height: U32) -> Shape:
  Shape{False{},False{},height,height,0,0,0}

def empty() -> Params:
  Params{0.0,0.0,0,0,0}

def valid(params: Params,+palette: U32) -> Bool:
  match params:
    case Params{+total,+lava,barrier,water_barrier,_}:
      Bool.and(Bool.and(D.finite(total),D.finite(lava)),
        Bool.and(Bool.and(F32.is_ge(total,0.0),F32.is_le(total,1.0)),
          Bool.and(Bool.and(F32.is_ge(lava,0.0),F32.is_le(lava,1.0)),
            Bool.and(U32.is_lt(barrier,palette),U32.is_lt(water_barrier,palette)))))
