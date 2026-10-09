import Base
import ./density.bend as Q
import ./int_provider.bend as I
import ./placement_data.bend as P
import ./placement_world.bend as E
import ./placement_checks.bend as C
import ./placement_random.bend as R
import ./provider_noise.bend as N
import ./word64.bend as W
import ./precise.bend as D

type Provider is Data:
  State{material: U32}
  Weighted{entries: List<&2,Entry>,total: W.U64}
  Randomized{source: Provider,values: I.Provider,variants: List<&2,Transform>}
  Rules{rules: List<&2,Rule>,fallback: Maybe<&2,Provider>}
  Rotated{source: Provider,direction: Maybe<&2,U32>,variants: List<&2,Transform>}
  Copy{source: Provider,variants: List<&2,Transform>}
  RandomBlocks{states: Q.Table<U32>}
  Noise{program: U32,states: Q.Table<U32>}
  Dual{fast: U32,slow: U32,minimum: U32,maximum: U32,states: Q.Table<U32>}
  Threshold{program: U32,threshold: F32,chance: F32,default: U32,low: Q.Table<U32>,high: Q.Table<U32>}
type Entry is Data:
  Entry{weight: U32,provider: Provider}
type Rule is Data:
  Rule{predicate: P.Predicate,provider: Provider}
type Replacement is Data:
  Replacement{current: U32,material: U32}
type Transform is Data:
  Transform{source: U32,minimum: U32,states: Q.Table<U32>,passthrough: Bool,replacements: List<&2,Replacement>}
type Sample is Data:
  Sample{state: W.U64,material: Maybe<&2,U32>}

@unsafe
def total(entries: List<&2,Entry>,+acc: W.U64) -> W.U64:
  match entries:
    case Nil{}: acc
    case Entry{weight,_} <> rest: total(rest,W.add(acc,W.U64{0,weight}))

@unsafe
def nullable(+provider: Provider) -> Bool:
  match provider:
    case Rules{_,None{}}: True{}
    case Rules{_,Some{fallback}}: nullable(fallback)
    case RandomBlocks{states}: U32.is_zero(Q.table_size(U32,states))
    case _: False{}

@unsafe
def entries_current(entries: List<&2,Entry>) -> Bool:
  match entries:
    case Nil{}: False{}
    case Entry{_,p} <> rest: Bool.or(reads_current(p),entries_current(rest))

@unsafe
def rules_current(rules: List<&2,Rule>,fallback: Maybe<&2,Provider>) -> Bool:
  match rules fallback:
    case Nil{} None{}: False{}
    case Nil{} Some{p}: optional_current(p)
    case Rule{_,p} <> rest _: Bool.or(optional_current(p),rules_current(rest,fallback))

@unsafe
def optional_current(+provider: Provider) -> Bool:
  match provider:
    case Randomized{source,_,_}: reads_current(source)
    case Rotated{source,_,_}: reads_current(source)
    case Copy{source,_}: reads_current(source)
    case Weighted{entries,_}: entries_current(entries)
    case Rules{rules,fallback}: rules_current(rules,fallback)
    case _: False{}

@unsafe
def reads_current(+provider: Provider) -> Bool:
  Bool.or(nullable(provider),optional_current(provider))

@unsafe
def table_states(+states: Q.Table<U32>,+palette: U32) -> Bool:
  match states:
    case Q.Empty{}: True{}
    case Q.Leaf{x}: U32.is_lt(x,palette)
    case Q.Branch{_,a,b}: Bool.and(table_states(a,palette),table_states(b,palette))

@unsafe
def nonempty(+states: Q.Table<U32>,+palette: U32) -> Bool:
  Bool.and(U32.is_gt(Q.table_size(U32,states),0),table_states(states,palette))

@unsafe
def find(transforms: List<&2,Transform>,+state: U32) -> Maybe<&2,Transform>:
  match transforms:
    case Nil{}: None{}
    case +row <> rest: find_row(row,rest,state)

@unsafe
def find_row(+row: Transform,rest: List<&2,Transform>,+state: U32) -> Maybe<&2,Transform>:
  match row:
    case Transform{source,_,_,_,_}: find_equal(U32.is_eq(source,state),row,rest,state)

@unsafe
def find_equal(equal: Bool,row: Transform,rest: List<&2,Transform>,state: U32) -> Maybe<&2,Transform>:
  match equal:
    case True{}: Some{row}
    case False{}: find(rest,state)

@unsafe
def transform_covers(found: Maybe<&2,Transform>,+values: I.Provider,+kind: U32) -> Bool:
  match found:
    case None{}: False{}
    case Some{Transform{_,+minimum,+states,+pass,_}}:
      Bool.or(U32.is_ne(kind,0),Bool.or(pass,I.within(values,minimum,U32.add(minimum,U32.sub(Q.table_size(U32,states),1)))))

