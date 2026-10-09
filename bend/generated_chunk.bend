import Base
import ./chunk_catalog.bend as CC
import ./chunk.bend as C
import ./density.bend as Q
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./terrain_surface.bend as S
import ./precise.bend as D
import ./noise.bend as N
import ./cave_lattice.bend as CV
import ./compression.bend as Z
import ./reader.bend as R

# Component serializer: immutable final block runs and optional cave-biome volume.
# Features/lighting are later producers.
type Request is Data:
  Request{x: U32,z: U32,low: U32,high: U32,version: U32}

def valid_request(ok: Bool,request: Request) -> Maybe<&2,Request>:
  match ok:
    case False{}: None{}
    case True{}: Some{request}

def decode(bytes: Z.Bytes) -> Maybe<&2,Request>:
  R.word(Maybe<&2,Request>,R.start(bytes),cursor => x => R.word(Maybe<&2,Request>,cursor,cursor => z =>
    R.word(Maybe<&2,Request>,cursor,cursor => low => R.word(Maybe<&2,Request>,cursor,cursor => high =>
      R.word(Maybe<&2,Request>,cursor,cursor => version => valid_request(R.end(cursor),Request{x,z,low,high,version}))))))

def world(chunk: U32) -> D.Number:
  D.scale(D.integer(chunk),16.0)

def signed_bounds(+point: D.Number) -> Bool:
  Bool.and(Bool.not(D.less(point,D.integer(2147483648))),
    Bool.not(D.less(D.integer(2147483647),D.add(point,D.scalar(15.0)))))

def covered(+grid: MD.Grid,+request: Request) -> Bool:
  match grid request:
    case MD.Grid{S.Tile{+ox,+oz,+width,+depth,+lo,+hi},_,_,_,_} Request{+x,+z,+low,+high,_}:
      Bool.and(Bool.and(U32.is_eq(lo,low),U32.is_eq(hi,high)),
        Bool.and(Bool.and(Bool.not(D.less(world(x),D.integer(ox))),Bool.not(D.less(world(z),D.integer(oz)))),
          Bool.and(Bool.not(D.less(D.add(D.integer(ox),D.unsigned(width)),D.add(world(x),D.scalar(16.0)))),
            Bool.not(D.less(D.add(D.integer(oz),D.unsigned(depth)),D.add(world(z),D.scalar(16.0)))))))

@unsafe
def block_column(+n: U32,+column: U32,+offset: U32,+runs: List<&2,MD.Run>,blocks: Array<U32>) -> Array<U32>:
  match n:
    case 0: blocks
    case _: block_column(U32.sub(n,1),column,offset,runs,Array.set(U32,blocks,
      U32.add(U32.mul(U32.sub(n,1),256),column),MC.material_at(runs,U32.add(offset,U32.sub(n,1)))))

@unsafe
def column_blocks(column: MD.Column,+n: U32,dx: U32,dz: U32,width: U32,+offset: U32,columns: Q.Table<MD.Column>,array: Array<U32>) -> Array<U32>:
  match column:
    case MD.Column{_,_,_,_,_,_,_,runs,_,_}: blocks(n,dx,dz,width,offset,columns,block_column(16,n,offset,runs,array))

@unsafe
def blocks(+n: U32,+dx: U32,+dz: U32,+width: U32,+offset: U32,+columns: Q.Table<MD.Column>,array: Array<U32>) -> Array<U32>:
  match n:
    case 0: array
    case _: column_blocks(Q.get(MD.Column,columns,U32.add(U32.add(dx,U32.mod(U32.sub(n,1),16)),
      U32.mul(width,U32.add(dz,U32.div(U32.sub(n,1),16)))),MC.empty()),U32.sub(n,1),dx,dz,width,offset,columns,array)

def biome(column: MD.Column) -> U32:
  match column:
    case MD.Column{_,biome,_,_,_,_,_,_,_,_}: biome

@unsafe
def quart(+n: U32,+point: Q.Point) -> Q.Point:
  Q.shifted(point,D.unsigned(U32.mul(U32.mod(n,4),4)),D.unsigned(U32.mul(U32.div(n,16),4)),D.unsigned(U32.mul(U32.mod(U32.div(n,4),4),4)))

def underground(caves: Maybe<&2,CV.Grid>,point: Q.Point,low: U32,high: U32,surface: U32) -> U32:
  match caves:
    case None{}: surface
    case Some{grid}: CV.inherit(CV.biome(grid,point,low,high),surface)

