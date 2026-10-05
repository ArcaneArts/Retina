// GPU-only density, exterior classification and packed cavity masks.
struct Request {
    origin_x: i32, origin_z: i32, min_y: i32, max_y: i32,
    seed_low: u32, seed_high: u32, base_height: f32, amplitude: f32,
    frequency: f32, profile: u32, tile_side: u32, padding: u32,
    density_offset: u32, density_side: u32, density_step_xz: u32, density_step_y: u32,
}
struct Column { height: i32, packed: u32, materials: u32 }
struct NoiseProfile { frequency: f32, amplitude: f32, count: u32, padding: u32, modifiers: array<f32,32> }
struct Carver { range: vec4<f32>, shape: vec4<f32>, detail: vec4<f32>, variation: vec4<f32>, length: vec4<f32>, axis: vec4<f32> }
struct CaveBiome { carvers: array<Carver,4>, climate: vec4<f32>, selection: vec4<f32> }
struct CaveProfile { globals: vec4<u32>, base: array<vec4<u32>,2>, aquifer:array<vec4<u32>,2>, noises: array<NoiseProfile,6>, biomes: array<CaveBiome> }
@group(0) @binding(0) var<storage,read> requests: array<Request>;
@group(0) @binding(1) var<storage,read> columns: array<Column>;
@group(0) @binding(2) var<storage,read> caves: CaveProfile;
@group(0) @binding(3) var<storage,read_write> nodes: array<vec4<f32>>;
@group(0) @binding(4) var<storage,read_write> mask: array<u32>;
@group(0) @binding(7) var<storage,read> climate_table:ClimateTable;
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
// Rounded, finite 3D canyons. Center height and radii belong to the registered
// carver; neither endpoint nor roof is stretched to the terrain surface.
// Curve/edge noise runs at density nodes, not once per carved voxel.
fn ravine_distance(point: vec3<f32>, seed: u32, settings:Carver) -> f32 {
    let cell=vec2<i32>(floor(point.xz/128.0));var distance=1000.0;
    // One tile covers 64 potential source chunks. The carver's registered
    // probability determines whether that tile has a canyon, including zero.
    let probability=1.0-pow(1.0-clamp(settings.range.z,0.0,1.0),64.0);
    for(var dz=-1;dz<=1;dz++){for(var dx=-1;dx<=1;dx++){
        let at=cell+vec2<i32>(dx,dz);let h=hash(bitcast<u32>(at.x)*0x9e3779b9u ^ bitcast<u32>(at.y)*0x85ebca6bu ^ seed ^ 17863u);
        if f32(h&65535u)/65535.0>=probability {continue;}
        let random=vec3<f32>(f32((h>>16u)&255u),f32((hash(h)>>8u)&255u),f32(hash(h+1u)&255u))/255.0;
        var thickness=settings.shape.x;var horizontal=settings.shape.y;var factor=settings.detail.y;
        if settings.variation.y>0.0 {thickness=mix(settings.variation.x,settings.variation.y,random.x);}
        if settings.variation.w>0.0 {horizontal=mix(settings.variation.z,settings.variation.w,random.y);}
        if settings.length.y>0.0 {factor=mix(settings.length.x,settings.length.y,random.z);}
        let half_length=max(1.0,112.0*factor*0.5);
        let center=(vec2<f32>(at)+vec2<f32>(0.25+random.y*0.5,0.25+random.z*0.5))*128.0;
        let height_random=f32(hash(h+3u)&65535u)/65535.0;
        var center_y=mix(settings.range.x,settings.range.y,height_random);
        if settings.length.w>0.0 {
            let span=settings.range.y-settings.range.x;
            let slope=(span-clamp(settings.length.w-1.0,0.0,span))*0.5;
            center_y=settings.range.x+height_random*slope+f32(hash(h+4u)&65535u)/65535.0*(span-slope);
        }
        var y_scale=settings.shape.z;
        if settings.axis.y>0.0 {y_scale=mix(settings.axis.x,settings.axis.y,random.z);}
        // Conservative rejection keeps expensive noise out of distant Y layers.
        let has_vertical_factor=settings.detail.w>0.0 || settings.length.z>0.0;
        let maximum_factor=select(1.0,max(0.2,settings.detail.w+settings.length.z),has_vertical_factor);
        let maximum_y=(1.5+thickness)*y_scale*maximum_factor*1.25+12.0;
        if abs(point.y-center_y)>maximum_y {continue;}
        let angle=f32((h>>8u)&65535u)/65535.0*6.2831853;
        let delta=point.xz-center;let along=dot(delta,vec2<f32>(cos(angle),sin(angle)));let side=dot(delta,vec2<f32>(-sin(angle),cos(angle)));
        let t=along/half_length;
        if abs(t)>=1.0 || abs(side)>half_length*0.18+(1.5+thickness)*horizontal*1.5+6.0 {continue;}
        let phase=f32(h&31u);
        let bend=sin(t*2.2+phase)*half_length*0.10
            +noise3(vec3<f32>(along*0.035,phase,0.0),seed+19391u)*4.0;
        let rotation=mix(settings.axis.z,settings.axis.w,height_random);
        let height_bend=sin(t*2.7+phase)*3.0+sin(rotation)*sin(t*1.8)*8.0;
        let radius=1.5+thickness*sqrt(max(0.0,1.0-t*t));
        let smoothness=max(settings.detail.z,1.0);
        let edge=noise3(vec3<f32>(along*0.06,point.y/(4.0*smoothness),phase),seed+24107u);
        let width=max(0.5,radius*horizontal*(0.82+edge*0.18));
        let vertical_factor=select(1.0,max(0.2,settings.detail.w+settings.length.z*(1.0-abs(t))),has_vertical_factor);
        let vertical_radius=max(2.0,radius*y_scale*vertical_factor*(0.85+edge*0.10));
        let wall=noise3(point*0.045,seed+31337u)*0.6;
        let horizontal_distance=(side-bend+wall)/width;
        let vertical_distance=(point.y-center_y-height_bend)/vertical_radius;
        distance=min(distance,horizontal_distance*horizontal_distance+vertical_distance*vertical_distance+t*t);
    }}return distance;
}
fn biome_at(x: u32, y: i32, z: u32, column: Column, r: Request) -> u32 {
    let surface = column.packed & 65535u;
    if y >= column.height-12 { return surface; }
    let seed = r.seed_low ^ hash(r.seed_high);
    let point = vec3<f32>(f32(r.origin_x+i32(x)),f32(y),f32(r.origin_z+i32(z)));
    if caves.globals.z>0u && climate_table.count.z>climate_table.count.y {
        let actual=run_program(0u,point,r,vec4<f32>(0.0));
        return climate_search(actual,false,true,false,surface,1e20);
    }
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
        if kind == 4u {
            let temperature = noise3(point*vec3<f32>(0.003,0.004,0.003),seed+7919u);
            let difference = vec4<f32>(temperature,humidity,continental,erosion)-b.climate;
            let error = dot(difference*difference,vec4<f32>(2.0,1.5,1.0,0.5));
            if error < fitness {fitness = error; selected = i;}
            continue;
        }
        let value = select(continental,humidity,kind == 1u);
        let climate_target = select(b.climate.z,b.climate.y,kind == 1u);
        if value < climate_target-0.15 { continue; }
        let error = abs(value-climate_target);
        if error < fitness { fitness = error; selected = i; }
    }
    return selected;
}
@compute @workgroup_size(64)
fn underground_queries(@builtin(global_invocation_id) id:vec3<u32>) {
    if id.x!=0u {return;}
    let r=requests[id.y];let bottom=i32(floor(f32(r.min_y)/4.0))*4;
    let y=bottom+i32((r.padding>>8u)&65535u);
    mask[id.y]=biome_at(8u,y,8u,columns[id.y],r);
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
    var chambers = cheese - layer*0.12 - max(pillar-0.48,0.0)*1.5;
    if caves.globals.y>0u {
        if bytecode[5]==0u {chambers=registered_density(point,r);}
        else {chambers=run_program(2u,point,r,vec4<f32>(0.0))[0];}
    }
    let a = registry_noise(point*vec3<f32>(1.6,1.0,1.6),1u,seed)+rough*0.06;
    let b = registry_noise(point*vec3<f32>(1.6,1.0,1.6),2u,seed)-rough*0.06;
    var ribbon=1000.0;let column=column_at(min((id.x%n)*4u,r.tile_side*16u-1u),min((id.x/n)*4u,r.tile_side*16u-1u),r.tile_side);
    for(var c=0u;c<4u;c++){let settings=caves.biomes[column.packed&65535u].carvers[c];if settings.range.w>0.5 && settings.range.z>0.0 {ribbon=min(ribbon,ravine_distance(point,seed,settings));}}
    nodes[id.y*n*n+id.x] = vec4<f32>(chambers,a,b,ribbon);
    let width = (r.padding&255u)*16u+2u;
    let surface_width = r.tile_side*16u;
    let quart_width = surface_width/4u;
    let layers = u32((r.max_y-bottom+3)/4);
    let volume_words = (width*width*u32(r.max_y-r.min_y)+31u)/32u;
    let surface_words = (surface_width*surface_width+31u)/32u;
    // The first stage writes quart IDs. The second can use each underground
    // biome's own carvers, without resampling climate per voxel or adding a pass.
    if id.y < layers && id.x < quart_width*quart_width/2u {
        let word = id.y*(quart_width*quart_width/2u)+id.x;
        var ids = 0u;
        for (var b=0u;b<2u;b++) {
            let at = id.x*2u+b;
            let qx = (at%quart_width)*4u; let qz = (at/quart_width)*4u;
            ids |= biome_at(qx,bottom+i32(id.y)*4,qz,column_at(qx,qz,r.tile_side),r) << (b*16u);
        }
        mask[volume_words+surface_words+word] = ids;
    }

}
fn interpolate(local: vec3<f32>, n: u32) -> vec4<f32> {
    let cell = vec3<u32>(floor(local)); let t = fract(local);
    let i = (cell.y*n+cell.z)*n+cell.x;
    let z0 = mix(mix(nodes[i],nodes[i+1u],t.x),mix(nodes[i+n*n],nodes[i+n*n+1u],t.x),t.y);
    let z1 = mix(mix(nodes[i+n],nodes[i+n+1u],t.x),mix(nodes[i+n*n+n],nodes[i+n*n+n+1u],t.x),t.y);
    return mix(z0,z1,t.z);
}
fn exterior_offset(r: Request) -> u32 {
    let n = r.tile_side*4u+1u;
    let bottom = i32(floor(f32(r.min_y)/4.0))*4;
    return n*n*(u32((r.max_y-bottom+3)/4)+1u);
}
fn chamber_density(x:u32,y:i32,z:u32,sampled:f32,r:Request)->f32 {
    if caves.globals.y>0u && bytecode[5]==0u && (r.density_step_xz%4u!=0u || r.density_step_y%4u!=0u) {
        // The cave grid subdivides vanilla's 4x8x4 cells exactly. Custom cell
        // boundaries inside a cave cell require the original density lattice.
        return registered_density(vec3<f32>(f32(r.origin_x+i32(x)),f32(y),f32(r.origin_z+i32(z))),r);
    }
    return sampled;
}
@compute @workgroup_size(64)
fn cave_exterior(@builtin(global_invocation_id) id: vec3<u32>) {
    let r = requests[0]; let width = r.tile_side*16u;
    let word = id.x; if word >= width*width/4u { return; }
    let bottom = i32(floor(f32(r.min_y)/4.0))*4;
    var limits = vec4<f32>(0.0);
    var entrances = vec4<f32>(0.0);
    let seed=r.seed_low ^ hash(r.seed_high);
    for(var b=0u;b<4u;b++) {
        let index=word*4u+b; let x=index%width; let z=index/width;
        let column=column_at(x,z,r.tile_side);
        var roof=column.height;
        if caves.globals.y>0u && bytecode[5]==0u {
            // Surface extraction defines the terrain envelope. Account for voxel
            // rounding at its zero crossing without stripping the selected topsoil.
            roof=column.height-1;
            while roof>r.min_y+5 {
                let density=chamber_density(x,roof,z,interpolate(vec3<f32>(f32(x),f32(roof-bottom),f32(z))*0.25,r.tile_side*4u+1u).x,r);
                if density>=0.0 { break; }
                roof-=1;
            }
        }
        limits[b]=f32(roof);
        let point=vec3<f32>(f32(r.origin_x+i32(x)),0.0,f32(r.origin_z+i32(z)));
        let aperture=noise3(point*0.012,seed+43117u)+noise3(point*0.026,seed+53731u)*0.25;
        entrances[b]=smoothstep(0.18,0.45,aperture);
    }
    // Four GPU-only limits per vector; this scratch never enters a readback.
    nodes[exterior_offset(r)+word]=limits;
    nodes[exterior_offset(r)+width*width/4u+word]=entrances;
}
// The same GPU classification drives both the packed cavity volume and vegetation support.
fn column_at(x: u32, z: u32, side: u32) -> Column {
    return columns[((z/16u)*side+x/16u)*256u+(z%16u)*16u+x%16u];
}
fn biome_sample(x: u32, y: i32, z: u32, r: Request) -> u32 {
    let width = (r.padding&255u)*16u+2u; let surface_width = r.tile_side*16u;
    let bottom = i32(floor(f32(r.min_y)/4.0))*4;
    let offset = (width*width*u32(r.max_y-r.min_y)+31u)/32u+(surface_width*surface_width+31u)/32u;
    let q = ((u32(y-bottom)/4u)*(surface_width/4u)+(z/4u))*(surface_width/4u)+x/4u;
    return (mask[offset+q/2u] >> ((q%2u)*16u)) & 65535u;
}
fn is_cave(x: u32, y: i32, z: u32, column: Column, r: Request) -> bool {
    if y < r.min_y+5 || y >= column.height { return false; }
    if (column.packed & (1u<<29u)) != 0u && y >= column.height-4 { return false; }
    let bottom = i32(floor(f32(r.min_y)/4.0))*4;
    let values = interpolate(vec3<f32>(f32(x),f32(y-bottom),f32(z))*0.25,r.tile_side*4u+1u);
    let biome = biome_sample(x,y,z,r);
    let index=z*(r.tile_side*16u)+x;
    let exterior=nodes[exterior_offset(r)+index/4u][index%4u];
    let entrance=nodes[exterior_offset(r)+(r.tile_side*16u)*(r.tile_side*16u)/4u+index/4u][index%4u];
    // Gradually narrow near-surface cavities except inside sparse coherent
    // entrance domains. All ordinary cave decisions 24+ blocks down are unchanged.
    let roof=(1.0-entrance)*(1.0-smoothstep(0.0,24.0,f32(column.height-1-y)));
    var carved = caves.globals.y>0u && chamber_density(x,y,z,values.x,r)<-roof*0.20 && f32(y)<exterior;
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
            let chamber_threshold = 0.43-strength*0.085-settings.detail.y*0.025+roof*0.35;
            let tunnel_width = (0.042+settings.shape.x*0.028)*settings.shape.y*sqrt(max(strength,0.001))*(1.0-roof*0.92);
            let floor = clamp((settings.detail.x+1.0)*0.03,0.0,0.06);
            carved = carved || (caves.globals.y==0u && values.x > chamber_threshold+(1.0-fade)*0.5)
                || (max(abs(values.y),abs(values.z)*settings.shape.z) < tunnel_width*fade-floor);
        } else {
            carved = carved || values.w < 1.0-roof*0.25;

        }
    }
    return carved;
}
@compute @workgroup_size(64)
fn cave_mask(@builtin(global_invocation_id) id: vec3<u32>) {
    let r = requests[0]; let width = (r.padding&255u)*16u+2u; let height = u32(r.max_y-r.min_y);
    let word = id.x+id.y*16384u; let count = width*width*height;
    let volume_words = (count+31u)/32u;
    let surface_width = r.tile_side*16u;
    let surface_words = (surface_width*surface_width+31u)/32u;
    if word >= max(volume_words,surface_words) { return; }
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
}

fn mix_hash(value:u32)->u32 {return hash(value);}
fn cell_hash(cell:vec2<i32>,low:u32,high:u32)->u32 {return hash(bitcast<u32>(cell.x)*0x9e3779b9u ^ bitcast<u32>(cell.y)*0x85ebca6bu ^ low ^ hash(high));}
