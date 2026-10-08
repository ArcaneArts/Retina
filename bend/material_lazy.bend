import Base
import ./density.bend as Q
import ./material_program.bend as M
import ./precise.bend as D
import ./registry.bend as P

# Immutable random-access instructions are prepared once per bulk dispatch.
# Each voxel owns its memo; branch decisions never leak to another position.
# The eager material evaluator remains the independent six-output query path.
type Program is Data:
  Program{nodes: Q.Table<Q.Instruction>,root: U32,count: U32}
type Prepared is Data:
  Prepared{programs: Q.Table<Program>,bands: Q.Table<U32>,sea: U32,layered: Bool}
type Environment is Data:
  Environment{model: Q.Model,program: Program,bands: Q.Table<U32>,sea: U32,layered: Bool,id: U32,point: Q.Point,low: U32,high: U32,context: M.Context}
type Frame is Type:
  Frame{values: Array<D.Number>,ready: Array<U32>}

def first(roots: List<&2,U32>) -> U32:
  match roots:
    case Nil{}: 0
    case root <> _: root

def program(source: Q.Program) -> Program:
  match source:
    case Q.Program{nodes,roots,count}: Program{Q.prepared_table(Q.Instruction,nodes),first(roots),count}

@unsafe
def programs(source: Q.Table<Q.Program>) -> Q.Table<Program>:
  match source:
    case Q.Empty{}: Q.Empty{}
    case Q.Leaf{value}: Q.Leaf{program(value)}
    case Q.Branch{n,a,b}: Q.Branch{n,programs(a),programs(b)}

def prepare(materials: M.Model) -> Prepared:
  match materials:
    case M.Model{source,bands,sea,layered,_}: Prepared{programs(source),bands,sea,layered}

def count(prepared: Prepared) -> U32:
  match prepared:
    case Prepared{programs,_,_,_}: Q.table_size(Program,programs)

# Immediate axes, flags and salts are not scratch dependencies. This follows
# the registered opcode contract, including spline children handled below.
def unary_dependencies(used: Bool,a: U32) -> List<&2,U32>:
  match used:
    case True{}: [a]
    case False{}: []


def binary_dependencies(used: Bool,+op: U32,a: U32,b: U32) -> List<&2,U32>:
  match used:
    case True{}: [a,b]
    case False{}: unary_dependencies(Bool.or(Bool.and(U32.is_ge(op,11),U32.is_le(op,22)),
      Bool.or(Bool.or(U32.is_eq(op,25),U32.is_eq(op,30)),Bool.or(Bool.or(U32.is_eq(op,43),U32.is_eq(op,44)),U32.is_eq(op,53)))),a)


def dependency_kind(+op: U32,a: U32,b: U32) -> List<&2,U32>:
  binary_dependencies(Bool.and(U32.is_ge(op,4),U32.is_le(op,10)),op,a,b)

def dependencies(node: Q.Instruction) -> List<&2,U32>:
  match node:
    case Q.Instruction{1,a,b,c,_}: [a,b,c]
    case Q.Instruction{23,a,b,c,_}: [a,b,c]
    case Q.Instruction{24,a,b,c,_}: [a,b,c]
    case Q.Instruction{27,a,b,c,_}: [a,b,c]
    case Q.Instruction{28,a,b,c,_}: [a,b,c]
    case Q.Instruction{31,a,b,c,_}: [a,b,c]
    case Q.Instruction{+op,+a,+b,_,_}: dependency_kind(op,a,b)

# Explicit pending operations keep GPU machine-stack use independent of graph
# depth. A legal 1024-node chain must not recursively call evaluate 1024 times.
type Task is Data:
  Visit{index: U32}
  Finish{index: U32,node: Q.Instruction}
  Select{index: U32,op: U32,a: U32,b: U32}
  Copy{index: U32,child: U32}

@unsafe
def schedule(xs: List<&2,U32>,tasks: List<&2,Task>) -> List<&2,Task>:
  match xs:
    case Nil{}: tasks
    case index <> rest: schedule(rest,Visit{index} <> tasks)

@unsafe
def spline_tasks(+n: U32,+at: U32,+points: Q.Table<Q.Vec4>,tasks: List<&2,Task>) -> List<&2,Task>:
  match n:
    case 0: tasks
    case _: spline_task(Q.point_at(points,at),n,at,points,tasks)

@unsafe
def spline_task(point: Q.Vec4,+n: U32,+at: U32,points: Q.Table<Q.Vec4>,tasks: List<&2,Task>) -> List<&2,Task>:
  match point:
    case Q.Vec4{_,_,child,_}: spline_tasks(U32.sub(n,1),U32.inc(at),points,Visit{F32.to_u32(child)} <> tasks)

@unsafe
def pending(+node: Q.Instruction,environment: Environment,tasks: List<&2,Task>) -> List<&2,Task>:
  match node environment:
    case Q.Instruction{25,_,b,c,_} Environment{Q.Model{_,points,_},_,_,_,_,_,_,_,_,_}:
      schedule(dependencies(node),spline_tasks(c,b,points,tasks))
    case _ _: schedule(dependencies(node),tasks)

@unsafe
def remember(frame: Frame,+index: U32,value: D.Number) -> Frame:
  match frame:
    case Frame{values,ready}: Frame{Array.set(D.Number,values,index,value),Array.set(U32,ready,index,1)}

@unsafe
def final_value(pair: Array<D.Number> & D.Number) -> D.Number:
  (_,value) = pair
  value

