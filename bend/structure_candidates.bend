import Base
import ./density.bend as Q
import ./structure_registry.bend as M
import ./structure_geometry.bend as G
import ./structure_random.bend as R
import ./structure_plan.bend as Plan
import ./placement_random.bend as Mix
import ./registry.bend as Registry
import ./word64.bend as W
import ./ore_random.bend as O

type Candidate is Data:
  Candidate{x: U32,z: U32}
type Start is Data:
  Start{set: U32,x: U32,z: U32}
type Choice is Data:
  Choice{state: W.U64,definition: Maybe<&2,U32>}

@unsafe
def candidate(+seed: W.U64,+x: U32,+z: U32,+m: M.Model,+placement: M.Node) -> Candidate:
  +spacing = M.int(M.get(m,placement,36n),32)
  +separation = M.int(M.get(m,placement,37n),8)
  +gx = G.floor_div(x,spacing)
  +gz = G.floor_div(z,spacing)
  +limit = U32.sub(spacing,separation)
  candidate_x(spread(R.new(R.salted(seed,gx,gz,O.signed(M.int(M.get(m,placement,38n),0)))),limit,M.named(m,M.get(m,placement,39n),40n)),
    limit,M.named(m,M.get(m,placement,39n),40n),U32.mul(gx,spacing),U32.mul(gz,spacing))

@unsafe
def spread(+state: W.U64,+limit: U32,+triangular: Bool) -> R.Draw:
  spread_first(R.integer(state,limit),limit,triangular)

@unsafe
def spread_first(+draw: R.Draw,+limit: U32,+triangular: Bool) -> R.Draw:
  match draw triangular:
    case R.Draw{state,v} True{}: spread_second(R.integer(state,limit),v)
    case _ False{}: draw

@unsafe
def spread_second(+draw: R.Draw,+a: U32) -> R.Draw:
  match draw:
    case R.Draw{state,b}: R.Draw{state,U32.div(U32.add(a,b),2)}

@unsafe
def candidate_x(+draw: R.Draw,+limit: U32,+triangular: Bool,+x: U32,+z: U32) -> Candidate:
  match draw:
    case R.Draw{state,v}: candidate_z(spread(state,limit,triangular),U32.add(x,v),z)

@unsafe
def candidate_z(+draw: R.Draw,+x: U32,+z: U32) -> Candidate:
  match draw:
    case R.Draw{_,v}: Candidate{x,U32.add(z,v)}

# Compare an exact 53-bit uniform integer with the original registry F64.
# Ceil(p*2^53) preserves the strict comparison, including subnormal p and
# values just above a representable draw; no F32 probability rounding.
@unsafe
def threshold(+value: Registry.Value) -> W.U64:
  match value:
    case Registry.Value{4,+hi,lo,_}: threshold_sign(U32.is_ge(hi,2147483648),hi,lo)
    case Registry.Value{3,+hi,+lo,_}: W.U64{U32.mul(2097152,W.flag(Bool.and(U32.is_lt(hi,2147483648),Bool.not(Bool.and(U32.is_zero(hi),U32.is_zero(lo)))))),0}
    case _: W.U64{2097152,0}

@unsafe
def threshold_sign(+negative: Bool,+hi: U32,+lo: U32) -> W.U64:
  match negative:
    case True{}: W.U64{0,0}
    case False{}: threshold_exp(U32.shrn(hi,20n),W.U64{U32.and(hi,1048575),lo})

@unsafe
def threshold_exp(+exp: U32,+mantissa: W.U64) -> W.U64:
  threshold_large(U32.is_ge(exp,1023),exp,mantissa)

@unsafe
def threshold_large(+large: Bool,+exp: U32,+mantissa: W.U64) -> W.U64:
  match large:
    case True{}: W.U64{2097152,0}
    case False{}: threshold_normal(U32.is_gt(exp,0),exp,mantissa)

@unsafe
def threshold_normal(+normal: Bool,+exp: U32,+mantissa: W.U64) -> W.U64:
  match normal:
    case False{}: W.U64{0,W.flag(Bool.not(W.equal(mantissa,W.U64{0,0})))}
    case True{}: threshold_shift(U32.sub(1022,exp),W.add(mantissa,W.U64{1048576,0}))

@unsafe
def threshold_shift(+shift: U32,+mantissa: W.U64) -> W.U64:
  threshold_small(U32.is_lt(shift,64),shift,mantissa)

