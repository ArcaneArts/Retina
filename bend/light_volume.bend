import Base
import ./density.bend as Q
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./light_rules.bend as Rules
import ./compression.bend as Z

# Immutable 8x8x8 bricks. Four light bytes share a word; uniform subtrees collapse.
# Materials and paired face coverage remain resident through all fourteen passes.
type Optics is Data:
  Optics{traits: U32,down: U32,up: U32,north: U32,south: U32,west: U32,east: U32}
type Model is Data:
  Model{sky: Bool,stride: U32,states: Q.Table<Optics>,blocked: Q.Table<U32>}
type Column is Data:
  Column{runs: List<&2,MD.Run>,ceiling: U32}
type Source is Data:
  Source{width: U32,height: U32,depth: U32,columns: Q.Table<Column>}
type Cells is Data:
  UniformCells{a: U32,b: U32,seed: U32,flags: U32}
  CellPair{left: Cells,right: Cells,flags: U32}
type Words is Data:
  Uniform{word: U32}
  Pair{left: Words,right: Words}
type Brick is Data:
  Brick{cells: Cells,light: Words,channels: U32}
type Grid is Data:
  Grid{x: U32,y: U32,z: U32,bricks: Q.Table<Brick>}

def optics(model: Model,id: U32) -> Optics:
  match model:
    case Model{_,_,states,_}: Q.get(Optics,states,id,Optics{15,0,0,0,0,0,0})

def traits(state: Optics) -> U32:
  match state:
    case Optics{t,_,_,_,_,_,_}: t

@unsafe
def face(state: Optics,d: U32) -> U32:
  match state d:
    case Optics{_,a,b,c,e,f,g} _: face_pick(d,a,b,c,e,f,g)

def face_pick(d: U32,a: U32,b: U32,c: U32,e: U32,f: U32,g: U32) -> U32:
  match d:
    case 0: a
    case 1: b
    case 2: c
    case 3: e
    case 4: f
    case _: g

# Read a scalar without returning an owned Optics record from the shared table.
# Field zero is traits; fields one through six are directional face indices.
def optical_word(state: Optics,+field: U32) -> U32:
  match state field:
    case Optics{t,_,_,_,_,_,_} 0: t
    case Optics{_,a,b,c,d,e,f} _: face_pick(U32.sub(field,1),a,b,c,d,e,f)

@unsafe
def optical_at(states: Q.Table<Optics>,+i: U32,+field: U32) -> U32:
  match states:
    case Q.Empty{}: Z.word(U32.is_zero(field),15,0)
    case Q.Leaf{state}: optical_word(state,field)
    case Q.Branch{_,+a,b}: optical_side(U32.is_lt(i,Q.table_size(Optics,a)),a,b,i,field)

@unsafe
def optical_side(left: Bool,+a: Q.Table<Optics>,b: Q.Table<Optics>,i: U32,field: U32) -> U32:
  match left:
    case True{}: optical_at(a,i,field)
    case False{}: optical_at(b,U32.sub(i,Q.table_size(Optics,a)),field)

def optical(model: Model,id: U32,field: U32) -> U32:
  match model:
    case Model{_,_,states,_}: optical_at(states,id,field)

@unsafe
def occludes(+model: Model,a: U32,b: U32,+d: U32) -> Bool:
  blocked(model,optical(model,a,U32.inc(d)),optical(model,b,U32.inc(U32.xor(d,1))))

def blocked(model: Model,a: U32,+b: U32) -> Bool:
  match model:
    case Model{_,stride,_,coverage}: U32.is_ne(U32.and(Q.get(U32,coverage,
      U32.add(U32.mul(a,stride),U32.div(b,32)),0),U32.shln(1,U32.to_nat(U32.mod(b,32)))),0)

# Runs arrive reversed. The first attenuating voxel/covered edge stops direct sun.
@unsafe
def ceiling(runs: List<&2,MD.Run>,+above: U32,+model: Model) -> U32:
  match runs:
    case Nil{}: Z.word(occludes(model,above,0,0),16,0)
    case MD.Run{+a,+b,+m} <> rest:
      ceiling_edge(Bool.or(U32.is_ne(Rules.opacity(optical(model,m,0)),0),occludes(model,above,m,0)),a,b,m,rest,model)

