// Actual Minecraft BIOME_INFO_NOISE permutation, uploaded once for the profile.
@group(0) @binding(0) var<storage, read> data:array<u32>;
@group(0) @binding(1) var<storage, read> points:array<vec4<u32>>;
@group(0) @binding(2) var<storage, read_write> output:array<i32>;
const gradients:array<vec2<f32>,12>=array<vec2<f32>,12>(
    vec2<f32>(1,1),vec2<f32>(-1,1),vec2<f32>(1,-1),vec2<f32>(-1,-1),
    vec2<f32>(1,0),vec2<f32>(-1,0),vec2<f32>(1,0),vec2<f32>(-1,0),
    vec2<f32>(0,1),vec2<f32>(0,-1),vec2<f32>(0,1),vec2<f32>(0,-1));
fn permute(i:i32)->u32 {return data[u32(i)&255u];}
fn corner(g:u32,p:vec2<f32>)->f32 {
    let t=max(0.0,0.5-dot(p,p));let square=t*t;
    return square*square*dot(gradients[g],p);
}
fn placement_noise(p:vec2<f32>)->f32 {
    let skew=(p.x+p.y)*0.3660254037844386;
    let cell=vec2<i32>(floor(p+skew));
    let unskew=f32(cell.x+cell.y)*0.21132486540518713;
    let a=p-(vec2<f32>(cell)-unskew);
    let step=select(vec2<i32>(0,1),vec2<i32>(1,0),a.x>a.y);
    let b=a-vec2<f32>(step)+0.21132486540518713;
    let c=a-1.0+0.42264973081037426;
    let g0=permute(cell.x+i32(permute(cell.y)))%12u;
    let g1=permute(cell.x+step.x+i32(permute(cell.y+step.y)))%12u;
    let g2=permute(cell.x+1+i32(permute(cell.y+1)))%12u;
    return 70.0*(corner(g0,a)+corner(g1,b)+corner(g2,c));
}
@compute @workgroup_size(64)
fn counts(@builtin(global_invocation_id) id:vec3<u32>) {
    let index=id.y*16384u+id.x;
    if index>=arrayLength(&points) {return;}
    let q=points[index];let rule=256u+q.z*8u;
    let p=vec2<f32>(vec2<i32>(bitcast<i32>(q.x),bitcast<i32>(q.y)))/bitcast<f32>(data[rule+4u]);
    let n=placement_noise(p);
    var count:i32;
    if data[rule]==0u {
        count=select(bitcast<i32>(data[rule+3u]),bitcast<i32>(data[rule+2u]),n<bitcast<f32>(data[rule+1u]));
    } else {
        count=i32(ceil((n+bitcast<f32>(data[rule+5u]))*f32(bitcast<i32>(data[rule+6u]))));
    }
    output[index]=max(0,count);
}