@unsafe
def threshold_small(+small: Bool,+shift: U32,+mantissa: W.U64) -> W.U64:
  match small:
    case False{}: W.U64{0,1}
    case True{}: W.add(W.shr(mantissa,shift),W.U64{0,W.flag(Bool.not(W.equal(mantissa,W.shl(W.shr(mantissa,shift),shift))))})

@unsafe
def frequency(+m: M.Model,+p: M.Node,+seed: W.U64,+x: U32,+z: U32) -> Bool:
  frequency_bound(threshold(M.value(M.get(m,p,41n))),m,p,seed,x,z)

@unsafe
def frequency_bound(+bound: W.U64,+m: M.Model,+p: M.Node,+seed: W.U64,+x: U32,+z: U32) -> Bool:
  frequency_zero(W.equal(bound,W.U64{0,0}),bound,m,p,seed,x,z)

@unsafe
def frequency_zero(+zero: Bool,+bound: W.U64,+m: M.Model,+p: M.Node,+seed: W.U64,+x: U32,+z: U32) -> Bool:
  match zero:
    case True{}: False{}
    case False{}: frequency_full(W.equal(bound,W.U64{2097152,0}),bound,m,p,seed,x,z)

@unsafe
def frequency_full(+full: Bool,+bound: W.U64,+m: M.Model,+p: M.Node,+seed: W.U64,+x: U32,+z: U32) -> Bool:
  match full:
    case True{}: True{}
    case False{}: frequency_one(M.named(m,M.get(m,p,42n),43n),bound,m,p,seed,x,z)

@unsafe
def frequency_one(+one: Bool,+bound: W.U64,+m: M.Model,+p: M.Node,+seed: W.U64,+x: U32,+z: U32) -> Bool:
  match one:
    case True{}: legacy_one(R.bits(R.new(W.xor(O.signed(U32.xor(G.floor_div(x,16),U32.mul(G.floor_div(z,16),16))),seed)),32),M.value(M.get(m,p,41n)))
    case False{}: frequency_two(M.named(m,M.get(m,p,42n),44n),bound,m,p,seed,x,z)

@unsafe
def legacy_one(+draw: R.Draw,+p: Registry.Value) -> Bool:
  match draw:
    case R.Draw{state,_}: legacy_probability(p,state)

@unsafe
def legacy_probability(value: Registry.Value,state: W.U64) -> Bool:
  match value:
    case Registry.Value{4,+hi,lo,_}: legacy_draw(R.integer(state,inverse(U32.shrn(hi,20n),W.U64{U32.and(hi,1048575),lo})))
    case _: False{}

# F64 reciprocal rounding followed by Rust's saturating U32 conversion.
@unsafe
def inverse(+exp: U32,mantissa: W.U64) -> U32:
  match exp:
    case 0: inverse_bits(1074,mantissa,W.U64{0,1},0)
    case _: inverse_bits(U32.sub(1075,exp),W.add(mantissa,W.U64{1048576,0}),W.U64{0,1},0)

@unsafe
def inverse_bits(n: U32,+denominator: W.U64,+remainder: W.U64,+q: U32) -> U32:
  match n:
    case 0: inverse_done(q,denominator,remainder)
    case _: inverse_room(U32.is_lt(q,2147483648),U32.sub(n,1),denominator,W.shl(remainder,1),q)

@unsafe
def inverse_room(room: Bool,n: U32,+denominator: W.U64,+remainder: W.U64,q: U32) -> U32:
  match room:
    case False{}: 4294967295
    case True{}: inverse_reduce(Bool.not(Mix.less(remainder,denominator)),n,denominator,remainder,q)

@unsafe
def inverse_reduce(subtract: Bool,n: U32,+denominator: W.U64,remainder: W.U64,q: U32) -> U32:
  match subtract:
    case False{}: inverse_bits(n,denominator,remainder,U32.mul(q,2))
    case True{}: inverse_bits(n,denominator,W.sub(remainder,denominator),U32.inc(U32.mul(q,2)))

@unsafe
def legacy_draw(+draw: R.Draw) -> Bool:
  match draw:
    case R.Draw{_,v}: U32.is_zero(v)