@unsafe
def ceiling_edge(stop: Bool,+a: U32,+b: U32,+m: U32,rest: List<&2,MD.Run>,+model: Model) -> U32:
  match stop:
    case True{}: U32.add(b,16)
    case False{}: ceiling_inside(Bool.and(U32.is_gt(U32.sub(b,a),1),occludes(model,m,m,0)),b,m,rest,model)

@unsafe
def ceiling_inside(stop: Bool,b: U32,m: U32,rest: List<&2,MD.Run>,model: Model) -> U32:
  match stop:
    case True{}: U32.add(b,15)
    case False{}: ceiling(rest,m,model)

def column(+runs: List<&2,MD.Run>,model: Model) -> Column:
  Column{runs,ceiling(List.reverse(&2,MD.Run,runs),0,model)}

@unsafe
def material(column: Column,+y: U32,height: U32) -> U32:
  material_inside(Bool.and(U32.is_ge(y,16),U32.is_lt(U32.add(y,16),height)),column,y)

def material_inside(inside: Bool,column: Column,y: U32) -> U32:
  match inside column:
    case False{} _: 0
    case True{} Column{runs,_}: MC.material_at(runs,U32.sub(y,16))

def sunlight(model: Model,column: Column,y: U32) -> Bool:
  match model column:
    case Model{sky,_,_,_} Column{_,at}: Bool.and(sky,U32.is_ge(y,at))

def flags(+t: U32,+sun: Bool) -> U32:
  U32.or(Z.word(U32.is_lt(Rules.opacity(t),15),Z.word(sun,1,3),0),
    U32.or(Z.word(U32.is_gt(Rules.emission(t),1),4,0),Z.word(sun,8,0)))

@unsafe
def voxel(+column: Column,+y: U32,height: U32,+model: Model) -> Cells:
  voxel_value(material(column,y,height),sunlight(model,column,y),model)

@unsafe
def voxel_value(+m: U32,+sun: Bool,model: Model) -> Cells:
  voxel_traits(m,sun,optical(model,m,0))

def voxel_traits(m: U32,+sun: Bool,+t: U32) -> Cells:
  UniformCells{m,0,U32.or(Rules.emission(t),Z.word(sun,240,0)),flags(t,sun)}

def four(a: Cells,b: Cells,c: Cells,d: Cells) -> Cells:
  match a b c d:
    case UniformCells{a,_,s0,f0} UniformCells{b,_,s1,f1} UniformCells{c,_,s2,f2} UniformCells{d,_,s3,f3}:
      UniformCells{U32.or(a,U32.shln(b,16n)),U32.or(c,U32.shln(d,16n)),
        U32.or(U32.or(s0,U32.shln(s1,8n)),U32.or(U32.shln(s2,16n),U32.shln(s3,24n))),U32.or(U32.or(f0,f1),U32.or(f2,f3))}
    case _ _ _ _: UniformCells{0,0,0,0}

def cell_flags(c: Cells) -> U32:
  match c:
    case UniformCells{_,_,_,f}: f
    case CellPair{_,_,f}: f

@unsafe
def cell_join(+a: Cells,+b: Cells) -> Cells:
  match a b:
    case UniformCells{+x,+y,+s,+f} UniformCells{xx,yy,ss,ff}:
      cell_equal(Bool.and(Bool.and(U32.is_eq(x,xx),U32.is_eq(y,yy)),U32.is_eq(s,ss)),a,b,U32.or(f,ff))
    case _ _: CellPair{a,b,U32.or(cell_flags(a),cell_flags(b))}

def cell_equal(equal: Bool,a: Cells,b: Cells,flags: U32) -> Cells:
  match equal:
    case True{}: a
    case False{}: CellPair{a,b,flags}

@unsafe
def join(+a: Words,+b: Words) -> Words:
  match a b:
    case Uniform{+x} Uniform{y}: joined(U32.is_eq(x,y),a,b)
    case _ _: Pair{a,b}

