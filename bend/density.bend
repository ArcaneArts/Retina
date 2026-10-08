import Base
import ./noise.bend as N
import ./precise.bend as D
import ./registry.bend as R

# Pure registered density DAG evaluator. Data trees are immutable resident
# inputs; each invocation owns its scratch values. Application-level numeric,
# noise, spline and interpolation work is Bend on either CPU or GPU.
type Vec4 is Data:
  Vec4{a: F32,b: F32,c: F32,d: F32}
type Point is Data:
  Point{x: D.Number,y: D.Number,z: D.Number}
type Noise is Data:
  Noise{horizontal: F32,frequency: F32,amplitude: F32,salt: U32,coefficients: List<&2,F32>}
type Instruction is Data:
  Instruction{op: U32,a: U32,b: U32,c: U32,p: Vec4}
type Program is Data:
  Program{nodes: List<&2,Instruction>,roots: List<&2,U32>,count: U32}
type Field is Data:
  Field{program: Program,horizontal: U32,vertical: U32}
type Table<-A: Data> is Data:
  Empty{}
  Leaf{value: A}
  Branch{size: U32,left: Table<A>,right: Table<A>}
type Model is Data:
  Model{noises: Table<Noise>,points: Table<Vec4>,fields: Table<Field>}
type Outputs is Data:
  Outputs{a: D.Number,b: D.Number,c: D.Number,d: D.Number,e: D.Number,f: D.Number}

def table_size(-A: Data,t: Table<A>) -> U32:
  match t:
    case Empty{}: 0
    case Leaf{_}: 1
    case Branch{n,_,_}: n

@unsafe
def table(-A: Data,+n: Nat,xs: List<&2,A>) -> Table<A> & List<&2,A>:
  match n xs:
    case 0n _: (Empty{},xs)
    case 1n Nil{}: (Empty{},[])
    case 1n x <> rest: (Leaf{x},rest)
    case _ _:
      table_left(A,table(A,Nat.div(n,2n),xs),n)

@unsafe
def table_left(-A: Data,pair: Table<A> & List<&2,A>,+n: Nat) -> Table<A> & List<&2,A>:
  (a,rest) = pair
  table_right(A,table(A,Nat.sub(n,Nat.div(n,2n)),rest),a,n)

@unsafe
def table_right(-A: Data,pair: Table<A> & List<&2,A>,a: Table<A>,n: Nat) -> Table<A> & List<&2,A>:
  (b,rest) = pair
  (Branch{U32.from_nat(n),a,b},rest)

def table_only(-A: Data,pair: Table<A> & List<&2,A>) -> Table<A>:
  (t,rest) = pair
  t

def prepared_table(-A: Data,+xs: List<&2,A>) -> Table<A>:
  table_only(A,table(A,List.length(&2,A,xs),xs))

@unsafe
def get(-A: Data,+t: Table<A>,+i: U32,+default: A) -> A:
  match t:
    case Empty{}: default
    case Leaf{value}: value
    case Branch{_,+a,b}: get_branch(A,U32.is_lt(i,table_size(A,a)),a,b,i,default)

@unsafe
def get_branch(-A: Data,left: Bool,+a: Table<A>,b: Table<A>,i: U32,default: A) -> A:
  match left:
    case True{}: get(A,a,i,default)
    case False{}: get(A,b,U32.sub(i,table_size(A,a)),default)

def point_axis(point: Point,+axis: U32) -> D.Number:
  match point:
    case Point{x,y,z}: D.choose(U32.is_zero(axis),x,D.choose(U32.is_eq(axis,1),y,z))

def world(x: U32,y: U32,z: U32) -> Point:
  Point{D.integer(x),D.integer(y),D.integer(z)}

def scale(point: Point,+xz: F32,y: F32) -> Point:
  match point:
    case Point{x,yy,z}: Point{D.scale(x,xz),D.scale(yy,y),D.scale(z,xz)}

