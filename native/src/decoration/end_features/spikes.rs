//! World-seeded spike layouts, source block loops, and crystal metadata.
use super::super::placement::{Modifier, Overlay};
use super::super::*;
use crate::{nbt, structures};
use serde_json::{json, Value};

#[derive(Clone, Deserialize)]
pub struct Spike {
    pub x: i32,
    pub z: i32,
    pub radius: i32,
    pub height: i32,
    pub guarded: bool,
}
#[derive(Clone, Deserialize)]
pub struct Recipe {
    pub centers: Vec<[i32; 2]>,
    pub spikes: Vec<Spike>,
    pub bars: Vec<u16>,
    pub obsidian: u16,
    pub air: u16,
    pub bedrock: u16,
    pub fire: u16,
    pub invulnerable: bool,
    pub beam_target: Option<[i32; 3]>,
    pub step: u32,
    pub index: u32,
    pub biomes: Vec<u16>,
    pub placement: Vec<Modifier>,
}
impl Recipe {
    /// Producer selection, not the affected block footprint. Validated spike
    /// placement keeps points in the anchor chunk; radii may still cross it.
    pub(in crate::decoration) fn affects_anchor(&self,x:i32,z:i32)->bool {
        if self.spikes.is_empty() {
            self.centers.iter().any(|p|p[0].div_euclid(16)==x && p[1].div_euclid(16)==z)
        } else {
            self.spikes.iter().any(|p|p.x.div_euclid(16)==x && p.z.div_euclid(16)==z)
        }
    }
    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        self.centers.len()==10 && self.bars.len()==16
            && [self.obsidian,self.air,self.bedrock,self.fire].iter().chain(&self.bars).all(|&id|(id as usize)<palette)
            && self.obsidian!=0 && self.bedrock!=0 && self.fire!=0 && self.bars.iter().all(|&id|id!=0)
            && self.spikes.iter().all(|s|(0..=46340).contains(&s.radius) && (0..=254).contains(&s.height)
                && s.x.checked_sub(s.radius).is_some() && s.x.checked_add(s.radius).is_some()
                && s.z.checked_sub(s.radius).is_some() && s.z.checked_add(s.radius).is_some())
            && super::placement::validate(&self.placement,&self.biomes,palette)
            && super::placement::owned(&self.placement)
            // Entity ownership stays in this root's anchor chunk. Unknown
            // cross-chunk modifiers are explicitly unsupported by this adapter.
            && self.placement.iter().all(|op|!matches!(op,Modifier::InSquare|Modifier::FixedPlacement{..}))
    }
    fn layout(&self, seed: u64) -> Vec<Spike> {
        if !self.spikes.is_empty() {
            return self.spikes.clone();
        }
        let key = structures::Random::new(seed).long() & 65535;
        let mut sizes: Vec<i32> = (0..10).collect();
        structures::Random::new(key).shuffle(&mut sizes);
        self.centers
            .iter()
            .zip(sizes)
            .map(|(c, size)| Spike {
                x: c[0],
                z: c[1],
                radius: 2 + size / 3,
                height: 76 + size * 3,
                guarded: size == 1 || size == 2,
            })
            .collect()
    }
    pub(in crate::decoration) fn generate(
        &self,
        origin: [i32; 3],
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        overlay: &Overlay,
    ) -> (Vec<WorldBlock>, Option<structures::ChunkData>) {
        let spikes = self.layout(request.seed);
        let mut writes = Vec::new();
        let mut entities = Vec::new();
        super::placement::walk(
            &self.placement,
            &self.biomes,
            self.index,
            self.step,
            origin,
            field,
            profile,
            request,
            overlay,
            &mut writes,
            |at, random, live, output| {
                for spike in &spikes {
                    if at[0].div_euclid(16) != spike.x.div_euclid(16)
                        || at[2].div_euclid(16) != spike.z.div_euclid(16)
                    {
                        continue;
                    }
                    let start = output.len();
                    self.geometry(spike, request, output);
                    let yaw = random.float() * 360.0;
                    entities.push(self.crystal(spike, yaw, request, entities.len()));
                    emit(
                        [spike.x, spike.height, spike.z],
                        self.bedrock,
                        request,
                        output,
                    );
                    // The source always places bedrock immediately below the
                    // crystal before querying FireBlock.getState: plain fire.
                    emit(
                        [spike.x, spike.height + 1, spike.z],
                        self.fire,
                        request,
                        output,
                    );
                    live.commit(output, start, field, profile, request);
                }
            },
        );
        let metadata = (!entities.is_empty()).then(|| {
            let mut tag = structures::empty_data();
            nbt::put(&mut tag, "entities", nbt::list(10, entities));
            structures::ChunkData {
                tag,
                ..Default::default()
            }
        });
        (writes, metadata)
    }
    fn geometry(&self, s: &Spike, r: ChunkRequest, output: &mut Vec<WorldBlock>) {
        let radius = i64::from(s.radius);
        // BlockPos.betweenClosed increments X, then Y, then Z. Out-of-storage
        // writes have no effect and consume no randomness, so clip that Y loop.
        let top = (s.height + 10).min(r.min_y + r.height as i32 - 1);
        for z in s.z - s.radius..=s.z + s.radius {
            for y in r.min_y..=top {
                for x in s.x - s.radius..=s.x + s.radius {
                    let dx = i64::from(x) - i64::from(s.x);
                    let dz = i64::from(z) - i64::from(s.z);
                    if dx * dx + dz * dz <= radius * radius + 1 && y < s.height {
                        emit([x, y, z], self.obsidian, r, output);
                    } else if y > 65 {
                        emit([x, y, z], self.air, r, output);
                    }
                }
            }
        }
        if s.guarded {
            for dx in -2i32..=2 {
                for dz in -2i32..=2 {
                    for dy in 0..=3 {
                        let xs = dx.abs() == 2;
                        let zs = dz.abs() == 2;
                        let top = dy == 3;
                        if xs || zs || top {
                            let xe = xs || top;
                            let ze = zs || top;
                            let bits = usize::from(xe && dz != -2)
                                | (usize::from(xe && dz != 2) << 1)
                                | (usize::from(ze && dx != -2) << 2)
                                | (usize::from(ze && dx != 2) << 3);
                            emit(
                                [s.x + dx, s.height + dy, s.z + dz],
                                self.bars[bits],
                                r,
                                output,
                            );
                        }
                    }
                }
            }
        }
    }
    fn crystal(&self, s: &Spike, yaw: f32, r: ChunkRequest, ordinal: usize) -> Value {
        let mut tag = nbt::compound();
        nbt::put(&mut tag, "id", nbt::string("minecraft:end_crystal"));
        nbt::put(
            &mut tag,
            "Pos",
            nbt::list(
                6,
                vec![
                    json!([6, f64::from(s.x) + 0.5]),
                    json!([6, s.height + 1]),
                    json!([6, f64::from(s.z) + 0.5]),
                ],
            ),
        );
        nbt::put(&mut tag, "Motion", nbt::list(6, vec![json!([6, 0.0]); 3]));
        nbt::put(
            &mut tag,
            "Rotation",
            nbt::list(5, vec![json!([5, yaw]), json!([5, 0.0])]),
        );
        nbt::put(&mut tag, "fall_distance", json!([6, 0.0]));
        nbt::put(&mut tag, "Fire", json!([2, 0]));
        nbt::put(&mut tag, "Air", json!([2, 300]));
        nbt::put(&mut tag, "OnGround", json!([1, 0]));
        nbt::put(
            &mut tag,
            "Invulnerable",
            json!([1, u8::from(self.invulnerable)]),
        );
        nbt::put(&mut tag, "PortalCooldown", nbt::int(0));
        nbt::put(&mut tag, "ShowBottom", json!([1, 1]));
        if let Some(target) = self.beam_target {
            nbt::put(&mut tag, "beam_target", json!([11, target]));
        }
        // UUID is runtime-generated in vanilla, outside the worldgen RNG. Give
        // each native spawn a stable unique identity without consuming that RNG.
        let key = r.seed
            ^ mix(r.chunk_x as u32 as u64)
            ^ mix((r.chunk_z as u32 as u64) << 32)
            ^ mix(u64::from(self.index) | (u64::from(self.step) << 32))
            ^ mix(ordinal as u64);
        let a = mix(key ^ 0x656e645f63727973);
        let b = mix(a);
        nbt::put(
            &mut tag,
            "UUID",
            json!([11, [a as i32, (a >> 32) as i32, b as i32, (b >> 32) as i32]]),
        );
        tag
    }
}
fn emit(at: [i32; 3], material: u16, r: ChunkRequest, out: &mut Vec<WorldBlock>) {
    if at[1] < r.min_y || at[1] >= r.min_y + r.height as i32 {
        return;
    }
    out.push(WorldBlock {
        root_order: 0,
        tick: 0,
        x: at[0],
        y: at[1],
        z: at[2],
        material,
        upper: 0,
        role: FEATURE,
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn spike_layout_matches_actual_source_for_signed_world_seeds() {
        let fixture: Value = serde_json::from_str(include_str!(
            "../../../tests/fixtures/end-spike-layout-26.3.json"
        ))
        .unwrap();
        let recipe:Recipe=serde_json::from_value(json!({"centers":fixture["centers"],"spikes":[],"bars":[1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1],"obsidian":1,"air":0,"bedrock":1,"fire":1,"invulnerable":false,"beam_target":null,"step":4,"index":0,"biomes":[0],"placement":[]})).unwrap();
        for case in fixture["cases"].as_array().unwrap() {
            let seed = case["seed"].as_i64().unwrap();
            let actual = recipe.layout(seed as u64);
            let expected = case["spikes"].as_array().unwrap();
            for z in -8..=8 {for x in -8..=8 {
                let selected=expected.iter().any(|s|s["x"].as_i64().unwrap().div_euclid(16)==i64::from(x)
                    && s["z"].as_i64().unwrap().div_euclid(16)==i64::from(z));
                assert_eq!(recipe.affects_anchor(x,z),selected,"producer chunk {x},{z}, seed {seed}");
            }}
            assert_eq!(actual.len(), expected.len());
            for (a, e) in actual.iter().zip(expected) {
                assert_eq!(
                    json!({"x":a.x,"z":a.z,"radius":a.radius,"height":a.height,"guarded":a.guarded}),
                    *e,
                    "world seed {seed}"
                );
            }
        }
        let mut explicit=recipe;
        explicit.spikes=vec![Spike{x:-17,z:31,radius:100,height:100,guarded:false}];
        assert!(explicit.affects_anchor(-2,1));
        assert!(!explicit.affects_anchor(-1,1)); // Spilled writes still belong to the center's producer.
        assert!(!explicit.affects_anchor(0,0)); // Explicit positions replace seeded centers.
    }
}
