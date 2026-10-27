//! Host-facing output from an already loaded definition. This JSON is metadata
//! transport, never a second generation input or profile registration format.
use super::{WorldDefinition, value};
use serde_json::{Value, json};

impl WorldDefinition {
    pub fn adapter_metadata(&self) -> Value {
        let dimensions:serde_json::Map<_,_> = self.dimensions().iter().map(|(key, dimension)| {
            let mut data = json!({"parameters":dimension.parameters()});
            if let Some(reason) = dimension.unsupported_reason() {
                data["unsupported_reason"] = reason.into();
            } else {
                let profile = dimension.profile().expect("validated supported dimension");
                data["material_palette"] = json!(self.source().dimensions[key].material_palette.iter().copied().collect::<Vec<_>>());
                data["biome_palette"] = json!(self.source().dimensions[key].biome_palette.iter().copied().collect::<Vec<_>>());
                let definitions:Vec<_> = profile.structures.definitions.iter().map(|d| json!({
                    "id":d.id,"kind":d.kind,"biomes":d.biomes,"config":d.config,
                    "unimplemented":d.unimplemented,"pool":d.pool,"template":d.template,
                })).collect();
                let sets:Vec<_> = profile.structures.sets.iter().map(|s| json!({
                    "id":s.id,"placement_only":s.placement_only,"placement":s.placement,
                    "entries":s.entries.iter().map(|e|json!({"definition":e.definition,"weight":e.weight})).collect::<Vec<_>>()
                })).collect();
                let biomes:Vec<_> = profile.biomes.iter().map(|b|json!({"id":b.id,"top":b.top,
                    "filler":b.filler,"underwater":b.underwater,"flags":b.flags,"lake_barrier":b.lake_barrier})).collect();
                let mut summary = json!({"sea_level":profile.sea_level,"stone":profile.stone,
                    "water":profile.water,"bedrock":profile.bedrock,"deepslate":profile.deepslate,
                    "snow":profile.snow,"ice":profile.ice,
                    "terrain_features":{"bands":profile.terrain_features.bands},"biomes":biomes,
                    "structures":{"definitions":definitions,"sets":sets}});
                if profile.terrain_kind == crate::profile::TerrainKind::Procedural {
                    summary["terrain_kind"] = "procedural".into();
                }
                if self.source().dimensions[key].profile.as_ref().is_some_and(|raw|
                    matches!(raw,fastnbt::Value::Compound(fields) if fields.contains_key("lava"))) {
                    summary["lava"] = json!(profile.geology.lava);
                }
                if let Some(flat) = &profile.flat {
                    summary["flat"] = json!({"base_layers":flat.base_layers,
                        "deferred_layers":flat.deferred_layers,"air_materials":flat.air_materials});
                }
                if let Some(end) = &profile.end {
                    summary["end"] = json!({"material":end.material,"biomes":end.biomes});
                }
                if profile.registry_program.is_some() || profile.end.is_some() {
                    summary["registry_program"] = json!({"material_layers":profile.material_layers(),
                        "material_halo":profile.material_halo(),
                        "density_composition":profile.registry_program.as_ref().is_some_and(|p|p.density_composition)});
                }
                data["profile_metadata"] = summary;
                data["limitations"] = limitations(self.source().dimensions[key].profile.as_ref().expect("supported profile"));
            }
            (key.clone(),data)
        }).collect();
        json!({"format_version":self.source().format_version,
            "generation_version":self.source().generation_version,
            "minecraft_version":self.source().minecraft_version,"data_version":self.data_version(),
            "exporter":self.source().exporter,"seed":self.seed(),
            "generate_structures":self.source().generate_structures,"bonus_chest":self.source().bonus_chest,
            "shared_types":self.source().shared_types,"dimensions":dimensions})
    }

    /// Explicit diagnostics/migration output. No caller can upload this to the
    /// saved-definition binding; ordinary adapters use the compact metadata.
    pub fn diagnostic_profile(&self, key: &str) -> Result<Value, String> {
        let profile = self.dimension(key)?.profile()?;
        let raw = self.source().dimensions[key]
            .profile
            .clone()
            .ok_or("missing saved profile")?;
        let mut data = value::decode(raw)?;
        data["materials"] = Value::Array(profile.materials.clone());
        Ok(data)
    }
}

/// Keep exporter reports available even though they are not execution programs.
/// Missing fields in older files remain absent; absence does not mean no omissions.
fn limitations(profile:&fastnbt::Value) -> Value {
    let fastnbt::Value::Compound(fields)=profile else { unreachable!("validated profile compound") };
    let mut report=serde_json::Map::new();
    for (source,target) in [("decoration_omissions","decorations"),("geology_omissions","geology"),
                            ("simple_current_omissions","simple_block_survival")] {
        if let Some(saved)=fields.get(source) {
            report.insert(target.into(),value::decode(saved.clone()).expect("validated structural profile"));
        }
    }
    for (source,field,target) in [("structures","omissions","structures"),("registry_program","approximations","approximations")] {
        if let Some(fastnbt::Value::Compound(saved))=fields.get(source) {
            if let Some(saved)=saved.get(field) {
                report.insert(target.into(),value::decode(saved.clone()).expect("validated structural profile"));
            }
        }
    }
    Value::Object(report)
}
