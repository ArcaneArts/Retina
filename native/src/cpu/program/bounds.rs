//! Conservative density certificates with the same interval operations as
//! density_bounds.wgsl. Unknown intervals always retain actual point evaluation.
use super::{Context, Evaluator, Opcode, scalar};
use crate::program::Instruction;
#[derive(Clone, Copy, Debug)]
pub(crate) struct Bounds {
    pub lo: f32,
    pub hi: f32,
}
impl Bounds {
    pub const UNKNOWN: Self = Self {
        lo: f32::NEG_INFINITY,
        hi: f32::INFINITY,
    };
    fn exact(v: f32) -> Self {
        Self { lo: v, hi: v }
    }
    pub fn finite(self) -> bool {
        self.lo.is_finite() && self.hi.is_finite() && self.lo <= self.hi
    }
    pub fn widen(self) -> Self {
        if !self.finite() {
            return Self::UNKNOWN;
        }
        let error = ((self.lo.abs() + self.hi.abs()) * 0.0000152587890625).max(1e-36);
        let out = Self {
            lo: self.lo - error,
            hi: self.hi + error,
        };
        if out.finite() { out } else { Self::UNKNOWN }
    }
    fn product(a: Self, b: Self) -> Self {
        let v = [a.lo * b.lo, a.lo * b.hi, a.hi * b.lo, a.hi * b.hi];
        Self {
            lo: v.into_iter().fold(f32::INFINITY, f32::min),
            hi: v.into_iter().fold(f32::NEG_INFINITY, f32::max),
        }
    }
}
#[cfg(test)]
const SUPPORTED: &[Opcode] = &[
    Opcode::Constant,
    Opcode::Coordinate,
    Opcode::Interpolate,
    Opcode::Add,
    Opcode::Subtract,
    Opcode::Multiply,
    Opcode::Minimum,
    Opcode::Maximum,
    Opcode::Absolute,
    Opcode::Square,
    Opcode::Cube,
    Opcode::HalfNegative,
    Opcode::QuarterNegative,
    Opcode::Squeeze,
    Opcode::Negate,
    Opcode::SquareRoot,
    Opcode::Sign,
    Opcode::Clamp,
    Opcode::RangeChoice,
    Opcode::Lerp,
];
pub(super) fn possible(op: Opcode, n: &Instruction, values: &[bool]) -> bool {
    let get = |i: u32| values.get(i as usize).copied().unwrap_or(false);
    use Opcode::*;
    match op {
        Constant | Coordinate => true,
        Interpolate | Lerp => get(n.a) && get(n.b) && get(n.c),
        Add | Subtract | Multiply | Minimum | Maximum => get(n.a) && get(n.b),
        Absolute | Square | Cube | HalfNegative | QuarterNegative | Squeeze | Negate
        | SquareRoot | Sign | Clamp => get(n.a),
        RangeChoice => get(n.a) && (get(n.b) || get(n.c)),
        _ => false,
    }
}
impl Evaluator<'_> {
    pub(crate) fn can_bound(&self, program: usize) -> bool {
        self.prepared
            .plans
            .get(program)
            .is_some_and(|p| p.boundable)
    }
    fn interpolation_bounds(&mut self, field: usize, lo: [f32; 3], hi: [f32; 3]) -> Bounds {
        if (0..3).any(|i| {
            !lo[i].is_finite()
                || !hi[i].is_finite()
                || lo[i].abs() > 8388608.0
                || hi[i].abs() > 8388608.0
                || lo[i] > hi[i]
        }) {
            return Bounds::UNKNOWN;
        }
        let f = &self.prepared.registry.interpolations[field];
        let step = [f.cell[0] as f32, f.cell[1] as f32, f.cell[0] as f32];
        let lower = std::array::from_fn::<_, 3, _>(|i| (lo[i] / step[i]).floor() * step[i]);
        let size = std::array::from_fn::<_, 3, _>(|i| {
            (((hi[i] / step[i]).ceil() * step[i] - lower[i]) / step[i]).ceil() as usize + 1
        });
        if size.iter().any(|&n| n > 256) || size.iter().product::<usize>() > 256 {
            return Bounds::UNKNOWN;
        }
        let mut bounds = Bounds {
            lo: f32::INFINITY,
            hi: f32::NEG_INFINITY,
        };
        for y in 0..size[1] {
            for z in 0..size[2] {
                for x in 0..size[0] {
                    let offset = [x, y, z];
                    let p = std::array::from_fn(|i| lower[i] + offset[i] as f32 * step[i]);
                    let value = self.corner(field, p, Context::default(), 0);
                    if !value.is_finite() {
                        return Bounds::UNKNOWN;
                    }
                    bounds.lo = bounds.lo.min(value);
                    bounds.hi = bounds.hi.max(value);
                }
            }
        }
        bounds.widen()
    }
    pub(crate) fn bounds(&mut self, pid: usize, lo: [f32; 3], hi: [f32; 3]) -> Bounds {
        if !self.can_bound(pid) {
            return Bounds::UNKNOWN;
        }
        let prepared = self.prepared;
        let program = &prepared.registry.programs[pid];
        let mut values = std::mem::take(&mut self.bounds_scratch);
        values.resize(program.nodes.len(), Bounds::UNKNOWN);
        for step in &prepared.plans[pid].steps {
            let n = &program.nodes[step.index];
            let get = |i: u32| values.get(i as usize).copied().unwrap_or(Bounds::UNKNOWN);
            let (a, b, c) = (get(n.a), get(n.b), get(n.c));
            let p = n.p;
            use Opcode::*;
            let mut result = if let Some(value) = step.constant {
                Bounds::exact(value)
            } else {
                match step.opcode {
                    Constant => Bounds::exact(p[0]),
                    Coordinate => Bounds {
                        lo: lo[n.a as usize],
                        hi: hi[n.a as usize],
                    },
                    Interpolate if a.finite() && b.finite() && c.finite() => self
                        .interpolation_bounds(
                            p[0] as usize,
                            [a.lo, b.lo, c.lo],
                            [a.hi, b.hi, c.hi],
                        ),
                    Add if a.finite() && b.finite() => Bounds {
                        lo: a.lo + b.lo,
                        hi: a.hi + b.hi,
                    },
                    Subtract if a.finite() && b.finite() => Bounds {
                        lo: a.lo - b.hi,
                        hi: a.hi - b.lo,
                    },
                    Multiply if a.finite() && b.finite() => Bounds::product(a, b),
                    Minimum if a.finite() && b.finite() => Bounds {
                        lo: a.lo.min(b.lo),
                        hi: a.hi.min(b.hi),
                    },
                    Maximum if a.finite() && b.finite() => Bounds {
                        lo: a.lo.max(b.lo),
                        hi: a.hi.max(b.hi),
                    },
                    Absolute | Square | Cube | HalfNegative | QuarterNegative | Squeeze
                    | Negate | SquareRoot | Sign | Clamp
                        if a.finite() =>
                    {
                        let l = scalar(step.opcode, n, |_| a.lo);
                        let h = scalar(step.opcode, n, |_| a.hi);
                        let crosses = a.lo <= 0.0 && a.hi >= 0.0;
                        Bounds {
                            lo: if crosses && matches!(step.opcode, Absolute | Square) {
                                0.0
                            } else {
                                l.min(h)
                            },
                            hi: l.max(h),
                        }
                    }
                    RangeChoice if a.finite() => {
                        if a.lo >= p[0] && a.hi < p[1] {
                            b
                        } else if a.hi < p[0] || a.lo >= p[1] {
                            c
                        } else if b.finite() && c.finite() {
                            Bounds {
                                lo: b.lo.min(c.lo),
                                hi: b.hi.max(c.hi),
                            }
                        } else {
                            Bounds::UNKNOWN
                        }
                    }
                    Lerp if a.finite() && b.finite() && c.finite() => {
                        let delta = Bounds {
                            lo: c.lo - b.hi,
                            hi: c.hi - b.lo,
                        }
                        .widen();
                        let product = Bounds::product(a, delta).widen();
                        Bounds {
                            lo: b.lo + product.lo,
                            hi: b.hi + product.hi,
                        }
                    }
                    _ => Bounds::UNKNOWN,
                }
            };
            if !matches!(step.opcode, Constant | Coordinate) {
                result = result.widen();
            }
            if !result.finite() {
                result = Bounds::UNKNOWN;
            }
            values[step.index] = result;
        }
        // The ordinary sampler checks all six returned roots, including implicit
        // node zero. An interval shortcut must not hide a non-finite output.
        let all_finite =
            (0..6).all(|i| values[program.roots.get(i).copied().unwrap_or(0) as usize].finite());
        let result = if all_finite {
            values[program.roots.first().copied().unwrap_or(0) as usize]
        } else {
            Bounds::UNKNOWN
        };
        self.bounds_scratch = values;
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cpu_interval_operations_cover_the_actual_gpu_certificate_switch() {
        let source = include_str!("../../density_bounds.wgsl");
        let body = source
            .split_once("switch op {")
            .unwrap()
            .1
            .split_once("default:{}")
            .unwrap()
            .0;
        let actual: std::collections::BTreeSet<u32> = body
            .split("case ")
            .skip(1)
            .map(|p| p.split_once("u:").unwrap().0.parse().unwrap())
            .collect();
        assert_eq!(actual, SUPPORTED.iter().map(|op| *op as u32).collect());
        assert!(!Bounds::UNKNOWN.finite());
        assert!(!Bounds::exact(f32::NAN).widen().finite());
        let zero = Bounds::exact(0.0).widen();
        assert!(zero.lo < 0.0 && zero.hi > 0.0);
    }
}

