//! Load the complete Java-exported world profile through the production parser.
use retina_worldgen::{profile::WorldProfile, structures::cities};
use serde_json::{Value, json};
use std::{collections::BTreeMap, error::Error, fs};

fn main() -> Result<(), Box<dyn Error>> {
    let mut args = std::env::args().skip(1);
    let profile_path = args
        .next()
        .ok_or("expected world-profile.json reference.json")?;
    let reference_path = args.next().ok_or("expected reference.json")?;
    let profile = WorldProfile::parse(&fs::read(&profile_path)?)?;
    let reference: Value = serde_json::from_slice(&fs::read(&reference_path)?)?;
    let structures = &profile.structures;
    let kit = structures
        .end_cities
        .as_ref()
        .ok_or("missing resident city data")?;
    kit.validate(structures)?;
    let expected: BTreeMap<String, [i32; 3]> = serde_json::from_value(reference["sizes"].clone())?;
    assert_eq!(kit.catalog(structures).sizes, expected);
    let mut markers = 0;
    let mut blocks = 0;
    let mut kinds = BTreeMap::<String, usize>::new();
    for name in cities::NAMES {
        let entry = &kit.templates[name];
        let template = &structures.templates[entry.template];
        blocks += template.blocks.len() / 4;
        for (&index, tag) in &entry.markers {
            let kind = tag[1]["metadata"][1]
                .as_str()
                .ok_or("missing source marker metadata")?;
            *kinds.entry(kind.to_owned()).or_default() += 1;
            let state = template.blocks[index * 4 + 3] as usize;
            let material = template.palettes[0][state][0] as usize;
            let material = &profile.materials[material];
            assert_eq!(
                material.as_str().or_else(|| material["id"].as_str()),
                Some("minecraft:structure_block")
            );
            markers += 1;
        }
    }
    assert_eq!(json!(kinds), reference["marker_kinds"]);
    assert_eq!(json!(blocks), reference["blocks"]);
    assert_eq!(json!(markers), reference["markers"]);
    assert!(
        structures
            .definitions
            .iter()
            .any(|d| d.kind == "end_city" && !d.unimplemented)
    );
    println!(
        "{}",
        json!({"schema":"retina.end.city-catalog-native.v1","templates":kit.templates.len(),
        "blocks":blocks,"markers":markers,"marker_kinds":kinds,"derived_horizontal_reach":kit.catalog(structures).horizontal_reach(),
        "world_profile_parsed":true,"full_regions_generated":0,"production_activated":false})
    );
    Ok(())
}
