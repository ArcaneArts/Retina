use super::*;
use crate::program::{Interpolation, Noise};
fn n(op: u32, a: u32, b: u32, c: u32, p: [f32; 4]) -> Instruction {
    Instruction { op, a, b, c, p }
}
fn registry(program: Program) -> RegistryProgram {
    RegistryProgram {
        generation: None,
        programs: vec![program.clone(), program.clone(), program],
        interpolations: vec![],
        noises: vec![Noise {
            horizontal_scale: 1.0,
            frequency: 0.01,
            amplitude: 1.0,
            salt: 871,
            coefficients: vec![1.0, 0.5],
        }],
        points: vec![],
        surface: [0, 1, 0],
        terrain_cell: [4, 8],
        density_composition: false,
        surface_noises: [0; 3],
        material_layers: false,
        material_halo: false,
        aquifer: None,
    }
}
fn one(op: u32) -> RegistryProgram {
    let mut r = registry(Program {
        nodes: vec![
            n(0, 0, 0, 0, [0.25; 4]),
            n(0, 0, 0, 0, [2.0; 4]),
            n(0, 0, 0, 0, [-0.5; 4]),
            n(op, 0, 1, 2, [0.0, 1.0, 1.0, 2.0]),
        ],
        roots: vec![3],
    });
    r.points = vec![[0.0, 0.5, 0.0, 0.0], [1.0, 0.25, 1.0, 0.0]];
    r.interpolations = vec![Interpolation {
        input: Program {
            nodes: vec![n(29, 0, 0, 0, [0.0; 4])],
            roots: vec![0],
        },
        cell: [4, 8],
    }];
    for program in &mut r.programs {
        let node = &mut program.nodes[3];
        match op {
            1 | 27 | 28 => node.p[0] = 0.0,
            2 => {
                node.a = 0;
                node.b = 1;
            }
            3 => {
                node.a = 1;
                node.p = [-64.0, 320.0, -1.0, 1.0];
            }
            25 => {
                node.b = 0;
                node.c = 2;
            }
            26 | 31 => node.p = [1.0, 1.0, 80.0, 160.0],
            29 => node.a = 1,
            52 => node.p = [100.0, 0.5, 8.0, 0.0],
            _ => {}
        }
    }
    r
}
#[test]
fn every_registered_opcode_executes_in_both_surface_modes() {
    let mut visited = std::collections::BTreeSet::new();
    for &opcode in Opcode::ALL {
        for layered in [false, true] {
            let mut r = one(opcode as u32);
            r.material_layers = layered;
            let prepared = Prepared::new(Arc::new(r)).unwrap();
            // This fixture covers the real opcode switch even for constant-foldable
            // instructions by executing its non-folded plan as an additional oracle.
            let mut direct = Prepared::new(prepared.registry.clone()).unwrap();
            for plan in &mut direct.plans {
                for step in &mut plan.steps {
                    step.constant = None;
                }
            }
            let context = Context {
                surface: [1.0, 3.0, 4.0, -2.0],
                detail: [2.0, 0.25, 63.0, 60.0],
            };
            for point in [[-17.25, 72.5, 33.125], [16777216.0, -31.5, -16777216.0]] {
                let a = prepared
                    .evaluator(u64::MAX, 63, &[4, 5, 6])
                    .run(0, point, context)
                    .unwrap();
                let b = direct
                    .evaluator(u64::MAX, 63, &[4, 5, 6])
                    .run(0, point, context)
                    .unwrap();
                assert!(a[0].is_finite(), "{opcode:?}: {a:?}");
                assert_eq!(a.map(f32::to_bits), b.map(f32::to_bits), "{opcode:?}");
            }
            visited.insert(opcode as u32);
        }
    }
    assert_eq!(visited.len(), 47);
}
#[test]
fn scalar_rules_cover_negative_gradients_splines_and_signed_zero() {
    let prepared = Prepared::new(Arc::new(one(25))).unwrap();
    // Hermite at x=.25, left value=.25, right=2, derivatives .5/.25.
    let expected = mix(0.25, 2.0, 0.25) + 0.25 * 0.75 * mix(0.5 - 1.75, -0.25 + 1.75, 0.25);
    assert_eq!(
        prepared
            .evaluator(0, 63, &[])
            .run(0, [0.0; 3], Context::default())
            .unwrap()[0],
        expected
    );
    assert_eq!(gradient(-0.25, 1, [0.0, 1.0, 0.0, 1.0]), 0.75);
    assert_eq!(
        scalar(Opcode::Sign, &n(21, 0, 0, 0, [0.0; 4]), |_| -0.0),
        0.0
    );
    assert_eq!(
        scalar(Opcode::Divide, &n(7, 0, 1, 0, [0.0; 4]), |i| if i == 0 {
            1.0
        } else {
            -0.00000001
        }),
        1_000_000.0
    );
}
#[test]
fn nested_interpolation_reuses_global_corners_and_preserves_a_linear_field() {
    let mut r = one(28);
    r.interpolations[0].input = Program {
        nodes: vec![
            n(29, 0, 0, 0, [0.0; 4]),
            n(29, 1, 0, 0, [0.0; 4]),
            n(4, 0, 1, 0, [0.0; 4]),
        ],
        roots: vec![2],
    };
    r.interpolations.push(Interpolation {
        input: Program {
            nodes: vec![
                n(29, 0, 0, 0, [0.0; 4]),
                n(29, 1, 0, 0, [0.0; 4]),
                n(29, 2, 0, 0, [0.0; 4]),
                n(28, 0, 1, 2, [0.0; 4]),
            ],
            roots: vec![3],
        },
        cell: [8, 16],
    });
    for p in &mut r.programs {
        p.nodes = vec![
            n(29, 0, 0, 0, [0.0; 4]),
            n(29, 1, 0, 0, [0.0; 4]),
            n(29, 2, 0, 0, [0.0; 4]),
            n(28, 0, 1, 2, [1.0, 0.0, 0.0, 0.0]),
        ];
    }
    let prepared = Prepared::new(Arc::new(r)).unwrap();
    let mut evaluator = prepared.evaluator(971, 63, &[]);
    for point in [
        [-17.0, -63.0, 31.0],
        [0.0, 0.0, 0.0],
        [7.5, 15.25, -0.5],
        [-17.0, -63.0, 31.0],
    ] {
        assert!(
            (evaluator.run(0, point, Context::default()).unwrap()[0] - point[0] - point[1]).abs()
                < 0.00001
        );
    }
    assert!(!evaluator.corners.is_empty());
}
#[test]
fn dead_branches_are_pruned_and_large_programs_reuse_scratch() {
    let mut r = one(0);
    for p in &mut r.programs {
        p.nodes = vec![n(0, 0, 0, 0, [1.0; 4])];
        for index in 1..5000 {
            p.nodes.push(n(4, index - 1, 0, 0, [0.0; 4]));
        }
        p.nodes.push(n(1, 0, 0, 0, [0.0, 1.0, 1.0, 0.0])); // unreachable noise
        p.roots = vec![4999];
    }
    let prepared = Prepared::new(Arc::new(r)).unwrap();
    assert_eq!(prepared.plans[0].steps.len(), 5000);
    assert!(prepared.plans[0].steps.iter().all(|s| s.constant.is_some()));
    let mut evaluator = prepared.evaluator(0, 63, &[]);
    assert_eq!(
        evaluator.run(0, [0.0; 3], Context::default()).unwrap()[0],
        5000.0
    );
    let capacity = evaluator.frames[0].capacity();
    assert_eq!(
        evaluator.run(0, [1.0; 3], Context::default()).unwrap()[0],
        5000.0
    );
    assert_eq!(evaluator.frames[0].capacity(), capacity);
}
#[test]
fn invalid_operations_are_rejected_before_evaluation() {
    let mut r = one(0);
    r.programs[0].nodes[3].op = 99;
    assert!(Prepared::new(Arc::new(r)).err().unwrap().contains("99"));
}
