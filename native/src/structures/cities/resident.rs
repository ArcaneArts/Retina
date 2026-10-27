//! References into the shared template store, with source-ordered marker NBT.
use super::{Catalog, NAMES};
use crate::{nbt, structures::Profile};
use serde::Deserialize;
use serde_json::Value;
use std::collections::BTreeMap;

#[derive(Clone, Deserialize)]
pub struct Resident {
    pub templates: BTreeMap<String, TemplateRef>,
}
#[derive(Clone, Deserialize)]
pub struct TemplateRef {
    pub template: usize,
    /// Ordinals refer to the normalized source block list. Keep full typed NBT,
    /// including markers not handled by vanilla's current city adapter.
    pub markers: BTreeMap<usize, Value>,
}
impl Resident {
    pub fn validate(&self, profile: &Profile) -> Result<(), String> {
        if self.templates.len() != NAMES.len() {
            return Err("End city catalog must contain all 19 source templates".into());
        }
        for name in NAMES {
            let Some(entry) = self.templates.get(name) else {
                return Err(format!("missing resident End city template: {name}"));
            };
            let Some(template) = profile.templates.get(entry.template) else {
                return Err(format!(
                    "invalid resident End city template reference: {name}"
                ));
            };
            if template.id != format!("minecraft:end_city/{name}")
                || !template
                    .transform
                    .as_ref()
                    .is_some_and(|transform| transform.mirror == 0 && transform.pivot == [0; 3])
                || entry.markers.iter().any(|(&index, tag)| {
                    index >= template.blocks.len() / 4
                        || tag[0].as_u64() != Some(10)
                        || !nbt::validate(tag, 0)
                })
            {
                return Err(format!(
                    "invalid resident End city template or markers: {name}"
                ));
            }
        }
        self.catalog(profile).validate()
    }
    /// Constructed only after reference validation. The planner therefore uses
    /// the same loaded dimensions as block execution and saved piece bounds.
    pub fn catalog(&self, profile: &Profile) -> Catalog {
        Catalog {
            sizes: self
                .templates
                .iter()
                .map(|(name, entry)| (name.clone(), profile.templates[entry.template].size))
                .collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn profile() -> Profile {
        let templates: Vec<_> = NAMES
            .iter()
            .map(|name| {
                json!({
                    "id":format!("minecraft:end_city/{name}"),"size":[1,1,1],"ground":1,
                    "palettes":[[[0,0,0,0]]],"blocks":[0,0,0,0],"tags":{},"joints":[],"entities":[],
                    "transform":{"mirror":0,"pivot":[0,0,0],"materials":[[0,0,0,0]]}
                })
            })
            .collect();
        let entries: BTreeMap<_,_> = NAMES.iter().enumerate().map(|(i,name)| {
            (name.to_string(),json!({"template":i,"markers":{"0":[10,{"mode":[8,"DATA"],"metadata":[8,"Chest"]}]}}))
        }).collect();
        serde_json::from_value(
            json!({"templates":templates,"definitions":[],"sets":[],"pools":{},
            "end_cities":{"templates":entries}}),
        )
        .unwrap()
    }
    #[test]
    fn city_resident_catalog_validates_references_and_full_marker_nbt() {
        let p = profile();
        p.validate(1, 1).unwrap();
        let kit = p.end_cities.as_ref().unwrap();
        assert_eq!(kit.catalog(&p).sizes.len(), 19);
        assert_eq!(
            nbt::field(&kit.templates["ship"].markers[&0], "metadata").unwrap(),
            &nbt::string("Chest")
        );
        let mut missing = p.clone();
        missing
            .end_cities
            .as_mut()
            .unwrap()
            .templates
            .remove("ship");
        assert!(
            missing
                .validate(1, 1)
                .unwrap_err()
                .contains("19 source templates")
        );
        let mut bad = p.clone();
        bad.end_cities
            .as_mut()
            .unwrap()
            .templates
            .get_mut("ship")
            .unwrap()
            .template = usize::MAX;
        assert!(
            bad.validate(1, 1)
                .unwrap_err()
                .contains("template reference")
        );
        let mut bad = p.clone();
        bad.end_cities
            .as_mut()
            .unwrap()
            .templates
            .get_mut("ship")
            .unwrap()
            .markers
            .insert(1, nbt::compound());
        assert!(bad.validate(1, 1).unwrap_err().contains("or markers"));
        let mut bad = p.clone();
        bad.end_cities
            .as_mut()
            .unwrap()
            .templates
            .get_mut("ship")
            .unwrap()
            .markers
            .insert(0, nbt::string("not a compound"));
        assert!(bad.validate(1, 1).unwrap_err().contains("or markers"));
        let mut bad = p.clone();
        bad.templates[12].transform.as_mut().unwrap().pivot = [1, 0, 0];
        assert!(bad.validate(1, 1).unwrap_err().contains("or markers"));
        // A position may be a marker in one palette and an ordinary NBT-bearing
        // block in another. Execution filters using the selected palette.
        let mut varied = p.clone();
        varied.templates[12].tags.insert(0, nbt::compound());
        varied.validate(1, 1).unwrap();
    }
}
