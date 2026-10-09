import Base
import ./geology.bend as G
import ./density.bend as Q
import ./density_lattice.bend as L
import ./terrain_surface.bend as S
import ./terrain_height.bend as H
import ./climate.bend as C
import ./precise.bend as D
import ./noise.bend as N

# Four-block, globally aligned cave fields. Registered density, paired tunnel
# noise and rounded finite canyons are evaluated at nodes, not per block.
type Layout is Data:
  Layout{origin: Q.Point,nx: U32,ny: U32,nz: U32,low: U32,high: U32}
type Sample is Data:
  Sample{fields: Q.Vec4,biome: U32}
type Surface is Data:
  Surface{height: D.Number,carvers: G.Biome}
type Grid is Data:
  Grid{layout: Layout,values: Q.Table<Sample>}
type Computed is Type:
  Computed{model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,geology: G.Profile,grid: Grid}

# Weighted mean avoids intermediate overflow when an exported finite weight is
# large. Transformed coordinates retain the compensated integer residual.
@unsafe
def layers(xs: List<&2,F32>,+frequency: F32,+persistence: F32,+octave: U32,+channel: U32,+point: Q.Point,+seed: U32,+mean: F32,+weights: F32) -> F32 & F32:
  match xs:
    case Nil{}: (mean,weights)
    case modifier <> rest: retained(F32.is_le(frequency,0.125),rest,frequency,persistence,octave,channel,point,seed,mean,weights,F32.mul(F32.max(modifier,0.0),persistence))

@unsafe
def retained(ok: Bool,rest: List<&2,F32>,+frequency: F32,+persistence: F32,+octave: U32,+channel: U32,+point: Q.Point,+seed: U32,+mean: F32,+weights: F32,+weight: F32) -> F32 & F32:
  match ok:
    case False{}: (mean,weights)
    case True{}: weighted(F32.is_gt(weight,0.0),rest,frequency,persistence,octave,channel,point,seed,mean,weights,weight)

@unsafe
def raw(+point: Q.Point,+frequency: F32,seed: U32) -> F32:
  match point:
    case Q.Point{x,y,z}: N.perlin_axes(D.axis(D.scale(x,frequency)),D.axis(D.scale(y,frequency)),D.axis(D.scale(z,frequency)),seed,0)

@unsafe
def weighted(ok: Bool,rest: List<&2,F32>,+frequency: F32,+persistence: F32,+octave: U32,+channel: U32,+point: Q.Point,+seed: U32,+mean: F32,+weights: F32,+weight: F32) -> F32 & F32:
  match ok:
    case False{}: layers(rest,F32.mul(frequency,2.0),F32.mul(persistence,0.5),U32.inc(octave),channel,point,seed,mean,weights)
    case True{}: layers(rest,F32.mul(frequency,2.0),F32.mul(persistence,0.5),U32.inc(octave),channel,point,seed,
      N.mix(mean,raw(point,frequency,U32.add(seed,U32.add(U32.mul(channel,7919),U32.mul(octave,1013)))),F32.div(weight,F32.add(weights,weight))),F32.add(weights,weight))

@unsafe
def sample(noise: G.Noise,channel: U32,point: Q.Point,seed: U32) -> F32:
  match noise:
    case G.Noise{frequency,amplitude,modifiers}: G.normalized(layers(modifiers,frequency,1.0,0,channel,point,seed,0.0,0.0),amplitude)

@unsafe
def noise(+noises: Q.Table<G.Noise>,+channel: U32,point: Q.Point,seed: U32) -> F32:
  sample(Q.get(G.Noise,noises,channel,G.Noise{0.0,0.0,[0.0]}),channel,point,seed)

@unsafe
def scale(point: Q.Point,+xz: F32,y: F32) -> Q.Point:
  match point:
    case Q.Point{x,py,z}: Q.Point{D.scale(x,xz),D.scale(py,y),D.scale(z,xz)}

def clamp(+x: F32,a: F32,b: F32) -> F32:
  F32.min(b,F32.max(a,x))

def random(+h: U32) -> F32:
  F32.div(U32.to_f32(U32.and(h,65535)),65535.0)

