import Base
import ./registry.bend as P
import ./registry_values.bend as R
import ./registry_density.bend as D
import ./structure_registry.bend as M
import ./structure_values.bend as V
import ./structure_nbt.bend as N
import ./binary.bend as B
import ./compression.bend as Z
import ./region.bend as MCA

# Repair publication can combine cached terrain with edited saved neighbors.
# Remove stale light in Bend, preserving every other typed NBT field, then emit
# one compressed sector-aligned record for the Java file-lifecycle bridge.
@unsafe
def remove(xs: List<&2,M.Field>,+a: U32,+b: U32) -> List<&2,M.Field>:
  match xs:
    case Nil{}: []
    case M.Field{+key,value} <> rest: retained(Bool.or(U32.is_eq(key,a),U32.is_eq(key,b)),key,value,rest,a,b)

@unsafe
def retained(skip: Bool,key: U32,value: M.Node,rest: List<&2,M.Field>,a: U32,b: U32) -> List<&2,M.Field>:
  match skip:
    case True{}: remove(rest,a,b)
    case False{}: M.Field{key,value} <> remove(rest,a,b)

@unsafe
def section(+tag: M.Node,+keys: D.Keys) -> M.Node:
  section_fields(M.item(tag,1),tag,keys)

@unsafe
def section_fields(node: M.Node,tag: M.Node,+keys: D.Keys) -> M.Node:
  match node:
    case M.Object{_,fields}: V.array([V.integer(10),V.object(remove(fields,D.key(keys,5n),D.key(keys,6n)))])
    case _: tag

@unsafe
def sections(n: U32,+i: U32,+items: M.Node,+keys: D.Keys,acc: List<&2,M.Node>) -> M.Node:
  match n:
    case 0: V.array([V.integer(9),V.array([V.integer(10),V.array(List.reverse(&2,M.Node,acc))])])
    case _: keep_section(section(M.item(items,i),keys),U32.sub(n,1),U32.inc(i),items,keys,acc)

@unsafe
def keep_section(+tag: M.Node,n: U32,i: U32,items: M.Node,+keys: D.Keys,acc: List<&2,M.Node>) -> M.Node:
  # Light-only padding has just Y after stripping its two arrays.
  keep_decided(Bool.and(U32.is_eq(M.count(M.item(tag,1)),1),V.has_field(M.item(tag,1),D.key(keys,7n))),tag,n,i,items,keys,acc)

@unsafe
def keep_decided(padding: Bool,tag: M.Node,n: U32,i: U32,items: M.Node,keys: D.Keys,acc: List<&2,M.Node>) -> M.Node:
  match padding:
    case True{}: sections(n,i,items,keys,acc)
    case False{}: sections(n,i,items,keys,tag <> acc)

@unsafe
def changed(+tag: M.Node,status: M.Node,+keys: D.Keys) -> M.Node:
  +items = M.item(M.item(M.field(M.item(tag,1),D.key(keys,4n)),1),1)
  V.put(V.put(V.put(tag,D.key(keys,2n),V.array([V.integer(1),V.integer(0)])),
    D.key(keys,3n),V.array([V.integer(8),status])),D.key(keys,4n),sections(M.count(items),0,items,keys,[]))

def compressed(result: Result<&1,&1,U32,Z.Bytes>) -> Result<&1,&1,U32,Z.Bytes>:
  match result:
    case Fail{code}: Fail{code}
    case Done{bytes}: MCA.packet(Z.compress(bytes))

@unsafe
def checked(valid: Bool,doc: P.Document,tag: M.Node,status: M.Node,keys: D.Keys) -> Result<&1,&1,U32,Z.Bytes>:
  match valid:
    case False{}: Fail{886}
    case True{}: N.root(Result<&1,&1,U32,Z.Bytes>,doc,changed(tag,status,keys),B.new(),doc => writer => compressed(B.done(writer)))

@unsafe
def projected(doc: P.Document,+root: M.Node,+keys: D.Keys) -> Result<&1,&1,U32,Z.Bytes>:
  +tag = M.field(root,D.key(keys,0n))
  +status = M.field(root,D.key(keys,1n))
  +list = M.field(M.item(tag,1),D.key(keys,4n))
  checked(Bool.and(U32.is_eq(M.int(M.item(tag,0),0),10),Bool.and(U32.is_eq(M.tag(status),5),
    Bool.and(U32.is_eq(M.int(M.item(list,0),0),9),U32.is_eq(M.int(M.item(M.item(list,1),0),0),10)))),doc,tag,status,keys)

def loaded(result: Result<&1,&1,U32,P.Document>) -> Result<&1,&1,U32,Z.Bytes>:
  match result:
    case Fail{code}: Fail{code}
    case Done{doc}: D.resolved(Result<&1,&1,U32,Z.Bytes>,["chunk","unlit_status","isLightOn","Status","sections","BlockLight","SkyLight","Y"],doc,[],doc => keys =>
      R.root(Result<&1,&1,U32,Z.Bytes>,doc,doc => root => V.project(Result<&1,&1,U32,Z.Bytes>,doc,root,doc => node => projected(doc,node,keys))))

def run(path: String) -> IO(Result<&1,&1,U32,Z.Bytes>):
  do IO<Result<&1,&1,U32,Z.Bytes>>:
    result : Result<&1,&1,U32,P.Document> <- P.load(path)
    IO.pure(Result<&1,&1,U32,Z.Bytes>,loaded(result))
