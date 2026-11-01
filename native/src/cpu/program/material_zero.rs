//! Conservative zero material results in the band above vertical gradients and
//! below the actual preliminary floor. Unknown nodes keep ordinary evaluation.
use super::{Opcode, Plan};
use crate::program::Program;
#[derive(Clone, Copy)]
struct Known {
    value: f32,
    lower: f32,
}
impl Known {
    fn constant(value: f32) -> Option<Self> {
        value.is_finite().then_some(Self {
            value,
            lower: f32::NEG_INFINITY,
        })
    }
    fn requires(self, condition: Self) -> Self {
        Self {
            lower: self.lower.max(condition.lower),
            ..self
        }
    }
}
pub(super) fn prepare(program: &Program, plan: &Plan) -> Option<f32> {
    let mut known = vec![None; program.nodes.len()];
    for step in &plan.steps {
        let node = &program.nodes[step.index];
        let get = |i: u32| known.get(i as usize).copied().flatten();
        known[step.index] = if let Some(value) = step.constant {
            Known::constant(value)
        } else {
            match step.opcode {
                // These are the only assumptions. Every result carries the
                // lower Y needed by gradients actually consumed by its proof.
                Opcode::PreliminarySurface => Known::constant(0.0),
                Opcode::VerticalChance => Some(Known {
                    value: 0.0,
                    lower: node.p[1],
                }),
                Opcode::And => get(node.a).and_then(|a: Known| {
                    if a.value == 0.0 {
                        Known::constant(0.0).map(|v| v.requires(a))
                    } else {
                        get(node.b).map(|b| b.requires(a))
                    }
                }),
                Opcode::Or => get(node.a).and_then(|a| {
                    if a.value != 0.0 {
                        Some(a)
                    } else {
                        get(node.b).map(|b| b.requires(a))
                    }
                }),
                Opcode::Not => get(node.a).and_then(|a| {
                    Known::constant(if a.value == 0.0 { 1.0 } else { 0.0 }).map(|v| v.requires(a))
                }),
                Opcode::RangeChoice => get(node.a).and_then(|a| {
                    let branch = if a.value >= node.p[0] && a.value < node.p[1] {
                        node.b
                    } else {
                        node.c
                    };
                    get(branch).map(|v| v.requires(a))
                }),
                _ => None,
            }
        };
    }
    let first = known[program.roots.first().copied().unwrap_or(0) as usize]?;
    if first.value != 0.0 {
        return None;
    }
    let mut lower = first.lower;
    // Sampler::run validates ALL six returned roots, including implicit node
    // zero. Never bypass an unproven/nonfinite returned value.
    for i in 0..6 {
        lower = lower.max(known[program.roots.get(i).copied().unwrap_or(0) as usize]?.lower);
    }
    Some(lower)
}

