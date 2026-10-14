import Base
import ./structure_blocks.bend as Bk
import ./structure_template.bend as T
import ./structure_geometry.bend as G
import ./structure_plan.bend as Plan
import ./structure_registry.bend as M
import ./structure_values.bend as V
import ./structure_processors.bend as Processors
import ./registry.bend as R
import ./registry_values.bend as RV
import ./registry_density.bend as D
import ./placement_world.bend as E
import ./placement_data.bend as P
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./density.bend as Q

type Biome is Data:
  Biome{filler: U32,flags: U32}
type Model is Data:
  Model{flags: Q.Table<U32>,biomes: Q.Table<Biome>,water: U32,lava: U32,jigsaw: U32}

# The source grid retains GPU-selected surface materials. Extend its subsurface
# voxel (or the exported biome filler for banded badlands), never the floor block.
@unsafe
def fill(+model: Model,column: MD.Column,minimum: U32) -> U32:
  match model column:
    case Model{_,biomes,_,_,_} MD.Column{height,biome,_,_,_,_,_,runs,_,_}: soil(Q.get(Biome,biomes,biome,Biome{0,0}),MC.material_at(runs,U32.sub(U32.sub(height,minimum),2)))

@unsafe
def soil(biome: Biome,+selected: U32) -> U32:
  match biome:
    case Biome{filler,flags}: soil_selected(Bool.or(U32.is_ne(U32.and(flags,8),0),Bool.or(U32.is_zero(selected),U32.is_eq(selected,4294967295))),filler,selected)

@unsafe
def soil_selected(fallback: Bool,filler: U32,selected: U32) -> U32:
  match fallback:
    case True{}: filler
    case False{}: selected

@unsafe
def replaceable(+model: Model,+id: U32) -> Bool:
  match model:
    case Model{flags,_,water,lava,_}: Bool.or(U32.is_zero(id),Bool.or(U32.is_eq(id,water),Bool.or(U32.is_eq(id,lava),U32.is_ne(U32.and(Q.get(U32,flags,id,0),28),0))))

@unsafe
def apply(enabled: Bool,+template: T.Template,+ctx: Bk.Context,+model: Model,world: E.World) -> Bk.Placement:
  match enabled template ctx:
    case True{} T.Template{blocks,_,True{}} Bk.Context{env,_,_,origin,_,bounds,Some{terrain},False{},_,_,_}: rows(blocks,origin,bounds,terrain,model,env,Bk.Placement{world,[],[],True{}})
    case _ _ _: Bk.Placement{world,[],[],True{}}

@unsafe
def rows(xs: List<&2,T.Block>,+origin: P.Position,+bounds: G.Box,+terrain: Plan.Heights,+model: Model,+env: Processors.Environment,out: Bk.Placement) -> Bk.Placement:
  match xs:
    case Nil{}: out
    case block <> rest: rows(rest,origin,bounds,terrain,model,env,block_floor(block,origin,bounds,terrain,model,env,out))

@unsafe
def block_floor(block: T.Block,origin: P.Position,+bounds: G.Box,+terrain: Plan.Heights,+model: Model,+env: Processors.Environment,out: Bk.Placement) -> Bk.Placement:
  match block model:
    case T.Block{P.Position{x,0,z},_,+original,_,False{},_} Model{_,_,_,_,jigsaw}: eligible(Bool.and(Bool.not(replaceable(model,original)),U32.is_ne(Processors.name(env,original),jigsaw)),G.add(origin,P.Position{x,0,z}),bounds,terrain,model,out)
    case _ _: out

@unsafe
def eligible(ok: Bool,+pos: P.Position,+bounds: G.Box,+terrain: Plan.Heights,+model: Model,out: Bk.Placement) -> Bk.Placement:
  match ok pos bounds:
    case False{} _ _: out
    case True{} P.Position{+x,y,+z} G.Box{P.Position{_,min,_},P.Position{_,max,_}}: horizontal(G.inside(bounds,P.Position{x,min,z}),pos,bounds,terrain,model,out,G.max(G.max(U32.sub(y,12),U32.sub(Plan.height(terrain,x,z),1)),min),G.min(y,U32.inc(max)))

@unsafe
def horizontal(inside: Bool,+pos: P.Position,bounds: G.Box,terrain: Plan.Heights,+model: Model,+out: Bk.Placement,bottom: U32,top: U32) -> Bk.Placement:
  match inside pos out:
    case False{} _ _: out
    case True{} P.Position{x,_,z} Bk.Placement{E.World{+source,_,_,_},_,_,_}: column(E.column(source,x,z),source,pos,model,out,bottom,top)

@unsafe
def column(found: Maybe<&2,MD.Column>,source: MD.Grid,+pos: P.Position,+model: Model,+out: Bk.Placement,bottom: U32,top: U32) -> Bk.Placement:
  match found source:
    case None{} _: invalid(out)
    case Some{column} MD.Grid{_,minimum,_,_,_}: filled(fill(model,column,minimum),pos,model,out,bottom,top)

