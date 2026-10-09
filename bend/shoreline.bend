import Base
import ./density.bend as Q
import ./density_lattice.bend as L
import ./terrain_surface.bend as S
import ./terrain_height.bend as H
import ./cave_lattice.bend as CV
import ./climate.bend as C
import ./precise.bend as D


def point_y(point: Q.Point,y: U32) -> Q.Point:
  match point:
    case Q.Point{x,_,z}: Q.Point{x,D.integer(y),z}

# The global variant is for consumers with a small resident tile. It samples
# the same pre-carving integer occupancy outside that tile, rather than clamping
# shoreline probes to an available vertex. All calculations stay in Bend.
@unsafe
def global_probes(+index: U32,+height: U32,+sea: U32,+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+point: Q.Point,+low: U32,+high: U32) -> Bool:
  match index:
    case 24: False{}
    case _: global_probe_done(opposite(height,CV.word(D.axis(CV.global_height(config,density,model,programs,shifted(point,offset(index)),low,high))),sea),U32.inc(index),height,sea,config,density,model,programs,point,low,high)

@unsafe
def global_probe_done(found: Bool,index: U32,height: U32,sea: U32,config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,point: Q.Point,low: U32,high: U32) -> Bool:
  match found:
    case True{}: True{}
    case False{}: global_probes(index,height,sea,config,density,model,programs,point,low,high)

@unsafe
def global_near(active: Bool,height: U32,sea: U32,config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,point: Q.Point,low: U32,high: U32) -> Bool:
  match active:
    case False{}: False{}
    case True{}: global_probes(0,height,sea,config,density,model,programs,point,low,high)

@unsafe
def global_at(+height: U32,config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,+climate: C.Model,point: Q.Point,low: U32,high: U32,+axes: C.Axes) -> S.Row:
  match config:
    case S.Config{_,_,+sea,_,_,_}:
      S.Row{D.value(D.integer(height)),axes,biome(selected(global_near(coast_height(height,sea),height,sea,config,density,model,programs,point,low,high),climate,axes,inland(climate,axes)))}

@unsafe
def global_kind(coasts: Bool,+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,+point: Q.Point,+low: U32,+high: U32) -> S.Row:
  match coasts:
    case False{}: raw_row(S.axes(Q.run_index(model,programs,0,point,low,high)),climate)
    case True{}: global_at(CV.word(D.axis(CV.global_height(config,density,model,programs,point,low,high))),config,density,model,programs,climate,point,low,high,S.axes(Q.run_index(model,programs,0,point,low,high)))

@unsafe
def raw_row(+axes: C.Axes,climate: C.Model) -> S.Row:
  S.Row{0.0,axes,biome(C.lookup(climate,C.Query{axes,False{},False{},False{},False{}}))}

@unsafe
def global_row(config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,+climate: C.Model,point: Q.Point,low: U32,high: U32) -> S.Row:
  global_kind(has_shores(climate),config,density,model,programs,climate,point,low,high)

@unsafe
def coast_height(+height: U32,+sea: U32) -> Bool:
  Bool.and(Bool.not(D.less(D.integer(height),D.sub(D.integer(sea),D.scalar(2.0)))),Bool.not(D.less(D.add(D.integer(sea),D.scalar(3.0)),D.integer(height))))

type Heights is Data:
  Heights{tile: S.Tile,values: Q.Table<U32>}

# Coastal selection is a separate resident GPU pass. It uses the exact highest
# solid integer voxel from the pre-cave density snapshot, including islands.
# No coarse biome quart, CPU noise or downloaded height map controls a shore.
def has_shores(climate: C.Model) -> Bool:
  match climate:
    case C.Model{_,_,C.Empty{}}: False{}
    case _: True{}

def halo(+tile: S.Tile) -> S.Tile:
  match tile:
    case S.Tile{x,z,width,depth,low,high}: S.Tile{U32.sub(x,6),U32.sub(z,6),U32.add(width,12),U32.add(depth,12),low,high}

