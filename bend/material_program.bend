import Base
import ./density.bend as Q
import ./precise.bend as D
import ./noise.bend as N
import ./registry.bend as P
import ./material_columns_data.bend as MD

# Registered material predicates share the numeric DAG, but have explicit
# context/immediate operands rather than unchecked numeric scratch reads.
# Zero means no selected material; palette IDs are encoded as ID + 1.
type Model is Data:
  Model{programs: Q.Table<Q.Program>,bands: Q.Table<U32>,sea: U32,layered: Bool,columns: Maybe<&2,MD.State>}
type Context is Data:
  Context{above: F32,depth: F32,slope: F32,offset: F32,below: F32,secondary: F32,water: F32,preliminary: F32}

@unsafe
def flag(+ok: Bool) -> D.Number:
  D.scalar(N.pick(ok,1.0,0.0))

@unsafe
def word(axis: N.Axis) -> U32:
  match axis:
    case N.Axis{+x,_}: x

@unsafe
def horizontal(+point: Q.Point) -> Q.Point:
  match point:
    case Q.Point{+x,_,+z}: Q.Point{x,D.scalar(0.0),z}

@unsafe
def cell_hash(+point: Q.Point,+low: U32,+high: U32) -> U32:
  match point:
    case Q.Point{+x,_,+z}: N.hash(U32.xor(U32.xor(U32.mul(word(D.axis(x)),2654435769),
      U32.mul(word(D.axis(z)),2246822507)),U32.xor(low,N.hash(high))))

@unsafe
def trunc(+x: F32) -> F32:
  N.pick(F32.is_lt(x,0.0),F32.neg(F32.floor(F32.abs(x))),F32.floor(x))

@unsafe
def band(+bands: Q.Table<U32>,+point: Q.Point,+offset: F32) -> D.Number:
  band_ready(U32.is_gt(Q.table_size(U32,bands),0),bands,Q.point_axis(point,1),offset)

@unsafe
def band_ready(+ok: Bool,+bands: Q.Table<U32>,+y: D.Number,+offset: F32) -> D.Number:
  match ok:
    case False{}: D.scalar(0.0)
    case True{}: band_value(bands,D.add(y,D.scalar(trunc(offset))),Q.table_size(U32,bands))

@unsafe
def band_value(bands: Q.Table<U32>,+at: D.Number,+size: U32) -> D.Number:
  +index = D.sub(at,D.scale(D.floor(D.divide(at,D.unsigned(size))),U32.to_f32(size)))
  D.unsigned(U32.inc(Q.get(U32,bands,F32.to_u32(D.value(index)),0)))

@unsafe
def stone(+layered: Bool,+a: U32,+b: U32,+p: Q.Vec4,+context: Context) -> D.Number:
  match p context:
    case Q.Vec4{+x,+y,_,_} Context{+above,+depth,_,_,+below,+secondary,_,_}:
      flag(Bool.and(Bool.or(layered,U32.is_eq(a,1)),F32.is_le(N.pick(Bool.and(layered,U32.is_ne(a,1)),below,above),
        F32.add(F32.add(F32.add(1.0,x),N.pick(U32.is_eq(b,1),depth,0.0)),
          N.pick(layered,trunc(F32.mul(F32.mul(F32.add(secondary,1.0),0.5),y)),F32.mul(y,0.5))))))

@unsafe
def water(+layered: Bool,+sea: U32,+a: U32,+p: Q.Vec4,+point: Q.Point,+context: Context) -> D.Number:
  match p context:
    case Q.Vec4{+x,+y,_,_} Context{+above,+depth,_,_,_,_,+water,_}:
      +py = Q.point_axis(point,1)
      +level = D.choose(layered,D.scalar(water),D.integer(sea))
      flag(Bool.or(Bool.and(layered,F32.is_eq(water,F32.neg(2147483648.0))),
        Bool.or(Bool.and(Bool.not(layered),Bool.not(D.less(D.add(py,D.scalar(above)),level))),
          Bool.not(D.less(D.add(py,D.scalar(N.pick(U32.is_eq(a,1),above,0.0))),
            D.add(level,D.scalar(F32.add(x,F32.mul(depth,y)))))))))

