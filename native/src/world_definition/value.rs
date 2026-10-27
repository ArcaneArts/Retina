//! Structural encoding of the existing profile's dynamic values into real NBT.
//!
//! Compounds and strings retain their natural shape. Bytes -1/0/1 represent
//! null/false/true. Integers use Int/Long; floating point uses Double. Integer
//! arrays use ByteArray (unsigned entries 0..255), IntArray or LongArray. String,
//! boolean and double lists are homogeneous native NBT lists. Other lists use
//! List<Compound { v: value }> so mixed tuples, nested arrays and nullable
//! entries remain lossless despite NBT's homogeneous-list requirement.
use fastnbt::Value as Nbt;
use serde_json::{Map, Value};

pub(super) fn decode(value: Nbt) -> Result<Value, String> {
    Ok(match value {
        Nbt::Byte(-1) => Value::Null,
        Nbt::Byte(0) => Value::Bool(false),
        Nbt::Byte(1) => Value::Bool(true),
        Nbt::Byte(_) => return Err("profile byte must represent null or a boolean".into()),
        Nbt::Short(v) => v.into(),
        Nbt::Int(v) => v.into(),
        Nbt::Long(v) => v.into(),
        Nbt::Float(v) => finite(f64::from(v))?,
        Nbt::Double(v) => finite(v)?,
        Nbt::String(v) => Value::String(v),
        Nbt::ByteArray(v) => Value::Array(v.iter().map(|&v| Value::from(v as u8)).collect()),
        Nbt::IntArray(v) => Value::Array(v.iter().map(|&v| Value::from(v)).collect()),
        Nbt::LongArray(v) => Value::Array(v.iter().map(|&v| Value::from(v)).collect()),
        Nbt::Compound(v) => Value::Object(
            v.into_iter()
                .map(|(k, v)| Ok((k, decode(v)?)))
                .collect::<Result<_, String>>()?,
        ),
        Nbt::List(v) => Value::Array(
            v.into_iter()
                .map(|v| match v {
                    Nbt::Compound(mut fields) => {
                        if fields.len() != 1 {
                            return Err("profile list entry requires exactly one v field".into());
                        }
                        decode(
                            fields
                                .remove("v")
                                .ok_or("profile list entry is missing v")?,
                        )
                    }
                    primitive => decode(primitive),
                })
                .collect::<Result<_, _>>()?,
        ),
    })
}

fn finite(v: f64) -> Result<Value, String> {
    serde_json::Number::from_f64(v)
        .map(Value::Number)
        .ok_or_else(|| "non-finite number in world definition".into())
}

// Reference encoder for migration fixtures and codec tests. The production
// compiler lives in Java and writes the same structural NBT representation.
#[cfg(test)]
pub(super) fn encode(value: &Value) -> Result<Nbt, String> {
    use fastnbt::{ByteArray, IntArray, LongArray};
    Ok(match value {
        Value::Null => Nbt::Byte(-1),
        Value::Bool(v) => Nbt::Byte(i8::from(*v)),
        Value::String(v) => Nbt::String(v.clone()),
        Value::Number(v) => {
            if let Some(n) = v.as_i64() {
                i32::try_from(n).map(Nbt::Int).unwrap_or(Nbt::Long(n))
            } else if v.is_f64() {
                Nbt::Double(v.as_f64().unwrap())
            } else {
                return Err("NBT integers must fit a signed 64-bit value".into());
            }
        }
        Value::Object(v) => Nbt::Compound(
            v.iter()
                .map(|(k, v)| Ok((k.clone(), encode(v)?)))
                .collect::<Result<_, String>>()?,
        ),
        Value::Array(v) => {
            if v.is_empty() {
                Nbt::List(Vec::new())
            } else if v.iter().all(Value::is_string) {
                Nbt::List(
                    v.iter()
                        .map(|v| Nbt::String(v.as_str().unwrap().into()))
                        .collect(),
                )
            } else if v.iter().all(Value::is_boolean) {
                Nbt::List(
                    v.iter()
                        .map(|v| Nbt::Byte(i8::from(v.as_bool().unwrap())))
                        .collect(),
                )
            } else if v
                .iter()
                .all(|v| v.as_i64().is_some_and(|n| (0..=255).contains(&n)))
            {
                Nbt::ByteArray(ByteArray::new(
                    v.iter().map(|v| v.as_i64().unwrap() as i8).collect(),
                ))
            } else if v
                .iter()
                .all(|v| v.as_i64().is_some_and(|n| i32::try_from(n).is_ok()))
            {
                Nbt::IntArray(IntArray::new(
                    v.iter().map(|v| v.as_i64().unwrap() as i32).collect(),
                ))
            } else if v.iter().all(|v| v.as_i64().is_some()) {
                Nbt::LongArray(LongArray::new(
                    v.iter().map(|v| v.as_i64().unwrap()).collect(),
                ))
            } else if v.iter().all(|v| v.as_number().is_some_and(|v| v.is_f64())) {
                Nbt::List(v.iter().map(|v| Nbt::Double(v.as_f64().unwrap())).collect())
            } else {
                Nbt::List(
                    v.iter()
                        .map(|v| {
                            Ok(Nbt::Compound(
                                [("v".into(), encode(v)?)].into_iter().collect(),
                            ))
                        })
                        .collect::<Result<_, String>>()?,
                )
            }
        }
    })
}

pub(super) fn material(value: &super::wire::BlockState) -> Value {
    let mut state = Map::new();
    state.insert("id".into(), value.id.clone().into());
    if !value.properties.is_empty() {
        state.insert(
            "properties".into(),
            Value::Object(
                value
                    .properties
                    .iter()
                    .map(|(k, v)| (k.clone(), Value::String(v.clone())))
                    .collect(),
            ),
        );
    }
    Value::Object(state)
}