@unsafe
def word(axis: N.Axis) -> U32:
  match axis:
    case N.Axis{x,_}: x

@unsafe
def cell(+x: D.Number) -> U32:
  word(D.axis(D.floor(D.divide(x,D.scalar(128.0)))))

@unsafe
def selected_range(+a: F32,+b: F32,+fallback: F32,t: F32) -> F32:
  N.pick(F32.is_gt(b,0.0),N.mix(a,b,t),fallback)

# One 128-block source cell represents 64 possible carver chunks. Candidate
# hashes and local distances remain stable at negative/distant coordinates.
@unsafe
def ravine(+settings: G.Carver,+point: Q.Point,seed: U32) -> F32:
  match settings point:
    case G.Carver{_,1,_,+probability,_,_,_,_,_} Q.Point{+x,+y,+z}:
      candidates(9,cell(x),cell(z),Q.Point{x,y,z},seed,settings,F32.sub(1.0,F32.pow(F32.sub(1.0,probability),64.0)),1000.0)
    case _ _: 1000.0

@unsafe
def candidates(+n: U32,+cx: U32,+cz: U32,+point: Q.Point,+seed: U32,+settings: G.Carver,+probability: F32,+best: F32) -> F32:
  match n:
    case 0: best
    case _: candidate(n,cx,cz,point,seed,settings,probability,best,
      U32.add(cx,U32.sub(U32.mod(U32.sub(n,1),3),1)),U32.add(cz,U32.sub(U32.div(U32.sub(n,1),3),1)))

@unsafe
def candidate(+n: U32,+cx: U32,+cz: U32,+point: Q.Point,+seed: U32,+settings: G.Carver,+probability: F32,+best: F32,+x: U32,+z: U32) -> F32:
  +h = N.hash(U32.xor(U32.xor(U32.mul(x,2654435769),U32.mul(z,2246822507)),U32.xor(seed,17863)))
  candidate_enabled(F32.is_lt(random(h),probability),n,cx,cz,point,seed,settings,probability,best,x,z,h)

@unsafe
def candidate_enabled(ok: Bool,+n: U32,cx: U32,cz: U32,+point: Q.Point,+seed: U32,+settings: G.Carver,probability: F32,best: F32,x: U32,z: U32,h: U32) -> F32:
  match ok:
    case False{}: candidates(U32.sub(n,1),cx,cz,point,seed,settings,probability,best)
    case True{}: candidates(U32.sub(n,1),cx,cz,point,seed,settings,probability,F32.min(best,candidate_shape(point,seed,settings,x,z,h)))

@unsafe
def candidate_shape(point: Q.Point,seed: U32,settings: G.Carver,cx: U32,cz: U32,+h: U32) -> F32:
  +rx = F32.div(U32.to_f32(U32.and(U32.shrn(h,16n),255)),255.0)
  +ry = F32.div(U32.to_f32(U32.and(U32.shrn(N.hash(h),8n),255)),255.0)
  +rz = F32.div(U32.to_f32(U32.and(N.hash(U32.inc(h)),255)),255.0)
  dimensions(point,seed,settings,cx,cz,h,rx,ry,rz,random(N.hash(U32.add(h,3))))

@unsafe
def dimensions(point: Q.Point,seed: U32,settings: G.Carver,cx: U32,cz: U32,h: U32,+rx: F32,+ry: F32,+rz: F32,+hr: F32) -> F32:
  match settings:
    case G.Carver{_,_,G.Height{+y0,+y1,_,+plateau},_,Q.Vec4{thick,horizontal,vertical,_},Q.Vec4{_,factor,smooth,vdefault},
      Q.Vec4{t0,t1,h0,h1},Q.Vec4{d0,d1,vcenter,_},Q.Vec4{v0,v1,r0,r1}}:
      centered(point,seed,cx,cz,h,ry,rz,hr,y0,y1,plateau,
        selected_range(t0,t1,thick,rx),selected_range(h0,h1,horizontal,ry),selected_range(v0,v1,vertical,rz),
        F32.max(1.0,F32.mul(56.0,selected_range(d0,d1,factor,rz))),smooth,vdefault,vcenter,N.mix(r0,r1,hr))

