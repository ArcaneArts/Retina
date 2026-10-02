struct Request {
    origin_x: i32,
    origin_z: i32,
    min_y: i32,
    max_y: i32,
    seed_low: u32,
    seed_high: u32,
    base_height: f32,
    amplitude: f32,
    frequency: f32,
    profile: u32,
    tile_side: u32,
    padding: u32,
}

@group(0) @binding(0) var<storage, read> requests: array<Request>;
struct Column { height: i32, packed: u32 }
struct NoiseProfile {
    frequency: f32, amplitude: f32, count: u32, padding: u32,
    modifiers: array<f32, 32>,
}
struct BiomeProfile { climate: vec4<f32>, terrain: vec4<f32>, materials: vec4<u32>, features: vec4<f32> }
struct WorldProfile {
    globals: vec4<f32>, ids: vec4<u32>, noises: array<NoiseProfile, 4>,
    biomes: array<BiomeProfile>,
}
struct Site { point: vec2<f32>, biome: u32, padding: u32, climate: vec4<f32> }
@group(0) @binding(1) var<storage, read_write> columns: array<Column>;
@group(0) @binding(2) var<storage, read> world: WorldProfile;
@group(0) @binding(3) var<storage, read_write> sites: array<Site>;
const SITE_SIDE: u32 = 12u;
const SITE_COUNT: u32 = 144u;

fn mix_hash(value: u32) -> u32 {
    var h = value;
    h = (h ^ (h >> 16u)) * 0x7feb352du;
    h = (h ^ (h >> 15u)) * 0x846ca68bu;
    return h ^ (h >> 16u);
}

fn gradient(cell: vec2<i32>, seed_low: u32, seed_high: u32) -> vec2<f32> {
    let h = mix_hash(bitcast<u32>(cell.x) * 0x9e3779b9u
        ^ bitcast<u32>(cell.y) * 0x85ebca6bu
        ^ seed_low ^ mix_hash(seed_high));
    switch h & 7u {
        case 0u: { return vec2<f32>(1.0, 1.0); }
        case 1u: { return vec2<f32>(-1.0, 1.0); }
        case 2u: { return vec2<f32>(1.0, -1.0); }
        case 3u: { return vec2<f32>(-1.0, -1.0); }
        case 4u: { return vec2<f32>(1.0, 0.0); }
        case 5u: { return vec2<f32>(-1.0, 0.0); }
        case 6u: { return vec2<f32>(0.0, 1.0); }
        default: { return vec2<f32>(0.0, -1.0); }
    }
}

fn contribution(cell: vec2<i32>, offset: vec2<f32>, seed_low: u32, seed_high: u32) -> f32 {
    let t = max(0.5 - dot(offset, offset), 0.0);
    let t2 = t * t;
    return t2 * t2 * dot(gradient(cell, seed_low, seed_high), offset);
}

fn simplex(point: vec2<f32>, seed_low: u32, seed_high: u32) -> f32 {
    let skew = (point.x + point.y) * 0.3660254037844386;
    let cell = vec2<i32>(floor(point + vec2<f32>(skew)));
    let unskew = f32(cell.x + cell.y) * 0.2113248654051871;
    let d0 = point - (vec2<f32>(cell) - vec2<f32>(unskew));
    var step = vec2<i32>(0, 1);
    if d0.x > d0.y {
        step = vec2<i32>(1, 0);
    }
    let d1 = d0 - vec2<f32>(step) + vec2<f32>(0.2113248654051871);
    let d2 = d0 - vec2<f32>(1.0) + vec2<f32>(0.4226497308103742);
    return 70.0 * (
        contribution(cell, d0, seed_low, seed_high)
        + contribution(cell + step, d1, seed_low, seed_high)
        + contribution(cell + vec2<i32>(1), d2, seed_low, seed_high));
}

