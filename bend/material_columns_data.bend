import Base
import ./density.bend as Q
import ./terrain_surface.bend as S
import ./chunk_catalog.bend as C
import ./geology.bend as G
import ./cave_lattice.bend as CV

# Offsets are relative to the world's minimum Y. Runs are bottom-up, half-open,
# exhaustive and coalesced. Their palette IDs remain the exported registry IDs.
type Config is Data:
  Config{stone: U32,water: U32,materials: U32,depth_noise: U32,secondary_noise: U32,band_noise: U32}
type Run is Data:
  Run{start: U32,end: U32,material: U32}
type Column is Data:
  Column{height: U32,biome: U32,depth: F32,slope: F32,band: F32,secondary: F32,preliminary: F32,runs: List<&2,Run>,count: U32,valid: Bool}
type Grid is Data:
  Grid{tile: S.Tile,minimum: U32,height: U32,columns: Q.Table<Column>,biomes: Maybe<&2,CV.Grid>}
type State is Data:
  State{config: Config,grid: Maybe<&2,Grid>,catalog: Maybe<&2,C.Catalog>,geology: Maybe<&2,G.Profile>,caves: Maybe<&2,CV.Grid>}

def clear(state: Maybe<&2,State>) -> Maybe<&2,State>:
  match state:
    case None{}: None{}
    case Some{State{config,_,catalog,geology,_}}: Some{State{config,None{},catalog,geology,None{}}}

def catalog(state: Maybe<&2,State>) -> Maybe<&2,C.Catalog>:
  match state:
    case None{}: None{}
    case Some{State{_,_,catalog,_,_}}: catalog


def geology(state: Maybe<&2,State>) -> Maybe<&2,G.Profile>:
  match state:
    case None{}: None{}
    case Some{State{_,_,_,geology,_}}: geology

def caves(state: Maybe<&2,State>) -> Maybe<&2,CV.Grid>:
  match state:
    case None{}: None{}
    case Some{State{_,_,_,_,caves}}: caves