def halo_bounds(tile: S.Tile) -> Bool:
  match tile:
    case S.Tile{+x,+z,width,depth,_,_}:
      Bool.and(Bool.and(Bool.not(D.less(D.integer(x),D.integer(2147483654))),Bool.not(D.less(D.integer(z),D.integer(2147483654)))),
        Bool.and(Bool.not(D.less(D.integer(2147483647),D.add(D.integer(x),D.unsigned(U32.add(width,5))))),
          Bool.not(D.less(D.integer(2147483647),D.add(D.integer(z),D.unsigned(U32.add(depth,5)))))))

def compatible_kind(coasts: Bool,+config: S.Config,+tile: S.Tile,+density: L.Grid) -> Bool:
  match coasts:
    case False{}: S.compatible(config,tile,density)
    case True{}: Bool.and(halo_bounds(tile),S.compatible(config,halo(tile),density))

def compatible(config: S.Config,surface: S.Grid,density: L.Grid,climate: C.Model) -> Bool:
  match surface:
    case S.Grid{tile,_}: compatible_kind(has_shores(climate),config,tile,density)

def biome(choice: C.Choice) -> U32:
  match choice:
    case C.Missing{}: 4294967295
    case C.Choice{_,id,_,_}: id

def fallback(choice: C.Choice,other: C.Choice) -> C.Choice:
  match choice:
    case C.Missing{}: other
    case _: choice

# A shore-only source remains usable. Ordinary worlds remove shore targets
# from inland selection, then let the full registry choose coastal alternatives.
def inland_choice(choice: C.Choice,climate: C.Model,point: C.Axes) -> C.Choice:
  match choice:
    case C.Missing{}: C.lookup(climate,C.Query{point,False{},False{},False{},False{}})
    case _: choice

def inland(+climate: C.Model,+point: C.Axes) -> C.Choice:
  inland_choice(C.lookup(climate,C.Query{point,False{},False{},False{},True{}}),climate,point)

def remapped(choice: C.Choice,+climate: C.Model,point: C.Axes,base: C.Choice) -> C.Choice:
  match choice point:
    case C.Missing{} _: base
    case C.Choice{_,_,_,continentalness} C.Axes{a,b,_,c,d,e}:
      fallback(C.lookup(climate,C.Query{C.Axes{a,b,continentalness,c,d,e},False{},False{},False{},False{}}),base)

def selected(coastal: Bool,+climate: C.Model,+point: C.Axes,base: C.Choice) -> C.Choice:
  match coastal:
    case False{}: base
    case True{}: remapped(C.coast(climate,point),climate,point,base)

def direction(index: U32,+step: D.Number) -> Q.Point:
  match index:
    case 0: Q.Point{step,D.scalar(0.0),D.scalar(0.0)}
    case 1: Q.Point{D.negate(step),D.scalar(0.0),D.scalar(0.0)}
    case 2: Q.Point{D.scalar(0.0),D.scalar(0.0),step}
    case 3: Q.Point{D.scalar(0.0),D.scalar(0.0),D.negate(step)}
    case 4: Q.Point{step,D.scalar(0.0),step}
    case 5: Q.Point{D.negate(step),D.scalar(0.0),step}
    case 6: Q.Point{step,D.scalar(0.0),D.negate(step)}
    case _: Q.Point{D.negate(step),D.scalar(0.0),D.negate(step)}

def offset_step(diagonal: Bool,index: U32,radius: U32) -> Q.Point:
  match diagonal:
    case False{}: direction(index,D.unsigned(radius))
    case True{}: direction(index,D.scalar(F32.round(F32.mul(U32.to_f32(radius),0.70710678))))

def offset(+index: U32) -> Q.Point:
  +radius = U32.mul(U32.inc(U32.div(index,8)),2)
  offset_step(U32.is_ge(U32.mod(index,8),4),U32.mod(index,8),radius)

