//! Specialize the validated resident DAG, sharing opcode expressions with the
//! interpreter. Noise parameters and world data remain in resident buffers.
use crate::program::{Instruction, Program, RegistryProgram};
use std::collections::HashMap;
use std::fmt::Write;
use std::sync::{Arc, Condvar, Mutex, Weak};
#[cfg(test)]
use std::sync::mpsc;
use std::time::Instant;
mod aquifer_columns;
#[cfg(test)]
mod compile_probe;
mod interpreter_dispatch;
pub(crate) mod interpreter_reuse;
mod material_dispatch;
mod interpreter_promotion;
pub(crate) mod incremental;
pub(crate) mod reuse_cache;
mod compile_queue;
mod compile_lifetime;
mod parallel;

#[derive(Clone, Copy, Default, serde::Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Execution {
    #[default]
    Auto,
    Interpreter,
    // Matched diagnostics wait for the real compile result; never silently
    // benchmark the interpreter when specialization failed.
    Specialized,
}

fn literal(value: f32) -> String {
    // Decimal conversion must not change registered f32 parameters.
    format!("bitcast<f32>(0x{:08x}u)", value.to_bits())
}

fn constant(n: &Instruction, values: &[Option<f32>]) -> Option<f32> {
    let a = || values[n.a as usize];
    let b = || values[n.b as usize];
    let value = match n.op {
        0 => n.p[0],
        4 => a()? + b()?,
        5 => a()? - b()?,
        6 => a()? * b()?,
        8 => a()?.min(b()?),
        9 => a()?.max(b()?),
        11 => a()?.abs(),
        12 => a()? * a()?,
        18 => -a()?,
        21 => {
            if a()? == 0.0 {
                0.0
            } else {
                a()?.signum()
            }
        }
        22 if n.p[0] <= n.p[1] => a()?.clamp(n.p[0], n.p[1]),
        40 => {
            if a()? == 0.0 {
                0.0
            } else {
                b()?
            }
        }
        41 => {
            if a()? != 0.0 {
                a()?
            } else {
                b()?
            }
        }
        43 => {
            if a()? == 0.0 {
                1.0
            } else {
                0.0
            }
        }
        44 => {
            if a()? >= n.p[0] && a()? <= n.p[1] {
                1.0
            } else {
                0.0
            }
        }
        _ => return None,
    };
    value.is_finite().then_some(value)
}

fn opcode_bodies() -> Result<HashMap<u32, &'static str>, String> {
    let source = include_str!("program.wgsl");
    let switch = source
        .find("switch op {")
        .ok_or("missing interpreter switch")?;
    let end = source[switch..]
        .find("default:{}")
        .ok_or("missing interpreter default")?
        + switch;
    let mut at = switch;
    let mut result = HashMap::new();
    while let Some(case) = source[at..end].find("case ") {
        at += case + 5;
        let number_end = source[at..].find("u:").ok_or("missing opcode number")? + at;
        let op = source[at..number_end]
            .parse()
            .map_err(|_| "invalid opcode number")?;
        let open = source[number_end..]
            .find('{')
            .ok_or("missing opcode body")?
            + number_end;
        let mut depth = 1;
        let mut close = open + 1;
        for (offset, ch) in source[open + 1..].char_indices() {
            if ch == '{' {
                depth += 1;
            }
            if ch == '}' {
                depth -= 1;
            }
            if depth == 0 {
                close = open + 1 + offset;
                break;
            }
        }
        if depth != 0 {
            return Err("unclosed interpreter opcode".into());
        }
        result.insert(op, &source[open + 1..close]);
        at = close + 1;
    }
    Ok(result)
}

fn substitute(body: &str, n: &Instruction) -> String {
    let body = body
        .replace("values[a]", &format!("v{}", n.a))
        .replace("values[b]", &format!("v{}", n.b))
        .replace("values[c]", &format!("v{}", n.c));
    let mut result = String::new();
    let mut token = String::new();
    let flush = |result: &mut String, token: &mut String| {
        match token.as_str() {
            "a" => write!(result, "{}u", n.a).unwrap(),
            "b" => write!(result, "{}u", n.b).unwrap(),
            "c" => write!(result, "{}u", n.c).unwrap(),
            _ => result.push_str(token),
        }
        token.clear();
    };
    for ch in body.chars() {
        if ch.is_ascii_alphanumeric() || ch == '_' {
            token.push(ch);
        } else {
            flush(&mut result, &mut token);
            result.push(ch);
        }
    }
    flush(&mut result, &mut token);
    result
}

