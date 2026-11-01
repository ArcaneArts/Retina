//! Bounded current-cell reuse for the bit-exact positive-zero context.
//! Other contexts keep the existing fully keyed corner maps.
use super::{
    Context, Evaluator, cpu_vm_cell_hits, cpu_vm_cell_lookups, cpu_vm_cell_vertices_reused,
    trilinear,
};
const SLOTS: usize = 32;
#[derive(Clone, Copy, Default)]
struct Cell {
    field: usize,
    lower: [u32; 3],
    values: [f32; 8],
    valid: u8,
}
pub(super) struct Cells([Cell; SLOTS]);
impl Cells {
    #[cold]
    fn new() -> Box<Self> {
        Box::new(Self([Cell::default(); SLOTS]))
    }
}
impl Evaluator<'_> {
    #[inline]
    fn remember_cell(&mut self, slot: usize, cell: Cell) {
        self.cells.get_or_insert_with(Cells::new).0[slot] = cell;
    }
    pub(super) fn interpolate_cell<const TRACK: bool>(
        &mut self,
        field: usize,
        lower: [f32; 3],
        size: [f32; 3],
        alpha: [f32; 3],
        context: Context,
        depth: usize,
    ) -> f32 {
        self.record::<TRACK>(cpu_vm_cell_lookups, 1);
        let slot = field & (SLOTS - 1);
        let key = lower.map(f32::to_bits);
        // Keep a copy across recursive misses. A nested field may replace this
        // slot; restoring this exact entry later can only evict that reuse.
        let previous = self
            .cells
            .as_ref()
            .map(|cells| cells.0[slot])
            .filter(|cell| cell.valid != 0 && cell.field == field && cell.lower == key);
        if previous.is_some() {
            self.record::<TRACK>(cpu_vm_cell_hits, 1);
        }
        let mut cell = previous.unwrap_or(Cell {
            field,
            lower: key,
            ..Cell::default()
        });
        // Skipped vertices MUST stay zero on every query, even if this cell has
        // an older value there: overflow/sign-zero behavior at alpha zero matters.
        let mut corners = [0.0; 8];
        for (index, value) in corners.iter_mut().enumerate() {
            let upper = [index & 1, (index >> 1) & 1, (index >> 2) & 1];
            if (0..3).any(|i| upper[i] != 0 && alpha[i] == 0.0) {
                continue;
            }
            let bit = 1u8 << index;
            if cell.valid & bit != 0 {
                *value = cell.values[index];
                self.record::<TRACK>(cpu_vm_cell_vertices_reused, 1);
            } else {
                let point = std::array::from_fn(|i| lower[i] + upper[i] as f32 * size[i]);
                *value = self.corner_inner::<TRACK>(field, point, context, depth);
                cell.values[index] = *value;
                cell.valid |= bit;
            }
            if alpha == [0.0; 3] {
                self.remember_cell(slot, cell);
                return *value;
            }
        }
        self.remember_cell(slot, cell);
        trilinear(corners, alpha)
    }
}

