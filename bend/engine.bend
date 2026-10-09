import Base
import ./noise.bend as N
import ./binary.bend as B
import ./compression.bend as Z
import ./reader.bend as R
import ./transport.bend as T
import ./registry.bend as P
import ./registry_noise.bend as RN
import ./registry_climate.bend as RC
import ./climate.bend as C
import ./climate_batch.bend as CB
import ./registry_density.bend as RD
import ./density.bend as Q
import ./density_batch.bend as DB
import ./density_lattice.bend as L
import ./terrain_surface.bend as S
import ./shoreline.bend as Shore
import ./registry_surface.bend as RS
import ./material_program.bend as M
import ./material_batch.bend as MB
import ./registry_material.bend as RM
import ./material_columns_data.bend as MD
import ./material_columns.bend as MC
import ./registry_material_columns.bend as RMC
import ./material.bend as Material
import ./chunk_catalog.bend as CC
import ./registry_chunk_catalog.bend as RCC
import ./generated_chunk.bend as GC
import ./generated_region.bend as GR
import ./geology.bend as G
import ./registry_geology.bend as RG
import ./ore_data.bend as O
import ./ore_batch.bend as OB
import ./ore_attempts.bend as OA
import ./ore_geometry.bend as OG
import ./ore_raster.bend as OR
import ./ore_spatial.bend as OP
import ./ore_apply.bend as OreApply
import ./cave_dressing.bend as Dressing
import ./geology_batch.bend as GB
import ./cave_lattice.bend as CV
import ./cave_batch.bend as CAV
import ./cave_biome_batch.bend as CBIO
import ./aquifer.bend as AQ
import ./registry_aquifer.bend as RAQ
import ./aquifer_batch.bend as AB
import ./aquifer_lattice.bend as AL
import ./aquifer_substance_batch.bend as ASB
import ./lake_candidates.bend as Lakes
import ./lake_data.bend as LD
import ./lake_lattice.bend as LL
import ./cave_carve.bend as Carve
import ./precise.bend as D

# Persistent binary worker. Protocol v1 is a component transport, not yet a
# world generator: PING=0, PREPARE_NOISE=1, SAMPLE_GRID=2, COMPRESS=3, STOP=4,
# LOAD_PROFILE=5 (staged path), QUERY_PROFILE=6 (dictionary/index path),
# PROFILE_TEXT=7 (lossless packed UTF-16 string),
# PROFILE_NOISE=8 (prepare a loaded climate/ridge stack using both seed words),
# PROFILE_CLIMATE=9 (prepare resident targets), CLIMATE_BATCH=10 (raw queries),
# PROFILE_DENSITY=11 (prepare numeric programs), DENSITY_BATCH=12 (raw queries),
# DENSITY_LATTICE=13 (build resident grid), LATTICE_BATCH=14 (cached queries),
# PROFILE_SURFACE=15, SURFACE_COLUMNS=16, SURFACE_BATCH=17, SURFACE_PLAN=18,
# PROFILE_MATERIAL=19, MATERIAL_BATCH=20, PROFILE_BLOCKS=21,
# BLOCK_COLUMNS=22, BLOCK_BATCH=23, BLOCK_RUNS=24, PROFILE_CHUNK=25,
# CHUNK_NBT=26, CHUNK_ZLIB=27, REGION_MCA=28 (private staged path),
# SHORELINE_COLUMNS=29 (resident exact heights and coastal biome selection),
# PROFILE_GEOLOGY=30, CAVE_NOISE_BATCH=31, CARVER_BATCH=32,
# CAVE_LATTICE=33, CAVE_FIELD_BATCH=34, CARVE_COLUMNS=35, CAVE_BIOME_BATCH=36,
# PROFILE_AQUIFER=37, AQUIFER_FIELD_BATCH=38, AQUIFER_LATTICE=39,
# AQUIFER_SUBSTANCE_BATCH=40, LAKE_SETTINGS=41, LAKE_CANDIDATES=42,
# LAKE_GEOMETRY=43, LAKE_GEOMETRY_BATCH=44, ORE_METADATA=45,
# ORE_REPLACEMENT_BATCH=46 (resident membership, bands and exposure policy),
# ORE_ATTEMPT_BATCH=47, ORE_GEOMETRY_BATCH=48, ORE_MASK_BATCH=49,
# ORE_APPLY_CORE=50 (globally anchored masks into resident block runs),
# CAVE_DRESS_CORE=51 (registered column-owned floor/ceiling decorations).
# Requests: RBND magic, payload length, request ID, opcode (all BE u32).
# Responses add a status u32 after opcode. stdout contains frames only.
type Settings is Data:
  Settings{noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,density: Maybe<&2,RD.Prepared>}

type State is Type:
  State{settings: Settings,profile: Maybe<&1,P.Document>}

type Grid is Data:
  Grid{x: U32,z: U32,width: U32,count: U32,step: U32}

type Samples is Type:
  Leaf{base: U32,values: Array<U32>}
  Node{left: Samples,right: Samples}

def point(active: Bool,+grid: Grid,+stack: N.Stack,+i: U32) -> U32:
  match active grid:
    case False{} _: 0
    case True{} Grid{x,z,+width,_,+step}:
      F32.bits(N.sample(stack,U32.add(x,U32.mul(U32.mod(i,width),step)),U32.add(z,U32.mul(U32.div(i,width),step))))

def fill(+n: Nat,+base: U32,+grid: Grid,+stack: N.Stack,a: Array<U32>,+count: U32) -> Array<U32>:
  match n:
    case 0n: a
    case 1n+p:
      +i = U32.from_nat(p)
      fill(p,base,grid,stack,Array.set(U32,a,i,point(U32.is_lt(U32.add(base,i),count),grid,stack,U32.add(base,i))),count)

def sample(+depth: Nat,+base: U32,+grid: Grid,+stack: N.Stack,+count: U32) -> Samples:
  match depth:
    case 0n: Leaf{base,fill(256n,base,grid,stack,[0 : U32*256n],count)}
    case 1n+p:
      a b = sample(p,base,grid,stack,count) sample(p,U32.add(base,U32.shln(256,p)),grid,stack,count)
      Node{a,b}

@unsafe
def depth(+count: U32,+capacity: U32,n: Nat) -> Nat:
  depth_more(U32.is_gt(count,capacity),count,capacity,n)

@unsafe
def depth_more(more: Bool,count: U32,capacity: U32,n: Nat) -> Nat:
  match more:
    case False{}: n
    case True{}: depth(count,U32.mul(capacity,2),Nat.add(n,1n))

def encode_array(n: Nat,a: Array<U32>,w: B.Writer,+i: U32) -> B.Writer:
  match n:
    case 0n: w
    case 1n+p: Z.read(B.Writer,a,i,a => value => encode_array(p,a,B.int(w,value),U32.inc(i)))

def encode(samples: Samples,w: B.Writer,+count: U32) -> B.Writer:
  match samples:
    case Leaf{+base,a}: encode_array(U32.to_nat(U32.min(256,Z.word(U32.is_lt(base,count),U32.sub(count,base),0))),a,w,0)
    case Node{a,b}: encode(b,encode(a,w,count),count)

def bytes(result: Result<&1,&1,U32,Z.Bytes>) -> IO(Z.Bytes):
  match result:
    case Done{value}: IO.pure(Z.Bytes,value)
    case Fail{code}: IO.die(Z.Bytes,code,"Bend response encoding failed")

def payload(w: B.Writer) -> IO(Z.Bytes):
  bytes(B.done(w))

def reply(output: File,+id: U32,+opcode: U32,+status: U32,body: Z.Bytes) -> IO(File):
  match body:
    case Z.Bytes{a,+size}:
      do IO<File>:
        header : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.int(B.new(),1380077124),size),id),opcode),status))
        output : File <- T.write(output,header)
        T.write(output,Z.Bytes{a,size})

@unsafe
def finish(input: File,output: File,stack: State,+backend: U32,id: U32,opcode: U32,status: U32,body: Z.Bytes,keep: Bool) -> IO(Unit):
  do IO<Unit>:
    output : File <- reply(output,id,opcode,status,body)
    continued(keep,input,output,stack,backend)

@unsafe
def continued(keep: Bool,input: File,output: File,stack: State,backend: U32) -> IO(Unit):
  match keep:
    case True{}: loop(input,output,stack,backend)
    case False{}:
      do IO<Unit>:
        _ : Unit <- File.close(input)
        File.close(output)

@unsafe
def error(input: File,output: File,stack: State,backend: U32,id: U32,opcode: U32,code: U32) -> IO(Unit):
  do IO<Unit>:
    body : Z.Bytes <- payload(B.int(B.new(),code))
    finish(input,output,stack,backend,id,opcode,1,body,True{})

def modifiers(-A: Type,n: Nat,cursor: R.Cursor,acc: List<&2,F32>,next: R.Cursor -> List<&2,F32> -> A) -> A:
  match n:
    case 0n: next(cursor,List.reverse(&2,F32,acc))
    case 1n+p: R.word(A,cursor,cursor => bits => modifiers(A,p,cursor,R.float(bits) <> acc,next))

@unsafe
def set_valid(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,
    cursor: R.Cursor,frequency: F32,amplitude: F32,channel: U32,low: U32,high: U32,count: U32) -> IO(Unit):
  match valid:
    case False{}: error(input,output,old,backend,id,opcode,603)
    case True{}:
      modifiers(IO(Unit),U32.to_nat(count),cursor,[],cursor => values =>
        set_ended(R.end(cursor),input,output,old,backend,id,opcode,frequency,amplitude,channel,low,high,values))