def shifted(point: Point,a: D.Number,b: D.Number,c: D.Number) -> Point:
  match point:
    case Point{x,y,z}: Point{D.add(x,a),D.add(y,b),D.add(z,c)}

def perlin(point: Point,seed: U32) -> F32:
  match point:
    case Point{x,y,z}: N.perlin_axes(D.axis(x),D.axis(y),D.axis(z),seed,0)

def octave(active: Bool,+point: Point,+frequency: F32,+seed: U32,+i: U32,weight: F32) -> F32:
  match active:
    case False{}: 0.0
    case True{}: F32.mul(F32.add(perlin(scale(point,frequency,frequency),U32.add(seed,U32.mul(i,1013))),
      perlin(scale(point,F32.mul(frequency,1.0181268882175227),F32.mul(frequency,1.0181268882175227)),
        U32.add(U32.xor(seed,2769414579),U32.mul(i,1013)))),weight)

def octaves(xs: List<&2,F32>,+point: Point,+frequency: F32,+seed: U32,+i: U32,sum: F32) -> F32:
  match xs:
    case Nil{}: sum
    case +w <> rest: octaves(rest,point,F32.mul(frequency,2.0),seed,U32.inc(i),
      F32.add(sum,octave(F32.is_ne(w,0.0),point,frequency,seed,i,w)))

def sample(noise: Noise,point: Point,low: U32,high: U32) -> F32:
  match noise:
    case Noise{horizontal,frequency,amplitude,salt,weights}: F32.mul(F32.mul(octaves(weights,scale(point,horizontal,1.0),frequency,
      U32.xor(U32.xor(low,N.hash(high)),salt),0,0.0),amplitude),0.625)

def noise(model: Model,index: U32,point: Point,low: U32,high: U32) -> D.Number:
  match model:
    case Model{noises,_,_}: D.scalar(sample(get(Noise,noises,index,Noise{1.0,1.0,0.0,0,[]}),point,low,high))

def blend_octaves(n: Nat,+limit: Point,+main: Point,+seed: U32,+i: U32,+weight: F32,+total: F32,+lower: F32,+upper: F32,+blend: F32) -> F32:
  match n:
    case 0n: F32.div(N.mix(lower,upper,F32.min(F32.max(F32.add(F32.mul(F32.div(blend,total),0.5),0.5),0.0),1.0)),total)
    case 1n+p:
      +offset = U32.mul(i,1013)
      blend_octaves(p,scale(limit,2.0,2.0),scale(main,2.0,2.0),seed,U32.inc(i),F32.mul(weight,0.5),F32.add(total,weight),
        F32.add(lower,F32.mul(perlin(limit,U32.add(seed,offset)),weight)),
        F32.add(upper,F32.mul(perlin(limit,U32.add(U32.add(seed,7919),offset)),weight)),
        F32.add(blend,F32.mul(perlin(main,U32.add(U32.add(seed,15838),offset)),weight)))

def old_blended(+point: Point,p: Vec4,low: U32,high: U32) -> D.Number:
  match p:
    case Vec4{+a,+b,c,d}: D.scalar(blend_octaves(8n,
      scale(point,F32.div(F32.mul(684.412,a),32768.0),F32.div(F32.mul(684.412,b),32768.0)),
      scale(point,F32.div(F32.div(F32.mul(684.412,a),c),128.0),F32.div(F32.div(F32.mul(684.412,b),d),128.0)),
      U32.xor(low,N.hash(high)),0,1.0,0.0,0.0,0.0,0.0))

def mapped(+t: D.Number,+mode: U32) -> D.Number:
  D.choose(U32.is_eq(mode,1),D.scalar(D.fraction(t)),
    D.choose(U32.is_eq(mode,2),D.scalar(F32.sub(1.0,F32.abs(F32.sub(F32.mul(D.fraction(D.scale(t,0.5)),2.0),1.0)))),
      D.minimum(D.maximum(t,D.scalar(0.0)),D.scalar(1.0))))

