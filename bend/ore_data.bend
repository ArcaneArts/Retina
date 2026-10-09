import Base
import ./density.bend as Q
import ./precise.bend as D
import ./reader.bend as R

# The persistent profile retains one opaque table. Recipe headers, ordered band
# maps and sparse biome memberships are packed in Bend after validation. This
# keeps the stock compiler's captured-argument count independent of recipes.
type Store is Data:
  Store{words: Q.Table<U32>}
type Band is Data:
  Band{minimum: U32,maximum: U32,materials: List<&2,U32>}
type Recipe is Data:
  Recipe{header: List<&2,U32>,bands: List<&2,Band>}

def empty() -> Store:
  Store{Q.prepared_table(U32,[2,0,0,0,6,6])}

def word(store: Store,i: U32) -> U32:
  match store:
    case Store{words}: Q.get(U32,words,i,0)

def recipes(store: Store) -> U32:
  word(store,1)

def biomes(store: Store) -> U32:
  word(store,2)

def palette(store: Store) -> U32:
  word(store,3)

@unsafe
def carveable(+store: Store,+id: U32) -> Bool:
  Bool.and(U32.is_lt(id,palette(store)),U32.is_eq(word(store,U32.add(word(store,5),id)),1))

@unsafe
def flags(table: Q.Table<Bool>) -> List<&2,U32>:
  match table:
    case Q.Empty{}: []
    case Q.Leaf{value}: flag(value) <> Nil{}
    case Q.Branch{_,a,b}: List.append(&2,U32,flags(a),flags(b))

def flag(value: Bool) -> U32:
  match value:
    case False{}: 0
    case True{}: 1

@unsafe
def from_flags(+table: Q.Table<Bool>) -> Store:
  Store{Q.prepared_table(U32,2 <> 0 <> 0 <> Q.table_size(Bool,table) <> 6 <> 6 <> flags(table))}

@unsafe
def slice(n: U32,+store: Store,+at: U32) -> List<&2,U32>:
  match n:
    case 0: []
    case _: word(store,at) <> slice(U32.sub(n,1),store,U32.inc(at))

@unsafe
def with_flags(+store: Store,+old: Store) -> Store:
  Store{Q.prepared_table(U32,List.append(&2,U32,slice(word(store,5),store,0),slice(palette(old),old,word(old,5))))}

def recipe_at(+id: U32) -> U32:
  U32.add(6,U32.mul(id,13))

def biome_at(+store: Store,id: U32) -> U32:
  U32.add(word(store,4),U32.mul(id,2))

@unsafe
def contains(n: U32,+store: Store,+at: U32,+id: U32) -> Bool:
  match n:
    case 0: False{}
    case _: contains_next(U32.is_eq(word(store,at),id),U32.sub(n,1),store,U32.inc(at),id)

@unsafe
def contains_next(found: Bool,n: U32,store: Store,at: U32,id: U32) -> Bool:
  match found:
    case True{}: True{}
    case False{}: contains(n,store,at,id)

@unsafe
def member(+store: Store,biome: U32,id: U32) -> Bool:
  +at = biome_at(store,biome)
  contains(word(store,U32.inc(at)),store,word(store,at),id)

@unsafe
def band(n: U32,+store: Store,+at: U32,+y: U32,+host: U32) -> U32:
  match n:
    case 0: 0
    case _: band_next(Bool.and(Bool.not(D.less(D.integer(y),D.integer(word(store,at)))),
      Bool.not(D.less(D.integer(word(store,U32.inc(at))),D.integer(y)))),U32.sub(n,1),store,at,y,host)

@unsafe
def band_next(found: Bool,n: U32,+store: Store,at: U32,y: U32,host: U32) -> U32:
  match found:
    case True{}: word(store,U32.add(U32.add(at,2),host))
    case False{}: band(n,store,U32.add(at,U32.add(palette(store),2)),y,host)

@unsafe
def replacement(+store: Store,+id: U32,y: U32,host: U32,+random: U32,exposed: Bool) -> U32:
  +at = recipe_at(id)
  replacement_allowed(Bool.not(Bool.and(exposed,F32.is_lt(F32.div(U32.to_f32(U32.shrn(random,8n)),16777216.0),
    R.float(word(store,U32.add(at,2)))))),store,at,y,host)

