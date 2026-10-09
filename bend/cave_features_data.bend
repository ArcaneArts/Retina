import Base
import ./density.bend as Q

# Immutable registry-derived cave recipes and final plant survival policy.
# A material's half is a signed block identity, not a palette-state partner.
type Policy is Data:
  Policy{flags: U32,half: U32,floor_mask: U32}
type Shape is Data:
  Shape{floor: U32,clay: U32,blossom: U32,minimum: U32,maximum: U32,vines: U32,density: F32,plants: F32}
type Arrays is Data:
  Arrays{replaceable: Q.Table<U32>,plants: Q.Table<U32>,bodies: Q.Table<U32>,tips: Q.Table<U32>,up: Q.Table<U32>,down: Q.Table<U32>}
type Config is Data:
  Config{kind: U32,flags: U32,shape: Shape,arrays: Arrays,policy: Q.Table<Policy>}

def empty_policy() -> Policy:
  Policy{0,0,0}

def empty() -> Config:
  Config{0,0,Shape{0,0,0,0,0,0,0.0,0.0},Arrays{Q.Empty{},Q.Empty{},Q.Empty{},Q.Empty{},Q.Empty{},Q.Empty{}},Q.Empty{}}

@unsafe
def chance(+value: F32) -> Bool:
  Bool.and(F32.is_ge(value,0.0),F32.is_le(value,1.0))

@unsafe
def materials(+xs: Q.Table<U32>,+palette: U32) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{value}: U32.is_lt(value,palette)
    case Q.Branch{_,a,b}: Bool.and(materials(a,palette),materials(b,palette))

@unsafe
def valid(+value: Config,+palette: U32) -> Bool:
  match value:
    case Config{kind,_,Shape{floor,clay,blossom,+minimum,+maximum,vines,density,plants},Arrays{+replaceable,ps,bs,ts,+up,+down},_}:
      Bool.and(U32.is_le(kind,4),Bool.and(Bool.and(U32.is_lt(floor,palette),Bool.and(U32.is_lt(clay,palette),U32.is_lt(blossom,palette))),
        Bool.and(Bool.and(U32.is_le(minimum,maximum),Bool.and(U32.is_le(maximum,128),U32.is_le(vines,128))),
          Bool.and(Bool.and(chance(density),chance(plants)),Bool.and(
            Bool.or(U32.is_zero(Q.table_size(U32,replaceable)),U32.is_eq(Q.table_size(U32,replaceable),palette)),
            Bool.and(Bool.and(Bool.or(U32.is_zero(Q.table_size(U32,up)),Bool.and(U32.is_eq(Q.table_size(U32,up),4),U32.is_eq(Q.table_size(U32,down),4))),
              Bool.or(U32.is_zero(Q.table_size(U32,down)),U32.is_eq(Q.table_size(U32,down),4))),
              Bool.and(materials(ps,palette),Bool.and(materials(bs,palette),Bool.and(materials(ts,palette),Bool.and(materials(up,palette),materials(down,palette)))))))))))

@unsafe
def policies(+n: U32,+base: U32,+flags: Q.Table<U32>,+halves: Q.Table<U32>,+masks: Q.Table<U32>) -> Q.Table<Policy>:
  match n:
    case 0: Q.Empty{}
    case 1: Q.Leaf{Policy{Q.get(U32,flags,base,0),Q.get(U32,halves,base,0),Q.get(U32,masks,base,0)}}
    case _:
      +half = U32.div(n,2)
      a b = policies(half,base,flags,halves,masks) policies(U32.sub(n,half),U32.add(base,half),flags,halves,masks)
      Q.Branch{n,a,b}

@unsafe
def policies_valid(+xs: Q.Table<Policy>) -> Bool:
  match xs:
    case Q.Empty{}: True{}
    case Q.Leaf{Policy{flags,half,mask}}: Bool.and(U32.is_le(flags,255),Bool.and(U32.is_ne(half,2147483648),U32.is_le(mask,3)))
    case Q.Branch{_,a,b}: Bool.and(policies_valid(a),policies_valid(b))

@unsafe
def flagged(+value: Config,+flag: U32) -> Bool:
  match value:
    case Config{_,flags,_,_,_}: U32.is_ne(U32.and(flags,flag),0)

@unsafe
def policy(+value: Config,+id: U32) -> Policy:
  match value:
    case Config{_,_,_,_,policy}: Q.get(Policy,policy,id,empty_policy())

@unsafe
def replaceable(+value: Config,+id: U32) -> Bool:
  match value:
    case Config{_,_,_,Arrays{replaceable,_,_,_,_,_},_}: U32.is_ne(Q.get(U32,replaceable,id,0),0)
