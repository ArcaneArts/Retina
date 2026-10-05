//! Sparse final grass support and DoublePlantBlock partner repair. Minecraft
//! postprocesses these blocks; direct FULL MCA/chunk replay must do it too.
use super::Placement;
use crate::{COLUMNS, profile::WorldProfile};

#[derive(Default)]
pub struct Updates(Vec<usize>);
impl Updates {
    pub fn record(&mut self, profile: &WorldProfile, index: usize, material: u16) {
        if profile
            .plant_halves
            .get(material as usize)
            .is_some_and(|v| *v != 0)
            || profile
                .plant_floor_masks
                .get(material as usize)
                .is_some_and(|v| *v != 0)
        {
            self.0.push(index);
        }
    }
    pub fn placements(&mut self, profile: &WorldProfile, placements: &[Placement]) {
        for p in placements {
            self.record(profile, p.index as usize, p.material);
            if p.role == super::PLANT && p.upper != 0 {
                self.record(profile, p.index as usize + COLUMNS, p.upper);
            }
        }
    }
    pub fn base(&mut self, profile: &WorldProfile, blocks: &[u16]) {
        // Normal terrain contains no checked plants. Unusual packs which place
        // them as base materials still receive complete final validation.
        if profile.base_plant_halves {
            for (index, &id) in blocks.iter().enumerate() {
                self.record(profile, index, id);
            }
        }
    }
    pub fn extend(&mut self, other: Self) {
        self.0.extend(other.0);
    }
    pub fn finish(self, profile: &WorldProfile, blocks: &mut [u16]) -> usize {
        let mut removed = 0;
        // Support first, then partners: a structure may replace only the soil
        // underneath an otherwise intact pair. Candidate order must not leave
        // its upper half behind when the lower is removed later in this pass.
        for &index in &self.0 {
            let Some(&id) = blocks.get(index) else {
                continue;
            };
            let mask = profile
                .plant_floor_masks
                .get(id as usize)
                .copied()
                .unwrap_or(0);
            if mask != 0
                && !index
                    .checked_sub(COLUMNS)
                    .and_then(|i| blocks.get(i))
                    .is_some_and(|id| profile.material_flags[*id as usize] & mask != 0)
            {
                blocks[index] = 0;
                removed += 1;
            }
        }
        for index in self.0 {
            let Some(&id) = blocks.get(index) else {
                continue;
            };
            let half = profile.plant_halves.get(id as usize).copied().unwrap_or(0);
            if half == 0 {
                continue;
            }
            let partner = if half > 0 {
                index.checked_add(COLUMNS)
            } else {
                index.checked_sub(COLUMNS)
            };
            if partner
                .and_then(|i| blocks.get(i))
                .and_then(|id| profile.plant_halves.get(*id as usize))
                != Some(&-half)
            {
                // DoublePlantBlock.updateShape returns air on a missing partner,
                // including tall seagrass and waterlogged small dripleaf.
                blocks[index] = 0;
                removed += 1;
            }
        }
        removed
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn final_soil_changes_remove_grass_and_both_halves_without_order_dependence() {
        let noise = serde_json::json!({"frequency":0.001,"amplitude":1,"modifiers":[1]});
        let profile = WorldProfile::parse(serde_json::json!({
            "biome_scale":128,"blend":0.55,"sea_level":63,"stone":1,"water":1,"bedrock":1,"deepslate":1,"snow":1,"ice":1,
            "materials":["air","soil","path","gravel","grass","lower","upper","feature","custom_soil"],
            "biomes":[{"id":"test:plain","climate":[0,0,0,0],"terrain":[0,0,0],"top":1,"filler":1,"underwater":1,"flags":0}],
            "noises":[noise,noise,noise,noise],"material_flags":[0,1,0,0,16,16,16,0,1],"heightmap_masks":[0,63,63,63,3,3,3,63,63],
            "plant_halves":[0,0,0,0,0,1,-1,0,0],"plant_floor_masks":[0,0,0,0,1,1,0,0,0]
        }).to_string().as_bytes()).unwrap();
        let mut blocks = vec![0; COLUMNS * 3];
        let mut updates = Updates::default();
        for (column, soil) in [(0, 2), (1, 3), (2, 1), (3, 2), (4, 3), (5, 8), (6, 2)] {
            blocks[column] = soil;
            blocks[COLUMNS + column] = if (3..=5).contains(&column) { 5 } else { 4 };
            if (3..=5).contains(&column) {
                blocks[2 * COLUMNS + column] = 6;
                updates.record(&profile, 2 * COLUMNS + column, 6); // Upper visited first.
            }
            updates.record(&profile, COLUMNS + column, blocks[COLUMNS + column]);
        }
        updates.record(&profile, COLUMNS, 4); // Duplicate update remains harmless.
        blocks[COLUMNS + 6] = 7; // An unrelated later feature replaces the grass.
        blocks[7] = 4;
        updates.record(&profile, 7, 4); // Build-bottom support is absent.
        assert_eq!(updates.finish(&profile, &mut blocks), 7);
        for c in [0, 1, 3, 4] {
            assert_eq!(blocks[COLUMNS + c], 0);
        }
        for c in [3, 4] {
            assert_eq!(blocks[2 * COLUMNS + c], 0);
        }
        assert_eq!(blocks[COLUMNS + 2], 4);
        assert_eq!((blocks[COLUMNS + 5], blocks[2 * COLUMNS + 5]), (5, 6));
        assert_eq!(blocks[COLUMNS + 6], 7);
        assert_eq!(blocks[7], 0);
    }
    #[test]
    fn overlap_bounds_and_different_state_properties_preserve_only_valid_pairs() {
        let noise = serde_json::json!({"frequency":0.001,"amplitude":1,"modifiers":[1]});
        let profile = WorldProfile::parse(serde_json::json!({
            "biome_scale":128,"blend":0.55,"sea_level":63,"stone":1,"water":1,"bedrock":1,"deepslate":1,"snow":1,"ice":1,
            "materials":["minecraft:air","test:single","test:lower","test:upper","test:lower_facing_east","test:other_upper"],
            "biomes":[{"id":"test:plain","climate":[0,0,0,0],"terrain":[0,0,0],"top":1,"filler":1,"underwater":1,"flags":0}],
            "noises":[noise,noise,noise,noise],"material_flags":[0,0,0,0,0,0],"heightmap_masks":[0,3,3,3,3,3],
            "plant_halves":[0,0,1,-1,1,-2]
        }).to_string().as_bytes()).unwrap();
        let mut blocks = vec![0; COLUMNS * 4];
        let mut updates = Updates::default();
        for (i, id) in [
            (0, 3),
            (1, 2),
            (257, 3),
            (2, 4),
            (258, 3),
            (3, 2),
            (259, 5),
            (4, 2),
            (260, 3),
            (768, 2),
        ] {
            blocks[i] = id;
            updates.record(&profile, i, id);
        }
        blocks[1] = 1; // aquatic single-block feature overwrites the lower half
        blocks[260] = 0; // structure clears the upper half
        assert_eq!(updates.finish(&profile, &mut blocks), 6);
        assert_eq!(blocks[1], 1);
        assert_eq!((blocks[2], blocks[258]), (4, 3)); // identity, not exact-state comparison
        for i in [0, 257, 3, 259, 4, 768] {
            assert_eq!(blocks[i], 0, "orphan at {i}");
        }
    }
}