@unsafe
def set_ended(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,
    frequency: F32,amplitude: F32,channel: U32,low: U32,high: U32,values: List<&2,F32>) -> IO(Unit):
  match valid:
    case False{}: error(input,output,old,backend,id,opcode,603)
    case True{}: set_result(N.prepare(frequency,amplitude,channel,low,high,values),input,output,old,backend,id,opcode)

def replace_noise(old: State,noise: N.Stack) -> State:
  match old:
    case State{Settings{_,climate,density},profile}: State{Settings{Some{noise},climate,density},profile}

@unsafe
def set_result(result: Result<&1,&1,U32,N.Stack>,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result:
    case Fail{code}: error(input,output,old,backend,id,opcode,code)
    case Done{stack}: finish(input,output,replace_noise(old,stack),backend,id,opcode,0,Z.Bytes{[0 : U32*1n],0},True{})

@unsafe
def set(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  R.word(IO(Unit),R.start(body),cursor => low => R.word(IO(Unit),cursor,cursor => high =>
    R.word(IO(Unit),cursor,cursor => frequency => R.word(IO(Unit),cursor,cursor => amplitude =>
    R.word(IO(Unit),cursor,cursor => channel => R.word(IO(Unit),cursor,cursor => +count =>
      set_valid(Bool.and(U32.is_gt(count,0),U32.is_le(count,32)),input,output,old,backend,id,opcode,
        cursor,R.float(frequency),R.float(amplitude),channel,low,high,count)))))))

@unsafe
def grid_valid(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,
    x: U32,z: U32,+width: U32,+height: U32,step: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{None{},climate,density},profile}: error(input,output,State{Settings{None{},climate,density},profile},backend,id,opcode,604)
    case True{} State{Settings{Some{+stack},climate,density},profile}:
      do IO<Unit>:
        samples : Samples = sample!(depth(U32.mul(width,height),256,0n),0,Grid{x,z,width,U32.mul(width,height),step},stack,U32.mul(width,height))
        body : Z.Bytes <- payload(encode(samples,B.new(),U32.mul(width,height)))
        finish(input,output,State{Settings{Some{stack},climate,density},profile},backend,id,opcode,0,body,True{})

@unsafe
def grid(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  R.word(IO(Unit),R.start(body),cursor => x => R.word(IO(Unit),cursor,cursor => z =>
    R.word(IO(Unit),cursor,cursor => +width => R.word(IO(Unit),cursor,cursor => +height =>
    R.word(IO(Unit),cursor,cursor => +step =>
      grid_valid(Bool.and(R.end(cursor),Bool.and(Bool.and(U32.is_gt(width,0),U32.is_le(width,512)),
        Bool.and(Bool.and(U32.is_gt(height,0),U32.is_le(height,512)),Bool.and(U32.is_gt(step,0),U32.is_le(step,1024))))),
        input,output,old,backend,id,opcode,x,z,width,height,step))))))

@unsafe
def empty_command(valid: Bool,input: File,output: File,stack: State,+backend: U32,id: U32,+opcode: U32) -> IO(Unit):
  match valid:
    case False{}: error(input,output,stack,backend,id,opcode,603)
    case True{}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.int(B.new(),1),2),0),36),backend))
        finish(input,output,stack,backend,id,opcode,0,body,U32.is_zero(opcode))

def clear_indices(noise: Settings) -> Settings:
  match noise:
    case Settings{stack,_,_}: Settings{stack,None{},None{}}

# Profile uploads are staged raw files, so large structure registries do not
# bypass the bounded frame queue. The worker owns one validated resident tape.
@unsafe
def profile_result(result: Result<&1,&1,U32,P.Document>,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result old:
    case Fail{code} _: error(input,output,old,backend,id,opcode,code)
    case Done{doc} State{noise,_}:
      P.info(IO(Unit),doc,doc => count => strings => tape => hash =>
        profile_ready(input,output,State{clear_indices(noise),Some{doc}},backend,id,opcode,count,strings,tape,hash))

@unsafe
def profile_ready(input: File,output: File,state: State,backend: U32,id: U32,opcode: U32,count: U32,strings: U32,tape: U32,hash: U32) -> IO(Unit):
  do IO<Unit>:
    body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.new(),count),strings),tape),hash))
    finish(input,output,state,backend,id,opcode,0,body,True{})

@unsafe
def profile_path(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,path: String) -> IO(Unit):
  match valid:
    case False{}: error(input,output,old,backend,id,opcode,603)
    case True{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,P.Document> <- P.load(path)
        profile_result(result,input,output,old,backend,id,opcode)

def path_words(-A: Type,n: Nat,cursor: R.Cursor,path: String,+valid: Bool,next: R.Cursor -> String -> Bool -> A) -> A:
  match n:
    case 0n: next(cursor,String.reverse(path),valid)
    case 1n+p: R.word(A,cursor,cursor => +code => path_words(A,p,cursor,SCon{Char.from_u32(code),path},
      Bool.and(valid,Bool.and(Bool.and(U32.is_gt(code,0),U32.is_le(code,1114111)),Bool.or(U32.is_lt(code,55296),U32.is_gt(code,57343)))),next))

@unsafe
def profile_size(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,cursor: R.Cursor,count: U32) -> IO(Unit):
  match valid:
    case False{}: error(input,output,old,backend,id,opcode,603)
    case True{}: path_words(IO(Unit),U32.to_nat(count),cursor,"",True{},cursor => path => valid =>
      profile_path(Bool.and(valid,R.end(cursor)),input,output,old,backend,id,opcode,path))

@unsafe
def profile_load(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  R.word(IO(Unit),R.start(body),cursor => +count => profile_size(Bool.and(U32.is_gt(count,0),U32.is_le(count,4096)),input,output,old,backend,id,opcode,cursor,count))

@unsafe
def query_done(valid: Bool,doc: P.Document,v: Maybe<&2,P.Value>,input: File,output: File,noise: Settings,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid v:
    case False{} _: error(input,output,State{noise,Some{doc}},backend,id,opcode,603)
    case True{} None{}: error(input,output,State{noise,Some{doc}},backend,id,opcode,707)
    case True{} Some{P.Value{tag,a,b,at}}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.new(),tag),a),b),at))
        finish(input,output,State{noise,Some{doc}},backend,id,opcode,0,body,True{})