@unsafe
def vertical(+a: U32,+p: Q.Vec4,+point: Q.Point,+context: Context) -> D.Number:
  match p context:
    case Q.Vec4{+x,+y,_,_} Context{+above,+depth,_,_,_,_,_,_}:
      flag(Bool.not(D.less(D.add(Q.point_axis(point,1),D.scalar(N.pick(U32.is_eq(a,1),above,0.0))),D.scalar(F32.add(x,F32.mul(depth,y))))))

@unsafe
def random_gradient(+program: U32,+p: Q.Vec4,+point: Q.Point,+low: U32,+high: U32) -> D.Number:
  match p:
    case Q.Vec4{+x,+y,_,_}: flag(F32.is_lt(F32.div(U32.to_f32(U32.and(cell_hash(point,U32.add(low,U32.mul(program,7919)),high),65535)),65535.0),
      F32.min(F32.max(D.value(D.divide(D.sub(D.scalar(y),Q.point_axis(point,1)),D.scalar(F32.max(F32.sub(y,x),1.0)))),0.0),1.0)))

@unsafe
def cold(+sea: U32,+p: Q.Vec4,+point: Q.Point) -> D.Number:
  match p:
    case Q.Vec4{+x,_,_,_}: cold_height(D.less(D.add(D.integer(sea),D.scalar(17.0)),Q.point_axis(point,1)),x,sea,point)

@unsafe
def cold_height(+above: Bool,+temperature: F32,+sea: U32,+point: Q.Point) -> D.Number:
  match above:
    case False{}: flag(F32.is_lt(temperature,0.15))
    case True{}: flag(F32.is_lt(F32.sub(temperature,F32.mul(D.value(D.add(D.scalar(F32.mul(Q.perlin(Q.scale(point,0.125,0.0),1234),8.0)),
      D.sub(Q.point_axis(point,1),D.add(D.integer(sea),D.scalar(17.0))))),0.00125)),0.15))

@unsafe
def noise_count(+p: Q.Vec4,+point: Q.Point) -> D.Number:
  match p:
    case Q.Vec4{+x,+y,+z,_}: D.scalar(F32.ceil(F32.mul(F32.add(F32.mul(Q.perlin(Q.scale(horizontal(point),F32.div(1.0,x),0.0),2345),0.625),y),z)))

@unsafe
def coverage(+count: D.Number,+salt: U32,+mode: U32,+p: Q.Vec4,+point: Q.Point,+context: Context,+sea: U32,+low: U32,+high: U32) -> D.Number:
  match p context:
    case Q.Vec4{+x,+y,+z,+w} Context{+above,_,_,_,_,_,_,_}:
      +surface = D.add(Q.point_axis(point,1),D.scalar(above))
      +lo = D.choose(U32.is_ne(U32.and(mode,2),0),surface,D.scalar(x))
      +hi = D.choose(U32.is_ne(U32.and(mode,2),0),surface,D.scalar(y))
      coverage_range(count,salt,mode,point,sea,low,high,z,w,
        D.choose(U32.is_ne(U32.and(mode,1),0),D.maximum(lo,surface),lo),
        D.choose(U32.is_ne(U32.and(mode,1),0),D.minimum(hi,D.sub(D.integer(sea),D.scalar(1.0))),hi),
        D.maximum(D.scalar(1.0),D.add(D.sub(hi,lo),D.scalar(1.0))))

@unsafe
def coverage_range(+count: D.Number,+salt: U32,+mode: U32,+point: Q.Point,+sea: U32,+low: U32,+high: U32,+radius: F32,+vertical: F32,+lo: D.Number,+hi: D.Number,+range: D.Number) -> D.Number:
  +eligible = D.maximum(D.scalar(0.0),D.add(D.sub(D.minimum(hi,D.add(Q.point_axis(point,1),D.scalar(vertical))),
    D.maximum(lo,D.sub(Q.point_axis(point,1),D.scalar(vertical)))),D.scalar(1.0)))
  coverage_active(Bool.and(D.less(D.scalar(0.0),count),D.less(D.scalar(0.0),eligible)),count,salt,point,low,high,radius,eligible,range)