def shifted(point: Q.Point,offset: Q.Point) -> Q.Point:
  match offset:
    case Q.Point{x,y,z}: Q.shifted(point,x,y,z)

def opposite_kind(submerged: Bool,probe: U32,sea: U32) -> Bool:
  match submerged:
    case False{}: D.less(D.integer(probe),D.integer(sea))
    case True{}: Bool.not(D.less(D.integer(probe),D.integer(sea)))

def opposite(+height: U32,probe: U32,+sea: U32) -> Bool:
  opposite_kind(D.less(D.integer(height),D.integer(sea)),probe,sea)

@unsafe
def solid_relative(relative: Q.Point,+nx: U32,+ny: U32,+nz: U32,+values: Q.Table<Q.Outputs>) -> Bool:
  match relative:
    case Q.Point{+x,+y,+z}:
      +ix = U32.min(F32.to_u32(D.value(D.floor(x))),U32.sub(nx,2))
      +iy = U32.min(F32.to_u32(D.value(D.floor(y))),U32.sub(ny,2))
      +iz = U32.min(F32.to_u32(D.value(D.floor(z))),U32.sub(nz,2))
      +tx = D.sub(x,D.unsigned(ix))
      +tz = D.sub(z,D.unsigned(iz))
      # Keep material vertical sampling's bilinear-XZ-then-Y operation order exactly.
      D.less(D.scalar(0.0),D.mix(S.layer(values,ix,iz,nx,ny,iy,tx,tz),
        S.layer(values,ix,iz,nx,ny,U32.inc(iy),tx,tz),D.sub(y,D.unsigned(iy))))

@unsafe
def solid(density: L.Grid,point: Q.Point,y: U32) -> Bool:
  match density:
    case L.Grid{L.Layout{origin,nx,ny,nz,h,v,_,_,_},values}:
      solid_relative(L.relative(point_y(point,y),origin,h,v),nx,ny,nz,values)

# Reuse the topmost continuous crossing (or a previously finalized height), but
# never treat its rounded F32 floor as occupancy. Verify adjacent integer signs
# with the same interpolation order as material columns. Exact-zero crossings
# can shift the answer down one block. Clipped islands, tiny-density crossings
# and ambiguous signs fall back to the full existing integer scan.
@unsafe
def corrected(below: Bool,+candidate: U32,+offset: U32,+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+point: Q.Point,+low: U32,+high: U32) -> U32:
  match below config:
    case True{} S.Config{_,+span,_,_,_,_}:
      bracket(Bool.or(U32.is_eq(offset,span),Bool.not(solid(density,point,candidate))),candidate,config,density,model,programs,point,low,high)
    case False{} _:
      lower_candidate(U32.is_ge(offset,2),candidate,config,density,model,programs,point,low,high)

@unsafe
def bracket(ok: Bool,candidate: U32,config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,point: Q.Point,low: U32,high: U32) -> U32:
  match ok:
    case True{}: candidate
    case False{}: H.height(config,density,point)

@unsafe
def lower_candidate(valid: Bool,+candidate: U32,+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+point: Q.Point,+low: U32,+high: U32) -> U32:
  match valid:
    case False{}: H.height(config,density,point)
    case True{}: bracket(solid(density,point,U32.sub(candidate,2)),U32.sub(candidate,1),config,density,model,programs,point,low,high)

@unsafe
def candidate(nonempty: Bool,+offset: U32,+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+point: Q.Point,+low: U32,+high: U32) -> U32:
  match nonempty config:
    case False{} _: H.height(config,density,point)
    case True{} S.Config{+minimum,_,_,_,_,_}:
      corrected(solid(density,point,U32.add(minimum,U32.sub(offset,1))),U32.add(minimum,offset),offset,config,density,model,programs,point,low,high)

