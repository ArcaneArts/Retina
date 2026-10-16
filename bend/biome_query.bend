import Base
import ./structure_terrain.bend as Terrain
import ./terrain_surface.bend as S
import ./density.bend as Q
import ./precise.bend as D
import ./word64.bend as W
import ./reader.bend as R
import ./compression.bend as Z
import ./binary.bend as B

# Read-only final quart-biome probes. Reuse globally aligned density/shore
# sampling and the same underground selection as the generated cave volume.
# Only IDs return to Java; queries never replace resident terrain or lighting.
type Point is Data:
  Point{x: U32,y: U32,z: U32}
type Query is Data:
  Query{seed: W.U64,points: List<&2,Point>}

@unsafe
def decode(bytes: Z.Bytes) -> Result<&1,&1,U32,Query>:
  R.word(Result<&1,&1,U32,Query>,R.start(bytes),cursor => lo =>
    R.word(Result<&1,&1,U32,Query>,cursor,cursor => hi =>
      R.word(Result<&1,&1,U32,Query>,cursor,cursor => +n =>
        bounded(U32.is_le(n,64),n,cursor,W.U64{hi,lo}))))

@unsafe
def bounded(ok: Bool,n: U32,cursor: R.Cursor,seed: W.U64) -> Result<&1,&1,U32,Query>:
  match ok:
    case False{}: Fail{887}
    case True{}: points(n,cursor,seed,[])

@unsafe
def points(n: U32,cursor: R.Cursor,seed: W.U64,acc: List<&2,Point>) -> Result<&1,&1,U32,Query>:
  match n:
    case 0: ended(R.end(cursor),seed,acc)
    case _: R.word(Result<&1,&1,U32,Query>,cursor,cursor => x =>
      R.word(Result<&1,&1,U32,Query>,cursor,cursor => y =>
        R.word(Result<&1,&1,U32,Query>,cursor,cursor => z => points(U32.sub(n,1),cursor,seed,Point{x,y,z} <> acc))))

def ended(ok: Bool,seed: W.U64,acc: List<&2,Point>) -> Result<&1,&1,U32,Query>:
  match ok:
    case False{}: Fail{603}
    case True{}: Done{Query{seed,List.reverse(&2,Point,acc)}}

@unsafe
def clamp_y(+y: U32,+minimum: U32,height: U32) -> U32:
  +maximum = U32.sub(U32.add(minimum,height),1)
  below(D.less(D.integer(y),D.integer(minimum)),y,minimum,maximum)

@unsafe
def below(low: Bool,+y: U32,minimum: U32,+maximum: U32) -> U32:
  match low:
    case True{}: minimum
    case False{}: above(D.less(D.integer(maximum),D.integer(y)),y,maximum)

def above(high: Bool,y: U32,maximum: U32) -> U32:
  match high:
    case True{}: maximum
    case False{}: y

def probe(p: Point,config: S.Config) -> Terrain.Probe:
  match p config:
    case Point{x,y,z} S.Config{minimum,height,_,_,_,_}:
      Terrain.Probe{U32.and(x,4294967292),U32.and(z,4294967292),
        Q.Leaf{Terrain.Spec{True{},U32.and(clamp_y(y,minimum,height),4294967292)}}}

@unsafe
def probes(xs: List<&2,Point>,+config: S.Config) -> List<&2,Terrain.Probe>:
  match xs:
    case Nil{}: []
    case p <> rest: probe(p,config) <> probes(rest,config)

def encode(values: Q.Table<Q.Table<U32>>,writer: B.Writer) -> B.Writer:
  match values:
    case Q.Empty{}: writer
    case Q.Leaf{row}: B.int(writer,Q.get(U32,row,0,4294967295))
    case Q.Branch{_,a,b}: encode(b,encode(a,writer))

@unsafe
def run(query: Query,env: Terrain.Environment) -> B.Writer:
  match query env:
    case Query{seed,points} Terrain.Environment{+config,density,model,programs,climate,_}:
      encode(Terrain.execute(Q.prepared_table(Terrain.Probe,probes(points,config)),
        Terrain.Environment{config,density,model,programs,climate,seed}),B.new())
