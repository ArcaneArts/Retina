import Base
import ./density.bend as Q
import ./precise.bend as D

# Registry-derived aquifer graphs. These immutable records and all field/search
# work run unchanged on the selected Bend CPU/GPU backend.
type Config is Data:
  Config{enabled: Bool,program: U32,bottom: U32,step: U32,explicit: Bool,minimum: U32,height: U32}
type Prepared is Data:
  Prepared{config: Config,programs: Q.Table<Q.Program>,cache: Maybe<&2,Cache>}

def first(outputs: Q.Outputs) -> D.Number:
  match outputs:
    case Q.Outputs{a,_,_,_,_,_}: a

def at(point: Q.Point,y: D.Number) -> Q.Point:
  match point:
    case Q.Point{x,_,z}: Q.Point{x,y,z}

@unsafe
def search(+y: D.Number,+bottom: D.Number,+step: U32,+fallback: U32,+model: Q.Model,+programs: Q.Table<Q.Program>,+program: U32,+point: Q.Point,+low: U32,+high: U32) -> D.Number:
  search_inside(Bool.not(D.less(y,bottom)),y,bottom,step,fallback,model,programs,program,point,low,high)

@unsafe
def search_inside(inside: Bool,+y: D.Number,bottom: D.Number,step: U32,fallback: U32,+model: Q.Model,+programs: Q.Table<Q.Program>,+program: U32,+point: Q.Point,+low: U32,+high: U32) -> D.Number:
  match inside:
    case False{}: D.integer(fallback)
    case True{}: search_found(D.less(D.scalar(0.0),first(Q.run_index(model,programs,program,at(point,y),low,high))),y,bottom,step,fallback,model,programs,program,point,low,high)

@unsafe
def search_found(found: Bool,+y: D.Number,bottom: D.Number,+step: U32,fallback: U32,model: Q.Model,programs: Q.Table<Q.Program>,program: U32,point: Q.Point,low: U32,high: U32) -> D.Number:
  match found:
    case True{}: y
    case False{}: search(D.sub(y,D.unsigned(step)),bottom,step,fallback,model,programs,program,point,low,high)

@unsafe
def surface_output(+config: Config,+value: Q.Outputs,model: Q.Model,programs: Q.Table<Q.Program>,point: Q.Point,low: U32,high: U32) -> D.Number:
  match config value:
    case Config{_,_,_,_,True{},_,_} Q.Outputs{a,_,_,_,_,_}: D.floor(a)
    case Config{_,program,bottom,+step,False{},+minimum,height} Q.Outputs{_,top,_,_,_,_}:
      # Search only physically present world layers. A graph's upper bound may
      # exceed the world ceiling; no off-world blocks need to be discovered.
      search(D.multiply(D.floor(D.divide(D.minimum(top,D.sub(D.add(D.integer(minimum),D.unsigned(height)),D.scalar(1.0))),D.unsigned(step))),D.unsigned(step)),
        D.maximum(D.integer(bottom),D.integer(minimum)),step,bottom,model,programs,4,point,low,high)

@unsafe
def surface(+config: Config,+model: Q.Model,+programs: Q.Table<Q.Program>,+point: Q.Point,+low: U32,+high: U32) -> D.Number:
  match config:
    case Config{_,+program,_,_,_,_,_}: surface_output(config,Q.run_index(model,programs,4,at(point,D.scalar(0.0)),low,high),model,programs,point,low,high)

@unsafe
def combined(value: Q.Outputs,config: Config,+model: Q.Model,+programs: Q.Table<Q.Program>,+program: U32,+point: Q.Point,+low: U32,+high: U32) -> Q.Outputs:
  match value:
    case Q.Outputs{flood,erosion,_,_,_,_}: Q.Outputs{flood,erosion,
      first(Q.run_index(model,programs,U32.inc(program),point,low,high)),
      first(Q.run_index(model,programs,U32.add(program,2),point,low,high)),
      first(Q.run_index(model,programs,U32.add(program,3),point,low,high)),
      surface(config,model,programs,point,low,high)}

@unsafe
def fields(+config: Config,+model: Q.Model,+programs: Q.Table<Q.Program>,+point: Q.Point,+low: U32,+high: U32) -> Q.Outputs:
  match config:
    case Config{False{},_,_,_,_,_,_}: Q.Outputs{D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0)}
    case Config{True{},+program,_,_,_,_,_}: combined(Q.run_index(model,programs,0,point,low,high),config,model,programs,0,point,low,high)


# Resident aquifer geometry is immutable. Compensated positions permit halo
# centers just outside signed-i32 world coordinates without wrapping.
type Layout is Data:
  Layout{origin: Q.Point,nx: U32,ny: U32,nz: U32,dx: U32,dy: U32,dz: U32,low: U32,high: U32}
type Status is Data:
  Status{level: D.Number,material: U32}
type Center is Data:
  Center{point: Q.Point,status: Status}
type Volume is Data:
  Volume{layout: Layout,values: Q.Table<D.Number>}
type Params is Data:
  Params{sea: U32,water: U32,lava: U32,level: U32,water_barriers: Bool}
type Cache is Data:
  Cache{layout: Layout,centers: Q.Table<Center>,barriers: Volume,params: Params}

def cached(prepared: Prepared,cache: Cache) -> Prepared:
  match prepared:
    case Prepared{config,programs,_}: Prepared{config,programs,Some{cache}}


def clear(prepared: Prepared) -> Prepared:
  match prepared:
    case Prepared{config,programs,_}: Prepared{config,programs,None{}}
