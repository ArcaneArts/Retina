import Base
import ./registry.bend as P
import ./registry_values.bend as V
import ./registry_density.bend as RD
import ./registry_surface.bend as RS
import ./registry_material.bend as RM
import ./aquifer.bend as A
import ./density.bend as Q

@unsafe
def valid(ok: Bool,config: A.Config) -> Result<&1,&1,U32,A.Config>:
  match ok:
    case False{}: Fail{742}
    case True{}: Done{config}

@unsafe
def complete(enabled: Result<&1,&1,U32,Bool>,program: Result<&1,&1,U32,U32>,surface: Result<&1,&1,U32,List<&2,U32>>,+count: U32,minimum: U32,height: U32) -> Result<&1,&1,U32,A.Config>:
  match enabled program surface:
    case Done{enabled} Done{+program} Done{bottom <> +step <> +mode <> Nil{}}:
      valid(Bool.and(Bool.and(U32.is_ge(count,5),U32.is_le(program,U32.sub(count,5))),
        Bool.and(Bool.and(U32.is_ge(step,1),U32.is_le(step,4096)),U32.is_le(mode,1))),
        A.Config{enabled,program,bottom,step,U32.is_eq(mode,1),minimum,height})
    case _ _ _: Fail{742}

@unsafe
def entry(-T: Type,found: Maybe<&2,P.Value>,doc: P.Document,count: U32,+minimum: U32,height: U32,next: P.Document -> Result<&1,&1,U32,A.Config> -> T) -> T:
  match found:
    case None{}: next(doc,Done{A.Config{False{},0,minimum,1,False{},minimum,height}})
    case Some{+value}: V.field(T,doc,value,"enabled",doc => enabled => V.field(T,doc,value,"program",doc => program =>
      V.field(T,doc,value,"surface",doc => found => RS.surface_value(T,doc,found,doc => surface =>
        next(doc,complete(RM.boolean(enabled),RD.integer(program),surface,count,minimum,height))))))

@unsafe
def roots(program: Q.Program) -> Nat:
  match program:
    case Q.Program{_,roots,_}: List.length(&2,U32,roots)

@unsafe
def arities(xs: List<&2,Q.Program>,config: A.Config) -> Bool:
  match xs config:
    case a <> b <> c <> d <> +e <> Nil{} A.Config{_,_,_,_,explicit,_,_}:
      Bool.and(Nat.is_ge(roots(a),2n),Bool.and(Bool.and(Nat.is_ge(roots(b),1n),Nat.is_ge(roots(c),1n)),
        Bool.and(Nat.is_ge(roots(d),1n),Bool.and(Nat.is_ge(roots(e),1n),Bool.or(explicit,Nat.is_ge(roots(e),2n))))))
    case _ _: False{}

@unsafe
def projected(-T: Type,result: Result<&1,&1,U32,List<&2,Q.Program>>,doc: P.Document,+config: A.Config,+model: Q.Model,next: P.Document -> Result<&1,&1,U32,A.Prepared> -> T) -> T:
  match result model:
    case Done{+programs} Q.Model{_,_,fields}: projected_valid(T,Bool.and(arities(programs,config),RD.valid_programs(model,programs,Q.table_size(Q.Field,fields))),doc,A.Prepared{config,Q.prepared_table(Q.Program,programs)},next)
    case _ _: next(doc,Fail{742})

@unsafe
def projected_valid(-T: Type,ok: Bool,doc: P.Document,prepared: A.Prepared,next: P.Document -> Result<&1,&1,U32,A.Prepared> -> T) -> T:
  match ok:
    case False{}: next(doc,Fail{742})
    case True{}: next(doc,Done{prepared})

@unsafe
def skip(-T: Type,n: Nat,doc: P.Document,+at: U32,+keys: RD.Keys,+config: A.Config,+model: Q.Model,next: P.Document -> Result<&1,&1,U32,A.Prepared> -> T) -> T:
  match n:
    case 0n: RD.programs_read(T,5n,doc,at,keys,[],doc => result => projected(T,result,doc,config,model,next))
    case 1n+p: P.get(T,doc,U32.inc(at),doc => span => skip(T,p,doc,U32.add(at,span),keys,config,model,next))

@unsafe
def configured(-T: Type,result: Result<&1,&1,U32,A.Config>,doc: P.Document,at: U32,model: Q.Model,next: P.Document -> Result<&1,&1,U32,A.Prepared> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{742})
    case Done{A.Config{False{},program,bottom,step,explicit,minimum,height}}: next(doc,Done{A.Prepared{A.Config{False{},program,bottom,step,explicit,minimum,height},Q.Empty{}}})
    case Done{+config}: enabled_config(T,config,doc,at,model,next)

@unsafe
def enabled_config(-T: Type,+config: A.Config,doc: P.Document,at: U32,model: Q.Model,next: P.Document -> Result<&1,&1,U32,A.Prepared> -> T) -> T:
  match config:
    case A.Config{_,program,_,_,_,_,_}: RD.keys(T,doc,doc => keys => skip(T,U32.to_nat(program),doc,at,keys,config,model,next))

@unsafe
def array(-T: Type,found: Maybe<&2,P.Value>,registry: P.Value,doc: P.Document,model: Q.Model,minimum: U32,height: U32,next: P.Document -> Result<&1,&1,U32,A.Prepared> -> T) -> T:
  match found:
    case Some{P.Value{6,count,_,at}}: V.field(T,doc,registry,"aquifer",doc => found => entry(T,found,doc,count,minimum,height,doc => result => configured(T,result,doc,U32.add(at,3),model,next)))
    case _: next(doc,Fail{742})

@unsafe
def registry(-T: Type,found: Maybe<&2,P.Value>,doc: P.Document,model: Q.Model,minimum: U32,height: U32,next: P.Document -> Result<&1,&1,U32,A.Prepared> -> T) -> T:
  match found:
    case None{}: next(doc,Fail{742})
    case Some{+value}: V.field(T,doc,value,"programs",doc => found => array(T,found,value,doc,model,minimum,height,next))

@unsafe
def prepare(-T: Type,doc: P.Document,model: Q.Model,minimum: U32,height: U32,next: P.Document -> Result<&1,&1,U32,A.Prepared> -> T) -> T:
  V.root(T,doc,doc => root => V.field(T,doc,root,"registry_program",doc => found => registry(T,found,doc,model,minimum,height,next)))