#[cfg(test)]
mod property_tests {
    use super::*;
    use std::sync::Arc;
    #[test]
    fn supported_intervals_enclose_actual_scalar_outputs_across_signs_and_branches() {
        for &op in SUPPORTED {
            let mut r = super::super::tests::one(op as u32);
            for program in &mut r.programs {
                for (axis, node) in program.nodes[..3].iter_mut().enumerate() {
                    node.op = 29;
                    node.a = axis as u32;
                    node.b = 0;
                    node.c = 0;
                    node.p = [0.0; 4];
                }
            }
            let p = super::super::Prepared::new(Arc::new(r)).unwrap();
            let mut e = p.evaluator(123456789, 63, &[]);
            for (lo, hi) in [
                ([-4.0, -7.0, -3.0], [3.0, 6.0, 5.0]),
                ([0.0, 0.0, 0.0], [0.0, 0.0, 0.0]),
                ([-0.001, 1.0, -1.0], [0.001, 2.0, 1.0]),
            ] {
                let bounds = e.bounds(1, lo, hi);
                assert!(bounds.finite(), "{op:?} {bounds:?}");
                for ix in 0..=4 {
                    for iy in 0..=4 {
                        for iz in 0..=4 {
                            let t = [ix, iy, iz];
                            let point = std::array::from_fn(|i| {
                                lo[i] + (hi[i] - lo[i]) * t[i] as f32 / 4.0
                            });
                            let value = e.run(1, point, Context::default()).unwrap()[0];
                            assert!(
                                value >= bounds.lo && value <= bounds.hi,
                                "{op:?} {point:?} value {value} outside {bounds:?}"
                            );
                        }
                    }
                }
            }
        }
    }
}