def joined(equal: Bool,a: Words,b: Words) -> Words:
  match equal:
    case True{}: a
    case False{}: Pair{a,b}

@unsafe
def get(words: Words,+i: U32,+half: U32) -> U32:
  match words:
    case Uniform{w}: w
    case Pair{a,b}: get_side(U32.is_lt(i,half),a,b,i,half)

@unsafe
def get_side(left: Bool,a: Words,b: Words,i: U32,+half: U32) -> U32:
  match left:
    case True{}: get(a,i,U32.div(half,2))
    case False{}: get(b,U32.sub(i,half),U32.div(half,2))

# Read material scalars directly. Returning a Cells subtree makes every voxel
# lookup own/share that subtree, causing reference-count traffic on the GPU.
@unsafe
def cell_material_at(cells: Cells,+i: U32,+half: U32,+lane: U32) -> U32:
  match cells:
    case UniformCells{a,b,_,_}: U32.and(U32.shrn(Z.word(U32.is_lt(lane,2),a,b),U32.to_nat(U32.mul(U32.mod(lane,2),16))),65535)
    case CellPair{a,b,_}: cell_material_side(U32.is_lt(i,half),a,b,i,half,lane)

@unsafe
def cell_material_side(left: Bool,a: Cells,b: Cells,i: U32,+half: U32,lane: U32) -> U32:
  match left:
    case True{}: cell_material_at(a,i,U32.div(half,2),lane)
    case False{}: cell_material_at(b,U32.sub(i,half),U32.div(half,2),lane)

@unsafe
def seeds(c: Cells) -> Words:
  match c:
    case UniformCells{_,_,s,_}: Uniform{s}
    case CellPair{a,b,_}: join(seeds(a),seeds(b))

def empty() -> Brick:
  Brick{UniformCells{0,0,0,0},Uniform{0},0}

def initial(+c: Cells) -> Brick:
  Brick{c,seeds(c),0}

@unsafe
def block_cells(+n: U32,+base: U32,+x: U32,+y: U32,+z: U32,+source: Source,+model: Model) -> Cells:
  match n:
    case 1: cell_at(U32.add(x,U32.mod(U32.mul(base,4),8)),U32.add(y,U32.div(base,16)),U32.add(z,U32.mod(U32.div(base,2),8)),source,model)
    case _: cell_join(block_cells(U32.div(n,2),base,x,y,z,source,model),block_cells(U32.div(n,2),U32.add(base,U32.div(n,2)),x,y,z,source,model))

def cell_at(+x: U32,+y: U32,+z: U32,source: Source,+model: Model) -> Cells:
  match source:
    case Source{+width,+height,_,+columns}:
      four(voxel(Q.get(Column,columns,U32.add(x,U32.mul(z,width)),Column{[],0}),y,height,model),
        voxel(Q.get(Column,columns,U32.add(U32.inc(x),U32.mul(z,width)),Column{[],0}),y,height,model),
        voxel(Q.get(Column,columns,U32.add(U32.add(x,2),U32.mul(z,width)),Column{[],0}),y,height,model),
        voxel(Q.get(Column,columns,U32.add(U32.add(x,3),U32.mul(z,width)),Column{[],0}),y,height,model))

@unsafe
def initialize(+n: U32,+base: U32,+sx: U32,+sz: U32,+source: Source,+model: Model) -> Q.Table<Brick>:
  match n:
    case 1: Q.Leaf{initial(block_cells(128,0,U32.mul(U32.mod(base,sx),8),U32.mul(U32.div(base,U32.mul(sx,sz)),8),U32.mul(U32.mod(U32.div(base,sx),sz),8),source,model))}
    case _:
      a b = initialize(U32.div(n,2),base,sx,sz,source,model) initialize(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),sx,sz,source,model)
      Q.Branch{n,a,b}

def prepare(+source: Source,+model: Model) -> Grid:
  match source:
    case Source{+width,+height,+depth,_}: Grid{U32.div(width,8),U32.div(height,8),U32.div(depth,8),
      initialize!(U32.div(U32.mul(U32.mul(width,height),depth),512),0,U32.div(width,8),U32.div(depth,8),source,model)}

