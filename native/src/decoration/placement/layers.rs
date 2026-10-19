//! Lazy count and terrain-dependent expansion. Ready entries use a sorted vector;
//! interleaved count cursors and resumed layer attempts use a small ordered heap.
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
#[derive(Clone)]
pub(crate) struct Queue {
    ready: Vec<Candidate>,
    resumed: BinaryHeap<Reverse<Candidate>>,
    retry: Option<Candidate>,
    deferred: Option<DeferredGroup>,
}
#[derive(Clone)]
struct DeferredGroup {
    recipe: u32,
    origin: [i32; 3],
    seed: u64,
}
pub(in crate::decoration) struct QueueCheckpoint {
    pub queue: Queue,
    pub cache: EvaluationCache,
}
impl Queue {
    pub(in crate::decoration) fn retained_bytes(&self) -> usize {
        let candidates = self
            .ready
            .iter()
            .chain(self.resumed.iter().map(|v| &v.0))
            .chain(self.retry.iter());
        (self.ready.capacity() + self.resumed.capacity()) * std::mem::size_of::<Candidate>()
            + candidates
                .map(|c| {
                    order_heap_bytes(&c.order)
                        + c.stream
                            .as_ref()
                            .map_or(0, |cursor| cursor.retained_bytes())
                        + if c.draws.spilled() {
                            c.draws.capacity() * std::mem::size_of::<[i32; 3]>() + 32
                        } else {
                            0
                        }
                })
                .sum::<usize>()
    }
    pub(in crate::decoration) fn new(mut ready: Vec<Candidate>) -> Self {
        ready.sort_by(|a, b| b.cmp(a));
        Self {
            ready,
            resumed: BinaryHeap::new(),
            retry: None,
            deferred: None,
        }
    }
    /// The proven GPU program owns this entire RNG group and has not consumed
    /// its first child. Keep only the original seed/origin while it is pending.
    /// Unsupported/mid-group continuations retain their existing queue exactly.
    pub(in crate::decoration) fn defer_gpu_group(
        &mut self,
        recipe: u32,
        origin: [i32; 3],
        seed: u64,
    ) -> bool {
        if self.deferred.is_some() || self.peek_group() != Some((recipe, recipe)) {
            return false;
        }
        let first = self.retry.as_ref().or_else(|| {
            match (self.ready.last(), self.resumed.peek().map(|v| &v.0)) {
                (Some(a), Some(b)) => Some(if b < a { b } else { a }),
                (a, b) => a.or(b),
            }
        });
        if first.is_none_or(|c| c.order.as_slice() != [0, 0] || c.layer.is_some()) {
            return false;
        }
        self.remove_group(recipe);
        // Vec::retain keeps the old expanded capacity; release it before the
        // saved prefix is charged against the per-region retention budget.
        self.ready.shrink_to_fit();
        self.resumed.shrink_to_fit();
        self.deferred = Some(DeferredGroup {
            recipe,
            origin,
            seed,
        });
        true
    }
    pub(in crate::decoration) fn peek_group(&self) -> Option<(u32, u32)> {
        if let Some(group) = &self.deferred {
            return Some((group.recipe, group.recipe));
        }
        let next = self.retry.as_ref().or_else(|| {
            match (self.ready.last(), self.resumed.peek().map(|v| &v.0)) {
                (Some(a), Some(b)) => Some(if b < a { b } else { a }),
                (a, b) => a.or(b),
            }
        });
        next.map(|c| (c.group, c.recipe))
    }
    pub(in crate::decoration) fn remove_group(&mut self, group: u32) {
        if self.deferred.as_ref().is_some_and(|d| d.recipe == group) {
            self.deferred = None;
        }
        self.ready.retain(|c| c.group != group);
        self.resumed.retain(|c| c.0.group != group);
        if self.retry.as_ref().is_some_and(|c| c.group == group) {
            self.retry = None;
        }
    }
    fn pop(&mut self) -> Option<Candidate> {
        if let Some(candidate) = self.retry.take() {
            return Some(candidate);
        }
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
        self.next_checkpoint(
            field,
            profile,
            request,
            overlay,
            cache,
            counts,
            queries,
            None,
            |_, _| false,
        )
    }
    pub(in crate::decoration) fn retry(&mut self, candidate: Candidate) {
        debug_assert!(self.retry.is_none());
        // This was already selected before suspension. Preserve that selection
        // even if another ready candidate has an equal comparison key.
        self.retry = Some(candidate);
    }
    pub(in crate::decoration) fn next_checkpoint(
        &mut self,
        field: &Field,
        profile: &WorldProfile,
        request: ChunkRequest,
        overlay: &Overlay,
        cache: &mut EvaluationCache,
        counts: Option<&counts::Counts>,
        queries: &mut Vec<counts::Query>,
        mut checkpoint: Option<&mut Option<QueueCheckpoint>>,
        stop_before: impl Fn(u32, u32) -> bool,
    ) -> Option<Candidate> {
        if let Some(group) = self.deferred.take() {
            // A failed/sparse GPU job resumes the original CPU stream. This
            // program has constant counts and no unresolved count providers.
            let program = profile.decorations[group.recipe as usize]
                .placement
                .as_ref()
                .unwrap();
            expand(
                program,
                group.recipe,
                group.recipe,
                group.origin,
                Rng::new(group.seed),
                field,
                request,
                counts,
                Some(&mut self.ready),
                queries,
            );
            self.ready.sort_by(|a, b| b.cmp(a));
        }
        // The caller checked the initial head already. Recheck only when lazy
        // expansion loops internally; ordinary streamed candidates return on
        // the first iteration and need no second GPU eligibility lookup.
        let mut initial = true;
        while initial
            || !self
                .peek_group()
                .is_some_and(|(group, recipe)| stop_before(group, recipe))
        {
            initial = false;
            let Some(mut candidate) = self.pop() else {
                break;
            };
            if let Some(mut cursor) = candidate.stream.take() {
                let program = profile.decorations[candidate.recipe as usize]
                    .placement
                    .as_ref()
                    .unwrap();
                if let Some((child_index, seed, child_draws)) = cursor.next(program, request) {
                    let mut order = candidate.order.clone();
                    *order.last_mut().unwrap() = child_index;
                    let next = Candidate {
                        recipe: candidate.recipe,
                        group: candidate.group,
                        order,
                        seed,
                        origin: candidate.origin,
                        draws: child_draws,
                        evaluation: candidate.evaluation.clone(),
                        layer: None,
                        stream: Some(cursor),
                    };
                    // Most streams precede the next parent/group already in the
                    // sorted vector. Avoid a heap operation for that common case.
                    if self.ready.last().is_none_or(|ready| next <= *ready) {
                        self.ready.push(next);
                    } else {
                        self.resumed.push(Reverse(next));
                    }
                }
            }
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
                candidate.draws.clear();
                candidate.evaluation = 0..0;
                state.resolved = true;
            }
            let program = recipe.placement.as_ref().unwrap();
            let Modifier::CountOnEveryLayer { count } = &program[state.index] else {
                unreachable!()
            };
            let retry = checkpoint
                .as_ref()
                .filter(|c| {
                    c.is_none() && counts.is_some() && program.has_noise_from(state.index + 1)
                })
                .map(|_| {
                    let mut retry = candidate.clone();
                    retry.layer = Some(state.clone());
                    retry
                });
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
                let before = queries.len();
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
                    candidate.order.clone(),
                );
                if queries.len() != before {
                    if let (Some(checkpoint), Some(retry)) = (checkpoint.as_deref_mut(), retry) {
                        let mut queue = self.clone();
                        queue.retry(retry);
                        *checkpoint = Some(QueueCheckpoint {
                            queue,
                            cache: cache.clone(),
                        });
                    }
                }
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