struct Emitter<'a> {
    program: &'a Program,
    points: &'a [[f32; 4]],
    bodies: &'a HashMap<u32, &'static str>,
    folded: Vec<Option<f32>>,
    columns: Option<&'a [Option<usize>]>,
    lazy_branches: bool,
}
impl<'a> Emitter<'a> {
    fn new(
        program: &'a Program,
        points: &'a [[f32; 4]],
        bodies: &'a HashMap<u32, &'static str>,
        columns: Option<&'a [Option<usize>]>,
    ) -> Self {
        let mut folded = Vec::with_capacity(program.nodes.len());
        for node in &program.nodes {
            folded.push(constant(node, &folded));
        }
        let mut branch_depth = Vec::with_capacity(program.nodes.len());
        for node in &program.nodes {
            let depth = node.dependencies(points).into_iter()
                .map(|d| branch_depth[d as usize]).max().unwrap_or(0usize);
            branch_depth.push(depth + usize::from(matches!(node.op, 23 | 40 | 41)));
        }
        let lazy_branches = program.nodes.len() <= 1024
            && branch_depth.iter().all(|&depth| depth <= 32);
        Self {
            lazy_branches,
            program,
            points,
            bodies,
            folded,
            columns,
        }
    }
    fn parameters(&self, i: usize, out: &mut String) {
        writeln!(out,"let at=bytecode[16u+program*8u]+{}u;let p=vec4<f32>(bitcast<f32>(bytecode[at]),bitcast<f32>(bytecode[at+1u]),bitcast<f32>(bytecode[at+2u]),bitcast<f32>(bytecode[at+3u]));",i*8+4).unwrap();
    }
    fn emit(&self, i: usize, memo: &mut [bool], out: &mut String) -> Result<(), String> {
        if memo[i] {
            return Ok(());
        }
        let n = &self.program.nodes[i];
        if let Some(slot) = self.columns.and_then(|c| c[i]) {
            writeln!(
                out,
                "// retained node\nlet v{i}=column_field_{slot}(point,request);"
            )
            .unwrap();
        } else if let Some(value) = self.folded[i] {
            writeln!(
                out,
                "// retained node\nlet v{i}=graph_round({});",
                literal(value)
            )
            .unwrap();
        } else if self.lazy_branches && matches!(n.op, 23 | 40 | 41) {
            // Large DAGs use the interpreter's eager, topological semantics.
            // Cloning shared subgraphs into every conditional arm can otherwise
            // expand a small DAG exponentially and exceed shader nesting limits.
            // Pure DAG branches need only their selected arm. Scoped memoization
            // shares values available in a dominating scope, without pretending
            // values calculated in one arm are available in another.
            self.emit(n.a as usize, memo, out)?;
            writeln!(out, "// retained node\nvar v{i}:f32;{{").unwrap();
            self.parameters(i, out);
            let predicate = match n.op {
                23 => format!("v{}>=p.x && v{}<p.y", n.a, n.a),
                _ => format!("v{}!=0.0", n.a),
            };
            writeln!(out, "if {predicate}{{").unwrap();
            let mut branch = memo.to_vec();
            let yes = if n.op == 41 { n.a } else { n.b };
            self.emit(yes as usize, &mut branch, out)?;
            writeln!(out, "v{i}=graph_round(v{yes});}}else{{").unwrap();
            if n.op == 40 {
                writeln!(out, "v{i}=graph_round(0.0);").unwrap();
            } else {
                let no = if n.op == 23 { n.c } else { n.b };
                let mut branch = memo.to_vec();
                self.emit(no as usize, &mut branch, out)?;
                writeln!(out, "v{i}=graph_round(v{no});").unwrap();
            }
            out.push_str("}}\n");
        } else {
            for dep in n.dependencies(self.points) {
                self.emit(dep as usize, memo, out)?;
            }
            writeln!(out, "// retained node\nvar v{i}:f32;{{").unwrap();
            self.parameters(i, out);
            out.push_str("var result=0.0;\n");
            if n.op == 28 {
                writeln!(
                    out,
                    "result=interpolation_field_{}(vec3<f32>(v{},v{},v{}),request,context);",
                    n.p[0] as usize, n.a, n.b, n.c
                )
                .unwrap();
            } else if n.op == 25 {
                let entries=self.points[n.b as usize..(n.b+n.c) as usize].iter().enumerate().map(|(k,v)|format!("vec3<f32>(bitcast<f32>(bytecode[bytecode[2]+{}u]),bitcast<f32>(bytecode[bytecode[2]+{}u]),v{})",(n.b as usize+k)*4,(n.b as usize+k)*4+1,v[2] as u32)).collect::<Vec<_>>().join(",");
                writeln!(
                    out,
                    "let knots=array<vec3<f32>,{}>({entries});let x=v{};var j=0u;",
                    n.c, n.a
                )
                .unwrap();
                writeln!(
                    out,
                    "for(var k=1u;k<{}u;k++){{if x>=knots[k].x{{j=k;}}}}",
                    n.c
                )
                .unwrap();
                out.push_str("let lx=knots[j].x;let ld=knots[j].y;let lv=knots[j].z;\n");
                writeln!(
                    out,
                    "if x<lx || j=={}u {{result=lv+(x-lx)*ld;}}else{{",
                    n.c - 1
                )
                .unwrap();
                out.push_str("let rx=knots[j+1u].x;let rd=knots[j+1u].y;let rv=knots[j+1u].z;let span=rx-lx;let t=(x-lx)/span;let delta=rv-lv;result=mix(lv,rv,t)+t*(1.0-t)*mix(ld*span-delta,-rd*span+delta,t);}\n");
            } else {
                out.push_str(&substitute(
                    self.bodies
                        .get(&n.op)
                        .ok_or_else(|| format!("unsupported specialization opcode {}", n.op))?,
                    n,
                ));
            }
            writeln!(out, "v{i}=graph_round(result);}}").unwrap();
        }
        memo[i] = true;
        Ok(())
    }
}
#[cfg(test)]
fn graph(
    program: &Program,
    points: &[[f32; 4]],
    bodies: &HashMap<u32, &'static str>,
) -> Result<String, String> {
    graph_columns(program, points, bodies, None)
}
fn graph_columns(
    program: &Program,
    points: &[[f32; 4]],
    bodies: &HashMap<u32, &'static str>,
    columns: Option<&[Option<usize>]>,
) -> Result<String, String> {
    let emitter = Emitter::new(program, points, bodies, columns);
    let roots: [u32; 6] = std::array::from_fn(|i| program.roots.get(i).copied().unwrap_or(0));
    let mut out = String::new();
    let mut memo = vec![false; program.nodes.len()];
    for root in roots {
        emitter.emit(root as usize, &mut memo, &mut out)?;
    }
    writeln!(
        out,
        "return array<f32,6>({});",
        roots.map(|r| format!("v{r}")).join(",")
    )
    .unwrap();
    Ok(out)
}

#[cfg(test)]
pub(crate) fn source(program: &RegistryProgram) -> Result<String, String> {
    source_columns(program).map(|s| s.0)
}
#[cfg(test)]
fn source_columns(program: &RegistryProgram) -> Result<(String, u32), String> {
    source_columns_options(program, false)
}

#[cfg(test)]
pub(crate) fn source_with_aquifer_columns(program: &RegistryProgram) -> Result<String, String> {
    source_columns_options(program, true).map(|s| s.0)
}

fn source_columns_options(
    program: &RegistryProgram,
    aquifer_columns: bool,
) -> Result<(String, u32), String> {
    let bodies = opcode_bodies()?;
    // Direct-height terrain has no density lattice and never dispatches the
    // horizontal column prepass. Emit its graph directly instead of expanding
    // thousands of raw-column fallbacks for a cache that cannot be populated.
    let columns = if program.surface[2] == 0 {
        crate::column_program::Plan::new(program)
    } else {
        crate::column_program::Plan::for_programs(program, |_| false)
    };
    let graphs = program.all_programs().collect::<Vec<_>>();
    let mut shared = HashMap::<String, usize>::new();
    // Preserve the interpreter's f32 node boundary through a dynamic integer
    // identity. The validated descriptor count makes this xor exactly zero.
    let mut functions = format!(
        "fn program_zero()->u32{{return bytecode[0]-{}u;}}\nfn graph_round(value:f32)->f32{{return bitcast<f32>(bitcast<u32>(value)^program_zero());}}\n",
        program.all_programs().count()
    );
    let fields = columns.owners.len();
    for (field, interpolation) in program.interpolations.iter().enumerate() {
        let body = graph_columns(
            &interpolation.input,
            &program.points,
            &bodies,
            Some(&columns.slots[program.programs.len() + field]),
        )?;
        writeln!(functions,"fn interpolation_graph_{field}(point:vec3<f32>,request:Request,context:vec4<f32>,program:u32)->array<f32,6>{{\n{body}}}").unwrap();
        functions.push_str(&crate::program::interpolation::specialized(field));
    }
    functions.push_str(&crate::program::interpolation::prepass(
        program.interpolation_depth(),
        true,
        program.interpolations.len(),
    ));
    // The cache occupies only additional GPU scratch after the existing density,
    // surface and lake lattices. It never enters a host readback buffer.
    functions.push_str("fn column_offset(r:Request)->u32{return lake_probe_offset(r)+lake_side(r)*lake_side(r)*20u*density_layers(r);}\n");
    for (slot, &(pid, node)) in columns.owners.iter().enumerate() {
        let p = graphs[pid];
        let emitter = Emitter::new(p, &program.points, &bodies, None);
        let mut raw = String::new();
        emitter.emit(node, &mut vec![false; p.nodes.len()], &mut raw)?;
        writeln!(functions,"fn raw_column_{slot}(point:vec3<f32>,request:Request)->f32{{let context=vec4<f32>(0.0);let program={pid}u;\n{raw}return v{node};}}").unwrap();
        writeln!(functions,"fn column_field_{slot}(point:vec3<f32>,r:Request)->f32{{if r.density_side>0u{{let local=(point.xz-vec2<f32>(density_origin(r).xz))/f32(r.density_step_xz);let cell=vec2<i32>(local);if all(local==vec2<f32>(cell)) && all(cell>=vec2<i32>(0)) && all(cell<vec2<i32>(i32(r.density_side))){{let grid=density_origin(r).xz+cell*i32(r.density_step_xz);if all(point.xz==vec2<f32>(grid)){{return surface_nodes[column_offset(r)+(u32(cell.y)*r.density_side+u32(cell.x))*{fields}u+{slot}u];}}}}}}return raw_column_{slot}(point,r);}}").unwrap();
    }
    let owners = columns
        .owners
        .iter()
        .map(|o| o.0)
        .collect::<std::collections::BTreeSet<_>>();
    for &pid in &owners {
        let p = graphs[pid];
        let emitter = Emitter::new(p, &program.points, &bodies, None);
        let mut body = String::new();
        let mut memo = vec![false; p.nodes.len()];
        for (slot, &(owner, node)) in columns
            .owners
            .iter()
            .enumerate()
            .filter(|(_, o)| o.0 == pid)
        {
            debug_assert_eq!(owner, pid);
            emitter.emit(node, &mut memo, &mut body)?;
            writeln!(
                body,
                "surface_nodes[column_offset(request)+node*{fields}u+{slot}u]=v{node};"
            )
            .unwrap();
        }
        writeln!(functions,"fn store_columns_{pid}(point:vec3<f32>,request:Request,node:u32){{let context=vec4<f32>(0.0);let program={pid}u;\n{body}}}").unwrap();
    }
    functions.push_str("@compute @workgroup_size(64) fn horizontal_nodes(@builtin(global_invocation_id) id:vec3<u32>){let r=requests[id.y];if id.x>=r.density_side*r.density_side{return;}let origin=density_origin(r);let point=vec3<f32>(f32(origin.x+i32(id.x%r.density_side)*i32(r.density_step_xz)),0.0,f32(origin.z+i32(id.x/r.density_side)*i32(r.density_step_xz)));\n");
    for pid in owners {
        writeln!(functions, "store_columns_{pid}(point,r,id.x);").unwrap();
    }
    functions.push_str("}\n");
    let mut indices = Vec::new();
    for (pid, p) in program.programs.iter().enumerate() {
        let body = graph_columns(p, &program.points, &bodies, Some(&columns.slots[pid]))?;
        let next = shared.len();
        let index = *shared.entry(body.clone()).or_insert_with(|| {
            writeln!(functions, "fn graph_{next}(point:vec3<f32>,request:Request,context:vec4<f32>,program:u32)->array<f32,6>{{\n{body}}}").unwrap();
            next
        });
        indices.push(index);
    }
    for (i, name) in ["run_climate", "run_surface_density", "run_final_density"]
        .iter()
        .enumerate()
    {
        writeln!(functions, "fn {name}(point:vec3<f32>,request:Request,context:vec4<f32>)->array<f32,6>{{return graph_{}(point,request,context,{i}u);}}", indices[i]).unwrap();
    }
    if let Some(a) = program.aquifer.as_ref().filter(|a| a.enabled) {
        for slot in 0..5 {
            let pid = a.program as usize + slot;
            writeln!(functions,"fn run_aquifer_{slot}(point:vec3<f32>,request:Request,context:vec4<f32>)->array<f32,6>{{return graph_{}(point,request,context,{pid}u);}}",indices[pid]).unwrap();
        }
    }
    if aquifer_columns {
        functions.push_str(&self::aquifer_columns::source(program, &bodies)?);
    }
    functions.push_str("fn run_program(program:u32,point:vec3<f32>,request:Request,context:vec4<f32>)->array<f32,6>{switch program{\n");
    // Density and aquifer stages have direct calls. The only remaining dynamic
    // selector is 3 + biome; exposing aquifer graphs here makes every material
    // pipeline compile unreachable fluid/preliminary-density branches.
    let material_end = program
        .aquifer
        .as_ref()
        .filter(|a| a.enabled)
        .map_or(indices.len(), |a| a.program as usize);
    for (i, f) in indices.iter().take(material_end).enumerate().skip(3) {
        writeln!(
            functions,
            "case {i}u:{{return graph_{f}(point,request,context,program);}}"
        )
        .unwrap();
    }
    functions.push_str("default:{return array<f32,6>();}}}\n");
    let interpreter = include_str!("program.wgsl");
    let start = interpreter
        .find("fn run_program(")
        .ok_or("missing interpreter start")?;
    let end = interpreter
        .find("fn density_floor_div(")
        .ok_or("missing interpreter end")?;
    Ok((
        format!(
            "{}\n{}\n{}\n{}",
            &interpreter[..start],
            functions,
            crate::program::density_bounds_source(
                program.interpreter_capacity()
            ),
            &interpreter[end..]
        ),
        fields as u32,
    ))
}