@unsafe
def brick(grid: Grid,+x: U32,+y: U32,+z: U32) -> Brick:
  match grid:
    case Grid{+sx,sy,+sz,values}: brick_valid(Bool.and(U32.is_lt(x,sx),Bool.and(U32.is_lt(y,sy),U32.is_lt(z,sz))),values,U32.add(x,U32.mul(sx,U32.add(z,U32.mul(sz,y)))))

def brick_valid(valid: Bool,values: Q.Table<Brick>,i: U32) -> Brick:
  match valid:
    case False{}: empty()
    case True{}: Q.get(Brick,values,i,empty())

def distance(+a: U32,+b: U32) -> U32:
  U32.sub(U32.max(a,b),U32.min(a,b))

def gap(a: U32,b: U32) -> U32:
  U32.sub(U32.max(7,U32.mul(distance(a,b),8)),7)

def brick_flags(b: Brick) -> U32:
  match b:
    case Brick{c,_,_}: cell_flags(c)

# Classification needs only four scalar flags, not ownership of a whole brick.
@unsafe
def table_flags(values: Q.Table<Brick>,+i: U32) -> U32:
  match values:
    case Q.Empty{}: 0
    case Q.Leaf{b}: brick_flags(b)
    case Q.Branch{_,+a,b}: flags_side(U32.is_lt(i,Q.table_size(Brick,a)),a,b,i)

@unsafe
def flags_side(left: Bool,+a: Q.Table<Brick>,b: Q.Table<Brick>,i: U32) -> U32:
  match left:
    case True{}: table_flags(a,i)
    case False{}: table_flags(b,U32.sub(i,Q.table_size(Brick,a)))

def valid_flags(valid: Bool,values: Q.Table<Brick>,i: U32) -> U32:
  match valid:
    case False{}: 0
    case True{}: table_flags(values,i)

def nearby_flags(grid: Grid,+x: U32,+y: U32,+z: U32) -> U32:
  match grid:
    case Grid{+sx,sy,+sz,values}: valid_flags(Bool.and(U32.is_lt(x,sx),Bool.and(U32.is_lt(y,sy),U32.is_lt(z,sz))),
      values,U32.add(x,U32.mul(sx,U32.add(z,U32.mul(sz,y)))))

@unsafe
def nearby(+n: U32,+x: U32,+y: U32,+z: U32,+grid: Grid,flags: U32) -> U32:
  match n:
    case 0: flags
    case _: nearby_at(U32.sub(n,1),U32.add(U32.sub(x,2),U32.mod(U32.sub(n,1),5)),
      U32.add(U32.sub(y,2),U32.div(U32.sub(n,1),25)),U32.add(U32.sub(z,2),U32.mod(U32.div(U32.sub(n,1),5),5)),x,y,z,grid,flags)

@unsafe
def nearby_at(n: U32,+nx: U32,+ny: U32,+nz: U32,+x: U32,+y: U32,+z: U32,+grid: Grid,flags: U32) -> U32:
  nearby_inside(U32.is_le(U32.add(gap(x,nx),U32.add(gap(y,ny),gap(z,nz))),14),n,nx,ny,nz,x,y,z,grid,flags)

@unsafe
def nearby_inside(inside: Bool,n: U32,nx: U32,ny: U32,nz: U32,x: U32,y: U32,z: U32,+grid: Grid,flags: U32) -> U32:
  match inside:
    case False{}: nearby(n,x,y,z,grid,flags)
    case True{}: nearby(n,x,y,z,grid,U32.or(flags,nearby_flags(grid,nx,ny,nz)))

def channels(+own: U32,+near: U32) -> U32:
  Z.word(U32.is_zero(U32.and(own,1)),0,U32.or(Z.word(U32.is_ne(U32.and(near,4),0),1,0),
    Z.word(Bool.and(U32.is_ne(U32.and(own,2),0),U32.is_ne(U32.and(near,8),0)),2,0)))