@unsafe
def center_y(+a: U32,+b: U32,+plateau: U32,+h: U32,+r: F32) -> D.Number:
  center_plateau(U32.is_gt(plateau,0),a,b,plateau,h,r)

@unsafe
def center_plateau(ok: Bool,+a: U32,+b: U32,plateau: U32,h: U32,+r: F32) -> D.Number:
  match ok:
    case False{}: D.mix(D.integer(a),D.integer(b),D.scalar(r))
    case True{}:
      +span = D.value(D.sub(D.integer(b),D.integer(a)))
      +slope = F32.mul(F32.sub(span,clamp(F32.sub(U32.to_f32(plateau),1.0),0.0,span)),0.5)
      D.add(D.integer(a),D.scalar(F32.add(F32.mul(r,slope),F32.mul(random(N.hash(U32.add(h,4))),F32.sub(span,slope)))))

@unsafe
def centered(+point: Q.Point,seed: U32,cx: U32,cz: U32,+h: U32,+ry: F32,+rz: F32,+hr: F32,y0: U32,y1: U32,plateau: U32,thickness: F32,horizontal: F32,vertical: F32,length: F32,smooth: F32,vdefault: F32,vcenter: F32,rotation: F32) -> F32:
  match point:
    case Q.Point{x,+y,z}:
      +dx = D.value(D.sub(x,D.add(D.scale(D.integer(cx),128.0),D.scalar(F32.mul(F32.add(0.25,F32.mul(ry,0.5)),128.0)))))
      +dz = D.value(D.sub(z,D.add(D.scale(D.integer(cz),128.0),D.scalar(F32.mul(F32.add(0.25,F32.mul(rz,0.5)),128.0)))))
      +dy = D.value(D.sub(y,center_y(y0,y1,plateau,h,hr)))
      +angle = F32.mul(F32.div(U32.to_f32(U32.and(U32.shrn(h,8n),65535)),65535.0),6.2831853)
      rotated(seed,h,thickness,horizontal,vertical,length,smooth,vdefault,vcenter,rotation,dy,
        F32.add(F32.mul(dx,F32.cos(angle)),F32.mul(dz,F32.sin(angle))),
        F32.add(F32.mul(dx,F32.neg(F32.sin(angle))),F32.mul(dz,F32.cos(angle))),y,point)

@unsafe
def rotated(seed: U32,h: U32,+thickness: F32,+horizontal: F32,+vertical: F32,+length: F32,smooth: F32,+vdefault: F32,+vcenter: F32,rotation: F32,+dy: F32,+along: F32,+side: F32,y: D.Number,point: Q.Point) -> F32:
  +has = Bool.or(F32.is_gt(vdefault,0.0),F32.is_gt(vcenter,0.0))
  +maximum_y = F32.add(F32.mul(F32.mul(F32.mul(F32.add(1.5,thickness),vertical),N.pick(has,F32.max(0.2,F32.add(vdefault,vcenter)),1.0)),1.25),12.0)
  nearby(Bool.and(F32.is_le(F32.abs(dy),maximum_y),Bool.and(F32.is_lt(F32.abs(F32.div(along,length)),1.0),
    F32.is_le(F32.abs(side),F32.add(F32.add(F32.mul(length,0.18),F32.mul(F32.mul(F32.add(1.5,thickness),horizontal),1.5)),6.0)))),
    seed,h,thickness,horizontal,vertical,length,smooth,vdefault,vcenter,rotation,dy,along,side,y,point)

