//! Typed NBT exported by Minecraft. No numeric block/entity registry IDs.
use serde_json::{Value, json};
pub fn compound() -> Value {
    json!([10, {}])
}
pub fn int(v: i32) -> Value {
    json!([3, v])
}
pub fn long(v: i64) -> Value {
    json!([4, v])
}
pub fn string(v: &str) -> Value {
    json!([8, v])
}
pub fn list(kind: u8, values: Vec<Value>) -> Value {
    json!([9, [kind, values]])
}
pub fn put(tag: &mut Value, key: &str, value: Value) {
    tag[1].as_object_mut().unwrap().insert(key.into(), value);
}
pub fn field<'a>(tag: &'a Value, key: &str) -> Option<&'a Value> {
    tag.get(1)?.get(key)
}
pub fn number(tag: &Value) -> f64 {
    tag[1].as_f64().unwrap_or(0.0)
}
pub fn list_values(tag: &Value) -> &[Value] {
    tag[1][1].as_array().map(Vec::as_slice).unwrap_or(&[])
}
fn text(out: &mut Vec<u8>, s: &str) {
    if s.is_ascii() && !s.as_bytes().contains(&0) {
        out.extend_from_slice(&(s.len() as u16).to_be_bytes());
        out.extend_from_slice(s.as_bytes());
        return;
    }
    let mut bytes = Vec::new();
    for c in s.encode_utf16() {
        if c != 0 && c < 128 {
            bytes.push(c as u8);
        } else if c < 2048 {
            bytes.extend([0xc0 | (c >> 6) as u8, 0x80 | (c & 63) as u8]);
        } else {
            bytes.extend([
                0xe0 | (c >> 12) as u8,
                0x80 | ((c >> 6) & 63) as u8,
                0x80 | (c & 63) as u8,
            ]);
        }
    }
    out.extend((bytes.len() as u16).to_be_bytes());
    out.extend(bytes);
}
pub fn named(out: &mut Vec<u8>, name: &str, tag: &Value) {
    out.push(tag[0].as_u64().unwrap() as u8);
    text(out, name);
    payload(out, tag);
}
pub fn fields(out: &mut Vec<u8>, tag: &Value) {
    for (key, value) in tag[1].as_object().unwrap() {
        named(out, key, value);
    }
}
pub fn root(tag: &Value) -> Vec<u8> {
    let mut out = Vec::new();
    named(&mut out, "", tag);
    out
}
fn payload(out: &mut Vec<u8>, tag: &Value) {
    let v = &tag[1];
    match tag[0].as_u64().unwrap() {
        1 => out.push(v.as_i64().unwrap() as u8),
        2 => out.extend((v.as_i64().unwrap() as i16).to_be_bytes()),
        3 => out.extend((v.as_i64().unwrap() as i32).to_be_bytes()),
        4 => out.extend(v.as_i64().unwrap().to_be_bytes()),
        5 => out.extend((v.as_f64().unwrap() as f32).to_be_bytes()),
        6 => out.extend(v.as_f64().unwrap().to_be_bytes()),
        8 => text(out, v.as_str().unwrap()),
        9 => {
            let kind = v[0].as_u64().unwrap() as u8;
            let values = v[1].as_array().unwrap();
            out.push(kind);
            out.extend((values.len() as i32).to_be_bytes());
            for value in values {
                if kind == 10 && value[0] != 10 {
                    named(out, "", value);
                    out.push(0);
                } else {
                    payload(out, value);
                }
            }
        }
        10 => {
            fields(out, tag);
            out.push(0);
        }
        7 | 11 | 12 => {
            let a = v.as_array().unwrap();
            out.extend((a.len() as i32).to_be_bytes());
            for x in a {
                match tag[0].as_u64().unwrap() {
                    7 => out.push(x.as_i64().unwrap() as u8),
                    11 => out.extend((x.as_i64().unwrap() as i32).to_be_bytes()),
                    _ => out.extend(x.as_i64().unwrap().to_be_bytes()),
                }
            }
        }
        _ => unreachable!("validated NBT tag"),
    }
}
pub fn validate(tag: &Value, depth: u32) -> bool {
    if depth > 128 || tag.as_array().is_none_or(|a| a.len() != 2) {
        return false;
    }
    let v = &tag[1];
    match tag[0].as_u64() {
        Some(1..=4) => v.as_i64().is_some(),
        Some(5 | 6) => v.as_f64().is_some_and(f64::is_finite),
        Some(8) => v.as_str().is_some_and(|s| {
            s.encode_utf16()
                .map(|c| {
                    if c != 0 && c < 128 {
                        1
                    } else if c < 2048 {
                        2
                    } else {
                        3
                    }
                })
                .sum::<usize>()
                <= 65535
        }),
        Some(7 | 11 | 12) => v
            .as_array()
            .is_some_and(|a| a.iter().all(|n| n.as_i64().is_some())),
        Some(9) => v.as_array().is_some_and(|a| {
            a.len() == 2
                && a[0].as_u64().is_some_and(|k| k <= 12)
                && a[1].as_array().is_some_and(|items| {
                    items
                        .iter()
                        .all(|t| validate(t, depth + 1) && (a[0] == 10 || a[0] == t[0]))
                })
        }),
        Some(10) => v.as_object().is_some_and(|o| {
            o.iter()
                .all(|(k, t)| k.len() < 65536 && validate(t, depth + 1))
        }),
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn strings_keep_java_modified_utf8() {
        let mut out = Vec::new();
        text(&mut out, "minecraft:plains");
        assert_eq!(&out[..2], &[0, 16]);
        assert_eq!(&out[2..], b"minecraft:plains");
        out.clear();
        text(&mut out, "A\0é😀");
        assert_eq!(
            out,
            [
                0, 11, 65, 0xc0, 0x80, 0xc3, 0xa9, 0xed, 0xa0, 0xbd, 0xed, 0xb8, 0x80
            ]
        );
    }
}