def classified(b: Brick,near: U32) -> Brick:
  match b:
    case Brick{+c,light,_}: Brick{c,light,channels(cell_flags(c),near)}

@unsafe
def classify(+n: U32,+base: U32,+sx: U32,+sz: U32,+grid: Grid) -> Q.Table<Brick>:
  match n:
    case 1: classify_at(U32.mod(base,sx),U32.div(base,U32.mul(sx,sz)),U32.mod(U32.div(base,sx),sz),grid)
    case _:
      a b = classify(U32.div(n,2),base,sx,sz,grid) classify(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),sx,sz,grid)
      Q.Branch{n,a,b}

def classify_passable(passable: Bool,b: Brick,x: U32,y: U32,z: U32,grid: Grid) -> Q.Table<Brick>:
  match passable:
    case False{}: Q.Leaf{classified(b,0)}
    case True{}: Q.Leaf{classified(b,nearby(125,x,y,z,grid,0))}

def classify_brick(+b: Brick,x: U32,y: U32,z: U32,grid: Grid) -> Q.Table<Brick>:
  classify_passable(U32.is_ne(U32.and(brick_flags(b),1),0),b,x,y,z,grid)

def classify_at(+x: U32,+y: U32,+z: U32,+grid: Grid) -> Q.Table<Brick>:
  classify_brick(brick(grid,x,y,z),x,y,z,grid)

def active(+grid: Grid) -> Grid:
  match grid:
    case Grid{+x,+y,+z,_}: Grid{x,y,z,classify!(U32.mul(x,U32.mul(y,z)),0,x,z,grid)}

# Cache the six adjacent bricks once per output brick, not per voxel lookup.
type Neighbors is Data:
  Neighbors{down: Brick,up: Brick,north: Brick,south: Brick,west: Brick,east: Brick}

def neighbors(+grid: Grid,+x: U32,+y: U32,+z: U32) -> Neighbors:
  Neighbors{brick(grid,x,U32.sub(y,1),z),brick(grid,x,U32.inc(y),z),brick(grid,x,y,U32.sub(z,1)),
    brick(grid,x,y,U32.inc(z)),brick(grid,U32.sub(x,1),y,z),brick(grid,U32.inc(x),y,z)}

def value(b: Brick,+i: U32) -> U32:
  match b:
    case Brick{_,words,_}: U32.and(U32.shrn(get(words,U32.div(i,4),64),U32.to_nat(U32.mul(U32.mod(i,4),8))),255)

def block(b: Brick,+i: U32) -> U32:
  match b:
    case Brick{cells,_,_}: cell_material_at(cells,U32.div(i,4),64,U32.mod(i,4))

def adjacent(d: U32,+x: U32,+y: U32,+z: U32) -> U32:
  match d:
    case 0: U32.add(x,U32.mul(8,U32.add(z,U32.mul(8,U32.mod(U32.sub(y,1),8)))))
    case 1: U32.add(x,U32.mul(8,U32.add(z,U32.mul(8,U32.mod(U32.inc(y),8)))))
    case 2: U32.add(x,U32.mul(8,U32.add(U32.mod(U32.sub(z,1),8),U32.mul(8,y))))
    case 3: U32.add(x,U32.mul(8,U32.add(U32.mod(U32.inc(z),8),U32.mul(8,y))))
    case 4: U32.add(U32.mod(U32.sub(x,1),8),U32.mul(8,U32.add(z,U32.mul(8,y))))
    case _: U32.add(U32.mod(U32.inc(x),8),U32.mul(8,U32.add(z,U32.mul(8,y))))

def outside(d: U32,x: U32,y: U32,z: U32) -> Bool:
  match d:
    case 0: U32.is_zero(y)
    case 1: U32.is_eq(y,7)
    case 2: U32.is_zero(z)
    case 3: U32.is_eq(z,7)
    case 4: U32.is_zero(x)
    case _: U32.is_eq(x,7)

