import Base
import ./density.bend as Q
import ./density_lattice.bend as L
import ./terrain_surface.bend as S
import ./terrain_height.bend as H
import ./shoreline.bend as Shore
import ./cave_lattice.bend as Cave
import ./climate.bend as C
import ./precise.bend as D
import ./structure_registry.bend as M
import ./structure_candidates.bend as Candidates
import ./structure_plan.bend as Plan
import ./structure_geometry.bend as G
import ./structure_random.bend as Random
import ./word64.bend as W

# Compact GPU inputs contain no template/processor payloads. A candidate's
# surface probe is shared by its weighted definitions; only underground starts
# need a second climate lookup at their registered start height.
type Environment is Data:
  Environment{config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,seed: W.U64}
type Spec is Data:
  Spec{underground: Bool,y: U32}
type Probe is Data:
  Probe{x: U32,z: U32,definitions: Q.Table<Spec>}

@unsafe
def projected(+m: M.Model,+d: M.Node) -> Bool:
  Bool.or(Bool.not(M.named(m,M.get(m,d,31n),49n)),U32.is_ne(M.tag(M.get(m,M.get(m,d,33n),58n)),0))

@unsafe
def spec(+m: M.Model,+d: M.Node,+seed: W.U64,+minimum: U32,+height: U32) -> Spec:
  spec_height(Plan.sample_height(m,M.get(m,M.get(m,d,33n),51n),Random.new(seed),minimum,height),Bool.not(projected(m,d)))

@unsafe
def spec_height(draw: Random.Draw,underground: Bool) -> Spec:
  match draw:
    case Random.Draw{_,y}: Spec{underground,y}

@unsafe
def specs(+m: M.Model,+entries: M.Node,+seed: W.U64,+minimum: U32,+height: U32,+i: U32,n: U32) -> List<&2,Spec>:
  match n:
    case 0: []
    case _: member_spec(member(m,entries,i,0,M.count(entries)),m,i,seed,minimum,height) <> specs(m,entries,seed,minimum,height,U32.inc(i),U32.sub(n,1))

@unsafe
def member(+m: M.Model,+entries: M.Node,+id: U32,+i: U32,n: U32) -> Bool:
  match n:
    case 0: False{}
    case _: Bool.or(U32.is_eq(M.int(M.get(m,M.item(entries,i),35n),4294967295),id),member(m,entries,id,U32.inc(i),U32.sub(n,1)))

@unsafe
def member_spec(used: Bool,+m: M.Model,id: U32,seed: W.U64,minimum: U32,height: U32) -> Spec:
  match used:
    case False{}: Spec{False{},0}
    case True{}: spec(m,M.item(Plan.array(m,2n),id),seed,minimum,height)

@unsafe
def probe(+m: M.Model,candidate: Candidates.Start,env: Environment) -> Probe:
  match candidate env:
    case Candidates.Start{+set,+x,+z} Environment{S.Config{minimum,height,_,_,_,_},_,_,_,_,seed}:
      Probe{U32.add(U32.mul(x,16),8),U32.add(U32.mul(z,16),8),Q.prepared_table(Spec,specs(m,M.get(m,Candidates.set(m,set),30n),Candidates.start_seed(m,M.get(m,Candidates.set(m,set),34n),seed,x,z),minimum,height,0,M.count(Plan.array(m,2n))))}

@unsafe
def definition(spec: Spec,env: Environment,+x: U32,+z: U32,surface: U32,+height: D.Number) -> U32:
  match spec env:
    case Spec{True{},+y} Environment{_,_,model,programs,+climate,W.U64{hi,lo}}:
      inherited(Cave.underground(Bool.and(C.has_underground(climate),D.less(D.integer(y),D.sub(height,D.scalar(12.0)))),model,programs,climate,Q.world(x,y,z),lo,hi),surface)
    case _ _: surface

def inherited(id: U32,surface: U32) -> U32:
  match id:
    case 4294967295: surface
    case _: id

@unsafe
def definitions(+n: U32,+base: U32,+specs: Q.Table<Spec>,+env: Environment,+x: U32,+z: U32,+surface: U32,+height: D.Number) -> Q.Table<U32>:
  match n:
    case 0: Q.Empty{}
    case 1: Q.Leaf{definition(Q.get(Spec,specs,base,Spec{False{},0}),env,x,z,surface,height)}
    case _: Q.Branch{n,definitions(U32.div(n,2),base,specs,env,x,z,surface,height),definitions(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),specs,env,x,z,surface,height)}

