import Base
import ./cave_lattice.bend as CV
import ./geology.bend as G
import ./density.bend as Q
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./terrain_surface.bend as S
import ./density_lattice.bend as L
import ./precise.bend as D
import ./noise.bend as N
import ./aquifer.bend as AQ
import ./aquifer_lattice.bend as AL
import ./aquifer_substance.bend as AS

type Computed is Type:
  Computed{grid: MD.Grid}

@unsafe
def smooth(+a: F32,+b: F32,x: F32) -> F32:
  smooth_curve(CV.clamp(F32.div(F32.sub(x,a),F32.sub(b,a)),0.0,1.0))

@unsafe
def smooth_curve(+t: F32) -> F32:
  F32.mul(F32.mul(t,t),F32.sub(3.0,F32.mul(2.0,t)))

@unsafe
def entrance(+point: Q.Point,+seed: U32) -> F32:
  smooth(0.18,0.45,F32.add(CV.raw(CV.horizontal(point),0.012,U32.add(seed,43117)),
    F32.mul(CV.raw(CV.horizontal(point),0.026,U32.add(seed,53731)),0.25)))

@unsafe
def carver(settings: G.Carver,+y: U32,+roof: F32,values: Q.Vec4) -> Bool:
  match settings values:
    case G.Carver{_,+kind,G.Height{minimum,maximum,_,_},+probability,Q.Vec4{+thickness,+horizontal,+vertical,+count},Q.Vec4{floor,room,_,_},_,_,_} Q.Vec4{chamber,a,b,ribbon}:
      carver_active(F32.is_gt(probability,0.0),kind,y,roof,chamber,a,b,ribbon,minimum,maximum,probability,thickness,horizontal,vertical,count,floor,room)

@unsafe
def carver_active(active: Bool,kind: U32,+y: U32,+roof: F32,chamber: F32,a: F32,b: F32,ribbon: F32,minimum: U32,maximum: U32,+probability: F32,+thickness: F32,horizontal: F32,+vertical: F32,+count: F32,floor: F32,room: F32) -> Bool:
  match active kind:
    case False{} _: False{}
    case True{} 1: F32.is_lt(ribbon,F32.sub(1.0,F32.mul(roof,0.25)))
    case True{} _:
      +margin = F32.max(4.0,F32.mul(F32.mul(thickness,vertical),4.0))
      +lower = D.sub(D.integer(minimum),D.scalar(margin))
      +upper = D.add(D.integer(maximum),D.scalar(margin))
      +fade = CV.clamp(F32.mul(F32.min(D.value(D.sub(D.integer(y),lower)),D.value(D.sub(upper,D.integer(y)))),0.125),0.0,1.0)
      +strength = CV.clamp(F32.mul(probability,count),0.0,2.0)
      +width = F32.mul(F32.mul(F32.mul(F32.add(0.042,F32.mul(thickness,0.028)),horizontal),F32.sqrt(F32.max(strength,0.001))),F32.sub(1.0,F32.mul(roof,0.92)))
      Bool.and(Bool.and(Bool.not(D.less(D.integer(y),lower)),Bool.not(D.less(upper,D.integer(y)))),
        F32.is_lt(F32.max(F32.abs(a),F32.mul(F32.abs(b),vertical)),F32.sub(F32.mul(width,fade),CV.clamp(F32.mul(F32.add(floor,1.0),0.03),0.0,0.06))))

@unsafe
def carvers(+n: U32,+i: U32,+settings: Q.Table<G.Carver>,+y: U32,+roof: F32,+values: Q.Vec4) -> Bool:
  match n:
    case 0: False{}
    case _: carver_found(carver(Q.get(G.Carver,settings,i,CV.empty_carver()),y,roof,values),U32.sub(n,1),U32.inc(i),settings,y,roof,values)

@unsafe
def carver_found(found: Bool,n: U32,i: U32,settings: Q.Table<G.Carver>,y: U32,roof: F32,values: Q.Vec4) -> Bool:
  match found:
    case True{}: True{}
    case False{}: carvers(n,i,settings,y,roof,values)

@unsafe
def cavity(values: Q.Vec4,+biome: G.Biome,+y: U32,+height: U32,+aperture: F32) -> Bool:
  match values biome:
    case Q.Vec4{+chamber,+a,+b,+ribbon} G.Biome{+settings}:
      +roof = F32.mul(F32.sub(1.0,aperture),F32.sub(1.0,smooth(0.0,24.0,D.value(D.sub(D.integer(U32.sub(height,1)),D.integer(y))))))
      Bool.or(Bool.and(D.less(D.integer(y),D.integer(U32.sub(height,1))),F32.is_lt(chamber,F32.neg(F32.mul(roof,0.20)))),
        carvers(Q.table_size(G.Carver,settings),0,settings,y,roof,Q.Vec4{chamber,a,b,ribbon}))