@unsafe
def nearby(ok: Bool,+seed: U32,h: U32,+thickness: F32,+horizontal: F32,+vertical: F32,+length: F32,+smooth: F32,+vdefault: F32,+vcenter: F32,+rotation: F32,+dy: F32,+along: F32,+side: F32,y: D.Number,point: Q.Point) -> F32:
  match ok:
    case False{}: 1000.0
    case True{}:
      +t = F32.div(along,length)
      +phase = U32.to_f32(U32.and(h,31))
      +bend = F32.add(F32.mul(F32.mul(F32.sin(F32.add(F32.mul(t,2.2),phase)),length),0.10),
        F32.mul(raw(Q.Point{D.scalar(F32.mul(along,0.035)),D.scalar(phase),D.scalar(0.0)},1.0,U32.add(seed,19391)),4.0))
      +yb = F32.add(F32.mul(F32.sin(F32.add(F32.mul(t,2.7),phase)),3.0),F32.mul(F32.mul(F32.sin(rotation),F32.sin(F32.mul(t,1.8))),8.0))
      +radius = F32.add(1.5,F32.mul(thickness,F32.sqrt(F32.max(0.0,F32.sub(1.0,F32.mul(t,t))))))
      +edge = raw(Q.Point{D.scalar(F32.mul(along,0.06)),D.divide(y,D.scalar(F32.mul(4.0,F32.max(smooth,1.0)))),D.scalar(phase)},1.0,U32.add(seed,24107))
      +width = F32.max(0.5,F32.mul(F32.mul(radius,horizontal),F32.add(0.82,F32.mul(edge,0.18))))
      +vf = N.pick(Bool.or(F32.is_gt(vdefault,0.0),F32.is_gt(vcenter,0.0)),F32.max(0.2,F32.add(vdefault,F32.mul(vcenter,F32.sub(1.0,F32.abs(t))))),1.0)
      +vr = F32.max(2.0,F32.mul(F32.mul(F32.mul(radius,vertical),vf),F32.add(0.85,F32.mul(edge,0.10))))
      distance(F32.div(F32.add(F32.sub(side,bend),F32.mul(raw(point,0.045,U32.add(seed,31337)),0.6)),width),F32.div(F32.sub(dy,yb),vr),t)

def distance(+x: F32,+y: F32,+t: F32) -> F32:
  F32.add(F32.add(F32.mul(x,x),F32.mul(y,y)),F32.mul(t,t))

@unsafe
def ravines(biome: G.Biome,+point: Q.Point,seed: U32) -> F32:
  match biome:
    case G.Biome{+carvers}: carver_distances(Q.table_size(G.Carver,carvers),0,carvers,point,seed,1000.0)

@unsafe
def carver_distances(n: U32,+i: U32,+carvers: Q.Table<G.Carver>,+point: Q.Point,+seed: U32,+best: F32) -> F32:
  match n:
    case 0: best
    case _: carver_distances(U32.sub(n,1),U32.inc(i),carvers,point,seed,F32.min(best,ravine(Q.get(G.Carver,carvers,i,empty_carver()),point,seed)))

def empty_carver() -> G.Carver:
  G.Carver{0,0,G.Height{0,0,False{},0},0.0,Q.Vec4{0.0,1.0,1.0,0.0},Q.Vec4{0.0,0.0,0.0,0.0},Q.Vec4{0.0,0.0,0.0,0.0},Q.Vec4{0.0,0.0,0.0,0.0},Q.Vec4{0.0,0.0,0.0,0.0}}

def size(layout: Layout) -> U32:
  match layout:
    case Layout{_,nx,ny,nz,_,_}: U32.mul(nx,U32.mul(ny,nz))

def axis_count(top: D.Number,origin: D.Number) -> U32:
  U32.max(2,U32.inc(F32.to_u32(F32.ceil(D.value(D.divide(D.sub(top,origin),D.scalar(4.0)))))))

@unsafe
def layout(config: S.Config,tile: S.Tile) -> Result<&1,&1,U32,Layout>:
  match config tile:
    case S.Config{minimum,height,_,_,_,_} S.Tile{x,z,width,depth,low,high}:
      aligned(Q.world(x,minimum,z),width,height,depth,low,high)

@unsafe
def aligned(+point: Q.Point,width: U32,height: U32,depth: U32,low: U32,high: U32) -> Result<&1,&1,U32,Layout>:
  match point:
    case Q.Point{+x,+y,+z}:
      +ox = Q.lattice(x,4)
      +oy = Q.lattice(y,4)
      +oz = Q.lattice(z,4)
      bounded(Layout{Q.Point{ox,oy,oz},axis_count(D.add(x,D.unsigned(U32.sub(width,1))),ox),
        axis_count(D.add(y,D.unsigned(height)),oy),axis_count(D.add(z,D.unsigned(U32.sub(depth,1))),oz),low,high})