def gradient(x: D.Number,p: Vec4,mode: U32) -> D.Number:
  match p:
    case Vec4{+a,b,c,d}: D.mix(D.scalar(c),D.scalar(d),mapped(D.divide(D.sub(x,D.scalar(a)),D.scalar(F32.sub(b,a))),mode))

def safe(+x: D.Number) -> D.Number:
  D.choose(F32.is_lt(F32.abs(D.value(x)),0.000001),D.scalar(0.000001),x)

def unary(+op: U32,+x: D.Number,p: Vec4) -> D.Number:
  match p:
    case Vec4{a,b,_,_}:
      +v = D.value(x)
      +c = F32.min(F32.max(v,F32.neg(1.0)),1.0)
      D.choose(U32.is_eq(op,11),D.absolute(x),
      D.choose(U32.is_eq(op,12),D.multiply(x,x),
      D.choose(U32.is_eq(op,13),D.multiply(D.multiply(x,x),x),
      D.choose(U32.is_eq(op,14),D.choose(F32.is_gt(v,0.0),x,D.scale(x,0.5)),
      D.choose(U32.is_eq(op,15),D.choose(F32.is_gt(v,0.0),x,D.scale(x,0.25)),
      D.choose(U32.is_eq(op,16),D.scalar(F32.sub(F32.mul(c,0.5),F32.div(F32.mul(F32.mul(c,c),c),24.0))),
      D.choose(U32.is_eq(op,17),D.divide(D.scalar(1.0),safe(x)),
      D.choose(U32.is_eq(op,18),D.negate(x),
      D.choose(U32.is_eq(op,19),D.scalar(F32.sqrt(F32.max(v,0.0))),
      D.choose(U32.is_eq(op,20),D.scalar(F32.log(F32.max(v,0.000001))),
      D.choose(U32.is_eq(op,21),D.scalar(N.pick(F32.is_gt(v,0.0),1.0,N.pick(F32.is_lt(v,0.0),F32.neg(1.0),0.0))),
        D.minimum(D.maximum(x,D.scalar(a)),D.scalar(b)))))))))))))

def binary(+op: U32,+a: D.Number,+b: D.Number) -> D.Number:
  D.choose(U32.is_eq(op,4),D.add(a,b),
  D.choose(U32.is_eq(op,5),D.sub(a,b),
  D.choose(U32.is_eq(op,6),D.multiply(a,b),
  D.choose(U32.is_eq(op,7),D.divide(a,safe(b)),
  D.choose(U32.is_eq(op,8),D.minimum(a,b),
  D.choose(U32.is_eq(op,9),D.maximum(a,b),D.scalar(F32.pow(F32.max(D.value(a),0.0),D.value(b)))))))))

def read(-A: Type,pair: Array<D.Number> & D.Number,next: Array<D.Number> -> D.Number -> A) -> A:
  (array,value) = pair
  next(array,value)

@unsafe
def spline_left(-A: Type,more: Bool,+points: Table<Vec4>,+base: U32,+count: U32,+j: U32,+k: U32,+x: D.Number,values: Array<D.Number>,next: Array<D.Number> -> D.Number -> A) -> A:
  match more:
    case False{}: spline_value(A,points,base,count,j,x,values,next)
    case True{}: spline_scan(A,points,get(Vec4,points,U32.add(base,k),Vec4{0.0,0.0,0.0,0.0}),base,count,j,k,x,values,next)

@unsafe
def spline_scan(-A: Type,+points: Table<Vec4>,point: Vec4,+base: U32,+count: U32,+j: U32,+k: U32,+x: D.Number,values: Array<D.Number>,next: Array<D.Number> -> D.Number -> A) -> A:
  match point:
    case Vec4{at,_,_,_}: spline_left(A,U32.is_lt(U32.inc(k),count),points,base,count,
      N.word(Bool.not(D.less(x,D.scalar(at))),k,j),U32.inc(k),x,values,next)

