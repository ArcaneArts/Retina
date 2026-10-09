import Base
import ./lake_data.bend as LD
import ./lake_lattice.bend as LL
import ./lake_candidates.bend as LC
import ./material_columns_data.bend as MD
import ./density.bend as Q
import ./terrain_surface.bend as S
import ./precise.bend as D
import ./noise.bend as N
import ./cave_lattice.bend as CV

# Only opaque resident plans and the loaded fluid ID cross into material
# geometry. Application calculations and interval edits stay in Bend.
type Input is Data:
  Input{cache: Maybe<&2,LD.Cache>,lava: U32}
type Choice is Data:
  Choice{distance: F32,basin: LD.Basin}

def empty() -> Input:
  Input{None{},0}

@unsafe
def distance(basin: LD.Basin,point: Q.Point,+noise: F32) -> F32:
  match basin point:
    case LD.Basin{Q.Point{x,_,z},radius,_,_,_,_,_,_,_} Q.Point{px,_,pz}:
      +dx = D.value(D.sub(px,x))
      +dz = F32.mul(D.value(D.sub(pz,z)),0.78)
      F32.add(F32.div(F32.sqrt(F32.add(F32.mul(dx,dx),F32.mul(dz,dz))),F32.max(radius,1.0)),noise)

@unsafe
def consider(value: Maybe<&2,LD.Basin>,+point: Q.Point,+noise: F32,choice: Choice) -> Choice:
  match value:
    case Some{+basin}: consider_eligible(basin,point,noise,choice)
    case None{}: choice

@unsafe
def consider_eligible(+basin: LD.Basin,point: Q.Point,noise: F32,choice: Choice) -> Choice:
  match basin:
    case LD.Basin{_,_,_,_,_,True{},_,_,_}: nearer(distance(basin,point,noise),basin,choice)
    case _: choice

@unsafe
def nearer(+d: F32,basin: LD.Basin,choice: Choice) -> Choice:
  match choice:
    case Choice{+previous,old}: choice_pick(F32.is_lt(d,previous),Choice{d,basin},Choice{previous,old})

def choice_pick(ok: Bool,a: Choice,b: Choice) -> Choice:
  match ok:
    case True{}: a
    case False{}: b

# Global cells are searched in a fixed order. A basin never gets clipped at a
# cell or region border, and overlapping candidates have a stable winner.
@unsafe
def neighborhood(+n: U32,+cache: LD.Cache,+x: U32,+z: U32,+low: U32,+high: U32,+point: Q.Point,+noise: F32,choice: Choice) -> Choice:
  match n:
    case 9: choice
    case _: neighborhood(U32.inc(n),cache,x,z,low,high,point,noise,
      consider(LL.lookup(cache,U32.add(U32.sub(x,1),U32.mod(n,3)),U32.add(U32.sub(z,1),U32.div(n,3)),low,high),point,noise,choice))

@unsafe
def shape(input: Input,+config: S.Config,+point: Q.Point,+low: U32,+high: U32,height: U32) -> LD.Shape:
  match input:
    case Input{None{},_}: LD.no_shape(height)
    case Input{Some{+cache},lava}: allowed(U32.is_zero(U32.and(LL.column_flags(cache,point),30)),cache,config,point,low,high,height,lava)

@unsafe
def allowed(ok: Bool,cache: LD.Cache,config: S.Config,+point: Q.Point,+low: U32,+high: U32,height: U32,lava: U32) -> LD.Shape:
  match ok point:
    case False{} _: LD.no_shape(height)
    case True{} Q.Point{+px,_,+pz}:
      +x = CV.word(D.axis(px))
      +z = CV.word(D.axis(pz))
      planned(neighborhood(0,cache,LC.cell(x),LC.cell(z),low,high,point,
        F32.mul(N.world_simplex(x,z,0.055,U32.add(low,9913),high),0.09),Choice{1.35,LD.empty_basin()}),config,height,lava)

@unsafe
def planned(choice: Choice,+config: S.Config,+height: U32,lava: U32) -> LD.Shape:
  match choice config:
    case Choice{+d,LD.Basin{_,_,+level,_,_,True{},is_lava,barrier,_}} S.Config{+minimum,+span,+sea,_,_,_}:
      eligible(Bool.and(F32.is_lt(d,1.35),Bool.and(Bool.and(U32.is_ge(span,8),D.less(D.add(D.integer(minimum),D.scalar(6.0)),D.integer(level))),Bool.and(D.less(D.add(D.integer(sea),D.scalar(3.0)),D.integer(level)),
        Bool.not(D.less(D.scalar(18.0),D.absolute(D.sub(D.integer(height),D.integer(level)))))))),d,config,height,level,is_lava,lava,barrier)
    case _ _: LD.no_shape(height)

