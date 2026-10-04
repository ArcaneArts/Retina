// One workgroup per vein; each lane owns distinct output words. No atomic order.
struct Vein {
    minimum: vec4<i32>,
    dimensions: vec4<u32>,
    source: vec4<u32>,
}
@group(0) @binding(0) var<storage, read> veins: array<Vein>;
@group(0) @binding(1) var<storage, read> spheres: array<vec4<f32>>;
@group(0) @binding(2) var<storage, read_write> output: array<u32>;
var<workgroup> local_spheres: array<vec4<f32>,64>;
var<workgroup> local_radii: array<f32,64>;
@compute @workgroup_size(64)
fn main(@builtin(workgroup_id) group: vec3<u32>, @builtin(local_invocation_index) lane:u32) {
    raster(group,lane,false,false);
}
@compute @workgroup_size(64)
fn ordered(@builtin(workgroup_id) group: vec3<u32>, @builtin(local_invocation_index) lane:u32) {
    raster(group,lane,true,false);
}
@compute @workgroup_size(64)
fn guarded(@builtin(workgroup_id) group: vec3<u32>, @builtin(local_invocation_index) lane:u32) {
    raster(group,lane,false,true);
}
fn raster(group:vec3<u32>,lane:u32,ordered:bool,guarded:bool) {
    let index = group.x + group.y*256u;
    if index>=arrayLength(&veins) {return;}
    let vein=veins[index];
    if lane<vein.source.y {
        let sphere=spheres[vein.source.x+lane];
        local_spheres[lane]=vec4<f32>(sphere.xyz,1.0/sphere.w);
        local_radii[lane]=sphere.w;
    }
    workgroupBarrier();
    let size=vein.dimensions.x*vein.dimensions.y*vein.dimensions.z;
    let per_word=select(32u,4u,ordered);
    let words=(size+per_word-1u)/per_word;
    for(var wi=lane;wi<words;wi+=64u) {
        var bits=0u;
        var uncertain=0u;
        for(var bit=0u;bit<per_word;bit++) {
            let i=wi*per_word+bit;
            if i>=size {break;}
            let xyz=vein.minimum.xyz+vec3<i32>(i32(i%vein.dimensions.x),i32(i/(vein.dimensions.x*vein.dimensions.z)),i32(i/vein.dimensions.x%vein.dimensions.z));
            let p=vec3<f32>(xyz)+vec3<f32>(0.5);
            let coarse_coordinate=any(abs(p)>=vec3<f32>(4194304.0));
            for(var j=0u;j<vein.source.y;j++) {
                let sphere=local_spheres[j];
                // At large coordinates, multiple integer voxel centers round
                // to one float. Preserve the original CPU per-sphere loop box.
                if guarded && coarse_coordinate {
                    let radius=vec3<f32>(local_radii[j]);
                    if any(xyz<vec3<i32>(floor(sphere.xyz-radius))) || any(xyz>vec3<i32>(floor(sphere.xyz+radius))) {continue;}
                }
                let delta=(p-sphere.xyz)*sphere.w;
                let yz=delta.y*delta.y+delta.z*delta.z;
                let distance=yz+delta.x*delta.x;
                if guarded && distance<0.9999 {
                    bits|=1u<<bit;
                    uncertain&=~(1u<<bit);
                    break;
                }
                // Far wider than arithmetic roundoff near the unit sphere. CPU
                // resolves these few voxels using its original f32 expression.
                if guarded && abs(distance-1.0)<=0.0001 {uncertain|=1u<<bit;}
                if distance<1.0 {
                    if ordered {bits|=(j+1u)<<(bit*8u);} else {bits|=1u<<bit;}
                    if !guarded {break;}
                }
            }
        }
        let stride=select(1u,2u,guarded);
        output[vein.dimensions.w+wi*stride]=bits;
        if guarded {output[vein.dimensions.w+wi*stride+1u]=uncertain;}
    }
}