@unsafe
def spline_value(-A: Type,+points: Table<Vec4>,+base: U32,+count: U32,+j: U32,x: D.Number,values: Array<D.Number>,next: Array<D.Number> -> D.Number -> A) -> A:
  spline_point(A,points,get(Vec4,points,U32.add(base,j),Vec4{0.0,0.0,0.0,0.0}),base,count,j,x,values,next)

@unsafe
def spline_point(-A: Type,points: Table<Vec4>,point: Vec4,base: U32,+count: U32,+j: U32,+x: D.Number,values: Array<D.Number>,next: Array<D.Number> -> D.Number -> A) -> A:
  match point:
    case Vec4{+at,+derivative,child,_}: read(A,Array.get(D.Number,values,F32.to_u32(child)),values => value =>
      spline_branch(A,Bool.or(D.less(x,D.scalar(at)),U32.is_eq(j,U32.sub(count,1))),points,base,j,x,at,derivative,value,values,next))

@unsafe
def spline_branch(-A: Type,end: Bool,points: Table<Vec4>,base: U32,j: U32,x: D.Number,at: F32,derivative: F32,value: D.Number,values: Array<D.Number>,next: Array<D.Number> -> D.Number -> A) -> A:
  match end:
    case True{}: next(values,D.add(value,D.scale(D.sub(x,D.scalar(at)),derivative)))
    case False{}: spline_right(A,get(Vec4,points,U32.inc(U32.add(base,j)),Vec4{0.0,0.0,0.0,0.0}),x,at,derivative,value,values,next)

@unsafe
def spline_right(-A: Type,point: Vec4,x: D.Number,+at: F32,+derivative: F32,+left: D.Number,values: Array<D.Number>,next: Array<D.Number> -> D.Number -> A) -> A:
  match point:
    case Vec4{right_at,right_derivative,child,_}: read(A,Array.get(D.Number,values,F32.to_u32(child)),values => +right =>
      next(values,
        spline_mix(x,at,right_at,derivative,right_derivative,left,right)))

@unsafe
def spline_mix(x: D.Number,+a: F32,+b: F32,ad: F32,bd: F32,+left: D.Number,+right: D.Number) -> D.Number:
  +span = F32.sub(b,a)
  +t = D.divide(D.sub(x,D.scalar(a)),D.scalar(span))
  +delta = D.sub(right,left)
  D.add(D.mix(left,right,t),D.multiply(D.multiply(t,D.sub(D.scalar(1.0),t)),
    D.mix(D.sub(D.scalar(F32.mul(ad,span)),delta),D.add(D.scalar(F32.neg(F32.mul(bd,span))),delta),t)))

# Fields only reference earlier fields, validated by the caller before making a
# Model resident. Evaluation uses the same global lattice for every request.
def lattice(x: D.Number,+step: U32) -> D.Number:
  D.scale(D.floor(D.divide(x,D.scalar(U32.to_f32(step)))),U32.to_f32(step))

@unsafe
def interpolate(model: Model,field: Field,point: Point,low: U32,high: U32) -> D.Number:
  match field point:
    case Field{program,+horizontal,+vertical} Point{+x,+y,+z}:
      +a = lattice(x,horizontal)
      +b = lattice(y,vertical)
      +c = lattice(z,horizontal)
      corners(model,program,Point{a,b,c},horizontal,vertical,low,high,0,[],
        D.value(D.divide(D.sub(x,a),D.scalar(U32.to_f32(horizontal)))),
        D.value(D.divide(D.sub(y,b),D.scalar(U32.to_f32(vertical)))),
        D.value(D.divide(D.sub(z,c),D.scalar(U32.to_f32(horizontal)))))

@unsafe
def corners(+model: Model,+program: Program,+origin: Point,+horizontal: U32,+vertical: U32,+low: U32,+high: U32,+i: U32,acc: List<&2,D.Number>,x: F32,y: F32,z: F32) -> D.Number:
  corners_more(U32.is_lt(i,8),model,program,origin,horizontal,vertical,low,high,i,acc,x,y,z)