@unsafe
def sampled(row: S.Row,+p: Probe,+env: Environment) -> Q.Table<U32>:
  match row p env:
    case S.Row{_,_,surface} Probe{+x,+z,+specs} Environment{config,density,model,programs,_,W.U64{hi,lo}}:
      definitions(Q.table_size(Spec,specs),0,specs,env,x,z,surface,Cave.global_height(config,density,model,programs,Q.world(x,0,z),lo,hi))

@unsafe
def sample_ready(+p: Probe,+env: Environment) -> Q.Table<U32>:
  match p env:
    case Probe{x,z,_} Environment{config,density,model,programs,climate,W.U64{hi,lo}}:
      sampled(Shore.global_row(config,density,model,programs,climate,Q.world(x,0,z),lo,hi),p,env)

# Missing region-halo probes need a small local lattice. Fork its vertices
# inside the existing GPU dispatch rather than rebuilding vertical columns in
# long serial noise calls for each shoreline direction. Only biome IDs leave
# this dispatch. The supplied region lattice is reused when it covers the tile.
def probe_tile(p: Probe,env: Environment) -> S.Tile:
  match p env:
    case Probe{x,z,_} Environment{_,_,_,_,climate,W.U64{hi,lo}}:
      +pad = U32.mul(W.flag(Shore.has_shores(climate)),23)
      +width = U32.inc(U32.mul(pad,2))
      S.Tile{U32.sub(x,pad),U32.sub(z,pad),width,width,lo,hi}

@unsafe
def sample_layout(result: Result<&1,&1,U32,L.Layout>,p: Probe,+env: Environment) -> Q.Table<U32>:
  match result env:
    case Fail{_} _: sample_ready(p,env)
    case Done{+layout} Environment{config,_,+model,+programs,climate,seed}:
      sample_ready(p,Environment{config,L.Grid{layout,L.vertices(U32.max(1,U32.div(U32.add(L.size(layout),16383),16384)),L.size(layout),0,model,programs,layout)},model,programs,climate,seed})

@unsafe
def probe_descriptor(bounds: D.Number & D.Number,tile: S.Tile,p: Probe,+env: Environment) -> Q.Table<U32>:
  (low,high) = bounds
  match tile env:
    case S.Tile{+x,+z,width,depth,lo,hi} Environment{S.Config{_,_,_,h,v,_},_,_,_,_,_}:
      sample_layout(L.layout(True{},Q.Point{D.integer(x),low,D.integer(z)},Q.Point{D.integer(U32.sub(U32.add(x,width),1)),high,D.integer(U32.sub(U32.add(z,depth),1))},h,v,1,lo,hi),p,env)

@unsafe
def sample_covered(covered: Bool,p: Probe,+env: Environment,tile: S.Tile) -> Q.Table<U32>:
  match covered env:
    case True{} _: sample_ready(p,env)
    case False{} Environment{config,_,_,_,_,_}: probe_descriptor(S.y_bounds(config),tile,p,env)

@unsafe
def sample(+p: Probe,+env: Environment) -> Q.Table<U32>:
  match env:
    case Environment{config,density,_,_,_,_}: sample_covered(S.compatible(config,probe_tile(p,env),density),p,env,probe_tile(p,env))

@unsafe
def probes(+n: U32,+base: U32,+ps: Q.Table<Probe>,+env: Environment) -> Q.Table<Q.Table<U32>>:
  match n:
    case 0: Q.Empty{}
    case 1: Q.Leaf{sample(Q.get(Probe,ps,base,Probe{0,0,Q.Empty{}}),env)}
    case _:
      +half = U32.div(n,2)
      a b = probes(half,base,ps,env) probes(U32.sub(n,half),U32.add(base,half),ps,env)
      Q.Branch{n,a,b}

def dispatched(+ps: Q.Table<Probe>,env: Environment) -> Q.Table<Q.Table<U32>>:
  result : Q.Table<Q.Table<U32>> = probes!(Q.table_size(Probe,ps),0,ps,env)
  result

def execute_count(n: U32,ps: Q.Table<Probe>,env: Environment) -> Q.Table<Q.Table<U32>>:
  match n:
    case 0: Q.Empty{}
    case _: dispatched(ps,env)

def execute(+ps: Q.Table<Probe>,env: Environment) -> Q.Table<Q.Table<U32>>:
  execute_count(Q.table_size(Probe,ps),ps,env)

@unsafe
def tile(+m: M.Model,+d: M.Node,+x: U32,+z: U32,seed: W.U64) -> S.Tile:
  match seed:
    case W.U64{hi,lo}:
      +radius = U32.inc(U32.div(U32.add(Candidates.definition_reach(m,d),15),16))
      +width = U32.mul(U32.inc(U32.mul(radius,2)),16)
      S.Tile{U32.mul(U32.sub(x,radius),16),U32.mul(U32.sub(z,radius),16),width,width,lo,hi}

