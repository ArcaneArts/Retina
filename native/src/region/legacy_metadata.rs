//! Minecraft 1.20.1 chunk metadata names and tag shapes at the storage boundary.
use crate::nbt;
use serde_json::{Value, json};

pub(super) fn fields(out: &mut Vec<u8>, data: &Value) {
    for (name, tag) in data[1].as_object().unwrap() {
        let id = match name.as_str() {
            "block_entities" => "minecraft:end_gateway",
            "entities" => "minecraft:end_crystal",
            _ => {
                nbt::named(out, name, tag);
                continue;
            }
        };
        let records = nbt::list_values(tag);
        if records.iter().any(|record| identity(record) == Some(id)) {
            let records = records.iter().cloned().map(convert).collect();
            nbt::named(out, name, &nbt::list(10, records));
        } else {
            nbt::named(out, name, tag);
        }
    }
}

fn identity(tag: &Value) -> Option<&str> {
    nbt::field(tag, "id")?.get(1)?.as_str()
}

fn convert(mut tag: Value) -> Value {
    match identity(&tag) {
        Some("minecraft:end_gateway") => position(&mut tag, "exit_portal", "ExitPortal"),
        Some("minecraft:end_crystal") => {
            position(&mut tag, "beam_target", "BeamTarget");
            let fields = tag[1].as_object_mut().unwrap();
            if let Some(distance) = fields.remove("fall_distance") {
                fields.insert("FallDistance".into(), json!([5, distance[1]]));
            }
        }
        _ => {}
    }
    tag
}

fn position(tag: &mut Value, from: &str, to: &str) {
    let Some(values) = nbt::field(tag, from).and_then(|pos| pos[1].as_array()) else {
        return;
    };
    let [x, y, z] = values.as_slice() else {
        return;
    };
    let legacy = json!([10, {"X": [3, x], "Y": [3, y], "Z": [3, z]}]);
    let fields = tag[1].as_object_mut().unwrap();
    fields.remove(from);
    fields.insert(to.into(), legacy);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn end_metadata_uses_legacy_positions_and_fall_distance() {
        let gateway = convert(json!([10, {
            "id": [8, "minecraft:end_gateway"], "exit_portal": [11, [-16, 78, 32]],
            "ExactTeleport": [1, 1]
        }]));
        assert_eq!(nbt::field(&gateway, "ExitPortal"), Some(&json!([10, {
            "X": [3, -16], "Y": [3, 78], "Z": [3, 32]
        }])));
        assert!(nbt::field(&gateway, "exit_portal").is_none());
        assert_eq!(nbt::field(&gateway, "ExactTeleport"), Some(&json!([1, 1])));
        let crystal = convert(json!([10, {
            "id": [8, "minecraft:end_crystal"], "beam_target": [11, [3, 4, 5]],
            "fall_distance": [6, 0.0], "ShowBottom": [1, 1]
        }]));
        assert_eq!(nbt::field(&crystal, "BeamTarget").unwrap()[1]["Z"], json!([3, 5]));
        assert_eq!(nbt::field(&crystal, "FallDistance"), Some(&json!([5, 0.0])));
        assert_eq!(nbt::field(&crystal, "ShowBottom"), Some(&json!([1, 1])));
    }
}
