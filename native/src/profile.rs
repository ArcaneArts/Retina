use crate::decoration::Recipe;
use bytemuck::{Pod, Zeroable};
use serde::Deserialize;
use serde_json::Value;

#[derive(Clone, Deserialize)]
pub struct BiomeProfile {
    #[serde(default)]
    pub lakes: [f32; 2],
    #[serde(default)]
    pub lake_barrier: u8,
    #[serde(default)]
    pub cave_kind: u32,
    #[serde(default)]
    pub cave_depth: [f32; 2],
    #[serde(default)]
    pub cave_features: crate::features::CaveFeatures,
    pub id: String,
    pub climate: [f32; 4],
    pub terrain: [f32; 3],
    pub top: u32,
    pub filler: u32,
    pub underwater: u32,
    pub flags: u32,
    #[serde(default)]
    pub decorations: Vec<u32>,
    #[serde(default)]
    pub ores: Vec<u32>,
    #[serde(default)]
    pub carvers: Vec<crate::geology::Carver>,
}

#[derive(Clone, Deserialize)]
pub struct NoiseProfile {
    pub frequency: f32,
    pub amplitude: f32,
    pub modifiers: Vec<f32>,
}

#[derive(Clone, Deserialize)]
pub struct WorldProfile {
    #[serde(default)]
    pub terrain_features: crate::features::TerrainFeatures,
    pub biome_scale: f32,
    pub blend: f32,
    pub sea_level: i32,
    pub stone: u8,
    pub water: u8,
    pub bedrock: u8,
    pub deepslate: u8,
    pub snow: u8,
    pub ice: u8,
    pub materials: Vec<Value>,
    pub biomes: Vec<BiomeProfile>,
    pub noises: Vec<NoiseProfile>,
    #[serde(default)]
    pub decorations: Vec<Recipe>,
    #[serde(flatten)]
    pub geology: crate::geology::GeologyProfile,
    pub material_flags: Vec<u8>,
    pub heightmap_masks: Vec<u8>,
    #[serde(skip)]
    pub encoded: Vec<u8>,
}

impl WorldProfile {
    pub fn parse(json: &[u8]) -> Result<Self, String> {
        let mut profile: Self =
            serde_json::from_slice(json).map_err(|e| format!("world profile: {e}"))?;
        if profile.biomes.is_empty()
            || profile.biomes.len() > 255
            || profile.materials.len() < 2
            || profile.materials.len() > 256
            || profile.noises.len() != 4
            || !profile.biome_scale.is_finite()
            || !(128.0..=4096.0).contains(&profile.biome_scale)
            || !profile.blend.is_finite()
            || !(0.1..=1.0).contains(&profile.blend)
        {
            return Err("invalid world profile dimensions".into());
        }
        if profile.material_flags.len() != profile.materials.len()
            || profile.heightmap_masks.len() != profile.materials.len()
        {
            return Err("material flags must cover the complete palette".into());
        }
        for recipe in &profile.decorations {
            recipe.validate(profile.materials.len())?;
        }
        for biome in &profile.biomes {
            if biome
                .decorations
                .iter()
                .any(|id| *id as usize >= profile.decorations.len())
                || biome.id.is_empty()
                || biome.id.len() > u16::MAX as usize
                || biome
                    .climate
                    .iter()
                    .chain(&biome.terrain)
                    .any(|f| !f.is_finite())
                || [biome.top, biome.filler, biome.underwater]
                    .iter()
                    .any(|id| *id as usize >= profile.materials.len())
            {
                return Err("invalid biome profile".into());
            }
        }
        for id in [
            profile.stone,
            profile.water,
            profile.bedrock,
            profile.deepslate,
            profile.snow,
            profile.ice,
        ] {
            if id as usize >= profile.materials.len() {
                return Err("invalid profile material reference".into());
            }
        }
        for material in &profile.materials {
            let id = material
                .as_str()
                .or_else(|| material.get("id").and_then(Value::as_str))
                .ok_or("material needs a block id")?;
            if id.is_empty() || id.len() > u16::MAX as usize {
                return Err("invalid material block id".into());
            }
            if let Some(properties) = material.get("properties") {
                for (key, value) in properties
                    .as_object()
                    .ok_or("block properties must be an object")?
                {
                    if key.len() > u16::MAX as usize
                        || value.as_str().is_none_or(|s| s.len() > u16::MAX as usize)
                    {
                        return Err("invalid block property".into());
                    }
                }
            }
        }
        for noise in &profile.noises {
            if noise.modifiers.is_empty()
                || noise.modifiers.len() > 32
                || !noise.frequency.is_finite()
                || noise.frequency <= 0.0
                || !noise.amplitude.is_finite()
                || noise.amplitude <= 0.0
                || noise.modifiers.iter().any(|v| !v.is_finite() || *v < 0.0)
            {
                return Err("invalid registry noise profile".into());
            }
        }
        for noise in &profile.geology.cave_noises {
            if noise.modifiers.is_empty()
                || noise.modifiers.len() > 32
                || !noise.frequency.is_finite()
                || noise.frequency <= 0.0
                || !noise.amplitude.is_finite()
                || noise.amplitude <= 0.0
                || noise.modifiers.iter().any(|v| !v.is_finite() || *v < 0.0)
            {
                return Err("invalid cave noise parameters".into());
            }
        }
        profile.geology.validate(&profile)?;
        profile.terrain_features.validate(&profile)?;
        profile.encoded = json.to_vec();
        Ok(profile)
    }

