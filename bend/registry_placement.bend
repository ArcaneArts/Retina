import Base
import ./registry.bend as R
import ./registry_values.bend as V
import ./registry_density.bend as D
import ./registry_material.bend as RM
import ./registry_cave_features.bend as A
import ./registry_decoration_counts.bend as RC
import ./registry_decoration_heights.bend as RH
import ./registry_int_provider.bend as RI
import ./registry_placement_predicate.bend as RP
import ./registry_placement_policy.bend as PP
import ./placement_data.bend as P
import ./placement_height.bend as H
import ./int_provider.bend as I
import ./decoration_counts.bend as C
import ./density.bend as Q
import ./word64.bend as W

type Context is Data:
  Context{keys: D.Keys,predicates: D.Keys,integers: D.Keys,counts: Q.Table<C.Row>,heights: Q.Table<H.Row>,palette: U32}
type Group is Data:
  Group{salt: W.U64,id: U32}

@unsafe
def fields(-T: Type,indices: List<&2,U32>,doc: R.Document,+value: R.Value,+keys: D.Keys,acc: List<&2,Maybe<&2,R.Value>>,next: R.Document -> List<&2,Maybe<&2,R.Value>> -> T) -> T:
  match indices:
    case Nil{}: next(doc,List.reverse(&2,Maybe<&2,R.Value>,acc))
    case i <> rest: D.named(T,doc,value,keys,U32.to_nat(i),doc => found => fields(T,rest,doc,value,keys,found <> acc,next))

@unsafe
def count_key(+r: U32,+m: U32,+s: U32,+rr: U32,+mm: U32,+ss: U32) -> Bool:
  Bool.or(U32.is_lt(r,rr),Bool.and(U32.is_eq(r,rr),Bool.or(U32.is_lt(m,mm),Bool.and(U32.is_eq(m,mm),U32.is_lt(s,ss)))))

@unsafe
def count_find(+rows: Q.Table<C.Row>,+r: U32,+m: U32,+s: U32,+lo: U32,+hi: U32) -> Maybe<&2,C.Rule>:
  count_room(U32.is_lt(lo,hi),rows,r,m,s,lo,hi)

@unsafe
def count_room(+room: Bool,+rows: Q.Table<C.Row>,+r: U32,+m: U32,+s: U32,+lo: U32,+hi: U32) -> Maybe<&2,C.Rule>:
  match room:
    case False{}: None{}
    case True{}:
      +mid = U32.add(lo,U32.div(U32.sub(hi,lo),2))
      count_row(Q.get(C.Row,rows,mid,C.Row{4294967295,0,0,C.Provider{I.Constant{0}}}),rows,r,m,s,lo,hi,mid)

@unsafe
def count_row(+row: C.Row,+rows: Q.Table<C.Row>,+r: U32,+m: U32,+s: U32,+lo: U32,+hi: U32,+mid: U32) -> Maybe<&2,C.Rule>:
  match row:
    case C.Row{+rr,+mm,+ss,rule}: count_equal(Bool.and(U32.is_eq(r,rr),Bool.and(U32.is_eq(m,mm),U32.is_eq(s,ss))),count_key(r,m,s,rr,mm,ss),rule,rows,r,m,s,lo,hi,mid)

@unsafe
def count_equal(+same: Bool,+left: Bool,+rule: C.Rule,+rows: Q.Table<C.Row>,+r: U32,+m: U32,+s: U32,+lo: U32,+hi: U32,+mid: U32) -> Maybe<&2,C.Rule>:
  match same left:
    case True{} _: Some{rule}
    case False{} True{}: count_find(rows,r,m,s,lo,mid)
    case False{} False{}: count_find(rows,r,m,s,U32.inc(mid),hi)

@unsafe
def height_find(+rows: Q.Table<H.Row>,+r: U32,+m: U32,+lo: U32,+hi: U32) -> Maybe<&2,H.Provider>:
  height_room(U32.is_lt(lo,hi),rows,r,m,lo,hi)

