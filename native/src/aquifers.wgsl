// Registered local aquifers. Geometry and noise remain GPU-only; Rust receives
// material runs and the existing one-bit air/exposure plane.
fn aquifer_div(a:i32,b:i32)->i32 {let q=a/b;return q-select(0,1,a%b<0);}
fn aquifer_min(r:Request)->vec3<i32> {
    return vec3<i32>(aquifer_div(r.origin_x+10,16),aquifer_div(r.min_y+1,12)-1,aquifer_div(r.origin_z+10,16));
}
fn aquifer_max(r:Request)->vec3<i32> {
    return vec3<i32>(aquifer_div(r.origin_x+i32((r.padding&255u)*16u)+11,16)+1,aquifer_div(r.max_y,12)+1,aquifer_div(r.origin_z+i32((r.padding&255u)*16u)+11,16)+1);
}
fn aquifer_size(r:Request)->vec3<u32> {return vec3<u32>(aquifer_max(r)-aquifer_min(r)+vec3<i32>(1));}
fn aquifer_surface_min(r:Request)->vec2<i32> {return aquifer_min(r).xz*16-vec2<i32>(48);}
fn aquifer_surface_size(r:Request)->vec2<u32> {
    return vec2<u32>((aquifer_max(r).xz*16+vec2<i32>(25)-aquifer_surface_min(r))/4+vec2<i32>(1));
}
fn aquifer_surface_offset(r:Request)->u32 {return exterior_offset(r)+(r.tile_side*16u)*(r.tile_side*16u)/2u;}
fn aquifer_centers_offset(r:Request)->u32 {
    let n=aquifer_surface_size(r);return aquifer_surface_offset(r)+n.x*n.y;
}
fn aquifer_barrier_offset(r:Request)->u32 {
    let n=aquifer_size(r);return aquifer_centers_offset(r)+n.x*n.y*n.z*2u;
}
fn aquifer_index(cell:vec3<i32>,r:Request)->u32 {
    let p=vec3<u32>(cell-aquifer_min(r));let n=aquifer_size(r);return (p.y*n.z+p.z)*n.x+p.x;
}
fn aquifer_volume_words(r:Request)->u32 {let w=(r.padding&255u)*16u+2u;return (w*w*u32(r.max_y-r.min_y)+31u)/32u;}
fn aquifer_fluid_offset(r:Request)->u32 {
    let w=r.tile_side*16u;let q=w/4u;let bottom=aquifer_div(r.min_y,4)*4;let layers=u32((r.max_y-bottom+3)/4);
    let core=((r.padding&255u)*16u)*((r.padding&255u)*16u);
    return aquifer_volume_words(r)+(w*w+31u)/32u+(q*q*layers+1u)/2u+4u+core*2u+((core+255u)/256u)*2u;
}
fn aquifer_fluid(x:u32,y:i32,z:u32,r:Request)->u32 {
    let w=(r.padding&255u)*16u+2u;let i=(u32(y-r.min_y)*w+z-15u)*w+x-15u;
    let base=aquifer_fluid_offset(r)+i/32u*2u;let bit=i%32u;
    return ((mask[base]>>bit)&1u)|(((mask[base+1u]>>bit)&1u)<<1u);
}
const AQUIFER_DRY:i32=-32512;
fn aquifer_global(y:i32)->vec2<i32> {
    let lava=bitcast<i32>(caves.base[1].z);let sea=bitcast<i32>(bytecode[8]);
    return select(vec2<i32>(sea,i32(caves.base[0].w)),vec2<i32>(-54,i32(caves.base[1].y)),y<lava);
}
fn aquifer_at(status:vec2<i32>,y:i32)->u32 {return select(0u,u32(status.y),y<status.x);}
@compute @workgroup_size(64)
fn aquifer_surface(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[0];let n=aquifer_surface_size(r);if id.x>=n.x*n.y {return;}
    let xz=aquifer_surface_min(r)+vec2<i32>(i32(id.x%n.x),i32(id.x/n.x))*4;
    let p=vec3<f32>(f32(xz.x),0.0,f32(xz.y));
    var level=bitcast<i32>(caves.aquifer[0].z);let step=i32(caves.aquifer[0].w);
    let value=run_program(caves.aquifer[0].y+4u,p,r,vec4<f32>(0.0));
    if caves.aquifer[1].x!=0u {level=i32(floor(value[0]));}
    else {
        let top=aquifer_div(i32(floor(value[1])),step)*step;
        for(var y=top;y>=level;y-=step) {
            if run_program(caves.aquifer[0].y+4u,vec3<f32>(p.x,f32(y),p.z),r,vec4<f32>(0.0))[0]>0.0 {level=y;break;}
        }
    }
    nodes[aquifer_surface_offset(r)+id.x]=vec4<f32>(f32(level),0.0,0.0,0.0);
}
fn aquifer_surface_level(xz:vec2<i32>,r:Request)->i32 {
    let p=vec2<u32>((vec2<i32>(aquifer_div(xz.x,4),aquifer_div(xz.y,4))*4-aquifer_surface_min(r))/4);
    return i32(nodes[aquifer_surface_offset(r)+p.y*aquifer_surface_size(r).x+p.x].x);
}
const AQUIFER_SURFACE_OFFSETS:array<vec2<i32>,13>=array<vec2<i32>,13>(
    vec2<i32>(0,0),vec2<i32>(-2,-1),vec2<i32>(-1,-1),vec2<i32>(0,-1),vec2<i32>(1,-1),
    vec2<i32>(-3,0),vec2<i32>(-2,0),vec2<i32>(-1,0),vec2<i32>(1,0),
    vec2<i32>(-2,1),vec2<i32>(-1,1),vec2<i32>(0,1),vec2<i32>(1,1));