@unsafe
def eligible(ok: Bool,+d: F32,config: S.Config,height: U32,level: U32,is_lava: Bool,lava: U32,barrier: U32) -> LD.Shape:
  match ok:
    case False{}: LD.no_shape(height)
    case True{}: shaped(F32.is_lt(d,1.0),d,config,height,level,is_lava,lava,barrier)

@unsafe
def shaped(+inner: Bool,+d: F32,config: S.Config,+height: U32,+level: U32,is_lava: Bool,lava: U32,barrier: U32) -> LD.Shape:
  match config:
    case S.Config{+minimum,+span,_,_,_,_}:
      +t = F32.min(F32.max(F32.div(F32.sub(d,1.0),0.35),0.0),1.0)
      +smooth = F32.mul(F32.mul(t,t),F32.sub(3.0,F32.mul(2.0,t)))
      +raw = D.choose(inner,D.sub(D.integer(level),D.scalar(F32.min(F32.max(F32.ceil(F32.mul(F32.sub(1.0,F32.mul(d,d)),6.0)),1.0),7.0))),
        D.floor(D.add(D.mix(D.integer(level),D.integer(height),D.scalar(smooth)),D.scalar(0.5))))
      LD.Shape{True{},inner,height,CV.word(D.axis(D.maximum(D.add(D.integer(minimum),D.scalar(6.0)),
        D.minimum(D.add(D.integer(minimum),D.unsigned(U32.sub(span,1))),raw)))),level,N.word(is_lava,lava,0),barrier}

# Exhaustive, coalesced bottom-up runs are edited by half-open intervals rather
# than walking every voxel. Work is proportional to run count and lake bounds.
@unsafe
def append(+start: U32,+end: U32,+id: U32,acc: List<&2,MD.Run>) -> List<&2,MD.Run>:
  append_nonempty(U32.is_lt(start,end),start,end,id,acc)

@unsafe
def append_nonempty(ok: Bool,+start: U32,+end: U32,+id: U32,acc: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match ok acc:
    case False{} _: acc
    case True{} MD.Run{a,+b,+m} <> rest: append_merge(Bool.and(U32.is_eq(b,start),U32.is_eq(m,id)),a,b,m,rest,start,end,id)
    case True{} Nil{}: [MD.Run{start,end,id}]

@unsafe
def append_merge(same: Bool,a: U32,b: U32,m: U32,rest: List<&2,MD.Run>,start: U32,end: U32,id: U32) -> List<&2,MD.Run>:
  match same:
    case True{}: MD.Run{a,end,id} <> rest
    case False{}: MD.Run{start,end,id} <> MD.Run{a,b,m} <> rest

@unsafe
def replace(runs: List<&2,MD.Run>,+low: U32,+high: U32,+id: U32,acc: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match runs:
    case Nil{}: List.reverse(&2,MD.Run,acc)
    case MD.Run{+start,+end,+old} <> rest:
      replace(rest,low,high,id,append(U32.max(start,high),end,old,
        append(U32.max(start,low),U32.min(end,high),id,append(start,U32.min(end,low),old,acc))))

@unsafe
def geometry(+shape: LD.Shape,config: S.Config,runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match shape config:
    case LD.Shape{False{},_,_,_,_,_,_} _: runs
    case LD.Shape{True{},+inner,+original,+height,+level,_,_} S.Config{+minimum,+span,_,_,_,_}:
      +bottom = U32.sub(height,minimum)
      +floor = U32.sub(bottom,U32.min(bottom,2))
      +filled = replace(List.reverse(&2,MD.Run,runs),U32.min(U32.sub(original,minimum),floor),bottom,2,[])
      List.reverse(&2,MD.Run,fluid_geometry(inner,bottom,U32.sub(level,minimum),replace(filled,bottom,span,0,[])))

@unsafe
def fluid_geometry(inner: Bool,bottom: U32,level: U32,runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match inner:
    case False{}: runs
    case True{}: replace(runs,bottom,level,1,[])

@unsafe
def finish(shape: LD.Shape,+minimum: U32,runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match shape:
    case LD.Shape{True{},True{},_,+height,level,fluid,barrier}:
      floor_barrier(barrier,U32.sub(height,minimum),replace_fluid(fluid,U32.sub(height,minimum),U32.sub(level,minimum),runs))
    case _: runs

@unsafe
def replace_fluid(fluid: U32,bottom: U32,level: U32,runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match fluid:
    case 0: runs
    case _: replace(runs,bottom,level,fluid,[])

@unsafe
def floor_barrier(barrier: U32,+bottom: U32,runs: List<&2,MD.Run>) -> List<&2,MD.Run>:
  match barrier:
    case 0: runs
    case _: replace(runs,U32.sub(bottom,U32.min(bottom,2)),bottom,barrier,[])