#[derive(Clone)]
pub(crate) struct Pipelines {
    pub kind: &'static str,
    pub complete: bool,
    world_kinds: HashMap<String, &'static str>,
    cave_kinds: HashMap<String, &'static str>,
    world: HashMap<String, wgpu::ComputePipeline>,
    cave: HashMap<String, wgpu::ComputePipeline>,
    pub horizontal_fields: u32,
    reuse: Option<interpreter_reuse::Plan>,
    dispatches: HashMap<String, u32>,
    promotion: Option<Arc<State>>,
    optimization_progress: Option<Arc<Progress>>,
}

#[derive(Clone, Copy)]
pub(crate) struct Selected<'a> {
    pub pipeline: &'a wgpu::ComputePipeline,
    pub dispatch: Option<u32>,
}
impl<'a> From<&'a wgpu::ComputePipeline> for Selected<'a> {
    fn from(pipeline: &'a wgpu::ComputePipeline) -> Self {
        Self {
            pipeline,
            dispatch: None,
        }
    }
}
impl Selected<'_> {
    pub fn bind(self, pass: &mut wgpu::ComputePass<'_>) {
        pass.set_pipeline(self.pipeline);
        if let Some(mode) = self.dispatch {
            pass.set_immediates(0, bytemuck::bytes_of(&mode));
        }
    }
}
impl Pipelines {
    pub fn world_kind(&self, entry: &str) -> &'static str {
        if !self.world.contains_key(entry) || self.reuse.is_some_and(|p| p.world_reuses(entry)) { "fallback" }
        else { self.world_kinds.get(entry).copied().unwrap_or(self.kind) }
    }
    pub fn cave_kind(&self, entry: &str) -> &'static str {
        if !self.cave.contains_key(entry) || self.reuse.is_some_and(|p| p.cave_reuses(entry)) { "fallback" }
        else { self.cave_kinds.get(entry).copied().unwrap_or(self.kind) }
    }
    /// Overlay the loaded profile's exact compatibility mask on complete generic
    /// pipelines. Pipeline clones retain the same device objects and selectors.
    pub fn with_reuse(&self, reuse: interpreter_reuse::Plan) -> Self {
        Self {
            kind: self.kind,
            complete: self.complete,
            world_kinds: self.world_kinds.clone(), cave_kinds: self.cave_kinds.clone(),
            world: self.world.clone(),
            cave: self.cave.clone(),
            horizontal_fields: self.horizontal_fields,
            reuse: Some(reuse),
            dispatches: self.dispatches.clone(),
            promotion: self.promotion.clone(),
            optimization_progress: self.optimization_progress.clone(),
        }
    }
    pub fn world<'a>(&'a self, entry: &str) -> &'a wgpu::ComputePipeline {
        &self.world[entry]
    }
    pub fn cave<'a>(&'a self, entry: &str) -> &'a wgpu::ComputePipeline {
        &self.cave[entry]
    }
    pub fn world_selected(&self, entry: &str) -> Selected<'_> {
        Selected {
            pipeline: self.world(entry),
            dispatch: self.dispatches.get(entry).copied(),
        }
    }
    fn cave_selected(&self, entry: &str) -> Selected<'_> {
        Selected {
            pipeline: self.cave(entry),
            dispatch: self.dispatches.get(entry).copied(),
        }
    }
    pub fn world_or<'a>(&'a self, entry: &str, base: impl Into<Selected<'a>>) -> Selected<'a> {
        if !self.complete && !self.world.contains_key(entry) || self.reuse.is_some_and(|p| p.world_reuses(entry)) {
            base.into()
        } else {
            self.world_selected(entry)
        }
    }
    pub fn cave_or<'a>(&'a self, entry: &str, base: impl Into<Selected<'a>>) -> Selected<'a> {
        if !self.complete && !self.cave.contains_key(entry) || self.reuse.is_some_and(|p| p.cave_reuses(entry)) {
            base.into()
        } else {
            self.cave_selected(entry)
        }
    }
    pub fn cached_cave(&self, entry: &str) -> Option<&wgpu::ComputePipeline> {
        self.cave.get(&format!("{entry}_cached"))
    }
    pub fn cached_world(&self, entry: &str) -> Option<&wgpu::ComputePipeline> {
        self.world.get(&format!("{entry}_cached"))
    }
}
#[derive(Default)]
pub(crate) struct State {
    result: Mutex<Option<Result<Arc<Pipelines>, String>>>,
    partial: Mutex<Option<Arc<Pipelines>>>,
    done: Condvar,
    pub progress: Arc<Progress>,
}
#[derive(Default)]
pub(crate) struct Progress {
    status: std::sync::atomic::AtomicU32,
    nanos: std::sync::atomic::AtomicU64,
    hits: std::sync::atomic::AtomicU64,
    source_bytes: u64,
    nodes: u32,
    emitted: u32,
    graphs: u32,
    horizontal_fields: u32,
    groups_ready: std::sync::atomic::AtomicU32,
    groups_total: std::sync::atomic::AtomicU32,
}
impl Progress {
    pub fn group_snapshot(&self) -> crate::pipeline::SpecializationSnapshot {
        use std::sync::atomic::Ordering;
        crate::pipeline::SpecializationSnapshot { version: 1, status: self.status.load(Ordering::Acquire),
            ready_groups: self.groups_ready.load(Ordering::Acquire), total_groups: self.groups_total.load(Ordering::Relaxed),
            compile_nanos: self.nanos.load(Ordering::Relaxed), source_bytes: self.source_bytes }
    }
    pub fn snapshot(&self) -> crate::pipeline::ProgramSnapshot {
        use std::sync::atomic::Ordering;
        crate::pipeline::ProgramSnapshot {
            version: 1,
            status: self.status.load(Ordering::Acquire),
            compile_nanos: self.nanos.load(Ordering::Relaxed),
            source_bytes: self.source_bytes,
            nodes: self.nodes,
            emitted: self.emitted,
            graphs: self.graphs,
            horizontal_fields: self.horizontal_fields,
            cache_hits: self.hits.load(Ordering::Relaxed),
            ..Default::default()
        }
    }
}
impl State {
    pub fn ready(&self, wait: bool) -> Result<Option<Arc<Pipelines>>, String> {
        let mut result = self.result.lock().unwrap_or_else(|e| e.into_inner());
        while wait && result.is_none() {
            result = self.done.wait(result).unwrap_or_else(|e| e.into_inner());
        }
        if !wait && !matches!(result.as_ref(), Some(Ok(_))) {
            if let Some(partial) = self.partial.lock().unwrap_or_else(|e| e.into_inner()).clone() { return Ok(Some(partial)); }
        }
        result.as_ref().cloned().transpose()
    }
}

