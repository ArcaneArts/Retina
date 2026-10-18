// Seed-driven ore geometry experiment. Not included in production shaders.
struct Input { origin_size: vec4<i32>, seed: vec4<u32>, a: vec4<f32>, delta: vec4<f32> }
struct Sphere { geometry: vec4<f32>, random: vec4<u32> }
@group(0) @binding(0) var<storage, read> inputs: array<Input>;
@group(0) @binding(1) var<storage, read_write> output: array<Sphere>;
@group(0) @binding(2) var<storage, read> coefficients: array<vec2<u32>>;
var<workgroup> spheres: array<vec4<f32>, 64>;

@compute @workgroup_size(64)
fn prepare(@builtin(workgroup_id) group: vec3<u32>, @builtin(local_invocation_index) lane: u32) {
    let input = inputs[group.x];
    if input.seed.w == 1u {
        if lane == 0u {
            let a = bitcast<u32>(input.a.x);
            let b = bitcast<u32>(input.delta.x);
            output[group.x * 64u].geometry = bitcast<vec4<f32>>(vec4<u32>(add_bits(a, b), mul_bits(a, b), add_bits(a, b ^ 0x80000000u), mul_bits(a, b ^ 0x80000000u)));
        }
        return;
    }
    let size = u32(input.origin_size.w);
    let seed = input.seed.xy;
    let angle = unit(random_at(seed, 1u)) * 3.1415927410125732;
    let dx = sin(angle) * f32(size) / 8.0;
    let dz = cos(angle) * f32(size) / 8.0;
    let origin = vec3<f32>(input.origin_size.xyz);
    let a = origin + vec3<f32>(dx, f32(i32(random_at(seed, 2u) % 3u) - 2), dz);
    let b = origin + vec3<f32>(-dx, f32(i32(random_at(seed, 3u) % 3u) - 2), -dz);
    if lane < size {
        let value = random_at(seed, lane + 4u);
        let t = f32(lane) / f32(size);
        var radius = (sin(3.1415927410125732 * t) + 1.0) * unit(value) * f32(size) / 32.0 + 0.5;
        var center = a + (b - a) * t;
        if input.seed.z != 0u {
            let coefficient = coefficients[size * 64u + lane];
            let scale = bitcast<u32>(f32(size) / 32.0);
            radius = bitcast<f32>(add_bits(mul_bits(mul_bits(coefficient.y, bitcast<u32>(unit(value))), scale), 0x3f000000u));
            for (var k = 0u; k < 3u; k++) {
                center[k] = bitcast<f32>(add_bits(bitcast<u32>(input.a[k]), mul_bits(bitcast<u32>(input.delta[k]), coefficient.x)));
            }
        }
        spheres[lane] = vec4<f32>(center, radius);
        output[group.x * 64u + lane].random = vec4<u32>(value, bitcast<u32>(radius), random_at(seed, 2u), random_at(seed, 3u));
    }
    workgroupBarrier();
    if lane == 0u {
        for (var i = 0u; i < size; i++) {
            if spheres[i].w < 0.0 { continue; }
            for (var j = i + 1u; j < size; j++) {
                if spheres[j].w < 0.0 { continue; }
                var dr = spheres[i].w - spheres[j].w;
                let delta = spheres[i].xyz - spheres[j].xyz;
                var distance = (delta.x * delta.x + delta.y * delta.y) + delta.z * delta.z;
                var radius_squared = dr * dr;
                if input.seed.z != 0u {
                    let dr_bits = add_bits(bitcast<u32>(spheres[i].w), bitcast<u32>(spheres[j].w) ^ 0x80000000u);
                    dr = bitcast<f32>(dr_bits);
                    radius_squared = bitcast<f32>(mul_bits(dr_bits, dr_bits));
                    var sum = 0u;
                    for (var k = 0u; k < 3u; k++) {
                        let d = add_bits(bitcast<u32>(spheres[i][k]), bitcast<u32>(spheres[j][k]) ^ 0x80000000u);
                        sum = add_bits(sum, mul_bits(d, d));
                    }
                    distance = bitcast<f32>(sum);
                }
                if radius_squared > distance {
                    if dr > 0.0 { spheres[j].w = -1.0; }
                    else { spheres[i].w = -1.0; break; }
                }
            }
        }
    }
    workgroupBarrier();
    if lane < size { output[group.x * 64u + lane].geometry = spheres[lane]; }
}
