// Exact layout-2 ore random stream using two u32 words.
fn add64(a: vec2<u32>, b: vec2<u32>) -> vec2<u32> {
    let low = a.x + b.x;
    return vec2<u32>(low, a.y + b.y + u32(low < a.x));
}
fn mul64(a: vec2<u32>, b: vec2<u32>) -> vec2<u32> {
    let a0 = a.x & 65535u;
    let a1 = a.x >> 16u;
    let b0 = b.x & 65535u;
    let b1 = b.x >> 16u;
    let p0 = a0 * b0;
    let p1 = a1 * b0;
    let p2 = a0 * b1;
    let carry = (p0 >> 16u) + (p1 & 65535u) + (p2 & 65535u);
    let low = (p0 & 65535u) | (carry << 16u);
    let high = a1 * b1 + (p1 >> 16u) + (p2 >> 16u) + (carry >> 16u);
    return vec2<u32>(low, high + a.x * b.y + a.y * b.x);
}
fn shift_right(a: vec2<u32>, n: u32) -> vec2<u32> {
    return vec2<u32>((a.x >> n) | (a.y << (32u - n)), a.y >> n);
}
fn random_at(seed: vec2<u32>, step: u32) -> u32 {
    var z = add64(seed, mul64(vec2<u32>(0x7f4a7c15u, 0x9e3779b9u), vec2<u32>(step, 0u)));
    z = mul64(z ^ shift_right(z, 30u), vec2<u32>(0x1ce4e5b9u, 0xbf58476du));
    z = mul64(z ^ shift_right(z, 27u), vec2<u32>(0x133111ebu, 0x94d049bbu));
    return (z ^ shift_right(z, 31u)).x;
}
fn unit(value: u32) -> f32 { return f32(value >> 8u) / 16777216.0; }