@unsafe
def coverage_active(+ok: Bool,+count: D.Number,+salt: U32,+point: Q.Point,+low: U32,+high: U32,+radius: F32,+eligible: D.Number,+range: D.Number) -> D.Number:
  match ok:
    case False{}: D.scalar(0.0)
    case True{}:
      +covered = F32.sub(1.0,F32.exp(F32.neg(D.value(D.scale(D.multiply(count,
        D.minimum(D.scalar(1.0),D.divide(eligible,range))),F32.div(F32.max(1.0,F32.mul(F32.mul(3.14159265,radius),radius)),256.0))))))
      +field = F32.mul(Q.perlin(Q.scale(horizontal(point),F32.div(1.0,F32.max(2.0,F32.mul(radius,2.0))),0.0),U32.xor(U32.xor(low,N.hash(high)),salt)),0.625)
      +t = F32.min(F32.max(F32.add(field,0.5),0.0),1.0)
      flag(F32.is_lt(F32.mul(F32.mul(t,t),F32.sub(3.0,F32.mul(2.0,t))),covered))

@unsafe
def material(+op: U32,+a: U32,+b: U32,+c: U32,+p: Q.Vec4,+bands: Q.Table<U32>,+sea: U32,+layered: Bool,+program: U32,+point: Q.Point,+low: U32,+high: U32,+context: Context,values: Array<D.Number>) -> Array<D.Number> & D.Number:
  match op context:
    case 42 _: (values,band(bands,point,offset(context)))
    case 45 _: (values,stone(layered,a,b,p,context))
    case 46 _: (values,water(layered,sea,a,p,point,context))
    case 47 _: (values,vertical(a,p,point,context))
    case 48 _: (values,random_gradient(program,p,point,low,high))
    case 49 Context{_,_,+slope,_,_,_,_,_}: (values,flag(F32.is_gt(slope,3.0)))
    case 50 Context{_,+depth,_,_,_,_,_,_}: (values,flag(F32.is_le(depth,0.0)))
    case 51 _: (values,cold(sea,p,point))
    case 52 _: (values,noise_count(p,point))
    case 54 Context{_,_,_,_,_,_,_,+preliminary}: (values,flag(Bool.or(Bool.not(layered),Bool.not(D.less(Q.point_axis(point,1),D.scalar(preliminary))))))
    case _ _: operand(Array.get(D.Number,values,a),op,b,c,p,bands,sea,layered,program,point,low,high,context)

@unsafe
def offset(+context: Context) -> F32:
  match context:
    case Context{_,_,_,+offset,_,_,_,_}: offset

@unsafe
def operand(pair: Array<D.Number> & D.Number,+op: U32,+b: U32,+c: U32,+p: Q.Vec4,bands: Q.Table<U32>,+sea: U32,+layered: Bool,+program: U32,+point: Q.Point,+low: U32,+high: U32,+context: Context) -> Array<D.Number> & D.Number:
  (values,+av) = pair
  match op p:
    case 43 _: (values,flag(F32.is_eq(D.value(av),0.0)))
    case 44 Q.Vec4{+x,+y,_,_}: (values,flag(Bool.and(Bool.not(D.less(av,D.scalar(x))),Bool.not(D.less(D.scalar(y),av)))))
    case 53 _: (values,coverage(av,b,c,p,point,context,sea,low,high))
    case _ _: selected(Array.get(D.Number,values,b),op,av)

@unsafe
def selected(pair: Array<D.Number> & D.Number,+op: U32,+av: D.Number) -> Array<D.Number> & D.Number:
  (values,bv) = pair
  match op:
    case 40: (values,D.choose(F32.is_ne(D.value(av),0.0),bv,D.scalar(0.0)))
    case 41: (values,D.choose(F32.is_ne(D.value(av),0.0),av,bv))
    case _: (values,D.scalar(F32.div(0.0,0.0)))