@unsafe
def corners_more(more: Bool,+model: Model,+program: Program,+origin: Point,+horizontal: U32,+vertical: U32,+low: U32,+high: U32,+i: U32,acc: List<&2,D.Number>,x: F32,y: F32,z: F32) -> D.Number:
  match more:
    case False{}: corner_mix(List.reverse(&2,D.Number,acc),x,y,z)
    case True{}: corners_output(model,program,origin,horizontal,vertical,low,high,i,acc,x,y,z,
      run(model,program,shifted(origin,D.scalar(U32.to_f32(U32.mul(U32.and(i,1),horizontal))),
        D.scalar(U32.to_f32(U32.mul(U32.and(U32.shrn(i,1n),1),vertical))),
        D.scalar(U32.to_f32(U32.mul(U32.and(U32.shrn(i,2n),1),horizontal)))),low,high))

@unsafe
def corners_output(+model: Model,+program: Program,+origin: Point,+horizontal: U32,+vertical: U32,+low: U32,+high: U32,+i: U32,acc: List<&2,D.Number>,x: F32,y: F32,z: F32,output: Outputs) -> D.Number:
  match output:
    case Outputs{a,_,_,_,_,_}: corners(model,program,origin,horizontal,vertical,low,high,U32.inc(i),a <> acc,x,y,z)

def corner_mix(values: List<&2,D.Number>,+x: F32,+y: F32,z: F32) -> D.Number:
  match values:
    case a <> b <> c <> d <> e <> f <> g <> h <> Nil{}:
      D.mix(D.mix(D.mix(a,b,D.scalar(x)),D.mix(c,d,D.scalar(x)),D.scalar(y)),
        D.mix(D.mix(e,f,D.scalar(x)),D.mix(g,h,D.scalar(x)),D.scalar(y)),D.scalar(z))
    case _: D.scalar(0.0)

@unsafe
def eval(-A: Type,+model: Model,instruction: Instruction,+point: Point,+low: U32,+high: U32,values: Array<D.Number>,next: Array<D.Number> -> D.Number -> A) -> A:
  match model instruction:
    case Model{_,+points,+fields} Instruction{+op,+a,+b,+c,+p}:
      read(A,Array.get(D.Number,values,a),values => +av => read(A,Array.get(D.Number,values,b),values => +bv =>
      read(A,Array.get(D.Number,values,c),values => +cv =>
        operation(A,model,points,fields,op,a,b,c,p,point,low,high,av,bv,cv,values,next))))