@unsafe
def query_steps(n: Nat,cursor: R.Cursor,doc: P.Document,v: Maybe<&2,P.Value>,input: File,output: File,noise: Settings,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match n v:
    case 0n _: query_done(R.end(cursor),doc,v,input,output,noise,backend,id,opcode)
    case 1n+p None{}: query_done(True{},doc,None{},input,output,noise,backend,id,opcode)
    case 1n+p Some{v}: R.word(IO(Unit),cursor,cursor => kind => R.word(IO(Unit),cursor,cursor => key =>
      query_step(kind,p,cursor,doc,v,key,input,output,noise,backend,id,opcode)))

@unsafe
def query_step(kind: U32,n: Nat,cursor: R.Cursor,doc: P.Document,v: P.Value,key: U32,input: File,output: File,noise: Settings,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match kind:
    case 0: P.field(IO(Unit),doc,v,key,doc => v => query_steps(n,cursor,doc,v,input,output,noise,backend,id,opcode))
    case 1: P.item(IO(Unit),doc,v,key,doc => v => query_steps(n,cursor,doc,v,input,output,noise,backend,id,opcode))
    case _: query_done(False{},doc,None{},input,output,noise,backend,id,opcode)

@unsafe
def query_count(valid: Bool,cursor: R.Cursor,doc: P.Document,count: U32,input: File,output: File,noise: Settings,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid:
    case False{}: query_done(False{},doc,None{},input,output,noise,backend,id,opcode)
    case True{}: P.info(IO(Unit),doc,doc => size => strings => at => hash => P.value(IO(Unit),doc,at,doc => v =>
      query_steps(U32.to_nat(count),cursor,doc,Some{v},input,output,noise,backend,id,opcode)))

@unsafe
def query_body(body: Z.Bytes,doc: P.Document,input: File,output: File,noise: Settings,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match body:
    case Z.Bytes{a,+size}: R.word(IO(Unit),R.start(Z.Bytes{a,size}),cursor => +count =>
      query_count(Bool.and(U32.is_le(count,128),U32.is_eq(size,U32.add(4,U32.mul(count,8)))),cursor,doc,count,input,output,noise,backend,id,opcode))

@unsafe
def profile_query(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{noise,None{}}: error(input,output,State{noise,None{}},backend,id,opcode,708)
    case State{noise,Some{doc}}: query_body(body,doc,input,output,noise,backend,id,opcode)

def text_words(-A: Type,n: Nat,doc: P.Document,+at: U32,w: B.Writer,next: P.Document -> B.Writer -> A) -> A:
  match n:
    case 0n: next(doc,w)
    case 1n+p: P.get(A,doc,at,doc => word => text_words(A,p,doc,U32.inc(at),B.int(w,word),next))

@unsafe
def text_ready(valid: Bool,doc: P.Document,at: U32,+length: U32,input: File,output: File,noise: Settings,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid:
    case False{}: error(input,output,State{noise,Some{doc}},backend,id,opcode,709)
    case True{}: text_words(IO(Unit),U32.to_nat(U32.div(U32.inc(length),2)),doc,at,B.int(B.new(),length),doc => w =>
      text_reply(doc,w,input,output,noise,backend,id,opcode))

@unsafe
def text_reply(doc: P.Document,w: B.Writer,input: File,output: File,noise: Settings,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  do IO<Unit>:
    body : Z.Bytes <- payload(w)
    finish(input,output,State{noise,Some{doc}},backend,id,opcode,0,body,True{})

@unsafe
def text_result(found: Maybe<&2,P.StringRef>,doc: P.Document,input: File,output: File,noise: Settings,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match found:
    case None{}: error(input,output,State{noise,Some{doc}},backend,id,opcode,707)
    case Some{P.StringRef{at,+length}}: text_ready(U32.is_le(length,8388606),doc,at,length,input,output,noise,backend,id,opcode)

@unsafe
def text_body(valid: Bool,doc: P.Document,key: U32,input: File,output: File,noise: Settings,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid:
    case False{}: error(input,output,State{noise,Some{doc}},backend,id,opcode,603)
    case True{}: P.string(IO(Unit),doc,key,doc => found => text_result(found,doc,input,output,noise,backend,id,opcode))

@unsafe
def profile_text(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{noise,None{}}: error(input,output,State{noise,None{}},backend,id,opcode,708)
    case State{noise,Some{doc}}: R.word(IO(Unit),R.start(body),cursor => key =>
      text_body(R.end(cursor),doc,key,input,output,noise,backend,id,opcode))

@unsafe
def selected_noise(valid: Bool,old: State,low: U32,high: U32,channel: U32,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{noise,None{}}: error(input,output,State{noise,None{}},backend,id,opcode,708)
    case True{} State{noise,Some{doc}}:
      RN.prepare(IO(Unit),doc,channel,low,high,doc => result =>
        set_result(result,input,output,State{noise,Some{doc}},backend,id,opcode))

@unsafe
def profile_noise(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  R.word(IO(Unit),R.start(body),cursor => low => R.word(IO(Unit),cursor,cursor => high =>
    R.word(IO(Unit),cursor,cursor => +channel => selected_noise(Bool.and(R.end(cursor),U32.is_le(channel,4)),
      old,low,high,channel,input,output,backend,id,opcode))))

@unsafe
def climate_ready(result: Result<&1,&1,U32,RC.Prepared>,doc: P.Document,settings: Settings,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result settings:
    case Fail{code} _: error(input,output,State{settings,Some{doc}},backend,id,opcode,code)
    case Done{RC.Prepared{model,count,biomes}} Settings{noise,_,density}:
      do IO<Unit>:
        # The response write ends CPU preparation's evaluation before the next
        # request can bang the resident index (stock Bend scheduling boundary).
        body : Z.Bytes <- payload(B.int(B.int(B.new(),count),biomes))
        finish(input,output,State{Settings{noise,Some{model},density},Some{doc}},backend,id,opcode,0,body,True{})

@unsafe
def profile_climate(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{settings,None{}}: error(input,output,State{settings,None{}},backend,id,opcode,708)
    case True{} State{settings,Some{doc}}: RC.prepare(IO(Unit),doc,doc => result =>
      climate_ready(result,doc,settings,input,output,backend,id,opcode))

@unsafe
def climate_queries(queries: CB.Input,model: C.Model,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case CB.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case CB.Input{queries,count,True{}}:
      do IO<Unit>:
        rows : Array<CB.Row> = CB.calculate!(model,queries)
        body : Z.Bytes <- payload(CB.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def climate_batch(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{noise,None{},density},profile}: error(input,output,State{Settings{noise,None{},density},profile},backend,id,opcode,714)
    case State{Settings{noise,Some{+model},density},profile}:
      climate_queries(CB.decode(body),model,State{Settings{noise,Some{model},density},profile},input,output,backend,id,opcode)

@unsafe
def density_ready(result: Result<&1,&1,U32,RD.Prepared>,doc: P.Document,settings: Settings,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result settings:
    case Fail{code} _: error(input,output,State{settings,Some{doc}},backend,id,opcode,code)
    case Done{RD.Prepared{+model,+programs,+noises,+points,+fields,lattice,surface,materials}} Settings{noise,climate,_}:
      do IO<Unit>:
        # Acknowledge CPU projection before any subsequent GPU evaluation.
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.new(),3),noises),points),fields))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,lattice,surface,materials}}},Some{doc}},backend,id,opcode,0,body,True{})

@unsafe
def profile_density(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{settings,None{}}: error(input,output,State{settings,None{}},backend,id,opcode,708)
    case True{} State{settings,Some{doc}}: RD.prepare(IO(Unit),doc,doc => result =>
      density_ready(result,doc,settings,input,output,backend,id,opcode))

@unsafe
def density_calculated(materials: Maybe<&2,M.Model>,computed: DB.Computed,count: U32,noises: U32,points: U32,fields: U32,lattice: Maybe<&2,L.Grid>,surface: Maybe<&2,S.State>,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case DB.Computed{model,programs,rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(DB.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,lattice,surface,materials}}},profile},backend,id,opcode,0,body,True{})

@unsafe
def density_queries(queries: DB.Input,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries prepared:
    case DB.Input{_,_,False{}} _:
      error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,603)
    case DB.Input{queries,count,True{}} RD.Prepared{model,programs,noises,points,fields,lattice,surface,materials}:
      density_calculated(materials,DB.execute(model,programs,queries),count,noises,points,fields,lattice,surface,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def density_batch(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case State{Settings{noise,climate,Some{prepared}},profile}:
      density_queries(DB.decode(body),prepared,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def lattice_generated(materials: Maybe<&2,M.Model>,generated: L.Generated,noises: U32,points: U32,fields: U32,surface: Maybe<&2,S.State>,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match generated:
    case L.Generated{model,programs,L.Grid{+layout,values}}:
      lattice_ack(materials,layout,L.Grid{layout,values},model,programs,noises,points,fields,surface,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def lattice_ack(materials: Maybe<&2,M.Model>,layout: L.Layout,grid: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,noises: U32,points: U32,fields: U32,surface: Maybe<&2,S.State>,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match layout:
    case L.Layout{_,+nx,+ny,+nz,_,_,_,_,_}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.new(),nx),ny),nz),U32.mul(U32.mul(nx,ny),nz)))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,Some{grid},surface,M.clear_columns(materials)}}},profile},backend,id,opcode,0,body,True{})

@unsafe
def lattice_prepare(result: Result<&1,&1,U32,L.Layout>,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result prepared:
    case Fail{code} _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,code)
    case Done{layout} RD.Prepared{model,programs,noises,points,fields,_,surface,materials}:
      lattice_generated(materials,L.generate(model,programs,layout),noises,points,fields,S.clear(surface),noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def density_lattice(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case State{Settings{noise,climate,Some{prepared}},profile}:
      lattice_prepare(L.decode(body),prepared,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def lattice_calculated(materials: Maybe<&2,M.Model>,computed: L.Computed,count: U32,noises: U32,points: U32,fields: U32,surface: Maybe<&2,S.State>,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case L.Computed{model,programs,grid,rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(DB.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,Some{grid},surface,materials}}},profile},backend,id,opcode,0,body,True{})

@unsafe
def lattice_queries(queries: DB.Input,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries prepared:
    case DB.Input{_,_,False{}} _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,603)
    case _ RD.Prepared{model,programs,noises,points,fields,None{},surface,materials}:
      error(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,None{},surface,materials}}},profile},backend,id,opcode,718)
    case DB.Input{queries,count,True{}} RD.Prepared{model,programs,noises,points,fields,Some{grid},surface,materials}:
      lattice_calculated(materials,L.execute(grid,model,programs,queries),count,noises,points,fields,surface,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def lattice_batch(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case State{Settings{noise,climate,Some{prepared}},profile}:
      lattice_queries(DB.decode(body),prepared,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def surface_ready(result: Result<&1,&1,U32,S.Config>,doc: P.Document,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result prepared:
    case Fail{code} _: error(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,code)
    case Done{+config} RD.Prepared{model,programs,noises,points,fields,lattice,_,materials}:
      do IO<Unit>:
        body : Z.Bytes <- payload(S.config_ack(config))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,lattice,Some{S.State{config,None{}}},M.clear_columns(materials)}}},Some{doc}},backend,id,opcode,0,body,True{})

@unsafe
def surface_profile(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case True{} State{settings,None{}}: error(input,output,State{settings,None{}},backend,id,opcode,708)
    case True{} State{Settings{noise,climate,Some{prepared}},Some{doc}}:
      RS.prepare(IO(Unit),doc,doc => result => surface_ready(result,doc,prepared,noise,climate,input,output,backend,id,opcode))

@unsafe
def surface_built(materials: Maybe<&2,M.Model>,computed: S.Computed,config: S.Config,noises: U32,points: U32,fields: U32,noise: Maybe<&2,N.Stack>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case S.Computed{model,programs,density,climate,S.Grid{+tile,values}}:
      surface_ack(materials,tile,S.Grid{tile,values},model,programs,density,climate,config,noises,points,fields,noise,profile,input,output,backend,id,opcode)

@unsafe
def surface_ack(materials: Maybe<&2,M.Model>,tile: S.Tile,columns: S.Grid,model: Q.Model,programs: Q.Table<Q.Program>,density: L.Grid,climate: C.Model,config: S.Config,noises: U32,points: U32,fields: U32,noise: Maybe<&2,N.Stack>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match tile:
    case S.Tile{_,_,+width,+depth,_,_}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.new(),width),depth),U32.mul(width,depth)))
        finish(input,output,State{Settings{noise,Some{climate},Some{RD.Prepared{model,programs,noises,points,fields,Some{density},Some{S.State{config,Some{columns}}},M.clear_columns(materials)}}},profile},backend,id,opcode,0,body,True{})