@unsafe
def eval(+model: Q.Model,bands: Q.Table<U32>,+sea: U32,+layered: Bool,+program: U32,+instruction: Q.Instruction,+point: Q.Point,+low: U32,+high: U32,+context: Context,values: Array<D.Number>) -> Array<D.Number> & D.Number:
  match instruction:
    case Q.Instruction{+op,+a,+b,+c,+p}: eval_kind(U32.is_lt(op,40),model,bands,sea,layered,program,Q.Instruction{op,a,b,c,p},point,low,high,context,values)

@unsafe
def eval_kind(+numeric: Bool,+model: Q.Model,bands: Q.Table<U32>,+sea: U32,+layered: Bool,+program: U32,+instruction: Q.Instruction,+point: Q.Point,+low: U32,+high: U32,+context: Context,values: Array<D.Number>) -> Array<D.Number> & D.Number:
  match numeric model instruction:
    case True{} Q.Model{+noises,+points,+fields} _: Q.eval(noises,points,fields,instruction,point,low,high,values)
    case False{} _ Q.Instruction{+op,+a,+b,+c,+p}: material(op,a,b,c,p,bands,sea,layered,program,point,low,high,context,values)

@unsafe
def nodes(+model: Q.Model,+bands: Q.Table<U32>,+sea: U32,+layered: Bool,+program: U32,xs: List<&2,Q.Instruction>,+point: Q.Point,+low: U32,+high: U32,+context: Context,+i: U32,values: Array<D.Number>,roots: List<&2,U32>) -> Q.Outputs:
  match xs:
    case Nil{}: Q.outputs(6n,roots,values,[])
    case instruction <> rest: node_value(eval(model,bands,sea,layered,program,instruction,point,low,high,context,values),model,bands,sea,layered,program,rest,point,low,high,context,i,roots)

@unsafe
def node_value(pair: Array<D.Number> & D.Number,+model: Q.Model,bands: Q.Table<U32>,+sea: U32,+layered: Bool,+program: U32,rest: List<&2,Q.Instruction>,+point: Q.Point,+low: U32,+high: U32,+context: Context,+i: U32,roots: List<&2,U32>) -> Q.Outputs:
  (values,value) = pair
  nodes(model,bands,sea,layered,program,rest,point,low,high,context,U32.inc(i),Array.set(D.Number,values,i,value),roots)

@unsafe
def run(+model: Q.Model,+materials: Model,+index: U32,+point: Q.Point,+low: U32,+high: U32,+context: Context) -> Q.Outputs:
  match materials:
    case Model{+programs,+bands,+sea,+layered,_}: run_index(model,bands,sea,layered,programs,index,U32.add(index,3),point,low,high,context)

# Specialized traversal borrows only the selected program. A generic get of
# a Data payload may force ownership and copy an entire program subtree.
@unsafe
def run_index(+model: Q.Model,+bands: Q.Table<U32>,+sea: U32,+layered: Bool,programs: Q.Table<Q.Program>,+index: U32,+id: U32,+point: Q.Point,+low: U32,+high: U32,+context: Context) -> Q.Outputs:
  match programs:
    case Q.Empty{}: Q.Outputs{D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0),D.scalar(0.0)}
    case Q.Leaf{program}: run_program(model,bands,sea,layered,program,id,point,low,high,context)
    case Q.Branch{_,+a,b}: run_branch(U32.is_lt(index,Q.table_size(Q.Program,a)),model,bands,sea,layered,a,b,index,id,point,low,high,context)

@unsafe
def run_branch(left: Bool,model: Q.Model,bands: Q.Table<U32>,sea: U32,layered: Bool,+a: Q.Table<Q.Program>,b: Q.Table<Q.Program>,index: U32,id: U32,point: Q.Point,low: U32,high: U32,context: Context) -> Q.Outputs:
  match left:
    case True{}: run_index(model,bands,sea,layered,a,index,id,point,low,high,context)
    case False{}: run_index(model,bands,sea,layered,b,U32.sub(index,Q.table_size(Q.Program,a)),id,point,low,high,context)