@unsafe
def sampled(found: Maybe<&2,Q.Vec4>,biome: G.Biome,y: U32,height: U32,aperture: F32) -> Bool:
  match found:
    case None{}: False{}
    case Some{values}: cavity(values,biome,y,height,aperture)

@unsafe
def carved(ok: Bool,+grid: CV.Grid,biomes: Q.Table<G.Biome>,surface: U32,+point: Q.Point,+y: U32,height: U32,aperture: F32,+low: U32,+high: U32) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: sampled(CV.lookup(grid,MC.at(point,y),low,high),Q.get(G.Biome,biomes,CV.inherit(CV.biome(grid,MC.at(point,y),low,high),surface),G.Biome{Q.Empty{}}),y,height,aperture)

@unsafe
def chamber(found: Maybe<&2,Q.Vec4>) -> F32:
  match found:
    case Some{Q.Vec4{density,_,_,_}}: density
    case None{}: F32.neg(0.02)

@unsafe
def replacement(carved: Bool,aquifer: Maybe<&2,AQ.Prepared>,grid: CV.Grid,+point: Q.Point,+y: U32,sea: U32,+water: U32,+lava: U32,+level: U32,low: U32,high: U32,+original: U32) -> U32:
  match carved aquifer:
    case False{} _: original
    case True{} None{}: N.word(D.less(D.integer(y),D.integer(level)),lava,0)
    case True{} Some{AQ.Prepared{AQ.Config{False{},_,_,_,_,_,_},_,_}}:
      AL.material(AL.global_status(AQ.Params{sea,water,lava,level,False{}},D.integer(y)),D.integer(y))
    case True{} Some{AQ.Prepared{_,_,Some{cache}}}:
      fluid(AS.substance(MC.at(point,y),cache,chamber(CV.lookup(grid,MC.at(point,y),low,high))),water,lava,original)
    case _ _: original

def fluid(substance: U32,water: U32,lava: U32,original: U32) -> U32:
  match substance:
    case 0: 0
    case 1: water
    case 2: lava
    case _: original

@unsafe
def aquifer_covered(aquifer: Maybe<&2,AQ.Prepared>,tile: S.Tile,+minimum: U32,span: U32) -> Bool:
  match aquifer tile:
    case None{} _: True{}
    case Some{AQ.Prepared{AQ.Config{False{},_,_,_,_,_,_},_,_}} _: True{}
    case Some{AQ.Prepared{_,_,Some{+cache}}} S.Tile{+x,+z,width,depth,+low,+high}:
      Bool.and(AS.covered(cache,Q.world(x,minimum,z),low,high),AS.covered(cache,Q.world(U32.add(x,U32.sub(width,1)),U32.add(minimum,U32.sub(span,1)),U32.add(z,U32.sub(depth,1))),low,high))
    case _ _: False{}

@unsafe
def material(+offset: U32,+original: U32,+minimum: U32,+height: U32,+sea: U32,water: U32,flags: Q.Table<Bool>,lava: U32,+level: U32,+aquifer: Maybe<&2,AQ.Prepared>,+grid: CV.Grid,biomes: Q.Table<G.Biome>,surface: U32,+point: Q.Point,aperture: F32,+low: U32,+high: U32) -> U32:
  +y = U32.add(minimum,offset)
  replacement(carved(Bool.and(Q.get(Bool,flags,original,False{}),Bool.and(U32.is_ge(offset,5),
      Bool.and(D.less(D.integer(y),D.integer(height)),Bool.not(Bool.and(Bool.not(D.less(D.integer(sea),D.integer(height))),
        Bool.not(D.less(D.integer(y),D.sub(D.integer(height),D.scalar(4.0))))))))),grid,biomes,surface,point,y,height,aperture,low,high),
    aquifer,grid,point,y,sea,water,lava,level,low,high,original)

@unsafe
def runs(+n: U32,+acc: List<&2,MD.Run>,+original: List<&2,MD.Run>,+minimum: U32,+height: U32,+sea: U32,+water: U32,+flags: Q.Table<Bool>,+lava: U32,+level: U32,+aquifer: Maybe<&2,AQ.Prepared>,+grid: CV.Grid,+biomes: Q.Table<G.Biome>,+surface: U32,+point: Q.Point,+aperture: F32,+low: U32,+high: U32) -> List<&2,MD.Run>:
  match n:
    case 0: acc
    case _: runs(U32.sub(n,1),MC.push(U32.sub(n,1),n,material(U32.sub(n,1),MC.material_at(original,U32.sub(n,1)),minimum,height,sea,water,flags,lava,level,aquifer,grid,biomes,surface,point,aperture,low,high),acc),
      original,minimum,height,sea,water,flags,lava,level,aquifer,grid,biomes,surface,point,aperture,low,high)

