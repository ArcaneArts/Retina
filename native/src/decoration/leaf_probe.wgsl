// Diagnostic only. Six synchronous relaxations preserve the CPU BFS's distance,
// root priority and direction-path order. Production uses the Rust BFS.
@group(0) @binding(0) var<storage, read> shape: array<u32>;
@group(0) @binding(1) var<storage, read> previous: array<u32>;
@group(0) @binding(2) var<storage, read_write> next: array<u32>;
@group(0) @binding(3) var<storage, read> trees: array<vec4<u32>>;
const LEAF: u32 = 0xfffffffeu;
const EMPTY: u32 = 0xffffffffu;
fn advance(value: u32, direction: u32) -> u32 {
    let distance = value >> 28u;
    if distance >= 6u { return EMPTY; }
    return ((distance + 1u) << 28u) | ((value & 0x0fffffffu) * 6u + direction);
}
fn propagate_cell(id: vec3<u32>) {
    let tree = trees[id.y];
    let size = tree.yzw;
    let i = id.x;
    if i >= size.x * size.y * size.z { return; }
    let at = tree.x + i;
    if shape[at] != LEAF { next[at] = shape[at]; return; }
    let x = i % size.x;
    let z = (i / size.x) % size.z;
    let y = i / (size.x * size.z);
    var value = previous[at];
    // Pull directions are the reverse of the CPU's push directions.
    if x + 1u < size.x { value = min(value, advance(previous[at + 1u], 1u)); }
    if x > 0u { value = min(value, advance(previous[at - 1u], 0u)); }
    if y + 1u < size.y { value = min(value, advance(previous[at + size.x * size.z], 3u)); }
    if y > 0u { value = min(value, advance(previous[at - size.x * size.z], 2u)); }
    if z + 1u < size.z { value = min(value, advance(previous[at + size.x], 5u)); }
    if z > 0u { value = min(value, advance(previous[at - size.x], 4u)); }
    next[at] = value;
}

@compute @workgroup_size(64)
fn propagate(@builtin(global_invocation_id) id: vec3<u32>) { propagate_cell(id); }
@compute @workgroup_size(64)
fn propagate_large(@builtin(global_invocation_id) id: vec3<u32>) {
    let t=trees[id.y];
    if t.y*t.z*t.w > 4096u { propagate_cell(id); }
}
// Atomic minima are monotonic. In-pass updates may accelerate discovery, but
// six barriers still cover every valid shortest path of length at most six.
var<workgroup> ranks:array<atomic<u32>,4096>;
@compute @workgroup_size(64)
fn propagate_local(@builtin(workgroup_id) group:vec3<u32>,@builtin(local_invocation_index) lane:u32) {
    let tree=trees[group.x];let size=tree.yzw;let count=size.x*size.y*size.z;
    if count>4096u {return;}
    for(var i=lane;i<count;i+=64u){atomicStore(&ranks[i],shape[tree.x+i]);}
    workgroupBarrier();
    for(var step=0u;step<6u;step++) {
        for(var i=lane;i<count;i+=64u) {
            if shape[tree.x+i]!=LEAF {continue;}
            let x=i%size.x;let z=(i/size.x)%size.z;let y=i/(size.x*size.z);
            var value=atomicLoad(&ranks[i]);
            if x+1u<size.x {value=min(value,advance(atomicLoad(&ranks[i+1u]),1u));}
            if x>0u {value=min(value,advance(atomicLoad(&ranks[i-1u]),0u));}
            if y+1u<size.y {value=min(value,advance(atomicLoad(&ranks[i+size.x*size.z]),3u));}
            if y>0u {value=min(value,advance(atomicLoad(&ranks[i-size.x*size.z]),2u));}
            if z+1u<size.z {value=min(value,advance(atomicLoad(&ranks[i+size.x]),5u));}
            if z>0u {value=min(value,advance(atomicLoad(&ranks[i-size.x]),4u));}
            atomicMin(&ranks[i],value);
        }
        workgroupBarrier();
    }
    for(var i=lane;i<count;i+=64u){next[tree.x+i]=atomicLoad(&ranks[i]);}
}
