//! Source-ordered gateway geometry and loaded block-entity prototypes.
use super::super::placement::{Modifier, Overlay};
use super::super::*;
use crate::{nbt, structures};
use serde_json::Value;
use std::collections::BTreeMap;
#[derive(Clone, Deserialize)]
pub struct Recipe {
    pub gateway: u16,
    pub bedrock: u16,
    pub air: u16,
    pub tag: Value,
    pub step: u32,
    pub index: u32,
    pub biomes: Vec<u16>,
    pub placement: Vec<Modifier>,
}
impl Recipe {
    pub(in crate::decoration) fn validate(&self, palette: usize) -> bool {
        self.gateway != 0
            && self.bedrock != 0
            && [self.gateway, self.bedrock, self.air]
                .iter()
                .all(|&id| (id as usize) < palette)
            && nbt::validate(&self.tag, 0)
            && nbt::field(&self.tag, "id").and_then(|v| v[1].as_str())
                == Some("minecraft:end_gateway")
            && super::placement::validate(&self.placement, &self.biomes, palette)
            && super::placement::owned(&self.placement)
    }
    pub(in crate::decoration) fn generate(
        &self,
        origin: [i32; 3],
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        overlay: &Overlay,
    ) -> (Vec<WorldBlock>, Option<structures::ChunkData>) {
        let mut writes = Vec::new();
        let mut entities = BTreeMap::new();
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
            |at, _random, live, output| {
                let start = output.len();
                for dz in -1..=1 {
                    for dy in -2i32..=2 {
                        for dx in -1..=1 {
                            let pos = [at[0] + dx, at[1] + dy, at[2] + dz];
                            if pos[1] < request.min_y
                                || pos[1] >= request.min_y + request.height as i32
                            {
                                continue;
                            }
                            let material = if dx == 0 && dy == 0 && dz == 0 {
                                self.gateway
                            } else if dy == 0 {
                                self.air
                            } else if (dy.abs() == 2 && dx == 0 && dz == 0)
                                || (dy.abs() != 2 && (dx == 0 || dz == 0))
                            {
                                self.bedrock
                            } else {
                                self.air
                            };
                            entities.remove(&pos);
                            output.push(WorldBlock {
                                root_order: 0,
                                tick: 0,
                                x: pos[0],
                                y: pos[1],
                                z: pos[2],
                                material,
                                upper: 0,
                                role: FEATURE,
                            });
                        }
                    }
                }
                if at[1] >= request.min_y && at[1] < request.min_y + request.height as i32 {
                    let mut tag = self.tag.clone();
                    for (key, n) in ["x", "y", "z"].into_iter().zip(at) {
                        nbt::put(&mut tag, key, nbt::int(n));
                    }
                    entities.insert(at, tag);
                }
                live.commit(output, start, field, profile, request);
            },
        );
        let metadata = (!entities.is_empty()).then(|| {
            let mut tag = structures::empty_data();
            nbt::put(
                &mut tag,
                "block_entities",
                nbt::list(10, entities.into_values().collect()),
            );
            structures::ChunkData {
                tag,
                ..Default::default()
            }
        });
        (writes, metadata)
    }
}
