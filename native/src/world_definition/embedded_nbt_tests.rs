//! Exercise embedded payloads through a physical compressed world definition.
use super::{
    WorldDefinition,
    tests::{document, gzip},
    value,
};
use fastnbt::Value as Nbt;
use serde_json::{Value, json};
use std::{
    fs,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};

fn saved(tag: Value) -> (PathBuf, Vec<u8>) {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let directory = std::env::temp_dir().join(format!(
        "retina-embedded-nbt-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    fs::create_dir(&directory).unwrap();
    let mut doc = document();
    let dimension = doc.dimensions.get_mut("minecraft:overworld").unwrap();
    let mut profile = value::decode(dimension.profile.take().unwrap()).unwrap();
    profile["structures"] = json!({"templates":[{
        "id":"test:typed_nbt","size":[1,1,1],"ground":0,"palettes":[[[1,1,1,1]]],
        "blocks":[0,0,0,0],"tags":{"0":tag},"joints":[],"entities":[tag]
    }],"definitions":[],"pools":{},"sets":[]});
    dimension.profile = Some(value::encode(&profile).unwrap());
    let bytes = gzip(&doc);
    fs::write(directory.join("retina.dat"), &bytes).unwrap();
    (directory, bytes)
}

#[test]
fn stored_embedded_nbt_preserves_numeric_widths_and_payload_bits() {
    let tag = json!([10,{
        "byte_min":[1,i8::MIN],"byte_max":[1,i8::MAX],
        "short_min":[2,i16::MIN],"short_max":[2,i16::MAX],
        "int_min":[3,i32::MIN],"int_max":[3,i32::MAX],
        "long_min":[4,i64::MIN],"long_max":[4,i64::MAX],
        "float_max":[5,f32::MAX as f64],"float_min":[5,f32::MIN as f64],
        "float_tiny":[5,f32::from_bits(1) as f64],"float_zero":[5,-0.0],
        "double_max":[6,f64::MAX],"double_min":[6,f64::MIN],
        "double_tiny":[6,f64::from_bits(1)],"double_zero":[6,-0.0],
        "bytes":[7,[i8::MIN,0,i8::MAX]],"ints":[11,[i32::MIN,0,i32::MAX]],
        "longs":[12,[i64::MIN,9007199254740993_i64,i64::MAX]],
        "text":[8,"A\u{0}你好🦊"],"nested":[9,[10,[[10,{"id":[8,"test:entity"]}]]]]
    }]);
    let (directory, before) = saved(tag.clone());
    let world = WorldDefinition::read(directory.join("retina.dat")).unwrap();
    let template = &world
        .dimension("minecraft:overworld")
        .unwrap()
        .profile()
        .unwrap()
        .structures
        .templates[0];
    for actual in [&template.tags[&0], &template.entities[0]] {
        assert_eq!(actual, &tag);
        let decoded: Nbt = fastnbt::from_bytes(&crate::nbt::root(actual)).unwrap();
        let Nbt::Compound(fields) = decoded else {
            panic!("embedded compound lost")
        };
        for (name, expected) in [
            ("byte_min", Nbt::Byte(i8::MIN)),
            ("byte_max", Nbt::Byte(i8::MAX)),
            ("short_min", Nbt::Short(i16::MIN)),
            ("short_max", Nbt::Short(i16::MAX)),
            ("int_min", Nbt::Int(i32::MIN)),
            ("int_max", Nbt::Int(i32::MAX)),
            ("long_min", Nbt::Long(i64::MIN)),
            ("long_max", Nbt::Long(i64::MAX)),
            ("float_max", Nbt::Float(f32::MAX)),
            ("float_min", Nbt::Float(f32::MIN)),
            ("float_tiny", Nbt::Float(f32::from_bits(1))),
            ("double_max", Nbt::Double(f64::MAX)),
            ("double_min", Nbt::Double(f64::MIN)),
            ("double_tiny", Nbt::Double(f64::from_bits(1))),
            (
                "bytes",
                Nbt::ByteArray(fastnbt::ByteArray::new(vec![i8::MIN, 0, i8::MAX])),
            ),
            (
                "ints",
                Nbt::IntArray(fastnbt::IntArray::new(vec![i32::MIN, 0, i32::MAX])),
            ),
            (
                "longs",
                Nbt::LongArray(fastnbt::LongArray::new(vec![
                    i64::MIN,
                    9007199254740993_i64,
                    i64::MAX,
                ])),
            ),
            ("text", Nbt::String("A\u{0}你好🦊".into())),
        ] {
            assert_eq!(fields[name], expected, "{name}");
        }
        let Nbt::Float(float_zero) = fields["float_zero"] else {
            panic!()
        };
        let Nbt::Double(double_zero) = fields["double_zero"] else {
            panic!()
        };
        assert_eq!(float_zero.to_bits(), (-0.0_f32).to_bits());
        assert_eq!(double_zero.to_bits(), (-0.0_f64).to_bits());
    }
    assert_eq!(fs::read(directory.join("retina.dat")).unwrap(), before);
    fs::remove_dir_all(directory).unwrap();
}

#[test]
fn stored_embedded_nbt_rejects_numeric_overflow_without_rewriting_file() {
    let mut accepted = Vec::new();
    for (name, payload) in [
        ("byte_high", json!([1, 128])),
        ("byte_low", json!([1, -129])),
        ("short_high", json!([2, 32768])),
        ("short_low", json!([2, -32769])),
        ("int_high", json!([3, 2147483648_i64])),
        ("int_low", json!([3, -2147483649_i64])),
        ("byte_array_high", json!([7, [128]])),
        ("byte_array_low", json!([7, [-129]])),
        ("int_array_high", json!([11, [2147483648_i64]])),
        ("int_array_low", json!([11, [-2147483649_i64]])),
        ("float_high", json!([5, f64::MAX])),
        ("float_low", json!([5, f64::MIN])),
    ] {
        let (directory, before) = saved(json!([10,{"value":payload}]));
        match WorldDefinition::read(directory.join("retina.dat")) {
            Ok(world) => {
                let template = &world
                    .dimension("minecraft:overworld")
                    .unwrap()
                    .profile()
                    .unwrap()
                    .structures
                    .templates[0];
                let decoded: Nbt =
                    fastnbt::from_bytes(&crate::nbt::root(&template.tags[&0])).unwrap();
                accepted.push(format!("{name}: {decoded:?}"));
            }
            Err(error) => assert!(
                error.contains("invalid structure template test:typed_nbt"),
                "{name}: {error}"
            ),
        }
        assert_eq!(fs::read(directory.join("retina.dat")).unwrap(), before);
        fs::remove_dir_all(directory).unwrap();
    }
    assert!(
        accepted.is_empty(),
        "malformed saved payloads accepted and changed: {accepted:?}"
    );
}

#[test]
fn stored_embedded_nbt_checks_modified_utf8_name_limits() {
    for (name, valid) in [
        ("🦊".repeat(10922) + "abc", true), // 65535 modified UTF-8 bytes.
        ("🦊".repeat(10923), false),
        ("\0".repeat(32768), false),
    ] {
        let raw_length = name.len();
        let tag = json!([10,{name.clone():[8,"value"]}]);
        let (directory, before) = saved(tag);
        let loaded = WorldDefinition::read(directory.join("retina.dat"));
        assert_eq!(loaded.is_ok(), valid, "raw name length {raw_length}");
        if let Ok(world) = loaded {
            let template = &world
                .dimension("minecraft:overworld")
                .unwrap()
                .profile()
                .unwrap()
                .structures
                .templates[0];
            let decoded: Nbt = fastnbt::from_bytes(&crate::nbt::root(&template.tags[&0])).unwrap();
            let Nbt::Compound(fields) = decoded else {
                panic!()
            };
            assert_eq!(fields.get(&name), Some(&Nbt::String("value".into())));
        }
        assert_eq!(fs::read(directory.join("retina.dat")).unwrap(), before);
        fs::remove_dir_all(directory).unwrap();
    }
}
