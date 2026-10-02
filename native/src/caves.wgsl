// Two GPU-only stages: registry-driven 3D fields on a global four-block lattice,
// then trilinear interpolation and final biome/roof classification into one bit per voxel.
struct Request {
    origin_x: i32, origin_z: i32, min_y: i32, max_y: i32,
    seed_low: u32, seed_high: u32, base_height: f32, amplitude: f32,
    frequency: f32, profile: u32, tile_side: u32, padding: u32,
}
struct Column { height: i32, packed: u32 }
struct NoiseProfile { frequency: f32, amplitude: f32, count: u32, padding: u32, modifiers: array<f32,32> }
struct Carver { range: vec4<f32>, shape: vec4<f32>, detail: vec4<f32> }
struct CaveBiome { carvers: array<Carver,4>, climate: vec4<f32>, selection: vec4<f32> }
struct CaveProfile { globals: vec4<u32>, noises: array<NoiseProfile,6>, biomes: array<CaveBiome> }
@group(0) @binding(0) var<storage,read> requests: array<Request>;
@group(0) @binding(1) var<storage,read> columns: array<Column>;
@group(0) @binding(2) var<storage,read> caves: CaveProfile;
@group(0) @binding(3) var<storage,read_write> nodes: array<vec4<f32>>;
@group(0) @binding(4) var<storage,read_write> mask: array<u32>;
fn hash(value: u32) -> u32 {
    var h = value; h = (h ^ (h >> 16u))*0x7feb352du; h = (h ^ (h >> 15u))*0x846ca68bu; return h ^ (h >> 16u);
}
fn corner(cell: vec3<i32>, offset: vec3<f32>, seed: u32) -> f32 {
    let h = hash(bitcast<u32>(cell.x)*0x9e3779b9u ^ bitcast<u32>(cell.y)*0x85ebca6bu ^ bitcast<u32>(cell.z)*0xc2b2ae35u ^ seed) & 15u;
    let a = select(offset.y,offset.x,h < 8u);
    let b = select(select(offset.z,offset.x,h == 12u || h == 14u),offset.y,h < 4u);
    return select(a,-a,(h & 1u)!=0u) + select(b,-b,(h & 2u)!=0u);
}
fn noise3(point: vec3<f32>, seed: u32) -> f32 {
    let cell = vec3<i32>(floor(point)); let d = point - vec3<f32>(cell);
    let t = d*d*d*(d*(d*6.0-15.0)+10.0);
    let z0 = mix(mix(corner(cell,d,seed),corner(cell+vec3<i32>(1,0,0),d-vec3<f32>(1,0,0),seed),t.x),
                 mix(corner(cell+vec3<i32>(0,1,0),d-vec3<f32>(0,1,0),seed),corner(cell+vec3<i32>(1,1,0),d-vec3<f32>(1,1,0),seed),t.x),t.y);
    let z1 = mix(mix(corner(cell+vec3<i32>(0,0,1),d-vec3<f32>(0,0,1),seed),corner(cell+vec3<i32>(1,0,1),d-vec3<f32>(1,0,1),seed),t.x),
                 mix(corner(cell+vec3<i32>(0,1,1),d-vec3<f32>(0,1,1),seed),corner(cell+vec3<i32>(1,1,1),d-vec3<f32>(1,1,1),seed),t.x),t.y);
    return mix(z0,z1,t.z)*1.6;
}
fn registry_noise(point: vec3<f32>, channel: u32, seed: u32) -> f32 {
    var frequency = caves.noises[channel].frequency; var persistence = 1.0;
    var sum = 0.0; var weights = 0.0;
    for (var i=0u; i<caves.noises[channel].count; i++) {
        // Filter frequencies above the sampling lattice's Nyquist limit.
        if frequency > 0.125 { break; }
        let weight = caves.noises[channel].modifiers[i]*persistence;
        if weight > 0.0 { sum += noise3(point*frequency,seed+channel*7919u+i*1013u)*weight; weights += weight; }
        frequency *= 2.0; persistence *= 0.5;
    }
    return sum/max(weights,0.0001)*caves.noises[channel].amplitude;
}
// Finite, curved ravine centerlines are globally anchored. This field is sampled
// once per density node rather than evaluating curve noise for every carved voxel.
fn ravine_distance(point: vec2<f32>, seed: u32) -> f32 {
    let cell = vec2<i32>(floor(point/192.0));
    var distance = 1000.0;
    for (var dz=-1; dz<=1; dz++) { for (var dx=-1; dx<=1; dx++) {
        let at = cell+vec2<i32>(dx,dz);
        let h = hash(bitcast<u32>(at.x)*0x9e3779b9u ^ bitcast<u32>(at.y)*0x85ebca6bu ^ seed ^ 17863u);
        if (h & 255u) > 72u { continue; }
        let center = (vec2<f32>(at)+vec2<f32>(0.5))*192.0;
        let angle = f32((h>>8u)&65535u)/65535.0*6.2831853;
        let delta = point-center;
        let along = dot(delta,vec2<f32>(cos(angle),sin(angle)));
        let side = dot(delta,vec2<f32>(-sin(angle),cos(angle)));
        let length = 46.0+f32((h>>24u)&31u);
        let bend = sin(along*0.032+f32(h&31u))*9.0;
        let width = 2.5+f32((h>>20u)&7u)*0.45;
        distance = min(distance,max(abs(side-bend)-width,abs(along)-length));
    }}
    return distance;
}
fn biome_at(x: u32, y: i32, z: u32, column: Column, r: Request) -> u32 {
    let surface = column.packed & 255u;
    if y >= column.height-12 { return surface; }
    let seed = r.seed_low ^ hash(r.seed_high);
    let point = vec3<f32>(f32(r.origin_x+i32(x)),f32(y),f32(r.origin_z+i32(z)));
    // Coarse coherent 3D climate domains; their targets/depths are registry values.
    let humidity = clamp(0.5+noise3(point*vec3<f32>(0.004,0.006,0.004),seed+2297u),-1.0,1.0);
    let continental = clamp(0.5+noise3(point*vec3<f32>(0.003,0.004,0.003),seed+3571u),-1.0,1.0);
    let erosion = noise3(point*vec3<f32>(0.002,0.005,0.002),seed+5573u);
    let depth = clamp(0.2+0.7*f32(column.height-y)/max(f32(column.height-r.min_y),1.0),0.2,0.9);
    var selected = surface; var fitness = 1e20;
    for (var i=0u; i<caves.globals.x; i++) {
        let b = caves.biomes[i]; let kind = u32(b.selection.z);
        if kind == 0u { continue; }
        if kind == 3u {
            if y < -20 && erosion < b.climate.w+0.12 { return i; }
            continue;
        }
        if depth < b.selection.x || depth > b.selection.y { continue; }
        let value = select(continental,humidity,kind == 1u);
        let climate_target = select(b.climate.z,b.climate.y,kind == 1u);
        if value < climate_target-0.15 { continue; }
        let error = abs(value-climate_target);
        if error < fitness { fitness = error; selected = i; }
    }
    return selected;
}
@compute @workgroup_size(64)
fn cave_nodes(@builtin(global_invocation_id) id: vec3<u32>) {
    let r = requests[0]; let n = r.tile_side*4u+1u;
    let bottom = i32(floor(f32(r.min_y)/4.0))*4;
    let ny = u32((r.max_y-bottom+3)/4)+1u;
    if id.x >= n*n || id.y >= ny { return; }
    let x = r.origin_x+i32(id.x%n)*4; let z = r.origin_z+i32(id.x/n)*4;
    let point = vec3<f32>(f32(x),f32(bottom+i32(id.y)*4),f32(z));
    let seed = r.seed_low ^ hash(r.seed_high);
    let rough = registry_noise(point,4u,seed);
    let cheese = registry_noise(point*vec3<f32>(1.0,1.7,1.0),0u,seed);
    let layer = abs(registry_noise(point*vec3<f32>(1.0,3.0,1.0),3u,seed));
    let pillar = registry_noise(point*vec3<f32>(1.0,0.25,1.0),5u,seed);
    let chambers = cheese - layer*0.12 - max(pillar-0.48,0.0)*1.5;
    let a = registry_noise(point*vec3<f32>(1.6,1.0,1.6),1u,seed)+rough*0.06;
    let b = registry_noise(point*vec3<f32>(1.6,1.0,1.6),2u,seed)-rough*0.06;
    var ribbon = 0.0;
    if id.y == 0u { ribbon = ravine_distance(point.xz,seed); }
    nodes[id.y*n*n+id.x] = vec4<f32>(chambers,a,b,ribbon);
}
fn interpolate(local: vec3<f32>, n: u32) -> vec4<f32> {
    let cell = vec3<u32>(floor(local)); let t = fract(local);
    let i = (cell.y*n+cell.z)*n+cell.x;
    let z0 = mix(mix(nodes[i],nodes[i+1u],t.x),mix(nodes[i+n*n],nodes[i+n*n+1u],t.x),t.y);
    let z1 = mix(mix(nodes[i+n],nodes[i+n+1u],t.x),mix(nodes[i+n*n+n],nodes[i+n*n+n+1u],t.x),t.y);
    var result = mix(z0,z1,t.z);
    let j = cell.z*n+cell.x;
    result.w = mix(mix(nodes[j].w,nodes[j+1u].w,t.x),mix(nodes[j+n].w,nodes[j+n+1u].w,t.x),t.z);
    return result;
}
// The same GPU classification drives both the packed cavity volume and vegetation support.
fn column_at(x: u32, z: u32, side: u32) -> Column {
    return columns[((z/16u)*side+x/16u)*256u+(z%16u)*16u+x%16u];
}
fn is_cave(x: u32, y: i32, z: u32, column: Column, r: Request) -> bool {
    if y < r.min_y+5 || y >= column.height { return false; }
    if (column.packed & (1u<<29u)) != 0u && y >= column.height-4 { return false; }
    let bottom = i32(floor(f32(r.min_y)/4.0))*4;
    let values = interpolate(vec3<f32>(f32(x),f32(y-bottom),f32(z))*0.25,r.tile_side*4u+1u);
    let biome = column.packed & 255u;
    var carved = false;
    for (var c=0u;c<4u;c++) {
        let settings = caves.biomes[biome].carvers[c];
        // Registry Y ranges locate tunnel centers, not their outer walls.
        let margin = max(4.0,settings.shape.x*settings.shape.z*4.0);
        let lower = settings.range.x-margin; let upper = settings.range.y+margin;
        if settings.range.z <= 0.0 { continue; }
        if settings.range.w < 0.5 && (f32(y) < lower || f32(y) > upper) { continue; }
        let fade = clamp(min(f32(y)-lower,upper-f32(y))*0.125,0.0,1.0);
        if settings.range.w < 0.5 {
            let strength = clamp(settings.range.z*settings.shape.w,0.0,2.0);
            let chamber_threshold = 0.43-strength*0.085-settings.detail.y*0.025;
            let tunnel_width = (0.042+settings.shape.x*0.028)*settings.shape.y*sqrt(max(strength,0.001));
            let floor = clamp((settings.detail.x+1.0)*0.03,0.0,0.06);
            carved = carved || (values.x > chamber_threshold+(1.0-fade)*0.5)
                || (max(abs(values.y),abs(values.z)*settings.shape.z) < tunnel_width*fade-floor);
        } else {
            let middle = (settings.range.x+settings.range.y)*0.5;
            let bottom_y = max(f32(r.min_y+6),middle-max(12.0,settings.shape.z*22.0));
            let top_y = max(upper,f32(column.height)+3.0);
            let vertical = clamp(min(f32(y)-bottom_y,top_y-f32(y))*0.12,0.0,1.0);
            let width = settings.shape.x*settings.shape.y*settings.range.z*10.0;
            carved = carved || (values.w < width*vertical-1.8 && vertical > 0.0);

        }
    }
    return carved;
}
@compute @workgroup_size(64)
fn cave_mask(@builtin(global_invocation_id) id: vec3<u32>) {
    let r = requests[0]; let width = r.padding*16u+2u; let height = u32(r.max_y-r.min_y);
    let word = id.x+id.y*16384u; let count = width*width*height;
    let volume_words = (count+31u)/32u;
    let surface_width = r.tile_side*16u;
    let surface_words = (surface_width*surface_width+31u)/32u;
    let quart_width = surface_width/4u;
    let bottom = i32(floor(f32(r.min_y)/4.0))*4;
    let quart_layers = u32((r.max_y-bottom+3)/4);
    let biome_count = quart_width*quart_width*quart_layers;
    let biome_words = (biome_count+3u)/4u;
    if word >= max(max(volume_words,surface_words),biome_words) { return; }
    if word < volume_words {
    var bits = 0u;
    for (var bit=0u; bit<32u; bit++) {
        let index = word*32u+bit; if index >= count { break; }
        let x = index%width+15u; let z = (index/width)%width+15u;
        let y = r.min_y+i32(index/(width*width));
        if is_cave(x,y,z,column_at(x,z,r.tile_side),r) { bits |= 1u << bit; }
    }
    mask[word] = bits;
    }
    // A one-bit surface map covers the complete decoration halo, without transferring
    // the halo's full 3D volume. It prevents trees anchored in neighboring chunks from
    // floating over entrances, identically for individual chunks and whole regions.
    if word < surface_words {
    var surface_bits = 0u;
    for (var bit=0u; bit<32u; bit++) {
        let index = word*32u+bit; if index >= surface_width*surface_width { break; }
        let x = index%surface_width; let z = index/surface_width;
        let column = column_at(x,z,r.tile_side);
        if is_cave(x,column.height-1,z,column,r) { surface_bits |= 1u << bit; }
    }
    mask[volume_words+word] = surface_bits;
    }
    // One byte per 4x4x4 biome sample, packed four per word in this same dispatch.
    if word < biome_words {
        var ids = 0u;
        for (var b=0u;b<4u;b++) {
            let index = word*4u+b; if index >= biome_count { break; }
            let x = (index%quart_width)*4u; let z = ((index/quart_width)%quart_width)*4u;
            let y = bottom+i32(index/(quart_width*quart_width))*4;
            ids |= biome_at(x,y,z,column_at(x,z,r.tile_side),r) << (b*8u);
        }
        mask[volume_words+surface_words+word] = ids;
    }
}
