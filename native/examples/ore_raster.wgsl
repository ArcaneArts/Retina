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
@compute @workgroup_size(64)
fn main(@builtin(workgroup_id) group: vec3<u32>, @builtin(local_invocation_index) lane:u32) {
    raster(group,lane,false);
}
@compute @workgroup_size(64)
fn ordered(@builtin(workgroup_id) group: vec3<u32>, @builtin(local_invocation_index) lane:u32) {
    raster(group,lane,true);
}
fn raster(group:vec3<u32>,lane:u32,ordered:bool) {
    let index = group.x + group.y*256u;
    if index>=arrayLength(&veins) {return;}
    let vein=veins[index];
    if lane<vein.source.y {local_spheres[lane]=spheres[vein.source.x+lane];}
    workgroupBarrier();
    let size=vein.dimensions.x*vein.dimensions.y*vein.dimensions.z;
    let per_word=select(32u,4u,ordered);
    let words=(size+per_word-1u)/per_word;
    for(var wi=lane;wi<words;wi+=64u) {
        var bits=0u;
        for(var bit=0u;bit<per_word;bit++) {
            let i=wi*per_word+bit;
            if i>=size {break;}
            let xyz=vein.minimum.xyz+vec3<i32>(i32(i%vein.dimensions.x),i32(i/(vein.dimensions.x*vein.dimensions.z)),i32(i/vein.dimensions.x%vein.dimensions.z));
            let p=vec3<f32>(xyz)+vec3<f32>(0.5);
            for(var j=0u;j<vein.source.y;j++) {
                let sphere=local_spheres[j];
                let delta=(p-sphere.xyz)*sphere.w;
                let yz=delta.y*delta.y+delta.z*delta.z;
                if yz+delta.x*delta.x<1.0 {
                    if ordered {bits|=(j+1u)<<(bit*8u);} else {bits|=1u<<bit;}
                    break;
                }
            }
        }
        output[vein.dimensions.w+wi]=bits;
    }
}
