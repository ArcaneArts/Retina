struct Vein { minimum: vec4<i32>, dimensions: vec4<u32>, source: vec4<u32> }
struct Seed { random_size: vec4<u32>, a: vec4<f32>, delta: vec4<f32> }
@group(0) @binding(0) var<storage, read> veins: array<Vein>;
@group(0) @binding(1) var<storage, read> seeds: array<Seed>;
@group(0) @binding(2) var<storage, read_write> output: array<u32>;
@group(0) @binding(3) var<storage, read> coefficients: array<vec2<u32>>;
var<workgroup> local_spheres: array<vec4<f32>, 64>;
var<workgroup> local_radii: array<f32, 64>;
var<workgroup> actual_minimum: vec3<i32>;
var<workgroup> actual_sizes: vec3<u32>;
var<workgroup> actual_count: u32;

@compute @workgroup_size(64)
fn seeded_masks(@builtin(workgroup_id) group: vec3<u32>, @builtin(local_invocation_index) lane: u32) {
    let index = group.x + group.y * 256u;
    if index >= arrayLength(&veins) { return; }
    let vein = veins[index];
    let input = seeds[vein.source.x];
    let size = input.random_size.z;
    if lane < size {
        let coefficient = coefficients[size * 64u + lane];
        let random = bitcast<u32>(unit(random_at(input.random_size.xy, lane + 4u)));
        let scale = bitcast<u32>(f32(size) / 32.0);
        let radius = add_bits(mul_bits(mul_bits(coefficient.y, random), scale), 0x3f000000u);
        var center: vec3<f32>;
        for (var k = 0u; k < 3u; k++) {
            center[k] = bitcast<f32>(add_bits(bitcast<u32>(input.a[k]), mul_bits(bitcast<u32>(input.delta[k]), coefficient.x)));
        }
        local_spheres[lane] = vec4<f32>(center, bitcast<f32>(radius));
    }
    workgroupBarrier();
    if lane == 0u {
        // Keep the original ordered containment walk, including its early break.
        for (var i = 0u; i < size; i++) {
            if local_spheres[i].w < 0.0 { continue; }
            for (var j = i + 1u; j < size; j++) {
                if local_spheres[j].w < 0.0 { continue; }
                var dr = local_spheres[i].w - local_spheres[j].w;
                let delta = local_spheres[i].xyz - local_spheres[j].xyz;
                let radius_squared = dr * dr;
                let distance = (delta.x * delta.x + delta.y * delta.y) + delta.z * delta.z;
                var contained = radius_squared > distance;
                // Inputs are already CPU-exact. This margin exceeds the accumulated
                // rounding/contraction error of the short, finite sum of squares.
                // Close comparisons use the original arithmetic bit for bit.
                if abs(radius_squared - distance) <= 0.00001 * (radius_squared + distance + 1.0) {
                    let dr_bits = add_bits(bitcast<u32>(local_spheres[i].w), bitcast<u32>(local_spheres[j].w) ^ 0x80000000u);
                    dr = bitcast<f32>(dr_bits);
                    var exact_distance = 0u;
                    for (var k = 0u; k < 3u; k++) {
                        let d = add_bits(bitcast<u32>(local_spheres[i][k]), bitcast<u32>(local_spheres[j][k]) ^ 0x80000000u);
                        exact_distance = add_bits(exact_distance, mul_bits(d, d));
                    }
                    contained = bitcast<f32>(mul_bits(dr_bits, dr_bits)) > bitcast<f32>(exact_distance);
                }
                if contained {
                    if dr > 0.0 { local_spheres[j].w = -1.0; }
                    else { local_spheres[i].w = -1.0; break; }
                }
            }
        }
        var count = 0u;
        var retained = vec2<u32>(0u);
        var low = vec3<i32>(2147483647);
        var high = vec3<i32>(-2147483647 - 1);
        for (var i = 0u; i < size; i++) {
            let sphere = local_spheres[i];
            if sphere.w <= 0.0 { continue; }
            retained[i / 32u] |= 1u << (i % 32u);
            for (var k = 0u; k < 3u; k++) {
                let c = bitcast<u32>(sphere[k]);
                let r = bitcast<u32>(sphere.w);
                low[k] = min(low[k], i32(floor(bitcast<f32>(add_bits(c, r ^ 0x80000000u)))));
                high[k] = max(high[k], i32(floor(bitcast<f32>(add_bits(c, r)))));
            }
            local_spheres[count] = sphere;
            count++;
        }
        actual_minimum = low;
        actual_sizes = vec3<u32>(high - low + vec3<i32>(1));
        actual_count = count;
        for (var k = 0u; k < 3u; k++) {
            output[vein.dimensions.w + k] = bitcast<u32>(low[k]);
            output[vein.dimensions.w + 4u + k] = actual_sizes[k];
        }
        output[vein.dimensions.w + 3u] = retained.x;
        output[vein.dimensions.w + 7u] = retained.y;
    }
    workgroupBarrier();
    if lane < actual_count {
        local_radii[lane] = local_spheres[lane].w;
        local_spheres[lane].w = 1.0 / local_spheres[lane].w;
    }
    workgroupBarrier();
    let dimensions = actual_sizes;
    let voxels = dimensions.x * dimensions.y * dimensions.z;
    let words = (voxels + 31u) / 32u;
    for (var wi = lane; wi < words; wi += 64u) {
        var bits = 0u;
        var uncertain = 0u;
        for (var bit = 0u; bit < 32u; bit++) {
            let i = wi * 32u + bit;
            if i >= voxels { break; }
            let xyz = actual_minimum + vec3<i32>(i32(i % dimensions.x), i32(i / (dimensions.x * dimensions.z)), i32(i / dimensions.x % dimensions.z));
            let p = vec3<f32>(xyz) + vec3<f32>(0.5);
            let coarse = any(abs(p) >= vec3<f32>(4194304.0));
            for (var j = 0u; j < actual_count; j++) {
                let sphere = local_spheres[j];
                if coarse {
                    let radius = vec3<f32>(local_radii[j]);
                    if any(xyz < vec3<i32>(floor(sphere.xyz - radius))) || any(xyz > vec3<i32>(floor(sphere.xyz + radius))) { continue; }
                }
                let delta = (p - sphere.xyz) * sphere.w;
                let yz = delta.y * delta.y + delta.z * delta.z;
                let distance = yz + delta.x * delta.x;
                if distance < 0.9999 {
                    bits |= 1u << bit;
                    uncertain &= ~(1u << bit);
                    break;
                }
                if abs(distance - 1.0) <= 0.0001 { uncertain |= 1u << bit; }
                if distance < 1.0 { bits |= 1u << bit; }
            }
        }
        output[vein.dimensions.w + 8u + wi * 2u] = bits;
        output[vein.dimensions.w + 9u + wi * 2u] = uncertain;
    }
}
