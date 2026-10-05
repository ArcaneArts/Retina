// Conservative certificates only. Unbounded/unsupported inputs always require
// the original GPU point evaluator; these bounds never invent terrain values.
fn density_composed(r:Request)->bool {return (r.padding&(1u<<26u))!=0u;}
fn bounds_unknown()->vec2<f32> {return vec2<f32>(bitcast<f32>(0xff800000u),bitcast<f32>(0x7f800000u));}
fn bounds_finite(v:vec2<f32>)->bool {return all(abs(v)<vec2<f32>(bitcast<f32>(0x7f800000u))) && v.x<=v.y;}
fn bounds_widen(v:vec2<f32>)->vec2<f32> {
    if !bounds_finite(v) {return bounds_unknown();}
    // Covers rounded endpoint arithmetic and the three trilinear mix steps.
    // Near zero/subnormal values remain uncertain rather than certifying a sign.
    let error=max((abs(v.x)+abs(v.y))*0.0000152587890625,0.000000000000000000000000000000000001);
    let out=vec2<f32>(v.x-error,v.y+error);
    if !bounds_finite(out) {return bounds_unknown();}return out;
}
fn bounds_product(a:vec2<f32>,b:vec2<f32>)->vec2<f32> {
    let v=vec4<f32>(a.x*b.x,a.x*b.y,a.y*b.x,a.y*b.y);
    return vec2<f32>(min(min(v.x,v.y),min(v.z,v.w)),max(max(v.x,v.y),max(v.z,v.w)));
}
fn interpolation_bounds(field:u32,lo:vec3<f32>,hi:vec3<f32>,r:Request)->vec2<f32> {
    if any(abs(lo)>vec3<f32>(8388608.0)) || any(abs(hi)>vec3<f32>(8388608.0)) || any(lo>hi) {return bounds_unknown();}
    let info=bytecode[12]+field*4u;
    let step=vec3<f32>(f32(bytecode[info+1u]),f32(bytecode[info+2u]),f32(bytecode[info+1u]));
    let lower=floor(lo/step)*step;
    let size=vec3<u32>(ceil((ceil(hi/step)*step-lower)/step))+vec3<u32>(1u);
    if any(size>vec3<u32>(256u)) || size.x*size.y*size.z>256u {return bounds_unknown();}
    var result=vec2<f32>(bitcast<f32>(0x7f800000u),bitcast<f32>(0xff800000u));
    for(var y=0u;y<size.y;y++){for(var z=0u;z<size.z;z++){for(var x=0u;x<size.x;x++){
        let point=lower+vec3<f32>(f32(x),f32(y),f32(z))*step;
        let cached=interpolation_cached_corner(field,point,r);
        var value=cached.x;
        if cached.y==0.0 {value=run_interpolation_input(field,point,r);}
        if !(abs(value)<bitcast<f32>(0x7f800000u)) {return bounds_unknown();}
        result=vec2<f32>(min(result.x,value),max(result.y,value));
    }}}
    return bounds_widen(result);
}
fn run_density_bounds(program:u32,lo:vec3<f32>,hi:vec3<f32>,r:Request)->vec2<f32> {
    let descriptor=16u+program*8u;let offset=bytecode[descriptor];let count=bytecode[descriptor+1u];let registers=offset+count*8u;
    var values:array<vec2<f32>,BOUND_VALUES>;
    for(var i=0u;i<count;i++){
        let at=offset+i*8u;let op=bytecode[at];let a=bytecode[at+1u];let b=bytecode[at+2u];let c=bytecode[at+3u];
        let p=vec4<f32>(bitcast<f32>(bytecode[at+4u]),bitcast<f32>(bytecode[at+5u]),bitcast<f32>(bytecode[at+6u]),bitcast<f32>(bytecode[at+7u]));
        let av=values[bytecode[registers+min(a,count-1u)]];let bv=values[bytecode[registers+min(b,count-1u)]];let cv=values[bytecode[registers+min(c,count-1u)]];
        var result=bounds_unknown();
        switch op {
            case 0u:{result=vec2<f32>(p.x);}
            case 29u:{result=vec2<f32>(lo[a],hi[a]);}
            case 28u:{if bounds_finite(av) && bounds_finite(bv) && bounds_finite(cv){result=interpolation_bounds(u32(p.x),vec3<f32>(av.x,bv.x,cv.x),vec3<f32>(av.y,bv.y,cv.y),r);}}
            case 4u:{if bounds_finite(av) && bounds_finite(bv){result=av+bv;}}
            case 5u:{if bounds_finite(av) && bounds_finite(bv){result=vec2<f32>(av.x-bv.y,av.y-bv.x);}}
            case 6u:{if bounds_finite(av) && bounds_finite(bv){result=bounds_product(av,bv);}}
            case 8u:{if bounds_finite(av) && bounds_finite(bv){result=min(av,bv);}}
            case 9u:{if bounds_finite(av) && bounds_finite(bv){result=max(av,bv);}}
            case 11u:{if bounds_finite(av){result=vec2<f32>(select(min(abs(av.x),abs(av.y)),0.0,av.x<=0.0 && av.y>=0.0),max(abs(av.x),abs(av.y)));}}
            case 12u:{if bounds_finite(av){result=vec2<f32>(select(min(av.x*av.x,av.y*av.y),0.0,av.x<=0.0 && av.y>=0.0),max(av.x*av.x,av.y*av.y));}}
            case 13u:{if bounds_finite(av){result=av*av*av;}}
            case 14u:{if bounds_finite(av){result=vec2<f32>(select(av.x*0.5,av.x,av.x>0.0),select(av.y*0.5,av.y,av.y>0.0));}}
            case 15u:{if bounds_finite(av){result=vec2<f32>(select(av.x*0.25,av.x,av.x>0.0),select(av.y*0.25,av.y,av.y>0.0));}}
            case 16u:{if bounds_finite(av){let x=clamp(av,vec2<f32>(-1.0),vec2<f32>(1.0));result=x*0.5-x*x*x/24.0;}}
            case 18u:{if bounds_finite(av){result=vec2<f32>(-av.y,-av.x);}}
            case 19u:{if bounds_finite(av){result=sqrt(max(av,vec2<f32>(0.0)));}}
            case 21u:{if bounds_finite(av){result=sign(av);}}
            case 22u:{if bounds_finite(av){result=clamp(av,vec2<f32>(p.x),vec2<f32>(p.y));}}
            case 23u:{if bounds_finite(av){if av.x>=p.x && av.y<p.y {result=bv;}else if av.y<p.x || av.x>=p.y {result=cv;}else if bounds_finite(bv) && bounds_finite(cv){result=vec2<f32>(min(bv.x,cv.x),max(bv.y,cv.y));}}}
            case 24u:{if bounds_finite(av) && bounds_finite(bv) && bounds_finite(cv){let delta=bounds_widen(vec2<f32>(cv.x-bv.y,cv.y-bv.x));let product=bounds_widen(bounds_product(av,delta));result=bv+product;}}
            default:{}
        }
        // Coordinates and constants retain exact singleton values, avoiding
        // artificial lattice expansion. All computed intervals widen outward.
        if op!=0u && op!=29u {result=bounds_widen(result);}
        values[bytecode[registers+i]]=result;
    }
    return values[bytecode[registers+bytecode[descriptor+2u]]];
}
