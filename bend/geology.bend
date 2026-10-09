import Base
import ./density.bend as Q
import ./precise.bend as D
import ./noise.bend as N
import ./aquifer.bend as A
import ./lake_data.bend as Lakes
import ./ore_data.bend as O
import ./cave_features_data.bend as F

# Loaded geology is immutable Data. The registry tape and file effects stay on
# the host; these records can be consumed unchanged by Bend CPU or GPU code.
type Height is Data:
  Height{minimum: U32,maximum: U32,triangle: Bool,plateau: U32}
type Noise is Data:
  Noise{frequency: F32,amplitude: F32,modifiers: List<&2,F32>}
type Carver is Data:
  Carver{id: U32,kind: U32,height: Height,probability: F32,shape: Q.Vec4,detail: Q.Vec4,variation: Q.Vec4,distance: Q.Vec4,axis: Q.Vec4}
type Biome is Data:
  Biome{carvers: Q.Table<Carver>,lakes: Lakes.Params,features: F.Config}
type Profile is Data:
  Profile{noises: Q.Table<Noise>,biomes: Q.Table<Biome>,data: O.Store,lava: U32,lava_level: U32,minimum: U32,height: U32,aquifer: Maybe<&2,A.Prepared>,lakes: Maybe<&2,Lakes.Cache>}

def finite(value: F32) -> Bool:
  U32.is_ne(U32.and(F32.bits(value),2139095040),2139095040)

def finite_values(values: List<&2,F32>) -> Bool:
  match values:
    case Nil{}: True{}
    case value <> rest: Bool.and(finite(value),finite_values(rest))

def height_valid(height: Height) -> Bool:
  match height:
    case Height{minimum,maximum,_,plateau}: Bool.and(Bool.not(D.less(D.integer(maximum),D.integer(minimum))),U32.is_le(plateau,2147483647))

@unsafe
def weight_sum(values: List<&2,F32>,+frequency: F32,+persistence: F32,sum: F32) -> F32:
  match values:
    case Nil{}: sum
    case value <> rest: weight_sum_retained(F32.is_le(frequency,0.125),value,rest,frequency,persistence,sum)

@unsafe
def weight_sum_retained(retained: Bool,value: F32,rest: List<&2,F32>,frequency: F32,+persistence: F32,sum: F32) -> F32:
  match retained:
    case False{}: sum
    case True{}: weight_sum(rest,F32.mul(frequency,2.0),F32.mul(persistence,0.5),F32.add(sum,F32.mul(F32.max(value,0.0),persistence)))

@unsafe
def noise_valid(noise: Noise) -> Bool:
  match noise:
    case Noise{+frequency,+amplitude,+modifiers}:
      Bool.and(Bool.and(finite(frequency),finite(amplitude)),
        Bool.and(Bool.and(F32.is_ge(frequency,0.0),F32.is_le(frequency,1073741824.0)),
          Bool.and(Bool.and(F32.is_ge(amplitude,0.0),F32.is_le(amplitude,1000000.0)),
            Bool.and(Bool.and(Nat.is_gt(List.length(&2,F32,modifiers),0n),Nat.is_le(List.length(&2,F32,modifiers),32n)),
              Bool.and(finite_values(modifiers),finite(weight_sum(modifiers,frequency,1.0,0.0)))))))

def carver_valid(carver: Carver) -> Bool:
  match carver:
    case Carver{_,+kind,+height,+probability,Q.Vec4{+thickness,+horizontal,+vertical,+count},Q.Vec4{floor,room,smoothness,vdefault},
        Q.Vec4{+t0,+t1,+h0,+h1},Q.Vec4{+d0,+d1,center,_},Q.Vec4{+v0,+v1,+r0,+r1}}:
      Bool.and(Bool.and(U32.is_le(kind,1),height_valid(height)),
        Bool.and(Bool.and(F32.is_ge(probability,0.0),F32.is_le(probability,1.0)),
          Bool.and(Bool.and(F32.is_ge(thickness,0.0),F32.is_ge(count,0.0)),
            Bool.and(Bool.and(F32.is_gt(horizontal,0.0),F32.is_gt(vertical,0.0)),
              Bool.and(Bool.and(F32.is_le(t0,t1),F32.is_le(h0,h1)),
                Bool.and(Bool.and(F32.is_le(d0,d1),F32.is_le(v0,v1)),
                  Bool.and(F32.is_le(r0,r1),finite_values([probability,thickness,horizontal,vertical,count,floor,room,smoothness,vdefault,
                    t0,t1,h0,h1,d0,d1,center,v0,v1,r0,r1]))))))))

# Cave sampling filters octaves above the 4-block lattice's Nyquist limit.
# Normalize only the retained positive weights, matching the cave approximation
# rather than the separately clamped 2D climate-stack algorithm.
@unsafe
def sample_layers(values: List<&2,F32>,+frequency: F32,+persistence: F32,+octave: U32,+channel: U32,+x: U32,+y: U32,+z: U32,+seed: U32,+sum: F32,+weights: F32) -> F32 & F32:
  match values:
    case Nil{}: (sum,weights)
    case modifier <> rest: sample_frequency(F32.is_le(frequency,0.125),modifier,rest,frequency,persistence,octave,channel,x,y,z,seed,sum,weights)

@unsafe
def sample_frequency(retained: Bool,+modifier: F32,rest: List<&2,F32>,+frequency: F32,+persistence: F32,+octave: U32,+channel: U32,+x: U32,+y: U32,+z: U32,+seed: U32,sum: F32,weights: F32) -> F32 & F32:
  match retained:
    case False{}: (sum,weights)
    case True{}:
      +weight = F32.mul(F32.max(modifier,0.0),persistence)
      sample_weight(F32.is_gt(weight,0.0),rest,frequency,persistence,octave,channel,x,y,z,seed,sum,weights,weight)

