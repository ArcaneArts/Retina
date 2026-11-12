//! Seeded concentric-ring placement and biome relocation for saved structure sets.
use super::{CacheKey, ChunkRequest, Random, WorldProfile};
use serde::Deserialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

type Positions = Arc<Vec<[i32; 2]>>;
type Cell = Arc<OnceLock<Result<Positions, String>>>;

#[derive(Clone, Copy, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum BiomeSource {
    Flat,
    Fixed,
    MultiNoise,
}

#[derive(Clone, Deserialize)]
pub struct Placement {
    distance: i32,
    spread: i32,
    count: i32,
    biome_source: BiomeSource,
    preferred: Vec<u16>,
    #[serde(skip)]
    cache: Arc<Mutex<HashMap<CacheKey, Cell>>>,
}

impl Placement {
    pub(crate) fn count(&self) -> i32 {
        self.count
    }

    pub(super) fn validate(&self, biomes: usize) -> bool {
        (0..=1023).contains(&self.distance)
            && (0..=1023).contains(&self.spread)
            && (0..=4095).contains(&self.count)
            && (self.biome_source == BiomeSource::MultiNoise || biomes == 1)
            && self
                .preferred
                .iter()
                .all(|&biome| (biome as usize) < biomes)
    }

    pub(super) fn positions(
        &self,
        profile: &WorldProfile,
        request: ChunkRequest,
    ) -> Result<Positions, String> {
        let key = CacheKey::from(ChunkRequest {
            chunk_x: 0,
            chunk_z: 0,
            ..request
        });
        let cell = {
            let mut cache = self
                .cache
                .lock()
                .map_err(|_| "structure ring cache poisoned")?;
            if cache.len() >= 4 && !cache.contains_key(&key) {
                cache.retain(|_, value| value.get().is_none());
            }
            cache.entry(key).or_default().clone()
        };
        cell.get_or_init(|| self.compute(profile, request).map(Arc::new))
            .clone()
    }

    fn compute(
        &self,
        profile: &WorldProfile,
        request: ChunkRequest,
    ) -> Result<Vec<[i32; 2]>, String> {
        if self.count == 0 {
            return Ok(Vec::new());
        }
        let fields = if self.biome_source == BiomeSource::MultiNoise && !self.preferred.is_empty() {
            Some(crate::cpu::fields::Fields::new(profile)?)
        } else {
            None
        };
        let mut sampler = fields
            .as_ref()
            .map(|fields| fields.sampler(profile, request));
        let mut preferred = vec![false; profile.biomes.len()];
        for &index in &self.preferred {
            preferred[index as usize] = true;
        }
        let seed = if self.biome_source == BiomeSource::Flat {
            0
        } else {
            request.seed
        };
        self.generate(seed, |center, mut rng| {
            const RADIUS: i32 = 112;
            if let Some(sampler) = &mut sampler {
                let mut selected = center;
                let mut matches = 0;
                let [x, z] = center.map(|value| value * 4 + 2);
                // Vanilla scans quart positions in z-major order and reservoir-samples the matches.
                for qz in z - RADIUS / 4..=z + RADIUS / 4 {
                    for qx in x - RADIUS / 4..=x + RADIUS / 4 {
                        let biome = sampler
                            .underground_biome([(qx * 4) as f32, 0.0, (qz * 4) as f32], 0)?;
                        if preferred[biome as usize] {
                            if matches == 0 || rng.int(matches + 1) == 0 {
                                selected = [qx.div_euclid(4), qz.div_euclid(4)];
                            }
                            matches += 1;
                        }
                    }
                }
                Ok(selected)
            } else if self.biome_source != BiomeSource::MultiNoise && preferred[0] {
                Ok(center.map(|chunk| {
                    (chunk * 16 + 8 - RADIUS + rng.int((2 * RADIUS + 1) as u32) as i32)
                        .div_euclid(16)
                }))
            } else {
                Ok(center)
            }
        })
    }

    fn generate(
        &self,
        seed: u64,
        mut relocate: impl FnMut([i32; 2], Random) -> Result<[i32; 2], String>,
    ) -> Result<Vec<[i32; 2]>, String> {
        use std::f64::consts::{PI, TAU};
        let mut random = Random::new(seed);
        let mut angle = random.double() * PI * 2.0;
        let (mut ring, mut index, mut spread) = (0, 0, self.spread);
        let mut positions = Vec::with_capacity(self.count as usize);
        for placement in 0..self.count {
            let radius = f64::from(self.distance * (4 + 6 * ring))
                + (random.double() - 0.5) * (f64::from(self.distance) * 2.5);
            let center =
                [angle.cos(), angle.sin()].map(|axis| (axis * radius + 0.5).floor() as i32);
            positions.push(relocate(center, Random::new(random.long()))?);
            angle += TAU / f64::from(spread);
            index += 1;
            if index == spread {
                ring += 1;
                index = 0;
                spread = (spread + 2 * spread / (ring + 1)).min(self.count - placement);
                angle += random.double() * PI * 2.0;
            }
        }
        Ok(positions)
    }
}