#[cfg(test)]
mod tests {
    use super::super::{Context, Prepared};
    use super::*;
    use crate::program::{Instruction, RegistryProgram};
    use std::sync::Arc;
    fn node(op: u32, a: u32, b: u32, c: u32, p: [f32; 4]) -> Instruction {
        Instruction { op, a, b, c, p }
    }
    fn fixture() -> RegistryProgram {
        let mut r = super::super::tests::one(40);
        r.material_layers = true;
        r.programs.push(Program {
            nodes: vec![
                node(0, 0, 0, 0, [0.0; 4]),
                node(48, 0, 0, 0, [-64.0, -59.0, 0.0, 0.0]),
                node(0, 0, 0, 0, [2.0; 4]),
                node(40, 1, 2, 0, [0.0; 4]),
                node(54, 0, 0, 0, [0.0; 4]),
                node(48, 0, 0, 0, [0.0, 8.0, 0.0, 0.0]),
                node(40, 5, 2, 0, [0.0; 4]),
                node(40, 4, 6, 0, [0.0; 4]),
                node(41, 3, 7, 0, [0.0; 4]),
            ],
            roots: vec![8, 8],
        });
        r
    }
    #[test]
    fn zero_band_uses_only_consumed_assumptions_and_exact_threshold_edges() {
        let p = Prepared::new(Arc::new(fixture())).unwrap();
        assert_eq!(p.material_zero[0], Some(-59.0));
        assert_eq!(p.material_zero_minimum, -59.0);
        let mut e = p.evaluator(u64::MAX, 63, &[]);
        assert!(!e.material_is_zero(3, (-59.0_f32).next_down(), 63.25));
        assert!(e.material_is_zero(3, -59.0, 63.25));
        assert!(!e.material_is_zero(3, 63.25, 63.25));
        assert!(!e.material_is_zero(0, 0.0, 63.25));
        assert!(!e.material_is_zero(usize::MAX, 0.0, 63.25));
        for x in [-28_000_000.0, -17.25, 0.0, 28_000_000.0] {
            for y in [-59.0, -0.0, 0.0, 7.0, 62.0, 63.25_f32.next_down()] {
                let context = Context {
                    detail: [0.0, 0.0, 0.0, 63.25],
                    ..Context::default()
                };
                let value = e.run(3, [x, y, -x], context).unwrap();
                assert_eq!(value[0], 0.0);
                assert!(value.iter().all(|v| v.is_finite()));
            }
        }
    }
    #[test]
    fn unknown_roots_nonfinite_values_disabled_layers_and_empty_roots_stay_safe() {
        let mut r = fixture();
        let mut p = r.programs[3].clone();
        p.nodes.push(node(0, 0, 0, 0, [f32::MAX; 4]));
        p.nodes.push(node(12, 9, 0, 0, [0.0; 4]));
        p.roots.push(10);
        r.programs[3] = p;
        assert!(Prepared::new(Arc::new(r)).unwrap().material_zero[0].is_none());
        let mut r = fixture();
        r.material_layers = false;
        assert!(Prepared::new(Arc::new(r)).unwrap().material_zero[0].is_none());
        let mut r = fixture();
        r.programs[3].roots.clear();
        let p = Prepared::new(Arc::new(r)).unwrap();
        assert_eq!(p.material_zero[0], Some(f32::NEG_INFINITY));
        let mut e = p.evaluator(1, 63, &[]);
        assert_eq!(e.run(3, [0.0; 3], Context::default()).unwrap(), [0.0; 6]);
        let mut r = fixture();
        r.programs[1].roots.clear();
        let p = Prepared::new(Arc::new(r)).unwrap();
        let mut e = p.evaluator(1, 63, &[]);
        let bounds = e.bounds(1, [-4.0; 3], [4.0; 3]);
        let actual = e.run(1, [0.0; 3], Context::default()).unwrap();
        assert!(bounds.finite() && bounds.lo <= actual[0] && bounds.hi >= actual[0]);
        let mut r = fixture();
        r.programs[3].nodes.push(node(29, 1, 0, 0, [0.0; 4]));
        r.programs[3].roots.push(9); // an unproven returned coordinate
        assert!(Prepared::new(Arc::new(r)).unwrap().material_zero[0].is_none());
    }
    #[test]
    fn actual_material_zero_proofs_match_all_returned_values_across_contexts_and_seeds() {
        use crate::world_definition::{WorldDefinition, catalog::WorldCatalog};
        let c = WorldCatalog::read(std::path::Path::new(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat"
        )))
        .unwrap();
        let d = WorldDefinition::from_gzip(c.materialize("normal", 123456789).unwrap().as_slice())
            .unwrap();
        let mut proved = 0;
        for key in ["minecraft:overworld", "minecraft:the_nether"] {
            let profile = d.dimension(key).unwrap().profile().unwrap();
            let r = profile.registry_program.as_ref().unwrap();
            let p = Prepared::new(Arc::new(r.clone())).unwrap();
            for seed in [1, u64::MAX] {
                let mut e = p.evaluator(seed, profile.sea_level, &profile.terrain_features.bands);
                for pid in 3..3 + profile.biomes.len() {
                    let Some(lower) = p.material_zero[pid - 3] else {
                        continue;
                    };
                    for preliminary in [-80.0_f32, 0.0, 63.25, 320.0, 1536.0] {
                        for y in [
                            lower.next_down(),
                            lower,
                            lower.next_up(),
                            preliminary.next_down(),
                            preliminary,
                            1535.0,
                        ] {
                            for variant in 0..8 {
                                let x = if variant < 4 { -17.25 } else { 28_000_000.0 };
                                let context = Context {
                                    surface: [
                                        variant as f32 * 20.0,
                                        variant as f32 - 2.0,
                                        variant as f32 * 2.0,
                                        variant as f32 - 4.0,
                                    ],
                                    detail: [
                                        variant as f32 * 40.0,
                                        variant as f32 * 0.5 - 2.0,
                                        if variant % 2 == 0 {
                                            i32::MIN as f32
                                        } else {
                                            63.0
                                        },
                                        preliminary,
                                    ],
                                };
                                if e.material_is_zero(pid, y, preliminary) {
                                    let value = e.run(pid, [x, y, -x], context).unwrap();
                                    assert_eq!(
                                        value[0], 0.0,
                                        "{key} pid={pid} seed={seed} y={y} context={:?}",
                                        context.detail
                                    );
                                    assert!(value.iter().all(|v| v.is_finite()));
                                    proved += 1;
                                }
                            }
                        }
                    }
                }
            }
        }
        assert!(proved > 1000);
        eprintln!("Verified {proved} actual material zero-proof samples");
    }
}
