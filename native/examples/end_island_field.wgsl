// Experiment only. Seeded f64 simplex eligibility and f32 center sizes are
// supplied by the exact shared CPU table, then contributions run on the GPU.
struct Params { origin: vec2<i32>, width: u32, count: u32 }
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var<storage, read> sizes: array<f32>;
@group(0) @binding(2) var<storage, read> points: array<vec2<i32>>;
@group(0) @binding(3) var<storage, read_write> output: array<vec2<f32>>;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) invocation: vec3<u32>) {
    let index = invocation.x;
    if index >= params.count { return; }
    let p = points[index];
    let section = p / vec2<i32>(8);
    let center = section / vec2<i32>(2);
    let sub = section % vec2<i32>(2);
    var height = -100.0f;
    for (var dx = -12; dx <= 12; dx++) {
        for (var dz = -12; dz <= 12; dz++) {
            let at = center + vec2<i32>(dx, dz) - params.origin;
            let size = sizes[u32(at.y) * params.width + u32(at.x)];
            if size == 0.0f { continue; }
            let offset = vec2<f32>(sub - vec2<i32>(dx * 2, dz * 2));
            let square_x = offset.x * offset.x;
            let square_z = offset.y * offset.y;
            let distance = sqrt(square_x + square_z);
            let candidate = clamp(100.0f - distance * size, -100.0f, 80.0f);
            height = max(height, candidate);
        }
    }
    let outer = (height - 8.0f) / 128.0f;
    let position = vec2<f32>(p);
    let square_x = position.x * position.x;
    let square_z = position.y * position.y;
    let distance = sqrt(square_x + square_z);
    let central = (clamp(100.0f - distance, -100.0f, 80.0f) - 8.0f) * 0.0078125f;
    output[index] = vec2<f32>(outer, max(central, outer));
}
