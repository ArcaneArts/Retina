//! Exact, naturally completed prefixes outside a finished region.
//! Unobserved prefixes remain holes; adjacent identical observed states may
//! share an interval, but no state is extrapolated across a missing prefix.
use super::{ColumnCell, ColumnRuns, ColumnState, Map, SavedState};
use super::super::ColumnTile;
use std::sync::Arc;

const BAND: usize = 64;
struct Span {
    start: usize,
    end: usize,
    state: u32,
}
pub(super) struct Exterior {
    origin: [i32; 2],
    width: usize,
    offsets: Box<[u32]>,
    spans: Box<[Span]>,
    states: Box<[SavedState]>,
    runs: Arc<Vec<u32>>,
}
fn index(origin: [i32; 2], width: usize, x: i32, z: i32) -> Option<usize> {
    let x = i64::from(x) - i64::from(origin[0]) + BAND as i64;
    let z = i64::from(z) - i64::from(origin[1]) + BAND as i64;
    let outer = width + 2 * BAND;
    if x < 0 || z < 0 || x >= outer as i64 || z >= outer as i64 { return None }
    let (x, z) = (x as usize, z as usize);
    if z < BAND { return Some(z * outer + x) }
    if z >= width + BAND { return Some(z * outer + x - width * width) }
    let row = BAND * outer + (z - BAND) * 2 * BAND;
    if x < BAND { Some(row + x) }
    else if x >= width + BAND { Some(row + x - width) }
    else { None }
}
impl Exterior {
    pub(super) fn capture(
        origin: [i32; 2], width: usize,
        columns: &Map<(usize, i32, i32), Arc<ColumnTile>>,
    ) -> Option<(Box<Self>, usize)> {
        let outer = width + 2 * BAND;
        let count = outer * outer - width * width;
        let mut cells = (0..count).map(|_| Vec::<(usize, u32)>::new()).collect::<Vec<_>>();
        let mut owners = Map::<usize, u32>::default();
        let mut states = Vec::new();
        let mut runs = Vec::new();
        let mut slots = 0;
        let mut intern = |cell: &ColumnCell| -> Option<Option<u32>> {
            let Some(value) = cell.get() else { return Some(None) };
            let value = value.as_ref().ok()?;
            let pointer = Arc::as_ptr(value) as usize;
            if let Some(&id) = owners.get(&pointer) { return Some(Some(id)) }
            let id = u32::try_from(states.len()).ok()?;
            let start = runs.len();
            runs.extend_from_slice(value.runs.slice());
            states.push(SavedState { range: start..runs.len(), heights: value.heights });
            owners.insert(pointer, id);
            Some(Some(id))
        };
        // All original cell owners stay borrowed until interning is complete.
        for (&(before, cx, cz), tile) in columns {
            for (slot, cell) in tile.0.iter().enumerate() {
                let x = cx * 16 + (slot % 16) as i32;
                let z = cz * 16 + (slot / 16) as i32;
                let Some(column) = index(origin, width, x, z) else { continue };
                if let Some(id) = intern(cell)? {
                    cells[column].push((before, id));
                    slots += 1;
                }
            }
        }
        let mut offsets = Vec::with_capacity(count + 1);
        let mut spans: Vec<Span> = Vec::new();
        for mut cell in cells {
            offsets.push(u32::try_from(spans.len()).ok()?);
            cell.sort_unstable_by_key(|&(before, _)| before);
            let start = spans.len();
            for (before, state) in cell {
                let end = before.checked_add(1)?;
                if spans.len() > start {
                    let last = spans.last_mut().unwrap();
                    if last.end == before && last.state == state {
                        last.end = end;
                        continue;
                    }
                }
                spans.push(Span { start: before, end, state });
            }
        }
        offsets.push(u32::try_from(spans.len()).ok()?);
        Some((Box::new(Self { origin, width, offsets: offsets.into_boxed_slice(),
            spans: spans.into_boxed_slice(), states: states.into_boxed_slice(),
            runs: Arc::new(runs) }), slots))
    }
    pub(super) fn column(&self, before: usize, x: i32, z: i32) -> Option<Arc<ColumnState>> {
        let column = index(self.origin, self.width, x, z)?;
        let spans = &self.spans[self.offsets[column] as usize..self.offsets[column + 1] as usize];
        let span = spans.get(spans.partition_point(|s| s.end <= before))?;
        if before < span.start { return None }
        let state = &self.states[span.state as usize];
        Some(Arc::new(ColumnState { runs: ColumnRuns::Cached {
            runs: self.runs.clone(), range: state.range.clone(),
        }, heights: state.heights }))
    }
    pub(super) fn retained_bytes(&self) -> usize {
        std::mem::size_of::<Self>() + self.offsets.len() * 4
            + self.spans.len() * std::mem::size_of::<Span>()
            + self.states.len() * std::mem::size_of::<SavedState>()
            + std::mem::size_of::<Vec<u32>>() + 2 * std::mem::size_of::<usize>()
            + self.runs.capacity() * 4
    }
    pub(super) fn counts(&self) -> (usize, usize) { (self.spans.len(), self.states.len()) }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exterior_ring_is_bijective_and_handles_signed_corners() {
        for (origin, width) in [([-512, -512], 512), ([0, 0], 16), ([512, 512], 1)] {
            let mut next = 0;
            for z in -64..width as i32 + 64 {
                for x in -64..width as i32 + 64 {
                    let actual = index(origin, width, origin[0] + x, origin[1] + z);
                    if (0..width as i32).contains(&x) && (0..width as i32).contains(&z) {
                        assert_eq!(actual, None);
                    } else {
                        assert_eq!(actual, Some(next)); next += 1;
                    }
                }
            }
            assert_eq!(next, (width + 128).pow(2) - width.pow(2));
            assert!(index(origin, width, origin[0] - 65, origin[1]).is_none());
            assert!(index(origin, width, origin[0] + width as i32 + 64, origin[1]).is_none());
        }
        assert!(index([i32::MIN; 2], 512, i32::MAX, i32::MAX).is_none());
        assert!(index([i32::MAX; 2], 512, i32::MIN, i32::MIN).is_none());
    }
    #[test]
    fn exact_prefix_ranges_preserve_holes_and_release_original_owners() {
        let (profile, _, _) = crate::decoration::replay_tests::fixture(42, 0);
        let a = Arc::new(ColumnState::new(vec![0; 16], &profile, 0));
        let b = Arc::new(ColumnState::new(vec![1; 16], &profile, 0));
        let weak = Arc::downgrade(&a);
        let mut columns = Map::default();
        // x=-1,z=0, to the left of the target. The gap at prefix3 is real.
        for (before, state) in [(0, a.clone()), (1, a.clone()), (2, b.clone()), (4, b.clone()), (5, b)] {
            let tile = Arc::new(ColumnTile::default());
            tile.0[15].set(Ok(state)).ok().unwrap();
            columns.insert((before, -1, 0), tile);
        }
        let (saved, slots) = Exterior::capture([0; 2], 16, &columns).unwrap();
        assert_eq!(slots, 5);
        assert_eq!(saved.counts(), (3, 2));
        for (before, material) in [(0, Some(0)), (1, Some(0)), (2, Some(1)), (3, None), (4, Some(1)), (5, Some(1)), (6, None)] {
            assert_eq!(saved.column(before, -1, 0).map(|s| s.material(0)), material);
        }
        assert!(saved.column(0, -2, 0).is_none());
        assert!(saved.column(0, 0, 0).is_none());
        assert!(saved.column(usize::MAX, -1, 0).is_none());
        drop(columns); drop(a);
        assert!(weak.upgrade().is_none());
        let column = saved.column(4, -1, 0).unwrap();
        drop(saved);
        assert_eq!(column.material(0), 1);
    }
    #[test]
    fn incomplete_cells_are_misses_and_failed_cells_reject_export() {
        let tile = Arc::new(ColumnTile::default());
        let mut columns = Map::default();
        columns.insert((3, -1, 0), tile.clone());
        let (empty, slots) = Exterior::capture([0; 2], 16, &columns).unwrap();
        assert_eq!(slots, 0);
        assert!(empty.column(3, -1, 0).is_none());
        tile.0[15].set(Err(Box::new(super::super::super::ColumnFailure("failed".into())))).ok().unwrap();
        assert!(Exterior::capture([0; 2], 16, &columns).is_none());
    }
}
