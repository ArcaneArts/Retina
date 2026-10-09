import Base

# Local lighting rules shared by future seeding/propagation passes. These do not
# light a region yet. Traits and paired face coverage come from LightingProfile.
type Seed is Data:
  Seed{light: U32,sun: Bool}

@unsafe
def opacity(traits: U32) -> U32:
  U32.and(traits,15)

@unsafe
def emission(traits: U32) -> U32:
  U32.and(U32.shrn(traits,4n),15)

@unsafe
def occludes(+from_face: U32,+to_face: U32,stride: U32,blocked: Array<U32>) -> Bool:
  occludes_word(Array.get(U32,blocked,U32.add(U32.mul(from_face,stride),U32.div(to_face,32))),to_face)

@unsafe
def occludes_word(pair: Array<U32> & U32,to_face: U32) -> Bool:
  (blocked,word) = pair
  U32.is_ne(U32.and(word,U32.shln(1,U32.to_nat(U32.mod(to_face,32)))),0)

@unsafe
def seed(+has_sky: Bool,+sun_above: Bool,+traits: U32,blocked_above: Bool) -> Seed:
  seed_sun(Bool.and(has_sky,Bool.and(sun_above,Bool.and(U32.is_zero(opacity(traits)),Bool.not(blocked_above)))),emission(traits))

@unsafe
def seed_sun(sun: Bool,emission: U32) -> Seed:
  match sun:
    case True{}: Seed{U32.or(emission,240),True{}}
    case False{}: Seed{emission,False{}}

@unsafe
def attenuate(+value: U32,+loss: U32) -> U32:
  attenuate_allowed(U32.is_gt(value,loss),value,loss)

@unsafe
def attenuate_allowed(allowed: Bool,value: U32,loss: U32) -> U32:
  match allowed:
    case True{}: U32.sub(value,loss)
    case False{}: 0

@unsafe
def channel(enabled: Bool,current: U32,incoming: U32,loss: U32) -> U32:
  match enabled:
    case False{}: current
    case True{}: U32.max(current,attenuate(incoming,loss))

# Both channels attenuate by at least one on propagated edges. Direct vertical
# sunlight at level 15 is seeded separately; it must not leak sideways at 15.
# Existing/emitted light is monotonic, including inside opaque emitters.
@unsafe
def propagate(current: U32,incoming: U32,+traits: U32,+blocked: Bool,channels: U32) -> U32:
  propagate_allowed(Bool.and(Bool.not(blocked),U32.is_lt(opacity(traits),15)),current,incoming,U32.max(1,opacity(traits)),channels)

@unsafe
def propagate_allowed(allowed: Bool,+current: U32,+incoming: U32,+loss: U32,+channels: U32) -> U32:
  match allowed:
    case False{}: current
    case True{}:
      U32.or(channel(U32.is_ne(U32.and(channels,1),0),U32.and(current,15),U32.and(incoming,15),loss),
        U32.shln(channel(U32.is_ne(U32.and(channels,2),0),U32.shrn(current,4n),U32.shrn(incoming,4n),loss),4n))

# Four adjacent Minecraft nibbles, low voxel index in the low nibble.
@unsafe
def pack4(a: U32,b: U32,c: U32,d: U32,+sky: Bool) -> U32:
  pack_shift(a,b,c,d,sky_shift(sky))

@unsafe
def sky_shift(sky: Bool) -> U32:
  match sky:
    case False{}: 0
    case True{}: 4

@unsafe
def pack_shift(a: U32,b: U32,c: U32,d: U32,+shift: U32) -> U32:
  pack_nat(a,b,c,d,U32.to_nat(shift))

@unsafe
def pack_nat(a: U32,b: U32,c: U32,d: U32,+shift: Nat) -> U32:
  U32.or(U32.or(U32.and(U32.shrn(a,shift),15),U32.shln(U32.and(U32.shrn(b,shift),15),4n)),
    U32.or(U32.shln(U32.and(U32.shrn(c,shift),15),8n),U32.shln(U32.and(U32.shrn(d,shift),15),12n)))