# Select a scalar contribution, never return a neighboring Brick per edge.
# This keeps the six cached bricks borrowed throughout the hot voxel loop.
@unsafe
def edge_from(out: Bool,b: Brick,ns: Neighbors,+d: U32,at: U32,m: U32,model: Model,channels: U32,current: U32,loss: U32) -> U32:
  match out:
    case False{}: edge(b,d,at,m,model,channels,current,loss)
    case True{}: edge_neighbor(ns,d,at,m,model,channels,current,loss)

@unsafe
def edge_neighbor(ns: Neighbors,+d: U32,at: U32,m: U32,model: Model,channels: U32,current: U32,loss: U32) -> U32:
  match ns:
    case Neighbors{a,b,c,e,f,g}: edge_pick(d,at,m,model,channels,current,loss,a,b,c,e,f,g)

@unsafe
def edge_pick(+d: U32,at: U32,m: U32,model: Model,channels: U32,current: U32,loss: U32,a: Brick,b: Brick,c: Brick,e: Brick,f: Brick,g: Brick) -> U32:
  match d:
    case 0: edge(a,d,at,m,model,channels,current,loss)
    case 1: edge(b,d,at,m,model,channels,current,loss)
    case 2: edge(c,d,at,m,model,channels,current,loss)
    case 3: edge(e,d,at,m,model,channels,current,loss)
    case 4: edge(f,d,at,m,model,channels,current,loss)
    case _: edge(g,d,at,m,model,channels,current,loss)

@unsafe
def edges(+n: U32,+i: U32,+m: U32,+b: Brick,+ns: Neighbors,+model: Model,+channels: U32,current: U32,+loss: U32) -> U32:
  match n:
    case 0: current
    case _: edges(U32.sub(n,1),i,m,b,ns,model,channels,
      edge_from(outside(U32.sub(n,1),U32.mod(i,8),U32.div(i,64),U32.mod(U32.div(i,8),8)),b,ns,U32.sub(n,1),
        adjacent(U32.sub(n,1),U32.mod(i,8),U32.div(i,64),U32.mod(U32.div(i,8),8)),m,model,channels,current,loss),loss)

@unsafe
def edge(+other: Brick,d: U32,+at: U32,m: U32,model: Model,channels: U32,current: U32,loss: U32) -> U32:
  edge_value(value(other,at),other,d,at,m,model,channels,current,loss)

@unsafe
def edge_value(+incoming: U32,other: Brick,d: U32,at: U32,m: U32,model: Model,+channels: U32,+current: U32,+loss: U32) -> U32:
  edge_needed(Bool.or(Bool.and(U32.is_ne(U32.and(channels,1),0),U32.is_gt(U32.and(incoming,15),U32.add(U32.and(current,15),loss))),
    Bool.and(U32.is_ne(U32.and(channels,2),0),U32.is_gt(U32.shrn(incoming,4n),U32.add(U32.shrn(current,4n),loss)))),incoming,other,d,at,m,model,channels,current,loss)

@unsafe
def edge_needed(needed: Bool,incoming: U32,other: Brick,d: U32,at: U32,m: U32,model: Model,channels: U32,current: U32,loss: U32) -> U32:
  match needed:
    case False{}: current
    case True{}: Rules.propagate_allowed(Bool.not(occludes(model,block(other,at),m,U32.xor(d,1))),current,incoming,loss,channels)

@unsafe
def spread_voxel(+i: U32,+b: Brick,ns: Neighbors,+model: Model,channels: U32) -> U32:
  +m = block(b,i)
  +opacity = Rules.opacity(optical(model,m,0))
  spread_passable(U32.is_lt(opacity,15),i,m,b,ns,model,channels,U32.max(1,opacity))

def spread_passable(passable: Bool,+i: U32,m: U32,+b: Brick,ns: Neighbors,model: Model,channels: U32,loss: U32) -> U32:
  match passable:
    case False{}: value(b,i)
    case True{}: edges(6,i,m,b,ns,model,channels,value(b,i),loss)