struct Job {
    interpreter: Option<Option<interpreter_reuse::Plan>>,
    shared_dispatch: bool,
    policy: crate::startup::InterpreterPolicy,
    promotion: Option<Arc<State>>,
    incremental: bool,
    program: String,
    state: Weak<State>,
    horizontal_fields: u32,
    material_layers: bool,
    aquifers: u8,
    terrain: bool,
    cached_masks: bool,
    cached_nodes: bool,
    lake_point_cache: bool,
    material_dispatch: bool,
    specialized_interpolation: bool,
    #[cfg(test)]
    reference_specialization: bool,
}
pub(crate) struct Compiler {
    sender: compile_queue::Sender<Job>,
    // Exact generated source is the fingerprint key; HashMap also checks key
    // equality, so a hash collision cannot reuse an unrelated graph pipeline.
    cache: reuse_cache::Cache<(String, bool), State>,
    material_dispatch: bool,
    specialized_interpolation: bool,
    tiered_interpreter: bool,
    incremental_specialization: bool,
    optimized_interpreters: reuse_cache::Cache<(String, Option<interpreter_reuse::Plan>, bool), State>,
    terrain_fast_cache: Option<Arc<crate::pipeline_cache::Cache>>,
    parallel: Option<Arc<parallel::Caches>>,
    #[cfg(test)]
    pub(crate) reference_specialization: bool,
}
impl Compiler {
    pub(crate) fn prune(&mut self) {
        self.cache.retain_warm(|state| !state.pending());
        self.optimized_interpreters.retain_warm(|state| !state.pending());
    }
    #[cfg(test)]
    pub(crate) fn cache_inventory(&self) -> serde_json::Value { self.cache.inventory() }