@unsafe
def bounded(+layout: Layout) -> Result<&1,&1,U32,Layout>:
  match layout:
    case Layout{_,+nx,+ny,+nz,_,_}: limited(Bool.and(Bool.and(U32.is_gt(nx,1),U32.is_gt(nz,1)),U32.is_le(nx,U32.div(2097152,U32.mul(ny,nz)))),layout)

@unsafe
def limited(ok: Bool,layout: Layout) -> Result<&1,&1,U32,Layout>:
  match ok:
    case False{}: Fail{738}
    case True{}: Done{layout}

@unsafe
def point(layout: Layout,+i: U32) -> Q.Point:
  match layout:
    case Layout{origin,+nx,+ny,_,_,_}: Q.shifted(origin,D.unsigned(U32.mul(U32.mod(i,nx),4)),
      D.unsigned(U32.mul(U32.mod(U32.div(i,nx),ny),4)),D.unsigned(U32.mul(U32.div(i,U32.mul(nx,ny)),4)))

# Height probes reuse the resident terrain lattice. For a quart anchor outside
# its X/Z coverage (thin/nonaligned tiles), evaluate the same global cells in a
# tiny local lattice on this GPU task. Never clamp to a tile edge or extrapolate.
@unsafe
def global_height(+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+point: Q.Point,+low: U32,+high: U32) -> D.Number:
  match density:
    case L.Grid{L.Layout{+origin,+nx,_,+nz,+h,+v,_,_,_},_}:
      height_relative(L.relative(point,origin,h,v),nx,nz,config,density,model,programs,point,low,high)

@unsafe
def height_relative(relative: Q.Point,nx: U32,nz: U32,config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,point: Q.Point,low: U32,high: U32) -> D.Number:
  match relative:
    case Q.Point{x,_,z}: height_covered(Bool.and(L.contains(x,nx),L.contains(z,nz)),config,density,model,programs,point,low,high)

@unsafe
def height_covered(covered: Bool,+config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,+point: Q.Point,low: U32,high: U32) -> D.Number:
  match covered config point:
    case True{} _ _: D.integer(H.height(config,density,point))
    case False{} S.Config{_,_,_,+h,+v,_} Q.Point{+x,_,+z}:
      height_local(S.y_bounds(config),config,model,programs,Q.lattice(x,h),Q.lattice(z,h),h,v,point,low,high)

@unsafe
def height_local(pair: D.Number & D.Number,+config: S.Config,+model: Q.Model,+programs: Q.Table<Q.Program>,x: D.Number,z: D.Number,h: U32,+v: U32,+point: Q.Point,low: U32,high: U32) -> D.Number:
  (minimum,+maximum) = pair
  +origin = Q.lattice(minimum,v)
  +layout : L.Layout = L.Layout{Q.Point{x,origin,z},2,L.count(maximum,origin,v),2,h,v,1,low,high}
  D.integer(H.height(config,L.Grid{layout,L.serial(L.size(layout),0,model,programs,layout)},point))

def horizontal(point: Q.Point) -> Q.Point:
  match point:
    case Q.Point{x,_,z}: Q.Point{x,D.scalar(0.0),z}

def chosen(choice: C.Choice,biomes: Q.Table<G.Biome>) -> G.Biome:
  match choice:
    case C.Missing{}: G.Biome{Q.Empty{}}
    case C.Choice{_,id,_,_}: Q.get(G.Biome,biomes,id,G.Biome{Q.Empty{}})