fn aquifer_status(point:vec3<i32>,r:Request)->vec2<i32> {
    let global=aquifer_global(point.y);var lowest=2147483647;var under=false;
    for(var i=0u;i<13u;i++) {
        let surface=aquifer_surface_level(point.xz+AQUIFER_SURFACE_OFFSETS[i]*16,r);let adjusted=surface+8;
        if i==0u && point.y-12>adjusted {return global;}
        let reaches=point.y+12>adjusted;
        if reaches || i==0u {
            let wet=aquifer_global(adjusted);
            if aquifer_at(wet,adjusted)!=0u {if i==0u {under=true;}if reaches {return wet;}}
        }
        lowest=min(lowest,surface);
    }
    let sampled=run_program(caves.aquifer[0].y,vec3<f32>(point),r,vec4<f32>(0.0));
    var level=AQUIFER_DRY;
    if sampled[1]<=0.0 {
        let factor=select(0.0,clamp(1.0-f32(lowest+8-point.y)/64.0,0.0,1.0),under);
        let flood=clamp(sampled[0],-1.0,1.0);
        if flood>mix(0.8,-0.3,factor) {level=global.x;}
        else if flood>mix(0.4,-0.8,factor) {
            let cell=vec3<i32>(aquifer_div(point.x,16),aquifer_div(point.y,40),aquifer_div(point.z,16));
            let spread=run_program(caves.aquifer[0].y+1u,vec3<f32>(cell),r,vec4<f32>(0.0))[0]*10.0;
            level=min(lowest,cell.y*40+20+i32(floor(spread/3.0))*3);
        }
    }
    var material=global.y;
    if level<=-10 && level!=AQUIFER_DRY && material!=i32(caves.base[1].y) {
        let cell=vec3<i32>(aquifer_div(point.x,64),aquifer_div(point.y,40),aquifer_div(point.z,64));
        if abs(run_program(caves.aquifer[0].y+2u,vec3<f32>(cell),r,vec4<f32>(0.0))[0])>0.3 {material=i32(caves.base[1].y);}
    }
    return vec2<i32>(level,material);
}
@compute @workgroup_size(64)
fn aquifer_centers(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[0];let n=aquifer_size(r);if id.x>=n.x*n.y*n.z {return;}
    let cell=aquifer_min(r)+vec3<i32>(i32(id.x%n.x),i32(id.x/(n.x*n.z)),i32((id.x/n.x)%n.z));
    let h=hash(bitcast<u32>(cell.x)*0x9e3779b9u ^ bitcast<u32>(cell.y)*0x85ebca6bu ^ bitcast<u32>(cell.z)*0xc2b2ae35u ^ r.seed_low ^ hash(r.seed_high)+91871u);
    let point=cell*vec3<i32>(16,12,16)+vec3<i32>(i32(h%10u),i32(hash(h+1u)%9u),i32(hash(h+2u)%10u));
    let status=aquifer_status(point,r);let at=aquifer_centers_offset(r)+id.x*2u;
    nodes[at]=vec4<f32>(vec3<f32>(point),f32(status.x));nodes[at+1u]=vec4<f32>(f32(status.y),0.0,0.0,0.0);
}
@compute @workgroup_size(64)
fn aquifer_barrier(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[0];let n=r.tile_side*4u+1u;let bottom=aquifer_div(r.min_y,4)*4;let ny=u32((r.max_y-bottom+3)/4)+1u;
    if id.x>=(n*n*ny+3u)/4u {return;}var data=vec4<f32>(0.0);
    for(var b=0u;b<4u;b++) {
        let at=id.x*4u+b;if at>=n*n*ny {break;}
        let p=vec3<f32>(f32(r.origin_x+i32(at%n)*4),f32(bottom+i32(at/(n*n))*4),f32(r.origin_z+i32((at/n)%n)*4));
        data[b]=run_program(caves.aquifer[0].y+3u,p,r,vec4<f32>(0.0))[0];
    }
    nodes[aquifer_barrier_offset(r)+id.x]=data;
}
fn aquifer_barrier_sample(at:u32,r:Request)->f32 {return nodes[aquifer_barrier_offset(r)+at/4u][at%4u];}
fn aquifer_barrier_value(p:vec3<f32>,r:Request)->f32 {
    let cell=vec3<u32>(floor(p));let t=fract(p);let n=r.tile_side*4u+1u;let i=(cell.y*n+cell.z)*n+cell.x;
    let a=mix(mix(aquifer_barrier_sample(i,r),aquifer_barrier_sample(i+1u,r),t.x),mix(aquifer_barrier_sample(i+n*n,r),aquifer_barrier_sample(i+n*n+1u,r),t.x),t.y);
    let b=mix(mix(aquifer_barrier_sample(i+n,r),aquifer_barrier_sample(i+n+1u,r),t.x),mix(aquifer_barrier_sample(i+n*n+n,r),aquifer_barrier_sample(i+n*n+n+1u,r),t.x),t.y);
    return mix(a,b,t.z);
}
fn aquifer_pressure(y:i32,noise:f32,a:vec2<i32>,b:vec2<i32>)->f32 {
    let type1=aquifer_at(a,y);let type2=aquifer_at(b,y);
    if caves.aquifer[1].y!=0u && ((type1==caves.base[1].y && type2==caves.base[0].w)||(type2==caves.base[1].y && type1==caves.base[0].w)) {return 2.0;}
    let difference=abs(a.x-b.x);if difference==0 {return 0.0;}
    let distance=f32(y)+0.5-f32(a.x+b.x)*0.5;let edge=f32(difference)*0.5-abs(distance);
    var gradient=0.0;
    if distance>0.0 {gradient=edge/select(2.5,1.5,edge>0.0);}
    else {gradient=(edge+3.0)/select(10.0,3.0,edge+3.0>0.0);}
    return 2.0*(gradient+select(0.0,noise,gradient>=-2.0 && gradient<=2.0));
}
fn aquifer_similar(a:i32,b:i32)->f32 {return 1.0-f32(b-a)/25.0;}
// 0 air, 1 default fluid, 2 lava, 3 solid pressure barrier.
fn aquifer_substance(x:u32,y:i32,z:u32,r:Request)->u32 {
    let global=aquifer_global(y);
    if aquifer_at(global,y)==caves.base[1].y {return 2u;}
    if caves.aquifer[0].x==1u {return select(0u,1u,aquifer_at(global,y)!=0u);}
    let point=vec3<i32>(r.origin_x+i32(x),y,r.origin_z+i32(z));
    let anchor=vec3<i32>(aquifer_div(point.x-5,16),aquifer_div(y+1,12),aquifer_div(point.z-5,16));
    var distance=array<i32,3>(2147483647,2147483647,2147483647);var closest=array<u32,3>(0u,0u,0u);
    for(var dx=0;dx<=1;dx++){for(var dy=-1;dy<=1;dy++){for(var dz=0;dz<=1;dz++){
        let index=aquifer_index(anchor+vec3<i32>(dx,dy,dz),r);let center=nodes[aquifer_centers_offset(r)+index*2u];
        let delta=vec3<i32>(center.xyz)-point;let squared=delta*delta;let d=squared.x+squared.y+squared.z;
        for(var i=0u;i<3u;i++){if distance[i]>=d {
            for(var j=2u;j>i;j--){distance[j]=distance[j-1u];closest[j]=closest[j-1u];}
            distance[i]=d;closest[i]=index;break;
        }}
    }}}
    var status:array<vec2<i32>,3>;
    for(var i=0u;i<3u;i++){let at=aquifer_centers_offset(r)+closest[i]*2u;status[i]=vec2<i32>(i32(nodes[at].w),i32(nodes[at+1u].x));}
    let material=aquifer_at(status[0],y);let result=select(select(0u,1u,material!=0u),2u,material==caves.base[1].y);
    let s12=aquifer_similar(distance[0],distance[1]);if s12<=0.0 {return result;}
    if caves.aquifer[1].y!=0u && material==caves.base[0].w && aquifer_at(aquifer_global(y-1),y-1)==caves.base[1].y {return result;}
    let bottom=aquifer_div(r.min_y,4)*4;let local=vec3<f32>(f32(x),f32(y-bottom),f32(z))*0.25;
    let noise=aquifer_barrier_value(local,r);
    // Additional registered carvers lack a final-density value. Preserve an
    // explicitly negative proxy for those cells, rather than passing solid density.
    let density=min(-0.02,chamber_density(x,y,z,interpolate(local,r.tile_side*4u+1u).x,r));
    if density+s12*aquifer_pressure(y,noise,status[0],status[1])>0.0 {return 3u;}
    let s13=aquifer_similar(distance[0],distance[2]);let s23=aquifer_similar(distance[1],distance[2]);
    if s13>0.0 && density+s12*s13*aquifer_pressure(y,noise,status[0],status[2])>0.0 {return 3u;}
    if s23>0.0 && density+s12*s23*aquifer_pressure(y,noise,status[1],status[2])>0.0 {return 3u;}
    return result;
}
@compute @workgroup_size(64)
fn aquifer_mask(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[0];let width=(r.padding&255u)*16u+2u;let count=width*width*u32(r.max_y-r.min_y);
    let word=id.x+id.y*16384u;if word>=aquifer_volume_words(r) {return;}
    let geometry=mask[word];var air=0u;var fluid1=0u;var fluid2=0u;
    for(var bit=0u;bit<32u;bit++) {
        let index=word*32u+bit;if index>=count {break;}if (geometry&(1u<<bit))==0u {continue;}
        let x=index%width+15u;let z=(index/width)%width+15u;let y=r.min_y+i32(index/(width*width));
        let substance=aquifer_substance(x,y,z,r);
        if substance==0u {air|=1u<<bit;}
        if substance==1u {fluid1|=1u<<bit;}
        if substance==2u {fluid2|=1u<<bit;}
    }
    mask[word]=air;mask[aquifer_fluid_offset(r)+word*2u]=fluid1;mask[aquifer_fluid_offset(r)+word*2u+1u]=fluid2;
}