fn fbm(point_in: vec2<f32>, low: u32, high: u32) -> f32 {
    var point = point_in;
    var weight = 1.0;
    var result = 0.0;
    var sum = 0.0;
    for (var octave = 0u; octave < 4u; octave++) {
        result += simplex(point, low + octave * 1013u, high) * weight;
        sum += weight;
        point *= 2.0; weight *= 0.5;
    }
    return result / sum;
}

fn registry_noise(point: vec2<f32>, channel: u32, low: u32, high: u32) -> f32 {
    var frequency = world.noises[channel].frequency;
    var persistence = 1.0;
    var sum = 0.0;
    var weight_sum = 0.0;
    for (var octave = 0u; octave < world.noises[channel].count; octave++) {
        let weight = world.noises[channel].modifiers[octave] * persistence;
        if weight > 0.0 {
            sum += simplex(point * frequency, low + channel * 7919u + octave * 1013u, high) * weight;
            weight_sum += weight;
        }
        frequency *= 2.0; persistence *= 0.5;
    }
    return clamp(sum / max(0.0001, weight_sum) * world.noises[channel].amplitude * 1.6, -1.0, 1.0);
}

fn cell_hash(cell: vec2<i32>, low: u32, high: u32) -> u32 {
    return mix_hash(bitcast<u32>(cell.x) * 0x9e3779b9u ^ bitcast<u32>(cell.y) * 0x85ebca6bu ^ low ^ mix_hash(high));
}

// Small first stage: global-coordinate biome sites and their registry climate targets.
// This buffer stays on the GPU; the CPU never receives or interpolates site data.
@compute @workgroup_size(64)
fn biome_sites(@builtin(global_invocation_id) id: vec3<u32>) {
    if id.x >= SITE_COUNT { return; }
    let request = requests[id.y];
    let scale = world.globals.x;
    let origin = vec2<i32>(floor(vec2<f32>(f32(request.origin_x), f32(request.origin_z)) / scale)) - vec2<i32>(3);
    let cell = origin + vec2<i32>(i32(id.x % SITE_SIDE), i32(id.x / SITE_SIDE));
    let h = cell_hash(cell, request.seed_low, request.seed_high);
    let jitter = vec2<f32>(f32(h & 65535u), f32(h >> 16u)) / 65535.0;
    let point = (vec2<f32>(cell) + vec2<f32>(0.2) + jitter * 0.6) * scale;
    var climate = vec4<f32>(0.0);
    for (var channel = 0u; channel < 4u; channel++) {
        climate[channel] = registry_noise(point, channel, request.seed_low, request.seed_high);
    }
    var best = 1e20;
    var biome = 0u;
    for (var index = 0u; index < world.ids.x; index++) {
        if (world.biomes[index].materials.w & 16u) != 0u { continue; }
        let difference = climate - world.biomes[index].climate;
        var fitness = dot(difference * difference, vec4<f32>(2.5, 1.5, 2.0, 0.5));
        // Ocean sites are selected by continentalness, rather than invading hot/cold land cells.
        let ocean = (world.biomes[index].materials.w & 4u) != 0u;
        if ocean != (climate.z < -0.25) { fitness += 8.0; }
        if fitness < best { best = fitness; biome = index; }
    }
    sites[id.y * SITE_COUNT + id.x] = Site(point, biome, 0u, climate);
}

