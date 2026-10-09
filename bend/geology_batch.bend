import Base
import ./geology.bend as G
import ./noise.bend as N
import ./density.bend as Q
import ./precise.bend as D
import ./registry.bend as P
import ./reader.bend as R
import ./binary.bend as B
import ./compression.bend as Z

type Request is Data:
  Request{active: Bool,x: U32,y: U32,z: U32,low: U32,high: U32}
type Input is Type:
  Input{queries: Array<Request>,count: U32,valid: Bool}
type Computed is Type:
  Computed{profile: G.Profile,rows: Array<Q.Outputs>}

@unsafe
def requests(n: Nat,cursor: R.Cursor,a: Array<Request>,+i: U32,+count: U32) -> Input:
  match n:
    case 0n: Input{a,count,R.end(cursor)}
    case 1n+p: R.word(Input,cursor,cursor => x => R.word(Input,cursor,cursor => y => R.word(Input,cursor,cursor => z =>
      R.word(Input,cursor,cursor => low => R.word(Input,cursor,cursor => high =>
      requests(p,cursor,Array.set(Request,a,i,Request{True{},x,y,z,low,high}),U32.inc(i),count))))))

@unsafe
def size(ok: Bool,cursor: R.Cursor,+count: U32) -> Input:
  match ok:
    case False{}: Input{[Request{False{},0,0,0,0,0}:Request*1n],0,False{}}
    case True{}: requests(U32.to_nat(count),cursor,Array.new(Request,P.capacity(count,1,0n),Request{False{},0,0,0,0,0}),0,count)

@unsafe
def decode(bytes: Z.Bytes) -> Input:
  R.word(Input,R.start(bytes),cursor => +count => size(U32.is_le(count,4096),cursor,count))

@unsafe
def channel(+noises: Q.Table<G.Noise>,+i: U32,x: U32,y: U32,z: U32,low: U32,high: U32) -> D.Number:
  D.scalar(G.sample_noise(Q.get(G.Noise,noises,i,G.Noise{0.0,0.0,[0.0]}),i,x,y,z,low,high))

@unsafe
def row(request: Request,+noises: Q.Table<G.Noise>) -> Q.Outputs:
  match request:
    case Request{False{},_,_,_,_,_}: Q.Outputs{D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0)}
    case Request{True{},+x,+y,+z,+low,+high}: Q.Outputs{channel(noises,0,x,y,z,low,high),channel(noises,1,x,y,z,low,high),
      channel(noises,2,x,y,z,low,high),channel(noises,3,x,y,z,low,high),channel(noises,4,x,y,z,low,high),channel(noises,5,x,y,z,low,high)}

@unsafe
def calculate(+noises: Q.Table<G.Noise>,queries: Array<Request>) -> Array<Q.Outputs>:
  match queries:
    case ALeaf{request}: ALeaf{row(request,noises)}
    case ANode{a,b}:
      a b = calculate(noises,a) calculate(noises,b)
      ANode{a,b}

@unsafe
def execute(profile: G.Profile,queries: Array<Request>) -> Computed:
  match profile:
    case G.Profile{+noises,biomes,flags,lava,level,minimum,height,aquifer,lakes}:
      rows : Array<Q.Outputs> = calculate!(noises,queries)
      Computed{G.Profile{noises,biomes,flags,lava,level,minimum,height,aquifer,lakes},rows}

# Bounded raw metadata queries expose the projected carvers without rewalking
# the registry tape. The numeric data is also the input to subsequent carving.
@unsafe
def vec(vector: Q.Vec4,w: B.Writer) -> B.Writer:
  match vector:
    case Q.Vec4{a,b,c,d}: B.int(B.int(B.int(B.int(w,F32.bits(a)),F32.bits(b)),F32.bits(c)),F32.bits(d))

@unsafe
def carver(carver: G.Carver,w: B.Writer) -> B.Writer:
  match carver:
    case G.Carver{id,kind,G.Height{minimum,maximum,triangle,plateau},probability,shape,detail,variation,distance,axis}:
      vec(axis,vec(distance,vec(variation,vec(detail,vec(shape,B.int(B.int(B.int(B.int(B.int(B.int(B.int(w,
        id),kind),minimum),maximum),N.word(triangle,1,0)),plateau),F32.bits(probability)))))))

@unsafe
def carvers(+n: U32,+values: Q.Table<G.Carver>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0: w
    case _: carvers(U32.sub(n,1),values,carver(Q.get(G.Carver,values,i,empty_carver()),w),U32.inc(i))

@unsafe
def empty_carver() -> G.Carver:
  G.Carver{0,0,G.Height{0,0,False{},0},0.0,Q.Vec4{0.0,0.0,0.0,0.0},Q.Vec4{0.0,0.0,0.0,0.0},Q.Vec4{0.0,0.0,0.0,0.0},Q.Vec4{0.0,0.0,0.0,0.0},Q.Vec4{0.0,0.0,0.0,0.0}}

@unsafe
def metadata_row(biome: G.Biome,w: B.Writer) -> B.Writer:
  match biome:
    case G.Biome{+values,_}: carvers(Q.table_size(G.Carver,values),values,B.int(w,Q.table_size(G.Carver,values)),0)

@unsafe
def metadata(n: Nat,cursor: R.Cursor,+biomes: Q.Table<G.Biome>,w: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match n:
    case 0n: metadata_end(R.end(cursor),w)
    case 1n+p: R.word(Result<&1,&1,U32,B.Writer>,cursor,cursor => +id =>
      metadata_valid(U32.is_lt(id,Q.table_size(G.Biome,biomes)),p,cursor,biomes,id,w))

@unsafe
def metadata_valid(ok: Bool,n: Nat,cursor: R.Cursor,+biomes: Q.Table<G.Biome>,id: U32,w: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match ok:
    case False{}: Fail{603}
    case True{}: metadata(n,cursor,biomes,metadata_row(Q.get(G.Biome,biomes,id,G.empty_biome()),w))

@unsafe
def metadata_end(ok: Bool,w: B.Writer) -> Result<&1,&1,U32,B.Writer>:
  match ok:
    case False{}: Fail{603}
    case True{}: Done{w}

@unsafe
def metadata_size(ok: Bool,count: U32,cursor: R.Cursor,biomes: Q.Table<G.Biome>) -> Result<&1,&1,U32,B.Writer>:
  match ok:
    case False{}: Fail{603}
    case True{}: metadata(U32.to_nat(count),cursor,biomes,B.new())

@unsafe
def metadata_decode(bytes: Z.Bytes,profile: G.Profile) -> Result<&1,&1,U32,B.Writer>:
  match profile:
    case G.Profile{_,biomes,_,_,_,_,_,_,_}: R.word(Result<&1,&1,U32,B.Writer>,R.start(bytes),cursor => +count =>
      metadata_size(U32.is_le(count,256),count,cursor,biomes))