@unsafe
def operation(-A: Type,+model: Model,points: Table<Vec4>,fields: Table<Field>,op: U32,+a: U32,+b: U32,+c: U32,+p: Vec4,+point: Point,+low: U32,+high: U32,+av: D.Number,+bv: D.Number,+cv: D.Number,values: Array<D.Number>,next: Array<D.Number> -> D.Number -> A) -> A:
  match op p:
    case 0 Vec4{x,_,_,_}: next(values,D.scalar(x))
    case 1 Vec4{x,y,z,_}: next(values,noise(model,F32.to_u32(x),shifted(scale(point,y,z),av,bv,cv),low,high))
    case 2 _:
      next(values,D.scale(noise(model,a,scale(shift_point(point,b),0.25,0.25),low,high),4.0))
    case 3 _: next(values,gradient(point_axis(point,a),p,b))
    case 4 _: next(values,binary(4,av,bv))
    case 5 _: next(values,binary(5,av,bv))
    case 6 _: next(values,binary(6,av,bv))
    case 7 _: next(values,binary(7,av,bv))
    case 8 _: next(values,binary(8,av,bv))
    case 9 _: next(values,binary(9,av,bv))
    case 10 _: next(values,binary(10,av,bv))
    case 11 _: next(values,unary(11,av,p))
    case 12 _: next(values,unary(12,av,p))
    case 13 _: next(values,unary(13,av,p))
    case 14 _: next(values,unary(14,av,p))
    case 15 _: next(values,unary(15,av,p))
    case 16 _: next(values,unary(16,av,p))
    case 17 _: next(values,unary(17,av,p))
    case 18 _: next(values,unary(18,av,p))
    case 19 _: next(values,unary(19,av,p))
    case 20 _: next(values,unary(20,av,p))
    case 21 _: next(values,unary(21,av,p))
    case 22 _: next(values,unary(22,av,p))
    case 23 Vec4{x,y,_,_}: next(values,D.choose(Bool.and(Bool.not(D.less(av,D.scalar(x))),D.less(av,D.scalar(y))),bv,cv))
    case 24 _: next(values,D.mix(bv,cv,av))
    case 25 _: spline_left(A,U32.is_gt(c,1),points,b,c,0,1,av,values,next)
    case 26 _: next(values,old_blended(point,p,low,high))
    case 27 Vec4{x,y,_,_}: next(values,D.scale(noise(model,F32.to_u32(x),Point{av,bv,cv},low,high),y))
    case 28 Vec4{x,_,_,_}: next(values,interpolate(model,get(Field,fields,F32.to_u32(x),Field{Program{[],[],0},4,8}),Point{av,bv,cv},low,high))
    case 29 _: next(values,point_axis(point,a))
    case 30 _: next(values,gradient(av,p,b))
    case 31 _: next(values,old_blended(Point{av,bv,cv},p,low,high))
    case _ _: next(values,D.scalar(F32.div(0.0,0.0)))

@unsafe
def shift_point(point: Point,+mode: U32) -> Point:
  match point:
    case Point{+x,+y,+z}: Point{D.choose(U32.is_eq(mode,2),z,x),D.choose(U32.is_eq(mode,1),D.scalar(0.0),D.choose(U32.is_eq(mode,2),x,y)),D.choose(U32.is_eq(mode,2),D.scalar(0.0),z)}

@unsafe
def nodes(+model: Model,xs: List<&2,Instruction>,+point: Point,+low: U32,+high: U32,+i: U32,values: Array<D.Number>,roots: List<&2,U32>) -> Outputs:
  match xs:
    case Nil{}: outputs(6n,roots,values,[])
    case instruction <> rest: eval(Outputs,model,instruction,point,low,high,values,values => value =>
      nodes(model,rest,point,low,high,U32.inc(i),Array.set(D.Number,values,i,value),roots))

@unsafe
def outputs(n: Nat,roots: List<&2,U32>,values: Array<D.Number>,acc: List<&2,D.Number>) -> Outputs:
  match n roots:
    case 0n _: output_list(List.reverse(&2,D.Number,acc))
    case 1n+p Nil{}: read(Outputs,Array.get(D.Number,values,0),values => value => outputs(p,[],values,value <> acc))
    case 1n+p root <> rest: read(Outputs,Array.get(D.Number,values,root),values => value => outputs(p,rest,values,value <> acc))

def output_list(xs: List<&2,D.Number>) -> Outputs:
  match xs:
    case a <> b <> c <> d <> e <> f <> Nil{}: Outputs{a,b,c,d,e,f}
    case _: Outputs{D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0)}

@unsafe
def run(+model: Model,program: Program,+point: Point,+low: U32,+high: U32) -> Outputs:
  match program:
    case Program{nodes_list,roots,count}: nodes(model,nodes_list,point,low,high,0,
      Array.new(D.Number,R.capacity(count,1,0n),D.scalar(0.0)),roots)

# Preparation rejects malformed DAGs before unchecked per-point array reads.
# This validator is for numeric density programs (0..31); material predicates
# are a separate layer and cannot silently execute as zero in this evaluator.
def finite_parameters(p: Vec4) -> Bool:
  match p:
    case Vec4{a,b,c,d}: Bool.and(Bool.and(D.finite(a),D.finite(b)),Bool.and(D.finite(c),D.finite(d)))

