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
    density_offset: u32,
    density_side: u32,
    density_step_xz: u32,
    density_step_y: u32,
}

@group(0) @binding(0) var<storage, read> requests: array<Request>;
struct Column { height: i32, packed: u32, materials: u32 }
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
struct ClimateTarget { low: vec4<f32>, high: vec4<f32>, extra: vec4<f32>, depth: vec4<f32> }
struct ClimateTable { count: vec4<u32>, noise: NoiseProfile, targets: array<ClimateTarget> }
@group(0) @binding(4) var<storage, read> climate_table: ClimateTable;
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
    var weirdness_value = 0.0;
    if bytecode[0]>0u {let actual=run_program(0u,vec3<f32>(point.x,0.0,point.y),request,vec4<f32>(0.0));climate=vec4<f32>(actual[0],actual[1],actual[2],actual[3]);weirdness_value=actual[4];}
    var best = 1e20;
    var biome = 0u;
    for (var index = 0u; index < world.ids.x; index++) {
        if (world.biomes[index].materials.w & 16u) != 0u || world.biomes[index].terrain.w > 0.5 { continue; }
        let difference = climate - world.biomes[index].climate;
        var fitness = dot(difference * difference, vec4<f32>(2.5, 1.5, 2.0, 0.5));
        // Biomes added without climate placement receive a small local niche
        // around their registered temperature/rainfall, even beside pack intervals.
        if climate_table.count.x > 0u { fitness -= 0.12; }
        // Ocean sites are selected by continentalness, rather than invading hot/cold land cells.
        let ocean = (world.biomes[index].materials.w & 4u) != 0u;
        if bytecode[0]==0u && ocean != (climate.z < -0.25) { fitness += 8.0; }
        if fitness < best { best = fitness; biome = index; }
    }
    if climate_table.count.x > 0u {
        let noise = climate_table.noise;
        var frequency = noise.frequency; var persistence = 1.0; var sum = 0.0; var weights = 0.0;
        for(var octave=0u;octave<noise.count;octave++) {
            let weight = noise.modifiers[octave]*persistence;
            sum += simplex(point*frequency,request.seed_low+31676u+octave*1013u,request.seed_high)*weight;
            weights += weight; frequency *= 2.0; persistence *= 0.5;
        }
        let weirdness = select(clamp(sum/max(weights,0.0001)*noise.amplitude*1.6,-1.0,1.0),weirdness_value,bytecode[0]>0u);
        for(var i=0u;i<climate_table.count.x;i++) {
            let entry = climate_table.targets[i]; let index = u32(entry.extra.w);
            if (world.biomes[index].materials.w & 16u)!=0u {continue;}
            let difference = climate-clamp(climate,entry.low,entry.high);
            let ridge = weirdness-clamp(weirdness,entry.extra.x,entry.extra.y);
            var fitness = dot(difference*difference,vec4<f32>(2.5,1.5,2.0,0.5))+ridge*ridge+entry.extra.z*entry.extra.z;
            let ocean = (world.biomes[index].materials.w & 4u) != 0u;
            if bytecode[0]==0u && ocean != (climate.z < -0.25) { fitness += 8.0; }
            if fitness < best { best = fitness; biome = index; }
        }
    }
    sites[id.y * SITE_COUNT + id.x] = Site(point, biome, 0u, climate);
}

