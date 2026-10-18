//! Stream terminal count children, including their random positional suffix.
//! Live-world predicates remain in the ordered queue evaluation path.
use super::*;

#[derive(Clone)]
pub(super) enum Step {
    Rarity(u32),
    Chance(f32),
    Select(f64, f64),
    InSquare,
    Offset(usize),
    Height(usize),
}
impl Step {
    pub(super) fn compile(index: usize, op: &Modifier) -> Option<Self> {
        match op {
            Modifier::RarityFilter { chance } => Some(Self::Rarity(*chance)),
            Modifier::RandomChance { chance } => Some(Self::Chance(*chance)),
            Modifier::Select { min, max } => Some(Self::Select(*min, *max)),
            Modifier::InSquare => Some(Self::InSquare),
            Modifier::Offset { .. } => Some(Self::Offset(index)),
            Modifier::HeightRange { .. } => Some(Self::Height(index)),
            _ => None,
        }
    }
}

#[derive(Clone)]
pub(super) struct Count {
    next: usize,
    end: usize,
    rng: u64,
    prefix: Arc<[[i32; 3]]>,
    scratch: smallvec::SmallVec<[[i32; 3]; 4]>,
}
impl Count {
    pub(super) fn new(end: usize, rng: u64, prefix: Arc<[[i32; 3]]>) -> Self {
        Self {
            next: 0,
            end,
            rng,
            prefix,
            scratch: Default::default(),
        }
    }
    pub(super) fn retained_bytes(&self) -> usize {
        // Conservatively include the prefix even if another entry also owns it.
        std::mem::size_of::<Self>()
            + 64
            + self.prefix.len() * std::mem::size_of::<[i32; 3]>()
            + if self.scratch.spilled() {
                self.scratch.capacity() * std::mem::size_of::<[i32; 3]>() + 32
            } else {
                0
            }
    }
    pub(super) fn next(
        &mut self,
        program: &Program,
        request: ChunkRequest,
    ) -> Option<(usize, u64, Draws)> {
        let mut parent = Rng::new(self.rng);
        'attempt: while self.next < self.end {
            let index = self.next;
            self.next += 1;
            let mut child = Rng::new(parent.next());
            self.rng = parent.0;
            self.scratch.clear();
            for op in &program.random_suffix {
                let draw = match op {
                    Step::Rarity(chance) => {
                        if child.unit() >= 1.0 / *chance as f64 {
                            continue 'attempt;
                        }
                        None
                    }
                    Step::Chance(chance) => {
                        if (child.unit() as f32) >= *chance {
                            continue 'attempt;
                        }
                        None
                    }
                    Step::Select(min, max) => {
                        let n = child.unit();
                        if n < *min || n >= *max {
                            continue 'attempt;
                        }
                        None
                    }
                    Step::InSquare => Some([child.below(16), 0, child.below(16)]),
                    Step::Offset(i) => {
                        let Modifier::Offset { x, y, z } = &program[*i] else {
                            unreachable!()
                        };
                        Some([
                            x.sample(&mut child),
                            y.sample(&mut child),
                            z.sample(&mut child),
                        ])
                    }
                    Step::Height(i) => {
                        let Modifier::HeightRange { height } = &program[*i] else {
                            unreachable!()
                        };
                        Some([0, height.sample(&mut child, request), 0])
                    }
                };
                if let Some(draw) = draw {
                    if self.scratch.is_empty() {
                        self.scratch.extend_from_slice(&self.prefix);
                    }
                    self.scratch.push(draw);
                }
            }
            let draws = if self.scratch.is_empty() {
                Draws::from_slice(&self.prefix)
            } else {
                Draws::from_slice(&self.scratch)
            };
            return Some((index, child.0, draws));
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    // Compare all ordered candidate data and live positions, not Candidate::Eq
    // (which intentionally compares only queue ordering keys).
    fn drain(programs: &[Value], seed: u64, streaming: bool) -> (Value, usize) {
        let (mut profile, field, request, recipe) = super::super::tests::fixture();
        profile.biomes[0].decorations = (0..programs.len() as u32).collect();
        profile.decorations = programs
            .iter()
            .map(|p| {
                let mut recipe = recipe.clone();
                let mut program: Program = serde_json::from_value(p.clone()).unwrap();
                if !streaming {
                    program.terminal_count = None;
                }
                recipe.placement = Some(program);
                recipe
            })
            .collect();
        let mut entries = Vec::new();
        let mut queries = Vec::new();
        for (id, recipe) in profile.decorations.iter().enumerate() {
            expand(
                recipe.placement.as_ref().unwrap(),
                id as u32,
                0,
                [0, 0, 0],
                Rng::new(seed),
                &field,
                request,
                None,
                Some(&mut entries),
                &mut queries,
            );
        }
        assert!(queries.is_empty());
        let size = entries.len();
        let mut queue = Queue::new(entries);
        let mut cache = EvaluationCache::default();
        let mut overlay = Overlay::default();
        let mut output = Vec::new();
        while let Some(candidate) = queue.next(
            &field,
            &profile,
            request,
            &overlay,
            &mut cache,
            None,
            &mut queries,
        ) {
            if output.len() == 3 {
                // Simulate suspension after selecting a leaf. The cursor has
                // advanced already and retry must not duplicate/skip siblings.
                queue.retry(candidate.clone());
                let retried = queue
                    .next(
                        &field,
                        &profile,
                        request,
                        &overlay,
                        &mut cache,
                        None,
                        &mut queries,
                    )
                    .unwrap();
                assert_eq!(
                    (retried.recipe, retried.order, retried.seed),
                    (candidate.recipe, candidate.order.clone(), candidate.seed)
                );
                queue = queue.clone();
                cache = cache.clone();
                overlay = overlay.snapshot();
            }
            let position = position(
                &candidate,
                &profile.decorations[candidate.recipe as usize],
                &field,
                &profile,
                request,
                &overlay,
                &mut cache,
            );
            output.push(json!({"recipe":candidate.recipe,"group":candidate.group,
                "order":candidate.order.as_slice(),"seed":candidate.seed,"origin":candidate.origin,
                "draws":&*candidate.draws,"start":candidate.evaluation.start,
                "end":candidate.evaluation.end,"position":position}));
            if let Some([x, y, z]) = position {
                overlay.write(&profile, [x, y.min(120), z], 4);
            }
        }
        assert!(queries.is_empty());
        (json!(output), size)
    }

    #[test]
    fn cursor_matches_eager_order_rng_live_filters_and_checkpoint_retry() {
        let cases = [
            json!([{"type":"count","count":3},{"type":"in_square"},{"type":"heightmap","map":1},
                {"type":"count","count":23},{"type":"offset","x":{"type":"uniform","min":-7,"max":7},"y":2,"z":3},
                {"type":"random_chance","chance":0.8},{"type":"height_range","height":{"absolute":60}},
                {"type":"in_square"},{"type":"block_predicate_filter","predicate":{"type":"true"}}]),
            json!([{"type":"count","count":4},{"type":"in_square"},
                {"type":"heightmap","map":1},{"type":"count","count":19},
                {"type":"block_predicate_filter","predicate":{"type":"true"}}]),
            json!([{"type":"count","count":4},{"type":"heightmap","map":1},
                {"type":"rarity_filter","chance":2},{"type":"count","count":13},
                {"type":"random_chance","chance":0.7},{"type":"biome"}]),
            json!([{"type":"count","count":1},{"type":"count","count":1},
                {"type":"count","count":1},{"type":"count","count":1},
                {"type":"heightmap","map":1},{"type":"count","count":13}]),
            json!([{"type":"count_on_every_layer","count":2},
                {"type":"count","count":5},{"type":"heightmap","map":1}]),
            json!([{"type":"cuboid","xz_size":1,"y_size":1},
                {"type":"count","count":7},{"type":"heightmap","map":1}]),
            json!([{"type":"count","count":3},{"type":"in_square"},
                {"type":"noise_threshold_count","noise_level":-0.8,"below_noise":5,"above_noise":7},
                {"type":"heightmap","map":1}]),
        ];
        for program in cases {
            let branches: Vec<_> = [(0., 0.3), (0.3, 0.8), (0.8, 1.)]
                .into_iter()
                .map(|(min, max)| {
                    let mut p = program.as_array().unwrap().clone();
                    p.push(json!({"type":"select","min":min,"max":max}));
                    json!(p)
                })
                .collect();
            for seed in [0, 42, 847, u64::MAX, 0u64.wrapping_sub(0x9e3779b97f4a7c15)] {
                let (expected, _) = drain(&branches, seed, false);
                let (actual, _) = drain(&branches, seed, true);
                assert_eq!(actual, expected, "seed {seed}, program {program}");
            }
        }
    }

    #[test]
    fn terminal_count_uses_one_entry_but_cuboids_keep_eager_fallback() {
        let compact = [json!([{"type":"count","count":4096},{"type":"heightmap","map":1}])];
        let (expected, old_entries) = drain(&compact, 42, false);
        let (actual, entries) = drain(&compact, 42, true);
        assert_eq!(actual, expected);
        assert_eq!((old_entries, entries), (4096, 1));
        let dynamic = [
            json!([{"type":"count","count":32},{"type":"cuboid","xz_size":1,"y_size":1},{"type":"heightmap","map":1}]),
        ];
        assert_eq!(drain(&dynamic, 42, true), drain(&dynamic, 42, false));
        let rejected = [json!([{"type":"count","count":4096},{"type":"random_chance","chance":0}])];
        assert_eq!(drain(&rejected, 42, true), drain(&rejected, 42, false));
    }
}
