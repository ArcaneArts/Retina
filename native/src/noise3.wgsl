fn corner(cell: vec3<i32>, offset: vec3<f32>, seed: u32) -> f32 {
    let h = mix_hash(bitcast<u32>(cell.x)*0x9e3779b9u ^ bitcast<u32>(cell.y)*0x85ebca6bu ^ bitcast<u32>(cell.z)*0xc2b2ae35u ^ seed) & 15u;
    let a = select(offset.y,offset.x,h < 8u);
    let b = select(select(offset.z,offset.x,h == 12u || h == 14u),offset.y,h < 4u);
    return select(a,-a,(h & 1u)!=0u) + select(b,-b,(h & 2u)!=0u);
}
fn noise3(point: vec3<f32>, seed: u32) -> f32 {
    let cell = vec3<i32>(floor(point)); let d = point - vec3<f32>(cell);
    let t = d*d*d*(d*(d*6.0-15.0)+10.0);
    let z0 = mix(mix(corner(cell,d,seed),corner(cell+vec3<i32>(1,0,0),d-vec3<f32>(1,0,0),seed),t.x),
                 mix(corner(cell+vec3<i32>(0,1,0),d-vec3<f32>(0,1,0),seed),corner(cell+vec3<i32>(1,1,0),d-vec3<f32>(1,1,0),seed),t.x),t.y);
    let z1 = mix(mix(corner(cell+vec3<i32>(0,0,1),d-vec3<f32>(0,0,1),seed),corner(cell+vec3<i32>(1,0,1),d-vec3<f32>(1,0,1),seed),t.x),
                 mix(corner(cell+vec3<i32>(0,1,1),d-vec3<f32>(0,1,1),seed),corner(cell+vec3<i32>(1,1,1),d-vec3<f32>(1,1,1),seed),t.x),t.y);
    return mix(z0,z1,t.z)*1.6;
}
