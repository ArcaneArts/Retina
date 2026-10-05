//! Lazy terrain-dependent expansion. Ordinary programs retain a sorted vector;
//! only resumed layer attempts enter the small ordered heap.
use super::*;
use std::cmp::{Ordering, Reverse};
use std::collections::BinaryHeap;

#[derive(Clone)]
pub(super) struct Attempt {
    pub index: usize,
    pub layer: usize,
    pub attempt: usize,
    pub found: bool,
    pub resolved: bool,
}
impl Candidate {
    fn compare(&self, other: &Self) -> Ordering {
        self.group
            .cmp(&other.group)
            .then(self.order.cmp(&other.order))
            // Shared selector branches must resolve their parent ground before
            // any branch writes at this attempt. The cached result is inherited.
            .then(other.layer.is_some().cmp(&self.layer.is_some()))
            .then(self.recipe.cmp(&other.recipe))
    }
}
impl PartialEq for Candidate {
    fn eq(&self, other: &Self) -> bool {
        self.compare(other) == Ordering::Equal
    }
}
impl Eq for Candidate {}
impl PartialOrd for Candidate {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
impl Ord for Candidate {
    fn cmp(&self, other: &Self) -> Ordering {
        self.compare(other)
    }
}
pub(crate) struct Queue {
    ready: Vec<Candidate>,
    resumed: BinaryHeap<Reverse<Candidate>>,
}
impl Queue {
    pub(in crate::decoration) fn new(mut ready: Vec<Candidate>) -> Self {
        ready.sort_by(|a, b| b.cmp(a));
        Self {
            ready,
            resumed: BinaryHeap::new(),
        }
    }
    fn pop(&mut self) -> Option<Candidate> {
        if self
            .resumed
            .peek()
            .is_some_and(|a| self.ready.last().is_none_or(|b| a.0 < *b))
        {
            self.resumed.pop().map(|v| v.0)
        } else {
            self.ready.pop()
        }
    }
    pub(in crate::decoration) fn next(
        &mut self,
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        overlay: &Overlay,
        cache: &mut EvaluationCache,
        counts: Option<&counts::Counts>,
        queries: &mut Vec<counts::Query>,
    ) -> Option<Candidate> {
        while let Some(mut candidate) = self.pop() {
            let Some(mut state) = candidate.layer.take() else {
                return Some(candidate);
            };
            let recipe = &profile.decorations[candidate.recipe as usize];
            if !state.resolved {
                let Some(at) = position_inner(
                    &candidate, recipe, field, profile, request, overlay, cache, false,
                ) else {
                    continue;
                };
                candidate.origin = at;
                candidate.actions.clear();
                state.resolved = true;
            }
            let program = recipe.placement.as_ref().unwrap();
            let Modifier::CountOnEveryLayer { count } = &program[state.index] else {
                unreachable!()
            };
            let mut rng = Rng::new(candidate.seed);
            // Minecraft intentionally resamples the provider at every loop test,
            // including the terminating test; this is not one count per layer.
            if state.attempt >= count.sample(&mut rng) as usize {
                if state.found {
                    state.layer += 1;
                    state.attempt = 0;
                    state.found = false;
                    let n = candidate.order.len();
                    candidate.order[n - 2] = state.layer;
                    candidate.order[n - 1] = 0;
                    candidate.seed = rng.0;
                    candidate.layer = Some(state);
                    self.resumed.push(Reverse(candidate));
                }
                continue;
            }
            let x = candidate.origin[0] + rng.below(16);
            let z = candidate.origin[2] + rng.below(16);
            let key = (candidate.group, state.index, candidate.order.clone());
            let at = *cache.entry(key).or_insert_with(|| {
                overlay
                    .ground_layer(field, profile, request, x, z, state.layer)
                    .map(|y| [x, y, z])
            });
            state.found |= at.is_some();
            if let Some(at) = at {
                let mut children = Vec::new();
                expand_from(
                    program,
                    candidate.recipe,
                    candidate.group,
                    at,
                    at,
                    Rng::new(rng.0),
                    field,
                    request,
                    counts,
                    Some(&mut children),
                    queries,
                    state.index + 1,
                    Vec::new(),
                    candidate.order.clone(),
                );
                self.resumed.extend(children.into_iter().map(Reverse));
            }
            state.attempt += 1;
            let n = candidate.order.len();
            candidate.order[n - 1] = state.attempt;
            candidate.seed = rng.0;
            candidate.layer = Some(state);
            self.resumed.push(Reverse(candidate));
        }
        None
    }
}

impl Overlay {
    /// Ground transitions are cached per live column, then invalidated by every
    /// write to that column. No biome-name, height or water-level guesses enter.
    pub(in crate::decoration) fn ground_layer(
        &self,
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        x: i32,
        z: i32,
        layer: usize,
    ) -> Option<i32> {
        if let Some(grounds) = self.grounds.borrow().get(&(x, z)) {
            return grounds.get(layer).copied();
        }
        let start = self.height(field, profile, request, x, z, 4)?;
        let empty = |m: u16| {
            profile.material_flags[m as usize] & 64 != 0
                || m == 0
                || m == profile.water
                || m == profile.geology.lava
        };
        let bedrock =
            |m: u16| profile.material_flags[m as usize] & 128 != 0 || m == profile.bedrock;
        let mut current = self.material(field, profile, request, [x, start, z])?;
        let mut grounds = Vec::new();
        for y in (request.min_y + 1..=start).rev() {
            let below = self.material(field, profile, request, [x, y - 1, z])?;
            if empty(current) && !empty(below) && !bedrock(below) {
                grounds.push(y);
            }
            current = below;
        }
        let result = grounds.get(layer).copied();
        self.grounds.borrow_mut().insert((x, z), grounds);
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn fixture(program: serde_json::Value) -> (WorldProfile, Field, ChunkRequest) {
        let recipe = json!({"source":"test:layers","salt":0,"density":1,"low_density":1,
            "noise_count":false,"rarity":1,"tries":1,"spread":[0,0,0],"kind":"plant",
            "states":[{"lower":4,"upper":0,"weight":1,"band":0,"dry":false}],"placement":program});
        let profile = serde_json::from_value(json!({
            "biome_scale":256,"blend":0.55,"sea_level":63,
            "stone":1,"water":7,"bedrock":6,"deepslate":1,"snow":1,"ice":7,
            "materials":["air","stone","grass","dirt","log","leaves","bedrock","water","cave_air"],
            "biomes":[{"id":"test:forest","climate":[0,0,0,0],"terrain":[0,0,0],
                "top":2,"filler":3,"underwater":3,"flags":0,"decorations":[0,1]}],
            "noises":[],"material_flags":[64,0,1,1,8,4,128,64,64],
            "heightmap_masks":[0,63,63,63,63,31,63,19,0],"ordered_decorations":true,
            "decorations":[recipe,recipe]
        }))
        .unwrap();
        let field = Field {
            substrate: None,
            origin_x: 0,
            origin_z: 0,
            side: 1,
            columns: vec![
                Column {
                    height: 32,
                    materials: 2 | (3 << 16),
                    packed: 0
                };
                COLUMNS
            ],
        };
        let request = ChunkRequest {
            seed: 42,
            chunk_x: 0,
            chunk_z: 0,
            min_y: -64,
            height: 128,
            base_height: 32.0,
            amplitude: 0.0,
            frequency: 0.008,
            reserved: 0,
        };
        (profile, field, request)
    }
    #[test]
    fn grounds_use_registered_empty_states_and_invalidate_after_live_writes() {
        let (profile, field, request) = fixture(json!([]));
        let mut overlay = Overlay::default();
        assert_eq!(
            overlay.ground_layer(&field, &profile, request, 5, 5, 0),
            Some(32)
        );
        assert_eq!(
            overlay.ground_layer(&field, &profile, request, 5, 5, 1),
            None
        );
        overlay.write(&profile, [5, 20, 5], 8); // Nonzero cave-air material.
        assert_eq!(
            overlay.ground_layer(&field, &profile, request, 5, 5, 1),
            Some(20)
        );
        overlay.write(&profile, [5, 19, 5], 7);
        assert_eq!(
            overlay.ground_layer(&field, &profile, request, 5, 5, 1),
            Some(19)
        );
        overlay.write(&profile, [5, 18, 5], 6); // Bedrock is never eligible ground.
        assert_eq!(
            overlay.ground_layer(&field, &profile, request, 5, 5, 1),
            None
        );
        overlay.write(&profile, [5, 18, 5], 1);
        overlay.write(&profile, [5, 32, 5], 4);
        assert_eq!(
            overlay.ground_layer(&field, &profile, request, 5, 5, 0),
            Some(33)
        );
        assert_eq!(
            overlay.ground_layer(&field, &profile, request, 5, 5, 1),
            Some(19)
        );
        assert_eq!(
            overlay.ground_layer(&field, &profile, request, 16, 5, 0),
            None
        );
    }
    #[test]
    fn lazy_layers_share_selector_parent_ground_and_discover_downstream_gpu_counts() {
        let program = json!([{"type":"count_on_every_layer","count":1},
            {"type":"noise_based_count","noise_to_count_ratio":1,"noise_factor":30,"noise_offset":1}]);
        let (mut profile, field, request) = fixture(program);
        let mut overlay = Overlay::default();
        for x in 0..16 {
            for z in 0..16 {
                overlay.write(&profile, [x, 20, z], 0);
            }
        }
        let mut initial = Vec::new();
        let mut queries = Vec::new();
        let mut counts = counts::Counts::default();
        let build = |initial: &mut Vec<Candidate>,
                     queries: &mut Vec<counts::Query>,
                     counts: &counts::Counts| {
            expand(
                profile.decorations[0].placement.as_ref().unwrap(),
                0,
                0,
                [0, -64, 0],
                Rng::new(7),
                &field,
                request,
                Some(counts),
                Some(initial),
                queries,
            );
        };
        build(&mut initial, &mut queries, &counts);
        let mut queue = Queue::new(initial);
        let mut cache = EvaluationCache::default();
        assert!(
            queue
                .next(
                    &field,
                    &profile,
                    request,
                    &overlay,
                    &mut cache,
                    Some(&counts),
                    &mut queries
                )
                .is_none()
        );
        assert_eq!(
            queries.len(),
            2,
            "only actual surface/cave layers request counts"
        );
        counts.extend(queries.drain(..).map(|q| (q, 1)));
        let mut initial = Vec::new();
        build(&mut initial, &mut queries, &counts);
        let mut queue = Queue::new(initial);
        let mut cache = EvaluationCache::default();
        let mut ys = Vec::new();
        while let Some(c) = queue.next(
            &field,
            &profile,
            request,
            &overlay,
            &mut cache,
            Some(&counts),
            &mut queries,
        ) {
            let at = position(
                &c,
                &profile.decorations[0],
                &field,
                &profile,
                request,
                &overlay,
                &mut cache,
            )
            .unwrap();
            ys.push(at[1]);
        }
        assert_eq!(ys, vec![32, 20]);
        assert!(queries.is_empty());
        // Two flattened selector branches share one layer attempt budget. A
        // selected branch may mutate ground before the other branch is visited.
        for (id, (min, max)) in [(0., 0.5), (0.5, 1.)].into_iter().enumerate() {
            profile.decorations[id].placement = Some(
                serde_json::from_value(json!([
                {"type":"count_on_every_layer","count":1},{"type":"select","min":min,"max":max}]))
                .unwrap(),
            );
        }
        let mut initial = Vec::new();
        for id in 0..2 {
            expand(
                profile.decorations[id].placement.as_ref().unwrap(),
                id as u32,
                0,
                [0, -64, 0],
                Rng::new(7),
                &field,
                request,
                None,
                Some(&mut initial),
                &mut queries,
            );
        }
        let mut queue = Queue::new(initial);
        let mut cache = EvaluationCache::default();
        let mut ys = Vec::new();
        while let Some(c) = queue.next(
            &field,
            &profile,
            request,
            &overlay,
            &mut cache,
            None,
            &mut queries,
        ) {
            if let Some(at) = position(
                &c,
                &profile.decorations[c.recipe as usize],
                &field,
                &profile,
                request,
                &overlay,
                &mut cache,
            ) {
                ys.push(at[1]);
                overlay.write(&profile, at, 4);
            }
        }
        assert_eq!(ys, vec![32, 20]);
    }
}