@unsafe
def height(runs: List<&2,MD.Run>,+minimum: U32,+water: U32,+lava: U32,+highest: U32) -> U32:
  match runs:
    case Nil{}: highest
    case MD.Run{_,end,+material} <> rest: height(rest,minimum,water,lava,N.word(Bool.and(U32.is_ne(material,0),Bool.and(U32.is_ne(material,water),U32.is_ne(material,lava))),U32.add(minimum,end),highest))

@unsafe
def column(column: MD.Column,+point: Q.Point,grid: CV.Grid,+profile: G.Profile,sea: U32,+water: U32,span: U32,+low: U32,+high: U32) -> MD.Column:
  match column profile:
    case MD.Column{height,+biome,depth,slope,band,secondary,preliminary,original,_,valid} G.Profile{_,biomes,flags,+lava,level,+minimum,_,aquifer}:
      completed(runs(span,[],original,minimum,height,sea,water,flags,lava,level,aquifer,grid,biomes,biome,point,
        entrance(point,U32.xor(low,N.hash(high))),low,high),minimum,water,lava,biome,depth,slope,band,secondary,preliminary,valid)

@unsafe
def completed(+runs: List<&2,MD.Run>,+minimum: U32,water: U32,lava: U32,biome: U32,depth: F32,slope: F32,band: F32,secondary: F32,preliminary: F32,valid: Bool) -> MD.Column:
  MD.Column{height(runs,minimum,water,lava,minimum),biome,depth,slope,band,secondary,preliminary,runs,U32.from_nat(List.length(&2,MD.Run,runs)),valid}

@unsafe
def columns(+limit: U32,+n: U32,+base: U32,+source: Q.Table<MD.Column>,+tile: S.Tile,+grid: CV.Grid,+profile: G.Profile,+sea: U32,+water: U32,+span: U32) -> Q.Table<MD.Column>:
  match n:
    case 1: Q.Leaf{at_column(Q.get(MD.Column,source,base,MC.empty()),S.point(tile,base),tile,grid,profile,sea,water,span)}
    case _: split(U32.is_le(n,limit),limit,n,base,source,tile,grid,profile,sea,water,span)

@unsafe
def at_column(value: MD.Column,point: Q.Point,tile: S.Tile,grid: CV.Grid,profile: G.Profile,sea: U32,water: U32,span: U32) -> MD.Column:
  match tile:
    case S.Tile{_,_,_,_,low,high}: column(value,point,grid,profile,sea,water,span,low,high)

@unsafe
def split(serial: Bool,+limit: U32,+n: U32,+base: U32,+source: Q.Table<MD.Column>,+tile: S.Tile,+grid: CV.Grid,+profile: G.Profile,+sea: U32,+water: U32,+span: U32) -> Q.Table<MD.Column>:
  match serial:
    case True{}: Q.Branch{n,columns(limit,U32.div(n,2),base,source,tile,grid,profile,sea,water,span),columns(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),source,tile,grid,profile,sea,water,span)}
    case False{}:
      +half = U32.div(n,2)
      a b = columns(limit,half,base,source,tile,grid,profile,sea,water,span) columns(limit,U32.sub(n,half),U32.add(base,half),source,tile,grid,profile,sea,water,span)
      Q.Branch{n,a,b}

@unsafe
def compatible(+source: MD.Grid,+grid: CV.Grid,+profile: G.Profile) -> Bool:
  match source profile:
    case MD.Grid{+tile,+minimum,+span,_,_} G.Profile{_,_,_,_,_,gmin,gspan,aquifer}:
      Bool.and(Bool.and(U32.is_eq(minimum,gmin),U32.is_eq(span,gspan)),Bool.and(covered(tile,minimum,span,grid),aquifer_covered(aquifer,tile,minimum,span)))

@unsafe
def covered(tile: S.Tile,+minimum: U32,span: U32,+grid: CV.Grid) -> Bool:
  match tile:
    case S.Tile{+x,+z,width,depth,+low,+high}: Bool.and(present(CV.lookup(grid,Q.world(x,minimum,z),low,high)),
      present(CV.lookup(grid,Q.world(U32.add(x,U32.sub(width,1)),U32.add(minimum,U32.sub(span,1)),U32.add(z,U32.sub(depth,1))),low,high)))

def present(value: Maybe<&2,Q.Vec4>) -> Bool:
  match value:
    case None{}: False{}
    case Some{_}: True{}

@unsafe
def execute(source: MD.Grid,+grid: CV.Grid,profile: G.Profile,sea: U32,water: U32) -> Computed:
  match source:
    case MD.Grid{+tile,minimum,+span,values,_}:
      result : Q.Table<MD.Column> = columns!(U32.max(1,U32.div(U32.add(S.total(tile),16383),16384)),S.total(tile),0,values,tile,grid,profile,sea,water,span)
      Computed{MD.Grid{tile,minimum,span,result,Some{grid}}}

def values(grid: MD.Grid) -> Q.Table<MD.Column>:
  match grid:
    case MD.Grid{_,_,_,values,_}: values
