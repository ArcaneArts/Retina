// Minecraft light values: block low nibble, sky high nibble. Four voxels per word.
// Flags select sky (bit 0), sparse propagation (bit 1), section nibble output (bit 2).
struct Params { side: u32, height: u32, chunks: u32, crop: u32,
    core: u32, flags: u32, face_stride: u32, words: u32 }
struct State { traits: u32, faces: array<u32, 6>, padding: u32 }
@group(0) @binding(0) var<uniform> p: Params;
@group(0) @binding(1) var<storage, read> blocks: array<u32>;
@group(0) @binding(2) var<storage, read> states: array<State>;
@group(0) @binding(3) var<storage, read> blocked: array<u32>;
@group(0) @binding(4) var<storage, read_write> source: array<u32>;
@group(0) @binding(5) var<storage, read_write> destination: array<u32>;
@group(0) @binding(6) var<storage, read_write> output: array<u32>;
// First three words are indirect dispatch arguments; classification stays on device.
struct Work { x: u32, y: u32, z: u32, count: atomic<u32>, bricks: array<atomic<u32>> }
@group(0) @binding(7) var<storage, read_write> work: Work;
@group(0) @binding(8) var<storage, read_write> active_bricks: array<u32>;

fn brick_index(x: u32, y: u32, z: u32) -> u32 {
    let side = p.side / 8u;
    return (y * side + z) * side + x;
}

fn material(x: u32, y: u32, z: u32) -> u32 {
    // Light-only sections are virtual air; only final terrain is uploaded.
    if y < 16u || y >= p.height - 16u { return 0u; }
    let i = ((z / 16u) * p.chunks + x / 16u) * (p.height - 32u) * 256u
        + (y - 16u) * 256u + (z % 16u) * 16u + x % 16u;
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
    let has_sky = (p.flags & 1u) != 0u;
    let sparse = (p.flags & 2u) != 0u;
    // Four aligned x lanes always stay inside the same chunk and packed word.
    let x = column % p.side;
    let z = column / p.side;
    let block_column = ((z / 16u) * p.chunks + x / 16u) * (p.height - 32u) * 128u
        + (z % 16u) * 8u + (x % 16u) / 2u;
    let plane_words = p.side * p.side / 4u;
    var sun = array<bool, 4>(has_sky, has_sky, has_sky, has_sky);
    var classification = 0u;
    var above = array<u32, 4>(0u, 0u, 0u, 0u);
    for (var dy = p.height; dy > 0u; dy--) {
        let y = dy - 1u;
        var materials = vec2<u32>(0u);
        if y >= 16u && y < p.height - 16u {
            let row = block_column + (y - 16u) * 128u;
            materials = vec2<u32>(blocks[row], blocks[row + 1u]);
        }
        var word = 0u;
        for (var lane = 0u; lane < 4u; lane++) {
            let m = (materials[lane / 2u] >> ((lane % 2u) * 16u)) & 65535u;
            // Direct sky remains 15 only above the first attenuating/covered edge.
            sun[lane] = sun[lane] && (states[m].traits & 15u) == 0u && !occludes(above[lane], m, 0u);
            let value = ((states[m].traits >> 4u) & 15u) | select(0u, 240u, sun[lane]);
            word |= value << (lane * 8u);
            above[lane] = m;
            if sparse {
                let passable = (states[m].traits & 15u) < 15u;
                classification |= select(0u, 1u, passable);
                classification |= select(0u, 2u, passable && !sun[lane]);
                classification |= select(0u, 4u, (value & 15u) > 1u);
                classification |= select(0u, 8u, sun[lane]);
            }
        }
        let index = y * plane_words + column / 4u;
        destination[index] = word;
        if sparse {
            // Unscheduled bricks remain seeds in BOTH ping-pong frontiers.
            source[index] = word;
            if y % 8u == 0u {
                if classification != 0u {
                    atomicOr(&work.bricks[brick_index(x / 8u, y / 8u, z / 8u)], classification);
                }
                classification = 0u;
            }
        }
    }
}

// No positive light can cross more than fourteen edges. An 8-voxel brick
// three bricks away starts at least seventeen edges away; +/-2 is conservative.
@compute @workgroup_size(64)
fn compact(@builtin(global_invocation_id) id: vec3<u32>) {
    let side = p.side / 8u;
    let height = (p.height + 7u) / 8u;
    let index = id.x;
    if index >= side * side * height { return; }
    let flags = atomicLoad(&work.bricks[index]);
    if (flags & 1u) == 0u { return; }
    let x = index % side; let z = (index / side) % side; let y = index / (side * side);
    let needed = 4u | select(0u, 8u, (p.flags & 1u) != 0u && (flags & 2u) != 0u);
    var nearby = flags;
    for (var by = max(0, i32(y) - 2); by <= min(i32(height) - 1, i32(y) + 2) && (nearby & needed) != needed; by++) {
        for (var bz = max(0, i32(z) - 2); bz <= min(i32(side) - 1, i32(z) + 2) && (nearby & needed) != needed; bz++) {
            for (var bx = max(0, i32(x) - 2); bx <= min(i32(side) - 1, i32(x) + 2) && (nearby & needed) != needed; bx++) {
                // Sum the minimum voxel distances between the two bricks.
                // Cube corners beyond fourteen edges cannot contribute either channel.
                let distance = max(0, abs(bx - i32(x)) * 8 - 7)
                    + max(0, abs(by - i32(y)) * 8 - 7) + max(0, abs(bz - i32(z)) * 8 - 7);
                if distance > 14 { continue; }
                nearby |= atomicLoad(&work.bricks[brick_index(u32(bx), u32(by), u32(bz))]);
            }
        }
    }
    let block_needed = (nearby & 4u) != 0u;
    let sky_needed = (needed & nearby & 8u) != 0u;
    if !block_needed && !sky_needed { return; }
    let slot = atomicAdd(&work.count, 1u);
    active_bricks[slot] = index | select(0u, 1u << 30u, block_needed) | select(0u, 1u << 31u, sky_needed);
}