@unsafe
def frequency_two(+two: Bool,+bound: W.U64,+m: M.Model,+p: M.Node,+seed: W.U64,+x: U32,+z: U32) -> Bool:
  match two:
    case True{}: float_draw(R.bits(R.new(R.salted(seed,x,z,W.U64{0,10387320})),24),bound)
    case False{}: frequency_three(M.named(m,M.get(m,p,42n),45n),bound,m,p,seed,x,z)

@unsafe
def frequency_three(+three: Bool,+bound: W.U64,+m: M.Model,+p: M.Node,+seed: W.U64,+x: U32,+z: U32) -> Bool:
  match three:
    case True{}: legacy_long_a(R.long(R.new(seed)),bound,seed,x,z)
    case False{}: float_draw(R.bits(R.new(R.salted(seed,M.int(M.get(m,p,38n),0),x,O.signed(z))),24),bound)

@unsafe
def float_draw(+draw: R.Draw,+bound: W.U64) -> Bool:
  match draw:
    case R.Draw{_,v}: Mix.less(W.shl(W.U64{0,v},29),bound)

@unsafe
def legacy_long_a(+draw: R.Long,+bound: W.U64,+seed: W.U64,+x: U32,+z: U32) -> Bool:
  match draw:
    case R.Long{state,a}: legacy_long_b(R.long(state),a,bound,seed,x,z)

@unsafe
def legacy_long_b(+draw: R.Long,+a: W.U64,+bound: W.U64,+seed: W.U64,+x: U32,+z: U32) -> Bool:
  match draw:
    case R.Long{_,b}: double_high(R.bits(R.new(W.xor(W.xor(W.mul(O.signed(x),a),W.mul(O.signed(z),b)),seed)),26),bound)

@unsafe
def double_high(+draw: R.Draw,+bound: W.U64) -> Bool:
  match draw:
    case R.Draw{state,a}: double_low(R.bits(state,27),a,bound)

@unsafe
def double_low(+draw: R.Draw,+a: U32,+bound: W.U64) -> Bool:
  match draw:
    case R.Draw{_,b}: Mix.less(W.add(W.shl(W.U64{0,a},27),W.U64{0,b}),bound)

@unsafe
def set(+m: M.Model,+id: U32) -> M.Node:
  M.item(Plan.array(m,4n),id)

@unsafe
def seen(+xs: List<&2,U32>,+id: U32) -> Bool:
  match xs:
    case Nil{}: False{}
    case x <> rest: Bool.or(U32.is_eq(x,id),seen(rest,id))

@unsafe
def permitted(+m: M.Model,+id: U32,+seed: W.U64,+x: U32,+z: U32,+visited: List<&2,U32>) -> Bool:
  permitted_at(candidate(seed,x,z,m,M.get(m,set(m,id),34n)),m,id,seed,x,z,visited)

@unsafe
def permitted_at(+at: Candidate,+m: M.Model,+id: U32,+seed: W.U64,+x: U32,+z: U32,+visited: List<&2,U32>) -> Bool:
  match at:
    case Candidate{a,b}: permitted_candidate(Bool.and(Bool.and(U32.is_eq(a,x),U32.is_eq(b,z)),Bool.not(seen(visited,id))),m,id,seed,x,z,visited)

@unsafe
def permitted_candidate(+valid: Bool,+m: M.Model,+id: U32,+seed: W.U64,+x: U32,+z: U32,+visited: List<&2,U32>) -> Bool:
  match valid:
    case False{}: False{}
    case True{}: permitted_frequency(frequency(m,M.get(m,set(m,id),34n),seed,x,z),m,id,seed,x,z,visited)

@unsafe
def find_set(+m: M.Model,+name: U32,+i: U32,+n: U32) -> U32:
  match n:
    case 0: 4294967295
    case _: find_set_equal(U32.is_eq(M.text(M.get(m,set(m,i),5n)),name),m,name,i,n)

@unsafe
def find_set_equal(+equal: Bool,+m: M.Model,+name: U32,+i: U32,+n: U32) -> U32:
  match equal:
    case True{}: i
    case False{}: find_set(m,name,U32.inc(i),U32.sub(n,1))

@unsafe
def permitted_frequency(+ok: Bool,+m: M.Model,+id: U32,+seed: W.U64,+x: U32,+z: U32,+visited: List<&2,U32>) -> Bool:
  match ok:
    case False{}: False{}
    case True{}: exclusion(m,M.get(m,M.get(m,set(m,id),34n),46n),seed,x,z,id <> visited)

