import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./registry_material.bend as RM
import ./registry_cave_features.bend as Arrays
import ./density.bend as Q
import ./light_volume.bend as L

# Project the shared loaded-registry optics once. No block names or guessed
# transparency enter lighting: all traits and paired faces come from Minecraft.
@unsafe
def finish(-A: Type,valid: Bool,doc: P.Document,model: L.Model,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{884})
    case True{}: next(doc,Done{model})

@unsafe
def coverage(-A: Type,result: Result<&1,&1,U32,Q.Table<U32>>,doc: P.Document,+faces: U32,+sky: Bool,states: Q.Table<L.Optics>,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{884})
    case Done{+bits}: finish(A,U32.is_eq(Q.table_size(U32,bits),U32.mul(faces,U32.div(U32.add(faces,31),32))),doc,
      L.Model{sky,U32.div(U32.add(faces,31),32),states,bits},next)

@unsafe
def states(-A: Type,n: U32,+at: U32,doc: P.Document,+root: P.Value,+keys: RD.Keys,+faces: U32,+sky: Bool,acc: List<&2,L.Optics>,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  match n:
    case 0: RD.named(A,doc,root,keys,4n,doc => found => Arrays.array(A,found,doc,0,doc => bits =>
      coverage(A,bits,doc,faces,sky,Q.prepared_table(L.Optics,List.reverse(&2,L.Optics,acc)),next)))
    case _: P.value(A,doc,at,doc => value => P.get(A,doc,U32.inc(at),doc => span =>
      Arrays.array(A,Some{value},doc,0,doc => row => state(A,row,U32.sub(n,1),U32.add(at,span),doc,root,keys,faces,sky,acc,next))))

@unsafe
def state(-A: Type,result: Result<&1,&1,U32,Q.Table<U32>>,n: U32,at: U32,doc: P.Document,root: P.Value,keys: RD.Keys,+faces: U32,sky: Bool,acc: List<&2,L.Optics>,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  match result:
    case Fail{_}: next(doc,Fail{884})
    case Done{+row}: state_valid(A,Bool.and(U32.is_eq(Q.table_size(U32,row),8),Bool.and(U32.is_le(Q.get(U32,row,0,256),255),valid_faces(6,row,faces))),row,n,at,doc,root,keys,faces,sky,acc,next)

@unsafe
def valid_faces(+n: U32,+row: Q.Table<U32>,+faces: U32) -> Bool:
  match n:
    case 0: True{}
    case _: Bool.and(U32.is_lt(Q.get(U32,row,n,faces),faces),valid_faces(U32.sub(n,1),row,faces))

@unsafe
def state_valid(-A: Type,valid: Bool,+row: Q.Table<U32>,n: U32,at: U32,doc: P.Document,root: P.Value,keys: RD.Keys,faces: U32,sky: Bool,acc: List<&2,L.Optics>,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{884})
    case True{}: states(A,n,at,doc,root,keys,faces,sky,L.Optics{Q.get(U32,row,0,0),Q.get(U32,row,1,0),Q.get(U32,row,2,0),Q.get(U32,row,3,0),Q.get(U32,row,4,0),Q.get(U32,row,5,0),Q.get(U32,row,6,0)} <> acc,next)

@unsafe
def table(-A: Type,found: Maybe<&2,P.Value>,palette: U32,doc: P.Document,root: P.Value,keys: RD.Keys,faces: U32,sky: Bool,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  match found:
    case Some{P.Value{6,+n,_,at}}: table_count(A,U32.is_eq(n,palette),n,U32.add(at,3),doc,root,keys,faces,sky,next)
    case _: next(doc,Fail{884})

@unsafe
def table_count(-A: Type,valid: Bool,n: U32,at: U32,doc: P.Document,root: P.Value,keys: RD.Keys,faces: U32,sky: Bool,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{884})
    case True{}: states(A,n,at,doc,root,keys,faces,sky,[],next)

@unsafe
def settings(-A: Type,face_count: Result<&1,&1,U32,U32>,sky: Result<&1,&1,U32,Bool>,palette: Result<&1,&1,U32,U32>,doc: P.Document,root: P.Value,keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  match face_count sky palette:
    case Done{+faces} Done{sky} Done{+palette}: settings_valid(A,Bool.and(Bool.and(U32.is_gt(faces,0),U32.is_le(faces,16384)),U32.is_gt(palette,0)),faces,sky,palette,doc,root,keys,next)
    case _ _ _: next(doc,Fail{884})

@unsafe
def settings_valid(-A: Type,valid: Bool,+faces: U32,+sky: Bool,+palette: U32,doc: P.Document,+root: P.Value,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  match valid:
    case False{}: next(doc,Fail{884})
    case True{}: RD.named(A,doc,root,keys,3n,doc => found => table(A,found,palette,doc,root,keys,faces,sky,next))

@unsafe
def lighting(-A: Type,found: Maybe<&2,P.Value>,palette: Result<&1,&1,U32,U32>,doc: P.Document,+keys: RD.Keys,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  match found:
    case Some{+root}: RD.named(A,doc,root,keys,1n,doc => sky => RD.named(A,doc,root,keys,2n,doc => faces =>
      settings(A,RD.integer(faces),RM.boolean(sky),palette,doc,root,keys,next)))
    case _: next(doc,Fail{884})

@unsafe
def prepare(-A: Type,doc: P.Document,next: P.Document -> Result<&1,&1,U32,L.Model> -> A) -> A:
  RD.resolved(A,["lighting","sky","faces","states","blocked","materials"],doc,[],doc => +keys =>
    V.root(A,doc,doc => +root => RD.named(A,doc,root,keys,5n,doc => materials => RD.named(A,doc,root,keys,0n,doc => found =>
      lighting(A,found,RM.count(materials,65535),doc,keys,next)))))
