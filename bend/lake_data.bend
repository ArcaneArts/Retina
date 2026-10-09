import Base
import ./precise.bend as D

# These are the loaded surface-lake placement budgets and palette references,
# not rainfall guesses. A zero budget means that no lake recipe was exported.
type Params is Data:
  Params{total: F32,lava: F32,barrier: U32,water_barrier: U32,flags: U32}

def empty() -> Params:
  Params{0.0,0.0,0,0,0}

def valid(params: Params,+palette: U32) -> Bool:
  match params:
    case Params{+total,+lava,barrier,water_barrier,_}:
      Bool.and(Bool.and(D.finite(total),D.finite(lava)),
        Bool.and(Bool.and(F32.is_ge(total,0.0),F32.is_le(total,1.0)),
          Bool.and(Bool.and(F32.is_ge(lava,0.0),F32.is_le(lava,1.0)),
            Bool.and(U32.is_lt(barrier,palette),U32.is_lt(water_barrier,palette)))))