@unsafe
def exclusion(+m: M.Model,+v: M.Node,+seed: W.U64,+x: U32,+z: U32,+visited: List<&2,U32>) -> Bool:
  exclusion_set(find_set(m,M.text(M.get(m,v,47n)),0,M.count(Plan.array(m,4n))),m,M.int(M.get(m,v,48n),0),seed,x,z,visited)

@unsafe
def exclusion_set(+other: U32,+m: M.Model,+count: U32,+seed: W.U64,+x: U32,+z: U32,+visited: List<&2,U32>) -> Bool:
  match other:
    case 4294967295: True{}
    case _: exclusion_spacing(m,other,count,seed,x,z,visited,M.int(M.get(m,M.get(m,set(m,other),34n),36n),32))

@unsafe
def exclusion_spacing(+m: M.Model,+other: U32,+count: U32,+seed: W.U64,+x: U32,+z: U32,+visited: List<&2,U32>,+spacing: U32) -> Bool:
  excluded_cells(m,other,seed,visited,spacing,U32.sub(x,count),U32.sub(z,count),U32.add(x,count),U32.add(z,count),G.floor_div(U32.sub(x,count),spacing),G.floor_div(U32.sub(z,count),spacing))

@unsafe
def excluded_cells(+m: M.Model,+other: U32,+seed: W.U64,+visited: List<&2,U32>,+spacing: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+gx: U32,+gz: U32) -> Bool:
  excluded_at(candidate(seed,U32.mul(gx,spacing),U32.mul(gz,spacing),m,M.get(m,set(m,other),34n)),m,other,seed,visited,spacing,x0,z0,x1,z1,gx,gz)

@unsafe
def excluded_at(+at: Candidate,+m: M.Model,+other: U32,+seed: W.U64,+visited: List<&2,U32>,+spacing: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+gx: U32,+gz: U32) -> Bool:
  match at:
    case Candidate{+x,+z}: excluded_in(Bool.not(Bool.or(Bool.or(G.lt(x,x0),G.lt(z,z0)),Bool.or(G.lt(x1,x),G.lt(z1,z)))),m,other,seed,visited,spacing,x0,z0,x1,z1,gx,gz,x,z)

@unsafe
def excluded_in(+inside: Bool,+m: M.Model,+other: U32,+seed: W.U64,+visited: List<&2,U32>,+spacing: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+gx: U32,+gz: U32,+x: U32,+z: U32) -> Bool:
  match inside:
    case False{}: excluded_next(False{},m,other,seed,visited,spacing,x0,z0,x1,z1,gx,gz)
    case True{}: excluded_next(permitted(m,other,seed,x,z,visited),m,other,seed,visited,spacing,x0,z0,x1,z1,gx,gz)

@unsafe
def excluded_next(+hit: Bool,+m: M.Model,+other: U32,+seed: W.U64,+visited: List<&2,U32>,+spacing: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+gx: U32,+gz: U32) -> Bool:
  match hit:
    case True{}: False{}
    case False{}: excluded_row(U32.is_eq(gx,G.floor_div(x1,spacing)),m,other,seed,visited,spacing,x0,z0,x1,z1,gx,gz)

@unsafe
def excluded_row(+end: Bool,+m: M.Model,+other: U32,+seed: W.U64,+visited: List<&2,U32>,+spacing: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+gx: U32,+gz: U32) -> Bool:
  match end:
    case False{}: excluded_cells(m,other,seed,visited,spacing,x0,z0,x1,z1,U32.inc(gx),gz)
    case True{}: excluded_end(U32.is_eq(gz,G.floor_div(z1,spacing)),m,other,seed,visited,spacing,x0,z0,x1,z1,gz)

@unsafe
def excluded_end(+end: Bool,+m: M.Model,+other: U32,+seed: W.U64,+visited: List<&2,U32>,+spacing: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+gz: U32) -> Bool:
  match end:
    case True{}: True{}
    case False{}: excluded_cells(m,other,seed,visited,spacing,x0,z0,x1,z1,G.floor_div(x0,spacing),U32.inc(gz))

@unsafe
def start_seed(+m: M.Model,+p: M.Node,+seed: W.U64,+x: U32,+z: U32) -> W.U64:
  Mix.mixed(W.xor(W.xor(W.xor(seed,W.mul(O.signed(x),W.U64{79,2570712328})),W.mul(O.signed(z),W.U64{30,4048968661})),O.signed(M.int(M.get(m,p,38n),0))))