@unsafe
def water(+m: M.Model,+d: M.Node) -> Bool:
  +field = M.get(m,M.get(m,d,33n),58n)
  Bool.or(M.named(m,field,59n),M.named(m,field,60n))

@unsafe
def heights(+n: U32,+base: U32,+tile: S.Tile,+config: S.Config,+density: L.Grid,+water: Bool) -> Q.Table<U32>:
  match n:
    case 0: Q.Empty{}
    case 1: Q.Leaf{height(H.height(config,density,S.point(tile,base)),config,water)}
    case _:
      +half = U32.div(n,2)
      Q.Branch{n,heights(half,base,tile,config,density,water),heights(U32.sub(n,half),U32.add(base,half),tile,config,density,water)}

# Keep the GPU task count bounded, like the shared density lattice generator.
@unsafe
def parallel_heights(+n: U32,+base: U32,+limit: U32,+tile: S.Tile,+config: S.Config,+density: L.Grid,+water: Bool) -> Q.Table<U32>:
  height_split(U32.is_gt(n,limit),n,base,limit,tile,config,density,water)

@unsafe
def height_split(split: Bool,+n: U32,+base: U32,+limit: U32,+tile: S.Tile,+config: S.Config,+density: L.Grid,+water: Bool) -> Q.Table<U32>:
  match split:
    case False{}: heights(n,base,tile,config,density,water)
    case True{}:
      +half = U32.div(n,2)
      a b = parallel_heights(half,base,limit,tile,config,density,water) parallel_heights(U32.sub(n,half),U32.add(base,half),limit,tile,config,density,water)
      Q.Branch{n,a,b}

def height(+ground: U32,config: S.Config,water: Bool) -> U32:
  match config water:
    case S.Config{_,_,sea,_,_,_} True{}: G.max(ground,sea)
    case _ False{}: ground

def field_values(tile: S.Tile,values: Q.Table<U32>) -> Plan.Heights:
  match tile:
    case S.Tile{x,z,width,_,_,_}: Plan.Heights{x,z,width,values}

def field(+tile: S.Tile,config: S.Config,density: L.Grid,water: Bool) -> Plan.Heights:
  values : Q.Table<U32> = parallel_heights!(S.total(tile),0,U32.max(1,U32.div(U32.add(S.total(tile),16383),16384)),tile,config,density,water)
  field_values(tile,values)

@unsafe
def generated(result: Result<&1,&1,U32,L.Layout>,tile: S.Tile,config: S.Config,model: Q.Model,programs: Q.Table<Q.Program>,water: Bool) -> Result<&1,&1,U32,Plan.Heights>:
  match result:
    case Fail{code}: Fail{code}
    case Done{layout}: generated_field(L.generate(model,programs,layout),tile,config,water)

def generated_field(result: L.Generated,tile: S.Tile,config: S.Config,water: Bool) -> Result<&1,&1,U32,Plan.Heights>:
  match result:
    case L.Generated{_,_,density}: Done{field(tile,config,density,water)}

@unsafe
def coverage(ok: Bool,+tile: S.Tile,+env: Environment,water: Bool) -> Result<&1,&1,U32,Plan.Heights>:
  match ok env:
    case True{} Environment{config,density,_,_,_,_}: Done{field(tile,config,density,water)}
    case False{} Environment{+config,_,model,programs,_,_}: descriptor(S.y_bounds(config),tile,config,model,programs,water)

@unsafe
def descriptor(bounds: D.Number & D.Number,+tile: S.Tile,+config: S.Config,model: Q.Model,programs: Q.Table<Q.Program>,water: Bool) -> Result<&1,&1,U32,Plan.Heights>:
  (low,high) = bounds
  match tile config:
    case S.Tile{+x,+z,width,depth,lo,hi} S.Config{_,_,_,h,v,_}:
      generated(L.layout(True{},Q.Point{D.integer(x),low,D.integer(z)},Q.Point{D.integer(U32.sub(U32.add(x,width),1)),high,D.integer(U32.sub(U32.add(z,depth),1))},h,v,1,lo,hi),tile,config,model,programs,water)

@unsafe
def terrain(+tile: S.Tile,+env: Environment,water: Bool) -> Result<&1,&1,U32,Plan.Heights>:
  match env:
    case Environment{config,density,_,_,_,_}: coverage(S.compatible(config,tile,density),tile,env,water)