#[cfg(test)]
mod tests {
    use super::super::{Prepared, cpu_vm_cell_lookups};
    use super::*;
    use crate::program::{Instruction, Interpolation, Program};
    use std::sync::Arc;
    fn n(op: u32, a: u32, b: u32, c: u32, p: [f32; 4]) -> Instruction {
        Instruction { op, a, b, c, p }
    }
    #[test]
    fn cached_cells_match_corner_reference_for_masks_contexts_and_nested_collisions() {
        assert!(std::mem::size_of::<Cells>() <= 2048);
        let mut r = super::super::tests::one(28);
        r.interpolations = (0..33)
            .map(|i| Interpolation {
                cell: [4, 8],
                input: Program {
                    nodes: vec![
                        n(0, 0, 0, 0, [i as f32; 4]),
                        n(29, 0, 0, 0, [0.0; 4]),
                        n(4, 0, 1, 0, [0.0; 4]),
                        n(50, 0, 0, 0, [0.0; 4]),
                        n(4, 2, 3, 0, [0.0; 4]),
                    ],
                    roots: vec![4],
                },
            })
            .collect();
        // Fields 0 and 32 use the same slot, including while 32 is evaluating.
        r.interpolations[32].input = Program {
            nodes: vec![
                n(0, 0, 0, 0, [32.0; 4]),
                n(29, 0, 0, 0, [0.0; 4]),
                n(29, 1, 0, 0, [0.0; 4]),
                n(29, 2, 0, 0, [0.0; 4]),
                n(28, 1, 2, 3, [0.0; 4]),
                n(4, 0, 4, 0, [0.0; 4]),
            ],
            roots: vec![5],
        };
        let prepared = Prepared::new(Arc::new(r)).unwrap();
        let mut cached = prepared
            .evaluator(u64::MAX, 63, &[])
            .with_timings(Some(Arc::new(crate::timings::Timings::default())));
        let mut reference = prepared.evaluator(u64::MAX, 63, &[]);
        reference.eager = true;
        for field in [0, 32, 1, 0, 32, 0] {
            for point in [
                [0.0; 3],
                [1.25, 4.0, 1.25],
                [1.25, 0.0, 1.25],
                [0.0, 4.0, 1.25],
                [2.25, 2.0, 3.25],
                [-17.5, -63.0, 31.25],
                [-0.0; 3],
                [28_000_000.0, 5.0, -28_000_000.0],
            ] {
                let a = cached.interpolate::<true>(field, point, Context::default(), 0);
                let b = reference.interpolate::<false>(field, point, Context::default(), 0);
                assert_eq!(a.to_bits(), b.to_bits(), "field={field} point={point:?}");
            }
        }
        let before = cached.statistics.as_ref().unwrap().get(cpu_vm_cell_lookups);
        let mut negative_zero = Context::default();
        negative_zero.surface[0] = -0.0;
        let contextual = Context {
            surface: [0.0, 2.0, 3.0, 4.0],
            detail: [5.0, 6.0, 7.0, 8.0],
        };
        for context in [negative_zero, contextual, negative_zero, contextual] {
            let point = [-17.5, -63.0, 31.25];
            assert_eq!(
                cached.interpolate::<true>(0, point, context, 0).to_bits(),
                reference
                    .interpolate::<false>(0, point, context, 0)
                    .to_bits()
            );
        }
        assert_eq!(
            cached.statistics.as_ref().unwrap().get(cpu_vm_cell_lookups),
            before,
            "nondefault and negative-zero contexts must keep the full-key corner path"
        );
        assert!(cached.cells.is_some());
        assert!(reference.cells.is_none());
    }
    #[test]
    fn unused_cached_vertices_cannot_overflow_a_zero_weight_interpolation() {
        let mut r = super::super::tests::one(28);
        r.interpolations[0].input = Program {
            nodes: vec![
                n(0, 0, 0, 0, [f32::MAX; 4]),
                n(0, 0, 0, 0, [-f32::MAX; 4]),
                n(29, 1, 0, 0, [0.0; 4]),
                n(23, 2, 0, 1, [-f32::MAX, 4.0, 0.0, 0.0]),
            ],
            roots: vec![3],
        };
        let prepared = Prepared::new(Arc::new(r)).unwrap();
        let mut cached = prepared.evaluator(1, 63, &[]);
        let mut reference = prepared.evaluator(1, 63, &[]);
        reference.eager = true;
        // Populate positive and negative MAX corners. The full interpolation
        // may overflow, but an exact-Y query must not read the unused upper Y.
        let _ = cached.interpolate::<false>(0, [1.25, 4.0, 1.25], Context::default(), 0);
        for point in [[1.25, 0.0, 1.25], [0.0; 3], [0.0, 0.0, 1.25]] {
            let a = cached.interpolate::<false>(0, point, Context::default(), 0);
            let b = reference.interpolate::<false>(0, point, Context::default(), 0);
            assert_eq!(a.to_bits(), f32::MAX.to_bits());
            assert_eq!(a.to_bits(), b.to_bits());
        }
    }
}
