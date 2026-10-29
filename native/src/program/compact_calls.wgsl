// Sampling loops retain one call site into the full interpreter per helper.
fn density_layer(cell:vec2<i32>,t:vec2<f32>,layer:u32,r:Request)->f32 {
    var corners:array<f32,4>;
    for(var corner=0u;corner<4u;corner++) {
        if corner>=2u && t.y==0.0 {break;}
        if (corner&1u)!=0u && !(t.x>0.0) {
            corners[corner]=corners[corner-1u];continue;
        }
        let offset=vec2<i32>(i32(corner&1u),i32(corner>>1u));
        corners[corner]=density_corner(cell+offset,layer,r);
    }
    let near=mix(corners[0],corners[1],t.x);
    if t.y==0.0 {return near;}
    return mix(near,mix(corners[2],corners[3],t.x),t.y);
}
fn registered_density(point:vec3<f32>,r:Request)->f32 {
    if density_composed(r) {return run_program(1u,point,r,vec4<f32>(0.0))[0];}
    let origin=density_origin(r);
    let local=(point-vec3<f32>(origin))/vec3<f32>(f32(r.density_step_xz),f32(r.density_step_y),f32(r.density_step_xz));
    let cell=vec2<i32>(floor(local.xz));let t=fract(local.xz);
    let y=clamp(local.y,0.0,f32(density_layers(r)-1u));let layer=min(u32(floor(y)),density_layers(r)-2u);
    var samples:array<f32,2>;
    for(var sample=0u;sample<2u;sample++) {
        samples[sample]=density_layer(cell,t,layer+sample,r);
    }
    return mix(samples[0],samples[1],y-f32(layer));
}
fn density_surface_height(point:vec2<f32>,r:Request,probe:u32)->f32 {
    let min_y=generation_min(r);let max_y=generation_max(r);
    if max_y<=min_y {return empty_surface(r);}
    if density_composed(r) {
        if probe!=0xffffffffu {
            var height=empty_surface(r);
            for(var layer=0u;layer<density_layers(r);layer++) {
                height=max(height,surface_nodes[lake_probe_offset(r)+(probe*density_layers(r)+layer)*4u]);
            }
            return height;
        }
        let origin=density_origin(r);
        let cell=vec2<i32>(floor((point-vec2<f32>(origin.xz))/f32(r.density_step_xz)));
        var layer=min(i32(density_layers(r))-2,density_floor_div(max_y-1-origin.y,i32(r.density_step_y)));
        while layer>=0 && origin.y+(layer+1)*i32(r.density_step_y)>min_y {
            let bottom=max(min_y,origin.y+layer*i32(r.density_step_y));
            let top=min(max_y,origin.y+(layer+1)*i32(r.density_step_y));
            let bounds=density_cell_bounds(vec3<i32>(cell.x,layer,cell.y),r);
            if bounds.x>0.0 {return f32(top);}
            if bounds.y>0.0 {
                var y=top-1;
                while y>=bottom {
                    if run_program(1u,vec3<f32>(point.x,f32(y),point.y),r,vec4<f32>(0.0))[0]>0.0 {return f32(max(y+1,r.min_y+1));}
                    y-=1;
                }
            }
            layer-=1;
        }
        return empty_surface(r);
    }
    if min_y!=r.min_y || max_y!=r.max_y {
        for(var y=max_y-1;y>=min_y;y--) {
            if registered_density(vec3<f32>(point.x,f32(y),point.y),r)>0.0 {return f32(y+1);}
        }
        return empty_surface(r);
    }
    // One call site samples successive layers. Inlining this helper must not
    // duplicate the complete density interpreter at three separate sites.
    let origin=density_origin(r);
    let local=(point-vec2<f32>(origin.xz))/f32(r.density_step_xz);
    let cell=vec2<i32>(floor(local));let t=fract(local);
    var layer=density_layers(r)-1u;
    var upper=0.0;var upper_y=0.0;var have_upper=false;
    loop {
        let lower=surface_layer(cell,t,layer,r,probe);
        let y=f32(origin.y)+f32(layer)*f32(r.density_step_y);
        if !have_upper {
            upper=lower;upper_y=y;have_upper=true;
            if upper_y<=f32(r.max_y) && upper>0.0 {return f32(r.max_y);}
        } else {
            if upper_y>f32(r.max_y) {
                let lower_y=upper_y-f32(r.density_step_y);
                upper=mix(lower,upper,(f32(r.max_y)-lower_y)/f32(r.density_step_y));
                upper_y=f32(r.max_y);
                if upper>0.0 {return f32(r.max_y);}
            }
            if lower>0.0 {
                let crossing=y+(upper_y-y)*lower/max(lower-upper,0.000001);
                return clamp(crossing+1.0,f32(r.min_y+1),f32(r.max_y));
            }
            upper=lower;upper_y=y;
        }
        if layer==0u {break;}
        layer-=1u;
    }
    return empty_surface(r);
}