# The caller supplies actual surface/underground GPU biome samples per
# definition. Weighted rejection consumes the same stream used by root layout.
@unsafe
def choose_definition(+m: M.Model,+set_id: U32,+seed: W.U64,+x: U32,+z: U32,+biomes: Q.Table<U32>) -> Choice:
  +entries = M.get(m,set(m,set_id),30n)
  choose_remaining(m,entries,Plan.indices(0,M.count(entries)),R.new(start_seed(m,M.get(m,set(m,set_id),34n),seed,x,z)),biomes)

@unsafe
def remaining_total(+m: M.Model,+entries: M.Node,+ids: List<&2,U32>,+acc: U32) -> U32:
  match ids:
    case Nil{}: acc
    case id <> rest: remaining_total(m,entries,rest,U32.add(acc,Plan.weight(m,M.item(entries,id))))

@unsafe
def choose_remaining(+m: M.Model,+entries: M.Node,+ids: List<&2,U32>,+state: W.U64,+biomes: Q.Table<U32>) -> Choice:
  choose_sum(remaining_total(m,entries,ids,0),m,entries,ids,state,biomes)

@unsafe
def choose_sum(+sum: U32,+m: M.Model,+entries: M.Node,+ids: List<&2,U32>,+state: W.U64,+biomes: Q.Table<U32>) -> Choice:
  match sum:
    case 0: Choice{state,None{}}
    case _: choose_pick(R.integer(state,sum),m,entries,ids,biomes)

@unsafe
def choose_pick(+draw: R.Draw,+m: M.Model,+entries: M.Node,+ids: List<&2,U32>,+biomes: Q.Table<U32>) -> Choice:
  match draw:
    case R.Draw{state,pick}: choose_id(remaining_pick(m,entries,ids,pick),m,entries,ids,state,biomes)

@unsafe
def remaining_pick(+m: M.Model,+entries: M.Node,+ids: List<&2,U32>,+pick: U32) -> U32:
  match ids:
    case Nil{}: 4294967295
    case id <> rest: remaining_here(U32.is_lt(pick,Plan.weight(m,M.item(entries,id))),m,entries,id,rest,pick)

@unsafe
def remaining_here(+here: Bool,+m: M.Model,+entries: M.Node,+id: U32,+rest: List<&2,U32>,+pick: U32) -> U32:
  match here:
    case True{}: id
    case False{}: remaining_pick(m,entries,rest,U32.sub(pick,Plan.weight(m,M.item(entries,id))))

@unsafe
def remove(+ids: List<&2,U32>,+id: U32) -> List<&2,U32>:
  match ids:
    case Nil{}: []
    case +i <> rest: remove_equal(U32.is_eq(i,id),i,rest,id)

@unsafe
def remove_equal(+equal: Bool,+i: U32,+rest: List<&2,U32>,+id: U32) -> List<&2,U32>:
  match equal:
    case True{}: rest
    case False{}: i <> remove(rest,id)

@unsafe
def choose_id(+id: U32,+m: M.Model,+entries: M.Node,+ids: List<&2,U32>,+state: W.U64,+biomes: Q.Table<U32>) -> Choice:
  choose_biome(M.int(M.get(m,M.item(entries,id),35n),0),m,entries,remove(ids,id),state,biomes)

@unsafe
def choose_biome(+definition: U32,+m: M.Model,+entries: M.Node,+ids: List<&2,U32>,+state: W.U64,+biomes: Q.Table<U32>) -> Choice:
  +allowed = M.get(m,M.item(Plan.array(m,2n),definition),32n)
  choose_accepted(biome_present(allowed,0,M.count(allowed),biome_sample(biomes,definition)),definition,m,entries,ids,state,biomes)

@unsafe
def biome_present(+allowed: M.Node,+i: U32,+n: U32,+biome: U32) -> Bool:
  match n:
    case 0: False{}
    case _: Bool.or(U32.is_eq(M.int(M.item(allowed,i),4294967295),biome),biome_present(allowed,U32.inc(i),U32.sub(n,1),biome))

@unsafe
def choose_accepted(+accepted: Bool,+definition: U32,+m: M.Model,+entries: M.Node,+ids: List<&2,U32>,+state: W.U64,+biomes: Q.Table<U32>) -> Choice:
  match accepted:
    case True{}: Choice{state,Some{definition}}
    case False{}: choose_remaining(m,entries,ids,state,biomes)