@unsafe
def surface_generate(ok: Bool,+config: S.Config,tile: S.Tile,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: C.Model,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match ok prepared:
    case True{} RD.Prepared{model,programs,noises,points,fields,Some{density},_,materials}:
      surface_built(materials,S.execute(config,tile,density,model,programs,climate),config,noises,points,fields,noise,profile,input,output,backend,id,opcode)
    case _ _: error(input,output,State{Settings{noise,Some{climate},Some{prepared}},profile},backend,id,opcode,721)

@unsafe
def surface_tile(result: Result<&1,&1,U32,S.Tile>,plan: Bool,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result plan prepared climate:
    case Fail{code} _ _ _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,code)
    case _ _ RD.Prepared{_,_,_,_,_,_,None{},_} _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,722)
    case Done{tile} True{} RD.Prepared{model,programs,noises,points,fields,density,Some{S.State{+config,columns}},materials} _:
      do IO<Unit>:
        body : Z.Bytes <- payload(S.descriptor(config,tile))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,Some{S.State{config,columns}},materials}}},profile},backend,id,opcode,0,body,True{})
    case _ False{} _ None{}: error(input,output,State{Settings{noise,None{},Some{prepared}},profile},backend,id,opcode,714)
    case Done{+tile} False{} RD.Prepared{model,programs,noises,points,fields,None{},surface,materials} Some{climate}:
      error(input,output,State{Settings{noise,Some{climate},Some{RD.Prepared{model,programs,noises,points,fields,None{},surface,materials}}},profile},backend,id,opcode,718)
    case Done{+tile} False{} RD.Prepared{model,programs,noises,points,fields,Some{+density},Some{S.State{+config,columns}},materials} Some{climate}:
      surface_generate(S.compatible(config,tile,density),config,tile,RD.Prepared{model,programs,noises,points,fields,Some{density},Some{S.State{config,columns}},materials},noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def surface_build(plan: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case State{Settings{noise,climate,Some{prepared}},profile}: surface_tile(S.decode(body),plan,prepared,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def shoreline_generate(ok: Bool,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: C.Model,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match ok prepared:
    case True{} RD.Prepared{model,programs,noises,points,fields,Some{density},Some{S.State{+config,Some{surface}}},materials}:
      surface_built(materials,Shore.execute(config,density,model,programs,climate,surface),config,noises,points,fields,noise,profile,input,output,backend,id,opcode)
    case _ _: error(input,output,State{Settings{noise,Some{climate},Some{prepared}},profile},backend,id,opcode,735)

@unsafe
def shoreline_build(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case True{} State{Settings{noise,None{},density},profile}: error(input,output,State{Settings{noise,None{},density},profile},backend,id,opcode,714)
    case True{} State{Settings{noise,Some{+climate},Some{RD.Prepared{model,programs,noises,points,fields,Some{+density},Some{S.State{+config,Some{+surface}}},materials}}},profile}:
      shoreline_generate(Shore.compatible(config,surface,density,climate),
        RD.Prepared{model,programs,noises,points,fields,Some{density},Some{S.State{config,Some{surface}}},materials},noise,climate,profile,input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,734)

@unsafe
def surface_queries(queries: S.Input,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries prepared:
    case S.Input{_,_,False{}} _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,603)
    case S.Input{queries,count,True{}} RD.Prepared{model,programs,noises,points,fields,density,Some{S.State{config,Some{+columns}}},materials}:
      do IO<Unit>:
        body : Z.Bytes <- payload(S.encode(U32.to_nat(count),queries,columns,B.new(),0))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,Some{S.State{config,Some{columns}}},materials}}},profile},backend,id,opcode,0,body,True{})
    case _ _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,723)

@unsafe
def surface_batch(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case State{Settings{noise,climate,Some{prepared}},profile}: surface_queries(S.query_decode(body),prepared,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def material_ready(result: Result<&1,&1,U32,M.Model>,doc: P.Document,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result prepared:
    case Fail{code} _: error(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,code)
    case Done{M.Model{+materials,+bands,+sea,+layered,columns}} RD.Prepared{model,programs,noises,points,fields,lattice,surface,_}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.new(),Q.table_size(Q.Program,materials)),Q.table_size(U32,bands)),sea),Z.word(layered,1,0)))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,lattice,surface,Some{M.Model{materials,bands,sea,layered,columns}}}}},Some{doc}},backend,id,opcode,0,body,True{})

@unsafe
def material_profile(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case True{} State{settings,None{}}: error(input,output,State{settings,None{}},backend,id,opcode,708)
    case True{} State{Settings{noise,climate,Some{RD.Prepared{+model,programs,noises,points,fields,lattice,surface,materials}}},Some{doc}}:
      RM.prepare(IO(Unit),doc,model,doc => result => material_ready(result,doc,RD.Prepared{model,programs,noises,points,fields,lattice,surface,materials},noise,climate,input,output,backend,id,opcode))

@unsafe
def material_calculated(computed: MB.Computed,count: U32,programs: Q.Table<Q.Program>,noises: U32,points: U32,fields: U32,lattice: Maybe<&2,L.Grid>,surface: Maybe<&2,S.State>,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case MB.Computed{model,materials,rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(DB.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,lattice,surface,Some{materials}}}},profile},backend,id,opcode,0,body,True{})

@unsafe
def material_queries(queries: MB.Input,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries prepared:
    case MB.Input{_,_,False{}} _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,603)
    case MB.Input{queries,count,True{}} RD.Prepared{model,programs,noises,points,fields,lattice,surface,Some{materials}}:
      material_calculated(MB.execute(model,materials,queries),count,programs,noises,points,fields,lattice,surface,noise,climate,profile,input,output,backend,id,opcode)
    case _ _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,725)

@unsafe
def material_batch(input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,lattice,surface,Some{M.Model{+materials,bands,sea,layered,columns}}}}},profile}:
      material_queries(MB.decode(body,Q.table_size(Q.Program,materials)),RD.Prepared{model,programs,noises,points,fields,lattice,surface,Some{M.Model{materials,bands,sea,layered,columns}}},noise,climate,profile,input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,725)

@unsafe
def columns_profile_ready(result: Result<&1,&1,U32,MD.Config>,doc: P.Document,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result prepared:
    case Fail{code} _: error(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,code)
    case Done{MD.Config{+stone,+water,+count,+a,+b,+c}} RD.Prepared{model,programs,noises,points,fields,density,surface,Some{M.Model{materials,bands,sea,layered,+old}}}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.int(B.int(B.new(),stone),water),count),a),b),c))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
          Some{M.Model{materials,bands,sea,layered,Some{MD.State{MD.Config{stone,water,count,a,b,c},None{},MD.catalog(old),MD.geology(old),None{}}}}}}}},Some{doc}},backend,id,opcode,0,body,True{})
    case _ _: error(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,725)

@unsafe
def columns_profile(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case True{} State{Settings{noise,climate,Some{RD.Prepared{+model,programs,noises,points,fields,density,surface,Some{materials}}}},Some{doc}}:
      RMC.prepare(IO(Unit),doc,model,doc => result => columns_profile_ready(result,doc,RD.Prepared{model,programs,noises,points,fields,density,surface,Some{materials}},noise,climate,input,output,backend,id,opcode))
    case _ _: error(input,output,old,backend,id,opcode,725)

@unsafe
def columns_finished(pair: U32 & U32,computed: MC.Computed,palette: MD.Config,config: S.Config,noises: U32,points: U32,fields: U32,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  (runs,invalid) = pair
  columns_valid(U32.is_zero(invalid),computed,runs,palette,config,noises,points,fields,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def columns_valid(valid: Bool,computed: MC.Computed,runs: U32,palette: MD.Config,config: S.Config,noises: U32,points: U32,fields: U32,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid computed:
    case False{} MC.Computed{model,programs,density,surface,materials,_}:
      error(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,Some{density},Some{S.State{config,Some{surface}}},Some{materials}}}},profile},backend,id,opcode,729)
    case True{} MC.Computed{model,programs,density,surface,M.Model{materials,bands,sea,layered,+old},MD.Grid{+tile,+minimum,+height,+values,+biomes}}:
      columns_ack(tile,MD.Grid{tile,minimum,height,values,biomes},MD.catalog(old),MD.geology(old),model,programs,density,surface,materials,bands,sea,layered,runs,palette,config,noises,points,fields,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def columns_ack(tile: S.Tile,grid: MD.Grid,catalog: Maybe<&2,CC.Catalog>,geology: Maybe<&2,G.Profile>,model: Q.Model,programs: Q.Table<Q.Program>,density: L.Grid,surface: S.Grid,materials: Q.Table<Q.Program>,bands: Q.Table<U32>,sea: U32,layered: Bool,runs: U32,palette: MD.Config,config: S.Config,noises: U32,points: U32,fields: U32,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match tile:
    case S.Tile{_,_,+width,+depth,_,_}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.new(),width),depth),U32.mul(width,depth)),runs))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,Some{density},Some{S.State{config,Some{surface}}},
          Some{M.Model{materials,bands,sea,layered,Some{MD.State{palette,Some{grid},catalog,geology,None{}}}}}}}},profile},backend,id,opcode,0,body,True{})