@unsafe
def biomes(+n: U32,+dx: U32,+dz: U32,+width: U32,+columns: Q.Table<MD.Column>,+caves: Maybe<&2,CV.Grid>,+point: Q.Point,+low: U32,+high: U32,array: Array<U32>) -> Array<U32>:
  match n:
    case 0: array
    case _: biomes(U32.sub(n,1),dx,dz,width,columns,caves,point,low,high,Array.set(U32,array,U32.sub(n,1),
      underground(caves,quart(U32.sub(n,1),point),low,high,biome(Q.get(MD.Column,columns,
        U32.add(U32.add(dx,U32.mul(U32.mod(U32.sub(n,1),4),4)),
          U32.mul(width,U32.add(dz,U32.mul(U32.mod(U32.div(U32.sub(n,1),4),4),4)))),MC.empty())))))

@unsafe
def sections(+n: U32,+dx: U32,+dz: U32,+width: U32,+columns: Q.Table<MD.Column>,+caves: Maybe<&2,CV.Grid>,+point: Q.Point,+low: U32,+high: U32,acc: List<C.Section>) -> List<C.Section>:
  match n:
    case 0: acc
    case _: sections(U32.sub(n,1),dx,dz,width,columns,caves,point,low,high,C.Section{
      blocks(256,dx,dz,width,U32.mul(U32.sub(n,1),16),columns,[0:U32*4096n]),
      biomes(64,dx,dz,width,columns,caves,Q.shifted(point,D.scalar(0.0),D.unsigned(U32.mul(U32.sub(n,1),16)),D.scalar(0.0)),low,high,[0:U32*64n]),None{}} <> acc)

def encode(grid: MD.Grid,catalog: CC.Catalog,request: Request) -> Result<&1,&1,U32,Z.Bytes>:
  match grid request:
    case MD.Grid{S.Tile{+ox,+oz,+width,_,_,_},+minimum,+height,columns,caves} Request{+x,+z,+low,+high,version}:
      C.encode(C.Chunk{version,x,z,N.signed(F32.div(D.value(D.integer(minimum)),16.0)),U32.div(height,16),
        sections(U32.div(height,16),F32.to_u32(D.value(D.sub(world(x),D.integer(ox)))),
          F32.to_u32(D.value(D.sub(world(z),D.integer(oz)))),width,columns,caves,Q.Point{world(x),D.integer(minimum),world(z)},low,high,[]),None{},C.Metadata{[],[],[],[]}},CC.profile(catalog))

def compress_result(compress: Bool,result: Result<&1,&1,U32,Z.Bytes>) -> Result<&1,&1,U32,Z.Bytes>:
  match compress result:
    case _ Fail{code}: Fail{code}
    case False{} Done{bytes}: Done{bytes}
    case True{} Done{bytes}: Done{Z.compress(bytes)}

def alignment(ok: Bool,grid: MD.Grid,catalog: CC.Catalog,request: Request,compress: Bool) -> Result<&1,&1,U32,Z.Bytes>:
  match ok:
    case False{}: Fail{733}
    case True{}: compress_result(compress,encode(grid,catalog,request))

def coverage(ok: Bool,+grid: MD.Grid,catalog: CC.Catalog,request: Request,compress: Bool) -> Result<&1,&1,U32,Z.Bytes>:
  match ok grid:
    case False{} _: Fail{732}
    case True{} MD.Grid{_,+minimum,+height,_,_}: alignment(Bool.and(U32.is_zero(U32.mod(minimum,16)),U32.is_zero(U32.mod(height,16))),grid,catalog,request,compress)

@unsafe
def run(found: Maybe<&2,Request>,+grid: MD.Grid,catalog: CC.Catalog,compress: Bool) -> Result<&1,&1,U32,Z.Bytes>:
  match found:
    case None{}: Fail{603}
    case Some{+request}: bounded(Bool.and(signed_bounds(world(x(request))),signed_bounds(world(z(request)))),grid,catalog,request,compress)

def x(request: Request) -> U32:
  match request:
    case Request{x,_,_,_,_}: x

def z(request: Request) -> U32:
  match request:
    case Request{_,z,_,_,_}: z

def bounded(ok: Bool,+grid: MD.Grid,catalog: CC.Catalog,+request: Request,compress: Bool) -> Result<&1,&1,U32,Z.Bytes>:
  match ok:
    case False{}: Fail{733}
    case True{}: coverage(covered(grid,request),grid,catalog,request,compress)
