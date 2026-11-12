//! Ordered top-layer modification using climate and material data from the definition.
use super::{Field, WorldBlock, blocks::write, counts::Noise, placement::Overlay};
use crate::{ChunkRequest, profile::WorldProfile};
use serde::Deserialize;

#[derive(Clone, Deserialize)]
pub struct Climate {
    snow: u16,
    ice: u16,
    water: u16,
    states: Vec<[u16; 2]>,
    frozen_biomes: Vec<bool>,
    altitude: Noise,
    frozen: [Noise; 3],
}

impl Climate {
    pub(crate) fn validate(&self, profile: &WorldProfile) -> Result<(), String> {
        let len = profile.materials.len();
        if [self.snow, self.ice, self.water]
            .into_iter()
            .any(|id| id == 0 || id as usize >= len)
            || self.states.len() != len
            || self
                .states
                .iter()
                .any(|&[flags, snowy]| flags > 7 || snowy as usize >= len)
            || self.frozen_biomes.len() != profile.biomes.len()
            || profile
                .biomes
                .iter()
                .any(|biome| !biome.temperature.is_finite())
            || self.frozen_biomes.contains(&true) && profile.decoration_noise.is_none()
        {
            return Err("invalid surface freezing materials or biomes".into());
        }
        self.altitude.validate()?;
        self.frozen.iter().try_for_each(Noise::validate)
    }

    fn temperature(&self, profile: &WorldProfile, biome: usize, x: i32, z: i32) -> f32 {
        let base = profile.biomes[biome].temperature;
        if !self.frozen_biomes[biome] {
            return base;
        }
        let info = profile.decoration_noise.as_ref().unwrap();
        let point = [f64::from(x), f64::from(z)];
        let frost = self
            .frozen
            .iter()
            .enumerate()
            .map(|(octave, noise)| {
                let scale = f64::from(1 << octave);
                noise.sample_precise(point.map(|v| v * 0.05 / scale)) * (1.0 / 7.0 * scale)
            })
            .sum::<f64>();
        if frost * 7.0 + info.sample_precise(point.map(|v| v * 0.2)) < 0.3
            && info.sample_precise(point.map(|v| v * 0.09)) < 0.8
        {
            0.2
        } else {
            base
        }
    }

    fn cold(&self, base: f32, [x, y, z]: [i32; 3]) -> bool {
        let mut temperature = base;
        if y > 80 {
            let offset = (self
                .altitude
                .sample_precise([x, z].map(|v| f64::from(v as f32 / 8.0)))
                * 8.0) as f32;
            temperature -= (offset + y as f32 - 80.0) * 0.05 / 40.0;
        }
        temperature < 0.15
    }

    pub(super) fn water_fluid(&self, material: u16) -> bool {
        self.states[material as usize][0] & 4 != 0
    }

    pub(super) fn lake_ice(
        &self,
        profile: &WorldProfile,
        field: &Field,
        at: [i32; 3],
        material: u16,
    ) -> Option<u16> {
        if material != self.water {
            return None;
        }
        let biome = field.biome(profile, at)?;
        self.cold(self.temperature(profile, biome, at[0], at[2]), at)
            .then_some(self.ice)
    }
}

pub(super) fn place(
    at: [i32; 3],
    field: &Field,
    profile: &WorldProfile,
    request: ChunkRequest,
    overlay: &Overlay,
    blocks: &mut Vec<WorldBlock>,
) {
    let Some(climate) = &profile.terrain_features.surface_freeze else {
        return;
    };
    let max_y = request.min_y + request.height as i32;
    for x in at[0]..at[0] + 16 {
        for z in at[2]..at[2] + 16 {
            let Some(y) = overlay.height(field, profile, request, x, z, 4) else {
                continue;
            };
            if y <= request.min_y || y > max_y {
                continue;
            }
            let top = [x, y, z];
            let ground = [x, y - 1, z];
            let Some(mut material) = overlay.material(field, profile, request, ground) else {
                continue;
            };
            if material != climate.water && climate.states[material as usize][0] & 1 == 0 {
                continue;
            }
            let Some(biome) = field.biome(profile, top) else {
                continue;
            };
            let base = climate.temperature(profile, biome, x, z);
            if material == climate.water && climate.cold(base, ground) {
                material = climate.ice;
                write(blocks, ground, material);
            }
            let [flags, snowy] = climate.states[material as usize];
            if flags & 1 != 0
                && y < max_y
                && climate.cold(base, top)
                && overlay
                    .material(field, profile, request, top)
                    .is_some_and(|id| climate.states[id as usize][0] & 2 != 0)
            {
                write(blocks, top, climate.snow);
                if snowy != 0 && snowy != material {
                    write(blocks, ground, snowy);
                }
            }
        }
    }
}