    pub fn new(
        device: &wgpu::Device,
        world: &wgpu::BindGroupLayout,
        cave: &wgpu::BindGroupLayout,
        material_dispatch: bool,
        specialized_interpolation: bool,
        device_failure: impl Fn() -> Option<String> + Send + Sync + 'static,
        driver_cache: Option<Arc<crate::pipeline_cache::Cache>>,
    ) -> Result<Self, String> {
        let device = device.clone();
        let tiered_interpreter = device.adapter_info().backend == wgpu::Backend::Vulkan
            && std::env::var("RETINA_VULKAN_TIERED_INTERPRETER").as_deref() == Ok("1");
        let incremental_specialization = std::env::var("RETINA_INCREMENTAL_SPECIALIZATION").as_deref() == Ok("1");
        let sequential_fast = tiered_interpreter && !crate::compile_budget::parallel_enabled();
        let fast_cache = sequential_fast.then(|| crate::pipeline_cache::Cache::open_variant(&device, "compiler", "fast-interpreter")).flatten();
        let terrain_fast_cache = sequential_fast.then(|| crate::pipeline_cache::Cache::open_variant(&device, "terrain", "fast-interpreter")).flatten();
        let parallel = crate::compile_budget::parallel_enabled().then(|| Arc::new(parallel::Caches::new(&device, "terrain-parallel", tiered_interpreter)));
        let world = world.clone();
        let cave = cave.clone();
        let (sender, receiver) = compile_queue::channel::<Job>(32, 64 * 1024 * 1024);
        let receiver = Arc::new(receiver);
        let device_failure = Arc::new(device_failure);
        for worker in 0..if crate::compile_budget::parallel_enabled() { 2 } else { 1 } {
            // The original worker can process every job. The extra required-only
            // lane prevents a long optional bundle from delaying a new preload.
            let lane = if worker == 0 { compile_queue::Lane::Any } else { compile_queue::Lane::Required };
            let device = device.clone(); let world = world.clone(); let cave = cave.clone();
            let receiver = receiver.clone(); let device_failure = device_failure.clone();
            let driver_cache = driver_cache.clone(); let fast_cache = fast_cache.clone();
            let worker_parallel = crate::compile_budget::parallel_enabled().then(|| parallel::Caches::new(&device, &format!("compiler-parallel-{worker}"), tiered_interpreter));
            let spawned = std::thread::Builder::new()
                .name(if worker == 1 { "retina-startup-compiler" } else { "retina-shader-compiler" }.into())
                .spawn(move || {
                    while let Some(job) = receiver.recv(lane) {
                        // Evicted jobs with no live world do not keep compiling after close.
                        let Some(state) = job.state.upgrade() else { continue; };
                        let start = Instant::now();
                        let priority = if compile_queue::Work::required(&job) { crate::compile_budget::Priority::Required } else { crate::compile_budget::Priority::Optional };
                        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| compile_lifetime::with_owner(Some(Arc::downgrade(&state)), || crate::compile_budget::with_priority(priority, || crate::startup::with_interpreter_policy(job.policy, || {
                            if let Some(error) = device_failure() { return Err(error); }
                            if let Some(reuse) = job.interpreter {
                                compile_interpreter(
                                    &device,
                                    &world,
                                    &cave,
                                    &job.program,
                                    reuse,
                                    job.shared_dispatch,
                                    if job.policy == crate::startup::InterpreterPolicy::Fast { fast_cache.as_deref() } else { driver_cache.as_deref() },
                                    worker_parallel.as_ref().filter(|_| priority == crate::compile_budget::Priority::Required),
                                )
                            } else {
                                compile_program(
                                    &device,
                                    &world,
                                    &cave,
                                    &job.program,
                                    job.horizontal_fields,
                                    job.material_layers,
                                    job.aquifers,
                                    true,
                                    job.terrain,
                                    job.cached_masks,
                                    job.cached_nodes,
                                    job.lake_point_cache,
                                    None,
                                    false,
                                    job.material_dispatch,
                                    job.specialized_interpolation,
                                    driver_cache.as_deref(),
                                    job.incremental.then_some(&state),
                                    parallel::Part::All,
                                    #[cfg(test)]
                                    !job.reference_specialization,
                                )
                            }
                        })))))
                        .unwrap_or_else(|_| Err("specialized GPU compilation panicked".into()));
                        let result = result.map(|mut pipelines| {
                            pipelines.promotion = job.promotion;
                            if job.policy == crate::startup::InterpreterPolicy::Optimized {
                                pipelines.optimization_progress = Some(state.progress.clone());
                            }
                            pipelines.kind = match job.policy {
                                crate::startup::InterpreterPolicy::Fast => "interpreter-fast",
                                crate::startup::InterpreterPolicy::Optimized => "interpreter-optimized",
                                crate::startup::InterpreterPolicy::Inherited => pipelines.kind,
                            };
                            crate::startup::message(format_args!("Compilation ready policy={:?} interpreter={} source_bytes={} elapsed_ms={:.3}",
                                job.policy, job.interpreter.is_some(), job.program.len(), start.elapsed().as_secs_f64()*1000.0));
                            pipelines
                        });
                        if let Err(error) = &result {
                            if error == "GPU compilation retired after its last profile owner closed" {
                                crate::startup::message(error);
                            } else if device_failure().is_some() {
                                eprintln!("Retina stops shader compilation after device failure: {error}");
                            } else {
                                eprintln!("Retina keeps the GPU interpreter: {error}");
                            }
                        }
                        state.progress.nanos.store(
                            start.elapsed().as_nanos().min(u64::MAX as u128) as u64,
                            std::sync::atomic::Ordering::Relaxed,
                        );
                        let status = if result.is_ok() {
                            if job.terrain { 2 } else { 4 }
                        } else {
                            3
                        };
                        let succeeded = result.is_ok();
                        *state.result.lock().unwrap_or_else(|e| e.into_inner()) =
                            Some(result.map(Arc::new));
                        if succeeded { *state.partial.lock().unwrap_or_else(|e| e.into_inner()) = None; }
                        state
                            .progress
                            .status
                            .store(status, std::sync::atomic::Ordering::Release);
                        state.done.notify_all();
                    }
                });
            if let Err(error) = spawned {
                if worker == 0 { return Err(format!("cannot start GPU shader compiler: {error}")); }
                crate::startup::info(format_args!("Additional startup compiler unavailable; keeping the existing worker: {error}"));
            }
        }
        Ok(Self {
            sender,
            cache: reuse_cache::Cache::new(8),
            material_dispatch,
            specialized_interpolation,
            tiered_interpreter,
            incremental_specialization,
            optimized_interpreters: reuse_cache::Cache::new(8),
            terrain_fast_cache,
            parallel,
            #[cfg(test)]
            reference_specialization: false,
        })
    }
    /// Prepare a profile-independent fallback after device startup, overlapping
    /// native registry parsing. The same compiler thread preserves job ordering.
    pub fn compact_interpreter(&mut self, composition: bool) -> Result<Arc<State>, String> {
        let source = crate::program::interpreter_source_density(
            crate::program::COMPACT_VALUES,
            1,
            composition,
        );
        #[cfg(test)]
        let source = if std::env::var("RETINA_PRELOAD_SOURCE_ERROR").as_deref() == Ok("1") {
            format!("{source}\ninvalid_preload_shader_source")
        } else {
            source
        };
        let state = Arc::new(State {
            progress: Arc::new(Progress {
                status: std::sync::atomic::AtomicU32::new(1),
                source_bytes: source.len() as u64,
                ..Default::default()
            }),
            ..Default::default()
        });
        let reuse = Some(interpreter_reuse::Plan::density_preload(composition));
        let (promotion, optimized_job) = if self.tiered_interpreter {
            let (state, job) = self.prepare_optimized(&source, reuse, true);
            (Some(state), job)
        } else { (None, None) };
        self.sender
            .send(Job {
                interpreter: Some(reuse),
                shared_dispatch: true,
                policy: if self.tiered_interpreter { crate::startup::InterpreterPolicy::Fast } else { crate::startup::InterpreterPolicy::Inherited },
                promotion,
                incremental: false,
                program: source,
                state: Arc::downgrade(&state),
                horizontal_fields: 0,
                material_layers: true,
                aquifers: 2,
                terrain: true,
                cached_masks: false,
                cached_nodes: false,
                lake_point_cache: false,
                material_dispatch: false,
                specialized_interpolation: false,
                #[cfg(test)]
                reference_specialization: false,
            })?;
        if let Some(job) = optimized_job {
            if let Err(error) = self.sender.send(job) { crate::startup::info(format_args!("Optional interpreter optimization not queued: {error}")); }
        }
        Ok(state)
    }
    pub fn request(
        &mut self,
        program: &RegistryProgram,
        terrain: bool,
        composition: bool,
        cached_masks: bool,
        lake_point_cache: bool,
        lake_primed_corners: bool,
        lake_sparse_fields: bool,
        aquifer_columns: bool,
    ) -> Result<Arc<State>, String> {
        let prepare = crate::startup::Stage::new("prepare specialized registry shader source");
        let (mut source, horizontal_fields) = source_columns_options(program, aquifer_columns)?;
        source = crate::program::density_composition_source(&source, composition);
        if program.interpreter_capacity() > 1024 || program.interpolation_depth() > 2 {
            source.push_str("\n// retina large graph call boundaries\n");
        }
        drop(prepare);
        let cached_nodes = composition
            && cached_masks
            && crate::program::composition::CachedDensity::for_nodes(program).is_some();
        let cached_masks = composition
            && cached_masks
            && crate::program::composition::CachedDensity::new(program).is_some();
        if cached_masks {
            source.push_str("\n// direct resident density mask, surface and lattice pipelines\n");
        }
        if cached_nodes {
            source.push_str("\n// resident density node pipeline\n");
        }
        let lake_point_cache =
            composition && lake_point_cache && !program.interpolations.is_empty();
        if lake_point_cache {
            source.push_str("\n// invocation-local lake interpolation cache\n");
            if lake_primed_corners {
                writeln!(
                    source,
                    "\n{}",
                    crate::program::interpolation::PRIMED_CORNERS_SOURCE_KEY
                )
                .unwrap();
            }
        }
        if composition && lake_sparse_fields {
            if let Some(plan) = crate::program::lake_sparse::Plan::new(program) {
                source.push_str(&plan.source(horizontal_fields));
            }
        }
        // The entry-point set is part of pipeline identity even when graphs match.
        writeln!(source, "// material pipelines: {}", program.material_layers).unwrap();
        writeln!(
            source,
            "// specialized interpolation prepasses: {}",
            self.specialized_interpolation
        )
        .unwrap();
        writeln!(
            source,
            "// shared material dispatch: {}",
            self.material_dispatch
        )
        .unwrap();
        writeln!(
            source,
            "// aquifer pipelines: {}",
            program
                .aquifer
                .as_ref()
                .map_or(0, |a| if a.enabled { 2 } else { 1 })
        )
        .unwrap();
        if self.incremental_specialization { source.push_str("\n// incremental specialization cohorts\n"); }
        let key = (source, terrain);
        if let Some(state) = self.cache.get(&key) {
            state
                .progress
                .hits
                .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            return Ok(state.clone());
        }
        let state = Arc::new(State {
            progress: Arc::new(Progress {
                status: std::sync::atomic::AtomicU32::new(1),
                source_bytes: key.0.len() as u64,
                groups_total: std::sync::atomic::AtomicU32::new(if self.incremental_specialization { 3 } else { 0 }),
                nodes: program.all_programs().map(|p| p.nodes.len() as u32).sum(),
                emitted: key.0.matches("// retained node\n").count() as u32,
                graphs: key.0.matches("fn graph_").count() as u32 - 1,
                horizontal_fields,
                ..Default::default()
            }),
            ..Default::default()
        });
        self.sender
            .send(Job {
                interpreter: None,
                shared_dispatch: false,
                policy: crate::startup::InterpreterPolicy::Inherited,
                promotion: None,
                incremental: self.incremental_specialization,
                program: key.0.clone(),
                state: Arc::downgrade(&state),
                horizontal_fields,
                material_layers: program.material_layers,
                aquifers: program
                    .aquifer
                    .as_ref()
                    .map_or(0, |a| if a.enabled { 2 } else { 1 }),
                terrain,
                cached_masks,
                cached_nodes,
                lake_point_cache,
                material_dispatch: self.material_dispatch,
                specialized_interpolation: self.specialized_interpolation,
                #[cfg(test)]
                reference_specialization: self.reference_specialization,
            })?;
        self.cache.insert(key, state.clone());
        Ok(state)
    }
}