@unsafe
def sample_weight(active: Bool,rest: List<&2,F32>,+frequency: F32,+persistence: F32,+octave: U32,+channel: U32,+x: U32,+y: U32,+z: U32,+seed: U32,+sum: F32,+weights: F32,+weight: F32) -> F32 & F32:
  match active:
    case False{}: sample_layers(rest,F32.mul(frequency,2.0),F32.mul(persistence,0.5),U32.inc(octave),channel,x,y,z,seed,sum,weights)
    case True{}: sample_layers(rest,F32.mul(frequency,2.0),F32.mul(persistence,0.5),U32.inc(octave),channel,x,y,z,seed,
      N.mix(sum,N.world_perlin(x,y,z,frequency,U32.add(seed,U32.add(U32.mul(channel,7919),U32.mul(octave,1013))),0),
        F32.div(weight,F32.add(weights,weight))),F32.add(weights,weight))

@unsafe
def normalized(pair: F32 & F32,amplitude: F32) -> F32:
  (mean,weights) = pair
  normalized_values(mean,weights,amplitude)

@unsafe
def normalized_values(mean: F32,+weights: F32,amplitude: F32) -> F32:
  F32.mul(F32.mul(mean,F32.div(weights,F32.max(weights,0.0001))),amplitude)

@unsafe
def sample_noise(noise: Noise,channel: U32,x: U32,y: U32,z: U32,low: U32,high: U32) -> F32:
  match noise:
    case Noise{frequency,amplitude,modifiers}:
      normalized(sample_layers(modifiers,frequency,1.0,0,channel,x,y,z,U32.xor(low,N.hash(high)),0.0,0.0),amplitude)

@unsafe
def sample(profile: Profile,+channel: U32,x: U32,y: U32,z: U32,low: U32,high: U32) -> F32:
  match profile:
    case Profile{noises,_,_,_,_,_,_,_,_}: sample_noise(Q.get(Noise,noises,channel,Noise{0.0,0.0,[0.0]}),channel,x,y,z,low,high)

def with_aquifer(profile: Profile,config: A.Prepared) -> Profile:
  match profile:
    case Profile{noises,biomes,flags,lava,level,minimum,height,_,lakes}: Profile{noises,biomes,flags,lava,level,minimum,height,Some{config},lakes}

def with_lakes(profile: Profile,cache: Lakes.Cache) -> Profile:
  match profile:
    case Profile{noises,biomes,flags,lava,level,minimum,height,aquifer,_}: Profile{noises,biomes,flags,lava,level,minimum,height,aquifer,Some{cache}}

def with_ores(profile: Profile,ores: O.Store) -> Profile:
  match profile:
    case Profile{noises,biomes,flags,lava,level,minimum,height,aquifer,lakes}: Profile{noises,biomes,O.with_flags(ores,flags),lava,level,minimum,height,aquifer,lakes}

def lakes(profile: Maybe<&2,Profile>) -> Maybe<&2,Lakes.Cache>:
  match profile:
    case Some{Profile{_,_,_,_,_,_,_,_,lakes}}: lakes
    case None{}: None{}

def carver_count(biomes: Q.Table<Biome>) -> U32:
  match biomes:
    case Q.Empty{}: 0
    case Q.Leaf{Biome{carvers,_,_}}: Q.table_size(Carver,carvers)
    case Q.Branch{_,a,b}: U32.add(carver_count(a),carver_count(b))

def empty_biome() -> Biome:
  Biome{Q.Empty{},Lakes.empty(),F.empty()}

def biomes_valid(biomes: Q.Table<Biome>,+palette: U32) -> Bool:
  match biomes:
    case Q.Empty{}: True{}
    case Q.Leaf{Biome{_,lakes,_}}: Lakes.valid(lakes,palette)
    case Q.Branch{_,a,b}: Bool.and(biomes_valid(a,palette),biomes_valid(b,palette))


def clear_aquifer(profile: Profile) -> Profile:
  match profile:
    case Profile{noises,biomes,flags,lava,level,minimum,height,Some{aquifer},_}:
      Profile{noises,biomes,flags,lava,level,minimum,height,Some{A.clear(aquifer)},None{}}
    case Profile{noises,biomes,flags,lava,level,minimum,height,None{},_}:
      Profile{noises,biomes,flags,lava,level,minimum,height,None{},None{}}

def clear_geometry(profile: Maybe<&2,Profile>) -> Maybe<&2,Profile>:
  match profile:
    case None{}: None{}
    case Some{profile}: Some{clear_aquifer(profile)}


@unsafe
def feature_biomes(+n: U32,+base: U32,+biomes: Q.Table<Biome>,+features: Q.Table<F.Config>) -> Q.Table<Biome>:
  match n:
    case 0: Q.Empty{}
    case 1: Q.Leaf{feature_biome(Q.get(Biome,biomes,base,empty_biome()),Q.get(F.Config,features,base,F.empty()))}
    case _:
      +half = U32.div(n,2)
      a b = feature_biomes(half,base,biomes,features) feature_biomes(U32.sub(n,half),U32.add(base,half),biomes,features)
      Q.Branch{n,a,b}

def feature_biome(biome: Biome,features: F.Config) -> Biome:
  match biome:
    case Biome{carvers,lakes,_}: Biome{carvers,lakes,features}

@unsafe
def with_features(profile: Profile,features: Q.Table<F.Config>) -> Profile:
  match profile:
    case Profile{noises,+biomes,flags,lava,level,minimum,height,aquifer,lakes}:
      Profile{noises,feature_biomes(Q.table_size(Biome,biomes),0,biomes,features),flags,lava,level,minimum,height,aquifer,lakes}
