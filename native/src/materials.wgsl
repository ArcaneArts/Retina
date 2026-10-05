// Complete vertical material runs. The count/emit stages share this evaluator;
// no fixed layer budget, per-voxel host transfer or CPU noise is involved.
fn material_base_offset(r:Request)->u32 {
    let width=(r.padding&255u)*16u+2u;let surface_width=r.tile_side*16u;
    let bottom=i32(floor(f32(r.min_y)/4.0))*4;
    let layers=u32((r.max_y-bottom+3)/4);
    return (width*width*u32(r.max_y-r.min_y)+31u)/32u
        +(surface_width*surface_width+31u)/32u
        +((surface_width/4u)*(surface_width/4u)*layers+1u)/2u;
}
fn material_width(r:Request)->u32 {return (r.padding&255u)*16u;}
fn material_groups(r:Request)->u32 {return (material_width(r)*material_width(r)+255u)/256u;}
fn material_runs_offset(r:Request)->u32 {
    return material_base_offset(r)+4u+2u*material_width(r)*material_width(r)+2u*material_groups(r)
        +select(0u,aquifer_volume_words(r)*2u,caves.aquifer[0].x!=0u);
}
fn material_carved(x:u32,y:i32,z:u32,r:Request)->bool {
    let width=(r.padding&255u)*16u+2u;
    let at=(u32(y-r.min_y)*width+(z-15u))*width+(x-15u);
    return (mask[at/32u]&(1u<<(at%32u)))!=0u;
}
struct MaterialVoxel { id:u32, solid:bool }
fn material_voxel(x:u32,y:i32,z:u32,c:Column,r:Request)->MaterialVoxel {
    let lake=(c.packed&(1u<<29u))!=0u;let icy=(c.packed&(1u<<28u))!=0u;
    let waterline=select(bitcast<i32>(bytecode[8]),c.height+i32((c.packed>>24u)&7u),lake);
    if y>=c.height {
        if y>=waterline {return MaterialVoxel(0u,false);}
        if lake && icy {return MaterialVoxel(caves.base[1].y,false);}
        if !lake && icy && y==waterline-1 {return MaterialVoxel(caves.base[1].x,false);}
        return MaterialVoxel(caves.base[0].w,false);
    }
    if caves.aquifer[0].x!=0u && caves.base[1].w!=0u {
        let fluid=aquifer_fluid(x,y,z,r);
        if fluid!=0u {return MaterialVoxel(select(caves.base[0].w,caves.base[1].y,fluid==2u),false);}
        if material_carved(x,y,z,r) {return MaterialVoxel(0u,false);}
    }
    if caves.aquifer[0].x==0u && caves.base[1].w!=0u && material_carved(x,y,z,r) {
        if y<bitcast<i32>(caves.base[1].z) {return MaterialVoxel(caves.base[1].y,false);}
        if (u32(caves.biomes[c.packed&65535u].selection.w)&4u)!=0u && y<waterline {
            return MaterialVoxel(caves.base[0].w,false);
        }
        return MaterialVoxel(0u,false);
    }
    // Explicit registered lake recipes have already produced their barrier cap.
    if lake && y>=c.height-3 {
        return MaterialVoxel(select(c.materials>>16u,c.materials&65535u,y==c.height-1),true);
    }
    return MaterialVoxel(caves.base[0].x,true);
}
fn material_column(index:u32,emit:bool,r0:Request) {
    var r=r0;r.density_side=0u; // This pass owns no density/cache scratch.
    let width=material_width(r);let count=width*width;
    if index>=count {return;}
    let x=index%width+16u;let z=index/width+16u;
    let c=column_at(x,z,r.tile_side);
    let point=vec2<f32>(f32(r.origin_x+i32(x)),f32(r.origin_z+i32(z)));
    let h=cell_hash(vec2<i32>(r.origin_x+i32(x),r.origin_z+i32(z)),r.seed_low+1381u,r.seed_high);
    let depth=f32(i32(program_noise(vec3<f32>(point.x,0.0,point.y),bytecode[9],r)*2.75+3.0+f32(h&65535u)/65535.0*0.25));
    let secondary=program_noise(vec3<f32>(point.x,0.0,point.y),bytecode[10],r);
    let band=round(program_noise(vec3<f32>(point.x,0.0,point.y),bytecode[11],r)*4.0);
    let slope=max(abs(f32(column_at(x+1u,z,r.tile_side).height-column_at(x-1u,z,r.tile_side).height)),
                  abs(f32(column_at(x,z+1u,r.tile_side).height-column_at(x,z-1u,r.tile_side).height)));
    let preliminary=f32(c.height-1)+depth-8.0;
    let base=material_base_offset(r);let groups=material_groups(r);
    var output=0u;
    if emit {output=material_runs_offset(r)+mask[base+5u+index*2u]+mask[base+4u+count*2u+groups+index/256u];}
    var above=0;var below=r.max_y;var water=-2147483648.0;
    var previous=0xffffffffu;var runs=0u;
    for(var y=r.max_y-1;y>=r.min_y;y--) {
        let voxel=material_voxel(x,y,z,c,r);var material=voxel.id;
        if !voxel.solid {
            if material==0u {above=0;water=-2147483648.0;}
            else if water==-2147483648.0 {water=f32(y+1);}
        } else {
            if below>=y {
                below=r.min_y;
                for(var look=y-1;look>=r.min_y;look--) {
                    if !material_voxel(x,look,z,c,r).solid {below=look+1;break;}
                }
            }
            above+=1;
            material_detail=vec4<f32>(f32(y-below+1),secondary,water,preliminary);
            // Shore alignment and the representative surface program use this
            // exact column. Borrowing a 4x4x4 quart's biome here moves the coast
            // in square patches and can even put grass back beside water.
            // Keep the existing quart field for underground material rules.
            var biome=c.packed&65535u;
            if y<c.height-12 {biome=biome_sample(x,y,z,r);}
            let selected=run_program(3u+biome,vec3<f32>(point.x,f32(y),point.y),r,vec4<f32>(f32(above),depth,slope,band))[0];
            if selected>0.0 && (c.packed&(1u<<29u))==0u {material=u32(selected-1.0);}
        }
        if previous!=material {
            if previous!=0xffffffffu {
                if emit {mask[output+runs]=(u32(y+1-r.min_y)<<16u)|previous;}
                runs+=1u;
            }
            previous=material;
        }
    }
    if emit {mask[output+runs]=previous;}
    runs+=1u;
    if !emit {mask[base+4u+index*2u]=runs;}
}
@compute @workgroup_size(64)
fn material_counts(@builtin(global_invocation_id) id:vec3<u32>) {
    let r=requests[0];
    if id.x==0u {let base=material_base_offset(r);mask[base]=0x52554e53u;mask[base+1u]=material_width(r);mask[base+2u]=material_width(r)*material_width(r);}
    material_column(id.x,false,r);
}
var<workgroup> material_scan:array<u32,256>;
@compute @workgroup_size(256)
fn material_prefix_blocks(@builtin(global_invocation_id) id:vec3<u32>,@builtin(local_invocation_index) lane:u32,@builtin(workgroup_id) group:vec3<u32>) {
    let r=requests[0];let base=material_base_offset(r);let count=material_width(r)*material_width(r);
    var value=0u;if id.x<count {value=mask[base+4u+id.x*2u];}
    material_scan[lane]=value;workgroupBarrier();
    for(var step=1u;step<256u;step*=2u) {
        var add=0u;if lane>=step {add=material_scan[lane-step];}
        workgroupBarrier();material_scan[lane]+=add;workgroupBarrier();
    }
    if id.x<count {mask[base+5u+id.x*2u]=material_scan[lane]-value;}
    if lane==255u {mask[base+4u+count*2u+group.x]=material_scan[lane];}
}
@compute @workgroup_size(1)
fn material_prefix_total() {
    let r=requests[0];let base=material_base_offset(r);let count=material_width(r)*material_width(r);let groups=material_groups(r);
    var total=0u;for(var group=0u;group<groups;group++) {
        mask[base+4u+count*2u+groups+group]=total;total+=mask[base+4u+count*2u+group];
    }
    mask[base+3u]=total;
}
@compute @workgroup_size(64)
fn material_emit(@builtin(global_invocation_id) id:vec3<u32>) {material_column(id.x,true,requests[0]);}