@unsafe
def columns_computed(computed: MC.Computed,palette: MD.Config,config: S.Config,noises: U32,points: U32,fields: U32,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case MC.Computed{model,programs,density,surface,materials,MD.Grid{tile,minimum,height,+values,+biomes}}:
      columns_finished(MC.summary(values),MC.Computed{model,programs,density,surface,materials,MD.Grid{tile,minimum,height,values,biomes}},palette,config,noises,points,fields,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def columns_generate(ok: Bool,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,+backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match ok prepared:
    case True{} RD.Prepared{model,programs,noises,points,fields,Some{density},Some{S.State{+config,Some{surface}}},Some{M.Model{materials,bands,sea,layered,Some{MD.State{+palette,old,catalog,geology,caves}}}}}:
      columns_computed(MC.execute(U32.is_eq(backend,1),config,palette,density,surface,model,programs,M.Model{materials,bands,sea,layered,Some{MD.State{palette,old,catalog,geology,caves}}}),palette,config,noises,points,fields,noise,climate,profile,input,output,backend,id,opcode)
    case _ _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,728)

@unsafe
def columns_build(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case True{} State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,Some{+density},Some{S.State{+config,Some{+surface}}},Some{M.Model{materials,bands,sea,layered,Some{columns}}}}}},profile}:
      columns_generate(MC.compatible(config,surface,density),RD.Prepared{model,programs,noises,points,fields,Some{density},Some{S.State{config,Some{surface}}},Some{M.Model{materials,bands,sea,layered,Some{columns}}}},noise,climate,profile,input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,727)

@unsafe
def columns_voxels(queries: MC.Input,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries prepared:
    case MC.Input{_,_,False{}} _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,603)
    case MC.Input{queries,count,True{}} RD.Prepared{model,programs,noises,points,fields,density,surface,Some{M.Model{materials,bands,sea,layered,Some{MD.State{palette,Some{+grid},catalog,geology,caves}}}}}:
      do IO<Unit>:
        body : Z.Bytes <- payload(MC.voxel_encode(U32.to_nat(count),queries,grid,B.new(),0))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,Some{M.Model{materials,bands,sea,layered,Some{MD.State{palette,Some{grid},catalog,geology,caves}}}}}}},profile},backend,id,opcode,0,body,True{})
    case _ _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,727)

@unsafe
def columns_queries(queries: S.Input,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries prepared:
    case S.Input{_,_,False{}} _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,603)
    case S.Input{queries,count,True{}} RD.Prepared{model,programs,noises,points,fields,density,surface,Some{M.Model{materials,bands,sea,layered,Some{MD.State{palette,Some{+grid},catalog,geology,caves}}}}}:
      do IO<Unit>:
        body : Z.Bytes <- payload(MC.encode(U32.to_nat(count),queries,grid,B.new(),0))
        finish(input,output,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,Some{M.Model{materials,bands,sea,layered,Some{MD.State{palette,Some{grid},catalog,geology,caves}}}}}}},profile},backend,id,opcode,0,body,True{})
    case _ _: error(input,output,State{Settings{noise,climate,Some{prepared}},profile},backend,id,opcode,727)

@unsafe
def columns_query_decode(+count: U32,queries: Array<S.Query>,valid: Bool,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  columns_queries(S.Input{queries,count,Bool.and(valid,U32.is_le(count,256))},prepared,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def columns_read_input(queries: S.Input,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,profile: Maybe<&1,P.Document>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case S.Input{queries,count,valid}: columns_query_decode(count,queries,valid,prepared,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def columns_read(voxels: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match voxels old:
    case _ State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case True{} State{Settings{noise,climate,Some{prepared}},profile}: columns_voxels(MC.decode(body),prepared,noise,climate,profile,input,output,backend,id,opcode)
    case False{} State{Settings{noise,climate,Some{prepared}},profile}: columns_read_input(S.query_decode(body),prepared,noise,climate,profile,input,output,backend,id,opcode)

@unsafe
def chunk_profile_ready(result: Result<&1,&1,U32,CC.Catalog>,doc: P.Document,+prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result prepared:
    case Fail{code} _: error(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,code)
    case Done{CC.Catalog{+catalog_materials,+catalog_biomes}} RD.Prepared{model,programs,noises,points,fields,density,surface,Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,grid,_,geology,caves}}}}}:
      next : RD.Prepared = RD.Prepared{model,programs,noises,points,fields,density,surface,
        Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,grid,Some{CC.Catalog{catalog_materials,catalog_biomes}},geology,caves}}}}}
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.new(),Q.table_size(Material.Material,catalog_materials)),Q.table_size(String,catalog_biomes)))
        finish(input,output,State{Settings{noise,climate,Some{next}},Some{doc}},backend,id,opcode,0,body,True{})
    case _ _: error(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,727)

@unsafe
def chunk_profile(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case True{} State{Settings{noise,climate,Some{+prepared}},Some{doc}}:
      RCC.prepare(IO(Unit),doc,doc => result => chunk_profile_ready(result,doc,prepared,noise,climate,input,output,backend,id,opcode))
    case _ _: error(input,output,old,backend,id,opcode,727)

@unsafe
def chunk_encoded(result: Result<&1,&1,U32,Z.Bytes>,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result:
    case Fail{code}: error(input,output,old,backend,id,opcode,code)
    case Done{bytes}: finish(input,output,old,backend,id,opcode,0,bytes,True{})

@unsafe
def chunk_generate(compress: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
      Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,Some{+grid},Some{+catalog},geology,caves}}}}}}},profile}:
      prepared : RD.Prepared = RD.Prepared{model,programs,noises,points,fields,density,surface,
        Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,Some{grid},Some{catalog},geology,caves}}}}}
      chunk_encoded(GC.run(GC.decode(body),grid,catalog,compress),State{Settings{noise,climate,Some{prepared}},profile},input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,731)

@unsafe
def region_saved(result: Result<&1,&1,U32,U32 & U32>,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result:
    case Fail{code}: error(input,output,old,backend,id,opcode,code)
    case Done{pair}:
      (chunks,sectors) = pair
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.new(),chunks),sectors))
        finish(input,output,old,backend,id,opcode,0,body,True{})

def region_run(partial: Bool,request: GR.Input,grid: MD.Grid,catalog: CC.Catalog) -> IO(Result<&1,&1,U32,U32 & U32>):
  match partial:
    case False{}: GR.run(request,grid,catalog)
    case True{}: GR.run_partial(request,grid,catalog)

@unsafe
def region_generate(input: File,output: File,old: State,backend: U32,id: U32,+opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{noise,climate,None{}},profile}: error(input,output,State{Settings{noise,climate,None{}},profile},backend,id,opcode,716)
    case State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
      Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,Some{+grid},Some{+catalog},geology,caves}}}}}}},profile}:
      prepared : RD.Prepared = RD.Prepared{model,programs,noises,points,fields,density,surface,
        Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,Some{grid},Some{catalog},geology,caves}}}}}
      do IO<Unit>:
        result : Result<&1,&1,U32,U32 & U32> <- region_run(U32.is_eq(opcode,52),GR.decode(body),grid,catalog)
        region_saved(result,State{Settings{noise,climate,Some{prepared}},profile},input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,731)

@unsafe
def geology_ready(result: Result<&1,&1,U32,G.Profile>,doc: P.Document,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result prepared:
    case Fail{code} _: error(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,code)
    case Done{+geology} RD.Prepared{model,programs,noises,points,fields,density,surface,Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,_,catalog,_,_}}}}}:
      geology_ack(geology,doc,RD.Prepared{model,programs,noises,points,fields,density,surface,
        Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,None{},catalog,Some{geology},None{}}}}}},noise,climate,input,output,backend,id,opcode)
    case _ _: error(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,727)

@unsafe
def geology_ack(geology: G.Profile,doc: P.Document,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match geology:
    case G.Profile{noises,+biomes,flags,lava,level,minimum,height,_,_}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.new(),
          Q.table_size(G.Noise,noises)),Q.table_size(G.Biome,biomes)),G.carver_count(biomes)),O.palette(flags)),lava),level),minimum),height))
        finish(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,0,body,True{})

@unsafe
def geology_profile(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{+settings,Some{doc}}:
      geology_dimensions(settings,State{settings,None{}},doc,input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,727)

# Retain the old state as an opaque owned Type while projecting the profile.
# Capturing its fully expanded Data fields in every registry continuation can
# generate native functions with enormous argument lists in the stock compiler.
@unsafe
def geology_dimensions(settings: Settings,old: State,doc: P.Document,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match settings:
    case Settings{_,_,Some{RD.Prepared{_,_,_,_,_,_,_,Some{M.Model{materials,_,_,_,Some{MD.State{MD.Config{_,_,palette,_,_,_},_,_,_,_}}}}}}}:
      RG.prepare(IO(Unit),doc,palette,Q.table_size(Q.Program,materials),doc => result =>
        geology_loaded(result,doc,old,input,output,backend,id,opcode))
    case _: geology_loaded(Fail{727},doc,old,input,output,backend,id,opcode)

@unsafe
def geology_loaded(result: Result<&1,&1,U32,G.Profile>,doc: P.Document,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match old:
    case State{Settings{noise,climate,Some{prepared}},_}: geology_ready(result,doc,prepared,noise,climate,input,output,backend,id,opcode)
    case State{settings,_}: error(input,output,State{settings,Some{doc}},backend,id,opcode,727)

@unsafe
def geology_computed(computed: GB.Computed,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case GB.Computed{_,rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(DB.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def geology_queries(queries: GB.Input,geology: G.Profile,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case GB.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case GB.Input{queries,count,True{}}: geology_computed(GB.execute(geology,queries),count,old,input,output,backend,id,opcode)

@unsafe
def geology_metadata(result: Result<&1,&1,U32,B.Writer>,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result:
    case Fail{code}: error(input,output,old,backend,id,opcode,code)
    case Done{writer}:
      do IO<Unit>:
        body : Z.Bytes <- payload(writer)
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def geology_request(metadata: Bool,geology: G.Profile,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match metadata:
    case False{}: geology_queries(GB.decode(body),geology,old,input,output,backend,id,opcode)
    case True{}: geology_metadata(GB.metadata_decode(body,geology),old,input,output,backend,id,opcode)

@unsafe
def geology_batch(metadata: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
        Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,grid,catalog,Some{+geology},caves}}}}}}},profile}:
      geology_request(metadata,geology,State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
        Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,grid,catalog,Some{geology},caves}}}}}}},profile},input,output,backend,id,opcode,body)
    case _: error(input,output,old,backend,id,opcode,737)