@unsafe
def height_room(+room: Bool,+rows: Q.Table<H.Row>,+r: U32,+m: U32,+lo: U32,+hi: U32) -> Maybe<&2,H.Provider>:
  match room:
    case False{}: None{}
    case True{}:
      +mid = U32.add(lo,U32.div(U32.sub(hi,lo),2))
      height_row(Q.get(H.Row,rows,mid,H.Row{4294967295,0,H.Constant{H.Absolute{0}}}),rows,r,m,lo,hi,mid)

@unsafe
def height_row(+row: H.Row,+rows: Q.Table<H.Row>,+r: U32,+m: U32,+lo: U32,+hi: U32,+mid: U32) -> Maybe<&2,H.Provider>:
  match row:
    case H.Row{+rr,+mm,p}: height_equal(Bool.and(U32.is_eq(r,rr),U32.is_eq(m,mm)),count_key(r,m,0,rr,mm,0),p,rows,r,m,lo,hi,mid)

@unsafe
def height_equal(+same: Bool,+left: Bool,+p: H.Provider,+rows: Q.Table<H.Row>,+r: U32,+m: U32,+lo: U32,+hi: U32,+mid: U32) -> Maybe<&2,H.Provider>:
  match same left:
    case True{} _: Some{p}
    case False{} True{}: height_find(rows,r,m,lo,mid)
    case False{} False{}: height_find(rows,r,m,U32.inc(mid),hi)

@unsafe
def counted(+found: Maybe<&2,C.Rule>,+layer: Bool) -> Result<&1,&1,U32,P.Modifier>:
  match found layer:
    case Some{C.Provider{p}} True{}: Done{P.Layer{p}}
    case Some{rule} False{}: Done{P.Count{rule}}
    case _ _: Fail{761}

@unsafe
def offsets(+x: Maybe<&2,C.Rule>,+y: Maybe<&2,C.Rule>,+z: Maybe<&2,C.Rule>) -> Result<&1,&1,U32,P.Modifier>:
  match x y z:
    case Some{C.Provider{x}} Some{C.Provider{y}} Some{C.Provider{z}}: Done{P.Offset{x,y,z}}
    case _ _ _: Fail{761}

@unsafe
def heighted(+found: Maybe<&2,H.Provider>) -> Result<&1,&1,U32,P.Modifier>:
  match found:
    case Some{p}: Done{P.HeightRange{p}}
    case _: Fail{761}

def signed_default(found: Maybe<&2,R.Value>,fallback: U32) -> Result<&1,&1,U32,U32>:
  match found:
    case None{}: Done{fallback}
    case Some{x}: D.signed(Some{x})

def boolean(found: Maybe<&2,R.Value>,fallback: Bool) -> Result<&1,&1,U32,Bool>:
  match found:
    case None{}: Done{fallback}
    case Some{x}: RM.boolean(Some{x})

@unsafe
def simple(+kind: U32,values: List<&2,Maybe<&2,R.Value>>) -> Result<&1,&1,U32,P.Modifier>:
  match kind values:
    case 34 x <> Nil{}: mapped(D.integer(x))
    case 39 x <> Nil{}: watered(D.signed(x))
    case 40 m <> a <> b <> Nil{}: relative(D.integer(m),signed_default(a,2147483648),signed_default(b,2147483647))
    case 42 a <> b <> Nil{}: selected(D.number(a),D.number(b))
    case 43 x <> Nil{}: rarity(D.integer(x))
    case 44 x <> Nil{}: chance(D.number(x))
    case _ _: Fail{761}

def mapped(result: Result<&1,&1,U32,U32>) -> Result<&1,&1,U32,P.Modifier>:
  match result:
    case Done{x}: Done{P.Heightmap{x}}
    case _: Fail{761}

def watered(result: Result<&1,&1,U32,U32>) -> Result<&1,&1,U32,P.Modifier>:
  match result:
    case Done{x}: Done{P.WaterDepth{x}}
    case _: Fail{761}

def rarity(result: Result<&1,&1,U32,U32>) -> Result<&1,&1,U32,P.Modifier>:
  match result:
    case Done{x}: Done{P.Rarity{x}}
    case _: Fail{761}

def chance(result: Result<&1,&1,U32,F32>) -> Result<&1,&1,U32,P.Modifier>:
  match result:
    case Done{x}: Done{P.Chance{x}}
    case _: Fail{761}