#[cfg(test)]
pub(crate) fn compile(
    device: &wgpu::Device,
    world: &wgpu::BindGroupLayout,
    cave: &wgpu::BindGroupLayout,
    program: &str,
    horizontal_fields: u32,
    material_layers: bool,
    aquifers: u8,
) -> Result<Pipelines, String> {
    compile_program(
        device,
        world,
        cave,
        program,
        horizontal_fields,
        material_layers,
        aquifers,
        true,
        true,
        false,
        false,
        false,
        None,
        false,
        false,
        false,
        None,
        None,
        parallel::Part::All,
        #[cfg(test)]
        true,
    )
}

pub(crate) fn compile_interpreter(
    device: &wgpu::Device,
    world: &wgpu::BindGroupLayout,
    cave: &wgpu::BindGroupLayout,
    program: &str,
    reuse: Option<interpreter_reuse::Plan>,
    shared_dispatch: bool,
    driver_cache: Option<&crate::pipeline_cache::Cache>,
    parallel: Option<&parallel::Caches>,
) -> Result<Pipelines, String> {
    if let Some(caches) = parallel {
        return parallel::compile(device, world, cave, program, reuse, shared_dispatch, caches);
    }
    compile_interpreter_part(device, world, cave, program, reuse, shared_dispatch, driver_cache, parallel::Part::All)
}

fn compile_interpreter_part(device: &wgpu::Device, world: &wgpu::BindGroupLayout,
    cave: &wgpu::BindGroupLayout, program: &str, reuse: Option<interpreter_reuse::Plan>,
    shared_dispatch: bool, driver_cache: Option<&crate::pipeline_cache::Cache>, part: parallel::Part,
) -> Result<Pipelines, String> {
    compile_program(
        device,
        world,
        cave,
        program,
        0,
        true,
        2,
        false,
        true,
        false,
        false,
        false,
        reuse,
        shared_dispatch,
        false,
        false,
        driver_cache,
        None,
        part,
        #[cfg(test)]
        true,
    )
}