# A cave-field replacement retains the generated material snapshot. It only
# invalidates future dependent cave masks, not independently encoded base data.
@unsafe
def cave_attach(caves: CV.Grid,+old: State) -> State:
  match old:
    case State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
      Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,grid,catalog,geology,_}}}}}}},profile}:
      State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
        Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,grid,catalog,geology,Some{caves}}}}}}}},profile}
    case _: old

@unsafe
def cave_ack(+grid: CV.Grid,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match grid:
    case CV.Grid{CV.Layout{Q.Point{x,y,z},nx,ny,nz,_,_},_}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.new(),
          CV.word(D.axis(x))),CV.word(D.axis(y))),CV.word(D.axis(z))),nx),ny),nz),CV.size(CV.grid_layout(grid))),4))
        finish(input,output,cave_attach(grid,old),backend,id,opcode,0,body,True{})

@unsafe
def cave_computed(computed: CV.Computed,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case CV.Computed{_,_,_,_,+grid}: cave_finite(CV.finite(grid),grid,old,input,output,backend,id,opcode)

@unsafe
def cave_finite(ok: Bool,grid: CV.Grid,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match ok:
    case False{}: error(input,output,old,backend,id,opcode,743)
    case True{}: cave_ack(grid,old,input,output,backend,id,opcode)

@unsafe
def cave_layout(result: Result<&1,&1,U32,CV.Layout>,config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,geology: G.Profile,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result:
    case Fail{code}: error(input,output,old,backend,id,opcode,code)
    case Done{layout}: cave_computed(CV.generate(layout,config,density,model,programs,climate,geology),old,input,output,backend,id,opcode)

@unsafe
def cave_build(valid: Bool,input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{_,Some{climate},Some{RD.Prepared{model,programs,_,_,_,Some{density},Some{S.State{config,Some{S.Grid{tile,_}}}},
      Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,Some{geology},_}}}}}}},_}:
      cave_layout(CV.layout(config,tile),config,density,model,programs,climate,geology,old,input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,739)

@unsafe
def cave_rows(computed: CAV.Computed,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case CAV.Computed{_,rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(CAV.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def cave_queries(queries: MC.Input,grid: CV.Grid,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case MC.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case MC.Input{queries,count,True{}}: cave_rows(CAV.execute(grid,queries),count,old,input,output,backend,id,opcode)

@unsafe
def cave_batch(input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{_,_,Some{RD.Prepared{_,_,_,_,_,_,_,Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,_,Some{grid}}}}}}}},_}:
      cave_queries(MC.decode(body),grid,old,input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,740)

@unsafe
def cave_biome_rows(computed: CBIO.Computed,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case CBIO.Computed{_,rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(CBIO.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def cave_biome_queries(queries: MC.Input,grid: CV.Grid,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case MC.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case MC.Input{queries,count,True{}}: cave_biome_rows(CBIO.execute(grid,queries),count,old,input,output,backend,id,opcode)

@unsafe
def cave_biome_batch(input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{_,_,Some{RD.Prepared{_,_,_,_,_,_,_,Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,_,Some{grid}}}}}}}},_}:
      cave_biome_queries(MC.decode(body),grid,old,input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,740)

@unsafe
def carved_attach(grid: MD.Grid,+old: State) -> State:
  match old:
    case State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
      Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,_,catalog,geology,caves}}}}}}},profile}:
      State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
        Some{M.Model{materials,bands,sea,layered,Some{MD.State{config,Some{grid},catalog,geology,caves}}}}}}},profile}
    case _: old

@unsafe
def carved_ack(computed: Carve.Computed,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case Carve.Computed{+grid}: carved_summary(MC.summary(Carve.values(grid)),grid,old,input,output,backend,id,opcode)

@unsafe
def carved_summary(pair: U32 & U32,+grid: MD.Grid,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  (runs,invalid) = pair
  match grid:
    case MD.Grid{S.Tile{_,_,+width,+depth,_,_},_,_,_,_}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.new(),width),depth),U32.mul(width,depth)),runs))
        finish(input,output,carved_attach(grid,old),backend,id,opcode,0,body,True{})

@unsafe
def carve_compatible(ok: Bool,source: MD.Grid,grid: CV.Grid,geology: G.Profile,sea: U32,water: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match ok:
    case False{}: error(input,output,old,backend,id,opcode,742)
    case True{}: carved_ack(Carve.execute(source,grid,geology,sea,water),old,input,output,backend,id,opcode)

@unsafe
def carve_build(valid: Bool,input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{_,_,Some{RD.Prepared{_,_,_,_,_,_,_,
      Some{M.Model{_,_,sea,_,Some{MD.State{MD.Config{_,water,_,_,_,_},Some{+source},_,Some{+geology},Some{+grid}}}}}}}},_}:
      carve_compatible(Carve.compatible(source,grid,geology),source,grid,geology,sea,water,old,input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,741)

@unsafe
def aquifer_attach(prepared: RD.Prepared,config: AQ.Prepared) -> RD.Prepared:
  match prepared:
    case RD.Prepared{model,programs,noises,points,fields,density,surface,Some{M.Model{materials,bands,sea,layered,Some{MD.State{palette,grid,catalog,Some{geology},caves}}}}}:
      RD.Prepared{model,programs,noises,points,fields,density,surface,Some{M.Model{materials,bands,sea,layered,Some{MD.State{palette,grid,catalog,Some{G.with_aquifer(geology,config)},caves}}}}}
    case _: prepared

@unsafe
def aquifer_ready(result: Result<&1,&1,U32,AQ.Prepared>,doc: P.Document,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match result:
    case Fail{code}: error(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,code)
    case Done{+config}: aquifer_ack(config,doc,aquifer_attach(prepared,config),noise,climate,input,output,backend,id,opcode)

@unsafe
def aquifer_ack(config: AQ.Prepared,doc: P.Document,prepared: RD.Prepared,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match config:
    case AQ.Prepared{AQ.Config{enabled,program,bottom,step,explicit,minimum,height},_,_}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.int(B.int(B.int(B.new(),N.word(enabled,1,0)),program),bottom),step),N.word(explicit,1,0)),minimum),height))
        finish(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,0,body,True{})

@unsafe
def aquifer_prepare(+prepared: RD.Prepared,doc: P.Document,noise: Maybe<&2,N.Stack>,climate: Maybe<&2,C.Model>,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match prepared:
    case RD.Prepared{model,_,_,_,_,_,_,Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,Some{G.Profile{_,_,_,_,_,minimum,height,_,_}},_}}}}}:
      RAQ.prepare(IO(Unit),doc,model,minimum,height,doc => result => aquifer_ready(result,doc,prepared,noise,climate,input,output,backend,id,opcode))
    case _: error(input,output,State{Settings{noise,climate,Some{prepared}},Some{doc}},backend,id,opcode,743)

@unsafe
def aquifer_profile(valid: Bool,input: File,output: File,old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{noise,climate,Some{prepared}},Some{doc}}: aquifer_prepare(prepared,doc,noise,climate,input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,743)

@unsafe
def aquifer_computed(computed: AB.Computed,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case AB.Computed{rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(DB.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def aquifer_queries(queries: MC.Input,config: AQ.Prepared,model: Q.Model,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case MC.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case MC.Input{queries,count,True{}}: aquifer_computed(AB.execute(config,model,queries),count,old,input,output,backend,id,opcode)

@unsafe
def aquifer_batch(input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{_,_,Some{RD.Prepared{model,programs,_,_,_,_,_,Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,Some{G.Profile{_,_,_,_,_,_,_,Some{config},_}},_}}}}}}},_}:
      aquifer_queries(MC.decode(body),config,model,old,input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,744)

@unsafe
def aquifer_cache_ack(computed: AL.Computed,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed old:
    case AL.Computed{+aq} State{Settings{noise,climate,Some{prepared}},profile}:
      aquifer_cache_reply(aq,State{Settings{noise,climate,Some{aquifer_attach(prepared,aq)}},profile},input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,745)

@unsafe
def aquifer_cache_reply(aq: AQ.Prepared,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match aq:
    case AQ.Prepared{_,_,Some{AQ.Cache{layout,_,AQ.Volume{barrier,_},_}}}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.new(),AL.size(layout)),AL.size(barrier)))
        finish(input,output,old,backend,id,opcode,0,body,True{})
    case _:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.new(),0),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def aquifer_water(catalog: CC.Catalog,water: U32) -> Bool:
  match catalog:
    case CC.Catalog{materials,_}: aquifer_water_material(Q.get(Material.Material,materials,water,CC.empty_material()))

def aquifer_water_material(material: Material.Material) -> Bool:
  match material:
    case Material.Material{Material.State{name,_},_}: String.eq(name,"minecraft:water")

