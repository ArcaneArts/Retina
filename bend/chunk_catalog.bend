import Base
import ./density.bend as Q
import ./material.bend as M
import ./chunk.bend as C
import ./registry.bend as P

# Immutable, registry-derived names/properties and Minecraft height predicates.
# No Java/NBT fallback and no block-name heuristic for heightmap behavior.
type Catalog is Data:
  Catalog{materials: Q.Table<M.Material>,biomes: Q.Table<String>}

def empty_material() -> M.Material:
  M.Material{M.State{"minecraft:air",[]},0}

@unsafe
def material_array(+n: U32,+table: Q.Table<M.Material>,array: Array<M.Material>) -> Array<M.Material>:
  match n:
    case 0: array
    case _: material_array(U32.sub(n,1),table,Array.set(M.Material,array,U32.sub(n,1),Q.get(M.Material,table,U32.sub(n,1),empty_material())))

@unsafe
def biome_array(+n: U32,+table: Q.Table<String>,array: Array<String>) -> Array<String>:
  match n:
    case 0: array
    case _: biome_array(U32.sub(n,1),table,Array.set(String,array,U32.sub(n,1),Q.get(String,table,U32.sub(n,1),"")))

def profile(catalog: Catalog) -> C.Profile:
  match catalog:
    case Catalog{+materials,+biomes}:
      +mc = Q.table_size(M.Material,materials)
      +bc = Q.table_size(String,biomes)
      C.Profile{material_array(mc,materials,Array.new(M.Material,P.capacity(mc,1,0n),empty_material())),mc,
        biome_array(bc,biomes,Array.new(String,P.capacity(bc,1,0n),"")),bc}
