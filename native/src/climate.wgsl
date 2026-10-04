struct ClimateTarget { low:vec4<f32>,high:vec4<f32>,extra:vec4<f32>,depth:vec2<f32>,order:u32,escape:u32 }
struct ClimateTable { count:vec4<u32>,noise:NoiseProfile,shores:vec4<u32>,targets:array<ClimateTarget> }

// Stackless preorder traversal. Strict pruning retains equal-distance targets so
// their original registry order can resolve ties, independent of BVH sorting.
fn climate_search(actual:array<f32,6>,weighted:bool,underground:bool,ocean_penalty:bool,initial:u32,initial_fitness:f32)->u32 {
    return climate_search_filtered(actual,weighted,underground,ocean_penalty,initial,initial_fitness,false);
}
fn climate_search_filtered(actual:array<f32,6>,weighted:bool,underground:bool,ocean_penalty:bool,initial:u32,initial_fitness:f32,exclude_shores:bool)->u32 {
    var at=select(0u,climate_table.count.y,underground);
    let end=select(climate_table.count.x,climate_table.count.z,underground);
    var best=initial_fitness;var selected=initial;var order=0u;
    let climate=vec4<f32>(actual[0],actual[1],actual[2],actual[3]);
    loop {
        if at>=end {break;}
        let entry=climate_table.targets[at];let branch=entry.order==0xffffffffu;
        let difference=climate-clamp(climate,entry.low,entry.high);
        let ridge=actual[4]-clamp(actual[4],entry.extra.x,entry.extra.y);
        var fitness=select(dot(difference,difference),dot(difference*difference,vec4<f32>(2.5,1.5,2.0,0.5)),weighted)+ridge*ridge;
        if underground {let depth=actual[5]-clamp(actual[5],entry.depth.x,entry.depth.y);fitness+=depth*depth;}
        fitness+=select(entry.extra.z*entry.extra.z,entry.extra.z,branch);
        let packed=u32(entry.extra.w);let biome=packed&65535u;
        if !branch && exclude_shores && ((packed>>16u)&64u)!=0u {at++;continue;}
        if !branch && ocean_penalty && ((packed>>16u)&4u)!=select(0u,4u,climate.z < -0.25) {fitness+=8.0;}
        if fitness>best {at=entry.escape;continue;}
        if !branch && (fitness<best || (fitness==best && entry.order+1u<order)) {
            best=fitness;selected=biome;order=entry.order+1u;
        }
        at++;
    }
    return selected;
}

// Nearest registered coastal target in temperature/humidity/erosion/weirdness.
// Its original continental interval is stored in depth; the full lookup then
// decides between its shore, river and other registered coastal alternatives.
fn coastal_climate(actual:array<f32,6>)->array<f32,6> {
    var at=climate_table.shores.x;var best=1e20;var order=0xffffffffu;
    var result=actual;let climate=vec4<f32>(actual[0],actual[1],0.0,actual[3]);
    loop {
        if at>=climate_table.shores.y {break;}
        let entry=climate_table.targets[at];let branch=entry.order==0xffffffffu;
        let difference=climate-clamp(climate,entry.low,entry.high);
        let ridge=actual[4]-clamp(actual[4],entry.extra.x,entry.extra.y);
        let fitness=dot(difference,difference)+ridge*ridge+select(entry.extra.z*entry.extra.z,entry.extra.z,branch);
        if fitness>best {at=entry.escape;continue;}
        if !branch && (fitness<best || (fitness==best && entry.order<order)) {
            best=fitness;order=entry.order;
            // Use the interval interior, rather than an endpoint shared with
            // the ocean/inland interval where registry-order ties can win.
            result[2]=(entry.depth.x+entry.depth.y)*0.5;
        }
        at++;
    }
    return result;
}