@unsafe
def aquifer_cache_valid(valid: Bool,aq: AQ.Prepared,model: Q.Model,tile: S.Tile,minimum: U32,height: U32,params: AQ.Params,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid aq:
    case False{} _: error(input,output,old,backend,id,opcode,746)
    case True{} AQ.Prepared{AQ.Config{False{},_,_,_,_,_,_},_,_}: aquifer_cache_reply(aq,old,input,output,backend,id,opcode)
    case True{} _: aquifer_cache_ack(AL.execute(aq,model,tile,minimum,height,params),old,input,output,backend,id,opcode)

@unsafe
def aquifer_cache_build(valid: Bool,input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{_,_,Some{RD.Prepared{model,_,_,_,_,_,_,Some{M.Model{_,_,+sea,_,Some{MD.State{MD.Config{_,+water,_,_,_,_},Some{MD.Grid{+tile,minimum,height,_,_}},Some{+catalog},Some{G.Profile{_,_,_,+lava,+level,_,_,Some{aq},_}},_}}}}}}},_}:
      aquifer_cache_valid(AL.compatible(tile,minimum,height),aq,model,tile,minimum,height,AQ.Params{sea,water,lava,level,aquifer_water(catalog,water)},old,input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,745)

@unsafe
def aquifer_substance_rows(computed: ASB.Computed,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case ASB.Computed{rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(ASB.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def aquifer_substance_queries(queries: MC.Input,cache: AQ.Cache,caves: CV.Grid,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case MC.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case MC.Input{queries,count,True{}}: aquifer_substance_rows(ASB.execute(cache,caves,queries),count,old,input,output,backend,id,opcode)

@unsafe
def aquifer_substance_batch(input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{_,_,Some{RD.Prepared{_,_,_,_,_,_,_,Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,Some{G.Profile{_,_,_,_,_,_,_,Some{AQ.Prepared{_,_,Some{cache}}},_}},Some{caves}}}}}}}},_}:
      aquifer_substance_queries(MC.decode(body),cache,caves,old,input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,747)

@unsafe
def lake_settings(input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{_,_,Some{RD.Prepared{_,_,_,_,_,_,_,Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,Some{G.Profile{_,biomes,_,_,_,_,_,_,_}},_}}}}}}},_}:
      geology_metadata(Lakes.metadata_decode(body,biomes),old,input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,748)

@unsafe
def lake_rows(computed: Lakes.Computed,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case Lakes.Computed{rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(Lakes.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def lake_queries(queries: S.Input,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,biomes: Q.Table<G.Biome>,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case S.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case S.Input{queries,count,True{}}: lake_rows(Lakes.execute(model,programs,climate,biomes,queries),count,old,input,output,backend,id,opcode)

@unsafe
def lake_batch(input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{_,Some{climate},Some{RD.Prepared{model,programs,_,_,_,_,_,Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,Some{G.Profile{_,biomes,_,_,_,_,_,_,_}},_}}}}}}},_}:
      lake_queries(S.query_decode(body),model,programs,climate,biomes,old,input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,749)

@unsafe
def lake_cache_attach(+old: State,cache: LD.Cache) -> State:
  match old:
    case State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
      Some{M.Model{materials,bands,sea,layered,Some{MD.State{palette,grid,catalog,Some{geology},caves}}}}}}},profile}:
      State{Settings{noise,climate,Some{RD.Prepared{model,programs,noises,points,fields,density,surface,
        Some{M.Model{materials,bands,sea,layered,Some{MD.State{palette,grid,catalog,Some{G.with_lakes(geology,cache)},caves}}}}}}},profile}
    case _: old

@unsafe
def lake_cache_ack(computed: LL.Computed,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case LL.Computed{+cache}: lake_cache_reply(cache,lake_cache_attach(old,cache),input,output,backend,id,opcode)

@unsafe
def lake_cache_reply(cache: LD.Cache,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  lake_cache_descriptor(LL.cache_layout(cache),old,input,output,backend,id,opcode)

@unsafe
def lake_cache_descriptor(layout: LD.Layout,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match layout:
    case LD.Layout{x,z,nx,nz,_,_}:
      do IO<Unit>:
        body : Z.Bytes <- payload(B.int(B.int(B.int(B.int(B.new(),x),z),nx),nz))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def lake_cache_valid(ok: Bool,+tile: S.Tile,config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,biomes: Q.Table<G.Biome>,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match ok:
    case False{}: error(input,output,old,backend,id,opcode,751)
    case True{}: lake_cache_ack(LL.execute(LL.layout(tile),tile,config,density,model,programs,climate,biomes),old,input,output,backend,id,opcode)

@unsafe
def lake_cache_build(valid: Bool,input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid old:
    case False{} _: error(input,output,old,backend,id,opcode,603)
    case True{} State{Settings{_,Some{climate},Some{RD.Prepared{model,programs,_,_,_,Some{+density},Some{S.State{+config,Some{S.Grid{+tile,_}}}},
      Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,Some{G.Profile{_,biomes,_,_,_,minimum,span,_,_}},_}}}}}}},_}:
      lake_cache_config(config,tile,density,model,programs,climate,biomes,minimum,span,old,input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,750)

@unsafe
def lake_cache_config(+config: S.Config,+tile: S.Tile,+density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,biomes: Q.Table<G.Biome>,minimum: U32,span: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match config:
    case S.Config{cmin,cspan,_,_,_,_}: lake_cache_valid(Bool.and(MC.halo_valid(config,tile,density),Bool.and(U32.is_eq(cmin,minimum),U32.is_eq(cspan,span))),tile,config,density,model,programs,climate,biomes,old,input,output,backend,id,opcode)

@unsafe
def lake_geometry_rows(computed: LL.Rows,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case LL.Rows{values}:
      do IO<Unit>:
        body : Z.Bytes <- payload(LL.encoded(count,0,values,B.new()))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def lake_geometry_queries(queries: S.Input,cache: LD.Cache,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case S.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case S.Input{queries,count,True{}}: lake_geometry_rows(LL.queries(cache,queries),count,old,input,output,backend,id,opcode)

@unsafe
def lake_geometry_batch(input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{_,_,Some{RD.Prepared{_,_,_,_,_,_,_,Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,Some{G.Profile{_,_,_,_,_,_,_,_,Some{cache}}},_}}}}}}},_}:
      lake_geometry_queries(S.query_decode(body),cache,old,input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,752)