@unsafe
def invalid(out: Bk.Placement) -> Bk.Placement:
  match out:
    case Bk.Placement{world,tags,counts,_}: Bk.Placement{world,tags,counts,False{}}

@unsafe
def filled(+material: U32,+pos: P.Position,+model: Model,out: Bk.Placement,+bottom: U32,+top: U32) -> Bk.Placement:
  match model:
    case Model{flags,_,_,_,_}: filled_valid(Bool.and(U32.is_gt(material,0),U32.is_lt(material,Q.table_size(U32,flags))),material,pos,model,out,bottom,top)

@unsafe
def filled_valid(valid: Bool,material: U32,+pos: P.Position,model: Model,out: Bk.Placement,bottom: U32,top: U32) -> Bk.Placement:
  match valid pos:
    case False{} _: invalid(out)
    case True{} P.Position{x,_,z}: down(material,x,z,model,out,bottom,top)

@unsafe
def down(+material: U32,+x: U32,+z: U32,+model: Model,+out: Bk.Placement,+bottom: U32,+top: U32) -> Bk.Placement:
  descending(G.lt(bottom,top),material,P.Position{x,U32.sub(top,1),z},model,out,bottom)

@unsafe
def descending(more: Bool,+material: U32,+pos: P.Position,+model: Model,+out: Bk.Placement,+bottom: U32) -> Bk.Placement:
  match more out:
    case False{} _: out
    case True{} Bk.Placement{world,_,_,_}: existing(E.material(world,pos),material,pos,model,out,bottom)

@unsafe
def existing(found: Maybe<&2,U32>,material: U32,+pos: P.Position,+model: Model,out: Bk.Placement,bottom: U32) -> Bk.Placement:
  match found:
    case None{}: invalid(out)
    case Some{id}: accepted(replaceable(model,id),material,pos,model,out,bottom)

@unsafe
def accepted(yes: Bool,+material: U32,+pos: P.Position,+model: Model,+out: Bk.Placement,bottom: U32) -> Bk.Placement:
  match yes pos out:
    case False{} _ _: out
    case True{} P.Position{x,y,z} Bk.Placement{world,tags,counts,valid}: down(material,x,z,model,Bk.Placement{E.put(world,pos,material),Bk.TagWrite{pos,M.empty()} <> tags,counts,valid},bottom,y)

@unsafe
def biomes(-A: Type,found: Maybe<&2,R.Value>,doc: R.Document,+keys: D.Keys,next: R.Document -> Q.Table<Biome> -> A) -> A:
  match found:
    case Some{R.Value{6,n,_,at}}: biome_rows(A,n,U32.add(at,3),doc,keys,[],next)
    case _: next(doc,Q.Empty{})

@unsafe
def biome_rows(-A: Type,n: U32,+at: U32,doc: R.Document,+keys: D.Keys,acc: List<&2,Biome>,next: R.Document -> Q.Table<Biome> -> A) -> A:
  match n:
    case 0: next(doc,Q.prepared_table(Biome,List.reverse(&2,Biome,acc)))
    case _: R.value(A,doc,at,doc => +value => R.get(A,doc,U32.inc(at),doc => span =>
      D.named(A,doc,value,keys,0n,doc => filler => D.named(A,doc,value,keys,1n,doc => flags =>
        biome_rows(A,U32.sub(n,1),U32.add(at,span),doc,keys,Biome{uint(filler),uint(flags)} <> acc,next)))))

@unsafe
def flag_nodes(+xs: M.Node,+i: U32,n: U32) -> List<&2,U32>:
  match n:
    case 0: []
    case _: M.int(M.item(xs,i),0) <> flag_nodes(xs,U32.inc(i),U32.sub(n,1))

@unsafe
def projected(-A: Type,found: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> M.Node -> A) -> A:
  match found:
    case None{}: next(doc,M.empty())
    case Some{value}: V.project(A,doc,value,next)

@unsafe
def prepare(-A: Type,doc: R.Document,next: R.Document -> Model -> A) -> A:
  D.resolved(A,["filler","flags","minecraft:jigsaw"],doc,[],doc => +keys => RV.root(A,doc,doc => +root =>
    RV.field(A,doc,root,"biomes",doc => found => biomes(A,found,doc,keys,doc => biomes =>
      RV.field(A,doc,root,"material_flags",doc => found => projected(A,found,doc,doc => +flags =>
        RV.field(A,doc,root,"water",doc => water => RV.field(A,doc,root,"lava",doc => lava =>
          next(doc,Model{Q.prepared_table(U32,flag_nodes(flags,0,M.count(flags))),biomes,uint(water),uint(lava),D.key(keys,2n)})))))))))

@unsafe
def uint(found: Maybe<&2,R.Value>) -> U32:
  match found:
    case Some{R.Value{3,_,value,_}}: value
    case _: 4294967295
