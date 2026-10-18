// Normal finite f32 arithmetic with explicit nearest/even rounding.
// Ore centers/radii and their differences/products never enter subnormal range.
// Integer operations prevent shader contraction from changing CPU rounding.
fn jam(value: u32, shift: u32) -> u32 {
    if shift == 0u { return value; }
    if shift >= 32u { return u32(value != 0u); }
    return (value >> shift) | u32((value << (32u - shift)) != 0u);
}
fn round_bits(sign: u32, exponent: i32, extended: u32) -> u32 {
    var significand = extended >> 3u;
    let tail = extended & 7u;
    if tail > 4u || (tail == 4u && (significand & 1u) != 0u) { significand++; }
    var e = exponent;
    if significand >= 0x1000000u { significand >>= 1u; e++; }
    return sign | (u32(e) << 23u) | (significand & 0x7fffffu);
}
fn add_bits(first: u32, second: u32) -> u32 {
    var a = first;
    var b = second;
    if (a & 0x7fffffffu) < (b & 0x7fffffffu) { a = second; b = first; }
    if (b & 0x7fffffffu) == 0u {
        if (a & 0x7fffffffu) == 0u { return a & b; }
        return a;
    }
    let sign = a & 0x80000000u;
    var exponent = i32((a >> 23u) & 255u);
    let difference = u32(exponent) - ((b >> 23u) & 255u);
    let ma = ((a & 0x7fffffu) | 0x800000u) << 3u;
    let mb = jam(((b & 0x7fffffu) | 0x800000u) << 3u, difference);
    var result: u32;
    if ((a ^ b) & 0x80000000u) == 0u {
        result = ma + mb;
        if result >= 0x8000000u { result = jam(result, 1u); exponent++; }
    } else {
        result = ma - mb;
        if result == 0u { return 0u; }
        let shift = countLeadingZeros(result) - 5u;
        result <<= shift;
        exponent -= i32(shift);
    }
    return round_bits(sign, exponent, result);
}
fn mul_bits(a: u32, b: u32) -> u32 {
    let sign = (a ^ b) & 0x80000000u;
    if (a & 0x7fffffffu) == 0u || (b & 0x7fffffffu) == 0u { return sign; }
    let ma = (a & 0x7fffffu) | 0x800000u;
    let mb = (b & 0x7fffffu) | 0x800000u;
    let product = mul64(vec2<u32>(ma, 0u), vec2<u32>(mb, 0u));
    let high = product.y >= 0x8000u;
    let shift = select(20u, 21u, high);
    let extended = (product.x >> shift) | (product.y << (32u - shift))
        | u32((product.x << (32u - shift)) != 0u);
    let exponent = i32((a >> 23u) & 255u) + i32((b >> 23u) & 255u) - 127 + i32(high);
    return round_bits(sign, exponent, extended);
}
