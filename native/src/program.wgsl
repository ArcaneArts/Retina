// Registry DAG interpreter. The only per-job inputs are coordinates and seed.
@group(0) @binding(5) var<storage,read> bytecode: array<u32>;
@group(0) @binding(6) var<storage,read_write> surface_nodes: array<f32>;
fn program_noise(point:vec3<f32>,index:u32,request:Request)->f32 {
    let at=bytecode[1]+index*37u;
    var frequency=bitcast<f32>(bytecode[at]);let amplitude=bitcast<f32>(bytecode[at+1u]);
    let seed=request.seed_low ^ mix_hash(request.seed_high) ^ bytecode[at+3u];
    let scaled=point*vec3<f32>(bitcast<f32>(bytecode[at+36u]),1.0,bitcast<f32>(bytecode[at+36u]));
    var sum=0.0;
    for(var i=0u;i<bytecode[at+2u];i++) {
        let w=bitcast<f32>(bytecode[at+4u+i]);
        if w!=0.0 {
            sum+=(noise3(scaled*frequency,seed+i*1013u)+noise3(scaled*(frequency*1.0181268882175227),(seed^0xa511e9b3u)+i*1013u))*w;
        }
        frequency*=2.0;
    }
    // noise3 has a 1.6 gain for the legacy field; remove it for registered Perlin amplitudes.
    return sum*amplitude*0.625;
}
fn run_program(program:u32,point:vec3<f32>,request:Request,context:vec4<f32>)->array<f32,6> {
    let descriptor=12u+program*8u;let offset=bytecode[descriptor];let count=bytecode[descriptor+1u];
    var values:array<f32,1024>;
    for(var i=0u;i<count;i++) {
        let at=offset+i*8u;let op=bytecode[at];let a=bytecode[at+1u];let b=bytecode[at+2u];let c=bytecode[at+3u];
        let p=vec4<f32>(bitcast<f32>(bytecode[at+4u]),bitcast<f32>(bytecode[at+5u]),bitcast<f32>(bytecode[at+6u]),bitcast<f32>(bytecode[at+7u]));
        var result=0.0;
        switch op {
            case 0u:{result=p.x;}
            case 1u:{result=program_noise(point*vec3<f32>(p.y,p.z,p.y)+vec3<f32>(values[a],values[b],values[c]),u32(p.x),request);}
            case 2u:{var q=point;if b==1u {q.y=0.0;}if b==2u {q=vec3<f32>(point.z,point.x,0.0);}result=program_noise(q*0.25,a,request)*4.0;}
            case 3u:{var t=(point[a]-p.x)/(p.y-p.x);if b==1u {t=fract(t);}else if b==2u {t=1.0-abs(fract(t*0.5)*2.0-1.0);}else {t=clamp(t,0.0,1.0);}result=mix(p.z,p.w,t);}
            case 4u:{result=values[a]+values[b];}
            case 5u:{result=values[a]-values[b];}
            case 6u:{result=values[a]*values[b];}
            case 7u:{result=values[a]/select(values[b],0.000001,abs(values[b])<0.000001);}
            case 8u:{result=min(values[a],values[b]);}
            case 9u:{result=max(values[a],values[b]);}
            case 10u:{result=pow(max(values[a],0.0),values[b]);}
            case 11u:{result=abs(values[a]);}
            case 12u:{result=values[a]*values[a];}
            case 13u:{result=values[a]*values[a]*values[a];}
            case 14u:{result=select(values[a]*0.5,values[a],values[a]>0.0);}
            case 15u:{result=select(values[a]*0.25,values[a],values[a]>0.0);}
            case 16u:{let x=clamp(values[a],-1.0,1.0);result=x*0.5-x*x*x/24.0;}
            case 17u:{result=1.0/select(values[a],0.000001,abs(values[a])<0.000001);}
            case 18u:{result=-values[a];}
            case 19u:{result=sqrt(max(values[a],0.0));}
            case 20u:{result=log(max(values[a],0.000001));}
            case 21u:{result=sign(values[a]);}
            case 22u:{result=clamp(values[a],p.x,p.y);}
            case 23u:{result=select(values[c],values[b],values[a]>=p.x && values[a]<p.y);}
            case 24u:{result=mix(values[b],values[c],values[a]);}
            case 25u:{
                let x=values[a];var j=0u;
                for(var k=1u;k<c;k++){if x>=bitcast<f32>(bytecode[bytecode[2]+(b+k)*4u]){j=k;}}
                let left=bytecode[2]+(b+j)*4u;let lx=bitcast<f32>(bytecode[left]);let ld=bitcast<f32>(bytecode[left+1u]);let lv=values[u32(bitcast<f32>(bytecode[left+2u]))];
                if x<lx || j==c-1u {result=lv+(x-lx)*ld;}else {
                    let right=left+4u;let rx=bitcast<f32>(bytecode[right]);let rd=bitcast<f32>(bytecode[right+1u]);let rv=values[u32(bitcast<f32>(bytecode[right+2u]))];
                    let span=rx-lx;let t=(x-lx)/span;let delta=rv-lv;
                    result=mix(lv,rv,t)+t*(1.0-t)*mix(ld*span-delta,-rd*span+delta,t);
                }
            }
            case 26u:{
                let limit=point*vec3<f32>(684.412*p.x,684.412*p.y,684.412*p.x)/32768.0;
                let main=point*vec3<f32>(684.412*p.x/p.z,684.412*p.y/p.w,684.412*p.x/p.z)/128.0;
                let seed=request.seed_low ^ mix_hash(request.seed_high);
                var lower=0.0;var upper=0.0;var blend=0.0;var weights=0.0;var weight=1.0;
                for(var octave=0u;octave<8u;octave++) {let scale=exp2(f32(octave));lower+=noise3(limit*scale,seed+octave*1013u)*weight;upper+=noise3(limit*scale,seed+7919u+octave*1013u)*weight;blend+=noise3(main*scale,seed+15838u+octave*1013u)*weight;weights+=weight;weight*=0.5;}
                result=mix(lower,upper,clamp(blend/weights*0.5+0.5,0.0,1.0))/weights;
            }
            case 40u:{result=select(0.0,values[b],values[a]!=0.0);}
            case 41u:{result=select(values[b],values[a],values[a]!=0.0);}
            case 42u:{if bytecode[7]>0u {let band=i32(round(point.y))+i32(context.w);let size=i32(bytecode[7]);result=f32(bytecode[bytecode[6]+u32(((band%size)+size)%size)])+1.0;}}
            case 43u:{result=select(0.0,1.0,values[a]==0.0);}
            case 44u:{result=select(0.0,1.0,values[a]>=p.x && values[a]<=p.y);}
            // context: stone depth, surface depth, local slope, terracotta offset.
            case 45u:{result=select(0.0,1.0,a==1u && context.x<=1.0+p.x+select(0.0,context.y,b==1u)+p.y*0.5);}
            case 46u:{result=select(0.0,1.0,point.y+select(0.0,context.x,a==1u)>=f32(bitcast<i32>(bytecode[8]))+p.x+context.y*p.y);}
            case 47u:{result=select(0.0,1.0,point.y+select(0.0,context.x,a==1u)>=p.x+context.y*p.y);}
            case 48u:{let h=cell_hash(vec2<i32>(point.xz),request.seed_low+u32(program)*7919u,request.seed_high);let chance=f32(h&65535u)/65535.0;result=select(0.0,1.0,chance<clamp((p.y-point.y)/max(p.y-p.x,1.0),0.0,1.0));}
            case 49u:{result=select(0.0,1.0,context.z>3.0);}
            case 50u:{result=select(0.0,1.0,context.y<=0.0);}
            case 51u:{var temperature=p.x;let snowline=f32(bitcast<i32>(bytecode[8]))+17.0;
                if point.y>snowline {temperature-=(noise3(point*vec3<f32>(0.125,0.0,0.125),1234u)*8.0+point.y-snowline)*0.00125;}
                result=select(0.0,1.0,temperature<0.15);}

            default:{}
        }
        values[i]=result;
    }
    var roots:array<f32,6>;for(var i=0u;i<6u;i++){roots[i]=values[bytecode[descriptor+2u+i]];}return roots;
}
fn density_floor_div(value:i32,step:i32)->i32 {
    return value/step-select(0,1,value%step<0);
}
fn density_origin(r:Request)->vec3<i32> {
    let sx=i32(r.density_step_xz);let sy=i32(r.density_step_y);
    // Four-block guard covers the surface-rule slope probes on every tile edge.
    return vec3<i32>(density_floor_div(r.origin_x-4,sx)*sx,density_floor_div(r.min_y,sy)*sy,density_floor_div(r.origin_z-4,sx)*sx);
}
fn density_layers(r:Request)->u32 {
    let bottom=density_origin(r).y;let step=i32(r.density_step_y);
    return u32((r.max_y-bottom+step-1)/step)+1u;
}
@compute @workgroup_size(64)
fn density_nodes(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[id.z];let n=r.density_side;
    if id.x>=n*n || id.y>=density_layers(r) {return;}
    let origin=density_origin(r);
    let offset=vec3<i32>(i32(id.x%n)*i32(r.density_step_xz),i32(id.y)*i32(r.density_step_y),i32(id.x/n)*i32(r.density_step_xz));
    surface_nodes[r.density_offset+id.y*n*n+id.x]=run_program(1u,vec3<f32>(origin+offset),r,vec4<f32>(0.0))[0];
}
fn density_corner(cell:vec2<i32>,layer:u32,r:Request)->f32 {
    let n=i32(r.density_side);
    if all(cell>=vec2<i32>(0)) && all(cell<vec2<i32>(n)) {
        return surface_nodes[r.density_offset+(layer*r.density_side+u32(cell.y))*r.density_side+u32(cell.x)];
    }
    // Lake banks and height probes can lie outside a cached tile. Evaluate the
    // same global grid node on the GPU instead of clamping to a tile edge.
    let origin=density_origin(r);
    let point=origin+vec3<i32>(cell.x*i32(r.density_step_xz),i32(layer)*i32(r.density_step_y),cell.y*i32(r.density_step_xz));
    return run_program(1u,vec3<f32>(point),r,vec4<f32>(0.0))[0];
}
fn density_layer(cell:vec2<i32>,t:vec2<f32>,layer:u32,r:Request)->f32 {
    let a=density_corner(cell,layer,r);var b=a;
    if t.x>0.0 {b=density_corner(cell+vec2<i32>(1,0),layer,r);}
    let near=mix(a,b,t.x);
    if t.y==0.0 {return near;}
    let c=density_corner(cell+vec2<i32>(0,1),layer,r);var d=c;
    if t.x>0.0 {d=density_corner(cell+vec2<i32>(1,1),layer,r);}
    return mix(near,mix(c,d,t.x),t.y);
}
fn registered_density(point:vec3<f32>,r:Request)->f32 {
    let origin=density_origin(r);
    let local=(point-vec3<f32>(origin))/vec3<f32>(f32(r.density_step_xz),f32(r.density_step_y),f32(r.density_step_xz));
    let cell=vec2<i32>(floor(local.xz));let t=fract(local.xz);
    let y=clamp(local.y,0.0,f32(density_layers(r)-1u));let layer=min(u32(floor(y)),density_layers(r)-2u);
    return mix(density_layer(cell,t,layer,r),density_layer(cell,t,layer+1u,r),y-f32(layer));
}
fn surface_layer(cell:vec2<i32>,t:vec2<f32>,layer:u32,r:Request,probe:u32)->f32 {
    if probe==0xffffffffu {return density_layer(cell,t,layer,r);}
    let at=lake_probe_offset(r)+(probe*density_layers(r)+layer)*4u;
    return mix(mix(surface_nodes[at],surface_nodes[at+1u],t.x),mix(surface_nodes[at+2u],surface_nodes[at+3u],t.x),t.y);
}
fn density_surface_height(point:vec2<f32>,r:Request,probe:u32)->f32 {
    // Interpolate densities before locating the highest solid interval. Blending
    // corner heights instead loses density gradients and creates planar shelves.
    let origin=density_origin(r);
    let local=(point-vec2<f32>(origin.xz))/f32(r.density_step_xz);
    let cell=vec2<i32>(floor(local));let t=fract(local);
    var layer=density_layers(r)-1u;
    var upper=surface_layer(cell,t,layer,r,probe);
    var upper_y=f32(origin.y)+f32(layer)*f32(r.density_step_y);
    if upper_y>f32(r.max_y) {
        let lower=surface_layer(cell,t,layer-1u,r,probe);
        let lower_y=upper_y-f32(r.density_step_y);
        upper=mix(lower,upper,(f32(r.max_y)-lower_y)/f32(r.density_step_y));
        upper_y=f32(r.max_y);
    }
    if upper>0.0 {return f32(r.max_y);}
    while layer>0u {
        layer-=1u;
        let lower=surface_layer(cell,t,layer,r,probe);
        if lower>0.0 {
            let y=f32(origin.y)+f32(layer)*f32(r.density_step_y);
            let crossing=y+(upper_y-y)*lower/max(lower-upper,0.000001);
            return clamp(crossing+1.0,f32(r.min_y+1),f32(r.max_y));
        }
        upper=lower;
        upper_y=f32(origin.y)+f32(layer)*f32(r.density_step_y);
    }
    return f32(r.min_y+1);
}
fn surface_width(r:Request)->u32 {return select(16u,r.tile_side*16u,r.tile_side>0u)+8u;}
fn surface_offset(r:Request)->u32 {return r.density_offset+r.density_side*r.density_side*density_layers(r);}
fn lake_side(r:Request)->u32 {
    let width=select(16u,r.tile_side*16u,r.tile_side>0u);
    let cell=vec2<i32>(density_floor_div(r.origin_x,128),density_floor_div(r.origin_z,128));
    let remainder=vec2<i32>(r.origin_x,r.origin_z)-cell*128;
    return (width+u32(max(remainder.x,remainder.y))+127u)/128u;
}
fn lake_offset(r:Request)->u32 {return surface_offset(r)+surface_width(r)*surface_width(r);}
fn lake_probe_offset(r:Request)->u32 {return lake_offset(r)+lake_side(r)*lake_side(r)*4u;}
@compute @workgroup_size(64)
fn surface_columns(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[id.y];let width=surface_width(r);if id.x>=width*width {return;}
    let point=vec2<f32>(f32(r.origin_x-4+i32(id.x%width)),f32(r.origin_z-4+i32(id.x/width)));
    surface_nodes[surface_offset(r)+id.x]=density_surface_height(point,r,0xffffffffu);
}
@compute @workgroup_size(64)
fn climate_nodes(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[id.y];if id.x>=25u {return;}
    let point=vec3<f32>(f32(r.origin_x+i32(id.x%5u)*4),0.0,f32(r.origin_z+i32(id.x/5u)*4));
    let node=(id.y*25u+id.x)*8u;
    let climate=run_program(0u,point,r,vec4<f32>(0.0));
    for(var channel=0u;channel<6u;channel++){surface_nodes[node+1u+channel]=climate[channel];}
}
@compute @workgroup_size(64)
fn height_nodes(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[id.y];let n=select(5u,r.tile_side*4u+1u,r.tile_side>0u);if id.x>=n*n {return;}
    let point=vec3<f32>(f32(r.origin_x+i32(id.x%n)*4),0.0,f32(r.origin_z+i32(id.x/n)*4));
    var height=f32(r.min_y);
    if bytecode[5]!=0u {height=run_program(1u,point,r,vec4<f32>(0.0))[0];}
    // Density-based surfaces are found per column after the 3D prepass.
    // Explicit registered height functions retain their separate 2D path.
    let node=(id.y*select(25u,20000u,r.tile_side>0u)+id.x)*8u;
    surface_nodes[node]=clamp(height+1.0,f32(r.min_y+1),f32(r.max_y));
    let climate=run_program(0u,point,r,vec4<f32>(0.0));
    for(var channel=0u;channel<6u;channel++){surface_nodes[node+1u+channel]=climate[channel];}
}
// Keep the full density interpreter out of the per-block column entry point's
// call graph. Its integer coordinates and slope probes always fit this cache.
fn registered_height_fast(point:vec2<f32>,r:Request,index:u32)->f32 {
    if bytecode[5]==0u {
        let local=vec2<u32>(point-vec2<f32>(f32(r.origin_x-4),f32(r.origin_z-4)));
        return surface_nodes[surface_offset(r)+local.y*surface_width(r)+local.x];
    }
    return registered_height_2d(point,r,index);
}
fn registered_height_2d(point:vec2<f32>,r:Request,index:u32)->f32 {
    let n=select(5u,r.tile_side*4u+1u,r.tile_side>0u);
    let local=(point-vec2<f32>(f32(r.origin_x),f32(r.origin_z)))*0.25;
    let cell=vec2<u32>(clamp(floor(local),vec2<f32>(0.0),vec2<f32>(f32(n-2u))));let t=clamp(local-vec2<f32>(cell),vec2<f32>(0.0),vec2<f32>(1.0));
    let at=(index*select(25u,20000u,r.tile_side>0u)+cell.y*n+cell.x)*8u;
    return mix(mix(surface_nodes[at],surface_nodes[at+8u],t.x),mix(surface_nodes[at+n*8u],surface_nodes[at+(n+1u)*8u],t.x),t.y);
}
fn registered_climate(point:vec2<f32>,r:Request,index:u32)->array<f32,6> {
    let n=select(5u,r.tile_side*4u+1u,r.tile_side>0u);
    let local=(point-vec2<f32>(f32(r.origin_x),f32(r.origin_z)))*0.25;
    if any(local<vec2<f32>(0.0)) || any(local>vec2<f32>(f32(n-1u))) {
        // Shared lake probes need global climate samples beyond a request edge.
        let grid=floor(point*0.25)*4.0;let t=fract(point*0.25);
        let a=run_program(0u,vec3<f32>(grid.x,0.0,grid.y),r,vec4<f32>(0.0));
        let b=run_program(0u,vec3<f32>(grid.x+4.0,0.0,grid.y),r,vec4<f32>(0.0));
        let c=run_program(0u,vec3<f32>(grid.x,0.0,grid.y+4.0),r,vec4<f32>(0.0));
        let d=run_program(0u,vec3<f32>(grid.x+4.0,0.0,grid.y+4.0),r,vec4<f32>(0.0));
        var result:array<f32,6>;for(var channel=0u;channel<6u;channel++){result[channel]=mix(mix(a[channel],b[channel],t.x),mix(c[channel],d[channel],t.x),t.y);}return result;
    }
    return registered_climate_fast(point,r,index);
}
fn registered_climate_fast(point:vec2<f32>,r:Request,index:u32)->array<f32,6> {
    let n=select(5u,r.tile_side*4u+1u,r.tile_side>0u);
    let local=(point-vec2<f32>(f32(r.origin_x),f32(r.origin_z)))*0.25;
    let cell=vec2<u32>(clamp(floor(local),vec2<f32>(0.0),vec2<f32>(f32(n-2u))));let t=clamp(local-vec2<f32>(cell),vec2<f32>(0.0),vec2<f32>(1.0));
    let at=(index*select(25u,20000u,r.tile_side>0u)+cell.y*n+cell.x)*8u;var climate:array<f32,6>;
    for(var c=0u;c<6u;c++){let j=at+1u+c;climate[c]=mix(mix(surface_nodes[j],surface_nodes[j+8u],t.x),mix(surface_nodes[j+n*8u],surface_nodes[j+(n+1u)*8u],t.x),t.y);}
    return climate;
}