@unsafe
def ore_computed(computed: OB.Computed,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case OB.Computed{rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(OB.encoded(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def ore_queries(queries: OB.Input,store: O.Store,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case OB.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case OB.Input{queries,count,True{}}: ore_computed(OB.execute(store,queries),count,old,input,output,backend,id,opcode)

@unsafe
def ore_request(metadata: Bool,+store: O.Store,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match metadata:
    case True{}: geology_metadata(OB.metadata_decode(body,store),old,input,output,backend,id,opcode)
    case False{}: ore_queries(OB.decode(body,store),store,old,input,output,backend,id,opcode)

@unsafe
def ore_batch(metadata: Bool,input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{_,_,Some{RD.Prepared{_,_,_,_,_,_,_,Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,Some{G.Profile{_,_,ores,_,_,_,_,_,_}},_}}}}}}},_}:
      ore_request(metadata,ores,old,input,output,backend,id,opcode,body)
    case _: error(input,output,old,backend,id,opcode,754)

@unsafe
def ore_attempt_rows(computed: OA.Computed,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case OA.Computed{rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(OA.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def ore_attempt_queries(queries: OA.Input,store: O.Store,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case OA.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case OA.Input{queries,count,True{}}: ore_attempt_rows(OA.execute(store,queries),count,old,input,output,backend,id,opcode)

@unsafe
def ore_geometry_rows(computed: OG.Computed,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case OG.Computed{rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(OG.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def ore_geometry_queries(queries: OG.Input,store: O.Store,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case OG.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case OG.Input{queries,count,True{}}: ore_geometry_rows(OG.execute(store,queries),count,old,input,output,backend,id,opcode)

@unsafe
def ore_mask_rows(computed: OR.Computed,count: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case OR.Computed{rows}:
      do IO<Unit>:
        body : Z.Bytes <- payload(OR.encode(U32.to_nat(count),rows,B.new(),0))
        finish(input,output,old,backend,id,opcode,0,body,True{})

@unsafe
def ore_mask_queries(queries: OG.Input,store: O.Store,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match queries:
    case OG.Input{_,_,False{}}: error(input,output,old,backend,id,opcode,603)
    case OG.Input{queries,count,True{}}: ore_mask_rows(OR.execute(store,queries),count,old,input,output,backend,id,opcode)

@unsafe
def ore_plan_request(mode: U32,+store: O.Store,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match mode:
    case 47: ore_attempt_queries(OA.decode(body,store),store,old,input,output,backend,id,opcode)
    case 48: ore_geometry_queries(OG.decode(body,store),store,old,input,output,backend,id,opcode)
    case _: ore_mask_queries(OG.decode(body,store),store,old,input,output,backend,id,opcode)

@unsafe
def ore_plan(mode: U32,input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match old:
    case State{Settings{_,_,Some{RD.Prepared{_,_,_,_,_,_,_,Some{M.Model{_,_,_,_,Some{MD.State{_,_,_,Some{G.Profile{_,_,ores,_,_,_,_,_,_}},_}}}}}}},_}:
      ore_plan_request(mode,ores,old,input,output,backend,id,opcode,body)
    case _: error(input,output,old,backend,id,opcode,754)

@unsafe
def ore_applied(computed: OreApply.Computed,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case OreApply.Computed{grid}: carved_ack(Carve.Computed{grid},old,input,output,backend,id,opcode)

@unsafe
def ore_apply_valid(ok: Bool,env: OP.Env,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match ok:
    case False{}: error(input,output,old,backend,id,opcode,756)
    case True{}: ore_applied(OreApply.execute(env),old,input,output,backend,id,opcode)

@unsafe
def ore_apply_core(decoded: Result<&1,&1,U32,S.Tile>,input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match decoded old:
    case Fail{_} _: error(input,output,old,backend,id,opcode,603)
    case Done{+core} State{Settings{_,Some{climate},Some{RD.Prepared{model,programs,_,_,_,Some{density},Some{S.State{config,_}},
      Some{M.Model{_,_,_,_,Some{MD.State{MD.Config{_,water,_,_,_,_},Some{+source},_,Some{G.Profile{_,_,ores,lava,_,minimum,height,_,_}},_}}}}}}},_}:
      ore_apply_source(core,source,config,density,model,programs,climate,ores,water,lava,minimum,height,old,input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,755)

@unsafe
def ore_apply_source(+core: S.Tile,+source: MD.Grid,config: S.Config,density: L.Grid,model: Q.Model,programs: Q.Table<Q.Program>,climate: C.Model,ores: O.Store,water: U32,lava: U32,minimum: U32,height: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match source:
    case MD.Grid{_,smin,span,_,_}: ore_apply_valid(Bool.and(OreApply.compatible(core,source),Bool.and(U32.is_eq(smin,minimum),U32.is_eq(span,height))),
      OP.Env{config,density,model,programs,climate,source,ores,core,water,lava},old,input,output,backend,id,opcode)

@unsafe
def dressing_applied(computed: Dressing.Computed,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match computed:
    case Dressing.Computed{grid}: carved_ack(Carve.Computed{grid},old,input,output,backend,id,opcode)

@unsafe
def dressing_valid(ok: Bool,env: Dressing.Env,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match ok:
    case False{}: error(input,output,old,backend,id,opcode,759)
    case True{}: dressing_applied(Dressing.execute(env),old,input,output,backend,id,opcode)

@unsafe
def dressing_source(+core: S.Tile,+source: MD.Grid,biomes: Q.Table<G.Biome>,water: U32,lava: U32,minimum: U32,height: U32,old: State,input: File,output: File,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match source:
    case MD.Grid{_,smin,span,_,Some{_}}: dressing_valid(Bool.and(Dressing.compatible(core,source),Bool.and(U32.is_eq(smin,minimum),U32.is_eq(span,height))),
      Dressing.Env{source,core,biomes,water,lava},old,input,output,backend,id,opcode)
    case _: error(input,output,old,backend,id,opcode,758)

@unsafe
def dressing_core(decoded: Result<&1,&1,U32,S.Tile>,input: File,output: File,+old: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match decoded old:
    case Fail{_} _: error(input,output,old,backend,id,opcode,603)
    case Done{core} State{Settings{_,_,Some{RD.Prepared{_,_,_,_,_,_,_,
      Some{M.Model{_,_,_,_,Some{MD.State{MD.Config{_,water,_,_,_,_},Some{source},_,Some{G.Profile{_,biomes,_,lava,_,minimum,height,_,_}},_}}}}}}},_}:
      dressing_source(core,source,biomes,water,lava,minimum,height,old,input,output,backend,id,opcode)
    case _ _: error(input,output,old,backend,id,opcode,758)

@unsafe
def dispatch(input: File,output: File,stack: State,backend: U32,id: U32,opcode: U32,body: Z.Bytes) -> IO(Unit):
  match opcode:
    case 0: empty_command(R.end(R.start(body)),input,output,stack,backend,id,0)
    case 1: set(input,output,stack,backend,id,1,body)
    case 2: grid(input,output,stack,backend,id,2,body)
    case 3: finish(input,output,stack,backend,id,3,0,Z.compress(body),True{})
    case 5: profile_load(input,output,stack,backend,id,5,body)
    case 6: profile_query(input,output,stack,backend,id,6,body)
    case 7: profile_text(input,output,stack,backend,id,7,body)
    case 8: profile_noise(input,output,stack,backend,id,8,body)
    case 9: profile_climate(R.end(R.start(body)),input,output,stack,backend,id,9)
    case 10: climate_batch(input,output,stack,backend,id,10,body)
    case 11: profile_density(R.end(R.start(body)),input,output,stack,backend,id,11)
    case 12: density_batch(input,output,stack,backend,id,12,body)
    case 13: density_lattice(input,output,stack,backend,id,13,body)
    case 14: lattice_batch(input,output,stack,backend,id,14,body)
    case 15: surface_profile(R.end(R.start(body)),input,output,stack,backend,id,15)
    case 16: surface_build(False{},input,output,stack,backend,id,16,body)
    case 17: surface_batch(input,output,stack,backend,id,17,body)
    case 18: surface_build(True{},input,output,stack,backend,id,18,body)
    case 19: material_profile(R.end(R.start(body)),input,output,stack,backend,id,19)
    case 20: material_batch(input,output,stack,backend,id,20,body)
    case 21: columns_profile(R.end(R.start(body)),input,output,stack,backend,id,21)
    case 22: columns_build(R.end(R.start(body)),input,output,stack,backend,id,22)
    case 23: columns_read(True{},input,output,stack,backend,id,23,body)
    case 24: columns_read(False{},input,output,stack,backend,id,24,body)
    case 25: chunk_profile(R.end(R.start(body)),input,output,stack,backend,id,25)
    case 26: chunk_generate(False{},input,output,stack,backend,id,26,body)
    case 27: chunk_generate(True{},input,output,stack,backend,id,27,body)
    case 28: region_generate(input,output,stack,backend,id,28,body)
    case 29: shoreline_build(R.end(R.start(body)),input,output,stack,backend,id,29)
    case 30: geology_profile(R.end(R.start(body)),input,output,stack,backend,id,30)
    case 31: geology_batch(False{},input,output,stack,backend,id,31,body)
    case 32: geology_batch(True{},input,output,stack,backend,id,32,body)
    case 33: cave_build(R.end(R.start(body)),input,output,stack,backend,id,33)
    case 34: cave_batch(input,output,stack,backend,id,34,body)
    case 35: carve_build(R.end(R.start(body)),input,output,stack,backend,id,35)
    case 36: cave_biome_batch(input,output,stack,backend,id,36,body)
    case 37: aquifer_profile(R.end(R.start(body)),input,output,stack,backend,id,37)
    case 38: aquifer_batch(input,output,stack,backend,id,38,body)
    case 39: aquifer_cache_build(R.end(R.start(body)),input,output,stack,backend,id,39)
    case 40: aquifer_substance_batch(input,output,stack,backend,id,40,body)
    case 41: lake_settings(input,output,stack,backend,id,41,body)
    case 42: lake_batch(input,output,stack,backend,id,42,body)
    case 43: lake_cache_build(R.end(R.start(body)),input,output,stack,backend,id,43)
    case 44: lake_geometry_batch(input,output,stack,backend,id,44,body)
    case 45: ore_batch(True{},input,output,stack,backend,id,45,body)
    case 46: ore_batch(False{},input,output,stack,backend,id,46,body)
    case 47: ore_plan(47,input,output,stack,backend,id,47,body)
    case 48: ore_plan(48,input,output,stack,backend,id,48,body)
    case 49: ore_plan(49,input,output,stack,backend,id,49,body)
    case 50: ore_apply_core(S.decode(body),input,output,stack,backend,id,50)
    case 51: dressing_core(S.decode(body),input,output,stack,backend,id,51)
    case 52: region_generate(input,output,stack,backend,id,52,body)
    case 4: empty_command(R.end(R.start(body)),input,output,stack,backend,id,4)
    case _: error(input,output,stack,backend,id,opcode,605)

@unsafe
def body_read(read: T.Read,output: File,stack: State,backend: U32,id: U32,opcode: U32) -> IO(Unit):
  match read:
    case T.End{_}: IO.die(Unit,602,"Bend transport ended before request body")
    case T.Complete{input,body}: dispatch(input,output,stack,backend,id,opcode,body)

@unsafe
def header_valid(valid: Bool,input: File,output: File,stack: State,backend: U32,+length: U32,id: U32,opcode: U32) -> IO(Unit):
  match valid:
    case False{}: IO.die(Unit,606,"Invalid Bend frame magic or payload size")
    case True{}:
      do IO<Unit>:
        read : T.Read <- T.exact(input,length,B.new(),False{})
        body_read(read,output,stack,backend,id,opcode)

@unsafe
def header_read(read: T.Read,output: File,stack: State,backend: U32) -> IO(Unit):
  match read:
    case T.End{input}: continued(False{},input,output,stack,backend)
    case T.Complete{input,header}:
      R.word(IO(Unit),R.start(header),cursor => +magic => R.word(IO(Unit),cursor,cursor => +length =>
        R.word(IO(Unit),cursor,cursor => id => R.word(IO(Unit),cursor,cursor => opcode =>
          header_valid(Bool.and(R.end(cursor),Bool.and(U32.is_eq(magic,1380077124),U32.is_le(length,16777216))),
            input,output,stack,backend,length,id,opcode)))))

@unsafe
def loop(input: File,output: File,stack: State,backend: U32) -> IO(Unit):
  do IO<Unit>:
    read : T.Read <- T.exact(input,16,B.new(),True{})
    header_read(read,output,stack,backend)

def launch(backend: U32) -> IO(Unit):
  do IO<Unit>:
    input : File <- IO.try(File,File.open("/dev/stdin","r"))
    output : File <- IO.try(File,File.open("/dev/stdout","w"))
    loop(input,output,State{Settings{None{},None{},None{}},None{}},backend)

def modes(cpu: Bool,gpu: Bool) -> IO(Unit):
  match cpu gpu:
    case True{} _: launch(0)
    case _ True{}: launch(1)
    case _ _: IO.die(Unit,2,"expected cpu or gpu execution mode")

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> +mode <> Nil{}:
      modes(String.eq(mode,"cpu"),String.eq(mode,"gpu"))
    case _: IO.die(Unit,2,"usage: engine cpu|gpu --threads N --gpu off|on")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