def relative(m: Result<&1,&1,U32,U32>,a: Result<&1,&1,U32,U32>,b: Result<&1,&1,U32,U32>) -> Result<&1,&1,U32,P.Modifier>:
  match m a b:
    case Done{m} Done{a} Done{b}: Done{P.Relative{m,a,b}}
    case _ _ _: Fail{761}

def selected(a: Result<&1,&1,U32,F32>,b: Result<&1,&1,U32,F32>) -> Result<&1,&1,U32,P.Modifier>:
  match a b:
    case Done{a} Done{b}: Done{P.Select{a,b}}
    case _ _: Fail{761}

@unsafe
def cuboid(-T: Type,x: Result<&1,&1,U32,I.Provider>,y: Result<&1,&1,U32,I.Provider>,e: Result<&1,&1,U32,Bool>,i: Result<&1,&1,U32,Bool>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Modifier> -> T) -> T:
  match x y e i:
    case Done{x} Done{y} Done{e} Done{i}: next(doc,Done{P.Cuboid{x,y,e,i}})
    case _ _ _ _: next(doc,Fail{761})

def filtered(result: Result<&1,&1,U32,P.Predicate>) -> Result<&1,&1,U32,P.Modifier>:
  match result:
    case Done{p}: Done{P.Filter{p}}
    case _: Fail{761}

def scanned(d: Result<&1,&1,U32,U32>,t: Result<&1,&1,U32,P.Predicate>,a: Result<&1,&1,U32,P.Predicate>,n: Result<&1,&1,U32,U32>) -> Result<&1,&1,U32,P.Modifier>:
  match d t a n:
    case Done{d} Done{t} Done{a} Done{n}: Done{P.Scan{d,t,a,n}}
    case _ _ _ _: Fail{761}

@unsafe
def scan_allowed(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+keys: D.Keys,next: R.Document -> Result<&1,&1,U32,P.Predicate> -> T) -> T:
  match found:
    case None{}: next(doc,Done{P.Always{}})
    case Some{x}: RP.parse(T,Some{x},doc,keys,next)

@unsafe
def complex(-T: Type,+kind: U32,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+ctx: Context,next: R.Document -> Result<&1,&1,U32,P.Modifier> -> T) -> T:
  match kind values ctx:
    case 33 x <> y <> e <> i <> Nil{} Context{_,_,keys,_,_,_}:
      RI.prepare(T,x,doc,keys,doc => xx => RI.prepare(T,y,doc,keys,doc => yy => cuboid(T,xx,yy,boolean(e,True{}),boolean(i,True{}),doc,next)))
    case 36 d <> t <> a <> n <> Nil{} Context{_,keys,_,_,_,_}:
      RP.parse(T,t,doc,keys,doc => tt => scan_allowed(T,a,doc,keys,doc => aa => next(doc,scanned(D.signed(d),tt,aa,D.integer(n)))))
    case 38 p <> Nil{} Context{_,keys,_,_,_,_}: RP.parse(T,p,doc,keys,doc => result => next(doc,filtered(result)))
    case _ _ _: next(doc,simple(kind,values))

@unsafe
def kind(-T: Type,+kind: U32,doc: R.Document,+value: R.Value,+ctx: Context,+recipe: U32,+modifier: U32,next: R.Document -> Result<&1,&1,U32,P.Modifier> -> T) -> T:
  match kind ctx:
    case 28 Context{_,_,_,+rows,_,_}: next(doc,counted(count_find(rows,recipe,modifier,0,0,Q.table_size(C.Row,rows)),False{}))
    case 29 Context{_,_,_,+rows,_,_}: next(doc,counted(count_find(rows,recipe,modifier,4,0,Q.table_size(C.Row,rows)),True{}))
    case 30 Context{_,_,_,+rows,_,_}: next(doc,counted(count_find(rows,recipe,modifier,0,0,Q.table_size(C.Row,rows)),False{}))
    case 31 Context{_,_,_,+rows,_,_}: next(doc,counted(count_find(rows,recipe,modifier,0,0,Q.table_size(C.Row,rows)),False{}))
    case 32 _: next(doc,Done{P.Square{}})
    case 35 Context{_,_,_,_,+rows,_}: next(doc,heighted(height_find(rows,recipe,modifier,0,Q.table_size(H.Row,rows))))
    case 37 Context{_,_,_,+rows,_,_}: next(doc,offsets(count_find(rows,recipe,modifier,1,0,Q.table_size(C.Row,rows)),count_find(rows,recipe,modifier,2,0,Q.table_size(C.Row,rows)),count_find(rows,recipe,modifier,3,0,Q.table_size(C.Row,rows))))
    case 41 _: next(doc,Done{P.Biome{}})
    case _ Context{keys,_,_,_,_,_}: fields(T,operands(kind),doc,value,keys,[],doc => values => complex(T,kind,values,doc,ctx,next))