    #[test]
    fn group_handoff_preserves_retry_and_merged_queue_order() {
        let candidate = |group, order| Candidate {
            recipe: group,
            group,
            order: std::iter::once(order).collect(),
            seed: 0,
            origin: [0; 3],
            draws: Default::default(),
            evaluation: 0..0,
            layer: None,
            stream: None,
        };
        let mut queue = Queue::new(vec![candidate(3, 0), candidate(5, 2), candidate(7, 0)]);
        queue
            .resumed
            .extend([Reverse(candidate(2, 0)), Reverse(candidate(5, 1))]);
        queue.retry(candidate(5, 0));
        assert_eq!(queue.peek_group(), Some((5, 5)), "retry takes precedence");
        queue.remove_group(5);
        for group in [2, 3, 7] {
            assert_eq!(queue.peek_group(), Some((group, group)));
            assert_eq!(queue.pop().unwrap().group, group);
        }
        assert!(queue.peek_group().is_none());
    }

    #[test]
    fn exhausted_layer_returns_control_before_consuming_next_gpu_group() {
        let (mut profile, field, request) = fixture(json!([
            {"type":"count_on_every_layer","count":0}
        ]));
        profile.decorations[1].placement = Some(
            serde_json::from_value(json!([
                {"type":"count","count":2}, {"type":"count","count":3},
                {"type":"in_square"}
            ]))
            .unwrap(),
        );
        let mut entries = Vec::new();
        for id in 0..2 {
            expand(
                profile.decorations[id].placement.as_ref().unwrap(),
                id as u32,
                id as u32,
                [0, request.min_y, 0],
                Rng::new(81),
                &field,
                request,
                None,
                Some(&mut entries),
                &mut Vec::new(),
            );
        }
        let mut queue = Queue::new(entries);
        let mut cache = EvaluationCache::default();
        assert!(
            queue
                .next_checkpoint(
                    &field,
                    &profile,
                    request,
                    &Overlay::default(),
                    &mut cache,
                    None,
                    &mut Vec::new(),
                    None,
                    |g, _| g == 1
                )
                .is_none()
        );
        assert_eq!(queue.peek_group(), Some((1, 1)));
        let first = queue
            .next(
                &field,
                &profile,
                request,
                &Overlay::default(),
                &mut cache,
                None,
                &mut Vec::new(),
            )
            .unwrap();
        assert_eq!(first.order.as_slice(), [0, 0]);
    }

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
    fn deferred_gpu_group_reconstructs_all_ordered_candidates_and_skips_success() {
        let (profile, field, request) = fixture(json!([
            {"type":"count","count":64}, {"type":"count","count":40},
            {"type":"in_square"},
            {"type":"height_range","height":{"type":"uniform",
                "min_inclusive":{"absolute":-64},"max_inclusive":{"absolute":63}}},
            {"type":"block_predicate_filter","predicate":{"type":"true"}},
            {"type":"biome"}
        ]));
        let mut entries = Vec::new();
        for id in 0..2 {
            expand(
                profile.decorations[id].placement.as_ref().unwrap(),
                id as u32,
                id as u32,
                [0, request.min_y, 0],
                Rng::new(1928 + id as u64),
                &field,
                request,
                None,
                Some(&mut entries),
                &mut Vec::new(),
            );
        }
        let original = Queue::new(entries);
        let drain = |mut queue: Queue| {
            let mut cache = EvaluationCache::default();
            let overlay = Overlay::default();
            let mut out = Vec::new();
            while let Some(c) = queue.next(
                &field,
                &profile,
                request,
                &overlay,
                &mut cache,
                None,
                &mut Vec::new(),
            ) {
                let at = position(
                    &c,
                    &profile.decorations[c.recipe as usize],
                    &field,
                    &profile,
                    request,
                    &overlay,
                    &mut cache,
                );
                out.push((
                    c.group,
                    c.recipe,
                    c.order,
                    c.seed,
                    c.origin,
                    c.draws,
                    c.evaluation,
                    at,
                ));
            }
            out
        };
        let expected = drain(original.clone());
        assert_eq!(expected.len(), 5120);
        let mut compact = original.clone();
        let before = compact.retained_bytes();
        assert!(compact.defer_gpu_group(0, [0, request.min_y, 0], 1928));
        assert!(compact.retained_bytes() <= before / 2);
        assert_eq!(compact.peek_group(), Some((0, 0)));
        assert_eq!(
            drain(compact.clone()),
            expected,
            "failed GPU and cloned snapshots resume exactly"
        );
        compact.remove_group(0);
        assert_eq!(
            drain(compact),
            expected[2560..],
            "successful GPU consumes only its own group"
        );
        let mut partial = original;
        partial.next(
            &field,
            &profile,
            request,
            &Overlay::default(),
            &mut EvaluationCache::default(),
            None,
            &mut Vec::new(),
        );
        assert!(!partial.defer_gpu_group(0, [0, request.min_y, 0], 1928));
        assert_eq!(
            drain(partial),
            expected[1..],
            "never restart a partially consumed group"
        );
    }
    #[test]
    fn retry_precedes_other_candidates_with_equal_order_keys() {
        let candidate = |seed| Candidate {
            recipe: 0,
            group: 0,
            order: Order::new(),
            seed,
            origin: [0, 0, 0],
            draws: Draws::new(),
            evaluation: 0..0,
            layer: None,
            stream: None,
        };
        let mut queue = Queue::new(vec![candidate(1), candidate(2)]);
        let chosen = queue.pop().unwrap();
        let seed = chosen.seed;
        let remaining = queue.clone().pop().unwrap().seed;
        queue.retry(chosen);
        assert_eq!(queue.pop().unwrap().seed, seed);
        assert_eq!(queue.pop().unwrap().seed, remaining);
        assert!(queue.pop().is_none());
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
