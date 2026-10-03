struct ClimateTarget { low:vec4<f32>,high:vec4<f32>,extra:vec4<f32>,depth:vec2<f32>,order:u32,escape:u32 }
struct ClimateTable { count:vec4<u32>,noise:NoiseProfile,targets:array<ClimateTarget> }

// Stackless preorder traversal. Strict pruning retains equal-distance targets so
// their original registry order can resolve ties, independent of BVH sorting.
fn climate_search(actual:array<f32,6>,weighted:bool,underground:bool,ocean_penalty:bool,initial:u32,initial_fitness:f32)->u32 {
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
        if !branch && ocean_penalty && ((packed>>16u)&4u)!=select(0u,4u,climate.z < -0.25) {fitness+=8.0;}
        if fitness>best {at=entry.escape;continue;}
        if !branch && (fitness<best || (fitness==best && entry.order+1u<order)) {
            best=fitness;selected=biome;order=entry.order+1u;
        }
        at++;
    }
    return selected;
}