def operands(+kind: U32) -> List<&2,U32>:
  match kind:
    case 33: [17,18,19,20]
    case 34: [6]
    case 36: [13,14,15,16]
    case 38: [7]
    case 39: [8]
    case 40: [6,9,10]
    case 42: [11,12]
    case 43: [5]
    case 44: [5]
    case _: []

@unsafe
def name(+keys: D.Keys,+id: U32,+i: U32) -> U32:
  name_equal(U32.is_eq(id,D.key(keys,U32.to_nat(i))),keys,id,i)

@unsafe
def name_equal(+same: Bool,+keys: D.Keys,+id: U32,+i: U32) -> U32:
  match same:
    case True{}: i
    case False{}: name_more(U32.is_lt(i,44),keys,id,U32.inc(i))

@unsafe
def name_more(+more: Bool,+keys: D.Keys,+id: U32,+i: U32) -> U32:
  match more:
    case False{}: 4294967295
    case True{}: name(keys,id,i)

@unsafe
def modifier_type(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+value: R.Value,+ctx: Context,+recipe: U32,+modifier: U32,next: R.Document -> Result<&1,&1,U32,P.Modifier> -> T) -> T:
  match found ctx:
    case Some{R.Value{5,id,_,_}} Context{keys,_,_,_,_,_}: kind(T,name(keys,id,28),doc,value,ctx,recipe,modifier,next)
    case _ _: next(doc,Fail{761})

@unsafe
def modifiers(-T: Type,+n: U32,+at: U32,+i: U32,doc: R.Document,+ctx: Context,+recipe: U32,acc: List<&2,P.Modifier>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Modifier>> -> T) -> T:
  match n ctx:
    case 0 _: next(doc,Done{Q.prepared_table(P.Modifier,List.reverse(&2,P.Modifier,acc))})
    case _ Context{keys,_,_,_,_,_}: R.value(T,doc,at,doc => +value => R.get(T,doc,U32.inc(at),doc => span => D.named(T,doc,value,keys,2n,doc => found =>
      modifier_type(T,found,doc,value,ctx,recipe,i,doc => result => appended(T,result,U32.sub(n,1),U32.add(at,span),U32.inc(i),doc,ctx,recipe,acc,next)))))

@unsafe
def appended(-T: Type,result: Result<&1,&1,U32,P.Modifier>,+n: U32,+at: U32,+i: U32,doc: R.Document,+ctx: Context,+recipe: U32,acc: List<&2,P.Modifier>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Modifier>> -> T) -> T:
  match result ctx:
    case Done{+m} Context{_,_,_,_,_,palette}: append_valid(T,P.modifier_valid(m,palette),m,n,at,i,doc,ctx,recipe,acc,next)
    case _ _: next(doc,Fail{761})

@unsafe
def append_valid(-T: Type,+valid: Bool,+m: P.Modifier,+n: U32,+at: U32,+i: U32,doc: R.Document,+ctx: Context,+recipe: U32,acc: List<&2,P.Modifier>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Modifier>> -> T) -> T:
  match valid:
    case False{}: next(doc,Fail{761})
    case True{}: modifiers(T,n,at,i,doc,ctx,recipe,m <> acc,next)

def salt(found: Maybe<&2,R.Value>) -> Result<&1,&1,U32,W.U64>:
  match found:
    case Some{R.Value{3,hi,lo,_}}: Done{W.U64{hi,lo}}
    case _: Fail{761}

@unsafe
def group(xs: List<&2,Group>,+salt: W.U64,+fallback: U32) -> U32:
  match xs:
    case Nil{}: fallback
    case Group{+s,id} <> rest: group_same(W.equal(s,salt),id,rest,salt,fallback)

