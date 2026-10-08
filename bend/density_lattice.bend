import Base
import ./density.bend as Q
import ./density_batch.bend as DB
import ./precise.bend as D
import ./reader.bend as R
import ./compression.bend as Z

# Global, reusable top-level density lattice. Samples and interpolation are Bend
# on the selected CPU/GPU backend. It is distinct from each registered op28 field:
# the original DAG (including those fields) is evaluated at these vertices.
type Layout is Data:
  Layout{origin: Q.Point,nx: U32,ny: U32,nz: U32,horizontal: U32,vertical: U32,program: U32,low: U32,high: U32}
type Grid is Data:
  Grid{layout: Layout,values: Q.Table<Q.Outputs>}
type Generated is Type:
  Generated{model: Q.Model,programs: Q.Table<Q.Program>,grid: Grid}
type Computed is Type:
  Computed{model: Q.Model,programs: Q.Table<Q.Program>,grid: Grid,rows: Array<Q.Outputs>}

def size(layout: Layout) -> U32:
  match layout:
    case Layout{_,nx,ny,nz,_,_,_,_,_}: U32.mul(U32.mul(nx,ny),nz)

def count(top: D.Number,origin: D.Number,step: U32) -> U32:
  U32.max(2,U32.inc(F32.to_u32(F32.ceil(D.value(D.divide(D.sub(top,origin),D.unsigned(step)))))))

def axis_valid(+a: D.Number,+b: D.Number) -> Bool:
  Bool.and(Bool.not(D.less(b,a)),Bool.not(D.less(D.scalar(4096.0),D.sub(b,a))))

@unsafe
def total_valid(ok: Bool,layout: Layout,xy: U32,nz: U32) -> Result<&1,&1,U32,Layout>:
  match ok:
    case False{}: Fail{717}
    case True{}: limited(U32.is_le(xy,U32.div(1048576,nz)),layout)

@unsafe
def limited(ok: Bool,layout: Layout) -> Result<&1,&1,U32,Layout>:
  match ok:
    case False{}: Fail{717}
    case True{}: Done{layout}

@unsafe
def sized(layout: Layout) -> Result<&1,&1,U32,Layout>:
  match layout:
    case Layout{_,+nx,+ny,+nz,_,_,_,_,_}:
      total_valid(U32.is_le(nx,U32.div(1048576,ny)),layout,U32.mul(nx,ny),nz)

@unsafe
def aligned(ok: Bool,minimum: Q.Point,maximum: Q.Point,+h: U32,+v: U32,program: U32,low: U32,high: U32) -> Result<&1,&1,U32,Layout>:
  match ok minimum maximum:
    case False{} _ _: Fail{717}
    case True{} Q.Point{+x,+y,+z} Q.Point{tx,ty,tz}:
      +ox = Q.lattice(x,h)
      +oy = Q.lattice(y,v)
      +oz = Q.lattice(z,h)
      sized(Layout{Q.Point{ox,oy,oz},count(tx,ox,h),count(ty,oy,v),count(tz,oz,h),h,v,program,low,high})

@unsafe
def layout(valid: Bool,+minimum: Q.Point,+maximum: Q.Point,+h: U32,+v: U32,+program: U32,low: U32,high: U32) -> Result<&1,&1,U32,Layout>:
  match minimum maximum:
    case Q.Point{x,y,z} Q.Point{tx,ty,tz}:
      aligned(Bool.and(valid,Bool.and(U32.is_lt(program,3),Bool.and(Bool.and(U32.is_gt(h,0),U32.is_le(h,4096)),
        Bool.and(Bool.and(U32.is_gt(v,0),U32.is_le(v,4096)),Bool.and(axis_valid(x,tx),Bool.and(axis_valid(y,ty),axis_valid(z,tz))))))),
        minimum,maximum,h,v,program,low,high)