fn compile_program(
    device: &wgpu::Device,
    world_layout: &wgpu::BindGroupLayout,
    cave: &wgpu::BindGroupLayout,
    program: &str,
    horizontal_fields: u32,
    material_layers: bool,
    aquifers: u8,
    specialized: bool,
    terrain: bool,
    cached_masks: bool,
    cached_nodes: bool,
    lake_point_cache: bool,
    reuse: Option<interpreter_reuse::Plan>,
    shared_dispatch: bool,
    material_dispatch: bool,
    specialized_interpolation: bool,
    driver_cache: Option<&crate::pipeline_cache::Cache>,
    incremental: Option<&Arc<State>>,
    part: parallel::Part,
    #[cfg(test)] outline_specialized: bool,
) -> Result<Pipelines, String> {
    let _startup = crate::startup::Stage::new(format!("compile world pipelines specialized={specialized} terrain={terrain} program_bytes={} part={part:?}", program.len()));
    let scope = device.push_error_scope(wgpu::ErrorFilter::Validation);
    #[cfg(test)]
    let probe = compile_probe::Probe::new(specialized);
    #[cfg(test)]
    let total_start = Instant::now();
    let result = (|| {
        let dispatches = std::cell::RefCell::new(HashMap::new());
        let root_program = program;
        let root_world_module = std::cell::RefCell::new(None::<(String, wgpu::ShaderModule)>);
        let make = |program: &str,
                        prefix: &str,
                        suffix: &str,
                        layout: &wgpu::BindGroupLayout,
                        entries: &[&str]|
         -> Result<HashMap<String, wgpu::ComputePipeline>, String> {
            compile_lifetime::check()?;
            if entries.is_empty() {
                return Ok(HashMap::new());
            }
            let cave_program;
            let program = if prefix == include_str!("caves.wgsl") {
                cave_program = crate::program::lake_sparse::without_source(program);
                cave_program.as_ref()
            } else {
                program
            };
            let source = format!(
                "{prefix}\n{}\n{program}\n{suffix}",
                include_str!("climate.wgsl")
            );
            let wrapper = if prefix == include_str!("simplex.wgsl") {
                "retina_world_dispatch"
            } else {
                "retina_cave_dispatch"
            };
            let shared_materials = material_dispatch
                && specialized
                && prefix == include_str!("caves.wgsl")
                && entries.contains(&"material_counts")
                && entries.contains(&"material_emit");
            let source = if shared_dispatch {
                interpreter_dispatch::source(source, entries, wrapper)?
            } else if shared_materials {
                material_dispatch::source(source)?
            } else {
                source
            };
            let compiled_entries = if shared_dispatch {
                vec![wrapper]
            } else if shared_materials {
                let mut compiled = entries
                    .iter()
                    .copied()
                    .filter(|e| !matches!(*e, "material_counts" | "material_emit"))
                    .collect::<Vec<_>>();
                compiled.push(material_dispatch::ENTRY);
                compiled
            } else {
                entries.to_vec()
            };
            let source = if specialized {
                let source = static_calls(&source);
                #[cfg(not(test))]
                let outline_specialized = true;
                if outline_specialized {
                    let large = source.contains("// retina large graph call boundaries");
                    let source = crate::program::outline::specialized(source);
                    if large { source.replace("retina_noinline_", "retina_noinline_large_") } else { source }
                } else { source }
            } else { source };
            let _jobs = crate::startup::PipelineBatch::new(
                if specialized { "specialized registry" } else { "interpreter registry" },
                compiled_entries.len(),
            );
            crate::startup::message(format_args!(
                "Pipeline workload specialized={specialized} source_bytes={} entries={} outlined_bodies={} [{}]",
                source.len(), compiled_entries.len(), source.matches("fn retina_noinline_").count(), compiled_entries.join(", ")
            ));
            #[cfg(test)]
            let source = probe.source(source, &compiled_entries);
            #[cfg(test)]
            let bytes = source.len();
            #[cfg(test)]
            let module_start = Instant::now();
            let module_scope = device.push_error_scope(wgpu::ErrorFilter::Validation);
            // Fields and remaining world entries use the exact same immutable
            // root template/options. Parse it once while publishing separately.
            let cache_root = incremental.is_some() && prefix == include_str!("simplex.wgsl")
                && suffix == include_str!("noise3.wgsl") && std::ptr::eq(program.as_ptr(), root_program.as_ptr())
                && program.len() == root_program.len();
            let cached = cache_root.then(|| root_world_module.borrow().as_ref()
                .filter(|(identity, _)| identity == &source).map(|(_, module)| module.clone())).flatten();
            let module = cached.unwrap_or_else(|| {
                let identity = cache_root.then(|| source.clone());
                let module = crate::startup::shader(&device, wgpu::ShaderModuleDescriptor {
                    label: Some("Retina specialized registry graphs"), source: wgpu::ShaderSource::Wgsl(source.into()),
                });
                if let Some(identity) = identity { *root_world_module.borrow_mut() = Some((identity, module.clone())); }
                module
            });
            let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some("Retina specialized registry graphs"),
                bind_group_layouts: &[Some(layout)],
                immediate_size: if shared_dispatch || shared_materials {
                    4
                } else {
                    0
                },
            });
            #[cfg(test)]
            probe.report("module_and_layout", entries[0], module_start, bytes);
            let mut pipelines: HashMap<String, wgpu::ComputePipeline> = compiled_entries
                .iter()
                .map(|entry| {
                    compile_lifetime::check()?;
                    #[cfg(test)]
                    let entry_name = probe.entry(entry);
                    #[cfg(not(test))]
                    let entry_name = *entry;
                    #[cfg(test)]
                    let entry_start = Instant::now();
                    let pipeline =
                        crate::pipeline_cache::create(&device, &wgpu::ComputePipelineDescriptor {
                            label: Some(entry),
                            layout: Some(&layout),
                            module: &module,
                            entry_point: Some(&entry_name),
                            compilation_options: Default::default(),
                            cache: None,
                        }, !specialized, driver_cache);
                    #[cfg(test)]
                    probe.report("pipeline", entry, entry_start, bytes);
                    Ok(((*entry).to_owned(), pipeline))
                })
                .collect::<Result<_, String>>()?;
            if let Some(error) = pollster::block_on(module_scope.pop()) {
                return Err(format!("GPU module cohort: {error}"));
            }
            if shared_dispatch {
                let pipeline = &pipelines[wrapper];
                Ok(entries
                    .iter()
                    .enumerate()
                    .map(|(mode, entry)| {
                        dispatches.borrow_mut().insert((*entry).to_owned(), mode as u32);
                        ((*entry).to_owned(), pipeline.clone())
                    })
                    .collect())
            } else if shared_materials {
                let pipeline = pipelines.remove(material_dispatch::ENTRY).unwrap();
                for (mode, entry) in ["material_counts", "material_emit"].into_iter().enumerate() {
                    dispatches.borrow_mut().insert(entry.to_owned(), mode as u32);
                    pipelines.insert(entry.to_owned(), pipeline.clone());
                }
                Ok(pipelines)
            } else {
                Ok(pipelines)
            }
        };
        let mut world_entries = vec![
            "main",
            "biome_sites",
            "biome_queries",
            "lake_candidates",
            "lake_density",
            "lake_nodes",
        ];
        if terrain {
            world_entries.extend([
                "climate_nodes",
                "height_nodes",
                "density_nodes",
                "surface_columns",
            ]);
        }
        if specialized {
            world_entries.push("horizontal_nodes");
        }
        let sparse_lakes = program.contains(crate::program::lake_sparse::SOURCE_KEY);
        if sparse_lakes {
            world_entries.push("lake_sparse_fields");
        }
        let interpolation_entries = (1..=program.matches("fn interpolation_nodes_").count())
            .map(|level| format!("interpolation_nodes_{level}"))
            .collect::<Vec<_>>();
        if terrain || specialized_interpolation {
            world_entries.extend(interpolation_entries.iter().map(String::as_str));
        }
        if lake_point_cache {
            // This bundle always uses the cached lake entry. Do not compile and
            // retain another lake pipeline which none of its requests will select.
            world_entries.retain(|entry| *entry != "lake_density");
        }
        world_entries.retain(|entry| !reuse.is_some_and(|p| p.world_reuses(entry)));
        if part == parallel::Part::Caves { world_entries.clear(); }
        let compile_caves = || -> Result<HashMap<String, wgpu::ComputePipeline>, String> {
            if part == parallel::Part::World { return Ok(HashMap::new()); }
            let material_source = if material_layers {
                format!(
                    "{}\n{}",
                    include_str!("aquifers.wgsl"),
                    include_str!("materials.wgsl")
                )
            } else {
                String::new()
            };
            let mut cave_entries = vec![
                "underground_queries",
                "cave_nodes",
                "cave_exterior",
                "cave_mask",
            ];
            if material_layers {
                cave_entries.extend(["material_counts", "material_emit"]);
            }
            if aquifers > 0 {
                cave_entries.push("aquifer_mask");
            }
            if aquifers > 1 {
                cave_entries.extend(["aquifer_surface", "aquifer_centers", "aquifer_barrier"]);
            }
            cave_entries.retain(|entry| !reuse.is_some_and(|p| p.cave_reuses(entry)));
            let mut cave_pipelines: HashMap<String, wgpu::ComputePipeline> = make(
                program,
                include_str!("caves.wgsl"),
                &material_source,
                cave,
                &cave_entries,
            )?;
            if cached_masks || cached_nodes {
                let cached_program = crate::program::composition::cached_source(program);
                let mut entries = ["cave_exterior", "cave_mask", "aquifer_mask"]
                    .into_iter()
                    .filter(|e| cached_masks && cave_entries.contains(e))
                    .collect::<Vec<_>>();
                if cached_nodes {
                    entries.push("cave_nodes");
                }
                let pipelines: HashMap<String, wgpu::ComputePipeline> = make(
                    &cached_program,
                    include_str!("caves.wgsl"),
                    &material_source,
                    cave,
                    &entries,
                )?;
                for (entry, pipeline) in pipelines {
                    cave_pipelines.insert(format!("{entry}_cached"), pipeline);
                }
            }
            Ok(cave_pipelines)
        };
        let field_entries = if incremental.is_some() {
            world_entries.iter().copied().filter(|entry| *entry == "horizontal_nodes" || entry.starts_with("interpolation_nodes_")).collect::<Vec<_>>()
        } else { world_entries.clone() };
        let mut world = make(program, include_str!("simplex.wgsl"), include_str!("noise3.wgsl"), world_layout, &field_entries)?;
        let mut cave_pipelines = HashMap::new();
        if let Some(state) = incremental {
            incremental::publish(state, 1, "interpolation-fields", &world, &cave_pipelines, &dispatches, horizontal_fields);
            cave_pipelines = compile_caves()?;
            incremental::publish(state, 2, "caves-and-materials", &world, &cave_pipelines, &dispatches, horizontal_fields);
            let mut remaining = world_entries.iter().copied().filter(|entry| !field_entries.contains(entry)).collect::<Vec<_>>();
            remaining.sort_by_key(|entry| *entry == "main");
            world.extend(make(program, include_str!("simplex.wgsl"), include_str!("noise3.wgsl"), world_layout, &remaining)?);
        }
        if sparse_lakes {
            let pipeline = world.remove("lake_sparse_fields").unwrap();
            world.insert("lake_sparse_fields_cached".to_owned(), pipeline);
            let sparse_program = crate::program::lake_sparse::cached_source(program);
            let sparse_world = include_str!("simplex.wgsl").replace(
                "let point=lake_probe_point(probe,r);",
                "lake_sparse_probe=probe;let point=lake_probe_point(probe,r);",
            );
            let mut pipelines = make(
                &sparse_program,
                &sparse_world,
                include_str!("noise3.wgsl"),
                world_layout,
                &["lake_density"],
            )?;
            world.insert(
                "lake_density_sparse_cached".to_owned(),
                pipelines.remove("lake_density").unwrap(),
            );
        }
        if lake_point_cache {
            let cached_program = crate::program::interpolation::point_cached_source(program);
            let pipelines: HashMap<String, wgpu::ComputePipeline> = make(
                &cached_program,
                include_str!("simplex.wgsl"),
                include_str!("noise3.wgsl"),
                world_layout,
                &["lake_density"],
            )?;
            world.extend(pipelines);
        }
        if cached_masks {
            let cached_program = crate::program::composition::cached_source(program);
            let pipelines: HashMap<String, wgpu::ComputePipeline> = make(
                &cached_program,
                include_str!("simplex.wgsl"),
                include_str!("noise3.wgsl"),
                world_layout,
                &["surface_columns", "density_nodes"],
            )?;
            for (entry, pipeline) in pipelines {
                world.insert(format!("{entry}_cached"), pipeline);
            }
        }
        if let Some(state) = incremental {
            incremental::publish(state, 3, "world-and-query-variants", &world, &cave_pipelines, &dispatches, horizontal_fields);
        } else { cave_pipelines = compile_caves()?; }
        Ok(Pipelines {
            kind: if specialized { "specialized" } else { "interpreter" },
            complete: true,
            world_kinds: HashMap::new(), cave_kinds: HashMap::new(),
            world,
            cave: cave_pipelines,
            horizontal_fields,
            reuse,
            dispatches: dispatches.into_inner(),
            promotion: None,
            optimization_progress: None,
        })
    })();
    // Source-generation errors must unwind the validation scope as well.
    if let Some(error) = pollster::block_on(scope.pop()) {
        return Err(format!("specialized GPU program: {error}"));
    }
    if let Some(cache) = driver_cache { cache.checkpoint(); }
    #[cfg(test)]
    probe.report("bundle", "all", total_start, program.len());
    result
}

