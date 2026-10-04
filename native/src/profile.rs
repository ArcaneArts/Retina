use crate::decoration::Recipe;
use bytemuck::{Pod, Zeroable};
use serde::Deserialize;
use serde_json::Value;

#[derive(Clone, Deserialize)]
pub struct BiomeProfile {
    #[serde(default)]
    pub snow_surface: bool,
    #[serde(default)]
    pub temperature: f32,
    #[serde(default)]
    pub lakes: [f32; 2],
    #[serde(default)]
    pub lake_barrier: u16,
    #[serde(default)]
    pub lake_water_barrier: u16,
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
pub struct ClimateTarget {
    pub biome: u32,
    pub min: [f32; 4],
    pub max: [f32; 4],
    pub weirdness: [f32; 2],
    #[serde(default)]
    pub depth: [f32; 2],
    pub offset: f32,
}

#[derive(Clone, Deserialize)]
pub struct WorldProfile {
    #[serde(default)]
    pub structures: crate::structures::Profile,
    #[serde(default)]
    pub registry_program: Option<crate::program::RegistryProgram>,
    #[serde(default)]
    pub program_execution: crate::specialize::Execution,
    #[serde(default)]
    pub terrain_features: crate::features::TerrainFeatures,
    #[serde(default)]
    pub climate_targets: Vec<ClimateTarget>,
    #[serde(default)]
    pub climate_lookup: crate::climate::Lookup,
    #[serde(default)]
    pub weirdness_noise: Option<NoiseProfile>,
    pub biome_scale: f32,
    pub blend: f32,
    pub sea_level: i32,
    pub stone: u16,
    pub water: u16,
    pub bedrock: u16,
    pub deepslate: u16,
    pub snow: u16,
    pub ice: u16,
    pub materials: Vec<Value>,
    pub biomes: Vec<BiomeProfile>,
    pub noises: Vec<NoiseProfile>,
    #[serde(default)]
    pub decorations: Vec<Recipe>,
    #[serde(default)]
    pub ordered_decorations: bool,
    #[serde(default)]
    pub decoration_biome_3d: bool,
    #[serde(default)]
    pub decoration_noise: Option<crate::decoration::counts::Noise>,
    #[serde(flatten)]
    pub geology: crate::geology::GeologyProfile,
    pub material_flags: Vec<u8>,
    pub heightmap_masks: Vec<u8>,
    /// Signed registered DoublePlantBlock identity: lower positive, upper negative.
    #[serde(default)]
    pub plant_halves: Vec<i32>,
    #[serde(skip)]
    pub base_plant_halves: bool,
    #[serde(skip)]
    pub encoded: Vec<u8>,
    #[serde(skip)]
    pub material_nbt: Vec<Vec<u8>>,
    #[serde(skip)]
    pub ore_membership: Vec<Vec<bool>>,
}

impl WorldProfile {
    pub fn parse(json: &[u8]) -> Result<Self, String> {
        let mut profile: Self =
            serde_json::from_slice(json).map_err(|e| format!("world profile: {e}"))?;
        if profile.biomes.is_empty()
            || profile.biomes.len() > 65535
            || profile.materials.len() < 2
            || profile.materials.len() > 65536
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
            || !profile.plant_halves.is_empty()
                && (profile.plant_halves.len() != profile.materials.len()
                    || profile.plant_halves.iter().any(|v| *v == i32::MIN))
        {
            return Err("material flags must cover the complete palette".into());
        }
        profile
            .structures
            .validate(profile.materials.len(), profile.biomes.len())?;
        for recipe in &profile.decorations {
            if !profile.ordered_decorations
                && matches!(
                    recipe.feature,
                    crate::decoration::Kind::BlockColumn { .. }
                        | crate::decoration::Kind::Bamboo { .. }
                        | crate::decoration::Kind::Aquatic { .. }
                        | crate::decoration::Kind::HugeMushroom { .. }
                        | crate::decoration::Kind::FallenTree { .. }
                )
            {
                return Err("registered block features require ordered decoration replay".into());
            }
            recipe.validate(profile.materials.len())?;
            if profile.decoration_noise.is_none()
                && recipe
                    .placement
                    .as_ref()
                    .is_some_and(|ops| ops.iter().any(|op| op.requires_registered_noise()))
            {
                return Err(
                    "registered spatial decoration counts require their noise permutation".into(),
                );
            }
        }
        if let Some(noise) = &profile.decoration_noise {
            noise.validate()?;
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
        for target in &profile.climate_targets {
            if target.biome as usize >= profile.biomes.len()
                || target
                    .min
                    .iter()
                    .zip(&target.max)
                    .any(|(min, max)| !min.is_finite() || !max.is_finite() || min > max)
                || target.weirdness.iter().any(|x| !x.is_finite())
                || target.weirdness[0] > target.weirdness[1]
                || target.depth.iter().any(|x| !x.is_finite())
                || target.depth[0] > target.depth[1]
                || !target.offset.is_finite()
            {
                return Err("invalid climate target".into());
            }
        }
        if !profile.climate_targets.is_empty() && profile.weirdness_noise.is_none() {
            return Err("climate targets require registered weirdness noise".into());
        }
        if let Some(noise) = &profile.weirdness_noise {
            if noise.modifiers.is_empty()
                || noise.modifiers.len() > 32
                || !noise.frequency.is_finite()
                || noise.frequency <= 0.0
                || !noise.amplitude.is_finite()
                || noise.amplitude <= 0.0
                || noise.modifiers.iter().any(|x| !x.is_finite() || *x < 0.0)
            {
                return Err("invalid weirdness noise".into());
            }
        }
        if let Some(program) = &profile.registry_program {
            program.validate(profile.biomes.len())?;
        }
        profile.geology.validate(&profile)?;
        profile.terrain_features.validate(&profile)?;
        profile.material_nbt = profile
            .materials
            .iter()
            .map(crate::region::material_nbt)
            .collect();
        profile.ore_membership = profile
            .biomes
            .iter()
            .map(|b| {
                let mut ids = vec![false; profile.geology.ores.len()];
                for &id in &b.ores {
                    ids[id as usize] = true;
                }
                ids
            })
            .collect();
        profile.structures.compile(&profile.materials);
        let is_half = |id: usize| profile.plant_halves.get(id).is_some_and(|v| *v != 0);
        profile.base_plant_halves = profile.biomes.iter().any(|b| {
            [b.top, b.filler, b.underwater]
                .iter()
                .any(|id| is_half(*id as usize))
        }) || profile.registry_program.as_ref().is_some_and(|r| {
            // A rule's block leaf is encoded as constant(material + 1).
            // Conservatively include other constants in material graphs as well.
            r.programs[3..3 + profile.biomes.len()]
                .iter()
                .any(|program| {
                    program
                        .nodes
                        .iter()
                        .any(|n| n.op == 0 && n.p[0] >= 1.0 && is_half(n.p[0] as usize - 1))
                })
        });
        profile.encoded = json.to_vec();
        Ok(profile)
    }

    pub fn climate_gpu_bytes(&self) -> Vec<u8> {
        crate::climate::bytes(self)
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
        for (index, biome) in self.biomes.iter().enumerate() {
            bytes.extend_from_slice(bytemuck::bytes_of(&GpuBiome {
                climate: biome.climate,
                terrain: [
                    biome.terrain[0],
                    biome.terrain[1],
                    biome.terrain[2],
                    if self
                        .climate_targets
                        .iter()
                        .any(|t| t.biome as usize == index)
                    {
                        1.0
                    } else {
                        0.0
                    },
                ],
                materials: [biome.top, biome.filler, biome.underwater, biome.flags],
                features: [
                    biome.lakes[0],
                    biome.lakes[1],
                    f32::from_bits(
                        biome.lake_barrier as u32 | ((biome.lake_water_barrier as u32) << 16),
                    ),
                    biome.temperature,
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
    pub materials: u32,
}

impl Column {
    pub fn biome(self) -> usize {
        (self.packed & 65535) as usize
    }
    fn surface_material(self, _p: &WorldProfile, top: bool) -> u16 {
        if top {
            self.materials as u16
        } else {
            (self.materials >> 16) as u16
        }
    }
    /// Terrain adaptation extends the column's underlying soil, never its top
    /// block or a structure floor. The GPU has already selected the surface rule.
    pub fn foundation_material(self, profile: &WorldProfile) -> u16 {
        if profile.biomes[self.biome()].flags & 8 != 0 {
            profile.biomes[self.biome()].filler as u16
        } else {
            let filler = self.surface_material(profile, false);
            if filler == 0 {
                profile.biomes[self.biome()].filler as u16
            } else {
                filler
            }
        }
    }
    pub fn material(self, y: i32, min_y: i32, profile: Option<&WorldProfile>) -> u16 {
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
            return self.surface_material(profile, true);
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
                profile.biomes[self.biome()].filler as u16
            } else {
                self.surface_material(profile, false)
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

/// Column constants resolved once, rather than rediscovering biome/lake state for every voxel.
pub(crate) struct PreparedColumn<'a> {
    height: i32,
    bedrock_end: i32,
    waterline: i32,
    filler_start: i32,
    top: u16,
    filler: u16,
    fluid: u16,
    ice: Option<u16>,
    bands: &'a [u16],
    band_offset: i32,
    band_min: i32,
    stone: u16,
    deepslate: u16,
    bedrock: u16,
}
impl<'a> PreparedColumn<'a> {
    pub fn new(c: Column, min_y: i32, p: &'a WorldProfile) -> Self {
        let lake = c.packed & (1 << 29) != 0;
        let icy = c.packed & (1 << 28) != 0;
        let depth = ((c.packed >> 24) & 7) as i32;
        let badlands =
            !lake && p.biomes[c.biome()].flags & 8 != 0 && !p.terrain_features.bands.is_empty();
        Self {
            height: c.height,
            bedrock_end: min_y + 1 + (c.packed >> 30) as i32,
            waterline: if lake { c.height + depth } else { p.sea_level },
            filler_start: c.height - 1 - if lake { 2 } else { depth },
            top: c.materials as u16,
            filler: if badlands {
                p.biomes[c.biome()].filler as u16
            } else {
                (c.materials >> 16) as u16
            },
            fluid: if lake && icy { p.geology.lava } else { p.water },
            ice: if !lake && icy { Some(p.ice) } else { None },
            bands: if badlands {
                &p.terrain_features.bands
            } else {
                &[]
            },
            band_offset: ((c.packed >> 16) & 255) as u8 as i8 as i32,
            band_min: p.sea_level - 16,
            stone: p.stone,
            deepslate: p.deepslate,
            bedrock: p.bedrock,
        }
    }
    pub fn end(&self) -> i32 {
        self.height.max(self.waterline).max(self.bedrock_end)
    }
    #[inline]
    pub fn material(&self, y: i32) -> u16 {
        if y < self.bedrock_end {
            return self.bedrock;
        }
        if y >= self.height {
            return if y >= self.waterline {
                0
            } else if y == self.waterline - 1 {
                self.ice.unwrap_or(self.fluid)
            } else {
                self.fluid
            };
        }
        if y == self.height - 1 {
            return self.top;
        }
        if !self.bands.is_empty() && y >= self.band_min {
            return self.bands[(y + self.band_offset).rem_euclid(self.bands.len() as i32) as usize];
        }
        if y >= self.filler_start {
            return self.filler;
        }
        if y < 0 { self.deepslate } else { self.stone }
    }
}