@unsafe
def outputs_covered(+provider: Provider,+transforms: List<&2,Transform>,+values: I.Provider,+kind: U32) -> Bool:
  outputs_each(outputs(provider),transforms,values,kind)

@unsafe
def outputs_each(xs: List<&2,U32>,+transforms: List<&2,Transform>,+values: I.Provider,+kind: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case id <> rest: Bool.and(transform_covers(find(transforms,id),values,kind),outputs_each(rest,transforms,values,kind))

@unsafe
def table_list(states: Q.Table<U32>,acc: List<&2,U32>) -> List<&2,U32>:
  match states:
    case Q.Empty{}: acc
    case Q.Leaf{x}: x <> acc
    case Q.Branch{_,a,b}: table_list(a,table_list(b,acc))

@unsafe
def transformed_outputs(xs: List<&2,Transform>) -> List<&2,U32>:
  match xs:
    case Nil{}: []
    case Transform{_,_,states,_,_} <> rest: table_list(states,transformed_outputs(rest))

@unsafe
def weighted_outputs(xs: List<&2,Entry>) -> List<&2,U32>:
  match xs:
    case Nil{}: []
    case Entry{_,p} <> rest: List.append(&2,U32,outputs(p),weighted_outputs(rest))

@unsafe
def rule_outputs(xs: List<&2,Rule>,fallback: Maybe<&2,Provider>) -> List<&2,U32>:
  match xs fallback:
    case Nil{} None{}: []
    case Nil{} Some{p}: outputs(p)
    case Rule{_,p} <> rest _: List.append(&2,U32,outputs(p),rule_outputs(rest,fallback))

@unsafe
def outputs(provider: Provider) -> List<&2,U32>:
  match provider:
    case State{x}: [x]
    case Weighted{entries,_}: weighted_outputs(entries)
    case Randomized{_,_,variants}: transformed_outputs(variants)
    case Rotated{_,_,variants}: transformed_outputs(variants)
    case Copy{_,variants}: transformed_outputs(variants)
    case Rules{rules,fallback}: rule_outputs(rules,fallback)
    case RandomBlocks{states}: table_list(states,[])
    case Noise{_,states}: table_list(states,[])
    case Dual{_,_,_,_,states}: table_list(states,[])
    case Threshold{_,_,_,default,low,high}: default <> table_list(low,table_list(high,[]))

@unsafe
def replacement_valid(xs: List<&2,Replacement>,+states: Q.Table<U32>,+palette: U32,+previous: Maybe<&2,U32>) -> Bool:
  match xs:
    case Nil{}: True{}
    case Replacement{+id,+material} <> rest:
      Bool.and(U32.is_lt(id,palette),Bool.and(P.member(states,material),Bool.and(sorted(previous,id),replacement_valid(rest,states,palette,Some{id}))))

def sorted(previous: Maybe<&2,U32>,id: U32) -> Bool:
  match previous:
    case None{}: True{}
    case Some{p}: U32.is_lt(p,id)

@unsafe
def transform_valid(xs: List<&2,Transform>,+palette: U32,+kind: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case Transform{+source,_,+states,+pass,replacements} <> rest:
      Bool.and(U32.is_lt(source,palette),Bool.and(nonempty(states,palette),Bool.and(
        transform_kind(kind,source,states,pass,replacements,palette),transform_valid(rest,palette,kind))))

@unsafe
def transform_kind(+kind: U32,+source: U32,+states: Q.Table<U32>,+pass: Bool,replacements: List<&2,Replacement>,+palette: U32) -> Bool:
  match kind:
    case 0: Bool.or(Bool.not(pass),Bool.and(U32.is_eq(Q.table_size(U32,states),1),U32.is_eq(Q.get(U32,states,0,0),source)))
    case 1: U32.is_eq(Q.table_size(U32,states),6)
    case _: Bool.and(P.member(states,source),replacement_valid(replacements,states,palette,None{}))

@unsafe
def entries_valid(xs: List<&2,Entry>,+palette: U32,+noises: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case Entry{_,+p} <> rest: Bool.and(Bool.not(nullable(p)),Bool.and(valid(p,palette,noises),entries_valid(rest,palette,noises)))

@unsafe
def rules_valid(xs: List<&2,Rule>,fallback: Maybe<&2,Provider>,+palette: U32,+noises: U32) -> Bool:
  match xs fallback:
    case Nil{} None{}: True{}
    case Nil{} Some{p}: valid(p,palette,noises)
    case Rule{predicate,p} <> rest _: Bool.and(P.predicate_valid(predicate,palette),Bool.and(valid(p,palette,noises),rules_valid(rest,fallback,palette,noises)))

def direction_valid(direction: Maybe<&2,U32>) -> Bool:
  match direction:
    case None{}: True{}
    case Some{x}: U32.is_lt(x,6)

@unsafe
def integer_variants(xs: List<&2,Transform>,+values: I.Provider) -> Bool:
  match xs:
    case Nil{}: True{}
    case Transform{_,+minimum,+states,+pass,_} <> rest:
      Bool.and(Bool.or(pass,I.within(values,minimum,U32.add(minimum,U32.sub(Q.table_size(U32,states),1)))),integer_variants(rest,values))

@unsafe
def source_valid(+source: Provider,+variants: List<&2,Transform>,+values: I.Provider,+kind: U32,+palette: U32,+noises: U32) -> Bool:
  Bool.and(valid(source,palette,noises),Bool.and(transform_valid(variants,palette,kind),
    Bool.or(reads_current(source),outputs_covered(source,variants,values,kind))))

@unsafe
def valid(+provider: Provider,+palette: U32,+noises: U32) -> Bool:
  match provider:
    case State{x}: U32.is_lt(x,palette)
    case Weighted{+entries,+sum}: Bool.and(Bool.not(W.equal(sum,W.U64{0,0})),Bool.and(W.equal(total(entries,W.U64{0,0}),sum),entries_valid(entries,palette,noises)))
    case Randomized{source,+values,+variants}: Bool.and(I.valid(values),Bool.and(integer_variants(variants,values),source_valid(source,variants,values,0,palette,noises)))
    case Rotated{source,direction,variants}: Bool.and(direction_valid(direction),source_valid(source,variants,I.Constant{0},1,palette,noises))
    case Copy{source,variants}: source_valid(source,variants,I.Constant{0},2,palette,noises)
    case Rules{rules,fallback}: rules_valid(rules,fallback,palette,noises)
    case RandomBlocks{states}: table_states(states,palette)
    case Noise{program,states}: Bool.and(U32.is_lt(program,noises),nonempty(states,palette))
    case Dual{fast,slow,+lo,+hi,states}: Bool.and(Bool.and(U32.is_lt(fast,noises),U32.is_lt(slow,noises)),Bool.and(Bool.and(U32.is_ge(lo,1),Bool.and(U32.is_le(lo,hi),U32.is_le(hi,64))),nonempty(states,palette)))
    case Threshold{program,+threshold,+chance,default,low,high}: Bool.and(U32.is_lt(program,noises),Bool.and(Bool.and(I.finite(threshold),Bool.and(F32.is_ge(threshold,F32.neg(1.0)),F32.is_le(threshold,1.0))),
      Bool.and(Bool.and(I.finite(chance),Bool.and(F32.is_ge(chance,0.0),F32.is_le(chance,1.0))),Bool.and(U32.is_lt(default,palette),Bool.and(nonempty(low,palette),nonempty(high,palette))))))

def value(optional: Maybe<&2,U32>) -> U32:
  match optional:
    case None{}: 0
    case Some{x}: x

@unsafe
def fallback(+sample: Sample,+world: E.World,+at: P.Position) -> Sample:
  match sample:
    case Sample{state,None{}}: Sample{state,Some{value(E.material(world,at))}}
    case _: sample

@unsafe
def weighted(xs: List<&2,Entry>,+selection: W.U64,+state: W.U64,+at: P.Position,+world: E.World,+noises: Q.Table<N.Program>) -> Sample:
  match xs:
    case Nil{}: Sample{state,None{}}
    case Entry{+weight,p} <> rest: weighted_selected(R.less(selection,W.U64{0,weight}),p,rest,selection,weight,state,at,world,noises)

@unsafe
def weighted_selected(selected: Bool,p: Provider,rest: List<&2,Entry>,+selection: W.U64,+weight: U32,+state: W.U64,+at: P.Position,+world: E.World,+noises: Q.Table<N.Program>) -> Sample:
  match selected:
    case True{}: sample(p,state,at,world,noises)
    case False{}: weighted(rest,W.sub(selection,W.U64{0,weight}),state,at,world,noises)

@unsafe
def weighted_draw(draw: R.Draw,entries: List<&2,Entry>,+sum: W.U64,+at: P.Position,+world: E.World,+noises: Q.Table<N.Program>) -> Sample:
  match draw:
    case R.Draw{state,word}: weighted(entries,R.remainder(word,sum),state,at,world,noises)

@unsafe
def picked(draw: R.Draw,+states: Q.Table<U32>) -> Sample:
  match draw:
    case R.Draw{state,word}: Sample{state,Some{Q.get(U32,states,R.low(word),0)}}

@unsafe
def random(+states: Q.Table<U32>,+state: W.U64) -> Sample:
  random_nonempty(U32.is_gt(Q.table_size(U32,states),0),states,state)

@unsafe
def random_nonempty(ok: Bool,+states: Q.Table<U32>,+state: W.U64) -> Sample:
  match ok:
    case False{}: Sample{state,None{}}
    case True{}: picked(R.integer(state,0,U32.sub(Q.table_size(U32,states),1)),states)

@unsafe
def rules(xs: List<&2,Rule>,+otherwise: Maybe<&2,Provider>,+state: W.U64,+at: P.Position,+world: E.World,+noises: Q.Table<N.Program>) -> Sample:
  match xs otherwise:
    case Nil{} None{}: Sample{state,None{}}
    case Nil{} Some{p}: optional(p,state,at,world,noises)
    case Rule{p,provider} <> rest _: rule_selected(C.true_value(C.predicate(p,world,at)),provider,rest,otherwise,state,at,world,noises)

@unsafe
def rule_selected(selected: Bool,provider: Provider,rest: List<&2,Rule>,+otherwise: Maybe<&2,Provider>,+state: W.U64,+at: P.Position,+world: E.World,+noises: Q.Table<N.Program>) -> Sample:
  match selected:
    case False{}: rules(rest,otherwise,state,at,world,noises)
    case True{}: rule_sample(optional(provider,state,at,world,noises),rest,otherwise,at,world,noises)

@unsafe
def rule_sample(+result: Sample,rest: List<&2,Rule>,+otherwise: Maybe<&2,Provider>,+at: P.Position,+world: E.World,+noises: Q.Table<N.Program>) -> Sample:
  match result:
    case Sample{state,None{}}: rules(rest,otherwise,state,at,world,noises)
    case _: result

@unsafe
def randomized(source: Sample,+values: I.Provider,variants: List<&2,Transform>) -> Sample:
  match source:
    case Sample{state,+material}: randomized_found(find(variants,value(material)),state,value(material),values)

@unsafe
def randomized_found(found: Maybe<&2,Transform>,+state: W.U64,+material: U32,+values: I.Provider) -> Sample:
  match found:
    case None{}: Sample{state,Some{material}}
    case Some{Transform{_,minimum,states,False{},_}}: randomized_draw(I.sample(values,state),minimum,states)
    case _: Sample{state,Some{material}}

@unsafe
def randomized_draw(draw: R.Draw,+minimum: U32,+states: Q.Table<U32>) -> Sample:
  match draw:
    case R.Draw{state,word}: Sample{state,Some{Q.get(U32,states,U32.sub(R.low(word),minimum),0)}}

@unsafe
def rotated_source(source: Sample,variants: List<&2,Transform>,+direction: U32) -> Sample:
  match source:
    case Sample{state,+material}: rotated_found(find(variants,value(material)),state,value(material),direction)

@unsafe
def rotated_found(found: Maybe<&2,Transform>,+state: W.U64,+material: U32,+direction: U32) -> Sample:
  match found:
    case None{}: Sample{state,Some{material}}
    case Some{Transform{_,_,states,_,_}}: Sample{state,Some{Q.get(U32,states,direction,0)}}

@unsafe
def rotated_draw(draw: R.Draw,source: Provider,variants: List<&2,Transform>,+at: P.Position,+world: E.World,+noises: Q.Table<N.Program>) -> Sample:
  match draw:
    case R.Draw{state,word}: rotated_source(sample(source,state,at,world,noises),variants,R.low(word))

@unsafe
def rotated(direction: Maybe<&2,U32>,source: Provider,variants: List<&2,Transform>,+state: W.U64,+at: P.Position,+world: E.World,+noises: Q.Table<N.Program>) -> Sample:
  match direction:
    case None{}: rotated_draw(R.integer(state,0,5),source,variants,at,world,noises)
    case Some{d}: rotated_source(sample(source,state,at,world,noises),variants,d)

@unsafe
def copied(xs: List<&2,Replacement>,+current: U32,+default: U32) -> U32:
  match xs:
    case Nil{}: default
    case Replacement{+id,material} <> rest: copied_equal(U32.is_eq(id,current),material,rest,current,default)

@unsafe
def copied_equal(equal: Bool,material: U32,rest: List<&2,Replacement>,current: U32,default: U32) -> U32:
  match equal:
    case True{}: material
    case False{}: copied(rest,current,default)

@unsafe
def copy_found(found: Maybe<&2,Transform>,+state: W.U64,+material: U32,+current: U32) -> Sample:
  match found:
    case None{}: Sample{state,Some{current}}
    case Some{Transform{_,_,_,_,replacements}}: Sample{state,Some{copied(replacements,current,material)}}

@unsafe
def copy_sample(source: Sample,variants: List<&2,Transform>,+current: U32) -> Sample:
  match source:
    case Sample{state,+material}: copy_found(find(variants,value(material)),state,value(material),current)

def noise_index(+noise: F32,+count: U32) -> U32:
  F32.to_u32(F32.mul(F32.min(0.9999,F32.max(0.0,F32.div(F32.add(1.0,noise),2.0))),U32.to_f32(count)))

@unsafe
def noise_selected(+value: F32,+states: Q.Table<U32>,+state: W.U64) -> Sample:
  Sample{state,Some{Q.get(U32,states,noise_index(value,Q.table_size(U32,states)),0)}}

# The variety count uses compensated arithmetic before floor, preserving the
# f64 selector's integer boundary even when a F32 intermediate rounds upward.
def variety(+noise: F32,+minimum: U32,+maximum: U32) -> U32:
  F32.to_u32(D.value(D.floor(D.add(D.scale(D.add(D.scalar(F32.min(1.0,F32.max(F32.neg(1.0),noise))),D.scalar(1.0)),
    F32.div(U32.to_f32(U32.inc(U32.sub(maximum,minimum))),2.0)),D.scalar(U32.to_f32(minimum))))))

@unsafe
def dual(+fast: U32,+slow: U32,+minimum: U32,+maximum: U32,+states: Q.Table<U32>,+state: W.U64,+at: P.Position,+noises: Q.Table<N.Program>) -> Sample:
  dual_at(noise_index(N.get(noises,fast,at),variety(N.get(noises,slow,at),minimum,maximum)),slow,states,state,at,noises)

@unsafe
def dual_at(+selected: U32,+slow: U32,+states: Q.Table<U32>,+state: W.U64,+at: P.Position,+noises: Q.Table<N.Program>) -> Sample:
  match at:
    case P.Position{x,y,z}: noise_selected(N.get(noises,slow,P.Position{U32.add(x,U32.mul(selected,54545)),y,U32.add(z,U32.mul(selected,34234))}),states,state)

@unsafe
def threshold_choice(draw: R.Draw,+chance: F32,+default: U32,+high: Q.Table<U32>) -> Sample:
  match draw:
    case R.Draw{state,word}: threshold_high(F32.is_lt(R.unit(word),chance),state,default,high)

@unsafe
def threshold_high(selected: Bool,+state: W.U64,+default: U32,+high: Q.Table<U32>) -> Sample:
  match selected:
    case False{}: Sample{state,Some{default}}
    case True{}: random(high,state)

@unsafe
def threshold(lowward: Bool,+chance: F32,+default: U32,+low: Q.Table<U32>,+high: Q.Table<U32>,+state: W.U64) -> Sample:
  match lowward:
    case True{}: random(low,state)
    case False{}: threshold_choice(R.next(state),chance,default,high)

# Optional rules keep None so later rules may run. Only getState/sample performs
# substrate fallback. This distinction matters for conditional simple blocks.
@unsafe
def optional(provider: Provider,+state: W.U64,+at: P.Position,+world: E.World,+noises: Q.Table<N.Program>) -> Sample:
  match provider:
    case State{x}: Sample{state,Some{x}}
    case Weighted{entries,sum}: weighted_draw(R.next(state),entries,sum,at,world,noises)
    case Randomized{source,values,variants}: randomized(sample(source,state,at,world,noises),values,variants)
    case Rotated{source,direction,variants}: rotated(direction,source,variants,state,at,world,noises)
    case Copy{source,variants}: copy_sample(sample(source,state,at,world,noises),variants,value(E.material(world,at)))
    case Rules{rows,fallback}: rules(rows,fallback,state,at,world,noises)
    case RandomBlocks{states}: random(states,state)
    case Noise{program,states}: noise_selected(N.get(noises,program,at),states,state)
    case Dual{fast,slow,lo,hi,states}: dual(fast,slow,lo,hi,states,state,at,noises)
    case Threshold{program,level,chance,default,low,high}: threshold(F32.is_lt(N.get(noises,program,at),level),chance,default,low,high,state)

@unsafe
def sample(provider: Provider,+state: W.U64,+at: P.Position,+world: E.World,+noises: Q.Table<N.Program>) -> Sample:
  fallback(optional(provider,state,at,world,noises),world,at)