def index(+x: F32,+count: U32) -> Bool:
  Bool.and(Bool.and(F32.is_ge(x,0.0),F32.is_lt(x,U32.to_f32(count))),F32.is_eq(x,F32.floor(x)))

def dependency3(+op: U32) -> Bool:
  Bool.or(Bool.or(U32.is_eq(op,1),U32.is_eq(op,23)),Bool.or(U32.is_eq(op,24),Bool.and(U32.is_ge(op,27),U32.is_le(op,28))))

def dependencies(+op: U32,+a: U32,+b: U32,+c: U32,+i: U32) -> Bool:
  Bool.and(Bool.or(Bool.not(Bool.or(dependency3(op),U32.is_eq(op,31))),Bool.and(U32.is_lt(a,i),Bool.and(U32.is_lt(b,i),U32.is_lt(c,i)))),
    Bool.and(Bool.or(Bool.not(Bool.and(U32.is_ge(op,4),U32.is_le(op,10))),Bool.and(U32.is_lt(a,i),U32.is_lt(b,i))),
      Bool.or(Bool.not(Bool.or(Bool.and(U32.is_ge(op,11),U32.is_le(op,22)),Bool.or(U32.is_eq(op,25),U32.is_eq(op,30)))),U32.is_lt(a,i))))

@unsafe
def valid_spline(n: Nat,+points: Table<Vec4>,+at: U32,+i: U32) -> Bool:
  match n:
    case 0n: True{}
    case 1n+p: valid_spline_point(p,points,get(Vec4,points,at,Vec4{0.0,0.0,F32.neg(1.0),0.0}),U32.inc(at),i)

@unsafe
def valid_spline_point(n: Nat,points: Table<Vec4>,point: Vec4,at: U32,+i: U32) -> Bool:
  match point:
    case Vec4{_,_,child,_}: Bool.and(index(child,i),valid_spline(n,points,at,i))

def spline_valid(yes: Bool,+points: Table<Vec4>,b: U32,+c: U32,+i: U32) -> Bool:
  match yes:
    case False{}: False{}
    case True{}: valid_spline(U32.to_nat(c),points,b,i)

@unsafe
def valid_instruction(model: Model,instruction: Instruction,+i: U32,+field_limit: U32) -> Bool:
  match model instruction:
    case Model{+noises,+points,_} Instruction{+op,+a,+b,+c,+p}:
      valid_parameters(noises,points,op,a,b,c,p,i,field_limit)

@unsafe
def valid_parameters(+noises: Table<Noise>,+points: Table<Vec4>,+op: U32,+a: U32,+b: U32,+c: U32,+p: Vec4,+i: U32,+field_limit: U32) -> Bool:
  match p:
    case Vec4{+x,+y,+z,+w}:
      Bool.and(Bool.and(U32.is_le(op,31),finite_parameters(p)),Bool.and(dependencies(op,a,b,c,i),
      Bool.and(Bool.or(Bool.not(Bool.or(U32.is_eq(op,1),U32.is_eq(op,27))),index(x,table_size(Noise,noises))),
      Bool.and(Bool.or(U32.is_ne(op,2),Bool.and(U32.is_lt(a,table_size(Noise,noises)),U32.is_le(b,2))),
      Bool.and(Bool.or(U32.is_ne(op,3),U32.is_le(a,2)),
      Bool.and(Bool.or(U32.is_ne(op,29),U32.is_le(a,2)),
      Bool.and(Bool.or(U32.is_ne(op,28),index(x,field_limit)),
      Bool.and(Bool.or(Bool.not(Bool.or(U32.is_eq(op,3),U32.is_eq(op,30))),Bool.and(U32.is_le(b,2),F32.is_ne(x,y))),
      Bool.and(Bool.or(Bool.not(Bool.or(U32.is_eq(op,26),U32.is_eq(op,31))),Bool.and(F32.is_ne(z,0.0),F32.is_ne(w,0.0))),
      Bool.or(U32.is_ne(op,25),spline_valid(Bool.and(U32.is_gt(c,0),Bool.and(U32.is_le(b,table_size(Vec4,points)),
        U32.is_le(c,U32.sub(table_size(Vec4,points),b)))),points,b,c,i)))))))))))

