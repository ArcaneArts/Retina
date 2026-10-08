import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./terrain_surface.bend as S
import ./precise.bend as D

# Optional stage preparation is strict: no guessed world limits/cell spacing.
# Numeric-only users need not provide these fields, and a rejection retains the
# previous surface state in the engine.
@unsafe
def valid(+ok: Bool,config: S.Config) -> Result<&1,&1,U32,S.Config>:
  match ok:
    case False{}: Fail{719}
    case True{}: Done{config}

@unsafe
def complete(minimum: Result<&1,&1,U32,U32>,height: Result<&1,&1,U32,U32>,sea: Result<&1,&1,U32,U32>,
    surface: Result<&1,&1,U32,List<&2,U32>>,cell: Result<&1,&1,U32,List<&2,U32>>) -> Result<&1,&1,U32,S.Config>:
  match minimum height sea surface cell:
    case Done{+minimum} Done{+height} Done{+sea} Done{+bottom <> +step <> +mode <> Nil{}} Done{+h <> +v <> Nil{}}:
      valid(Bool.and(Bool.and(U32.is_ge(height,1),U32.is_le(height,4096)),
        Bool.and(Bool.and(U32.is_ge(h,1),U32.is_le(h,4096)),Bool.and(Bool.and(U32.is_ge(v,1),U32.is_le(v,4096)),
          Bool.and(Bool.and(U32.is_ge(step,1),U32.is_le(step,4096)),
            Bool.and(U32.is_le(mode,1),
              Bool.not(D.less(D.integer(2147483647),D.add(D.integer(minimum),D.unsigned(height))))))))),
        S.Config{minimum,height,sea,h,v,U32.is_eq(mode,1)})
    case _ _ _ _ _: Fail{719}

@unsafe
def surface_complete(bottom: Result<&1,&1,U32,U32>,step: Result<&1,&1,U32,U32>,mode: Result<&1,&1,U32,U32>) -> Result<&1,&1,U32,List<&2,U32>>:
  match bottom step mode:
    case Done{bottom} Done{step} Done{mode}: Done{[bottom,step,mode]}
    case _ _ _: Fail{719}

@unsafe
def surface_value(-A: Type,doc: P.Document,found: Maybe<&2,P.Value>,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  match found:
    case Some{P.Value{6,3,b,at}}: surface_items(A,doc,P.Value{6,3,b,at},next)
    case Some{P.Value{8,3,b,at}}: surface_items(A,doc,P.Value{8,3,b,at},next)
    case Some{P.Value{9,3,b,at}}: surface_items(A,doc,P.Value{9,3,b,at},next)
    case _: next(doc,Fail{719})

@unsafe
def surface_items(-A: Type,doc: P.Document,+value: P.Value,next: P.Document -> Result<&1,&1,U32,List<&2,U32>> -> A) -> A:
  P.item(A,doc,value,0,doc => bottom => P.item(A,doc,value,1,doc => step =>
    P.item(A,doc,value,2,doc => mode => next(doc,surface_complete(RD.signed(bottom),RD.integer(step),RD.integer(mode))))))

@unsafe
def program(-A: Type,found: Maybe<&2,P.Value>,doc: P.Document,minimum: Result<&1,&1,U32,U32>,height: Result<&1,&1,U32,U32>,sea: Result<&1,&1,U32,U32>,next: P.Document -> Result<&1,&1,U32,S.Config> -> A) -> A:
  match found:
    case Some{+value}: V.field(A,doc,value,"surface",doc => surface => surface_value(A,doc,surface,doc => surface =>
      V.field(A,doc,value,"terrain_cell",doc => cell => RD.ints(A,doc,cell,2,2,doc => cell => next(doc,complete(minimum,height,sea,surface,cell))))))
    case None{}: next(doc,Fail{719})

@unsafe
def prepare(-A: Type,doc: P.Document,next: P.Document -> Result<&1,&1,U32,S.Config> -> A) -> A:
  V.root(A,doc,doc => +root => V.field(A,doc,root,"geology_min_y",doc => minimum =>
    V.field(A,doc,root,"geology_height",doc => height => V.field(A,doc,root,"sea_level",doc => sea =>
      V.field(A,doc,root,"registry_program",doc => found => program(A,found,doc,RD.signed(minimum),RD.integer(height),RD.signed(sea),next))))))