@unsafe
def guessed_kind(fast: Bool,hint: D.Number,+config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,point: Q.Point,low: U32,high: U32) -> U32:
  match fast config:
    case False{} _: H.height(config,density,point)
    case True{} S.Config{+minimum,+span,_,_,_,_}:
      +offset = F32.to_u32(D.value(D.maximum(D.scalar(0.0),D.minimum(D.unsigned(span),D.sub(D.floor(hint),D.integer(minimum))))))
      candidate(U32.is_gt(offset,0),offset,config,density,model,programs,point,low,high)

@unsafe
def guessed(+hint: D.Number,+config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,point: Q.Point,low: U32,high: U32) -> U32:
  match config:
    case S.Config{_,_,_,_,_,True{}}: H.height(config,density,point)
    case S.Config{+minimum,+span,_,_,_,False{}}:
      # Raw surface rows use F32. Outside its sub-block precision range, retain
      # the full compensated scan instead of trusting a rounded height hint.
      guessed_kind(Bool.and(D.finite(D.value(hint)),Bool.and(F32.is_le(F32.abs(D.value(D.integer(minimum))),8388608.0),
        F32.is_le(F32.abs(D.value(D.add(D.integer(minimum),D.unsigned(span)))),8388608.0))),hint,config,density,model,programs,point,low,high)

@unsafe
def hint_at(inside: Bool,+dx: D.Number,+dz: D.Number,+width: U32,values: Q.Table<S.Row>,config: S.Config,density: L.Grid,point: Q.Point) -> D.Number:
  match inside:
    case False{}: S.height(config,point,density)
    case True{}: hint_row(Q.get(S.Row,values,U32.add(F32.to_u32(D.value(dx)),U32.mul(width,F32.to_u32(D.value(dz)))),S.empty()))

def hint_row(row: S.Row) -> D.Number:
  match row:
    case S.Row{height,_,_}: D.scalar(height)

@unsafe
def hint(surface: S.Grid,+config: S.Config,+density: L.Grid,+point: Q.Point) -> D.Number:
  match surface point:
    case S.Grid{S.Tile{x,z,+width,+depth,_,_},values} Q.Point{px,_,pz}:
      +dx = D.sub(px,D.integer(x))
      +dz = D.sub(pz,D.integer(z))
      hint_at(Bool.and(L.contains(dx,width),L.contains(dz,depth)),dx,dz,width,values,config,density,point)

@unsafe
def height_row(+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,surface: S.Grid,+tile: S.Tile,+index: U32) -> U32:
  match tile:
    case S.Tile{_,_,_,_,low,high}:
      guessed(hint(surface,config,density,S.point(tile,index)),config,density,model,programs,S.point(tile,index),low,high)

@unsafe
def heights(+limit: U32,+n: U32,+base: U32,+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+surface: S.Grid,+tile: S.Tile) -> Q.Table<U32>:
  match n:
    case 1: Q.Leaf{height_row(config,density,model,programs,surface,tile,base)}
    case _: height_split(U32.is_le(n,limit),limit,n,base,config,density,model,programs,surface,tile)

@unsafe
def height_split(serial: Bool,+limit: U32,+n: U32,+base: U32,+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+surface: S.Grid,+tile: S.Tile) -> Q.Table<U32>:
  match serial:
    case True{}: Q.Branch{n,heights(limit,U32.div(n,2),base,config,density,model,programs,surface,tile),
      heights(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),config,density,model,programs,surface,tile)}
    case False{}:
      +half = U32.div(n,2)
      a b = heights(limit,half,base,config,density,model,programs,surface,tile)
        heights(limit,U32.sub(n,half),U32.add(base,half),config,density,model,programs,surface,tile)
      Q.Branch{n,a,b}

def height_tile(coasts: Bool,+tile: S.Tile) -> S.Tile:
  match coasts:
    case True{}: halo(tile)
    case False{}: tile

@unsafe
def height_at(cache: Heights,point: Q.Point) -> U32:
  match cache point:
    case Heights{S.Tile{x,z,width,_,_,_},values} Q.Point{px,_,pz}:
      Q.get(U32,values,U32.add(F32.to_u32(D.value(D.sub(px,D.integer(x)))),
        U32.mul(width,F32.to_u32(D.value(D.sub(pz,D.integer(z)))))),0)

