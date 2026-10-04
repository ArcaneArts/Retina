// Actual registered NormalNoise stacks: permutations, offsets and weights are
// initialized once by Minecraft. Runtime spatial sampling stays on the GPU.
@group(0) @binding(0) var<storage, read> data:array<u32>;
@group(0) @binding(1) var<storage, read> points:array<vec4<u32>>;
@group(0) @binding(2) var<storage, read_write> output:array<f32>;
const gradients:array<vec3<f32>,16>=array<vec3<f32>,16>(
    vec3<f32>(1,1,0),vec3<f32>(-1,1,0),vec3<f32>(1,-1,0),vec3<f32>(-1,-1,0),
    vec3<f32>(1,0,1),vec3<f32>(-1,0,1),vec3<f32>(1,0,-1),vec3<f32>(-1,0,-1),
    vec3<f32>(0,1,1),vec3<f32>(0,-1,1),vec3<f32>(0,1,-1),vec3<f32>(0,-1,-1),
    vec3<f32>(1,1,0),vec3<f32>(0,-1,1),vec3<f32>(-1,1,0),vec3<f32>(0,-1,-1));
fn phase(point:u32,coefficient:vec2<u32>,offset:vec2<u32>)->vec2<u32> {
    let a0=point&65535u;let a1=point>>16u;
    let b0=coefficient.x&65535u;let b1=coefficient.x>>16u;
    let t0=a0*b0;let t1=a1*b0;let t2=a0*b1;
    let carry=((t0>>16u)+(t1&65535u)+(t2&65535u))>>16u;
    let low=t0+((t1+t2)<<16u);
    let high=a1*b1+(t1>>16u)+(t2>>16u)+carry+point*coefficient.y
        -select(0u,coefficient.x,bitcast<i32>(point)<0);
    let sum=low+offset.x;
    return vec2<u32>(sum,(high+offset.y+select(0u,1u,sum<low))&65535u);
}
fn fraction(p:vec2<u32>)->f32 {
    return f32(p.y&255u)/256.0+f32(p.x)/1099511627776.0;
}
fn permute(layer:u32,i:u32)->u32 {return data[layer+10u+(i&255u)];}
fn gradient(layer:u32,xy:u32,z:u32,p:vec3<f32>)->f32 {
    // Explicit order agrees with GradientNoise.Gradient.dot's float arithmetic.
    let g=gradients[permute(layer,xy+z)&15u];
    return (g.x*p.x+g.y*p.y)+g.z*p.z;
}
fn lerp(t:f32,a:f32,b:f32)->f32 {return a+t*(b-a);}
fn provider_fade(t:f32)->f32 {return t*t*t*(t*(t*6.0-15.0)+10.0);}
fn perlin(layer:u32,point:vec3<u32>)->f32 {
    let coefficient=vec2<u32>(data[layer],data[layer+1u]);
    let px=phase(point.x,coefficient,vec2<u32>(data[layer+4u],data[layer+5u]));
    let py=phase(point.y,coefficient,vec2<u32>(data[layer+6u],data[layer+7u]));
    let pz=phase(point.z,coefficient,vec2<u32>(data[layer+8u],data[layer+9u]));
    let cell=vec3<u32>(px.y>>8u,py.y>>8u,pz.y>>8u);
    let p=vec3<f32>(fraction(px),fraction(py),fraction(pz));
    let x0=permute(layer,cell.x);let x1=permute(layer,cell.x+1u);
    let xy00=permute(layer,x0+cell.y);let xy01=permute(layer,x0+cell.y+1u);
    let xy10=permute(layer,x1+cell.y);let xy11=permute(layer,x1+cell.y+1u);
    let a=vec3<f32>(provider_fade(p.x),provider_fade(p.y),provider_fade(p.z));
    let z0=lerp(a.y,
        lerp(a.x,gradient(layer,xy00,cell.z,p),gradient(layer,xy10,cell.z,p-vec3<f32>(1,0,0))),
        lerp(a.x,gradient(layer,xy01,cell.z,p-vec3<f32>(0,1,0)),gradient(layer,xy11,cell.z,p-vec3<f32>(1,1,0))));
    let z1=lerp(a.y,
        lerp(a.x,gradient(layer,xy00,cell.z+1u,p-vec3<f32>(0,0,1)),gradient(layer,xy10,cell.z+1u,p-vec3<f32>(1,0,1))),
        lerp(a.x,gradient(layer,xy01,cell.z+1u,p-vec3<f32>(0,1,1)),gradient(layer,xy11,cell.z+1u,p-vec3<f32>(1,1,1))));
    return lerp(a.z,z0,z1);
}
@compute @workgroup_size(64)
fn provider_noise(@builtin(global_invocation_id) id:vec3<u32>) {
    let index=id.y*16384u+id.x;
    if index>=arrayLength(&points) {return;}
    let q=points[index];
    let start=data[1u+q.w*2u];let count=data[2u+q.w*2u];
    var value=0.0;
    for(var i=0u;i<count;i++) {
        let layer=start+i*268u;
        value+=bitcast<f32>(data[layer+2u])*perlin(layer,q.xyz);
    }
    output[index]=value;
}
