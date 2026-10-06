// Minecraft light values: block low nibble, sky high nibble. Four voxels per word.
struct Params { side: u32, height: u32, chunks: u32, crop: u32,
    core: u32, sky: u32, face_stride: u32, words: u32 }
struct State { traits: u32, faces: array<u32, 6>, padding: u32 }
@group(0) @binding(0) var<uniform> p: Params;
@group(0) @binding(1) var<storage, read> blocks: array<u32>;
@group(0) @binding(2) var<storage, read> states: array<State>;
@group(0) @binding(3) var<storage, read> blocked: array<u32>;
@group(0) @binding(4) var<storage, read> source: array<u32>;
@group(0) @binding(5) var<storage, read_write> destination: array<u32>;
@group(0) @binding(6) var<storage, read_write> output: array<u32>;

fn material(x: u32, y: u32, z: u32) -> u32 {
    let i = ((z / 16u) * p.chunks + x / 16u) * p.height * 256u
        + y * 256u + (z % 16u) * 16u + x % 16u;
    return (blocks[i / 2u] >> ((i % 2u) * 16u)) & 65535u;
}
fn occludes(from_material: u32, to_material: u32, direction: u32) -> bool {
    let a = states[from_material].faces[direction];
    let b = states[to_material].faces[direction ^ 1u];
    return (blocked[a * p.face_stride + b / 32u] & (1u << (b % 32u))) != 0u;
}
fn light(i: u32) -> u32 { return (source[i / 4u] >> ((i % 4u) * 8u)) & 255u; }

@compute @workgroup_size(64)
fn initialize(@builtin(global_invocation_id) id: vec3<u32>) {
    let column = id.x * 4u;
    if column >= p.side * p.side { return; }
    var sun = array<bool, 4>(p.sky != 0u, p.sky != 0u, p.sky != 0u, p.sky != 0u);
    var above = array<u32, 4>(0u, 0u, 0u, 0u);
    for (var dy = p.height; dy > 0u; dy--) {
        let y = dy - 1u;
        var word = 0u;
        for (var lane = 0u; lane < 4u; lane++) {
            let x = (column + lane) % p.side;
            let z = (column + lane) / p.side;
            let m = material(x, y, z);
            // Direct sky remains 15 only above the first attenuating/covered edge.
            sun[lane] = sun[lane] && (states[m].traits & 15u) == 0u && !occludes(above[lane], m, 0u);
            let value = ((states[m].traits >> 4u) & 15u) | select(0u, 240u, sun[lane]);
            word |= value << (lane * 8u);
            above[lane] = m;
        }
        destination[(y * p.side * p.side + column) / 4u] = word;
    }
}

@compute @workgroup_size(64)
fn spread(@builtin(global_invocation_id) id: vec3<u32>) {
    let word_id = id.y * 16384u + id.x;
    if word_id >= p.words { return; }
    let plane = p.side * p.side;
    var result = 0u;
    for (var lane = 0u; lane < 4u; lane++) {
        let i = word_id * 4u + lane;
        let x = i % p.side; let z = (i / p.side) % p.side; let y = i / plane;
        let m = material(x, y, z);
        let opacity = max(1u, states[m].traits & 15u);
        let current = light(i);
        var block = current & 15u; var sky = current >> 4u;
        if opacity < 15u {
            for (var d = 0u; d < 6u; d++) {
                var nx = x; var ny = y; var nz = z;
                switch d {
                    case 0u: { if y == 0u { continue; } ny--; }
                    case 1u: { if y + 1u == p.height { continue; } ny++; }
                    case 2u: { if z == 0u { continue; } nz--; }
                    case 3u: { if z + 1u == p.side { continue; } nz++; }
                    case 4u: { if x == 0u { continue; } nx--; }
                    default: { if x + 1u == p.side { continue; } nx++; }
                }
                let adjacent = light(ny * plane + nz * p.side + nx);
                let b = adjacent & 15u; let s = adjacent >> 4u;
                if (b > block + opacity || s > sky + opacity) && !occludes(material(nx, ny, nz), m, d ^ 1u) {
                    block = max(block, select(0u, b - opacity, b > opacity));
                    sky = max(sky, select(0u, s - opacity, s > opacity));
                }
            }
        }
        result |= (block | sky << 4u) << (lane * 8u);
    }
    destination[word_id] = result;
}

@compute @workgroup_size(64)
fn pack(@builtin(global_invocation_id) id: vec3<u32>) {
    let word_id = id.y * 16384u + id.x;
    if word_id >= p.core * p.core * p.height * 64u { return; }
    var result = 0u;
    for (var lane = 0u; lane < 4u; lane++) {
        let i = word_id * 4u + lane;
        let chunk = i / (p.height * 256u); let local = i % (p.height * 256u);
        let x = (p.crop + chunk % p.core) * 16u + local % 16u;
        let z = (p.crop + chunk / p.core) * 16u + (local / 16u) % 16u;
        let y = local / 256u;
        result |= light(y * p.side * p.side + z * p.side + x) << (lane * 8u);
    }
    output[word_id] = result;
}