// Final GPU stage: domain warp, Voronoi assignment, continuous height blending,
// simplex relief, coherent material borders, soil depth, cold-water and bedrock flags.
struct TerrainSample { height: i32, biome: u32, terrain: vec3<f32>, climate: vec4<f32> }
fn terrain_base(point: vec2<f32>, request: Request, request_index: u32) -> TerrainSample {
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
    if bytecode[0]>0u {
        let actual=registered_climate_fast(point,request,request_index);
        climate=vec4<f32>(actual[0],actual[1],actual[2],actual[3]);
        biome=registered_biome(actual,biome);
        return TerrainSample(height,biome,terrain,climate);
    }
    return TerrainSample(height,biome,terrain,climate);
}
fn registered_biome(actual:array<f32,6>,initial:u32)->u32 {
    var biome=initial;let climate=vec4<f32>(actual[0],actual[1],actual[2],actual[3]);
    if climate_table.count.x>0u {
        var best=1e20;
        for(var index=0u;index<world.ids.x;index++) {
            if world.biomes[index].terrain.w>0.5 || (world.biomes[index].materials.w & 16u)!=0u {continue;}
            let difference=climate-world.biomes[index].climate;let fitness=dot(difference,difference)-0.03;
            if fitness<best {best=fitness;biome=index;}
        }
        for(var i=0u;i<climate_table.count.x;i++) {
            let entry=climate_table.targets[i];if (world.biomes[u32(entry.extra.w)].materials.w & 16u)!=0u {continue;}
            let difference=climate-clamp(climate,entry.low,entry.high);let ridge=actual[4]-clamp(actual[4],entry.extra.x,entry.extra.y);
            let fitness=dot(difference,difference)+ridge*ridge+entry.extra.z*entry.extra.z;
            if fitness<best {best=fitness;biome=u32(entry.extra.w);}
        }
    }
    return biome;
}
fn terrain_probe(point:vec2<f32>,r:Request,index:u32)->TerrainSample {
    var sample=terrain_base(point,r,index);
    if bytecode[0]>0u {
        let actual=registered_climate(point,r,index);
        sample.climate=vec4<f32>(actual[0],actual[1],actual[2],actual[3]);
        sample.biome=registered_biome(actual,sample.biome);
    }
    return sample;
}
fn terrain_cached(point:vec2<f32>,r:Request,index:u32)->TerrainSample {
    var sample=terrain_base(point,r,index);
    if bytecode[0]>0u {sample.height=i32(floor(registered_height_fast(point,r,index)));}return sample;
}
fn lake_center(cell:vec2<i32>,r:Request)->vec2<f32> {
    let h=cell_hash(cell,r.seed_low+8647u,r.seed_high);
    return (vec2<f32>(cell)+vec2<f32>(0.25)+vec2<f32>(f32(h&255u),f32((h>>8u)&255u))/510.0)*128.0;
}
// radius, fluid level, center biome, eligibility. A whole lake shares these probes.
fn lake_parameters(cell:vec2<i32>,r:Request,index:u32)->vec4<f32> {
    let h=cell_hash(cell,r.seed_low+8647u,r.seed_high);
    let point=lake_center(cell,r);let center=terrain_probe(point,r,index);
    let options=world.biomes[center.biome].features;
    let chance=f32((h>>16u)&65535u)/65535.0;
    let lava=chance<options.y;var radius=24.0+f32(h&7u);
    if lava {radius*=0.25;}
    return vec4<f32>(radius,f32(r.min_y),f32(center.biome),select(0.0,1.0,lava || chance<options.x));
}
@compute @workgroup_size(64)
fn lake_candidates(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[id.y];let n=lake_side(r);if id.x>=n*n {return;}
    let cell=vec2<i32>(density_floor_div(r.origin_x,128),density_floor_div(r.origin_z,128))+vec2<i32>(i32(id.x%n),i32(id.x/n));
    let width=i32(select(16u,r.tile_side*16u,r.tile_side>0u));
    let last=vec2<i32>(density_floor_div(r.origin_x+width-1,128),density_floor_div(r.origin_z+width-1,128));
    var info=vec4<f32>(0.0);if all(cell<=last) {info=lake_parameters(cell,r,id.y);}
    let at=lake_offset(r)+id.x*4u;
    for(var i=0u;i<4u;i++){surface_nodes[at+i]=info[i];}
}
fn lake_probe_point(probe:u32,r:Request)->vec2<f32> {
    let lake=probe/5u;let n=lake_side(r);
    let cell=vec2<i32>(density_floor_div(r.origin_x,128),density_floor_div(r.origin_z,128))+vec2<i32>(i32(lake%n),i32(lake/n));
    let center=lake_center(cell,r);let radius=surface_nodes[lake_offset(r)+lake*4u];
    switch probe%5u {
        case 1u:{return center+vec2<f32>(radius+5.0,0.0);}
        case 2u:{return center-vec2<f32>(radius+5.0,0.0);}
        case 3u:{return center+vec2<f32>(0.0,(radius+5.0)/0.78);}
        case 4u:{return center-vec2<f32>(0.0,(radius+5.0)/0.78);}
        default:{return center;}
    }
}
@compute @workgroup_size(64)
fn lake_density(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[id.z];let n=lake_side(r);
    if id.x>=n*n*20u || id.y>=density_layers(r) {return;}
    let probe=id.x/4u;let corner=id.x%4u;
    if surface_nodes[lake_offset(r)+(probe/5u)*4u+3u]<0.5 {return;}
    let point=lake_probe_point(probe,r);
    let cell=vec2<i32>(floor((point-vec2<f32>(density_origin(r).xz))/f32(r.density_step_xz)))+vec2<i32>(i32(corner&1u),i32(corner>>1u));
    // Every corner/Y sample has its own invocation. Outside-grid noise is never
    // evaluated serially inside a lake's vertical scan.
    surface_nodes[lake_probe_offset(r)+(probe*density_layers(r)+id.y)*4u+corner]=density_corner(cell,id.y,r);
}
@compute @workgroup_size(64)
fn lake_nodes(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[id.y];let n=lake_side(r);if id.x>=n*n {return;}
    let at=lake_offset(r)+id.x*4u;if surface_nodes[at+3u]<0.5 {return;}
    var height=r.max_y;
    for(var p=0u;p<5u;p++) {
        let probe=id.x*5u+p;
        height=min(height,i32(floor(density_surface_height(lake_probe_point(probe,r),r,probe))));
    }
    surface_nodes[at+1u]=f32(height-2);
}
fn cached_lake(cell:vec2<i32>,r:Request,index:u32)->vec4<f32> {
    if bytecode[0]>0u && bytecode[5]==0u {
        let local=cell-vec2<i32>(density_floor_div(r.origin_x,128),density_floor_div(r.origin_z,128));
        let at=lake_offset(r)+(u32(local.y)*lake_side(r)+u32(local.x))*4u;
        return vec4<f32>(surface_nodes[at],surface_nodes[at+1u],surface_nodes[at+2u],surface_nodes[at+3u]);
    }
    return lake_geometry_legacy(cell,r,index);
}
// Legacy and explicit-height profiles retain their original height lookup.
// This separate helper avoids pulling uncached density evaluation into main.
fn lake_geometry_legacy(cell:vec2<i32>,r:Request,index:u32)->vec4<f32> {
    let h=cell_hash(cell,r.seed_low+8647u,r.seed_high);
    let point=lake_center(cell,r);let center=terrain_cached(point,r,index);
    let options=world.biomes[center.biome].features;
    let chance=f32((h>>16u)&65535u)/65535.0;
    let lava=chance<options.y;var radius=24.0+f32(h&7u);
    if lava {radius*=0.25;}
    var level=f32(r.min_y);let eligible=lava || chance<options.x;
    if eligible {
        let x0=terrain_cached(point+vec2<f32>(radius+5.0,0.0),r,index).height;
        let x1=terrain_cached(point-vec2<f32>(radius+5.0,0.0),r,index).height;
        let z0=terrain_cached(point+vec2<f32>(0.0,(radius+5.0)/0.78),r,index).height;
        let z1=terrain_cached(point-vec2<f32>(0.0,(radius+5.0)/0.78),r,index).height;
        level=f32(min(center.height,min(min(x0,x1),min(z0,z1)))-2);
    }
    return vec4<f32>(radius,level,f32(center.biome),select(0.0,1.0,eligible));
}
@compute @workgroup_size(64)
fn biome_queries(@builtin(global_invocation_id) id:vec3<u32>) {
    if id.x!=0u {return;}
    let r=requests[id.y];
    if r.profile==0u {columns[id.y]=Column(0,0u,0u);return;}
    let point=vec2<f32>(f32(r.origin_x+8),f32(r.origin_z+8));
    columns[id.y]=Column(0,terrain_base(point,r,id.y).biome,0u);
}
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let probe=(requests[0].padding & (1u<<30u))!=0u;
    let height_probe=(requests[0].padding & (1u<<29u))!=0u;
    if probe && id.x!=0u {return;}
    let region = requests[0].tile_side > 0u;
    let request_index = select(id.y, 0u, region);
    let request = requests[request_index];
    var tile = vec2<i32>(0);
    if region {
        tile = vec2<i32>(i32(id.y % request.tile_side), i32(id.y / request.tile_side)) * 16;
    }
    let coordinate = vec2<i32>(request.origin_x + tile.x + select(i32(id.x & 15u),8,probe),
                               request.origin_z + tile.y + select(i32(id.x >> 4u),8,probe));
    let point = vec2<f32>(coordinate);
    let index = select(id.y * 256u + id.x,id.y,probe);
    if request.profile == 0u {
        let height = i32(floor(request.base_height + request.amplitude * fbm(point * request.frequency, request.seed_low, request.seed_high)));
        columns[index] = Column(clamp(height, request.min_y + 1, request.max_y), 0u, 0u);
        return;
    }
    let sample = terrain_cached(point,request,request_index);
    var height = sample.height;
    let biome = sample.biome;
    // One coherent region membership selects both the biome and its surface.
    // The only per-column hash below controls hidden bedrock thickness.
    let h = cell_hash(coordinate, request.seed_low + 1381u, request.seed_high);
    let material = world.biomes[biome].materials;
    var top = 0u;
    var filler = 0u;
    var flags = 0u;
    let sea_level = bitcast<i32>(world.ids.y);
    if height < sea_level {
        top = 1u;
        filler = top;
    } else if (world.biomes[biome].materials.w & 1u) != 0u {
        top = 2u;
    } else if (material.w & 2u) != 0u && height > sea_level + 65 {
        top = 3u; filler = top;
    }
    var frozen=(material.w & 1u)!=0u;
    if bytecode[0]>0u {
        var temperature=world.biomes[biome].features.w;
        let snowline=f32(sea_level+17);
        if f32(height)>snowline {temperature-=(noise3(vec3<f32>(point.x,0.0,point.y)*0.125,1234u)*8.0+f32(height)-snowline)*0.00125;}
        frozen=(material.w&32u)!=0u && temperature<0.15;
    }
    if frozen { flags |= 1u << 28u; }
    // Vanilla vegetation threshold sampling stays on the GPU, sharing this readback.
    if simplex(point * 0.005, 2345u, 0u) < -0.8 { flags |= 1u << 27u; }
    let depth_noise = simplex(point * request.frequency * 3.0, request.seed_low + 559u, request.seed_high);
    var depth = u32(clamp(i32(floor(3.5 + depth_noise * 2.0)), 2, 6));
    if bytecode[0]>0u {
        let registered=program_noise(vec3<f32>(point.x,0.0,point.y),bytecode[9],request);
        depth=u32(clamp(i32(registered*2.75+3.0+f32(h&65535u)/65535.0*0.25),0,7));
    }
    // Fixed global cells make rounded, warped basins with a flat waterline.
    // Their geometry is computed here, before any readback or Rust assembly.
    let lake_cell = vec2<i32>(floor(point / 128.0));
    let lh = cell_hash(lake_cell, request.seed_low + 8647u, request.seed_high);
    let lake_point = lake_center(lake_cell,request);
    let lake_delta = point - lake_point;
    var radius = 24.0+f32(lh & 7u);
    var basin = length(lake_delta * vec2<f32>(1.0,0.78)) / radius
        + simplex(point * 0.055, request.seed_low + 9913u, request.seed_high)*0.09;
    if basin < 1.35 {
        let lake = cached_lake(lake_cell,request,request_index);
        let center_biome=u32(lake.z);
        let options = world.biomes[center_biome].features;
        let chance = f32((lh >> 16u) & 65535u)/65535.0;
        let lava_lake = chance < options.y;
        if lava_lake {
            radius = lake.x;
            basin = length(lake_delta * vec2<f32>(1.0,0.78)) / radius
                + simplex(point * 0.055, request.seed_low + 9913u, request.seed_high)*0.09;
        }
        let eligible = lake.w>0.0;
        if eligible && basin < 1.35 && (material.w & (2u | 4u | 8u | 16u)) == 0u {
            // A fixed level below the lowest sampled bank contains the fluid.
            let level = i32(lake.y);
            if level > sea_level+3 && abs(height-level) <= 18 {
                if basin < 1.0 {
                    let drop = u32(clamp(i32(ceil((1.0-basin*basin)*6.0)),1,7));
                    height = clamp(level-i32(drop),request.min_y+6,request.max_y-1);
                    depth = drop; flags &= ~(1u<<28u); flags |= 1u<<29u;
                    if lava_lake { flags |= 1u<<28u; top = 4u; filler = center_biome; }
                    else { top = 1u; filler = top; }
                } else {
                    height = i32(round(mix(f32(level),f32(height),smoothstep(1.0,1.35,basin))));
                }
            }
        }
    }
    if probe || height_probe {columns[index]=Column(height,biome | (depth<<24u) | flags,0u);return;}
    // Terracotta retains its red-sand cap. The filler byte transports the small
    // signed band offset; Rust only indexes the resident 192-entry color table.
    if (material.w & 8u) != 0u && world.globals.z > 0.0 {
        var offset = i32(round(simplex(point * world.globals.z,request.seed_low+6833u,request.seed_high)*4.0*world.globals.w));
        if bytecode[0]>0u {offset=i32(round(program_noise(vec3<f32>(point.x,0.0,point.y),bytecode[11],request)*4.0));}
        filler = bitcast<u32>(clamp(offset,-127,127)) & 255u;
    }
    var top_id=material.x;var filler_id=material.y;
    if top==1u {top_id=material.z;}else if top==2u {top_id=world.ids.w;}else if top==3u {top_id=world.ids.z;}
    if filler==1u {filler_id=material.z;}else if filler==3u {filler_id=world.ids.z;}
    if bytecode[0]>0u && (flags & (1u<<29u))==0u {
        let slope=abs(registered_height_fast(point+vec2<f32>(4.0,0.0),request,request_index)-registered_height_fast(point-vec2<f32>(4.0,0.0),request,request_index))
                  +abs(registered_height_fast(point+vec2<f32>(0.0,4.0),request,request_index)-registered_height_fast(point-vec2<f32>(0.0,4.0),request,request_index));
        let band=f32(bitcast<i32>(filler<<24u)>>24);
        let selected_top=run_program(3u+biome,vec3<f32>(point.x,f32(height-1),point.y),request,vec4<f32>(1.0,f32(depth),slope,band))[0];
        let selected_filler=run_program(3u+biome,vec3<f32>(point.x,f32(height-3),point.y),request,vec4<f32>(3.0,f32(depth),slope,band))[0];
        if selected_top>0.0 {top_id=u32(selected_top-1.0);}if selected_filler>0.0 {filler_id=u32(selected_filler-1.0);}
    }
    if (flags & ((1u<<29u)|(1u<<28u)))==((1u<<29u)|(1u<<28u)) {top_id=u32(world.biomes[filler].features.z);filler_id=top_id;}
    flags |= (h >> 16u & 3u) << 30u;
    columns[index] = Column(height, biome | ((filler & 255u) << 16u) | (depth << 24u) | flags, top_id | (filler_id << 16u));
}