@unsafe
def group_same(+same: Bool,+id: U32,rest: List<&2,Group>,+salt: W.U64,+fallback: U32) -> U32:
  match same:
    case True{}: id
    case False{}: group(rest,salt,fallback)

@unsafe
def recipe_program(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+ctx: Context,+id: U32,+group: U32,+salt: W.U64,next: R.Document -> Result<&1,&1,U32,P.Recipe> -> T) -> T:
  match found:
    case None{}: next(doc,Done{P.Recipe{group,salt,False{},Q.Empty{}}})
    case Some{R.Value{0,_,_,_}}: next(doc,Done{P.Recipe{group,salt,False{},Q.Empty{}}})
    case Some{R.Value{6,n,_,at}}: modifiers(T,n,U32.add(at,3),0,doc,ctx,id,[],doc => result => recipe_result(T,result,doc,group,salt,next))
    case _: next(doc,Fail{761})

@unsafe
def recipe_result(-T: Type,result: Result<&1,&1,U32,Q.Table<P.Modifier>>,doc: R.Document,+group: U32,+salt: W.U64,next: R.Document -> Result<&1,&1,U32,P.Recipe> -> T) -> T:
  match result:
    case Done{p}: next(doc,Done{P.Recipe{group,salt,True{},p}})
    case _: next(doc,Fail{761})

@unsafe
def recipe_fields(-T: Type,values: List<&2,Maybe<&2,R.Value>>,doc: R.Document,+ctx: Context,+id: U32,+groups: List<&2,Group>,next: R.Document -> Result<&1,&1,U32,P.Recipe> -> T) -> T:
  match values:
    case None{} <> s <> p <> Nil{}: recipe_salt(T,salt(s),p,doc,ctx,id,groups,next)
    case s <> _ <> p <> Nil{}: recipe_salt(T,salt(s),p,doc,ctx,id,groups,next)
    case _: next(doc,Fail{761})

@unsafe
def recipe_salt(-T: Type,result: Result<&1,&1,U32,W.U64>,found: Maybe<&2,R.Value>,doc: R.Document,+ctx: Context,+id: U32,+groups: List<&2,Group>,next: R.Document -> Result<&1,&1,U32,P.Recipe> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{761})
    case Done{+salt}: recipe_program(T,found,doc,ctx,id,group(groups,salt,id),salt,next)

@unsafe
def recipes(-T: Type,+n: U32,+at: U32,+i: U32,doc: R.Document,+ctx: Context,+groups: List<&2,Group>,acc: List<&2,P.Recipe>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Recipe>> -> T) -> T:
  match n ctx:
    case 0 _: next(doc,Done{Q.prepared_table(P.Recipe,List.reverse(&2,P.Recipe,acc))})
    case _ Context{keys,_,_,_,_,_}: R.value(T,doc,at,doc => value => R.get(T,doc,U32.inc(at),doc => span =>
      fields(T,[3,4,1],doc,value,keys,[],doc => values => recipe_fields(T,values,doc,ctx,i,groups,doc => result =>
        recipes_more(T,result,U32.sub(n,1),U32.add(at,span),U32.inc(i),doc,ctx,groups,acc,next)))))

@unsafe
def recipes_more(-T: Type,result: Result<&1,&1,U32,P.Recipe>,+n: U32,+at: U32,+i: U32,doc: R.Document,+ctx: Context,+groups: List<&2,Group>,acc: List<&2,P.Recipe>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Recipe>> -> T) -> T:
  match result:
    case Fail{_}: next(doc,Fail{761})
    case Done{+r}: grouped(T,r,n,at,i,doc,ctx,groups,r <> acc,next)

@unsafe
def grouped(-T: Type,+r: P.Recipe,+n: U32,+at: U32,+i: U32,doc: R.Document,+ctx: Context,+groups: List<&2,Group>,acc: List<&2,P.Recipe>,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Recipe>> -> T) -> T:
  match r:
    case P.Recipe{group,salt,_,_}: recipes(T,n,at,i,doc,ctx,Group{salt,group} <> groups,acc,next)