@unsafe
def definition_reach(+m: M.Model,+definition: M.Node) -> U32:
  definition_jigsaw(M.named(m,M.get(m,definition,31n),49n),m,definition)

@unsafe
def template_reach(+m: M.Model,+part: M.Node) -> U32:
  +size = M.get(m,G.template(m,part),6n)
  U32.max(M.int(M.item(size,0),0),M.int(M.item(size,2),0))

@unsafe
def definition_jigsaw(+jigsaw: Bool,+m: M.Model,+d: M.Node) -> U32:
  match jigsaw:
    case False{}: template_reach(m,d)
    case True{}: pool_reach(m,M.get(m,Plan.pool(m,M.text(M.get(m,d,19n))),30n),0,Plan.distance(m,d))

@unsafe
def pool_reach(+m: M.Model,+entries: M.Node,+i: U32,+distance: U32) -> U32:
  pool_reach_more(U32.is_lt(i,M.count(entries)),m,entries,i,distance)

@unsafe
def pool_reach_more(+more: Bool,+m: M.Model,+entries: M.Node,+i: U32,+distance: U32) -> U32:
  match more:
    case False{}: distance
    case True{}: U32.max(U32.add(distance,parts_reach(m,M.get(m,M.item(entries,i),24n),0)),pool_reach(m,entries,U32.inc(i),distance))

@unsafe
def parts_reach(+m: M.Model,+parts: M.Node,+i: U32) -> U32:
  parts_more(U32.is_lt(i,M.count(parts)),m,parts,i)

@unsafe
def parts_more(+more: Bool,+m: M.Model,+parts: M.Node,+i: U32) -> U32:
  match more:
    case False{}: 0
    case True{}: U32.max(template_reach(m,M.item(parts,i)),parts_reach(m,parts,U32.inc(i)))

@unsafe
def set_reach(+m: M.Model,+entries: M.Node,+i: U32) -> U32:
  set_more(U32.is_lt(i,M.count(entries)),m,entries,i)

@unsafe
def set_more(+more: Bool,+m: M.Model,+entries: M.Node,+i: U32) -> U32:
  match more:
    case False{}: 0
    case True{}: U32.max(definition_reach(m,M.item(Plan.array(m,2n),M.int(M.get(m,M.item(entries,i),35n),0))),set_reach(m,entries,U32.inc(i)))

@unsafe
def region(+m: M.Model,+seed: W.U64,+x: U32,+z: U32,+side: U32) -> List<&2,Start>:
  region_sets(m,seed,x,z,side,0,M.count(Plan.array(m,4n)),[])

@unsafe
def region_sets(+m: M.Model,+seed: W.U64,+x: U32,+z: U32,+side: U32,+id: U32,+n: U32,+acc: List<&2,Start>) -> List<&2,Start>:
  match n:
    case 0: List.reverse(&2,Start,acc)
    case _: region_sets(m,seed,x,z,side,U32.inc(id),U32.sub(n,1),region_halo(m,seed,x,z,side,id,
      U32.inc(U32.div(U32.add(set_reach(m,M.get(m,set(m,id),30n),0),15),16)),acc))

@unsafe
def region_halo(+m: M.Model,+seed: W.U64,+x: U32,+z: U32,+side: U32,+id: U32,+halo: U32,+acc: List<&2,Start>) -> List<&2,Start>:
  region_spacing(m,seed,id,U32.sub(x,halo),U32.sub(z,halo),U32.add(U32.add(x,side),halo),U32.add(U32.add(z,side),halo),
    M.int(M.get(m,M.get(m,set(m,id),34n),36n),32),acc)

@unsafe
def region_spacing(+m: M.Model,+seed: W.U64,+id: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+spacing: U32,+acc: List<&2,Start>) -> List<&2,Start>:
  region_cells(m,seed,id,x0,z0,x1,z1,spacing,G.floor_div(x0,spacing),G.floor_div(z0,spacing),acc)

@unsafe
def region_cells(+m: M.Model,+seed: W.U64,+id: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+spacing: U32,+gx: U32,+gz: U32,+acc: List<&2,Start>) -> List<&2,Start>:
  region_at(candidate(seed,U32.mul(gx,spacing),U32.mul(gz,spacing),m,M.get(m,set(m,id),34n)),m,seed,id,x0,z0,x1,z1,spacing,gx,gz,acc)