@compute @workgroup_size(1)
fn dispatch_args() {
    work.x = 256u; work.y = (atomicLoad(&work.count) + 255u) / 256u; work.z = 1u;
}

fn propagate(word_id: u32, channels: u32) -> u32 {
    let plane = p.side * p.side;
    let first = word_id * 4u;
    let x0 = first % p.side;
    let z = (first / p.side) % p.side;
    let y = first / plane;
    let current_word = source[word_id];
    var result = 0u;
    for (var lane = 0u; lane < 4u; lane++) {
        let x = x0 + lane;
        let m = material(x, y, z);
        let opacity = max(1u, states[m].traits & 15u);
        let current = (current_word >> (lane * 8u)) & 255u;
        var block = current & 15u; var sky = current >> 4u;
        if opacity < 15u && ((channels & 1u) != 0u && block < 14u || (channels & 2u) != 0u && sky < 14u) {
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
                let b = select(0u, adjacent & 15u, (channels & 1u) != 0u);
                let s = select(0u, adjacent >> 4u, (channels & 2u) != 0u);
                if (b > block + opacity || s > sky + opacity) && !occludes(material(nx, ny, nz), m, d ^ 1u) {
                    block = max(block, select(0u, b - opacity, b > opacity));
                    sky = max(sky, select(0u, s - opacity, s > opacity));
                }
            }
        }
        result |= (block | sky << 4u) << (lane * 8u);
    }
    return result;
}

@compute @workgroup_size(64)
fn spread(@builtin(global_invocation_id) id: vec3<u32>) {
    let word_id = id.y * 16384u + id.x;
    if word_id >= p.words { return; }
    destination[word_id] = propagate(word_id, 3u);
}

@compute @workgroup_size(64)
fn spread_sparse(@builtin(workgroup_id) id: vec3<u32>, @builtin(local_invocation_index) lane: u32) {
    let slot = id.y * 256u + id.x;
    if slot >= atomicLoad(&work.count) { return; }
    let record = active_bricks[slot];
    let brick = record & 0x3fffffffu;
    let channels = record >> 30u;
    let side = p.side / 8u;
    let x = (brick % side) * 8u;
    let z = ((brick / side) % side) * 8u;
    let y = (brick / (side * side)) * 8u;
    for (var local = lane; local < 128u; local += 64u) {
        let py = y + local / 16u;
        if py >= p.height { continue; }
        let word_id = (py * p.side * p.side + (z + (local / 2u) % 8u) * p.side
            + x + (local % 2u) * 4u) / 4u;
        destination[word_id] = propagate(word_id, channels);
    }
}

@compute @workgroup_size(64)
fn pack(@builtin(global_invocation_id) id: vec3<u32>) {
    let word_id = id.y * 16384u + id.x;
    if word_id >= p.core * p.core * p.height * 64u { return; }
    let chunk = word_id / (p.height * 64u);
    let local = word_id % (p.height * 64u);
    if (p.flags & 4u) != 0u {
        // Each section contains Minecraft's 2048-byte block array then sky array.
        let voxel = (local / 1024u) * 4096u + (local % 512u) * 8u;
        let x = (p.crop + chunk % p.core) * 16u + voxel % 16u;
        let z = (p.crop + chunk / p.core) * 16u + (voxel / 16u) % 16u;
        let y = voxel / 256u;
        let index = (y * p.side * p.side + z * p.side + x) / 4u;
        let shift = ((local / 512u) % 2u) * 4u;
        output[word_id] = nibbles(source[index] >> shift) | (nibbles(source[index + 1u] >> shift) << 16u);
        return;
    }
    let x = (p.crop + chunk % p.core) * 4u + local % 4u;
    let z = (p.crop + chunk / p.core) * 16u + (local / 4u) % 16u;
    let y = local / 64u;
    // Crop and row boundaries are multiples of four voxels, so no byte shuffle.
    output[word_id] = source[(y * p.side + z) * (p.side / 4u) + x];
}

// Gather one channel from four light bytes into four consecutive nibbles.
fn nibbles(word: u32) -> u32 {
    return (word & 15u) | ((word >> 4u) & 240u)
        | ((word >> 8u) & 3840u) | ((word >> 12u) & 61440u);
}