    pub fn gpu_bytes(&self) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(bytemuck::bytes_of(&[
            self.biome_scale,
            self.blend,
            self.terrain_features.band_frequency,
            self.terrain_features.band_amplitude,
        ]));
        bytes.extend_from_slice(bytemuck::bytes_of(&[
            self.biomes.len() as u32,
            self.sea_level as u32,
            self.stone as u32,
            self.snow as u32,
        ]));
        for noise in &self.noises {
            let mut modifiers = [0.0; 32];
            modifiers[..noise.modifiers.len()].copy_from_slice(&noise.modifiers);
            bytes.extend_from_slice(bytemuck::bytes_of(&GpuNoise {
                frequency: noise.frequency,
                amplitude: noise.amplitude,
                count: noise.modifiers.len() as u32,
                padding: 0,
                modifiers,
            }));
        }
        for biome in &self.biomes {
            bytes.extend_from_slice(bytemuck::bytes_of(&GpuBiome {
                climate: biome.climate,
                terrain: [biome.terrain[0], biome.terrain[1], biome.terrain[2], 0.0],
                materials: [biome.top, biome.filler, biome.underwater, biome.flags],
                features: [
                    biome.lakes[0],
                    biome.lakes[1],
                    biome.lake_barrier as f32,
                    0.0,
                ],
            }));
        }
        bytes
    }
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct GpuNoise {
    frequency: f32,
    amplitude: f32,
    count: u32,
    padding: u32,
    modifiers: [f32; 32],
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct GpuBiome {
    climate: [f32; 4],
    terrain: [f32; 4],
    materials: [u32; 4],
    features: [f32; 4],
}

/// One compact GPU readback record per column. All interpolation and surface choice is finished.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Pod, Zeroable)]
pub struct Column {
    pub height: i32,
    pub packed: u32,
}

impl Column {
    pub fn biome(self) -> usize {
        (self.packed & 255) as usize
    }
    pub fn material(self, y: i32, min_y: i32, profile: Option<&WorldProfile>) -> u8 {
        let Some(profile) = profile else {
            return if y < self.height { 1 } else { 0 };
        };
        if y < min_y + 1 + (self.packed >> 30) as i32 {
            return profile.bedrock;
        }
        let lake = self.packed & (1 << 29) != 0;
        let depth = ((self.packed >> 24) & 7) as i32;
        let waterline = if lake {
            self.height + depth
        } else {
            profile.sea_level
        };
        if y >= self.height {
            return if y < waterline {
                if lake && self.packed & (1 << 28) != 0 {
                    profile.geology.lava
                } else if !lake && self.packed & (1 << 28) != 0 && y == waterline - 1 {
                    profile.ice
                } else {
                    profile.water
                }
            } else {
                0
            };
        }
        if y == self.height - 1 {
            return ((self.packed >> 8) & 255) as u8;
        }
        // Badlands reuse the filler byte for the GPU's signed band offset.
        if !lake
            && profile.biomes[self.biome()].flags & 8 != 0
            && !profile.terrain_features.bands.is_empty()
            && y >= profile.sea_level - 16
        {
            let offset = ((self.packed >> 16) & 255) as u8 as i8 as i32;
            let bands = &profile.terrain_features.bands;
            return bands[(y + offset).rem_euclid(bands.len() as i32) as usize];
        }
        if y >= self.height - 1 - if lake { 2 } else { depth } {
            return if !lake
                && profile.biomes[self.biome()].flags & 8 != 0
                && !profile.terrain_features.bands.is_empty()
            {
                profile.biomes[self.biome()].filler as u8
            } else {
                ((self.packed >> 16) & 255) as u8
            };
        }
        if y < 0 {
            profile.deepslate
        } else {
            profile.stone
        }
    }

    pub fn surface_height(self, profile: Option<&WorldProfile>) -> i32 {
        profile.map_or(self.height, |p| {
            if self.packed & (1 << 29) != 0 {
                self.height + ((self.packed >> 24) & 7) as i32
            } else {
                self.height.max(p.sea_level)
            }
        })
    }
}