// Final GPU stage: domain warp, Voronoi assignment, continuous height blending,
// simplex relief, coherent material borders, soil depth, cold-water and bedrock flags.
struct TerrainSample { height: i32, biome: u32, terrain: vec3<f32>, climate: vec4<f32> }
fn terrain_at(point: vec2<f32>, request: Request, request_index: u32) -> TerrainSample {
    let scale = world.globals.x;
    let warp = vec2<f32>(
        simplex(point / (scale * 2.0), request.seed_low + 407u, request.seed_high),
        simplex(point / (scale * 2.0), request.seed_low + 911u, request.seed_high)) * scale * 0.18;
    // Layered, spatially continuous warp makes ribbon-like borders instead of
    // independent random block choices. Biome IDs and surface recipes share it.
    let ribbon_point = (point + warp) / (scale * 0.22);
    let ribbon = vec2<f32>(
        simplex(ribbon_point, request.seed_low + 1487u, request.seed_high),
        simplex(ribbon_point, request.seed_low + 2371u, request.seed_high)) * scale * 0.055;
    let wisp_point = (point + warp + ribbon) / (scale * 0.075);
    let wisps = vec2<f32>(
        simplex(wisp_point, request.seed_low + 3253u, request.seed_high),
        simplex(wisp_point, request.seed_low + 4721u, request.seed_high)) * scale * 0.02;
    let warped = point + warp + ribbon + wisps;
    let origin = vec2<i32>(floor(vec2<f32>(f32(request.origin_x), f32(request.origin_z)) / scale)) - vec2<i32>(3);
    let cell = vec2<i32>(floor(warped / scale)) - origin;
    var nearest = 1e20;
    var biome = 0u;
    var total = 0.0;
    var terrain = vec3<f32>(0.0);
    var climate = vec4<f32>(0.0);
    for (var dz = -2; dz <= 2; dz++) {
        for (var dx = -2; dx <= 2; dx++) {
            let at = cell + vec2<i32>(dx, dz);
            let site = sites[request_index * SITE_COUNT + u32(at.y) * SITE_SIDE + u32(at.x)];
            let delta = (warped - site.point) / scale;
            let distance = dot(delta, delta);
            if distance < nearest { nearest = distance; biome = site.biome; }
            let taper = max(0.0, 1.0 - distance / (1.75 * 1.75));
            let weight = exp(-distance / (world.globals.y * world.globals.y)) * taper * taper;
            terrain += world.biomes[site.biome].terrain.xyz * weight;
            climate += site.climate * weight;
            total += weight;
        }
    }
    terrain /= total; climate /= total;
    // Blend fixed-frequency fields, rather than varying their input frequency.
    // Multiplying global coordinates by a blended frequency creates phase jumps
    // and steep noise at distant biome boundaries.
    let broad = fbm(point * request.frequency * 0.6, request.seed_low, request.seed_high);
    let detailed = fbm(point * request.frequency, request.seed_low, request.seed_high);
    let relief = mix(broad, detailed, clamp((terrain.z - 0.6) / 0.4, 0.0, 1.0));
    let continental_shape = climate.z * 12.0;
    let erosion_scale = clamp(1.0 - climate.w * 0.35, 0.6, 1.4);
    let height = clamp(i32(floor(request.base_height + terrain.x + continental_shape
        + request.amplitude * terrain.y * relief * erosion_scale)), request.min_y + 1, request.max_y);
    return TerrainSample(height,biome,terrain,climate);
}
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let region = requests[0].tile_side > 0u;
    let request_index = select(id.y, 0u, region);
    let request = requests[request_index];
    var tile = vec2<i32>(0);
    if region {
        tile = vec2<i32>(i32(id.y % request.tile_side), i32(id.y / request.tile_side)) * 16;
    }
    let coordinate = vec2<i32>(request.origin_x + tile.x + i32(id.x & 15u),
                               request.origin_z + tile.y + i32(id.x >> 4u));
    let point = vec2<f32>(coordinate);
    let index = id.y * 256u + id.x;
    if request.profile == 0u {
        let height = i32(floor(request.base_height + request.amplitude * fbm(point * request.frequency, request.seed_low, request.seed_high)));
        columns[index] = Column(clamp(height, request.min_y + 1, request.max_y), 0u);
        return;
    }
    let sample = terrain_at(point,request,request_index);
    var height = sample.height;
    let biome = sample.biome;
    // One coherent region membership selects both the biome and its surface.
    // The only per-column hash below controls hidden bedrock thickness.
    let h = cell_hash(coordinate, request.seed_low + 1381u, request.seed_high);
    let material = world.biomes[biome].materials;
    var top = material.x;
    var filler = material.y;
    var flags = 0u;
    let sea_level = bitcast<i32>(world.ids.y);
    if height < sea_level {
        top = world.biomes[biome].materials.z;
        filler = top;
    } else if (world.biomes[biome].materials.w & 1u) != 0u {
        top = world.ids.w;
    } else if (material.w & 2u) != 0u && height > sea_level + 65 {
        top = world.ids.z; filler = top;
    }
    if (world.biomes[biome].materials.w & 1u) != 0u { flags |= 1u << 28u; }
    // Vanilla vegetation threshold sampling stays on the GPU, sharing this readback.
    if simplex(point * 0.005, 2345u, 0u) < -0.8 { flags |= 1u << 27u; }
    let depth_noise = simplex(point * request.frequency * 3.0, request.seed_low + 559u, request.seed_high);
    var depth = u32(clamp(i32(floor(3.5 + depth_noise * 2.0)), 2, 6));
    // Fixed global cells make rounded, warped basins with a flat waterline.
    // Their geometry is computed here, before any readback or Rust assembly.
    let lake_cell = vec2<i32>(floor(point / 128.0));
    let lh = cell_hash(lake_cell, request.seed_low + 8647u, request.seed_high);
    let lake_center = (vec2<f32>(lake_cell) + vec2<f32>(0.25) + vec2<f32>(f32(lh & 255u),f32((lh >> 8u) & 255u))/510.0)*128.0;
    let lake_delta = point - lake_center;
    let radius = 24.0+f32(lh & 7u);
    let basin = length(lake_delta * vec2<f32>(1.0,0.78)) / radius
        + simplex(point * 0.055, request.seed_low + 9913u, request.seed_high)*0.09;
    if basin < 1.35 {
        let center = terrain_at(lake_center,request,request_index);
        let options = world.biomes[center.biome].features;
        let chance = f32((lh >> 16u) & 65535u)/65535.0;
        let lava_lake = chance < options.y;
        let eligible = lava_lake || chance < options.x;
        if eligible && (material.w & (2u | 4u | 8u | 16u)) == 0u {
            // A fixed level below the lowest sampled bank contains the fluid.
            let shore_x0 = terrain_at(lake_center+vec2<f32>(radius+5.0,0.0),request,request_index).height;
            let shore_x1 = terrain_at(lake_center-vec2<f32>(radius+5.0,0.0),request,request_index).height;
            let shore_z0 = terrain_at(lake_center+vec2<f32>(0.0,(radius+5.0)/0.78),request,request_index).height;
            let shore_z1 = terrain_at(lake_center-vec2<f32>(0.0,(radius+5.0)/0.78),request,request_index).height;
            let level = min(center.height,min(min(shore_x0,shore_x1),min(shore_z0,shore_z1)))-2;
            if level > sea_level+3 && abs(height-level) <= 18 {
                if basin < 1.0 {
                    let drop = u32(clamp(i32(ceil((1.0-basin*basin)*6.0)),1,7));
                    height = clamp(level-i32(drop),request.min_y+6,request.max_y-1);
                    depth = drop; flags &= ~(1u<<28u); flags |= 1u<<29u;
                    if lava_lake { flags |= 1u<<28u; top = u32(options.z); filler = top; }
                    else { top = material.z; filler = top; }
                } else {
                    height = i32(round(mix(f32(level),f32(height),smoothstep(1.0,1.35,basin))));
                }
            }
        }
    }
    // Terracotta retains its red-sand cap. The filler byte transports the small
    // signed band offset; Rust only indexes the resident 192-entry color table.
    if (material.w & 8u) != 0u && world.globals.z > 0.0 {
        let offset = i32(round(simplex(point * world.globals.z,request.seed_low+6833u,request.seed_high)*4.0*world.globals.w));
        filler = bitcast<u32>(clamp(offset,-127,127)) & 255u;
    }
    flags |= (h >> 16u & 3u) << 30u;
    columns[index] = Column(height, biome | (top << 8u) | (filler << 16u) | (depth << 24u) | flags);
}