@unsafe
def decode(bytes: Z.Bytes) -> Result<&1,&1,U32,Layout>:
  R.word(Result<&1,&1,U32,Layout>,R.start(bytes),cursor => program => R.word(Result<&1,&1,U32,Layout>,cursor,cursor => x =>
  R.word(Result<&1,&1,U32,Layout>,cursor,cursor => y => R.word(Result<&1,&1,U32,Layout>,cursor,cursor => z =>
  R.word(Result<&1,&1,U32,Layout>,cursor,cursor => tx => R.word(Result<&1,&1,U32,Layout>,cursor,cursor => ty =>
  R.word(Result<&1,&1,U32,Layout>,cursor,cursor => tz => R.word(Result<&1,&1,U32,Layout>,cursor,cursor => h =>
  R.word(Result<&1,&1,U32,Layout>,cursor,cursor => v => R.word(Result<&1,&1,U32,Layout>,cursor,cursor => low =>
  R.word(Result<&1,&1,U32,Layout>,cursor,cursor => high =>
    layout(R.end(cursor),Q.world(x,y,z),Q.world(tx,ty,tz),h,v,program,low,high))))))))))))

def vertex(model: Q.Model,programs: Q.Table<Q.Program>,layout: Layout,+i: U32) -> Q.Outputs:
  match layout:
    case Layout{origin,+nx,+ny,_,+h,v,program,low,high}:
      Q.run_index(model,programs,program,Q.shifted(origin,D.unsigned(U32.mul(U32.mod(i,nx),h)),
        D.unsigned(U32.mul(U32.mod(U32.div(i,nx),ny),v)),D.unsigned(U32.mul(U32.div(i,U32.mul(nx,ny)),h))),low,high)

# A bounded fork tree gives each task a short serial run, avoiding a GPU task
# per voxel on large grids. Small grids expose every vertex; large grids aim
# for roughly 16,384 tasks. Later stages reuse immutable random-access trees.
@unsafe
def serial(+n: U32,+base: U32,+model: Q.Model,+programs: Q.Table<Q.Program>,+layout: Layout) -> Q.Table<Q.Outputs>:
  match n:
    case 1: Q.Leaf{vertex(model,programs,layout,base)}
    case _: Q.Branch{n,serial(U32.div(n,2),base,model,programs,layout),
      serial(U32.sub(n,U32.div(n,2)),U32.add(base,U32.div(n,2)),model,programs,layout)}

@unsafe
def vertices(+limit: U32,+n: U32,+base: U32,+model: Q.Model,+programs: Q.Table<Q.Program>,+layout: Layout) -> Q.Table<Q.Outputs>:
  match n:
    case 1: Q.Leaf{vertex(model,programs,layout,base)}
    case _: grouped(U32.is_le(n,limit),limit,n,base,model,programs,layout)

@unsafe
def grouped(leaf: Bool,+limit: U32,+n: U32,+base: U32,+model: Q.Model,+programs: Q.Table<Q.Program>,+layout: Layout) -> Q.Table<Q.Outputs>:
  match leaf:
    case True{}: serial(n,base,model,programs,layout)
    case False{}:
      +half = U32.div(n,2)
      a b = vertices(limit,half,base,model,programs,layout)
        vertices(limit,U32.sub(n,half),U32.add(base,half),model,programs,layout)
      Q.Branch{n,a,b}

def generate(+model: Q.Model,+programs: Q.Table<Q.Program>,+layout: Layout) -> Generated:
  values : Q.Table<Q.Outputs> = vertices!(U32.max(1,U32.div(U32.add(size(layout),16383),16384)),size(layout),0,model,programs,layout)
  Generated{model,programs,Grid{layout,values}}

@unsafe
def value_at(values: Q.Table<Q.Outputs>,+i: U32) -> Q.Outputs:
  match values:
    case Q.Empty{}: Q.Outputs{D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0)}
    case Q.Leaf{Q.Outputs{a,b,c,d,e,f}}: Q.Outputs{a,b,c,d,e,f}
    case Q.Branch{_,+a,b}: value_branch(U32.is_lt(i,Q.table_size(Q.Outputs,a)),a,b,i)

@unsafe
def value_branch(left: Bool,+a: Q.Table<Q.Outputs>,b: Q.Table<Q.Outputs>,i: U32) -> Q.Outputs:
  match left:
    case True{}: value_at(a,i)
    case False{}: value_at(b,U32.sub(i,Q.table_size(Q.Outputs,a)))

def mix(a: Q.Outputs,b: Q.Outputs,+t: D.Number) -> Q.Outputs:
  match a b:
    case Q.Outputs{a,b,c,d,e,f} Q.Outputs{aa,bb,cc,dd,ee,ff}:
      Q.Outputs{D.mix(a,aa,t),D.mix(b,bb,t),D.mix(c,cc,t),D.mix(d,dd,t),D.mix(e,ee,t),D.mix(f,ff,t)}