@unsafe
def loop(+environment: Environment,tasks: List<&2,Task>,frame: Frame,+root: U32) -> D.Number:
  match tasks frame:
    case Nil{} Frame{values,_}: final_value(Array.get(D.Number,values,root))
    case Visit{+index} <> rest Frame{values,ready}:
      visited(Array.get(U32,ready,index),values,environment,index,rest,root)
    case Finish{index,node} <> rest Frame{values,ready}:
      finish(node,environment,index,rest,values,ready,root)
    case Select{index,op,+a,b} <> rest Frame{values,ready}:
      choose(Array.get(D.Number,values,a),ready,environment,index,op,b,rest,root)
    case Copy{index,+child} <> rest Frame{values,ready}:
      copied(Array.get(D.Number,values,child),ready,environment,index,rest,root)

@unsafe
def visited(pair: Array<U32> & U32,values: Array<D.Number>,+environment: Environment,+index: U32,tasks: List<&2,Task>,root: U32) -> D.Number:
  (ready,flag) = pair
  match flag:
    case 0: instruction(environment,index,tasks,Frame{values,ready},root)
    case _: loop(environment,tasks,Frame{values,ready},root)

@unsafe
def instruction(+environment: Environment,+index: U32,tasks: List<&2,Task>,frame: Frame,root: U32) -> D.Number:
  match environment:
    case Environment{_,Program{nodes,_,_},_,_,_,_,_,_,_,_}:
      operation(Q.get(Q.Instruction,nodes,index,Q.Instruction{0,0,0,0,Q.Vec4{0.0,0.0,0.0,0.0}}),environment,index,tasks,frame,root)

@unsafe
def operation(+node: Q.Instruction,+environment: Environment,+index: U32,tasks: List<&2,Task>,frame: Frame,root: U32) -> D.Number:
  match node:
    case Q.Instruction{40,+a,b,_,_}: loop(environment,Visit{a} <> Select{index,40,a,b} <> tasks,frame,root)
    case Q.Instruction{41,+a,b,_,_}: loop(environment,Visit{a} <> Select{index,41,a,b} <> tasks,frame,root)
    case _: loop(environment,pending(node,environment,Finish{index,node} <> tasks),frame,root)

@unsafe
def choose(pair: Array<D.Number> & D.Number,ready: Array<U32>,environment: Environment,index: U32,op: U32,b: U32,tasks: List<&2,Task>,root: U32) -> D.Number:
  (values,+a) = pair
  chosen(F32.is_ne(D.value(a),0.0),op,a,b,environment,index,tasks,Frame{values,ready},root)

@unsafe
def chosen(selected: Bool,op: U32,a: D.Number,+b: U32,environment: Environment,+index: U32,tasks: List<&2,Task>,frame: Frame,root: U32) -> D.Number:
  match selected op:
    case False{} 40: loop(environment,tasks,remember(frame,index,D.scalar(0.0)),root)
    case True{} 41: loop(environment,tasks,remember(frame,index,a),root)
    case _ _: loop(environment,Visit{b} <> Copy{index,b} <> tasks,frame,root)

@unsafe
def copied(pair: Array<D.Number> & D.Number,ready: Array<U32>,environment: Environment,index: U32,tasks: List<&2,Task>,root: U32) -> D.Number:
  (values,value) = pair
  loop(environment,tasks,remember(Frame{values,ready},index,value),root)

@unsafe
def finish(node: Q.Instruction,+environment: Environment,index: U32,tasks: List<&2,Task>,values: Array<D.Number>,ready: Array<U32>,root: U32) -> D.Number:
  match environment:
    case Environment{model,_,bands,sea,layered,id,point,low,high,context}:
      copied(M.eval(model,bands,sea,layered,id,node,point,low,high,context,values),ready,environment,index,tasks,root)

@unsafe
def run_program(+model: Q.Model,+program: Program,bands: Q.Table<U32>,sea: U32,layered: Bool,id: U32,point: Q.Point,low: U32,high: U32,context: M.Context) -> D.Number:
  match program:
    case Program{_,+root,+count}:
      loop(Environment{model,program,bands,sea,layered,id,point,low,high,context},[Visit{root}],
        Frame{Array.new(D.Number,P.capacity(count,1,0n),D.scalar(0.0)),Array.new(U32,P.capacity(count,1,0n),0)},root)

@unsafe
def run_index(+model: Q.Model,programs: Q.Table<Program>,bands: Q.Table<U32>,sea: U32,layered: Bool,+index: U32,id: U32,point: Q.Point,low: U32,high: U32,context: M.Context) -> D.Number:
  match programs:
    case Q.Empty{}: D.scalar(0.0)
    case Q.Leaf{program}: run_program(model,program,bands,sea,layered,id,point,low,high,context)
    case Q.Branch{_,+a,b}: run_branch(U32.is_lt(index,Q.table_size(Program,a)),model,a,b,bands,sea,layered,index,id,point,low,high,context)

@unsafe
def run_branch(left: Bool,model: Q.Model,+a: Q.Table<Program>,b: Q.Table<Program>,bands: Q.Table<U32>,sea: U32,layered: Bool,index: U32,id: U32,point: Q.Point,low: U32,high: U32,context: M.Context) -> D.Number:
  match left:
    case True{}: run_index(model,a,bands,sea,layered,index,id,point,low,high,context)
    case False{}: run_index(model,b,bands,sea,layered,U32.sub(index,Q.table_size(Program,a)),id,point,low,high,context)

@unsafe
def run(+model: Q.Model,prepared: Prepared,+index: U32,point: Q.Point,low: U32,high: U32,context: M.Context) -> D.Number:
  match prepared:
    case Prepared{programs,bands,sea,layered}: run_index(model,programs,bands,sea,layered,index,U32.add(index,3),point,low,high,context)