@unsafe
def probes(+index: U32,+height: U32,+sea: U32,+cache: Heights,+point: Q.Point) -> Bool:
  match index:
    case 24: False{}
    case _: probe_done(opposite(height,height_at(cache,shifted(point,offset(index))),sea),U32.inc(index),height,sea,cache,point)

@unsafe
def probe_done(found: Bool,index: U32,height: U32,sea: U32,cache: Heights,point: Q.Point) -> Bool:
  match found:
    case True{}: True{}
    case False{}: probes(index,height,sea,cache,point)

@unsafe
def near(active: Bool,height: U32,sea: U32,cache: Heights,point: Q.Point) -> Bool:
  match active:
    case False{}: False{}
    case True{}: probes(0,height,sea,cache,point)

@unsafe
def at(+height: U32,+config: S.Config,cache: Heights,+climate: C.Model,point: Q.Point,+axes: C.Axes) -> S.Row:
  match config:
    case S.Config{_,_,+sea,_,_,_}:
      S.Row{D.value(D.integer(height)),axes,biome(selected(near(Bool.and(has_shores(climate),
        coast_height(height,sea)),
        height,sea,cache,point),climate,axes,inland(climate,axes)))}

@unsafe
def row(config: S.Config,+cache: Heights,climate: C.Model,+tile: S.Tile,values: Q.Table<S.Row>,+index: U32) -> S.Row:
  sampled(Q.get(S.Row,values,index,S.empty()),height_at(cache,S.point(tile,index)),config,cache,climate,S.point(tile,index))

@unsafe
def sampled(row: S.Row,height: U32,config: S.Config,cache: Heights,climate: C.Model,point: Q.Point) -> S.Row:
  match row:
    case S.Row{_,axes,_}: at(height,config,cache,climate,point,axes)

@unsafe
def rows(+limit: U32,+n: U32,+base: U32,+config: S.Config,+cache: Heights,+climate: C.Model,+tile: S.Tile,+values: Q.Table<S.Row>) -> Q.Table<S.Row>:
  match n:
    case 1: Q.Leaf{row(config,cache,climate,tile,values,base)}
    case _: split(U32.is_le(n,limit),limit,n,base,config,cache,climate,tile,values)

@unsafe
def split(serial: Bool,+limit: U32,+n: U32,+base: U32,+config: S.Config,+cache: Heights,+climate: C.Model,+tile: S.Tile,+values: Q.Table<S.Row>) -> Q.Table<S.Row>:
  match serial:
    case True{}: Q.Branch{n,rows(limit,U32.div(n,2),base,config,cache,climate,tile,values),
      rows(limit,U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),config,cache,climate,tile,values)}
    case False{}:
      +half = U32.div(n,2)
      a b = rows(limit,half,base,config,cache,climate,tile,values)
        rows(limit,U32.sub(n,half),U32.add(base,half),config,cache,climate,tile,values)
      Q.Branch{n,a,b}

def execute(+config: S.Config,+density: L.Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,+climate: C.Model,surface: S.Grid) -> S.Computed:
  match surface:
    case S.Grid{+tile,+values}:
      +expanded = height_tile(has_shores(climate),tile)
      # Two bulk device steps: exact height cache, then bounded coastal lookups.
      # No per-neighbor rescan and no intermediate height payload crosses IPC.
      cached : Q.Table<U32> = heights!(U32.max(1,U32.div(U32.add(S.total(expanded),16383),16384)),S.total(expanded),0,config,density,model,programs,S.Grid{tile,values},expanded)
      next : Q.Table<S.Row> = rows!(U32.max(1,U32.div(U32.add(S.total(tile),16383),16384)),S.total(tile),0,config,Heights{expanded,cached},climate,tile,values)
      S.Computed{model,programs,density,climate,S.Grid{tile,next}}