@unsafe
def region_at(+at: Candidate,+m: M.Model,+seed: W.U64,+id: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+spacing: U32,+gx: U32,+gz: U32,+acc: List<&2,Start>) -> List<&2,Start>:
  match at:
    case Candidate{+x,+z}: region_inside(Bool.not(Bool.or(Bool.or(G.lt(x,x0),G.lt(z,z0)),Bool.or(G.lt(x1,x),G.lt(z1,z)))),m,seed,id,x0,z0,x1,z1,spacing,gx,gz,x,z,acc)

@unsafe
def region_inside(+inside: Bool,+m: M.Model,+seed: W.U64,+id: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+spacing: U32,+gx: U32,+gz: U32,+x: U32,+z: U32,+acc: List<&2,Start>) -> List<&2,Start>:
  match inside:
    case False{}: region_next(m,seed,id,x0,z0,x1,z1,spacing,gx,gz,acc)
    case True{}: region_accepted(permitted(m,id,seed,x,z,[]),m,seed,id,x0,z0,x1,z1,spacing,gx,gz,x,z,acc)

@unsafe
def region_accepted(+ok: Bool,+m: M.Model,+seed: W.U64,+id: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+spacing: U32,+gx: U32,+gz: U32,+x: U32,+z: U32,+acc: List<&2,Start>) -> List<&2,Start>:
  match ok:
    case False{}: region_next(m,seed,id,x0,z0,x1,z1,spacing,gx,gz,acc)
    case True{}: region_next(m,seed,id,x0,z0,x1,z1,spacing,gx,gz,Start{id,x,z} <> acc)

@unsafe
def region_next(+m: M.Model,+seed: W.U64,+id: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+spacing: U32,+gx: U32,+gz: U32,+acc: List<&2,Start>) -> List<&2,Start>:
  region_row(U32.is_eq(gx,G.floor_div(x1,spacing)),m,seed,id,x0,z0,x1,z1,spacing,gx,gz,acc)

@unsafe
def region_row(+end: Bool,+m: M.Model,+seed: W.U64,+id: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+spacing: U32,+gx: U32,+gz: U32,+acc: List<&2,Start>) -> List<&2,Start>:
  match end:
    case False{}: region_cells(m,seed,id,x0,z0,x1,z1,spacing,U32.inc(gx),gz,acc)
    case True{}: region_end(U32.is_eq(gz,G.floor_div(z1,spacing)),m,seed,id,x0,z0,x1,z1,spacing,gz,acc)

@unsafe
def region_end(+end: Bool,+m: M.Model,+seed: W.U64,+id: U32,+x0: U32,+z0: U32,+x1: U32,+z1: U32,+spacing: U32,+gz: U32,+acc: List<&2,Start>) -> List<&2,Start>:
  match end:
    case True{}: acc
    case False{}: region_cells(m,seed,id,x0,z0,x1,z1,spacing,G.floor_div(x0,spacing),U32.inc(gz),acc)

@unsafe
def inverse_done(+q: U32,+denominator: W.U64,+remainder: W.U64) -> U32:
  inverse_roundable(Bool.and(U32.is_ne(q,4294967295),Bool.not(W.equal(remainder,W.U64{0,0}))),q,denominator,remainder)

@unsafe
def inverse_roundable(roundable: Bool,+q: U32,+denominator: W.U64,remainder: W.U64) -> U32:
  match roundable:
    case False{}: q
    case True{}:
      +next = U32.inc(q)
      +shift = U32.add(U32.sub(53,U32.from_nat(U32.log2(next))),W.flag(U32.is_zero(U32.and(next,U32.sub(next,1)))))
      U32.add(q,W.flag(Bool.not(Mix.less(W.shr(denominator,shift),W.sub(denominator,remainder)))))

@unsafe
def biome_sample(+biomes: Q.Table<U32>,+id: U32) -> U32:
  biome_in_range(U32.is_lt(id,Q.table_size(U32,biomes)),biomes,id)

@unsafe
def biome_in_range(valid: Bool,biomes: Q.Table<U32>,id: U32) -> U32:
  match valid:
    case False{}: 4294967295
    case True{}: Q.get(U32,biomes,id,4294967295)
