// Registry DAG interpreter. The only per-job inputs are coordinates and seed.
@group(0) @binding(5) var<storage,read> bytecode: array<u32>;
@group(0) @binding(6) var<storage,read_write> surface_nodes: array<f32>;
fn program_noise(point:vec3<f32>,index:u32,request:Request)->f32 {
    let at=bytecode[1]+index*36u;
    var frequency=bitcast<f32>(bytecode[at]);let amplitude=bitcast<f32>(bytecode[at+1u]);
    let seed=request.seed_low ^ mix_hash(request.seed_high) ^ bytecode[at+3u];
    var sum=0.0;
    for(var i=0u;i<bytecode[at+2u];i++) {
        let w=bitcast<f32>(bytecode[at+4u+i]);
        if w!=0.0 {
            sum+=(noise3(point*frequency,seed+i*1013u)+noise3(point*(frequency*1.0181268882175227),(seed^0xa511e9b3u)+i*1013u))*w;
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
@compute @workgroup_size(64)
fn height_nodes(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[id.y];let n=select(5u,r.tile_side*4u+1u,r.tile_side>0u);if id.x>=n*n {return;}
    let point=vec3<f32>(f32(r.origin_x+i32(id.x%n)*4),0.0,f32(r.origin_z+i32(id.x/n)*4));
    var height=run_program(1u,point,r,vec4<f32>(0.0))[0];
    if bytecode[5]==0u {
        let upper=run_program(1u,point,r,vec4<f32>(0.0))[1];let step=i32(bytecode[4]);let bottom=max(r.min_y,bitcast<i32>(bytecode[3]));
        // The game's preliminary surface lookup rounds DOWN to an eight-block
        // probe. Using that probe as the actual terrain ceiling produces shelves.
        // Start above the zero crossing so the first solid sample has an air
        // sample to interpolate against, even when upper_bound is already exact.
        height=f32(bottom);var previous=min(ceil(clamp(upper,f32(bottom),f32(r.max_y))/f32(step))*f32(step),f32(r.max_y));
        var initial_density=run_program(1u,vec3<f32>(point.x,previous,point.z),r,vec4<f32>(0.0))[0];
        while initial_density>0.0 && previous<f32(r.max_y) {
            previous=min(previous+f32(step),f32(r.max_y));
            initial_density=run_program(1u,vec3<f32>(point.x,previous,point.z),r,vec4<f32>(0.0))[0];
        }
        var previous_density=0.0;
        for(var y=i32(previous);y>=bottom;y-=step) {
            let density=run_program(1u,vec3<f32>(point.x,f32(y),point.z),r,vec4<f32>(0.0))[0];
            if density>0.0 {height=f32(y);if previous>f32(y) && previous_density<=0.0 {height=mix(f32(y),previous,density/max(density-previous_density,0.000001));}break;}
            previous=f32(y);previous_density=density;
        }
    }
    let node=(id.y*select(25u,20000u,r.tile_side>0u)+id.x)*8u;
    surface_nodes[node]=clamp(height+1.0,f32(r.min_y+1),f32(r.max_y));
    let climate=run_program(0u,point,r,vec4<f32>(0.0));
    for(var channel=0u;channel<6u;channel++){surface_nodes[node+1u+channel]=climate[channel];}
}
fn registered_height(point:vec2<f32>,r:Request,index:u32)->f32 {
    let n=select(5u,r.tile_side*4u+1u,r.tile_side>0u);
    let local=(point-vec2<f32>(f32(r.origin_x),f32(r.origin_z)))*0.25;
    let cell=vec2<u32>(clamp(floor(local),vec2<f32>(0.0),vec2<f32>(f32(n-2u))));let t=clamp(local-vec2<f32>(cell),vec2<f32>(0.0),vec2<f32>(1.0));
    let at=(index*select(25u,20000u,r.tile_side>0u)+cell.y*n+cell.x)*8u;
    return mix(mix(surface_nodes[at],surface_nodes[at+8u],t.x),mix(surface_nodes[at+n*8u],surface_nodes[at+(n+1u)*8u],t.x),t.y);
}
fn registered_climate(point:vec2<f32>,r:Request,index:u32)->array<f32,6> {
    let n=select(5u,r.tile_side*4u+1u,r.tile_side>0u);
    let local=(point-vec2<f32>(f32(r.origin_x),f32(r.origin_z)))*0.25;
    let cell=vec2<u32>(clamp(floor(local),vec2<f32>(0.0),vec2<f32>(f32(n-2u))));let t=clamp(local-vec2<f32>(cell),vec2<f32>(0.0),vec2<f32>(1.0));
    let at=(index*select(25u,20000u,r.tile_side>0u)+cell.y*n+cell.x)*8u;var climate:array<f32,6>;
    for(var c=0u;c<6u;c++){let j=at+1u+c;climate[c]=mix(mix(surface_nodes[j],surface_nodes[j+8u],t.x),mix(surface_nodes[j+n*8u],surface_nodes[j+(n+1u)*8u],t.x),t.y);}
    return climate;
}