@unsafe
def decorations(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,+ctx: Context,next: R.Document -> Result<&1,&1,U32,Q.Table<P.Recipe>> -> T) -> T:
  match found:
    case Some{R.Value{6,n,_,at}}: recipes(T,n,U32.add(at,3),0,doc,ctx,[],[],next)
    case _: next(doc,Fail{761})

@unsafe
def keys(-T: Type,doc: R.Document,next: R.Document -> D.Keys -> T) -> T:
  D.resolved(T,["decorations","placement","type","placement_salt","salt","chance","map","predicate","max_water_depth",
    "min_inclusive","max_inclusive","min","max","direction","target_condition","allowed_search_condition","max_steps",
    "xz_size","y_size","include_edges","include_interior","materials","heightmap_masks","material_flags","biomes","water","lava","bedrock",
    "count","count_on_every_layer","noise_threshold_count","noise_based_count","in_square","cuboid","heightmap","height_range","environment_scan",
    "offset","block_predicate_filter","surface_water_depth_filter","surface_relative_threshold_filter","biome","select","rarity_filter","random_chance"],doc,[],next)

# Policy assembly is kept separate from the instruction projection below.
@unsafe
def policies(-T: Type,recipes: Result<&1,&1,U32,Q.Table<P.Recipe>>,+permutation: Q.Table<U32>,doc: R.Document,+root: R.Value,+keys: D.Keys,+palette: U32,next: R.Document -> Result<&1,&1,U32,P.Model> -> T) -> T:
  match recipes:
    case Fail{_}: next(doc,Fail{761})
    case Done{+recipes}: PP.prepare(T,doc,root,palette,Q.table_size(P.Recipe,recipes),doc => policy => final_model(T,policy,recipes,permutation,doc,next))

@unsafe
def final_model(-T: Type,policy: Result<&1,&1,U32,P.Policy>,+recipes: Q.Table<P.Recipe>,+permutation: Q.Table<U32>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Model> -> T) -> T:
  match policy:
    case Fail{_}: next(doc,Fail{761})
    case Done{policy}: next(doc,Done{P.Model{recipes,permutation,policy}})

@unsafe
def projected(-T: Type,heights: Result<&1,&1,U32,Q.Table<H.Row>>,counts: C.Model,doc: R.Document,+palette: U32,next: R.Document -> Result<&1,&1,U32,P.Model> -> T) -> T:
  match heights counts:
    case Done{heights} C.Model{+permutation,+counts}: keys(T,doc,doc => +keys => RP.keys(T,doc,doc => +predicates => RI.keys(T,doc,doc => +integers =>
      V.root(T,doc,doc => +root => D.named(T,doc,root,keys,0n,doc => found => decorations(T,found,doc,Context{keys,predicates,integers,counts,heights,palette},doc => recipes =>
        policies(T,recipes,permutation,doc,root,keys,palette,next)))))))
    case _ _: next(doc,Fail{761})

@unsafe
def counted_projection(-T: Type,counts: Result<&1,&1,U32,C.Model>,doc: R.Document,+palette: U32,next: R.Document -> Result<&1,&1,U32,P.Model> -> T) -> T:
  match counts:
    case Fail{_}: next(doc,Fail{761})
    case Done{counts}: RH.prepare(T,doc,doc => heights => projected(T,heights,counts,doc,palette,next))

@unsafe
def palette(-T: Type,found: Maybe<&2,R.Value>,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Model> -> T) -> T:
  match found:
    case Some{R.Value{6,+n,_,_}}: palette_valid(T,Bool.and(U32.is_gt(n,0),U32.is_le(n,65536)),n,doc,next)
    case _: next(doc,Fail{761})

@unsafe
def palette_valid(-T: Type,+valid: Bool,+n: U32,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Model> -> T) -> T:
  match valid:
    case False{}: next(doc,Fail{761})
    case True{}: RC.prepare(T,doc,doc => counts => counted_projection(T,counts,doc,n,next))

@unsafe
def prepare(-T: Type,doc: R.Document,next: R.Document -> Result<&1,&1,U32,P.Model> -> T) -> T:
  V.root(T,doc,doc => root => V.field(T,doc,root,"materials",doc => found => palette(T,found,doc,next)))
