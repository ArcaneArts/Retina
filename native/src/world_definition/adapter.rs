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
                        "material_halo":profile.material_halo()});
                }
                data["profile_metadata"] = summary;
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