/// Constant calls bypass the material dispatch and its other graph call paths.
pub(crate) fn static_calls(source: &str) -> String {
    let mut output = source
        .replace("run_program(0u,", "run_climate(")
        .replace("run_program(1u,", "run_surface_density(")
        .replace("run_program(2u,", "run_final_density(");
    for slot in 0..5 {
        if source.contains(&format!("fn run_aquifer_{slot}(")) {
            let graph = if slot == 0 {
                "caves.aquifer[0].y".to_owned()
            } else {
                format!("caves.aquifer[0].y+{slot}u")
            };
            output = output.replace(
                &format!("run_program({graph},"),
                &format!("run_aquifer_{slot}("),
            );
        }
    }
    aquifer_columns::kernel(&output)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "requires an actual exported profile in RETINA_PROGRAM_PREPARATION_PROFILE"]
    fn actual_profile_source_preparation() {
        let path = std::env::var("RETINA_PROGRAM_PREPARATION_PROFILE").unwrap();
        let json: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
        let program: RegistryProgram =
            serde_json::from_value(json["registry_program"].clone()).unwrap();
        program
            .validate(json["biomes"].as_array().unwrap().len())
            .unwrap();
        for repeat in 0..5 {
            let start = Instant::now();
            let (source, fields) = source_columns(&program).unwrap();
            println!(
                "QA_EVT {}",
                serde_json::json!({"event":"actual_profile_source_preparation","status":"pass",
                    "context":{"profile":path,"repeat":repeat,"prepare_ms":start.elapsed().as_secs_f64()*1000.0,
                    "source_bytes":source.len(),"horizontal_fields":fields}})
            );
            std::hint::black_box(source);
        }
    }
    fn n(op: u32, a: u32, b: u32, c: u32, p: [f32; 4]) -> Instruction {
        Instruction { op, a, b, c, p }
    }
    #[test]
    fn material_dispatch_excludes_direct_aquifer_graphs() {
        let registry = RegistryProgram {
            interpolations: vec![],
            programs: (0..9)
                .map(|v| Program {
                    nodes: vec![n(0, 0, 0, 0, [v as f32, 0.0, 0.0, 0.0])],
                    roots: vec![0],
                })
                .collect(),
            noises: vec![],
            points: vec![],
            surface: [-64, 8, 0],
            terrain_cell: [4, 8],
            density_composition: false,
            surface_noises: [0; 3],
            generation: None,
            material_layers: true,
            material_halo: false,
            aquifer: Some(crate::program::AquiferProgram {
                enabled: true,
                program: 4,
                surface: [-64, 8, 0],
            }),
        };
        let generated = source(&registry).unwrap();
        assert!(generated.contains("fn run_aquifer_4("));
        let dispatch = generated
            .split("fn run_program(")
            .nth(1)
            .unwrap()
            .split("fn density_composed(")
            .next()
            .unwrap();
        assert!(dispatch.contains("case 3u:"));
        assert!(!dispatch.contains("case 4u:"));
        assert!(
            static_calls(&format!(
                "{generated}\nrun_program(caves.aquifer[0].y+4u,p,r,c);"
            ))
            .ends_with("run_aquifer_4(p,r,c);")
        );
    }
    #[test]
    fn large_branch_dags_emit_each_node_once_without_nested_arm_expansion() {
        let mut program = Program {
            nodes: vec![n(29, 0, 0, 0, [0.0; 4])], roots: vec![],
        };
        for i in 1..100 {
            program.nodes.push(n(23, 0, i - 1, 0, [0.0, 1.0, 0.0, 0.0]));
        }
        program.roots.push(99);
        let generated = graph(&program, &[], &opcode_bodies().unwrap()).unwrap();
        assert_eq!(generated.matches("// retained node").count(), 100);
        let mut depth = 0i32;
        let mut peak = 0;
        for c in generated.chars() {
            if c == '{' { depth += 1; peak = peak.max(depth); }
            if c == '}' { depth -= 1; }
        }
        assert_eq!(depth, 0);
        assert!(peak < 4);
    }

    #[test]
    fn branch_dominance_does_not_reuse_arm_local_values() {
        let program = Program {
            nodes: vec![
                n(0, 0, 0, 0, [0.0; 4]),
                n(3, 0, 0, 0, [0.0, 10.0, -1.0, 1.0]),
                n(11, 1, 0, 0, [0.0; 4]),
                n(22, 2, 0, 0, [0.0, 0.5, 0.0, 0.0]),
                n(23, 1, 3, 2, [0.0, 1.0, 0.0, 0.0]),
            ],
            roots: vec![4, 3],
        };
        let source = graph(&program, &[], &opcode_bodies().unwrap()).unwrap();
        assert_eq!(source.matches("var v2:f32").count(), 3);
        assert_eq!(source.matches("var v3:f32").count(), 2);
        assert!(source.contains("if v1>=p.x && v1<p.y"));
        assert!(source.contains("v4=graph_round(v3);"));
        assert!(source.ends_with("return array<f32,6>(v4,v3,v0,v0,v0,v0);\n"));
    }
    #[test]
    fn reachable_nodes_folding_splines_and_shared_graphs() {
        let nodes = vec![
            n(0, 0, 0, 0, [0.0; 4]),
            n(0, 0, 0, 0, [2.0; 4]),
            n(4, 1, 1, 0, [0.0; 4]),
            n(3, 0, 0, 0, [0.0, 10.0, -1.0, 1.0]),
            n(25, 3, 0, 2, [0.0; 4]),
            n(26, 0, 0, 0, [1.0; 4]),
        ];
        let program = Program {
            nodes,
            roots: vec![4],
        };
        let registry = RegistryProgram {
            interpolations: vec![],
            programs: vec![program.clone(), program.clone(), program],
            noises: vec![],
            points: vec![[-1.0, 1.0, 1.0, 0.0], [1.0, 0.0, 2.0, 0.0]],
            surface: [0, 8, 0],
            terrain_cell: [4, 8],
            density_composition: false,
            surface_noises: [0; 3],
            generation: None,
            material_layers: false,
            material_halo: false,
            aquifer: None,
        };
        let source = source(&registry).unwrap();
        assert!(!source.contains("array<f32,1024>"));
        assert!(!source.contains("var v5"));
        assert!(source.contains("let v2=graph_round(bitcast<f32>(0x40800000u))"));
        assert_eq!(source.matches("fn graph_").count(), 2);
        assert!(source.contains("vec3<f32>,2"));
        assert!(source.contains("v0,v0,v0,v0,v0"));
        assert_eq!(opcode_bodies().unwrap().len(), 47);
        let mut direct = registry;
        direct.surface[2] = 1;
        let (direct_source, fields) = source_columns(&direct).unwrap();
        assert_eq!(fields, 0);
        assert!(!direct_source.contains("fn raw_column_"));
        assert!(!direct_source.contains("fn column_field_"));
    }
}