@unsafe
def choice(+layout: Layout,+config: S.Config,+density: L.Grid,+enabled: Bool,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+biomes: Q.Table<G.Biome>,+i: U32) -> Surface:
  match layout:
    case Layout{_,+nx,+ny,_,+low,+high}:
      +p = horizontal(point(layout,U32.add(U32.mod(i,nx),U32.mul(U32.div(i,nx),U32.mul(nx,ny)))))
      Surface{surface_height(enabled,config,density,model,programs,p,low,high),
        chosen(C.lookup(climate,C.Query{S.axes(Q.run_index(model,programs,0,p,low,high)),False{},False{},False{},False{}}),biomes)}

@unsafe
def surface_height(enabled: Bool,config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,point: Q.Point,low: U32,high: U32) -> D.Number:
  match enabled:
    case False{}: D.scalar(0.0)
    case True{}: global_height(config,density,model,programs,point,low,high)

@unsafe
def choices(+limit: U32,+n: U32,+base: U32,+layout: Layout,+config: S.Config,+density: L.Grid,+enabled: Bool,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+biomes: Q.Table<G.Biome>) -> Q.Table<Surface>:
  match n:
    case 1: Q.Leaf{choice(layout,config,density,enabled,model,programs,climate,biomes,base)}
    case _: choice_split(U32.is_le(n,limit),limit,n,base,layout,config,density,enabled,model,programs,climate,biomes)

@unsafe
def choice_split(serial: Bool,+limit: U32,+n: U32,+base: U32,+layout: Layout,+config: S.Config,+density: L.Grid,+enabled: Bool,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+biomes: Q.Table<G.Biome>) -> Q.Table<Surface>:
  match serial:
    case True{}: Q.Branch{n,choices(limit,U32.div(n,2),base,layout,config,density,enabled,model,programs,climate,biomes),choices(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),layout,config,density,enabled,model,programs,climate,biomes)}
    case False{}:
      +half = U32.div(n,2)
      a b = choices(limit,half,base,layout,config,density,enabled,model,programs,climate,biomes) choices(limit,U32.sub(n,half),U32.add(base,half),layout,config,density,enabled,model,programs,climate,biomes)
      Q.Branch{n,a,b}

# MAX means inherit the finalized surface biome of the actual block column.
# This preserves coastal remapping above ground instead of redoing it at Y=0.
@unsafe
def selected_biome(enabled: Bool,+surface: Surface,+point: Q.Point,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,low: U32,high: U32) -> U32:
  match surface point:
    case Surface{height,_} Q.Point{+x,+y,+z}:
      underground(Bool.and(enabled,D.less(y,D.sub(height,D.scalar(12.0)))),model,programs,climate,Q.Point{x,y,z},low,high)

@unsafe
def underground(active: Bool,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,point: Q.Point,low: U32,high: U32) -> U32:
  match active:
    case False{}: 4294967295
    case True{}: S.biome(C.lookup(climate,C.Query{S.axes(Q.run_index(model,programs,0,point,low,high)),False{},True{},False{},False{}}))

def carver_biome(id: U32,surface: Surface,biomes: Q.Table<G.Biome>) -> G.Biome:
  match id surface:
    case 4294967295 Surface{_,carvers}: carvers
    case _ _: Q.get(G.Biome,biomes,id,G.Biome{Q.Empty{}})

@unsafe
def vertex(+layout: Layout,+enabled: Bool,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+noises: Q.Table<G.Noise>,+biomes: Q.Table<G.Biome>,+surfaces: Q.Table<Surface>,+i: U32) -> Sample:
  match layout:
    case Layout{_,+nx,+ny,_,+low,+high}:
      +p = point(layout,i)
      +surface = Q.get(Surface,surfaces,U32.add(U32.mod(i,nx),U32.mul(nx,U32.div(i,U32.mul(nx,ny)))),Surface{D.scalar(0.0),G.Biome{Q.Empty{}}})
      +id = selected_biome(enabled,surface,p,model,programs,climate,low,high)
      +seed = U32.xor(low,N.hash(high))
      +rough = noise(noises,4,p,seed)
      Sample{Q.Vec4{D.value(S.first(Q.run_index(model,programs,2,p,low,high))),
        F32.add(noise(noises,1,scale(p,1.6,1.0),seed),F32.mul(rough,0.06)),
        F32.sub(noise(noises,2,scale(p,1.6,1.0),seed),F32.mul(rough,0.06)),
        ravines(carver_biome(id,surface,biomes),p,seed)},id}

