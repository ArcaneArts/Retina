import Base
import ./structure_registry.bend as M
import ./structure_values.bend as V
import ./structure_plan.bend as Plan
import ./structure_template.bend as T
import ./structure_processors.bend as S
import ./registry.bend as R

# A cache belongs to exactly one loaded registry document. Prepared templates
# and programs are immutable and shared by starts/chunks; quotas are not cached.
type Rotated is Data:
  Rotated{id: U32,palette: U32,rotation: U32,template: T.Template}
type Program is Data:
  Program{source: R.Value,program: S.Program}
type Cache is Data:
  Cache{templates: List<&2,Rotated>,programs: List<&2,Program>,template_hits: U32,program_hits: U32}

@unsafe
def empty() -> Cache:
  Cache{[],[],0,0}

@unsafe
def template_find(xs: List<&2,Rotated>,+id: U32,+palette: U32,+rotation: U32) -> Maybe<&2,T.Template>:
  match xs:
    case Nil{}: None{}
    case Rotated{a,b,c,t} <> rest: template_match(Bool.and(U32.is_eq(id,a),Bool.and(U32.is_eq(palette,b),U32.is_eq(rotation,c))),t,rest,id,palette,rotation)

@unsafe
def template_match(same: Bool,t: T.Template,rest: List<&2,Rotated>,id: U32,palette: U32,rotation: U32) -> Maybe<&2,T.Template>:
  match same:
    case True{}: Some{t}
    case False{}: template_find(rest,id,palette,rotation)

@unsafe
def template(-A: Type,doc: R.Document,+model: M.Model,+tc: T.Context,+cache: Cache,+id: U32,palette: U32,+rotation: U32,next: R.Document -> Cache -> T.Template -> A) -> A:
  +node = M.item(Plan.array(model,1n),id)
  template_palette(A,doc,tc,cache,id,palette,rotation,node,M.count(M.get(model,node,8n)),next)

@unsafe
def template_palette(-A: Type,doc: R.Document,+tc: T.Context,+cache: Cache,+id: U32,+palette: U32,+rotation: U32,+node: M.Node,+n: U32,next: R.Document -> Cache -> T.Template -> A) -> A:
  match cache n:
    case _ 0: next(doc,cache,T.Template{[],M.empty(),False{}})
    case Cache{xs,_,_,_} _: template_cached(A,template_find(xs,id,U32.mod(palette,n),rotation),doc,tc,cache,id,U32.mod(palette,n),rotation,node,next)

@unsafe
def template_cached(-A: Type,found: Maybe<&2,T.Template>,doc: R.Document,+tc: T.Context,+cache: Cache,+id: U32,+palette: U32,+rotation: U32,node: M.Node,next: R.Document -> Cache -> T.Template -> A) -> A:
  match found cache:
    case Some{t} Cache{xs,ps,hits,ph}: next(doc,Cache{xs,ps,U32.inc(hits),ph},t)
    case None{} _: T.prepare(A,doc,tc,M.value(node),palette,rotation,doc => t => template_stored(A,doc,cache,id,palette,rotation,t,next))

@unsafe
def template_stored(-A: Type,doc: R.Document,cache: Cache,id: U32,palette: U32,rotation: U32,+t: T.Template,next: R.Document -> Cache -> T.Template -> A) -> A:
  match cache:
    case Cache{xs,ps,hits,ph}: next(doc,Cache{Rotated{id,palette,rotation,t} <> xs,ps,hits,ph},t)

@unsafe
def same(+a: R.Value,+b: R.Value) -> Bool:
  match a b:
    case R.Value{a,b,c,d} R.Value{e,f,g,h}: Bool.and(Bool.and(U32.is_eq(a,e),U32.is_eq(b,f)),Bool.and(U32.is_eq(c,g),U32.is_eq(d,h)))

@unsafe
def program_find(xs: List<&2,Program>,+source: R.Value) -> Maybe<&2,S.Program>:
  match xs:
    case Nil{}: None{}
    case Program{value,p} <> rest: program_match(same(source,value),p,rest,source)

@unsafe
def program_match(equal: Bool,p: S.Program,rest: List<&2,Program>,source: R.Value) -> Maybe<&2,S.Program>:
  match equal:
    case True{}: Some{p}
    case False{}: program_find(rest,source)

@unsafe
def program(-A: Type,doc: R.Document,+env: S.Environment,+cache: Cache,+source: R.Value,next: R.Document -> Cache -> S.Program -> A) -> A:
  match cache:
    case Cache{_,ps,_,_}: program_cached(A,program_find(ps,source),doc,env,cache,source,next)

@unsafe
def program_cached(-A: Type,found: Maybe<&2,S.Program>,doc: R.Document,+env: S.Environment,cache: Cache,+source: R.Value,next: R.Document -> Cache -> S.Program -> A) -> A:
  match found cache:
    case Some{p} Cache{xs,ps,th,hits}: next(doc,Cache{xs,ps,th,U32.inc(hits)},p)
    case None{} _: V.project(A,doc,source,doc => node => program_stored(A,doc,cache,source,S.compile(env,node),next))

@unsafe
def program_stored(-A: Type,doc: R.Document,cache: Cache,source: R.Value,+p: S.Program,next: R.Document -> Cache -> S.Program -> A) -> A:
  match cache:
    case Cache{xs,ps,th,hits}: next(doc,Cache{xs,Program{source,p} <> ps,th,hits},p)
