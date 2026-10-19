//! Feature-scheduled updates survive command distribution and chunk publication.
use super::Placement;
use crate::{ChunkRequest, nbt};
use serde::Deserialize;
use serde_json::Value;
use std::collections::HashSet;

#[derive(Clone, Copy, Deserialize, Hash, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    Block,
    Fluid,
}

#[derive(Clone, Deserialize)]
pub struct Definition {
    pub kind: Kind,
    pub id: String,
}

#[derive(Default)]
pub struct Chunk {
    blocks: Vec<Value>,
    fluids: Vec<Value>,
}
impl Chunk {
    pub fn collect(
        request: ChunkRequest,
        definitions: &[Definition],
        placements: &[Placement],
    ) -> Result<Self, String> {
        let mut result = Self::default();
        let mut seen = HashSet::new();
        for p in placements.iter().filter(|p| p.tick != 0) {
            let d = definitions
                .get(p.tick as usize - 1)
                .ok_or("missing scheduled feature tick definition")?;
            if p.index as usize >= request.block_count() {
                return Err("scheduled feature tick outside chunk storage".into());
            }
            // Minecraft ProtoChunkTicks keeps the first (type,position) pair,
            // independently of the eventual state or later overwrites.
            if !seen.insert((d.kind, d.id.as_str(), p.index)) {
                continue;
            }
            let mut tick = nbt::compound();
            nbt::put(&mut tick, "i", nbt::string(&d.id));
            nbt::put(
                &mut tick,
                "x",
                nbt::int(request.chunk_x * 16 + (p.index % 16) as i32),
            );
            nbt::put(
                &mut tick,
                "y",
                nbt::int(request.min_y + (p.index / 256) as i32),
            );
            nbt::put(
                &mut tick,
                "z",
                nbt::int(request.chunk_z * 16 + ((p.index % 256) / 16) as i32),
            );
            // ProtoChunkTicks normalizes world-generation delays to zero.
            nbt::put(&mut tick, "t", nbt::int(0));
            nbt::put(&mut tick, "p", nbt::int(0));
            match d.kind {
                Kind::Block => result.blocks.push(tick),
                Kind::Fluid => result.fluids.push(tick),
            }
        }
        Ok(result)
    }
    pub fn write(self, root: &mut Value) {
        nbt::put(root, "block_ticks", nbt::list(10, self.blocks));
        nbt::put(root, "fluid_ticks", nbt::list(10, self.fluids));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn scheduled_updates_keep_type_position_order_and_storage_coordinates() {
        let request = ChunkRequest {
            seed: 42,
            chunk_x: -2,
            chunk_z: -3,
            min_y: -64,
            height: 384,
            base_height: 64.,
            amplitude: 0.,
            frequency: 0.008,
            reserved: 0,
        };
        let definitions = vec![
            Definition {
                kind: Kind::Fluid,
                id: "minecraft:lava".into(),
            },
            Definition {
                kind: Kind::Fluid,
                id: "minecraft:water".into(),
            },
            Definition {
                kind: Kind::Block,
                id: "minecraft:lava".into(),
            },
        ];
        let placement = |index, tick| Placement {
            index,
            material: 1,
            upper: 0,
            role: super::super::FEATURE,
            tick,
        };
        let writes = [
            placement(0, 1),
            placement(0, 0),
            placement(0, 1),
            placement(0, 2),
            placement(0, 3),
            placement(98303, 1),
        ];
        let mut root = nbt::compound();
        Chunk::collect(request, &definitions, &writes)
            .unwrap()
            .write(&mut root);
        let fluids = nbt::list_values(nbt::field(&root, "fluid_ticks").unwrap());
        let blocks = nbt::list_values(nbt::field(&root, "block_ticks").unwrap());
        assert_eq!(fluids.len(), 3);
        assert_eq!(blocks.len(), 1);
        assert_eq!(nbt::field(&fluids[0], "i").unwrap()[1], "minecraft:lava");
        assert_eq!(nbt::field(&fluids[1], "i").unwrap()[1], "minecraft:water");
        for (tick, expected) in [(&fluids[0], [-32, -64, -48]), (&fluids[2], [-17, 319, -33])] {
            for (key, value) in ["x", "y", "z"].into_iter().zip(expected) {
                assert_eq!(nbt::number(nbt::field(tick, key).unwrap()), value as f64);
            }
            assert_eq!(nbt::number(nbt::field(tick, "t").unwrap()), 0.);
            assert_eq!(nbt::number(nbt::field(tick, "p").unwrap()), 0.);
        }
        assert!(Chunk::collect(request, &definitions, &[placement(98304, 1)]).is_err());
        assert!(Chunk::collect(request, &definitions, &[placement(0, 4)]).is_err());
        assert_eq!(std::mem::size_of::<Placement>(), 12);
        assert_eq!(std::mem::size_of::<super::super::WorldBlock>(), 20);
    }
}