def interpolate(+values: Q.Table<Q.Outputs>,+i: U32,+nx: U32,+xy: U32,+tx: D.Number,+ty: D.Number,tz: D.Number) -> Q.Outputs:
  mix(mix(mix(value_at(values,i),value_at(values,U32.inc(i)),tx),
      mix(value_at(values,U32.add(i,nx)),value_at(values,U32.inc(U32.add(i,nx))),tx),ty),
    mix(mix(value_at(values,U32.add(i,xy)),value_at(values,U32.inc(U32.add(i,xy))),tx),
      mix(value_at(values,U32.add(U32.add(i,xy),nx)),value_at(values,U32.inc(U32.add(U32.add(i,xy),nx))),tx),ty),tz)

def relative(point: Q.Point,origin: Q.Point,+h: U32,v: U32) -> Q.Point:
  match point origin:
    case Q.Point{x,y,z} Q.Point{ox,oy,oz}: Q.Point{D.divide(D.sub(x,ox),D.unsigned(h)),D.divide(D.sub(y,oy),D.unsigned(v)),D.divide(D.sub(z,oz),D.unsigned(h))}

def contains(+p: D.Number,n: U32) -> Bool:
  Bool.and(Bool.not(D.less(p,D.scalar(0.0))),Bool.not(D.less(D.unsigned(U32.sub(n,1)),p)))

@unsafe
def located(covered: Bool,point: Q.Point,+nx: U32,+ny: U32,nz: U32,values: Q.Table<Q.Outputs>,model: Q.Model,programs: Q.Table<Q.Program>,request: DB.Request) -> Q.Outputs:
  match covered point:
    case False{} _: DB.row(True{},model,programs,request)
    case True{} Q.Point{+x,+y,+z}:
      +ix = U32.min(F32.to_u32(D.value(D.floor(x))),U32.sub(nx,2))
      +iy = U32.min(F32.to_u32(D.value(D.floor(y))),U32.sub(ny,2))
      +iz = U32.min(F32.to_u32(D.value(D.floor(z))),U32.sub(nz,2))
      interpolate(values,U32.add(ix,U32.mul(nx,U32.add(iy,U32.mul(ny,iz)))),nx,U32.mul(nx,ny),
        D.sub(x,D.unsigned(ix)),D.sub(y,D.unsigned(iy)),D.sub(z,D.unsigned(iz)))

@unsafe
def lookup(valid: Bool,+point: Q.Point,+nx: U32,+ny: U32,+nz: U32,values: Q.Table<Q.Outputs>,model: Q.Model,programs: Q.Table<Q.Program>,request: DB.Request) -> Q.Outputs:
  match point:
    case Q.Point{x,y,z}: located(Bool.and(valid,Bool.and(contains(x,nx),Bool.and(contains(y,ny),contains(z,nz)))),point,nx,ny,nz,values,model,programs,request)

@unsafe
def row(active: Bool,grid: Grid,model: Q.Model,programs: Q.Table<Q.Program>,+request: DB.Request) -> Q.Outputs:
  match active grid request:
    case False{} _ _: DB.row(False{},model,programs,request)
    case True{} Grid{Layout{origin,nx,ny,nz,h,v,program,low,high},values} DB.Request{pid,x,y,z,lo,hi}:
      lookup(Bool.and(U32.is_eq(program,pid),Bool.and(U32.is_eq(low,lo),U32.is_eq(high,hi))),
        relative(Q.world(x,y,z),origin,h,v),nx,ny,nz,values,model,programs,request)

@unsafe
def calculate(+grid: Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,requests: Array<DB.Request>) -> Array<Q.Outputs>:
  match requests:
    case ALeaf{+request}: ALeaf{row(DB.active(request),grid,model,programs,request)}
    case ANode{a,b}:
      a b = calculate(grid,model,programs,a) calculate(grid,model,programs,b)
      ANode{a,b}

def execute(+grid: Grid,+model: Q.Model,+programs: Q.Table<Q.Program>,requests: Array<DB.Request>) -> Computed:
  rows : Array<Q.Outputs> = calculate!(grid,model,programs,requests)
  Computed{model,programs,grid,rows}