def valid_nodes(+model: Model,xs: List<&2,Instruction>,+i: U32,+limit: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case instruction <> rest: Bool.and(valid_instruction(model,instruction,i,limit),valid_nodes(model,rest,U32.inc(i),limit))

def valid_roots(xs: List<&2,U32>,+count: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case root <> rest: Bool.and(U32.is_lt(root,count),valid_roots(rest,count))

def valid_program(+model: Model,program: Program,limit: U32) -> Bool:
  match program:
    case Program{+nodes_list,+roots,+count}: Bool.and(Bool.and(U32.is_gt(count,0),U32.is_le(count,1024)),
      Bool.and(U32.is_eq(count,U32.from_nat(List.length(&2,Instruction,nodes_list))),
      Bool.and(Nat.is_le(List.length(&2,U32,roots),6n),Bool.and(valid_roots(roots,count),valid_nodes(model,nodes_list,0,limit)))))

def valid_coefficients(xs: List<&2,F32>) -> Bool:
  match xs:
    case Nil{}: True{}
    case x <> rest: Bool.and(D.finite(x),valid_coefficients(rest))

def valid_noise(noise: Noise) -> Bool:
  match noise:
    case Noise{+horizontal,+frequency,+amplitude,_,+coefficients}:
      Bool.and(Bool.and(D.finite(horizontal),F32.is_gt(horizontal,0.0)),
      Bool.and(Bool.and(D.finite(frequency),F32.is_gt(frequency,0.0)),
      Bool.and(D.finite(amplitude),Bool.and(Nat.is_gt(List.length(&2,F32,coefficients),0n),
      Bool.and(Nat.is_le(List.length(&2,F32,coefficients),32n),valid_coefficients(coefficients))))))

def valid_noises(xs: Table<Noise>) -> Bool:
  match xs:
    case Empty{}: True{}
    case Leaf{noise}: valid_noise(noise)
    case Branch{_,a,b}: Bool.and(valid_noises(a),valid_noises(b))

def valid_points(xs: Table<Vec4>) -> Bool:
  match xs:
    case Empty{}: True{}
    case Leaf{point}: finite_parameters(point)
    case Branch{_,a,b}: Bool.and(valid_points(a),valid_points(b))

@unsafe
def single_root(program: Program) -> Bool:
  match program:
    case Program{_,roots,_}: Nat.is_eq(List.length(&2,U32,roots),1n)

def valid_field(+model: Model,field: Field,i: U32) -> Bool:
  match field:
    case Field{+program,+horizontal,+vertical}:
      Bool.and(Bool.and(Bool.and(U32.is_gt(horizontal,0),U32.is_le(horizontal,2147483647)),
        Bool.and(U32.is_gt(vertical,0),U32.is_le(vertical,2147483647))),
        Bool.and(single_root(program),valid_program(model,program,i)))

def valid_field_table(+model: Model,fields: Table<Field>,+i: U32) -> Bool:
  match fields:
    case Empty{}: True{}
    case Leaf{field}: valid_field(model,field,i)
    case Branch{_,+a,b}: Bool.and(valid_field_table(model,a,i),valid_field_table(model,b,U32.add(i,table_size(Field,a))))

def valid_model(+model: Model) -> Bool:
  match model:
    case Model{+noises,+points,+fields}:
      Bool.and(Bool.and(U32.is_le(table_size(Noise,noises),8192),U32.is_le(table_size(Vec4,points),65536)),
        Bool.and(U32.is_le(table_size(Field,fields),128),Bool.and(valid_noises(noises),
          Bool.and(valid_points(points),valid_field_table(model,fields,0)))))