@unsafe
def run_program(+model: Q.Model,bands: Q.Table<U32>,+sea: U32,+layered: Bool,+program: Q.Program,+id: U32,+point: Q.Point,+low: U32,+high: U32,+context: Context) -> Q.Outputs:
  match program:
    case Q.Program{+xs,+roots,+count}: nodes(model,bands,sea,layered,id,xs,point,low,high,context,0,Array.new(D.Number,P.capacity(count,1,0n),D.scalar(0.0)),roots)

# Material DAG dependencies differ from numeric operands: flags and salts must
# never be mistaken for scratch addresses. Numeric fields stay numeric-only.
@unsafe
def material_valid(+instruction: Q.Instruction,+i: U32) -> Bool:
  match instruction:
    case Q.Instruction{+op,+a,+b,+c,+p}: Bool.and(Q.finite_parameters(p),
      Bool.and(Bool.and(U32.is_ge(op,40),U32.is_le(op,54)),Bool.and(
        Bool.or(Bool.not(Bool.or(Bool.or(U32.is_eq(op,40),U32.is_eq(op,41)),Bool.or(Bool.or(U32.is_eq(op,43),U32.is_eq(op,44)),U32.is_eq(op,53)))),U32.is_lt(a,i)),
        Bool.and(Bool.or(Bool.not(Bool.or(U32.is_eq(op,40),U32.is_eq(op,41))),U32.is_lt(b,i)),material_parameters(op,a,b,c,p)))))

@unsafe
def material_parameters(+op: U32,+a: U32,+b: U32,+c: U32,+p: Q.Vec4) -> Bool:
  match op p:
    case 45 _: Bool.and(U32.is_le(a,1),U32.is_le(b,1))
    case 46 _: U32.is_le(a,1)
    case 47 _: U32.is_le(a,1)
    case 52 Q.Vec4{+x,_,_,_}: F32.is_gt(x,0.0)
    case 53 Q.Vec4{_,_,+radius,+vertical}: Bool.and(U32.is_le(c,3),Bool.and(F32.is_ge(radius,0.0),F32.is_ge(vertical,0.0)))
    case _ _: True{}

@unsafe
def valid_nodes(+model: Q.Model,xs: List<&2,Q.Instruction>,+i: U32,+limit: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case +instruction <> rest: Bool.and(Bool.or(Q.valid_instruction(model,instruction,i,limit),material_valid(instruction,i)),valid_nodes(model,rest,U32.inc(i),limit))

@unsafe
def valid_program(+model: Q.Model,+program: Q.Program,+limit: U32) -> Bool:
  match program:
    case Q.Program{+xs,+roots,+count}: Bool.and(Bool.and(U32.is_gt(count,0),U32.is_le(count,1024)),
      Bool.and(U32.is_eq(count,U32.from_nat(List.length(&2,Q.Instruction,xs))),Bool.and(Nat.is_le(List.length(&2,U32,roots),6n),
        Bool.and(Q.valid_roots(roots,count),valid_nodes(model,xs,0,limit)))))

@unsafe
def valid_programs(+model: Q.Model,xs: List<&2,Q.Program>,+limit: U32) -> Bool:
  match xs:
    case Nil{}: True{}
    case program <> rest: Bool.and(valid_program(model,program,limit),valid_programs(model,rest,limit))

# Generated columns depend on the current density/surface snapshots. Preparing
# either keeps the material programs/config, but drops their derived block runs.
def clear_columns(materials: Maybe<&2,Model>) -> Maybe<&2,Model>:
  match materials:
    case None{}: None{}
    case Some{Model{programs,bands,sea,layered,columns}}: Some{Model{programs,bands,sea,layered,MD.clear(columns)}}

# Computational dispatches need only the immutable program model, never an old
# generated column cache. The outer worker retains that cache for later queries.
def rules(materials: Model) -> Model:
  match materials:
    case Model{programs,bands,sea,layered,_}: Model{programs,bands,sea,layered,None{}}
