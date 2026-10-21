//! Ordered feature/structure commands and metadata share one chunk replay.
use super::*;
use crate::{nbt, structures};
use serde_json::Value;
use std::{
    collections::{BTreeMap, HashSet},
    sync::Arc,
};

struct Event {
    end: usize,
    tag: Value,
    pieces: usize,
    placed: usize,
}
pub(crate) struct Generation {
    pub placements: Vec<Vec<Placement>>,
    pub(crate) boundary_versions: Option<super::dependencies::PendingVersions>,
    events: Vec<Vec<Event>>,
    starts: Vec<Value>,
}
impl Generation {
    pub(super) fn new(
        request: ChunkRequest,
        side: usize,
        profile: &WorldProfile,
        starts: &[Arc<structures::Start>],
    ) -> Self {
        Self {
            placements: vec![Vec::new(); side * side],
            boundary_versions: None,
            events: (0..side * side).map(|_| Vec::new()).collect(),
            starts: (0..side * side)
                .map(|i| {
                    structures::start_data(
                        ChunkRequest {
                            chunk_x: request.chunk_x + (i % side) as i32,
                            chunk_z: request.chunk_z + (i / side) as i32,
                            ..request
                        },
                        profile,
                        starts,
                    )
                })
                .collect(),
        }
    }
    fn slot(request: ChunkRequest, side: usize, x: i32, z: i32) -> Option<usize> {
        let (x, z) = (x - request.chunk_x, z - request.chunk_z);
        (x >= 0 && z >= 0 && x < side as i32 && z < side as i32)
            .then(|| z as usize * side + x as usize)
    }
    pub(super) fn push(&mut self, request: ChunkRequest, side: usize, block: WorldBlock) {
        let Some(slot) = Self::slot(
            request,
            side,
            block.x.div_euclid(16),
            block.z.div_euclid(16),
        ) else {
            return;
        };
        let y = block.y - request.min_y;
        if y < 0 || y >= request.height as i32 {
            return;
        }
        self.placements[slot].push(Placement {
            index: y as u32 * 256
                + block.z.rem_euclid(16) as u32 * 16
                + block.x.rem_euclid(16) as u32,
            material: block.material,
            upper: block.upper,
            role: block.role,
            tick: block.tick,
        });
    }
    pub(super) fn event(
        &mut self,
        request: ChunkRequest,
        side: usize,
        x: i32,
        z: i32,
        data: structures::ChunkData,
    ) {
        if let Some(slot) = Self::slot(request, side, x, z) {
            self.events[slot].push(Event {
                end: self.placements[slot].len(),
                tag: data.tag,
                pieces: data.pieces,
                placed: data.placed,
            });
        }
    }
    pub(crate) fn apply(
        &self,
        slot: usize,
        request: ChunkRequest,
        profile: &WorldProfile,
        placements: &[Placement],
        blocks: &mut [u16],
    ) -> Result<structures::ChunkData, String> {
        let mut root = self.starts[slot].clone();
        let starts = nbt::field(nbt::field(&root, "structures").unwrap(), "starts").unwrap()[1]
            .as_object()
            .unwrap()
            .len();
        let mut entities = BTreeMap::<usize, Value>::new();
        let mut begin = 0;
        let mut pieces = 0;
        let mut placed = 0;
        let mut ticks = [HashSet::new(), HashSet::new()];
        for event in self.events[slot]
            .iter()
            .map(Some)
            .chain(std::iter::once(None))
        {
            let end = event.map_or(placements.len(), |e| e.end);
            let commands = &placements[begin..end];
            decorate_observed(Some(profile), commands, blocks, |index, before, after| {
                if structures::block_name(profile, before) != structures::block_name(profile, after)
                {
                    entities.remove(&index);
                }
            });
            let mut updates = nbt::compound();
            ticks::Chunk::collect(request, &profile.decoration_ticks, commands)?
                .write(&mut updates);
            append_ticks(&mut root, &updates, &mut ticks);
            begin = end;
            if let Some(event) = event {
                pieces += event.pieces;
                placed += event.placed;
                for tag in nbt::field(&event.tag, "block_entities")
                    .map(nbt::list_values)
                    .unwrap_or(&[])
                {
                    let pos = ["x", "y", "z"]
                        .map(|key| nbt::number(nbt::field(tag, key).unwrap()) as i32);
                    let index = (pos[1] - request.min_y) as usize * 256
                        + pos[2].rem_euclid(16) as usize * 16
                        + pos[0].rem_euclid(16) as usize;
                    entities.insert(index, tag.clone());
                }
                append(
                    &mut root,
                    "entities",
                    nbt::field(&event.tag, "entities")
                        .map(nbt::list_values)
                        .unwrap_or(&[]),
                );
                append_ticks(&mut root, &event.tag, &mut ticks);
                if let Some(processing) = nbt::field(&event.tag, "PostProcessing") {
                    let incoming = nbt::list_values(processing);
                    let mut sections = nbt::field(&root, "PostProcessing")
                        .map(nbt::list_values)
                        .unwrap_or(&[])
                        .to_vec();
                    sections.resize_with(request.height as usize / 16, || nbt::list(2, Vec::new()));
                    for (target, source) in sections.iter_mut().zip(incoming) {
                        let mut values = nbt::list_values(target).to_vec();
                        for value in nbt::list_values(source) {
                            if !values.contains(value) {
                                values.push(value.clone());
                            }
                        }
                        *target = nbt::list(2, values);
                    }
                    nbt::put(&mut root, "PostProcessing", nbt::list(9, sections));
                }
            }
        }
        nbt::put(
            &mut root,
            "block_entities",
            nbt::list(10, entities.into_values().collect()),
        );
        for key in ["block_ticks", "fluid_ticks"] {
            if nbt::field(&root, key).is_none() {
                nbt::put(&mut root, key, nbt::list(10, Vec::new()));
            }
        }
        if nbt::field(&root, "entities").is_none() {
            nbt::put(&mut root, "entities", nbt::list(10, Vec::new()));
        }
        Ok(structures::ChunkData {
            tag: root,
            writes: Vec::new(),
            plant_updates: pairs::Updates::default(),
            starts,
            pieces,
            placed,
        })
    }
}
fn append(root: &mut Value, key: &str, incoming: &[Value]) {
    if incoming.is_empty() {
        return;
    }
    let mut values = nbt::field(root, key)
        .map(nbt::list_values)
        .unwrap_or(&[])
        .to_vec();
    values.extend_from_slice(incoming);
    nbt::put(root, key, nbt::list(10, values));
}
fn append_ticks(root: &mut Value, source: &Value, seen: &mut [HashSet<(String, [i32; 3])>; 2]) {
    for (index, key) in ["block_ticks", "fluid_ticks"].into_iter().enumerate() {
        let values: Vec<_> = nbt::field(source, key)
            .map(nbt::list_values)
            .unwrap_or(&[])
            .iter()
            .filter(|tick| {
                let id = nbt::field(tick, "i").unwrap()[1]
                    .as_str()
                    .unwrap()
                    .to_owned();
                let pos =
                    ["x", "y", "z"].map(|key| nbt::number(nbt::field(tick, key).unwrap()) as i32);
                seen[index].insert((id, pos))
            })
            .cloned()
            .collect();
        append(root, key, &values);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn command(index: u32, material: u16, role: u8, tick: u16) -> Placement {
        Placement {
            index,
            material,
            upper: 0,
            role,
            tick,
        }
    }
    fn entity(request: ChunkRequest, index: u32, marker: &str) -> Value {
        let mut tag = nbt::compound();
        nbt::put(&mut tag, "id", nbt::string("minecraft:chest"));
        nbt::put(&mut tag, "marker", nbt::string(marker));
        for (key, value) in [
            ("x", request.chunk_x * 16 + (index % 16) as i32),
            ("y", request.min_y + (index / 256) as i32),
            ("z", request.chunk_z * 16 + (index % 256 / 16) as i32),
        ] {
            nbt::put(&mut tag, key, nbt::int(value));
        }
        tag
    }
    #[test]
    fn accepted_changes_remove_old_entities_but_same_block_states_retain_them() {
        let (mut profile, _, request) = super::super::replay_tests::fixture(42, -2);
        profile.structures.names = vec![
            "air", "stone", "grass", "dirt", "chest", "chest", "plant", "water", "upper",
        ]
        .into_iter()
        .map(str::to_owned)
        .collect();
        let mut generation = Generation::new(request, 1, &profile, &[]);
        generation.placements[0] = vec![
            command(0, 4, FEATURE, 0),
            command(1, 4, FEATURE, 0),
            command(2, 4, FEATURE, 0),
            command(0, 5, FEATURE, 0), // State-only change keeps the chest's data.
            command(0, 1, PLANT, 0),   // Rejected placement must not remove it.
            command(1, 1, FEATURE, 0), // Accepted different block removes it.
            command(2, 0, FEATURE, 0),
            command(2, 4, FEATURE, 0), // Returning to chest cannot revive old data.
            command(3, 4, FEATURE, 0),
            command(3, 5, FEATURE, 0),
        ];
        let first: Vec<_> = (0..3).map(|i| entity(request, i, "first")).collect();
        let later = entity(request, 3, "later");
        for (end, entities) in [(3, first.clone()), (9, vec![later.clone()])] {
            let mut tag = nbt::compound();
            nbt::put(&mut tag, "block_entities", nbt::list(10, entities));
            generation.events[0].push(Event {
                end,
                tag,
                pieces: 1,
                placed: 1,
            });
        }
        let mut blocks = vec![0; request.block_count()];
        let data = generation
            .apply(0, request, &profile, &generation.placements[0], &mut blocks)
            .unwrap();
        assert_eq!(&blocks[..4], &[5, 1, 4, 5]);
        assert_eq!(
            nbt::list_values(nbt::field(&data.tag, "block_entities").unwrap()),
            &[first[0].clone(), later]
        );
    }
    #[test]
    fn feature_and_structure_ticks_keep_first_scheduled_type_position_in_step_order() {
        let (mut profile, _, request) = super::super::replay_tests::fixture(42, -2);
        profile.structures.names = (0..profile.materials.len())
            .map(|i| i.to_string())
            .collect();
        profile.decoration_ticks = vec![ticks::Definition {
            kind: ticks::Kind::Fluid,
            id: "minecraft:lava".into(),
        }];
        let commands = vec![
            command(0, 1, FEATURE, 1),
            command(1, 1, FEATURE, 0),
            command(1, 0, FEATURE, 1),
            command(2, 1, FEATURE, 1),
        ];
        let tick = |index, priority| {
            let mut root = nbt::compound();
            ticks::Chunk::collect(
                request,
                &profile.decoration_ticks,
                &[command(index, 1, FEATURE, 1)],
            )
            .unwrap()
            .write(&mut root);
            let mut tag = nbt::list_values(nbt::field(&root, "fluid_ticks").unwrap())[0].clone();
            nbt::put(&mut tag, "p", nbt::int(priority));
            tag
        };
        let mut generation = Generation::new(request, 1, &profile, &[]);
        for (end, incoming) in [(2, vec![tick(0, -1), tick(1, -1)]), (4, vec![tick(2, 1)])] {
            let mut tag = nbt::compound();
            nbt::put(&mut tag, "fluid_ticks", nbt::list(10, incoming));
            nbt::put(&mut tag, "block_ticks", nbt::list(10, vec![tick(0, -2)]));
            generation.events[0].push(Event {
                end,
                tag,
                pieces: 0,
                placed: 0,
            });
        }
        let mut blocks = vec![0; request.block_count()];
        let data = generation
            .apply(0, request, &profile, &commands, &mut blocks)
            .unwrap();
        assert_eq!(
            nbt::list_values(nbt::field(&data.tag, "fluid_ticks").unwrap()),
            &[tick(0, 0), tick(1, -1), tick(2, 0)]
        );
        assert_eq!(
            nbt::list_values(nbt::field(&data.tag, "block_ticks").unwrap()),
            &[tick(0, -2)]
        );
        assert_eq!(
            blocks[1], 0,
            "overwriting a block does not cancel its scheduled tick"
        );
    }
}