@unsafe
def spread_words(+n: U32,+base: U32,+b: Brick,+ns: Neighbors,+model: Model,+channels: U32,+cutoff: U32) -> Words:
  match n:
    case 1: Uniform{U32.or(U32.or(spread_voxel(U32.mul(base,4),b,ns,model,channels),U32.shln(spread_voxel(U32.add(U32.mul(base,4),1),b,ns,model,channels),8n)),
      U32.or(U32.shln(spread_voxel(U32.add(U32.mul(base,4),2),b,ns,model,channels),16n),U32.shln(spread_voxel(U32.add(U32.mul(base,4),3),b,ns,model,channels),24n)))}
    case _: spread_fork(U32.is_gt(n,cutoff),n,base,b,ns,model,channels,cutoff)

# Bound the fork frontier near the runtime's 16K GPU lanes. Tiny fixtures need
# word-level tasks; full tiles already supply thousands of independent bricks.
@unsafe
def spread_fork(parallel: Bool,+n: U32,+base: U32,+b: Brick,+ns: Neighbors,+model: Model,+channels: U32,+cutoff: U32) -> Words:
  match parallel:
    case False{}: join(spread_words(U32.div(n,2),base,b,ns,model,channels,cutoff),spread_words(U32.div(n,2),U32.add(base,U32.div(n,2)),b,ns,model,channels,cutoff))
    case True{}:
      a c = spread_words(U32.div(n,2),base,b,ns,model,channels,cutoff) spread_words(U32.div(n,2),U32.add(base,U32.div(n,2)),b,ns,model,channels,cutoff)
      join(a,c)

def spread_brick(+b: Brick,ns: Neighbors,model: Model,cutoff: U32) -> Brick:
  match b:
    case Brick{c,light,0}: Brick{c,light,0}
    case Brick{c,_,+channels}: Brick{c,spread_words(128,0,b,ns,model,channels,cutoff),channels}

@unsafe
def spread(+n: U32,+base: U32,+sx: U32,+sz: U32,+grid: Grid,+model: Model,+cutoff: U32) -> Q.Table<Brick>:
  match n:
    case 1: spread_at(U32.mod(base,sx),U32.div(base,U32.mul(sx,sz)),U32.mod(U32.div(base,sx),sz),grid,model,cutoff)
    case _:
      a b = spread(U32.div(n,2),base,sx,sz,grid,model,cutoff) spread(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),sx,sz,grid,model,cutoff)
      Q.Branch{n,a,b}

def spread_ready(+b: Brick,x: U32,y: U32,z: U32,grid: Grid,model: Model,cutoff: U32) -> Q.Table<Brick>:
  match b:
    case Brick{_,_,0}: Q.Leaf{b}
    case _: Q.Leaf{spread_brick(b,neighbors(grid,x,y,z),model,cutoff)}

def spread_at(+x: U32,+y: U32,+z: U32,+grid: Grid,model: Model,cutoff: U32) -> Q.Table<Brick>:
  spread_ready(brick(grid,x,y,z),x,y,z,grid,model,cutoff)

def step(+grid: Grid,+model: Model) -> Grid:
  match grid:
    case Grid{+x,+y,+z,_}: Grid{x,y,z,spread!(U32.mul(x,U32.mul(y,z)),0,x,z,grid,model,U32.max(1,U32.div(U32.add(U32.mul(x,U32.mul(y,z)),127),128)))}

@unsafe
def iterations(n: U32,grid: Grid,+model: Model) -> IO(Grid):
  match n:
    case 0: IO.pure(Grid,grid)
    case _:
      do IO<Grid>:
        _ : Nat <- IO.now()
        changed : Grid = step(grid,model)
        iterations(U32.sub(n,1),changed,model)

def run(source: Source,+model: Model) -> IO(Grid):
  do IO<Grid>:
    _ : Nat <- IO.now()
    seeded : Grid = prepare(source,model)
    _ : Nat <- IO.now()
    scheduled : Grid = active(seeded)
    iterations(14,scheduled,model)

def at(grid: Grid,+x: U32,+y: U32,+z: U32) -> U32:
  value(brick(grid,U32.div(x,8),U32.div(y,8),U32.div(z,8)),U32.add(U32.mod(x,8),U32.mul(8,U32.add(U32.mod(z,8),U32.mul(8,U32.mod(y,8))))))