@unsafe
def replacement_allowed(allowed: Bool,+store: Store,+at: U32,y: U32,host: U32) -> U32:
  match allowed:
    case False{}: 0
    case True{}: band(word(store,U32.add(at,11)),store,word(store,U32.add(at,12)),y,host)

# Build directories before the payload. Order and duplicate membership entries
# are retained; membership tests are set-like, as in the native generator.
@unsafe
def band_words(xs: List<&2,Recipe>,+palette: U32) -> Nat:
  match xs:
    case Nil{}: 0n
    case Recipe{_,bands} <> rest: Nat.add(Nat.mul(List.length(&2,Band,bands),U32.to_nat(U32.add(palette,2))),band_words(rest,palette))

@unsafe
def member_words(xs: List<&2,List<&2,U32>>) -> Nat:
  match xs:
    case Nil{}: 0n
    case ids <> rest: Nat.add(List.length(&2,U32,ids),member_words(rest))

@unsafe
def headers(xs: List<&2,Recipe>,+palette: U32,+offset: U32) -> List<&2,U32>:
  match xs:
    case Nil{}: []
    case Recipe{header,+bands} <> rest: List.append(&2,U32,header,
      U32.from_nat(List.length(&2,Band,bands)) <> offset <>
        headers(rest,palette,U32.add(offset,U32.mul(U32.from_nat(List.length(&2,Band,bands)),U32.add(palette,2)))))

@unsafe
def directories(xs: List<&2,List<&2,U32>>,+offset: U32) -> List<&2,U32>:
  match xs:
    case Nil{}: []
    case +ids <> rest: offset <> U32.from_nat(List.length(&2,U32,ids)) <>
      directories(rest,U32.add(offset,U32.from_nat(List.length(&2,U32,ids))))

@unsafe
def maps(xs: List<&2,Band>) -> List<&2,U32>:
  match xs:
    case Nil{}: []
    case Band{minimum,maximum,materials} <> rest: minimum <> maximum <> List.append(&2,U32,materials,maps(rest))

@unsafe
def payload(xs: List<&2,Recipe>) -> List<&2,U32>:
  match xs:
    case Nil{}: []
    case Recipe{_,bands} <> rest: List.append(&2,U32,maps(bands),payload(rest))

@unsafe
def memberships(xs: List<&2,List<&2,U32>>) -> List<&2,U32>:
  match xs:
    case Nil{}: []
    case ids <> rest: List.append(&2,U32,ids,memberships(rest))

@unsafe
def packed(+layout: U32,+recipes: List<&2,Recipe>,+members: List<&2,List<&2,U32>>,+palette: U32,+base: U32,+bands: U32,+after: U32) -> Store:
  Store{Q.prepared_table(U32,layout <> U32.from_nat(List.length(&2,Recipe,recipes)) <>
    U32.from_nat(List.length(&2,List<&2,U32>,members)) <> palette <> base <> U32.add(after,U32.from_nat(member_words(members))) <>
    List.append(&2,U32,headers(recipes,palette,bands),List.append(&2,U32,directories(members,after),
      List.append(&2,U32,payload(recipes),memberships(members)))))}

@unsafe
def packing(ok: Bool,layout: U32,recipes: List<&2,Recipe>,members: List<&2,List<&2,U32>>,palette: U32,base: U32,bands: U32,after: U32) -> Result<&1,&1,U32,Store>:
  match ok:
    case False{}: Fail{753}
    case True{}: Done{packed(layout,recipes,members,palette,base,bands,after)}

@unsafe
def prepare(layout: U32,+recipes: List<&2,Recipe>,+members: List<&2,List<&2,U32>>,+palette: U32) -> Result<&1,&1,U32,Store>:
  +base = U32.add(6,U32.mul(U32.from_nat(List.length(&2,Recipe,recipes)),13))
  +bands = U32.add(base,U32.mul(U32.from_nat(List.length(&2,List<&2,U32>,members)),2))
  +after = Nat.add(U32.to_nat(bands),band_words(recipes,palette))
  packing(Nat.is_le(Nat.add(Nat.add(after,member_words(members)),U32.to_nat(palette)),4194304n),layout,recipes,members,palette,base,bands,U32.from_nat(after))