@unsafe
def vertices(+limit: U32,+n: U32,+base: U32,+layout: Layout,+enabled: Bool,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+noises: Q.Table<G.Noise>,+biomes: Q.Table<G.Biome>,+surfaces: Q.Table<Surface>) -> Q.Table<Sample>:
  match n:
    case 1: Q.Leaf{vertex(layout,enabled,model,programs,climate,noises,biomes,surfaces,base)}
    case _: vertex_split(U32.is_le(n,limit),limit,n,base,layout,enabled,model,programs,climate,noises,biomes,surfaces)

@unsafe
def vertex_split(serial: Bool,+limit: U32,+n: U32,+base: U32,+layout: Layout,+enabled: Bool,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+noises: Q.Table<G.Noise>,+biomes: Q.Table<G.Biome>,+surfaces: Q.Table<Surface>) -> Q.Table<Sample>:
  match serial:
    case True{}: Q.Branch{n,vertices(limit,U32.div(n,2),base,layout,enabled,model,programs,climate,noises,biomes,surfaces),vertices(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),layout,enabled,model,programs,climate,noises,biomes,surfaces)}
    case False{}:
      +half = U32.div(n,2)
      a b = vertices(limit,half,base,layout,enabled,model,programs,climate,noises,biomes,surfaces) vertices(limit,U32.sub(n,half),U32.add(base,half),layout,enabled,model,programs,climate,noises,biomes,surfaces)
      Q.Branch{n,a,b}

def generate(+layout: Layout,+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,geology: G.Profile) -> Computed:
  match layout geology:
    case Layout{_,+nx,_,+nz,_,_} G.Profile{+noises,+biomes,+flags,+lava,+level,+minimum,+height,aquifer}:
      +enabled = C.has_underground(climate)
      selected : Q.Table<Surface> = choices!(U32.max(1,U32.div(U32.add(U32.mul(nx,nz),16383),16384)),U32.mul(nx,nz),0,layout,config,density,enabled,model,programs,climate,biomes)
      values : Q.Table<Sample> = vertices!(U32.max(1,U32.div(U32.add(size(layout),16383),16384)),size(layout),0,layout,enabled,model,programs,climate,noises,biomes,selected)
      Computed{model,programs,climate,G.Profile{noises,biomes,flags,lava,level,minimum,height,aquifer},Grid{layout,values}}

def mix(a: Q.Vec4,b: Q.Vec4,+t: F32) -> Q.Vec4:
  match a b:
    case Q.Vec4{a,b,c,d} Q.Vec4{e,f,g,h}: Q.Vec4{N.mix(a,e,t),N.mix(b,f,t),N.mix(c,g,t),N.mix(d,h,t)}

@unsafe
def get(+values: Q.Table<Sample>,i: U32) -> Q.Vec4:
  fields(Q.get(Sample,values,i,Sample{Q.Vec4{0.0,0.0,0.0,1000.0},4294967295}))

def fields(sample: Sample) -> Q.Vec4:
  match sample:
    case Sample{fields,_}: fields

@unsafe
def interpolate(+values: Q.Table<Sample>,+i: U32,+nx: U32,+xy: U32,+x: F32,+y: F32,z: F32) -> Q.Vec4:
  mix(mix(mix(get(values,i),get(values,U32.inc(i)),x),mix(get(values,U32.add(i,nx)),get(values,U32.inc(U32.add(i,nx))),x),y),
    mix(mix(get(values,U32.add(i,xy)),get(values,U32.inc(U32.add(i,xy))),x),mix(get(values,U32.add(U32.add(i,xy),nx)),get(values,U32.inc(U32.add(U32.add(i,xy),nx))),x),y),z)

@unsafe
def located(ok: Bool,relative: Q.Point,+nx: U32,+ny: U32,nz: U32,values: Q.Table<Sample>) -> Maybe<&2,Q.Vec4>:
  match ok relative:
    case False{} _: None{}
    case True{} Q.Point{+x,+y,+z}:
      +ix = U32.min(F32.to_u32(D.value(D.floor(x))),U32.sub(nx,2))
      +iy = U32.min(F32.to_u32(D.value(D.floor(y))),U32.sub(ny,2))
      +iz = U32.min(F32.to_u32(D.value(D.floor(z))),U32.sub(nz,2))
      Some{interpolate(values,U32.add(ix,U32.mul(nx,U32.add(iy,U32.mul(ny,iz)))),nx,U32.mul(nx,ny),D.value(D.sub(x,D.unsigned(ix))),D.value(D.sub(y,D.unsigned(iy))),D.value(D.sub(z,D.unsigned(iz))))}

@unsafe
def lookup(+grid: Grid,+point: Q.Point,+low: U32,+high: U32) -> Maybe<&2,Q.Vec4>:
  match grid:
    case Grid{Layout{origin,+nx,+ny,+nz,lo,hi},values}:
      relative_checked(Bool.and(U32.is_eq(low,lo),U32.is_eq(high,hi)),L.relative(point,origin,4,4),nx,ny,nz,values)

@unsafe
def relative_checked(ok: Bool,+relative: Q.Point,+nx: U32,+ny: U32,+nz: U32,values: Q.Table<Sample>) -> Maybe<&2,Q.Vec4>:
  match relative:
    case Q.Point{+x,+y,+z}: located(Bool.and(ok,Bool.and(L.contains(x,nx),Bool.and(L.contains(y,ny),L.contains(z,nz)))),Q.Point{x,y,z},nx,ny,nz,values)

def grid_layout(grid: Grid) -> Layout:
  match grid:
    case Grid{layout,_}: layout

def finite_values(values: Q.Table<Sample>) -> Bool:
  match values:
    case Q.Empty{}: True{}
    case Q.Leaf{Sample{Q.Vec4{a,b,c,d},_}}: Bool.and(Bool.and(D.finite(a),D.finite(b)),Bool.and(D.finite(c),D.finite(d)))
    case Q.Branch{_,a,b}: Bool.and(finite_values(a),finite_values(b))

def finite(grid: Grid) -> Bool:
  match grid:
    case Grid{_,values}: finite_values(values)

# Discrete quart IDs use the containing global four-block cell. Field values
# interpolate independently. The inclusive final node is a valid sample too.
@unsafe
def biome(+grid: Grid,+point: Q.Point,+low: U32,+high: U32) -> Maybe<&2,U32>:
  match grid:
    case Grid{Layout{origin,+nx,+ny,+nz,lo,hi},values}:
      biome_relative(Bool.and(U32.is_eq(low,lo),U32.is_eq(high,hi)),L.relative(point,origin,4,4),nx,ny,nz,values)

@unsafe
def biome_relative(ok: Bool,+relative: Q.Point,+nx: U32,+ny: U32,+nz: U32,values: Q.Table<Sample>) -> Maybe<&2,U32>:
  match relative:
    case Q.Point{+x,+y,+z}: biome_located(Bool.and(ok,Bool.and(L.contains(x,nx),Bool.and(L.contains(y,ny),L.contains(z,nz)))),Q.Point{x,y,z},nx,ny,values)

@unsafe
def biome_located(ok: Bool,relative: Q.Point,+nx: U32,+ny: U32,values: Q.Table<Sample>) -> Maybe<&2,U32>:
  match ok relative:
    case False{} _: None{}
    case True{} Q.Point{x,y,z}:
      sample_biome(Q.get(Sample,values,U32.add(F32.to_u32(D.value(D.floor(x))),U32.mul(nx,U32.add(F32.to_u32(D.value(D.floor(y))),U32.mul(ny,F32.to_u32(D.value(D.floor(z))))))),Sample{Q.Vec4{0.0,0.0,0.0,1000.0},4294967295}))

def sample_biome(sample: Sample) -> Maybe<&2,U32>:
  match sample:
    case Sample{_,id}: Some{id}

def inherit(found: Maybe<&2,U32>,surface: U32) -> U32:
  match found:
    case None{}: surface
    case Some{4294967295}: surface
    case Some{id}: id
